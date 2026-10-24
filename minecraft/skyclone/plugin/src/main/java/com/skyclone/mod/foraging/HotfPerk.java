package com.skyclone.mod.foraging;

import java.util.List;

/**
 * One Heart of the Forest perk as data (pure). Costs follow research 07 §4.5:
 * {@code floor((next+1)^exp)} whispers, keystones {@code (next+7)^5}, Daily Wishes {@code 200 + 18n}.
 * {@code tier} is the HotF tier that unlocks it; the first level costs one token.
 */
public record HotfPerk(String id, String name, int tier, int slot, boolean desert, int max, double exp, Kind kind,
                       String icon, String exclusive, List<String> describe) {

    public enum Kind { POWER, LINEAR, KEYSTONE, CORE, TOKEN }

    public long cost(int current) {
        return switch (kind) {
            case LINEAR -> SweepMath.linearCost(current, 200, 18);
            case KEYSTONE -> SweepMath.keystoneCost(current);
            case TOKEN -> 0;
            case CORE -> current == 0 ? 0 : current == 1 ? 50_000 : current == 2 ? 100_000 : current == 3 ? 200_000 : 300_000;
            default -> SweepMath.perkCost(current, exp);
        };
    }

    /** Which currency the NEXT level costs ("forest"/"desert"); the core switches to desert at level 4. */
    public boolean desertFor(int current) { return kind == Kind.CORE ? current >= 3 : desert; }

    public String currency(int current) { return desertFor(current) ? "Desert Whispers" : "Forest Whispers"; }

    /** The 26 perks we ship (wave 5 added the hunting, diving, contest, safari and the two daily perks). */
    public static final List<HotfPerk> ALL = List.of(
            p("center", "Center of the Forest", 1, 40, false, 5, 0, Kind.CORE, "OAK_SAPLING", null,
                    "Level I gives a token. Level II: +5% Sweep and Foraging", "Fortune, III: +10% whispers, IV: +10% gift loot,",
                    "V: one more token. Levels IV-V cost Desert Whispers."),
            p("sweep", "Sweep", 1, 31, false, 50, 3.0, Kind.POWER, "IRON_AXE", null, "Grants &2+{1}∮ Sweep&7."),
            p("foraging_fortune", "Foraging Fortune", 2, 30, false, 50, 3.05, Kind.POWER, "OAK_LOG", null, "Grants &6+{3}☘ Foraging Fortune&7."),
            p("luck_of_the_forest", "Luck of the Forest", 2, 32, false, 40, 3.07, Kind.POWER, "GOLD_NUGGET", null, "Tree Gifts give &a+{0.5}% &7more loot."),
            p("collector", "Collector", 2, 33, false, 50, 2.9, Kind.POWER, "SEA_PICKLE", null,
                    "&a+{2}% &7chance to pick double Velvet Lilac,", "Sea Lumies and Ruby Veilshroom."),
            p("galateas_might", "Moonmarsh Might", 3, 34, false, 50, 3.2, Kind.POWER, "GOLDEN_SWORD", null,
                    "&a+{0.5}% &7Health, Defense, Strength and", "Crit Damage while in the Moonmarsh."),
            p("daily_wishes", "Daily Wishes", 3, 22, true, 100, 0, Kind.LINEAR, "CLOCK", null,
                    "The first log of each wood you cut each", "day grants &b+{4} Forest &7and &e+{8} Desert Whispers&7."),
            p("efficient_forager", "Efficient Forager", 3, 28, true, 100, 2.6, Kind.POWER, "EXPERIENCE_BOTTLE", null, "Grants &3+{0.1}☯ Foraging Wisdom&7."),
            p("strength_boost", "Strength Boost", 4, 20, true, 50, 3.1, Kind.POWER, "BLAZE_POWDER", null, "Grants &c+{2}❁ Strength &7on foraging islands."),
            p("speed_boost", "Speed Boost", 4, 24, true, 50, 3.1, Kind.POWER, "SUGAR", null, "Grants &f+{2}✦ Speed &7on foraging islands."),
            p("forest_fisher", "Forest Fisher", 4, 14, true, 40, 3.07, Kind.POWER, "FISHING_ROD", null,
                    "Grants &b+{0.5}☂ Fishing Speed &7and &3+{0.1}α Sea", "Creature Chance &7on foraging islands."),
            p("half_full", "Half Full", 5, 10, true, 25, 4.1, Kind.POWER, "HONEY_BOTTLE", "half_empty",
                    "&6+{1}☘ Foraging Fortune &7and &2+{0.4}∮ Sweep &7for each", "player on your island with &aHalf Empty&7 (max 5)."),
            p("half_empty", "Half Empty", 5, 16, true, 25, 4.1, Kind.POWER, "GLASS_BOTTLE", "half_full",
                    "&6+{1}☘ Foraging Fortune &7and &2+{0.4}∮ Sweep &7for each", "player on your island with &aHalf Full&7 (max 5)."),
            p("essence_fortune", "Essence Fortune", 5, 12, true, 50, 3.2, Kind.POWER, "GHAST_TEAR", null, "&a+{0.5}% &7chance of double Forest Essence", "from Tree Gifts."),
            p("forest_strength", "Forest Strength", 6, 3, true, 50, 3.4, Kind.POWER, "IRON_INGOT", null,
                    "Gain &a{0.1}% &7of your &c❁ Strength &7as &6☘ Foraging", "Fortune &7and &2∮ Sweep &8(caps at 1,000 Strength)&7."),
            p("forest_speed", "Forest Speed", 6, 5, true, 50, 3.4, Kind.POWER, "FEATHER", null,
                    "Gain &a{0.2}% &7of your &f✦ Speed &7as &6☘ Foraging", "Fortune &7and &2∮ Sweep &8(caps at 500 Speed)&7."),
            p("gifts_250", "250 Gifts", 7, 1, true, 40, 3.07, Kind.POWER, "CHEST", null,
                    "Your first &a250 &7Tree Gifts each day give", "&a+{1}% &7loot and &b+20 &7more whispers."),
            p("ricochet", "Ricochet", 7, 7, true, 10, 5.5, Kind.POWER, "ARROW", null, "A thrown axe has a &a{1}% &7chance to bounce", "on to a second tree."),
            p("timber", "Timber", 8, 4, true, 10, 0, Kind.KEYSTONE, "DIAMOND_AXE", null,
                    "Every swing has a &a{1}% &7chance to fell", "the &awhole tree&7. &8(keystone)"),
            // ---- wave 5: research 07 §4.5 perks whose systems now exist (hunting, diving, contests, safari)
            p("hunters_luck", "Hunter's Luck", 2, 29, false, 50, 3.2, Kind.POWER, "LEAD", null, "Grants &d+{1}☘ Hunting Fortune&7."),
            p("pressure_resistance", "Pressure Resistance", 3, 35, false, 50, 2.9, Kind.POWER, "PRISMARINE_SHARD", null,
                    "Grants &9+{1}❍ Pressure Resistance&7, so you", "can dive deeper without harm."),
            p("iron_lungs", "Iron Lungs", 4, 26, true, 50, 2.9, Kind.POWER, "TURTLE_HELMET", null,
                    "Grants &3+{1}⚶ Respiration&7: hold your", "breath longer underwater."),
            p("starlyn_supreme", "Starfall Supreme", 5, 18, true, 100, 2.6, Kind.POWER, "NETHER_STAR", null,
                    "Gain &a+{0.2}% &7more points in Starfall", "Contests."),
            p("two_for_one", "Two-for-One", 6, 8, true, 100, 2.45, Kind.POWER, "NAME_TAG", null,
                    "&a+{0.25}% &7chance for an extra Safari", "Ticket from Canyon Starfall Contests."),
            p("daily_grove", "Wandering Grove", 3, 21, false, 1, 0, Kind.TOKEN, "COMPASS", null,
                    "Each day brings one of: &6+50 Fig&7,", "&6Mangrove &7or &6Spiral Fortune&7, or &2+10∮ Sweep&7."),
            p("daily_hive", "Hive Mind", 5, 23, false, 1, 0, Kind.TOKEN, "HONEYCOMB", null,
                    "Each day brings one of: hives refill &a20%", "faster, honey lures critters &a20% &7faster,",
                    "&a+2 &7Honeycomb per hive, or a &a25% &7chance", "of a second critter.")
    );

    private static HotfPerk p(String id, String name, int tier, int slot, boolean desert, int max, double exp, Kind kind,
                              String icon, String exclusive, String... lines) {
        return new HotfPerk(id, name, tier, slot, desert, max, exp, kind, icon, exclusive, List.of(lines));
    }

    public static HotfPerk byId(String id) {
        for (HotfPerk p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    /** The per-level value written in braces in a describe line ("+{3}" -> 3). */
    public static String render(String line, int level) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            int a = line.indexOf('{', i);
            if (a < 0) { sb.append(line, i, line.length()); break; }
            int b = line.indexOf('}', a);
            if (b < 0) { sb.append(line, i, line.length()); break; }
            sb.append(line, i, a);
            double per = Double.parseDouble(line.substring(a + 1, b));
            double v = per * Math.max(1, level);
            sb.append(v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v));
            i = b + 1;
        }
        return sb.toString();
    }
}
