using System.Collections.Generic;
using System.IO;
using TMPro;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.UI;

/// <summary>
/// Builds the theme content, the Inquisitor prefab and the character-select
/// scene — spec §15.11 / §15.12.
///
/// <para><c>Tools &gt; Aftermarket &gt; Build Character Select</c>. Idempotent:
/// re-running updates the assets in place so GUIDs survive, and rebuilds the
/// scene from scratch (it is a build output, not a place for hand edits — unlike
/// AshfallDepot).</para>
///
/// <para><b>It does not touch AshfallDepot.unity.</b> The select screen is its own
/// scene, so nothing here can disturb the one file this project intends hand
/// dressing to accumulate in.</para>
/// </summary>
public static class CharacterSelectBuilder
{
    const string k_ThemeDir = "Assets/_Project/Content/Themes";
    const string k_CatalogPath = k_ThemeDir + "/ThemeCatalog.asset";
    const string k_InqDir = "Assets/_Project/Art/Characters/Inquisitor";
    const string k_InqFbx = k_InqDir + "/SK_INQUISITOR_M_01.fbx";
    const string k_InqPrefab = k_InqDir + "/Inquisitor.prefab";
    const string k_ScenePath = "Assets/_Project/Scenes/CharacterSelect.unity";
    const string k_CharDir = "Assets/_Project/Content/Characters";
    const string k_CharCatalog = k_CharDir + "/CharacterCatalog.asset";
    const string k_RecolorShader = "Assets/_Project/Art/Shaders/Recolor.shadergraph";

    /// <summary>
    /// The starting set. Hue shifts are in TURNS, and they are spaced rather than
    /// picked: five evenly around the wheel from a red-ish source reads as five
    /// distinct colours. Two are held back per §15.12.1 ("character themes —
    /// LOCKED, admin only") so the locked presentation is exercised rather than
    /// merely written.
    /// </summary>
    struct Seed
    {
        public string id, name;
        public float hue, sat, val;
        public bool admin;
        // Region 1 (armour) — left at identity unless a theme deliberately
        // touches the metal. null means "leave the armour exactly as authored".
        public float[] armour;

        public Seed(string id, string name, float hue, float sat, float val, bool admin,
                    float[] armour = null)
        {
            this.id = id; this.name = name; this.hue = hue; this.sat = sat; this.val = val;
            this.admin = admin; this.armour = armour;
        }
    }

    static readonly Seed[] k_Seeds =
    {
        new Seed("default",  "Default", 0.00f, 1.00f, 1.00f, false),
        new Seed("ember",    "Ember",   0.05f, 1.35f, 1.05f, false),
        new Seed("void",     "Void",    0.00f, 0.15f, 0.45f, false),
        new Seed("azure",    "Azure",   0.55f, 1.25f, 1.00f, true),
        new Seed("verdant",  "Verdant", 0.32f, 1.20f, 0.95f, true),
        new Seed("rose",     "Rose",    0.88f, 1.30f, 1.10f, true),

        // Exercises region 1 on the real model. Every other theme leaves the
        // armour alone, which is the right default but means nothing here would
        // ever prove the ARMOUR channel is wired — and an unexercised channel is
        // how a mask ends up silently one-region. Cloth stays black; the metal
        // goes gold.
        new Seed("gilded",   "Gilded",  0.00f, 0.30f, 0.75f, true,
                 new[] { 0.12f, 2.20f, 1.15f }),

        // --- second wave -----------------------------------------------------
        // Hue is in TURNS and these are SPACED, not picked: the first six sit at
        // 0.00 / 0.05 / 0.32 / 0.55 / 0.88, leaving the 0.60-0.85 arc and the
        // 0.10-0.30 arc empty. Dropping a theme into a gap that is already
        // occupied gives two swatches a player cannot tell apart, and swatch
        // colour is the entire affordance on that row.
        new Seed("amethyst", "Amethyst", 0.72f, 1.30f, 0.95f, false),
        new Seed("saffron",  "Saffron",  0.13f, 1.40f, 1.10f, false),
        new Seed("glacier",  "Glacier",  0.52f, 0.55f, 1.20f, false),
        new Seed("crimson",  "Crimson",  0.97f, 1.45f, 0.80f, false),

        // Region-1 themes. Gilded was the only one touching the metal, so the
        // armour channel had exactly one witness; these give it three more, at
        // different hues, so a regression cannot hide behind a single sample.
        new Seed("obsidian", "Obsidian", 0.00f, 0.10f, 0.35f, false,
                 new[] { 0.00f, 0.20f, 0.40f }),
        new Seed("verdigris","Verdigris",0.42f, 0.85f, 0.85f, true,
                 new[] { 0.40f, 1.60f, 0.95f }),
        new Seed("rosegold", "Rose Gold",0.94f, 0.90f, 1.05f, true,
                 new[] { 0.04f, 1.70f, 1.20f }),
    };

    [MenuItem("Tools/Aftermarket/Build Character Select")]
    public static void Build()
    {
        // *** THIS GUARD IS NOT OPTIONAL. ***
        // EditorSceneManager.NewScene, like OpenScene, does NOT prompt when called
        // from a script: it discards unsaved changes and clears the undo stack. Run
        // this with AshfallDepot open and half an hour of hand dressing unsaved and
        // the dressing is simply gone. CLAUDE.md records this exact trap being paid
        // for three times already — SceneShot, SkySetup and MapSceneTests.
        //
        // It has to come FIRST, before the character prefabs, because that
        // instantiates the model into whatever scene is open and dirties it.
        if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
        {
            Debug.Log("[CharacterSelect] cancelled — the open scene has unsaved changes and " +
                      "building would discard them.");
            return;
        }

        var log = new List<string>();

        ThemeCatalog catalog = BuildThemes(log);
        GameObject prefab = BuildAllCharacterPrefabs(log);
        BuildScene(catalog, prefab, log);

        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();
        Debug.Log("[CharacterSelect]\n  " + string.Join("\n  ", log));
    }

    // ----------------------------------------------------------------- themes

    static ThemeCatalog BuildThemes(List<string> log)
    {
        Directory.CreateDirectory(k_ThemeDir);

        var made = new List<ThemeDefinition>();
        foreach (Seed s in k_Seeds)
        {
            string path = k_ThemeDir + "/Theme_" + s.name + ".asset";
            var t = AssetDatabase.LoadAssetAtPath<ThemeDefinition>(path);
            if (t == null)
            {
                t = ScriptableObject.CreateInstance<ThemeDefinition>();
                AssetDatabase.CreateAsset(t, path);
            }
            t.themeId = s.id;
            t.displayName = s.name;
            t.adminOnly = s.admin;
            // Region 0 = cloth. Region 1 (armour) is only written when a theme
            // deliberately touches the metal; leaving the array at length 1 makes
            // ThemeDefinition.Region(1) return identity, which is what keeps the
            // armour silver.
            var cloth = new RegionTint { hueShift = s.hue, saturationMul = s.sat, valueMul = s.val };
            t.regions = s.armour == null
                ? new[] { cloth }
                : new[]
                {
                    cloth,
                    new RegionTint { hueShift = s.armour[0], saturationMul = s.armour[1], valueMul = s.armour[2] },
                };
            EditorUtility.SetDirty(t);
            made.Add(t);
        }

        var catalog = AssetDatabase.LoadAssetAtPath<ThemeCatalog>(k_CatalogPath);
        if (catalog == null)
        {
            catalog = ScriptableObject.CreateInstance<ThemeCatalog>();
            AssetDatabase.CreateAsset(catalog, k_CatalogPath);
        }
        catalog.themes = made.ToArray();
        EditorUtility.SetDirty(catalog);

        log.Add("themes            " + made.Count + " assets + catalog (" +
                CountAdmin() + " admin-only, so the lock state is exercised)");
        return catalog;
    }

    static int CountAdmin()
    {
        int n = 0;
        foreach (Seed s in k_Seeds)
        {
            if (s.admin) n++;
        }
        return n;
    }

    // ------------------------------------------------------------- the model


    /// <summary>
    /// Authors one <see cref="CharacterDefinition"/> per playable character and
    /// collects them into a catalog (§18.1). Idempotent: existing assets are
    /// updated in place so their GUIDs survive, because a CharacterDefinition is
    /// referenced by the catalog and a fresh GUID would orphan it.
    ///
    /// <para>The roster is DISCOVERED from the character art folder rather than
    /// listed here. A hardcoded list is what made "add a second character" a code
    /// change, which is the thing §18.1 forbids — and it is why the Next/Prev
    /// arrows sat on the screen for a roster of one.</para>
    /// </summary>
    static CharacterCatalog BuildCharacterCatalog(GameObject inquisitor, List<string> log)
    {
        Directory.CreateDirectory(k_CharDir);
        AssetDatabase.Refresh();

        var found = new List<CharacterDefinition>();

        // Every character prefab under Art/Characters/<Name>/<Name>.prefab.
        foreach (var guid in AssetDatabase.FindAssets("t:Prefab", new[] { "Assets/_Project/Art/Characters" }))
        {
            string path = AssetDatabase.GUIDToAssetPath(guid);
            var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(path);
            if (prefab == null) continue;
            if (prefab.GetComponentInChildren<SkinnedMeshRenderer>(true) == null)
            {
                // A prefab with no skinned mesh is scenery that happens to live in
                // the character folder, not somebody you can play as.
                continue;
            }

            string id = Path.GetFileNameWithoutExtension(path).ToLowerInvariant();
            string assetPath = $"{k_CharDir}/Character_{Path.GetFileNameWithoutExtension(path)}.asset";
            var def = AssetDatabase.LoadAssetAtPath<CharacterDefinition>(assetPath);
            if (def == null)
            {
                def = ScriptableObject.CreateInstance<CharacterDefinition>();
                AssetDatabase.CreateAsset(def, assetPath);
            }
            def.characterId = id;
            if (string.IsNullOrWhiteSpace(def.displayName) || def.displayName == "The Assassin")
                def.displayName = id == "inquisitor" ? "The Assassin" : Nice(id);
            def.prefab = prefab;
            EditorUtility.SetDirty(def);
            found.Add(def);
        }

        found.Sort((a, b) => string.CompareOrdinal(a.characterId, b.characterId));

        var catalog = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(k_CharCatalog);
        if (catalog == null)
        {
            catalog = ScriptableObject.CreateInstance<CharacterCatalog>();
            AssetDatabase.CreateAsset(catalog, k_CharCatalog);
        }
        catalog.characters = found.ToArray();
        EditorUtility.SetDirty(catalog);
        AssetDatabase.SaveAssets();

        foreach (var d in found) log.Add("  roster          " + d.characterId + "  (" + d.displayName + ")");
        if (found.Count == 0)
            log.Add("  roster          EMPTY — no prefab with a SkinnedMeshRenderer under Art/Characters.");
        return catalog;
    }

    static string Nice(string id)
    {
        if (string.IsNullOrEmpty(id)) return id;
        var sb = new System.Text.StringBuilder();
        bool up = true;
        foreach (char c in id)
        {
            if (c == '_' || c == '-') { sb.Append(' '); up = true; continue; }
            sb.Append(up ? char.ToUpperInvariant(c) : c);
            up = false;
        }
        return sb.ToString();
    }

    /// <summary>
    /// Builds a prefab for EVERY character folder under Art/Characters, not just
    /// the Inquisitor. The pack ships a family on one skeleton and the importer
    /// can now bring any of them across, so a builder that only knows one name is
    /// the same hardcoded-roster problem one layer down.
    /// </summary>
    static GameObject BuildAllCharacterPrefabs(List<string> log)
    {
        GameObject first = null;
        string root = "Assets/_Project/Art/Characters";
        if (!AssetDatabase.IsValidFolder(root))
        {
            log.Add("NO CHARACTERS     " + root + " does not exist — run " +
                    "'python Tools/import_inquisitor.py'.");
            return null;
        }
        foreach (string dir in AssetDatabase.GetSubFolders(root))
        {
            var built = BuildCharacterPrefab(dir, log);
            if (built != null && first == null) first = built;
        }
        return first;
    }

    static GameObject BuildCharacterPrefab(string dir, List<string> log)
    {
        string name = System.IO.Path.GetFileName(dir);
        string fbx = null;
        foreach (string guid in AssetDatabase.FindAssets("t:Model", new[] { dir }))
        {
            string path = AssetDatabase.GUIDToAssetPath(guid);
            if (path.EndsWith(".fbx", System.StringComparison.OrdinalIgnoreCase)) { fbx = path; break; }
        }
        if (fbx == null)
        {
            log.Add("NO MESH           " + dir + " has no .fbx; skipped.");
            return null;
        }
        string prefabPath = dir + "/" + name + ".prefab";

        var model = AssetDatabase.LoadAssetAtPath<GameObject>(fbx);
        if (model == null)
        {
            log.Add("MODEL MISSING     " + fbx + " would not load; skipped.");
            return null;
        }

        GameObject instance = (GameObject)PrefabUtility.InstantiatePrefab(model);
        instance.name = name;

        // Switch the clothes/body/head onto Recolor so a theme is not a silent
        // no-op. If the shader is not built yet, say so — do NOT leave the prefab
        // looking themed-capable when it is not.
        Shader recolor = AssetDatabase.LoadAssetAtPath<Shader>(k_RecolorShader);
        int swapped = 0, skipped = 0;

        // EVERY material in the character's folder, not just the ones this prefab
        // happens to wear.
        //
        // The first version walked the prefab's renderers only, so
        // Inquisitor_Clothes_Red — imported specifically to BE the recolour
        // source — stayed on plain URP/Lit and every theme was a silent no-op on
        // it. Caught by rendering, not by reading: ThemeSystem reported "3
        // renderer(s) recoloured" instead of 22 and the measured hue did not
        // move by so much as 0.002 across six themes. An alternate colourway is
        // exactly the thing a prefab-only walk cannot see.
        var mats = new List<Material>();
        foreach (string guid in AssetDatabase.FindAssets("t:Material", new[] { dir }))
        {
            var m = AssetDatabase.LoadAssetAtPath<Material>(AssetDatabase.GUIDToAssetPath(guid));
            if (m != null) mats.Add(m);
        }

        {
            foreach (Material m in mats)
            {
                if (m == null) continue;
                if (recolor == null) { skipped++; continue; }
                if (m.shader == recolor) continue;

                // Alpha-cut materials — hair and eyelash cards, whose texture
                // alpha decides which pixels exist — may only move onto Recolor
                // if Recolor can actually clip. It could not until the graph
                // gained an AlphaClipThreshold, and moving them anyway rendered
                // every hair card as a solid quad: the hair fused into one lump.
                // That was "the $200 models don't look like what I bought".
                //
                // The capability is MEASURED, not assumed. Regenerate the graph
                // without alpha and these fall back to being left alone rather
                // than silently turning solid again.
                bool cut = m.GetFloat("_AlphaClip") > 0.5f || m.IsKeywordEnabled("_ALPHATEST_ON")
                           || m.renderQueue == (int)UnityEngine.Rendering.RenderQueue.AlphaTest;
                bool recolorCanClip = recolor.FindPropertyIndex("_Cutoff") >= 0;
                if (cut && !recolorCanClip)
                {
                    skipped++;
                    log.Add("ALPHA-CUT, LEFT ALONE  " + m.name +
                            " — this Recolor build has no _Cutoff, so it cannot clip.");
                    continue;
                }
                Texture baseMap = m.HasProperty("_BaseMap") ? m.GetTexture("_BaseMap") : null;
                Texture bump = m.HasProperty("_BumpMap") ? m.GetTexture("_BumpMap") : null;
                Texture mg = m.HasProperty("_MetallicGlossMap") ? m.GetTexture("_MetallicGlossMap") : null;
                Texture occ = m.HasProperty("_OcclusionMap") ? m.GetTexture("_OcclusionMap") : null;
                float smooth = m.HasProperty("_Smoothness") ? m.GetFloat("_Smoothness") : 0.5f;

                m.shader = recolor;

                // Re-push every map AFTER the shader swap. Unity keeps properties
                // whose names match, but a slot the new shader does not declare is
                // dropped silently — reading them first and writing them back is
                // what stops a swap quietly emptying _BumpMap.
                if (m.HasProperty("_BaseMap") && baseMap != null) m.SetTexture("_BaseMap", baseMap);
                if (m.HasProperty("_BumpMap") && bump != null) m.SetTexture("_BumpMap", bump);
                if (m.HasProperty("_MetallicGlossMap") && mg != null) m.SetTexture("_MetallicGlossMap", mg);
                if (m.HasProperty("_OcclusionMap") && occ != null) m.SetTexture("_OcclusionMap", occ);
                if (m.HasProperty("_Smoothness")) m.SetFloat("_Smoothness", smooth);
                if (m.HasProperty("_HueShift")) m.SetFloat("_HueShift", 0f);
                if (m.HasProperty("_SaturationMul")) m.SetFloat("_SaturationMul", 1f);
                if (m.HasProperty("_ValueMul")) m.SetFloat("_ValueMul", 1f);
                EditorUtility.SetDirty(m);
                swapped++;
            }
        }

        GameObject prefab = PrefabUtility.SaveAsPrefabAsset(instance, prefabPath);
        Object.DestroyImmediate(instance);

        if (recolor == null)
        {
            log.Add("SHADER MISSING    " + k_RecolorShader + " does not exist, so " + name + "'s " +
                    skipped + " material(s) are still plain URP/Lit and THEMES WILL DO NOTHING to " +
                    "this character. ThemeSystem.Apply warns about this at runtime too.");
        }
        else
        {
            log.Add("character         " + prefabPath + ", " + swapped + " material(s) on Recolor");
        }
        return prefab;
    }

    // ------------------------------------------------------------- the scene

    static void BuildScene(ThemeCatalog catalog, GameObject character, List<string> log)
    {
        Scene scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

        // --- preview stage -------------------------------------------------
        var stage = new GameObject("PreviewStage");
        var anchor = new GameObject("Turntable").transform;
        anchor.SetParent(stage.transform);
        anchor.localPosition = Vector3.zero;

        var camGo = new GameObject("PreviewCamera");
        camGo.transform.SetParent(stage.transform);
        camGo.transform.localPosition = new Vector3(0f, 1.05f, 2.6f);
        camGo.transform.localRotation = Quaternion.Euler(3f, 180f, 0f);
        Camera cam = camGo.AddComponent<Camera>();
        cam.clearFlags = CameraClearFlags.SolidColor;
        cam.backgroundColor = new Color(0.06f, 0.06f, 0.08f, 1f);
        cam.fieldOfView = 38f;
        cam.depth = 0;
        // Law 9: a camera that does not render a layer makes its content invisible
        // while everything else still says it is there.
        cam.cullingMask = ~0;

        var sun = new GameObject("KeyLight");
        sun.transform.SetParent(stage.transform);
        sun.transform.localRotation = Quaternion.Euler(35f, 205f, 0f);
        Light key = sun.AddComponent<Light>();
        key.type = LightType.Directional;
        key.intensity = 1.5f;

        var fill = new GameObject("FillLight");
        fill.transform.SetParent(stage.transform);
        fill.transform.localRotation = Quaternion.Euler(15f, 25f, 0f);
        Light f = fill.AddComponent<Light>();
        f.type = LightType.Directional;
        f.intensity = 0.55f;
        f.color = new Color(0.75f, 0.82f, 1f);

        // A neutral ambient, so a themed model is judged on its own colour rather
        // than on whatever sky happens to be loaded. This is the exact trap the
        // zombie labelling fell into: "cold blue-grey" turned out to describe the
        // HDRI, not the art.
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Flat;
        RenderSettings.ambientLight = new Color(0.32f, 0.32f, 0.34f, 1f);

        // --- UI ------------------------------------------------------------
        TMP_FontAsset font = FindAnyFont();
        if (font == null)
            log.Add("NO TMP FONT       every label will render nothing. Import the TMP Essential Resources.");

        var canvasGo = new GameObject("Canvas", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
        Canvas canvas = canvasGo.GetComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        var scaler = canvasGo.GetComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1920f, 1080f);

        // The EventSystem MUST use InputSystemUIInputModule: activeInputHandler is
        // 1, so StandaloneInputModule reads UnityEngine.Input and throws every
        // frame. Same rule CoreLoopBuilder documents; plain AddComponent, not
        // ObjectFactory, so no template .inputactions reference is serialized.
        var es = new GameObject("EventSystem", typeof(UnityEngine.EventSystems.EventSystem));
        es.AddComponent<UnityEngine.InputSystem.UI.InputSystemUIInputModule>();

        TMP_Text nameLabel = MakeLabel(canvasGo.transform, "CharacterName", font, 64f,
                                       new Vector2(0.5f, 0.92f), TextAlignmentOptions.Center, "—");
        TMP_Text themeLabel = MakeLabel(canvasGo.transform, "ThemeName", font, 36f,
                                        new Vector2(0.5f, 0.86f), TextAlignmentOptions.Center, "Default");
        MakeLabel(canvasGo.transform, "Hint", font, 24f, new Vector2(0.5f, 0.06f),
                  TextAlignmentOptions.Center,
                  "locked themes are shown greyed with a dot — §15.12.2");

        var rowGo = new GameObject("ThemeRow", typeof(RectTransform), typeof(HorizontalLayoutGroup));
        rowGo.transform.SetParent(canvasGo.transform, false);
        var row = (RectTransform)rowGo.transform;
        Anchor(row, new Vector2(0.5f, 0.14f), new Vector2(1100f, 96f));
        var layout = rowGo.GetComponent<HorizontalLayoutGroup>();
        layout.spacing = 14f;
        layout.childAlignment = TextAnchor.MiddleCenter;
        layout.childForceExpandWidth = false;
        layout.childForceExpandHeight = false;

        Button template = MakeButton(rowGo.transform, "SwatchTemplate", font, "Theme");
        Button prev = MakeArrow(canvasGo.transform, "Prev", font, new Vector2(0.12f, 0.5f), "<");
        Button next = MakeArrow(canvasGo.transform, "Next", font, new Vector2(0.88f, 0.5f), ">");

        // A screen that saves silently reads as a screen that does nothing. The
        // arrows already persist the choice the moment you look at someone, so
        // these two buttons add no state -- they add the only evidence that the
        // choice was made, and the only way back out.
        Button confirm = MakeWideButton(canvasGo.transform, "Select", font, "SELECT",
                                        new Vector2(0f, -300f),
                                        new Color(0.85f, 0.28f, 0.5f, 0.95f));
        Button back = MakeWideButton(canvasGo.transform, "Back", font, "BACK",
                                     new Vector2(0f, -392f),
                                     new Color(0.18f, 0.16f, 0.22f, 0.95f));

        // --- wire ------------------------------------------------------------
        var select = canvasGo.AddComponent<CharacterSelect>();
        select.themeCatalog = catalog;
        select.previewAnchor = anchor;
        select.characterNameLabel = nameLabel;
        select.themeNameLabel = themeLabel;
        select.themeRow = row;
        select.swatchTemplate = template;
        select.prevCharacterButton = prev;
        select.nextCharacterButton = next;
        // Persistent listeners: one added at runtime is not serialized, so the
        // buttons would work in the session that built them and be dead in a build.
        UnityEditor.Events.UnityEventTools.AddPersistentListener(confirm.onClick, select.Confirm);
        UnityEditor.Events.UnityEventTools.AddPersistentListener(back.onClick, select.Back);
        select.characterCatalog = BuildCharacterCatalog(character, log);

        Directory.CreateDirectory(Path.GetDirectoryName(k_ScenePath));
        NavBarBuilder.Build(canvasGo.transform, font, k_ScenePath);

        EditorSceneManager.SaveScene(scene, k_ScenePath);

        // Report the wiring by READING IT BACK, not by asserting what was just
        // written. Every "built successfully" line in this project that described
        // intent rather than state has eventually been wrong.
        log.Add("scene             " + k_ScenePath);
        log.Add("  characters      " +
                (select.characterCatalog == null ? "NO CATALOG"
                 : select.characterCatalog.CountNonNull() + " in the roster") +
                (character == null ? "  <-- NOTHING TO PREVIEW" : ""));
        log.Add("  themes in row   " + (catalog == null ? 0 : catalog.themes.Length));
        log.Add("  font            " + (nameLabel != null && nameLabel.font != null
                                        ? nameLabel.font.name : "NULL — labels will be invisible"));
        log.Add("  input module    " + (es.GetComponent<UnityEngine.InputSystem.UI.InputSystemUIInputModule>() != null
                                        ? "InputSystemUIInputModule" : "MISSING"));
    }

    // ------------------------------------------------------------------ bits

    /// <summary>
    /// The project's real UI font — NOT simply the first search hit.
    ///
    /// <para>A bare <c>FindAssets("t:TMP_FontAsset")</c> returns
    /// <c>LiberationSans SDF - Fallback</c> first in this project. That is a
    /// <b>dynamic</b> font asset: its atlas holds whatever glyphs some unrelated
    /// scene happened to make TMP rasterise, so a label built against it renders
    /// correctly or not depending on what was opened earlier. Prefer TMP's own
    /// configured default, then any non-Fallback asset, and only then the
    /// fallback — with a warning, because at that point the text is at the mercy
    /// of an atlas nobody controls.</para>
    /// </summary>
    static TMP_FontAsset FindAnyFont()
    {
        if (TMP_Settings.defaultFontAsset != null) return TMP_Settings.defaultFontAsset;

        TMP_FontAsset fallback = null;
        foreach (string guid in AssetDatabase.FindAssets("t:TMP_FontAsset"))
        {
            var a = AssetDatabase.LoadAssetAtPath<TMP_FontAsset>(AssetDatabase.GUIDToAssetPath(guid));
            if (a == null) continue;
            if (a.name.IndexOf("Fallback", System.StringComparison.OrdinalIgnoreCase) >= 0)
            {
                if (fallback == null) fallback = a;
                continue;
            }
            return a;
        }

        if (fallback != null)
            Debug.LogWarning("[CharacterSelect] only a Fallback TMP font was found (" +
                             fallback.name + "). Its atlas contains whatever another scene " +
                             "made TMP rasterise, so labels may render incompletely.");
        return fallback;
    }

    static void Anchor(RectTransform rt, Vector2 pivot, Vector2 size)
    {
        rt.anchorMin = rt.anchorMax = pivot;
        rt.pivot = new Vector2(0.5f, 0.5f);
        rt.anchoredPosition = Vector2.zero;
        rt.sizeDelta = size;
    }

    static TMP_Text MakeLabel(Transform parent, string name, TMP_FontAsset font, float size,
                              Vector2 pivot, TextAlignmentOptions align, string text)
    {
        var go = new GameObject(name, typeof(RectTransform));
        go.transform.SetParent(parent, false);
        var t = go.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = size;
        t.alignment = align;
        t.text = text;
        Anchor((RectTransform)go.transform, pivot, new Vector2(1400f, size * 1.6f));
        return t;
    }

    static Button MakeButton(Transform parent, string name, TMP_FontAsset font, string text)
    {
        var go = new GameObject(name, typeof(RectTransform), typeof(Image), typeof(Button));
        go.transform.SetParent(parent, false);
        ((RectTransform)go.transform).sizeDelta = new Vector2(160f, 72f);
        go.GetComponent<Image>().color = Color.white;

        var labelGo = new GameObject("Label", typeof(RectTransform));
        labelGo.transform.SetParent(go.transform, false);
        var t = labelGo.AddComponent<TextMeshProUGUI>();
        t.font = font;
        t.fontSize = 24f;
        t.alignment = TextAlignmentOptions.Center;
        t.color = Color.black;
        t.text = text;
        var rt = (RectTransform)labelGo.transform;
        rt.anchorMin = Vector2.zero;
        rt.anchorMax = Vector2.one;
        rt.offsetMin = rt.offsetMax = Vector2.zero;
        return go.GetComponent<Button>();
    }

    /// <summary>A full-width button for the actions that leave this screen.</summary>
    static Button MakeWideButton(Transform parent, string name, TMP_FontAsset font,
                                 string text, Vector2 pos, Color colour)
    {
        var go = new GameObject("Button_" + name);
        go.transform.SetParent(parent, false);
        var img = go.AddComponent<Image>();
        img.color = colour;
        var b = go.AddComponent<Button>();
        b.targetGraphic = img;
        var rt = img.rectTransform;
        rt.anchorMin = rt.anchorMax = new Vector2(0.5f, 0.5f);
        rt.pivot = new Vector2(0.5f, 0.5f);
        rt.anchoredPosition = pos;
        rt.sizeDelta = new Vector2(420f, 76f);

        var t = new GameObject("Label").AddComponent<TextMeshProUGUI>();
        t.transform.SetParent(go.transform, false);
        t.font = font;
        t.text = text;
        t.fontSize = 34f;
        t.color = Color.white;
        t.alignment = TextAlignmentOptions.Center;
        t.rectTransform.anchorMin = Vector2.zero;
        t.rectTransform.anchorMax = Vector2.one;
        t.rectTransform.offsetMin = t.rectTransform.offsetMax = Vector2.zero;
        return b;
    }

    static Button MakeArrow(Transform parent, string name, TMP_FontAsset font, Vector2 pivot, string glyph)
    {
        Button b = MakeButton(parent, name, font, glyph);
        Anchor((RectTransform)b.transform, pivot, new Vector2(88f, 88f));
        return b;
    }
}
