using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace Probe;

/// <summary>
/// The per-app boost, built around the measurement in Program.cs: process loopback taps the
/// stream AFTER the app's own volume and mute, so the app cannot be silenced and replayed.
///
/// What works instead — turn the app almost all the way down and make the difference up on a
/// captured copy:
///
///   app session volume := Leak (3%)          what the speakers hear directly
///   our copy           := captured * dial/Leak   what the speakers hear from us
///
/// The direct path is then 30 dB under our copy, which is inaudible, and it is the *same*
/// signal so the worst it can do is a faint comb filter. Attenuating by 30 dB costs nothing
/// because the pipeline is float32 with ~144 dB of range — we are spending headroom we have.
///
/// The alternative was per-app endpoint routing onto a silent device, which needs either a
/// virtual cable installed or a spare output nobody is listening to. This needs neither.
/// </summary>
internal static class Boost
{
    /// <summary>How much of the app's own output still reaches the speakers directly.</summary>
    private const float Leak = 0.03f;

    public static void Run(MMDevice device, AudioSessionControl session, int pid, float dial,
        int? seconds = null)
    {
        var mix = device.AudioClient.MixFormat;
        var rate = mix.SampleRate;
        var channels = Math.Min(mix.Channels, 2); // process loopback is happy with stereo

        Console.WriteLine($"Boost {dial:F2}x  ({rate} Hz, {channels}ch)");

        // Waiting on Enter is useless when stdin is not a terminal — it returns immediately and
        // the boost stops before anyone hears it. Fall back to a fixed run.
        var runFor = seconds ?? (Console.IsInputRedirected ? 20 : (int?)null);
        Console.WriteLine(runFor is null ? "  press Enter to stop" : $"  running for {runFor}s");
        Console.WriteLine();

        var format = ProcessLoopback.BuildFloatFormat(rate, channels);
        var client = ProcessLoopback.Activate((uint)pid);
        var capture = ProcessLoopback.InitCapture(client, format, 10_000_000, verbose: false);

        var waveFormat = WaveFormat.CreateIeeeFloatWaveFormat(rate, channels);
        var buffer = new BufferedWaveProvider(waveFormat)
        {
            // Small, and discarded rather than grown: if we ever fall behind we want to drop
            // audio and stay in sync, not build a growing delay behind the picture.
            BufferDuration = TimeSpan.FromMilliseconds(200),
            DiscardOnBufferOverflow = true,
        };

        var output = new WasapiOut(device, AudioClientShareMode.Shared, true, 60);
        output.Init(buffer);

        client.Start();

        // Calibrate before touching the volume.
        //
        // The capture does not hand back the app's samples at face value — measured against a
        // steady tone it came back at a constant 0.21187 of the generated amplitude, the same
        // at 48 and 96 kHz and the same at every amplitude. Ignoring that made a 4x boost come
        // out 0.86x, i.e. quieter than not boosting at all.
        //
        // The session's peak meter reports the app's level BEFORE its volume scalar, so the
        // ratio of meter to captured peak is exactly the makeup gain per unit of dial, and it
        // is measurable here rather than hardcoded — nothing about 0.21187 is promised to be
        // the same on another machine.
        var startVolume = session.SimpleAudioVolume.Volume;
        DrainPeak(capture, channels, 250); // settle
        var capturedPeak = DrainPeak(capture, channels, 400);
        var meterPeak = session.AudioMeterInformation.MasterPeakValue;

        float makeup;
        if (meterPeak > 0.001 && capturedPeak > 1e-7)
        {
            makeup = (float)(dial * startVolume * meterPeak / (Leak * capturedPeak));
            Console.WriteLine($"  calibrated: meter {meterPeak:F5} vs capture {capturedPeak:F6} " +
                              $"at {startVolume:P0}  ->  capture gain {capturedPeak / (meterPeak * startVolume):F5}");
        }
        else
        {
            // Nothing audible to calibrate against. Uncorrected, but a silent app has nothing
            // to boost yet either, and the live trim below fixes it once sound starts.
            makeup = dial / Leak;
            Console.WriteLine("  NOT calibrated (app is silent) — using uncorrected makeup");
        }

        Console.WriteLine($"  app dropped to {Leak:P0}, captured copy multiplied by {makeup:F1}x");

        session.SimpleAudioVolume.Volume = Leak;
        output.Play();

        // Read the volume back rather than trusting the write. The whole scheme rests on the
        // app actually going quiet, and a session that refuses the write would look, from the
        // speakers, exactly like a boost that is not working.
        Console.WriteLine($"  session volume readback = {session.SimpleAudioVolume.Volume:P1}" +
                          $"   muted={session.SimpleAudioVolume.Mute}");
        Console.WriteLine($"  rendering to '{device.FriendlyName}', state={output.PlaybackState}");

        // A full meter on our own buffer only proves we GENERATED a loud signal. Whether anyone
        // can hear it is a different question, and on a machine with SteelSeries Sonar in the
        // path the two can disagree: Sonar does its own per-app routing, and an unknown process
        // can land somewhere quiet. So watch every endpoint's own meter and see where it dies.
        var meterEnum = new MMDeviceEnumerator();
        var endpoints = meterEnum.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active).ToList();

        Thread.Sleep(600);
        foreach (var ep in endpoints)
        {
            SessionCollection eps;
            try { eps = ep.AudioSessionManager.Sessions; }
            catch { continue; }

            for (var i = 0; i < eps.Count; i++)
            {
                if ((int)eps[i].GetProcessID != Environment.ProcessId) continue;
                Console.WriteLine($"  our own session on '{Short(ep.FriendlyName)}': " +
                                  $"vol={eps[i].SimpleAudioVolume.Volume:P0} " +
                                  $"muted={eps[i].SimpleAudioVolume.Mute} state={eps[i].State}");
            }
        }

        Console.WriteLine();

        var stats = new Stats();
        var stop = new ManualResetEventSlim(false);
        var pump = new Thread(() => Pump(capture, buffer, channels, makeup, stop, stats))
        {
            IsBackground = true,
            Priority = ThreadPriority.AboveNormal, // audio thread; do not let it get starved
        };
        pump.Start();

        if (runFor is null)
        {
            Console.ReadLine();
        }
        else
        {
            for (var s = runFor.Value; s > 0; s--)
            {
                Thread.Sleep(1000);
                var (inPeak, outPeak, gainRide) = stats.TakeSnapshot();
                Console.WriteLine(
                    $"  in {inPeak:F5}  out {outPeak:F5}  lim {gainRide:F3}  | {Meters(endpoints)}");
            }
        }

        stop.Set();
        pump.Join(1000);
        output.Stop();
        output.Dispose();
        client.Stop();
        Marshal.FreeHGlobal(format);
        Marshal.ReleaseComObject(capture);
        Marshal.ReleaseComObject(client);
    }

    /// <summary>
    /// Measures the endpoint meters with the boost off, then on.
    ///
    /// "It sounds quieter when it is on" needs to become a number before it can be fixed, and
    /// the endpoint's own meter is the closest thing to what the speakers actually get — our
    /// internal meter only proves we generated a loud signal, not that anything plays it.
    /// </summary>
    public static void AB(MMDevice device, AudioSessionControl session, int pid, float dial)
    {
        var meterEnum = new MMDeviceEnumerator();
        var endpoints = meterEnum.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active).ToList();

        // MasterPeakValue turned out to be useless here — it read ~0.99 on Sonar-Gaming and a
        // pinned 1.000 on the speakers whether the boost was on or off, because Sonar applies a
        // large gain of its own and the meter saturates. Loopback-capturing the endpoint and
        // taking RMS measures the actual waveform instead, and RMS tracks loudness.
        var targets = new List<MMDevice> { device };
        foreach (var ep in endpoints)
        {
            var real = !ep.FriendlyName.Contains("SteelSeries", StringComparison.OrdinalIgnoreCase);
            if (real && targets.All(t => t.ID != ep.ID)) targets.Add(ep);
        }

        Console.WriteLine("=== BASELINE: boost off, app at its own volume ===");
        foreach (var t in targets)
        {
            Console.WriteLine($"  {Short(t.FriendlyName),-34} rms {LoopbackRms(t, 2500):F5}");
        }

        Console.WriteLine();
        Console.WriteLine($"=== BOOSTED: {dial:F1}x ===");

        var runner = new Thread(() => RunEngine(device, session, pid, dial, 8)) { IsBackground = true };
        runner.Start();
        Thread.Sleep(1500); // let the pre-roll settle before measuring

        var boosted = targets.Select(t => (t, rms: LoopbackRms(t, 2000))).ToList();
        runner.Join();

        Console.WriteLine();
        foreach (var (t, rms) in boosted)
        {
            Console.WriteLine($"  {Short(t.FriendlyName),-34} rms {rms:F5}  (boosted)");
        }
    }

    /// <summary>
    /// Sweeps the app's session volume down and measures what process loopback hands back.
    ///
    /// The whole attenuate-and-over-boost design assumes this is linear: drop the app to 3% and
    /// a 33x makeup puts it back exactly. Measured against a steady tone the boost came out
    /// 4.7x quieter than that predicts, so either the scalar is not linear or it bottoms out.
    /// Whichever it is, this finds the lowest leak that still behaves.
    ///
    /// Needs a constant source — run tone.exe, not music, or the song's own dynamics swamp it.
    /// </summary>
    public static void Curve(MMDevice device, AudioSessionControl session, int pid)
    {
        var mix = device.AudioClient.MixFormat;
        var rate = mix.SampleRate;
        var channels = Math.Min(mix.Channels, 2);

        var format = ProcessLoopback.BuildFloatFormat(rate, channels);
        var client = ProcessLoopback.Activate((uint)pid);
        var capture = ProcessLoopback.InitCapture(client, format, 10_000_000, verbose: false);
        client.Start();

        // Suspicion: the loopback tap sits after the ENDPOINT volume as well as the session
        // volume. If so the endpoint gain hits our copy twice — once on the way in, once on the
        // way back out — and that is a loss the makeup gain never accounts for.
        var endpoint = device.AudioEndpointVolume;
        var linear = Math.Pow(10, endpoint.MasterVolumeLevel / 20.0);
        Console.WriteLine();
        Console.WriteLine($"  endpoint volume: {endpoint.MasterVolumeLevelScalar:P1} scalar, " +
                          $"{endpoint.MasterVolumeLevel:F1} dB, {linear:F4} linear, muted={endpoint.Mute}");
        Console.WriteLine();
        Console.WriteLine("  volume   captured peak   expected   linear?");

        double reference = 0;
        foreach (var volume in new[] { 1f, 0.5f, 0.25f, 0.1f, 0.05f, 0.03f, 0.01f })
        {
            session.SimpleAudioVolume.Volume = volume;
            DrainPeak(capture, channels, 400); // discard: the change is not instant
            var peak = DrainPeak(capture, channels, 700);

            // If this meter reports the level BEFORE whatever attenuates the capture, then the
            // ratio between the two is the missing constant — measurable live, per app, with no
            // calibration tone and nothing hardcoded.
            var meter = session.AudioMeterInformation.MasterPeakValue;
            Console.WriteLine($"           session meter {meter:F6}  " +
                              $"capture/meter {(meter > 0 ? peak / meter : 0):F5}");

            if (volume >= 1f) reference = peak;
            var expected = reference * volume;
            var ratio = expected > 0 ? peak / expected : 0;

            Console.WriteLine($"  {volume,6:P0}   {peak,13:F6}   {expected,8:F6}   {ratio,6:F3}" +
                              (ratio is > 0.9 and < 1.1 ? "" : "   <-- OFF"));
        }

        session.SimpleAudioVolume.Volume = 1f;
        client.Stop();
        Marshal.FreeHGlobal(format);
        Marshal.ReleaseComObject(capture);
        Marshal.ReleaseComObject(client);
    }

    private static double DrainPeak(ProcessLoopback.IAudioCaptureClient capture, int channels, int ms)
    {
        double peak = 0;
        var floats = Array.Empty<float>();
        var until = DateTime.UtcNow.AddMilliseconds(ms);

        while (DateTime.UtcNow < until)
        {
            capture.GetNextPacketSize(out var frames);
            if (frames == 0)
            {
                Thread.Sleep(2);
                continue;
            }

            capture.GetBuffer(out var data, out var got, out var flags, out _, out _);
            if (got > 0 && data != IntPtr.Zero && (flags & 0x2) == 0)
            {
                var n = (int)got * channels;
                if (floats.Length < n) floats = new float[n];
                Marshal.Copy(data, floats, 0, n);
                for (var i = 0; i < n; i++) peak = Math.Max(peak, Math.Abs(floats[i]));
            }

            capture.ReleaseBuffer(got);
        }

        return peak;
    }

    /// <summary>Runs the app's real engine, so a measurement here describes the shipping code.</summary>
    private static void RunEngine(MMDevice device, AudioSessionControl session, int pid,
        float dial, int seconds)
    {
        var engine = new AppBoost.BoostEngine(device, session, pid) { Dial = dial };
        engine.Start();
        Thread.Sleep(seconds * 1000);
        engine.Dispose();
    }

    /// <summary>RMS of everything an endpoint is actually playing, over a window.</summary>
    private static double LoopbackRms(MMDevice endpoint, int ms)
    {
        using var capture = new WasapiLoopbackCapture(endpoint);
        double sumSq = 0;
        long n = 0;

        capture.DataAvailable += (_, e) =>
        {
            // WasapiLoopbackCapture always hands back the endpoint's mix format, which is float.
            for (var i = 0; i + 4 <= e.BytesRecorded; i += 4)
            {
                var f = BitConverter.ToSingle(e.Buffer, i);
                sumSq += f * f;
                n++;
            }
        };

        capture.StartRecording();
        Thread.Sleep(ms);
        capture.StopRecording();
        Thread.Sleep(150); // let the last DataAvailable land

        return n > 0 ? Math.Sqrt(sumSq / n) : 0;
    }

    private static string Meters(List<MMDevice> endpoints) => string.Join("  ", endpoints.Select(ep =>
    {
        try { return $"{Short(ep.FriendlyName)}={ep.AudioMeterInformation.MasterPeakValue:F3}"; }
        catch { return $"{Short(ep.FriendlyName)}=?"; }
    }));

    /// <summary>Endpoint names are long enough to make a per-second line unreadable.</summary>
    private static string Short(string friendlyName)
    {
        var cut = friendlyName.IndexOf(" (", StringComparison.Ordinal);
        return cut > 0 ? friendlyName[..cut] : friendlyName;
    }

    /// <summary>Peaks either side of the gain stage, so a silent link can be pinned down.</summary>
    private sealed class Stats
    {
        private double inPeak, outPeak, gain = 1;

        public void Record(double captured, double rendered, double env)
        {
            inPeak = Math.Max(inPeak, captured);
            outPeak = Math.Max(outPeak, rendered);
            gain = env;
        }

        public (double inPeak, double outPeak, double gain) TakeSnapshot()
        {
            var snap = (inPeak, outPeak, gain);
            inPeak = 0;
            outPeak = 0;
            return snap;
        }
    }

    private static void Pump(ProcessLoopback.IAudioCaptureClient capture,
        BufferedWaveProvider buffer, int channels, float makeup, ManualResetEventSlim stop,
        Stats stats)
    {
        var bytes = Array.Empty<byte>();
        var floats = Array.Empty<float>();

        // A feedback limiter rather than a hard clip. 400% will run into the ceiling constantly,
        // and clipping that much sounds like tearing paper; riding the gain down instead is the
        // difference between "louder" and "broken".
        var env = 1.0;
        var attack = Math.Exp(-1.0 / (0.002 * buffer.WaveFormat.SampleRate));  // 2 ms
        var release = Math.Exp(-1.0 / (0.200 * buffer.WaveFormat.SampleRate)); // 200 ms
        const double ceiling = 0.98;

        while (!stop.IsSet)
        {
            capture.GetNextPacketSize(out var frames);
            if (frames == 0)
            {
                Thread.Sleep(2);
                continue;
            }

            capture.GetBuffer(out var data, out var got, out var flags, out _, out _);
            if (got > 0 && data != IntPtr.Zero)
            {
                var n = (int)got * channels;
                if (floats.Length < n)
                {
                    floats = new float[n];
                    bytes = new byte[n * 4];
                }

                if ((flags & 0x2) != 0) // AUDCLNT_BUFFERFLAGS_SILENT: the pointer is not filled in
                {
                    Array.Clear(floats, 0, n);
                }
                else
                {
                    Marshal.Copy(data, floats, 0, n);
                    double capturedPeak = 0, renderedPeak = 0;
                    for (var i = 0; i < n; i += channels)
                    {
                        double peak = 0;
                        for (var c = 0; c < channels; c++)
                        {
                            capturedPeak = Math.Max(capturedPeak, Math.Abs(floats[i + c]));
                            floats[i + c] *= makeup;
                            peak = Math.Max(peak, Math.Abs(floats[i + c]));
                        }

                        var target = peak > ceiling ? ceiling / peak : 1.0;
                        var coef = target < env ? attack : release;
                        env = coef * env + (1 - coef) * target;

                        for (var c = 0; c < channels; c++)
                        {
                            floats[i + c] = (float)Math.Clamp(floats[i + c] * env, -1.0, 1.0);
                            renderedPeak = Math.Max(renderedPeak, Math.Abs(floats[i + c]));
                        }
                    }

                    stats.Record(capturedPeak, renderedPeak, env);
                }

                Buffer.BlockCopy(floats, 0, bytes, 0, n * 4);
                buffer.AddSamples(bytes, 0, n * 4);
            }

            capture.ReleaseBuffer(got);
        }
    }
}
