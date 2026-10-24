using UnityEngine;

/// <summary>
/// Movement and look (spec §3.1). Legacy Input Manager, as the old build used —
/// Active Input Handling is set to "Both" so the Input System package can drive
/// UI in parallel. Phase 2 migrates gameplay input properly.
///
/// Recoil is a CAMERA-VISUAL offset only: it is subtracted when composing the
/// camera rotation and never fed back into rotationX, so it does not permanently
/// alter aim. It does still deflect shots while decaying, because the bullet ray
/// originates at the camera — that is intended.
/// </summary>
[RequireComponent(typeof(CharacterController))]
public class FPSController : MonoBehaviour
{
    [Header("Movement")]
    public float walkSpeed = 6f;
    public float sprintSpeed = 10f;
    public float jumpForce = 8f;
    public float gravity = 20f;

    [Header("Look")]
    public float lookSpeed = 2.0f;
    public float lookXLimit = 85f;

    [Header("Recoil (visual only)")]
    public float recoilSnappiness = 10f;
    public float recoilReturnSpeed = 15f;

    [Header("Runtime state")]
    [Tooltip("Written by SlowZone.")]
    public float speedMultiplier = 1f;
    [Tooltip("Read by GunSystem (sprint-to-fire delay) and GameFeel (sprint FOV).")]
    public bool isSprinting = false;

    [Header("References")]
    public Transform cameraHolder;

    private CharacterController controller;
    private Vector3 velocity;
    private float rotationX = 0f;

    // recoil, in degrees
    private float currentRecoilX, currentRecoilY;
    private float targetRecoilX, targetRecoilY;

    void Awake()
    {
        controller = GetComponent<CharacterController>();
        if (cameraHolder == null && Camera.main != null) cameraHolder = Camera.main.transform.parent;
        if (cameraHolder == null) cameraHolder = transform;
    }

    void Start()
    {
        Cursor.lockState = CursorLockMode.Locked;
        Cursor.visible = false;
    }

    void Update()
    {
        // Spec §2.3 / trap #2: input still fires at timeScale 0.
        if (PauseGate.IsPaused) return;

        HandleLook();
        HandleMove();
    }

    void HandleMove()
    {
        // Sprinting is blocked while firing (spec §3.1).
        isSprinting = GameInput.Sprinting && !GameInput.Firing;

        float speed = isSprinting ? sprintSpeed : walkSpeed;
        if (isSprinting && PlayerPerks.instance != null && PlayerPerks.instance.HasPerk(PerkType.StaminUp))
            speed = sprintSpeed * PlayerPerks.instance.staminUpMultiplier;   // 10 -> 13 m/s
        speed *= speedMultiplier;

        Vector2 move = GameInput.Move;
        Vector3 input = transform.right * move.x + transform.forward * move.y;
        if (input.sqrMagnitude > 1f) input.Normalize();

        if (controller.isGrounded)
        {
            if (velocity.y < 0f) velocity.y = -2f;      // keep it pinned to the floor
            if (GameInput.JumpPressedThisFrame) velocity.y = jumpForce;
        }
        velocity.y -= gravity * Time.deltaTime;          // gravity applied manually

        Vector3 motion = input * speed;
        motion.y = velocity.y;
        controller.Move(motion * Time.deltaTime);
    }

    void HandleLook()
    {
        Vector2 look = GameInput.Look;
        rotationX -= look.y * lookSpeed;
        rotationX = Mathf.Clamp(rotationX, -lookXLimit, lookXLimit);
        transform.Rotate(Vector3.up * look.x * lookSpeed);

        // Recoil decays toward zero; the CURRENT value chases the TARGET.
        targetRecoilX = Mathf.Lerp(targetRecoilX, 0f, recoilReturnSpeed * Time.deltaTime);
        targetRecoilY = Mathf.Lerp(targetRecoilY, 0f, recoilReturnSpeed * Time.deltaTime);
        currentRecoilX = Mathf.Lerp(currentRecoilX, targetRecoilX, recoilSnappiness * Time.deltaTime);
        currentRecoilY = Mathf.Lerp(currentRecoilY, targetRecoilY, recoilSnappiness * Time.deltaTime);

        // Composed, not accumulated — rotationX is never written by recoil.
        if (cameraHolder != null)
            cameraHolder.localRotation = Quaternion.Euler(rotationX - currentRecoilX, currentRecoilY, 0f);
    }

    /// <summary>
    /// Called by GunSystem per shot. The old signature took a third "tilt"
    /// argument that was accepted and silently ignored (spec §3.1) — dropped
    /// rather than carried forward as a lie.
    /// </summary>
    public void AddRecoil(float up, float side)
    {
        targetRecoilX += up;
        targetRecoilY += Random.Range(-side, side);
    }
}
