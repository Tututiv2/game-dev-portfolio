using System.Collections.Generic;
using System.IO;
using Unity.AI.Navigation;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.AI;

/// <summary>
/// Phase 3, layer 2: seeds the map scene ONCE from the modular kit, then gets out
/// of the way forever.
///
/// <para><b>This is deliberately not a map builder, and the difference is the whole
/// point.</b> §12's ★★ box retires the old generate-on-every-build approach for two
/// separate reasons: the geometry was code-placed primitives, and the scenes were
/// <i>build outputs</i>, so "the next build wipes any hand edit". §12 requires the
/// map to be lit and dressed "in the editor, by hand and by eye".</para>
///
/// <para>So this tool <b>refuses to overwrite an existing scene</b>. It hands over a
/// correct, snapping, PBR-surfaced blockout with the navigation laws already
/// satisfied, and after that the scene belongs to the level designer, not to code.
/// Re-running it is a no-op with a clear message rather than a silent wipe.</para>
///
/// <para>Scope is exactly §17's Phase 3 — "Map + navigation ... plus navmesh and
/// spawn validation". Buyable doors and power are Phase 4, wallbuys Phase 6, perks
/// and Pack-a-Punch Phase 7. Those are intentionally NOT placed here; the blockout
/// leaves the openings and the zone shape they will attach to.</para>
/// </summary>
public static class MapSeeder
{
    public const string ScenePath = "Assets/_Project/Scenes/AshfallDepot.unity";
    const string k_NavMeshAssetPath = "Assets/_Project/Scenes/AshfallDepot_NavMesh.asset";

    /// <summary>§14 Law 13: "Use 0.12 m navmesh voxels".</summary>
    public const float NavMeshVoxelSize = 0.12f;

    /// <summary>§14 Law 8: "Spawn points need >= 2 m of clearance".</summary>
    public const float RequiredSpawnClearance = 2f;

    const int k_Cells = 14;                 // 14 x 14 cells of 4 m = 56 m square. "One small contained map".
    const int k_DividerRow = 4;             // Zone A is z 0..4; zones B and C are z 5..13.
    const int k_DividerCol = 6;             // Zone B is x 0..6; zone C is x 7..13.

    static float W(int cell) => cell * MapKitBuilder.Grid;

    /// <summary>
    /// The DESIGNED player start: zone A, cell (5, 1), feet on the yard floor.
    ///
    /// <para>Public because <see cref="MapBake.FixPlayerStart"/> searches outward
    /// from HERE rather than from wherever the Player currently is. Searching from
    /// the current position is what let a repair drift: the first run started from
    /// the broken (24, 1.2, 4), and once it had answered 10.5 m away on the loading
    /// dock ramp, re-running found that spot valid and reported "already clear" —
    /// baking the bad answer in permanently. Anchored to the design intent, the
    /// repair converges to the same place every time it is run.</para>
    /// </summary>
    public static readonly Vector3 PlayerStart = new Vector3(20f, 0.05f, 4f);

    [MenuItem("Tools/Aftermarket/Seed Map Scene")]
    public static void Seed()
    {
        if (File.Exists(ScenePath))
        {
            Debug.LogWarning(
                $"[MapSeeder] {ScenePath} already exists, so nothing was written.\n" +
                "This tool seeds the map once and never overwrites it — §12 requires the scene to be " +
                "dressed by hand in the editor, and a re-runnable builder would wipe that work.\n" +
                "To start over you delete the scene file yourself. BACK IT UP FIRST — deleting is " +
                "not undoable, and if the scene has uncommitted hand dressing then git cannot get " +
                "it back either. Copy it somewhere outside Assets/, or commit first, and only then " +
                "delete and re-run.");
            return;
        }

        foreach (var piece in MapKitBuilder.Pieces)
        {
            if (AssetDatabase.LoadAssetAtPath<GameObject>(piece.PrefabPath) == null)
            {
                Debug.LogError($"[MapSeeder] kit piece missing: {piece.PrefabPath}. " +
                               "Run Tools > Aftermarket > Build Map Kit first.");
                return;
            }
        }

        Directory.CreateDirectory(Path.GetDirectoryName(ScenePath));
        s_Placed = 0;
        var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);

        var structure = new GameObject("Structure").transform;
        BuildGround(structure);
        BuildPerimeter(structure);
        BuildZoneDividers(structure);
        BuildWarehouse(structure);
        BuildSubstation(structure);
        BuildSpawnYard(structure);
        BuildCover(structure);

        BuildLighting();
        var player = BuildPlayerSpawn();

        // Order matters: the navmesh has to exist before spawn points can be
        // validated against it, because validation IS a navmesh query.
        var surface = BuildNavigation();
        var spawns = BuildValidatedEnemySpawnPoints(player.position);

        // Read these BEFORE saving. ForceReserializeAssets below reloads the scene,
        // which destroys every object reference gathered above — reading them after
        // throws MissingReferenceException.
        //
        // s_Placed, not a MeshRenderer count. Counting renderers overstates the
        // number of PIECES, because Kit_Wall_4_Window is authored as four separate
        // boxes (sill, header, two jambs) and so contributes four renderers per
        // placement. The old count reported 413 where 392 pieces were placed.
        int instanceCount = s_Placed;
        int rendererCount = structure.GetComponentsInChildren<MeshRenderer>().Length;
        int spawnCount = spawns.Length;
        Vector3 playerPosition = player.position;

        // BEFORE SaveScene, not after. BuildNavMesh() leaves NavMeshData as an
        // unsaved object; if the scene is written while it is still ownerless, the
        // data is embedded INTO the scene file, and inline NavMeshData forces the
        // whole scene to be serialized as binary regardless of ForceText. Creating
        // the asset first leaves the scene holding a plain reference.
        SaveNavMeshAsset(surface);

        EditorSceneManager.SaveScene(scene, ScenePath);
        AssetDatabase.SaveAssets();
        AssetDatabase.Refresh();

        // A scene that is meant to be hand-dressed and committed MUST be text.
        // A binary scene cannot be diffed or merged, so the level designer's git
        // history becomes a megabyte of opaque blobs per edit. Every other asset in
        // this project is YAML, and EditorSettings is already ForceText, but the
        // scene still saved as binary — so force it explicitly rather than trusting
        // the project setting.
        if (EditorSettings.serializationMode != SerializationMode.ForceText)
        {
            Debug.LogWarning($"[MapSeeder] serialization mode was {EditorSettings.serializationMode}; " +
                             "setting ForceText so the map scene stays diffable.");
            EditorSettings.serializationMode = SerializationMode.ForceText;
        }
        AssetDatabase.ForceReserializeAssets(new[] { ScenePath },
            ForceReserializeAssetsOptions.ReserializeAssetsAndMetadata);
        AssetDatabase.Refresh();

        Debug.Log($"[MapSeeder] seeded {ScenePath}: " +
                  $"{instanceCount} kit pieces placed ({rendererCount} mesh renderers — " +
                  "Kit_Wall_4_Window is four boxes), " +
                  $"{spawnCount} enemy spawn points, player at {playerPosition}. " +
                  "It is yours now — this tool will not touch it again.");
    }

    // ---- geometry ----------------------------------------------------------

    static void BuildGround(Transform parent)
    {
        var floors = new GameObject("Ground").transform;
        floors.SetParent(parent, false);

        for (int x = 0; x < k_Cells; x++)
            for (int z = 0; z < k_Cells; z++)
                Place("Kit_Floor_4x4", floors, new Vector3(W(x), 0f, W(z)), 0f);
    }

    static void BuildPerimeter(Transform parent)
    {
        // A contained map: solid boundary, so there is no "invisible wall" to explain
        // (§14 Law 14) and nothing to fall off.
        var walls = new GameObject("Perimeter").transform;
        walls.SetParent(parent, false);

        for (int x = 0; x < k_Cells; x++)
        {
            WallOnEdge(walls, x, 0, Edge.South);
            WallOnEdge(walls, x, k_Cells - 1, Edge.North);
        }
        for (int z = 0; z < k_Cells; z++)
        {
            WallOnEdge(walls, 0, z, Edge.West);
            WallOnEdge(walls, k_Cells - 1, z, Edge.East);
        }
    }

    static void BuildZoneDividers(Transform parent)
    {
        var walls = new GameObject("ZoneDividers").transform;
        walls.SetParent(parent, false);

        // Zone A (spawn yard) -> zones B and C. Two openings, at the cells where
        // Phase 4 will hang the 750 and 1000 buyable doors. §12.1's progression
        // pacing, at this map's much smaller scale.
        for (int x = 0; x < k_Cells; x++)
        {
            bool opening = x == 3 || x == 10;
            WallOnEdge(walls, x, k_DividerRow, Edge.North, opening ? "Kit_Wall_4_Doorway" : "Kit_Wall_4");
        }

        // Zone B <-> zone C, with one free doorway so the two halves interconnect
        // rather than being dead ends — the loop matters more than the square footage.
        for (int z = k_DividerRow + 1; z < k_Cells; z++)
        {
            bool opening = z == 9;
            WallOnEdge(walls, k_DividerCol, z, Edge.East, opening ? "Kit_Wall_4_Doorway" : "Kit_Wall_4");
        }
    }

    static void BuildWarehouse(Transform parent)
    {
        // Zone B interior building. Windows on the long side so it reads as a
        // building and gives sightlines without being a box you cannot see into.
        var b = new GameObject("Warehouse").transform;
        b.SetParent(parent, false);

        RoomShell(b, x0: 1, z0: 7, x1: 5, z1: 11,
            doorCells: new[] { new Vector2Int(3, 7) },
            windowCells: new[] { new Vector2Int(1, 11), new Vector2Int(5, 11) });

        // The staircase is INSIDE, climbing +Z, so its top edge meets roof cell
        // (5, 11) along a full 4 m shared edge. That detail is load-bearing: an
        // outside staircase landing beside the building would put the agent on a
        // 0.5 m wall top, and a 0.5 m ledge carries NO navmesh at all once the
        // 0.5 m agent radius is eroded from both sides. It would look connected
        // in the editor and be unreachable in play.
        Place("Kit_Stairs_4", b, new Vector3(W(5), 0f, W(10)), 0f);

        // Roof at storey height, minus the stairwell it climbs out of and one
        // collapsed panel that drops daylight into the middle of the floor.
        Roof(b, x0: 1, z0: 7, x1: 5, z1: 11,
             holes: new[] { new Vector2Int(5, 10), new Vector2Int(2, 9) });
    }

    static void BuildSubstation(Transform parent)
    {
        var b = new GameObject("Substation").transform;
        b.SetParent(parent, false);

        RoomShell(b, x0: 9, z0: 7, x1: 12, z1: 10,
            doorCells: new[] { new Vector2Int(9, 7) },
            windowCells: new[] { new Vector2Int(12, 10) });

        Place("Kit_Stairs_4", b, new Vector3(W(12), 0f, W(9)), 0f);

        Roof(b, x0: 9, z0: 7, x1: 12, z1: 10,
             holes: new[] { new Vector2Int(12, 9), new Vector2Int(10, 8) });
    }

    /// <summary>
    /// Zone A — the spawn yard. It was 56 x 20 m of nothing but three half walls,
    /// which is not a fight, it is a car park: sightlines ran the full width and
    /// there was no reason to move anywhere.
    ///
    /// <para>Three things go in, all made of existing kit pieces: an enclosed guard
    /// office that cuts the long axis in half, a raised loading dock reached by the
    /// ramp, and cover placed around both.</para>
    /// </summary>
    static void BuildSpawnYard(Transform parent)
    {
        var yard = new GameObject("SpawnYard").transform;
        yard.SetParent(parent, false);

        // Guard office. Three cells wide so the door cell (3, 2) sits on the south
        // run ALONE — in a 2 x 2 room every cell is a corner, so a door cell lands
        // on two runs and you get two doorways facing different ways.
        // THREE cells deep, not two, and that is forced by the staircase.
        //
        // A Kit_Stairs_4 fills one whole cell and is entered from its low end only —
        // buildSides makes its flanks solid. In a two-deep room every cell touches
        // both the front and back wall, so the stair's foot is always buried in a
        // wall and its landing is always over the eaves. The first attempt did
        // exactly that: the roof was reachable in theory and the stair itself baked
        // navmesh that nothing could stand on.
        //
        // Three deep puts the stair in the middle row, entered from open floor at
        // its foot and landing on a roof slab at its head.
        RoomShell(yard, x0: 2, z0: 1, x1: 4, z1: 3,
            doorCells: new[] { new Vector2Int(3, 1) },
            windowCells: new[] { new Vector2Int(3, 3) });

        // The office roof is walkable, so it gets a way up like every other raised
        // surface. Roofing it without one left a 12 x 8 m island of navmesh at storey
        // height that nothing could reach — invisible from the ground and invisible
        // to a spot-check. Map_EveryWalkableSurfaceAboveTheGroundIsReachable
        // enumerates the baked navmesh rather than probing chosen points, so it
        // caught both this and the stair-into-a-wall version that replaced it.
        Place("Kit_Stairs_4", yard, new Vector3(W(4), 0f, W(2)), 0f);
        Roof(yard, x0: 2, z0: 1, x1: 4, z1: 3, holes: new[] { new Vector2Int(4, 2) });

        // Loading dock: a 2 x 2 deck at ramp height, on half-wall supports. The
        // deck uses Kit_Floor_4x4 because that piece is seated by its TOP, so
        // placing it at y = RampRise puts the walkable surface exactly where the
        // ramp arrives instead of half a slab above it.
        float deckY = MapKitBuilder.RampRise;
        for (int x = 10; x <= 11; x++)
            for (int z = 1; z <= 2; z++)
                Place("Kit_Floor_4x4", yard, new Vector3(W(x), deckY, W(z)), 0f);

        // Supports, on every outer edge EXCEPT the west face of (10, 2), which is
        // where the ramp lands.
        WallOnEdge(yard, 10, 1, Edge.South, "Kit_Wall_4_Half");
        WallOnEdge(yard, 11, 1, Edge.South, "Kit_Wall_4_Half");
        WallOnEdge(yard, 10, 2, Edge.North, "Kit_Wall_4_Half");
        WallOnEdge(yard, 11, 2, Edge.North, "Kit_Wall_4_Half");
        WallOnEdge(yard, 11, 1, Edge.East, "Kit_Wall_4_Half");
        WallOnEdge(yard, 11, 2, Edge.East, "Kit_Wall_4_Half");
        WallOnEdge(yard, 10, 1, Edge.West, "Kit_Wall_4_Half");

        // The ramp, climbing +X into the deck's west edge. Yaw 90 turns the wedge's
        // local +Z (its high end) to world +X.
        Place("Kit_Ramp_4", yard, new Vector3(W(9), 0f, W(2)), 90f);
    }

    /// <summary>
    /// Four walls around a rectangle of cells, with named cells swapped for doorway
    /// or window pieces. The rectangle is inclusive on both corners.
    /// </summary>
    static void RoomShell(Transform parent, int x0, int z0, int x1, int z1,
                          Vector2Int[] doorCells, Vector2Int[] windowCells)
    {
        string PieceFor(int x, int z)
        {
            foreach (var c in doorCells) if (c.x == x && c.y == z) return "Kit_Wall_4_Doorway";
            foreach (var c in windowCells) if (c.x == x && c.y == z) return "Kit_Wall_4_Window";
            return "Kit_Wall_4";
        }

        for (int x = x0; x <= x1; x++)
        {
            WallOnEdge(parent, x, z0, Edge.South, PieceFor(x, z0));
            WallOnEdge(parent, x, z1, Edge.North, PieceFor(x, z1));
        }
        for (int z = z0; z <= z1; z++)
        {
            WallOnEdge(parent, x0, z, Edge.West, PieceFor(x0, z));
            WallOnEdge(parent, x1, z, Edge.East, PieceFor(x1, z));
        }
    }

    static void BuildCover(Transform parent)
    {
        var cover = new GameObject("Cover").transform;
        cover.SetParent(parent, false);

        // Half walls give the spawn points something to come out from behind
        // (§14 Law 8: "Put cover ~3 m in front of the spawn"). The two that used to
        // sit at (2, 2) and (11, 2) are gone — the guard office and the loading dock
        // now occupy those cells.
        var halfWalls = new[]
        {
            new Vector3(W(6), 0f, W(1)),  new Vector3(W(12), 0f, W(3)),
            new Vector3(W(6), 0f, W(3)),  new Vector3(W(0), 0f, W(2)),
            new Vector3(W(2), 0f, W(12)), new Vector3(W(11), 0f, W(12)),
            new Vector3(W(8), 0f, W(6)),  new Vector3(W(3), 0f, W(6)),
            new Vector3(W(9), 0f, W(12)),
        };
        foreach (var p in halfWalls)
            Place("Kit_Wall_4_Half", cover, p, 0f);

        var pillars = new[]
        {
            new Vector3(W(8), 0f, W(3)),  new Vector3(W(4), 0f, W(12)),
            new Vector3(W(10), 0f, W(6)), new Vector3(W(7), 0f, W(1)),
            new Vector3(W(13), 0f, W(12)),
        };
        foreach (var p in pillars)
            Place("Kit_Pillar", cover, p, 0f);
    }

    /// <summary>Storey height for a walkable roof. The ceiling slab is seated by its
    /// BOTTOM, so it goes at WallHeight - Thickness to put its top surface exactly on
    /// WallHeight — flush with the tops of the walls carrying it.</summary>
    static float RoofY => MapKitBuilder.WallHeight - MapKitBuilder.Thickness;

    /// <summary>
    /// Roofs a rectangle of cells, skipping named ones. The holes are load-bearing,
    /// not decoration: one is the stairwell the roof is reached through, and the
    /// others are collapsed panels that let the sun into a room which is otherwise
    /// a sealed box.
    /// </summary>
    static void Roof(Transform parent, int x0, int z0, int x1, int z1, Vector2Int[] holes = null)
    {
        for (int x = x0; x <= x1; x++)
        {
            for (int z = z0; z <= z1; z++)
            {
                bool open = false;
                if (holes != null)
                    foreach (var h in holes)
                        if (h.x == x && h.y == z) { open = true; break; }

                if (!open) Place("Kit_Ceiling_4x4", parent, new Vector3(W(x), RoofY, W(z)), 0f);
            }
        }
    }

    // ---- scene furniture ---------------------------------------------------

    static void BuildLighting()
    {
        var sunGo = new GameObject("Sun");
        var sun = sunGo.AddComponent<Light>();
        sun.type = LightType.Directional;
        sun.color = new Color(1f, 0.58f, 0.32f);   // §12.1's dusk key light
        sun.intensity = 1.35f;
        sun.shadows = LightShadows.Soft;
        sunGo.transform.rotation = Quaternion.Euler(38f, -40f, 0f);

        RenderSettings.fog = true;
        RenderSettings.fogMode = FogMode.ExponentialSquared;
        RenderSettings.fogColor = new Color(0.5f, 0.4f, 0.31f);
        RenderSettings.fogDensity = 0.013f;
        RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Trilight;
        RenderSettings.ambientSkyColor = new Color(0.42f, 0.36f, 0.32f);
        RenderSettings.ambientEquatorColor = new Color(0.3f, 0.26f, 0.24f);
        RenderSettings.ambientGroundColor = new Color(0.16f, 0.14f, 0.13f);
    }

    static Transform BuildPlayerSpawn()
    {
        // Minimal but real: the scene is walkable the moment it opens, which is the
        // only way to judge a blockout. The weapon rig and HUD are later phases.
        var player = new GameObject("Player");

        // Cell (5, 1), NOT (6, 1), and y = 0.05, NOT 1.2. Both halves were wrong
        // and neither was catchable by reading this line:
        //
        // 1. Cell (6, 1) is where BuildCover places a Kit_Wall_4_Half — spanning
        //    (22, 0, 3.75) to (26, 2, 4.25). The player start sat at its exact
        //    centre, so the game began with the capsule INSIDE a wall. Two
        //    placements, both written in cell coordinates, and nothing compared
        //    them.
        // 2. CharacterController.center is (0, 0.9, 0) and height is 1.8, so
        //    transform.position.y is the FEET, not the capsule centre. At 1.2 the
        //    player started 1.2 m above a floor whose walkable top is y = 0.
        //
        // Tools > Aftermarket > Fix Player Start re-derives this against the live
        // scene and is what found it. Run it after any re-seed.
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
        // §14 Law 9: a non-Everything culling mask silently hides whole layers.
        cam.cullingMask = ~0;
        camGo.AddComponent<AudioListener>();

        // Without this the camera renders with NO post-processing, because
        // renderPostProcessing defaults to false on a URP camera that has no
        // UniversalAdditionalCameraData. The previous seed had none, so the whole
        // HDRI-and-tonemapping setup did nothing in play: every value over 1.0
        // clipped and the sky was a flat white sheet. It looked like a broken
        // skybox and was a missing component.
        var camData = camGo.AddComponent<UnityEngine.Rendering.Universal.UniversalAdditionalCameraData>();
        camData.renderPostProcessing = true;
        camData.antialiasing = UnityEngine.Rendering.Universal.AntialiasingMode.SubpixelMorphologicalAntiAliasing;

        var fps = player.AddComponent<FPSController>();
        fps.cameraHolder = holder;
        player.AddComponent<PlayerHealth>();

        return player.transform;
    }

    /// <summary>
    /// §17's "spawn validation", done properly: spawn points are DERIVED from the
    /// baked navmesh rather than typed in by hand and hoped for.
    ///
    /// <para>Hand-placed coordinates were tried first and were quietly wrong — one
    /// point landed 1.47 m from a staircase edge and had 0.97 m of walkable surface
    /// around it, just under §14 Law 8's requirement, which no amount of reading the
    /// numbers would have revealed. Every candidate here has to survive three
    /// checks against the real navmesh, so a spawn that violates Law 8 cannot be
    /// written into the scene at all.</para>
    /// </summary>
    static Transform[] BuildValidatedEnemySpawnPoints(Vector3 playerPosition)
    {
        const int wanted = 8;
        const float minSeparation = 12f;

        // A little over the bare Law 8 minimum. Sitting exactly on the limit means
        // any later hand edit tips it under.
        float requiredEdgeDistance = RequiredSpawnClearance * 0.5f * 1.25f;

        if (!NavMesh.SamplePosition(playerPosition, out var playerOnMesh, 3f, NavMesh.AllAreas))
        {
            Debug.LogError("[MapSeeder] the player spawn is not on the navmesh; cannot validate spawns.");
            return new Transform[0];
        }

        // Candidates: every cell centre, scored by how open it is.
        var candidates = new List<(Vector3 position, float clearance)>();
        for (int x = 0; x < k_Cells; x++)
        {
            for (int z = 0; z < k_Cells; z++)
            {
                var probe = new Vector3(W(x), 0f, W(z));

                // 1. Is there navmesh here at all?
                if (!NavMesh.SamplePosition(probe, out var hit, 2f, NavMesh.AllAreas)) continue;

                // 1b. On the GROUND, not on a roof or the loading dock. The map now
                //     has walkable surface at 2 m and 4 m, and a spawn point up there
                //     would drop a round's worth of zombies onto a rooftop the player
                //     may never visit. Every other check would pass — the roofs are
                //     wide open and fully connected — so this cannot be caught later.
                if (hit.position.y > 1f) continue;

                // 2. §14 Law 8 — enough walkable surface around it.
                if (!NavMesh.FindClosestEdge(hit.position, out var edge, NavMesh.AllAreas)) continue;
                if (edge.distance < requiredEdgeDistance) continue;

                // 3. Can a zombie actually walk from here to the player? A spawn
                //    that cannot reach the player is a round that never ends.
                var path = new NavMeshPath();
                NavMesh.CalculatePath(hit.position, playerOnMesh.position, NavMesh.AllAreas, path);
                if (path.status != NavMeshPathStatus.PathComplete) continue;

                candidates.Add((hit.position, edge.distance));
            }
        }

        // Spread them out: repeatedly take the valid candidate furthest from every
        // point already chosen, so spawns ring the map instead of clumping.
        var chosen = new List<Vector3>();
        candidates.Sort((a, b) => b.clearance.CompareTo(a.clearance));

        foreach (var (position, _) in candidates)
        {
            if (chosen.Count >= wanted) break;

            bool tooClose = false;
            foreach (var already in chosen)
                if (Vector3.Distance(already, position) < minSeparation) { tooClose = true; break; }

            if (!tooClose) chosen.Add(position);
        }

        if (chosen.Count < wanted)
            Debug.LogWarning($"[MapSeeder] only {chosen.Count} of {wanted} spawn points met Law 8 " +
                             $"({candidates.Count} candidates passed validation). The blockout may be too tight.");

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

    static NavMeshSurface BuildNavigation()
    {
        var go = new GameObject("Navigation");
        var surface = go.AddComponent<NavMeshSurface>();

        surface.collectObjects = CollectObjects.All;
        surface.useGeometry = NavMeshCollectGeometry.PhysicsColliders;

        // §14 Law 13: 0.12 m voxels, for clean paths through doorways.
        surface.overrideVoxelSize = true;
        surface.voxelSize = NavMeshVoxelSize;

        // §14 Law 12 / §1.2: door debris must be excluded from the bake, or it
        // bakes as solid floor. Blocking is a carving NavMeshObstacle instead, so
        // opening a door re-carves rather than needing a rebake. Resolved by name —
        // §1.2 says never hard-code a layer index.
        int debris = LayerMask.NameToLayer(BuyableDoor.DebrisLayerName);
        surface.layerMask = debris >= 0 ? ~(1 << debris) : ~0;

        surface.BuildNavMesh();
        return surface;
    }

    static void SaveNavMeshAsset(NavMeshSurface surface)
    {
        if (surface == null || surface.navMeshData == null)
        {
            Debug.LogWarning("[MapSeeder] the navmesh bake produced no data.");
            return;
        }

        // BuildNavMesh() leaves the data in memory. Without this it is not an asset
        // and does not survive a domain reload, so the scene reopens with no navmesh.
        if (!AssetDatabase.Contains(surface.navMeshData))
            AssetDatabase.CreateAsset(surface.navMeshData, k_NavMeshAssetPath);
    }

    // ---- placement ---------------------------------------------------------

    enum Edge { North, South, East, West }

    /// <summary>
    /// Places a wall on one edge of cell (cx, cz). Walls live on cell BOUNDARIES,
    /// not cell centres — that is what makes a modular kit tile.
    /// </summary>
    static void WallOnEdge(Transform parent, int cx, int cz, Edge edge, string piece = "Kit_Wall_4")
    {
        float half = MapKitBuilder.Grid * 0.5f;
        Vector3 centre = new Vector3(W(cx), 0f, W(cz));

        Vector3 position;
        float yaw;
        switch (edge)
        {
            case Edge.North: position = centre + new Vector3(0f, 0f, half); yaw = 0f; break;
            case Edge.South: position = centre - new Vector3(0f, 0f, half); yaw = 0f; break;
            case Edge.East: position = centre + new Vector3(half, 0f, 0f); yaw = 90f; break;
            default: position = centre - new Vector3(half, 0f, 0f); yaw = 90f; break;
        }

        Place(piece, parent, position, yaw);
    }

    /// <summary>Pieces placed this seed. Counted here rather than inferred from
    /// renderers, which multi-part pieces inflate.</summary>
    static int s_Placed;

    static GameObject Place(string pieceName, Transform parent, Vector3 position, float yaw)
    {
        s_Placed++;
        var piece = System.Array.Find(MapKitBuilder.Pieces, p => p.Name == pieceName);
        var prefab = AssetDatabase.LoadAssetAtPath<GameObject>(piece.PrefabPath);

        // InstantiatePrefab, not Instantiate: the placed pieces stay linked to the
        // kit prefabs, so rebuilding the kit updates the map and the level designer
        // can still override any individual instance by hand.
        var go = (GameObject)PrefabUtility.InstantiatePrefab(prefab, parent);
        go.transform.position = position;
        go.transform.rotation = Quaternion.Euler(0f, yaw, 0f);

        StripProBuilderFromInstance(prefab, go);
        return go;
    }

    /// <summary>
    /// Removes the <c>ProBuilderMesh</c> component from a placed instance and points
    /// its MeshFilter and MeshCollider back at the kit's shared .mesh asset.
    ///
    /// <para><b>Why:</b> a live ProBuilderMesh rebuilds its own private copy of the
    /// geometry for every instance. Left alone, this map embedded <b>341 separate
    /// meshes</b> into the scene file — and a scene carrying that much inline mesh
    /// data is written as <b>binary</b>, not YAML, even with EditorSettings on
    /// ForceText. Both consequences are bad for a scene meant to be hand-dressed
    /// and committed: it bloats, and it cannot be diffed or merged. That is a
    /// smaller version of exactly what §12 objects to in the old maps.</para>
    ///
    /// <para><b>The trade-off, stated plainly:</b> instances can no longer be
    /// vertex-edited in place with the ProBuilder tools. The kit <i>prefabs</i> keep
    /// their ProBuilderMesh and stay fully ProBuilder-editable, and editing a prefab
    /// updates every instance — which is how a modular kit is supposed to work.
    /// Moving, rotating, deleting and re-materialing instances by hand all still
    /// work normally, and that is what dressing a blockout actually consists of.</para>
    /// </summary>
    static void StripProBuilderFromInstance(GameObject prefab, GameObject instance)
    {
        foreach (var pb in instance.GetComponentsInChildren<UnityEngine.ProBuilder.ProBuilderMesh>(true))
            Object.DestroyImmediate(pb, true);

        // Re-point at the prefab's shared mesh assets. ProBuilder may have swapped
        // in its per-instance copy before it was destroyed.
        var sourceFilters = prefab.GetComponentsInChildren<MeshFilter>(true);
        var instanceFilters = instance.GetComponentsInChildren<MeshFilter>(true);

        if (sourceFilters.Length != instanceFilters.Length) return;

        for (int i = 0; i < instanceFilters.Length; i++)
        {
            instanceFilters[i].sharedMesh = sourceFilters[i].sharedMesh;

            var collider = instanceFilters[i].GetComponent<MeshCollider>();
            if (collider != null) collider.sharedMesh = sourceFilters[i].sharedMesh;
        }
    }
}
