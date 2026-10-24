package com.skyclone.mod.hunting;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The genre's mob types the attribute rulers / resistances / fortunes name ("Undead", "Aquatic",
 * "Woodland"...). A mob module can state them outright with {@code MobDef.extra.types}; otherwise the
 * entity type, slayer category, flags and id decide. (Hunting's own copy: modules never import each other.)
 */
final class MobTypes {

    private MobTypes() {}

    private static final Set<EntityType> UNDEAD = EnumSet.of(EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK,
            EntityType.DROWNED, EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.BOGGED,
            EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN, EntityType.PHANTOM, EntityType.WITHER,
            EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE);
    private static final Set<EntityType> ARTHROPOD = EnumSet.of(EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.SILVERFISH,
            EntityType.ENDERMITE, EntityType.BEE);
    private static final Set<EntityType> ENDER = EnumSet.of(EntityType.ENDERMAN, EntityType.ENDERMITE, EntityType.ENDER_DRAGON, EntityType.SHULKER);
    private static final Set<EntityType> SKELETAL = EnumSet.of(EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.BOGGED);
    private static final Set<EntityType> INFERNAL = EnumSet.of(EntityType.BLAZE, EntityType.MAGMA_CUBE, EntityType.GHAST,
            EntityType.PIGLIN_BRUTE, EntityType.HOGLIN, EntityType.STRIDER);
    private static final Set<EntityType> ANIMAL = EnumSet.of(EntityType.WOLF, EntityType.COW, EntityType.PIG, EntityType.SHEEP,
            EntityType.CHICKEN, EntityType.RABBIT, EntityType.MOOSHROOM, EntityType.HORSE, EntityType.LLAMA, EntityType.FOX,
            EntityType.OCELOT, EntityType.CAT, EntityType.POLAR_BEAR, EntityType.GOAT, EntityType.PANDA, EntityType.DONKEY,
            EntityType.MULE, EntityType.CAMEL, EntityType.SNIFFER, EntityType.ARMADILLO, EntityType.PARROT);
    private static final Set<EntityType> AQUATIC = EnumSet.of(EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.GUARDIAN,
            EntityType.ELDER_GUARDIAN, EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH,
            EntityType.DOLPHIN, EntityType.TURTLE, EntityType.AXOLOTL, EntityType.DROWNED, EntityType.FROG);
    private static final Set<EntityType> AIRBORNE = EnumSet.of(EntityType.PHANTOM, EntityType.BAT, EntityType.GHAST, EntityType.PARROT,
            EntityType.VEX, EntityType.ALLAY, EntityType.BEE, EntityType.BLAZE, EntityType.BREEZE);
    private static final Set<EntityType> HUMANOID = EnumSet.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.PILLAGER,
            EntityType.VINDICATOR, EntityType.EVOKER, EntityType.WITCH, EntityType.VILLAGER, EntityType.ZOMBIE_VILLAGER,
            EntityType.PIGLIN, EntityType.PIGLIN_BRUTE, EntityType.DROWNED, EntityType.HUSK, EntityType.STRAY, EntityType.ILLUSIONER);
    private static final Set<EntityType> FROZEN = EnumSet.of(EntityType.STRAY, EntityType.POLAR_BEAR, EntityType.SNOW_GOLEM);
    private static final Set<EntityType> CUBIC = EnumSet.of(EntityType.SLIME, EntityType.MAGMA_CUBE);
    private static final Set<EntityType> CONSTRUCT = EnumSet.of(EntityType.IRON_GOLEM, EntityType.SNOW_GOLEM, EntityType.SHULKER);

    static boolean is(String type, LivingEntity e) {
        if (type == null || e == null) return false;
        MobDef d = null;
        try { if (Sky.mobs().isSkyMob(e)) d = Sky.mobs().defOf(e); } catch (RuntimeException ignored) { }
        return is(type, e.getType(), d);
    }

    static boolean is(String type, EntityType et, MobDef d) {
        if (d != null && d.extra != null) {
            for (String key : new String[]{"types", "mobTypes"}) {
                if (d.extra.has(key) && d.extra.get(key).isJsonArray()) {
                    for (JsonElement el : d.extra.getAsJsonArray(key)) {
                        if (el.isJsonPrimitive() && type.equalsIgnoreCase(el.getAsString())) return true;
                    }
                }
            }
        }
        String id = d == null ? "" : (d.id + " " + d.familyId()).toLowerCase(Locale.ROOT);
        String slayer = d == null || d.slayer == null ? "" : d.slayer.toLowerCase(Locale.ROOT);
        boolean sea = d != null && (d.has("sea_creature") || d.has("sea-creature"));
        return switch (type) {
            case "undead" -> UNDEAD.contains(et) || slayer.equals("zombie") || id.contains("zombie") || id.contains("ghoul")
                    || id.contains("skeleton") || id.contains("revenant") || id.contains("wraith");
            case "arthropod" -> ARTHROPOD.contains(et) || slayer.equals("spider") || id.contains("spider") || id.contains("silverfish");
            case "ender" -> ENDER.contains(et) || slayer.equals("enderman") || id.contains("zealot") || id.contains("ender");
            case "skeletal" -> SKELETAL.contains(et) || id.contains("skeleton");
            case "infernal" -> INFERNAL.contains(et) || slayer.equals("blaze") || id.contains("blaze") || id.contains("demon")
                    || id.contains("inferno") || id.startsWith("crimson_");
            case "magmatic" -> et == EntityType.MAGMA_CUBE || et == EntityType.STRIDER || id.contains("magma") || id.contains("lava");
            case "animal" -> ANIMAL.contains(et) || slayer.equals("wolf");
            case "aquatic" -> sea || AQUATIC.contains(et);
            case "airborne" -> AIRBORNE.contains(et) || (d != null && d.has("flying"));
            case "humanoid" -> HUMANOID.contains(et) || id.contains("goblin") || id.contains("barbarian") || id.contains("mage");
            case "frozen" -> FROZEN.contains(et) || id.contains("glacite") || id.contains("ice") || id.contains("frozen") || id.contains("yeti")
                    || id.startsWith("gt_");
            case "woodland" -> id.startsWith("park_") || id.startsWith("marsh_") || id.contains("forest") || id.contains("ent");
            case "subterranean" -> id.startsWith("ch_") || id.startsWith("dwarven_") || id.startsWith("deep_caverns_")
                    || id.startsWith("mining_") || id.startsWith("gold_mine_") || id.contains("cave");
            case "arcane" -> et == EntityType.WITCH || et == EntityType.EVOKER || id.contains("mage") || id.contains("witch")
                    || id.contains("wizard") || id.contains("rift_");
            case "mythological" -> id.contains("mytholog") || id.startsWith("myth_") || id.contains("minos") || id.contains("gaia")
                    || id.contains("griffin") || id.contains("minotaur");
            case "construct" -> CONSTRUCT.contains(et) || id.contains("golem") || id.contains("automaton") || id.contains("tiki")
                    || id.contains("sentry");
            case "cubic" -> CUBIC.contains(et) || id.contains("slime") || id.contains("cube");
            case "elusive" -> id.contains("elusive") || id.contains("special_zealot") || id.contains("trapper_") && id.contains("elusive");
            case "pest" -> id.startsWith("garden_pest_");
            case "critter" -> id.startsWith("critter_");
            case "boss" -> d != null && (d.has("boss") || id.contains("slayer_") || id.startsWith("dboss_"));
            default -> false;
        };
    }
}
