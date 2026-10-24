package com.skyclone.mod.armor;

/**
 * Every set-bonus formula, as pure functions (no Bukkit), so the numbers can be unit tested and
 * checked by the self-tests against research 06 §3.
 */
public final class SetMath {

    private SetMath() {}

    /** The Starlight Wand's own Starfall field (weapons, research 06): 3 blocks, 5 pulses one second apart. */
    public static final double STARFALL_RADIUS = 3;
    public static final int STARFALL_PULSES = 5;

    /** Protective Blood: +1% of a piece's Defense per 1% of missing health (research 06 §3.2). */
    public static double protectorBonus(double pieceDefenseSum, double health, double maxHealth, double pctPerPct) {
        if (maxHealth <= 0 || pieceDefenseSum <= 0) return 0;
        double missingPct = clamp(100.0 * (1.0 - health / maxHealth), 0, 100);
        return pieceDefenseSum * missingPct * pctPerPct / 100.0;
    }

    /** Tank (Emerald): +1 HP and +1 Defense per {@code per} Emerald collection, capped. */
    public static double emeraldBonus(long collection, double per, double cap) {
        if (per <= 0 || collection <= 0) return 0;
        return Math.min(cap, Math.floor(collection / per));
    }

    /** Blazing Aura cap: 500 damage/s, +100 per 5,000 Blaze Rods in the collection. */
    public static double blazeAuraCap(long blazeRods, double baseCap, double perFiveK) {
        return baseCap + Math.floor(Math.max(0, blazeRods) / 5000.0) * perFiveK;
    }

    /** Blazing Aura damage per second to one mob: base + pct% of its max health, capped. */
    public static double blazeAuraDamage(double mobMaxHealth, double pct, double base, double cap) {
        return Math.min(cap, base + mobMaxHealth * pct / 100.0);
    }

    /** Refraction (Crystal): stat factor 0..2 from the light level 0..15. */
    public static double crystalFactor(int lightLevel) {
        return clamp(lightLevel, 0, 15) / 15.0 * 2.0;
    }

    /** Smart Miner (Goblin): +1 Mining Speed per 15 Intelligence removed (none for negative Int). */
    public static double goblinMiningSpeed(double intelligence) {
        return intelligence <= 0 ? 0 : Math.floor(intelligence / 15.0);
    }

    /** Vindicate (Heavy): +1 Speed per 50 Defense. */
    public static double vindicateSpeed(double defense) {
        return defense <= 0 ? 0 : Math.floor(defense / 50.0);
    }

    /** Dashing (Tuxedo): multiplier that brings total health down to the cap (1 when already below). */
    public static double tuxedoHealthFactor(double totalHealth, double cap) {
        if (totalHealth <= cap || totalHealth <= 0) return 1.0;
        return cap / totalHealth;
    }

    /**
     * Tiered bonuses in the reference scale with pieces worn: 1 piece gives nothing, then thirds
     * (Cropie: 2 pcs +15, 3 pcs +30, 4 pcs +45 Farming Fortune).
     */
    public static double tieredThirds(int pieces, double fullValue) {
        if (pieces <= 1) return 0;
        return fullValue * Math.min(3, pieces - 1) / 3.0;
    }

    /** A per-piece linear tiered bonus (Angler, Backwater, Mineralworks...). */
    public static double perPiece(int pieces, double each) {
        return Math.max(0, pieces) * each;
    }

    /** Berserk: health and defense multiplier, 1.0 at no pieces down to 0.5 at four. */
    public static double berserkFactor(int pieces) {
        return 1.0 - 0.5 * clamp(pieces, 0, 4) / 4.0;
    }

    /** Counter-driven bonuses (Armor of Magma: kills/10, Lavaborn: kills/5), capped. */
    public static double perKills(long kills, double perKills, double cap) {
        if (kills <= 0 || perKills <= 0) return 0;
        return Math.min(cap, Math.floor(kills / perKills));
    }

    /** Battalion / pack style: stacks from nearby wearers, capped. */
    public static int stacks(int wearersInRange, int cap) {
        return (int) clamp(wearersInRange, 0, cap);
    }

    /** Static Charge: charges gained after {@code seconds} worn, capped. */
    public static int staticCharges(long secondsSinceLastHit, long secondsPerCharge, int cap) {
        if (secondsPerCharge <= 0) return 0;
        return (int) Math.min(cap, Math.max(0, secondsSinceLastHit / secondsPerCharge));
    }

    /** Chance (0..1) that a farming rare-crop roll hits: reference % scaled by pieces worn over four. */
    /**
     * Rare-crop chance per crop broken at 4/4, in PERCENT, from the in-game lore datamined into NEU
     * (MELON/CROPIE/SQUASH/FERMENTO_CHESTPLATE, post-Greenhouse): Tater 0.03% Cropie, Cropie 0.01% Squash,
     * Squash 0.005% Fermento, Fermento 0.002% Helianthus. The 2026-10-06 audit rows G5/G6 asked for
     * 0.03% / 0.007%; that research row was tagged low-confidence (0.03% is the Tater set's Cropie rate)
     * and the NEU lore contradicts it, so ours stays.
     */
    public static final double CROPIE_PCT = 0.03, SQUASH_PCT = 0.01, FERMENTO_PCT = 0.005, HELIANTHUS_PCT = 0.002;

    public static double rareCropChance(double percentAtFour, int pieces, double multiplier) {
        return Math.max(0, percentAtFour) / 100.0 * clamp(pieces, 0, 4) / 4.0 * Math.max(0, multiplier);
    }

    /** Clamp a hit so no single hit takes more than {@code maxFraction} of max health (Mithril Coat). */
    public static double capHit(double damage, double maxHealth, double maxFraction) {
        double cap = maxHealth * maxFraction;
        return cap > 0 ? Math.min(damage, cap) : damage;
    }

    /**
     * Right-click equip de-dupe: {@code last} is the previous equip as {tick, armour slot}. The same
     * click reported twice (block + air packet) lands on the same tick, or the next one when the two
     * packets straddle a tick, and always on the same slot. A different slot is a different click.
     */
    public static boolean sameEquipClick(int[] last, int now, int slot) {
        return last != null && last.length >= 2 && last[1] == slot && now - last[0] >= 0 && now - last[0] <= 1;
    }

    /**
     * Rekindle (Rekindled Ember, wave 3): additive % against a BURNING target. {@code perPiece} per
     * worn piece (4 x 50 = the research's +200% burning damage) plus {@code perSecond} for every second
     * the wearer has been on fire, capped at {@code fireCap} (research: 1.2%/s up to 50%).
     */
    public static double rekindleBonus(int pieces, double perPiece, double secondsOnFire, double perSecond, double fireCap) {
        if (pieces <= 0) return 0;
        double fire = clamp(Math.max(0, secondsOnFire) * perSecond, 0, Math.max(0, fireCap));
        return pieces * perPiece + fire;
    }

    /** Bouncy (Slug Boots): upward velocity for a sneaking landing after {@code fall} blocks; 0 below the minimum. */
    public static double bounceVelocity(double fall, double minFall, double perBlock) {
        if (fall < minFall || perBlock <= 0) return 0;
        return Math.min(2.5, fall * perBlock);
    }

    /** Breath in ticks for {@code respirationLevels} (vanilla 300 + {@code secondsPerLevel} s per level). */
    public static int maxAirTicks(double respirationLevels, double secondsPerLevel) {
        double extra = Math.max(0, respirationLevels) * Math.max(0, secondsPerLevel) * 20.0;
        return (int) Math.min(300 + extra, 20 * 60 * 10);
    }

    /** Coin multiplier the crowns put on a mythic creature kill: the best crown worn wins (they share the head slot). */
    public static double crownCoins(boolean greed, boolean avarice, boolean avariceCelebration, double greedX, double avariceX, double celebrationX) {
        double m = 1;
        if (greed) m = Math.max(m, greedX);
        if (avarice) m = Math.max(m, avariceX);
        if (avariceCelebration) m = Math.max(m, celebrationX);
        return m;
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------ wave 5

    /**
     * Young Blood's "+100 Walk Speed Cap": core turns Speed into movement up to its cap (400) and sets
     * that as the attribute BASE; the speed above the cap that the set lets through is an ADD_SCALAR
     * modifier on top of that base: {@code min(speed, cap + extra) / cap - 1}. 0 at or below the cap.
     */
    public static double speedCapScalar(double speed, double coreCap, double extraCap) {
        if (coreCap <= 0 || extraCap <= 0 || speed <= coreCap) return 0;
        return Math.min(speed, coreCap + extraCap) / coreCap - 1;
    }

    /**
     * Ticks between two melee hits one player may land on one mob - the same formula core uses
     * ({@code DamageMath.invulnTicks}): base / (1 + min(attackSpeed, cap) / 100), floored at {@code min}.
     */
    public static int hitWindow(int baseTicks, double attackSpeed, double cap, int min) {
        double as = clamp(attackSpeed, 0, Math.max(0, cap));
        int t = (int) Math.round(Math.max(1, baseTicks) / (1 + as / 100.0));
        return Math.max(Math.max(1, min), t);
    }

    /**
     * Newton's Demise decides whether a melee click core refused for its hit rate may land anyway:
     * only when core's own window is what refused it ({@code sinceCoreHit < coreWindow}), the set's
     * longer cap gives a shorter window, and that shorter window has passed since our last hit.
     */
    public static boolean newtonMayLand(long sinceCoreHit, long sinceOurHit, int coreWindow, int ourWindow) {
        if (ourWindow >= coreWindow || sinceCoreHit < 0) return false;
        return sinceCoreHit < coreWindow && sinceOurHit >= ourWindow;
    }

    /**
     * Fear from the Greater Spook reforge on one piece (reforges data: EPIC +4, LEGENDARY +5; a
     * recombobulated legendary reads the nearest lower rarity, i.e. still 5). 0 for any other reforge.
     * {@code rarityOrdinal} is {@code Rarity.ordinal()} (EPIC = 3, LEGENDARY = 4).
     */
    public static double reforgeFear(String reforge, int rarityOrdinal) {
        if (reforge == null || !reforge.equalsIgnoreCase("greater_spook")) return 0;
        return rarityOrdinal >= 4 ? 5 : 4;
    }

    /** Blaze armour aura with the Blaze pet's "Bling Armor" boost (+a% to the set's stats AND ability). */
    public static double petBoosted(double value, double petPercent) {
        return petPercent <= 0 ? value : value * (1 + petPercent / 100.0);
    }

    // ------------------------------------------------------------------ wave 5, second pass

    /**
     * Magma Lord tier count: worn armour pieces plus the Magma Lord Necklace (equipment), which the
     * reference counts as a fifth piece of both tiers ("Fireproof (x/2)", "Lord's Blessing (x/5)").
     * A necklace on its own is never a tier (you need at least one armour piece to have the set at all).
     */
    public static int magmaLordPieces(int armourPieces, boolean necklace) {
        return armourPieces <= 0 ? 0 : armourPieces + (necklace ? 1 : 0);
    }

    /** Pieces Lord's Blessing needs: the dial (5), but never more than the armour alone can reach when no necklace exists. */
    public static int magmaLordNeed(int dialNeed, boolean necklaceRegistered) {
        int n = Math.max(1, dialNeed);
        return necklaceRegistered ? n : Math.min(n, 4);
    }

    /**
     * Magic Find a worn set adds to the drops of ONE killed mob (core's per-target loot hook):
     * Lord's Blessing vs magmatic, Familiarity (per Mythos piece) and the Crown of Greed vs mythological.
     */
    public static double magicFindVs(boolean magmatic, boolean mythic, boolean lordsBlessing, int mythosPieces, boolean crownOfGreed,
                                     double magmaLordMf, double familiarityPerPiece, double greedMf) {
        double mf = 0;
        if (magmatic && lordsBlessing) mf += magmaLordMf;
        if (mythic) {
            mf += perPiece(mythosPieces, familiarityPerPiece);
            if (crownOfGreed) mf += greedMf;
        }
        return mf;
    }

    /** Peace Treaty: the most pieces worn of any ONE Hunter set reaches the threshold. */
    public static boolean peaceTreaty(int need, int... hunterSetPieces) {
        if (need <= 0) return false;
        for (int n : hunterSetPieces) if (n >= need) return true;
        return false;
    }

    /**
     * Bat Swarm: one step of a homing bat. Returns the new position {x,y,z} moved at most {@code speed}
     * toward the target; lands exactly on it when closer than one step.
     */
    public static double[] batStep(double[] from, double[] to, double speed) {
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d <= speed || d < 1e-9) return new double[]{to[0], to[1], to[2]};
        double f = speed / d;
        return new double[]{from[0] + dx * f, from[1] + dy * f, from[2] + dz * f};
    }

    /** Bat Swarm: the bat that is ready again {@code respawnSeconds} after it exploded (0 = never exploded). */
    public static boolean batReady(long explodedAtMillis, long nowMillis, int respawnSeconds) {
        return explodedAtMillis <= 0 || nowMillis - explodedAtMillis >= Math.max(0, respawnSeconds) * 1000L;
    }

    /**
     * Old Blood on Feather Falling: the extra safe-fall distance is the share of what the Old Dragon pieces'
     * own Feather Falling grants (enchanting applies the base; we add {@code boost} of it on top).
     */
    public static double oldBloodSafeFall(double oldPiecesFeatherFallingValue, double boost) {
        return oldPiecesFeatherFallingValue <= 0 || boost <= 0 ? 0 : oldPiecesFeatherFallingValue * boost;
    }

    /** Nether Lord: lava cells (dx,dz offsets) within a disc of {@code radius} blocks, frost-walker style. */
    public static java.util.List<int[]> discOffsets(double radius) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int r = (int) Math.floor(Math.max(0, radius));
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                if (dx * dx + dz * dz <= radius * radius + 1e-9) out.add(new int[]{dx, dz});
        return out;
    }
}
