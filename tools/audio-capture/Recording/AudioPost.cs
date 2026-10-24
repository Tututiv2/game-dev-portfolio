using System;
using System.IO;
using NAudio.Wave;

namespace AudioCapture.Recording;

/// <summary>
/// Turns the float scratch WAV into the deliverable: 16-bit PCM at the scratch's own rate and
/// channel count, optionally silence-trimmed and peak-normalised. Two streaming passes, so a
/// one-hour take costs the same memory as a ten-second one.
/// </summary>
public static class AudioPost
{
    public const double SilenceThresholdDb = -45.0;
    public const double TargetPeakDb = -1.0;
    private const double PadSeconds = 0.15;    // breathing room kept around the sound
    private const double FadeSeconds = 0.005;  // kills the click at a hard trim edge
    private const float MaxGain = 20f;         // ~26 dB, so a silent take is not amplified into hiss

    public sealed record Result(
        string Path,
        TimeSpan Duration,
        int SampleRate,
        int Channels,
        double PeakDb,
        double GainDb,
        bool Trimmed,
        bool WasSilent);

    public static Result Render(string scratchPath, string outPath, bool trim, bool normalize)
    {
        float threshold = (float)Math.Pow(10.0, SilenceThresholdDb / 20.0);

        int rate, channels;
        long totalFrames;
        float peak = 0f;
        long firstFrame = -1, lastFrame = -1;

        // Pass 1 -- peak, and the first/last frame that is not silence.
        using (var reader = new WaveFileReader(scratchPath))
        {
            rate = reader.WaveFormat.SampleRate;
            channels = reader.WaveFormat.Channels;

            var sp = reader.ToSampleProvider();
            var buf = new float[16384];
            long sampleIdx = 0;
            int n;
            while ((n = sp.Read(buf, 0, buf.Length)) > 0)
            {
                for (int i = 0; i < n; i++, sampleIdx++)
                {
                    float a = Math.Abs(buf[i]);
                    if (a > peak) peak = a;
                    if (a > threshold)
                    {
                        // A frame counts as sound if ANY of its channels does.
                        long f = sampleIdx / channels;
                        if (firstFrame < 0) firstFrame = f;
                        lastFrame = f;
                    }
                }
            }
            totalFrames = sampleIdx / channels;
        }

        bool wasSilent = firstFrame < 0;
        long start = 0, end = totalFrames;
        bool trimmed = false;

        if (trim && !wasSilent)
        {
            int pad = (int)(PadSeconds * rate);
            start = Math.Max(0, firstFrame - pad);
            end = Math.Min(totalFrames, lastFrame + pad + 1);
            trimmed = start > 0 || end < totalFrames;
        }

        float target = (float)Math.Pow(10.0, TargetPeakDb / 20.0);
        float gain = 1f;

        if (normalize && peak > 1e-6f)
        {
            gain = target / peak;
            if (gain > MaxGain) gain = MaxGain;
        }
        else if (peak > 1f)
        {
            // Even with normalising off, never hand back a clipped file. A surround fold or a gain
            // slider above 1x can push the float scratch past full scale; pull it back instead.
            gain = target / peak;
        }

        long count = Math.Max(0, end - start);
        int fade = (int)(FadeSeconds * rate);
        if (fade * 2 > count) fade = (int)(count / 2);

        Directory.CreateDirectory(Path.GetDirectoryName(outPath)!);

        // Pass 2 -- trim, gain, fade the edges, quantise once.
        using (var reader = new WaveFileReader(scratchPath))
        using (var writer = new WaveFileWriter(outPath, new WaveFormat(rate, 16, channels)))
        {
            var sp = reader.ToSampleProvider();
            var buf = new float[16384];
            var outBuf = new float[16384];
            long sampleIdx = 0;
            int n;

            while (sampleIdx / channels < end && (n = sp.Read(buf, 0, buf.Length)) > 0)
            {
                int w = 0;
                for (int i = 0; i < n; i++, sampleIdx++)
                {
                    long frame = sampleIdx / channels;
                    if (frame < start) continue;
                    if (frame >= end) break;

                    float v = buf[i] * gain;

                    long pos = frame - start;
                    if (fade > 0)
                    {
                        if (pos < fade) v *= pos / (float)fade;
                        else if (pos >= count - fade) v *= (count - 1 - pos) / (float)fade;
                    }

                    if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                    outBuf[w++] = v;
                }
                if (w > 0) writer.WriteSamples(outBuf, 0, w);
            }
        }

        return new Result(
            outPath,
            TimeSpan.FromSeconds((double)count / rate),
            rate,
            channels,
            peak > 1e-9f ? 20.0 * Math.Log10(peak) : double.NegativeInfinity,
            20.0 * Math.Log10(gain),
            trimmed,
            wasSilent);
    }
}
