using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace SoundBoard
{
    public enum RetriggerMode { Restart, Overlap, Toggle }

    /// <summary>
    /// One mixer per output device. Short sounds are decoded to float once and replayed
    /// from RAM so a keypress costs nothing but an AddMixerInput; long files stream.
    /// </summary>
    public sealed class AudioEngine : IDisposable
    {
        public static readonly WaveFormat Fmt = WaveFormat.CreateIeeeFloatWaveFormat(44100, 2);
        const double CacheMaxSeconds = 30;

        sealed class Rig
        {
            public IWavePlayer Player;
            public MixingSampleProvider Mixer;
            public VolumeSampleProvider Master;
            public string Label;
        }

        sealed class Voice
        {
            public string Id;
            public ISampleProvider Root;    // exactly what went into the mixer
            public Rig Rig;
            public IDisposable Resource;    // stream reader, when not cached
        }

        readonly object _lock = new object();
        readonly Dictionary<string, float[]> _cache = new Dictionary<string, float[]>(StringComparer.OrdinalIgnoreCase);
        readonly HashSet<string> _tooLong = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        readonly List<Voice> _voices = new List<Voice>();

        Rig _a, _b;
        float _master = 0.8f;

        public string PrimaryStatus { get; private set; } = "not started";
        public string SecondaryStatus { get; private set; } = "off";

        public float MasterVolume
        {
            get { return _master; }
            set
            {
                _master = Math.Max(0f, Math.Min(1.5f, value));
                lock (_lock)
                {
                    if (_a != null) _a.Master.Volume = _master;
                    if (_b != null) _b.Master.Volume = _master;
                }
            }
        }

        // ---------------------------------------------------------------- devices

        public sealed class DeviceInfo
        {
            public string Id { get; set; }
            public string Name { get; set; }
        }

        public static List<DeviceInfo> Devices()
        {
            var list = new List<DeviceInfo> { new DeviceInfo { Id = "", Name = "System default" } };
            try
            {
                using (var en = new MMDeviceEnumerator())
                {
                    foreach (var d in en.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
                    {
                        list.Add(new DeviceInfo { Id = d.ID, Name = d.FriendlyName });
                        d.Dispose();
                    }
                }
            }
            catch { }
            return list;
        }

        public void SetOutputs(string primaryId, string secondaryId)
        {
            lock (_lock)
            {
                StopAllLocked();
                TearDown(ref _a);
                TearDown(ref _b);

                string err;
                _a = BuildRig(primaryId, out err);
                PrimaryStatus = _a != null ? _a.Label : ("failed: " + err);

                // secondaryId == null means "no second output".
                if (secondaryId != null)
                {
                    _b = BuildRig(secondaryId, out err);
                    SecondaryStatus = _b != null ? _b.Label : ("failed: " + err);
                }
                else
                {
                    SecondaryStatus = "off";
                }
            }
        }

        Rig BuildRig(string deviceId, out string error)
        {
            error = null;
            MMDevice dev = null;
            string name = "System default";
            try
            {
                // A fresh enumerator/device per rig: an MMDevice caches one AudioClient,
                // so two players must never share one.
                var en = new MMDeviceEnumerator();
                if (string.IsNullOrEmpty(deviceId))
                    dev = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                else
                    dev = en.GetDevice(deviceId);
                name = dev.FriendlyName;
            }
            catch (Exception ex) { error = ex.Message; dev = null; }

            var mixer = new MixingSampleProvider(Fmt) { ReadFully = true };
            mixer.MixerInputEnded += OnInputEnded;
            var master = new VolumeSampleProvider(mixer) { Volume = _master };

            IWavePlayer player = null;
            try
            {
                if (dev != null)
                {
                    player = new WasapiOut(dev, AudioClientShareMode.Shared, true, 80);
                    player.Init(master);
                    player.Play();
                }
            }
            catch (Exception ex)
            {
                error = ex.Message;
                try { if (player != null) player.Dispose(); } catch { }
                player = null;
            }

            if (player == null)
            {
                // WASAPI can refuse an endpoint (exclusive-mode hog, odd driver). WaveOut
                // always works, at the cost of a little latency.
                try
                {
                    player = new WaveOutEvent { DesiredLatency = 120, NumberOfBuffers = 3 };
                    player.Init(master);
                    player.Play();
                    name += " (WaveOut)";
                }
                catch (Exception ex)
                {
                    error = ex.Message;
                    try { if (player != null) player.Dispose(); } catch { }
                    return null;
                }
            }

            return new Rig { Player = player, Mixer = mixer, Master = master, Label = name };
        }

        void TearDown(ref Rig r)
        {
            if (r == null) return;
            try { r.Mixer.MixerInputEnded -= OnInputEnded; } catch { }
            try { r.Player.Stop(); } catch { }
            try { r.Player.Dispose(); } catch { }
            r = null;
        }

        // ---------------------------------------------------------------- loading

        /// <summary>Decode to the mixer format so playback is a straight memcpy.</summary>
        static ISampleProvider ToFormat(ISampleProvider sp)
        {
            if (sp.WaveFormat.Channels == 1) sp = new MonoToStereoSampleProvider(sp);
            else if (sp.WaveFormat.Channels > 2)
                sp = new MultiplexingSampleProvider(new[] { sp }, 2);
            if (sp.WaveFormat.SampleRate != Fmt.SampleRate)
                sp = new WdlResamplingSampleProvider(sp, Fmt.SampleRate);
            return sp;
        }

        float[] GetCached(string path)
        {
            lock (_lock)
            {
                float[] hit;
                if (_cache.TryGetValue(path, out hit)) return hit;
                if (_tooLong.Contains(path)) return null;
            }

            float[] data = null;
            bool tooLong = false;
            try
            {
                using (var reader = new AudioFileReader(path))
                {
                    if (reader.TotalTime.TotalSeconds > CacheMaxSeconds)
                    {
                        tooLong = true;
                    }
                    else
                    {
                        var sp = ToFormat(reader);
                        var buf = new float[Fmt.SampleRate * 2];   // 1 s
                        int len = 0, n;
                        while ((n = sp.Read(buf, len, buf.Length - len)) > 0)
                        {
                            len += n;
                            if (len == buf.Length) Array.Resize(ref buf, buf.Length * 2);
                        }
                        Array.Resize(ref buf, len);
                        data = buf;
                    }
                }
            }
            catch { return null; }

            lock (_lock)
            {
                if (tooLong) { _tooLong.Add(path); return null; }
                _cache[path] = data;
            }
            return data;
        }

        public void Preload(IEnumerable<string> paths)
        {
            var copy = new List<string>(paths);
            Task.Run(() =>
            {
                foreach (var p in copy)
                {
                    if (!string.IsNullOrEmpty(p) && File.Exists(p)) GetCached(p);
                }
            });
        }

        public void ForgetCache(string path)
        {
            lock (_lock) { _cache.Remove(path); _tooLong.Remove(path); }
        }

        // ---------------------------------------------------------------- playback

        /// <summary>
        /// Plays <paramref name="path"/>. <paramref name="id"/> groups voices so a
        /// second press of the same binding can restart or toggle it.
        /// </summary>
        public string Play(string id, string path, float volume, RetriggerMode mode, bool primaryOnly)
        {
            if (string.IsNullOrEmpty(path) || !File.Exists(path)) return "file missing";

            if (mode == RetriggerMode.Toggle && IsPlaying(id)) { Stop(id); return null; }
            if (mode == RetriggerMode.Restart) Stop(id);

            var cached = GetCached(path);

            lock (_lock)
            {
                var rigs = new List<Rig>();
                if (_a != null) rigs.Add(_a);
                if (_b != null && !primaryOnly) rigs.Add(_b);
                if (rigs.Count == 0) return "no output device";

                foreach (var rig in rigs)
                {
                    ISampleProvider src;
                    IDisposable res = null;
                    try
                    {
                        if (cached != null)
                        {
                            src = new CachedProvider(cached);
                        }
                        else
                        {
                            var reader = new AudioFileReader(path);
                            res = reader;
                            src = ToFormat(reader);
                        }
                        var vol = new VolumeSampleProvider(src) { Volume = Math.Max(0f, Math.Min(2f, volume)) };
                        rig.Mixer.AddMixerInput((ISampleProvider)vol);
                        _voices.Add(new Voice { Id = id, Root = vol, Rig = rig, Resource = res });
                    }
                    catch (Exception ex)
                    {
                        if (res != null) { try { res.Dispose(); } catch { } }
                        return ex.Message;
                    }
                }
            }
            return null;
        }

        public int VoiceCount { get { lock (_lock) { return _voices.Count; } } }

        public bool IsPlaying(string id)
        {
            lock (_lock)
            {
                foreach (var v in _voices) if (v.Id == id) return true;
                return false;
            }
        }

        public void Stop(string id)
        {
            var doomed = new List<Voice>();
            lock (_lock)
            {
                for (int i = _voices.Count - 1; i >= 0; i--)
                    if (_voices[i].Id == id) { doomed.Add(_voices[i]); _voices.RemoveAt(i); }
            }
            foreach (var v in doomed) Kill(v);
        }

        public void StopAll()
        {
            List<Voice> doomed;
            lock (_lock) { doomed = new List<Voice>(_voices); _voices.Clear(); }
            foreach (var v in doomed) Kill(v);
        }

        void StopAllLocked()
        {
            foreach (var v in _voices)
            {
                try { v.Rig.Mixer.RemoveMixerInput(v.Root); } catch { }
                if (v.Resource != null) { try { v.Resource.Dispose(); } catch { } }
            }
            _voices.Clear();
        }

        void Kill(Voice v)
        {
            // RemoveMixerInput blocks on the mixer's own lock, which the audio thread
            // holds while raising MixerInputEnded — so that handler must not take _lock.
            try { v.Rig.Mixer.RemoveMixerInput(v.Root); } catch { }
            if (v.Resource != null) { try { v.Resource.Dispose(); } catch { } }
        }

        void OnInputEnded(object sender, SampleProviderEventArgs e)
        {
            var ended = e.SampleProvider;
            Task.Run(() =>
            {
                Voice found = null;
                lock (_lock)
                {
                    for (int i = 0; i < _voices.Count; i++)
                        if (ReferenceEquals(_voices[i].Root, ended)) { found = _voices[i]; _voices.RemoveAt(i); break; }
                }
                if (found != null && found.Resource != null)
                { try { found.Resource.Dispose(); } catch { } }
            });
        }

        public void Dispose()
        {
            lock (_lock)
            {
                StopAllLocked();
                TearDown(ref _a);
                TearDown(ref _b);
            }
        }

        /// <summary>Replays a decoded buffer with no allocation and no file I/O.</summary>
        sealed class CachedProvider : ISampleProvider
        {
            readonly float[] _data;
            int _pos;
            public CachedProvider(float[] data) { _data = data; }
            public WaveFormat WaveFormat { get { return Fmt; } }
            public int Read(float[] buffer, int offset, int count)
            {
                int avail = _data.Length - _pos;
                int n = Math.Min(avail, count);
                if (n <= 0) return 0;
                Array.Copy(_data, _pos, buffer, offset, n);
                _pos += n;
                return n;
            }
        }
    }
}
