using System.Collections.Generic;
using System.IO;
using TMPro;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// Builds <c>Assets/_Project/Scenes/Inventory.unity</c> — the §15.2/§15.9 "what
/// you own" screen.
///
/// <para>Its own scene rather than a tab in <c>Store.unity</c>: the two are being
/// built independently, and a second scene costs nothing while a shared one is a
/// merge conflict waiting to happen in a file nobody can diff by eye.</para>
/// </summary>
public static class InventoryBuilder
{
    const string k_ScenePath = "Assets/_Project/Scenes/Inventory.unity";

    [MenuItem("Tools/Aftermarket/Build Inventory")]
    public static void Build()
    {
        // NewScene does NOT prompt from a script — it discards unsaved changes and
        // clears the undo stack. CLAUDE.md records this trap being paid for four
        // times; this guard has to be the first thing that happens.
        if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
        {
            Debug.Log("[Inventory] cancelled — the open scene has unsaved changes.");
            return;
        }

        var log = new List<string>();
        Scene scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

        var camGo = new GameObject("UICamera");
        Camera cam = camGo.AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.05f, 0.05f, 0.07f, 1f);
        cam.cullingMask = ~0;      // Law 9

        TMP_FontAsset font = FindFont();
        if (font == null)
            log.Add("NO TMP FONT — every label renders nothing. Import the TMP essentials.");

        var canvasGo = new GameObject("Canvas", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
        var canvas = canvasGo.GetComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        var scaler = canvasGo.GetComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1920f, 1080f);

        // activeInputHandler is 1, so StandaloneInputModule reads UnityEngine.Input
        // and throws every frame. Plain AddComponent, not ObjectFactory, so no
        // template .inputactions reference is serialized.
        var es = new GameObject("EventSystem", typeof(UnityEngine.EventSystems.EventSystem));
        es.AddComponent<UnityEngine.InputSystem.UI.InputSystemUIInputModule>();

        TMP_Text title = Label(canvasGo.transform, "Title", font, 56f,
                               new Vector2(0.5f, 0.94f), new Vector2(1400f, 80f), "COLLECTION");
        TMP_Text count = Label(canvasGo.transform, "CountLabel", font, 30f,
                               new Vector2(0.5f, 0.885f), new Vector2(1400f, 46f), "0 ITEMS");
        TMP_Text empty = Label(canvasGo.transform, "EmptyLabel", font, 34f,
                               new Vector2(0.5f, 0.5f), new Vector2(900f, 200f), "");

        // --- grid ----------------------------------------------------------
        var gridGo = new GameObject("Grid", typeof(RectTransform), typeof(GridLayoutGroup));
        gridGo.transform.SetParent(canvasGo.transform, false);
        var gridRt = (RectTransform)gridGo.transform;
        Anchor(gridRt, new Vector2(0.5f, 0.48f), new Vector2(1500f, 700f));
        var layout = gridGo.GetComponent<GridLayoutGroup>();
        layout.cellSize = new Vector2(230f, 150f);
        layout.spacing = new Vector2(16f, 16f);
        layout.childAlignment = TextAnchor.UpperCenter;

        Button tile = MakeTile(gridGo.transform, "TileTemplate", font);

        // --- detail --------------------------------------------------------
        var detailGo = new GameObject("DetailPanel", typeof(RectTransform), typeof(Image));
        detailGo.transform.SetParent(canvasGo.transform, false);
        Anchor((RectTransform)detailGo.transform, new Vector2(0.5f, 0.5f), new Vector2(820f, 720f));
        detailGo.GetComponent<Image>().color = new Color(0.04f, 0.04f, 0.06f, 0.97f);

        var borderGo = new GameObject("DetailBorder", typeof(RectTransform), typeof(Image));
        borderGo.transform.SetParent(detailGo.transform, false);
        Anchor((RectTransform)borderGo.transform, new Vector2(0.5f, 0.5f), new Vector2(840f, 740f));
        borderGo.transform.SetAsFirstSibling();
        Image detailBorder = borderGo.GetComponent<Image>();

        // The serial is the largest thing here for the same reason it is on the
        // reveal card (§15.8.1): it is what is unique in the world.
        TMP_Text dSerial = Label(detailGo.transform, "DetailSerial", font, 96f,
                                 new Vector2(0.5f, 0.86f), new Vector2(780f, 130f), "#0 / 0");
        TMP_Text dName = Label(detailGo.transform, "DetailName", font, 40f,
                               new Vector2(0.5f, 0.74f), new Vector2(780f, 60f), "—");
        TMP_Text dRarity = Label(detailGo.transform, "DetailRarity", font, 30f,
                                 new Vector2(0.5f, 0.67f), new Vector2(780f, 46f), "—");
        TMP_Text dFloat = Label(detailGo.transform, "DetailFloat", font, 30f,
                                new Vector2(0.5f, 0.61f), new Vector2(780f, 46f), "—");
        TMP_Text dVariant = Label(detailGo.transform, "DetailVariant", font, 30f,
                                  new Vector2(0.5f, 0.55f), new Vector2(780f, 46f), "");
        TMP_Text dProv = Label(detailGo.transform, "DetailProvenance", font, 24f,
                               new Vector2(0.5f, 0.32f), new Vector2(760f, 260f), "");
        dProv.alignment = TextAlignmentOptions.TopLeft;

        Button close = MakeButton(detailGo.transform, "DetailClose", font, "CLOSE");
        Anchor((RectTransform)close.transform, new Vector2(0.5f, 0.08f), new Vector2(220f, 64f));

        // Saved INACTIVE. InventoryScreen.Start also disables it, but that is a
        // frame late and — more to the point — anyone who opens this scene in the
        // editor without pressing Play would otherwise see a full-screen panel
        // sitting over the grid and reasonably conclude the screen is broken.
        detailGo.SetActive(false);

        // --- wire ------------------------------------------------------------
        var inv = canvasGo.AddComponent<InventoryScreen>();
        inv.grid = gridRt;
        inv.tileTemplate = tile;
        inv.countLabel = count;
        inv.emptyLabel = empty;
        inv.detailPanel = detailGo;
        inv.detailBorder = detailBorder;
        inv.detailSerial = dSerial;
        inv.detailName = dName;
        inv.detailRarity = dRarity;
        inv.detailFloat = dFloat;
        inv.detailVariant = dVariant;
        inv.detailProvenance = dProv;
        inv.detailClose = close;
        inv.designs = LoadAll<SkinDesign>("Assets/_Project/Content/SkinDesigns");

        Directory.CreateDirectory(Path.GetDirectoryName(k_ScenePath));
        NavBarBuilder.Build(canvasGo.transform, font, k_ScenePath);

        EditorSceneManager.SaveScene(scene, k_ScenePath);

        // Report by reading the component back, not by restating what was set.
        int wired = 0, missing = 0;
        var missingNames = new List<string>();
        foreach (var f in typeof(InventoryScreen).GetFields(
                     System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Instance))
        {
            object v = f.GetValue(inv);
            bool empty2 = v == null || (v is Object && (Object)v == null);
            if (empty2) { missing++; missingNames.Add(f.Name); } else wired++;
        }

        log.Add("scene            " + k_ScenePath);
        log.Add("  wired          " + wired + ", missing " + missing +
                (missing > 0 ? " (" + string.Join(", ", missingNames) + ")" : ""));
        log.Add("  designs        " + inv.designs.Length);
        log.Add("  font           " + (title.font != null ? title.font.name : "NULL"));
        log.Add("  input module   " +
                (es.GetComponent<UnityEngine.InputSystem.UI.InputSystemUIInputModule>() != null
                 ? "InputSystemUIInputModule" : "MISSING"));

        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();
        Debug.Log("[Inventory]\n  " + string.Join("\n  ", log));
    }

    static T[] LoadAll<T>(string folder) where T : Object
    {
        var found = new List<T>();
        foreach (string guid in AssetDatabase.FindAssets("t:" + typeof(T).Name, new[] { folder }))
        {
            var a = AssetDatabase.LoadAssetAtPath<T>(AssetDatabase.GUIDToAssetPath(guid));
            if (a != null) found.Add(a);
        }
        found.Sort(delegate (T a, T b) { return string.CompareOrdinal(a.name, b.name); });
        return found.ToArray();
    }

    /// <summary>
    /// Prefer TMP's configured default. A bare FindAssets returns
    /// "LiberationSans SDF - Fallback" first here — a DYNAMIC font whose atlas
    /// holds whatever some other scene made TMP rasterise, so labels render
    /// correctly or not depending on what was opened earlier.
    /// </summary>
    static TMP_FontAsset FindFont()
    {
        if (TMP_Settings.defaultFontAsset != null) return TMP_Settings.defaultFontAsset;
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

    static void Anchor(RectTransform rt, Vector2 pivot, Vector2 size)
    {
        rt.anchorMin = rt.anchorMax = pivot;
        rt.pivot = new Vector2(0.5f, 0.5f);
        rt.anchoredPosition = Vector2.zero;
        rt.sizeDelta = size;
    }

    static TMP_Text Label(Transform parent, string name, TMP_FontAsset font, float size,
                          Vector2 pivot, Vector2 rect, string text)
    {
        var go = new GameObject(name, typeof(RectTransform));
        go.transform.SetParent(parent, false);
        var t = go.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = size;
        t.alignment = TextAlignmentOptions.Center;
        t.text = text;
        t.enableWordWrapping = true;
        Anchor((RectTransform)go.transform, pivot, rect);
        return t;
    }

    static Button MakeButton(Transform parent, string name, TMP_FontAsset font, string text)
    {
        var go = new GameObject(name, typeof(RectTransform), typeof(Image), typeof(Button));
        go.transform.SetParent(parent, false);
        ((RectTransform)go.transform).sizeDelta = new Vector2(200f, 64f);
        go.GetComponent<Image>().color = Color.white;

        var labelGo = new GameObject("Label", typeof(RectTransform));
        labelGo.transform.SetParent(go.transform, false);
        var t = labelGo.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = 24f;
        t.alignment = TextAlignmentOptions.Center;
        t.color = Color.black;
        t.text = text;
        var rt = (RectTransform)labelGo.transform;
        rt.anchorMin = Vector2.zero;
        rt.anchorMax = Vector2.one;
        rt.offsetMin = rt.offsetMax = Vector2.zero;
        return go.GetComponent<Button>();
    }

    static Button MakeTile(Transform parent, string name, TMP_FontAsset font)
    {
        Button b = MakeButton(parent, name, font, "");
        ((RectTransform)b.transform).sizeDelta = new Vector2(230f, 150f);
        TMP_Text t = b.GetComponentInChildren<TMP_Text>();
        t.fontSize = 22f;
        t.color = new Color(0.06f, 0.06f, 0.08f);
        b.gameObject.SetActive(false);
        return b;
    }
}
