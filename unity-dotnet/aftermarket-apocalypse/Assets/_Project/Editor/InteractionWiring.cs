using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Makes the map's interactables actually interactable. Idempotent — safe to run
/// as often as you like, and it reports doing nothing when there is nothing to do.
///
/// <para><b>Why this exists.</b> Phases 4 and 5 placed two buyable doors, a power
/// switch and seven boarded windows, and <b>not one of them could be used in
/// play</b>. Two separate omissions, each silent:</para>
///
/// <list type="number">
/// <item><c>InteractionSystem.FindTarget</c> returns null unless the collider it
/// hits passes <c>CompareTag("Interactable")</c>. The tag exists in
/// TagManager.asset and nothing in the scene carried it — every prompt was dead
/// before it reached <c>GetComponentInParent&lt;IInteractable&gt;</c>.</item>
/// <item>The <c>Player</c> rig had no <c>InteractionSystem</c> component at all.
/// <c>MapSeeder.BuildPlayerSpawn</c> adds a CharacterController, an FPSController
/// and PlayerHealth, and stops there — so even a correctly tagged door had
/// nothing raycasting at it.</item>
/// </list>
///
/// <para>Both are the shape of bug this project keeps recording: no error, no
/// warning, tests green, and the feature simply does not happen. The guard is
/// <c>MapSceneTests.Map_EveryInteractableIsReachableInPlay</c>, which asserts the
/// component exists and every <c>IInteractable</c> carries the tag on the collider
/// the raycast would actually hit.</para>
/// </summary>
public static class InteractionWiring
{
    [MenuItem("Tools/Aftermarket/Wire Interaction (tags + player component)")]
    public static void Wire()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[InteractionWiring] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[InteractionWiring] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        int tagged = TagInteractables(scene);
        bool addedComponent = EnsurePlayerCanInteract(scene);

        if (tagged == 0 && !addedComponent)
        {
            Debug.Log("[InteractionWiring] nothing to do — every interactable is already tagged " +
                      "and the Player already has an InteractionSystem.");
            return;
        }

        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        Debug.Log($"[InteractionWiring] tagged {tagged} collider(s) as " +
                  $"'{InteractionSystem.InteractableTag}'" +
                  (addedComponent ? " and added InteractionSystem to the Player." : "."));
    }

    /// <summary>
    /// Tags the colliders the raycast will actually hit.
    ///
    /// <para>Tagging the component's own GameObject is not enough on its own: the
    /// raycast tests <c>hit.collider</c>, and <c>FindTarget</c> resolves the script
    /// with <c>GetComponentInParent</c> precisely so a machine can keep its collider
    /// on a child mesh. So every collider at or under an IInteractable gets the tag.</para>
    /// </summary>
    static int TagInteractables(Scene scene)
    {
        int tagged = 0;

        foreach (var root in scene.GetRootGameObjects())
        {
            foreach (var behaviour in root.GetComponentsInChildren<MonoBehaviour>(true))
            {
                if (behaviour is not IInteractable) continue;

                foreach (var collider in behaviour.GetComponentsInChildren<Collider>(true))
                {
                    if (collider.CompareTag(InteractionSystem.InteractableTag)) continue;
                    collider.gameObject.tag = InteractionSystem.InteractableTag;
                    tagged++;
                }
            }
        }
        return tagged;
    }

    static bool EnsurePlayerCanInteract(Scene scene)
    {
        GameObject player = null;
        foreach (var root in scene.GetRootGameObjects())
            if (root.name == "Player") { player = root; break; }

        if (player == null)
        {
            Debug.LogError("[InteractionWiring] no 'Player' root in the map; cannot add " +
                           "InteractionSystem.");
            return false;
        }

        if (player.GetComponentInChildren<InteractionSystem>(true) != null) return false;

        player.AddComponent<InteractionSystem>();
        return true;
    }
}
