package com.skyclone.mod.enchanting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The pure formulas behind the damage enchants and the XP economy (unit tested).
 * Numbers come from research 01 §14 via enchants.json; only the SHAPE of each formula lives here.
 */
public final class EnchantMath {

    private EnchantMath() {}

    // ---- damage ---------------------------------------------------------------------------------

    /** Execute: +v% per 1% of the target's MISSING health. */
    public static double execute(double perPct, double hp, double maxHp) {
        if (maxHp <= 0) return 0;
        double missing = Math.max(0, Math.min(100, (1 - hp / maxHp) * 100));
        return perPct * missing;
    }

    /** Prosecute: +v% per 1% of the target's CURRENT health. */
    public static double prosecute(double perPct, double hp, double maxHp) {
        if (maxHp <= 0) return 0;
        double cur = Math.max(0, Math.min(100, hp / maxHp * 100));
        return perPct * cur;
    }

    /** Giant Killer: +v% per 1% of max health the target has ABOVE the player, capped. */
    public static double giantKiller(double perPct, double cap, double targetMax, double playerMax) {
        if (playerMax <= 0 || targetMax <= playerMax) return 0;
        double over = (targetMax - playerMax) / playerMax * 100;
        return Math.min(cap, perPct * over);
    }

    /** Titan Killer: +v% per 100 target Defense, capped. */
    public static double titanKiller(double per100, double cap, double defense) {
        if (defense <= 0) return 0;
        return Math.min(cap, per100 * Math.floor(defense / 100.0));
    }

    /**
     * Lethality: the target's Defense is lowered by {@code pct} percent; returned as the damage
     * multiplier that has the same effect on the mob-side formula {@code x 100/(100+defense)}.
     */
    public static double defenseShredMultiplier(double defense, double pct) {
        if (defense <= 0 || pct <= 0) return 1.0;
        double shred = Math.min(100, pct) / 100.0;
        return (100 + defense) / (100 + defense * (1 - shred));
    }

    /**
     * Extra Defense against one kind of hit (Blast/Projectile Protection, Tidal, Last Stand), applied
     * as a multiplier on damage the player takes: {@code (100 + D) / (100 + D + extra)}.
     */
    public static double extraDefenseMultiplier(double defense, double extra) {
        if (extra <= 0) return 1.0;
        double d = Math.max(0, defense);
        return (100 + d) / (100 + d + extra);
    }

    /** Snipe: +v% per 10 blocks the arrow travelled. */
    public static double snipe(double per10, double distance) {
        return per10 * Math.floor(Math.max(0, distance) / 10.0);
    }

    /**
     * Strength added to one crit (Soul Eater): the hit already scales by (1 + str/100), so adding
     * {@code bonus} Strength multiplies it by (1 + (str + bonus)/100) / (1 + str/100).
     */
    public static double strengthBonusMultiplier(double strength, double bonus) {
        double base = 1 + Math.max(0, strength) / 100.0;
        return (1 + (Math.max(0, strength) + Math.max(0, bonus)) / 100.0) / base;
    }

    // ---- vanilla experience ----------------------------------------------------------------------

    /** Total experience points needed to reach a level from zero (vanilla formula). */
    public static long pointsForLevel(int level) {
        if (level <= 0) return 0;
        if (level <= 16) return (long) level * level + 6L * level;
        if (level <= 31) return Math.round(2.5 * level * level - 40.5 * level + 360);
        return Math.round(4.5 * level * level - 162.5 * level + 2220);
    }

    // ---- mob families ---------------------------------------------------------------------------

    /**
     * The enchant-relevant families of a mob ("undead", "arthropod", "ender", "cubic", "airborne",
     * "aquatic", "magmatic", "infernal", "woodland", "skeletal", "wither"), from its entity type,
     * its MobDef flags and an explicit {@code extra.types} list a module may put on the MobDef.
     */
    public static Set<String> mobTypes(String entity, Collection<String> flags, Collection<String> explicit, String id) {
        Set<String> t = new HashSet<>();
        if (explicit != null) for (String s : explicit) if (s != null) t.add(s.toLowerCase(Locale.ROOT));
        String e = entity == null ? "" : entity.toUpperCase(Locale.ROOT);
        String i = id == null ? "" : id.toLowerCase(Locale.ROOT);
        switch (e) {
            case "ZOMBIE", "ZOMBIE_VILLAGER", "HUSK", "DROWNED", "ZOMBIFIED_PIGLIN", "ZOGLIN", "PHANTOM", "ZOMBIE_HORSE", "GIANT" -> t.add("undead");
            case "SKELETON", "STRAY", "BOGGED", "SKELETON_HORSE" -> { t.add("undead"); t.add("skeletal"); }
            case "WITHER_SKELETON" -> { t.add("undead"); t.add("skeletal"); t.add("wither"); t.add("infernal"); }
            case "WITHER" -> { t.add("undead"); t.add("wither"); t.add("airborne"); }
            case "SPIDER", "CAVE_SPIDER", "SILVERFISH", "BEE" -> t.add("arthropod");
            case "ENDERMITE" -> { t.add("arthropod"); t.add("ender"); }
            case "ENDERMAN", "SHULKER" -> t.add("ender");
            case "ENDER_DRAGON" -> { t.add("ender"); t.add("airborne"); }
            case "SLIME", "CREEPER" -> t.add("cubic");
            case "MAGMA_CUBE" -> { t.add("cubic"); t.add("magmatic"); t.add("infernal"); }
            case "BLAZE" -> { t.add("magmatic"); t.add("infernal"); t.add("airborne"); }
            case "GHAST", "HAPPY_GHAST" -> { t.add("magmatic"); t.add("infernal"); t.add("airborne"); }
            case "STRIDER" -> { t.add("magmatic"); t.add("infernal"); }
            case "PIGLIN", "PIGLIN_BRUTE", "HOGLIN" -> t.add("infernal");
            case "BAT", "VEX", "ALLAY", "PARROT", "BREEZE" -> t.add("airborne");
            case "GUARDIAN", "ELDER_GUARDIAN", "SQUID", "GLOW_SQUID", "DOLPHIN", "COD", "SALMON", "PUFFERFISH",
                 "TROPICAL_FISH", "TURTLE", "AXOLOTL", "TADPOLE", "FROG" -> t.add("aquatic");
            case "WOLF", "FOX", "OCELOT", "RABBIT", "PANDA", "GOAT", "ARMADILLO" -> t.add("woodland");
            default -> {}
        }
        if (e.equals("DROWNED")) t.add("aquatic");
        if (flags != null) {
            if (flags.contains("sea_creature")) t.add("aquatic");
            if (flags.contains("flying")) t.add("airborne");
        }
        if (i.contains("dragon")) { t.add("ender"); t.add("airborne"); }
        if (i.startsWith("end_") || i.contains("zealot") || i.contains("voidgloom")) t.add("ender");
        if (i.contains("treant") || i.contains("dryad") || i.startsWith("park_") || i.contains("forest")) t.add("woodland");
        return t;
    }

    // ---- wave 3 helpers ----------------------------------------------------------------------------

    /** A gemstone block (stained glass or pane) by material name — Lapidary's mining-speed half. */
    public static boolean isGemstoneBlock(String material) {
        if (material == null) return false;
        String n = material.toUpperCase(Locale.ROOT);
        return n.endsWith("STAINED_GLASS") || n.endsWith("STAINED_GLASS_PANE");
    }

    /** The One: {"collections":[{"id":"WHEAT","tiers":[{"tier":1},...]}]} -> {WHEAT: highest tier}. */
    public static Map<String, Integer> collectionTops(JsonElement root) {
        Map<String, Integer> out = new HashMap<>();
        if (root == null || !root.isJsonObject()) return out;
        JsonElement cols = root.getAsJsonObject().get("collections");
        if (cols == null || !cols.isJsonArray()) return out;
        for (JsonElement el : (JsonArray) cols) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id") || !o.has("tiers") || !o.get("tiers").isJsonArray()) continue;
            int top = 0;
            for (JsonElement t : o.getAsJsonArray("tiers")) {
                if (t.isJsonObject() && t.getAsJsonObject().has("tier")) top = Math.max(top, t.getAsJsonObject().get("tier").getAsInt());
            }
            if (top > 0) out.put(o.get("id").getAsString(), top);
        }
        return out;
    }

    /** Piercing: the share of the arrow's damage this victim takes (first target full, every later one pct%). */
    public static double piercingShare(int victimNo, double pct) {
        return victimNo <= 1 ? 1.0 : Math.max(0, pct) / 100.0;
    }

    /** Duplex: the fire-damage multiplier kept on a victim — a stronger mark replaces a weaker one, never the reverse. */
    public static double duplexMark(double current, double incoming) {
        return Math.max(1.0, Math.max(current, incoming));
    }
}
