using UnityEngine;

/// <summary>
/// Figure-8 weapon bob, velocity-driven (spec §4.2).
///
/// LAW: the Y axis runs at 2x the speed field, so speed values must be roughly
/// HALF what a naive up-down bob would use. The defaults below are post-tuning
/// after "way too fast" feedback.
///
/// WARNING carried from spec Part I's "READ FIRST" box: these tuned values were
/// never re-applied to the scene objects, so every shipped scene ran 14/18
/// instead of 3.5/5.5 — 4x the intended speed — because a serialized inspector
/// value always beats a script default. Do not hand-edit these per instance.
/// Phase 2 moves them into a ScriptableObject so there is exactly one source of
/// truth and this class of bug becomes impossible.
///
/// Amplitude comes from real CharacterController.velocity, not from input, so it
/// fades correctly when you walk into a wall.
///
/// Spec §4.2 / trap #7: this writes localPosition as a DELTA. WeaponSway writes
/// localRotation ABSOLUTELY and must live on a different GameObject.
/// </summary>
public class WeaponBob : MonoBehaviour
{
    [Header("Walk")]
    public float walkBobSpeed = 3.5f;
    public float walkBobAmount = 0.03f;

    [Header("Sprint")]
    public float sprintBobSpeed = 5.5f;
    public float sprintBobAmount = 0.055f;

    [Header("Shape")]
    [Tooltip("X amplitude as a fraction of Y.")]
    public float sideSwayRatio = 0.6f;
    public float smoothing = 8f;
    public float landDip = 0.06f;

    private CharacterController controller;
    private FPSController player;
    private float timer;
    private float currentAmount;
    private Vector3 lastOffset;
    private float landOffset;
    private bool wasGrounded = true;

    void Start()
    {
        player = FindFirstObjectByType<FPSController>();
        if (player != null) controller = player.GetComponent<CharacterController>();
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;

        float planarSpeed = 0f;
        bool grounded = true;
        if (controller != null)
        {
            Vector3 v = controller.velocity;
            v.y = 0f;
            planarSpeed = v.magnitude;
            grounded = controller.isGrounded;
        }

        bool sprinting = player != null && player.isSprinting;
        float speed = sprinting ? sprintBobSpeed : walkBobSpeed;
        float targetAmount = (planarSpeed > 0.15f && grounded)
            ? (sprinting ? sprintBobAmount : walkBobAmount) * Mathf.Clamp01(planarSpeed / 6f)
            : 0f;

        currentAmount = Mathf.Lerp(currentAmount, targetAmount, Time.deltaTime * smoothing);
        if (targetAmount > 0f) timer += Time.deltaTime * speed;

        // Landing dip
        if (grounded && !wasGrounded) landOffset = -landDip;
        wasGrounded = grounded;
        landOffset = Mathf.Lerp(landOffset, 0f, Time.deltaTime * 8f);

        Vector3 offset = new Vector3(
            Mathf.Sin(timer) * currentAmount * sideSwayRatio,     // X at step rate
            Mathf.Sin(timer * 2f) * currentAmount + landOffset,   // Y at DOUBLE step rate
            0f);

        // DELTA, never absolute — this is what lets it compose safely with
        // WeaponAiming, which also writes localPosition.
        transform.localPosition += offset - lastOffset;
        lastOffset = offset;
    }
}
