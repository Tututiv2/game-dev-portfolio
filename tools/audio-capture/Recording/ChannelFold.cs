using System;
using NAudio.Wave;

namespace AudioCapture.Recording;

/// <summary>
/// Folds any channel count down to mono or stereo.
///
/// This matters more than it looks: a WASAPI loopback endpoint configured for 7.1 hands back eight
/// channels even when the app playing is plain stereo, and a flat average of eight would then be
/// 12 dB quiet. So it uses the conventional ITU fold — front pair at unity, centre and surrounds at
/// -3 dB, LFE dropped — which passes stereo content through at exactly its own level.
///
/// Folding to stereo also keeps left and right apart. Folding them together is not just a loss of
/// width: wherever the two channels disagree in phase they cancel, and what cancels first is the
/// treble, which is why a mono capture of a stereo effect sounds duller than the source.
/// </summary>
public sealed class ChannelFold : ISampleProvider
{
    private const float Minus3dB = 0.70710678f;

    private readonly ISampleProvider source;
    private readonly int inCh;
    private readonly int outCh;
    private readonly float[][] matrix;
    private float[] buf = Array.Empty<float>();

    public WaveFormat WaveFormat { get; }

    public ChannelFold(ISampleProvider source, int outChannels)
    {
        if (outChannels is not (1 or 2))
            throw new ArgumentOutOfRangeException(nameof(outChannels), "Only mono and stereo are supported.");

        this.source = source;
        inCh = source.WaveFormat.Channels;
        outCh = outChannels;
        matrix = MatrixFor(inCh, outCh);
        WaveFormat = WaveFormat.CreateIeeeFloatWaveFormat(source.WaveFormat.SampleRate, outCh);
    }

    /// <summary>
    /// Weights as [outputChannel][inputChannel], already scaled so stereo content arrives at unity.
    ///
    /// Channel count alone identifies the layout, because WAVE_FORMAT_EXTENSIBLE fixes the order for
    /// the standard masks: FL, FR, FC, LFE, BL, BR, SL, SR. It has to be enough — NAudio 2.3.0 does
    /// not expose ChannelMask on WaveFormatExtensible at all.
    /// </summary>
    internal static float[][] MatrixFor(int inCh, int outCh) =>
        outCh == 1 ? new[] { MonoWeights(inCh) } : StereoWeights(inCh);

    private static float[] MonoWeights(int inCh)
    {
        float[] w = inCh switch
        {
            1 => new[] { 1f },
            2 => new[] { 1f, 1f },
            3 => new[] { 1f, 1f, Minus3dB },                                             // FL FR FC
            4 => new[] { 1f, 1f, Minus3dB, Minus3dB },                                   // quad
            6 => new[] { 1f, 1f, Minus3dB, 0f, Minus3dB, Minus3dB },                     // 5.1, LFE dropped
            8 => new[] { 1f, 1f, Minus3dB, 0f, Minus3dB, Minus3dB, Minus3dB, Minus3dB }, // 7.1, LFE dropped
            _ => Flat(inCh),
        };

        // Divide by the front pair so a stereo source comes through at its own level.
        float div = inCh == 1 ? 1f : 2f;
        var scaled = new float[w.Length];
        for (int i = 0; i < w.Length; i++) scaled[i] = w[i] / div;
        return scaled;
    }

    private static float[][] StereoWeights(int inCh)
    {
        var l = new float[inCh];
        var r = new float[inCh];

        switch (inCh)
        {
            case 1: l[0] = 1f; r[0] = 1f; break;                                       // duplicate
            case 2: l[0] = 1f; r[1] = 1f; break;                                       // straight through
            case 3: l[0] = 1f; l[2] = Minus3dB; r[1] = 1f; r[2] = Minus3dB; break;     // FL FR FC
            case 4: l[0] = 1f; l[2] = Minus3dB; r[1] = 1f; r[3] = Minus3dB; break;     // FL FR BL BR
            case 6:                                                                     // FL FR FC LFE BL BR
                l[0] = 1f; l[2] = Minus3dB; l[4] = Minus3dB;
                r[1] = 1f; r[2] = Minus3dB; r[5] = Minus3dB;
                break;
            case 8:                                                                     // + SL SR
                l[0] = 1f; l[2] = Minus3dB; l[4] = Minus3dB; l[6] = Minus3dB;
                r[1] = 1f; r[2] = Minus3dB; r[5] = Minus3dB; r[7] = Minus3dB;
                break;
            default:
                // Unknown layout: no way to tell which side is which, so fold flat and duplicate.
                var m = MonoWeights(inCh);
                for (int i = 0; i < inCh; i++) { l[i] = m[i]; r[i] = m[i]; }
                break;
        }

        return new[] { l, r };
    }

    private static float[] Flat(int inCh)
    {
        var w = new float[inCh];
        for (int i = 0; i < inCh; i++) w[i] = 2f / inCh;   // the /2 below cancels back to a plain average
        return w;
    }

    public int Read(float[] buffer, int offset, int count)
    {
        int frames = count / outCh;
        int need = frames * inCh;
        if (buf.Length < need) buf = new float[need];

        int read = source.Read(buf, 0, need);
        int got = read / inCh;

        for (int f = 0; f < got; f++)
        {
            int b = f * inCh;
            int o = offset + f * outCh;
            for (int oc = 0; oc < outCh; oc++)
            {
                float[] w = matrix[oc];
                float sum = 0f;
                for (int c = 0; c < inCh; c++) sum += buf[b + c] * w[c];
                buffer[o + oc] = sum;
            }
        }

        return got * outCh;
    }
}
