using System.Diagnostics;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;

namespace Probe;

/// <summary>
/// The harness that answered the question the design rested on:
///
///   does process loopback capture the app's audio BEFORE or AFTER its volume slider?
///
/// Measured on 2026-07-29: AFTER. Halving the session volume halves the capture (peak ratio
/// 0.509 across 20 interleaved toggles), and muting the app captures digital silence. So the
/// app cannot be silenced and replayed — see Boost.cs for what replaced that plan.
///
/// Modes: (default) the interleaved pre/post test, --mute, --control, --boost N.
/// </summary>
internal static class Program
{
    private static int Main(string[] args)
    {
        // The control runs first every time: if GetService fails on a plain device client too,
        // nothing measured afterwards means anything.
        // Opt-in: the control's raw MMDeviceEnumerator and NAudio's cannot both live in one
        // process — the second activation gets back the first's RCW and the cast fails.
        if (args.Contains("--control"))
        {
            Control.Run();
            return 0;
        }

        // Where the loudness ceiling actually is. The boost measured a clean 3.94x on the virtual
        // endpoint but only 1.89x at the speakers, because the speakers were already at full
        // scale — so whether any analog headroom is left is the whole question.
        if (args.Contains("--volumes"))
        {
            var devices = new MMDeviceEnumerator();
            foreach (var d in devices.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active))
            {
                var v = d.AudioEndpointVolume;
                Console.WriteLine($"  {d.FriendlyName,-62} {v.MasterVolumeLevelScalar,7:P1}  " +
                                  $"{v.MasterVolumeLevel,7:F1} dB  muted={v.Mute}");
            }

            return 0;
        }

        var target = args.FirstOrDefault(a => !a.StartsWith("--")) ?? "Spotify";

        var found = FindSession(target);
        if (found is null)
        {
            Console.WriteLine($"Nothing called '{target}' has an audio session. Is it actually playing?");
            return 1;
        }

        var (device, session, pid, name) = found.Value;
        Console.WriteLine($"Target: {name} (audio pid {pid}) on {device.FriendlyName}");

        var originalVolume = session.SimpleAudioVolume.Volume;
        Console.WriteLine($"Session volume right now: {originalVolume:P0}");

        // Leaving the app parked at 3% because we were killed would be baffling, so put it
        // back on Ctrl+C as well as on the normal way out.
        void Restore()
        {
            session.SimpleAudioVolume.Mute = false;
            session.SimpleAudioVolume.Volume = originalVolume;
        }

        Console.CancelKeyPress += (_, e) => { Restore(); e.Cancel = false; };

        try
        {
            // Read-only. A force-killed engine never runs its teardown, so the app it was
            // boosting stays parked at 3% with nothing on screen to explain why it went quiet.
            if (args.Contains("--check"))
            {
                Console.WriteLine(originalVolume > 0.9f
                    ? "  OK — not left attenuated."
                    : $"  LEFT ATTENUATED at {originalVolume:P0} — restoring to 100%.");
                if (originalVolume < 0.9f) originalVolume = 1f;
                return 0;
            }

            if (args.Contains("--curve"))
            {
                Boost.Curve(device, session, pid);
                return 0;
            }

            var boostArg = args.SkipWhile(a => a != "--boost").Skip(1).FirstOrDefault();
            if (boostArg is not null)
            {
                var dialArg = float.Parse(boostArg);
                if (args.Contains("--ab"))
                {
                    Boost.AB(device, session, pid, dialArg);
                    return 0;
                }

                var secondsArg = args.SkipWhile(a => a != "--seconds").Skip(1).FirstOrDefault();
                Boost.Run(device, session, pid, dialArg,
                    secondsArg is null ? null : int.Parse(secondsArg));
                return 0;
            }

            return args.Contains("--mute") ? MuteTest(pid, session) : Interleaved(pid, session);
        }
        finally
        {
            Restore();
            Console.WriteLine($"Restored session volume to {originalVolume:P0}");
        }
    }

    /// <summary>
    /// Finds the session actually rendering audio for an app. This is deliberately not
    /// "the process with the main window": Chrome and Spotify both play through a child
    /// process, so the window pid has no session at all. Sessions are also per endpoint,
    /// so every active render device gets searched.
    /// </summary>
    private static (MMDevice device, AudioSessionControl session, int pid, string name)? FindSession(string target)
    {
        // Not disposed: the MMDevice we hand back stays in use for the whole run, and disposing
        // the enumerator takes its devices with it.
        var enumerator = new MMDeviceEnumerator();
        var devices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active);

        Console.WriteLine("Audio sessions currently on this machine:");
        (MMDevice, AudioSessionControl, int, string)? match = null;

        foreach (var device in devices)
        {
            var sessions = device.AudioSessionManager.Sessions;
            for (var i = 0; i < sessions.Count; i++)
            {
                var s = sessions[i];
                var pid = (int)s.GetProcessID;
                if (pid == 0) continue;

                string name;
                try { name = Process.GetProcessById(pid).ProcessName; }
                catch { continue; }

                var active = s.State == AudioSessionState.AudioSessionStateActive;
                Console.WriteLine($"  {name,-24} pid {pid,-8} {(active ? "PLAYING" : "idle")}  on {device.FriendlyName}");

                // Prefer a session that is actually making sound right now.
                if (name.Contains(target, StringComparison.OrdinalIgnoreCase) &&
                    (match is null || active))
                {
                    match = (device, s, pid, name);
                }
            }
        }

        Console.WriteLine();
        return match;
    }

    /// <summary>
    /// Volume turned out to be applied before the loopback tap. Mute is a separate control and
    /// is not always applied at the same point, so it gets its own test: mute the app inside a
    /// running capture and see whether the samples keep coming.
    ///
    /// If they do, the whole design is saved — mute kills what the speakers hear while we keep
    /// a full-scale copy to boost.
    /// </summary>
    private static int MuteTest(int pid, AudioSessionControl session)
    {
        var client = ProcessLoopback.Activate((uint)pid);
        var format = ProcessLoopback.BuildFloatFormat(48000, 2);
        var capture = ProcessLoopback.InitCapture(client, format, 10_000_000, verbose: false);
        client.Start();

        session.SimpleAudioVolume.Mute = false;
        Drain(capture, 300);
        var (s1, n1, p1) = Drain(capture, 1500);

        session.SimpleAudioVolume.Mute = true;
        Drain(capture, 300);
        var (s2, n2, p2) = Drain(capture, 1500);

        session.SimpleAudioVolume.Mute = false;
        client.Stop();
        Marshal.FreeHGlobal(format);
        Marshal.ReleaseComObject(capture);
        Marshal.ReleaseComObject(client);

        var rmsOn = n1 > 0 ? Math.Sqrt(s1 / n1) : 0;
        var rmsMuted = n2 > 0 ? Math.Sqrt(s2 / n2) : 0;
        Console.WriteLine();
        Console.WriteLine($"  unmuted:  rms {rmsOn:F5}  peak {p1:F5}  samples {n1}");
        Console.WriteLine($"  muted:    rms {rmsMuted:F5}  peak {p2:F5}  samples {n2}");
        Console.WriteLine();
        Console.WriteLine(rmsMuted > rmsOn * 0.5
            ? "VERDICT: mute is applied AFTER the tap. We keep capturing a full copy while the\n" +
              "         speakers hear nothing — mute-and-replay works after all."
            : "VERDICT: mute is applied BEFORE the tap too. Muting the app mutes the capture.");
        return 0;
    }

    /// <summary>
    /// Toggles the session volume between 100% and 50% many times inside ONE continuous
    /// capture, and pools each phase's samples.
    ///
    /// Measuring 3 s at 100% and then 3 s at 50% compares two different parts of a song, and
    /// music varies by more than 6 dB between two arbitrary three-second windows — the first
    /// run split 0.88 by RMS and 0.53 by peak for exactly that reason. Alternating fast makes
    /// the content roughly identical across the two pools, so what is left is the volume.
    /// </summary>
    private static int Interleaved(int pid, AudioSessionControl session)
    {
        const int cycles = 20;
        const int phaseMs = 350;
        const int settleMs = 150;

        var client = ProcessLoopback.Activate((uint)pid);
        var format = ProcessLoopback.BuildFloatFormat(48000, 2);
        var capture = ProcessLoopback.InitCapture(client, format, 10_000_000);
        client.Start();

        var sumSq = new double[2];
        var count = new long[2];
        var peak = new double[2];

        Console.WriteLine($"Toggling 100%/50% every {phaseMs} ms for {cycles * (phaseMs + settleMs) / 1000.0:F0}s...");
        for (var c = 0; c < cycles; c++)
        {
            var phase = c % 2; // 0 = full, 1 = half
            session.SimpleAudioVolume.Volume = phase == 0 ? 1.0f : 0.5f;

            Drain(capture, settleMs); // discard: the change is not instant, and the buffer lags
            var (s, n, p) = Drain(capture, phaseMs);
            sumSq[phase] += s;
            count[phase] += n;
            peak[phase] = Math.Max(peak[phase], p);
        }

        client.Stop();
        Marshal.FreeHGlobal(format);
        Marshal.ReleaseComObject(capture);
        Marshal.ReleaseComObject(client);

        var rmsFull = count[0] > 0 ? Math.Sqrt(sumSq[0] / count[0]) : 0;
        var rmsHalf = count[1] > 0 ? Math.Sqrt(sumSq[1] / count[1]) : 0;

        Console.WriteLine();
        Console.WriteLine($"  at 100%:  rms {rmsFull:F5}  peak {peak[0]:F5}  samples {count[0]}");
        Console.WriteLine($"  at  50%:  rms {rmsHalf:F5}  peak {peak[1]:F5}  samples {count[1]}");

        if (peak[0] < 0.0001 && peak[1] < 0.0001)
        {
            Console.WriteLine("VERDICT: captured silence. Play something in the app and re-run.");
            return 2;
        }

        var rmsRatio = rmsHalf / Math.Max(rmsFull, 1e-9);
        var peakRatio = peak[1] / Math.Max(peak[0], 1e-9);
        Console.WriteLine($"  rms ratio  (50/100) = {rmsRatio:F3}");
        Console.WriteLine($"  peak ratio (50/100) = {peakRatio:F3}");
        Console.WriteLine();

        // 1.0 means the slider did not touch what we captured; 0.5 means it did.
        // Judge on RMS: peak is one sample out of ~34000 and far noisier.
        Console.WriteLine(rmsRatio < 0.75
            ? "VERDICT: POST-volume. The app's own slider is already applied to what we capture,\n" +
              "         so silencing the app silences the copy too. The mute-and-replay dial is\n" +
              "         dead; per-app endpoint routing would be needed instead."
            : "VERDICT: PRE-volume. The capture ignores the app's slider, so we can turn the app\n" +
              "         down to nothing and replay our own louder copy. The dial design works.");
        return 0;
    }

    private static (double sumSq, long n, double peak) Drain(
        ProcessLoopback.IAudioCaptureClient capture, int ms)
    {
        double sumSq = 0, peak = 0;
        long n = 0;
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
            if (got > 0 && data != IntPtr.Zero && (flags & 0x2) == 0) // 0x2 = SILENT
            {
                var floats = new float[got * 2];
                Marshal.Copy(data, floats, 0, floats.Length);
                foreach (var f in floats)
                {
                    sumSq += f * f;
                    peak = Math.Max(peak, Math.Abs(f));
                }

                n += floats.Length;
            }

            capture.ReleaseBuffer(got);
        }

        return (sumSq, n, peak);
    }
}
