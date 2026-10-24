using System.Globalization;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Photographs the Assassin under every theme and MEASURES the result.
///
/// <para>This exists because the two things being claimed cannot be checked any
/// other way. "The Black variant is bound to `_BaseMap` correctly" is an
/// assertion about a YAML file; "the character is no longer white" is an
/// assertion about pixels. The whole reason this character was broken is that a
/// material named Black held the white texture and nothing anywhere complained,
/// so a binding check alone would be measuring something adjacent to the
/// complaint.</para>
///
/// <para>Lighting is flat neutral grey on purpose. The zombie labelling went
/// wrong three times partly because renders were taken under
/// <c>belfast_sunset_puresky</c> and "cold blue-grey" turned out to describe the
/// sky. A colour judgement needs neutral light or it is a judgement about the
/// lamp.</para>
/// </summary>
public static class CharacterShot
{
    const string k_Out = "Screenshots/character";
    const string k_Prefab = "Assets/_Project/Art/Characters/Inquisitor/Inquisitor.prefab";
    const string k_Catalog = "Assets/_Project/Content/Themes/ThemeCatalog.asset";

    [MenuItem("Tools/Aftermarket/Capture Character Screenshots")]
    public static void Capture()
    {
        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(k_Prefab);
        if (prefab == null)
        {
            Debug.LogError("[CharacterShot] no prefab at " + k_Prefab +
                           " — run Tools > Aftermarket > Build Character Select.");
            return;
        }
        var catalog = AssetDatabase.LoadAssetAtPath<ThemeCatalog>(k_Catalog);
        if (catalog == null)
        {
            Debug.LogError("[CharacterShot] no theme catalog at " + k_Catalog + ".");
            return;
        }

        Directory.CreateDirectory(k_Out);
        var report = new StringBuilder();
        report.AppendLine("THE ASSASSIN — one render per theme, under FLAT NEUTRAL light.");
        report.AppendLine("Hue is the saturation-weighted circular mean of the subject pixels.");
        report.AppendLine();

        GameObject stage = null, camGo = null, keyGo = null, fillGo = null;
        RenderTexture rt = null;

        try
        {
            stage = new GameObject("ShotStage") { hideFlags = HideFlags.HideAndDontSave };

            keyGo = new GameObject("Key") { hideFlags = HideFlags.HideAndDontSave };
            keyGo.transform.rotation = Quaternion.Euler(35f, 205f, 0f);
            Light key = keyGo.AddComponent<Light>();
            key.type = LightType.Directional;
            key.intensity = 1.5f;
            key.color = Color.white;

            fillGo = new GameObject("Fill") { hideFlags = HideFlags.HideAndDontSave };
            fillGo.transform.rotation = Quaternion.Euler(15f, 25f, 0f);
            Light fill = fillGo.AddComponent<Light>();
            fill.type = LightType.Directional;
            fill.intensity = 0.5f;
            fill.color = Color.white;

            var prevAmbientMode = RenderSettings.ambientMode;
            var prevAmbient = RenderSettings.ambientLight;
            RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
            RenderSettings.ambientLight = new Color(0.30f, 0.30f, 0.30f, 1f);

            camGo = new GameObject("Cam") { hideFlags = HideFlags.HideAndDontSave };
            Camera cam = camGo.AddComponent<Camera>();
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = new Color(0.05f, 0.05f, 0.05f, 1f);
            cam.fieldOfView = 34f;
            cam.cullingMask = ~0;
            camGo.transform.position = new Vector3(0f, 1.0f, 2.9f);
            camGo.transform.rotation = Quaternion.Euler(2f, 180f, 0f);

            rt = new RenderTexture(720, 1080, 24, RenderTextureFormat.ARGB32);
            cam.targetTexture = rt;

            // Both clothes materials, because the interesting fact is the
            // DIFFERENCE between them. The Black atlas is near-greyscale
            // (measured mean saturation 0.048), and a hue rotation of a grey is
            // invisible by definition — so shooting only the shipped default
            // would show a theme system that "does nothing" and prove nothing
            // about whether it works.
            string[] clothes =
            {
                "Assets/_Project/Art/Characters/Inquisitor/Materials/Inquisitor_Clothes_Black.mat",
                "Assets/_Project/Art/Characters/Inquisitor/Materials/Inquisitor_Clothes_Red.mat",
            };

            foreach (string clothPath in clothes)
            {
            var cloth = AssetDatabase.LoadAssetAtPath<Material>(clothPath);
            report.AppendLine("--- clothes: " + Path.GetFileNameWithoutExtension(clothPath) +
                              (cloth == null ? "   *** MISSING ***" : "") + " ---");

            foreach (ThemeDefinition theme in catalog.themes)
            {
                if (theme == null) continue;

                GameObject body = (GameObject)PrefabUtility.InstantiatePrefab(prefab);
                body.hideFlags = HideFlags.HideAndDontSave;
                body.transform.SetParent(stage.transform, false);
                body.transform.localPosition = Vector3.zero;

                if (cloth != null) SwapClothes(body, cloth);

                int touched = ThemeSystem.Apply(body, theme);

                cam.Render();
                Texture2D tex = ReadBack(rt);
                string tag = Path.GetFileNameWithoutExtension(clothPath).Replace("Inquisitor_Clothes_", "").ToLowerInvariant();
                string path = k_Out + "/inquisitor_" + tag + "_" + theme.themeId + ".png";
                File.WriteAllBytes(path, tex.EncodeToPNG());

                float hue, sat, val;
                int px;
                Measure(tex, cam.backgroundColor, out hue, out sat, out val, out px);

                // A whole-model mean cannot tell cloth from armour, so it cannot
                // show whether the region mask is doing anything — it averages
                // the two back together. Split the subject by saturation: the
                // dyed cloth carries colour, the metal is desaturated by
                // construction (measured albedo saturation 0.329 vs 0.069). The
                // armour bucket must stay PUT across themes that only name
                // region 0; that is the mask working, and nothing else here can
                // see it.
                float clothHue, clothSat, metalHue, metalSat;
                int clothPx, metalPx;
                MeasureSplit(tex, cam.backgroundColor, 0.18f,
                             out clothHue, out clothSat, out clothPx,
                             out metalHue, out metalSat, out metalPx);
                Object.DestroyImmediate(tex);

                report.AppendLine(string.Format(CultureInfo.InvariantCulture,
                    "{0,-9} cloth shift {1,6:0.00} sat x{2,4:0.00} val x{3,4:0.00} | " +
                    "armour shift {4,6:0.00} sat x{5,4:0.00}  ->  " +
                    "ALL hue {6:0.000} sat {7:0.000} val {8:0.000} | " +
                    "CLOTH hue {9:0.000} sat {10:0.000} ({11}px) | " +
                    "ARMOUR hue {12:0.000} sat {13:0.000} ({14}px) | {15} rend",
                    theme.displayName,
                    theme.Region(0).hueShift, theme.Region(0).saturationMul, theme.Region(0).valueMul,
                    theme.Region(1).hueShift, theme.Region(1).saturationMul,
                    hue, sat, val,
                    clothHue, clothSat, clothPx,
                    metalHue, metalSat, metalPx, touched));

                Object.DestroyImmediate(body);
            }
            report.AppendLine();
            }

            RenderSettings.ambientMode = prevAmbientMode;
            RenderSettings.ambientLight = prevAmbient;
        }
        finally
        {
            if (rt != null) { rt.Release(); Object.DestroyImmediate(rt); }
            if (camGo != null) Object.DestroyImmediate(camGo);
            if (keyGo != null) Object.DestroyImmediate(keyGo);
            if (fillGo != null) Object.DestroyImmediate(fillGo);
            if (stage != null) Object.DestroyImmediate(stage);
        }

        File.WriteAllText(k_Out + "/report.txt", report.ToString());
        Debug.Log("[CharacterShot]\n" + report);
    }

    /// <summary>
    /// Put <paramref name="cloth"/> on every slot currently wearing an
    /// Inquisitor_Clothes_* material. Matched by NAME rather than by slot index:
    /// the index differs per renderer and a hardcoded one would silently repaint
    /// the head on some of them.
    /// </summary>
    static void SwapClothes(GameObject root, Material cloth)
    {
        foreach (Renderer r in root.GetComponentsInChildren<Renderer>(true))
        {
            Material[] mats = r.sharedMaterials;
            bool changed = false;
            for (int i = 0; i < mats.Length; i++)
            {
                if (mats[i] != null && mats[i].name.StartsWith("Inquisitor_Clothes_"))
                {
                    mats[i] = cloth;
                    changed = true;
                }
            }
            if (changed) r.sharedMaterials = mats;
        }
    }

    /// <summary>
    /// Split the subject into "carries colour" and "does not" at
    /// <paramref name="satSplit"/>, and measure each separately.
    ///
    /// <para>This is a proxy for the region mask, not a readout of it — it
    /// segments the FINAL IMAGE by saturation rather than sampling the mask. That
    /// is deliberate: a readout of the mask would tell you what the mask says,
    /// and the question is what the PIXELS did. If the armour bucket's hue moves
    /// when only the cloth region was themed, the mask is not working no matter
    /// what the texture contains.</para>
    /// </summary>
    static void MeasureSplit(Texture2D tex, Color bg, float satSplit,
                             out float hiHue, out float hiSat, out int hiCount,
                             out float loHue, out float loSat, out int loCount)
    {
        Color[] px = tex.GetPixels();
        double hx = 0, hy = 0, hs = 0, lx = 0, ly = 0, ls = 0;
        hiCount = 0;
        loCount = 0;
        foreach (Color c in px)
        {
            if (Mathf.Abs(c.r - bg.r) + Mathf.Abs(c.g - bg.g) + Mathf.Abs(c.b - bg.b) < 0.06f) continue;
            float h, s, v;
            Color.RGBToHSV(c, out h, out s, out v);
            // Near-black pixels have a hue that is numerically real and visually
            // meaningless; they would drag both buckets around.
            if (v < 0.04f) continue;
            float cx = s * Mathf.Cos(h * 2f * Mathf.PI);
            float cy = s * Mathf.Sin(h * 2f * Mathf.PI);
            if (s >= satSplit) { hx += cx; hy += cy; hs += s; hiCount++; }
            else { lx += cx; ly += cy; ls += s; loCount++; }
        }
        hiHue = Ang(hx, hy);
        loHue = Ang(lx, ly);
        hiSat = hiCount > 0 ? (float)(hs / hiCount) : 0f;
        loSat = loCount > 0 ? (float)(ls / loCount) : 0f;
    }

    static float Ang(double x, double y)
    {
        float a = Mathf.Atan2((float)y, (float)x) / (2f * Mathf.PI);
        return a < 0f ? a + 1f : a;
    }

    static Texture2D ReadBack(RenderTexture rt)
    {
        RenderTexture prev = RenderTexture.active;
        RenderTexture.active = rt;
        var tex = new Texture2D(rt.width, rt.height, TextureFormat.RGB24, false);
        tex.ReadPixels(new Rect(0, 0, rt.width, rt.height), 0, 0);
        tex.Apply();
        RenderTexture.active = prev;
        return tex;
    }

    /// <summary>
    /// Subject = anything that is not the flat background. Hue is averaged as a
    /// CIRCLE, weighted by saturation: a plain arithmetic mean of hue puts the
    /// average of red (0.02) and red (0.98) in the middle of the wheel at cyan,
    /// and near-grey pixels have a hue that is numerically real and visually
    /// meaningless.
    /// </summary>
    static void Measure(Texture2D tex, Color bg, out float hue, out float sat, out float val, out int count)
    {
        Color[] px = tex.GetPixels();
        double sx = 0, sy = 0, ss = 0, sv = 0;
        count = 0;
        foreach (Color c in px)
        {
            if (Mathf.Abs(c.r - bg.r) + Mathf.Abs(c.g - bg.g) + Mathf.Abs(c.b - bg.b) < 0.06f) continue;
            float h, s, v;
            Color.RGBToHSV(c, out h, out s, out v);
            sx += s * Mathf.Cos(h * 2f * Mathf.PI);
            sy += s * Mathf.Sin(h * 2f * Mathf.PI);
            ss += s;
            sv += v;
            count++;
        }
        if (count == 0) { hue = sat = val = 0f; return; }
        float ang = Mathf.Atan2((float)sy, (float)sx) / (2f * Mathf.PI);
        hue = ang < 0f ? ang + 1f : ang;
        sat = (float)(ss / count);
        val = (float)(sv / count);
    }
}
