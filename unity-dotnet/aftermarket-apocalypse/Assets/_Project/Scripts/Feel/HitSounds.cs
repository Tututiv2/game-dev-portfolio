using UnityEngine;

/// <summary>
/// Hit confirmation audio. Pure static — never attached to anything (spec §2.1).
/// All clips synthesized at runtime; the project ships no hit-sound files.
///
/// The headshot ding is spec §8.1's exact recipe:
///   (sin(2*pi*1568*t)*0.6 + sin(2*pi*2352*t)*0.28) * e^(-20t)
/// 1568 Hz with a partial at exactly 1.5x "reads as ding, not beep".
/// </summary>
public static class HitSounds
{
    private const int Rate = 22050;

    private static AudioSource src;
    private static AudioClip ding;
    private static AudioClip tick;

    private static void Build()
    {
        GameObject go = new GameObject("HitSounds");
        Object.DontDestroyOnLoad(go);
        src = go.AddComponent<AudioSource>();
        src.spatialBlend = 0f;
        src.playOnAwake = false;

        ding = MakeDing();
        tick = MakeTick();
    }

    /// <summary>Played on a headshot KILL, at volume 0.28 (spec §8.1).</summary>
    public static void Headshot()
    {
        if (src == null) Build();
        if (src != null && ding != null)
        {
            src.pitch = 1f;
            src.PlayOneShot(ding, 0.28f);
        }
    }

    /// <summary>Generic body hit marker.</summary>
    public static void Hit()
    {
        if (src == null) Build();
        if (src != null && tick != null)
        {
            src.pitch = Random.Range(0.95f, 1.05f);
            src.PlayOneShot(tick, 0.18f);
        }
    }

    private static AudioClip MakeDing()
    {
        int length = (int)(Rate * 0.28f);
        float[] data = new float[length];
        for (int i = 0; i < length; i++)
        {
            float t = (float)i / Rate;
            float env = Mathf.Exp(-t * 20f);
            data[i] = (Mathf.Sin(2f * Mathf.PI * 1568f * t) * 0.6f
                     + Mathf.Sin(2f * Mathf.PI * 2352f * t) * 0.28f) * env;
        }
        AudioClip clip = AudioClip.Create("HeadshotDing", length, 1, Rate, false);
        clip.SetData(data, 0);
        return clip;
    }

    private static AudioClip MakeTick()
    {
        int length = (int)(Rate * 0.06f);
        float[] data = new float[length];
        System.Random rng = new System.Random(4242);
        for (int i = 0; i < length; i++)
        {
            float t = (float)i / Rate;
            float env = Mathf.Exp(-t * 90f);
            data[i] = (Mathf.Sin(2f * Mathf.PI * 900f * t) * 0.5f
                     + (float)(rng.NextDouble() * 2.0 - 1.0) * 0.25f) * env;
        }
        AudioClip clip = AudioClip.Create("HitTick", length, 1, Rate, false);
        clip.SetData(data, 0);
        return clip;
    }
}
