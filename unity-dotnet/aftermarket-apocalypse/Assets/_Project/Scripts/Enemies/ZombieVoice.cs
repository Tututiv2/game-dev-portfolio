using UnityEngine;

/// <summary>
/// Synthesized zombie groans (spec §5.4). Three clips, 22050 Hz mono, base
/// frequencies 68 / 82 / 98 Hz, durations 2.1 / 2.4 / 2.7 s, deterministic seeds
/// so every playthrough hears the same three voices.
///
/// SYNTH LAW (spec §5.4, learned the hard way): version 1 used sine vibrato and
/// perfect integer harmonics and "sounded like bees". A voice needs
///   random-walk pitch + DETUNED partials + filtered noise + burst envelopes.
/// Never sine-vibrato. Never integer harmonics. The 1.98 / 3.07 / 4.13
/// multipliers below are deliberately not 2 / 3 / 4.
/// </summary>
public class ZombieVoice : MonoBehaviour
{
    public float pitchScale = 1f;     // crawlers get 0.7

    private const int Rate = 22050;
    private static AudioClip[] shared;

    private AudioSource src;
    private float nextGroanTime;

    void Awake()
    {
        if (shared == null) BuildClips();

        src = gameObject.AddComponent<AudioSource>();
        src.playOnAwake = false;
        src.spatialBlend = 1f;                              // 3D
        src.rolloffMode = AudioRolloffMode.Linear;
        src.minDistance = 2f;
        src.maxDistance = 24f;
        src.volume = 0.55f;

        nextGroanTime = Time.time + Random.Range(0.5f, 4f);
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;
        if (Time.time < nextGroanTime) return;

        nextGroanTime = Time.time + Random.Range(4f, 11f);
        Groan(Random.Range(0.82f, 1.18f));
    }

    public void AttackGrowl()
    {
        Groan(Random.Range(1.2f, 1.4f));
    }

    void Groan(float pitch)
    {
        if (src == null || shared == null) return;
        src.pitch = pitch * pitchScale;
        src.PlayOneShot(shared[Random.Range(0, shared.Length)]);
    }

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    static void ResetStatics()
    {
        shared = null;
    }

    static void BuildClips()
    {
        shared = new AudioClip[3];
        shared[0] = MakeGroan(68f, 2.1f, 1001);
        shared[1] = MakeGroan(82f, 2.4f, 1002);
        shared[2] = MakeGroan(98f, 2.7f, 1003);
    }

    static AudioClip MakeGroan(float baseFreq, float duration, int seed)
    {
        int length = (int)(Rate * duration);
        float[] data = new float[length];
        System.Random rng = new System.Random(seed);

        // --- burst envelope: 2-3 bursts of 0.50-1.05 s with 0.15-0.40 s gaps ---
        int burstCount = 2 + (rng.NextDouble() < 0.5 ? 0 : 1);
        float[] burstStart = new float[burstCount];
        float[] burstLength = new float[burstCount];
        float cursor = 0f;
        for (int b = 0; b < burstCount; b++)
        {
            burstStart[b] = cursor;
            burstLength[b] = 0.50f + (float)rng.NextDouble() * 0.55f;
            cursor += burstLength[b] + 0.15f + (float)rng.NextDouble() * 0.25f;
        }

        float phase = 0f;
        float freq = baseFreq;
        float freqTarget = baseFreq;
        float lp = 0f;

        for (int i = 0; i < length; i++)
        {
            // Random-walk pitch: a new target 50x/s, glided toward slowly.
            if (i % 441 == 0)
                freqTarget = baseFreq * (0.82f + (float)rng.NextDouble() * 0.40f);   // 0.82 .. 1.22
            freq = Mathf.Lerp(freq, freqTarget, 0.004f);

            // PHASE ACCUMULATION, not sin(2*pi*f*t) — mandatory whenever f varies
            // over time (spec §9.1 synth law 2).
            phase += 2f * Mathf.PI * freq / Rate;

            float voice = Mathf.Sin(phase) * 0.5f
                        + Mathf.Sin(phase * 1.98f) * 0.26f      // DETUNED, never integer
                        + Mathf.Sin(phase * 3.07f) * 0.12f
                        + Mathf.Sin(phase * 4.13f) * 0.05f;

            // Filtered noise = breath.
            float white = (float)(rng.NextDouble() * 2.0 - 1.0);
            lp = lp * 0.92f + white * 0.08f;

            float t = (float)i / Rate;
            float env = 0f;
            for (int b = 0; b < burstCount; b++)
            {
                float bt = t - burstStart[b];
                if (bt < 0f || bt > burstLength[b]) continue;
                env = Mathf.Max(env, Mathf.Sin(Mathf.PI * bt / burstLength[b]));
            }

            data[i] = (voice * 0.6f + lp * 0.85f) * env * 0.55f;
        }

        AudioClip clip = AudioClip.Create("Groan" + seed, length, 1, Rate, false);
        clip.SetData(data, 0);
        return clip;
    }
}
