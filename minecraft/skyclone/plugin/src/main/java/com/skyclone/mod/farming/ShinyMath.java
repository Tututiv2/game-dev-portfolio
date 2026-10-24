package com.skyclone.mod.farming;

/**
 * The pure half of Shiny Pigs and the Dark Cacao Truffle (wave 5): bestiary tiers, coin payout, the flee step and the
 * truffle's cocoa fortune. No Bukkit, so the unit tests run the shipping numbers.
 */
final class ShinyMath {

    private ShinyMath() {}

    /** Bestiary tier for a kill count: how many thresholds are reached (NEU bracket 5 for Shiny Pigs, 15 tiers to 400). */
    static int tier(long kills, int[] thresholds) {
        if (thresholds == null) return 0;
        int t = 0;
        for (int k : thresholds) if (kills >= k) t++; else break;
        return t;
    }

    /** Pig pet "Shining Stampede": +{perTier} Potato Fortune per Shiny Pig bestiary tier (research 06: +5 at Lv100 Epic+). */
    static double stampedeFortune(double perTier, int tier) {
        if (!(perTier > 0) || tier <= 0) return 0;
        return perTier * tier;
    }

    /**
     * Coins a delivered Shiny Pig pays: {@code (base + perLevel x Farming level)}, then the Pig pet's Hamfisted
     * ({@code +ham%}, research 06: 50% at Lv100 Epic/Legendary) and the Shiny Rod ({@code +rod%}, NEU lore: "Extract 25%
     * extra coins from shiny orbs"), multiplied. TUNE: the reference's base payout is not in the data.
     */
    static long coins(double base, double perLevel, int farmingLevel, double hamPercent, double rodPercent) {
        double v = Math.max(0, base) + Math.max(0, perLevel) * Math.max(0, farmingLevel);
        v *= 1 + Math.max(0, hamPercent) / 100.0;
        v *= 1 + Math.max(0, rodPercent) / 100.0;
        return (long) Math.floor(v);
    }

    /**
     * Where a fleeing pig runs next (x, z): {@code step} blocks straight away from the player; if that would take it
     * further than {@code maxFromOrb} from its orb, it runs along the circle instead (the tangent on the side away from
     * the player), so a chase never drags it off across the island. A player standing exactly on the pig sends it +x.
     */
    static double[] fleeTarget(double pigX, double pigZ, double playerX, double playerZ, double orbX, double orbZ,
                               double step, double maxFromOrb) {
        double ax = pigX - playerX, az = pigZ - playerZ;
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1e-6) { ax = 1; az = 0; len = 1; }
        ax /= len; az /= len;
        double tx = pigX + ax * step, tz = pigZ + az * step;
        double ox = tx - orbX, oz = tz - orbZ;
        if (ox * ox + oz * oz <= maxFromOrb * maxFromOrb) return new double[]{tx, tz};
        // tangent to the orb circle at the pig: perpendicular to (pig - orb); pick the side further from the player
        double rx = pigX - orbX, rz = pigZ - orbZ;
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-6) { rx = 1; rz = 0; rl = 1; }
        rx /= rl; rz /= rl;
        double t1x = -rz, t1z = rx;          // one tangent
        double t2x = rz, t2z = -rx;          // the other
        double d1 = sq(pigX + t1x * step - playerX) + sq(pigZ + t1z * step - playerZ);
        double d2 = sq(pigX + t2x * step - playerX) + sq(pigZ + t2z * step - playerZ);
        double sx = d1 >= d2 ? t1x : t2x, sz = d1 >= d2 ? t1z : t2z;
        // and pull it back inside a little so it never leaves the circle for good
        double inX = -rx * 0.35, inZ = -rz * 0.35;
        return new double[]{pigX + (sx + inX) * step, pigZ + (sz + inZ) * step};
    }

    /** Is a caught pig close enough to its orb to be delivered? Horizontal radius, plus 3 blocks of height. */
    static boolean delivered(double dx, double dy, double dz, double radius) {
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= 3;
    }

    /**
     * Refined Dark Cacao Truffle (NEU lore): +{buff} Cocoa Beans Fortune while the 60-minute buff runs, plus +1
     * permanent per truffle eaten, up to {@code maxPermanent}.
     */
    static double truffleFortune(int eaten, int maxPermanent, boolean buffActive, double buff) {
        double v = Math.max(0, Math.min(eaten, Math.max(0, maxPermanent)));
        if (buffActive) v += Math.max(0, buff);
        return v;
    }

    private static double sq(double v) { return v * v; }
}
