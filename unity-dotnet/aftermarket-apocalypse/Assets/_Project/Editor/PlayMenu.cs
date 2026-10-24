using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

/// <summary>
/// One-click "open this screen and start it". <c>Tools > Aftermarket > Play</c>.
///
/// <para>Every screen in this project is a separate scene, so trying one means
/// finding it in the Project window, double-clicking it, and only then pressing
/// Play — and getting that wrong silently runs the scene you already had open.
/// These items do all three steps and name what you should expect to see.</para>
///
/// <para><b>They save first.</b> Entering play mode discards nothing, but OPENING a
/// scene from a script does: <c>EditorSceneManager.OpenScene</c> does not prompt,
/// it drops unsaved changes and clears the undo stack. Everything here goes
/// through <c>SaveCurrentModifiedScenesIfUserWantsTo</c>, so a stray click can
/// never cost work.</para>
/// </summary>
public static class PlayMenu
{
    const string k_Map = "Assets/_Project/Scenes/AshfallDepot.unity";
    const string k_Store = "Assets/_Project/Scenes/Store.unity";
    const string k_Inventory = "Assets/_Project/Scenes/Inventory.unity";
    const string k_Character = "Assets/_Project/Scenes/CharacterSelect.unity";

    [MenuItem("Tools/Aftermarket/Play/▶ Main Menu (START HERE)", priority = -1)]
    public static void PlayMainMenu()
    {
        Go("Assets/_Project/Scenes/MainMenu.unity",
           "This is the front door, and it is what a BUILD opens on.\n" +
           "PLAY drops you into the map as whoever CHARACTER last picked.\n" +
           "CHARACTER / STORE / COLLECTION reach the other three screens.");
    }

    [MenuItem("Tools/Aftermarket/Play/▶ The Map (shoot things)", priority = 0)]
    public static void PlayMap()
    {
        Go(k_Map,
           "WASD to move, mouse to look, left click to shoot.\n" +
           "Press 1 / 2 / 3 to switch weapon — 3 is the Sakura Fang.\n" +
           "Walk into a chalk outline or a machine and press F to buy.");
    }

    [MenuItem("Tools/Aftermarket/Play/▶ Store (open a Sacred)", priority = 1)]
    public static void PlayStore()
    {
        Go(k_Store,
           "Click a Sacred tile to open one. The three FREE ones cost nothing.\n" +
           "For the paid tiles, run Tools > Aftermarket > Dev — Grant Test Currency first.\n" +
           "The bar at the top switches to COLLECTION and CHARACTER.");
    }

    [MenuItem("Tools/Aftermarket/Play/▶ Collection (what you own)", priority = 2)]
    public static void PlayInventory()
    {
        Go(k_Inventory,
           "Empty until you have opened a Sacred — that is correct, not broken.\n" +
           "Open one in the Store first, then come back.");
    }

    [MenuItem("Tools/Aftermarket/Play/▶ Character Select (7 characters)", priority = 3)]
    public static void PlayCharacter()
    {
        Go(k_Character,
           "The < and > arrows step through all seven characters.\n" +
           "The swatch row along the bottom switches theme; locked ones are greyed.");
    }

    [MenuItem("Tools/Aftermarket/Play/■ Stop", priority = 20)]
    public static void Stop() => EditorApplication.isPlaying = false;

    // -----------------------------------------------------------------------
    static void Go(string scenePath, string howToUse)
    {
        if (!File.Exists(scenePath))
        {
            Debug.LogError($"[Play] {scenePath} does not exist — its builder has not been run.");
            return;
        }

        // Leaving play mode on while swapping scenes gives you the OLD scene still
        // running, which reads as "the menu item did nothing".
        if (EditorApplication.isPlaying) EditorApplication.isPlaying = false;

        if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
        {
            Debug.LogWarning("[Play] cancelled — the open scene has unsaved changes.");
            return;
        }

        EditorSceneManager.OpenScene(scenePath, OpenSceneMode.Single);
        Debug.Log($"[Play] {Path.GetFileNameWithoutExtension(scenePath)}\n{howToUse}");
        EditorApplication.isPlaying = true;
    }
}
