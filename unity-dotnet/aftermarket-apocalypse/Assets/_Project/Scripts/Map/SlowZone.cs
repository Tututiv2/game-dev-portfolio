using UnityEngine;

/// <summary>
/// Mud, water, debris — slows the player while they stand in it (spec §9).
///
/// The old version had NO reference counting, so walking out of one overlapping
/// zone into another left you at full speed (or stuck slow). This one counts
/// occupancy per player, so overlapping zones compose correctly.
/// </summary>
[RequireComponent(typeof(Collider))]
public class SlowZone : MonoBehaviour
{
    [Range(0.1f, 1f)] public float slowMultiplier = 0.8f;

    private static int activeZoneCount;
    private static float strongestSlow = 1f;

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    static void ResetStatics()
    {
        activeZoneCount = 0;
        strongestSlow = 1f;
    }

    void Reset()
    {
        GetComponent<Collider>().isTrigger = true;
    }

    void OnTriggerEnter(Collider other)
    {
        FPSController player = other.GetComponentInParent<FPSController>();
        if (player == null) return;

        activeZoneCount++;
        strongestSlow = Mathf.Min(strongestSlow, slowMultiplier);
        player.speedMultiplier = strongestSlow;
    }

    void OnTriggerExit(Collider other)
    {
        FPSController player = other.GetComponentInParent<FPSController>();
        if (player == null) return;

        activeZoneCount = Mathf.Max(0, activeZoneCount - 1);
        if (activeZoneCount == 0)
        {
            strongestSlow = 1f;
            player.speedMultiplier = 1f;
        }
    }
}
