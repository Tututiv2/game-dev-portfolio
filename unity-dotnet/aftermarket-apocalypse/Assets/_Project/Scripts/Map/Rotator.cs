using UnityEngine;

/// <summary>Spins and optionally bobs. Carousels, wheels, floating props (spec §9).</summary>
public class Rotator : MonoBehaviour
{
    public Vector3 degreesPerSecond = new Vector3(0f, 45f, 0f);
    public float bobHeight = 0.15f;
    public float bobSpeed = 1.5f;

    [Tooltip("Ferris wheels and carousels only start once the power is on.")]
    public bool requirePower = false;

    private Vector3 basePosition;
    private float age;

    void Start()
    {
        basePosition = transform.localPosition;
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;
        if (requirePower && !PowerSwitch.IsPowered) return;

        age += Time.deltaTime;
        transform.Rotate(degreesPerSecond * Time.deltaTime, Space.Self);

        if (bobHeight > 0f)
            transform.localPosition = basePosition + Vector3.up * (Mathf.Sin(age * bobSpeed) * bobHeight);
    }
}
