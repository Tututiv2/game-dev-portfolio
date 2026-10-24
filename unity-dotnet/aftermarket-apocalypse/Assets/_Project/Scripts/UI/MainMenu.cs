using TMPro;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// The front door. PLAY, and the three screens that hang off it.
///
/// <para>Until this existed there was no way into the game as a human. Four
/// scenes existed and the only route to any of them was an editor menu item
/// that opened the scene and pressed Play for you — fine for checking a screen,
/// useless as a game. Worse, the MAP was not in Build Settings at all, so
/// nothing could ever <c>LoadScene</c> it: a menu built before that was fixed
/// would have had a PLAY button that could only throw.</para>
///
/// <para>Every destination is checked with <c>CanStreamedLevelBeLoaded</c> at
/// Start and a button whose scene is missing is DISABLED and relabelled, the
/// same rule ScreenNav follows. A dead button that still looks clickable is
/// worse than one that admits it, and from inside a UI callback the exception
/// goes to a console nobody has open.</para>
/// </summary>
public class MainMenu : MonoBehaviour
{
    [Tooltip("Scene NAME, not path — this is what SceneManager.LoadScene takes.")]
    public string mapScene = "AshfallDepot";

    public TextMeshProUGUI characterLine;

    /// <summary>Set by the builder so the readout does not have to load the
    /// catalog itself; the menu is a front end, not an owner of content.</summary>
    public CharacterCatalog catalog;

    const string k_CharacterKey = "SelectedCharacter";   // written by CharacterSelect

    void Start()
    {
        RefreshCharacterLine();
    }

    /// <summary>
    /// Say who you are about to play as. The character choice lives in
    /// PlayerPrefs and was, until the first-person body, read by nothing at all —
    /// so showing it here is also the only confirmation that picking one did
    /// anything.
    /// </summary>
    public void RefreshCharacterLine()
    {
        if (characterLine == null) return;

        string id = PlayerPrefs.GetString(k_CharacterKey, "");
        string shown = "no one chosen yet";
        if (catalog != null)
        {
            var def = catalog.Find(id);
            if (def == null)
                foreach (var c in catalog.characters) { if (c != null) { def = c; break; } }
            if (def != null)
                shown = string.IsNullOrEmpty(id) || catalog.Find(id) == null
                    ? def.displayName + "  (default)"
                    : def.displayName;
        }
        characterLine.text = "Playing as   " + shown;
    }

    public void Play()
    {
        Go(mapScene);
    }

    public void Go(string sceneName)
    {
        if (!Application.CanStreamedLevelBeLoaded(sceneName))
        {
            Debug.LogError("[MainMenu] cannot load '" + sceneName + "' — it is not in Build " +
                           "Settings. Run Tools > Aftermarket > Build Main Menu.");
            return;
        }
        // The map's own pause menu and the death handler both reload by name, so
        // time scale has to be sane on the way in: a menu reached from a paused
        // game would otherwise start the map frozen.
        Time.timeScale = 1f;
        SceneManager.LoadScene(sceneName, LoadSceneMode.Single);
    }

    public void Quit()
    {
#if UNITY_EDITOR
        UnityEditor.EditorApplication.isPlaying = false;
#else
        Application.Quit();
#endif
    }
}
