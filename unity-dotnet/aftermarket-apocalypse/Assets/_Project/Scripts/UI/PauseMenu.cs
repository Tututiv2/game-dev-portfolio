using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Esc to pause (spec §2.3).
///
/// The state itself lives in <see cref="PauseGate"/>, in Core, NOT here. That is
/// the structural fix for spec §13.15 — "you cannot pause on Map2 or Map3", the
/// most user-visible bug in the old project. The map builders' PruneOldLevel()
/// destroyed the System object carrying PauseMenu, so the panels survived with
/// nothing driving them and Esc was dead on both real maps. With the flag and
/// its reset owned by a static in Core, and GameBootstrap guaranteeing a
/// controller exists, deleting a scene object can no longer disable pausing.
/// </summary>
public class PauseMenu : MonoBehaviour
{
    [Tooltip("Optional. The panel to show while paused.")]
    public GameObject pauseMenuPanel;

    [Tooltip("Optional. Hidden while paused.")]
    public GameObject hudRoot;

    // pauseKey was a KeyCode field here. Removed with the Input System migration —
    // the binding is in GameInput now, and leaving a dead inspector field behind
    // would advertise a knob that turns nothing.

    void Start()
    {
        // Never inherit a paused state from a previous scene.
        PauseGate.Set(false);
        if (pauseMenuPanel != null) pauseMenuPanel.SetActive(false);
    }

    void Update()
    {
        if (GameInput.PausePressedThisFrame) Toggle();
    }

    public void Toggle()
    {
        SetPaused(!PauseGate.IsPaused);
    }

    public void SetPaused(bool paused)
    {
        PauseGate.Set(paused);

        if (pauseMenuPanel != null) pauseMenuPanel.SetActive(paused);
        if (hudRoot != null) hudRoot.SetActive(!paused);

        Cursor.lockState = paused ? CursorLockMode.None : CursorLockMode.Locked;
        Cursor.visible = paused;
    }

    // ---- button hooks ----

    public void Resume()
    {
        SetPaused(false);
    }

    public void Restart()
    {
        PauseGate.Set(false);
        SceneManager.LoadScene(SceneManager.GetActiveScene().name);
    }

    public void QuitToMenu()
    {
        // Without this the static stays true and the NEXT match starts frozen.
        // PauseGate also resets itself on entering play mode, so this is belt
        // and braces rather than the only guard.
        PauseGate.Set(false);
        if (Application.CanStreamedLevelBeLoaded("MainMenu")) SceneManager.LoadScene("MainMenu");
        else Debug.LogWarning("PauseMenu: no scene named 'MainMenu' is in the build settings.");
    }

    public void QuitGame()
    {
        Application.Quit();
    }
}
