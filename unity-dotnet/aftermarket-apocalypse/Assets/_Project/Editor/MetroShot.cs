using System.Collections.Generic;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Photographs Ashfall Metro from the places a player stands.
///
/// <para>Everything else about this map is measured — reachability, the loop, spawn
/// clearance. None of it can say whether the map is legible: whether you can tell a
/// door from a wall, or see far enough to know where to run. That needs eyes, and
/// eyes need pictures taken at 1.7 m rather than from orbit.</para>
/// </summary>
public static class MetroShot
{
    const int k_Width = 1600;
    const int k_Height = 900;
    const string k_OutDir = "Screenshots/metro";
    const float Eye = 1.7f;

    /// <summary>from, to — both at eye height unless the name says otherwise.</summary>
    static readonly (string name, Vector3 from, Vector3 to)[] k_Views =
    {
        ("03_spawn_looking_west",  new Vector3(34f, Eye, 12f), new Vector3(18f, Eye, 12f)),
        ("04_spawn_looking_east",  new Vector3(30f, Eye, 12f), new Vector3(46f, Eye, 12f)),
        ("05_concourse",           new Vector3(8f,  Eye, 8f),  new Vector3(8f,  Eye, 26f)),
        ("06_spine_to_north_door", new Vector3(32f, Eye, 26f), new Vector3(32f, Eye, 38f)),
        ("07_north_hall",          new Vector3(38f, Eye, 46f), new Vector3(18f, Eye, 40f)),
        ("08_maintenance_door",    new Vector3(40f, Eye, 12f), new Vector3(56f, Eye, 13f)),
        ("08b_maintenance_stair",  new Vector3(56f, Eye, 5f),  new Vector3(50f, Eye, 15f)),
        ("09_vault_pap",           new Vector3(50f, Eye, 38f), new Vector3(53f, Eye, 46f)),
        ("10_plant_room",          new Vector3(8f,  Eye, 40f), new Vector3(6f,  Eye, 48f)),
    };

    [MenuItem("Tools/Aftermarket/Shoot New Map (Ashfall Metro)")]
    public static void Shoot()
    {
        if (!MapBake.OpenSceneIfNeeded("MetroShot", MetroSeeder.ScenePath)) return;
        Directory.CreateDirectory(k_OutDir);

        var cam = MakeCamera();
        var report = new StringBuilder("[MetroShot]\n");

        // An aerial, and a PLAN that cuts below the ceilings — a top-down shot of a
        // roofed building is a photograph of its roof.
        report.AppendLine(Frame(cam, "01_aerial", new Vector3(-14f, 46f, -10f),
                                new Vector3(30f, 0f, 30f), 60f, 0.05f));
        // near is measured FROM THE CAMERA, so a cut at 2.6 m is (camY - 2.6), not
        // (someOtherNumber - 2.6). Getting that wrong clipped at 6.6 m, which is
        // above the 3.5 m ceilings — so the "cut" plan was a photograph of the roofs.
        const float camY = 44f, cutAt = 2.6f;
        report.AppendLine(Frame(cam, "02_plan_cut", new Vector3(30f, camY, 30f),
                                new Vector3(30f, 0f, 30f), 75f, camY - cutAt));

        foreach (var v in k_Views)
            report.AppendLine(Frame(cam, v.name, v.from, v.to, 70f, 0.05f));

        Debug.Log(report.ToString());
        File.WriteAllText(Path.Combine(k_OutDir, "report.txt"), report.ToString());
    }

    static Camera MakeCamera()
    {
        var go = new GameObject("~MetroCam");
        var cam = go.AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.10f, 0.11f, 0.14f);
        cam.farClipPlane = 400f;
        cam.cullingMask = ~0;
        var data = go.AddComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>();
        data.renderPostProcessing = true;
        return cam;
    }

    static string Frame(Camera cam, string name, Vector3 pos, Vector3 look, float fov, float near)
    {
        cam.transform.position = pos;
        var dir = (look - pos).normalized;
        // Looking straight down, LookRotation(-Y, +Y) is degenerate and Unity picks
        // an arbitrary roll — which silently rotated the plan shot so north was not
        // up and it did not match the ASCII plan it is supposed to confirm.
        var up = Mathf.Abs(dir.y) > 0.95f ? Vector3.forward : Vector3.up;
        cam.transform.rotation = Quaternion.LookRotation(dir, up);
        cam.fieldOfView = fov;
        cam.nearClipPlane = near;

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

            File.WriteAllBytes(Path.Combine(k_OutDir, name + ".png"), tex.EncodeToPNG());

            // A blank render is a perfectly valid PNG. "1 distinct colour" is the
            // only thing that tells you the camera was inside a wall.
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
            return $"  {name,-26} mean luma {sum / n / 255.0:0.000}, {unique.Count} distinct colours";
        }
        finally
        {
            RenderTexture.active = null;
            Object.DestroyImmediate(tex);
            rt.Release();
            Object.DestroyImmediate(rt);
        }
    }
}
