using System.Collections;
using UnityEngine;
using UnityEngine.AI;

/// <summary>
/// A points-gated door (spec §9, §12.1). Default 750 Essence; the Ashfall
/// progression runs 750 / 1000 / 1250 / 1500 / 1750.
///
/// Three engine-level laws from spec §14 are enforced here rather than left to
/// whoever builds the map:
///
///  LAW 12 — door debris is EXCLUDED from the navmesh bake and blocks via a
///     CARVING NavMeshObstacle, so opening a door re-carves instead of needing a
///     rebake. Both the obstacle and the collider go off immediately on
///     purchase, before the 1.5 s sink animation, so zombies path through the
///     moment you pay.
///
///  LAW 9 — the DoorDebris layer is OR'd into every camera's cullingMask on
///     first use. A non-Everything mask culls the new layer and the doors render
///     INVISIBLE.
///
///  §1.2 — the layer is resolved BY NAME, never by hard-coded index.
/// </summary>
public class BuyableDoor : MonoBehaviour, IInteractable
{
    public const string DebrisLayerName = "DoorDebris";

    public int cost = 750;
    public float sinkDistance = 4f;
    public float sinkDuration = 1.5f;

    /// <summary>Read by ObjectiveManager's first quest step. Reset per match.</summary>
    public static int totalOpened;

    private bool opened;
    private static bool cameraMasksPatched;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    static void ResetStatics()
    {
        totalOpened = 0;
        cameraMasksPatched = false;
    }

    void Awake()
    {
        PatchCameraMasks();

        int layer = LayerMask.NameToLayer(DebrisLayerName);
        if (layer < 0)
        {
            Debug.LogError("BuyableDoor: layer '" + DebrisLayerName + "' does not exist. " +
                           "Doors will bake into the navmesh as solid floor and bullets will " +
                           "pass through them. Add it in Project Settings > Tags and Layers.");
        }
        else
        {
            SetLayerRecursive(gameObject, layer);
        }

        // The block itself is a carving obstacle, not baked geometry (LAW 12).
        NavMeshObstacle obstacle = GetComponent<NavMeshObstacle>();
        if (obstacle == null) obstacle = gameObject.AddComponent<NavMeshObstacle>();
        obstacle.carving = true;
        obstacle.enabled = true;
    }

    /// <summary>LAW 9. Runs once per play session.</summary>
    public static void PatchCameraMasks()
    {
        if (cameraMasksPatched) return;
        int layer = LayerMask.NameToLayer(DebrisLayerName);
        if (layer < 0) return;

        foreach (Camera cam in Camera.allCameras)
            cam.cullingMask |= (1 << layer);

        cameraMasksPatched = true;
    }

    static void SetLayerRecursive(GameObject go, int layer)
    {
        go.layer = layer;
        foreach (Transform child in go.transform) SetLayerRecursive(child.gameObject, layer);
    }

    public string GetPrompt()
    {
        return opened ? null : "Open Door [" + cost + "]";
    }

    public bool Interact(GameObject player)
    {
        if (opened) return false;
        if (!ScoreManager.TrySpend(cost)) return false;

        opened = true;
        totalOpened++;

        // IMMEDIATELY, before the animation — zombies must be able to path
        // through the frame you pay, not 1.5 s later.
        NavMeshObstacle obstacle = GetComponent<NavMeshObstacle>();
        if (obstacle != null) obstacle.enabled = false;
        foreach (Collider c in GetComponentsInChildren<Collider>()) c.enabled = false;

        StartCoroutine(Sink());
        return true;
    }

    IEnumerator Sink()
    {
        Vector3 start = transform.position;
        Vector3 end = start + Vector3.down * sinkDistance;
        float t = 0f;
        while (t < sinkDuration)
        {
            t += Time.deltaTime;
            transform.position = Vector3.Lerp(start, end, t / sinkDuration);
            yield return null;
        }
        transform.position = end;
        gameObject.SetActive(false);
    }
}
