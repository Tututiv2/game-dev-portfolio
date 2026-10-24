using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.SceneManagement;

/// <summary>
/// Proves <c>Assets/_Project/Art/Shaders/Recolor.shadergraph</c> actually works,
/// in three escalating steps, because this project has been burned repeatedly by
/// checks that measure something adjacent to the thing they guard.
///
/// <list type="number">
/// <item><b>It imports and compiles.</b> The asset loads as a real
/// <see cref="Shader"/>, <see cref="ShaderUtil.ShaderHasError"/> is false, and
/// every property the theme system will address exists under its exact URP/Lit
/// name. A hand-authored ShaderGraph that fails to import loads as null; one
/// that imports with a renamed property loads fine and turns nothing.</item>
///
/// <item><b>It actually recolours.</b> A sphere wearing the shader over a solid
/// red albedo is rendered under controlled light at three
/// <c>_HueShift</c> values and the mean hue of the render is MEASURED. "It
/// compiled" is not proof — a graph wired to the wrong slot compiles perfectly
/// and this whole file exists because of that failure class.</item>
///
/// <item><b>The sprinter order is right.</b> <c>EnemyAI.TintRenderers</c>
/// signals sprinters by writing <c>_BaseColor</c> into a MaterialPropertyBlock
/// at <c>RoundRules.SprinterTint</c> (0.62). Spec §15.11.3 says the theme sets
/// the base hue and the type signal applies ON TOP of it, so the recolour must
/// happen FIRST and <c>_BaseColor</c> must multiply the RESULT.</item>
///
/// <item><b>The regions are independent</b> (§15.11.1's optional
/// <c>_RegionMask</c>). A three-band mask on a QUAD, measured band by band —
/// hue in STEP 4, saturation and value in STEP 5. A whole-image mean would
/// average three correct regions into one wrong number and would pass on a
/// shader that ignored the mask entirely.</item>
///
/// <item><b>The mask is data, not colour</b> (STEP 6). The band mask cannot
/// prove this: its texels are 0 and 255, both fixed points of the sRGB curve,
/// and normalising the weights cancels any common factor. A mask texel of
/// (255,128,0) is where the two readings finally disagree.</item>
/// </list>
///
/// <para><b>STEP 2 is also the no-regression test for the region mask.</b> It
/// leaves <c>_RegionMask</c> unbound, so the ShaderLab <c>"red"</c> default
/// must sample (1,0,0,1) and normalise to weights (1,0,0) — 100% region 0,
/// which is exactly the single-parameter-set behaviour that shipped before
/// regions existed. A <c>"white"</c> default would give (1/3,1/3,1/3) and
/// silently average all three sets on every material in the project.</para>
///
/// <para><b>The grey-tint test alone cannot see the bug it is aimed at.</b>
/// A neutral grey multiply does not move a hue on either side of an HSV round
/// trip, so "same hue, darker" passes for BOTH wirings. The decisive check is
/// <see cref="CheckMultiplyOrder"/>: with a COLOURED <c>_BaseColor</c>, a graph
/// that tints before the conversion produces a hue difference of EXACTLY the
/// hue shift between shift 0 and shift 0.33, because <c>frac(h + s) - h == s</c>
/// no matter what <c>h</c> is. A graph that tints after does not.</para>
///
/// <para><b>Run WITHOUT -nographics.</b> A null graphics device renders every
/// frame as a flat fill; every render below reports its distinct-colour count
/// for exactly that reason, the same guard SceneShot and ZombieShot carry.</para>
///
/// <code>
/// Unity.exe -batchmode -projectPath . -executeMethod RecolorShaderCheck.Run -quit
/// </code>
/// </summary>
public static class RecolorShaderCheck
{
    public const string ShaderPath = "Assets/_Project/Art/Shaders/Recolor.shadergraph";

    const string k_OutDir = "Screenshots/shader";
    const int k_Size = 512;

    /// <summary>Circular tolerance on a measured hue, in turns. Lighting,
    /// the specular lobe and 8-bit quantisation all bleed a little.</summary>
    const float k_HueTolerance = 0.02f;

    /// <summary>Every property the theme system and URP/Lit material migration
    /// depend on. A missing name here is a knob that turns nothing — this
    /// project's signature bug.</summary>
    static readonly string[] k_RequiredProperties =
    {
        "_BaseMap", "_BaseColor", "_BumpMap", "_MetallicGlossMap", "_Metallic",
        "_Smoothness", "_OcclusionMap", "_EmissionMap", "_EmissionColor",
        "_HueShift", "_SaturationMul", "_ValueMul",
        "_HueShift2", "_SaturationMul2", "_ValueMul2",
        "_HueShift3", "_SaturationMul3", "_ValueMul3",
        "_RegionMask",
    };

    static readonly List<string> s_Failures = new List<string>();
    static readonly StringBuilder s_Report = new StringBuilder();

    [MenuItem("Tools/Aftermarket/Check Recolor Shader")]
    public static void RunFromMenu()
    {
        Execute();
        if (s_Failures.Count == 0) Debug.Log("[RecolorShaderCheck] PASS\n" + s_Report);
        else Debug.LogError("[RecolorShaderCheck] FAIL\n" + s_Report);
    }

    /// <summary>Batchmode entry point. Exits non-zero on any failure so a
    /// build script cannot read "it ran" as "it worked".</summary>
    public static void Run()
    {
        int code = 0;
        try
        {
            Execute();
            code = s_Failures.Count == 0 ? 0 : 1;
        }
        catch (Exception e)
        {
            s_Report.AppendLine("EXCEPTION: " + e);
            code = 1;
        }

        string banner = code == 0
            ? "[RecolorShaderCheck] ===== PASS ====="
            : "[RecolorShaderCheck] ===== FAIL =====";
        Debug.Log(banner + "\n" + s_Report);
        Console.WriteLine(banner);
        Console.WriteLine(s_Report.ToString());

        string dir = OutDir();
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, "report.txt"), banner + "\n" + s_Report);

        EditorApplication.Exit(code);
    }

    static string OutDir()
    {
        return Path.GetFullPath(Path.Combine(Application.dataPath, "..", k_OutDir));
    }

    static void Fail(string message)
    {
        s_Failures.Add(message);
        s_Report.AppendLine("  FAIL: " + message);
    }

    static void Line(string message)
    {
        s_Report.AppendLine(message);
    }

    static string F(string format, params object[] args)
    {
        return string.Format(CultureInfo.InvariantCulture, format, args);
    }

    // ------------------------------------------------------------------ steps

    static void Execute()
    {
        s_Failures.Clear();
        s_Report.Length = 0;
        Line("shader asset: " + ShaderPath);
        Line("colour space: " + QualitySettings.activeColorSpace);

        AssetDatabase.Refresh();

        Shader shader = AssetDatabase.LoadAssetAtPath<Shader>(ShaderPath);
        if (shader == null)
        {
            Fail("LoadAssetAtPath<Shader> returned NULL. The .shadergraph did not import as a " +
                 "shader at all — check the Unity log for a ShaderGraph import error.");
            return;
        }

        Line("STEP 1 - import and compile");
        Line("  shader.name       = " + shader.name);
        Line("  ShaderHasError    = " + ShaderUtil.ShaderHasError(shader));

        UnityEditor.ShaderMessage[] messages = ShaderUtil.GetShaderMessages(shader);
        Line("  shader messages   = " + messages.Length);
        foreach (UnityEditor.ShaderMessage m in messages)
        {
            Line("    [" + m.severity + "] " + m.message + "  " + m.messageDetails +
                 "  (" + m.file + ":" + m.line + ", " + m.platform + ")");
            if (m.severity == UnityEditor.Rendering.ShaderCompilerMessageSeverity.Error)
                Fail("shader compiler error: " + m.message + " " + m.messageDetails);
        }
        if (ShaderUtil.ShaderHasError(shader))
            Fail("ShaderUtil.ShaderHasError(shader) is true.");

        ReportProperties(shader);

        if (s_Failures.Count > 0)
        {
            Line("Stopping before the render steps: the shader is not usable.");
            return;
        }

        RenderProofs(shader);
    }

    static void ReportProperties(Shader shader)
    {
        int count = shader.GetPropertyCount();
        var found = new HashSet<string>();
        Line("  properties (" + count + "):");
        for (int i = 0; i < count; i++)
        {
            string name = shader.GetPropertyName(i);
            found.Add(name);
            Line("    " + name.PadRight(20) + " " + shader.GetPropertyType(i) +
                 "  flags=" + shader.GetPropertyFlags(i));
        }

        foreach (string required in k_RequiredProperties)
            if (!found.Contains(required))
                Fail("the shader has no property named '" + required + "'. Materials cannot be " +
                     "switched over from URP/Lit and the theme system cannot address it.");

        CheckRegionMaskDefault(shader);
    }

    /// <summary>
    /// The ShaderLab default for <c>_RegionMask</c> must be <c>"red"</c>.
    ///
    /// <para>Unbound, a "red" default samples (1,0,0,1); normalised that is
    /// weights (1,0,0), i.e. 100% region 0, which is EXACTLY what every
    /// material rendered before regions existed. A "white" default samples
    /// (1,1,1,1) -> (1/3,1/3,1/3) and would silently AVERAGE all three
    /// parameter sets on every material in the project, changing how
    /// already-working art renders with nothing logged anywhere.</para>
    ///
    /// <para>This reads the string out of the imported shader rather than out
    /// of the generator, so it is a check on the artefact and not on the
    /// intent. <see cref="CheckNoRegressionWithNoMask"/> then proves the same
    /// thing by RENDER, because a correct default string with the weights wired
    /// backwards would still pass here.</para>
    /// </summary>
    static void CheckRegionMaskDefault(Shader shader)
    {
        int count = shader.GetPropertyCount();
        for (int i = 0; i < count; i++)
        {
            if (shader.GetPropertyName(i) != "_RegionMask") continue;

            string def = shader.GetPropertyTextureDefaultName(i);
            Line("  _RegionMask ShaderLab default = \"" + def + "\"   dimension = " +
                 shader.GetPropertyTextureDimension(i));
            if (def != "red")
                Fail("_RegionMask's texture default is \"" + def + "\", not \"red\". Unbound it " +
                     "must sample (1,0,0,1) so the weights are (1,0,0) = 100% region 0. Any " +
                     "other default silently changes how every existing material renders.");
            return;
        }
    }

    // ----------------------------------------------------------- the renders

    static void RenderProofs(Shader shader)
    {
        string dir = OutDir();
        Directory.CreateDirectory(dir);

        // Ambient light, the skybox and the reflection probe are SCENE settings,
        // and they are what a measured hue is most easily poisoned by — the
        // map's own HDRI is a blue-grey sky, and this project has already
        // recorded once that "cold blue-grey" described the sky and not the
        // zombie. They are captured, overridden and restored; nothing is ever
        // saved, and no scene is opened or closed. (Creating a scene was tried
        // first and cannot work: batchmode always holds an unsaved untitled
        // scene, which makes an additive NewScene throw.)
        Lighting saved = Lighting.Capture();

        GameObject sphere = null;
        GameObject lightGo = null;
        GameObject camGo = null;
        Material mat = null;
        Texture2D redTex = null;
        Texture2D orangeTex = null;

        try
        {
            // Deliberately dim. The framebuffer is 8-bit ARGB32: a lit red
            // channel that clips at 1.0 loses the very ratio the hue is read
            // from, so total incident light is kept under 1.0. Reflections are
            // switched off outright — an environment specular term is white-ish
            // and would desaturate the subject toward an unreadable hue.
            Lighting.ApplyNeutral(new Color(0.15f, 0.15f, 0.15f, 1f));

            redTex = SolidTexture(new Color32(255, 0, 0, 255));
            orangeTex = SolidTexture(new Color32(255, 128, 64, 255));

            mat = new Material(shader);
            mat.hideFlags = HideFlags.HideAndDontSave;
            mat.SetTexture("_BaseMap", redTex);
            mat.SetColor("_BaseColor", Color.white);
            mat.SetFloat("_Metallic", 0f);
            mat.SetFloat("_Smoothness", 0f);
            mat.SetFloat("_SaturationMul", 1f);
            mat.SetFloat("_ValueMul", 1f);
            mat.SetFloat("_HueShift", 0f);

            sphere = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            sphere.hideFlags = HideFlags.HideAndDontSave;
            sphere.transform.position = Vector3.zero;
            Renderer rend = sphere.GetComponent<Renderer>();
            rend.sharedMaterial = mat;

            lightGo = new GameObject("RecolorCheckLight");
            lightGo.hideFlags = HideFlags.HideAndDontSave;
            Light light = lightGo.AddComponent<Light>();
            light.type = LightType.Directional;
            light.color = Color.white;
            light.intensity = 0.7f;
            light.shadows = LightShadows.None;
            lightGo.transform.rotation = Quaternion.Euler(30f, 20f, 0f);

            camGo = new GameObject("RecolorCheckCam");
            camGo.hideFlags = HideFlags.HideAndDontSave;
            Camera cam = camGo.AddComponent<Camera>();
            cam.orthographic = true;
            cam.orthographicSize = 0.62f;
            cam.clearFlags = CameraClearFlags.SolidColor;
            cam.backgroundColor = Color.black;
            cam.nearClipPlane = 0.05f;
            cam.farClipPlane = 20f;
            camGo.transform.position = new Vector3(0f, 0f, -3f);
            camGo.transform.rotation = Quaternion.identity;

            // ---------- STEP 2: the hue actually moves, with NO region mask
            Line("STEP 2 - it actually recolours, AND regions cause no regression");
            Line("  solid red albedo, white light, _RegionMask deliberately UNBOUND");
            Line("  albedo texel sRGB (255,0,0) -> linear (1,0,0) -> hue 0.0000");
            CheckNoRegressionWithNoMask(mat);

            float[] shifts = { 0f, 0.33f, 0.66f };
            var measured = new float[shifts.Length];
            for (int i = 0; i < shifts.Length; i++)
            {
                mat.SetFloat("_HueShift", shifts[i]);
                rend.SetPropertyBlock(null);
                Sample s = Render(cam, Path.Combine(dir,
                    F("hue_{0:0.00}.png", shifts[i])));
                measured[i] = s.hue;
                Line(F("  _HueShift {0:0.00} -> measured hue {1:0.0000}  (expected {2:0.0000})  " +
                       "value {3:0.000}  sat {4:0.000}  {5}",
                       shifts[i], s.hue, Frac(shifts[i]), s.value, s.saturation, s.Diagnostics()));

                if (s.pixels < 500)
                    Fail(F("only {0} lit subject pixels at _HueShift {1:0.00} — the sphere did " +
                           "not render.", s.pixels, shifts[i]));
                else if (CircularDistance(s.hue, Frac(shifts[i])) > k_HueTolerance)
                    Fail(F("_HueShift {0:0.00} produced hue {1:0.0000}, expected {2:0.0000} " +
                           "(off by {3:0.0000}).", shifts[i], s.hue, Frac(shifts[i]),
                           CircularDistance(s.hue, Frac(shifts[i]))));
            }

            for (int i = 1; i < shifts.Length; i++)
            {
                float movedBy = Frac(measured[i] - measured[0]);
                Line(F("  hue moved by {0:0.0000} for a shift of {1:0.00}", movedBy, shifts[i]));
                if (CircularDistance(movedBy, shifts[i]) > k_HueTolerance)
                    Fail(F("the hue moved by {0:0.0000} when _HueShift was {1:0.00}.",
                           movedBy, shifts[i]));
            }

            // ---------- STEP 3a: the sprinter tint, exactly as EnemyAI applies it
            Line("STEP 3a - _BaseColor tint (MaterialPropertyBlock, as EnemyAI.TintRenderers does)");
            mat.SetFloat("_HueShift", 0.33f);
            rend.SetPropertyBlock(null);
            Sample white = Render(cam, Path.Combine(dir, "tint_white.png"));

            var block = new MaterialPropertyBlock();
            rend.GetPropertyBlock(block);
            // EnemyAI reads the material's own _BaseColor and scales it, so with a
            // white base this is literally Color.white * RoundRules.SprinterTint.
            block.SetColor("_BaseColor", Color.white * 0.62f);
            rend.SetPropertyBlock(block);
            Sample tinted = Render(cam, Path.Combine(dir, "tint_0.62.png"));
            rend.SetPropertyBlock(null);

            Line(F("  _BaseColor white       -> hue {0:0.0000}  value {1:0.000}", white.hue, white.value));
            Line(F("  _BaseColor 0.62 grey   -> hue {0:0.0000}  value {1:0.000}", tinted.hue, tinted.value));
            Line(F("  hue drift {0:0.0000}   value ratio {1:0.000}",
                   CircularDistance(white.hue, tinted.hue),
                   white.value > 0f ? tinted.value / white.value : 0f));

            if (CircularDistance(white.hue, tinted.hue) > k_HueTolerance)
                Fail(F("tinting _BaseColor to 0.62 grey moved the hue from {0:0.0000} to {1:0.0000}. " +
                       "The multiply is on the wrong side of the HSV conversion.",
                       white.hue, tinted.hue));
            if (tinted.value >= white.value * 0.95f)
                Fail(F("tinting _BaseColor to 0.62 grey did not darken the render " +
                       "({0:0.000} -> {1:0.000}). _BaseColor is not reaching Base Color at all.",
                       white.value, tinted.value));

            // ---------- STEP 3b: the decisive order test
            CheckMultiplyOrder(cam, mat, rend, orangeTex, dir);

            // ---------- STEPS 4-6: the region mask
            CheckRegions(cam, light, sphere, shader, dir);
        }
        finally
        {
            if (sphere != null) UnityEngine.Object.DestroyImmediate(sphere);
            if (lightGo != null) UnityEngine.Object.DestroyImmediate(lightGo);
            if (camGo != null) UnityEngine.Object.DestroyImmediate(camGo);
            if (mat != null) UnityEngine.Object.DestroyImmediate(mat);
            if (redTex != null) UnityEngine.Object.DestroyImmediate(redTex);
            if (orangeTex != null) UnityEngine.Object.DestroyImmediate(orangeTex);

            saved.Restore();
        }
    }

    /// <summary>
    /// The scene lighting state this check has to override, captured so it can
    /// be put back exactly. Restoring is done in a <c>finally</c>, and nothing
    /// here ever calls SaveScene — so an open map is returned to the state it
    /// was in even if a measurement throws.
    /// </summary>
    struct Lighting
    {
        AmbientMode m_Mode;
        Color m_Ambient;
        float m_AmbientIntensity;
        Material m_Skybox;
        bool m_Fog;
        DefaultReflectionMode m_ReflectionMode;
        Texture m_CustomReflection;
        float m_ReflectionIntensity;

        public static Lighting Capture()
        {
            var l = new Lighting();
            l.m_Mode = RenderSettings.ambientMode;
            l.m_Ambient = RenderSettings.ambientLight;
            l.m_AmbientIntensity = RenderSettings.ambientIntensity;
            l.m_Skybox = RenderSettings.skybox;
            l.m_Fog = RenderSettings.fog;
            l.m_ReflectionMode = RenderSettings.defaultReflectionMode;
            l.m_CustomReflection = RenderSettings.customReflectionTexture;
            l.m_ReflectionIntensity = RenderSettings.reflectionIntensity;
            return l;
        }

        public static void ApplyNeutral(Color ambient)
        {
            RenderSettings.skybox = null;
            RenderSettings.fog = false;
            RenderSettings.ambientMode = AmbientMode.Flat;
            RenderSettings.ambientLight = ambient;
            RenderSettings.ambientIntensity = 1f;
            RenderSettings.defaultReflectionMode = DefaultReflectionMode.Custom;
            RenderSettings.customReflectionTexture = null;
            RenderSettings.reflectionIntensity = 0f;
            // The flat ambient colour only reaches a shader through the
            // spherical-harmonics probe, which is not recomputed just because
            // the field changed.
            DynamicGI.UpdateEnvironment();
        }

        public void Restore()
        {
            RenderSettings.ambientMode = m_Mode;
            RenderSettings.ambientLight = m_Ambient;
            RenderSettings.ambientIntensity = m_AmbientIntensity;
            RenderSettings.skybox = m_Skybox;
            RenderSettings.fog = m_Fog;
            RenderSettings.defaultReflectionMode = m_ReflectionMode;
            RenderSettings.customReflectionTexture = m_CustomReflection;
            RenderSettings.reflectionIntensity = m_ReflectionIntensity;
            DynamicGI.UpdateEnvironment();
        }
    }

    /// <summary>
    /// The only test here that can tell the two wirings apart.
    ///
    /// <para>If <c>_BaseColor</c> multiplies the albedo BEFORE the RGB-to-HSV
    /// conversion, then the hue at shift 0.33 is <c>frac(h + 0.33)</c> of the
    /// hue at shift 0, whatever the tint is — the difference is EXACTLY the
    /// shift. If it multiplies AFTER, the tint reshapes an already-rotated
    /// colour and the difference is something else entirely.</para>
    ///
    /// <para>The albedo must have all three channels non-zero, or the tint
    /// cannot change its hue and the test degenerates. Pure red would pass
    /// either way.</para>
    /// </summary>
    static void CheckMultiplyOrder(Camera cam, Material mat, Renderer rend,
                                   Texture2D orangeTex, string dir)
    {
        Line("STEP 3b - decisive order test (coloured _BaseColor, orange albedo)");
        mat.SetTexture("_BaseMap", orangeTex);
        var tint = new Color(0.20f, 0.20f, 1.00f, 1f);
        mat.SetColor("_BaseColor", tint);

        mat.SetFloat("_HueShift", 0f);
        Sample at0 = Render(cam, Path.Combine(dir, "order_tint_shift0.00.png"));
        mat.SetFloat("_HueShift", 0.33f);
        Sample at33 = Render(cam, Path.Combine(dir, "order_tint_shift0.33.png"));

        float difference = Frac(at33.hue - at0.hue);
        Line(F("  tint {0}", tint));
        Line(F("  shift 0.00 -> hue {0:0.0000}", at0.hue));
        Line(F("  shift 0.33 -> hue {0:0.0000}", at33.hue));
        Line(F("  difference {0:0.0000}  (== 0.33 would mean the tint is applied BEFORE the " +
               "HSV conversion)", difference));

        if (CircularDistance(difference, 0.33f) <= k_HueTolerance)
            Fail(F("with a coloured _BaseColor the hue moved by exactly the shift ({0:0.0000}). " +
                   "That only happens when _BaseColor feeds the INPUT of the RGB-to-HSV node. " +
                   "The sprinter tint would be hue-shifted and the readability signal destroyed.",
                   difference));
        else
            Line("  OK: the tint is applied to the RESULT of the recolour, per spec 15.11.3.");

        mat.SetColor("_BaseColor", Color.white);
    }

    // ------------------------------------------------------------- the regions

    /// <summary>
    /// The material used for STEP 2 never assigns <c>_RegionMask</c>, so the
    /// three hue measurements it takes ARE the no-regression test — as long as
    /// that stays true. It is asserted rather than trusted, because "we just
    /// never set it" is the kind of fact that stops being true the moment
    /// somebody adds a convenience default to the setup block.
    /// </summary>
    static void CheckNoRegressionWithNoMask(Material mat)
    {
        Texture bound = mat.GetTexture("_RegionMask");
        Line("  mat.GetTexture(\"_RegionMask\") = " +
             (bound == null ? "null  (so the shader falls back to the \"red\" default)"
                            : bound.name));
        if (bound != null)
            Fail("STEP 2's material has a _RegionMask bound (" + bound.name + "), so its three " +
                 "hue measurements no longer test the unbound-default path at all.");
    }

    /// <summary>
    /// STEPS 4-6. The whole point of §15.11.1's optional mask: a theme can
    /// recolour the cloth without dragging the armour and the face with it.
    ///
    /// <para>A QUAD, not a sphere. The regions have to be flat, unambiguous and
    /// separable in screen space; on a sphere every band is a different shading
    /// angle and a hue difference could be the lighting.</para>
    ///
    /// <para>Every band is measured SEPARATELY. A single whole-image mean would
    /// average three correct regions back into one wrong number and would pass
    /// just as happily on a shader that ignored the mask entirely.</para>
    /// </summary>
    static void CheckRegions(Camera cam, Light light, GameObject sphere, Shader shader, string dir)
    {
        Line("STEP 4 - regions are independent (3-band mask on a quad)");

        GameObject quad = null;
        Material mat = null;
        Texture2D albedo = null;
        Texture2D bandMask = null;
        Texture2D blendMask = null;
        Quaternion savedLight = light.transform.rotation;
        bool sphereWasActive = sphere.activeSelf;

        try
        {
            // The sphere and the quad both sit at the origin.
            sphere.SetActive(false);

            // Unity's Quad primitive carries its normal on -Z, so it faces a
            // camera sitting at -Z. Pointing the light straight down +Z gives
            // every band an identical, dead-on Lambert term — so a hue
            // difference BETWEEN bands cannot be a shading artefact, which is
            // the only thing this step is trying to prove.
            light.transform.rotation = Quaternion.Euler(0f, 0f, 0f);

            albedo = SolidTexture(new Color32(255, 0, 0, 255));

            // Left third region 0, middle third region 1, right third region 2.
            // Unity's Quad maps UV (0,0)-(1,1) across local x -0.5..0.5, and the
            // camera looks down +Z with no roll, so texel 0 lands on the LEFT of
            // the image and band index 0 below is region 0.
            bandMask = MaskTexture(new Color32[]
            {
                new Color32(255, 0, 0, 255),
                new Color32(0, 255, 0, 255),
                new Color32(0, 0, 255, 255),
            });

            mat = new Material(shader);
            mat.hideFlags = HideFlags.HideAndDontSave;
            mat.SetTexture("_BaseMap", albedo);
            mat.SetColor("_BaseColor", Color.white);
            mat.SetFloat("_Metallic", 0f);
            mat.SetFloat("_Smoothness", 0f);
            mat.SetTexture("_RegionMask", bandMask);
            SetAllRegions(mat, 0f, 1f, 1f);

            quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
            quad.hideFlags = HideFlags.HideAndDontSave;
            quad.transform.position = Vector3.zero;
            quad.transform.rotation = Quaternion.identity;
            quad.GetComponent<Renderer>().sharedMaterial = mat;

            Line("  mask texture format = " + bandMask.graphicsFormat +
                 "   (a *_SRGB format here would mean the GPU decodes the mask as colour)");
            Line("  albedo = solid red, so every band starts from hue 0.0000");

            float[] regionHues = { 0f, 0.33f, 0.66f };
            mat.SetFloat("_HueShift", regionHues[0]);
            mat.SetFloat("_HueShift2", regionHues[1]);
            mat.SetFloat("_HueShift3", regionHues[2]);

            Color32[] pixels;
            Sample whole = Render(cam, Path.Combine(dir, "region_hue_bands.png"), out pixels);
            Line(F("  whole-image mean hue {0:0.0000} — MEANINGLESS on its own, three regions " +
                   "averaged together. {1}", whole.hue, whole.Diagnostics(4)));

            Band[] bands = MeasureThirds(pixels, k_Size, k_Size);
            if (bands == null)
            {
                Fail("the quad did not render at all — no lit pixels in region_hue_bands.png.");
            }
            else
            {
                for (int i = 0; i < 3; i++)
                {
                    Line(F("  region {0}: _HueShift{1} = {2:0.00} -> measured hue {3:0.0000}  " +
                           "(expected {4:0.0000})  sat {5:0.000}  value {6:0.000}  {7} px",
                           i, i == 0 ? "" : (i + 1).ToString(), regionHues[i], bands[i].hue,
                           Frac(regionHues[i]), bands[i].saturation, bands[i].value,
                           bands[i].subjectPixels));

                    if (bands[i].huePixels < 200)
                        Fail(F("region {0} has only {1} measurable pixels.", i, bands[i].huePixels));
                    else if (CircularDistance(bands[i].hue, Frac(regionHues[i])) > k_HueTolerance)
                        Fail(F("region {0} came back at hue {1:0.0000}, expected {2:0.0000} " +
                               "(off by {3:0.0000}). The mask channels are not driving " +
                               "independent parameter sets.",
                               i, bands[i].hue, Frac(regionHues[i]),
                               CircularDistance(bands[i].hue, Frac(regionHues[i]))));
                }

                // Three regions that are each individually plausible but all
                // EQUAL is what a shader that ignores the mask produces, and a
                // per-region assertion against a hard-coded expectation would
                // catch that only because the expectations differ. Saying it
                // outright costs three lines and cannot be misread.
                for (int i = 0; i < 3; i++)
                    for (int j = i + 1; j < 3; j++)
                        if (CircularDistance(bands[i].hue, bands[j].hue) < 0.05f)
                            Fail(F("regions {0} and {1} came back at the same hue " +
                                   "({2:0.0000} vs {3:0.0000}). The mask is not separating them.",
                                   i, j, bands[i].hue, bands[j].hue));
            }

            // ---------- STEP 5: saturation and value are per-region too
            Line("STEP 5 - saturation and value are per-region, not just hue");
            SetAllRegions(mat, 0f, 1f, 1f);
            mat.SetFloat("_SaturationMul2", 0f);   // region 1 -> greyscale
            mat.SetFloat("_ValueMul3", 0.4f);      // region 2 -> darker

            Sample sv = Render(cam, Path.Combine(dir, "region_sat_value.png"), out pixels);
            Line("  region 0 sat x1 val x1 | region 1 sat x0 val x1 | region 2 sat x1 val x0.4  " +
                 sv.Diagnostics(4));
            Band[] svBands = MeasureThirds(pixels, k_Size, k_Size);
            if (svBands == null)
            {
                Fail("the quad did not render for the saturation/value step.");
            }
            else
            {
                for (int i = 0; i < 3; i++)
                    Line(F("  region {0}: sat {1:0.000}  value {2:0.000}  ({3} px)",
                           i, svBands[i].saturation, svBands[i].value, svBands[i].subjectPixels));

                if (svBands[1].saturation > 0.05f)
                    Fail(F("region 1 has _SaturationMul2 = 0 but measured saturation " +
                           "{0:0.000}; it should be greyscale.", svBands[1].saturation));
                if (svBands[0].saturation < 0.5f)
                    Fail(F("region 0 kept _SaturationMul = 1 but measured saturation " +
                           "{0:0.000}; region 1's zero leaked into it.", svBands[0].saturation));
                if (svBands[2].saturation < 0.5f)
                    Fail(F("region 2 kept _SaturationMul3 = 1 but measured saturation " +
                           "{0:0.000}; region 1's zero leaked into it.", svBands[2].saturation));

                float ratio = svBands[0].value > 0f ? svBands[2].value / svBands[0].value : 0f;
                Line(F("  region 2 value / region 0 value = {0:0.000}  (_ValueMul3 = 0.40; the " +
                       "gap is the albedo-independent dielectric specular term)", ratio));
                if (ratio < 0.30f || ratio > 0.55f)
                    Fail(F("_ValueMul3 = 0.4 gave a value ratio of {0:0.000} against region 0.",
                           ratio));
                if (svBands[1].value < svBands[0].value * 0.8f)
                    Fail(F("region 1 kept _ValueMul2 = 1 but is darker than region 0 " +
                           "({0:0.000} vs {1:0.000}); region 2's 0.4 leaked into it.",
                           svBands[1].value, svBands[0].value));
            }

            // ---------- STEP 6: the mask is sampled as DATA, not as colour
            CheckMaskIsSampledLinear(cam, mat, quad, dir, ref blendMask);
        }
        finally
        {
            if (quad != null) UnityEngine.Object.DestroyImmediate(quad);
            if (mat != null) UnityEngine.Object.DestroyImmediate(mat);
            if (albedo != null) UnityEngine.Object.DestroyImmediate(albedo);
            if (bandMask != null) UnityEngine.Object.DestroyImmediate(bandMask);
            if (blendMask != null) UnityEngine.Object.DestroyImmediate(blendMask);
            light.transform.rotation = savedLight;
            sphere.SetActive(sphereWasActive);
        }
    }

    /// <summary>
    /// The one measurement that can tell a linear mask from an sRGB one.
    ///
    /// <para>The 3-band mask above cannot: its texels are 0 and 255, which are
    /// fixed points of the sRGB curve, and normalisation cancels any common
    /// factor — so a mask decoded as colour would produce the SAME three
    /// answers. This uses a mask texel of (255, 128, 0), where the two readings
    /// disagree: linear gives weights (0.665, 0.334, 0) and an sRGB decode
    /// gives (0.823, 0.178, 0). Against a region-1 hue of 0.6 that is a
    /// measured hue of 0.2005 versus 0.1065.</para>
    ///
    /// <para>What this proves is that the TEXTURE was created linear. The graph
    /// itself cannot enforce it — ShaderGraph's Sample Texture 2D node has no
    /// data/linear mode and the decode is a property of the texture asset — so
    /// an authored mask must be imported with sRGB off.</para>
    /// </summary>
    static void CheckMaskIsSampledLinear(Camera cam, Material mat, GameObject quad,
                                         string dir, ref Texture2D blendMask)
    {
        Line("STEP 6 - the mask is sampled as DATA (no sRGB decode)");

        blendMask = MaskTexture(new Color32[] { new Color32(255, 128, 0, 255) });
        mat.SetTexture("_RegionMask", blendMask);
        SetAllRegions(mat, 0f, 1f, 1f);
        const float k_Region1Hue = 0.6f;
        mat.SetFloat("_HueShift2", k_Region1Hue);

        float g = 128f / 255f;
        float wLinear = g / (1f + g);
        float gDecoded = Mathf.GammaToLinearSpace(g);
        float wDecoded = gDecoded / (1f + gDecoded);
        float expectLinear = Frac(wLinear * k_Region1Hue);
        float expectDecoded = Frac(wDecoded * k_Region1Hue);

        Sample s = Render(cam, Path.Combine(dir, "region_linear_blend.png"));

        Line("  mask texel (255,128,0)   texture format = " + blendMask.graphicsFormat);
        Line(F("  linear read  -> weights ({0:0.000}, {1:0.000}, 0) -> hue {2:0.0000}",
               1f - wLinear, wLinear, expectLinear));
        Line(F("  sRGB-decoded -> weights ({0:0.000}, {1:0.000}, 0) -> hue {2:0.0000}",
               1f - wDecoded, wDecoded, expectDecoded));
        // A uniform mask over a flat quad: subject plus background is TWO
        // distinct colours and that is the correct answer here.
        Line(F("  measured hue {0:0.0000}   {1}", s.hue, s.Diagnostics(2)));

        float dLinear = CircularDistance(s.hue, expectLinear);
        float dDecoded = CircularDistance(s.hue, expectDecoded);
        Line(F("  distance to linear {0:0.0000}, to sRGB-decoded {1:0.0000}", dLinear, dDecoded));

        if (s.pixels < 500)
            Fail("the quad did not render for the linear-sampling step.");
        else if (dLinear > k_HueTolerance)
            Fail(F("the blended-mask hue is {0:0.0000}; a linearly sampled mask gives " +
                   "{1:0.0000}{2}.", s.hue, expectLinear,
                   dDecoded < dLinear ? " and an sRGB-decoded one gives " +
                       expectDecoded.ToString("0.0000", CultureInfo.InvariantCulture) +
                       ", which is what this matches" : ""));
        else
            Line("  OK: the mask is being read as data.");
    }

    /// <summary>Writes the same triple into all three parameter sets.</summary>
    static void SetAllRegions(Material mat, float hue, float saturation, float value)
    {
        mat.SetFloat("_HueShift", hue);
        mat.SetFloat("_HueShift2", hue);
        mat.SetFloat("_HueShift3", hue);
        mat.SetFloat("_SaturationMul", saturation);
        mat.SetFloat("_SaturationMul2", saturation);
        mat.SetFloat("_SaturationMul3", saturation);
        mat.SetFloat("_ValueMul", value);
        mat.SetFloat("_ValueMul2", value);
        mat.SetFloat("_ValueMul3", value);
    }

    /// <summary>
    /// A region mask, created LINEAR. The fifth constructor argument is
    /// <c>linear</c>: true gives an R8G8B8A8_UNorm texture, false gives
    /// _SRGB and the GPU would gamma-decode a data map on every sample. This is
    /// the code equivalent of clearing the sRGB checkbox on a real mask's
    /// importer, and <see cref="CheckMaskIsSampledLinear"/> proves it took.
    /// </summary>
    static Texture2D MaskTexture(Color32[] texels)
    {
        var t = new Texture2D(texels.Length, 1, TextureFormat.RGBA32, false, true);
        t.hideFlags = HideFlags.HideAndDontSave;
        t.wrapMode = TextureWrapMode.Clamp;
        t.filterMode = FilterMode.Point;   // hard region edges, no bleed
        t.SetPixels32(texels);
        t.Apply(false, false);
        return t;
    }

    // ------------------------------------------------------------ measurement

    struct Sample
    {
        public float hue;          // turns, 0..1, saturation-weighted circular mean
        public float saturation;
        public float value;
        public int pixels;
        public int distinctColours;
        public int kilobytes;

        /// <param name="flatBelow">How few distinct colours means "this render
        /// is a flat fill and the graphics device is probably null". Eight is
        /// right for a shaded sphere. It is a PARAMETER because the region
        /// renders are a flat-lit quad, where three bands plus a black
        /// background legitimately give four — and a report that cries
        /// "SUSPECT" at a correct render trains you to ignore it.</param>
        public string Diagnostics(int flatBelow = 8)
        {
            return string.Format(CultureInfo.InvariantCulture,
                "[{0} px, {1} distinct colours, {2} KB{3}]",
                pixels, distinctColours, kilobytes,
                distinctColours < flatBelow ? ", SUSPECT: essentially a flat fill" : "");
        }
    }

    /// <summary>One vertical strip of a render, measured on its own.</summary>
    struct Band
    {
        public float hue;
        public float saturation;   // mean over EVERY lit pixel, greyscale included
        public float value;
        public int subjectPixels;
        public int huePixels;      // the saturated subset the hue is read from
    }

    /// <summary>
    /// Splits the rendered subject into three equal vertical thirds and
    /// measures each one, sampling only the middle 60% of each third.
    ///
    /// <para>The thirds are taken from the subject's MEASURED screen extent,
    /// not from hard-coded pixel columns. The quad's footprint depends on the
    /// camera's orthographic size, and a literal here would go quietly wrong
    /// the first time that changed — measuring something adjacent to the thing
    /// it guards is this project's recurring failure.</para>
    ///
    /// <para>The 20% margins keep the antialiased region borders out of every
    /// measurement; a border texel is a genuine blend of two parameter sets and
    /// its hue belongs to neither band.</para>
    /// </summary>
    static Band[] MeasureThirds(Color32[] pixels, int width, int height)
    {
        int minX, maxX;
        if (!SubjectSpan(pixels, width, height, out minX, out maxX)) return null;

        float span = maxX - minX + 1;
        float third = span / 3f;
        var bands = new Band[3];
        for (int i = 0; i < 3; i++)
        {
            int x0 = Mathf.RoundToInt(minX + third * i + third * 0.2f);
            int x1 = Mathf.RoundToInt(minX + third * (i + 1) - third * 0.2f);
            bands[i] = MeasureBand(pixels, width, height, x0, x1);
        }
        return bands;
    }

    static bool SubjectSpan(Color32[] pixels, int width, int height, out int minX, out int maxX)
    {
        minX = int.MaxValue;
        maxX = int.MinValue;
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                Color32 p = pixels[y * width + x];
                if (p.r < 6 && p.g < 6 && p.b < 6) continue;   // the black clear colour
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
            }
        }
        return maxX >= minX;
    }

    /// <summary>
    /// Same decode and same saturation-weighted circular mean as
    /// <see cref="Measure"/>, restricted to one column range — except that
    /// SATURATION is averaged over every lit pixel rather than over the
    /// saturated subset. A region whose <c>_SaturationMul</c> is zero has no
    /// saturated pixels at all, so averaging the subset would report the mean
    /// of an empty set and the greyscale check could never fail.
    /// </summary>
    static Band MeasureBand(Color32[] pixels, int width, int height, int x0, int x1)
    {
        bool linearSpace = QualitySettings.activeColorSpace == ColorSpace.Linear;
        double xSum = 0, ySum = 0, satSum = 0, valueSum = 0;
        int subject = 0, hued = 0;

        for (int y = 0; y < height; y++)
        {
            for (int x = x0; x < x1; x++)
            {
                Color32 p = pixels[y * width + x];
                float r = p.r / 255f, g = p.g / 255f, b = p.b / 255f;
                if (linearSpace)
                {
                    r = Mathf.GammaToLinearSpace(r);
                    g = Mathf.GammaToLinearSpace(g);
                    b = Mathf.GammaToLinearSpace(b);
                }

                float h, s, v;
                Color.RGBToHSV(new Color(r, g, b), out h, out s, out v);
                if (v < 0.02f) continue;            // background
                subject++;
                satSum += s;
                valueSum += v;
                if (s < 0.15f) continue;            // hue is numerically meaningless
                double angle = h * 2.0 * Math.PI;
                xSum += s * Math.Cos(angle);
                ySum += s * Math.Sin(angle);
                hued++;
            }
        }

        var band = new Band();
        band.subjectPixels = subject;
        band.huePixels = hued;
        if (subject > 0)
        {
            band.saturation = (float)(satSum / subject);
            band.value = (float)(valueSum / subject);
        }
        if (hued > 0)
            band.hue = Frac((float)(Math.Atan2(ySum, xSum) / (2.0 * Math.PI)));
        return band;
    }

    static Texture2D SolidTexture(Color32 c)
    {
        // sRGB (linear:false) so the sampler decodes it the way a real albedo
        // map is decoded. The whole point of the shader is what happens to a
        // texture's colour, so feeding it a linear texture would test something
        // the game never does.
        var t = new Texture2D(8, 8, TextureFormat.RGBA32, false, false);
        t.hideFlags = HideFlags.HideAndDontSave;
        t.wrapMode = TextureWrapMode.Clamp;
        t.filterMode = FilterMode.Point;
        var pixels = new Color32[64];
        for (int i = 0; i < pixels.Length; i++) pixels[i] = c;
        t.SetPixels32(pixels);
        t.Apply(false, false);
        return t;
    }

    static Sample Render(Camera cam, string pngPath)
    {
        Color32[] ignored;
        return Render(cam, pngPath, out ignored);
    }

    static Sample Render(Camera cam, string pngPath, out Color32[] pixels)
    {
        var rt = new RenderTexture(k_Size, k_Size, 24, RenderTextureFormat.ARGB32,
                                   RenderTextureReadWrite.sRGB);
        rt.antiAliasing = 1;
        var tex = new Texture2D(k_Size, k_Size, TextureFormat.RGBA32, false, false);
        try
        {
            cam.targetTexture = rt;
            cam.Render();
            cam.targetTexture = null;

            RenderTexture previous = RenderTexture.active;
            RenderTexture.active = rt;
            tex.ReadPixels(new Rect(0, 0, k_Size, k_Size), 0, 0);
            tex.Apply();
            RenderTexture.active = previous;

            byte[] bytes = tex.EncodeToPNG();
            File.WriteAllBytes(pngPath, bytes);

            pixels = tex.GetPixels32();
            return Measure(tex, bytes.Length);
        }
        finally
        {
            UnityEngine.Object.DestroyImmediate(tex);
            rt.Release();
            UnityEngine.Object.DestroyImmediate(rt);
        }
    }

    /// <summary>
    /// Saturation-weighted circular mean hue of the lit subject.
    ///
    /// <para>The framebuffer is sRGB-encoded, and gamma is a per-channel curve —
    /// it preserves which channel is largest but NOT the hue angle inside a
    /// sector. So every pixel is decoded back to linear before the hue is taken,
    /// which is the space the shader's own HSV round trip works in.</para>
    ///
    /// <para>Weakly-saturated pixels (the specular rim, the terminator) are
    /// dropped: their hue is numerically meaningless and would drag a plain
    /// average toward whatever the shading is doing.</para>
    /// </summary>
    static Sample Measure(Texture2D tex, int byteLength)
    {
        Color32[] pixels = tex.GetPixels32();
        bool linearSpace = QualitySettings.activeColorSpace == ColorSpace.Linear;

        double x = 0, y = 0, valueSum = 0, satSum = 0;
        int used = 0;
        var unique = new HashSet<int>();

        for (int i = 0; i < pixels.Length; i++)
        {
            Color32 p = pixels[i];
            unique.Add((p.r >> 3 << 10) | (p.g >> 3 << 5) | (p.b >> 3));

            float r = p.r / 255f, g = p.g / 255f, b = p.b / 255f;
            if (linearSpace)
            {
                r = Mathf.GammaToLinearSpace(r);
                g = Mathf.GammaToLinearSpace(g);
                b = Mathf.GammaToLinearSpace(b);
            }

            float h, s, v;
            Color.RGBToHSV(new Color(r, g, b), out h, out s, out v);
            if (v < 0.02f || s < 0.15f) continue;   // background / desaturated rim

            double angle = h * 2.0 * Math.PI;
            x += s * Math.Cos(angle);
            y += s * Math.Sin(angle);
            valueSum += v;
            satSum += s;
            used++;
        }

        var sample = new Sample();
        sample.pixels = used;
        sample.distinctColours = unique.Count;
        sample.kilobytes = byteLength / 1024;
        if (used > 0)
        {
            double mean = Math.Atan2(y, x) / (2.0 * Math.PI);
            sample.hue = Frac((float)mean);
            sample.value = (float)(valueSum / used);
            sample.saturation = (float)(satSum / used);
        }
        return sample;
    }

    static float Frac(float v)
    {
        float f = v - Mathf.Floor(v);
        return f >= 1f ? 0f : f;
    }

    /// <summary>Shortest distance between two hues on the colour wheel.</summary>
    static float CircularDistance(float a, float b)
    {
        float d = Mathf.Abs(Frac(a) - Frac(b));
        return d > 0.5f ? 1f - d : d;
    }
}
