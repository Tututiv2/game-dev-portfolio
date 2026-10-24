using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using AudioCapture.Recording;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace AudioCapture.SelfTest;

/// <summary>
/// Drives the real Recorder / AudioPost against the real audio hardware. The loopback tests play a
/// short quiet tone through the default output so there is something to capture.
/// </summary>
internal static class Program
{
    private static int passed, failed;
    private static readonly string Work = Path.Combine(Path.GetTempPath(), "audiocapture_selftest");

    private static int Main()
    {
        Directory.CreateDirectory(Work);
        Console.WriteLine("AudioCapture self-test");
        Console.WriteLine(new string('-', 70));

        try
        {
            TestChannelFold();
            TestMonoFoldCancels();
            TestOutputNaming();
            TestPostTrimAndNormalise();
            TestPostSilentInput();
            TestPostAntiClip();
            TestPostStereo();
            TestLoopbackCapture();
            TestLoopbackStereo();
            TestMicCapture();
            TestMixedCapture();
        }
        catch (Exception ex)
        {
            Fail("harness", ex.ToString());
        }

        Console.WriteLine(new string('-', 70));
        Console.WriteLine($"{passed} passed, {failed} failed");
        try { Directory.Delete(Work, true); } catch { }
        return failed == 0 ? 0 : 1;
    }

    // -- pure logic, no hardware -----------------------------------------

    private static void TestChannelFold()
    {
        // -- to mono --
        var m8 = ChannelFold.MatrixFor(8, 1)[0];
        // Stereo content in the front pair of a 7.1 endpoint (FL = FR = 1, rest silent).
        Check("fold->mono: stereo in a 7.1 stream keeps its level",
              Math.Abs(m8[0] + m8[1] - 1.0) < 1e-6, $"gain {m8[0] + m8[1]:0.0000}x");
        Check("fold->mono: LFE is dropped from 7.1", m8[3] == 0f, "ch3 weight 0");
        Check("fold->mono: LFE is dropped from 5.1", ChannelFold.MatrixFor(6, 1)[0][3] == 0f, "ch3 weight 0");

        var m2 = ChannelFold.MatrixFor(2, 1)[0];
        Check("fold->mono: plain stereo is a straight average",
              Math.Abs(m2[0] - 0.5f) < 1e-6 && Math.Abs(m2[1] - 0.5f) < 1e-6, "0.5, 0.5");

        // -- to stereo --
        var s8 = ChannelFold.MatrixFor(8, 2);
        Check("fold->stereo: front pair passes through at unity",
              Math.Abs(s8[0][0] - 1f) < 1e-6 && Math.Abs(s8[1][1] - 1f) < 1e-6, "L<-FL 1.0, R<-FR 1.0");
        Check("fold->stereo: left does not leak into right",
              s8[1][0] == 0f && s8[0][1] == 0f, "no FL->R, no FR->L");
        Check("fold->stereo: centre goes to both sides at -3 dB",
              Math.Abs(s8[0][2] - 0.70710678f) < 1e-6 && Math.Abs(s8[1][2] - 0.70710678f) < 1e-6, "0.7071 each");
        Check("fold->stereo: surrounds stay on their own side",
              s8[0][4] > 0 && s8[1][4] == 0 && s8[1][5] > 0 && s8[0][5] == 0, "BL->L only, BR->R only");
        Check("fold->stereo: LFE is dropped", s8[0][3] == 0f && s8[1][3] == 0f, "ch3 weight 0 both sides");

        var s2 = ChannelFold.MatrixFor(2, 2);
        Check("fold->stereo: a stereo source is untouched",
              s2[0][0] == 1f && s2[0][1] == 0f && s2[1][0] == 0f && s2[1][1] == 1f, "identity");

        var s1 = ChannelFold.MatrixFor(1, 2);
        Check("fold->stereo: a mono source is duplicated", s1[0][0] == 1f && s1[1][0] == 1f, "1.0 both sides");
    }

    /// <summary>
    /// The whole reason stereo exists here: running an out-of-phase pair through the mono fold
    /// cancels it to nothing, and in real material what cancels is mostly the treble.
    /// </summary>
    private static void TestMonoFoldCancels()
    {
        const int rate = 48000;
        var stereo = new float[rate * 2];
        for (int i = 0; i < rate; i++)
        {
            float v = 0.5f * (float)Math.Sin(2 * Math.PI * 8000.0 * i / rate);
            stereo[i * 2] = v;          // L
            stereo[i * 2 + 1] = -v;     // R, inverted
        }

        var toMono = new ChannelFold(new ArraySampleProvider(stereo, rate, 2), 1);
        var mono = Drain(toMono);
        float monoPeak = mono.Length == 0 ? 0 : mono.Max(Math.Abs);

        var toStereo = new ChannelFold(new ArraySampleProvider(stereo, rate, 2), 2);
        var kept = Drain(toStereo);
        float keptPeak = kept.Length == 0 ? 0 : kept.Max(Math.Abs);

        // 8 kHz at 48 kHz is 6 samples per cycle, so no sample lands on the crest -- compare against
        // what the input actually peaks at, not the nominal 0.5 amplitude.
        float inPeak = stereo.Max(Math.Abs);

        Check("fold: an out-of-phase pair cancels to silence in mono",
              monoPeak < 1e-6f, $"mono peak {monoPeak:0.000000}");
        Check("fold: the same pair survives intact in stereo",
              Math.Abs(keptPeak - inPeak) < 1e-5f, $"stereo peak {keptPeak:0.0000}, input {inPeak:0.0000}");
    }

    private static void TestOutputNaming()
    {
        string folder = Path.Combine(Work, "dataset");
        var when = new DateTime(2026, 8, 7, 14, 30, 5);

        // Dataset mode numbers from 0001 and keeps counting as files land.
        var (p1, id1) = OutputWriter.NextOutputPath(folder, "voice", dataset: true, when);
        Check("naming: first dataset take is 0001", id1 == "voice_0001", id1);
        Check("naming: dataset takes go into wavs/",
              Path.GetDirectoryName(p1)!.EndsWith("wavs"), Path.GetFileName(Path.GetDirectoryName(p1)!));
        File.WriteAllBytes(p1, new byte[16]);

        var (p2, id2) = OutputWriter.NextOutputPath(folder, "voice", dataset: true, when);
        Check("naming: the next take is 0002", id2 == "voice_0002", id2);
        File.WriteAllBytes(p2, new byte[16]);

        // A gap must not restart the count and overwrite existing takes.
        File.WriteAllBytes(Path.Combine(folder, "wavs", "voice_0009.wav"), new byte[16]);
        var (_, id3) = OutputWriter.NextOutputPath(folder, "voice", dataset: true, when);
        Check("naming: counts past a gap instead of overwriting", id3 == "voice_0010", id3);

        // A stray timestamped file in wavs/ must not derail the parser.
        File.WriteAllBytes(Path.Combine(folder, "wavs", "voice_20260807_143005.wav"), new byte[16]);
        var (_, id4) = OutputWriter.NextOutputPath(folder, "voice", dataset: true, when);
        Check("naming: ignores a non-numeric stray file", id4 == "voice_0010", id4);

        // Timestamped mode.
        string flat = Path.Combine(Work, "flat");
        var (f1, fid1) = OutputWriter.NextOutputPath(flat, "voice", dataset: false, when);
        Check("naming: timestamped take", fid1 == "voice_20260807_143005", fid1);
        File.WriteAllBytes(f1, new byte[16]);
        var (_, fid2) = OutputWriter.NextOutputPath(flat, "voice", dataset: false, when);
        Check("naming: same-second collision gets a suffix", fid2 == "voice_20260807_143005_2", fid2);

        Check("naming: unsafe prefix characters are replaced",
              OutputWriter.SanitizePrefix("my voice/take:1") == "my_voice_take_1",
              OutputWriter.SanitizePrefix("my voice/take:1"));
        Check("naming: an empty prefix falls back", OutputWriter.SanitizePrefix("   ") == "voice", "voice");

        // Transcripts.
        OutputWriter.WriteTranscript(folder, p1, id1, "  Hello   there,\r\n friend.  ", dataset: true);
        OutputWriter.WriteTranscript(folder, p2, id2, "Second line.", dataset: true);
        string csv = File.ReadAllText(Path.Combine(folder, "metadata.csv"));
        Check("transcript: metadata.csv is LJSpeech id|text|text",
              csv == "voice_0001|Hello there, friend.|Hello there, friend.\nvoice_0002|Second line.|Second line.\n",
              csv.Replace("\n", "\\n"));

        string note = OutputWriter.WriteTranscript(folder, p1, "voice_0003", "   ", dataset: true);
        Check("transcript: an empty dataset row is skipped, not written",
              note.Contains("skipped") && File.ReadAllText(Path.Combine(folder, "metadata.csv")).Split('\n').Length == 3,
              note);

        OutputWriter.WriteTranscript(flat, f1, fid1, "A spoken line.", dataset: false);
        string side = Path.ChangeExtension(f1, ".txt");
        Check("transcript: timestamped mode writes a .txt beside the wav",
              File.Exists(side) && File.ReadAllText(side) == "A spoken line.", Path.GetFileName(side));

        Check("transcript: sidecar is UTF-8 with no BOM",
              File.ReadAllBytes(side)[0] != 0xEF, "first byte 0x" + File.ReadAllBytes(side)[0].ToString("X2"));

        // -- renaming a take from the post-take dialog --
        string renamed = OutputWriter.RenameTake(f1, "sword hit 3");
        Check("rename: the wav moves and the name is sanitised",
              Path.GetFileName(renamed) == "sword_hit_3.wav" && File.Exists(renamed) && !File.Exists(f1),
              Path.GetFileName(renamed));
        Check("rename: the transcript sidecar follows the wav",
              File.Exists(Path.ChangeExtension(renamed, ".txt")) && !File.Exists(side),
              "sword_hit_3.txt");

        // A second take claiming the same name must not destroy the first.
        var (f2, fid3) = OutputWriter.NextOutputPath(flat, "voice", dataset: false, when.AddSeconds(30));
        File.WriteAllBytes(f2, new byte[16]);
        string renamed2 = OutputWriter.RenameTake(f2, "sword hit 3");
        Check("rename: a name collision gets a suffix instead of overwriting",
              Path.GetFileName(renamed2) == "sword_hit_3_2.wav" && File.Exists(renamed),
              Path.GetFileName(renamed2));

        Check("rename: renaming to the same name is a no-op",
              OutputWriter.RenameTake(renamed, "sword_hit_3") == renamed, "unchanged");
    }

    private static void TestPostTrimAndNormalise()
    {
        const int rate = 24000;
        // 1 s silence, 1 s of a quiet 440 Hz tone, 1 s silence.
        var samples = new float[rate * 3];
        for (int i = 0; i < rate; i++)
            samples[rate + i] = 0.05f * (float)Math.Sin(2 * Math.PI * 440.0 * i / rate);

        string scratch = Path.Combine(Work, "synth.wav");
        WriteFloatWav(scratch, samples, rate);

        string outPath = Path.Combine(Work, "synth_out.wav");
        var r = AudioPost.Render(scratch, outPath, trim: true, normalize: true);

        Check("post: writes 24 kHz mono 16-bit", FormatOf(outPath) == (24000, 1, 16), FormatOf(outPath).ToString());

        // 1 s of tone plus 0.15 s of padding at each end.
        Check("post: trims silence to ~1.30 s",
              Math.Abs(r.Duration.TotalSeconds - 1.30) < 0.05,
              $"{r.Duration.TotalSeconds:0.000} s");

        var outSamples = ReadSamples(outPath);
        float peak = outSamples.Length == 0 ? 0 : outSamples.Max(Math.Abs);
        double peakDb = 20 * Math.Log10(peak);
        Check("post: normalises peak to -1 dBFS", Math.Abs(peakDb + 1.0) < 0.25, $"{peakDb:0.00} dBFS");

        Check("post: reports the pre-gain peak", Math.Abs(r.PeakDb - 20 * Math.Log10(0.05)) < 0.5, $"{r.PeakDb:0.00} dB");
        Check("post: reports +25 dB of gain", Math.Abs(r.GainDb - 25.0) < 0.6, $"{r.GainDb:+0.00} dB");

        Check("post: the tone comes through clean",
              ToneShare(outSamples, 440, 24000) > 0.9,
              $"440 Hz share {ToneShare(outSamples, 440, 24000):0.000}");

        // Without trim the full three seconds must come back.
        var r2 = AudioPost.Render(scratch, Path.Combine(Work, "synth_notrim.wav"), trim: false, normalize: false);
        Check("post: trim off keeps the full 3.00 s", Math.Abs(r2.Duration.TotalSeconds - 3.0) < 0.02, $"{r2.Duration.TotalSeconds:0.000} s");
        Check("post: normalise off leaves gain at 0 dB", Math.Abs(r2.GainDb) < 0.01, $"{r2.GainDb:0.00} dB");
    }

    private static void TestPostSilentInput()
    {
        const int rate = 22050;
        string scratch = Path.Combine(Work, "silent.wav");
        WriteFloatWav(scratch, new float[rate], rate);

        string outPath = Path.Combine(Work, "silent_out.wav");
        var r = AudioPost.Render(scratch, outPath, trim: true, normalize: true);

        Check("post: flags an all-silent take", r.WasSilent, "WasSilent=" + r.WasSilent);
        Check("post: does not amplify silence into noise",
              File.Exists(outPath) && ReadSamples(outPath).All(v => v == 0f), "output is digital silence");
        Check("post: honours 22 050 Hz", FormatOf(outPath).rate == 22050, FormatOf(outPath).rate + " Hz");
    }

    private static void TestPostAntiClip()
    {
        const int rate = 24000;
        var samples = new float[rate];
        for (int i = 0; i < rate; i++)
            samples[i] = 1.8f * (float)Math.Sin(2 * Math.PI * 300.0 * i / rate);   // way past full scale

        string scratch = Path.Combine(Work, "hot.wav");
        WriteFloatWav(scratch, samples, rate);

        string outPath = Path.Combine(Work, "hot_out.wav");
        var r = AudioPost.Render(scratch, outPath, trim: false, normalize: false);

        var s = ReadSamples(outPath);
        int atCeiling = s.Count(v => Math.Abs(v) > 0.9995f);
        Check("post: over-full-scale input is pulled back, not clipped",
              atCeiling == 0 && r.GainDb < -4.0, $"{atCeiling} clipped samples, {r.GainDb:0.00} dB applied");
        Check("post: de-clipped signal is still the same tone",
              ToneShare(s, 300, rate) > 0.9, $"300 Hz share {ToneShare(s, 300, rate):0.000}");
    }

    private static void TestPostStereo()
    {
        const int rate = 48000;
        // Left carries a 1 kHz tone at 0.4, right a 5 kHz tone at 0.2. They must stay apart.
        var s = new float[rate * 2];
        for (int i = 0; i < rate; i++)
        {
            s[i * 2] = 0.4f * (float)Math.Sin(2 * Math.PI * 1000.0 * i / rate);
            s[i * 2 + 1] = 0.2f * (float)Math.Sin(2 * Math.PI * 5000.0 * i / rate);
        }

        string scratch = Path.Combine(Work, "stereo.wav");
        using (var w = new WaveFileWriter(scratch, WaveFormat.CreateIeeeFloatWaveFormat(rate, 2)))
            w.WriteSamples(s, 0, s.Length);

        string outPath = Path.Combine(Work, "stereo_out.wav");
        var r = AudioPost.Render(scratch, outPath, trim: false, normalize: true);

        Check("post-stereo: writes 48 kHz stereo 16-bit", FormatOf(outPath) == (48000, 2, 16), FormatOf(outPath).ToString());
        Check("post-stereo: duration is in frames, not samples",
              Math.Abs(r.Duration.TotalSeconds - 1.0) < 0.01, $"{r.Duration.TotalSeconds:0.000} s");
        Check("post-stereo: reports 2 channels", r.Channels == 2, r.Channels + " ch");

        var outSamples = ReadSamples(outPath);
        Check("post-stereo: sample count is frames x 2", outSamples.Length == rate * 2, outSamples.Length + " samples");

        // De-interleave and confirm each side kept its own tone.
        var left = new float[rate];
        var right = new float[rate];
        for (int i = 0; i < rate; i++) { left[i] = outSamples[i * 2]; right[i] = outSamples[i * 2 + 1]; }

        Check("post-stereo: left still carries only its 1 kHz tone",
              ToneShare(left, 1000, rate) > 0.9 && ToneShare(left, 5000, rate) < 0.05,
              $"1k {ToneShare(left, 1000, rate):0.000}, 5k {ToneShare(left, 5000, rate):0.000}");
        Check("post-stereo: right still carries only its 5 kHz tone",
              ToneShare(right, 5000, rate) > 0.9 && ToneShare(right, 1000, rate) < 0.05,
              $"5k {ToneShare(right, 5000, rate):0.000}, 1k {ToneShare(right, 1000, rate):0.000}");

        // Normalising is one gain across the pair, so the balance between sides must not change.
        float lPeak = left.Max(Math.Abs), rPeak = right.Max(Math.Abs);
        Check("post-stereo: normalising uses one gain, so the L/R balance holds",
              Math.Abs(lPeak / rPeak - 2.0) < 0.02, $"L/R {lPeak / rPeak:0.000} (was 2.000)");
        Check("post-stereo: the louder side lands at -1 dBFS",
              Math.Abs(20 * Math.Log10(lPeak) + 1.0) < 0.25, $"{20 * Math.Log10(lPeak):0.00} dBFS");
    }

    // -- real capture ----------------------------------------------------

    private static void TestLoopbackStereo()
    {
        var en = new MMDeviceEnumerator();
        MMDevice render;
        try { render = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); }
        catch { Skip("loopback-stereo: no playback device"); return; }

        Console.WriteLine("   capturing 48 kHz STEREO from the 8-channel endpoint (2.0 s, 6 kHz tone)");

        using var rec = new Recorder(48000, 2, micDevice: null, systemDevice: render, micGain: 1f, sysGain: 1f);
        using var tone = PlayTone(6000, 0.15f);

        rec.Start();
        Thread.Sleep(2000);
        rec.Stop();
        tone.Stop();

        Check("loopback-stereo: reports 2 channels", rec.Channels == 2, rec.Channels + " ch");
        Check("loopback-stereo: captured roughly 2.0 s",
              Math.Abs(rec.Duration.TotalSeconds - 2.0) < 0.35, $"{rec.Duration.TotalSeconds:0.000} s");

        string outPath = Path.Combine(Work, "loopback_stereo.wav");
        var r = AudioPost.Render(rec.ScratchPath, outPath, trim: true, normalize: true);
        rec.DeleteScratch();

        Check("loopback-stereo: 48 kHz stereo 16-bit", FormatOf(outPath) == (48000, 2, 16), FormatOf(outPath).ToString());
        Check("loopback-stereo: level survived the 8-to-2 fold", r.PeakDb > -22.0, $"pre-gain peak {r.PeakDb:0.0} dBFS");

        // A 6 kHz tone is above the 12 kHz ceiling of a 24 kHz file only by contrast -- what this
        // proves is that 48 kHz round-trips content that 24 kHz would have had to low-pass.
        var all = ReadSamples(outPath);
        var left = new float[all.Length / 2];
        for (int i = 0; i < left.Length; i++) left[i] = all[i * 2];
        Check("loopback-stereo: 6 kHz came through the 96->48 kHz resample",
              ToneShare(left, 6000, 48000) > 0.5, $"6 kHz share {ToneShare(left, 6000, 48000):0.000}");
    }

    private static void TestLoopbackCapture()
    {
        var en = new MMDeviceEnumerator();
        MMDevice render;
        try { render = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia); }
        catch { Skip("loopback: no playback device on this machine"); return; }

        var mix = render.AudioClient.MixFormat;
        Console.WriteLine($"   output: {render.FriendlyName}");
        Console.WriteLine($"           {mix.SampleRate} Hz, {mix.Channels} ch -> resampling and folding to 24 kHz mono");

        using var rec = new Recorder(24000, 1, micDevice: null, systemDevice: render, micGain: 1f, sysGain: 1f);
        using var tone = PlayTone(440, 0.15f);

        rec.Start();
        Thread.Sleep(2500);
        rec.Stop();
        tone.Stop();

        Check("loopback: captured roughly 2.5 s",
              Math.Abs(rec.Duration.TotalSeconds - 2.5) < 0.35, $"{rec.Duration.TotalSeconds:0.000} s");

        string outPath = Path.Combine(Work, "loopback.wav");
        var r = AudioPost.Render(rec.ScratchPath, outPath, trim: true, normalize: true);
        rec.DeleteScratch();

        Check("loopback: produced a non-silent file", !r.WasSilent, $"peak {r.PeakDb:0.0} dBFS");
        Check("loopback: 24 kHz mono 16-bit", FormatOf(outPath) == (24000, 1, 16), FormatOf(outPath).ToString());

        // The tone plays at 0.15 amplitude (-16.5 dBFS). Folding 8 channels down must not lose it:
        // a flat average would land near -28 dBFS instead.
        Check("loopback: level survived the 8-channel fold",
              r.PeakDb > -22.0, $"pre-gain peak {r.PeakDb:0.0} dBFS (flat average would be ~{20 * Math.Log10(0.15 / 4):0.0})");

        var s = ReadSamples(outPath);
        double share = ToneShare(s, 440, 24000);
        Check("loopback: the captured audio IS the 440 Hz tone", share > 0.5, $"440 Hz share {share:0.000}");
    }

    private static void TestMicCapture()
    {
        var en = new MMDeviceEnumerator();
        MMDevice mic;
        try { mic = en.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Multimedia); }
        catch { Skip("mic: no capture device on this machine"); return; }

        Console.WriteLine($"   input:  {mic.FriendlyName}");

        using var rec = new Recorder(22050, 1, micDevice: mic, systemDevice: null, micGain: 1f, sysGain: 1f);
        rec.Start();
        Thread.Sleep(1500);
        rec.Stop();

        // Content depends on the room, so this asserts the stream ran, not that anyone spoke.
        Check("mic: captured roughly 1.5 s",
              Math.Abs(rec.Duration.TotalSeconds - 1.5) < 0.35, $"{rec.Duration.TotalSeconds:0.000} s");

        string outPath = Path.Combine(Work, "mic.wav");
        AudioPost.Render(rec.ScratchPath, outPath, trim: false, normalize: false);
        rec.DeleteScratch();

        Check("mic: 22 050 Hz mono 16-bit", FormatOf(outPath) == (22050, 1, 16), FormatOf(outPath).ToString());

        int samples = ReadSamples(outPath).Length;
        Check("mic: sample count matches the reported duration",
              Math.Abs(samples / 22050.0 - rec.Duration.TotalSeconds) < 0.02, $"{samples} samples");
    }

    private static void TestMixedCapture()
    {
        var en = new MMDeviceEnumerator();
        MMDevice render, mic;
        try
        {
            render = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            mic = en.GetDefaultAudioEndpoint(DataFlow.Capture, Role.Multimedia);
        }
        catch { Skip("mixed: needs both a playback and a capture device"); return; }

        Console.WriteLine("   mixing mic + system for 2.0 s (880 Hz tone)");

        using var rec = new Recorder(24000, 1, micDevice: mic, systemDevice: render, micGain: 1f, sysGain: 1f);
        using var tone = PlayTone(880, 0.15f);

        rec.Start();
        Thread.Sleep(2000);
        rec.Stop();
        tone.Stop();

        // Two devices on different clocks and different rates (48 kHz mic, 96 kHz output here):
        // the pump reads to the slower of the two, so the length must still be right.
        Check("mixed: two clocks stayed in step for 2.0 s",
              Math.Abs(rec.Duration.TotalSeconds - 2.0) < 0.4, $"{rec.Duration.TotalSeconds:0.000} s");

        string outPath = Path.Combine(Work, "mixed.wav");
        var r = AudioPost.Render(rec.ScratchPath, outPath, trim: false, normalize: true);
        rec.DeleteScratch();

        Check("mixed: not silent", !r.WasSilent, $"peak {r.PeakDb:0.0} dBFS");

        double share = ToneShare(ReadSamples(outPath), 880, 24000);
        Check("mixed: the system tone is present in the mix", share > 0.3, $"880 Hz share {share:0.000}");
    }

    // -- helpers ---------------------------------------------------------

    /// <summary>
    /// Plays through WinMM rather than WASAPI: WasapiOut in shared mode demands the endpoint's exact
    /// mix format, and a 96 kHz 8-channel extensible endpoint rejects anything hand-built. WinMM
    /// converts for us and lands on the same default endpoint, which is what the loopback test needs.
    /// </summary>
    private static IWavePlayer PlayTone(double freq, float amplitude)
    {
        var gen = new SignalGenerator(48000, 2)
        {
            Gain = amplitude,
            Frequency = freq,
            Type = SignalGeneratorType.Sin
        };
        var outp = new WaveOutEvent { DesiredLatency = 120 };
        outp.Init(gen.ToWaveProvider());
        outp.Play();
        Thread.Sleep(300);   // let the render stream settle before capture starts
        return outp;
    }

    /// <summary>Feeds a fixed interleaved buffer to a provider chain, for testing folds offline.</summary>
    private sealed class ArraySampleProvider : ISampleProvider
    {
        private readonly float[] data;
        private int pos;
        public WaveFormat WaveFormat { get; }

        public ArraySampleProvider(float[] interleaved, int rate, int channels)
        {
            data = interleaved;
            WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(rate, channels);
        }

        public int Read(float[] buffer, int offset, int count)
        {
            int n = Math.Min(count, data.Length - pos);
            if (n <= 0) return 0;
            Array.Copy(data, pos, buffer, offset, n);
            pos += n;
            return n;
        }
    }

    private static float[] Drain(ISampleProvider sp)
    {
        var all = new List<float>();
        var buf = new float[8192];
        int n;
        while ((n = sp.Read(buf, 0, buf.Length)) > 0)
            for (int i = 0; i < n; i++) all.Add(buf[i]);
        return all.ToArray();
    }

    private static void WriteFloatWav(string path, float[] samples, int rate)
    {
        using var w = new WaveFileWriter(path, WaveFormat.CreateIeeeFloatWaveFormat(rate, 1));
        w.WriteSamples(samples, 0, samples.Length);
    }

    private static (int rate, int channels, int bits) FormatOf(string path)
    {
        using var r = new WaveFileReader(path);
        return (r.WaveFormat.SampleRate, r.WaveFormat.Channels, r.WaveFormat.BitsPerSample);
    }

    private static float[] ReadSamples(string path)
    {
        using var r = new WaveFileReader(path);
        var sp = r.ToSampleProvider();
        var all = new List<float>();
        var buf = new float[16384];
        int n;
        while ((n = sp.Read(buf, 0, buf.Length)) > 0)
            for (int i = 0; i < n; i++) all.Add(buf[i]);
        return all.ToArray();
    }

    /// <summary>Energy at <paramref name="freq"/> as a share of the whole spectrum, via Goertzel.</summary>
    private static double ToneShare(float[] x, double freq, int rate)
    {
        if (x.Length < 1024) return 0;
        double target = Goertzel(x, freq, rate);

        double rest = 0;
        for (double f = 100; f < rate / 2.0; f += 100)
        {
            if (Math.Abs(f - freq) < 60) continue;   // skip the target's own bin and its leakage
            rest += Goertzel(x, f, rate);
        }

        double total = target + rest;
        return total <= 0 ? 0 : target / total;
    }

    private static double Goertzel(float[] x, double freq, int rate)
    {
        double w = 2 * Math.PI * freq / rate;
        double c = 2 * Math.Cos(w);
        double s1 = 0, s2 = 0;
        foreach (var v in x)
        {
            double s0 = v + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double power = s1 * s1 + s2 * s2 - c * s1 * s2;
        return power <= 0 ? 0 : Math.Sqrt(power) / (x.Length / 2.0);
    }

    private static void Check(string name, bool ok, string detail)
    {
        if (ok) { passed++; Console.WriteLine($"  PASS  {name}   ({detail})"); }
        else { failed++; Console.WriteLine($"  FAIL  {name}   ({detail})"); }
    }

    private static void Fail(string name, string detail) => Check(name, false, detail);

    private static void Skip(string reason) => Console.WriteLine($"  SKIP  {reason}");
}
