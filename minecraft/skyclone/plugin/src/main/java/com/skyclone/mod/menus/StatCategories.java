package com.skyclone.mod.menus;

import com.skyclone.api.stat.Stat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Which stat lives on which page of "Stats &amp; Equipment", the icon each stat gets, and a one-line
 * description in our own words. Pure (no Bukkit) so a unit test can prove every {@link Stat} is on
 * exactly one page - a stat that is on no page is a stat the player can never inspect.
 *
 * <p>Pages follow research 08 section 11.2: Combat, Gathering, Wisdom, Misc.
 */
public final class StatCategories {

    private StatCategories() {}

    public enum Category {
        COMBAT("Combat Stats", 'c', "DIAMOND_SWORD", "Everything that decides how hard you hit and how much punishment you can take."),
        GATHERING("Gathering Stats", '6', "IRON_PICKAXE", "Speed, luck and power for mining, farming, foraging and fishing."),
        WISDOM("Wisdom Stats", '3', "ENCHANTED_BOOK", "Bonus skill experience, one stat per skill."),
        MISC("Misc Stats", 'f', "CLOCK", "Movement, luck, mana and the odd stat that fits nowhere else.");

        public final String title;
        public final char color;
        public final String icon;
        public final String description;

        Category(String title, char color, String icon, String description) {
            this.title = title;
            this.color = color;
            this.icon = icon;
            this.description = description;
        }
    }

    /** One stat's presentation. {@code icon} is a Bukkit Material NAME (checked by a self-test). */
    public record Info(Stat stat, Category category, String icon, String description) {}

    private static final Map<Stat, Info> INFO = new EnumMap<>(Stat.class);

    private static void put(Stat s, Category c, String icon, String desc) { INFO.put(s, new Info(s, c, icon, desc)); }

    static {
        Category C = Category.COMBAT, G = Category.GATHERING, W = Category.WISDOM, M = Category.MISC;
        // ---- combat ----
        put(Stat.HEALTH, C, "GOLDEN_APPLE", "Your hit points. When they run out, you die.");
        put(Stat.DEFENSE, C, "IRON_CHESTPLATE", "Shaves a share off every hit you take: damage is multiplied by 100 / (100 + Defense).");
        put(Stat.STRENGTH, C, "BLAZE_POWDER", "Raises melee and bow damage by 1% per point.");
        put(Stat.INTELLIGENCE, C, "ENCHANTED_BOOK", "Adds to your mana pool and scales the damage of magic abilities.");
        put(Stat.CRIT_CHANCE, C, "TIPPED_ARROW", "The odds that a hit becomes a critical hit.");
        put(Stat.CRIT_DAMAGE, C, "SKELETON_SKULL", "How much extra damage a critical hit deals.");
        put(Stat.ATTACK_SPEED, C, "GOLDEN_AXE", "Lets you land melee hits on the same target more often.");
        put(Stat.ABILITY_DAMAGE, C, "BEACON", "Multiplies the damage of item abilities.");
        put(Stat.TRUE_DEFENSE, C, "BONE_MEAL", "Reduces true damage, the kind that ignores normal Defense.");
        put(Stat.FEROCITY, C, "RED_DYE", "Each 100 points is one extra strike per hit; the remainder is the chance of one more.");
        put(Stat.VITALITY, C, "GLISTERING_MELON_SLICE", "Boosts every source of healing you receive.");
        put(Stat.MENDING, C, "GOLDEN_CARROT", "Boosts the healing you give to other players.");
        put(Stat.HEALTH_REGEN, C, "GHAST_TEAR", "How quickly your health comes back on its own.");
        // ---- gathering ----
        put(Stat.MINING_SPEED, G, "GOLDEN_PICKAXE", "How fast you break blocks with a pickaxe or drill.");
        put(Stat.MINING_FORTUNE, G, "GOLD_NUGGET", "Each 100 points is one extra drop from ores and stone.");
        put(Stat.ORE_FORTUNE, G, "GOLD_ORE", "Fortune that only counts on ores.");
        put(Stat.BLOCK_FORTUNE, G, "COBBLESTONE", "Fortune that only counts on plain blocks like stone and sand.");
        put(Stat.GEMSTONE_FORTUNE, G, "AMETHYST_SHARD", "Fortune that only counts on gemstones.");
        put(Stat.DWARVEN_METAL_FORTUNE, G, "PRISMARINE_CRYSTALS", "Fortune that only counts on mithril, titanium and other dwarven metals.");
        put(Stat.MINING_SPREAD, G, "TNT", "The chance to also break the blocks next to the one you mine.");
        put(Stat.BREAKING_POWER, G, "DIAMOND_PICKAXE", "Decides which blocks your tool is strong enough to break at all.");
        put(Stat.PRISTINE, G, "EMERALD", "The chance that a gemstone you mine comes out a grade better.");
        put(Stat.FARMING_FORTUNE, G, "WHEAT", "Each 100 points is one extra drop from crops.");
        put(Stat.FORAGING_FORTUNE, G, "OAK_SAPLING", "Each 100 points is one extra drop from logs.");
        put(Stat.SWEEP, G, "SHEARS", "How many logs a single swing can fell at once.");
        put(Stat.SEA_CREATURE_CHANCE, G, "PRISMARINE_SHARD", "The chance that a catch is a sea creature instead of loot.");
        put(Stat.FISHING_SPEED, G, "FISHING_ROD", "Shortens the wait before something bites.");
        put(Stat.TROPHY_FISH_CHANCE, G, "TROPICAL_FISH", "The chance of hooking a trophy fish in lava.");
        put(Stat.DOUBLE_HOOK_CHANCE, G, "TRIPWIRE_HOOK", "The chance that a sea creature brings a friend.");
        put(Stat.TREASURE_CHANCE, G, "CHEST", "The chance that a catch is treasure.");
        put(Stat.PEST_CHANCE, G, "SILVERFISH_SPAWN_EGG", "Raises how often pests show up while you farm the Garden.");
        put(Stat.HUNTING_FORTUNE, G, "LEAD", "More shards from every creature you catch while hunting.");   // core-wave5
        // ---- wisdom ----
        put(Stat.COMBAT_WISDOM, W, "STONE_SWORD", "Each point is +1% Combat experience.");
        put(Stat.MINING_WISDOM, W, "STONE_PICKAXE", "Each point is +1% Mining experience.");
        put(Stat.FARMING_WISDOM, W, "GOLDEN_HOE", "Each point is +1% Farming experience.");
        put(Stat.FORAGING_WISDOM, W, "JUNGLE_SAPLING", "Each point is +1% Foraging experience.");
        put(Stat.FISHING_WISDOM, W, "FISHING_ROD", "Each point is +1% Fishing experience.");
        put(Stat.ENCHANTING_WISDOM, W, "ENCHANTING_TABLE", "Each point is +1% Enchanting experience.");
        put(Stat.ALCHEMY_WISDOM, W, "BREWING_STAND", "Each point is +1% Alchemy experience.");
        put(Stat.CARPENTRY_WISDOM, W, "CRAFTING_TABLE", "Each point is +1% Carpentry experience.");
        put(Stat.RUNECRAFTING_WISDOM, W, "MAGMA_CREAM", "Each point is +1% Runecrafting experience.");
        put(Stat.SOCIAL_WISDOM, W, "EMERALD", "Each point is +1% Social experience.");
        put(Stat.TAMING_WISDOM, W, "BONE", "Each point is +1% Taming experience.");
        put(Stat.HUNTING_WISDOM, W, "LEAD", "Each point is +1% Hunting experience.");   // core-wave5
        // ---- misc ----
        put(Stat.SPEED, M, "SUGAR", "How fast you walk.");
        put(Stat.MAGIC_FIND, M, "STICK", "Raises the odds of rare drops (those under 5%).");
        put(Stat.PET_LUCK, M, "LEAD", "Better odds of pet drops, and better pets from the pet shop.");
        put(Stat.SWING_RANGE, M, "FISHING_ROD", "How far away you can hit things.");
        put(Stat.COLD_RESISTANCE, M, "SNOWBALL", "Slows how fast the cold of the deep glacite tunnels hurts you.");
        put(Stat.HEAT_RESISTANCE, M, "MAGMA_BLOCK", "Slows how fast the heat of the deep hollows hurts you.");
        put(Stat.MANA_REGEN, M, "LAPIS_LAZULI", "How quickly your mana comes back.");
        put(Stat.MANA_POOL_BONUS, M, "LIGHT_BLUE_DYE", "Extra mana on top of what Intelligence gives.");
        put(Stat.RIFT_TIME, M, "CLOCK", "How long you can stay in the Rift.");
    }

    /** Presentation for a stat. Never null: a stat added to the API later lands on Misc with a generic icon. */
    public static Info info(Stat s) {
        Info i = INFO.get(s);
        return i != null ? i : new Info(s, Category.MISC, "PAPER", "A stat.");
    }

    /** True when the stat has a hand-written entry (unit test: every stat must). */
    public static boolean covered(Stat s) { return INFO.containsKey(s); }

    /** Stats of one page, in declaration order. */
    public static List<Stat> of(Category c) {
        List<Stat> out = new ArrayList<>();
        for (Stat s : Stat.values()) if (info(s).category() == c) out.add(s);
        return Collections.unmodifiableList(out);
    }

    /** "&amp;c❁ Strength &amp;f350" or "&amp;9☣ Crit Chance &amp;f65%". */
    public static String valueLine(Stat s, double v) {
        return "&" + s.color + s.symbol + " " + s.display + " &f" + format(s, v);
    }

    /** "350", "65%", "12.5" - one decimal at most, percent stats get a '%'. */
    public static String format(Stat s, double v) {
        double r = Math.round(v * 10) / 10.0;
        String n = (r == Math.rint(r)) ? String.format(java.util.Locale.US, "%,d", (long) r)
                : String.format(java.util.Locale.US, "%,.1f", r);
        return s.percent ? n + "%" : n;
    }

    // ---- breakdown rows ----------------------------------------------------------------------------

    /** How a breakdown row changes a stat: flat points, an additive percent, or a true multiplier. */
    public enum PartKind { FLAT, PERCENT, MULTIPLIER }

    /** One row of {@code StatService.breakdown}, split into the provider id and what kind of row it is. */
    public record SourceKey(String source, PartKind kind) {}

    /**
     * Core's breakdown keys are {@code "<provider>"} for flat points, {@code "<provider> (%)"} for an
     * additive percent and {@code "<provider> (x)"} for a multiplier (StatServiceImpl.breakdown). Adding
     * all three into one "sources" sum would show a x1.5 set bonus as "+1.5 Strength".
     */
    public static SourceKey parseKey(String key) {
        if (key == null) return new SourceKey("", PartKind.FLAT);
        String k = key.trim();
        if (k.endsWith("(%)")) return new SourceKey(k.substring(0, k.length() - 3).trim(), PartKind.PERCENT);
        if (k.endsWith("(x)")) return new SourceKey(k.substring(0, k.length() - 3).trim(), PartKind.MULTIPLIER);
        return new SourceKey(k, PartKind.FLAT);
    }

    /** "+12", "+10%", "x1.50" - how a row reads in lore (colour codes included). */
    public static String partValue(Stat s, PartKind kind, double v) {
        return switch (kind) {
            case FLAT -> (v >= 0 ? "&a+" : "&c") + format(s, v);
            case PERCENT -> (v >= 0 ? "&a+" : "&c") + format(Stat.HEALTH, v) + "%";
            case MULTIPLIER -> (v >= 1 ? "&a" : "&c") + "x" + String.format(java.util.Locale.US, "%.2f", v);
        };
    }

    /** Pretty name for a stat provider id from {@code StatService.breakdown}. */
    public static String sourceName(String providerId) {
        if (providerId == null || providerId.isBlank()) return "Unknown";
        String id = parseKey(providerId).source().toLowerCase(java.util.Locale.ROOT);
        return switch (id) {
            case "base" -> "Base Value";
            case "armor" -> "Armor";
            case "held" -> "Held Item";
            case "equipment" -> "Equipment";
            case "skills" -> "Skills";
            case "pets" -> "Pet";
            case "accessories" -> "Accessories";
            case "potions" -> "Active Effects";
            case "armor_sets", "sets", "armor_sets_final" -> "Armor Set Bonus";
            case "mayors" -> "Mayor Perks";
            case "progression.cakes", "cakes" -> "Century Cakes";
            case "progression.fairy", "fairy" -> "Fairy Souls";
            case "progression.bestiary", "bestiary" -> "Bestiary";
            case "progression.sblevel", "sblevel" -> "SkyBlock Level";
            case "mining", "hotm" -> "Heart of the Mountain";
            case "garden" -> "Garden";
            case "slayer", "slayer_levels" -> "Slayer Levels";
            case "dungeons" -> "Dungeons";
            case "collections" -> "Collections";
            case "bank.cookie", "cookie" -> "Cookie Buff";
            case "bank.upgrades" -> "Account Upgrades";
            case "enchanting" -> "Enchantments";
            case "reforges" -> "Reforges";
            case "foraging_harp" -> "Harp Songs";
            case "weapons_held" -> "Weapon Bonuses";
            case "weapons_buffs" -> "Weapon Buffs";
            default -> titleCase(id.replace('.', ' ').replace('-', ' '));
        };
    }

    /** Icon material name for a stat provider id. */
    public static String sourceIcon(String providerId) {
        String id = providerId == null ? "" : parseKey(providerId).source().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("cookie")) return "COOKIE";
        if (id.contains("harp")) return "NOTE_BLOCK";
        if (id.startsWith("weapon")) return "IRON_SWORD";
        if (id.startsWith("farming")) return "WHEAT";
        if (id.startsWith("foraging")) return "OAK_LOG";
        if (id.startsWith("collection")) return "PAINTING";
        if (id.startsWith("bank")) return "GOLD_BLOCK";
        if (id.startsWith("armor")) return "DIAMOND_CHESTPLATE";
        if (id.startsWith("held")) return "IRON_SWORD";
        if (id.startsWith("equipment")) return "LEATHER_CHESTPLATE";
        if (id.startsWith("skill")) return "DIAMOND_SWORD";
        if (id.startsWith("pet")) return "BONE";
        if (id.startsWith("accessor")) return "GOLD_NUGGET";
        if (id.startsWith("potion")) return "POTION";
        if (id.contains("cake")) return "CAKE";
        if (id.contains("fairy")) return "PINK_DYE";
        if (id.contains("bestiary")) return "BOOK";
        if (id.contains("mayor")) return "GOLDEN_HELMET";
        if (id.contains("mining") || id.contains("hotm")) return "PRISMARINE_CRYSTALS";
        if (id.contains("garden")) return "SUNFLOWER";
        if (id.contains("slayer")) return "ROTTEN_FLESH";
        if (id.contains("level")) return "EXPERIENCE_BOTTLE";
        if (id.contains("reforge")) return "ANVIL";
        if (id.contains("enchant")) return "ENCHANTED_BOOK";
        if (id.equals("base")) return "BEDROCK";
        return "PAPER";
    }

    static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split("[ _]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
