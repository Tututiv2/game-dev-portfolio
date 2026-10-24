using System.Text;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Asks UNITY what each zombie prefab is actually wearing, instead of reading the
/// YAML and inferring it.
///
/// <para>This exists because the two disagreed. Every file on disk says
/// <c>Enemy_Skin1.prefab -> SK_ZombieM_02_Mesh1.fbx -> ZombieM_02_Body_Skin1.mat
/// -> TX_ZombieM_02_Body_Skin1_Albedo.png</c>, that PNG is byte-equivalent to the
/// vendor's own TGA, and it is a BLUE ice texture — and yet the render of skin 1
/// comes out flat olive-green with the blue channel at exactly 0 on half its body
/// pixels. Reading more YAML cannot settle that. Only the loaded material can.</para>
///
/// <para>It reports the resolved shader, the bound albedo, and the MEAN COLOUR OF
/// THE PIXELS UNITY ACTUALLY HAS — decoded off the raw file rather than off the
/// imported <c>Texture2D</c>, because an imported texture is not readable unless
/// its importer says so and silently returns black when it is not. A diagnostic
/// that reports black because it could not read is worse than none.</para>
/// </summary>
public static class ZombieSkinAudit
{
    [MenuItem("Tools/Aftermarket/Audit Zombie Skins")]
    public static void Audit()
    {
        var sb = new StringBuilder();
        sb.AppendLine("ZOMBIE SKIN AUDIT — what Unity resolves, not what the YAML says");
        sb.AppendLine();

        for (int skin = 1; skin <= 3; skin++)
        {
            string path = ZombieImporter.PrefabPathForSkin(skin);
            var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(path);
            sb.AppendLine("=== skin " + skin + "  (" + ZombieImporter.SkinLook(skin) + ")");
            sb.AppendLine("    prefab: " + path + (prefab == null ? "   *** NOT FOUND ***" : ""));
            if (prefab == null) { sb.AppendLine(); continue; }

            foreach (var r in prefab.GetComponentsInChildren<Renderer>(true))
            {
                foreach (var m in r.sharedMaterials)
                {
                    if (m == null) { sb.AppendLine("    " + r.name + ": NULL MATERIAL SLOT"); continue; }
                    var tex = m.HasProperty("_BaseMap") ? m.GetTexture("_BaseMap") : null;
                    sb.AppendLine("    renderer '" + r.name + "'");
                    sb.AppendLine("        material  " + m.name + "   (asset " +
                                  AssetDatabase.GetAssetPath(m) + ")");
                    sb.AppendLine("        shader    " + (m.shader == null ? "NULL" : m.shader.name));
                    sb.AppendLine("        _BaseMap  " + (tex == null ? "NONE" :
                                  tex.name + "  (" + AssetDatabase.GetAssetPath(tex) + ")"));
                    sb.AppendLine("        mean of that file's pixels: " + MeanOfFile(tex));
                    sb.AppendLine("        _BaseColor " + Col(m, "_BaseColor") +
                                  "   _EmissionColor " + Col(m, "_EmissionColor") +
                                  "   _EMISSION=" + m.IsKeywordEnabled("_EMISSION"));
                    sb.AppendLine("        _Metallic " + Get(m, "_Metallic") +
                                  "  _Smoothness " + Get(m, "_Smoothness") +
                                  "  renderQueue " + m.renderQueue);
                }
            }
            sb.AppendLine();
        }

        Debug.Log(sb.ToString());
        System.IO.Directory.CreateDirectory("Screenshots/zombie");
        System.IO.File.WriteAllText("Screenshots/zombie/skin_audit.txt", sb.ToString());
    }

    static string Col(Material m, string p)
    {
        if (!m.HasProperty(p)) return p + "=absent";
        Color c = m.GetColor(p);
        return string.Format("({0:0.###}, {1:0.###}, {2:0.###}, {3:0.###})", c.r, c.g, c.b, c.a);
    }

    static string Get(Material m, string p)
    {
        return m.HasProperty(p) ? m.GetFloat(p).ToString("0.###") : "absent";
    }

    /// <summary>
    /// Mean RGB of the texture's SOURCE FILE, decoded into a throwaway readable
    /// Texture2D. Reading <c>tex.GetPixels()</c> directly would need
    /// <c>isReadable</c> on the importer and throws otherwise — which would make
    /// this report a property of the import settings while looking like a
    /// property of the art.
    /// </summary>
    static string MeanOfFile(Texture t)
    {
        if (t == null) return "n/a";
        string p = AssetDatabase.GetAssetPath(t);
        if (string.IsNullOrEmpty(p) || !System.IO.File.Exists(p)) return "source file missing";

        var tmp = new Texture2D(2, 2);
        if (!tmp.LoadImage(System.IO.File.ReadAllBytes(p), false))
        {
            Object.DestroyImmediate(tmp);
            return "could not decode " + System.IO.Path.GetExtension(p);
        }

        // Sample a grid rather than every texel: these are 4096², and reading 16.7
        // million pixels three times over is minutes of nothing useful.
        const int N = 96;
        double r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = 0; y < N; y++)
        {
            for (int x = 0; x < N; x++)
            {
                Color c = tmp.GetPixelBilinear((x + 0.5f) / N, (y + 0.5f) / N);
                r += c.r; g += c.g; b += c.b; n++;
            }
        }
        int w = tmp.width, h = tmp.height;
        Object.DestroyImmediate(tmp);
        return string.Format("({0:0.###}, {1:0.###}, {2:0.###})  [{3}x{4} source]",
                             r / n, g / n, b / n, w, h);
    }
}
