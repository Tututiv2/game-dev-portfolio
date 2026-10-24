using UnityEngine;

/// <summary>
/// Failing bulbs and slow pulses (spec §9).
/// Flicker: a random 0.15-1.0x of base intensity every 0.03-0.3 s.
/// Pulse:   0.3 + 0.7 * sin.
/// </summary>
[RequireComponent(typeof(Light))]
public class FlickerLight : MonoBehaviour
{
    public enum Mode { Flicker, Pulse }
    public Mode mode = Mode.Flicker;
    public float pulseSpeed = 1.4f;

    private Light lamp;
    private float baseIntensity;
    private float nextChange;
    private float age;

    void Awake()
    {
        lamp = GetComponent<Light>();
        baseIntensity = lamp.intensity;
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;

        if (mode == Mode.Pulse)
        {
            age += Time.deltaTime * pulseSpeed;
            lamp.intensity = baseIntensity * (0.3f + 0.7f * Mathf.Abs(Mathf.Sin(age)));
            return;
        }

        if (Time.time < nextChange) return;
        nextChange = Time.time + Random.Range(0.03f, 0.3f);
        lamp.intensity = baseIntensity * Random.Range(0.15f, 1f);
    }
}
