package com.skyclone.mod.galatea;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Trophy Frogs, pure (no Bukkit) so the rules are unit-tested. Twelve species x four qualities
 * (Bronze/Silver/Gold/Diamond): the item ids, names, rarities and catch conditions are the reference
 * game's (NEU {@code *_FROG_BRONZE} cards, storage's sack families); the per-catch odds are ours and
 * mirror trophy fishing's tier table and pity (marked TUNE).
 */
public final class FrogMath {

    private FrogMath() {}

    public enum Tier {
        BRONZE("&8", "BRONZE"), SILVER("&7", "SILVER"), GOLD("&6", "GOLD"), DIAMOND("&b", "DIAMOND");
        public final String color, label;
        Tier(String color, String label) { this.color = color; this.label = label; }
    }

    /** What the player was doing when the line came in. */
    public static final class Context {
        public boolean onAtoll = true, raining, midair, underwater, onLeaves, inCave, onHighlands, blessed, froggles,
                redSweater, flying, amongLilyPads;
    }

    /** A species: {@code id} is the item-id stem ({@code COMMON_FROG} -> COMMON_FROG_BRONZE). */
    public record Species(String id, String name, String rarity, String condition, String hint, int weight) {
        public String item(Tier t) { return id + "_" + t.name(); }
    }

    // weights: COMMON 50 / UNCOMMON 20 / RARE 9 / EPIC 5 / LEGENDARY 1   // TUNE (no published table)
    public static final List<Species> SPECIES = List.of(
            new Species("COMMON_FROG", "Common Frog", "COMMON", "any", "Caught anywhere on the Atoll.", 50),
            new Species("LEAP_FROG", "Leap Frog", "UNCOMMON", "midair", "Caught while you are in mid-air.", 20),
            new Species("EXPLODING_FROG", "Exploding Frog", "UNCOMMON", "lily_pads", "Caught standing among a raft of lily pads.", 20),
            new Species("REALITY_HOPPER", "Reality Hopper", "UNCOMMON", "froggles", "Caught while wearing Frog Goggles (they show the wormholes).", 20),
            new Species("WETLANDS_FROG", "Wetlands Frog", "UNCOMMON", "rain", "Caught while the Atoll is raining.", 20),
            new Species("SEA_FROG", "Sea Frog", "RARE", "underwater", "Caught with your head under water.", 9),
            new Species("BULLFROG", "Bullfrog", "RARE", "red_sweater", "Caught while wearing a Red Sweater.", 9),
            new Species("BLESSED_FROG", "Blessed Frog", "RARE", "blessed", "Caught while a Frogcoin blessing is active.", 9),
            new Species("TREE_FROG", "Tree Frog", "EPIC", "leaves", "Caught while standing on leaves.", 5),
            new Species("CAVE_FROG", "Cave Frog", "EPIC", "cave", "Caught in the Lotus Eater's Cave.", 5),
            new Species("HIGHLANDS_FROG", "Highlands Frog", "EPIC", "highlands", "Caught on the Lotus Highlands.", 5),
            new Species("PUDDLE_JUMPER", "Puddle Jumper", "LEGENDARY", "flying", "Caught while flying over the Atoll (or dropped by the Puddle Jumper).", 1));

    public static Species species(String stem) {
        for (Species s : SPECIES) if (s.id.equals(stem)) return s;
        return null;
    }

    /** "COMMON_FROG_GOLD" -> {species, tier}, or null. */
    public static Object[] parse(String itemId) {
        if (itemId == null) return null;
        for (Tier t : Tier.values()) {
            String suf = "_" + t.name();
            if (itemId.endsWith(suf)) {
                Species s = species(itemId.substring(0, itemId.length() - suf.length()));
                if (s != null) return new Object[]{s, t};
            }
        }
        return null;
    }

    public static boolean holds(Species s, Context c) {
        if (!c.onAtoll) return false;
        return switch (s.condition) {
            case "any" -> true;
            case "midair" -> c.midair;
            case "lily_pads" -> c.amongLilyPads;
            case "froggles" -> c.froggles;
            case "rain" -> c.raining;
            case "underwater" -> c.underwater;
            case "red_sweater" -> c.redSweater;
            case "blessed" -> c.blessed;
            case "leaves" -> c.onLeaves;
            case "cave" -> c.inCave;
            case "highlands" -> c.onHighlands;
            case "flying" -> c.flying;
            default -> false;
        };
    }

    public static List<Species> eligible(Context c) {
        List<Species> out = new ArrayList<>();
        for (Species s : SPECIES) if (holds(s, c)) out.add(s);
        return out;
    }

    /** Weighted pick among the eligible species; {@code roll} in [0,1). Null when none qualify. */
    public static Species pick(List<Species> eligible, double roll) {
        int total = 0;
        for (Species s : eligible) total += s.weight;
        if (total <= 0) return null;
        double r = roll * total;
        for (Species s : eligible) { r -= s.weight; if (r < 0) return s; }
        return eligible.get(eligible.size() - 1);
    }

    /** Chance a catch on the Atoll also brings a frog: base x (1 + Trophy Chance/100), capped at 1. */
    public static double frogChance(double base, double trophyChance) {
        return Math.min(1.0, Math.max(0, base) * (1 + Math.max(0, trophyChance) / 100.0));
    }

    /**
     * The quality: Diamond 0.2%, Gold 2%, Silver 25%, else Bronze (trophy fishing's table, TUNE), each
     * scaled by (1 + TC/100). Pity: {@code sinceGold} >= goldPity forces Gold, {@code sinceDiamond} >=
     * diamondPity forces Diamond.
     */
    public static Tier tier(double roll, double trophyChance, int sinceGold, int sinceDiamond, int goldPity, int diamondPity) {
        if (diamondPity > 0 && sinceDiamond >= diamondPity) return Tier.DIAMOND;
        if (goldPity > 0 && sinceGold >= goldPity) return Tier.GOLD;
        double m = 1 + Math.max(0, trophyChance) / 100.0;
        if (roll < 0.002 * m) return Tier.DIAMOND;
        if (roll < 0.022 * m) return Tier.GOLD;
        if (roll < 0.272 * m) return Tier.SILVER;
        return Tier.BRONZE;
    }

    /** One whole roll (frog or not) with a seeded random - what the listener and /sb galatea simulate use. */
    public static String roll(Context c, Random rng, double base, double tc, int sinceGold, int sinceDiamond, int goldPity, int diamondPity) {
        if (rng.nextDouble() >= frogChance(base, tc)) return null;
        Species s = pick(eligible(c), rng.nextDouble());
        if (s == null) return null;
        return s.item(tier(rng.nextDouble(), tc, sinceGold, sinceDiamond, goldPity, diamondPity));
    }

    // ---- collector titles ------------------------------------------------------------------------

    public static final String[] TITLES = {"", "Novice Frog Collector", "Adept Frog Collector", "Expert Frog Collector", "Master Frog Collector"};

    /** Title 1..4 = every species caught at Bronze / Silver / Gold / Diamond quality or better. */
    public static int title(Map<String, Integer> caught) {
        int best = 0;
        for (Tier t : Tier.values()) {
            boolean all = true;
            for (Species s : SPECIES) if (bestTier(caught, s) < t.ordinal()) { all = false; break; }
            if (all) best = t.ordinal() + 1; else break;
        }
        return best;
    }

    /** Best tier ordinal caught of a species, -1 when never. */
    public static int bestTier(Map<String, Integer> caught, Species s) {
        int best = -1;
        for (Tier t : Tier.values()) if (caught != null && caught.getOrDefault(s.item(t), 0) > 0) best = t.ordinal();
        return best;
    }

    // ---- releasing frogs to the professor for Lotus ----------------------------------------------

    /** Lotus value of one frog: rarity base x quality multiplier. // TUNE (research: "Trophy Frogs -> Lotus currency", no table). */
    public static long lotusValue(Species s, Tier t) {
        long base = switch (s.rarity) {
            case "COMMON" -> 4; case "UNCOMMON" -> 8; case "RARE" -> 16; case "EPIC" -> 32; default -> 64;
        };
        long mult = switch (t) { case BRONZE -> 1; case SILVER -> 4; case GOLD -> 16; case DIAMOND -> 64; };
        return base * mult;
    }

    /** Silver Lotus = 80 Lotus, Gold = 80 Silver, Diamond = 20 Gold (the crafting recipes: 5x16, 5x16, 5x4). */
    public static final long SILVER = 80, GOLD = 80 * 80, DIAMOND = 20 * 80 * 80;

    /** Lotus units -> {diamond, gold, silver, lotus}, largest first. */
    public static long[] denominations(long lotus) {
        long d = lotus / DIAMOND; lotus -= d * DIAMOND;
        long g = lotus / GOLD; lotus -= g * GOLD;
        long s = lotus / SILVER; lotus -= s * SILVER;
        return new long[]{d, g, s, lotus};
    }
}
