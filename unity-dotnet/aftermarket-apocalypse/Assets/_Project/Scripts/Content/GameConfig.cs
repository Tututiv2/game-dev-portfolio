using UnityEngine;

/// <summary>
/// Spec §18.1 / Part I "READ FIRST": the tuning that defines pacing, in ONE
/// asset, so there is exactly one source of truth.
///
/// RoundRules holds the same numbers as compile-time constants and is what the
/// runtime actually reads today. This asset is the authoring surface: assign one
/// in the scene and <see cref="Apply"/> pushes it into RoundRules' overridable
/// slots at boot. That ordering matters — the constants keep the game working
/// with no config present, and the asset can only ever change values that are
/// deliberately exposed.
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Game Config", fileName = "GameConfig")]
public class GameConfig : ScriptableObject
{
    [Header("Waves (spec §5.2)")]
    [Tooltip("zombiesPerRound = round * this.")]
    public int zombiesPerRoundMultiplier = 5;

    [Tooltip("Spec §13.1 defect #1 / §16: the old build had NO cap.")]
    public int maxConcurrentZombies = 24;

    [Tooltip("Script default was 2 s; the shipped scene ran 5 s, halving the game's pacing.")]
    public float timeBetweenSpawns = 2f;

    [Header("Zombies (spec §5.1)")]
    public float walkSpeed = 1.5f;
    public float runSpeed = 3.5f;
    public float sprintSpeed = 6f;
    public float healthGrowthPerRound = 1.1f;
    public int healthGrowthCapRound = 50;
    [Range(0f, 1f)] public float crawlerChance = 0.12f;

    [Header("Power-ups (spec §7)")]
    [Range(0f, 1f)] public float powerUpDropChance = 0.04f;
    public float powerUpBuffDuration = 30f;
    public float powerUpOrbLifetime = 20f;

    void OnValidate()
    {
        maxConcurrentZombies = Mathf.Max(1, maxConcurrentZombies);
        timeBetweenSpawns = Mathf.Max(0.05f, timeBetweenSpawns);
        healthGrowthCapRound = Mathf.Max(1, healthGrowthCapRound);
    }

    public void Apply()
    {
        RoundRules.Override(this);
    }
}
