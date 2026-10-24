using System.Collections;
using UnityEngine;

public enum GumType { InPlainSight, MaxAmmo, Perkaholic }

/// <summary>
/// One gum at a time, eaten with the 5 key (spec §6).
///
/// TWO DEFECTS FIXED HERE:
///
///  §13.2 — Perkaholic was a 30-second NO-OP that only wrote to the console. It
///     now grants perks up to PlayerPerks.MaxPerks, which is what Perkaholic
///     does. Note it is NOT timed: the spec table lists "30 s", but that number
///     was the old dead coroutine's duration, not a design decision — perks are
///     kept until death like any other perk. Flagged rather than silently chosen.
///
///  §13.3 — InPlainSight was a walkSpeed += 5 buff, NOT stealth; zombies still
///     tracked you. It now actually makes zombies ignore you, via
///     <see cref="StealthActive"/>, which EnemyAI checks every frame.
/// </summary>
public class GobblegumManager : MonoBehaviour
{
    public static GobblegumManager instance;

    [Header("Durations")]
    public float inPlainSightDuration = 10f;

    public GumType? Held { get; private set; }

    private static float stealthUntil = -1f;

    /// <summary>Read by EnemyAI. While true, zombies do not chase or attack.</summary>
    public static bool StealthActive { get { return Time.time < stealthUntil; } }

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    static void ResetStatics()
    {
        stealthUntil = -1f;
    }

    void Awake()
    {
        if (instance != null && instance != this) { Destroy(this); return; }
        instance = this;
    }

    void OnDestroy()
    {
        if (instance == this) instance = null;
    }

    void Update()
    {
        if (PauseGate.IsPaused) return;
        if (GameInput.SlotPressedThisFrame(4)) Eat();
    }

    public bool Give(GumType gum)
    {
        if (Held.HasValue) return false;      // one at a time
        Held = gum;
        if (PowerUpManager.instance != null)
            PowerUpManager.instance.Announce("GOBBLEGUM: " + Pretty(gum) + "  [5 to eat]");
        return true;
    }

    public void Eat()
    {
        if (!Held.HasValue) return;
        GumType gum = Held.Value;
        Held = null;

        switch (gum)
        {
            case GumType.MaxAmmo:
                AmmoService.RefillAllWeapons();
                Announce("MAX AMMO");
                break;

            case GumType.InPlainSight:
                // Real stealth, not a speed buff. Extends rather than replaces,
                // for the same reason the power-ups do (spec §13.6).
                stealthUntil = Mathf.Max(Time.time, stealthUntil) + inPlainSightDuration;
                Announce("IN PLAIN SIGHT");
                break;

            case GumType.Perkaholic:
                GrantAllPerks();
                break;
        }
    }

    void GrantAllPerks()
    {
        if (PlayerPerks.instance == null) return;
        int granted = 0;
        foreach (PerkType perk in System.Enum.GetValues(typeof(PerkType)))
        {
            if (PlayerPerks.instance.GivePerk(perk)) granted++;
        }
        Announce("PERKAHOLIC  —  +" + granted + " PERK" + (granted == 1 ? "" : "S"));
    }

    static string Pretty(GumType gum)
    {
        switch (gum)
        {
            case GumType.InPlainSight: return "In Plain Sight";
            case GumType.MaxAmmo: return "Max Ammo";
            default: return "Perkaholic";
        }
    }

    static void Announce(string message)
    {
        if (PowerUpManager.instance != null) PowerUpManager.instance.Announce(message);
        else Debug.Log("[Gum] " + message);
    }
}
