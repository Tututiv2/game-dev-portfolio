using UnityEngine;

/// <summary>
/// Mouse-driven weapon sway (spec §4.2).
///
/// Writes localRotation ABSOLUTELY, so per trap #7 it must sit on a DIFFERENT
/// GameObject from WeaponAiming (which also writes localRotation). The rig in
/// spec §1.3 puts WeaponSway on WeaponHolder and WeaponAiming on the gun itself.
///
/// Both values were halved after "the gun snapped around too fast" feedback. As
/// with WeaponBob, the shipped scenes still carried the old 2 / 8 because the
/// tuning was never re-applied to the instances.
/// </summary>
public class WeaponSway : MonoBehaviour
{
    public float swayMultiplier = 1.1f;
    public float smooth = 4.5f;

    private Quaternion baseRotation;

    void Start()
    {
        baseRotation = transform.localRotation;
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;

        Vector2 look = GameInput.Look;
        float mouseX = look.x * swayMultiplier;
        float mouseY = look.y * swayMultiplier;

        Quaternion rotX = Quaternion.AngleAxis(-mouseY, Vector3.right);
        Quaternion rotY = Quaternion.AngleAxis(mouseX, Vector3.up);
        Quaternion target = baseRotation * rotX * rotY;

        transform.localRotation = Quaternion.Slerp(transform.localRotation, target, smooth * Time.deltaTime);
    }
}
