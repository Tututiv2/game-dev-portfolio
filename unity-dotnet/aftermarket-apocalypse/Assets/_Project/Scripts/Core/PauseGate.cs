using UnityEngine;

/// <summary>
/// THE PAUSE CONTRACT (spec §2.3, trap #2).
///
/// Input still fires at timeScale 0, so Time.timeScale alone is NOT enough —
/// Input.GetKeyDown(KeyCode.F) would silently spend Essence while paused.
/// Every gameplay Update() must early-return on <see cref="IsPaused"/>.
///
/// This lives in Core, NOT on the pause UI, deliberately. In the old build the
/// flag was PauseMenu.GameIsPaused and PauseMenu.Awake() was what reset it —
/// so when the map builder's PruneOldLevel() destroyed the object carrying
/// PauseMenu (spec §13.15), the reset went with it. A static gate owned by Core
/// cannot be pruned out of a scene.
/// </summary>
public static class PauseGate
{
    public static bool IsPaused { get; private set; }

    /// <summary>Raised whenever the pause state actually changes.</summary>
    public static event System.Action<bool> Changed;

    public static void Set(bool paused)
    {
        if (IsPaused == paused) return;
        IsPaused = paused;
        Time.timeScale = paused ? 0f : 1f;
        if (Changed != null) Changed(paused);
    }

    /// <summary>
    /// Statics survive scene loads. Without this reset, pausing and quitting to
    /// the menu leaves the flag true and the NEXT match starts frozen (spec §2.3).
    /// SubsystemRegistration runs before the first scene loads, on every entry
    /// into play mode, whether or not domain reload is enabled.
    /// </summary>
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    private static void ResetOnPlay()
    {
        IsPaused = false;
        Changed = null;
        Time.timeScale = 1f;
    }
}
