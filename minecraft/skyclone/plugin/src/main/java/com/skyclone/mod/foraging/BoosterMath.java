package com.skyclone.mod.foraging;

import java.util.Locale;

/**
 * Boosters, pure (no Bukkit): what each one grants and whether it may go on an item. Research has the boosters'
 * names, the items they go on ("Foraging Armor, Equipment, or Axes") and that the Uncommon one is an UPGRADE of a
 * Common one already on the item - but no numbers, so every value here is ours (TUNE, scaled by dial
 * {@code foraging.booster-scale}). One booster per item; a different kind replaces nothing (refused).
 */
final class BoosterMath {

    private BoosterMath() {}

    /** The five kinds. {@code common}/{@code uncommon} are the per-item amounts (Fighting = % damage). */
    enum Kind {
        SWEEP("Sweep", "&2", 1, 2),
        FORAGING_FORTUNE("Foraging Fortune", "&6", 5, 10),
        FORAGING_WISDOM("Foraging Wisdom", "&3", 0.5, 1),
        HUNTING_WISDOM("Hunting Wisdom", "&3", 0.5, 1),
        FIGHTING("Fighting", "&c", 5, 10);

        final String display, color;
        final double common, uncommon;

        Kind(String display, String color, double common, double uncommon) {
            this.display = display; this.color = color; this.common = common; this.uncommon = uncommon;
        }

        double value(int tier) { return tier >= 2 ? uncommon : tier == 1 ? common : 0; }

        static Kind parse(String s) {
            if (s == null) return null;
            try { return valueOf(s.trim().toUpperCase(Locale.ROOT).replace(' ', '_')); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /**
     * May a booster of {@code kind}/{@code tier} go on an item that currently carries {@code curKind}/{@code curTier}
     * (null/0 = none)? Returns null when it may, else the reason shown to the player.
     */
    static String check(Kind kind, int tier, Kind curKind, int curTier) {
        if (kind == null) return "That is not a booster.";
        if (tier <= 1) {
            if (curKind == null || curTier <= 0) return null;
            return curKind == kind ? "This item already has a " + kind.display + " Booster." : "This item already has a " + curKind.display + " Booster.";
        }
        if (curKind == null || curTier <= 0) return "Apply a Common " + kind.display + " Booster first.";
        if (curKind != kind) return "This item has a " + curKind.display + " Booster, not a " + kind.display + " one.";
        if (curTier >= 2) return "This booster is already Uncommon.";
        return null;
    }

    /** Karma's value past its last listed level (Forest's Favor lifts Karma V to VI): the last step repeated. */
    static double extrapolate(double[] values, int level) {
        if (values == null || values.length == 0 || level <= 0) return 0;
        if (level <= values.length) return values[level - 1];
        double last = values[values.length - 1];
        double step = values.length >= 2 ? last - values[values.length - 2] : last;
        return last + step * (level - values.length);
    }
}
