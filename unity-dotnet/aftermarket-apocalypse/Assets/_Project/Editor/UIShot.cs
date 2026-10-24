using System.Collections.Generic;
using System.IO;
using System.Text;
using TMPro;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// Photographs the UI scenes. <c>Tools > Aftermarket > Capture UI Screenshots</c>.
/// Needs a graphics device — run WITHOUT -nographics.
///
/// <para><b>"Batchmode cannot capture a ScreenSpaceOverlay canvas" is true and it
/// is not the end of the story.</b> An Overlay canvas is composited straight to the
/// backbuffer and never passes through a camera, so <c>Camera.Render()</c> into a
/// RenderTexture comes back empty. But render mode is a runtime property: flip the
/// canvas to ScreenSpaceCamera, point it at a temporary camera, render, and put it
/// back. Same canvas, same layout, same fonts — it goes through a camera for the
/// duration of one frame.</para>
///
/// <para>This matters because the store, the collection, the nav bar and the
/// character-select screen have all been built, wired, tested and COMMITTED without
/// a single human or tool ever seeing one of them. Every check on them so far asks
/// whether a reference is non-null. A TMP label with a null font renders nothing
/// while looking perfectly healthy in the hierarchy — this project's own notes say
/// so — and no existence check can tell those apart.</para>
///
/// <para><b>It never saves.</b> The render-mode flip is a mutation, so every scene
/// is opened, shot and left dirty-but-unsaved, and the flip is reversed anyway.</para>
/// </summary>
public static class UIShot
{
    const int k_W = 1600;
    const int k_H = 900;

    static readonly string[] k_Scenes =
    {
        "Assets/_Project/Scenes/MainMenu.unity",
        "Assets/_Project/Scenes/Store.unity",
        "Assets/_Project/Scenes/Inventory.unity",
        "Assets/_Project/Scenes/CharacterSelect.unity",
    };

    static string Dir => Path.Combine(Path.GetDirectoryName(Application.dataPath) ?? ".",
                                      "Screenshots", "ui");

    [MenuItem("Tools/Aftermarket/Capture UI Screenshots")]
    public static void Capture()
    {
        if (SystemInfo.graphicsDeviceType == UnityEngine.Rendering.GraphicsDeviceType.Null)
        {
            Debug.LogError("[UIShot] null graphics device — every PNG would be a flat fill. " +
                           "Re-run WITHOUT -nographics.");
            return;
        }

        // Opening a scene from script does NOT prompt: it discards unsaved changes
        // and clears the undo stack. Taking a picture must never cost work.
        if (!Application.isBatchMode && !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
        {
            Debug.LogWarning("[UIShot] cancelled — the open scene has unsaved changes.");
            return;
        }

        Directory.CreateDirectory(Dir);
        var report = new StringBuilder();
        report.AppendLine("UI scenes, rendered by temporarily flipping each Overlay canvas");
        report.AppendLine("to ScreenSpaceCamera. Nothing is saved.");
        report.AppendLine();

        foreach (string scenePath in k_Scenes)
        {
            if (!File.Exists(scenePath))
            {
                report.AppendLine($"{Path.GetFileName(scenePath),-24} MISSING — run its builder.");
                continue;
            }
            Shoot(scenePath, report);
        }

        File.WriteAllText(Path.Combine(Dir, "report.txt"), report.ToString());
        Debug.Log("[UIShot]\n" + report);
    }

    static void Shoot(string scenePath, StringBuilder report)
    {
        Scene scene = EditorSceneManager.OpenScene(scenePath, OpenSceneMode.Single);
        string name = Path.GetFileNameWithoutExtension(scenePath);

        var canvases = new List<Canvas>();
        foreach (var go in scene.GetRootGameObjects())
            canvases.AddRange(go.GetComponentsInChildren<Canvas>(true));

        if (canvases.Count == 0)
        {
            report.AppendLine($"{name,-24} NO CANVAS AT ALL");
            return;
        }

        var camGo = new GameObject("~UIShotCam", typeof(Camera)) { hideFlags = HideFlags.HideAndDontSave };
        var cam = camGo.GetComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.06f, 0.06f, 0.08f);
        cam.orthographic = false;
        cam.nearClipPlane = 0.01f;
        cam.farClipPlane = 100f;
        cam.transform.position = new Vector3(0f, 0f, -10f);

        // Remember and restore. Leaving a scene's canvases on ScreenSpaceCamera
        // would be a silent, invisible edit to a committed build output.
        var prevMode = new Dictionary<Canvas, RenderMode>();
        var prevCam = new Dictionary<Canvas, Camera>();
        var prevDist = new Dictionary<Canvas, float>();
        foreach (var c in canvases)
        {
            prevMode[c] = c.renderMode;
            prevCam[c] = c.worldCamera;
            prevDist[c] = c.planeDistance;
            if (c.renderMode == RenderMode.ScreenSpaceOverlay)
            {
                c.renderMode = RenderMode.ScreenSpaceCamera;
                c.worldCamera = cam;
                c.planeDistance = 1f;
            }
        }
        Canvas.ForceUpdateCanvases();

        string path = Path.Combine(Dir, name + ".png");
        var stats = Render(cam, path);

        foreach (var c in canvases)
        {
            c.renderMode = prevMode[c];
            c.worldCamera = prevCam[c];
            c.planeDistance = prevDist[c];
        }
        Object.DestroyImmediate(camGo);

        // --- what the picture cannot tell you on its own ---------------------
        int labels = 0, nullFont = 0, emptyText = 0, graphics = 0, activeGraphics = 0;
        foreach (var go in scene.GetRootGameObjects())
        {
            foreach (var t in go.GetComponentsInChildren<TMP_Text>(true))
            {
                labels++;
                // A TMP component with a null font renders NOTHING while looking
                // healthy in the inspector. It is the exact failure an
                // "is not null" assertion on the label cannot see.
                if (t.font == null) nullFont++;
                if (string.IsNullOrWhiteSpace(t.text)) emptyText++;
            }
            foreach (var g in go.GetComponentsInChildren<Graphic>(true))
            {
                graphics++;
                if (g.isActiveAndEnabled) activeGraphics++;
            }
        }

        report.AppendLine($"{name,-24} {canvases.Count} canvas, {labels} label(s), " +
                          $"{activeGraphics}/{graphics} graphic(s) active");
        report.AppendLine($"{"",-24} render: {stats.distinct} distinct colours, " +
                          $"mean luma {stats.luma:F3}, {stats.nonBg * 100f:F1}% non-background");
        if (nullFont > 0)
            report.AppendLine($"{"",-24} *** {nullFont} label(s) HAVE NO FONT — they render nothing ***");
        if (emptyText > 0)
            report.AppendLine($"{"",-24} {emptyText} label(s) are blank (may be filled at runtime)");
        // A blank render and a good one are identical on disk. "1 distinct colour"
        // is the tell, and it is why this number is printed at all.
        if (stats.distinct < 12)
            report.AppendLine($"{"",-24} *** ONLY {stats.distinct} DISTINCT COLOURS — this looks blank ***");
        report.AppendLine();
    }

    struct Stats { public int distinct; public float luma, nonBg; }

    static Stats Render(Camera cam, string path)
    {
        var rt = new RenderTexture(k_W, k_H, 24, RenderTextureFormat.ARGB32) { antiAliasing = 2 };
        cam.targetTexture = rt;
        cam.Render();
        var prev = RenderTexture.active;
        RenderTexture.active = rt;
        var tex = new Texture2D(k_W, k_H, TextureFormat.RGB24, false);
        tex.ReadPixels(new Rect(0, 0, k_W, k_H), 0, 0);
        tex.Apply();
        RenderTexture.active = prev;
        cam.targetTexture = null;

        File.WriteAllBytes(path, tex.EncodeToPNG());

        var px = tex.GetPixels();
        var seen = new HashSet<int>();
        double luma = 0;
        int nonBg = 0;
        foreach (var p in px)
        {
            luma += p.r * 0.2126 + p.g * 0.7152 + p.b * 0.0722;
            if (p.r > 0.10f || p.g > 0.10f || p.b > 0.12f) nonBg++;
            seen.Add(((int)(p.r * 31) << 10) | ((int)(p.g * 31) << 5) | (int)(p.b * 31));
        }
        Object.DestroyImmediate(tex);
        rt.Release();
        Object.DestroyImmediate(rt);
        return new Stats
        {
            distinct = seen.Count,
            luma = (float)(luma / px.Length),
            nonBg = nonBg / (float)px.Length,
        };
    }
}
