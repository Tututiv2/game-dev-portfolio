using System.Collections.Generic;
using UnityEngine;

public enum PerkType
{
    Juggernog,
    SpeedCola,
    DoubleTap,
    StaminUp,
    QuickRevive
}

/// <summary>
/// Perk ownership and every perk multiplier (spec §3.3).
/// Max 4, not persisted, lost on death — that is the BO2 contract and is
/// deliberate (spec §13.13 notes they are not persisted; that is design, not a
/// bug, and the reward for a deep run is banked Embers instead).
/// </summary>
public class PlayerPerks : MonoBehaviour
{
    public static PlayerPerks instance;

    public const int MaxPerks = 4;

    [Header("Perk values (spec §3.3)")]
    public float juggernogHealth = 250f;
    public float speedColaMultiplier = 0.5f;    // x reload time
    public float doubleTapDamage = 2.0f;        // x damage, applied AFTER the headshot multiplier
    public float doubleTapFireRate = 0.75f;     // x interval => +33% RPM
    public float staminUpMultiplier = 1.3f;     // x sprint speed

    private readonly List<PerkType> owned = new List<PerkType>();

    public IReadOnlyList<PerkType> Owned { get { return owned; } }
    public int PerkCount { get { return owned.Count; } }

    void Awake()
    {
        if (instance != null && instance != this) { Destroy(this); return; }
        instance = this;
    }

    void OnDestroy()
    {
        if (instance == this) instance = null;
    }

    public bool HasPerk(PerkType perk)
    {
        return owned.Contains(perk);
    }

    /// <summary>
    /// The 4-slot cap. Spec §3.3: GivePerk only ever guarded duplicates, so the
    /// machine had to remember to call this itself — easy to forget on the fifth
    /// machine somebody adds. GivePerk now enforces it too.
    /// </summary>
    public bool CanBuyPerk(PerkType perk)
    {
        return !HasPerk(perk) && owned.Count < MaxPerks;
    }

    public bool GivePerk(PerkType perk)
    {
        if (!CanBuyPerk(perk)) return false;
        owned.Add(perk);

        if (perk == PerkType.Juggernog)
        {
            PlayerHealth health = GetComponent<PlayerHealth>();
            if (health == null) health = FindFirstObjectByType<PlayerHealth>();
            if (health != null) health.ApplyJuggernog(juggernogHealth);
        }
        return true;
    }

    /// <summary>Cheat death once. Consumes the perk. Called by PlayerHealth.Die().</summary>
    public bool UseQuickRevive()
    {
        if (!HasPerk(PerkType.QuickRevive)) return false;
        owned.Remove(PerkType.QuickRevive);
        return true;
    }

    // ---- multiplier queries, null-safe so callers do not need to guard ----

    public static float ReloadMultiplier()
    {
        if (instance == null || !instance.HasPerk(PerkType.SpeedCola)) return 1f;
        return instance.speedColaMultiplier;
    }

    public static float FireIntervalMultiplier()
    {
        if (instance == null || !instance.HasPerk(PerkType.DoubleTap)) return 1f;
        return instance.doubleTapFireRate;
    }

    public static bool HasDoubleTap()
    {
        return instance != null && instance.HasPerk(PerkType.DoubleTap);
    }

    public static float DoubleTapDamage()
    {
        return instance != null ? instance.doubleTapDamage : 2f;
    }
}
