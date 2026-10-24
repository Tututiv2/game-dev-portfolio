package com.skyclone.mod.hollows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The quests' fixed facts and the small pure formulas behind them (no Bukkit, unit-tested).
 * Crystal order matches the mining module's Nucleus sockets.
 */
public final class Lore {

    private Lore() {}

    public record Quest(String gem, String crystal, String zone, String structure, String giver, String how) {}

    public static final List<Quest> QUESTS = List.of(
            new Quest("AMETHYST", "AMETHYST_CRYSTAL", "&5Jungle", "Jungle Temple", "the Temple Altar",
                    "Use a Jungle Key at the shrine on top of the temple, cross the parkour inside and defeat the Door Guardian in the sanctum."),
            new Quest("JADE", "JADE_CRYSTAL", "&aMithril Deposits", "Mines of Varnas", "the four Keepers",
                    "Follow a Metal Detector to the chests buried under the hall floor, dig them open and return each lost tool to its Keeper."),
            new Quest("SAPPHIRE", "SAPPHIRE_CRYSTAL", "&bRelic Remnants", "Lost Relic City", "Professor Gearhart",
                    "Bring the Professor all six automaton parts (or an assembled apparatus)."),
            new Quest("AMBER", "AMBER_CRYSTAL", "&6Goblin Holdout", "Goblin King's Burrow", "King Grubnak",
                    "Offer the Goblin King any Goblin Egg, or help slay the Goblin Queen in her den south of the fire pit."),
            new Quest("TOPAZ", "TOPAZ_CRYSTAL", "&cMagma Fields", "Emberdeep", "Magmaw",
                    "Help defeat Magmaw in the Emberdeep arena. Every hit counts as one."));

    /** Keeper -> the tool their order lost. */
    public static final Map<String, String> KEEPER_TOOLS = new LinkedHashMap<>();
    static {
        KEEPER_TOOLS.put("lapis", "DWARVEN_LAPIS_SWORD");
        KEEPER_TOOLS.put("emerald", "DWARVEN_EMERALD_HAMMER");
        KEEPER_TOOLS.put("diamond", "DWARVEN_DIAMOND_AXE");
        KEEPER_TOOLS.put("gold", "DWARVEN_GOLD_HAMMER");
    }

    public static final List<String> ROBOT_PARTS = List.of("ELECTRON_TRANSMITTER", "FTX_3070", "ROBOTRON_REFLECTOR",
            "SUPERLITE_MOTOR", "CONTROL_SWITCH", "SYNTHETIC_HEART");
    public static final String APPARATUS = "PRECURSOR_APPARATUS";
    public static final List<String> GOBLIN_EGGS = List.of("GOBLIN_EGG", "GOBLIN_EGG_GREEN", "GOBLIN_EGG_YELLOW", "GOBLIN_EGG_RED", "GOBLIN_EGG_BLUE");
    public static final String METAL_DETECTOR = "DWARVEN_METAL_DETECTOR";

    public static Quest quest(String gem) {
        for (Quest q : QUESTS) if (q.gem().equalsIgnoreCase(gem)) return q;
        return null;
    }

    /** Within this many blocks of the buried spot, a right-click digs the treasure up. */
    public static final double DIG_RADIUS = 2.0;

    /** What the Metal Detector says at a distance: the closer, the hotter the word. */
    public static String reading(double distance) {
        String heat = distance <= DIG_RADIUS ? "&a&lRIGHT HERE!" : distance < 6 ? "&c&lBURNING" : distance < 12 ? "&6Hot"
                : distance < 24 ? "&eWarm" : distance < 40 ? "&bCool" : "&9Cold";
        return "&7Treasure: " + heat + " &8(" + String.format(java.util.Locale.ROOT, "%.1f", distance) + "m)";
    }

    /** Beep pitch for a distance (0.5 far .. 2.0 on top). */
    public static float pitch(double distance) {
        double t = Math.max(0, Math.min(1, 1 - distance / 40.0));
        return (float) (0.5 + 1.5 * t);
    }

    /** Hits needed to bring down a hit-counted boss. */
    public static int hitsToKill(double health, double damagePerHit) {
        if (damagePerHit <= 0) return Integer.MAX_VALUE;
        return (int) Math.ceil(health / damagePerHit);
    }

    /** Expected number of a per-hit drop over a whole fight. */
    public static double expectedDrops(double health, double damagePerHit, double chancePerHit) {
        return hitsToKill(health, damagePerHit) * chancePerHit;
    }
}
