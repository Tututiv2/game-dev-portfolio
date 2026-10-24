using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Phase 7: perk machines, Pack-a-Punch, an Ammo Crate and the Gobblegum machine.
///
/// <para><b>Placement again, not new systems.</b> <see cref="PerkMachine"/>,
/// <see cref="PackAPunchMachine"/>, <see cref="AmmoCrate"/>,
/// <see cref="GobblegumMachine"/>, <see cref="PowerUpManager"/> and
/// <see cref="PlayerPerks"/> all came out of Phase 1 complete, and the five perk
/// assets already exist. What was missing was any of them being in the map.</para>
///
/// <para><b>Everything is tagged and colour-coded as it is built.</b> Phases 4
/// and 5 shipped untagged and were dead in play; the perk colours come from each
/// <see cref="PerkDefinition.machineColor"/> so a player can tell Juggernog from
/// Speed Cola across a room, which is the only thing making five identical boxes
/// readable before there is machine art.</para>
///
/// <para><b>Power gating is what ties this phase to Phase 4.</b> Perks and PaP
/// set <c>requiresPower</c>, so before the substation switch is thrown every one
/// of them shows "REQUIRES POWER" and refuses. The Gobblegum machine and the
/// Ammo Crate do not — §6's table lists neither as power-gated, and they are the
/// only sinks available in the opening rounds.</para>
/// </summary>
public static class Phase7Builder
{
    const string k_Root = "Phase7";

    /// <summary>
    /// Perks are spread one per zone so buying the set means opening the map,
    /// which is what makes §12.1's door progression worth paying for. Costs are
    /// BO2's, and §6 notes perk costs vary per map.
    /// </summary>
    static readonly (PerkType perk, int cost, Vector3 at, float yaw)[] k_Perks =
    {
        // Zone A — the only perk you can reach before buying a door. Quick Revive
        // is the traditional cheap one and the traditional starting-room perk.
        (PerkType.QuickRevive, 1500, new Vector3(30f, 0f, 4.5f),  180f),
        // Zone B, inside the warehouse.
        (PerkType.Juggernog,   2500, new Vector3(6f,  0f, 42.5f),   0f),
        (PerkType.SpeedCola,   3000, new Vector3(18f, 0f, 28.5f), 180f),
        // Zone C, in and around the substation.
        (PerkType.DoubleTap,   2000, new Vector3(38f, 0f, 40.5f),   0f),
        (PerkType.StaminUp,    2000, new Vector3(48f, 0f, 22f),    90f),
    };

    /// <summary>§6: Pack-a-Punch is 5000 and power-gated. It sits in the
    /// substation with the switch, so the room that turns the map on is also the
    /// room holding its biggest prize.</summary>
    static readonly Vector3 k_PackAPunch = new Vector3(44f, 0f, 36.5f);

    /// <summary>§6: 1000, no power. Zone A, so ammo is buyable from round one.</summary>
    static readonly Vector3 k_AmmoCrate = new Vector3(18f, 0f, 4.5f);

    /// <summary>§6: 500, no power.</summary>
    static readonly Vector3 k_Gobblegum = new Vector3(44f, 0f, 6f);

    [MenuItem("Tools/Aftermarket/Build Phase 7 (Perks + PaP + Power-ups)")]
    public static void Build()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[Phase7] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[Phase7] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        foreach (var go in scene.GetRootGameObjects())
        {
            if (go.name != k_Root) continue;
            Debug.LogWarning($"[Phase7] a '{k_Root}' root already exists, so nothing was built. " +
                             "Delete it to run again.");
            return;
        }

        var definitions = LoadPerkDefinitions();
        var root = new GameObject(k_Root).transform;
        var keepOut = MapBake.KeepOutPoints();

        foreach (var p in k_Perks) BuildPerk(p.perk, p.cost, p.at, p.yaw, root, definitions, keepOut);

        BuildBox("PackAPunch", k_PackAPunch, new Vector3(1.6f, 1.8f, 1.0f),
                 // VIOLET, not gold. Gold was the first choice and it was wrong:
                 // Double Tap's machineColor is (1, 0.85, 0.3) and both machines
                 // stand in the substation, so two of the map's most expensive
                 // interactables were a metre apart in near-identical colours --
                 // defeating the only thing making five untextured boxes readable.
                 // Violet is also BO2's own PaP lighting.
                 new Color(0.45f, 0.15f, 0.75f), root, keepOut).AddComponent<PackAPunchMachine>();

        BuildBox("AmmoCrate", k_AmmoCrate, new Vector3(1.2f, 0.7f, 0.9f),
                 new Color(0.35f, 0.4f, 0.25f), root, keepOut).AddComponent<AmmoCrate>();

        BuildBox("GobblegumMachine", k_Gobblegum, new Vector3(0.8f, 1.8f, 0.8f),
                 new Color(0.9f, 0.3f, 0.55f), root, keepOut).AddComponent<GobblegumMachine>();

        bool addedPerks = EnsurePlayerHasPerks(scene);
        bool addedManager = EnsurePowerUpManager(scene);

        // Thirteen solid boxes went into this map across Phases 6 and 7 with no
        // rebake at all, so the navmesh went on reporting open floor where a 1.8 m
        // perk machine stood and agents walked straight through it. Doors and
        // barriers are the documented exceptions; an ordinary machine is not one.
        MapBake.Rebake("Phase7");

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        Debug.Log($"[Phase7] built {k_Perks.Length} perk machines, Pack-a-Punch (5000), " +
                  $"an Ammo Crate (1000) and a Gobblegum machine (500) under '{k_Root}', " +
                  $"all tagged '{InteractionSystem.InteractableTag}'." +
                  (addedPerks ? " Added PlayerPerks to the Player." : "") +
                  (addedManager ? " Added a PowerUpManager." : "") +
                  " Perks and PaP are power-gated and will read REQUIRES POWER until the " +
                  "substation switch is thrown. Rebaked the navmesh so the machines block. " +
                  "NOTE: PlayerPerks (on the Player) and PowerUpManager (a scene root) are " +
                  "SHARED and live outside the Phase7 root -- deleting that root undoes the " +
                  "machines only, and those two must be removed by hand.");
    }

    static Dictionary<PerkType, PerkDefinition> LoadPerkDefinitions()
    {
        var map = new Dictionary<PerkType, PerkDefinition>();
        foreach (var guid in AssetDatabase.FindAssets("t:PerkDefinition",
                                                      new[] { "Assets/_Project/Content" }))
        {
            var def = AssetDatabase.LoadAssetAtPath<PerkDefinition>(
                AssetDatabase.GUIDToAssetPath(guid));
            if (def != null) map[def.perk] = def;
        }
        return map;
    }

    static void BuildPerk(PerkType perk, int cost, Vector3 at, float yaw, Transform root,
                          Dictionary<PerkType, PerkDefinition> definitions,
                          List<Vector3> keepOut)
    {
        // Colour from the perk asset, so the machine matches its HUD icon and two
        // sources cannot disagree about what colour Juggernog is.
        var colour = definitions.TryGetValue(perk, out var def)
            ? def.machineColor
            : Color.grey;

        var go = BuildBox("Perk_" + perk, at, new Vector3(0.9f, 1.8f, 0.9f), colour, root, keepOut);
        go.transform.rotation = Quaternion.Euler(0f, yaw, 0f);

        var machine = go.AddComponent<PerkMachine>();
        machine.perk = perk;
        machine.cost = cost;
        machine.requiresPower = true;
    }

    static GameObject BuildBox(string name, Vector3 at, Vector3 size, Color colour, Transform root,
                               List<Vector3> keepOut)
    {
        // Every one of these is solid cover, so none may stand on a spawn point.
        // Spawn points are DERIVED from the bake, so this cannot be checked by
        // reading the coordinate table above -- only by asking the scene.
        if (!MapBake.FindClearSpot(at, size, keepOut, out var clear, out string why))
        {
            Debug.LogError($"[Phase7] {name} at {at} is inside the {MapBake.SpawnKeepOut} m " +
                           "spawn keep-out and nothing clear was found within 6 m. Placed as " +
                           "typed -- fix the coordinate.");
        }
        else if (clear != at)
        {
            Debug.LogWarning($"[Phase7] {name} moved {Vector3.Distance(at, clear):F2} m " +
                             $"from {at} to {clear} because {why}.");
        }
        at = clear;

        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        go.name = name;
        go.transform.SetParent(root, false);
        // `at.y` IS THE FLOOR the machine stands on, and the centre sits half its
        // own height above that.
        //
        // This used to be `at + up * (size.y * 0.5f - 0.9f)`, which lands a base on
        // y = 0 only when the caller happens to pass at.y = 0.9. Every caller did
        // except the Ammo Crate, which passed 0.7 -- putting its centre at 0.15, its
        // base at -0.20, and 29% of the crate under a concrete floor whose walkable
        // top is exactly y = 0. An anchor formula that depends on a magic constant
        // appearing in the caller's data is a trap the data cannot see.
        go.transform.position = at + Vector3.up * (size.y * 0.5f);
        go.transform.localScale = size;

        // Tagged AS IT IS BUILT. Phases 4 and 5 both shipped untagged and every
        // machine in them was unusable with no error anywhere.
        go.tag = InteractionSystem.InteractableTag;

        // An instance material so five machines can be five colours without
        // authoring five assets. Not shared — writing to sharedMaterial here would
        // recolour every box at once.
        var renderer = go.GetComponent<MeshRenderer>();
        var shader = Shader.Find("Universal Render Pipeline/Lit");
        if (shader != null)
        {
            var material = new Material(shader) { name = name + "_Mat" };
            material.SetColor("_BaseColor", colour);
            material.SetFloat("_Smoothness", 0.35f);
            renderer.sharedMaterial = material;
        }
        return go;
    }

    static bool EnsurePlayerHasPerks(Scene scene)
    {
        GameObject player = null;
        foreach (var go in scene.GetRootGameObjects())
            if (go.name == "Player") { player = go; break; }

        if (player == null)
        {
            Debug.LogError("[Phase7] no 'Player' root; perk machines would charge and do nothing.");
            return false;
        }

        if (player.GetComponentInChildren<PlayerPerks>(true) != null) return false;

        // PerkMachine.Interact refunds and refuses when there is no PlayerPerks to
        // grant to, but a map where every perk machine refuses is not a map with
        // perks in it.
        player.AddComponent<PlayerPerks>();
        return true;
    }

    /// <summary>
    /// Adds the scene's PowerUpManager AT THE SCENE ROOT, deliberately NOT under
    /// the Phase7 root.
    ///
    /// <para><b>Because the phase root is the documented undo unit</b> — Phase 4's
    /// builder defines it as "deleting the root undoes the whole pass" — and this
    /// object is not Phase 7's to take away. §772's <c>Announce</c> is the game's
    /// general banner API: Phase 4's PowerSwitch says "POWER ON" through it and
    /// Phase 6's MysteryBox says "THE BOX HAS MOVED" through it. Parented under
    /// Phase7, deleting that root to undo THIS phase silently broke two earlier,
    /// already-signed-off phases, with no error anywhere.</para>
    ///
    /// <para>The other shared additions — WeaponInventory and PlayerPerks on the
    /// Player, and the weapon view models under CameraHolder — are outside every
    /// phase root for the same reason. The build log names them, so the undo story
    /// is stated rather than implied.</para>
    /// </summary>
    static bool EnsurePowerUpManager(Scene scene)
    {
        foreach (var go in scene.GetRootGameObjects())
            if (go.GetComponentInChildren<PowerUpManager>(true) != null) return false;

        var holder = new GameObject("PowerUpManager");
        holder.AddComponent<PowerUpManager>();
        return true;
    }
}
