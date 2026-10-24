using UnityEngine;
using UnityEngine.InputSystem;

/// <summary>
/// The one place gameplay reads input. Closes the Phase 2 Input System migration.
///
/// <para><b>Why a facade instead of rewriting eight files.</b> The legacy calls were
/// spread across <c>FPSController</c>, <c>GunSystem</c>, <c>WeaponInventory</c>,
/// <c>WeaponSway</c>, <c>WeaponAiming</c>, <c>InteractionSystem</c>,
/// <c>GobblegumManager</c> and <c>PauseMenu</c>. Giving each of them its own
/// <c>InputAction</c> fields would have meant eight sets of enable/disable
/// lifecycle to get wrong and eight places to rebind a key. One facade means the
/// call sites stay one-liners and every binding lives at one address — the same
/// reasoning that puts tuning in <c>RoundRules</c> rather than inspector fields.</para>
///
/// <para><b>Why the actions are built in code and not loaded from
/// <c>Assets/InputSystem_Actions.inputactions</c>.</b> That asset is Unity's
/// template. Nothing in this project reads it — the only reference to its GUID is
/// <c>EditorBuildSettings.asset</c>, where it sits as the UI module default — and
/// it does not define Reload, Aim, Pause or the weapon slots that gameplay needs.
/// Loading it at runtime would also need either a serialized reference on a
/// component (nothing in the scene has a <c>PlayerInput</c>) or a Resources copy.
/// Code-defined actions need no scene wiring, cannot lose a reference, and can be
/// read by a test. <b>The asset is still not the gameplay input source; do not
/// edit it expecting the game to change.</b></para>
///
/// <para><b>The 0.1 is not arbitrary.</b> Legacy <c>Input.GetAxis("Mouse X")</c>
/// returns the frame's mouse delta multiplied by the axis sensitivity in
/// <c>ProjectSettings/InputManager.asset</c>, which is <b>0.1</b> for Mouse X and
/// Mouse Y. <c>Mouse.delta</c> in the new system is raw pixels. Reading it
/// straight would have made looking about ten times faster and silently
/// invalidated every <c>lookSpeed</c> and <c>swayMultiplier</c> already tuned in
/// this project.</para>
/// </summary>
public static class GameInput
{
    /// <summary>
    /// Converts new-system raw mouse pixels to the legacy axis scale. See the class
    /// summary — this is read from InputManager.asset, not chosen.
    /// </summary>
    public const float MouseSensitivityCompensation = 0.1f;

    /// <summary>Weapon slots 1..N on the number row.</summary>
    public const int SlotCount = 5;

    static InputActionMap s_Map;
    static InputAction s_Move, s_Look, s_Fire, s_Aim, s_Jump, s_Sprint;
    static InputAction s_Reload, s_Interact, s_Pause, s_Scroll;
    static InputAction[] s_Slots;

    // ---- reads ------------------------------------------------------------

    /// <summary>Strafe/forward, -1..1 per axis. Replaces Horizontal / Vertical.</summary>
    public static Vector2 Move { get { Ensure(); return s_Move.ReadValue<Vector2>(); } }

    /// <summary>Mouse or stick look, already scaled to the legacy axis range.</summary>
    public static Vector2 Look
    {
        get
        {
            Ensure();
            return s_Look.ReadValue<Vector2>() * MouseSensitivityCompensation;
        }
    }

    public static bool Firing { get { Ensure(); return s_Fire.IsPressed(); } }
    public static bool FirePressedThisFrame { get { Ensure(); return s_Fire.WasPressedThisFrame(); } }
    public static bool Aiming { get { Ensure(); return s_Aim.IsPressed(); } }
    public static bool JumpPressedThisFrame { get { Ensure(); return s_Jump.WasPressedThisFrame(); } }
    public static bool Sprinting { get { Ensure(); return s_Sprint.IsPressed(); } }
    public static bool ReloadPressedThisFrame { get { Ensure(); return s_Reload.WasPressedThisFrame(); } }
    public static bool InteractPressedThisFrame { get { Ensure(); return s_Interact.WasPressedThisFrame(); } }
    public static bool PausePressedThisFrame { get { Ensure(); return s_Pause.WasPressedThisFrame(); } }

    /// <summary>
    /// Scroll wheel, normalised to the legacy <c>Input.GetAxis("Mouse ScrollWheel")</c>
    /// range. The new system reports raw ticks (120 per notch on Windows), the old
    /// one reported roughly +/-0.1, and <c>WeaponInventory</c> compares against a
    /// threshold tuned for the old scale.
    /// </summary>
    public static float WeaponScroll
    {
        get
        {
            Ensure();
            float raw = s_Scroll.ReadValue<Vector2>().y;
            return Mathf.Approximately(raw, 0f) ? 0f : Mathf.Sign(raw) * 0.1f;
        }
    }

    /// <summary>Number-row key <paramref name="index"/> (0-based) pressed this frame.</summary>
    public static bool SlotPressedThisFrame(int index)
    {
        Ensure();
        if (index < 0 || index >= s_Slots.Length) return false;
        return s_Slots[index].WasPressedThisFrame();
    }

    /// <summary>
    /// "Any key or click" — for dismissing a modal like the §15.8.1 Sacred
    /// reveal, where every input should mean "yes, I have seen it".
    ///
    /// <para>It reuses the gameplay actions already bound rather than adding a
    /// new one, because a fresh action here would need its own bindings and this
    /// file's whole point is that bindings live at ONE address. Fire, jump,
    /// interact and pause together cover mouse, keyboard and gamepad.</para>
    ///
    /// <para>It deliberately does NOT read <c>UnityEngine.Input.anyKeyDown</c>:
    /// <c>activeInputHandler</c> is 1, so that API throws at runtime.</para>
    /// </summary>
    public static bool AnyDismissPressed()
    {
        Ensure();
        return s_Fire.WasPressedThisFrame()
            || s_Jump.WasPressedThisFrame()
            || s_Interact.WasPressedThisFrame()
            || s_Pause.WasPressedThisFrame();
    }

    // ---- construction -----------------------------------------------------

    /// <summary>
    /// Lazy, because a static holding live InputActions cannot survive a domain
    /// reload and there is no bootstrap object guaranteed to run before the first
    /// Update. Cheap after the first call.
    /// </summary>
    static void Ensure()
    {
        if (s_Map != null && s_Map.enabled) return;
        if (s_Map != null) { s_Map.Enable(); return; }

        s_Map = new InputActionMap("Gameplay");

        s_Move = s_Map.AddAction("Move", InputActionType.Value);
        s_Move.AddCompositeBinding("2DVector")
            .With("Up", "<Keyboard>/w").With("Down", "<Keyboard>/s")
            .With("Left", "<Keyboard>/a").With("Right", "<Keyboard>/d");
        s_Move.AddCompositeBinding("2DVector")
            .With("Up", "<Keyboard>/upArrow").With("Down", "<Keyboard>/downArrow")
            .With("Left", "<Keyboard>/leftArrow").With("Right", "<Keyboard>/rightArrow");
        s_Move.AddBinding("<Gamepad>/leftStick");

        s_Look = s_Map.AddAction("Look", InputActionType.Value, "<Mouse>/delta");
        // Sticks are -1..1 per frame while a mouse reports pixels, so a raw stick
        // binding here would be imperceptibly slow once the 0.1 compensation is
        // applied. Scale it back up to something in the same league.
        s_Look.AddBinding("<Gamepad>/rightStick").WithProcessor("scaleVector2(x=12,y=12)");

        s_Fire = s_Map.AddAction("Fire", InputActionType.Button, "<Mouse>/leftButton");
        s_Fire.AddBinding("<Gamepad>/rightTrigger");

        s_Aim = s_Map.AddAction("Aim", InputActionType.Button, "<Mouse>/rightButton");
        s_Aim.AddBinding("<Gamepad>/leftTrigger");

        s_Jump = s_Map.AddAction("Jump", InputActionType.Button, "<Keyboard>/space");
        s_Jump.AddBinding("<Gamepad>/buttonSouth");

        s_Sprint = s_Map.AddAction("Sprint", InputActionType.Button, "<Keyboard>/leftShift");
        s_Sprint.AddBinding("<Gamepad>/leftStickPress");

        s_Reload = s_Map.AddAction("Reload", InputActionType.Button, "<Keyboard>/r");
        s_Reload.AddBinding("<Gamepad>/buttonWest");

        s_Interact = s_Map.AddAction("Interact", InputActionType.Button, "<Keyboard>/f");
        s_Interact.AddBinding("<Gamepad>/buttonNorth");

        s_Pause = s_Map.AddAction("Pause", InputActionType.Button, "<Keyboard>/escape");
        s_Pause.AddBinding("<Gamepad>/start");

        s_Scroll = s_Map.AddAction("WeaponScroll", InputActionType.Value, "<Mouse>/scroll");

        s_Slots = new InputAction[SlotCount];
        for (int i = 0; i < SlotCount; i++)
            s_Slots[i] = s_Map.AddAction($"Slot{i + 1}", InputActionType.Button,
                                         $"<Keyboard>/{i + 1}");

        s_Map.Enable();
    }
}
