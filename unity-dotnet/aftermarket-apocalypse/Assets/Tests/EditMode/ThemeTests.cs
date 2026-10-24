using NUnit.Framework;
using UnityEngine;

/// <summary>
/// Spec §15.11 — the theme system.
///
/// <para>The tests that matter here are not "a theme can be applied". They are
/// the two ways this feature can be shipped, pass an existence check, and be
/// broken:</para>
///
/// <list type="number">
/// <item>It silently deletes the sprinter readability signal, because
/// <c>Renderer.SetPropertyBlock</c> REPLACES the block rather than merging into
/// it. At round 10+ everything sprints, and §15.11.3 says losing that read is
/// genuinely dangerous. A cosmetic feature quietly disabling a gameplay signal
/// logs nothing and looks fine in a screenshot.</item>
/// <item>It applies to a material whose shader has no <c>_HueShift</c>, does
/// absolutely nothing, and reports success. A MaterialPropertyBlock accepts any
/// property name; the shader decides whether it means anything. That is this
/// project's signature bug — a serialized knob that turns nothing.</item>
/// </list>
/// </summary>
public class ThemeTests
{
    static ThemeDefinition MakeTheme(string id, float hue, float sat, float val)
    {
        var t = ScriptableObject.CreateInstance<ThemeDefinition>();
        t.themeId = id;
        t.displayName = id;
        t.regions = new[] { new RegionTint { hueShift = hue, saturationMul = sat, valueMul = val } };
        return t;
    }

    /// <summary>
    /// A renderer carrying a shader that DOES declare the recolour properties.
    /// Built on URP/Lit plus a property block is not enough — <c>HasProperty</c>
    /// asks the SHADER — so this uses whatever Recolor shader exists, and skips
    /// rather than lies when it does not.
    /// </summary>
    static GameObject MakeRecolourable(out Renderer r)
    {
        Shader s = Shader.Find("Shader Graphs/Recolor");
        if (s == null) s = Shader.Find("Aftermarket/Recolor");
        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        r = go.GetComponent<Renderer>();
        if (s != null)
        {
            // sharedMaterial on a primitive is Unity's global default material.
            // Assigning a NEW material avoids mutating it for the whole project —
            // ZombieShot got this wrong and permanently tinted Default-Material.
            r.sharedMaterial = new Material(s);
        }
        return go;
    }

    [Test]
    public void Theme_ApplyDoesNotEraseTheSprinterTint()
    {
        Renderer r;
        GameObject go = MakeRecolourable(out r);
        try
        {
            if (!r.sharedMaterial.HasProperty("_HueShift"))
                Assert.Ignore("Recolor shader not built yet — nothing to compose against.");

            // Exactly what EnemyAI.TintRenderers does for a sprinter.
            var tint = new MaterialPropertyBlock();
            r.GetPropertyBlock(tint);
            tint.SetColor("_BaseColor", Color.white * RoundRules.SprinterTint);
            r.SetPropertyBlock(tint);

            ThemeSystem.Apply(go, MakeTheme("ember", 0.05f, 1.3f, 1f));

            var read = new MaterialPropertyBlock();
            r.GetPropertyBlock(read);
            Color after = read.GetColor("_BaseColor");

            Assert.AreEqual(RoundRules.SprinterTint, after.r, 0.001f,
                "Applying a theme wiped _BaseColor out of the property block, which is the " +
                "sprinter tint (RoundRules.SprinterTint). SetPropertyBlock REPLACES the block; " +
                "ThemeSystem must GetPropertyBlock first and merge. §15.11.3: the theme sets " +
                "the base hue, the type signal applies on top.");

            Assert.AreEqual(0.05f, read.GetFloat("_HueShift"), 0.0001f,
                "The theme's own hue shift did not survive either.");
        }
        finally { Object.DestroyImmediate(go); }
    }

    [Test]
    public void Theme_ApplyReportsZeroWhenTheShaderCannotRecolour()
    {
        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        try
        {
            Shader lit = Shader.Find("Universal Render Pipeline/Lit");
            if (lit == null) Assert.Ignore("URP/Lit not found.");
            go.GetComponent<Renderer>().sharedMaterial = new Material(lit);

            int touched = ThemeSystem.Apply(go, MakeTheme("azure", 0.55f, 1.2f, 1f));

            Assert.AreEqual(0, touched,
                "ThemeSystem said it recoloured a renderer whose shader has no _HueShift. " +
                "A MaterialPropertyBlock accepts any property name and the shader decides " +
                "whether it means anything, so 'the call succeeded' is not evidence the model " +
                "changed colour. It must count only renderers that can actually respond.");
        }
        finally { Object.DestroyImmediate(go); }
    }

    [Test]
    public void Theme_ANullThemeClearsTheRecolourRatherThanDoingNothing()
    {
        Renderer r;
        GameObject go = MakeRecolourable(out r);
        try
        {
            if (!r.sharedMaterial.HasProperty("_HueShift"))
                Assert.Ignore("Recolor shader not built yet.");

            ThemeSystem.Apply(go, MakeTheme("rose", 0.88f, 1.3f, 1.1f));
            ThemeSystem.Clear(go);

            var read = new MaterialPropertyBlock();
            r.GetPropertyBlock(read);
            Assert.AreEqual(0f, read.GetFloat("_HueShift"), 0.0001f,
                "Un-equipping a theme left the previous hue shift in the block, so the " +
                "character can never be returned to its authored colours.");
            Assert.AreEqual(1f, read.GetFloat("_SaturationMul"), 0.0001f);
            Assert.AreEqual(1f, read.GetFloat("_ValueMul"), 0.0001f);
        }
        finally { Object.DestroyImmediate(go); }
    }

    /// <summary>
    /// §15.11.1's whole reason for the mask: a theme that names only the cloth
    /// must leave the armour and the face ALONE. If a missing region fell back to
    /// region 0 instead of to identity, every one-region theme would hue-shift
    /// the metal and the face with it — which is the "everything including his
    /// face is blue" failure the mask exists to prevent, reintroduced in the one
    /// place nobody would look for it.
    /// </summary>
    [Test]
    public void Theme_ARegionTheAuthorDidNotSupplyStaysAtIdentity()
    {
        ThemeDefinition t = MakeTheme("cloth-only", 0.55f, 1.25f, 1f);
        Assert.AreEqual(1, t.regions.Length, "precondition: this theme names only region 0");

        for (int i = 1; i < ThemeSystem.Regions; i++)
        {
            RegionTint r = t.Region(i);
            Assert.AreEqual(0f, r.hueShift, 0.0001f,
                "region " + i + " picked up region 0's hue shift instead of staying neutral.");
            Assert.AreEqual(1f, r.saturationMul, 0.0001f, "region " + i + " saturation is not neutral.");
            Assert.AreEqual(1f, r.valueMul, 0.0001f, "region " + i + " value is not neutral.");
        }
    }

    [Test]
    public void Theme_ApplyPushesEveryRegionNotJustTheFirst()
    {
        Renderer r;
        GameObject go = MakeRecolourable(out r);
        try
        {
            if (!r.sharedMaterial.HasProperty("_HueShift2"))
                Assert.Ignore("Recolor shader has no second region yet.");

            var t = ScriptableObject.CreateInstance<ThemeDefinition>();
            t.themeId = "gilded";
            t.regions = new[]
            {
                new RegionTint { hueShift = 0.10f, saturationMul = 0.30f, valueMul = 0.75f },
                new RegionTint { hueShift = 0.20f, saturationMul = 2.20f, valueMul = 1.15f },
            };

            ThemeSystem.Apply(go, t);

            var read = new MaterialPropertyBlock();
            r.GetPropertyBlock(read);

            Assert.AreEqual(0.10f, read.GetFloat("_HueShift"), 0.0001f, "region 0 hue");
            Assert.AreEqual(0.20f, read.GetFloat("_HueShift2"), 0.0001f,
                "region 1's hue never reached the shader, so the armour channel is decorative.");
            Assert.AreEqual(2.20f, read.GetFloat("_SaturationMul2"), 0.0001f, "region 1 saturation");
            Assert.AreEqual(0f, read.GetFloat("_HueShift3"), 0.0001f,
                "region 2 was not supplied and must be pushed as IDENTITY — leaving it unset " +
                "would let a stale value from a previous theme survive in the block.");
            Assert.AreEqual(1f, read.GetFloat("_SaturationMul3"), 0.0001f, "region 2 saturation");
        }
        finally { Object.DestroyImmediate(go); }
    }

    /// <summary>
    /// A region mask is DATA. Flagged sRGB, every weight is gamma-decoded on
    /// upload and the blend between regions is wrong — the exact trap five of the
    /// Inquisitor's own maps shipped with. And block compression averages across
    /// 4x4 blocks, which bleeds one region's id into its neighbour at precisely
    /// the boundaries the texture exists to define.
    /// </summary>
    [Test]
    public void Theme_TheRegionMasksAreLinearAndUncompressed()
    {
        string dir = "Assets/_Project/Art/Characters/Inquisitor/Textures";
        string[] guids = UnityEditor.AssetDatabase.FindAssets("RegionMask t:Texture2D", new[] { dir });
        if (guids.Length == 0) Assert.Ignore("No region masks yet — run Tools/make_region_mask.py.");

        foreach (string g in guids)
        {
            string path = UnityEditor.AssetDatabase.GUIDToAssetPath(g);
            var imp = (UnityEditor.TextureImporter)UnityEditor.AssetImporter.GetAtPath(path);
            Assert.IsFalse(imp.sRGBTexture,
                path + " is flagged sRGB. It is a region id map, not colour — every weight " +
                "arrives gamma-decoded and the region blend is wrong.");
            Assert.AreEqual(UnityEditor.TextureImporterCompression.Uncompressed, imp.textureCompression,
                path + " is block-compressed, which bleeds region ids across the boundaries " +
                "the mask exists to define.");
        }
    }

    [Test]
    public void Theme_TheCatalogResolvesByIdAndRefusesToInventOne()
    {
        var cat = ScriptableObject.CreateInstance<ThemeCatalog>();
        var ember = MakeTheme("ember", 0.05f, 1.3f, 1f);
        cat.themes = new[] { ember, MakeTheme("void", 0f, 0.15f, 0.45f) };

        Assert.AreSame(ember, cat.Find("ember"));
        Assert.IsNull(cat.Find("nope"),
            "An unknown theme id must resolve to null so the art is left alone. Returning " +
            "the first entry instead would silently dress a player in someone else's colour.");
        Assert.IsNull(cat.Find(null));
    }

    [Test]
    public void Theme_AThemeWithNoRegionsIsNeutralRatherThanNull()
    {
        var t = ScriptableObject.CreateInstance<ThemeDefinition>();
        t.regions = null;
        Assert.IsNotNull(t.Primary,
            "ThemeDefinition.Primary must never be null — a NullReferenceException inside " +
            "the render path is a far worse failure than an unthemed model.");
        Assert.IsTrue(t.IsIdentity);
    }

    [Test]
    public void Theme_TheSeededCatalogHoldsBackTheAdminOnlyOnes()
    {
        var cat = UnityEditor.AssetDatabase.LoadAssetAtPath<ThemeCatalog>(
            "Assets/_Project/Content/Themes/ThemeCatalog.asset");
        if (cat == null)
            Assert.Ignore("Themes not built yet — Tools > Aftermarket > Build Character Select.");

        int admin = 0, free = 0;
        foreach (ThemeDefinition t in cat.themes)
        {
            if (t == null) continue;
            if (t.adminOnly) admin++; else free++;
        }

        Assert.Greater(free, 0, "Every theme is admin-only, so a normal player sees a wall of " +
                                "locked swatches and the feature reads as broken.");
        Assert.Greater(admin, 0, "No theme is admin-only, so §15.12.2's locked-but-visible " +
                                 "presentation is never exercised and will rot untested.");
    }

    [Test]
    public void Theme_EveryThemeIdIsUnique()
    {
        var cat = UnityEditor.AssetDatabase.LoadAssetAtPath<ThemeCatalog>(
            "Assets/_Project/Content/Themes/ThemeCatalog.asset");
        if (cat == null) Assert.Ignore("Themes not built yet.");

        var seen = new System.Collections.Generic.HashSet<string>();
        foreach (ThemeDefinition t in cat.themes)
        {
            if (t == null) continue;
            Assert.IsTrue(seen.Add(t.themeId),
                "Two themes share the id '" + t.themeId + "'. The id is the entitlement key " +
                "(§15.12.2) and the PlayerPrefs key, so a duplicate makes one theme " +
                "unreachable and grants the other's purchase.");
        }
    }
}
