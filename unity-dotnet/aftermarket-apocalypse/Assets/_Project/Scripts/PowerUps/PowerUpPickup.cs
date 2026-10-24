using UnityEngine;

/// <summary>The floating orb. Spins 90 deg/s, bobs 0.25 m at 2 rad/s, lives 20 s (spec §7).</summary>
public class PowerUpPickup : MonoBehaviour
{
    public PowerUpType type;
    public float lifetime = 20f;

    private Vector3 basePosition;
    private float age;
    private bool collected;

    void Start()
    {
        basePosition = transform.position;
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;

        age += Time.deltaTime;
        transform.Rotate(Vector3.up, 90f * Time.deltaTime, Space.World);
        transform.position = basePosition + Vector3.up * (Mathf.Sin(age * 2f) * 0.25f);

        if (age >= lifetime) Destroy(gameObject);
    }

    void OnTriggerEnter(Collider other)
    {
        if (collected) return;
        if (other.GetComponentInParent<FPSController>() == null) return;

        collected = true;
        if (PowerUpManager.instance != null) PowerUpManager.instance.Collect(type);
        Destroy(gameObject);
    }
}
