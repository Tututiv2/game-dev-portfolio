using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Restore the vendor's own alpha settings on the imported character materials.
///
/// <para><b>THE BUG THIS FIXES.</b> Hair and eyelashes are alpha-cut CARDS — flat
/// quads with a texture whose alpha decides which pixels exist. The vendor ships
/// them on URP/Lit with <c>_AlphaClip 1</c>, <c>_AlphaToMask 1</c> and a tuned
/// cutoff (0.222 for Miyoko's hair, 0.366 for her lashes). The import authored
/// fresh URP/Lit materials with alpha clipping OFF, and
/// <c>CharacterSelectBuilder</c> then moved every material in the folder onto the
/// Recolor Shader Graph, which has no alpha clip at all.</para>
///
/// <para>Rendered opaque, every hair card becomes a solid quad, so the hair
/// fuses into one connected slab. That is the whole of "they don't look like
/// what I bought" — the MESHES are byte-for-byte identical to the vendor's and
/// the TEXTURES are the same files at the vendor's own maxTextureSize. Nothing
/// was made low-poly. Two float fields and a shader keyword were dropped.</para>
///
/// <para>Matching is BY BASE TEXTURE, not by material name. The vendor calls it
/// <c>MAT_MIYOKO_HAIR</c> and the import calls it <c>Miyoko_Miyoko_Hair</c>;
/// there is no name rule that survives both packs, and inventing one is how the
/// wrong settings would get copied onto the wrong material while every log line
/// looked healthy.</para>
/// </summary>
public static class CharacterMaterialRepair
{
    const string k_Characters = "Assets/_Project/Art/Characters";
    const string k_UrpLit = "Universal Render Pipeline/Lit";

    // The paid packs live in the old project. CLAUDE.md records that it is kept
    // intact for exactly this reason.
    static readonly string[] k_VendorRoots =
    {
        @"..\Game creation\Zombies\Zombies Unity\Assets\Ida Faber",
        @"..\Game creation\Zombies\Zombies Unity\Assets\IdaFaber",
    };

    struct Alpha
    {
        public bool clip;
        public bool toMask;
        public float cutoff;
        public string from;
    }

    [MenuItem("Tools/Aftermarket/Repair Character Materials")]
    public static void Repair()
    {
        var vendor = ReadVendorAlpha(out int matsRead);
        if (vendor == null) return;

        var log = new StringBuilder();
        log.AppendLine($"Read {matsRead} vendor material(s); {vendor.Count} base texture(s) " +
                       "have alpha settings worth restoring.");

        Shader urpLit = Shader.Find(k_UrpLit);
        if (urpLit == null) { Debug.LogError("[MatRepair] URP/Lit shader not found."); return; }

        int fixedCount = 0, checkedCount = 0;
        foreach (string guid in AssetDatabase.FindAssets("t:Material", new[] { k_Characters }))
        {
            string path = AssetDatabase.GUIDToAssetPath(guid);
            var mat = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (mat == null) continue;
            checkedCount++;

            var tex = mat.HasProperty("_BaseMap") ? mat.GetTexture("_BaseMap") : null;
            if (tex == null) continue;

            Alpha a;
            if (!vendor.TryGetValue(tex.name, out a) || !a.clip) continue;

            // The threshold property is named _Cutoff on BOTH shaders, which is
            // what lets a vendor cutoff survive the move onto Recolor. It is
            // always set; the rest depends on which shader the material is on.
            mat.SetFloat("_Cutoff", a.cutoff);

            bool isShaderGraph = mat.shader != urpLit;
            if (isShaderGraph)
            {
                // A Shader Graph with Alpha Clipping enabled in its target clips
                // unconditionally — there is no _AlphaClip toggle and no
                // _ALPHATEST_ON keyword to set. Writing them here would be two
                // more knobs that turn nothing.
                mat.renderQueue = (int)UnityEngine.Rendering.RenderQueue.AlphaTest;
            }
            else
            {
                mat.SetFloat("_AlphaClip", 1f);
                mat.SetFloat("_AlphaToMask", 1f);
                mat.SetFloat("_Surface", 0f);      // still Opaque queue, just cut
                mat.SetFloat("_ZWrite", 1f);
                // On URP/Lit the KEYWORD is what switches the branch on. Setting
                // the float alone leaves the material looking correct in the
                // inspector and rendering solid — the exact shape of this bug.
                mat.EnableKeyword("_ALPHATEST_ON");
                mat.renderQueue = (int)UnityEngine.Rendering.RenderQueue.AlphaTest;
            }

            EditorUtility.SetDirty(mat);
            fixedCount++;
            log.AppendLine($"  {Path.GetFileName(path),-38} <- {a.from,-30} " +
                           $"cutoff {a.cutoff:F3}, alpha clip ON");
        }

        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();

        if (fixedCount == 0)
            log.AppendLine("  nothing needed repair — every cut-out material already clips.");
        Debug.Log($"[MatRepair] checked {checkedCount} material(s), repaired {fixedCount}.\n" + log);
    }

    /// <summary>
    /// Parse the vendor .mat files as TEXT. They belong to a different Unity
    /// project, so AssetDatabase cannot load them and the YAML is the only way
    /// in. Only the fields that were lost are read.
    /// </summary>
    static Dictionary<string, Alpha> ReadVendorAlpha(out int matsRead)
    {
        matsRead = 0;
        var roots = new List<string>();
        string project = Directory.GetParent(Application.dataPath).FullName;
        foreach (string r in k_VendorRoots)
        {
            string full = Path.GetFullPath(Path.Combine(project, r));
            if (Directory.Exists(full)) roots.Add(full);
        }
        if (roots.Count == 0)
        {
            Debug.LogError("[MatRepair] the vendor packs were not found next to this project " +
                           "(expected ../Game creation/Zombies/Zombies Unity/Assets/...). " +
                           "They are the only record of the settings the characters shipped with.");
            return null;
        }

        // guid -> texture filename, so a material's _BaseMap can be named.
        var texByGuid = new Dictionary<string, string>();
        foreach (string root in roots)
            foreach (string meta in Directory.GetFiles(root, "*.meta", SearchOption.AllDirectories))
            {
                string asset = meta.Substring(0, meta.Length - 5);
                string ext = Path.GetExtension(asset).ToLowerInvariant();
                if (ext != ".png" && ext != ".tga") continue;
                var m = Regex.Match(File.ReadAllText(meta), @"guid: ([a-f0-9]{32})");
                if (m.Success) texByGuid[m.Groups[1].Value] = Path.GetFileNameWithoutExtension(asset);
            }

        var result = new Dictionary<string, Alpha>();
        foreach (string root in roots)
            foreach (string matPath in Directory.GetFiles(root, "*.mat", SearchOption.AllDirectories))
            {
                string text = File.ReadAllText(matPath);
                matsRead++;

                var bm = Regex.Match(text,
                    @"- _BaseMap:\s*\r?\n\s*m_Texture: \{fileID: \d+, guid: ([a-f0-9]{32})");
                if (!bm.Success) continue;
                string texName;
                if (!texByGuid.TryGetValue(bm.Groups[1].Value, out texName)) continue;

                var a = new Alpha
                {
                    clip = Val(text, "_AlphaClip") > 0.5f,
                    toMask = Val(text, "_AlphaToMask") > 0.5f,
                    cutoff = Val(text, "_Cutoff"),
                    from = Path.GetFileNameWithoutExtension(matPath),
                };
                if (!a.clip) continue;

                // Several colourways share one texture and agree on the cutoff;
                // first wins, and a disagreement is reported rather than silently
                // resolved.
                Alpha existing;
                if (result.TryGetValue(texName, out existing))
                {
                    if (Mathf.Abs(existing.cutoff - a.cutoff) > 0.001f)
                        Debug.LogWarning($"[MatRepair] '{texName}' has two vendor cutoffs " +
                                         $"({existing.cutoff} from {existing.from}, {a.cutoff} " +
                                         $"from {a.from}); keeping the first.");
                    continue;
                }
                result[texName] = a;
            }

        return result;
    }

    static float Val(string text, string key)
    {
        var m = Regex.Match(text, key + @": ([-\d.eE]+)");
        float v;
        return m.Success && float.TryParse(m.Groups[1].Value,
            System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out v) ? v : 0f;
    }
}
