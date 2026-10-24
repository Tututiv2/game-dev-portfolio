using System.Collections.Generic;
using TMPro;
using UnityEditor;
using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// The one place that knows which screens exist and how to get between them.
///
/// <para>Shared by <see cref="StoreBuilder"/>, <see cref="InventoryBuilder"/> and
/// <see cref="CharacterSelectBuilder"/> so all three bars are identical. Three
/// hand-built bars would be three lists that drift, and the way you find out is a
/// button that leads nowhere.</para>
/// </summary>
public static class NavBarBuilder
{
    /// <summary>
    /// The Phase 8 screens, in the order they appear on the bar. Path first
    /// because Build Settings needs paths; the NAME is derived from it, because a
    /// separately-typed name is the second source of truth that makes
    /// <c>LoadScene</c> fail on a scene that is definitely in the list.
    /// </summary>
    public static readonly string[] ScenePaths =
    {
        "Assets/_Project/Scenes/Store.unity",
        "Assets/_Project/Scenes/Inventory.unity",
        "Assets/_Project/Scenes/CharacterSelect.unity",
    };

    public static readonly string[] Labels = { "STORE", "COLLECTION", "CHARACTER" };

    public static string NameOf(string scenePath)
    {
        return System.IO.Path.GetFileNameWithoutExtension(scenePath);
    }

    [MenuItem("Tools/Aftermarket/Wire Screen Navigation")]
    public static void EnsureInBuildSettingsMenu()
    {
        int added = EnsureInBuildSettings();
        Debug.Log("[Nav] build settings now contain all " + ScenePaths.Length +
                  " Phase 8 screens (" + added + " added this run).\n" +
                  "  " + string.Join("\n  ", ScenePaths));
    }

    /// <summary>
    /// Put every screen in Build Settings, enabled. Returns how many were added.
    ///
    /// <para><b>This is not optional decoration.</b> <c>SceneManager.LoadScene</c>
    /// throws for a scene that is not in the list, so a nav bar built without this
    /// is a row of buttons that each raise an exception from inside a UI callback.
    /// Existing entries are left where they are — reordering the list changes
    /// scene BUILD INDICES, and anything that loads by index would silently start
    /// loading a different scene.</para>
    /// </summary>
    public static int EnsureInBuildSettings()
    {
        var scenes = new List<EditorBuildSettingsScene>(EditorBuildSettings.scenes);
        int added = 0;

        foreach (string path in ScenePaths)
        {
            if (!System.IO.File.Exists(path)) continue;      // not built yet

            bool present = false;
            for (int i = 0; i < scenes.Count; i++)
            {
                if (scenes[i].path != path) continue;
                present = true;
                if (!scenes[i].enabled)
                {
                    // A disabled entry is invisible to CanStreamedLevelBeLoaded,
                    // which is the same failure as being absent but harder to see
                    // in the inspector.
                    scenes[i] = new EditorBuildSettingsScene(path, true);
                }
                break;
            }
            if (!present)
            {
                scenes.Add(new EditorBuildSettingsScene(path, true));
                added++;
            }
        }

        EditorBuildSettings.scenes = scenes.ToArray();
        return added;
    }

    /// <summary>
    /// Add the bar to a canvas and return the wired component.
    /// </summary>
    public static ScreenNav Build(Transform canvas, TMP_FontAsset font, string currentScenePath)
    {
        EnsureInBuildSettings();

        var barGo = new GameObject("NavBar", typeof(RectTransform), typeof(HorizontalLayoutGroup));
        barGo.transform.SetParent(canvas, false);
        var bar = (RectTransform)barGo.transform;
        bar.anchorMin = bar.anchorMax = new Vector2(0.5f, 0.975f);
        bar.pivot = new Vector2(0.5f, 0.5f);
        bar.anchoredPosition = Vector2.zero;
        bar.sizeDelta = new Vector2(900f, 62f);

        var layout = barGo.GetComponent<HorizontalLayoutGroup>();
        layout.spacing = 10f;
        layout.childAlignment = TextAnchor.MiddleCenter;
        layout.childForceExpandWidth = false;
        layout.childForceExpandHeight = false;

        var nav = barGo.AddComponent<ScreenNav>();
        nav.currentSceneName = NameOf(currentScenePath);

        var targets = new List<ScreenNav.Target>();
        for (int i = 0; i < ScenePaths.Length; i++)
        {
            Button b = MakeNavButton(barGo.transform, "Nav_" + NameOf(ScenePaths[i]), font, Labels[i]);
            targets.Add(new ScreenNav.Target
            {
                sceneName = NameOf(ScenePaths[i]),
                label = Labels[i],
                button = b,
            });
        }
        nav.targets = targets.ToArray();
        return nav;
    }

    static Button MakeNavButton(Transform parent, string name, TMP_FontAsset font, string text)
    {
        var go = new GameObject(name, typeof(RectTransform), typeof(Image), typeof(Button));
        go.transform.SetParent(parent, false);
        ((RectTransform)go.transform).sizeDelta = new Vector2(210f, 52f);
        go.GetComponent<Image>().color = new Color(0.85f, 0.86f, 0.9f);

        var labelGo = new GameObject("Label", typeof(RectTransform));
        labelGo.transform.SetParent(go.transform, false);
        var t = labelGo.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = 24f;
        t.alignment = TextAlignmentOptions.Center;
        t.color = new Color(0.06f, 0.06f, 0.08f);
        t.text = text;
        var rt = (RectTransform)labelGo.transform;
        rt.anchorMin = Vector2.zero;
        rt.anchorMax = Vector2.one;
        rt.offsetMin = rt.offsetMax = Vector2.zero;
        return go.GetComponent<Button>();
    }
}
