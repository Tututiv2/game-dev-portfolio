using System.Collections.Generic;
using System.IO;
using NUnit.Framework;
using Unity.AI.Navigation;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.AI;
using UnityEngine.Rendering;
using UnityEngine.SceneManagement;
using UnityEngine.TestTools;

/// <summary>
/// Phase 3, layer 2: the seeded map scene.
///
/// These open the real scene off disk and check the ⚙ navigation laws from §14
/// against it. That matters more than it sounds: every one of these laws is a bug
/// that already shipped once, and every one of them is invisible until you are
/// standing in the map watching a zombie walk through a wall.
/// </summary>
public class MapSceneTests
{
    static Scene s_Scene;

    [OneTimeSetUp]
    public void OpenTheMap()
    {
        Assert.IsTrue(File.Exists(MapSeeder.ScenePath),
            $"No map scene at {MapSeeder.ScenePath}. Run Tools > Aftermarket > Seed Map Scene.");

        // Third place this trap was set, and the most dangerous of the three,
        // because pressing Run in the Test Runner is the routine thing to do and
        // Unity does NOT save the open scene first.
        //
        // OpenScene called from a script does not prompt: it discards unsaved
        // changes and clears the undo stack. So running the suite with the map open
        // and half an hour of hand dressing in it would have destroyed the dressing,
        // silently, with no undo and nothing in Backups/ to recover from.
        //
        // If the map is already open, test it as it stands — testing the dressing
        // you are actually doing is more useful than testing the last saved copy.
        var open = SceneManager.GetActiveScene();
        if (open.path == MapSeeder.ScenePath)
        {
            s_Scene = open;
            return;
        }

        if (!Application.isBatchMode &&
            !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            Assert.Ignore("Skipped: the open scene has unsaved changes and loading the map " +
                          "would discard them. Save or discard, then run the tests again.");

        s_Scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
    }

    static T FindOne<T>() where T : Component
    {
        var found = new List<T>();
        foreach (var root in s_Scene.GetRootGameObjects())
            found.AddRange(root.GetComponentsInChildren<T>(true));

        Assert.AreEqual(1, found.Count,
            $"Expected exactly one {typeof(T).Name} in the map, found {found.Count}.");
        return found[0];
    }

    [Test]
    public void Map_BakesAtTwelveCentimetreVoxels_Law13()
    {
        var surface = FindOne<NavMeshSurface>();

        Assert.IsTrue(surface.overrideVoxelSize,
            "The NavMeshSurface is on default voxel size. §14 Law 13 requires 0.12 m, " +
            "which is what gives clean paths through doorways.");
        Assert.AreEqual(MapSeeder.NavMeshVoxelSize, surface.voxelSize, 1e-4f, "§14 Law 13");

        Assert.IsNotNull(surface.navMeshData,
            "The surface has no baked NavMeshData asset — the scene reopens with no navmesh.");
    }

    [Test]
    public void Map_ExcludesDoorDebrisFromTheBake_Law12()
    {
        int debris = LayerMask.NameToLayer(BuyableDoor.DebrisLayerName);
        Assert.GreaterOrEqual(debris, 0,
            $"No '{BuyableDoor.DebrisLayerName}' layer in TagManager.asset — §1.2 says it is load-bearing.");

        var surface = FindOne<NavMeshSurface>();
        bool included = (surface.layerMask.value & (1 << debris)) != 0;

        Assert.IsFalse(included,
            "DoorDebris is included in the navmesh bake. §14 Law 12: debris must be excluded and " +
            "block via a carving NavMeshObstacle instead, or closed doors bake as solid floor and " +
            "opening one needs a full rebake.");
    }

    [Test]
    public void Map_EveryCameraSeesEveryLayer_Law9()
    {
        var cameras = new List<Camera>();
        foreach (var root in s_Scene.GetRootGameObjects())
            cameras.AddRange(root.GetComponentsInChildren<Camera>(true));

        Assert.IsNotEmpty(cameras, "The map has no camera.");

        foreach (var cam in cameras)
            Assert.AreEqual(~0, cam.cullingMask,
                $"Camera '{cam.name}' has a restricted culling mask. §14 Law 9: any layer missing " +
                "from the mask renders invisible, which is how the doors ended up invisible before.");
    }

    [Test]
    public void Map_EverySpawnPointIsReachableByTheNavmesh_Law8()
    {
        // Law 8's actual consequence is "the navmesh can't reach them", so this
        // tests reachability rather than eyeballing distances: every spawn must sit
        // on the navmesh AND have a complete path to where the player starts. A
        // spawn that cannot path to the player is a zombie that never arrives.
        var surface = FindOne<NavMeshSurface>();
        Assert.IsNotNull(surface.navMeshData, "No baked navmesh, so reachability cannot be tested.");

        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map to path towards.");

        Assert.IsTrue(NavMesh.SamplePosition(player.transform.position, out var playerHit, 3f, NavMesh.AllAreas),
            $"The player spawn at {player.transform.position} is not on the navmesh.");

        var spawnRoot = GameObject.Find("EnemySpawnPoints");
        Assert.IsNotNull(spawnRoot, "No EnemySpawnPoints in the map.");
        Assert.Greater(spawnRoot.transform.childCount, 0, "EnemySpawnPoints is empty.");

        foreach (Transform spawn in spawnRoot.transform)
        {
            Assert.IsTrue(NavMesh.SamplePosition(spawn.position, out var spawnHit, 2f, NavMesh.AllAreas),
                $"{spawn.name} at {spawn.position} is not on the navmesh — nothing can spawn there.");

            var path = new NavMeshPath();
            NavMesh.CalculatePath(spawnHit.position, playerHit.position, NavMesh.AllAreas, path);

            Assert.AreEqual(NavMeshPathStatus.PathComplete, path.status,
                $"{spawn.name} has no complete path to the player ({path.status}). §14 Law 8 — " +
                "a walled-off spawn point is a round that never ends.");
        }
    }

    [Test]
    public void Map_EverySpawnPointHasTwoMetresOfClearance_Law8()
    {
        // §14 Law 8: ">= 2 m of clearance between cover and boundary", whose stated
        // consequence is "the navmesh can't reach them".
        //
        // So measure the navmesh, not the colliders. Physics probing is the wrong
        // instrument here and two attempts proved it: any sphere or capsule big
        // enough to test 2 m of lateral clearance also reaches down through the
        // floor the zombie is standing on, and reports the ground as an obstruction.
        //
        // NavMesh.FindClosestEdge answers the real question directly — how much
        // walkable surface surrounds this point — and ignores floors by
        // construction, because a floor is not an edge.
        var spawnRoot = GameObject.Find("EnemySpawnPoints");
        Assert.IsNotNull(spawnRoot, "No EnemySpawnPoints in the map.");

        float required = MapSeeder.RequiredSpawnClearance * 0.5f;

        foreach (Transform spawn in spawnRoot.transform)
        {
            Assert.IsTrue(NavMesh.SamplePosition(spawn.position, out var onMesh, 2f, NavMesh.AllAreas),
                $"{spawn.name} at {spawn.position} is not on the navmesh.");

            Assert.IsTrue(NavMesh.FindClosestEdge(onMesh.position, out var edge, NavMesh.AllAreas),
                $"{spawn.name}: could not find a navmesh edge to measure against.");

            Assert.GreaterOrEqual(edge.distance, required,
                $"{spawn.name} at {spawn.position} has only {edge.distance:0.##} m of walkable " +
                $"surface around it, under the {required} m needed for " +
                $"{MapSeeder.RequiredSpawnClearance} m of clearance. §14 Law 8.");
        }
    }

    [Test]
    public void Map_TheAuthoredRaisedSurfacesExist()
    {
        // Named surfaces, so deleting one fails loudly rather than silently shrinking
        // the map. This checks EXISTENCE at the right height only — it deliberately
        // does not claim anything about the map as a whole, which is what
        // Map_EveryWalkableSurfaceAboveTheGroundIsReachable is for.
        var raised = new (string what, Vector3 at)[]
        {
            ("warehouse roof",   new Vector3(12f, MapKitBuilder.WallHeight, 36f)),
            ("substation roof",  new Vector3(44f, MapKitBuilder.WallHeight, 32f)),
            ("guard office roof", new Vector3(8f, MapKitBuilder.WallHeight, 8f)),
            ("loading dock",     new Vector3(44f, MapKitBuilder.RampRise,    4f)),
        };

        foreach (var (what, at) in raised)
        {
            Assert.IsTrue(NavMesh.SamplePosition(at, out var hit, 1.5f, NavMesh.AllAreas),
                $"The {what} at {at} carries no navmesh — there is nothing walkable up there.");

            Assert.AreEqual(at.y, hit.position.y, 0.6f,
                $"The nearest navmesh to the {what} is at y {hit.position.y:0.##}, not {at.y}. " +
                "That is the ground below it, so the raised surface itself is not walkable.");
        }
    }

    [Test]
    public void Map_EveryWalkableSurfaceAboveTheGroundIsReachable()
    {
        // The first seed placed a ramp and a staircase that both ended four metres up
        // in open air — nothing was ever at storey height for them to arrive on. Every
        // test in this file passed: real kit instances, navmesh baked, spawns clear.
        // Only a screenshot taken standing on top of the ramp revealed it.
        //
        // The first attempt at writing that down probed three hand-picked points, and
        // that was not good enough: the very next thing added to the map was a roof
        // over the guard office with no stairs under it, a 12 x 8 m island of navmesh
        // at storey height that nothing could reach — and a three-point test could not
        // see it, because it was not one of the three points.
        //
        // So enumerate the navmesh instead of sampling it. Every triangle the bake
        // produced above ground level has to have a route from where the player
        // starts. Adding raised geometry cannot outrun this test.
        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map.");
        Assert.IsTrue(NavMesh.SamplePosition(player.transform.position, out var from, 3f, NavMesh.AllAreas),
            "The player spawn is not on the navmesh.");

        var triangulation = NavMesh.CalculateTriangulation();

        // One probe per 2 m cell per storey. The bake produces thousands of slivers;
        // clustering keeps this to a few dozen path queries while still covering
        // every distinct raised region.
        var probes = new Dictionary<Vector3Int, Vector3>();
        for (int i = 0; i < triangulation.indices.Length; i += 3)
        {
            Vector3 centre = (triangulation.vertices[triangulation.indices[i]] +
                              triangulation.vertices[triangulation.indices[i + 1]] +
                              triangulation.vertices[triangulation.indices[i + 2]]) / 3f;

            if (centre.y < 1f) continue;   // ground floor

            var cell = new Vector3Int(Mathf.RoundToInt(centre.x / 2f),
                                      Mathf.RoundToInt(centre.y),
                                      Mathf.RoundToInt(centre.z / 2f));
            if (!probes.ContainsKey(cell)) probes[cell] = centre;
        }

        Assert.Greater(probes.Count, 0,
            "There is no navmesh above ground level at all. The roofs are not walkable, " +
            "so the map has no verticality regardless of what geometry is placed.");

        var stranded = new List<string>();
        foreach (var probe in probes.Values)
        {
            if (!NavMesh.SamplePosition(probe, out var onMesh, 1f, NavMesh.AllAreas))
            {
                stranded.Add($"{probe} (could not re-sample)");
                continue;
            }

            var path = new NavMeshPath();
            NavMesh.CalculatePath(from.position, onMesh.position, NavMesh.AllAreas, path);
            if (path.status != NavMeshPathStatus.PathComplete)
                stranded.Add($"({probe.x:0.#}, {probe.y:0.#}, {probe.z:0.#})");
        }

        Assert.IsEmpty(stranded,
            $"{stranded.Count} of {probes.Count} raised navmesh regions have no route from the " +
            $"player spawn: {string.Join("; ", stranded.GetRange(0, Mathf.Min(8, stranded.Count)))}. " +
            "Walkable surface with no way onto it reads as a route and is not one.");
    }

    [Test]
    public void Map_NoEnemySpawnPointIsOnARaisedSurface()
    {
        // The map now has walkable navmesh at 2 m and at 4 m, and those surfaces are
        // wide, clear and fully connected — so they score BETTER on every existing
        // spawn check than the cluttered ground does. Without this, the spawn
        // derivation would happily put a round's worth of zombies on a rooftop.
        var spawnRoot = GameObject.Find("EnemySpawnPoints");
        Assert.IsNotNull(spawnRoot, "No EnemySpawnPoints in the map.");

        foreach (Transform spawn in spawnRoot.transform)
            Assert.Less(spawn.position.y, 1f,
                $"{spawn.name} is at y {spawn.position.y:0.##} — it is on a roof or the loading " +
                "dock, not on the ground floor where the player fights.");
    }

    [Test]
    public void Map_GeometryIsKitPrefabInstances_NotLooseMeshes()
    {
        // §12 retires code-placed primitives. The blockout is only legitimate if the
        // pieces are instances of the authored kit, still linked to their prefabs so
        // the level designer can override any one of them by hand.
        var structure = GameObject.Find("Structure");
        Assert.IsNotNull(structure, "No Structure root in the map.");

        var renderers = structure.GetComponentsInChildren<MeshRenderer>(true);
        Assert.Greater(renderers.Length, 100, "The blockout looks empty.");

        var kitPaths = new HashSet<string>();
        foreach (var piece in MapKitBuilder.Pieces) kitPaths.Add(piece.PrefabPath);

        int checkedInstances = 0;
        foreach (var renderer in renderers)
        {
            var root = PrefabUtility.GetOutermostPrefabInstanceRoot(renderer.gameObject);
            Assert.IsNotNull(root,
                $"'{renderer.name}' is not part of a prefab instance — it is loose geometry.");

            string source = PrefabUtility.GetPrefabAssetPathOfNearestInstanceRoot(root);
            Assert.Contains(source, new List<string>(kitPaths),
                $"'{renderer.name}' comes from '{source}', which is not a Build Map Kit piece.");

            Assert.IsNotNull(renderer.sharedMaterial, $"'{renderer.name}' has no material.");
            checkedInstances++;
        }

        Assert.Greater(checkedInstances, 100);
    }

    [Test]
    public void Map_IsLitByTheHdriSkybox()
    {
        // §17: a free CC0 HDRI replaces the procedural skybox and gives image-based
        // lighting "with real colour and direction".
        Assert.IsNotNull(RenderSettings.skybox,
            "The map has no skybox material. Run Tools > Aftermarket > Apply Sky.");

        StringAssert.Contains("Panoramic", RenderSettings.skybox.shader.name,
            $"Expected the equirectangular skybox shader, got '{RenderSettings.skybox.shader.name}'.");

        var hdri = RenderSettings.skybox.GetTexture("_MainTex");
        Assert.IsNotNull(hdri, "The skybox material has no HDRI bound.");

        // An .hdr imported as sRGB crushes the highlights that make image-based
        // lighting work, and the sun in the image stops reading as a light source.
        string path = AssetDatabase.GetAssetPath(hdri);
        StringAssert.EndsWith(".hdr", path, "The sky texture is not an HDR image.");

        var importer = AssetImporter.GetAtPath(path) as TextureImporter;
        Assert.IsNotNull(importer, $"No TextureImporter for {path}.");
        Assert.IsFalse(importer.sRGBTexture,
            $"{path} is imported as sRGB. HDRIs are linear light data, not colour-managed art.");

        Assert.AreEqual(AmbientMode.Skybox, RenderSettings.ambientMode,
            "Ambient light is not coming from the skybox, so the HDRI is only a backdrop " +
            "and the map is not actually image-based lit.");
    }

    [Test]
    public void Map_SceneIsTextYamlSoHandEditsCanBeDiffed()
    {
        // This scene exists to be hand-dressed and committed, so it has to be
        // readable text. It silently was not: an unsaved NavMeshData owned by the
        // scene at save time gets embedded, and inline NavMeshData forces the whole
        // scene to binary even though EditorSettings is ForceText. A binary scene
        // makes every dressing pass an opaque 1 MB blob in git history.
        Assert.AreEqual(SerializationMode.ForceText, EditorSettings.serializationMode,
            "The project is no longer forcing text serialization.");

        using (var reader = new StreamReader(MapSeeder.ScenePath))
        {
            var buffer = new char[5];
            reader.Read(buffer, 0, 5);
            Assert.AreEqual("%YAML", new string(buffer),
                $"{MapSeeder.ScenePath} is not text YAML. Check that MapSeeder writes the " +
                "NavMeshData asset BEFORE saving the scene — if it does not, the data is " +
                "embedded and the scene is forced to binary.");
        }

        // The same bug also embedded one private mesh copy per placed instance.
        string body = File.ReadAllText(MapSeeder.ScenePath);
        StringAssert.DoesNotContain("pb_Mesh", body,
            "The scene contains inline ProBuilder meshes. Placed instances must reference " +
            "the kit's shared .mesh assets, not carry their own copies.");
    }

    [Test]
    public void Map_SeederRefusesToOverwriteHandDressing()
    {
        // The contract that makes this a seed rather than a builder, and the whole
        // reason §12 retired the old approach: "the next build wipes any hand edit".
        var before = File.ReadAllBytes(MapSeeder.ScenePath);

        LogAssert.Expect(LogType.Warning, new System.Text.RegularExpressions.Regex("already exists"));
        MapSeeder.Seed();

        var after = File.ReadAllBytes(MapSeeder.ScenePath);
        Assert.AreEqual(before.Length, after.Length,
            "MapSeeder.Seed() modified an existing scene. It must never overwrite hand dressing.");
        Assert.AreEqual(before, after, "MapSeeder.Seed() rewrote the scene file.");
    }

    [Test]
    public void Map_HasTheTwoBuyableDoorsAtTheDividerOpenings()
    {
        // §12.1's progression at this map's scale. MapSeeder put Kit_Wall_4_Doorway
        // pieces at exactly two cells on the zone divider and left them empty for
        // this phase; the third opening (zone B to zone C) is deliberately free so
        // the two halves interconnect rather than being dead ends.
        var doors = new List<BuyableDoor>();
        foreach (var root in s_Scene.GetRootGameObjects())
            doors.AddRange(root.GetComponentsInChildren<BuyableDoor>(true));

        Assert.AreEqual(2, doors.Count,
            "Expected exactly two buyable doors on the zone divider, found " + doors.Count + ".");

        var costs = new List<int>();
        foreach (var d in doors) costs.Add(d.cost);
        costs.Sort();
        CollectionAssert.AreEqual(new[] { 750, 1000 }, costs,
            "Door costs should be 750 and 1000 per §12.1, got " + string.Join(", ", costs) + ".");

        // Both sit on the divider line, which MapSeeder places at the north edge of
        // cell row 4 -- z = 4*4 + 2.
        foreach (var d in doors)
            Assert.AreEqual(18f, d.transform.position.z, 0.6f,
                d.name + " is not on the zone divider at z = 18.");
    }

    [Test]
    public void Map_DoorDebrisIsExcludedFromTheNavmeshBake_Law12()
    {
        // The single most consequential wiring detail in this phase. If a door is
        // left on the Default layer it gets BAKED as solid floor, and the doorway
        // is impassable before it is ever bought -- with no error anywhere. §14
        // Law 12: debris is excluded from the bake and blocks with a carving
        // NavMeshObstacle instead, which is also why nothing rebakes when one opens.
        int debris = LayerMask.NameToLayer(BuyableDoor.DebrisLayerName);
        Assert.GreaterOrEqual(debris, 0,
            "No '" + BuyableDoor.DebrisLayerName + "' layer in TagManager.asset.");

        var doors = new List<BuyableDoor>();
        foreach (var root in s_Scene.GetRootGameObjects())
            doors.AddRange(root.GetComponentsInChildren<BuyableDoor>(true));
        Assert.IsNotEmpty(doors, "No buyable doors in the map.");

        foreach (var d in doors)
            Assert.AreEqual(debris, d.gameObject.layer,
                d.name + " is on layer " + LayerMask.LayerToName(d.gameObject.layer) +
                ", not " + BuyableDoor.DebrisLayerName + ". It will bake as solid floor.");

        var surface = FindOne<NavMeshSurface>();
        Assert.IsFalse((surface.layerMask.value & (1 << debris)) != 0,
            "The NavMeshSurface still includes the DoorDebris layer in its bake mask.");
    }

    [Test]
    public void Map_DoorwaysArePassableInTheBakedNavmesh()
    {
        // The corollary of Law 12, and the thing that would actually be noticed in
        // play: because the doors are not baked, the navmesh still runs through
        // both openings. A closed door blocks at RUNTIME via its carving obstacle.
        // If someone "helpfully" rebakes with the doors present, this fails.
        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map.");
        Assert.IsTrue(NavMesh.SamplePosition(player.transform.position, out var from, 3f, NavMesh.AllAreas),
            "The player spawn is not on the navmesh.");

        // A point well inside each far zone, past its divider opening.
        var beyond = new (string what, Vector3 at)[]
        {
            ("zone B past the 750 door",  new Vector3(12f, 0f, 24f)),
            ("zone C past the 1000 door", new Vector3(40f, 0f, 24f)),
        };

        foreach (var (what, at) in beyond)
        {
            Assert.IsTrue(NavMesh.SamplePosition(at, out var hit, 2f, NavMesh.AllAreas),
                "No navmesh at " + what + " " + at + ".");

            var path = new NavMeshPath();
            NavMesh.CalculatePath(from.position, hit.position, NavMesh.AllAreas, path);
            Assert.AreEqual(NavMeshPathStatus.PathComplete, path.status,
                "No complete path to " + what + " (" + path.status + "). The doors have " +
                "probably been baked into the navmesh instead of carving at runtime.");
        }
    }

    [Test]
    public void Map_HasOnePowerSwitchAndLightsForItToTurnOn()
    {
        // PowerSwitch.IsPowered is TRUE when no switch exists, so a map without one
        // silently behaves as permanently powered -- the failure mode here is not an
        // error, it is a switch that never mattered.
        var switches = new List<PowerSwitch>();
        var powered = new List<EnableWithPower>();
        foreach (var root in s_Scene.GetRootGameObjects())
        {
            switches.AddRange(root.GetComponentsInChildren<PowerSwitch>(true));
            powered.AddRange(root.GetComponentsInChildren<EnableWithPower>(true));
        }

        Assert.AreEqual(1, switches.Count,
            "Expected exactly one PowerSwitch, found " + switches.Count + ".");

        Assert.Greater(powered.Count, 0,
            "The map has a PowerSwitch but nothing with EnableWithPower, so buying power " +
            "would change nothing visible. §12.2 calls the cascade the signature moment.");

        foreach (var p in powered)
            Assert.IsNotEmpty(p.GetComponentsInChildren<Light>(true),
                p.name + " has EnableWithPower but no Light under it, so the cascade " +
                "will sweep over it and nothing will happen.");
    }

    [Test]
    public void Map_EveryWindowIsBoardedUp()
    {
        // §1623: "Boarded windows zombies tear down and you rebuild for +10 per
        // board. This is the round-one gameplay loop and it doesn't exist."
        // A window with no barrier is a hole in that loop, so the count is tied to
        // the number of window pieces rather than to a hand-written list.
        int windowPieces = 0;
        var barriers = new List<WindowBarrier>();

        foreach (var root in s_Scene.GetRootGameObjects())
        {
            barriers.AddRange(root.GetComponentsInChildren<WindowBarrier>(true));

            foreach (var t in root.GetComponentsInChildren<Transform>(true))
            {
                var source = UnityEditor.PrefabUtility.GetCorrespondingObjectFromSource(t.gameObject);
                if (source == null || !source.name.StartsWith("Kit_Wall_4_Window")) continue;
                if (UnityEditor.PrefabUtility.GetOutermostPrefabInstanceRoot(t.gameObject) != t.gameObject) continue;
                windowPieces++;
            }
        }

        Assert.Greater(windowPieces, 0, "The map has no Kit_Wall_4_Window pieces at all.");
        Assert.AreEqual(windowPieces, barriers.Count,
            "There are " + windowPieces + " window pieces but " + barriers.Count +
            " barriers. Every window is an entrance; an unboarded one is a free way in.");
    }

    [Test]
    public void Map_EveryBarrierHasItsBoardsAndAClosedRoute()
    {
        // The two halves that make a barrier a barrier: planks to tear, and a
        // NavMeshLink that is SHUT until they are gone. A barrier whose link is
        // left enabled is scenery — zombies walk through the window past intact
        // boards and the player's repairs buy nothing.
        var barriers = new List<WindowBarrier>();
        foreach (var root in s_Scene.GetRootGameObjects())
            barriers.AddRange(root.GetComponentsInChildren<WindowBarrier>(true));

        Assert.IsNotEmpty(barriers, "No WindowBarrier in the map.");

        foreach (var b in barriers)
        {
            int boards = 0;
            foreach (Transform child in b.transform)
                if (child.name.StartsWith("Board")) boards++;

            Assert.AreEqual(b.maxBoards, boards,
                b.name + " has " + boards + " board objects but maxBoards is " + b.maxBoards +
                ". WindowBarrier toggles them by name, so the count must match.");

            var link = b.GetComponent<Unity.AI.Navigation.NavMeshLink>();
            Assert.IsNotNull(link, b.name + " has no NavMeshLink, so tearing its boards " +
                                    "opens no route and the window is decorative.");
            Assert.IsFalse(link.enabled,
                b.name + "'s NavMeshLink is enabled in the saved scene, so zombies can climb " +
                "through a fully boarded window.");

            Assert.IsNotNull(b.GetComponent<Collider>(),
                b.name + " has no collider, so InteractionSystem's raycast cannot hit it and " +
                "it can never be repaired.");
        }
    }

    [Test]
    public void Barrier_AFullRebuildIsWorthSixtyEssence()
    {
        // §1623 pins the rate at +10 per board. Six boards is the genre standard
        // and is what makes a full rebuild worth having; this asserts the two
        // numbers together so changing one without the other is caught.
        Assert.AreEqual(10, WindowBarrier.RewardPerBoard,
            "§1623 specifies +10 Essence per board.");

        var barriers = new List<WindowBarrier>();
        foreach (var root in s_Scene.GetRootGameObjects())
            barriers.AddRange(root.GetComponentsInChildren<WindowBarrier>(true));

        foreach (var b in barriers)
            Assert.AreEqual(60, b.maxBoards * WindowBarrier.RewardPerBoard,
                b.name + " pays " + (b.maxBoards * WindowBarrier.RewardPerBoard) +
                " for a full rebuild, not 60.");
    }

    [Test]
    public void Map_EveryInteractableIsReachableInPlay()
    {
        // THE GUARD THAT WAS MISSING. Phases 4 and 5 placed two buyable doors, a
        // power switch and seven boarded windows, and not one of them could be used
        // in play. Two silent omissions, neither of which any existing test could
        // see:
        //
        //   1. InteractionSystem.FindTarget returns null unless the collider it hits
        //      passes CompareTag("Interactable"). The tag existed in TagManager and
        //      nothing in the scene carried it, so every prompt died before it
        //      reached GetComponentInParent<IInteractable>().
        //   2. The Player rig had no InteractionSystem at all -- MapSeeder adds a
        //      CharacterController, an FPSController and PlayerHealth and stops.
        //
        // The previous barrier test asserted only "a Collider exists", with a
        // comment naming the exact failure it did not check. This checks the two
        // things the raycast actually requires.
        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map.");
        Assert.IsNotNull(player.GetComponentInChildren<InteractionSystem>(true),
            "The Player has no InteractionSystem, so nothing in the map can be used: " +
            "no doors bought, no power switched on, no barriers rebuilt.");

        var untagged = new List<string>();
        foreach (var root in s_Scene.GetRootGameObjects())
        {
            foreach (var behaviour in root.GetComponentsInChildren<MonoBehaviour>(true))
            {
                if (behaviour is not IInteractable) continue;

                var colliders = behaviour.GetComponentsInChildren<Collider>(true);
                if (colliders.Length == 0)
                {
                    untagged.Add(behaviour.name + " (no collider at all)");
                    continue;
                }

                // FindTarget tests hit.collider's tag, so it is the COLLIDER that
                // must carry it, not merely the object holding the script.
                bool anyTagged = false;
                foreach (var c in colliders)
                    if (c.CompareTag(InteractionSystem.InteractableTag)) { anyTagged = true; break; }

                if (!anyTagged) untagged.Add(behaviour.name);
            }
        }

        Assert.IsEmpty(untagged,
            "These IInteractables have no collider tagged '" + InteractionSystem.InteractableTag +
            "', so InteractionSystem.FindTarget will never return them and they are dead in " +
            "play: " + string.Join(", ", untagged) +
            ". Run Tools > Aftermarket > Wire Interaction.");
    }

    [Test]
    public void Map_WallbuysAndBoxesAreWiredAndPriced()
    {
        // §1621: wallbuys are "the core economy sink of BO2" and were "entirely
        // absent". §6's table puts the Mystery Box at 950.
        var wallbuys = new List<WallBuy>();
        var boxes = new List<MysteryBox>();
        foreach (var root in s_Scene.GetRootGameObjects())
        {
            wallbuys.AddRange(root.GetComponentsInChildren<WallBuy>(true));
            boxes.AddRange(root.GetComponentsInChildren<MysteryBox>(true));
        }

        Assert.IsNotEmpty(wallbuys, "No wallbuys in the map.");
        Assert.AreEqual(950, MysteryBox.Cost, "§6's table prices the Mystery Box at 950.");
        Assert.GreaterOrEqual(boxes.Count, 2,
            "Fewer than two Mystery Boxes, so a teddy bear has nowhere to relocate to and " +
            "§1622's relocation cannot happen.");

        foreach (var box in boxes)
            Assert.IsNotEmpty(box.relocationPoints,
                box.name + " has no relocation points, so 'the box has moved' would be a lie.");

        foreach (var w in wallbuys)
        {
            Assert.Greater(w.Cost, 0, w.name + " costs nothing.");
            Assert.Greater(w.AmmoCost, 0,
                w.name + " has a free ammo refill, which removes the sink entirely.");
            Assert.Less(w.AmmoCost, w.Cost,
                w.name + " charges at least as much to resupply as to buy the gun outright.");
        }
    }

    [Test]
    public void Map_PlayerCanActuallyReceiveAWeapon()
    {
        // A wallbuy with nowhere to put the gun charges the player and hands them
        // nothing. The map had NO WeaponInventory at all until Phase 6 added one.
        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map.");
        Assert.IsNotNull(player.GetComponentInChildren<WeaponInventory>(true),
            "The Player has no WeaponInventory, so every wallbuy and every Mystery Box " +
            "would take Essence and give nothing back.");
    }

    [Test]
    public void Map_PlayerHasWeaponViewModelsMatchingTheirAssets()
    {
        // Phase 6 shipped with WeaponInventory.weapons EMPTY, so wallbuys and the
        // Mystery Box both refused to charge. This is the unblock, and it checks
        // the three things that would each break it silently.
        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map.");

        var inventory = player.GetComponentInChildren<WeaponInventory>(true);
        Assert.IsNotNull(inventory, "The Player has no WeaponInventory.");
        Assert.IsNotEmpty(inventory.weapons,
            "WeaponInventory.weapons is empty, so every wallbuy and the Mystery Box " +
            "refuse to charge. Run Tools > Aftermarket > Build Weapon Rig.");

        var definitions = WeaponRigBuilder.LoadDefinitions();
        Assert.AreEqual(definitions.Count, inventory.weapons.Count,
            "There are " + definitions.Count + " WeaponDefinition assets but " +
            inventory.weapons.Count + " view models. Slot indices written into wallbuys " +
            "would point at the wrong gun.");

        for (int i = 0; i < inventory.weapons.Count; i++)
        {
            var go = inventory.weapons[i];
            Assert.IsNotNull(go, "Weapon slot " + i + " is null.");

            var gun = go.GetComponentInChildren<GunSystem>(true);
            Assert.IsNotNull(gun, go.name + " has no GunSystem, so it cannot fire.");

            // The asset is the source of truth. A scene value that disagrees with
            // it is the Part I "READ FIRST" defect in miniature.
            Assert.AreEqual(definitions[i].weaponName, gun.weaponName,
                "Slot " + i + " view model does not match its asset -- re-run Build Weapon Rig.");
            Assert.AreEqual(definitions[i].magazineSize, gun.magazineSize,
                gun.weaponName + " magazine differs from its WeaponDefinition.");

            // §13.1 defect #25: Default|Enemy meant a wall counted as a hit.
            int enemyLayer = LayerMask.NameToLayer("Enemy");
            if (enemyLayer >= 0)
                Assert.AreEqual(1 << enemyLayer, gun.whatIsEnemy.value,
                    gun.weaponName + "'s whatIsEnemy is not Enemy-only, so world geometry " +
                    "would register as an enemy hit.");

            // A collider on a view model sits inside the camera and eats the
            // player's own interaction raycast.
            Assert.IsEmpty(go.GetComponentsInChildren<Collider>(true),
                go.name + " has a collider. It sits in front of the camera and would block " +
                "InteractionSystem's raycast, so no door or barrier could ever be used.");
        }

        Assert.IsNotEmpty(inventory.startingWeapons, "The player starts with no weapon at all.");
    }

    [Test]
    public void Map_HasThePerkMachinesPackAPunchAndPowerGating()
    {
        // §6's cost table and the power rule. The failure this guards is not a
        // crash: it is a map where you can buy Juggernog before finding the power
        // switch, which removes the reason Phase 4 exists.
        var perks = new List<PerkMachine>();
        var paps = new List<PackAPunchMachine>();
        var crates = new List<AmmoCrate>();
        var gums = new List<GobblegumMachine>();
        foreach (var root in s_Scene.GetRootGameObjects())
        {
            perks.AddRange(root.GetComponentsInChildren<PerkMachine>(true));
            paps.AddRange(root.GetComponentsInChildren<PackAPunchMachine>(true));
            crates.AddRange(root.GetComponentsInChildren<AmmoCrate>(true));
            gums.AddRange(root.GetComponentsInChildren<GobblegumMachine>(true));
        }

        Assert.GreaterOrEqual(perks.Count, 4, "Fewer than four perk machines in the map.");
        Assert.AreEqual(1, paps.Count, "Expected exactly one Pack-a-Punch, found " + paps.Count + ".");
        Assert.AreEqual(5000, paps[0].cost, "§6 prices Pack-a-Punch at 5000.");

        Assert.AreEqual(1, crates.Count, "Expected one Ammo Crate.");
        Assert.AreEqual(1000, crates[0].cost, "§6 prices the Ammo Crate at 1000.");
        Assert.AreEqual(1, gums.Count, "Expected one Gobblegum machine.");
        Assert.AreEqual(500, gums[0].cost, "§6 prices Gobblegum at 500.");

        // No duplicate perks -- two Juggernogs means one is dead money.
        var seen = new List<PerkType>();
        foreach (var p in perks)
        {
            Assert.IsFalse(seen.Contains(p.perk), "Two machines both sell " + p.perk + ".");
            seen.Add(p.perk);
            Assert.Greater(p.cost, 0, p.name + " is free.");
            Assert.IsTrue(p.requiresPower,
                p.name + " is not power-gated, so it can be bought before the switch is found " +
                "and Phase 4's progression stops mattering.");
        }

        Assert.IsTrue(paps[0].requiresPower, "Pack-a-Punch is not power-gated.");

        // The Ammo Crate and Gobblegum are deliberately NOT gated -- they are the
        // only sinks available in the opening rounds, before power exists.
        Assert.IsNotNull(crates[0], "No Ammo Crate.");
    }

    [Test]
    public void Map_PlayerCanReceivePerksAndBannersCanBeShown()
    {
        // PerkMachine refunds and refuses when there is no PlayerPerks to grant to,
        // so without one every perk machine is a prompt that gives nothing back.
        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map.");
        Assert.IsNotNull(player.GetComponentInChildren<PlayerPerks>(true),
            "The Player has no PlayerPerks, so every perk machine refuses.");

        // §772: PowerUpManager.Announce is the game's general banner API and the
        // power switch routes through it. With no manager in the scene, "POWER ON"
        // and "THE BOX HAS MOVED" are silently dropped.
        bool hasManager = false;
        foreach (var root in s_Scene.GetRootGameObjects())
            if (root.GetComponentInChildren<PowerUpManager>(true) != null) { hasManager = true; break; }

        Assert.IsTrue(hasManager,
            "No PowerUpManager in the map, so every Announce() call -- including the power " +
            "switch's 'POWER ON' -- goes nowhere.");
    }

    // ---------------------------------------------------------------- placement

    /// <summary>
    /// THE PLAYER MUST NOT START INSIDE A WALL.
    ///
    /// <para>It did, for seven phases. <c>MapSeeder</c> typed the start at cell
    /// (6, 1) and <c>BuildCover</c> independently placed a <c>Kit_Wall_4_Half</c>
    /// across that same cell — (22, 0, 3.75) to (26, 2, 4.25) — with the start at
    /// its exact centre. Separately, <c>CharacterController.center</c> is
    /// (0, 0.9, 0) over a 1.8 m height, so <c>transform.position.y</c> is the
    /// FEET: y = 1.2 floated the player over a floor at 0.</para>
    ///
    /// <para>111 tests passed the whole time, and the map screenshots looked
    /// correct, because nothing had ever asked whether the one position a player
    /// is guaranteed to occupy was occupiable. This is a point-vs-volume failure:
    /// any test of the POINT passes. Only the CAPSULE fails.</para>
    /// </summary>
    [Test]
    public void Map_PlayerDoesNotStartInsideStructure()
    {
        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map.");

        var cc = player.GetComponentInChildren<CharacterController>();
        Assert.IsNotNull(cc, "The Player has no CharacterController.");

        Physics.SyncTransforms();

        var centre = player.transform.position + cc.center;
        var up = Vector3.up * Mathf.Max(0f, cc.height * 0.5f - cc.radius);

        // EVERYTHING solid, not only Kit_ structure. A barrel blocks a
        // CharacterController exactly as well as a wall does, and PropDresser puts
        // 22 of them in this map -- a version of this test that only looked for
        // Kit_ ancestors could not have seen the player spawn inside one.
        var inside = new List<string>();
        foreach (var c in Physics.OverlapCapsule(centre + up, centre - up, cc.radius,
                                                 ~0, QueryTriggerInteraction.Ignore))
        {
            if (c.GetComponentInParent<CharacterController>() != null) continue;
            inside.Add($"{c.transform.root.name}/{c.name}");
        }

        Assert.IsEmpty(inside,
            $"The player capsule at {player.transform.position} intersects solid geometry: " +
            string.Join(", ", inside) + ". Run Tools > Aftermarket > Fix Player Start.");

        // And standing on the floor, not floating over it or sunk into it.
        Assert.IsTrue(NavMesh.SamplePosition(player.transform.position, out var hit, 1f,
                                             NavMesh.AllAreas),
            $"The player start {player.transform.position} is not within 1 m of the navmesh.");
        Assert.Less(Mathf.Abs(player.transform.position.y - hit.position.y), 0.6f,
            $"The player start is {player.transform.position.y:F2} but the walkable surface " +
            $"under it is {hit.position.y:F2}. position.y is the FEET, not the capsule centre.");
    }

    /// <summary>
    /// Solid machines must not stand on a derived spawn point.
    ///
    /// <para>Phases 6 and 7 placed thirteen solid boxes with no clearance check at
    /// all, and <c>MysteryBox_Warehouse</c> was hand-typed 2.00 m centre-to-centre
    /// from <c>Spawn_00</c> — inside both <c>MapBake.SpawnKeepOut</c> and §14 Law
    /// 8's 2 m. <c>PropDresser</c> already enforced this and had actually skipped a
    /// crate for it; the check was private to that one file, so the next two tools
    /// to need it simply went without.</para>
    ///
    /// <para>Wallbuys are exempt and that is a decision, not an oversight: they are
    /// 6 cm of chalk on a wall, they block nothing, and reading as cover is exactly
    /// what their thin scale exists to prevent. Buyable doors are exempt because a
    /// doorway is a passage — its debris is temporary and excluded from the bake.
    /// <c>Door_YardToWarehouse</c> currently sits 2.00 m from a spawn; that is
    /// Phase 4 geometry, it is recorded here rather than quietly allowed, and
    /// moving it means moving a doorway.</para>
    /// </summary>
    [Test]
    public void Map_SolidMachinesClearTheSpawnKeepOut()
    {
        var spawnRoot = GameObject.Find("EnemySpawnPoints");
        Assert.IsNotNull(spawnRoot, "No EnemySpawnPoints root.");

        var spawns = new List<Vector3>();
        foreach (Transform t in spawnRoot.transform) spawns.Add(t.position);
        Assert.IsNotEmpty(spawns, "No spawn points to check against.");

        var failures = new List<string>();
        foreach (var mb in Object.FindObjectsByType<MonoBehaviour>(FindObjectsSortMode.None))
        {
            if (!(mb is IInteractable)) continue;
            if (mb is WallBuy || mb is BuyableDoor || mb is WindowBarrier || mb is PowerSwitch)
                continue;

            var p = mb.transform.position;
            foreach (var spawn in spawns)
            {
                float d = Vector2.Distance(new Vector2(spawn.x, spawn.z), new Vector2(p.x, p.z));
                if (d < 2.5f) failures.Add($"{mb.name} is {d:F2} m from a spawn at {spawn}");
            }
        }

        Assert.IsEmpty(failures,
            "Solid machines standing on a spawn point trap the agent that spawns there:\n" +
            string.Join("\n", failures));
    }

    /// <summary>
    /// Wall decals must actually be on a wall.
    ///
    /// <para><c>WallBuy_Yard_Pistol</c> was typed at (24, 1.5, 1.8) facing +Z. The
    /// nearest wall is at z = 4 — BEHIND the panel's back face and 1.9 m away — so
    /// the chalk hung in open air at 1.5 m with nothing behind it. The other two
    /// were seated correctly, which is the signature of a rule applied by hand
    /// rather than by the tool. <c>MapBake.SeatOnWall</c> now derives both position
    /// and facing from the surface it finds.</para>
    /// </summary>
    [Test]
    public void Map_WallbuysAreSeatedAgainstAWall()
    {
        Physics.SyncTransforms();

        var buys = Object.FindObjectsByType<WallBuy>(FindObjectsSortMode.None);
        Assert.IsNotEmpty(buys, "No wallbuys in the map.");

        foreach (var buy in buys)
        {
            // The panel faces out along its own forward, so the wall is behind it.
            var origin = buy.transform.position;
            var back = -buy.transform.forward;

            bool seated = false;
            foreach (var hit in Physics.RaycastAll(origin - back * 0.05f, back, 0.5f, ~0,
                                                   QueryTriggerInteraction.Ignore))
            {
                if (hit.collider.GetComponentInParent<CharacterController>() != null) continue;
                if (hit.collider.GetComponentInParent<IInteractable>() != null) continue;
                for (var t = hit.collider.transform; t != null; t = t.parent)
                    if (t.name.StartsWith("Kit_")) { seated = true; break; }
                if (seated) break;
            }

            Assert.IsTrue(seated,
                $"{buy.name} at {origin} has no map structure within 0.5 m behind it — " +
                "it is a chalk outline hanging in open air.");
        }
    }

    /// <summary>
    /// Nothing may be sunk into the floor.
    ///
    /// <para>The Ammo Crate was, by 0.20 m of its 0.70 m height. Phase 7's anchor
    /// formula was <c>at + up * (size.y * 0.5f - 0.9f)</c>, which lands a base on
    /// y = 0 only when the caller passes <c>at.y = 0.9</c>. Every caller did except
    /// the Ammo Crate, which passed 0.7. A formula that depends on a magic constant
    /// appearing in the caller's data is a trap the data cannot see.</para>
    /// </summary>
    [Test]
    public void Map_MachinesSitOnTheFloorNotInIt()
    {
        var failures = new List<string>();
        foreach (var mb in Object.FindObjectsByType<MonoBehaviour>(FindObjectsSortMode.None))
        {
            if (!(mb is IInteractable)) continue;
            if (mb is WallBuy || mb is BuyableDoor || mb is WindowBarrier || mb is PowerSwitch)
                continue;

            var renderer = mb.GetComponentInChildren<Renderer>();
            if (renderer == null) continue;

            // Only ground-floor machines: an upper-storey one is above its own slab.
            if (mb.transform.position.y > 2f) continue;

            if (renderer.bounds.min.y < -0.02f)
                failures.Add($"{mb.name} base is at y = {renderer.bounds.min.y:F2}");
        }

        Assert.IsEmpty(failures,
            "Machines buried in a floor whose walkable top is y = 0:\n" +
            string.Join("\n", failures));
    }

    /// <summary>
    /// The Mystery Box must be able to relocate more than once, and never onto
    /// another box.
    ///
    /// <para>Phase 6 wired each box's <c>relocationPoints</c> to the OTHER box's
    /// live Transform. The first teddy bear moved box A exactly on top of box B;
    /// from then on every candidate was at distance zero from the box weighing it,
    /// so <c>Relocate</c> found nothing FOREVER while <c>Announce("THE BOX HAS
    /// MOVED")</c> still fired. Every later bear took 950 Essence, gave no weapon,
    /// moved nothing, and said it had moved.</para>
    /// </summary>
    [Test]
    public void Map_MysteryBoxCanRelocateRepeatedlyAndNeverOntoAnotherBox()
    {
        var boxes = Object.FindObjectsByType<MysteryBox>(FindObjectsSortMode.None);
        Assert.GreaterOrEqual(boxes.Length, 2, "Fewer than two Mystery Boxes — §1622 asks for " +
                              "relocation and one box has nowhere to go.");

        foreach (var box in boxes)
            Assert.IsNotEmpty(box.relocationPoints, $"{box.name} has no relocation points.");

        // Dedicated markers must exist, not just the other box. With only
        // box-to-box wiring the destinations are consumed by being arrived at.
        var subject = boxes[0];
        var others = new List<Vector3>();
        foreach (var b in boxes) if (b != subject) others.Add(b.transform.position);

        int free = 0;
        foreach (var t in subject.relocationPoints)
        {
            if (t == null) continue;
            bool onABox = false;
            foreach (var o in others)
                if ((o - t.position).sqrMagnitude <= 1f) { onABox = true; break; }
            if (!onABox && (t.position - subject.transform.position).sqrMagnitude > 1f) free++;
        }

        Assert.GreaterOrEqual(free, 2,
            $"{subject.name} has only {free} relocation target(s) that are not another box's " +
            "current spot. One is a one-shot: after the first move the box has nowhere left " +
            "to go and every later teddy bear charges 950 for nothing.");
    }

    /// <summary>
    /// Shared components must not live inside a phase root.
    ///
    /// <para>The phase roots are the documented undo unit — Phase 4 defines it as
    /// "deleting the root undoes the whole pass". <c>PowerUpManager</c> was created
    /// under <c>Phase7</c>, so deleting that root to undo Phase 7 also removed the
    /// banner API that Phase 4's PowerSwitch ("POWER ON") and Phase 6's MysteryBox
    /// ("THE BOX HAS MOVED") both route through — degrading two earlier,
    /// already-signed-off phases with no error anywhere.</para>
    /// </summary>
    [Test]
    public void Map_SharedComponentsAreOutsideEveryPhaseRoot()
    {
        var manager = Object.FindFirstObjectByType<PowerUpManager>();
        Assert.IsNotNull(manager, "No PowerUpManager in the map.");

        for (var t = manager.transform; t != null; t = t.parent)
        {
            Assert.IsFalse(t.name.StartsWith("Phase"),
                $"PowerUpManager is under '{t.name}'. That root is the undo unit for its phase, " +
                "so undoing that phase silently removes the banner API earlier phases use.");
        }
    }

    /// <summary>
    /// The player must start where it was DESIGNED to start, on flat ground.
    ///
    /// <para><c>Map_PlayerDoesNotStartInsideStructure</c> passed on a start that
    /// was 10.5 m from the design position, standing on the loading-dock ramp,
    /// because "capsule clear of Kit_ colliders" and "|y − navmesh y| &lt; 0.6" are
    /// both true up there. The repair tool had offset only x and z, so its probe
    /// kept the broken start's y of 1.2 and <c>SamplePosition</c>'s 1.0 m reach
    /// could not touch the yard navmesh at 0.04 — every ring over flat floor
    /// failed and the first spot that could succeed was raised geometry. Worse, it
    /// was self-sealing: re-run from the ramp and the ramp samples fine, so the
    /// tool said "already clear" and the wrong answer became permanent.</para>
    ///
    /// <para>Two assertions, because either alone passes on the ramp: near the
    /// design position, AND on the ground floor rather than a slope.</para>
    /// </summary>
    [Test]
    public void Map_PlayerStartsWhereItWasDesignedToOnFlatGround()
    {
        var player = GameObject.Find("Player");
        Assert.IsNotNull(player, "No Player in the map.");

        var actual = player.transform.position;
        var designed = MapSeeder.PlayerStart;

        float drift = Vector2.Distance(new Vector2(actual.x, actual.z),
                                       new Vector2(designed.x, designed.z));
        Assert.Less(drift, 4f,
            $"The player starts at {actual}, {drift:F2} m from the designed start {designed}. " +
            "A repair that wanders across the map is not a repair — run " +
            "Tools > Aftermarket > Fix Player Start, which anchors to the design position.");

        // The yard floor's navmesh is at y = 0.04; the dock is 2 m and the ramp
        // between them is anything in between. 0.3 keeps the player off both.
        Assert.Less(actual.y, 0.3f,
            $"The player starts at y = {actual.y:F3}, which is not the ground floor — " +
            "it is on a ramp, a crate or the loading dock.");
    }

    /// <summary>
    /// Every Mystery Box relocation marker must be somewhere a box can legally be.
    ///
    /// <para><c>MysteryBox.Relocate</c> teleports with a bare
    /// <c>transform.position = destination.position</c> and no further test, so a
    /// marker is trusted absolutely. The markers were hand-typed and
    /// <c>FindClearSpot</c> validated only ring candidates — meaning it never ran
    /// on any of the three, because none of them failed the spawn check that
    /// gates it. The relocation fix would have swapped "destinations that consume
    /// themselves" for "destinations nobody checked".</para>
    /// </summary>
    [Test]
    public void Map_MysteryBoxRelocationMarkersAreStandableOn()
    {
        Physics.SyncTransforms();

        var markers = new List<Transform>();
        foreach (var box in Object.FindObjectsByType<MysteryBox>(FindObjectsSortMode.None))
            foreach (var t in box.relocationPoints)
                if (t != null && !markers.Contains(t)) markers.Add(t);

        Assert.IsNotEmpty(markers, "No relocation markers at all.");

        foreach (var m in markers)
        {
            // A live box's own transform is a legal target by construction.
            if (m.GetComponent<MysteryBox>() != null) continue;

            Assert.IsTrue(NavMesh.SamplePosition(new Vector3(m.position.x, 0f, m.position.z),
                                                 out var hit, 1.5f, NavMesh.AllAreas),
                $"Relocation marker {m.name} at {m.position} has no walkable navmesh under it. " +
                "The box would teleport into a wall.");

            Assert.Less(hit.position.y, 0.5f,
                $"Relocation marker {m.name} sits over a raised surface at y = " +
                $"{hit.position.y:F2}, not the ground floor.");

            var centre = new Vector3(m.position.x, hit.position.y + 0.6f, m.position.z);
            foreach (var c in Physics.OverlapBox(centre, new Vector3(0.48f, 0.48f, 0.32f),
                                                 Quaternion.identity, ~0,
                                                 QueryTriggerInteraction.Ignore))
            {
                for (var t = c.transform; t != null; t = t.parent)
                    Assert.IsFalse(t.name.StartsWith("Kit_"),
                        $"Relocation marker {m.name} at {m.position} is inside {t.name}.");
            }
        }
    }

    /// <summary>
    /// Every floor-standing machine must actually be standing on ground-floor
    /// navmesh, and not inside map structure.
    ///
    /// <para><b>Nothing checked this, and the two tests that looked like they did
    /// were each measuring one axis of it.</b>
    /// <c>Map_SolidMachinesClearTheSpawnKeepOut</c> measures distance to a spawn
    /// point; <c>Map_MachinesSitOnTheFloorNotInIt</c> measures
    /// <c>renderer.bounds.min.y</c>. A machine standing 0.4 m inside a warehouse
    /// wall passes both — its base is still at y = 0 and it is still 6 m from any
    /// spawn.</para>
    ///
    /// <para>That mattered because <see cref="MapBake.FindClearSpot"/> used to run
    /// its navmesh and occupancy tests ONLY on ring candidates, i.e. only once the
    /// typed coordinate had already failed the spawn check. Of sixteen placements
    /// across Phases 6 and 7 exactly one was ever nudged, so fifteen hand-typed
    /// literals went into the map with nothing but a distance check against them.
    /// The fix validates the desired point too — this is what stops that quietly
    /// regressing, and it reads the SCENE rather than the builder.</para>
    ///
    /// <para>Wall decals, doors, barriers and the power switch are excluded: they
    /// are mounted ON structure by design, so "not inside a Kit_ piece" is the
    /// wrong question for them.</para>
    /// </summary>
    [Test]
    public void Map_FloorStandingMachinesStandOnGroundFloorNavmesh()
    {
        Physics.SyncTransforms();

        var failures = new List<string>();
        foreach (var mb in Object.FindObjectsByType<MonoBehaviour>(FindObjectsSortMode.None))
        {
            if (!(mb is IInteractable)) continue;
            if (mb is WallBuy || mb is BuyableDoor || mb is WindowBarrier || mb is PowerSwitch)
                continue;

            var p = mb.transform.position;
            if (p.y > 2f) continue;   // an upper-storey machine is above its own slab

            if (!NavMesh.SamplePosition(new Vector3(p.x, 0f, p.z), out var hit, 1.5f,
                                        NavMesh.AllAreas))
            {
                failures.Add($"{mb.name} at {p} has no walkable navmesh under it at ground level");
                continue;
            }
            if (hit.position.y > 0.5f)
            {
                failures.Add($"{mb.name} at {p} stands on a raised surface " +
                             $"(navmesh at y = {hit.position.y:F2}), not the ground floor");
                continue;
            }

            // The ORIENTED box, not renderer.bounds. Perk machines carry a yaw, and
            // a world AABB of a rotated box is already inflated -- testing that
            // would report a wall the machine does not actually touch. Shrunk by
            // 2 cm so a machine authored flush against a wall is not a failure.
            var box = mb.GetComponentInChildren<BoxCollider>();
            if (box == null) continue;

            var half = Vector3.Scale(box.size * 0.5f, box.transform.lossyScale)
                       - Vector3.one * 0.02f;
            if (half.x <= 0f || half.y <= 0f || half.z <= 0f) continue;

            foreach (var c in Physics.OverlapBox(box.transform.TransformPoint(box.center), half,
                                                 box.transform.rotation, ~0,
                                                 QueryTriggerInteraction.Ignore))
            {
                for (var t = c.transform; t != null; t = t.parent)
                {
                    if (!t.name.StartsWith("Kit_")) continue;
                    failures.Add($"{mb.name} at {p} is inside map structure ({t.name})");
                    break;
                }
            }
        }

        Assert.IsEmpty(failures,
            "Machines that are not standing where a player could reach them:\n" +
            string.Join("\n", failures));
    }
}
