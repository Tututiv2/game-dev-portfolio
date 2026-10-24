using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Phase 6: chalk wallbuys and two Mystery Boxes.
///
/// <para>Additive under one <c>Phase6</c> root, refuses to re-run, same contract
/// as the Phase 4 and 5 builders — <b>with one stated exception</b>. Phase 4
/// defines that contract as "deleting the root undoes the whole pass", and the
/// <see cref="WeaponInventory"/> this tool adds to the <c>Player</c> root is
/// outside it, as are the view models <see cref="WeaponRigBuilder"/> hangs under
/// <c>CameraHolder</c>. They are shared with later phases, so they cannot live
/// under this one; deleting <c>Phase6</c> removes the machines and leaves those
/// behind. The build log says so on every run rather than leaving it implied.</para>
///
/// <para><b>Everything it places is TAGGED as it is built.</b> Phases 4 and 5
/// both shipped with nothing tagged, which made every door, switch and barrier
/// unusable in play with no error anywhere. That is not repeated here, and
/// <c>MapSceneTests.Map_EveryInteractableIsReachableInPlay</c> fails the build if
/// it ever is.</para>
///
/// <para><b>It also adds the missing <see cref="WeaponInventory"/>.</b> There was
/// none in the map at all — <see cref="MapSeeder"/> never added one — so a wallbuy
/// would have charged the player and had nothing to hand the gun to. A machine
/// that takes your money and gives you nothing is worse than an absent one.</para>
/// </summary>
public static class Phase6Builder
{
    const string k_Root = "Phase6";

    /// <summary>
    /// Wallbuys sit ON a wall, so each is a position plus the yaw of the wall face
    /// it is painted on. Costs come from the weapon asset, not from here — see
    /// <see cref="WallBuy"/>.
    /// </summary>
    static readonly (string name, int slot, Vector3 at, float yaw)[] k_WallBuys =
    {
        // Zone A, the spawn yard: the first gun you can afford off barrier money.
        ("WallBuy_Yard_Pistol",     0, new Vector3(24f, 1.5f, 1.8f),  0f),
        // Zone B, past the 750 door.
        ("WallBuy_Warehouse_Rifle", 1, new Vector3(2.3f, 1.5f, 30f),  90f),
        // Zone C, past the 1000 door.
        ("WallBuy_Substation_Rifle",1, new Vector3(34.3f, 1.5f, 34f), 90f),
    };

    /// <summary>Two boxes, so a teddy bear always has somewhere to send one.</summary>
    static readonly (string name, Vector3 at)[] k_Boxes =
    {
        ("MysteryBox_Warehouse",  new Vector3(12f, 0.6f, 34f)),
        ("MysteryBox_Substation", new Vector3(44f, 0.6f, 30f)),
    };

    /// <summary>
    /// DEDICATED relocation markers, and they are the fix for a box that used to
    /// destroy itself on first use.
    ///
    /// <para>This was written as "each box relocates to where the other box is",
    /// which reads sensibly and is fatal: the first teddy bear moved box A exactly
    /// on top of box B, and from then on every candidate sat at distance zero from
    /// the box weighing it, so <c>Relocate</c> found no candidates FOREVER. Every
    /// later bear charged 950, gave no weapon, moved nothing, and announced a
    /// move. Empty markers at spots no box starts on mean the destinations cannot
    /// be consumed by being arrived at.</para>
    /// </summary>
    static readonly (string name, Vector3 at)[] k_BoxSpots =
    {
        ("BoxSpot_Yard",       new Vector3(34f, 0.6f, 10f)),
        ("BoxSpot_Warehouse2", new Vector3(6f,  0.6f, 24f)),
        ("BoxSpot_Substation2",new Vector3(50f, 0.6f, 44f)),
    };

    [MenuItem("Tools/Aftermarket/Build Phase 6 (Wallbuys + Mystery Box)")]
    public static void Build()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[Phase6] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[Phase6] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        foreach (var go in scene.GetRootGameObjects())
        {
            if (go.name != k_Root) continue;
            Debug.LogWarning($"[Phase6] a '{k_Root}' root already exists, so nothing was built. " +
                             "Delete it to run again.");
            return;
        }

        var root = new GameObject(k_Root).transform;
        var keepOut = MapBake.KeepOutPoints();

        var definitions = LoadWeaponDefinitions();
        foreach (var w in k_WallBuys) BuildWallBuy(w.name, w.slot, w.at, w.yaw, root, definitions);

        var boxes = new List<MysteryBox>();
        foreach (var b in k_Boxes) boxes.Add(BuildBox(b.name, b.at, root, keepOut));

        // Empty markers, placed once and never moved onto by construction.
        var spots = new GameObject("BoxRelocationSpots").transform;
        spots.SetParent(root, false);
        // A marker that cannot be validated is NOT CREATED. MysteryBox.Relocate
        // teleports with a bare `transform.position = destination.position` and no
        // further test, so an unchecked marker is a box inside a wall — and the
        // silent `? clear : s.at` fallback this replaces meant the fix for
        // "destinations that consume themselves" shipped as "destinations nobody
        // validated". Fewer good targets beats more bad ones.
        var boxSize = new Vector3(1.2f, 1.2f, 0.8f);
        int markers = 0;
        foreach (var s in k_BoxSpots)
        {
            if (!MapBake.FindClearSpot(s.at, boxSize, keepOut, out var clear, out string why))
            {
                Debug.LogError($"[Phase6] relocation marker {s.name} at {s.at} is unusable " +
                               $"({why}) and nothing clear was found within 6 m, so it was NOT " +
                               "created. Retype the coordinate.");
                continue;
            }
            if (clear != s.at)
            {
                Debug.LogWarning($"[Phase6] relocation marker {s.name} moved " +
                                 $"{Vector3.Distance(s.at, clear):F2} m from {s.at} to {clear} " +
                                 $"because {why}.");
            }

            var marker = new GameObject(s.name).transform;
            marker.SetParent(spots, false);
            marker.position = clear;
            markers++;
        }

        if (markers < 2)
        {
            Debug.LogError($"[Phase6] only {markers} usable relocation marker(s). A box needs " +
                           "at least two somewhere-elses or the teddy bear has nothing to do.");
        }

        // Every box may go to every marker, plus the other box's STARTING spot --
        // which is safe now only because Relocate() rejects a point another box is
        // standing on.
        foreach (var box in boxes)
        {
            foreach (Transform marker in spots) box.relocationPoints.Add(marker);
            foreach (var other in boxes)
                if (other != box) box.relocationPoints.Add(other.transform);
        }

        bool addedInventory = EnsurePlayerHasWeapons(scene);

        // Thirteen solid boxes went into this map across Phases 6 and 7 with no
        // rebake at all, so the navmesh reported open floor where a 1.2 m Mystery
        // Box stood and agents walked through it. Doors and barriers are the
        // documented exceptions; an ordinary machine is not one.
        MapBake.Rebake("Phase6");

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        Debug.Log($"[Phase6] built {k_WallBuys.Length} wallbuys and {k_Boxes.Length} mystery boxes " +
                  $"(each {MysteryBox.Cost}) under '{k_Root}', all tagged " +
                  $"'{InteractionSystem.InteractableTag}'." +
                  (addedInventory
                      ? " Added the missing WeaponInventory to the Player — without it a wallbuy " +
                        "charges you and hands the gun to nothing."
                      : " The Player already had a WeaponInventory."));
    }

    static List<WeaponDefinition> LoadWeaponDefinitions()
    {
        var list = new List<WeaponDefinition>();
        foreach (var guid in AssetDatabase.FindAssets("t:WeaponDefinition",
                                                      new[] { "Assets/_Project/Content" }))
        {
            var def = AssetDatabase.LoadAssetAtPath<WeaponDefinition>(
                AssetDatabase.GUIDToAssetPath(guid));
            if (def != null) list.Add(def);
        }
        list.Sort((a, b) => string.CompareOrdinal(a.name, b.name));
        return list;
    }

    static void BuildWallBuy(string name, int slot, Vector3 at, float yaw, Transform parent,
                             List<WeaponDefinition> definitions)
    {
        // A flat chalk panel on the wall. Thin on purpose — this is paint, not a
        // machine, so it must not read as something you could hide behind.
        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        go.name = name;
        go.transform.SetParent(parent, false);
        go.transform.localScale = new Vector3(1.1f, 0.55f, 0.06f);

        // Seated against the wall it is painted on, not placed at a typed point.
        // No spawn keep-out applies: this is 6 cm of chalk on a wall, it blocks
        // nothing, and the whole point of the thin scale is that it must not read
        // as cover.
        if (MapBake.SeatOnWall(at, yaw, 0.03f, out var seated, out var facing))
        {
            go.transform.position = seated;
            go.transform.rotation = facing;
        }
        else
        {
            go.transform.position = at;
            go.transform.rotation = Quaternion.Euler(0f, yaw, 0f);
            Debug.LogError($"[Phase6] {name} at {at} has no wall within 3 m in either " +
                           "direction, so it is hanging in open air. Move the coordinate " +
                           "against a wall.");
        }

        var material = AssetDatabase.LoadAssetAtPath<Material>(
            "Assets/_Project/Art/Materials/Grunge texture/Grunge texture.mat");
        if (material != null) go.GetComponent<MeshRenderer>().sharedMaterial = material;

        go.tag = InteractionSystem.InteractableTag;

        var buy = go.AddComponent<WallBuy>();
        buy.weaponSlot = slot;
        if (slot >= 0 && slot < definitions.Count) buy.definition = definitions[slot];
    }

    static MysteryBox BuildBox(string name, Vector3 at, Transform parent, List<Vector3> keepOut)
    {
        var size = new Vector3(1.2f, 1.2f, 0.8f);

        // A Mystery Box IS solid cover, so it must not stand on a spawn point.
        if (!MapBake.FindClearSpot(at, size, keepOut, out var position, out string why))
        {
            Debug.LogError($"[Phase6] {name} at {at} is inside the " +
                           $"{MapBake.SpawnKeepOut} m spawn keep-out and no clear spot was " +
                           "found within 6 m. Placed as typed — fix the coordinate.");
            position = at;
        }
        else if (position != at)
        {
            Debug.LogWarning($"[Phase6] {name} moved {Vector3.Distance(at, position):F2} m " +
                             $"from {at} to {position} because {why}.");
        }

        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        go.name = name;
        go.transform.SetParent(parent, false);
        go.transform.position = position;
        go.transform.localScale = size;

        var material = AssetDatabase.LoadAssetAtPath<Material>(
            "Assets/_Project/Art/Materials/Rusty metal texture/Rusty metal texture.mat");
        if (material != null) go.GetComponent<MeshRenderer>().sharedMaterial = material;

        go.tag = InteractionSystem.InteractableTag;
        return go.AddComponent<MysteryBox>();
    }

    /// <summary>
    /// The map had no WeaponInventory anywhere. Adding it here rather than in
    /// MapSeeder because the seeder is one-shot and will not run again.
    /// </summary>
    static bool EnsurePlayerHasWeapons(Scene scene)
    {
        GameObject player = null;
        foreach (var go in scene.GetRootGameObjects())
            if (go.name == "Player") { player = go; break; }

        if (player == null)
        {
            Debug.LogError("[Phase6] no 'Player' root; wallbuys will charge and give nothing.");
            return false;
        }

        if (player.GetComponentInChildren<WeaponInventory>(true) != null) return false;

        player.AddComponent<WeaponInventory>();
        Debug.LogWarning("[Phase6] the Player had no WeaponInventory, so one was added. Its " +
                         "'weapons' list is EMPTY — no view-model prefabs exist yet, so a " +
                         "wallbuy will take the money and GiveWeapon will refuse the slot. " +
                         "Populate that list before play-testing Phase 6.");
        return true;
    }
}
