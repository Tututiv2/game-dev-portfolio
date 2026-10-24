using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.ProBuilder;
using UnityEngine;
using UnityEngine.ProBuilder;

/// <summary>
/// Phase 3, layer 1 of 3: the modular structure kit.
///
/// Spec §17's rewritten art box says structure is "a small modular kit ... on a
/// fixed grid (2 m or 4 m), so pieces snap and tile", surfaced with the owned PBR
/// material sets, because "quality comes from materials, not mesh detail".
///
/// This authors that kit through the ProBuilder scripting API rather than by hand.
/// The point of using ProBuilder instead of raw <see cref="Mesh"/> is that every
/// piece stays fully editable with the normal ProBuilder tools afterwards — this
/// tool gives a correct, reproducible starting kit, not a locked one.
///
/// Idempotent, exactly like <c>ContentBootstrap</c>: prefabs are overwritten in
/// place at fixed paths, so GUIDs survive a re-run and anything already placed in
/// a scene keeps pointing at the right piece.
/// </summary>
public static class MapKitBuilder
{
    // ---- The grid contract. Everything in the kit obeys these three numbers. ----

    /// <summary>Cell size. §17 offers 2 m or 4 m; 4 m suits a zombies map's sightlines.</summary>
    public const float Grid = 4f;

    /// <summary>Storey height. Walls are one grid cell tall so the kit stacks.</summary>
    public const float WallHeight = 4f;

    /// <summary>
    /// Structural thickness. This is <b>Law 7</b> expressed as geometry: collision
    /// must be ≥ 0.5 m or the 0.12 m navmesh voxel misses it and zombies path
    /// straight through. Because the visual mesh is itself 0.5 m thick, the
    /// MeshCollider satisfies Law 7 with no separate collision proxy to forget.
    /// </summary>
    public const float Thickness = 0.5f;

    /// <summary>
    /// How far <c>Kit_Ramp_4</c> climbs across its one cell: half a storey, on a
    /// 1:2 slope. Half a storey and not a whole one because a 4 m rise over a 4 m
    /// run is a 45 degree ramp, which is a wall you slide down. Anything that needs
    /// to reach roof height uses the stairs, or a ramp onto a 2 m platform — and
    /// 2 m is exactly the height of <c>Kit_Wall_4_Half</c>, so half walls make
    /// correct supports for that platform with no new piece.
    /// </summary>
    public const float RampRise = 2f;

    /// <summary>
    /// Default metres of surface per texture repeat, for any piece that does not
    /// state its own. See <see cref="KitPiece.MetresPerTile"/> for why this is no
    /// longer one number for the whole kit.
    /// </summary>
    const float k_DefaultMetresPerTile = 2f;

    public const string KitFolder = "Assets/_Project/Art/Kit";
    const string k_MeshFolder = KitFolder + "/Meshes";
    const string k_MaterialFolder = "Assets/_Project/Art/Materials";

    /// <summary>
    /// Every piece the kit ships, and the material set that surfaces it. Tests read
    /// this array, so adding a piece here is what makes it a required piece.
    /// </summary>
    public static readonly KitPiece[] Pieces =
    {
        new KitPiece("Kit_Floor_4x4",       "Concrete/Concrete",                             1.0f, 0.22f),
        new KitPiece("Kit_Ceiling_4x4",     "Concrete (5)/Concrete (5)",                     1.0f, 0.22f),
        new KitPiece("Kit_Wall_4",          "Brick wall seamless/Brick wall seamless",       0.7f, 0.25f),
        new KitPiece("Kit_Wall_4_Doorway",  "Brick wall seamless/Brick wall seamless",       0.7f, 0.25f),
        new KitPiece("Kit_Wall_4_Window",   "Brick wall seamless (3)/Brick wall seamless (3)", 0.7f, 0.25f),
        new KitPiece("Kit_Wall_4_Half",     "Cracked concrete/Cracked concrete",             1.5f, 0.20f),
        new KitPiece("Kit_Pillar",          "Concrete (9)/Concrete (9)",                     1.0f, 0.22f),
        new KitPiece("Kit_Stairs_4",        "Cracked concrete (4)/Cracked concrete (4)",     1.0f, 0.20f),
        new KitPiece("Kit_Ramp_4",          "Diamond Plate Metal 1/Diamond Plate Metal 1",   0.5f, 0.55f),
    };

    public struct KitPiece
    {
        public readonly string Name;
        /// <summary>Path under <c>Art/Materials</c>, without the <c>.mat</c> extension.</summary>
        public readonly string Material;

        /// <summary>
        /// Metres of surface per texture repeat, for THIS piece.
        ///
        /// <para>This used to be one constant (2 m) shared by the whole kit, on the
        /// reasoning that a single number keeps texel density uniform. It does — and
        /// uniform texel density is worth having — but it also makes every material
        /// depict its subject at whatever size 2 m happens to imply, which is only
        /// correct by accident.</para>
        ///
        /// <para><b>The brick number is measured, not chosen.</b> A render of a 4 m
        /// wall with a 1 m reference cube against it put a brick course at 0.217 m
        /// and a brick at 0.67 m long. Real brick is 0.086 m per course and 0.215 m
        /// long, so the texture was depicting brick at roughly 2.8x life size — which
        /// is why the walls read as a toy at a distance and as blur up close. 2 / 2.8
        /// is 0.7, and 0.7 puts a course at 0.076 m and a brick at 0.235 m.</para>
        ///
        /// <para>The other numbers are <b>eye judgements, not measurements</b>: the
        /// diamond plate was showing 30 cm treads (real is nearer 5 cm) and the
        /// concrete read as 4 m granite slabs. They are a starting point to be
        /// tuned by looking, not a result to be trusted.</para>
        /// </summary>
        public readonly float MetresPerTile;

        /// <summary>
        /// Smoothness this piece's material is conditioned to.
        ///
        /// <para>Needed because the pack ships sets at wildly different values and
        /// several at <b>zero</b> — <c>Concrete</c>, <c>Concrete (5)</c> and
        /// <c>Cracked concrete</c> all had <c>_Smoothness: 0</c>, and
        /// <c>Concrete.mat</c> also had <c>_SpecColor</c> at pure black. A perfectly
        /// rough surface has no specular lobe at all, so those three were fully
        /// diffuse no matter what F0 was written next to them: the ground in this map
        /// was flat paint, not PBR.</para>
        ///
        /// <para>Kept low deliberately. Weathered brick and dusty concrete are not
        /// semi-gloss, and high smoothness on a normal-mapped surface is half of what
        /// produced the distance sparkle. The ramp's diamond plate is the exception —
        /// painted steel really is glossier than a wall.</para>
        /// </summary>
        public readonly float Smoothness;

        public KitPiece(string name, string material,
                        float metresPerTile = k_DefaultMetresPerTile,
                        float smoothness = 0.25f)
        {
            Name = name;
            Material = material;
            MetresPerTile = metresPerTile;
            Smoothness = smoothness;
        }

        public string PrefabPath => $"{KitFolder}/{Name}.prefab";
        public string MaterialPath => $"{k_MaterialFolder}/{Material}.mat";
    }

    [MenuItem("Tools/Aftermarket/Build Map Kit")]
    public static void Build() => Build(force: false);

    [MenuItem("Tools/Aftermarket/Build Map Kit (FORCE - discards scene overrides)")]
    public static void BuildForced() => Build(force: true);

    /// <summary>
    /// Authors the kit. <b>Not safe once the map scene exists</b> — see the guard.
    /// </summary>
    public static void Build(bool force)
    {
        // Rebuilding is idempotent in GUID (the prefab and .mesh ASSET paths are
        // fixed, so anything referencing a piece keeps working) but it is NOT
        // idempotent in fileID: PrefabUtility.SaveAsPrefabAsset mints fresh local
        // fileIDs for the prefab's CHILD objects every time. Verified on this kit —
        // rebuilding Kit_Wall_4_Window reassigned all four of its children.
        //
        // The scene stores per-instance overrides against exactly those child
        // fileIDs, including the m_Mesh override MapSeeder writes on every placed
        // piece. Orphaned overrides are dropped silently on the next save, so a
        // rebuild after the map is dressed quietly discards hand edits. There is no
        // error and no undo.
        if (!force && File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError(
                $"[MapKitBuilder] refusing to rebuild: {MapSeeder.ScenePath} exists.\n" +
                "Rebuilding assigns NEW fileIDs to each prefab's child objects, and the scene's " +
                "per-instance overrides target the old ones. They would be orphaned and dropped " +
                "on the next save — silently, with no undo.\n" +
                "If the scene is not dressed yet and you accept that, use " +
                "Tools > Aftermarket > Build Map Kit (FORCE), then re-seed the map.\n" +
                "To change only the surfacing, use Tools > Aftermarket > Condition Kit Materials, " +
                "which touches .mat assets only and cannot disturb any fileID.");
            return;
        }

        Directory.CreateDirectory(KitFolder);
        Directory.CreateDirectory(k_MeshFolder);

        var built = new List<string>();
        try
        {
            foreach (var piece in Pieces)
            {
                var root = Author(piece);
                if (root == null) continue;

                // Must happen BEFORE SaveAsPrefabAsset. A ProBuilder mesh is generated
                // at runtime and is not an asset, and SaveAsPrefabAsset does not pull it
                // into the prefab file — the prefab lands with a null sharedMesh, so it
                // renders nothing and has no collision. ProBuilder only persists meshes
                // itself when the experimental `meshesAreAssets` preference is on, which
                // it is not by default. So the kit persists them explicitly.
                PersistMeshes(root);

                PrefabUtility.SaveAsPrefabAsset(root, piece.PrefabPath);
                Object.DestroyImmediate(root);
                built.Add(piece.Name);
            }
        }
        finally
        {
            AssetDatabase.SaveAssets();
            AssetDatabase.Refresh();
        }

        Debug.Log($"[MapKitBuilder] {built.Count}/{Pieces.Length} pieces written to {KitFolder}: {string.Join(", ", built)}.");
    }

    /// <summary>
    /// Surfacing fixes only. Separate from <see cref="Build"/> on purpose.
    ///
    /// <para>These were originally bolted onto the end of Build(), which meant any
    /// geometry rebuild also re-asserted them over shared <c>.mat</c> assets — so a
    /// material hand-tuned during dressing would be quietly reverted by an unrelated
    /// kit change. Split out, this touches nothing but material properties, mints no
    /// fileIDs, and can be run at any point in the project's life.</para>
    /// </summary>
    [MenuItem("Tools/Aftermarket/Condition Kit Materials")]
    public static void ConditionKitMaterials()
    {
        int sharpened = ApplyAnisotropicFiltering();
        int conditioned = ConditionMaterialsForDistance();
        Debug.Log($"[MapKitBuilder] anisotropic filtering set on {sharpened} textures; " +
                  $"{conditioned} materials conditioned.");
    }

    /// <summary>
    /// Strips parallax mapping and takes the gloss down on the kit's materials.
    ///
    /// <para><b>Why:</b> distant walls were breaking into a bright diamond lattice —
    /// a regular, obviously-wrong moire that got worse when the brick tiling was
    /// corrected from 2 m to 0.7 m per repeat. The obvious suspect was anisotropic
    /// filtering, and that was wrong: the textures already had mipmaps on, aniso 8
    /// and trilinear filtering, and the PC quality level already forces aniso on.
    /// Turning it on again changed nothing.</para>
    ///
    /// <para>The actual cause is that every material in this pack ships with its
    /// <c>displacement.png</c> bound to <c>_ParallaxMap</c>. That switches on URP
    /// Lit's parallax path, which offsets the UV per pixel by view angle — and at a
    /// grazing angle, at distance, with a tile repeating every 0.7 m, the offset
    /// swims against the mip chain and produces exactly that lattice. Parallax buys
    /// almost nothing on a wall you mostly see face-on and costs a great deal here.
    /// </para>
    ///
    /// <para>Smoothness comes down at the same time: 0.5 is a semi-gloss, and
    /// weathered brick and dusty concrete are not semi-gloss. High smoothness on a
    /// normal-mapped surface is the other half of the sparkle, because the mip chain
    /// averages the normals away while leaving the specular lobe just as tight.</para>
    /// </summary>
    static int ConditionMaterialsForDistance()
    {
        var seen = new HashSet<string>();
        int changed = 0;

        foreach (var piece in Pieces)
        {
            if (!seen.Add(piece.MaterialPath)) continue;

            var material = AssetDatabase.LoadAssetAtPath<Material>(piece.MaterialPath);
            if (material == null) continue;

            bool dirty = false;

            if (material.HasProperty("_ParallaxMap") && material.GetTexture("_ParallaxMap") != null)
            {
                material.SetTexture("_ParallaxMap", null);
                material.DisableKeyword("_PARALLAXMAP");
                dirty = true;
            }

            // The pack binds each set's roughness.png to _MetallicGlossMap. URP Lit
            // reads that map's RED channel as METALLIC, and a roughness map is mostly
            // bright — so every wall in this project was rendering as a semi-metal
            // and mirroring the sky. That is where the bright diamond lattice on
            // distant walls came from: an environment reflection, modulated by the
            // normal map, therefore repeating exactly with the tiling. It was not
            // anisotropic filtering (already forced on) and not parallax (removed
            // above and it made no difference); both of those were guesses, this one
            // is what the material file actually says.
            //
            // These are SPECULAR-workflow materials (_WorkflowMode 0, keyword
            // _SPECULAR_SETUP), so the reflective map is _SpecGlossMap and clearing
            // _MetallicGlossMap alone does nothing — which is exactly what happened
            // on the first attempt: it reported zero changes while the file still
            // had the map bound.
            foreach (var slot in new[] { "_SpecGlossMap", "_MetallicGlossMap" })
            {
                if (!material.HasProperty(slot) || material.GetTexture(slot) == null) continue;
                material.SetTexture(slot, null);
                dirty = true;
            }
            material.DisableKeyword("_METALLICSPECGLOSSMAP");

            // The dielectric F0. Brick, concrete and rubble are all dielectrics and
            // reflect about 0.04 of incident light; the pack had a bright roughness
            // texture driving this instead, which is a mirror.
            //
            // 0.04 is the LINEAR value, and it must not be written straight into the
            // property. _SpecColor is Color-typed, and in a linear-colour-space
            // project Unity gamma-decodes Color properties on upload — so authoring
            // 0.04 arrives at the shader as roughly 0.003, about thirteen times too
            // dark, which is not "physically correct dielectric" but "specular
            // switched off". URP's own Lit.shader defaults _SpecColor to (0.2,0.2,0.2)
            // for exactly this reason. Color.gamma is Unity's own encoder, so this
            // asks for linear 0.04 and gets whatever authored value produces it.
            var f0Linear = new Color(0.04f, 0.04f, 0.04f, 1f);
            var dielectric = QualitySettings.activeColorSpace == ColorSpace.Linear
                ? f0Linear.gamma
                : f0Linear;

            if (material.HasProperty("_SpecColor") &&
                !ApproximatelyEqual(material.GetColor("_SpecColor"), dielectric))
            {
                material.SetColor("_SpecColor", dielectric);
                dirty = true;
            }

            if (material.HasProperty("_Metallic") && material.GetFloat("_Metallic") > 0f)
            {
                material.SetFloat("_Metallic", 0f);
                dirty = true;
            }

            // ASSERT the piece's smoothness, do not merely clamp it.
            //
            // The previous version only ever lowered it — `if (current > target)` —
            // which was useless for the three sets the pack ships at _Smoothness: 0
            // (Concrete, Concrete (5), Cracked concrete). Once _SpecGlossMap is
            // cleared and _METALLICSPECGLOSSMAP is off, URP Lit reads smoothness from
            // that float, and 0 is a perfectly rough surface with no specular lobe at
            // all. So those three stayed fully diffuse and the corrected _SpecColor
            // bought them nothing — the ground was painted concrete, not lit concrete.
            // A guard that can only go one way cannot fix a value that starts at the
            // bottom.
            //
            // Setting rather than clamping means this tool asserts a known baseline,
            // so do not run it after hand-tuning a material. It is a separate menu
            // item precisely so that is your choice and not a side effect of a
            // geometry rebuild.
            if (material.HasProperty("_Smoothness") &&
                !Mathf.Approximately(material.GetFloat("_Smoothness"), piece.Smoothness))
            {
                material.SetFloat("_Smoothness", piece.Smoothness);
                dirty = true;
            }

            // Fully qualified: UnityEditor.ProBuilder also defines an EditorUtility.
            if (dirty) { UnityEditor.EditorUtility.SetDirty(material); changed++; }
        }

        AssetDatabase.SaveAssets();
        return changed;
    }

    static bool ApproximatelyEqual(Color a, Color b) =>
        Mathf.Abs(a.r - b.r) < 1e-3f && Mathf.Abs(a.g - b.g) < 1e-3f && Mathf.Abs(a.b - b.b) < 1e-3f;

    /// <summary>
    /// Turns on anisotropic filtering for every texture the kit's materials use.
    ///
    /// <para>Without it, a tiling texture viewed at a grazing angle — which is what
    /// a floor and a receding wall always are — shimmers into a moire lattice,
    /// because the mip chain picks one blur level for a footprint that is long in
    /// one direction and short in the other. It got much worse when the brick was
    /// corrected from 2 m to 0.7 m per repeat, since halving the tile size doubles
    /// the frequency the mip chain has to cope with. Fixing the texel scale without
    /// this makes distant walls worse, not better.</para>
    ///
    /// <para>Idempotent and cheap: a texture already at the target level is skipped,
    /// so re-running Build does not trigger a full reimport of the material pack.</para>
    /// </summary>
    static int ApplyAnisotropicFiltering()
    {
        const int level = 8;

        var seen = new HashSet<string>();
        int changed = 0;

        foreach (var piece in Pieces)
        {
            var material = AssetDatabase.LoadAssetAtPath<Material>(piece.MaterialPath);
            if (material == null) continue;

            foreach (var property in material.GetTexturePropertyNames())
            {
                var texture = material.GetTexture(property);
                if (texture == null) continue;

                string path = AssetDatabase.GetAssetPath(texture);
                if (string.IsNullOrEmpty(path) || !seen.Add(path)) continue;

                var importer = AssetImporter.GetAtPath(path) as TextureImporter;
                if (importer == null) continue;
                if (importer.anisoLevel >= level && importer.filterMode == FilterMode.Trilinear) continue;

                importer.anisoLevel = level;
                importer.filterMode = FilterMode.Trilinear;   // aniso does nothing on Point/Bilinear
                importer.SaveAndReimport();
                changed++;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------------

    static GameObject Author(KitPiece piece)
    {
        var material = AssetDatabase.LoadAssetAtPath<Material>(piece.MaterialPath);
        if (material == null)
        {
            Debug.LogError($"[MapKitBuilder] {piece.Name}: material not found at {piece.MaterialPath}. " +
                           "The ShadowVultureMaterialPack subset has to be copied into Art/Materials first.");
            return null;
        }

        // The root carries no geometry. Its origin is the piece's grid anchor:
        // bottom-centre of the 4x4 footprint, so a piece dropped at a grid
        // coordinate lands exactly where the coordinate says it does.
        var root = new GameObject(piece.Name);

        switch (piece.Name)
        {
            case "Kit_Floor_4x4":
                // Seated by its TOP so the walkable surface is y = 0.
                Seat(Cube(root, Grid, Thickness, Grid), Seating.Top);
                break;

            case "Kit_Ceiling_4x4":
                Seat(Cube(root, Grid, Thickness, Grid), Seating.Bottom);
                break;

            case "Kit_Wall_4":
                Seat(Cube(root, Grid, WallHeight, Thickness), Seating.Bottom);
                break;

            case "Kit_Wall_4_Half":
                // Waist-high cover. Half a storey.
                Seat(Cube(root, Grid, WallHeight * 0.5f, Thickness), Seating.Bottom);
                break;

            case "Kit_Pillar":
                Seat(Cube(root, Thickness, WallHeight, Thickness), Seating.Bottom);
                break;

            case "Kit_Wall_4_Doorway":
            {
                // GenerateDoor's `depth` is the FULL front-to-back thickness, not a
                // half-extent — measured, not assumed: passing 0.25 produced a 0.25 m
                // thick wall, which the Law 7 test rejected.
                var door = ShapeGenerator.GenerateDoor(
                    PivotLocation.Center,
                    totalWidth: Grid,
                    totalHeight: WallHeight,
                    ledgeHeight: WallHeight - 2.4f,   // 2.4 m clear head height
                    legWidth: 1.1f,                   // 1.8 m clear opening
                    depth: Thickness);
                Adopt(root, door);
                Seat(door, Seating.Bottom);
                break;
            }

            case "Kit_Wall_4_Window":
            {
                // Four boxes, not one mesh: a header, a sill and two jambs. Every
                // piece is >= Thickness in its thinnest axis, so Law 7 holds for
                // each collider individually.
                const float sillH = 1.0f;      // sill top
                const float headH = 2.6f;      // opening top
                const float jambW = 0.75f;     // each side post

                float openW = Grid - jambW * 2f;

                var sill = Cube(root, openW, sillH, Thickness);
                sill.transform.localPosition = new Vector3(0f, sillH * 0.5f, 0f);

                var header = Cube(root, openW, WallHeight - headH, Thickness);
                header.transform.localPosition = new Vector3(0f, headH + (WallHeight - headH) * 0.5f, 0f);

                var left = Cube(root, jambW, WallHeight, Thickness);
                left.transform.localPosition = new Vector3(-(openW + jambW) * 0.5f, WallHeight * 0.5f, 0f);

                var right = Cube(root, jambW, WallHeight, Thickness);
                right.transform.localPosition = new Vector3((openW + jambW) * 0.5f, WallHeight * 0.5f, 0f);
                break;
            }

            case "Kit_Stairs_4":
            {
                var stair = ShapeGenerator.GenerateStair(
                    PivotLocation.Center,
                    new Vector3(Grid, WallHeight, Grid),
                    steps: 12,
                    buildSides: true);
                Adopt(root, stair);
                Seat(stair, Seating.Bottom);
                break;
            }

            case "Kit_Ramp_4":
            {
                // A TRUE WEDGE, replacing a rotated slab.
                //
                // The slab version tiled correctly, seated correctly, and passed every
                // test in MapKitTests — because every one of those tests measures
                // BOUNDS. Its bounds did start at y = 0. Its WALKABLE SURFACE did not:
                // a slab tilted about its own centre lifts its low end by
                // Thickness * cos(angle) = 0.45 m, so the ramp had a 0.45 m lip at the
                // foot. That is above the navmesh step height, so a zombie could not
                // get onto it at all, and above CharacterController.stepOffset, so
                // nor could the player. The ramp was unusable and nothing could see it.
                //
                // Built by collapsing a cube's two low-end top corners down onto the
                // base rather than authoring triangles by hand. That keeps ProBuilder's
                // winding and normals exactly as generated — the silent-inversion risk
                // the old comment was avoiding — while giving a flat base on y = 0 and
                // a top face that runs from y = 0 at the low edge to RampRise at the
                // high edge, across exactly one grid cell.
                var wedge = ShapeGenerator.GenerateCube(PivotLocation.Center, new Vector3(Grid, RampRise, Grid));
                Adopt(root, wedge);

                var source = wedge.positions;
                var collapsed = new Vector3[source.Count];
                for (int i = 0; i < source.Count; i++)
                {
                    collapsed[i] = source[i];
                    // Low end is -Z; the cube is centred, so its base is -RampRise/2.
                    if (collapsed[i].y > 0f && collapsed[i].z < 0f)
                        collapsed[i].y = -RampRise * 0.5f;
                }
                wedge.positions = collapsed;

                wedge.ToMesh();
                wedge.Refresh();
                Seat(wedge, Seating.Bottom);
                break;
            }

            default:
                Debug.LogError($"[MapKitBuilder] no authoring rule for '{piece.Name}'.");
                Object.DestroyImmediate(root);
                return null;
        }

        foreach (var mesh in root.GetComponentsInChildren<ProBuilderMesh>())
            Finish(mesh, material, piece.MetresPerTile);

        return root;
    }

    /// <summary>
    /// Writes each child's generated mesh out as a real .mesh asset and repoints the
    /// MeshFilter and MeshCollider at it.
    ///
    /// Idempotent in the way that matters: if the asset already exists, its contents
    /// are overwritten in place with <see cref="EditorUtility.CopySerialized"/> rather
    /// than deleted and recreated, so the GUID survives and anything already placed in
    /// a scene keeps pointing at the same mesh.
    /// </summary>
    static void PersistMeshes(GameObject root)
    {
        var filters = root.GetComponentsInChildren<MeshFilter>();
        for (int i = 0; i < filters.Length; i++)
        {
            var filter = filters[i];
            var generated = filter.sharedMesh;
            if (generated == null) continue;

            string path = filters.Length == 1
                ? $"{k_MeshFolder}/{root.name}.mesh"
                : $"{k_MeshFolder}/{root.name}_{i}.mesh";

            var existing = AssetDatabase.LoadAssetAtPath<Mesh>(path);
            Mesh asset;
            if (existing != null)
            {
                // Fully qualified: UnityEditor.ProBuilder also defines an EditorUtility.
                UnityEditor.EditorUtility.CopySerialized(generated, existing);
                asset = existing;
            }
            else
            {
                asset = Object.Instantiate(generated);
                asset.name = Path.GetFileNameWithoutExtension(path);
                AssetDatabase.CreateAsset(asset, path);
            }

            filter.sharedMesh = asset;

            var collider = filter.GetComponent<MeshCollider>();
            if (collider != null) collider.sharedMesh = asset;
        }
    }

    // ---- helpers -----------------------------------------------------------

    enum Seating { Bottom, Top }

    static ProBuilderMesh Cube(GameObject root, float x, float y, float z)
    {
        var mesh = ShapeGenerator.GenerateCube(PivotLocation.Center, new Vector3(x, y, z));
        Adopt(root, mesh);
        mesh.transform.localPosition = new Vector3(0f, y * 0.5f, 0f);
        return mesh;
    }

    static void Adopt(GameObject root, ProBuilderMesh mesh)
    {
        var t = mesh.transform;
        t.SetParent(root.transform, false);
        t.localPosition = Vector3.zero;
        t.localRotation = Quaternion.identity;
        t.localScale = Vector3.one;
        mesh.gameObject.name = root.name + "_Mesh";
    }

    /// <summary>
    /// Offsets a child so the root's origin sits at the piece's grid anchor:
    /// centred on X/Z, and either sitting on y = 0 or hanging below it.
    /// Reads real bounds, so it works for stairs and doorways whose generator
    /// pivot is not where you would guess.
    /// </summary>
    static void Seat(ProBuilderMesh mesh, Seating seating)
    {
        mesh.ToMesh();
        mesh.Refresh();

        var b = mesh.GetComponent<MeshFilter>().sharedMesh.bounds;
        float y = seating == Seating.Bottom ? -b.min.y : -b.max.y;
        mesh.transform.localPosition = new Vector3(-b.center.x, y, -b.center.z);
    }

    static void Finish(ProBuilderMesh mesh, Material material, float metresPerTile)
    {
        // ProBuilder's auto-UV planar projection spans one UV unit per metre of
        // local geometry, so scale = 1/metresPerTile makes every face on this piece
        // tile at the same rate regardless of the face's size. Local rather than
        // world projection on purpose: world-space UVs are baked at authoring
        // position and would not follow a prefab instance.
        var faces = mesh.faces;
        for (int i = 0; i < faces.Count; i++)
        {
            var face = faces[i];
            face.manualUV = false;
            var uv = face.uv;
            uv.useWorldSpace = false;
            uv.scale = Vector2.one / metresPerTile;
            face.uv = uv;
        }

        mesh.ToMesh();
        mesh.Refresh();
        mesh.Optimize();

        mesh.GetComponent<MeshRenderer>().sharedMaterial = material;

        // Law 7 again: the collider is the 0.5 m-thick visual mesh itself.
        var collider = mesh.gameObject.GetComponent<MeshCollider>();
        if (collider == null) collider = mesh.gameObject.AddComponent<MeshCollider>();
        collider.sharedMesh = mesh.GetComponent<MeshFilter>().sharedMesh;
    }
}
