using System.Collections;
using System.IO;
using System.Text;
using NUnit.Framework;
using TMPro;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.TestTools;
using UnityEngine.UI;

/// <summary>
/// Photographs the UI scenes IN PLAY, and counts what is actually on screen.
///
/// <para><b>Why the edit-mode shot was not enough.</b> UIShot proved the chrome —
/// canvases, layout, fonts, headings — by flipping each Overlay canvas to
/// ScreenSpaceCamera for one frame. But the store's Sacred tiles, the collection's
/// item grid and the balance readouts are all BUILT AT RUNTIME from the backend, so
/// an editor render shows a correct, complete, empty shop. It answers "does this
/// screen exist" and cannot answer "does this screen have anything in it".</para>
///
/// <para>These are diagnostics with teeth: they assert the things whose absence is
/// invisible — that the store ends up with more than zero tiles, and that no label
/// on screen is missing its font. A TMP component with a null font renders nothing
/// while looking healthy in the hierarchy, so the picture alone cannot show it.</para>
/// </summary>
public class UIPlayShots
{
    static string Dir => Path.Combine(Path.GetDirectoryName(Application.dataPath) ?? ".",
                                      "Screenshots", "ui_play");

    static readonly StringBuilder s_Report = new StringBuilder();

    [OneTimeSetUp]
    public void Setup()
    {
        Directory.CreateDirectory(Dir);
        s_Report.Clear();
        s_Report.AppendLine("UI scenes captured IN PLAY, after the screen has populated itself.");
        s_Report.AppendLine();
    }

    [OneTimeTearDown]
    public void Teardown()
    {
        File.WriteAllText(Path.Combine(Dir, "report.txt"), s_Report.ToString());
        Debug.Log("[UIPlayShots]\n" + s_Report);
    }

    [UnityTest]
    public IEnumerator Store_HasTilesInItOnceItHasRun()
    {
        yield return Load("Store");
        int tiles = CountInteractiveTiles();
        Shoot("Store", tiles);

        // The one assertion that matters. LocalBackend.FindSacred used to fall back
        // to Resources.LoadAll("Sacreds") against a folder that does not exist, so
        // the catalogue loaded as an EMPTY ARRAY and every purchase failed on the
        // first click with nothing logged. An empty catalogue is a valid catalogue,
        // and a store with no tiles looks exactly like a store that is still loading.
        Assert.That(tiles, Is.GreaterThan(0),
            "the store rendered with ZERO buyable tiles — the Sacred catalogue is empty.");
    }

    [UnityTest]
    public IEnumerator Inventory_RendersWithoutAMissingFont()
    {
        yield return Load("Inventory");
        Shoot("Inventory", CountInteractiveTiles());
        AssertEveryLabelHasAFont("Inventory");
    }

    [UnityTest]
    public IEnumerator CharacterSelect_ShowsACharacterAndItsThemeRow()
    {
        yield return Load("CharacterSelect");
        Shoot("CharacterSelect", CountInteractiveTiles());
        AssertEveryLabelHasAFont("CharacterSelect");

        var select = Object.FindFirstObjectByType<CharacterSelect>();
        Assert.That(select, Is.Not.Null, "no CharacterSelect in the scene.");
        Assert.That(select.Count, Is.GreaterThan(1),
            "the roster has " + select.Count + " character(s); the Next/Prev arrows " +
            "have nothing to step to.");

        // A preview anchor with no child is an empty turntable, which reads as a
        // broken screen rather than as an unselected character.
        Assert.That(select.previewAnchor, Is.Not.Null, "no previewAnchor.");
        Assert.That(select.previewAnchor.childCount, Is.GreaterThan(0),
            "the preview anchor is empty — no character model was instantiated.");
    }

    // -----------------------------------------------------------------------
    static IEnumerator Load(string sceneName)
    {
        SceneManager.LoadScene(sceneName, LoadSceneMode.Single);
        // Plain frame yields only.
        //
        // WaitForEndOfFrame NEVER FIRES IN -batchmode: there is no render loop to
        // reach the end of, so the coroutine parks forever and the whole run hangs
        // with no error and no timeout -- observed, the log just idles on USB
        // polling. ScreenCapture.CaptureScreenshotAsTexture depends on the same
        // point in the frame and is equally unusable here.
        for (int i = 0; i < 10; i++) yield return null;
    }

    static int CountInteractiveTiles()
    {
        int n = 0;
        foreach (var b in Object.FindObjectsByType<Button>(FindObjectsSortMode.None))
            if (b.isActiveAndEnabled) n++;
        return n;
    }

    static void AssertEveryLabelHasAFont(string where)
    {
        var missing = new System.Collections.Generic.List<string>();
        foreach (var t in Object.FindObjectsByType<TMP_Text>(FindObjectsSortMode.None))
            if (t.font == null) missing.Add(t.name);
        Assert.That(missing, Is.Empty,
            where + ": these labels have no font and therefore render NOTHING while " +
            "looking healthy in the hierarchy: " + string.Join(", ", missing));
    }

    static void Shoot(string name, int tiles)
    {
        // Same canvas flip UIShot uses, for the same reason -- an Overlay canvas
        // never passes through a camera -- but here the screen has RUN, so the
        // tiles and grids the editor render could not show are populated.
        var cam = new GameObject("~ShotCam", typeof(Camera)).GetComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.06f, 0.06f, 0.08f);
        cam.transform.position = new Vector3(0f, 0f, -10f);

        var canvases = Object.FindObjectsByType<Canvas>(FindObjectsSortMode.None);
        var restore = new System.Collections.Generic.List<(Canvas, RenderMode, Camera, float)>();
        foreach (var c in canvases)
        {
            restore.Add((c, c.renderMode, c.worldCamera, c.planeDistance));
            if (c.renderMode != RenderMode.ScreenSpaceOverlay) continue;
            c.renderMode = RenderMode.ScreenSpaceCamera;
            c.worldCamera = cam;
            c.planeDistance = 1f;
        }
        Canvas.ForceUpdateCanvases();

        var rt = new RenderTexture(1600, 900, 24, RenderTextureFormat.ARGB32);
        cam.targetTexture = rt;
        cam.Render();
        var prevActive = RenderTexture.active;
        RenderTexture.active = rt;
        var tex = new Texture2D(1600, 900, TextureFormat.RGB24, false);
        tex.ReadPixels(new Rect(0, 0, 1600, 900), 0, 0);
        tex.Apply();
        RenderTexture.active = prevActive;

        foreach (var (c, mode, wc, dist) in restore)
        {
            if (c == null) continue;
            c.renderMode = mode; c.worldCamera = wc; c.planeDistance = dist;
        }
        cam.targetTexture = null;
        Object.DestroyImmediate(cam.gameObject);
        rt.Release();
        Object.DestroyImmediate(rt);

        File.WriteAllBytes(Path.Combine(Dir, name + ".png"), tex.EncodeToPNG());

        var px = tex.GetPixels();
        var seen = new System.Collections.Generic.HashSet<int>();
        double luma = 0;
        foreach (var p in px)
        {
            luma += p.r * 0.2126 + p.g * 0.7152 + p.b * 0.0722;
            seen.Add(((int)(p.r * 31) << 10) | ((int)(p.g * 31) << 5) | (int)(p.b * 31));
        }
        Object.DestroyImmediate(tex);

        s_Report.AppendLine($"{name,-18} {tiles,3} active button(s)  " +
                            $"{seen.Count,4} distinct colours  mean luma {luma / px.Length:F3}" +
                            (seen.Count < 12 ? "   *** LOOKS BLANK ***" : ""));
    }
}
