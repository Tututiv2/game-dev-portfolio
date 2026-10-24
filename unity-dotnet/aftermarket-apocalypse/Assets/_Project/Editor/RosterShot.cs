using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Photographs EVERY character in the catalog. <c>Tools > Aftermarket > Capture
/// Roster Screenshots</c>. Needs a graphics device — run without -nographics.
///
/// <para><b>Why this exists.</b> CharacterShot renders the Inquisitor and only the
/// Inquisitor, which was correct when the roster was one. Six characters were then
/// imported, verified by the importer's own end-state checks, wired into a catalog
/// and committed — with nobody having seen a single one of them. Every check that
/// existed asks whether the pieces resolve, and a model can resolve perfectly and
/// still be inside-out, untextured or two centimetres tall.</para>
///
/// <para>It measures as well as renders, because a magenta character and a correct
/// one are the same file size: mean saturation and the share of pixels near URP's
/// error colour are both reported, so "it imported" and "it looks right" stay
/// separate claims.</para>
/// </summary>
public static class RosterShot
{
    const int k_W = 640;
    const int k_H = 900;

    static string Dir => Path.Combine(Path.GetDirectoryName(Application.dataPath) ?? ".",
                                      "Screenshots", "roster");

    [MenuItem("Tools/Aftermarket/Capture Roster Screenshots")]
    public static void Capture()
    {
        if (SystemInfo.graphicsDeviceType == UnityEngine.Rendering.GraphicsDeviceType.Null)
        {
            Debug.LogError("[RosterShot] null graphics device — every render would be a flat " +
                           "fill. Re-run WITHOUT -nographics.");
            return;
        }

        var catalog = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(
            "Assets/_Project/Content/Characters/CharacterCatalog.asset");
        if (catalog == null || catalog.characters == null || catalog.characters.Length == 0)
        {
            Debug.LogError("[RosterShot] no CharacterCatalog — run Build Character Select.");
            return;
        }

        Directory.CreateDirectory(Dir);
        var report = new StringBuilder();
        report.AppendLine("Roster, one render each, flat neutral light.");
        report.AppendLine("magenta% is the share of pixels near URP's shader-error colour.");
        report.AppendLine();

        // AMBIENT IS PINNED, and this is the whole point of the shot.
        //
        // The first version set three white lights and left RenderSettings alone,
        // so it inherited the ambient of whatever scene happened to be open. The
        // Assassin's clothes are (0.318, 0.310, 0.308) and its body (0.607, 0.605,
        // 0.598) -- both essentially NEUTRAL -- so the model takes the colour of
        // whatever lights it, and it came back salmon. Miyoko, whose suit carries
        // its own value, came back correct in the same pass, which is exactly what
        // makes this kind of error survive review.
        //
        // CLAUDE.md records this precise mistake being made three times on the
        // zombie skins, where "cold blue-grey" turned out to describe
        // belfast_sunset_puresky and not the zombie at all. A render is albedo x
        // lighting x whatever is broken, so a shot used to JUDGE albedo has to own
        // its lighting.
        var prevMode = RenderSettings.ambientMode;
        var prevAmb = RenderSettings.ambientLight;
        var prevSky = RenderSettings.skybox;
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
        RenderSettings.ambientLight = new Color(0.34f, 0.34f, 0.34f, 1f);
        RenderSettings.skybox = null;

        var stage = new GameObject("~RosterStage") { hideFlags = HideFlags.HideAndDontSave };

        // Flat neutral light on purpose. A themed or tinted model has to be judged
        // on its own colour — the zombie labelling went wrong three times because
        // "cold blue-grey" turned out to describe the map's HDRI, not the zombie.
        foreach (var dir in new[] { new Vector3(30f, 20f, 0f), new Vector3(20f, 200f, 0f),
                                    new Vector3(70f, 110f, 0f) })
        {
            var l = new GameObject("L", typeof(Light)) { hideFlags = HideFlags.HideAndDontSave };
            l.transform.SetParent(stage.transform);
            l.transform.rotation = Quaternion.Euler(dir);
            var light = l.GetComponent<Light>();
            light.type = LightType.Directional;
            light.intensity = dir.y > 100f ? 0.55f : 1.1f;
            light.color = Color.white;
        }

        var camGo = new GameObject("Cam", typeof(Camera)) { hideFlags = HideFlags.HideAndDontSave };
        camGo.transform.SetParent(stage.transform);
        var cam = camGo.GetComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.13f, 0.13f, 0.16f);
        cam.fieldOfView = 35f;

        foreach (var def in catalog.characters)
        {
            if (def == null || def.prefab == null)
            {
                report.AppendLine($"{(def == null ? "<null>" : def.characterId),-14} NO PREFAB");
                continue;
            }

            var inst = (GameObject)PrefabUtility.InstantiatePrefab(def.prefab);
            inst.hideFlags = HideFlags.HideAndDontSave;
            inst.transform.position = Vector3.zero;
            inst.transform.rotation = Quaternion.Euler(0f, 165f, 0f);

            var rs = inst.GetComponentsInChildren<Renderer>(true);
            if (rs.Length == 0)
            {
                report.AppendLine($"{def.characterId,-14} NO RENDERERS");
                Object.DestroyImmediate(inst);
                continue;
            }
            var b = rs[0].bounds;
            for (int i = 1; i < rs.Length; i++) b.Encapsulate(rs[i].bounds);

            // Frame from the measured bounds, so a character authored at a
            // different scale is still fully in shot instead of silently cropped.
            float radius = Mathf.Max(b.size.magnitude * 0.5f, 0.05f);
            float dist = radius / Mathf.Sin(cam.fieldOfView * Mathf.Deg2Rad * 0.5f) * 1.15f;
            cam.transform.position = b.center + new Vector3(0f, b.size.y * 0.06f, -dist);
            cam.transform.LookAt(b.center);
            cam.nearClipPlane = Mathf.Max(dist / 500f, 0.01f);
            cam.farClipPlane = dist * 4f;

            string path = Path.Combine(Dir, def.characterId + ".png");
            var stats = Render(cam, path);

            report.AppendLine(
                $"{def.characterId,-14} {rs.Length,3} rend  " +
                $"h {b.size.y:F2}m  mean sat {stats.sat:F3}  magenta {stats.magenta * 100f:F1}%" +
                (stats.magenta > 0.02f ? "   <-- SHADER ERROR COLOUR ON SCREEN" : "") +
                (stats.distinct < 200 ? "   <-- only " + stats.distinct + " distinct colours" : ""));

            Object.DestroyImmediate(inst);
        }

        Object.DestroyImmediate(stage);
        RenderSettings.ambientMode = prevMode;
        RenderSettings.ambientLight = prevAmb;
        RenderSettings.skybox = prevSky;
        File.WriteAllText(Path.Combine(Dir, "report.txt"), report.ToString());
        Debug.Log("[RosterShot]\n" + report);
    }

    struct Stats { public float sat, magenta; public int distinct; }

    static Stats Render(Camera cam, string path)
    {
        var rt = new RenderTexture(k_W, k_H, 24, RenderTextureFormat.ARGB32) { antiAliasing = 4 };
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
        double sat = 0; int magenta = 0, n = 0;
        var seen = new System.Collections.Generic.HashSet<int>();
        foreach (var p in px)
        {
            float mx = Mathf.Max(p.r, Mathf.Max(p.g, p.b));
            float mn = Mathf.Min(p.r, Mathf.Min(p.g, p.b));
            if (mx < 0.06f) continue;               // background
            n++;
            sat += mx > 0f ? (mx - mn) / mx : 0f;
            // URP's error shader is a saturated magenta: red and blue high, green low.
            if (p.r > 0.6f && p.b > 0.6f && p.g < 0.25f) magenta++;
            seen.Add(((int)(p.r * 31) << 10) | ((int)(p.g * 31) << 5) | (int)(p.b * 31));
        }
        Object.DestroyImmediate(tex);
        rt.Release();
        Object.DestroyImmediate(rt);
        return new Stats
        {
            sat = n > 0 ? (float)(sat / n) : 0f,
            magenta = n > 0 ? magenta / (float)n : 0f,
            distinct = seen.Count,
        };
    }
}
