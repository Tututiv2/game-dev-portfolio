package com.skyclone.mod.slayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every slayer formula, pure (no Bukkit) so it is unit-tested against the research numbers.
 *
 * <p>Loot model (research 04 sec 2.11): the token (Ghoul Flesh, Broodweaver Web...) is always given. Then ONE weighted
 * pick happens over the token's weight plus every drop the player's slayer level allows at this tier. A weight is the
 * drop's displayed per-kill chance at max level; drops below the player's level leave the pool and the pool
 * renormalises. Magic Find and the Trailblazer perk multiply weights of drops whose base chance is under 5%, the RNG
 * meter multiplies its selected drop. Dyes roll on their own.
 */
public final class SlayerMath {

    private SlayerMath() {}

    /** Drops at or above this base chance (%) are "common" and ignore Magic Find (core rule: MF only under 5%). */
    public static final double RARE_THRESHOLD = 5.0;

    // ---- levels --------------------------------------------------------------------------------

    /** Level reached with {@code xp} on a cumulative table. */
    public static int level(long[] table, double xp) {
        int lvl = 0;
        for (long need : table) {
            if (xp >= need) lvl++;
            else break;
        }
        return lvl;
    }

    /** Cumulative XP needed for the next level, or -1 at max. */
    public static long nextThreshold(long[] table, double xp) {
        int lvl = level(table, xp);
        return lvl >= table.length ? -1 : table[lvl];
    }

    /** Cumulative XP at the start of the current level. */
    public static long levelStart(long[] table, double xp) {
        int lvl = level(table, xp);
        return lvl == 0 ? 0 : table[lvl - 1];
    }

    /** 0..1 progress through the current level (1 at max). */
    public static double levelProgress(long[] table, double xp) {
        long next = nextThreshold(table, xp);
        if (next < 0) return 1;
        long start = levelStart(table, xp);
        return Math.max(0, Math.min(1, (xp - start) / (double) (next - start)));
    }

    // ---- loot ----------------------------------------------------------------------------------

    /** Effective weight of one non-token weighted drop for a player. 0 when not eligible. */
    public static double weight(SlayerCatalog.LootEntry e, int tier, int slayerLevel, double magicFind,
                                double rarePercentBonus, double rngMultiplier) {
        return weight(e, tier, slayerLevel, magicFind, rarePercentBonus, rngMultiplier, false);
    }

    /** {@link #weight}; {@code lootshare} = the helper's roll, the only one lootshare-only drops are in. */
    public static double weight(SlayerCatalog.LootEntry e, int tier, int slayerLevel, double magicFind,
                                double rarePercentBonus, double rngMultiplier, boolean lootshare) {
        if (e.token || e.independent) return 0;
        if (e.lootshareOnly && !lootshare) return 0;
        double base = e.chanceAt(tier);
        if (base <= 0 || slayerLevel < e.minLevel) return 0;
        double w = base;
        if (base < RARE_THRESHOLD) {
            w *= 1 + Math.max(0, Math.min(900, magicFind)) / 100.0;
            w *= 1 + Math.max(0, rarePercentBonus) / 100.0;
        }
        return w * Math.max(1, rngMultiplier);
    }

    /**
     * The chance (0..1) of each weighted drop for one kill. The token's own share is under key {@code ""}.
     * {@code rngItem} may be null; {@code rngMultiplier} applies only to it.
     */
    public static Map<String, Double> chances(SlayerCatalog.Type type, int tier, int slayerLevel, double magicFind,
                                              double rarePercentBonus, String rngItem, double rngMultiplier) {
        return chances(type, tier, slayerLevel, magicFind, rarePercentBonus, rngItem, rngMultiplier, e -> true);
    }

    /**
     * {@link #chances} over the drops {@code available} accepts; the others leave the pool (their share goes back to
     * every remaining drop, the token included). Used for Hunting shards while no hunting module is loaded: a shard
     * that cannot be delivered must not eat a roll.
     */
    public static Map<String, Double> chances(SlayerCatalog.Type type, int tier, int slayerLevel, double magicFind,
                                              double rarePercentBonus, String rngItem, double rngMultiplier,
                                              java.util.function.Predicate<SlayerCatalog.LootEntry> available) {
        return chances(type, tier, slayerLevel, magicFind, rarePercentBonus, rngItem, rngMultiplier, available, false);
    }

    /**
     * {@link #chances}; {@code lootshare} = a helper's lootshare roll, which also holds the lootshare-only drops (the
     * summoner's pool never does: their weight goes to nobody, the token's share is fixed by the data).
     */
    public static Map<String, Double> chances(SlayerCatalog.Type type, int tier, int slayerLevel, double magicFind,
                                              double rarePercentBonus, String rngItem, double rngMultiplier,
                                              java.util.function.Predicate<SlayerCatalog.LootEntry> available,
                                              boolean lootshare) {
        SlayerCatalog.Tier t = type.tier(tier);
        Map<String, Double> w = new LinkedHashMap<>();
        double token = t == null ? 100 : Math.max(0, t.tokenWeight);
        double sum = token;
        for (SlayerCatalog.LootEntry e : type.loot) {
            if (available != null && !available.test(e)) continue;
            double m = e.item.equals(rngItem) ? rngMultiplier : 1;
            double x = weight(e, tier, slayerLevel, magicFind, rarePercentBonus, m, lootshare);
            if (x > 0) {
                w.put(e.item, x);
                sum += x;
            }
        }
        Map<String, Double> out = new LinkedHashMap<>();
        if (sum <= 0) return out;
        out.put("", token / sum);
        for (Map.Entry<String, Double> en : w.entrySet()) out.put(en.getKey(), en.getValue() / sum);
        return out;
    }

    /** Pick one key of {@link #chances} with a uniform roll in [0,1). Returns "" for the token share. */
    public static String pick(Map<String, Double> chances, double roll) {
        double acc = 0;
        String last = "";
        for (Map.Entry<String, Double> e : chances.entrySet()) {
            acc += e.getValue();
            last = e.getKey();
            if (roll < acc) return e.getKey();
        }
        return last;
    }

    /** Chance (0..1) of an independent drop (dyes, the Paragon shard). */
    public static double independentChance(SlayerCatalog.LootEntry e, int tier, int slayerLevel, double magicFind) {
        return independentChance(e, tier, slayerLevel, magicFind, 0, 1);
    }

    /**
     * Chance (0..1) of an independent drop with the same boosts a weighted rare drop gets: Magic Find and the
     * mayor's rare-drop perk (only under {@link #RARE_THRESHOLD}) and the RNG meter multiplier when it is selected.
     */
    public static double independentChance(SlayerCatalog.LootEntry e, int tier, int slayerLevel, double magicFind,
                                           double rarePercentBonus, double rngMultiplier) {
        if (!e.independent || slayerLevel < e.minLevel) return 0;
        double base = e.chanceAt(tier);
        if (base <= 0) return 0;
        double c = base;
        if (base < RARE_THRESHOLD) {
            c *= 1 + Math.max(0, Math.min(900, magicFind)) / 100.0;
            c *= 1 + Math.max(0, rarePercentBonus) / 100.0;
        }
        return Math.min(1, c * Math.max(1, rngMultiplier) / 100.0);
    }

    // ---- RNG meter -----------------------------------------------------------------------------

    /** 0..1 meter fill. */
    public static double meterProgress(double meterXp, long required) {
        if (required <= 0) return 0;
        return Math.max(0, Math.min(1, meterXp / required));
    }

    /** Weight multiplier for the selected drop while the meter fills: 1 + boost * progress (dial boost). */
    public static double meterMultiplier(double progress, double boost) {
        return 1 + Math.max(0, boost) * Math.max(0, Math.min(1, progress));
    }

    // ---- quests --------------------------------------------------------------------------------

    /** Quest price after a percentage discount (Cut-Rate Contracts = 50). Never negative. */
    public static double cost(double base, double discountPercent) {
        return Math.max(0, Math.floor(base * (1 - Math.max(0, Math.min(100, discountPercent)) / 100.0)));
    }

    /**
     * Quest price after the mayor's discount and the Hunting attribute Slayer Discount (Paragon shard, +0.5% a level):
     * the two apply one after the other, so 50% off and 5% off is 47.5% off, never more than 100%.
     */
    public static double cost(double base, double mayorPercent, double attributePercent) {
        double afterMayor = base * (1 - clampPct(mayorPercent) / 100.0);
        return Math.max(0, Math.floor(afterMayor * (1 - clampPct(attributePercent) / 100.0)));
    }

    private static double clampPct(double v) { return Math.max(0, Math.min(100, v)); }

    /**
     * Combat XP a fresh quest starts with: the Hunting attribute Slayer Progress (Wolverine shard) starts it this %
     * complete. Never fills the bar: at least one kill is always needed.
     */
    public static double startingProgress(double target, double progressPercent) {
        if (!(target > 0) || !(progressPercent > 0)) return 0;
        return Math.min(Math.max(0, target - 1), Math.floor(target * Math.min(100, progressPercent) / 100.0));
    }

    /**
     * The Primordial (NEU lore): a slain slayer boss or miniboss is cocooned with {@code basePercent}% (10) plus the
     * Hunting attribute Cocoon Chance (Primordial shard, +0.5% a level). 0..1. A hatched mob never cocoons again.
     */
    public static double cocoonChance(boolean wearing, double basePercent, double attributePercent, boolean alreadyHatched) {
        if (!wearing || alreadyHatched) return 0;
        return Math.max(0, Math.min(100, Math.max(0, basePercent) + Math.max(0, attributePercent))) / 100.0;
    }

    /**
     * Extra ferocity strikes from a per-target Ferocity bonus (Vivid Darkness: +200 against endermen). The player's
     * own Ferocity already rolled its strikes in core; this adds the difference the bonus makes, with the same
     * rule (every 100 is a strike, the remainder a chance) and the same cap. {@code roll} in [0,1).
     */
    public static int bonusFerocityStrikes(double ferocity, double bonus, double cap, double roll) {
        double f = Math.max(0, Math.min(cap, Math.max(0, ferocity)));
        double g = Math.max(0, Math.min(cap, Math.max(0, ferocity) + Math.max(0, bonus)));
        double extra = (g - f) / 100.0;
        int n = (int) Math.floor(extra);
        if (roll < extra - n) n++;
        return n;
    }

    /**
     * Fire Pillar hits one strike counts for, as THIS module sets it: always 1. "Twice as fast" belongs to the Wisp pets
     * (Drophammer / Icehammer) and the pets module applies it in its Damage handler, folding Coldfused into the same
     * x2 (so a Glacial Wisp, whose own perk does not double, still doubles with a Coldfused weapon). Doubling here as
     * well made a Coldfused strike count 4 hits (wave 5 integration fix).
     */
    public static final int PILLAR_HITS_PER_STRIKE = 1;

    /**
     * Coldfused (research 01): "Only if Wisp is equipped ... breaking one [fire pillar] grants +30 True Defense and
     * +1.15x damage for 60s". The strike that broke it must carry the Coldfused weapon and a Wisp pet must be out.
     */
    public static boolean coldfusedBreakBonus(boolean coldfused, boolean wispOut) {
        return coldfused && wispOut;
    }

    /**
     * Paralytic Venom's Vitality reduction after the Tarantula pet's Webbed Cells ("Vitality reduction is 30% less
     * effective against you"): {@code venomPct x (1 - webbed/100)}, webbed clamped to 0..100.
     */
    public static double venomVitality(double venomPct, double webbedPct) {
        double w = Math.max(0, Math.min(100, webbedPct));
        return Math.max(0, venomPct) * (1 - w / 100.0);
    }

    /** Slayer XP for a kill after percentage boosts (Slayer XP Buff = 25). */
    public static double slayerXp(double base, double boostPercent) {
        return base * (1 + Math.max(0, boostPercent) / 100.0);
    }

    /** After-kill heal duration in seconds: 6 s at level 0 up to 10 s at level 9 (research 2.1). */
    public static double healSeconds(int slayerLevel) {
        return 6 + Math.max(0, Math.min(9, slayerLevel)) * 4.0 / 9.0;
    }

    /** Seconds between two Ghoul Life Drains (research 2.5: "every 3-5 s"). */
    public static final double DRAIN_INTERVAL = 4.0;
    /**
     * Pestilence damage per second as a share of the tier's stated DPS (TUNE; Defense applies). It was 0.3 ON TOP of
     * the stated melee; since wave-4 loops it is part of the stated number (see {@link #ghoulMeleeShare}).
     */
    public static final double PESTILENCE_SHARE = 0.15;
    /** The Ghoul's melee never falls below this share of the stated DPS, whatever the drain dial says. */
    public static final double MIN_MELEE_SHARE = 0.25;

    /**
     * Raw damage of one Ghoul Life Drain (TUNE: research gives no number): {@code seconds} worth of the tier's stated
     * damage per second, applied as normal damage so Defense counts. The default dial is 0.5 s per 4 s drain (12.5% of
     * the stated DPS on average, carved OUT of the melee, see {@link #ghoulMeleeShare}). It used to be 1.5x DPS as
     * TRUE damage, which at T3 was ~45 true dps on top of melee and killed a 600 HP / 700 Defense player in ~13 s
     * whatever their Defense (playtest 2026-10-02).
     */
    public static double lifeDrain(double tierDps, double seconds) {
        return Math.max(0, tierDps) * Math.max(0, seconds);
    }

    /** Pestilence raw damage per second for a tier (Defense applies). */
    public static double pestilence(double tierDps) {
        return Math.max(0, tierDps) * PESTILENCE_SHARE;
    }

    /** Average raw damage per second of Life Drain. */
    public static double lifeDrainDps(double tierDps, double seconds) {
        return lifeDrain(tierDps, seconds) / DRAIN_INTERVAL;
    }

    /**
     * Share of a Ghoul tier's stated DPS its melee carries, so that melee + Life Drain + Pestilence add up to the
     * "Damage: X per second" on the tier tooltip (the reference figure, research 04 sec 2.3). Wave-4 loops playtest:
     * the two TUNE abilities were stacked ON TOP of the stated melee (x1.55 at T3, 186 raw/s against "120 per
     * second"), and a Superior Dragon player with an enchanted Shadow Fury and Life Steal III (598 HP, 7.5k dps)
     * died twice with the boss far from dead, although research puts T1-T3 in the early-dragon-armour stage.
     * Enrage still doubles the melee part only.
     */
    public static double ghoulMeleeShare(int tier, double drainSeconds) {
        double share = 1 - lifeDrainDps(1, drainSeconds) - (tier >= 2 ? PESTILENCE_SHARE : 0);
        return Math.max(MIN_MELEE_SHARE, Math.min(1, share));
    }

    /**
     * Raw damage per second a Ghoul tier deals to someone standing in it (outside Enrage): melee share x stated DPS x
     * the boss damage dial + Life Drain and Pestilence x the mechanic dial. Equals the stated DPS at default dials.
     */
    public static double ghoulRawPerSecond(int tier, double tierDps, double drainSeconds, double bossDamageScale,
                                           double mechanicScale) {
        double melee = Math.max(0, tierDps) * ghoulMeleeShare(tier, drainSeconds) * Math.max(0, bossDamageScale);
        double mech = lifeDrainDps(tierDps, drainSeconds) + (tier >= 2 ? pestilence(tierDps) : 0);
        return melee + mech * Math.max(0, mechanicScale);
    }

    /** Defense reduction (same formula as core combat): dmg * 100 / (100 + defense). */
    public static double afterDefense(double raw, double defense) {
        return Math.max(0, raw) * 100.0 / (100.0 + Math.max(0, defense));
    }

    /**
     * Seconds a player lasts standing in a boss with no healing at all: health / (incoming after Defense). Infinity
     * when nothing gets through. A floor, not a forecast: healing (Life Steal, regen, wands) stretches it.
     */
    public static double secondsToLast(double health, double incomingPerSecond) {
        if (!(incomingPerSecond > 0)) return Double.POSITIVE_INFINITY;
        return Math.max(0, health) / incomingPerSecond;
    }

    /** Immolate true damage for the boss's current health fraction (research 2.9 table). */
    public static double immolate(double[] row, double hpFraction) {
        if (row == null || row.length < 3) return 0;
        if (hpFraction > 2.0 / 3.0) return row[0];
        if (hpFraction > 1.0 / 3.0) return row[1];
        return row[2];
    }

    /** Hits needed to break the hitshield at a tier (research 2.8: 15/30/60/100). */
    public static int hitShield(int tier) {
        return switch (tier) {
            case 1 -> 15;
            case 2 -> 30;
            case 3 -> 60;
            default -> 100;
        };
    }

    /** Backstab bonus percent by tier (NEU boss pages: 20/30/40/60/80). */
    public static double backstab(int tier) {
        return switch (tier) {
            case 1 -> 20;
            case 2 -> 30;
            case 3 -> 40;
            case 4 -> 60;
            default -> 80;
        };
    }

    /** Paralytic venom: {vitality %, speed %} reduction by tier (NEU boss pages). */
    public static double[] venom(int tier) {
        return switch (tier) {
            case 1 -> new double[]{0, 0};
            case 2 -> new double[]{15, 10};
            case 3 -> new double[]{30, 25};
            case 4 -> new double[]{50, 50};
            default -> new double[]{75, 75};
        };
    }

    /** Egg sacs laid per Web Cocoon and hits each takes (research 2.6 / NEU: T3 2, T4 3, T5 4 x 9 hits). */
    public static int[] eggSacs(int tier) {
        return switch (tier) {
            case 3 -> new int[]{2, 3};
            case 4 -> new int[]{3, 3};
            case 5 -> new int[]{4, 9};
            default -> new int[]{0, 0};
        };
    }

    /**
     * The bar-full grace (playtest wave3): the boss is drawn by the full bar but rises only once its summoner is
     * ready - at or above {@code readyPercent} of max Health and not hit for {@code calmSeconds} - or when
     * {@code maxWaitSeconds} have passed whatever the state. {@code maxWaitSeconds <= 0} turns the grace off (the
     * reference spawns the boss at once, next to you, in the middle of the pack).
     */
    public static boolean readyToSummon(double healthFraction, double secondsSinceHurt, double secondsWaited,
                                        double readyPercent, double calmSeconds, double maxWaitSeconds) {
        if (!(maxWaitSeconds > 0)) return true;
        if (secondsWaited >= maxWaitSeconds) return true;
        boolean healthy = !(healthFraction >= 0) || healthFraction * 100.0 >= readyPercent - 1e-9;
        return healthy && secondsSinceHurt >= calmSeconds;
    }

    /**
     * "Enchanted Book (Smite VI)": every enchanted book level shares the name "Enchanted Book" (the enchant is in its
     * lore), so a list of drops or a loot line must say which one it is. Blank enchant name leaves the name alone.
     */
    public static String withEnchant(String itemName, String enchantName, int level) {
        String n = itemName == null ? "" : itemName;
        if (enchantName == null || enchantName.isBlank()) return n;
        String e = enchantName.trim() + (level > 0 ? " " + roman(level) : "");
        return n + " (" + e + ")";
    }

    /** "IV" */
    public static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    /** m:ss */
    public static String clock(long seconds) {
        if (seconds < 0) seconds = 0;
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }
}
