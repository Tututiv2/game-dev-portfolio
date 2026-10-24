using System.Collections.Generic;
using System.IO;
using Unity.AI.Navigation;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.AI;
using UnityEngine.SceneManagement;

/// <summary>
/// First dressing pass: places the forged props into the map.
///
/// <para><b>This is NOT a seeder and it must never become one.</b> It only ever
/// ADDS, under a single <c>Props</c> root, and it refuses to run twice by
/// checking for that root. Everything it makes is a normal GameObject you can
/// move, rotate or delete by hand — deleting the <c>Props</c> root undoes the
/// whole pass and lets you re-run it. <see cref="MapSeeder"/>'s one-shot rule is
/// untouched: the structure, navmesh surface and spawn points are not
/// regenerated here.</para>
///
/// <para><b>Why props are placed by a tool at all</b>, when §12 wants the map
/// dressed by hand: this is the starting scatter, not the finished dressing. It
/// puts cover where the blockout has none and gives the hand pass something to
/// push around, which is a great deal easier than placing the first forty
/// objects into an empty yard.</para>
/// </summary>
public static class PropDresser
{
    const string k_Root = "Props";
    const string k_PropFolder = "Assets/_Project/Art/Props";

    struct Placement
    {
        public string Slug;
        public float X, Z, Yaw;
        public string Material;

        public Placement(string slug, float x, float z, float yaw, string material)
        {
            Slug = slug; X = x; Z = z; Yaw = yaw; Material = material;
        }
    }

    // Cell centres are 4 m apart; these are world metres so props can sit off-grid,
    // which is the point — clutter that snaps to the structure grid reads as more
    // structure rather than as debris.
    static readonly Placement[] k_Placements =
    {
        // --- Zone A, the spawn yard: cover on the long open axis ---
        new Placement("barrel_rusted",  21.0f,  6.4f,  12f, "Rusty metal texture/Rusty metal texture"),
        new Placement("barrel_rusted",  21.8f,  7.1f, -40f, "Rusty metal texture/Rusty metal texture"),
        new Placement("crate_wooden",   26.5f,  6.0f,  25f, "Grunge texture/Grunge texture"),
        new Placement("debris_pile",    31.5f,  9.0f,   0f, "Cracked concrete/Cracked concrete"),
        new Placement("pallet_stack",   19.4f, 13.2f,  -8f, "Grunge texture/Grunge texture"),
        new Placement("crate_wooden",   33.0f,  3.4f, -15f, "Grunge texture/Grunge texture"),
        new Placement("barrel_rusted",  33.8f,  4.1f,  30f, "Rusty metal texture/Rusty metal texture"),
        new Placement("debris_pile",     6.5f, 16.4f,  55f, "Cracked concrete/Cracked concrete"),
        new Placement("crate_wooden",   45.5f, 14.6f,  40f, "Grunge texture/Grunge texture"),

        // --- Against the loading dock, so the ramp has a reason to exist ---
        new Placement("pallet_stack",   36.6f,  4.2f,  18f, "Grunge texture/Grunge texture"),
        new Placement("barrel_rusted",  37.4f,  9.6f, -22f, "Rusty metal texture/Rusty metal texture"),

        // --- Zone B, warehouse interior and its approach ---
        new Placement("crate_wooden",    7.2f, 31.0f,  10f, "Grunge texture/Grunge texture"),
        new Placement("crate_wooden",    8.1f, 31.9f, -35f, "Grunge texture/Grunge texture"),
        new Placement("barrel_rusted",  17.6f, 29.4f,   0f, "Rusty metal texture/Rusty metal texture"),
        new Placement("pallet_stack",   10.5f, 41.0f,  62f, "Grunge texture/Grunge texture"),
        new Placement("debris_pile",     5.0f, 37.0f, -20f, "Cracked concrete/Cracked concrete"),
        new Placement("barrel_rusted",   2.9f, 24.0f,  44f, "Rusty metal texture/Rusty metal texture"),

        // --- Zone C, substation ---
        new Placement("debris_pile",    45.0f, 21.5f,  15f, "Cracked concrete/Cracked concrete"),
        new Placement("crate_wooden",   38.4f, 30.6f, -12f, "Grunge texture/Grunge texture"),
        new Placement("barrel_rusted",  47.2f, 30.2f,   8f, "Rusty metal texture/Rusty metal texture"),
        new Placement("barrel_rusted",  47.9f, 31.0f, -28f, "Rusty metal texture/Rusty metal texture"),
        new Placement("pallet_stack",   50.5f, 24.4f,  33f, "Grunge texture/Grunge texture"),

        // --- The shrine: a landmark, deliberately alone and visible ---
        new Placement("shrine_obelisk", 24.0f, 47.5f,   0f, "Partially mossy stone/Partially mossy stone"),
    };

    // generator_unit is NOT in that list on purpose. Every other prop reconstructed
    // cleanly once the DINOv2 encoder was wired up; that one is still a spiky mess
    // lying on its side (Screenshots/props/generator_unit.png). It stays out of the
    // map until it is re-forged.

    [MenuItem("Tools/Aftermarket/Dress Map With Props")]
    public static void Dress()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[PropDresser] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        // Same guard as SceneShot and SkySetup: OpenScene from a script does not
        // prompt, it discards unsaved changes and clears the undo stack.
        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[PropDresser] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        // Scan the scene's ROOT OBJECTS, not GameObject.Find.
        //
        // GameObject.Find only matches ACTIVE objects, and deactivating the Props
        // root is the obvious thing to do while dressing — you switch it off to see
        // the blockout underneath. With Find, that made the guard pass, so a second
        // run would silently stack another 22 props on the first set and rebake the
        // navmesh around doubled geometry.
        bool alreadyDressed = false;
        foreach (var go in scene.GetRootGameObjects())
            if (go.name == k_Root) { alreadyDressed = true; break; }

        if (alreadyDressed)
        {
            Debug.LogWarning(
                $"[PropDresser] a '{k_Root}' root already exists, so nothing was placed. " +
                "This tool only ever adds. Delete the Props object to run it again — " +
                "that also throws away any hand adjustments made to those props.");
            return;
        }

        var keepOut = MapBake.KeepOutPoints();

        var root = new GameObject(k_Root).transform;
        int placed = 0, skipped = 0;

        foreach (var p in k_Placements)
        {
            var position = new Vector3(p.X, 0f, p.Z);

            if (!MapBake.IsClearOfSpawns(position, keepOut, out float nearest))
            {
                Debug.LogWarning($"[PropDresser] skipped {p.Slug} at {position} — {nearest:F2} m " +
                                 $"from a spawn point or the player start, inside the " +
                                 $"{MapBake.SpawnKeepOut} m keep-out.");
                skipped++;
                continue;
            }

            if (Place(p, root, position)) placed++;
        }

        MapBake.Rebake("PropDresser");

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        Debug.Log($"[PropDresser] placed {placed} props ({skipped} skipped for spawn clearance) " +
                  $"under '{k_Root}', and rebaked the navmesh so they block movement. " +
                  "They are ordinary objects — move, rotate or delete any of them by hand.");
    }

    static bool Place(Placement p, Transform root, Vector3 position)
    {
        string path = $"{k_PropFolder}/{p.Slug}.fbx";
        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(path);
        if (prefab == null)
        {
            Debug.LogError($"[PropDresser] missing {path}. Run Tools/forge/forge_props.py.");
            return false;
        }

        var go = (GameObject)PrefabUtility.InstantiatePrefab(prefab, root);
        go.name = p.Slug;
        go.transform.position = position;
        go.transform.rotation = Quaternion.Euler(0f, p.Yaw, 0f);

        // Sit it ON the floor. The pipeline centres each mesh on its bounds, so
        // half of every prop is below y = 0 until this runs.
        var bounds = WorldBounds(go);
        go.transform.position += new Vector3(0f, -bounds.min.y, 0f);

        ApplyMaterial(go, p.Material);
        AddCollider(go);
        return true;
    }

    /// <summary>
    /// ONE box collider on the root, not a MeshCollider per child.
    ///
    /// <para>§14 Law 6: stacked clutter keeps a single collider, or the individual
    /// colliders become navmesh stairs and zombies climb the junk pile. A box is
    /// also the right shape for cover — you want the player's bullets and body to
    /// behave predictably against a barrel, not to catch on a 1,500-triangle
    /// silhouette.</para>
    ///
    /// <para>§14 Law 7 wants collision at least 0.5 m thick. Every prop here is
    /// well over that on its thinnest axis (the barrel, the slimmest, is 0.64 m),
    /// but the check is asserted rather than assumed.</para>
    /// </summary>
    static void AddCollider(GameObject go)
    {
        // LOCAL bounds, built from the mesh corners. NOT
        // InverseTransformVector(worldBounds.size).
        //
        // That was the first version and it is silently, badly wrong. A world
        // Bounds is axis-ALIGNED: for a prop rotated by yaw it is already the
        // inflated box that encloses the rotated mesh. Inverse-transforming its
        // SIZE rotates that inflated box a second time, which is not a coordinate
        // conversion of an extent at all. At yaw 44 degrees it collapses one axis:
        // the barrel, a 0.64 m drum, got m_Size x = 0.0224 — a 22 mm plate. The
        // navmesh bakes from PhysicsColliders at 0.12 m voxels, so a 22 mm box is
        // thinner than one voxel and carves nothing; the prop looked like cover and
        // blocked nobody. Eight of twenty-three colliders came out under the Law 7
        // floor this way.
        var local = LocalBounds(go);

        var box = go.AddComponent<BoxCollider>();
        box.center = local.center;
        box.size = local.size;

        // Measure what was actually WRITTEN, in world units. The first version
        // checked the world AABB instead — which is ~0.9 m on the barrel's
        // thinnest axis and passes comfortably — so it sat silent while a 22 mm
        // collider went into the scene. A guard that reads a different variable
        // from the one it is guarding is not a guard.
        var worldSize = Vector3.Scale(box.size, go.transform.lossyScale);
        float thinnest = Mathf.Min(Mathf.Abs(worldSize.x),
                         Mathf.Min(Mathf.Abs(worldSize.y), Mathf.Abs(worldSize.z)));

        if (thinnest < MapKitBuilder.Thickness)
            Debug.LogWarning($"[PropDresser] {go.name} collider is {thinnest:0.###} m on its " +
                             $"thinnest axis, under the {MapKitBuilder.Thickness} m Law 7 floor. " +
                             $"The {MapSeeder.NavMeshVoxelSize} m navmesh voxel may miss it, so it " +
                             "will not block movement. Size written: " +
                             $"{worldSize.x:0.###} x {worldSize.y:0.###} x {worldSize.z:0.###}.");
    }

    /// <summary>
    /// Axis-aligned bounds of every child mesh, expressed in <paramref name="go"/>'s
    /// OWN local space. Built by pushing the eight corners of each mesh's local
    /// bounds through to the root, so rotation on a child or on the root itself is
    /// handled correctly and nothing gets inflated twice.
    /// </summary>
    static Bounds LocalBounds(GameObject go)
    {
        var root = go.transform;
        bool any = false;
        var local = new Bounds();

        foreach (var filter in go.GetComponentsInChildren<MeshFilter>(true))
        {
            var mesh = filter.sharedMesh;
            if (mesh == null) continue;

            var b = mesh.bounds;
            Vector3 mn = b.min, mx = b.max;
            var corners = new[]
            {
                new Vector3(mn.x, mn.y, mn.z), new Vector3(mx.x, mn.y, mn.z),
                new Vector3(mn.x, mx.y, mn.z), new Vector3(mx.x, mx.y, mn.z),
                new Vector3(mn.x, mn.y, mx.z), new Vector3(mx.x, mn.y, mx.z),
                new Vector3(mn.x, mx.y, mx.z), new Vector3(mx.x, mx.y, mx.z),
            };

            foreach (var c in corners)
            {
                var p = root.InverseTransformPoint(filter.transform.TransformPoint(c));
                if (!any) { local = new Bounds(p, Vector3.zero); any = true; }
                else local.Encapsulate(p);
            }
        }

        return local;
    }

    static void ApplyMaterial(GameObject go, string materialPath)
    {
        var material = AssetDatabase.LoadAssetAtPath<Material>(
            $"Assets/_Project/Art/Materials/{materialPath}.mat");
        if (material == null)
        {
            Debug.LogWarning($"[PropDresser] no material at {materialPath}; {go.name} will " +
                             "render with Unity's default.");
            return;
        }

        // The forge bakes a normal map but there is no albedo step anywhere in the
        // chain, so an unassigned prop renders as flat white. A kit material at
        // least puts it in the same world as the walls until proper prop textures
        // exist.
        foreach (var renderer in go.GetComponentsInChildren<MeshRenderer>(true))
            renderer.sharedMaterial = material;
    }

    static Bounds WorldBounds(GameObject go)
    {
        var renderers = go.GetComponentsInChildren<Renderer>(true);
        var bounds = renderers.Length > 0 ? renderers[0].bounds : new Bounds(go.transform.position, Vector3.zero);
        for (int i = 1; i < renderers.Length; i++) bounds.Encapsulate(renderers[i].bounds);
        return bounds;
    }

}
