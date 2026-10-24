using System.Collections.Generic;
using System.IO;
using System.Linq;
using NUnit.Framework;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Ashfall Metro — the second map.
///
/// <para>Most of these ask the PLAN rather than the scene, because the plan is the
/// thing a person edits and a layout mistake should fail before a 90 second rebuild,
/// not after. The ones that do open the scene check what only exists once it is
/// built: the navmesh, and whether every machine can be walked to.</para>
///
/// <para>Unlike <c>AshfallDepot</c>, this scene is a BUILD OUTPUT, so opening it in
/// a test cannot destroy hand dressing — there is none to destroy.</para>
/// </summary>
public class MetroMapTests
{
    static MapPlan.Storey Plan => MetroSeeder.BuildPlan();

    // ---- the layout, without building anything ----------------------------

    [Test]
    public void Metro_ThePlanContainsALoop()
    {
        // The whole game past round 10 is walking a horde in a circle. Two
        // internally vertex-disjoint routes IS a circle; one route is a dead end
        // wearing a map's clothes. This caught the first layout, where everything
        // north of the spine hung off a single doorway.
        int routes = MetroVerify.DisjointRoutes(Plan, new Vector2Int(8, 2), new Vector2Int(8, 11));
        Assert.GreaterOrEqual(routes, 2,
            $"only {routes} independent route(s) between the spawn hall and the north hall");
    }

    [Test]
    public void Metro_EveryCellIsReachableFromTheSpawn()
    {
        // A room nobody can walk to is invisible in every screenshot and in every
        // other test. Flood fill the plan the way a player walks it.
        var plan = Plan;
        var start = new Vector2Int(8, 2);
        Assert.IsTrue(plan.Floor.Contains(start), "the spawn cell is not walkable");

        var seen = new HashSet<Vector2Int> { start };
        var queue = new Queue<Vector2Int>();
        queue.Enqueue(start);
        while (queue.Count > 0)
        {
            var c = queue.Dequeue();
            foreach (MapPlan.Edge e in new[] { MapPlan.Edge.North, MapPlan.Edge.East,
                                               MapPlan.Edge.South, MapPlan.Edge.West })
            {
                var step = MapPlan.Step(e);
                var n = new Vector2Int(c.x + step.x, c.y + step.y);
                if (!plan.Floor.Contains(n) || seen.Contains(n)) continue;

                // Solid walls stop a player; a doorway is bought and then gone.
                string piece;
                if (plan.Forced.TryGetValue((c, e), out piece) ||
                    plan.Forced.TryGetValue((n, MapPlan.Opposite(e)), out piece))
                    if (piece != "Kit_Wall_4_Doorway") continue;

                seen.Add(n);
                queue.Enqueue(n);
            }
        }

        var stranded = plan.Floor.Except(seen).ToList();
        Assert.IsEmpty(stranded,
            $"{stranded.Count} walkable cell(s) cannot be reached from the spawn: " +
            string.Join(", ", stranded.Take(8)));
    }

    [Test]
    public void Metro_TheImportedModulesLandInsideTheMap()
    {
        // The module block is placed from the cut manifest, and its cells run along
        // NEGATIVE x and z. Getting that backwards puts the corridor outside the
        // building, where it still renders perfectly.
        var plan = Plan;
        foreach (var mpath in KitModuleBuilder.ManifestPaths())
        {
            var man = KitModuleBuilder.Load(mpath);
            Assert.IsNotEmpty(man.modules, "the module manifest is empty");
            foreach (var mod in man.modules)
            {
                var cell = new Vector2Int(14 - mod.cell[0], 4 - mod.cell[1]);
                Assert.IsTrue(plan.Reserved.Contains(cell),
                    $"module {mod.name} lands on cell {cell}, which the plan does not reserve");
                Assert.IsTrue(plan.Floor.Contains(cell),
                    $"module {mod.name} lands on cell {cell}, which is not part of the map");
            }
        }
    }

    [Test]
    public void Metro_NoCellIsBothKitAndImportedGeometry()
    {
        // Reserved cells get no kit floor. If a cell were floored twice the two
        // surfaces would z-fight and the navmesh would bake a double layer.
        var plan = Plan;
        foreach (var c in plan.Reserved)
            Assert.IsTrue(plan.Floor.Contains(c),
                $"cell {c} is reserved for a module but is not walkable");
    }

    [Test]
    public void Metro_ThePlayerStartsOnTheFloorOfTheSpawnHall()
    {
        var start = MetroSeeder.PlayerStart;
        var cell = new Vector2Int(Mathf.RoundToInt(start.x / MapPlan.Cell),
                                  Mathf.RoundToInt(start.z / MapPlan.Cell));
        Assert.IsTrue(Plan.Floor.Contains(cell),
            $"the player starts at {start} = cell {cell}, which is not a walkable cell");

        // y is the FEET, not the capsule centre. The other map started the player
        // 1.2 m in the air because of exactly this.
        Assert.Less(start.y, 0.3f, "the player start y should be the feet, near the floor");
    }

    // ---- the built scene ---------------------------------------------------

    static Scene OpenMetro()
    {
        Assert.IsTrue(File.Exists(MetroSeeder.ScenePath),
            $"No scene at {MetroSeeder.ScenePath}. Run Tools > Aftermarket > " +
            "Build New Map (Ashfall Metro) - EVERYTHING.");
        var open = SceneManager.GetActiveScene();
        return open.path == MetroSeeder.ScenePath
            ? open
            : EditorSceneManager.OpenScene(MetroSeeder.ScenePath, OpenSceneMode.Single);
    }

    [Test]
    public void Metro_IsInBuildSettings()
    {
        // A scene not in Build Settings cannot be loaded, and the symptom is a
        // button that does nothing.
        Assert.IsTrue(EditorBuildSettings.scenes.Any(s => s.path == MetroSeeder.ScenePath),
            $"{MetroSeeder.ScenePath} is not in Build Settings, so nothing can load it");
    }

    [Test]
    public void Metro_SceneIsTextYamlSoItCanBeDiffed()
    {
        var first = File.ReadLines(MetroSeeder.ScenePath).FirstOrDefault() ?? "";
        Assert.IsTrue(first.StartsWith("%YAML"),
            "the scene is not text YAML — an inline NavMeshData forces the whole " +
            "scene to binary whatever EditorSettings says");
    }

    [Test]
    public void Metro_EverythingIsReachableAndTheLoopSurvivesTheBake()
    {
        var report = MetroVerify.Verify(OpenMetro());
        Assert.Greater(report.navMeshArea, 500f, "the navmesh is missing or tiny");
        Assert.AreEqual(0, report.unreachable,
            $"{report.unreachable} gameplay object(s) cannot be walked to:\n  " +
            string.Join("\n  ", report.problems));
        Assert.AreEqual(0, report.spawnsThatCannotReachThePlayer,
            "an enemy spawn that cannot path to the player is a round that never ends");
        Assert.GreaterOrEqual(report.disjointRoutes, 2, "the built map has no loop");
    }

    [Test]
    public void Metro_HasTheGameplayARoundBasedMapNeeds()
    {
        var scene = OpenMetro();
        var all = scene.GetRootGameObjects()
                       .SelectMany(g => g.GetComponentsInChildren<MonoBehaviour>(true))
                       .ToList();

        int Count<T>() where T : MonoBehaviour { return all.OfType<T>().Count(); }

        Assert.GreaterOrEqual(Count<BuyableDoor>(), 4, "too few buyable doors to gate progression");
        Assert.AreEqual(1, Count<PowerSwitch>(), "there must be exactly one power switch");
        Assert.GreaterOrEqual(Count<PerkMachine>(), 4, "too few perk machines");
        Assert.GreaterOrEqual(Count<MysteryBox>(), 1, "no Mystery Box");
        Assert.AreEqual(1, Count<PackAPunchMachine>(), "there must be exactly one Pack-a-Punch");
        Assert.GreaterOrEqual(Count<WallBuy>(), 2, "too few wallbuys");
        Assert.GreaterOrEqual(Count<WindowBarrier>(), 4, "too few boarded windows");
        Assert.GreaterOrEqual(Count<EnableWithPower>(), 3,
            "the power switch turns nothing on, so buying power buys nothing visible");

        // The perks must be DIFFERENT perks. Five Juggernogs is five of one thing.
        var perks = all.OfType<PerkMachine>().Select(p => p.perk).ToList();
        Assert.AreEqual(perks.Count, perks.Distinct().Count(),
            "two perk machines dispense the same perk");
    }

    [Test]
    public void Metro_PerksAndPackAPunchAreGatedOnPower()
    {
        var scene = OpenMetro();
        var all = scene.GetRootGameObjects()
                       .SelectMany(g => g.GetComponentsInChildren<MonoBehaviour>(true)).ToList();

        foreach (var perk in all.OfType<PerkMachine>())
            Assert.IsTrue(perk.requiresPower,
                $"{perk.name} is not power-gated, so the power switch is worth nothing to it");
        foreach (var pap in all.OfType<PackAPunchMachine>())
            Assert.IsTrue(pap.requiresPower, "Pack-a-Punch is not power-gated");
    }

    [Test]
    public void Metro_EveryInteractableCarriesTheTagOrItIsSceneryForever()
    {
        var scene = OpenMetro();
        foreach (var root in scene.GetRootGameObjects())
            foreach (var mb in root.GetComponentsInChildren<MonoBehaviour>(true))
            {
                if (!(mb is IInteractable)) continue;
                var colliders = mb.GetComponentsInChildren<Collider>(true);
                Assert.IsNotEmpty(colliders, $"{mb.name} has no collider to raycast against");
                Assert.IsTrue(colliders.Any(c => c.CompareTag(InteractionSystem.InteractableTag)),
                    $"{mb.name}: no collider carries the '{InteractionSystem.InteractableTag}' tag, " +
                    "so InteractionSystem.FindTarget returns null and it can never be used");
            }
    }

    [Test]
    public void Metro_DoorsAreOnTheDebrisLayerSoTheyDoNotBakeAsFloor()
    {
        var scene = OpenMetro();
        int layer = LayerMask.NameToLayer(BuyableDoor.DebrisLayerName);
        Assert.GreaterOrEqual(layer, 0, $"no '{BuyableDoor.DebrisLayerName}' layer");

        foreach (var root in scene.GetRootGameObjects())
            foreach (var door in root.GetComponentsInChildren<BuyableDoor>(true))
                Assert.AreEqual(layer, door.gameObject.layer,
                    $"{door.name} is not on {BuyableDoor.DebrisLayerName}, so the navmesh baked it " +
                    "as solid floor and its doorway is impassable before it is ever bought");
    }
}
