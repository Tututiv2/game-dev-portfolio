using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using Probe;

namespace AppBoost;

/// <summary>
/// One app's boost: capture that process, multiply, play the result back.
///
/// The shape of this is forced by a measurement in probe/ — process loopback taps the stream
/// AFTER the app's own volume slider and mute, so the app cannot be silenced and replayed. What
/// works instead is to turn it almost all the way down and make the difference up on the copy:
///
///   app session volume := Leak (3%)              what the speakers hear directly
///   our copy           := captured * dial/Leak   what the speakers hear from us
///
/// The direct path lands 30 dB under our copy, which is inaudible, and it is the same signal so
/// the worst it can do is a faint comb filter. Losing 30 dB to attenuation costs nothing in a
/// float32 pipeline with ~144 dB of range.
/// </summary>
internal sealed class BoostEngine : IDisposable
{
    /// <summary>How much of the app's own output still reaches the speakers directly.</summary>
    private const float Leak = 0.03f;

    private readonly MMDevice device;
    private readonly AudioSessionControl session;
    private readonly int pid;

    private readonly ManualResetEventSlim stop = new(false);
    private float originalVolume = 1f;

    private ProcessLoopback.IAudioClient? client;
    private ProcessLoopback.IAudioCaptureClient? capture;
    private WasapiOut? output;
    private BufferedWaveProvider? buffer;
    private IntPtr format;
    private Thread? pump;

    // Read on the audio thread, written from the UI thread every time the dial moves.
    private volatile float dial = 1f;

    // Peak of what we last rendered, with a decay so the meter falls rather than flickers.
    private volatile float outPeak;

    /// <summary>
    /// Makeup gain per unit of dial, measured rather than assumed.
    ///
    /// The capture does not hand back the app's samples at face value. Measured against a
    /// steady tone it came back at a constant 0.21187 of the generated amplitude — identical at
    /// 48 and 96 kHz and at every amplitude, so it is a fixed gain somewhere in the tap. Using
    /// the obvious dial/Leak made a 4x boost come out at 0.86x: quieter than not boosting.
    ///
    /// The session's peak meter reports the app's level BEFORE its volume scalar, so
    /// meter/captured-peak is exactly this number, and measuring it beats hardcoding 0.21187 —
    /// nothing promises that constant holds on another machine or another endpoint.
    /// </summary>
    private volatile float calibration;

    /// <summary>Level of the boosted copy, for the meter. Approximate by design.</summary>
    public float OutPeak => outPeak;

    public BoostEngine(MMDevice device, AudioSessionControl session, int pid)
    {
        this.device = device;
        this.session = session;
        this.pid = pid;
    }

    public float Dial
    {
        get => dial;
        set => dial = value;
    }

    public void Start()
    {
        var mix = device.AudioClient.MixFormat;
        var rate = mix.SampleRate;
        var channels = Math.Min(mix.Channels, 2);

        format = ProcessLoopback.BuildFloatFormat(rate, channels);
        client = ProcessLoopback.Activate((uint)pid);
        capture = ProcessLoopback.InitCapture(client, format, 10_000_000, verbose: false);

        buffer = new BufferedWaveProvider(WaveFormat.CreateIeeeFloatWaveFormat(rate, channels))
        {
            // Bounded and discarding rather than growing: falling behind should cost a glitch,
            // not a delay that creeps further behind the picture for as long as the app runs.
            BufferDuration = TimeSpan.FromMilliseconds(300),
            DiscardOnBufferOverflow = true,
        };

        output = new WasapiOut(device, AudioClientShareMode.Shared, true, 50);
        output.Init(buffer);

        originalVolume = session.SimpleAudioVolume.Volume;
        client.Start();

        // Calibrate while the app is still at its own volume, so the very first sample we play
        // is already the right loudness rather than jumping once the live trim catches up.
        DrainPeak(capture, channels, 250); // settle
        var capturedPeak = DrainPeak(capture, channels, 350);
        var meterPeak = session.AudioMeterInformation.MasterPeakValue;
        calibration = meterPeak > 0.001f && capturedPeak > 1e-7
            ? (float)(meterPeak / capturedPeak * (originalVolume / Leak))
            : 0f; // silent app: nothing to measure yet, the live trim handles it

        session.SimpleAudioVolume.Volume = Leak;

        pump = new Thread(() => Pump(capture, buffer, channels))
        {
            IsBackground = true,
            Priority = ThreadPriority.AboveNormal, // an audio thread that gets starved crackles
        };

        // MTA, to sit in the same apartment as AudioService's thread and the client it made.
        // Across apartments these interface pointers would need a proxy that does not exist.
        pump.SetApartmentState(ApartmentState.MTA);
        pump.Start();

        // Pre-roll. Starting playback against an empty buffer means the renderer's first reads
        // find nothing and emit silence, which is an audible dropout at the top of every track.
        var waitUntil = DateTime.UtcNow.AddMilliseconds(500);
        while (buffer.BufferedDuration < TimeSpan.FromMilliseconds(60) && DateTime.UtcNow < waitUntil)
        {
            Thread.Sleep(5);
        }

        output.Play();
    }

    /// <summary>Peak of the capture over a window, used to calibrate before playback starts.</summary>
    private static double DrainPeak(ProcessLoopback.IAudioCaptureClient cap, int channels, int ms)
    {
        double peak = 0;
        var floats = Array.Empty<float>();
        var until = DateTime.UtcNow.AddMilliseconds(ms);

        while (DateTime.UtcNow < until)
        {
            cap.GetNextPacketSize(out var frames);
            if (frames == 0)
            {
                Thread.Sleep(2);
                continue;
            }

            cap.GetBuffer(out var data, out var got, out var flags, out _, out _);
            if (got > 0 && data != IntPtr.Zero && (flags & 0x2) == 0)
            {
                var n = (int)got * channels;
                if (floats.Length < n) floats = new float[n];
                Marshal.Copy(data, floats, 0, n);
                for (var i = 0; i < n; i++) peak = Math.Max(peak, Math.Abs(floats[i]));
            }

            cap.ReleaseBuffer(got);
        }

        return peak;
    }

    private void Pump(ProcessLoopback.IAudioCaptureClient cap, BufferedWaveProvider buf, int channels)
    {
        var bytes = Array.Empty<byte>();
        var floats = Array.Empty<float>();

        // Live trim. The meter and the capture are compared over the SAME window, because the
        // meter reports its peak since the last read — sampling it against a single 10 ms packet
        // would compare a 100 ms peak with a 10 ms one and drift the gain upward.
        float windowPeak = 0;
        var sinceTrim = 0;

        // Gain, then saturation — deliberately with no slow envelope anywhere.
        //
        // Two things have been tried here and both failed for the same reason. A peak limiter
        // gave ~10% at 400%: modern masters already peak near full scale, so the gain was pulled
        // straight back off. Replacing it with a compressor gave nothing at all, because music
        // hits peaks constantly and a 120 ms release kept the gain reduction permanently
        // engaged — the quiet parts never came up, net about +2 dB.
        //
        // Anything that reacts to the signal's level and then turns it down loses this fight.
        // Above 0 dBFS the only way left to add loudness is to change the waveform's shape, and
        // soft saturation does exactly that: below the knee samples pass untouched, above it they
        // bend smoothly toward the ceiling instead of being attenuated or squared off. That is
        // what makes a booster loud. The cost is distortion as the dial climbs, which is the
        // honest trade every booster makes, not a defect.
        const double knee = 0.85;
        const double ceiling = 0.98;

        while (!stop.IsSet)
        {
            cap.GetNextPacketSize(out var frames);
            if (frames == 0)
            {
                Thread.Sleep(1);
                continue;
            }

            cap.GetBuffer(out var data, out var got, out var flags, out _, out _);
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
                    outPeak *= 0.7f;
                }
                else
                {
                    // An uncalibrated engine has no idea how much gain a unit of dial is worth,
                    // and guessing loudly is worse than guessing quietly.
                    var makeup = dial * (calibration > 0 ? calibration : 1f / Leak);

                    float rendered = 0;
                    Marshal.Copy(data, floats, 0, n);
                    for (var i = 0; i < n; i++)
                    {
                        windowPeak = Math.Max(windowPeak, Math.Abs(floats[i]));

                        double x = floats[i] * makeup;
                        var magnitude = Math.Abs(x);
                        if (magnitude > knee)
                        {
                            var over = (magnitude - knee) / (ceiling - knee);
                            magnitude = knee + (ceiling - knee) * Math.Tanh(over);
                            x = x < 0 ? -magnitude : magnitude;
                        }

                        floats[i] = (float)x;
                        rendered = Math.Max(rendered, (float)magnitude);
                    }

                    outPeak = Math.Max(rendered, outPeak * 0.7f);

                    // Roughly every 100 ms, re-derive the makeup from what the app is actually
                    // doing. This also absorbs the user moving the app's own volume slider,
                    // which would otherwise silently change what a dial position means.
                    if (++sinceTrim >= 10)
                    {
                        sinceTrim = 0;
                        try
                        {
                            var meter = session.AudioMeterInformation.MasterPeakValue;
                            if (meter > 0.01f && windowPeak > 1e-7f)
                            {
                                var measured = meter / windowPeak;
                                calibration = calibration > 0
                                    ? calibration * 0.8f + measured * 0.2f
                                    : measured;
                            }
                        }
                        catch { /* the session can vanish with the app */ }

                        windowPeak = 0;
                    }
                }

                Buffer.BlockCopy(floats, 0, bytes, 0, n * 4);
                buf.AddSamples(bytes, 0, n * 4);
            }

            cap.ReleaseBuffer(got);
        }
    }

    public void Dispose()
    {
        stop.Set();
        pump?.Join(1000);

        // Volume first and unconditionally: leaving the app parked at 3% is the one failure the
        // user would have to go fix by hand, so it must not depend on the teardown below.
        try { session.SimpleAudioVolume.Volume = originalVolume; }
        catch { /* the session can vanish with the app */ }

        try { output?.Stop(); output?.Dispose(); } catch { }
        try { client?.Stop(); } catch { }
        if (capture is not null) Marshal.ReleaseComObject(capture);
        if (client is not null) Marshal.ReleaseComObject(client);
        if (format != IntPtr.Zero) Marshal.FreeHGlobal(format);

        capture = null;
        client = null;
        output = null;
        format = IntPtr.Zero;
    }
}
