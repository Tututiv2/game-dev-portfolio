using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Phase 4: the two buyable doors and the power switch, plus the lights the
/// switch exists to turn on.
///
/// <para><b>The gameplay was already written.</b> <see cref="BuyableDoor"/>,
/// <see cref="PowerSwitch"/> and <see cref="EnableWithPower"/> all came out of
/// Phase 1 complete, including §14 Law 12 (debris carves rather than bakes) and
/// Law 9 (the DoorDebris layer is OR'd into every camera mask). Phase 4 is
/// therefore placement and wiring, not new systems — this tool puts them where
/// <see cref="MapSeeder"/> deliberately left holes for them.</para>
///
/// <para><b>Additive and reversible</b>, same contract as
/// <see cref="PropDresser"/>: everything lands under one <c>Phase4</c> root, the
/// tool refuses to run if that root exists, and deleting the root undoes the
/// whole pass. Structure, spawn points and props are untouched.</para>
///
/// <para><b>No navmesh rebake, and that is the point of Law 12.</b> The debris
/// sits on the <c>DoorDebris</c> layer, which <c>MapSeeder</c> excluded from the
/// bake (<c>surface.layerMask = ~(1 &lt;&lt; debris)</c>). It blocks with a carving
/// <c>NavMeshObstacle</c> that <c>BuyableDoor.Awake</c> adds, and disables that
/// obstacle the frame you pay. Baking the doors in would mean a full rebake every
/// time one opened.</para>
/// </summary>
public static class Phase4Builder
{
    const string k_Root = "Phase4";

    /// <summary>
    /// §12.1's progression, at this map's scale. The seeder put doorway pieces at
    /// exactly these two cells on the zone divider and nowhere else; the third
    /// opening (zone B to zone C, at z = 9) is deliberately free so the two halves
    /// interconnect rather than being dead ends.
    /// </summary>
    static readonly (string name, float x, float z, int cost)[] k_Doors =
    {
        ("Door_YardToWarehouse", 12f, 18f,  750),
        ("Door_YardToSubstation", 40f, 18f, 1000),
    };

    // Kit_Wall_4_Doorway: legWidth 1.1 either side of a 4 m piece leaves a 1.8 m
    // opening, and ledgeHeight = WallHeight - 2.4 puts the head at 2.4 m. The
    // debris fills exactly that hole and no more, so it reads as blocking the
    // doorway rather than as a slab bolted over a wall.
    const float k_DoorWidth = 1.8f;
    const float k_DoorHeight = 2.4f;
    const float k_DoorDepth = 0.5f;

    /// <summary>Interior of the substation (cells x9..12, z7..10 = 34..50 x 26..42),
    /// on the east wall, well clear of the door at x = 36.</summary>
    static readonly Vector3 k_SwitchPosition = new Vector3(48.6f, 1.35f, 33f);

    /// <summary>
    /// Lights the cascade sweeps on, nearest-first from the switch. Without these
    /// the power switch is a prompt that changes nothing — §12.2 calls the cascade
    /// this game's signature moment, and it needs something to sweep across.
    /// </summary>
    static readonly (string name, Vector3 at)[] k_Lights =
    {
        ("Light_Substation_A",  new Vector3(38f, 3.4f, 30f)),
        ("Light_Substation_B",  new Vector3(46f, 3.4f, 38f)),
        ("Light_Warehouse_A",   new Vector3(8f,  3.4f, 30f)),
        ("Light_Warehouse_B",   new Vector3(16f, 3.4f, 40f)),
        ("Light_GuardOffice",   new Vector3(12f, 3.4f, 10f)),
        ("Light_Yard_West",     new Vector3(8f,  3.6f, 6f)),
        ("Light_Yard_East",     new Vector3(42f, 3.6f, 8f)),
        ("Light_ZoneC_Open",    new Vector3(46f, 3.6f, 20f)),
        ("Light_ZoneB_Open",    new Vector3(6f,  3.6f, 22f)),
    };

    [MenuItem("Tools/Aftermarket/Build Phase 4 (Doors + Power)")]
    public static void Build()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[Phase4] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        // OpenScene from a script does not prompt; it discards unsaved changes and
        // clears the undo stack. Fourth place this guard is needed.
        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[Phase4] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        // Root objects, not GameObject.Find: Find skips inactive objects, so
        // switching the root off to inspect the map would let a second run stack
        // a duplicate set on top.
        foreach (var go in scene.GetRootGameObjects())
        {
            if (go.name != k_Root) continue;
            Debug.LogWarning($"[Phase4] a '{k_Root}' root already exists, so nothing was built. " +
                             "Delete it to run again.");
            return;
        }

        int debrisLayer = LayerMask.NameToLayer(BuyableDoor.DebrisLayerName);
        if (debrisLayer < 0)
        {
            Debug.LogError($"[Phase4] no '{BuyableDoor.DebrisLayerName}' layer in TagManager. " +
                           "Doors would bake into the navmesh as solid floor. Aborting.");
            return;
        }

        var root = new GameObject(k_Root).transform;
        var doorsRoot = new GameObject("Doors").transform;
        doorsRoot.SetParent(root, false);
        var lightsRoot = new GameObject("PoweredLights").transform;
        lightsRoot.SetParent(root, false);

        foreach (var d in k_Doors) BuildDoor(d.name, d.x, d.z, d.cost, doorsRoot, debrisLayer);
        BuildSwitch(root);
        foreach (var l in k_Lights) BuildLight(l.name, l.at, lightsRoot);

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        Debug.Log($"[Phase4] built {k_Doors.Length} buyable doors (750 / 1000), one power switch, " +
                  $"and {k_Lights.Length} powered lights under '{k_Root}'. " +
                  "The navmesh was NOT rebaked and must not be: debris is excluded from the bake " +
                  "and blocks with a carving obstacle (§14 Law 12).");
    }

    static void BuildDoor(string name, float x, float z, int cost, Transform parent, int layer)
    {
        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        go.name = name;
        go.transform.SetParent(parent, false);
        go.transform.position = new Vector3(x, k_DoorHeight * 0.5f, z);
        go.transform.localScale = new Vector3(k_DoorWidth, k_DoorHeight, k_DoorDepth);

        // Set here as well as in BuyableDoor.Awake, so the layer is correct in the
        // saved scene and not only once play mode starts. MapSeeder excluded this
        // layer from the bake, so a door left on Default would be baked as solid
        // floor and the doorway would be impassable before it was ever bought.
        go.layer = layer;

        var material = AssetDatabase.LoadAssetAtPath<Material>(
            "Assets/_Project/Art/Materials/Rusty metal texture/Rusty metal texture.mat");
        if (material != null) go.GetComponent<MeshRenderer>().sharedMaterial = material;

        // Without this tag InteractionSystem.FindTarget returns null and the door
        // can never be bought -- no error, no prompt, nothing.
        go.tag = InteractionSystem.InteractableTag;

        var door = go.AddComponent<BuyableDoor>();
        door.cost = cost;

        // The BoxCollider from CreatePrimitive stays: BuyableDoor disables every
        // collider under itself on purchase, and InteractionSystem raycasts against
        // it to show the prompt.
    }

    static void BuildSwitch(Transform parent)
    {
        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        go.name = "PowerSwitch";
        go.transform.SetParent(parent, false);
        go.transform.position = k_SwitchPosition;
        go.transform.localScale = new Vector3(0.25f, 0.6f, 0.4f);

        var material = AssetDatabase.LoadAssetAtPath<Material>(
            "Assets/_Project/Art/Materials/Diamond Plate Metal 1/Diamond Plate Metal 1.mat");
        if (material != null) go.GetComponent<MeshRenderer>().sharedMaterial = material;

        go.tag = InteractionSystem.InteractableTag;
        go.AddComponent<PowerSwitch>();
    }

    static void BuildLight(string name, Vector3 at, Transform parent)
    {
        // EnableWithPower on the parent, the Light as a CHILD — it collects with
        // GetComponentsInChildren, which includes the object itself, so either
        // works; a child keeps the light's own transform free for aiming later.
        var holder = new GameObject(name);
        holder.transform.SetParent(parent, false);
        holder.transform.position = at;
        holder.AddComponent<EnableWithPower>();

        var lightGo = new GameObject("Point");
        lightGo.transform.SetParent(holder.transform, false);

        var light = lightGo.AddComponent<Light>();
        light.type = LightType.Point;
        light.color = new Color(1f, 0.87f, 0.68f);   // warm sodium, against the dusk key
        light.intensity = 3.2f;
        light.range = 14f;
        light.shadows = LightShadows.None;           // point shadows on nine lights is not free
    }
}
