using System.Collections;
using UnityEngine;

/// <summary>
/// The whole soundscape, synthesized (spec §9.1). All clips 22050 Hz mono, built
/// in Awake — before any FireCrackle.Start consumes them. The project ships
/// exactly five MP3s and none of them are ambience.
///
/// TWO SYNTH LAWS (spec §9.1). Both are encoded below, not just described:
///  1. Every looping clip is built from a WHOLE NUMBER OF CYCLES PER LOOP, or it
///     clicks at the seam. The wind swell is exactly 2 cycles, the rumble throb
///     is exactly 140 cycles in 4 s (35 Hz), the drone's partials are chosen so
///     the 0.7 Hz beat lands cleanly.
///  2. Any oscillator whose frequency CHANGES over time uses PHASE ACCUMULATION
///     (phase += 2*pi*f/rate), never sin(2*pi*f*t). The latter produces garbage
///     when f varies — that is what the round sting's 68->42 Hz sweep needs.
/// </summary>
public class AmbientAudio : MonoBehaviour
{
    public static AmbientAudio instance;

    private const int Rate = 22050;

    [Header("Levels")]
    public float windVolume = 0.35f;
    public float droneVolume = 0.13f;
    public float rumbleVolume = 0.3f;

    [Header("Rumble placement (spec §9.1)")]
    public Vector3 rumblePosition = new Vector3(130f, 10f, 20f);
    public float rumbleMinDistance = 40f;
    public float rumbleMaxDistance = 170f;

    /// <summary>Shared by every FireCrackle so barrels can play it at random
    /// offsets and never sync.</summary>
    public static AudioClip crackleClip;
    private static AudioClip roundStingClip;

    private AudioSource windSource;
    private AudioSource droneSource;
    private AudioSource rumbleSource;
    private static AudioSource stingSource;

    private float baseWindVolume;

    void Awake()
    {
        if (instance != null && instance != this) { Destroy(this); return; }
        instance = this;

        crackleClip = MakeCrackle();
        roundStingClip = MakeRoundSting();

        windSource = MakeSource("Wind", MakeWind(), windVolume, true, 0f);
        droneSource = MakeSource("Drone", MakeDrone(), droneVolume, true, 0f);

        rumbleSource = MakeSource("Rumble", MakeRumble(), rumbleVolume, true, 1f);
        rumbleSource.transform.position = rumblePosition;
        rumbleSource.rolloffMode = AudioRolloffMode.Linear;
        rumbleSource.minDistance = rumbleMinDistance;
        rumbleSource.maxDistance = rumbleMaxDistance;

        baseWindVolume = windVolume;

        GameObject stingGO = new GameObject("RoundSting");
        stingGO.transform.SetParent(transform);
        stingSource = stingGO.AddComponent<AudioSource>();
        stingSource.spatialBlend = 0f;
        stingSource.playOnAwake = false;
    }

    void Start()
    {
        StartCoroutine(GustRoutine());
    }

    void OnDestroy()
    {
        if (instance == this) instance = null;
    }

    AudioSource MakeSource(string name, AudioClip clip, float volume, bool loop, float spatialBlend)
    {
        GameObject go = new GameObject(name);
        go.transform.SetParent(transform);
        AudioSource s = go.AddComponent<AudioSource>();
        s.clip = clip;
        s.volume = volume;
        s.loop = loop;
        s.spatialBlend = spatialBlend;
        s.playOnAwake = false;
        s.Play();
        return s;
    }

    /// <summary>The deep boom that says "here they come".</summary>
    public static void PlayRoundSting()
    {
        if (stingSource == null || roundStingClip == null) return;
        stingSource.PlayOneShot(roundStingClip, 0.8f);
    }

    /// <summary>
    /// Gusts every 20-40 s: a 3.5 s half-sine swell to 1.9x, during which every
    /// particle system named Ash* speeds up to match.
    /// </summary>
    IEnumerator GustRoutine()
    {
        while (true)
        {
            yield return new WaitForSeconds(Random.Range(20f, 40f));

            ParticleSystem[] ash = FindAshSystems();
            float t = 0f;
            const float duration = 3.5f;
            while (t < duration)
            {
                t += Time.deltaTime;
                float swell = Mathf.Sin(Mathf.PI * (t / duration));       // 0 -> 1 -> 0
                float factor = Mathf.Lerp(1f, 1.9f, swell);
                if (windSource != null) windSource.volume = baseWindVolume * factor;
                foreach (ParticleSystem ps in ash)
                {
                    if (ps == null) continue;
                    ParticleSystem.MainModule main = ps.main;
                    main.simulationSpeed = factor;
                }
                yield return null;
            }
            if (windSource != null) windSource.volume = baseWindVolume;
            foreach (ParticleSystem ps in ash)
            {
                if (ps == null) continue;
                ParticleSystem.MainModule main = ps.main;
                main.simulationSpeed = 1f;
            }
        }
    }

    ParticleSystem[] FindAshSystems()
    {
        var all = FindObjectsByType<ParticleSystem>(FindObjectsSortMode.None);
        var list = new System.Collections.Generic.List<ParticleSystem>();
        foreach (ParticleSystem ps in all)
            if (ps != null && ps.name.StartsWith("Ash")) list.Add(ps);
        return list.ToArray();
    }

    // ================= synthesis =================

    /// <summary>6 s. Brown noise (leaky integrator, step 0.04, leak 0.997) times a
    /// swell of exactly TWO cycles across the loop, so the seam is silent.</summary>
    static AudioClip MakeWind()
    {
        const float duration = 6f;
        int length = (int)(Rate * duration);
        float[] data = new float[length];
        System.Random rng = new System.Random(7001);
        float brown = 0f;

        for (int i = 0; i < length; i++)
        {
            brown = brown * 0.997f + (float)(rng.NextDouble() * 2.0 - 1.0) * 0.04f;
            float u = (float)i / length;                                  // normalised 0..1
            float swell = 0.55f + 0.45f * Mathf.Sin(4f * Mathf.PI * u);    // exactly 2 cycles
            data[i] = brown * swell;
        }
        Normalise(data, 0.9f);
        AudioClip clip = AudioClip.Create("Wind", length, 1, Rate, false);
        clip.SetData(data, 0);
        return clip;
    }

    /// <summary>2 s. A 2.5% hiss floor plus 35 additive pops of 40-400 samples,
    /// each decaying as e^(-k/45).</summary>
    static AudioClip MakeCrackle()
    {
        const float duration = 2f;
        int length = (int)(Rate * duration);
        float[] data = new float[length];
        System.Random rng = new System.Random(7002);

        for (int i = 0; i < length; i++)
            data[i] = (float)(rng.NextDouble() * 2.0 - 1.0) * 0.025f;

        for (int p = 0; p < 35; p++)
        {
            int start = rng.Next(0, length);
            int popLength = rng.Next(40, 400);
            float amp = 0.25f + (float)rng.NextDouble() * 0.55f;
            for (int k = 0; k < popLength && start + k < length; k++)
            {
                float decay = Mathf.Exp(-k / 45f);
                data[start + k] += (float)(rng.NextDouble() * 2.0 - 1.0) * amp * decay;
            }
        }
        Normalise(data, 0.95f);
        AudioClip clip = AudioClip.Create("Crackle", length, 1, Rate, false);
        clip.SetData(data, 0);
        return clip;
    }

    /// <summary>4 s. Brown noise plus a 35 Hz throb — 140 WHOLE cycles in 4 s, so
    /// it loops seamlessly — modulated by a 1-cycle LFO.</summary>
    static AudioClip MakeRumble()
    {
        const float duration = 4f;
        int length = (int)(Rate * duration);
        float[] data = new float[length];
        System.Random rng = new System.Random(7003);
        float brown = 0f;

        for (int i = 0; i < length; i++)
        {
            brown = brown * 0.996f + (float)(rng.NextDouble() * 2.0 - 1.0) * 0.05f;
            float u = (float)i / length;
            float throb = Mathf.Sin(2f * Mathf.PI * 140f * u);        // 140 cycles per loop = 35 Hz
            float lfo = 0.6f + 0.4f * Mathf.Sin(2f * Mathf.PI * u);   // exactly 1 cycle
            data[i] = (brown * 0.7f + throb * 0.3f) * lfo;
        }
        Normalise(data, 0.9f);
        AudioClip clip = AudioClip.Create("Rumble", length, 1, Rate, false);
        clip.SetData(data, 0);
        return clip;
    }

    /// <summary>8 s. Three detuned partials whose beat frequency is 0.7 Hz —
    /// 55.7 - 55.0. The detune IS the sound; perfect unison would be dead.</summary>
    static AudioClip MakeDrone()
    {
        const float duration = 8f;
        int length = (int)(Rate * duration);
        float[] data = new float[length];

        for (int i = 0; i < length; i++)
        {
            float t = (float)i / Rate;
            data[i] = Mathf.Sin(2f * Mathf.PI * 55.0f * t) * 0.35f
                    + Mathf.Sin(2f * Mathf.PI * 55.7f * t) * 0.30f
                    + Mathf.Sin(2f * Mathf.PI * 65.4f * t) * 0.18f;
        }
        CrossfadeTail(data, (int)(Rate * 0.25f));   // law 1: these are not whole cycles, so crossfade
        AudioClip clip = AudioClip.Create("Drone", length, 1, Rate, false);
        clip.SetData(data, 0);
        return clip;
    }

    /// <summary>1.4 s. A PHASE-ACCUMULATED sweep from 68 Hz down to 42 Hz over
    /// 1.2 s, times e^(-2.2t), plus a noise slap at e^(-26t).
    /// sin(2*pi*f*t) here would produce garbage — see synth law 2.</summary>
    static AudioClip MakeRoundSting()
    {
        const float duration = 1.4f;
        int length = (int)(Rate * duration);
        float[] data = new float[length];
        System.Random rng = new System.Random(7004);

        float phase = 0f;
        for (int i = 0; i < length; i++)
        {
            float t = (float)i / Rate;
            float sweepT = Mathf.Clamp01(t / 1.2f);
            float freq = Mathf.Lerp(68f, 42f, sweepT);

            phase += 2f * Mathf.PI * freq / Rate;          // PHASE ACCUMULATION

            float body = Mathf.Sin(phase) * Mathf.Exp(-t * 2.2f);
            float slap = (float)(rng.NextDouble() * 2.0 - 1.0) * 0.35f * Mathf.Exp(-t * 26f);
            data[i] = body * 0.9f + slap;
        }
        AudioClip clip = AudioClip.Create("RoundSting", length, 1, Rate, false);
        clip.SetData(data, 0);
        return clip;
    }

    // ---- helpers ----

    static void Normalise(float[] data, float peak)
    {
        float max = 0f;
        for (int i = 0; i < data.Length; i++) max = Mathf.Max(max, Mathf.Abs(data[i]));
        if (max <= 0.0001f) return;
        float scale = peak / max;
        for (int i = 0; i < data.Length; i++) data[i] *= scale;
    }

    /// <summary>Synth law 1's other half: when a clip is NOT a whole number of
    /// cycles, fade its tail into its head so the loop seam is inaudible.</summary>
    static void CrossfadeTail(float[] data, int fadeSamples)
    {
        if (fadeSamples <= 0 || fadeSamples * 2 >= data.Length) return;
        for (int i = 0; i < fadeSamples; i++)
        {
            float k = (float)i / fadeSamples;
            int tail = data.Length - fadeSamples + i;
            data[tail] = data[tail] * (1f - k) + data[i] * k;
        }
    }
}
