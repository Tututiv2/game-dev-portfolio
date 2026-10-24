using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace AudioCapture.Recording;

/// <summary>
/// Captures microphone and/or system audio (WASAPI loopback), down-mixes both to mono at the
/// target rate and writes a 32-bit float scratch WAV. <see cref="AudioPost"/> turns that into
/// the final 16-bit file.
///
/// Float scratch rather than 16-bit: normalising after quantising bakes the quantisation noise
/// into the gain, so the scratch stays float until the one and only conversion at the end.
/// </summary>
public sealed class Recorder : IDisposable
{
    private sealed class Src
    {
        public IWaveIn Capture = null!;
        public BufferedWaveProvider Buffer = null!;
        public WaveFormat Format = null!;
        public volatile float Peak;
    }

    private readonly List<Src> sources = new();
    private readonly MixingSampleProvider mixer;
    private readonly int rate;
    private readonly object writeLock = new();

    private readonly int outCh;

    private Src? mic, sys;
    private WasapiOut? keepAlive;
    private WaveFileWriter? writer;
    private Thread? pump;
    private volatile bool running;
    private long framesWritten;

    public string ScratchPath { get; }
    public int SampleRate => rate;
    public int Channels => outCh;
    public float MicPeak => mic?.Peak ?? 0f;
    public float SysPeak => sys?.Peak ?? 0f;
    public bool HasMic => mic != null;
    public bool HasSystem => sys != null;
    public TimeSpan Duration => TimeSpan.FromSeconds((double)Interlocked.Read(ref framesWritten) / rate);

    public Recorder(int sampleRate, int channels, MMDevice? micDevice, MMDevice? systemDevice, float micGain, float sysGain)
    {
        if (micDevice == null && systemDevice == null)
            throw new InvalidOperationException("No capture source selected.");
        if (channels is not (1 or 2))
            throw new ArgumentOutOfRangeException(nameof(channels), "Only mono and stereo are supported.");

        rate = sampleRate;
        outCh = channels;
        ScratchPath = Path.Combine(Path.GetTempPath(), "audiocapture_" + Guid.NewGuid().ToString("N") + ".wav");

        mixer = new MixingSampleProvider(WaveFormat.CreateIeeeFloatWaveFormat(rate, outCh)) { ReadFully = true };

        if (micDevice != null)
            mic = AddSource(new WasapiCapture(micDevice, true, 50), micGain);

        if (systemDevice != null)
        {
            sys = AddSource(new WasapiLoopbackCapture(systemDevice), sysGain);
            StartKeepAlive(systemDevice);
        }

        writer = new WaveFileWriter(ScratchPath, WaveFormat.CreateIeeeFloatWaveFormat(rate, outCh));
    }

    private Src AddSource(IWaveIn capture, float gain)
    {
        // WASAPI hands back WAVE_FORMAT_EXTENSIBLE; flatten it so the peak meter can read the bytes.
        WaveFormat fmt = capture.WaveFormat;
        if (fmt is WaveFormatExtensible ext)
        {
            try { fmt = ext.ToStandardWaveFormat(); } catch { /* keep the extensible form */ }
        }

        var buffer = new BufferedWaveProvider(fmt)
        {
            BufferDuration = TimeSpan.FromSeconds(10),
            DiscardOnBufferOverflow = true,
            ReadFully = true   // a source returning 0 gets dropped by MixingSampleProvider
        };

        var src = new Src { Capture = capture, Buffer = buffer, Format = fmt };

        capture.DataAvailable += (_, e) =>
        {
            if (e.BytesRecorded <= 0) return;
            buffer.AddSamples(e.Buffer, 0, e.BytesRecorded);
            float p = PeakOf(e.Buffer, e.BytesRecorded, fmt);
            src.Peak = p > src.Peak ? p : src.Peak * 0.65f + p * 0.35f;
        };

        // Fold first, then resample: folding 8 channels down to 2 before resampling is a quarter of
        // the resampler work, and the fold is rate-independent either way.
        ISampleProvider chain = buffer.ToSampleProvider();
        if (fmt.Channels != outCh) chain = new ChannelFold(chain, outCh);
        if (fmt.SampleRate != rate) chain = new WdlResamplingSampleProvider(chain, rate);
        if (Math.Abs(gain - 1f) > 0.001f) chain = new VolumeSampleProvider(chain) { Volume = gain };

        mixer.AddMixerInput(chain);
        sources.Add(src);
        return src;
    }

    /// <summary>
    /// WASAPI loopback can stop delivering packets entirely when nothing is playing on the endpoint,
    /// which would silently truncate the recording. Playing digital silence keeps the stream running.
    ///
    /// The endpoint is re-resolved rather than reused: MMDevice caches one AudioClient, so handing the
    /// same instance to both the loopback capture and this output would have them fight over it.
    /// </summary>
    private void StartKeepAlive(MMDevice device)
    {
        try
        {
            var own = new MMDeviceEnumerator().GetDevice(device.ID);
            // Must be the endpoint's own mix format: a shared-mode client rejects a hand-built
            // float format outright when the device is WAVE_FORMAT_EXTENSIBLE (e.g. any 7.1 device).
            keepAlive = new WasapiOut(own, AudioClientShareMode.Shared, false, 100);
            keepAlive.Init(new SilenceProvider(own.AudioClient.MixFormat));
            keepAlive.Play();
        }
        catch { keepAlive = null; }
    }

    public void Start()
    {
        running = true;
        foreach (var s in sources) s.Capture.StartRecording();

        pump = new Thread(Pump)
        {
            IsBackground = true,
            Priority = ThreadPriority.AboveNormal,
            Name = "audiocapture-pump"
        };
        pump.Start();
    }

    /// <summary>
    /// Pulls only as much as the slowest live source can actually supply. Pacing off the wall clock
    /// instead would pad silence whenever a device fell behind and stretch the file.
    /// </summary>
    private void Pump()
    {
        var buf = new float[rate * outCh];   // one second, ample
        while (running)
        {
            if (!DrainOnce(buf)) Thread.Sleep(4);
        }
    }

    private bool DrainOnce(float[] buf)
    {
        // ~2 ms of slack so the resampler is never asked for more than its source can supply;
        // BufferedWaveProvider.ReadFully would quietly pad the shortfall with silence.
        int margin = Math.Max(8, rate / 500);

        int avail = int.MaxValue;
        foreach (var s in sources)
        {
            long srcFrames = s.Buffer.BufferedBytes / s.Buffer.WaveFormat.BlockAlign;
            int atTarget = (int)(srcFrames * (long)rate / s.Format.SampleRate) - margin;
            if (atTarget < avail) avail = atTarget;
        }
        if (avail == int.MaxValue || avail <= 0) return false;

        // avail is in frames; ISampleProvider.Read counts interleaved samples.
        int wantFrames = Math.Min(avail, buf.Length / outCh);
        int read = mixer.Read(buf, 0, wantFrames * outCh);
        if (read <= 0) return false;

        lock (writeLock)
        {
            writer?.WriteSamples(buf, 0, read);
        }
        Interlocked.Add(ref framesWritten, read / outCh);
        return true;
    }

    public void Stop()
    {
        if (!running && writer == null) return;
        running = false;

        foreach (var s in sources)
        {
            try { s.Capture.StopRecording(); } catch { }
        }
        pump?.Join(1500);
        pump = null;

        // Whatever the capture threads queued after the last pump pass.
        var tail = new float[rate * outCh];
        for (int i = 0; i < 200 && DrainOnce(tail); i++) { }

        lock (writeLock)
        {
            try { writer?.Flush(); } catch { }
            writer?.Dispose();
            writer = null;
        }

        try { keepAlive?.Stop(); } catch { }
        foreach (var s in sources) s.Peak = 0f;
    }

    public void Dispose()
    {
        Stop();
        foreach (var s in sources)
        {
            try { s.Capture.Dispose(); } catch { }
        }
        sources.Clear();
        try { keepAlive?.Dispose(); } catch { }
        keepAlive = null;
    }

    public void DeleteScratch()
    {
        try { if (File.Exists(ScratchPath)) File.Delete(ScratchPath); } catch { }
    }

    internal static float PeakOf(byte[] b, int count, WaveFormat fmt)
    {
        float peak = 0f;
        bool floatFmt = fmt.Encoding == WaveFormatEncoding.IeeeFloat && fmt.BitsPerSample == 32;

        if (floatFmt)
        {
            for (int i = 0; i + 3 < count; i += 4)
            {
                float v = Math.Abs(BitConverter.ToSingle(b, i));
                if (v > peak) peak = v;
            }
        }
        else if (fmt.BitsPerSample == 16)
        {
            for (int i = 0; i + 1 < count; i += 2)
            {
                float v = Math.Abs(BitConverter.ToInt16(b, i) / 32768f);
                if (v > peak) peak = v;
            }
        }
        else if (fmt.BitsPerSample == 24)
        {
            for (int i = 0; i + 2 < count; i += 3)
            {
                int s = (b[i + 2] << 16) | (b[i + 1] << 8) | b[i];
                if ((s & 0x800000) != 0) s |= unchecked((int)0xFF000000);
                float v = Math.Abs(s / 8388608f);
                if (v > peak) peak = v;
            }
        }
        else if (fmt.BitsPerSample == 32)
        {
            for (int i = 0; i + 3 < count; i += 4)
            {
                float v = Math.Abs(BitConverter.ToInt32(b, i) / 2147483648f);
                if (v > peak) peak = v;
            }
        }
        return peak > 1f ? 1f : peak;
    }
}
