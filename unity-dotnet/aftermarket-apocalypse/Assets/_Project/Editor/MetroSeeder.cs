using System.Collections.Generic;
using System.IO;
using System.Linq;
using Unity.AI.Navigation;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.AI;
using UnityEngine.SceneManagement;
using Edge = MapPlan.Edge;

/// <summary>
/// <b>Ashfall Metro</b> — the second map, and the first one authored as data.
///
/// <para>Six zones on the project's 4 m grid, wrapped into a loop, with the
/// corridor-and-stairwell modules cut out of <c>Corridor__Stairs.blend</c> serving
/// as one of them. Everything below is a table: rooms are rectangles, doors are cell
/// edges, machines are coordinates. <see cref="MapPlan"/> derives the geometry and
/// <see cref="MapProps"/> builds the gameplay objects, so a third map is another
/// table and no new code.</para>
///
/// <para><b>This scene is a BUILD OUTPUT.</b> Unlike <c>AshfallDepot</c> — which
/// <see cref="MapSeeder"/> seeds once and refuses to touch again because it is
/// dressed by hand — this one is rebuilt from scratch every run, like
/// <c>Store.unity</c>. Hand edits to it WILL be lost. That is the trade for being
/// able to move a room by editing one line.</para>
///
/// <para><b>The loop is the design.</b> A round-based map that is a tree of dead
/// ends is unplayable past round 10, because the whole game is walking a horde in a
/// circle. <see cref="MetroVerify"/> asserts the loop exists by counting
/// vertex-disjoint routes, rather than by looking at the plan and believing it.</para>
/// </summary>
public static class MetroSeeder
{
    public const string ScenePath = "Assets/_Project/Scenes/AshfallMetro.unity";
    const string k_NavMeshAssetPath = "Assets/_Project/Scenes/AshfallMetro_NavMesh.asset";

    /// <summary>Cell (8,2), and y is the FEET — not the capsule centre. Both halves
    /// of that were wrong the first time it was written for the other map.</summary>
    public static readonly Vector3 PlayerStart = new Vector3(32f, 0.05f, 8f);

    /// <summary>Where the module block's cell (0,0) lands. The modules run along
    /// NEGATIVE x and z in Unity — that is what the derived axis mapping says — so
    /// the block grows down and left from this anchor to cover x 12..14, z 1..4.</summary>
    const int k_ModuleAnchorX = 14;
    const int k_ModuleAnchorZ = 4;

    // ---- the map, as a table ----------------------------------------------

    /// <summary>The layout, so the verifier and the tests read the same table the
    /// builder does rather than a copy of it.</summary>
    public static MapPlan.Storey BuildPlan()
    {
        var s = new MapPlan.Storey(0);

        s.Room(5, 1, 10, 4);            // A  Platform Hall  (spawn)
        s.Room(1, 1, 4, 6);             // B  West Concourse
        // C's floor is the whole east strip, with the imported modules dropped into
        // it below. The first version left the cells the modules do NOT cover as
        // void, and a void cell beside a module is a hole in the floor you can see
        // straight through - the kit does not floor it and the module never did.
        s.Room(11, 1, 14, 4);           // C  Maintenance strip (the module block sits in it)
        s.Room(11, 5, 14, 7);           // Link E  (module block up to the spine)
        s.Room(5, 6, 10, 8);            // Spine
        s.Room(4, 9, 11, 13, false);    // D  North Hall — ROOF COLLAPSED, open to sky
        s.Room(1, 9, 3, 13);            // E  Plant Room  (the power switch)
        s.Room(12, 9, 14, 13);          // F  Vault       (Pack-a-Punch)
        s.Room(12, 8, 14, 8);           // service stair: Link E -> F, the SECOND way north

        // Without that one row, everything north of the spine hung off a single
        // doorway and the whole top half of the map was a dead end. The loop check
        // counted 1 route where a round-based map needs 2.

        // C — Maintenance: imported geometry, so the kit must not floor or wall it.
        foreach (var c in ModuleCells()) s.Module(c.x, c.y, c.x, c.y);

        // --- doors: one cell of each boundary is a doorway, the rest is wall -----
        Gate(s, 5, 1, 5, 4, Edge.West, doorAtZ: 3);          // A -> B
        Gate(s, 11, 1, 11, 4, Edge.West, doorAtZ: 3);        // A -> C maintenance
        Gate(s, 5, 6, 5, 8, Edge.West, doorAtZ: 6);          // B -> Spine
        GateX(s, 5, 8, 10, 8, Edge.North, doorAtX: 8);       // Spine -> D
        Gate(s, 4, 9, 4, 13, Edge.West, doorAtZ: 11);        // D -> E
        Gate(s, 11, 9, 11, 13, Edge.East, doorAtZ: 11);      // D -> F
        GateX(s, 12, 8, 14, 8, Edge.South, doorAtX: 13);     // Link E -> service stair

        // --- windows: boarded, and the only way through before a door is bought --
        // On INTERIOR boundaries on purpose. A window onto a void has a NavMeshLink
        // that leads nowhere; between two rooms it is a real route that opens when
        // the last board comes off.
        s.Wall(5, 1, Edge.West, "Kit_Wall_4_Window");
        s.Wall(5, 4, Edge.West, "Kit_Wall_4_Window");
        s.Wall(6, 8, Edge.North, "Kit_Wall_4_Window");
        s.Wall(10, 8, Edge.North, "Kit_Wall_4_Window");
        s.Wall(4, 9, Edge.West, "Kit_Wall_4_Window");
        s.Wall(4, 13, Edge.West, "Kit_Wall_4_Window");
        s.Wall(11, 9, Edge.East, "Kit_Wall_4_Window");
        s.Wall(11, 13, Edge.East, "Kit_Wall_4_Window");

        return s;
    }

    /// <summary>Wall a whole boundary except one cell, which becomes a doorway.</summary>
    static void Gate(MapPlan.Storey s, int x0, int z0, int x1, int z1, Edge e, int doorAtZ)
    {
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++)
                s.Wall(x, z, e, z == doorAtZ ? "Kit_Wall_4_Doorway" : "Kit_Wall_4");
    }

    static void GateX(MapPlan.Storey s, int x0, int z0, int x1, int z1, Edge e, int doorAtX)
    {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                s.Wall(x, z, e, x == doorAtX ? "Kit_Wall_4_Doorway" : "Kit_Wall_4");
    }

    static readonly (string name, Vector3 at, float yaw, int cost)[] k_Doors =
    {
        ("Door_A_to_B_750",     new Vector3(18f, 0f, 12f), 90f,  750),
        ("Door_A_to_Maint_1000",new Vector3(42f, 0f, 12f), 90f, 1000),
        ("Door_B_to_Spine_1250",new Vector3(18f, 0f, 24f), 90f, 1250),
        ("Door_Spine_to_D_1500",new Vector3(32f, 0f, 34f),  0f, 1500),
        ("Door_D_to_Plant_1250",new Vector3(14f, 0f, 44f), 90f, 1250),
        ("Door_D_to_Vault_1250",new Vector3(46f, 0f, 44f), 90f, 1250),
        ("Door_Service_1000",   new Vector3(52f, 0f, 30f),  0f, 1000),
    };

    static readonly (PerkType perk, int cost, Vector3 at, float yaw)[] k_Perks =
    {
        (PerkType.QuickRevive, 1500, new Vector3(21f, 0f, 15f),   0f),   // A, before any door
        (PerkType.SpeedCola,   3000, new Vector3(8f,  0f, 21f),   0f),   // B
        (PerkType.Juggernog,   2500, new Vector3(20f, 0f, 40f),  90f),   // D
        (PerkType.StaminUp,    2000, new Vector3(8f,  0f, 48f),   0f),   // E
        (PerkType.DoubleTap,   2000, new Vector3(52f, 0f, 48f),   0f),   // F
    };

    static readonly (string name, int slot, Vector3 at, float yaw)[] k_WallBuys =
    {
        ("WallBuy_Platform_Pistol", 0, new Vector3(28f, 1.5f, 3.0f),  0f),
        ("WallBuy_Concourse_Rifle", 1, new Vector3(3.0f, 1.5f, 16f), 90f),
        ("WallBuy_Maint_Rifle",     1, new Vector3(55f, 1.5f, 24f),  90f),
    };

    static readonly (string name, Vector3 at)[] k_Boxes =
    {
        ("MysteryBox_Concourse", new Vector3(8f, 0.6f, 12f)),
        ("MysteryBox_NorthHall", new Vector3(32f, 0.6f, 44f)),
    };

    static readonly (string name, Vector3 at)[] k_BoxSpots =
    {
        ("BoxSpot_Platform", new Vector3(24f, 0.6f, 8f)),
        ("BoxSpot_Spine",    new Vector3(32f, 0.6f, 28f)),
        ("BoxSpot_Vault",    new Vector3(52f, 0.6f, 40f)),
    };

    static readonly (string name, Vector3 at)[] k_PoweredLights =
    {
        ("Light_NorthHall_W", new Vector3(20f, 3.4f, 40f)),
        ("Light_NorthHall_E", new Vector3(40f, 3.4f, 48f)),
        ("Light_Plant",       new Vector3(8f,  3.4f, 44f)),
        ("Light_Vault",       new Vector3(52f, 3.4f, 44f)),
        ("Light_Spine",       new Vector3(32f, 3.4f, 28f)),
    };

    static readonly (string name, Vector3 at)[] k_Lamps =
    {
        ("Lamp_Platform_W", new Vector3(24f, 3.2f, 8f)),
        ("Lamp_Platform_E", new Vector3(36f, 3.2f, 12f)),
        ("Lamp_Concourse",  new Vector3(8f,  3.2f, 12f)),
        ("Lamp_Maint",      new Vector3(52f, 3.2f, 24f)),
    };

    static readonly Vector3 k_PowerSwitch = new Vector3(2.35f, 1.35f, 44f);
    static readonly Vector3 k_PackAPunch = new Vector3(52f, 0f, 44f);
    static readonly Vector3 k_AmmoCrate = new Vector3(38f, 0f, 5f);
    static readonly Vector3 k_Gobblegum = new Vector3(6f, 0f, 6f);

    // ---- module block ------------------------------------------------------

    /// <summary>
    /// Which map cells the imported modules occupy, read from the cut manifest
    /// rather than typed.
    ///
    /// <para>Re-cutting the .blend with a different footprint moves these with it.
    /// A hand-typed copy would be the second source of truth this project keeps
    /// getting caught by.</para>
    /// </summary>
    static List<Vector2Int> ModuleCells()
    {
        var cells = new List<Vector2Int>();
        foreach (var mpath in KitModuleBuilder.ManifestPaths())
        {
            var man = KitModuleBuilder.Load(mpath);
            foreach (var mod in man.modules)
                cells.Add(new Vector2Int(k_ModuleAnchorX - mod.cell[0],
                                         k_ModuleAnchorZ - mod.cell[1]));
        }
        return cells;
    }

    static int PlaceModules(Transform parent)
    {
        int n = 0;
        foreach (var mpath in KitModuleBuilder.ManifestPaths())
        {
            var man = KitModuleBuilder.Load(mpath);
            foreach (var mod in man.modules)
            {
                var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(
                    $"{KitModuleBuilder.ModuleFolder}/Kit_{mod.name}.prefab");
                if (prefab == null) continue;

                // The module's own origin is the centre of its cell, so placing it at
                // a cell centre is the whole transform. Its collision travels with it.
                var go = (GameObject)PrefabUtility.InstantiatePrefab(prefab, parent);
                go.transform.position = MapPlan.Centre(k_ModuleAnchorX - mod.cell[0],
                                                       k_ModuleAnchorZ - mod.cell[1]);
                n++;
            }
        }
        return n;
    }

    // ---- build -------------------------------------------------------------

    /// <summary>The whole map, systems included, in one go: geometry, gameplay,
    /// the permanent Systems root, then the verification pass. This is the entry
    /// point worth using; the pieces are separate so any one can be re-run.</summary>
    [MenuItem("Tools/Aftermarket/Build New Map (Ashfall Metro) - EVERYTHING")]
    public static void BuildAll()
    {
        Build();
        // Scene-agnostic now, so the second map gets the same ScoreManager, HUD,
        // EventSystem, pause menu and round director the first one has.
        CoreLoopBuilder.Build(ScenePath);
        MetroVerify.Run();
    }

    [MenuItem("Tools/Aftermarket/Build New Map (Ashfall Metro)")]
    public static void Build()
    {
        foreach (var piece in MapKitBuilder.Pieces)
            if (AssetDatabase.LoadAssetAtPath<GameObject>(piece.PrefabPath) == null)
            {
                Debug.LogError($"[Metro] kit piece missing: {piece.PrefabPath}. Build Map Kit first.");
                return;
            }

        Directory.CreateDirectory(Path.GetDirectoryName(ScenePath));
        MapPlan.ResetCount();

        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
        var plan = BuildPlan();

        var structure = new GameObject("Structure").transform;
        int kitPieces = MapPlan.BuildStorey(plan, structure);

        var moduleRoot = new GameObject("Modules").transform;
        moduleRoot.SetParent(structure, false);
        int modules = PlaceModules(moduleRoot);

        BuildLighting();
        var player = BuildPlayer();

        // Doors BEFORE the bake is fine and deliberate: they sit on the DoorDebris
        // layer, which the bake excludes, so the navmesh sees open doorways and the
        // door blocks at runtime with a carving obstacle instead (§14 Law 12).
        var gameplay = new GameObject("Gameplay").transform;
        var doorsRoot = new GameObject("Doors").transform;
        doorsRoot.SetParent(gameplay, false);
        foreach (var d in k_Doors) MapProps.Door(d.name, doorsRoot, d.at, d.yaw, d.cost);

        var switchRoot = new GameObject("Power").transform;
        switchRoot.SetParent(gameplay, false);
        MapProps.PowerSwitchAt("PowerSwitch", switchRoot, k_PowerSwitch, 90f);
        foreach (var l in k_PoweredLights) MapProps.PoweredLight(l.name, switchRoot, l.at);

        var lampRoot = new GameObject("Lamps").transform;
        lampRoot.SetParent(structure, false);
        foreach (var l in k_Lamps) MapProps.Lamp(l.name, lampRoot, l.at);

        // Barriers are FOUND on the window pieces, not typed.
        var barrierRoot = new GameObject("Barriers").transform;
        barrierRoot.SetParent(gameplay, false);
        var windows = MapProps.FindWindows(scene);
        for (int i = 0; i < windows.Count; i++) MapProps.Barrier(windows[i], barrierRoot, i);

        // Order matters: the navmesh has to exist before spawn points can be
        // validated against it, because validation IS a navmesh query.
        var surface = BuildNavigation();
        var spawns = BuildValidatedSpawnPoints(PlayerStart);

        // Machines after the spawn points, because their placement is checked
        // against them — a Mystery Box standing on a spawn point is a round that
        // never ends.
        var keepOut = MapBake.KeepOutPoints();
        var machines = new GameObject("Machines").transform;
        machines.SetParent(gameplay, false);

        var definitions = MapProps.WeaponDefinitions();
        foreach (var w in k_WallBuys)
            MapProps.WallBuyAt(w.name, machines, w.at, w.yaw, w.slot, definitions);

        var boxes = new List<MysteryBox>();
        foreach (var b in k_Boxes) boxes.Add(MapProps.BoxAt(b.name, machines, b.at, keepOut));

        var spots = new GameObject("BoxRelocationSpots").transform;
        spots.SetParent(machines, false);
        foreach (var s in k_BoxSpots)
        {
            if (!MapBake.FindClearSpot(s.at, new Vector3(1.2f, 1.2f, 0.8f), keepOut,
                                       out var clear, out string why))
            {
                Debug.LogError($"[Metro] relocation marker {s.name} is unusable ({why}); not created.");
                continue;
            }
            var marker = new GameObject(s.name).transform;
            marker.SetParent(spots, false);
            marker.position = clear;
        }
        foreach (var box in boxes)
        {
            foreach (Transform marker in spots) box.relocationPoints.Add(marker);
            foreach (var other in boxes) if (other != box) box.relocationPoints.Add(other.transform);
        }

        foreach (var p in k_Perks)
            MapProps.PerkMachineAt(p.perk, p.cost, machines, p.at, p.yaw, keepOut);
        MapProps.PackAPunch(machines, k_PackAPunch, 0f, keepOut);
        MapProps.AmmoCrateAt(machines, k_AmmoCrate, 0f, keepOut);
        MapProps.GobblegumAt(machines, k_Gobblegum, 0f, keepOut);

        // Thirteen solid machines went into the other map with no rebake and the
        // navmesh reported open floor where they stood.
        surface.BuildNavMesh();

        // BEFORE SaveScene. BuildNavMesh leaves NavMeshData ownerless, and an inline
        // NavMeshData forces the whole scene to serialize as BINARY whatever
        // EditorSettings says.
        SaveNavMeshAsset(surface);

        EditorSceneManager.SaveScene(scene, ScenePath);
        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();

        if (EditorSettings.serializationMode != SerializationMode.ForceText)
            EditorSettings.serializationMode = SerializationMode.ForceText;
        AssetDatabase.ForceReserializeAssets(new[] { ScenePath },
            ForceReserializeAssetsOptions.ReserializeAssetsAndMetadata);

        AddToBuildSettings();

        Debug.Log($"[Metro] {ScenePath}\n" +
                  $"  {kitPieces} kit pieces, {modules} imported modules, " +
                  $"{windows.Count} boarded windows, {k_Doors.Length} doors, " +
                  $"{spawns.Length} validated spawn points\n" +
                  MapPlan.Ascii(plan) + "\n" +
                  "  This scene is a BUILD OUTPUT and is rebuilt from scratch every run.");
    }

    // ---- the scene's furniture --------------------------------------------

    static void BuildLighting()
    {
        var sun = new GameObject("Sun");
        var light = sun.AddComponent<Light>();
        light.type = LightType.Directional;
        light.intensity = 1.35f;
        light.color = new Color(0.75f, 0.80f, 0.95f);
        sun.transform.rotation = Quaternion.Euler(52f, 205f, 0f);
        light.shadows = LightShadows.Soft;

        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Trilight;
        // Raised after looking at it: the first pass was legible in a screenshot
        // and would have been a black screen to play. An interior map has no sky to
        // fill it in, so the ambient IS the lighting until the power comes on.
        RenderSettings.ambientSkyColor = new Color(0.34f, 0.38f, 0.46f);
        RenderSettings.ambientEquatorColor = new Color(0.24f, 0.25f, 0.29f);
        RenderSettings.ambientGroundColor = new Color(0.13f, 0.13f, 0.15f);
        RenderSettings.fog = true;
        RenderSettings.fogMode = FogMode.ExponentialSquared;
        RenderSettings.fogDensity = 0.007f;
        RenderSettings.fogColor = new Color(0.14f, 0.15f, 0.18f);
    }

    static Transform BuildPlayer()
    {
        var player = new GameObject("Player");
        player.transform.position = PlayerStart;

        var controller = player.AddComponent<CharacterController>();
        controller.height = 1.8f;
        controller.radius = 0.4f;
        controller.center = new Vector3(0f, 0.9f, 0f);

        var holder = new GameObject("CameraHolder").transform;
        holder.SetParent(player.transform, false);
        holder.localPosition = new Vector3(0f, 1.6f, 0f);

        var camGo = new GameObject("Main Camera");
        camGo.tag = "MainCamera";
        camGo.transform.SetParent(holder, false);
        var cam = camGo.AddComponent<Camera>();
        cam.farClipPlane = 200f;
        // MapSeeder never set this, and every weapon was sliced 0.170 m into its
        // length for four phases.
        cam.nearClipPlane = 0.02f;
        cam.cullingMask = ~0;
        camGo.AddComponent<AudioListener>();

        var camData = camGo.AddComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>();
        camData.renderPostProcessing = true;
        camData.antialiasing = UnityEngine.Rendering.Universal.AntialiasingMode.SubpixelMorphologicalAntiAliasing;

        var fps = player.AddComponent<FPSController>();
        fps.cameraHolder = holder;
        player.AddComponent<PlayerHealth>();
        player.AddComponent<PlayerPerks>();
        player.AddComponent<InteractionSystem>();
        player.AddComponent<WeaponInventory>();
        return player.transform;
    }

    static NavMeshSurface BuildNavigation()
    {
        var go = new GameObject("Navigation");
        var surface = go.AddComponent<NavMeshSurface>();
        surface.collectObjects = CollectObjects.All;
        surface.useGeometry = NavMeshCollectGeometry.PhysicsColliders;
        surface.overrideVoxelSize = true;
        surface.voxelSize = 0.12f;

        int debris = LayerMask.NameToLayer(BuyableDoor.DebrisLayerName);
        surface.layerMask = debris >= 0 ? ~(1 << debris) : ~0;
        surface.BuildNavMesh();
        return surface;
    }

    static void SaveNavMeshAsset(NavMeshSurface surface)
    {
        if (surface == null || surface.navMeshData == null)
        {
            Debug.LogWarning("[Metro] the navmesh bake produced no data.");
            return;
        }
        if (AssetDatabase.Contains(surface.navMeshData))
        {
            EditorUtility.SetDirty(surface.navMeshData);
            return;
        }
        // This scene is rebuilt from scratch, so a navmesh asset from the previous
        // run is usually sitting at this path already.
        if (File.Exists(k_NavMeshAssetPath)) AssetDatabase.DeleteAsset(k_NavMeshAssetPath);
        AssetDatabase.CreateAsset(surface.navMeshData, k_NavMeshAssetPath);
    }

    static Transform[] BuildValidatedSpawnPoints(Vector3 playerPosition)
    {
        const int wanted = 8;
        const float minSeparation = 12f;
        float requiredEdge = 1.2f * 0.5f * 1.25f;

        if (!NavMesh.SamplePosition(playerPosition, out var playerOnMesh, 3f, NavMesh.AllAreas))
        {
            Debug.LogError("[Metro] the player spawn is not on the navmesh; cannot validate spawns.");
            return new Transform[0];
        }

        var candidates = new List<(Vector3 position, float clearance)>();
        for (int x = 0; x <= 16; x++)
            for (int z = 0; z <= 16; z++)
            {
                var probe = MapPlan.Centre(x, z);
                if (!NavMesh.SamplePosition(probe, out var hit, 2f, NavMesh.AllAreas)) continue;
                if (hit.position.y > 1f) continue;          // ground floor only
                if (!NavMesh.FindClosestEdge(hit.position, out var edge, NavMesh.AllAreas)) continue;
                if (edge.distance < requiredEdge) continue;

                // A spawn that cannot walk to the player is a round that never ends.
                var path = new NavMeshPath();
                NavMesh.CalculatePath(hit.position, playerOnMesh.position, NavMesh.AllAreas, path);
                if (path.status != NavMeshPathStatus.PathComplete) continue;

                candidates.Add((hit.position, edge.distance));
            }

        candidates.Sort((a, b) => b.clearance.CompareTo(a.clearance));
        var chosen = new List<Vector3>();
        foreach (var (position, _) in candidates)
        {
            if (chosen.Count >= wanted) break;
            if (chosen.Any(c => Vector3.Distance(c, position) < minSeparation)) continue;
            chosen.Add(position);
        }

        if (chosen.Count < wanted)
            Debug.LogWarning($"[Metro] only {chosen.Count} of {wanted} spawn points cleared " +
                             $"validation ({candidates.Count} candidates). The layout may be too tight.");

        var root = new GameObject("EnemySpawnPoints").transform;
        var list = new List<Transform>();
        for (int i = 0; i < chosen.Count; i++)
        {
            var go = new GameObject($"Spawn_{i:00}");
            go.transform.SetParent(root, false);
            go.transform.position = chosen[i];
            list.Add(go.transform);
        }
        return list.ToArray();
    }

    static void AddToBuildSettings()
    {
        // A scene not in Build Settings cannot be loaded, and the symptom is a
        // button that does nothing — already paid for by the Store screen.
        var scenes = EditorBuildSettings.scenes.ToList();
        if (scenes.Any(s => s.path == ScenePath)) return;
        scenes.Add(new EditorBuildSettingsScene(ScenePath, true));
        EditorBuildSettings.scenes = scenes.ToArray();
        Debug.Log($"[Metro] added {ScenePath} to Build Settings.");
    }
}
