using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// Applies a <see cref="ThemeDefinition"/> to a live object — spec §15.11 and
/// §15.12.4.
///
/// <para><b>MaterialPropertyBlock, never material instancing.</b> Touching
/// <c>renderer.material</c> clones the material, which breaks SRP batching and
/// leaks an instance per object. A block costs nothing.</para>
///
/// <para><b>It works at any moment on a live object, by design.</b>
/// <c>CamoSystem</c> assigns materials only in <c>Awake</c> (spec §13.10), so
/// changing a camo needs a scene reload. The store's live preview and equipping
/// without restarting both depend on this one not repeating that.</para>
///
/// <para><b>It reads the existing block before writing.</b>
/// <c>Renderer.SetPropertyBlock</c> REPLACES the whole block, it does not merge.
/// <c>EnemyAI.TintRenderers</c> writes <c>_BaseColor</c> into the same block to
/// mark sprinters, and a plain <c>SetPropertyBlock(new MaterialPropertyBlock())</c>
/// here would erase that — a gameplay-readability signal (§15.11.3) silently
/// deleted by a cosmetic feature. Get, modify, set.</para>
///
/// <para><b>Why it never touches _BaseColor.</b> The theme moves hue, saturation
/// and value INSIDE the shader; <c>_BaseColor</c> stays free for the sprinter
/// multiplier, which <c>Recolor.shadergraph</c> applies to the RESULT of the HSV
/// transform. That is what "the theme sets the base hue; the existing type signals
/// apply on top of it" means in practice, and it is why the two compose instead of
/// fighting.</para>
/// </summary>
public static class ThemeSystem
{
    // Must match Recolor.shadergraph's exposed property reference names. One set
    // per _RegionMask channel: [0] R = cloth, [1] G = armour, [2] B = leather/skin.
    static readonly int[] k_HueShift =
    {
        Shader.PropertyToID("_HueShift"),
        Shader.PropertyToID("_HueShift2"),
        Shader.PropertyToID("_HueShift3"),
    };
    static readonly int[] k_SaturationMul =
    {
        Shader.PropertyToID("_SaturationMul"),
        Shader.PropertyToID("_SaturationMul2"),
        Shader.PropertyToID("_SaturationMul3"),
    };
    static readonly int[] k_ValueMul =
    {
        Shader.PropertyToID("_ValueMul"),
        Shader.PropertyToID("_ValueMul2"),
        Shader.PropertyToID("_ValueMul3"),
    };

    /// <summary>How many mask channels the shader implements.</summary>
    public const int Regions = 3;

    /// <summary>Optional lookup for the string overload. Assign at boot.</summary>
    public static ThemeCatalog Catalog;

    /// <summary>
    /// The theme every zombie spawns wearing (§15.11.3). Read once in
    /// <c>EnemyAI.Start</c>, before the sprinter tint.
    ///
    /// <para>Static rather than a field on the spawner because a zombie can be
    /// pooled or spawned from anywhere, and a per-spawner copy is a second source
    /// of truth that drifts. Null means "the art as authored", which is the
    /// default and costs nothing.</para>
    /// </summary>
    public static ThemeDefinition ZombieTheme;

    /// <summary>
    /// Resolve a theme id. Returns null when unknown — callers treat that as
    /// "leave the art alone", which is the safe direction.
    /// </summary>
    public static ThemeDefinition Resolve(string themeId)
    {
        if (string.IsNullOrEmpty(themeId) || themeId == "default") return null;
        if (Catalog == null) return null;
        return Catalog.Find(themeId);
    }

    /// <summary>§15.12.4's one-call form.</summary>
    public static int Apply(GameObject root, string themeId)
    {
        return Apply(root, Resolve(themeId));
    }

    /// <summary>
    /// Push <paramref name="theme"/> onto every renderer under
    /// <paramref name="root"/>. Returns how many renderers were actually
    /// affected. A null theme CLEARS the recolour back to identity rather than
    /// doing nothing, so un-equipping works.
    /// </summary>
    public static int Apply(GameObject root, ThemeDefinition theme)
    {
        if (root == null) return 0;

        // A theme that names only region 0 leaves 1 and 2 at identity, which with
        // a mask bound means "recolour the cloth, leave the armour silver and the
        // face alone". That is §15.11.1's whole point, and it is the DEFAULT
        // rather than something an author has to remember to do.
        var tints = new RegionTint[Regions];
        for (int i = 0; i < Regions; i++)
        {
            tints[i] = theme != null ? theme.Region(i) : new RegionTint();
        }

        var block = new MaterialPropertyBlock();
        int touched = 0;
        int deaf = 0;
        string deafExample = null;

        foreach (Renderer r in root.GetComponentsInChildren<Renderer>(true))
        {
            if (r is ParticleSystemRenderer) continue;

            // A renderer whose shader has no _HueShift will accept the block and
            // ignore it — the feature silently does nothing while every log line
            // says it worked. Count those and say so.
            bool listens = false;
            foreach (Material m in r.sharedMaterials)
            {
                if (m != null && m.HasProperty(k_HueShift[0])) { listens = true; break; }
            }
            if (!listens)
            {
                deaf++;
                if (deafExample == null)
                {
                    Material m0 = r.sharedMaterial;
                    deafExample = r.name + " (" +
                                  (m0 == null || m0.shader == null ? "no material" : m0.shader.name) + ")";
                }
                continue;
            }

            r.GetPropertyBlock(block);
            for (int i = 0; i < Regions; i++)
            {
                block.SetFloat(k_HueShift[i], tints[i].hueShift);
                block.SetFloat(k_SaturationMul[i], tints[i].saturationMul);
                block.SetFloat(k_ValueMul[i], tints[i].valueMul);
            }
            r.SetPropertyBlock(block);
            touched++;
        }

        if (deaf > 0 && theme != null && !theme.IsIdentity)
        {
            Debug.LogWarning("[Theme] '" + theme.themeId + "' was applied to " + root.name +
                             " but " + deaf + " renderer(s) use a shader with no _HueShift, so " +
                             "they will not recolour — e.g. " + deafExample + ". Switch those " +
                             "materials to Recolor.shadergraph, or the theme is a no-op there.");
        }

        return touched;
    }

    /// <summary>
    /// Remove any recolour, leaving every other property in the block alone.
    /// </summary>
    public static void Clear(GameObject root)
    {
        Apply(root, (ThemeDefinition)null);
    }
}
