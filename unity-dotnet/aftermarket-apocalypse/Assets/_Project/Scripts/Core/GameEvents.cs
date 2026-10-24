using UnityEngine;

/// <summary>
/// The static broadcast hub (spec §18.2).
///
/// Singletons stay for true MANAGERS — things you ask a question of. Broadcasts
/// go through here instead. The spec's reasoning: "with 12 perks, 8 power-ups,
/// an economy and co-op, every system grabbing five singletons becomes
/// unmaintainable." A listener that wants to know a zombie died should not have
/// to find EnemySpawner, ScoreManager and CamoSystem to learn it.
///
/// Every event is reset on entering play mode. Static event subscriptions
/// survive scene loads and domain reloads-off; without the reset, listeners from
/// a previous match receive callbacks after their GameObjects are destroyed,
/// which surfaces as MissingReferenceException from a system that "should not
/// even be running".
/// </summary>
public static class GameEvents
{
    // --- match flow ---
    public static event System.Action<int> RoundStarted;
    public static event System.Action<int> RoundEnded;
    public static event System.Action MatchEnded;

    // --- combat ---
    /// <summary>position, wasHeadshot</summary>
    public static event System.Action<Vector3, bool> ZombieKilled;
    public static event System.Action<int> EssenceChanged;
    public static event System.Action<float, float> PlayerHealthChanged;   // current, max

    // --- world ---
    public static event System.Action PowerTurnedOn;
    public static event System.Action<int> DoorOpened;                     // totalOpened
    public static event System.Action<PowerUpType> PowerUpCollected;

    // --- economy (Phase 8 listens; raised from Phase 2 onward) ---
    public static event System.Action<SkinInstance> SkinMinted;
    public static event System.Action<int> EmbersChanged;
    public static event System.Action<int> SoulCrystalsChanged;

    public static void RaiseRoundStarted(int round) { if (RoundStarted != null) RoundStarted(round); }
    public static void RaiseRoundEnded(int round) { if (RoundEnded != null) RoundEnded(round); }
    public static void RaiseMatchEnded() { if (MatchEnded != null) MatchEnded(); }

    public static void RaiseZombieKilled(Vector3 at, bool headshot)
    {
        if (ZombieKilled != null) ZombieKilled(at, headshot);
    }

    public static void RaiseEssenceChanged(int value) { if (EssenceChanged != null) EssenceChanged(value); }
    public static void RaisePlayerHealthChanged(float cur, float max)
    {
        if (PlayerHealthChanged != null) PlayerHealthChanged(cur, max);
    }

    public static void RaisePowerTurnedOn() { if (PowerTurnedOn != null) PowerTurnedOn(); }
    public static void RaiseDoorOpened(int total) { if (DoorOpened != null) DoorOpened(total); }
    public static void RaisePowerUpCollected(PowerUpType t)
    {
        if (PowerUpCollected != null) PowerUpCollected(t);
    }

    public static void RaiseSkinMinted(SkinInstance item) { if (SkinMinted != null) SkinMinted(item); }
    public static void RaiseEmbersChanged(int v) { if (EmbersChanged != null) EmbersChanged(v); }
    public static void RaiseSoulCrystalsChanged(int v) { if (SoulCrystalsChanged != null) SoulCrystalsChanged(v); }

    /// <summary>
    /// Drop every subscriber. Called automatically on entering play mode, and
    /// available to tests so one test's listeners cannot leak into the next.
    /// </summary>
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    public static void ResetAll()
    {
        RoundStarted = null;
        RoundEnded = null;
        MatchEnded = null;
        ZombieKilled = null;
        EssenceChanged = null;
        PlayerHealthChanged = null;
        PowerTurnedOn = null;
        DoorOpened = null;
        PowerUpCollected = null;
        SkinMinted = null;
        EmbersChanged = null;
        SoulCrystalsChanged = null;
    }
}
