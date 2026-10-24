package com.skyclone.core.items;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/**
 * Every tunable of the items core (items, stats, vitals, abilities). Read with {@code .get()} at
 * USE time so {@code /sb dial set} takes effect live.
 */
public final class ItemsDials {

    private ItemsDials() {}

    // ---- items -----------------------------------------------------------------------------
    public static Dials.Dial<Boolean> CONVERT_VANILLA;
    public static Dials.Dial<Boolean> VANILLA_GUARDS;
    public static Dials.Dial<Integer> DUPE_SCAN_SECONDS;
    public static Dials.Dial<Boolean> DUPE_DELETE;
    public static Dials.Dial<Boolean> DUPE_DELETE_CROSS_PLAYER;
    public static Dials.Dial<Integer> ENCHANT_DETAIL_MAX;

    // ---- stats -----------------------------------------------------------------------------
    public static Dials.Dial<Integer> STAT_TICKS;
    public static Dials.Dial<Double> SPEED_CAP;
    public static Dials.Dial<Double> SWING_RANGE_CAP;
    public static Dials.Dial<Double> ATTACK_SPEED_BASE;
    public static Dials.Dial<Boolean> APPLY_ATTRIBUTES;

    // ---- vitals ----------------------------------------------------------------------------
    public static Dials.Dial<Double> REGEN_SECONDS;
    public static Dials.Dial<Double> REGEN_MULT;
    public static Dials.Dial<Double> REGEN_OUT_OF_COMBAT_MULT;
    public static Dials.Dial<Double> COMBAT_SECONDS;
    public static Dials.Dial<Double> MANA_REGEN_PCT;
    public static Dials.Dial<Double> DEATH_COIN_LOSS;
    public static Dials.Dial<Boolean> DEATH_SAFE_ON_PRIVATE_ISLAND;
    public static Dials.Dial<Boolean> DISABLE_HUNGER;
    public static Dials.Dial<Double> HEART_SCALE_MAX;
    public static Dials.Dial<Boolean> ACTION_BAR;
    public static Dials.Dial<Boolean> VANILLA_DAMAGE_NET;

    // ---- abilities -------------------------------------------------------------------------
    public static Dials.Dial<Integer> ABILITY_FLASH_TICKS;
    public static Dials.Dial<Boolean> ABILITIES_ENABLED;

    static void register() {
        Dials d = Sky.dials();
        CONVERT_VANILLA = d.flag("items.convert-vanilla", true,
                "Turn vanilla items into the sky item of the same id when they enter a player's inventory");
        VANILLA_GUARDS = d.flag("items.vanilla-guards", true,
                "Stop vanilla crafting/smelting/anvils/placing/eating from consuming or altering custom sky items");
        DUPE_SCAN_SECONDS = d.integer("items.dupe-scan-seconds", 30,
                "Seconds between anti-dupe scans of online players (0 = only on join)");
        DUPE_DELETE = d.flag("items.dupe-delete", true,
                "Delete the newer copy when one player holds two stacks with the same item uuid");
        DUPE_DELETE_CROSS_PLAYER = d.flag("items.dupe-delete-cross-player", false,
                "Also delete copies held by DIFFERENT players (off = log only; which copy is the original is unknowable)");
        ENCHANT_DETAIL_MAX = d.integer("items.enchant-detail-max", 3,
                "Items with at most this many enchants list each on its own line with its description");

        STAT_TICKS = d.integer("stats.recompute-ticks", 10, "Ticks between full stat recomputes per player");
        SPEED_CAP = d.number("stats.speed-cap", 400, "Highest Speed stat that still makes you faster (genre cap 400)");
        SWING_RANGE_CAP = d.number("stats.swing-range-cap", 15, "Highest melee reach in blocks (genre cap 15)");
        ATTACK_SPEED_BASE = d.number("stats.attack-speed-attribute", 1024,
                "Vanilla ATTACK_SPEED attribute base; high = no vanilla attack cooldown (the genre has none)");
        APPLY_ATTRIBUTES = d.flag("stats.apply-attributes", true,
                "Write Speed / Swing Range / attack speed into vanilla attributes");

        REGEN_SECONDS = d.number("vitals.regen-interval-seconds", 2,
                "Seconds between health regen ticks (API javadoc: 2s)");
        REGEN_MULT = d.number("vitals.regen-multiplier", 1.0,
                "Multiplier on (1.5 + max/100) * HealthRegen/100 per regen tick");
        REGEN_OUT_OF_COMBAT_MULT = d.number("vitals.regen-out-of-combat-multiplier", 1.0,
                "Extra regen multiplier while out of combat. TUNE: research 01 §6.1 says the June-2026 game doubles it (2.0)");
        COMBAT_SECONDS = d.number("vitals.combat-seconds", 5,
                "Seconds after losing health during which the player counts as in combat");
        MANA_REGEN_PCT = d.number("vitals.mana-regen-percent", 2,
                "Percent of max mana regenerated per second before Mana Regen (research 01 §7: 2)");
        DEATH_COIN_LOSS = d.number("vitals.death-coin-loss", 0.5,
                "Fraction of the purse lost on death outside safe zones (research 01 §5: half)");
        DEATH_SAFE_ON_PRIVATE_ISLAND = d.flag("vitals.death-safe-private-island", true,
                "Dying on a private island costs no coins");
        DISABLE_HUNGER = d.flag("vitals.disable-hunger", true, "Keep the hunger bar full (the genre has no hunger)");
        HEART_SCALE_MAX = d.number("vitals.heart-scale-max", 40,
                "Most vanilla half-hearts shown as max health grows (20 = 10 hearts at 100 HP, 40 = 20 hearts)");
        ACTION_BAR = d.flag("vitals.action-bar", true, "Show the health / defense / mana action bar");
        VANILLA_DAMAGE_NET = d.flag("vitals.vanilla-damage-net", true,
                "Catch vanilla damage that combat did not route and apply it to custom health (never let vanilla kill)");

        ABILITY_FLASH_TICKS = d.integer("abilities.flash-ticks", 40, "How long the '-50 Mana (Ability)' action bar text stays");
        ABILITIES_ENABLED = d.flag("abilities.enabled", true, "Master switch for item ability triggers");
    }

    static boolean flag(Dials.Dial<Boolean> d, boolean def) {
        try { Boolean b = d == null ? null : d.get(); return b == null ? def : b; } catch (RuntimeException e) { return def; }
    }

    static double num(Dials.Dial<Double> d, double def) {
        try { Double v = d == null ? null : d.get(); return v == null || v.isNaN() ? def : v; } catch (RuntimeException e) { return def; }
    }

    static int integer(Dials.Dial<Integer> d, int def) {
        try { Integer v = d == null ? null : d.get(); return v == null ? def : v; } catch (RuntimeException e) { return def; }
    }
}
