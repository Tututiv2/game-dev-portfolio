using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Renders the zombie prefab from four sides and measures it, because this
/// project's own rule is that nothing counts until somebody has looked at it.
///
/// <para><b>Why the zombie specifically needs this.</b> Three of its failure
/// modes are completely invisible to a test suite AND to the import log:</para>
///
/// <list type="bullet">
/// <item><b>Scale.</b> A character FBX that imports at 100x is a 180 m zombie.
/// Every component is present, every reference resolves, and nothing warns.</item>
/// <item><b>Materials.</b> The pack's model remaps its two slots to the
/// <i>Built-in</i> materials, which were not carried over, so an unfixed import
/// renders untextured grey or magenta — with a clean import log.</item>
/// <item><b>Facing.</b> A model rotated the wrong way moonwalks. The yaw is
/// derived from the foot bones now rather than typed, and the measurement said
/// 0 degrees where a hand-written literal had said 180 — so the derivation was
/// worth having, and looking at the render is how that gets confirmed rather
/// than merely believed.</item>
/// </list>
///
/// <para><b>Run WITHOUT -nographics.</b> A null graphics device renders every
/// frame as a flat fill that looks like a valid PNG on disk. The per-shot
/// "distinct colours" count below is what catches it — the same guard
/// <see cref="SceneShot"/> carries, for the same reason.</para>
///
/// <code>
/// Unity.exe -batchmode -projectPath . -executeMethod ZombieShot.Capture -quit
/// </code>
///
/// <para>Read-only with respect to the scene: everything it creates is
/// <c>HideAndDontSave</c> and destroyed in a finally, and it never saves.</para>
/// </summary>
public static class ZombieShot
{
    const int k_Width = 900;
    const int k_Height = 1200;
    const string k_OutDir = "Screenshots/zombie";

    /// <summary>
    /// Staged far above the map so nothing occludes it and the background is
    /// plain sky — a clean silhouette is what makes a scale or facing error
    /// obvious. The map's own Sun and HDRI ambient still light it, so the
    /// materials are being judged under the lighting the game actually uses.
    /// </summary>
    static readonly Vector3 k_Stage = new Vector3(0f, 200f, 0f);

    /// <summary>
    /// Shoots EVERY imported skin, not just the primary.
    ///
    /// <para>It used to shoot <c>Enemy.prefab</c> alone. With three looks on disk
    /// that is a contact sheet of one third of the art, and the two it omits are
    /// exactly the two nobody has ever looked at — which is how this project
    /// shipped six unusable props that all passed their checks.</para>
    /// </summary>
    [MenuItem("Tools/Aftermarket/Capture Zombie Screenshots")]
    public static void Capture()
    {
        int shot = 0;
        for (int skin = 1; skin <= 3; skin++)
        {
            var path = ZombieImporter.PrefabPathForSkin(skin);
            var p = AssetDatabase.LoadAssetAtPath<GameObject>(path);
            if (p == null) continue;
            CaptureOne(p, skin);
            shot++;
        }
        if (shot == 0)
            Debug.LogError("[ZombieShot] no zombie prefabs at all under " + ZombieImporter.ArtRoot +
                           ". Run 'python Tools/import_zombie.py' then Tools > Aftermarket > " +
                           "Import Zombie.");
        else
            Debug.Log("[ZombieShot] captured " + shot + " zombie skin(s).");
    }

    static void CaptureOne(GameObject prefab, int skin)
    {
        // Each skin gets its own folder so the four angles never overwrite
        // another skin's, which would leave a directory that looks complete and
        // shows one zombie four times.
        string outDir = k_OutDir + "/skin" + skin;
        Directory.CreateDirectory(outDir);

        GameObject zombie = null, cube = null, camGo = null;
        var report = new StringBuilder();

        try
        {
            zombie = (GameObject)PrefabUtility.InstantiatePrefab(prefab);
            zombie.hideFlags = HideFlags.HideAndDontSave;
            zombie.transform.position = k_Stage;

            // A 1 m cube beside it. Texture and model scale are both unanswerable
            // by eye; this project already learned that on the kit brick, where
            // "the brick looks too big" only became actionable as "a course is
            // 0.217 m and should be 0.086" once there was a ruler in frame.
            cube = GameObject.CreatePrimitive(PrimitiveType.Cube);
            cube.hideFlags = HideFlags.HideAndDontSave;
            cube.transform.position = k_Stage + new Vector3(1.2f, 0.5f, 0f);
            cube.GetComponent<Renderer>().sharedMaterial.color = new Color(0f, 0.9f, 1f);

            camGo = new GameObject("ZombieShotCam") { hideFlags = HideFlags.HideAndDontSave };
            var cam = camGo.AddComponent<Camera>();
            cam.fieldOfView = 40f;
            cam.nearClipPlane = 0.05f;
            cam.farClipPlane = 500f;

            // MEASURE FIRST, and report it whatever the pictures look like.
            report.AppendLine(Measure(zombie));

            // The yaw here places the CAMERA, not the subject. The model faces the
            // root's +Z, so seeing its FRONT means standing on the +Z side and
            // looking back — yaw 0. The first version had these labels inverted
            // and produced a picture captioned "front" showing the back of the
            // head, which is precisely the kind of confidently-wrong caption this
            // project's SceneShot notes already record once.
            var angles = new (string name, float yaw)[]
            {
                ("front", 0f), ("left", 90f), ("back", 180f), ("right", 270f),
            };

            foreach (var (name, yaw) in angles)
            {
                var focus = k_Stage + Vector3.up * 0.95f;
                var dir = Quaternion.Euler(0f, yaw, 0f) * Vector3.forward;
                camGo.transform.position = focus + dir * 3.2f + Vector3.up * 0.15f;
                camGo.transform.LookAt(focus);

                string path = Path.Combine(outDir, name + ".png");
                report.AppendLine("  " + name.PadRight(6) + " " + RenderToFile(cam, path));
            }

            File.WriteAllText(Path.Combine(outDir, "report.txt"), report.ToString());
            Debug.Log("[ZombieShot] skin " + skin + " -> " + outDir + "/\n" + report);
        }
        finally
        {
            if (zombie != null) Object.DestroyImmediate(zombie);
            if (cube != null) Object.DestroyImmediate(cube);
            if (camGo != null) Object.DestroyImmediate(camGo);
        }
    }

    /// <summary>
    /// Every line here is READ OFF the instance. Nothing is asserted — a report
    /// that describes the code instead of the object is worse than no report,
    /// which this project has already paid for once in SceneShot's report.txt.
    /// </summary>
    static string Measure(GameObject zombie)
    {
        var sb = new StringBuilder();
        var renderers = zombie.GetComponentsInChildren<Renderer>(true);

        if (renderers.Length == 0) return "NO RENDERERS AT ALL.\n";

        var bounds = renderers[0].bounds;
        foreach (var r in renderers) bounds.Encapsulate(r.bounds);

        sb.AppendFormat(CultureInfo.InvariantCulture,
            "zombie bounds  {0:0.00} w x {1:0.00} h x {2:0.00} d (metres)\n",
            bounds.size.x, bounds.size.y, bounds.size.z);
        sb.AppendLine("  a human-scale zombie is about 1.8 m tall. 180 or 0.018 means the FBX " +
                      "unit scale is wrong.");

        sb.AppendLine("materials:");
        var seen = new HashSet<Material>();
        foreach (var r in renderers)
            foreach (var m in r.sharedMaterials)
            {
                if (m == null) { sb.AppendLine("  EMPTY SLOT on " + r.name); continue; }
                if (!seen.Add(m)) continue;
                string baseMap = m.HasProperty("_BaseMap") && m.GetTexture("_BaseMap") != null
                    ? m.GetTexture("_BaseMap").name
                    : "NO ALBEDO BOUND";
                sb.AppendLine("  " + m.name + "  shader=" + m.shader.name + "  albedo=" + baseMap);
            }

        var head = zombie.GetComponentsInChildren<Transform>(true);
        int tagged = 0;
        foreach (var t in head) if (t.CompareTag("Head")) tagged++;
        sb.AppendLine("head-tagged colliders: " + tagged + "  (must be 1, or headshots do not exist)");

        var animator = zombie.GetComponentInChildren<Animator>(true);
        sb.AppendLine("model yaw: " + (animator != null
            ? animator.transform.localEulerAngles.y.ToString("F0", CultureInfo.InvariantCulture) + " deg (derived from the foot bones at build time)"
            : "NO ANIMATOR"));

        sb.AppendLine("ragdoll: " + zombie.GetComponentsInChildren<Rigidbody>(true).Length +
                      " bodies, " + zombie.GetComponentsInChildren<CharacterJoint>(true).Length +
                      " joints");
        sb.AppendLine("renders:");
        return sb.ToString();
    }

    /// <summary>
    /// Renders one frame and reports mean luminance and distinct-colour count. A
    /// blank render is byte-plausible and looks identical to a good one in a file
    /// listing; "1 distinct colour" is the tell.
    /// </summary>
    static string RenderToFile(Camera cam, string path)
    {
        var rt = new RenderTexture(k_Width, k_Height, 24, RenderTextureFormat.ARGB32,
                                   RenderTextureReadWrite.sRGB) { antiAliasing = 4 };
        var tex = new Texture2D(k_Width, k_Height, TextureFormat.RGBA32, false, false);
        try
        {
            cam.targetTexture = rt;
            cam.Render();
            cam.targetTexture = null;

            var previous = RenderTexture.active;
            RenderTexture.active = rt;
            tex.ReadPixels(new Rect(0, 0, k_Width, k_Height), 0, 0);
            tex.Apply();
            RenderTexture.active = previous;

            var bytes = tex.EncodeToPNG();
            File.WriteAllBytes(path, bytes);

            var pixels = tex.GetPixels32();
            double sum = 0;
            var unique = new HashSet<int>();
            for (int i = 0; i < pixels.Length; i += 7)
            {
                var p = pixels[i];
                sum += 0.2126 * p.r + 0.7152 * p.g + 0.0722 * p.b;
                unique.Add((p.r >> 3 << 10) | (p.g >> 3 << 5) | (p.b >> 3));
            }
            int sampled = (pixels.Length + 6) / 7;

            return string.Format(CultureInfo.InvariantCulture,
                "{0} KB, mean luma {1:0.0}/255, {2} distinct colours{3}",
                bytes.Length / 1024, sum / sampled, unique.Count,
                unique.Count < 8 ? "   <-- SUSPECT: essentially a flat fill" : "");
        }
        finally
        {
            cam.targetTexture = null;
            Object.DestroyImmediate(tex);
            rt.Release();
            Object.DestroyImmediate(rt);
        }
    }
}
