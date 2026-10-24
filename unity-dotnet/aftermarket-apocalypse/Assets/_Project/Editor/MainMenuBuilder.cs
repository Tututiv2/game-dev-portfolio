using System.Collections.Generic;
using System.IO;
using TMPro;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.EventSystems;
using UnityEngine.InputSystem.UI;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// Build <c>Scenes/MainMenu.unity</c> and make it the scene the game STARTS on.
///
/// <para>A build output: re-running rebuilds it from scratch, like Store and
/// Inventory and unlike the hand-dressed map.</para>
///
/// <para>It also repairs two things that made the project unplayable as a game
/// rather than as a set of scenes:</para>
/// <list type="bullet">
/// <item>Build index 0 was <c>Assets/Scenes/SampleScene.unity</c>, the untouched
/// URP template. Every build launched an empty grey room.</item>
/// <item><c>AshfallDepot.unity</c> — the actual map — was not in Build Settings
/// at all, so <c>SceneManager.LoadScene</c> could never reach it and a build
/// would not have contained it.</item>
/// </list>
/// </summary>
public static class MainMenuBuilder
{
    const string k_Scene = "Assets/_Project/Scenes/MainMenu.unity";
    const string k_Map = "Assets/_Project/Scenes/AshfallDepot.unity";
    const string k_Catalog = "Assets/_Project/Content/Characters/CharacterCatalog.asset";

    struct Entry
    {
        public string label, scene, hint;
        public Entry(string label, string scene, string hint)
        { this.label = label; this.scene = scene; this.hint = hint; }
    }

    static readonly Entry[] Buttons =
    {
        new Entry("PLAY", "AshfallDepot", "drop into the map"),
        new Entry("CHARACTER", "CharacterSelect", "pick who you are"),
        new Entry("STORE", "Store", "open a Sacred"),
        new Entry("COLLECTION", "Inventory", "what you own"),
    };

    [MenuItem("Tools/Aftermarket/Build Main Menu")]
    public static void Build()
    {
        if (!Application.isBatchMode && !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
        {
            Debug.LogWarning("[MainMenu] cancelled — the open scene has unsaved changes.");
            return;
        }

        var font = FindAnyFont();
        if (font == null)
        {
            // Refuse rather than ship a menu of invisible text. A TMP component
            // with a null font renders NOTHING while looking healthy in the
            // hierarchy — the trap PlaytestRig was written around.
            Debug.LogError("[MainMenu] no TMP font asset found; every label would render " +
                           "nothing. Import the TMP Essential Resources first.");
            return;
        }

        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

        var camGo = new GameObject("MenuCamera");
        var cam = camGo.AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.055f, 0.05f, 0.075f);
        cam.orthographic = true;

        // InputSystemUIInputModule, never StandaloneInputModule: activeInputHandler
        // is 1, so the legacy module reads UnityEngine.Input and throws every
        // frame. Added with a plain AddComponent so Reset() does not serialize a
        // reference to the InputSystem_Actions template.
        var es = new GameObject("EventSystem");
        es.AddComponent<EventSystem>();
        es.AddComponent<InputSystemUIInputModule>();

        var canvasGo = new GameObject("Canvas");
        var canvas = canvasGo.AddComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        var scaler = canvasGo.AddComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1920, 1080);
        canvasGo.AddComponent<GraphicRaycaster>();

        var menu = canvasGo.AddComponent<MainMenu>();
        menu.mapScene = "AshfallDepot";
        menu.catalog = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(k_Catalog);

        Title(canvas.transform, font, "AFTERMARKET", 96f, new Vector2(0f, 340f), new Color(1f, 0.42f, 0.66f));
        Title(canvas.transform, font, "APOCALYPSE", 96f, new Vector2(0f, 250f), Color.white);

        for (int i = 0; i < Buttons.Length; i++)
            MakeButton(canvas.transform, font, menu, Buttons[i], 60f - i * 104f);

        menu.characterLine = Title(canvas.transform, font, "", 30f, new Vector2(0f, -400f),
                                   new Color(0.72f, 0.72f, 0.78f));

        var quit = MakeButton(canvas.transform, font, menu,
                              new Entry("QUIT", null, "close the game"), -470f);
        quit.onClick.RemoveAllListeners();
        UnityEditor.Events.UnityEventTools.AddPersistentListener(quit.onClick, menu.Quit);

        Directory.CreateDirectory(Path.GetDirectoryName(k_Scene));
        EditorSceneManager.SaveScene(scene, k_Scene);

        int added = RegisterScenes();
        AssetDatabase.SaveAssets();

        Debug.Log($"[MainMenu] built {k_Scene} with {Buttons.Length} destinations. " +
                  $"Build Settings updated ({added} scene(s) added); MainMenu is now index 0 and " +
                  "AshfallDepot is included, so PLAY can actually load the map.");
    }

    /// <summary>
    /// Put the menu at index 0 and make sure every destination is present and
    /// enabled.
    ///
    /// <para>Reordering Build Settings changes scene BUILD INDICES, which
    /// NavBarBuilder rightly refuses to do. It is safe HERE because nothing in
    /// this project loads a scene by index — checked: every call site passes a
    /// name. If that ever stops being true, this is the code that breaks it.</para>
    /// </summary>
    static int RegisterScenes()
    {
        var wanted = new List<string> { k_Scene, k_Map };
        foreach (var e in Buttons)
            if (e.scene != null)
            {
                string path = "Assets/_Project/Scenes/" + e.scene + ".unity";
                if (File.Exists(path) && !wanted.Contains(path)) wanted.Add(path);
            }

        var scenes = new List<EditorBuildSettingsScene>(EditorBuildSettings.scenes);
        int added = 0;
        foreach (string path in wanted)
        {
            if (!File.Exists(path)) continue;
            int at = scenes.FindIndex(s => s.path == path);
            if (at < 0) { scenes.Add(new EditorBuildSettingsScene(path, true)); added++; }
            else if (!scenes[at].enabled) scenes[at] = new EditorBuildSettingsScene(path, true);
        }

        // The menu must be the scene the game opens on.
        int menuAt = scenes.FindIndex(s => s.path == k_Scene);
        if (menuAt > 0)
        {
            var m = scenes[menuAt];
            scenes.RemoveAt(menuAt);
            scenes.Insert(0, m);
        }

        EditorBuildSettings.scenes = scenes.ToArray();
        return added;
    }

    static TextMeshProUGUI Title(Transform parent, TMP_FontAsset font, string text,
                                 float size, Vector2 pos, Color colour)
    {
        var go = new GameObject("Text_" + (text.Length == 0 ? "CharacterLine" : text));
        go.transform.SetParent(parent, false);
        var t = go.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.text = text;
        t.fontSize = size;
        t.color = colour;
        t.alignment = TextAlignmentOptions.Center;
        var rt = t.rectTransform;
        rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.5f);
        rt.pivot = new Vector2(0.5f, 0.5f);
        rt.anchoredPosition = pos;
        rt.sizeDelta = new Vector2(1100f, size * 1.5f);
        return t;
    }

    static Button MakeButton(Transform parent, TMP_FontAsset font, MainMenu menu,
                             Entry entry, float y)
    {
        var go = new GameObject("Button_" + entry.label);
        go.transform.SetParent(parent, false);
        var img = go.AddComponent<Image>();
        img.color = new Color(0.16f, 0.13f, 0.2f, 0.95f);
        var button = go.AddComponent<Button>();
        button.targetGraphic = img;

        var rt = img.rectTransform;
        rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.5f);
        rt.pivot = new Vector2(0.5f, 0.5f);
        rt.anchoredPosition = new Vector2(0f, y);
        rt.sizeDelta = new Vector2(520f, 84f);

        var label = Title(go.transform, font, entry.label, 40f, new Vector2(-70f, 0f), Color.white);
        label.alignment = TextAlignmentOptions.Left;
        label.rectTransform.sizeDelta = new Vector2(300f, 60f);

        // Kept inside the button. At x 150 with a 230 wide box the right edge
        // lands at 265 against a half-width of 260, so every hint was clipped by
        // five pixels — visible in Screenshots/ui/MainMenu.png and invisible to
        // any check that only asks whether the label exists.
        var hint = Title(go.transform, font, entry.hint, 20f, new Vector2(115f, 0f),
                         new Color(0.68f, 0.62f, 0.72f));
        hint.alignment = TextAlignmentOptions.Right;
        hint.rectTransform.sizeDelta = new Vector2(250f, 40f);

        if (entry.scene != null)
        {
            // A persistent listener, not a lambda: a runtime-added listener is
            // not serialized, so the buttons would work in the editor session
            // that built them and do nothing in a build.
            string target = entry.scene;
            UnityEditor.Events.UnityEventTools.AddStringPersistentListener(
                button.onClick, menu.Go, target);
        }
        return button;
    }

    static TMP_FontAsset FindAnyFont()
    {
        if (TMP_Settings.instance != null && TMP_Settings.defaultFontAsset != null)
            return TMP_Settings.defaultFontAsset;

        TMP_FontAsset fallback = null;
        foreach (string guid in AssetDatabase.FindAssets("t:TMP_FontAsset"))
        {
            var a = AssetDatabase.LoadAssetAtPath<TMP_FontAsset>(AssetDatabase.GUIDToAssetPath(guid));
            if (a == null) continue;
            if (a.name.IndexOf("Fallback", System.StringComparison.OrdinalIgnoreCase) >= 0)
            {
                if (fallback == null) fallback = a;
                continue;
            }
            return a;
        }
        return fallback;
    }
}
