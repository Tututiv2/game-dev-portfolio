using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Reassembles the cut modules and photographs them.
///
/// <para>Everything else about this pipeline is measured — tri counts, collider
/// thickness, standable space, the axis mapping. None of that can tell you the
/// modules line up, because a seam is only a seam to a pair of eyes. This puts
/// every module back at its own cell coordinate and renders the result.</para>
///
/// <para>It also reports the reassembled bounds against the source, which IS a
/// measurement: if slicing lost or shifted anything, the footprint changes.</para>
/// </summary>
public static class KitModuleShot
{
    const int k_Width = 1600;
    const int k_Height = 900;
    static readonly string k_OutDir = "Screenshots/kitmodules";

    [MenuItem("Tools/Aftermarket/Shoot Kit Modules")]
    public static void Shoot()
    {
        Directory.CreateDirectory(k_OutDir);
        var report = new StringBuilder();

        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
        RenderSettings.ambientLight = new Color(0.30f, 0.31f, 0.35f);

        var key = new GameObject("~Key");
        var light = key.AddComponent<Light>();
        light.type = LightType.Directional;
        light.intensity = 1.6f;
        light.color = new Color(1f, 0.96f, 0.90f);
        key.transform.rotation = Quaternion.Euler(42f, 138f, 0f);

        var placed = new List<GameObject>();
        var stairModules = new List<GameObject>();

        foreach (var mpath in KitModuleBuilder.ManifestPaths())
        {
            var man = KitModuleBuilder.Load(mpath);
            if (!KitModuleBuilder.DeriveManifestMapping(man, out var map, out var why))
            {
                report.AppendLine($"  {Path.GetFileName(mpath)}: {why}");
                continue;
            }
            report.AppendLine($"  axis mapping {why}");

            foreach (var mod in man.modules)
            {
                string path = $"{KitModuleBuilder.ModuleFolder}/Kit_{mod.name}.prefab";
                var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(path);
                if (prefab == null)
                {
                    report.AppendLine($"  {mod.name}: no prefab");
                    continue;
                }

                // The module's origin is the centre of its own cell, in Blender space.
                // Mapping that point is what puts it back where it was cut from —
                // using the SAME derivation the collision used, so a placement error
                // and a collision error cannot disagree.
                float cell = man.cell_m;
                var blenderCentre = new[] { (mod.cell[0] + 0.5f) * cell,
                                            (mod.cell[1] + 0.5f) * cell, 0f };

                var go = (GameObject)PrefabUtility.InstantiatePrefab(prefab);
                go.transform.position = map.Point(blenderCentre);
                placed.Add(go);
                if (mod.stair_wedge) stairModules.Add(go);
            }
        }

        if (placed.Count == 0)
        {
            Debug.LogError("[KitModuleShot] nothing placed.");
            return;
        }

        var bounds = Combined(placed);
        report.AppendLine($"  reassembled {placed.Count} modules");
        report.AppendLine($"  footprint {bounds.size.x:0.00} x {bounds.size.z:0.00} m, " +
                          $"height {bounds.size.y:0.00} m");
        report.AppendLine($"  origin    {bounds.min.x:0.00}..{bounds.max.x:0.00} x, " +
                          $"{bounds.min.y:0.00}..{bounds.max.y:0.00} y, " +
                          $"{bounds.min.z:0.00}..{bounds.max.z:0.00} z");

        var cam = MakeCamera();
        float span = Mathf.Max(bounds.size.x, bounds.size.z);

        report.AppendLine(Frame(cam, "01_aerial",
            bounds.center + new Vector3(span * 0.85f, span * 0.80f, -span * 0.85f),
            bounds.center));

        report.AppendLine(Frame(cam, "02_plan",
            bounds.center + new Vector3(0f, span * 1.5f, 0.01f), bounds.center));

        // Eye level, standing on the floor plane, looking along the long axis.
        float eye = 1.7f;
        bool longAxisIsZ = bounds.size.z >= bounds.size.x;
        Vector3 from = longAxisIsZ
            ? new Vector3(bounds.center.x, bounds.min.y + eye, bounds.min.z + 0.6f)
            : new Vector3(bounds.min.x + 0.6f, bounds.min.y + eye, bounds.center.z);
        Vector3 to = longAxisIsZ
            ? new Vector3(bounds.center.x, bounds.min.y + eye, bounds.max.z)
            : new Vector3(bounds.max.x, bounds.min.y + eye, bounds.center.z);
        report.AppendLine(Frame(cam, "03_eye_along", from, to));

        report.AppendLine(Frame(cam, "04_eye_back", to, from));

        if (stairModules.Count > 0)
        {
            var sb = Combined(new List<GameObject> { stairModules[0] });
            report.AppendLine(Frame(cam, "05_stairs",
                sb.center + new Vector3(sb.size.x + 3f, 2.2f, sb.size.z + 3f), sb.center));
        }

        Debug.Log("[KitModuleShot]\n" + report);
        File.WriteAllText(Path.Combine(k_OutDir, "report.txt"), report.ToString());
        EditorSceneManager.SaveScene(scene, "Assets/_Project/Scenes/_KitModulePreview.unity");
    }

    static Bounds Combined(List<GameObject> objs)
    {
        Bounds? b = null;
        foreach (var go in objs)
            foreach (var r in go.GetComponentsInChildren<Renderer>())
            {
                if (b.HasValue) { var c = b.Value; c.Encapsulate(r.bounds); b = c; }
                else b = r.bounds;
            }
        return b ?? new Bounds(Vector3.zero, Vector3.one);
    }

    static Camera MakeCamera()
    {
        var go = new GameObject("~Cam");
        var cam = go.AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.16f, 0.17f, 0.20f);
        cam.nearClipPlane = 0.05f;
        cam.farClipPlane = 900f;
        cam.fieldOfView = 60f;
        go.AddComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>();
        return cam;
    }

    /// <summary>
    /// Renders one frame and reports mean luminance plus distinct colour count.
    /// A blank render — camera inside geometry, no device, wrong pipeline — is a
    /// perfectly valid PNG, and "1 unique colour" is the only thing that says so.
    /// </summary>
    static string Frame(Camera cam, string name, Vector3 pos, Vector3 look)
    {
        cam.transform.position = pos;
        cam.transform.rotation = Quaternion.LookRotation((look - pos).normalized, Vector3.up);

        var rt = new RenderTexture(k_Width, k_Height, 24, RenderTextureFormat.ARGB32,
                                   RenderTextureReadWrite.sRGB) { antiAliasing = 4 };
        var tex = new Texture2D(k_Width, k_Height, TextureFormat.RGBA32, false, false);
        try
        {
            cam.targetTexture = rt;
            cam.Render();
            cam.targetTexture = null;

            var prev = RenderTexture.active;
            RenderTexture.active = rt;
            tex.ReadPixels(new Rect(0, 0, k_Width, k_Height), 0, 0);
            tex.Apply();
            RenderTexture.active = prev;

            string path = Path.Combine(k_OutDir, name + ".png");
            File.WriteAllBytes(path, tex.EncodeToPNG());

            var px = tex.GetPixels32();
            double sum = 0;
            var unique = new HashSet<int>();
            for (int i = 0; i < px.Length; i += 7)
            {
                var p = px[i];
                sum += 0.2126 * p.r + 0.7152 * p.g + 0.0722 * p.b;
                unique.Add((p.r >> 3 << 10) | (p.g >> 3 << 5) | (p.b >> 3));
            }
            int n = px.Length / 7 + 1;
            return $"  {name,-14} mean luma {sum / n / 255.0:0.000}, {unique.Count} distinct colours";
        }
        finally
        {
            RenderTexture.active = null;
            UnityEngine.Object.DestroyImmediate(tex);
            rt.Release();
            UnityEngine.Object.DestroyImmediate(rt);
        }
    }
}
