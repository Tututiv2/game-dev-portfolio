using System.Collections.Generic;
using System.Linq;
using Unity.AI.Navigation;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Every gameplay object a round-based map needs, built to this project's
/// conventions, for ANY map.
///
/// <para>Phases 4 to 7 each build these correctly and each hard-code
/// <c>AshfallDepot</c>'s coordinates, so none of them can serve a second map. The
/// knowledge that matters is not the coordinates — it is that a door must sit on
/// the <c>DoorDebris</c> layer or it bakes into the navmesh as solid floor, that
/// nothing is interactable until its COLLIDER carries the tag, and that a barrier
/// needs a NavMeshLink across the sill. That is what this file carries.</para>
/// </summary>
public static class MapProps
{
    const string k_RustyMetal = "Assets/_Project/Art/Materials/Rusty metal texture/Rusty metal texture.mat";
    const string k_DiamondPlate = "Assets/_Project/Art/Materials/Diamond Plate Metal 1/Diamond Plate Metal 1.mat";
    const string k_Grunge = "Assets/_Project/Art/Materials/Grunge texture/Grunge texture.mat";

    static Material Mat(string path) { return AssetDatabase.LoadAssetAtPath<Material>(path); }

    /// <summary>A tagged, collidable primitive — the shape every machine here shares.</summary>
    static GameObject Block(string name, Transform parent, Vector3 at, Vector3 scale,
                            float yaw, string materialPath, Color? tint = null)
    {
        var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
        go.name = name;
        go.transform.SetParent(parent, false);
        go.transform.position = at;
        go.transform.rotation = Quaternion.Euler(0f, yaw, 0f);
        go.transform.localScale = scale;

        var mr = go.GetComponent<MeshRenderer>();
        if (tint.HasValue)
        {
            // A real material asset, NOT a MaterialPropertyBlock over the metal
            // texture. _BaseColor MULTIPLIES the albedo map, and the diamond-plate
            // albedo is nearly black, so five colour-coded perk machines rendered as
            // five identical black boxes. Colour is how you find a perk across a
            // dark room, so it has to survive the lighting: its own material, and
            // emissive, so the machine is a lamp rather than a thing a lamp lights.
            mr.sharedMaterial = MachineMaterial(name, tint.Value);
        }
        else
        {
            var m = materialPath == null ? null : Mat(materialPath);
            if (m != null) mr.sharedMaterial = m;
        }

        // Without this tag InteractionSystem.FindTarget returns null and the object
        // is scenery: no prompt, no purchase, no error.
        go.tag = InteractionSystem.InteractableTag;
        return go;
    }


    const string k_MachineMaterialFolder = "Assets/_Project/Art/Materials/Machines";

    /// <summary>An emissive material asset per machine colour, created on demand.</summary>
    public static Material MachineMaterial(string name, Color tint, float emission = 0.5f)
    {
        System.IO.Directory.CreateDirectory(k_MachineMaterialFolder);
        string safe = new string(name.Select(c => char.IsLetterOrDigit(c) ? c : '_').ToArray());
        string path = $"{k_MachineMaterialFolder}/M_{safe}.mat";

        var mat = AssetDatabase.LoadAssetAtPath<Material>(path);
        if (mat == null)
        {
            mat = new Material(Shader.Find("Universal Render Pipeline/Lit"));
            AssetDatabase.CreateAsset(mat, path);
        }
        mat.SetColor("_BaseColor", tint);
        mat.SetFloat("_Metallic", 0.1f);
        mat.SetFloat("_Smoothness", 0.35f);
        mat.EnableKeyword("_EMISSION");
        mat.globalIlluminationFlags = MaterialGlobalIlluminationFlags.RealtimeEmissive;
        // 1.6 was the first guess and every machine rendered as a white slab:
        // emission above 1 clips through the tonemapper, so the colour coding - the
        // entire point of tinting them - was gone. Glow enough to find in the dark,
        // not enough to lose the hue.
        mat.SetColor("_EmissionColor", tint * emission);
        EditorUtility.SetDirty(mat);
        return mat;
    }

    // ---- doors and power --------------------------------------------------

    public const float DoorWidth = 4f;
    public const float DoorHeight = 3.2f;
    public const float DoorDepth = 0.35f;

    /// <summary>
    /// A buyable door filling one cell edge.
    ///
    /// <para>The <c>DoorDebris</c> layer is not decoration. The navmesh bake excludes
    /// that layer, so a door left on Default bakes as solid floor and its doorway is
    /// impassable <i>before anyone buys it</i> — §14 Law 12.</para>
    /// </summary>
    public static GameObject Door(string name, Transform parent, Vector3 at, float yaw, int cost)
    {
        int layer = LayerMask.NameToLayer(BuyableDoor.DebrisLayerName);
        if (layer < 0)
        {
            Debug.LogError($"[MapProps] no '{BuyableDoor.DebrisLayerName}' layer in the TagManager. " +
                           "A door built now would bake into the navmesh as solid floor.");
            return null;
        }

        var go = Block(name, parent, at + Vector3.up * (DoorHeight * 0.5f),
                       new Vector3(DoorWidth, DoorHeight, DoorDepth), yaw, k_RustyMetal);
        go.layer = layer;
        var door = go.AddComponent<BuyableDoor>();
        door.cost = cost;
        return go;
    }

    public static GameObject PowerSwitchAt(string name, Transform parent, Vector3 at, float yaw)
    {
        var go = Block(name, parent, at, new Vector3(0.25f, 0.6f, 0.4f), yaw, k_DiamondPlate);
        go.AddComponent<PowerSwitch>();
        return go;
    }

    public static GameObject PoweredLight(string name, Transform parent, Vector3 at,
                                          float range = 14f, float intensity = 3.2f)
    {
        var holder = new GameObject(name);
        holder.transform.SetParent(parent, false);
        holder.transform.position = at;
        holder.AddComponent<EnableWithPower>();

        var lightGo = new GameObject("Point");
        lightGo.transform.SetParent(holder.transform, false);
        var light = lightGo.AddComponent<Light>();
        light.type = LightType.Point;
        light.color = new Color(1f, 0.87f, 0.68f);
        light.intensity = intensity;
        light.range = range;
        light.shadows = LightShadows.None;
        return holder;
    }

    /// <summary>An always-on lamp, for the rooms you can reach before the power.</summary>
    public static GameObject Lamp(string name, Transform parent, Vector3 at,
                                  float range = 12f, float intensity = 1.6f)
    {
        var go = new GameObject(name);
        go.transform.SetParent(parent, false);
        go.transform.position = at;
        var light = go.AddComponent<Light>();
        light.type = LightType.Point;
        light.color = new Color(0.75f, 0.82f, 1f);
        light.intensity = intensity;
        light.range = range;
        light.shadows = LightShadows.None;
        return go;
    }

    // ---- machines ---------------------------------------------------------

    public static List<WeaponDefinition> WeaponDefinitions()
    {
        var list = new List<WeaponDefinition>();
        foreach (var guid in AssetDatabase.FindAssets("t:WeaponDefinition"))
        {
            var def = AssetDatabase.LoadAssetAtPath<WeaponDefinition>(
                AssetDatabase.GUIDToAssetPath(guid));
            if (def != null) list.Add(def);
        }
        list.Sort((a, b) => string.CompareOrdinal(a.name, b.name));
        return list;
    }

    public static GameObject WallBuyAt(string name, Transform parent, Vector3 at, float yaw,
                                     int slot, List<WeaponDefinition> definitions)
    {
        var go = Block(name, parent, at, new Vector3(1.1f, 0.55f, 0.06f), yaw, k_Grunge);

        // Seated against the wall it is painted on, rather than trusted to a typed
        // coordinate. 6 cm of chalk blocks nothing, so no keep-out applies.
        if (MapBake.SeatOnWall(at, yaw, 0.03f, out var seated, out var facing))
        {
            go.transform.position = seated;
            go.transform.rotation = facing;
        }
        else
        {
            Debug.LogWarning($"[MapProps] wallbuy {name} at {at} found no wall within 3 m — " +
                             "it is hanging in open air.");
        }

        var buy = go.AddComponent<WallBuy>();
        buy.weaponSlot = slot;
        if (slot >= 0 && slot < definitions.Count) buy.definition = definitions[slot];
        return go;
    }

    public static MysteryBox BoxAt(string name, Transform parent, Vector3 at, List<Vector3> keepOut)
    {
        var size = new Vector3(1.2f, 1.2f, 0.8f);
        if (!MapBake.FindClearSpot(at, size, keepOut, out var clear, out string why))
        {
            Debug.LogError($"[MapProps] mystery box {name} at {at} is unusable ({why}).");
            clear = at;
        }
        var go = Block(name, parent, clear, size, 0f, k_RustyMetal,
                       new Color(0.85f, 0.72f, 0.35f));
        return go.AddComponent<MysteryBox>();
    }

    public static GameObject PerkMachineAt(PerkType perk, int cost, Transform parent,
                                           Vector3 at, float yaw, List<Vector3> keepOut)
    {
        var size = new Vector3(1.1f, 2.0f, 0.8f);
        if (!MapBake.FindClearSpot(at + Vector3.up * (size.y * 0.5f), size, keepOut,
                                   out var clear, out string why))
        {
            Debug.LogWarning($"[MapProps] perk {perk} at {at}: {why}");
            clear = at + Vector3.up * (size.y * 0.5f);
        }

        var go = Block($"Perk_{perk}", parent, clear, size, yaw, k_DiamondPlate,
                       PerkColour(perk));
        var machine = go.AddComponent<PerkMachine>();
        machine.perk = perk;
        machine.cost = cost;
        machine.requiresPower = true;
        return go;
    }

    /// <summary>Falls back to a readable colour when the PerkDefinition has none —
    /// five identical grey boxes is not a map you can learn.</summary>
    public static Color PerkColour(PerkType perk)
    {
        foreach (var guid in AssetDatabase.FindAssets("t:PerkDefinition"))
        {
            var def = AssetDatabase.LoadAssetAtPath<PerkDefinition>(
                AssetDatabase.GUIDToAssetPath(guid));
            if (def != null && def.perk == perk && def.machineColor.a > 0f)
                return def.machineColor;
        }
        switch (perk)
        {
            case PerkType.Juggernog: return new Color(0.75f, 0.15f, 0.15f);
            case PerkType.SpeedCola: return new Color(0.20f, 0.70f, 0.25f);
            case PerkType.DoubleTap: return new Color(0.85f, 0.55f, 0.10f);
            case PerkType.StaminUp: return new Color(0.95f, 0.85f, 0.20f);
            default: return new Color(0.25f, 0.55f, 0.90f);
        }
    }

    public static GameObject PackAPunch(Transform parent, Vector3 at, float yaw,
                                        List<Vector3> keepOut)
    {
        var size = new Vector3(1.6f, 1.8f, 1.0f);
        MapBake.FindClearSpot(at + Vector3.up * (size.y * 0.5f), size, keepOut,
                              out var clear, out _);
        var go = Block("PackAPunch", parent, clear, size, yaw, k_DiamondPlate,
                       new Color(0.45f, 0.20f, 0.65f));
        var pap = go.AddComponent<PackAPunchMachine>();
        pap.cost = 5000;
        pap.requiresPower = true;
        return go;
    }

    public static GameObject AmmoCrateAt(Transform parent, Vector3 at, float yaw,
                                         List<Vector3> keepOut)
    {
        var size = new Vector3(1.2f, 0.8f, 0.8f);
        MapBake.FindClearSpot(at + Vector3.up * (size.y * 0.5f), size, keepOut,
                              out var clear, out _);
        var go = Block("AmmoCrate", parent, clear, size, yaw, k_RustyMetal,
                       new Color(0.35f, 0.55f, 0.30f));
        go.AddComponent<AmmoCrate>().cost = 1000;
        return go;
    }

    public static GameObject GobblegumAt(Transform parent, Vector3 at, float yaw,
                                         List<Vector3> keepOut)
    {
        var size = new Vector3(0.7f, 1.6f, 0.7f);
        MapBake.FindClearSpot(at + Vector3.up * (size.y * 0.5f), size, keepOut,
                              out var clear, out _);
        var go = Block("GobblegumMachine", parent, clear, size, yaw, k_DiamondPlate,
                       new Color(0.90f, 0.45f, 0.70f));
        go.AddComponent<GobblegumMachine>().cost = 500;
        return go;
    }

    // ---- barriers ---------------------------------------------------------

    const float k_SillHeight = 1.0f;
    const float k_HeadHeight = 2.6f;
    const float k_OpeningWidth = 2.5f;

    /// <summary>
    /// Boards a <c>Kit_Wall_4_Window</c>, deriving the barrier's transform from the
    /// piece rather than from a typed coordinate, so moving the window moves the
    /// barrier with it.
    /// </summary>
    public static WindowBarrier Barrier(Transform window, Transform parent, int index)
    {
        var go = new GameObject($"Barrier_{index:00}");
        go.transform.SetParent(parent, false);
        go.transform.position = window.position +
                                Vector3.up * ((k_SillHeight + k_HeadHeight) * 0.5f);
        go.transform.rotation = window.rotation;

        var barrier = go.AddComponent<WindowBarrier>();
        var boardMaterial = Mat(k_Grunge);

        float span = k_HeadHeight - k_SillHeight;
        float step = span / (barrier.maxBoards + 1);
        for (int i = 0; i < barrier.maxBoards; i++)
        {
            var board = GameObject.CreatePrimitive(PrimitiveType.Cube);
            board.name = $"Board_{i:00}";
            board.transform.SetParent(go.transform, false);
            board.transform.localPosition = new Vector3(0f, -span * 0.5f + step * (i + 1), 0f);
            board.transform.localRotation = Quaternion.Euler(0f, 0f, i % 2 == 0 ? 4f : -3f);
            board.transform.localScale = new Vector3(k_OpeningWidth, 0.12f, 0.08f);
            if (boardMaterial != null) board.GetComponent<MeshRenderer>().sharedMaterial = boardMaterial;

            // No collider: the planks are visual state, and a zombie forced to shoot
            // through its own barrier geometry is a bug.
            Object.DestroyImmediate(board.GetComponent<BoxCollider>());
        }

        // One trigger for the whole opening, so a half-torn window can still be
        // aimed at — which is exactly when you need to repair it.
        var box = go.AddComponent<BoxCollider>();
        box.size = new Vector3(k_OpeningWidth, k_HeadHeight - k_SillHeight, 0.5f);
        box.isTrigger = true;
        go.tag = InteractionSystem.InteractableTag;

        var link = go.AddComponent<NavMeshLink>();
        link.startPoint = new Vector3(0f, -(k_SillHeight + k_HeadHeight) * 0.5f, -1.2f);
        link.endPoint = new Vector3(0f, -(k_SillHeight + k_HeadHeight) * 0.5f, 1.2f);
        link.width = k_OpeningWidth * 0.8f;
        link.bidirectional = true;
        link.enabled = false;
        return barrier;
    }

    /// <summary>Every <c>Kit_Wall_4_Window</c> instance root in the open scene.</summary>
    public static List<Transform> FindWindows(UnityEngine.SceneManagement.Scene scene)
    {
        var found = new List<Transform>();
        foreach (var root in scene.GetRootGameObjects())
            foreach (var t in root.GetComponentsInChildren<Transform>(true))
            {
                var source = PrefabUtility.GetCorrespondingObjectFromSource(t.gameObject);
                if (source == null || !source.name.StartsWith("Kit_Wall_4_Window")) continue;
                if (PrefabUtility.GetOutermostPrefabInstanceRoot(t.gameObject) != t.gameObject) continue;
                found.Add(t);
            }
        return found;
    }
}
