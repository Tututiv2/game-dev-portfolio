package com.skyclone.mod.weapons;

/**
 * Every weapons formula, pure (no Bukkit) so unit tests and self-tests can check them against the
 * research numbers. Sources: research/06 §4 (weapons), research/01 §4 (damage).
 */
final class WeaponMath {

    private WeaponMath() {}

    /** Shortbow shot cooldown: base / (1 + AS/100), Bonus Attack Speed capped at 100, floored at {@code min}. */
    static double shortbowCooldown(double base, double attackSpeed, double min) {
        double as = Math.max(0, Math.min(100, attackSpeed));
        return Math.max(min, base / (1 + as / 100.0));
    }

    /**
     * Gilded King's Sword (Midas' Sword) Avarice bonus to Damage and Strength by Shadow Auction price.
     * TUNE: piecewise curve as remembered from the reference wiki; endpoints match research 06
     * ("max +120 at >= 50M").
     */
    static double midasSwordBonus(double paid) {
        if (paid <= 0) return 0;
        if (paid < 1_000_000) return paid / 50_000.0;
        if (paid < 2_500_000) return 20 + (paid - 1_000_000) / 100_000.0;
        if (paid < 7_500_000) return 35 + (paid - 2_500_000) / 200_000.0;
        if (paid < 25_000_000) return 60 + (paid - 7_500_000) / 500_000.0;
        if (paid < 50_000_000) return 95 + (paid - 25_000_000) / 1_000_000.0;
        return 120;
    }

    /** Gilded King's Staff bonus ability damage: TUNE linear up to research 06's "26,000 at 100M". */
    static double midasStaffBonus(double paid) {
        if (paid <= 0) return 0;
        return 26_000 * Math.min(1.0, paid / 100_000_000.0);
    }

    /** Emerald Blade: coefficient x purse^(1/4), purse capped (research 06: cap 2B; formula marked (?)). */
    static double emeraldBlade(double purse, double coefficient, double cap) {
        if (purse <= 0) return 0;
        return coefficient * Math.pow(Math.min(purse, cap), 0.25);
    }

    /** Hurricane Bow arrows per shot from kills: 20 -> 2, 50 -> 3, 100 -> 4, 250 -> 5 (research 06). */
    static int hurricaneArrows(long kills) {
        if (kills >= 250) return 5;
        if (kills >= 100) return 4;
        if (kills >= 50) return 3;
        if (kills >= 20) return 2;
        return 1;
    }

    /** Kills needed for the next Hurricane upgrade, or -1 when maxed. */
    static long hurricaneNext(long kills) {
        long[] steps = {20, 50, 100, 250};
        for (long s : steps) if (kills < s) return s;
        return -1;
    }

    /** Raider Sword: +1 Damage per 500 kills, max 35 (research 06). */
    static int raiderDamage(long kills) { return (int) Math.min(35, Math.max(0, kills) / 500); }

    /** Raider Sword: +1 Strength per 500 wood collected, max 100 (research 06). */
    static int raiderStrength(long wood) { return (int) Math.min(100, Math.max(0, wood) / 500); }

    /** Tactician's Sword: +15 Damage per Combat collection at tier VII+ (research 06). */
    static int tacticianDamage(int collectionsAtSeven) { return 15 * Math.max(0, collectionsAtSeven); }

    /** Shaman Sword: +1 Damage per {@code per} max health (research 06: per 50). */
    static double perHealth(double maxHealth, double per) { return per <= 0 ? 0 : Math.floor(Math.max(0, maxHealth) / per); }

    /** +1 Damage per {@code per} kills up to {@code max} (Fel Sword: 100 / 100). */
    static int killsDamage(long kills, int per, int max) { return per <= 0 ? 0 : (int) Math.min(max, Math.max(0, kills) / per); }

    /** % bonus for every 1% of missing health: Spirit Sword 2, Bone Reaver 1. */
    static double missingHealthBonus(double health, double max, double pctPerPct) {
        if (max <= 0) return 0;
        double missing = Math.max(0, Math.min(1, 1 - health / max)) * 100.0;
        return missing * pctPerPct;
    }

    /**
     * End Stone Focus Burn: per 100 mana burned, 1% damage reduction (max 20) and +25% next hit (max 500).
     * Returns {reductionPct, nextHitPct}.
     */
    static double[] focusBurn(double mana) {
        int units = (int) Math.floor(Math.max(0, mana) / 100.0);
        return new double[]{Math.min(20, units), Math.min(500, units * 25.0)};
    }

    /** The multiplier equivalent of gaining {@code bonus} Strength at {@code strength} (melee formula). */
    static double strengthMultiplier(double strength, double bonus) {
        double base = 1 + Math.max(0, strength) / 100.0;
        return (base + bonus / 100.0) / base;
    }

    /** Defense ignore as a damage multiplier: (100 + def) / (100 + def x (1 - ignore%)). */
    static double defenseIgnoreMultiplier(double defense, double ignorePct) {
        if (defense <= 0) return 1;
        double kept = defense * (1 - Math.max(0, Math.min(100, ignorePct)) / 100.0);
        return (100 + defense) / (100 + kept);
    }

    /** Jester-chine: each shot in a streak costs +30 mana (research 06). */
    static double streakCost(double base, int streak, double step) { return base + step * Math.max(0, streak); }

    /** Halberd throw: each throw in a row doubles damage and mana, max 16x (research 06). */
    static double doubling(int streak) { return Math.pow(2, Math.min(4, Math.max(0, streak))); }

    /** Bad Health: +5 Strength per 5% of total health you have, max +100 (research 06). */
    static int badHealthStrength(double health, double max) {
        if (max <= 0) return 0;
        double pct = Math.max(0, Math.min(1, health / max)) * 100.0;
        return (int) Math.min(100, Math.floor(pct / 5.0) * 5);
    }

    /** Ender Bow pearl: 10% of the mob's max health, capped at 500 (research 06). */
    static double pearlDamage(double maxHealth) { return Math.min(500, Math.max(0, maxHealth) * 0.10); }

    /** Bouncy Arrow: each bounce deals 15% less than the hit before (research 06). */
    static double bounceMultiplier(int bounce) { return Math.pow(0.85, Math.max(0, bounce)); }

    /** Skewer Arrow: each further pierce victim takes +7.5% more, up to 5 victims (research 06). */
    static double skewerMultiplier(int victimIndex) { return 1 + 0.075 * Math.max(0, Math.min(4, victimIndex)); }

    /** Yaw offsets (degrees) for {@code n} arrows fanned {@code spacing} apart around 0. */
    static double[] spread(int n, double spacing) {
        double[] out = new double[Math.max(1, n)];
        double start = -(out.length - 1) * spacing / 2.0;
        for (int i = 0; i < out.length; i++) out[i] = start + i * spacing;
        return out;
    }

    /** Is (dx, dz) inside a cone of half-angle {@code halfDeg} around facing (fx, fz)? 2D, horizontal. */
    static boolean inCone(double dx, double dz, double fx, double fz, double halfDeg) {
        double dl = Math.sqrt(dx * dx + dz * dz), fl = Math.sqrt(fx * fx + fz * fz);
        if (dl < 1e-6) return true;
        if (fl < 1e-6) return false;
        double cos = (dx * fx + dz * fz) / (dl * fl);
        return cos >= Math.cos(Math.toRadians(halfDeg));
    }

    /** Distance from point P to the segment AB (3D). */
    static double distToSegment(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz) {
        double vx = bx - ax, vy = by - ay, vz = bz - az;
        double wx = px - ax, wy = py - ay, wz = pz - az;
        double len2 = vx * vx + vy * vy + vz * vz;
        double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, (wx * vx + wy * vy + wz * vz) / len2));
        double cx = ax + t * vx - px, cy = ay + t * vy - py, cz = az + t * vz - pz;
        return Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** Crit chance after Oblivion Shortbow's divide-by-4 (research 06: "Crit Chance / 4"). */
    static double dividedCrit(double critChance, double div) { return div <= 0 ? critChance : critChance / div; }

    /** Rose chain: hop {@code n} (0-based) deals 1 + n x bonus. */
    static double hopMultiplier(int hop, double bonus) { return 1 + Math.max(0, hop) * bonus; }

    /**
     * Does a weapon's Catacombs requirement apply right now? The reference keeps it to dungeon runs
     * ({@code catacombs_requirements}), so outside a dungeon the weapon gives its stats and hits freely.
     * {@code mode} is the dial {@code weapons.catacombs-requirement}; anything unrecognised (including the
     * retired dial's "combat"/"keep" values) reads as the reference rule, except "keep" which meant strict.
     */
    static boolean catacombsApplies(String mode, boolean inDungeon) {
        String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (m) {
            case "none", "off" -> false;
            case "everywhere", "always", "keep" -> true;
            default -> inDungeon;
        };
    }

    /** Petal Storm: +2% per 10% missing health (research 06). */
    static double petalBonus(double health, double max) {
        if (max <= 0) return 0;
        double missing = Math.max(0, Math.min(1, 1 - health / max)) * 100.0;
        return Math.floor(missing / 10.0) * 2.0;
    }

    /** Teleport range of a blade: base blocks + 1 per Transmission Tuner (max 4, research 01). */
    static double teleportRange(double base, int tuners) {
        return Math.max(0, base) + Math.max(0, Math.min(4, tuners));
    }

    /** Raise Souls mana per soul (TUNE: the reference only says 'based on the power of the monster'). */
    static double soulMana(int level) {
        return Math.min(500, 20 + 4 * Math.max(1, level));
    }

    /** Burning Vengeance: the last (up to) 8 hits dealt, summed (research 06). */
    static double vengeance(java.util.Collection<Double> lastHits) {
        double sum = 0;
        int n = 0;
        java.util.List<Double> l = new java.util.ArrayList<>(lastHits);
        for (int i = l.size() - 1; i >= 0 && n < 8; i--, n++) sum += Math.max(0, l.get(i) == null ? 0 : l.get(i));
        return sum;
    }

    /** Wraith Drain: heal 200 + 4% of max Health (research 06). */
    static double wraithDrain(double maxHealth) { return 200 + 0.04 * Math.max(0, maxHealth); }

    /** Fear: +5 per worn Great Spook armor piece (research 06 armor table: 4 pieces = 20). */
    static int fear(int greatSpookPieces) { return 5 * Math.max(0, Math.min(4, greatSpookPieces)); }

    /** Ferocity: one extra strike per 100, plus the remainder as a chance (research 01). */
    static int ferocityStrikes(double ferocity, double roll) {
        if (ferocity <= 0) return 0;
        int whole = (int) Math.floor(ferocity / 100.0);
        return whole + (roll < (ferocity - whole * 100.0) / 100.0 ? 1 : 0);
    }

    /** Infinite Quiver: % chance a shot takes no arrow (research 01: 3% per level, X = 30%), capped at 100. */
    static double infiniteQuiverPct(int level, double perLevel) {
        if (level <= 0 || perLevel <= 0) return 0;
        return Math.min(100.0, level * perLevel);
    }

    /**
     * Flaming Sword burn: damage of one burn tick (once a second) = pct% of the hit that lit the target,
     * for {@code seconds} ticks. Returns {perTick, ticks}.
     */
    static double[] burn(double hit, double pctPerSecond, double seconds) {
        int ticks = (int) Math.max(0, Math.floor(seconds));
        double per = Math.max(0, hit) * Math.max(0, pctPerSecond) / 100.0;
        return new double[]{per, ticks};
    }

    // ---- wave 5 -----------------------------------------------------------------------------------

    /**
     * A Bestiary family's tier: how many of its cumulative kill thresholds the kills reach, counting only
     * thresholds at or under the family's kill cap and never above {@code maxTier} (progression's bestiary.json).
     */
    static int bestiaryTier(long kills, int[] thresholds, int cap, int maxTier) {
        if (thresholds == null || kills <= 0) return 0;
        int t = 0;
        for (int thr : thresholds) {
            if (cap > 0 && thr > cap) break;
            if (kills >= thr) t++;
            else break;
        }
        return maxTier > 0 ? Math.min(maxTier, t) : t;
    }

    /** Labyrinth Blade (research 06): +{@code per} (damage % or Magic Find) per Mythological Bestiary tier. */
    static double perTier(int tiers, double per) { return Math.max(0, tiers) * Math.max(0, per); }

    /**
     * Fear (research 06): 5 per worn Great Spook armor piece, +1 per worn piece reforged Greater Spook, and the
     * Eerie pet's Fearcreasing: +{@code perTen} Fear per 10 Primal Fears slain, counting at most 150 kills.
     */
    static int fearTotal(int greatSpookPieces, int greaterSpookPieces, long primalFearKills, double perTen) {
        return fearSum(fear(greatSpookPieces), Math.max(0, greaterSpookPieces), 0, primalFearKills, perTen);
    }

    /**
     * Fear from every source (wave 5): {@code gear} = the {@code FEAR} lines of worn armour + equipment (5 each on the
     * Great Spook pieces) + the best Great Spook accessory (2 / 4 / 6), {@code reforge} = the Greater Spook reforge's
     * per-rarity Fear (reforges data: EPIC 4, LEGENDARY 5), {@code potion} = the Dread potion's +3, plus the Eerie pet's
     * Fearcreasing (+perTen per 10 Primal Fears slain, at most 150 counted).
     */
    static int fearSum(int gear, int reforge, int potion, long primalFearKills, double perTen) {
        int f = Math.max(0, gear) + Math.max(0, reforge) + Math.max(0, potion);
        if (perTen > 0) f += (int) Math.floor(Math.min(150, Math.max(0, primalFearKills)) / 10 * perTen);
        return f;
    }

    /**
     * Dungeon floor roll (research items.json {@code tiered_stats}): what an item stamped {@code item_tier = tier} adds
     * over the def's lowest roll (index 0). Tiers past the end use the last roll.
     */
    static double tierDelta(double[] rolls, int tier) {
        if (rolls == null || rolls.length < 2 || tier <= 0) return 0;
        return rolls[Math.min(tier, rolls.length - 1)] - rolls[0];
    }

    /** "Entrance", "Floor 1".."Floor 7", then "Master 1".. for item_tier 0..: the floor a roll belongs to. */
    static String floorName(int tier) {
        if (tier <= 0) return "Entrance";
        if (tier <= 7) return "Floor " + tier;
        return "Master " + (tier - 7);
    }

    /** Adaptive Blade (Healer): the part of a life-steal heal shared with each nearby ally (research 06: 33%). */
    static double healerShare(double healed, double pct) {
        if (healed <= 0 || pct <= 0) return 0;
        return healed * Math.min(100, pct) / 100.0;
    }

    /** Only the best accessory of a family counts (Great Spook Talisman 2 / Ring 4 / Artifact 6). */
    static int bestOf(int... values) {
        int b = 0;
        for (int v : values) b = Math.max(b, v);
        return b;
    }

    /** Eerie "Fearama" (research 06): +pct% damage to spooky mobs per Fear. */
    static double fearama(int fear, double pctPerFear) { return Math.max(0, fear) * Math.max(0, pctPerFear); }

    /** Ghoul "Army of the Dead" (research 06): +2 souls stored while the perk is active. */
    static int soulCapacity(int base, boolean ghoulArmy) { return base <= 0 ? 0 : base + (ghoulArmy ? 2 : 0); }

    /** Chance (0..1) a kill drops its soul: the base % raised by the Ghoul's "+a% more often", capped at 100%. */
    static double soulChance(double basePct, double morePct) {
        return Math.max(0, Math.min(100, Math.max(0, basePct) * (1 + Math.max(0, morePct) / 100.0))) / 100.0;
    }

    /**
     * Adaptive Blade, Mage (research 06: "melee attacks restore 25% additional mana"): 25% of the mana a dungeon melee
     * hit already restores, which research 04 §3.5 puts at 5 + 1% of max mana. // TUNE: the base is the dungeons rule.
     */
    static double adaptiveMageMana(double maxMana, double pct) {
        return (5 + 0.01 * Math.max(0, maxMana)) * Math.max(0, pct) / 100.0;
    }
}
