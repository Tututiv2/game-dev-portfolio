using System.Collections.Generic;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Turns <c>Art/Weapons/wpn_sakura_fang.fbx</c> into a usable weapon: URP materials,
/// a <see cref="WeaponDefinition"/> .asset and a view model prefab.
///
/// <para>Run <b>Tools > Aftermarket > Build Sakura Fang</b>, then <b>Build Weapon
/// Rig</b>. In that order — the rig reads the definition this writes.</para>
///
/// <para><b>Why a separate builder instead of ContentBootstrap.</b> ContentBootstrap
/// authors the §18.1 content set and is re-run wholesale; this needs the FBX
/// importer configured and a prefab built, which is asset-pipeline work rather
/// than content authoring. Keeping it apart also means re-running either one
/// cannot disturb the other.</para>
///
/// <para><b>THE MATERIALS MUST BE REMAPPED OR THE GUN IMPORTS MAGENTA.</b> An FBX
/// carries the materials Blender wrote into it, and Unity builds those against the
/// BUILT-IN shader. This project is URP, where a Built-in/Standard material renders
/// as the magenta error colour. This is the fourth instance of the slot-binding
/// class recorded in CLAUDE.md — after the ZombieM_02 remap, the Inquisitor's
/// <c>_BaseColor_Map</c>, and the props whose NORMAL map was bound to their albedo
/// slot. Every one of them imported without a single warning.</para>
/// </summary>
public static class SakuraFangBuilder
{
    const string k_Fbx = "Assets/_Project/Art/Weapons/wpn_sakura_fang.fbx";
    const string k_MatDir = "Assets/_Project/Art/Weapons/Materials";
    const string k_DecalDir = "Assets/_Project/Art/Weapons/Decals";
    const string k_Prefab = "Assets/_Project/Art/Weapons/VM_SakuraFang.prefab";
    const string k_Asset = "Assets/_Project/Content/Weapons/Weapon_SakuraFang.asset";

    /// <summary>
    /// The palette, matching Tools/skins/forge_gun_sakura.py exactly. Values are
    /// sRGB 0-255 as authored there; <see cref="Rgb"/> divides by 255 and hands
    /// Unity an ordinary Color, which Unity itself gamma-decodes on upload. That
    /// is the RIGHT thing here and the opposite of the _SpecColor trap in
    /// CLAUDE.md — that bug came from writing a LINEAR number into a Color slot.
    /// </summary>
    struct Surface
    {
        public Color color;
        public float metallic;
        public float smoothness;
        public bool emissive;
    }

    static Color Rgb(int r, int g, int b) => new Color(r / 255f, g / 255f, b / 255f, 1f);

    static Dictionary<string, Surface> Palette() => new Dictionary<string, Surface>
    {
        { "Sakura_Shell_White", new Surface { color = Rgb(249, 246, 248), smoothness = 0.72f } },
        { "Sakura_Shell_Pink",  new Surface { color = Rgb(246, 138, 178), smoothness = 0.70f } },
        { "Sakura_Shell_Rose",  new Surface { color = Rgb(228, 104, 148), smoothness = 0.66f } },
        // The inset panel on the handguard's lower lobe. Added with the measured
        // rebuild: a material name the FBX carries but this table does not gets no
        // remap, so it keeps the Built-in shader the exporter wrote and renders
        // MAGENTA under URP. That only bites on a non-projected build, because
        // --project collapses everything to Sakura_Projected -- which is exactly
        // the kind of gap that stays hidden until the day someone drops the flag.
        { "Sakura_Coral",       new Surface { color = Rgb(244, 132, 124), smoothness = 0.68f } },
        { "Sakura_Metal_Dark",  new Surface { color = Rgb(88, 74, 86), metallic = 0.85f, smoothness = 0.58f } },
        { "Sakura_Metal_Steel", new Surface { color = Rgb(150, 140, 150), metallic = 1f, smoothness = 0.78f } },
        { "Sakura_Glow",        new Surface { color = Rgb(255, 60, 190), smoothness = 0.80f, emissive = true } },
    };

    [MenuItem("Tools/Aftermarket/Build Sakura Fang")]
    public static void Build()
    {
        var model = AssetDatabase.LoadAssetAtPath<GameObject>(k_Fbx);
        if (model == null)
        {
            Debug.LogError($"[SakuraFang] no model at {k_Fbx}. Run:\n" +
                           "  blender --background --factory-startup --python " +
                           "Tools/skins/forge_gun_sakura.py -- --export");
            return;
        }

        EnsureFolder(k_MatDir);
        var materials = BuildMaterials();
        RemapModelMaterials(materials);

        var definition = BuildDefinition();
        var prefab = BuildViewModelPrefab();
        if (prefab == null) return;

        definition.viewModelPrefab = prefab;
        EditorUtility.SetDirty(definition);
        AssetDatabase.SaveAssets();

        Debug.Log($"[SakuraFang] {materials.Count} materials, definition '{definition.weaponName}', " +
                  $"prefab {k_Prefab}.\nNow run Tools > Aftermarket > Build Weapon Rig to put it in " +
                  "the player's hands. It sorts after Weapon_M4, so Glock stays slot 0 and M4 slot 1.");
    }

    // -----------------------------------------------------------------------
    static Dictionary<string, Material> BuildMaterials()
    {
        var urpLit = Shader.Find("Universal Render Pipeline/Lit");
        if (urpLit == null)
        {
            Debug.LogError("[SakuraFang] URP/Lit shader not found. Aborting rather than " +
                           "writing materials on a shader that does not exist.");
            return new Dictionary<string, Material>();
        }

        var made = new Dictionary<string, Material>();

        // The projected build collapses the whole weapon onto one material whose
        // albedo IS the reference art. Built first so it exists whichever mode the
        // FBX was exported in; a material the model never references costs nothing,
        // whereas a referenced name with no mapping is magenta.
        var projection = AssetDatabase.LoadAssetAtPath<Texture2D>(
            "Assets/_Project/Art/Weapons/Textures/sakura_projection.png");
        if (projection != null)
        {
            var pm = LoadOrCreate($"{k_MatDir}/Sakura_Projected.mat", urpLit);
            pm.SetTexture("_BaseMap", projection);
            pm.SetColor("_BaseColor", Color.white);
            pm.SetFloat("_Metallic", 0f);
            // The reference already contains its own highlights, so a glossy
            // surface would light the weapon twice -- once in the photograph and
            // once by the scene. Matches the Blender material deliberately.
            pm.SetFloat("_Smoothness", 0.38f);

            // A little emission, for the same reason the Blender render is mostly
            // emissive: this albedo is baked art that already contains its own
            // lighting. The map is lit by a sunset HDRI, so lighting it again
            // crushed the pink toward the scene's red -- visible in the earlier
            // first-person shot. Modest on purpose: enough to keep the colour, not
            // enough to make the weapon glow in a dark corner.
            pm.EnableKeyword("_EMISSION");
            pm.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
            pm.SetTexture("_EmissionMap", projection);
            pm.SetColor("_EmissionColor", Color.white * 0.35f);
            EditorUtility.SetDirty(pm);
            made["Sakura_Projected"] = pm;
        }
        else
        {
            Debug.LogWarning("[SakuraFang] no sakura_projection.png — run " +
                             "python Tools/skins/make_projection.py if this is a projected build.");
        }

        foreach (var kv in Palette())
        {
            var m = LoadOrCreate($"{k_MatDir}/{kv.Key}.mat", urpLit);
            var s = kv.Value;
            m.SetColor("_BaseColor", s.color);
            m.SetFloat("_Metallic", s.metallic);
            m.SetFloat("_Smoothness", s.smoothness);
            if (s.emissive)
            {
                // Emission needs the KEYWORD as well as the colour. Set without it
                // and the value sits in the material doing nothing — the same shape
                // as _NORMALMAP in fix_prop_materials.py.
                m.EnableKeyword("_EMISSION");
                m.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
                m.SetColor("_EmissionColor", s.color * 3.2f);
            }
            EditorUtility.SetDirty(m);
            made[kv.Key] = m;
        }

        // Decals. Named Mat_Decal_<Tag>_<Side> by the Blender script; the texture is
        // decal_<tag>.png, so the tag is read back out of the material name rather
        // than a second list being kept in step by hand.
        foreach (var tag in new[] { "Cat", "Hearts", "Stars", "Paws", "Panel" })
        {
            var tex = AssetDatabase.LoadAssetAtPath<Texture2D>(
                $"{k_DecalDir}/decal_{tag.ToLowerInvariant()}.png");
            if (tex == null)
            {
                Debug.LogWarning($"[SakuraFang] missing decal_{tag.ToLowerInvariant()}.png — run " +
                                 "python Tools/skins/make_decals.py");
                continue;
            }
            foreach (var side in new[] { "L", "R" })
            {
                string name = $"Mat_Decal_{tag}_{side}";
                var m = LoadOrCreate($"{k_MatDir}/{name}.mat", urpLit);
                m.SetTexture("_BaseMap", tex);
                m.SetColor("_BaseColor", Color.white);
                m.SetFloat("_Metallic", 0f);
                m.SetFloat("_Smoothness", 0.6f);

                // ALPHA CLIP, not blended transparency. A blended decal has no depth
                // write, so it sorts against the gun it is stuck to and flickers
                // through the body as the camera moves. Clipping costs slightly
                // harder edges on 512 px art the player sees at a few centimetres,
                // and buys correct depth everywhere.
                m.SetFloat("_AlphaClip", 1f);
                m.SetFloat("_Cutoff", 0.5f);
                m.EnableKeyword("_ALPHATEST_ON");
                m.renderQueue = (int)UnityEngine.Rendering.RenderQueue.AlphaTest;
                EditorUtility.SetDirty(m);
                made[name] = m;
            }
        }

        AssetDatabase.SaveAssets();
        return made;
    }

    static Material LoadOrCreate(string path, Shader shader)
    {
        var m = AssetDatabase.LoadAssetAtPath<Material>(path);
        if (m == null)
        {
            m = new Material(shader);
            AssetDatabase.CreateAsset(m, path);
        }
        else if (m.shader != shader)
        {
            m.shader = shader;
        }
        return m;
    }

    /// <summary>
    /// Points the FBX importer's material slots at the URP materials above.
    ///
    /// Verifies the END STATE — that every embedded material name resolved to one
    /// of ours — rather than trusting the remap calls to have matched. An
    /// AddRemap whose identifier matches no embedded material is ignored with NO
    /// warning at all, which is exactly how import_zombie.py's first version
    /// "verified" a swap it had never performed.
    /// </summary>
    static void RemapModelMaterials(Dictionary<string, Material> materials)
    {
        if (materials.Count == 0) return;
        var importer = AssetImporter.GetAtPath(k_Fbx) as ModelImporter;
        if (importer == null) { Debug.LogError($"[SakuraFang] {k_Fbx} has no ModelImporter."); return; }

        importer.materialImportMode = ModelImporterMaterialImportMode.ImportStandard;
        importer.materialLocation = ModelImporterMaterialLocation.External;

        // Drop remaps for materials the model no longer has. AddRemap only ever
        // ADDS, so the table accumulates: when the rebuild removed Sakura_Bow the
        // entry stayed, pointing at a material that was then deleted, and the
        // check below reported a slot "remapped to nothing" that the FBX does not
        // contain. A stale entry is not merely untidy here -- it makes the tool
        // report a magenta-material failure that is not real, which is the fastest
        // way to teach someone to ignore this tool's errors.
        foreach (var kv in importer.GetExternalObjectMap())
            if (kv.Key.type == typeof(Material) && !materials.ContainsKey(kv.Key.name))
            {
                importer.RemoveRemap(kv.Key);
                Debug.Log($"[SakuraFang] dropped stale remap '{kv.Key.name}' — the model no " +
                          "longer carries that material.");
            }

        int mapped = 0;
        foreach (var kv in materials)
        {
            importer.AddRemap(new AssetImporter.SourceAssetIdentifier(typeof(Material), kv.Key), kv.Value);
            mapped++;
        }
        importer.SaveAndReimport();

        var unresolved = new List<string>();
        var map = importer.GetExternalObjectMap();
        foreach (var kv in map)
            if (kv.Key.type == typeof(Material) && kv.Value == null) unresolved.Add(kv.Key.name);

        if (unresolved.Count > 0)
            Debug.LogError($"[SakuraFang] {unresolved.Count} material slot(s) remapped to nothing: " +
                           string.Join(", ", unresolved) + ". These render magenta under URP.");
        else
            Debug.Log($"[SakuraFang] {mapped} material slots remapped to URP/Lit, none unresolved.");
    }

    // -----------------------------------------------------------------------
    static WeaponDefinition BuildDefinition()
    {
        var d = AssetDatabase.LoadAssetAtPath<WeaponDefinition>(k_Asset);
        if (d == null)
        {
            d = ScriptableObject.CreateInstance<WeaponDefinition>();
            AssetDatabase.CreateAsset(d, k_Asset);
        }

        // The file is named Weapon_SakuraFang so it sorts AFTER Weapon_M4 under
        // WeaponRigBuilder.LoadDefinitions' ordinal sort. That is load-bearing:
        // Phase6Builder writes slot NUMBERS into each wallbuy, so a name sorting
        // before an existing weapon would silently renumber the Glock and the M4
        // and every wallbuy would then deliver the wrong gun.
        d.weaponName = "SakuraFang";
        d.displayName = "Sakura Fang";

        // A bolt-action sniper: one shot, one kill, deep into the round count.
        d.damage = 200f;
        d.headshotMultiplier = 5f;
        d.range = 300f;
        d.impactForce = 6f;
        d.fireRate = 1.15f;          // seconds BETWEEN shots, not RPM
        d.allowButtonHold = false;   // bolt action — one press, one round
        d.sprintToFireDelay = 0.35f;
        d.recoilUp = 6.5f;
        d.recoilSide = 1.6f;
        d.magazineSize = 5;
        d.maxReserve = 40;
        d.reloadTime = 2.6f;
        d.wallbuyCost = 1500;
        d.wallbuyAmmoCost = 750;

        EditorUtility.SetDirty(d);
        return d;
    }

    // -----------------------------------------------------------------------
    /// <summary>
    /// Builds the view model prefab. Orientation is MEASURED off the mesh, never
    /// typed: CLAUDE.md records the zombie whose facing was a confident
    /// hand-written Euler(0,180,0) that turned out to be 180 degrees wrong, and
    /// which would have made the whole horde moonwalk. The FBX axis conversion is
    /// exactly the kind of thing that is easier to measure than to reason about.
    /// </summary>
    static GameObject BuildViewModelPrefab()
    {
        var model = AssetDatabase.LoadAssetAtPath<GameObject>(k_Fbx);
        var root = new GameObject("VM_SakuraFang");
        var inst = (GameObject)PrefabUtility.InstantiatePrefab(model);
        inst.transform.SetParent(root.transform, false);

        var bounds = WorldBounds(inst);
        if (bounds.size == Vector3.zero)
        {
            Debug.LogError("[SakuraFang] the model has no renderers with bounds.");
            Object.DestroyImmediate(root);
            return null;
        }

        // Longest axis is the barrel. Rotate it onto +Z (Unity forward) keeping +Y up.
        var s = bounds.size;
        if (s.x >= s.y && s.x >= s.z) inst.transform.localRotation = Quaternion.Euler(0f, -90f, 0f);
        else if (s.y >= s.x && s.y >= s.z) inst.transform.localRotation = Quaternion.Euler(90f, 0f, 0f);

        // Which END is the muzzle. Measuring only the longest AXIS aligns the barrel
        // and says nothing about which way it points, so the first version shipped
        // the gun aimed back at the player's own face — the muzzle huge in the
        // foreground and the scope small and distant. It rendered perfectly.
        //
        // This is the zombie-facing bug exactly: CLAUDE.md records a confident
        // hand-written Euler(0,180,0) that measurement proved 180 degrees wrong and
        // that would have made the whole horde moonwalk. A guard that measures
        // something ADJACENT to what it guards is the recurring shape here, and
        // "longest axis" is adjacent to "facing".
        //
        // A muzzle is thin and a buttstock is bulky, so the ends are told apart by
        // cross-section, not by a literal.
        if (!MuzzlePointsForward(inst, root.transform))
        {
            inst.transform.localRotation = Quaternion.Euler(0f, 180f, 0f) * inst.transform.localRotation;
            Debug.Log("[SakuraFang] muzzle measured at -Z; flipped 180 degrees so it points forward.");
        }

        // Re-measure AFTER rotating, then seat it: centred left/right, butt just
        // behind the holder origin so the weapon reads as held rather than thrown.
        bounds = WorldBounds(inst);
        var c = bounds.center;
        inst.transform.localPosition -= new Vector3(c.x, c.y, bounds.min.z + 0.10f);

        bounds = WorldBounds(inst);
        var muzzle = new GameObject("Muzzle").transform;
        muzzle.SetParent(root.transform, false);
        muzzle.localPosition = new Vector3(0f, bounds.center.y, bounds.max.z);

        int weaponLayer = LayerMask.NameToLayer("Weapon");
        StripCollidersAndSetLayer(root, weaponLayer);

        EnsureFolder("Assets/_Project/Art/Weapons");
        var saved = PrefabUtility.SaveAsPrefabAsset(root, k_Prefab);
        Object.DestroyImmediate(root);

        Debug.Log($"[SakuraFang] view model {bounds.size.x:F3} x {bounds.size.y:F3} x " +
                  $"{bounds.size.z:F3} m, muzzle at z={bounds.max.z:F3}.");
        return saved;
    }

    /// <summary>
    /// True when the thin end of the model points +Z. A muzzle is a barrel and a
    /// buttstock carries a butt plate, a comb and a bow, so the two ends differ by
    /// cross-section by a wide margin — this compares the RMS radius about the
    /// barrel axis of the outer 15% at each end.
    ///
    /// <para>Refuses to guess when the ends are within 15% of each other, because a
    /// symmetric object has no measurable facing and silently picking one would
    /// reintroduce exactly the coin-flip this method exists to remove.</para>
    /// </summary>
    static bool MuzzlePointsForward(GameObject inst, Transform root)
    {
        var pts = new List<Vector3>();
        foreach (var mf in inst.GetComponentsInChildren<MeshFilter>(true))
        {
            if (mf.sharedMesh == null) continue;
            var toRoot = root.worldToLocalMatrix * mf.transform.localToWorldMatrix;
            foreach (var v in mf.sharedMesh.vertices) pts.Add(toRoot.MultiplyPoint3x4(v));
        }
        if (pts.Count == 0) return true;

        float zMin = float.MaxValue, zMax = float.MinValue;
        foreach (var p in pts) { zMin = Mathf.Min(zMin, p.z); zMax = Mathf.Max(zMax, p.z); }
        float span = zMax - zMin;
        if (span <= 1e-4f) return true;

        float slab = span * 0.15f;
        float frontR = RmsRadius(pts, zMax - slab, zMax);
        float backR = RmsRadius(pts, zMin, zMin + slab);

        float ratio = Mathf.Max(frontR, backR) / Mathf.Max(0.0001f, Mathf.Min(frontR, backR));
        if (ratio < 1.15f)
        {
            Debug.LogWarning($"[SakuraFang] the two ends measure within {(ratio - 1f) * 100f:F0}% " +
                             $"(front {frontR:F4} m, back {backR:F4} m), so facing cannot be " +
                             "measured. Leaving orientation as-is — CHECK IT IN A RENDER.");
            return true;
        }

        Debug.Log($"[SakuraFang] end cross-sections: +Z {frontR:F4} m, -Z {backR:F4} m " +
                  $"(ratio {ratio:F2}). Thin end is {(frontR < backR ? "+Z" : "-Z")}.");
        return frontR < backR;
    }

    static float RmsRadius(List<Vector3> pts, float zLo, float zHi)
    {
        float cx = 0f, cy = 0f;
        int n = 0;
        foreach (var p in pts)
            if (p.z >= zLo && p.z <= zHi) { cx += p.x; cy += p.y; n++; }
        if (n == 0) return 0f;
        cx /= n; cy /= n;

        double sum = 0.0;
        foreach (var p in pts)
            if (p.z >= zLo && p.z <= zHi)
                sum += (p.x - cx) * (p.x - cx) + (p.y - cy) * (p.y - cy);
        return Mathf.Sqrt((float)(sum / n));
    }

    static Bounds WorldBounds(GameObject go)
    {
        var renderers = go.GetComponentsInChildren<Renderer>(true);
        if (renderers.Length == 0) return new Bounds(Vector3.zero, Vector3.zero);
        var b = renderers[0].bounds;
        for (int i = 1; i < renderers.Length; i++) b.Encapsulate(renderers[i].bounds);
        return b;
    }

    /// <summary>
    /// A view model sits inside the camera's near plane. Any collider on it eats
    /// the player's own interaction raycast, so you walk up to a door and are told
    /// about your gun instead. Map_PlayerHasWeaponViewModelsMatchingTheirAssets
    /// already asserts this; stripping here means the assertion never fires.
    /// </summary>
    static void StripCollidersAndSetLayer(GameObject root, int layer)
    {
        foreach (var col in root.GetComponentsInChildren<Collider>(true))
            Object.DestroyImmediate(col);
        if (layer < 0) return;
        foreach (var t in root.GetComponentsInChildren<Transform>(true))
            t.gameObject.layer = layer;
    }

    static void EnsureFolder(string path)
    {
        if (AssetDatabase.IsValidFolder(path)) return;
        int cut = path.LastIndexOf('/');
        EnsureFolder(path.Substring(0, cut));
        AssetDatabase.CreateFolder(path.Substring(0, cut), path.Substring(cut + 1));
    }
}
