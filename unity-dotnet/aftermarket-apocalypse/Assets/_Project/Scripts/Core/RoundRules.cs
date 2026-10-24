using UnityEngine;

/// <summary>
/// Every round-scaling number in the game, in one place, as pure functions.
///
/// In the old build these lived as inspector fields on EnemySpawner and EnemyAI,
/// which is exactly how the scene ended up running timeBetweenSpawns = 5 while
/// the script said 2 (spec Part I "READ FIRST"). A serialized value always beats
/// a script default, so the cure is to not have per-instance tuning fields at all
/// for anything that defines the game's pacing.
///
/// The defaults below are the spec's numbers and are what runs with no config
/// asset present. A <see cref="GameConfig"/> may override the tunable ones at
/// boot (spec §18.1) — one shared asset, still exactly one copy of each number.
///
/// Pure C# apart from Mathf, so it is directly unit-testable.
/// </summary>
public static class RoundRules
{
    // ---- overridable tuning (defaults = the spec's numbers) ----

    public static int ZombiesPerRoundMultiplier { get; private set; }
    public static int MaxConcurrentZombies { get; private set; }
    public static float TimeBetweenSpawns { get; private set; }
    public static float WalkSpeed { get; private set; }
    public static float RunSpeed { get; private set; }
    public static float SprintSpeed { get; private set; }
    public static float HealthGrowthPerRound { get; private set; }
    public static int HealthGrowthCapRound { get; private set; }
    public static float CrawlerChance { get; private set; }

    static RoundRules() { ResetToDefaults(); }

    /// <summary>Statics survive scene loads; a config applied in one match must not
    /// leak into the next if that scene has no config.</summary>
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    public static void ResetToDefaults()
    {
        ZombiesPerRoundMultiplier = 5;      // spec §5.2
        MaxConcurrentZombies = 24;          // spec §13.1 defect #1 / §16
        TimeBetweenSpawns = 2f;             // the INTENT; the scene shipped 5
        WalkSpeed = 1.5f;                   // spec §5.1 bands
        RunSpeed = 3.5f;
        SprintSpeed = 6.0f;
        HealthGrowthPerRound = 1.1f;        // +10% compounding
        HealthGrowthCapRound = 50;
        CrawlerChance = 0.12f;              // 12% of spawns
    }

    public static void Override(GameConfig cfg)
    {
        if (cfg == null) return;
        ZombiesPerRoundMultiplier = Mathf.Max(1, cfg.zombiesPerRoundMultiplier);
        MaxConcurrentZombies = Mathf.Max(1, cfg.maxConcurrentZombies);
        TimeBetweenSpawns = Mathf.Max(0.05f, cfg.timeBetweenSpawns);
        WalkSpeed = cfg.walkSpeed;
        RunSpeed = cfg.runSpeed;
        SprintSpeed = cfg.sprintSpeed;
        HealthGrowthPerRound = cfg.healthGrowthPerRound;
        HealthGrowthCapRound = Mathf.Max(1, cfg.healthGrowthCapRound);
        CrawlerChance = Mathf.Clamp01(cfg.crawlerChance);
    }

    // ---- fixed constants ----

    /// <summary>
    /// Essence in hand on the first frame of a match.
    ///
    /// <para>It lives here rather than on the ScoreManager component for the
    /// reason the whole class exists: a serialized field always beats a script
    /// default, so a starting balance typed into a scene is a number nobody can
    /// find and nobody can test. <see cref="ScoreManager"/> reads this in Awake
    /// and its <c>essence</c> field is deliberately NOT serialized, so there is
    /// exactly one copy.</para>
    ///
    /// <para>500 is genre standard and is what makes §12.1's 750 / 1000 doors
    /// cost something: you cannot open either one without first working the
    /// round-one barrier loop (§17 Phase 5 — 7 windows x 6 boards x 10 Essence =
    /// 60 a full rebuild) and killing for it.</para>
    /// </summary>
    public const int StartingEssence = 500;

    /// <summary>Crawlers are slow whatever the round and never run or sprint.</summary>
    public const float CrawlerSpeedMultiplier = 0.6f;

    /// <summary>Sprinters are tinted to this brightness so the horde stays
    /// readable at a glance. Spec §15.11.3 requires any future zombie Theme to
    /// apply UNDER this, never instead of it.</summary>
    public const float SprinterTint = 0.62f;

    /// <summary>
    /// Spec §5.5 records a mismatch: ZombieStabilizer.spawnDuration was 3.5 s
    /// while EnemyAI.standUpTime was 3.0 s, so the AI unfroze half a second
    /// before the model finished rising. "Pick one value." This is it, and both
    /// scripts read it.
    /// </summary>
    public const float ZombieStandUpTime = 3.0f;

    // ---- wave size ----

    /// <summary>Spec §5.2: zombiesPerRound = round * 5.</summary>
    public static int ZombiesForRound(int round)
    {
        if (round < 1) return 0;
        return round * ZombiesPerRoundMultiplier;
    }

    // ---- zombie stats by round (spec §5.1) ----

    public enum Gait { Walk, Run, Sprint }

    public static Gait GaitForRound(int round)
    {
        if (round >= 10) return Gait.Sprint;
        if (round >= 5) return Gait.Run;
        return Gait.Walk;
    }

    /// <summary>1-4 walk 1.5 m/s, 5-9 run 3.5, 10+ sprint 6.0.</summary>
    public static float SpeedForRound(int round)
    {
        switch (GaitForRound(round))
        {
            case Gait.Sprint: return SprintSpeed;
            case Gait.Run: return RunSpeed;
            default: return WalkSpeed;
        }
    }

    /// <summary>Spec §5.1: +10% compounding, capped at round 50 (x106.7 -> 5,335 HP).</summary>
    public static float HealthMultiplierForRound(int round)
    {
        int capped = Mathf.Clamp(round, 1, HealthGrowthCapRound);
        return Mathf.Pow(HealthGrowthPerRound, capped - 1);
    }

    // ---- Soul Crystal drops for surviving deep rounds (spec §15.1) ----
    // Formula carried over verbatim from the retired "Ash Shards" drop; only the
    // currency it pays into changed.

    /// <summary>
    /// 30% at round 10, +3%/round, clamped to 85%.
    /// Spec §10.3 annotates this "caps at R28", but its own formula gives 0.84 at
    /// round 28; the clamp first bites at round 29. The formula wins.
    /// </summary>
    public static float SoulCrystalChance(int finishedRound)
    {
        if (finishedRound < 10) return 0f;
        return Mathf.Min(0.3f + (finishedRound - 10) * 0.03f, 0.85f);
    }

    /// <summary>1-2, plus one more per 20 rounds. <paramref name="roll"/> is the
    /// 1..2 inclusive random component, passed in so this stays testable.</summary>
    public static int SoulCrystalAmount(int finishedRound, int roll)
    {
        return roll + finishedRound / 20;
    }

    // ---- presentation ----

    private static readonly string[] Romans =
    {
        "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
        "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX",
        "XXI", "XXII", "XXIII", "XXIV", "XXV"
    };

    /// <summary>Roman numerals I-XXV, then plain digits (spec §5.2).</summary>
    public static string RoundName(int round)
    {
        if (round > 0 && round < Romans.Length) return Romans[round];
        return round.ToString();
    }
}
