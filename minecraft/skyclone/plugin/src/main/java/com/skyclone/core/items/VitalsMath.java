package com.skyclone.core.items;

import com.skyclone.api.ui.Text;

/** Pure formulas for health, mana and the HUD. Unit-tested. */
public final class VitalsMath {

    private VitalsMath() {}

    /**
     * HP restored per regen tick: {@code (1.5 + max/100) * HealthRegen/100 * mult}
     * (API javadoc; research 01 §6.1 gives the same formula).
     */
    public static double healthRegen(double maxHealth, double healthRegenStat, double mult) {
        if (maxHealth <= 0 || healthRegenStat <= 0 || mult <= 0) return 0;
        return (1.5 + maxHealth / 100.0) * healthRegenStat / 100.0 * mult;
    }

    /** Mana per second: {@code pct% of max * (1 + ManaRegen/100)} (research 01 §7: 2%). */
    public static double manaRegenPerSecond(double maxMana, double manaRegenStat, double pct) {
        if (maxMana <= 0 || pct <= 0) return 0;
        return maxMana * pct / 100.0 * Math.max(0, 1 + manaRegenStat / 100.0);
    }

    /**
     * Vanilla health scale (half-hearts shown) for a max health. 10 hearts at 100 HP growing to
     * 20 hearts. TUNE: thresholds follow the reference game's observed heart steps
     * (125/165/230/300/400/500/650/800/1000/1250); research has no exact table.
     */
    public static double heartScale(double maxHealth, double cap) {
        double[] steps = {125, 165, 230, 300, 400, 500, 650, 800, 1000, 1250};
        int hearts = 10;
        for (double s : steps) if (maxHealth >= s) hearts++;
        double scale = hearts * 2.0;
        return Math.max(2, Math.min(Math.max(2, cap), scale));
    }

    /** The vanilla health value that shows {@code hp/max} on a {@code vanillaMax} bar; never 0 while alive. */
    public static double gauge(double hp, double max, double vanillaMax) {
        if (max <= 0) return vanillaMax;
        double v = vanillaMax * hp / max;
        if (v < 0.5) v = 0.5;
        return Math.min(vanillaMax, v);
    }

    /** How far below a respawn spot's feet a floor may be (builders allow a 4-block drop). */
    public static final int FLOOR_SEARCH_DEPTH = 8;

    /**
     * Is there a floor within {@code depth} blocks at or below {@code feetY} (never below the
     * world's {@code minY})? A respawn spot without one is a void-death loop.
     */
    public static boolean hasFloor(int feetY, int minY, int depth, java.util.function.IntPredicate floorAt) {
        int bottom = Math.max(minY, feetY - Math.max(0, depth));
        for (int y = feetY; y >= bottom; y--) if (floorAt.test(y)) return true;
        return false;
    }

    /**
     * World x (or z) of the centre of the instance slot a coordinate lies in: slots sit
     * {@code spacing} apart (at least 16) with slot 0 at 0, matching core.world's instance grid.
     */
    public static double slotOrigin(double coord, int spacing) {
        int sp = Math.max(16, spacing);
        return Math.round(coord / sp) * (double) sp;
    }

    /**
     * Coins lost on death, rounded DOWN to a whole coin (the player keeps the fraction). Playtest
     * "endgame": a tenth-rounded loss read "lost 6,247,670.5 coins!".
     */
    public static double deathLoss(double purse, double fraction) {
        if (!(purse > 0) || !(fraction > 0)) return 0;
        return wholeCoins(purse * Math.min(1, fraction));
    }

    /** Whole coins, rounded down; NaN / negative = 0. Applied again after SkyDeath listeners scale the loss. */
    public static double wholeCoins(double coins) {
        if (!(coins > 0) || Double.isInfinite(coins)) return 0;
        return Math.floor(coins);
    }

    /** "&c1,234/1,500❤     &a612❈ Defense     &b850/950✎ Mana"; {@code middle} replaces the defense part when non-null. */
    public static String actionBar(double hp, double max, double absorption, double defense, double mana, double maxMana, String middle) {
        String hpCol = absorption > 0 ? "&6" : "&c";
        String hpPart = hpCol + Text.commasInt(Math.ceil(Math.max(0, hp + absorption))) + "/" + Text.commasInt(Math.ceil(max)) + "❤";
        String mid = middle != null ? middle : "&a" + Text.commasInt(Math.max(0, defense)) + "❈ Defense";
        String manaPart = "&b" + Text.commasInt(Math.max(0, mana)) + "/" + Text.commasInt(Math.max(0, maxMana)) + "✎ Mana";
        return hpPart + "     " + mid + "     " + manaPart;
    }

    /**
     * A pool's value after its maximum moved from {@code oldMax} to {@code newMax}: the FILL FRACTION
     * is kept, so taking armour off and putting a set back on returns you where you were rather than
     * clamped to the naked maximum (playtest-loops: 596/596 -> 117/117 and still 117/700 after the
     * new set went on, with regen needing a minute to refill it). A full pool stays exactly full
     * (no float drift from 596*117/596), an empty one stays empty, and an unknown old maximum
     * (fresh state, first recompute) just clamps.
     */
    public static double rescale(double value, double oldMax, double newMax) {
        if (Double.isNaN(value) || newMax <= 0) return Math.max(0, Double.isNaN(value) ? 0 : Math.min(value, Math.max(0, newMax)));
        if (value <= 0) return 0;
        if (!(oldMax > 0) || Double.isInfinite(oldMax) || oldMax == newMax) return Math.min(value, newMax);
        if (value >= oldMax - 1e-6) return newMax;
        return Math.min(newMax, value * newMax / oldMax);
    }

    /** Cost of an ability's mana field: a value in (0,1) is a fraction of max mana. */
    public static double manaCost(double field, double maxMana) {
        if (field <= 0) return 0;
        if (field < 1) return Math.max(0, maxMana * field);
        return field;
    }
}
