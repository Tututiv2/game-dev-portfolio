using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

/// <summary>
/// Give the Player a body: the character you picked, in true first person.
///
/// <para>Idempotent and non-destructive. It adds one component and fills two
/// references; re-running reports rather than replaces. Nothing here spawns the
/// mesh — <see cref="FirstPersonBody"/> does that at runtime from the id
/// CharacterSelect saved, so the scene stores a choice-independent rig and the
/// map does not have to be rebuilt when you change character.</para>
/// </summary>
public static class FirstPersonBodyBuilder
{
    const string k_Catalog = "Assets/_Project/Content/Characters/CharacterCatalog.asset";
    const string k_HandPose = "Assets/_Project/Content/HandPose.asset";

    [MenuItem("Tools/Aftermarket/Build First-Person Body")]
    public static void Build()
    {
        if (!File.Exists(MapSeeder.ScenePath))
        {
            Debug.LogError($"[FirstPersonBody] no scene at {MapSeeder.ScenePath}.");
            return;
        }

        var scene = SceneManager.GetActiveScene();
        if (scene.path != MapSeeder.ScenePath)
        {
            if (!Application.isBatchMode &&
                !EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo())
            {
                Debug.LogWarning("[FirstPersonBody] cancelled — the open scene has unsaved changes.");
                return;
            }
            scene = EditorSceneManager.OpenScene(MapSeeder.ScenePath, OpenSceneMode.Single);
        }

        var catalog = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(k_Catalog);
        if (catalog == null)
        {
            Debug.LogError($"[FirstPersonBody] no CharacterCatalog at {k_Catalog}.");
            return;
        }

        GameObject player = null;
        foreach (var root in scene.GetRootGameObjects())
        {
            var found = root.GetComponentInChildren<FPSController>(true);
            if (found != null) { player = found.gameObject; break; }
        }
        if (player == null)
        {
            Debug.LogError("[FirstPersonBody] no FPSController in the scene, so there is no " +
                           "Player to give a body to.");
            return;
        }

        var fps = player.GetComponent<FPSController>();
        var body = player.GetComponent<FirstPersonBody>();
        bool added = body == null;
        if (added) body = Undo.AddComponent<FirstPersonBody>(player);

        body.catalog = catalog;

        // Created on demand so a fresh checkout does not need the editor window
        // opened once before the hands work.
        var pose = AssetDatabase.LoadAssetAtPath<HandPoseSettings>(k_HandPose);
        if (pose == null)
        {
            pose = ScriptableObject.CreateInstance<HandPoseSettings>();
            AssetDatabase.CreateAsset(pose, k_HandPose);
        }
        body.handPose = pose;

        // The camera holder is READ FROM THE RIG, not created. FPSController
        // already pitches this transform, and a second one would pitch nothing
        // while looking correct in the inspector.
        if (fps.cameraHolder != null) body.cameraHolder = fps.cameraHolder;
        else
        {
            var cam = player.GetComponentInChildren<Camera>(true);
            if (cam != null) body.cameraHolder = cam.transform.parent;
        }

        if (body.cameraHolder == null)
            Debug.LogWarning("[FirstPersonBody] could not find a camera holder on the Player; " +
                             "the body will still spawn but the eye height will be measured " +
                             "against the Player root.");

        EditorUtility.SetDirty(body);
        EditorSceneManager.MarkSceneDirty(scene);
        EditorSceneManager.SaveScene(scene);
        AssetDatabase.SaveAssets();

        Debug.Log($"[FirstPersonBody] {(added ? "added" : "updated")} FirstPersonBody on " +
                  $"'{player.name}', catalog '{catalog.name}' ({catalog.CountNonNull()} characters), " +
                  $"camera holder '{(body.cameraHolder == null ? "none" : body.cameraHolder.name)}', " +
                  $"hand pose '{k_HandPose}' (tune it with Tools > Aftermarket > First-Person Hand Editor). " +
                  "The mesh is spawned at RUNTIME from the character saved by CharacterSelect, so " +
                  "switching character needs no rebuild.");
    }
}
