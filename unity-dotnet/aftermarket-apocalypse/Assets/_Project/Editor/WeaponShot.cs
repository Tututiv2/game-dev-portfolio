using System.Collections.Generic;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Renders every weapon view model from the player's own camera and MEASURES how it
/// sits in frame. <c>Tools > Aftermarket > Capture Weapon Screenshots</c>.
///
/// <para>Needs a real graphics device, same as SceneShot:</para>
/// <code>
/// Unity.exe -batchmode -projectPath . -executeMethod WeaponShot.CaptureBatch -quit
/// </code>
/// <para><b>No -nographics</b> — that gives a null device and every PNG comes out a
/// flat fill. This errors rather than writing blank files.</para>
///
/// <para><b>Why measure and not just look.</b> "Is the gun too big" is exactly the
/// question an eye answers badly and inconsistently: it depends on the monitor, the
/// crop and what you looked at last. CLAUDE.md already records this — texture scale
/// was unanswerable by eye until a 1 m reference cube turned "the brick looks too
/// big" into "a course is 0.217 m and should be 0.086". So this reports screen
/// coverage as a fraction and near-plane clearance in metres, and the picture is
/// there to confirm the numbers rather than to replace them.</para>
///
/// <para>Read-only with respect to the scene: it toggles view models to photograph
/// them one at a time and restores every activation state afterwards.</para>
/// </summary>
public static class WeaponShot
{
    const int k_Width = 1280;
    const int k_Height = 720;

    static string ShotDir =>
        Path.Combine(Path.GetDirectoryName(Application.dataPath) ?? ".", "Screenshots", "weapons");

    [MenuItem("Tools/Aftermarket/Capture Weapon Screenshots")]
    public static void CaptureBatch()
    {
        if (SystemInfo.graphicsDeviceType == UnityEngine.Rendering.GraphicsDeviceType.Null)
        {
            Debug.LogError("[WeaponShot] null graphics device — every render would be a flat " +
                           "fill. Re-run WITHOUT -nographics.");
            return;
        }

        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            // Never discard unsaved work to take a picture. EditorSceneManager.OpenScene
            // from a script does not prompt — it drops changes and clears the undo stack.
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[WeaponShot] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        GameObject player = null;
        foreach (var go in scene.GetRootGameObjects())
            if (go.name == "Player") { player = go; break; }
        if (player == null) { Debug.LogError("[WeaponShot] no 'Player' root."); return; }

        var cam = player.GetComponentInChildren<Camera>(true);
        if (cam == null) { Debug.LogError("[WeaponShot] the Player has no Camera."); return; }

        var holder = FindDeep(player.transform, "WeaponHolder");
        if (holder == null)
        {
            Debug.LogError("[WeaponShot] no WeaponHolder — run Build Weapon Rig first.");
            return;
        }

        Directory.CreateDirectory(ShotDir);

        // Remember what was on, so photographing costs the scene nothing.
        var models = new List<Transform>();
        var wasActive = new Dictionary<Transform, bool>();
        foreach (Transform child in holder)
        {
            models.Add(child);
            wasActive[child] = child.gameObject.activeSelf;
        }

        var report = new StringBuilder();
        report.AppendLine("Weapon view models, rendered from the live Player camera.");
        report.AppendLine($"camera: fov {cam.fieldOfView:F1}, near {cam.nearClipPlane:F3} m, " +
                          $"holder local {holder.localPosition}");
        report.AppendLine();

        foreach (var target in models)
        {
            foreach (var m in models) m.gameObject.SetActive(m == target);

            string path = Path.Combine(ShotDir, $"fp_{target.name}.png");
            Render(cam, path);

            var line = Measure(cam, target);
            report.AppendLine($"{target.name}");
            report.AppendLine("  " + line);
            report.AppendLine($"  -> {Path.GetFileName(path)}");
            report.AppendLine();
            Debug.Log($"[WeaponShot] {target.name}: {line}");
        }

        foreach (var kv in wasActive) kv.Key.gameObject.SetActive(kv.Value);

        File.WriteAllText(Path.Combine(ShotDir, "fp_report.txt"), report.ToString());
        Debug.Log($"[WeaponShot] {models.Count} view model(s) -> {ShotDir}");
    }

    /// <summary>
    /// Screen coverage and near-plane clearance, both derived from the real mesh
    /// bounds rather than from the transform. A view model that pokes through the
    /// near plane is INVISIBLE at the tip while looking correct in the hierarchy,
    /// so clearance is measured along the camera's forward axis, which is the axis
    /// the near plane actually cuts on.
    /// </summary>
    static string Measure(Camera cam, Transform vm)
    {
        var renderers = vm.GetComponentsInChildren<Renderer>(true);
        if (renderers.Length == 0) return "NO RENDERERS — this view model draws nothing.";

        var b = renderers[0].bounds;
        for (int i = 1; i < renderers.Length; i++) b.Encapsulate(renderers[i].bounds);

        float minX = 1f, maxX = 0f, minY = 1f, maxY = 0f;
        float nearest = float.MaxValue, farthest = float.MinValue;
        int behind = 0;

        for (int i = 0; i < 8; i++)
        {
            var corner = new Vector3(
                (i & 1) == 0 ? b.min.x : b.max.x,
                (i & 2) == 0 ? b.min.y : b.max.y,
                (i & 4) == 0 ? b.min.z : b.max.z);

            var vp = cam.WorldToViewportPoint(corner);
            if (vp.z <= 0f) behind++;
            minX = Mathf.Min(minX, vp.x); maxX = Mathf.Max(maxX, vp.x);
            minY = Mathf.Min(minY, vp.y); maxY = Mathf.Max(maxY, vp.y);

            float depth = Vector3.Dot(corner - cam.transform.position, cam.transform.forward);
            nearest = Mathf.Min(nearest, depth);
            farthest = Mathf.Max(farthest, depth);
        }

        float coverW = Mathf.Clamp01(maxX) - Mathf.Clamp01(minX);
        float coverH = Mathf.Clamp01(maxY) - Mathf.Clamp01(minY);
        float clearance = nearest - cam.nearClipPlane;

        var notes = new List<string>();
        if (behind > 0) notes.Add($"{behind}/8 corners BEHIND the camera");
        if (clearance < 0f) notes.Add($"CLIPPED by the near plane by {-clearance:F3} m");
        if (coverW > 0.75f) notes.Add("fills over 75% of screen width");
        if (coverW < 0.05f) notes.Add("under 5% of screen width — barely visible");
        if (minX > 1f || maxX < 0f || minY > 1f || maxY < 0f) notes.Add("ENTIRELY OFF SCREEN");

        return $"size {b.size.x:F2}x{b.size.y:F2}x{b.size.z:F2} m, " +
               $"depth {nearest:F3}..{farthest:F3} m, near-plane clearance {clearance:+0.000;-0.000} m, " +
               $"screen {coverW * 100f:F0}% w x {coverH * 100f:F0}% h" +
               (notes.Count > 0 ? "  [" + string.Join("; ", notes) + "]" : "  [ok]");
    }

    static void Render(Camera cam, string path)
    {
        var rt = new RenderTexture(k_Width, k_Height, 24, RenderTextureFormat.ARGB32)
        { antiAliasing = 4 };
        var prev = cam.targetTexture;
        cam.targetTexture = rt;
        cam.Render();

        var active = RenderTexture.active;
        RenderTexture.active = rt;
        var tex = new Texture2D(k_Width, k_Height, TextureFormat.RGB24, false);
        tex.ReadPixels(new Rect(0, 0, k_Width, k_Height), 0, 0);
        tex.Apply();
        RenderTexture.active = active;
        cam.targetTexture = prev;

        File.WriteAllBytes(path, tex.EncodeToPNG());
        Object.DestroyImmediate(tex);
        rt.Release();
        Object.DestroyImmediate(rt);
    }

    static Transform FindDeep(Transform root, string name)
    {
        if (root.name == name) return root;
        foreach (Transform c in root)
        {
            var hit = FindDeep(c, name);
            if (hit != null) return hit;
        }
        return null;
    }
}
