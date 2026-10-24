using UnityEngine;

/// <summary>
/// Animates the _BaseMap UV offset on animated camo materials (spec §4.3).
///
/// Matched with Contains() rather than equality because runtime material
/// instances get a " (Instance)" suffix — that is the reason the old code used
/// Contains, and removing it silently kills the effect.
///
/// Spec §19 / §15.7: this becomes the RGB (Secret RGB) animated-variant driver
/// in Phase 8. It already does the job; it just needs pointing at more materials.
/// </summary>
public class SmartCamoScroller : MonoBehaviour
{
    [Tooltip("Material name fragments that should scroll.")]
    public string[] animatedNameFragments = { "Cosmic", "RGB" };

    public Vector2 scrollSpeed = new Vector2(0.06f, 0.03f);

    private Renderer[] targets;
    private MaterialPropertyBlock block;
    private Vector2 offset;

    void Start()
    {
        block = new MaterialPropertyBlock();
        var found = new System.Collections.Generic.List<Renderer>();
        foreach (Renderer r in GetComponentsInChildren<Renderer>(true))
        {
            if (r.sharedMaterial == null) continue;
            if (Matches(r.sharedMaterial.name)) found.Add(r);
        }
        targets = found.ToArray();
        enabled = targets.Length > 0;
    }

    bool Matches(string materialName)
    {
        foreach (string fragment in animatedNameFragments)
            if (!string.IsNullOrEmpty(fragment) && materialName.Contains(fragment)) return true;
        return false;
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;
        if (targets == null || targets.Length == 0) return;

        offset += scrollSpeed * Time.deltaTime;
        offset.x %= 1f;
        offset.y %= 1f;

        // MaterialPropertyBlock, not .material — costs nothing, preserves SRP
        // batching and leaks no material instances (spec §15.11.1).
        foreach (Renderer r in targets)
        {
            if (r == null) continue;
            r.GetPropertyBlock(block);
            block.SetVector("_BaseMap_ST", new Vector4(1f, 1f, offset.x, offset.y));
            r.SetPropertyBlock(block);
        }
    }

    /// <summary>Re-scan after a camo change, since ApplyCamo swaps materials live.</summary>
    public void Rescan()
    {
        Start();
    }
}
