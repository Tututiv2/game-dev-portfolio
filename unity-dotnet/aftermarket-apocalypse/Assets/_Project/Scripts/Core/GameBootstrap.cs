using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Guarantees the systems that must never be missing actually exist, in every
/// gameplay scene, without anyone having to remember to place them.
///
/// This is the second half of the fix for spec §13.15. The old project lost its
/// pause controller because a build step destroyed the GameObject carrying it,
/// and nothing noticed until a player pressed Esc on a shipped map. Anything
/// whose absence silently breaks the game is created here instead of being
/// trusted to scene setup.
///
/// It deliberately does NOT create gameplay content (spawners, managers with
/// scene references) — only the self-building, zero-configuration systems that
/// spec §2.2 already designed to work this way.
/// </summary>
public static class GameBootstrap
{
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    private static void OnGameStart()
    {
        SceneManager.sceneLoaded -= OnSceneLoaded;
        SceneManager.sceneLoaded += OnSceneLoaded;
        EnsureSystems(SceneManager.GetActiveScene());
    }

    private static void OnSceneLoaded(Scene scene, LoadSceneMode mode)
    {
        EnsureSystems(scene);
    }

    private static void EnsureSystems(Scene scene)
    {
        // Only gameplay scenes need these; a scene with no player is a menu.
        if (Object.FindFirstObjectByType<FPSController>() == null) return;

        GameFeel.EnsureExists();

        if (Object.FindFirstObjectByType<PauseMenu>() == null)
        {
            GameObject go = new GameObject("PauseController (auto)");
            go.AddComponent<PauseMenu>();
            Debug.Log("GameBootstrap: no PauseMenu in '" + scene.name + "', added one. " +
                      "Esc will pause even with no panel wired (spec §13.15).");
        }

        if (Object.FindFirstObjectByType<PowerUpManager>() == null)
        {
            GameObject go = new GameObject("PowerUpManager (auto)");
            go.AddComponent<PowerUpManager>();
        }

        if (Object.FindFirstObjectByType<GameFeedback>() == null)
        {
            GameObject go = new GameObject("GameFeedback (auto)");
            go.AddComponent<GameFeedback>();
        }

        AudioListenerGuard();
    }

    /// <summary>
    /// Spec §13.19: every gameplay scene shipped with THREE AudioListeners
    /// (Main Camera, WeaponCamera, Minimap Camera) and Unity warns about it every
    /// run. Keep the one on the main camera, disable the rest.
    /// </summary>
    private static void AudioListenerGuard()
    {
        AudioListener[] listeners = Object.FindObjectsByType<AudioListener>(FindObjectsSortMode.None);
        if (listeners.Length <= 1) return;

        AudioListener keep = null;
        if (Camera.main != null) keep = Camera.main.GetComponent<AudioListener>();
        if (keep == null) keep = listeners[0];

        int disabled = 0;
        foreach (AudioListener l in listeners)
        {
            if (l == null || l == keep) continue;
            l.enabled = false;
            disabled++;
        }
        if (disabled > 0)
            Debug.Log("GameBootstrap: disabled " + disabled + " extra AudioListener(s) (spec §13.19).");
    }
}
