using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// Spec §18.6: "Pool everything that spawns repeatedly — zombies, popups,
/// tracers, casings, gibs, decals, kill-feed rows, case-spinner cards."
///
/// Spec §13.14 records that the old build pooled ONLY blood decals; everything
/// else called Instantiate. At round 25 that is 125 zombie instantiations plus a
/// tracer, a casing and a popup per shot. §15.8.1 adds a hard case: the Sacred
/// reel is ~30 cards per open and "30 instantiations per open is a stutter".
///
/// Prewarm is deliberate. A pool that grows lazily still spikes on the frame the
/// horde arrives, which is exactly the frame that must not spike.
/// </summary>
public class ObjectPool
{
    private readonly GameObject prefab;
    private readonly Transform parent;
    private readonly Stack<GameObject> idle = new Stack<GameObject>();
    private readonly int maxRetained;

    public int IdleCount { get { return idle.Count; } }
    public int TotalCreated { get; private set; }

    public ObjectPool(GameObject prefab, int prewarm = 0, int maxRetained = 256, Transform parent = null)
    {
        this.prefab = prefab;
        this.parent = parent;
        this.maxRetained = Mathf.Max(1, maxRetained);

        for (int i = 0; i < prewarm; i++)
        {
            GameObject go = Create();
            go.SetActive(false);
            idle.Push(go);
        }
    }

    private GameObject Create()
    {
        TotalCreated++;
        GameObject go = Object.Instantiate(prefab, parent);
        return go;
    }

    public GameObject Get(Vector3 position, Quaternion rotation)
    {
        GameObject go = null;
        // Destroyed-while-idle is normal across scene loads; skip the corpses.
        while (idle.Count > 0 && go == null) go = idle.Pop();
        if (go == null) go = Create();

        go.transform.SetPositionAndRotation(position, rotation);
        go.SetActive(true);
        return go;
    }

    /// <summary>
    /// Return an object. Beyond maxRetained it is destroyed instead of retained —
    /// an unbounded pool that only ever grows is a memory leak wearing a
    /// performance-optimisation hat.
    /// </summary>
    public void Release(GameObject go)
    {
        if (go == null) return;
        go.SetActive(false);
        if (idle.Count >= maxRetained)
        {
            DestroyObject(go);
            return;
        }
        if (parent != null) go.transform.SetParent(parent, false);
        idle.Push(go);
    }

    public void Clear()
    {
        while (idle.Count > 0)
        {
            GameObject go = idle.Pop();
            DestroyObject(go);
        }
    }

    /// <summary>
    /// Object.Destroy is deferred to end-of-frame and is an ERROR outside play
    /// mode. The pool is exercised from EditMode tests and is usable from editor
    /// tooling, so it must pick the right one rather than assuming it is running
    /// in a game.
    /// </summary>
    private static void DestroyObject(GameObject go)
    {
        if (go == null) return;
        if (Application.isPlaying) Object.Destroy(go);
        else Object.DestroyImmediate(go);
    }
}
