using System.Collections.Generic;
using System.IO;
using Unity.AI.Navigation;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Phase 5: boarded windows on every <c>Kit_Wall_4_Window</c> in the map.
///
/// <para>Additive under one <c>Phase5</c> root, refuses to re-run, same contract
/// as <see cref="PropDresser"/> and <see cref="Phase4Builder"/>.</para>
///
/// <para><b>Barriers are FOUND, not typed.</b> The window pieces are placed by
/// <see cref="MapSeeder"/> and there are seven of them; writing seven sets of
/// coordinates by hand would be a second source of truth that drifts the moment
/// the map changes. This walks the scene for instances of the window prefab and
/// derives each barrier's transform from the piece it sits in, so moving a window
/// by hand moves its barrier with it on the next run.</para>
/// </summary>
public static class Phase5Builder
{
    const string k_Root = "Phase5";

    /// <summary>Kit_Wall_4_Window's opening: sill top 1.0 m, head 2.6 m, and the
    /// jambs leave <c>Grid - 0.75*2</c> = 2.5 m of clear width. Boards span it.</summary>
    const float k_SillHeight = 1.0f;
    const float k_HeadHeight = 2.6f;
    const float k_OpeningWidth = 2.5f;

    [MenuItem("Tools/Aftermarket/Build Phase 5 (Window Barriers)")]
    public static void Build()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[Phase5] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[Phase5] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        foreach (var go in scene.GetRootGameObjects())
        {
            if (go.name != k_Root) continue;
            Debug.LogWarning($"[Phase5] a '{k_Root}' root already exists, so nothing was built. " +
                             "Delete it to run again.");
            return;
        }

        var windows = FindWindowPieces(scene);
        if (windows.Count == 0)
        {
            Debug.LogError("[Phase5] found no Kit_Wall_4_Window instances in the map. " +
                           "Nothing to board up.");
            return;
        }

        var root = new GameObject(k_Root).transform;
        var boardMaterial = AssetDatabase.LoadAssetAtPath<Material>(
            "Assets/_Project/Art/Materials/Grunge texture/Grunge texture.mat");

        int built = 0;
        foreach (var window in windows)
        {
            BuildBarrier(window, root, boardMaterial, built);
            built++;
        }

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        Debug.Log($"[Phase5] boarded {built} windows under '{k_Root}', 6 boards each " +
                  $"({WindowBarrier.RewardPerBoard} Essence per board). Each carries a " +
                  "NavMeshLink across the sill, disabled until the last board is torn off. " +
                  "The navmesh was NOT rebaked — the links are runtime routes, not baked geometry.");
    }

    static List<Transform> FindWindowPieces(Scene scene)
    {
        var found = new List<Transform>();
        foreach (var root in scene.GetRootGameObjects())
        {
            foreach (var t in root.GetComponentsInChildren<Transform>(true))
            {
                var source = PrefabUtility.GetCorrespondingObjectFromSource(t.gameObject);
                if (source == null) continue;
                if (!source.name.StartsWith("Kit_Wall_4_Window")) continue;

                // Only the instance ROOT, not its four child boxes.
                if (PrefabUtility.GetOutermostPrefabInstanceRoot(t.gameObject) != t.gameObject) continue;
                found.Add(t);
            }
        }
        return found;
    }

    static void BuildBarrier(Transform window, Transform parent, Material boardMaterial, int index)
    {
        var go = new GameObject($"Barrier_{index:00}");
        go.transform.SetParent(parent, false);

        // Sit the barrier in the middle of the opening and inherit the wall's yaw,
        // so a window rotated 90 degrees gets boards across it and not through it.
        go.transform.position = window.position +
                                Vector3.up * ((k_SillHeight + k_HeadHeight) * 0.5f);
        go.transform.rotation = window.rotation;

        var barrier = go.AddComponent<WindowBarrier>();

        // Six horizontal planks across the opening, named so WindowBarrier can find
        // and toggle them.
        float span = k_HeadHeight - k_SillHeight;
        float step = span / (barrier.maxBoards + 1);
        for (int i = 0; i < barrier.maxBoards; i++)
        {
            var board = GameObject.CreatePrimitive(PrimitiveType.Cube);
            board.name = $"Board_{i:00}";
            board.transform.SetParent(go.transform, false);
            board.transform.localPosition = new Vector3(
                0f, -span * 0.5f + step * (i + 1), 0f);
            board.transform.localRotation = Quaternion.Euler(0f, 0f, i % 2 == 0 ? 4f : -3f);
            board.transform.localScale = new Vector3(k_OpeningWidth, 0.12f, 0.08f);

            if (boardMaterial != null)
                board.GetComponent<MeshRenderer>().sharedMaterial = boardMaterial;

            // No collider on the planks. They are visual state, and a zombie that
            // has to shoot through its own barrier geometry is a bug; the barrier's
            // own trigger below is what the player interacts with.
            Object.DestroyImmediate(board.GetComponent<BoxCollider>());
        }

        // One collider for the whole barrier so InteractionSystem's raycast has
        // something to hit even when boards are missing — otherwise you could not
        // aim at a half-torn window to repair it, which is precisely when you need to.
        var box = go.AddComponent<BoxCollider>();
        box.size = new Vector3(k_OpeningWidth, k_HeadHeight - k_SillHeight, 0.5f);
        box.isTrigger = true;

        // InteractionSystem raycasts with QueryTriggerInteraction.Collide, so the
        // trigger is hit -- but FindTarget then rejects anything not carrying this
        // tag. Untagged, the barrier is scenery and the round-one loop is dead.
        go.tag = InteractionSystem.InteractableTag;

        // The route through the window: outside to inside, across the sill.
        // Disabled by WindowBarrier.Awake until the last board comes off.
        var link = go.AddComponent<NavMeshLink>();
        link.startPoint = new Vector3(0f, -(k_SillHeight + k_HeadHeight) * 0.5f, -1.2f);
        link.endPoint = new Vector3(0f, -(k_SillHeight + k_HeadHeight) * 0.5f, 1.2f);
        link.width = k_OpeningWidth * 0.8f;
        link.bidirectional = true;
        link.enabled = false;
    }
}
