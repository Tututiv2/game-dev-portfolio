package com.skyclone.api.stat;

/**
 * Every player stat. Symbols and colours follow the genre's conventions so a stat reads the
 * same in lore, in the stats menu and on the action bar. {@code base} is what a fresh player
 * has with nothing equipped.
 *
 * <p>Colour is a legacy code character ('c' = red, 'a' = green...), used as "&amp;" + color.
 * {@code percent} stats display with a "%" suffix.
 */
public enum Stat {
    // ---- combat ----
    HEALTH("Health", "❤", 'c', 100, false),
    DEFENSE("Defense", "❈", 'a', 0, false),
    TRUE_DEFENSE("True Defense", "❂", 'f', 0, false),
    STRENGTH("Strength", "❁", 'c', 0, false),
    INTELLIGENCE("Intelligence", "✎", 'b', 0, false),   // base 0: max mana = 100 + Intelligence (research 01 §2)
    CRIT_CHANCE("Crit Chance", "☣", '9', 30, true),
    CRIT_DAMAGE("Crit Damage", "☠", '9', 50, true),
    ATTACK_SPEED("Bonus Attack Speed", "⚔", 'e', 0, true),
    ABILITY_DAMAGE("Ability Damage", "๑", 'c', 0, true),
    FEROCITY("Ferocity", "⫽", 'c', 0, false),
    HEALTH_REGEN("Health Regen", "❣", 'c', 100, false),
    VITALITY("Vitality", "♨", '4', 100, false),
    MENDING("Mending", "☄", 'a', 100, false),
    SWING_RANGE("Swing Range", "Ⓢ", 'e', 3, false),
    SPEED("Speed", "✦", 'f', 100, false),
    MAGIC_FIND("Magic Find", "✯", 'b', 0, false),
    PET_LUCK("Pet Luck", "♣", 'd', 0, false),
    // ---- gathering ----
    MINING_SPEED("Mining Speed", "⸕", '6', 0, false),
    MINING_FORTUNE("Mining Fortune", "☘", '6', 0, false),
    ORE_FORTUNE("Ore Fortune", "☘", '6', 0, false),
    BLOCK_FORTUNE("Block Fortune", "☘", '6', 0, false),
    GEMSTONE_FORTUNE("Gemstone Fortune", "☘", '6', 0, false),
    DWARVEN_METAL_FORTUNE("Dwarven Metal Fortune", "☘", '6', 0, false),
    MINING_SPREAD("Mining Spread", "▚", 'e', 0, false),
    BREAKING_POWER("Breaking Power", "Ⓟ", '2', 0, false),
    PRISTINE("Pristine", "✧", '5', 0, false),
    COLD_RESISTANCE("Cold Resistance", "❄", 'b', 0, false),
    HEAT_RESISTANCE("Heat Resistance", "♨", 'c', 0, false),
    FARMING_FORTUNE("Farming Fortune", "☘", '6', 0, false),
    FORAGING_FORTUNE("Foraging Fortune", "☘", '6', 0, false),
    SWEEP("Sweep", "∮", '2', 0, false),
    SEA_CREATURE_CHANCE("Sea Creature Chance", "α", '3', 20, true),
    FISHING_SPEED("Fishing Speed", "☂", 'b', 0, false),
    TROPHY_FISH_CHANCE("Trophy Fish Chance", "♔", '6', 0, true),
    DOUBLE_HOOK_CHANCE("Double Hook Chance", "⚓", '9', 0, true),
    TREASURE_CHANCE("Treasure Chance", "⛃", '6', 0, true),
    PEST_CHANCE("Bonus Pest Chance", "ൠ", '2', 0, true),
    /** Hunting: more shards per catch (wave 5). Symbol/colour are ours; the reference uses a private glyph. // TUNE */
    HUNTING_FORTUNE("Hunting Fortune", "☘", 'd', 0, false),
    // ---- wisdom (bonus skill XP %) ----
    COMBAT_WISDOM("Combat Wisdom", "☯", '3', 0, false),
    MINING_WISDOM("Mining Wisdom", "☯", '3', 0, false),
    FARMING_WISDOM("Farming Wisdom", "☯", '3', 0, false),
    FORAGING_WISDOM("Foraging Wisdom", "☯", '3', 0, false),
    FISHING_WISDOM("Fishing Wisdom", "☯", '3', 0, false),
    ENCHANTING_WISDOM("Enchanting Wisdom", "☯", '3', 0, false),
    ALCHEMY_WISDOM("Alchemy Wisdom", "☯", '3', 0, false),
    CARPENTRY_WISDOM("Carpentry Wisdom", "☯", '3', 0, false),
    RUNECRAFTING_WISDOM("Runecrafting Wisdom", "☯", '3', 0, false),
    SOCIAL_WISDOM("Social Wisdom", "☯", '3', 0, false),
    TAMING_WISDOM("Taming Wisdom", "☯", '3', 0, false),
    HUNTING_WISDOM("Hunting Wisdom", "☯", '3', 0, false),
    // ---- misc ----
    MANA_REGEN("Mana Regen", "✎", 'b', 0, true),
    RIFT_TIME("Rift Time", "ф", 'a', 0, false),
    MANA_POOL_BONUS("Bonus Mana", "✎", 'b', 0, false);

    public final String display;
    public final String symbol;
    public final char color;
    public final double base;
    public final boolean percent;

    Stat(String display, String symbol, char color, double base, boolean percent) {
        this.display = display;
        this.symbol = symbol;
        this.color = color;
        this.base = base;
        this.percent = percent;
    }

    /** "&c❁ Strength" */
    public String label() { return "&" + color + symbol + " " + display; }

    /** Lenient lookup: "strength", "STRENGTH", "crit_chance", "Crit Chance". Null when unknown. */
    public static Stat parse(String s) {
        if (s == null) return null;
        String k = s.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        // common aliases from external data
        switch (k) {
            case "WALK_SPEED" -> k = "SPEED";
            case "DAMAGE" -> { return null; } // weapon damage is not a player stat; see ItemDef.damage
            case "BONUS_ATTACK_SPEED" -> k = "ATTACK_SPEED";
            case "SEA_CREATURE" -> k = "SEA_CREATURE_CHANCE";
            default -> {}
        }
        try { return Stat.valueOf(k); } catch (IllegalArgumentException e) { return null; }
    }
}
