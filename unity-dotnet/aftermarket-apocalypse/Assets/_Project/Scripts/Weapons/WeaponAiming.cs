using UnityEngine;

/// <summary>
/// Hip / ADS / sprint poses, plus the reload flourish (spec §4.2).
/// Lerps localPosition and localRotation toward the target pose at
/// Time.deltaTime * 8. Composes with WeaponBob because bob applies a delta.
/// </summary>
public class WeaponAiming : MonoBehaviour
{
    [Header("Poses")]
    public Vector3 hipPosition = new Vector3(0.18f, -0.12f, 0.35f);
    public Vector3 hipRotation = Vector3.zero;
    public Vector3 adsPosition = new Vector3(0f, -0.06f, 0.28f);
    public Vector3 adsRotation = Vector3.zero;
    public Vector3 sprintPosition = new Vector3(0.22f, -0.18f, 0.3f);
    public Vector3 sprintRotation = new Vector3(0f, -25f, -12f);

    [Header("Reload flourish (spec §4.2)")]
    public float reloadPitch = 32f;
    public float reloadRoll = -26f;
    public float reloadDrop = 0.06f;

    public float poseSpeed = 8f;

    private GunSystem gun;
    private FPSController player;

    public bool IsAiming { get; private set; }

    void Start()
    {
        gun = GetComponent<GunSystem>();
        if (gun == null) gun = GetComponentInChildren<GunSystem>();
        player = FindFirstObjectByType<FPSController>();
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;

        bool sprinting = player != null && player.isSprinting;
        IsAiming = !sprinting && GameInput.Aiming;

        Vector3 targetPos = sprinting ? sprintPosition : (IsAiming ? adsPosition : hipPosition);
        Vector3 targetRot = sprinting ? sprintRotation : (IsAiming ? adsRotation : hipRotation);

        // Reload flourish rides on top of whatever pose is active.
        float tilt = gun != null ? gun.reloadTilt : 0f;
        if (tilt > 0f)
        {
            targetRot += new Vector3(reloadPitch * tilt, 0f, reloadRoll * tilt);
            targetPos += Vector3.down * (reloadDrop * tilt);
        }

        transform.localPosition = Vector3.Lerp(transform.localPosition, targetPos, Time.deltaTime * poseSpeed);
        transform.localRotation = Quaternion.Slerp(transform.localRotation, Quaternion.Euler(targetRot), Time.deltaTime * poseSpeed);
    }
}
