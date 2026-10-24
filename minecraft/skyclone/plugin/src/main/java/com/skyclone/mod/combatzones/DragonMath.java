package com.skyclone.mod.combatzones;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The numbers behind the dragon fight, the Broodmother clock and the Colossus meter. Pure Java
 * (no Bukkit) so the unit tests run the shipping formulas.
 *
 * <p>Research 03 sec 14.3: seven variants, Superior 4%, the other six 16% each (Holy is ours,
 * default weight 0). Sec 14.4: loot "quality" = 100 per eye placed + a damage-rank bonus (1st +200,
 * 2nd +175, 3rd +150 ...); the player gets the most valuable drop the quality can buy, highest
 * weight first, and dragon fragments (22 weight each) when nothing is affordable.
 */
public final class DragonMath {

    private DragonMath() {}

    /** One dragon variant. {@code damageTakenMult} &lt; 1 = takes less damage (Protector). */
    public record Variant(String id, String name, double health, double damage, double weight,
                          double damageTakenMult, double abilityMult, double speedMult, char color) {}

    public static final List<Variant> VARIANTS = List.of(
            new Variant("young", "Young", 7_500_000, 1100, 16, 1.0, 1.0, 2.25, 'f'),
            new Variant("old", "Old", 15_000_000, 1100, 16, 1.0, 1.0, 0.8, '7'),
            new Variant("protector", "Protector", 9_000_000, 1100, 16, 0.75, 1.0, 1.0, '8'),   // TUNE: "reduced damage taken"
            new Variant("strong", "Strong", 9_000_000, 1100, 16, 1.0, 1.5, 1.0, 'c'),          // TUNE: "amplified attacks"
            new Variant("unstable", "Unstable", 9_000_000, 1100, 16, 1.0, 1.0, 1.0, '5'),
            new Variant("wise", "Wise", 9_000_000, 2200, 16, 1.0, 2.0, 1.0, 'b'),              // TUNE: "stronger magic attacks"
            new Variant("superior", "Superior", 12_000_000, 1650, 4, 1.0, 1.5, 1.0, '6'),
            new Variant("holy", "Holy", 9_000_000, 1100, 0, 1.0, 1.0, 1.0, 'e'));             // ours: off by default (dial)

    /**
     * The variant's colour as it is shown on dark backgrounds (sidebar, chat, titles, tooltips).
     * Protector's identity colour is dark gray ({@code &8}) and Unstable's dark purple ({@code &5});
     * on the sidebar both were unreadable (playtest-endgame), so dark codes map to their light twin.
     */
    public static char readable(char c) {
        return switch (Character.toLowerCase(c)) {
            case '0', '8' -> '7';
            case '1' -> '9';
            case '2' -> 'a';
            case '3' -> 'b';
            case '4' -> 'c';
            case '5' -> 'd';
            default -> c;
        };
    }

    public static Variant variant(String id) {
        for (Variant v : VARIANTS) if (v.id().equals(id)) return v;
        return null;
    }

    /** Weighted roll over variants with the given weights (variant id -> weight). */
    public static Variant roll(Map<String, Double> weights, Random r) {
        double total = 0;
        for (Variant v : VARIANTS) total += Math.max(0, weights.getOrDefault(v.id(), v.weight()));
        if (total <= 0) return VARIANTS.get(0);
        double x = r.nextDouble() * total;
        for (Variant v : VARIANTS) {
            x -= Math.max(0, weights.getOrDefault(v.id(), v.weight()));
            if (x < 0) return v;
        }
        return VARIANTS.get(VARIANTS.size() - 1);
    }

    // ---------------------------------------------------------------------------------- abilities

    /** Rush 550, Fireball 200 per hit, Lightning 200 (true damage), +50% below half health. */
    public static final double RUSH = 550, FIREBALL = 200, LIGHTNING = 200;

    public static double abilityDamage(double base, Variant v, double hpFraction) {
        double d = base * (v == null ? 1 : v.abilityMult());
        if (hpFraction < 0.5) d *= 1.5;
        return d;
    }

    // ---------------------------------------------------------------------------------- loot

    /** Damage-rank bonus by placement (1-based). Research gives 200/175/150 "..."; the tail is TUNE. */
    public static int rankBonus(int place) {
        return switch (place) {
            case 1 -> 200;
            case 2 -> 175;
            case 3 -> 150;
            case 4 -> 125;
            case 5 -> 110;
            case 6, 7, 8 -> 100;
            case 9, 10 -> 90;
            default -> place > 0 ? 50 : 0;     // TUNE: anyone else who dealt damage
        };
    }

    public static int quality(int eyes, int place) {
        return Math.max(0, eyes) * 100 + rankBonus(place);
    }

    /**
     * A drop on the dragon loot list. {@code perEyeChance} &gt; 0 = also needs a roll of chance x eyes;
     * {@code flatChance} &gt; 0 = also needs a roll of that chance, eyes or not (wave 5: the Dragon Horn's 30%).
     */
    public record LootEntry(String item, int weight, double perEyeChance, boolean variantSpecific, double flatChance) {
        public LootEntry(String item, int weight, double perEyeChance, boolean variantSpecific) {
            this(item, weight, perEyeChance, variantSpecific, 0);
        }
    }

    /**
     * Loot-quality weights, highest first. Wave 5: the weights are SkyHanni's {@code DragonProfitTrackerItems.json}
     * (Horn 452, Claw 451, pets 450, Wyrmbreath Blade 450, chestplate 410, leggings 360, helmet 295, boots 290, Nest
     * scroll 250 - research 03's "160-200" for claw/horn was a guess) and the roll chances are NEU's
     * {@code <VARIANT>_ENDER_DRAGON_BOSS} pages (pets .05% / .01% per eye, claw 2% per eye, blade 3% per eye, Horn a flat
     * 30% on the Superior Dragon only). The Nest travel scroll's chance is not published: TUNE 10%.
     */
    public static List<LootEntry> lootTable(String variant) {
        String v = variant.toUpperCase();
        List<LootEntry> l = new ArrayList<>();
        if (v.equals("SUPERIOR")) l.add(new LootEntry("DRAGON_HORN", 452, 0, false, 0.3));
        l.add(new LootEntry("DRAGON_CLAW", 451, 0.02, false));
        l.add(new LootEntry("ENDER_DRAGON;4", 450, 0.0001, false));
        l.add(new LootEntry("ENDER_DRAGON;3", 450, 0.0005, false));
        l.add(new LootEntry("ASPECT_OF_THE_DRAGON", 450, 0.03, false));
        l.add(new LootEntry(v + "_DRAGON_CHESTPLATE", 410, 0, true));
        l.add(new LootEntry(v + "_DRAGON_LEGGINGS", 360, 0, true));
        l.add(new LootEntry(v + "_DRAGON_HELMET", 295, 0, true));
        l.add(new LootEntry(v + "_DRAGON_BOOTS", 290, 0, true));
        l.add(new LootEntry("DRAGON_NEST_TRAVEL_SCROLL", 250, 0, false, 0.1));      // TUNE: chance unpublished
        l.sort(Comparator.comparingInt(LootEntry::weight).reversed());             // stable: ties keep the order above
        return l;
    }

    public static final int FRAGMENT_WEIGHT = 22;

    /** What one player gets: at most one item, else fragments. */
    public record Award(String item, int fragments) {}

    /**
     * The most valuable affordable drop (highest weight first; chance drops also need their roll,
     * chance x eyes placed); if none, {@code quality / 22} fragments, at least 1, at most {@code maxFrags}.
     * {@code exists} filters ids that are not registered in this build.
     */
    public static Award award(String variant, int quality, int eyes, Random r, java.util.function.Predicate<String> exists, int maxFrags) {
        for (LootEntry e : lootTable(variant)) {
            if (quality < e.weight()) continue;
            if (exists != null && !exists.test(e.item())) continue;
            if (e.perEyeChance() > 0 && r.nextDouble() >= e.perEyeChance() * Math.max(1, eyes)) continue;
            if (e.flatChance() > 0 && r.nextDouble() >= e.flatChance()) continue;
            return new Award(e.item(), 0);
        }
        int frags = Math.max(1, Math.min(maxFrags, quality / FRAGMENT_WEIGHT));
        return new Award(null, frags);
    }

    /** Places by damage, highest first (ties keep insertion order). */
    public static <K> Map<K, Integer> placements(Map<K, Double> damage) {
        List<Map.Entry<K, Double>> es = new ArrayList<>(damage.entrySet());
        es.removeIf(e -> e.getValue() <= 0);
        es.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<K, Integer> out = new LinkedHashMap<>();
        int i = 1;
        for (Map.Entry<K, Double> e : es) out.put(e.getKey(), i++);
        return out;
    }

    // ---------------------------------------------------------------------------------- broodmother

    /** The five visible stages of the Broodmother clock (research 03 sec 13.2). */
    public static final String[] BROOD_STAGES = {"Slain", "Dormant", "Soon", "Awakening", "Imminent", "Alive"};

    /** Stage index 0..4 from seconds since she was last slain; 5 means it is time to spawn. */
    public static int broodStage(long secondsSinceSlain, long cycleSeconds) {
        if (cycleSeconds <= 0) return 5;
        if (secondsSinceSlain >= cycleSeconds) return 5;
        return (int) Math.min(4, secondsSinceSlain * 5 / cycleSeconds);
    }

    // ---------------------------------------------------------------------------------- colossus

    /** Colossus stage 0..5 from end stone mined since the last one. */
    public static int protectorStage(long mined, long perStage) {
        if (perStage <= 0) return 5;
        return (int) Math.min(5, mined / perStage);
    }
}
