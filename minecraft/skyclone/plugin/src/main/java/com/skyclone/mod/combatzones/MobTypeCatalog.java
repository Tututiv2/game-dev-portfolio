package com.skyclone.mod.combatzones;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The genre's mob types (research 01 sec 4: Undead / Arthropod / Ender / Wither / Cubic / Aquatic / Airborne /
 * Infernal / Magmatic / Woodland / Skeletal / Mythological, plus the ones later islands added) as Fenn and Lark
 * teach them at their crib in the Spider's Den (the reference's Ike and Michael, "Mob Types intro", research 03
 * sec 13.3). Pure Java: the guide menu, the first-kill tracker and the unit tests all read this table.
 *
 * <p>A mob's types come from {@code MobDef.extra.mobTypes} / {@code extra.types} when its module states them
 * (the accessories module asked every mob owner to), else from the entity type, slayer category and id - the same
 * inference accessories, weapons and hunting each keep their own copy of (modules never import each other).
 */
public final class MobTypeCatalog {

    private MobTypeCatalog() {}

    /** One type: id (lower_snake, as MobDef.extra.mobTypes spells it), name, colour code, icon material, lesson. */
    public record Type(String id, String name, char color, String icon, String lesson) {}

    public static final List<Type> TYPES = List.of(
            new Type("undead", "Undead", '2', "ROTTEN_FLESH", "Zombies, skeletons and other risen things.\nSmite and holy weapons bite deepest."),
            new Type("skeletal", "Skeletal", 'f', "BONE", "Anything built of bare bone. Some blades\nshatter them faster."),
            new Type("arthropod", "Arthropod", '4', "SPIDER_EYE", "Spiders, silverfish and mites. Bane of\nArthropods and spider weapons shine here."),
            new Type("ender", "Ender", '5', "ENDER_PEARL", "Endermen, Zealots and the dragons. Ender\nSlayer and the End's own gear hit hardest."),
            new Type("wither", "Wither", '8', "WITHER_SKELETON_SKULL", "Wither skeletons and their lords. Some\nrelics shrug off their blows."),
            new Type("cubic", "Cubic", 'a', "SLIME_BALL", "Slimes and magma cubes. Cubism makes\nshort work of them."),
            new Type("airborne", "Airborne", 'b', "FEATHER", "Things that fly. Gravity pulls them down,\nand bows reach them best."),
            new Type("aquatic", "Aquatic", '3', "PRISMARINE_SHARD", "Sea creatures and other swimmers.\nImpaling was made for them."),
            new Type("infernal", "Infernal", 'c', "BLAZE_POWDER", "Blazes, ghasts and the Crimson Isle's\nfolk. Smoldering burns them."),
            new Type("magmatic", "Magmatic", '6', "MAGMA_CREAM", "Creatures of lava. Pyroclasm and lava\nfishing gear are built for them."),
            new Type("animal", "Animal", 'e', "LEATHER", "Wolves, cows, pigs and other beasts."),
            new Type("humanoid", "Humanoid", '7', "PLAYER_HEAD", "Walking, talking, sword-swinging people\nand things shaped like them."),
            new Type("construct", "Construct", '7', "IRON_BLOCK", "Golems and other built guardians, like\nthe End Stone Colossus."),
            new Type("frozen", "Frozen", 'b', "ICE", "Creatures of ice and snow."),
            new Type("woodland", "Woodland", '2', "OAK_SAPLING", "The forest's own. Woodsplitter and\nrune-axes cut through them."),
            new Type("mythological", "Mythological", 'd', "GOLDEN_SHOVEL", "Beasts dug up from old burrows."),
            new Type("pest", "Pest", '2', "SWEET_BERRIES", "The Garden's pests."),
            new Type("spooky", "Spooky", '6', "JACK_O_LANTERN", "Festival frights.") );

    public static Type type(String id) {
        if (id == null) return null;
        for (Type t : TYPES) if (t.id().equals(id.toLowerCase(Locale.ROOT))) return t;
        return null;
    }

    private static final Set<String> UNDEAD = Set.of("ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER", "ZOMBIFIED_PIGLIN",
            "ZOGLIN", "SKELETON", "STRAY", "BOGGED", "WITHER_SKELETON", "WITHER", "PHANTOM", "SKELETON_HORSE", "ZOMBIE_HORSE", "GIANT");
    private static final Set<String> SKELETAL = Set.of("SKELETON", "STRAY", "BOGGED", "WITHER_SKELETON", "SKELETON_HORSE");
    private static final Set<String> WITHER = Set.of("WITHER", "WITHER_SKELETON");
    private static final Set<String> ARTHROPOD = Set.of("SPIDER", "CAVE_SPIDER", "SILVERFISH", "ENDERMITE", "BEE");
    private static final Set<String> ENDER = Set.of("ENDERMAN", "ENDERMITE", "ENDER_DRAGON", "SHULKER");
    private static final Set<String> CUBIC = Set.of("SLIME", "MAGMA_CUBE");
    private static final Set<String> AIRBORNE = Set.of("PHANTOM", "BAT", "GHAST", "PARROT", "VEX", "ALLAY", "BEE", "BLAZE", "BREEZE", "ENDER_DRAGON");
    private static final Set<String> AQUATIC = Set.of("SQUID", "GLOW_SQUID", "GUARDIAN", "ELDER_GUARDIAN", "COD", "SALMON",
            "PUFFERFISH", "TROPICAL_FISH", "DOLPHIN", "TURTLE", "AXOLOTL", "DROWNED", "FROG");
    private static final Set<String> INFERNAL = Set.of("BLAZE", "MAGMA_CUBE", "GHAST", "PIGLIN", "PIGLIN_BRUTE", "HOGLIN", "STRIDER");
    private static final Set<String> ANIMAL = Set.of("WOLF", "COW", "PIG", "SHEEP", "CHICKEN", "RABBIT", "MOOSHROOM", "HORSE",
            "LLAMA", "FOX", "OCELOT", "CAT", "POLAR_BEAR", "GOAT", "PANDA", "DONKEY", "MULE", "CAMEL", "SNIFFER", "ARMADILLO");
    private static final Set<String> HUMANOID = Set.of("PILLAGER", "VINDICATOR", "EVOKER", "WITCH", "VILLAGER", "ILLUSIONER", "PLAYER", "MANNEQUIN");
    private static final Set<String> CONSTRUCT = Set.of("IRON_GOLEM", "SNOW_GOLEM", "COPPER_GOLEM");
    private static final Set<String> FROZEN = Set.of("STRAY", "POLAR_BEAR", "SNOW_GOLEM");

    /**
     * Types of a mob. {@code stated} = its module's explicit list (null/empty = infer); only ids this catalogue
     * knows are kept, so a module's private tags ("enderman", "slime") are ignored.
     */
    public static Set<String> of(String entity, String id, String slayer, boolean seaCreature, Collection<String> stated) {
        Set<String> out = new LinkedHashSet<>();
        if (stated != null && !stated.isEmpty()) {
            for (String s : stated) if (type(s) != null) out.add(s.toLowerCase(Locale.ROOT));
            if (!out.isEmpty()) return out;
        }
        String ent = entity == null ? "" : entity.toUpperCase(Locale.ROOT);
        String i = id == null ? "" : id.toLowerCase(Locale.ROOT);
        String sl = slayer == null ? "" : slayer.toLowerCase(Locale.ROOT);
        if (UNDEAD.contains(ent) || sl.equals("zombie") || sl.equals("vampire")) out.add("undead");
        if (SKELETAL.contains(ent)) out.add("skeletal");
        if (WITHER.contains(ent) || i.contains("wither")) out.add("wither");
        if (ARTHROPOD.contains(ent) || sl.equals("spider")) out.add("arthropod");
        if (ENDER.contains(ent) || sl.equals("enderman") || i.startsWith("end_")) out.add("ender");
        if (CUBIC.contains(ent)) out.add("cubic");
        if (AIRBORNE.contains(ent)) out.add("airborne");
        if (AQUATIC.contains(ent) || seaCreature) out.add("aquatic");
        if (INFERNAL.contains(ent) || sl.equals("blaze") || i.startsWith("crimson_")) out.add("infernal");
        if (seaCreature && (i.contains("lava") || i.contains("magma"))) out.add("magmatic");
        if (ANIMAL.contains(ent) || sl.equals("wolf")) out.add("animal");
        if (HUMANOID.contains(ent)) out.add("humanoid");
        if (CONSTRUCT.contains(ent)) out.add("construct");
        if (FROZEN.contains(ent) || i.contains("frozen") || i.contains("yeti")) out.add("frozen");
        if (i.startsWith("galatea_") || i.contains("woodland")) out.add("woodland");
        if (i.contains("minos") || i.contains("mythological") || i.contains("gaia") || i.contains("siamese")) out.add("mythological");
        if (i.startsWith("garden_pest") || i.startsWith("pest_")) out.add("pest");
        if (i.contains("spooky") || i.contains("halloween")) out.add("spooky");
        return out;
    }

    /** SkyBlock XP for the first kill of each type (research 02: "mob types (1 each -> 33)"). */
    public static final int SBXP_PER_TYPE = 1;
}
