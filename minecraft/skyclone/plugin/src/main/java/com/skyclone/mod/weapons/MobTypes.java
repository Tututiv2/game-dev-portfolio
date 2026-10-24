package com.skyclone.mod.weapons;

import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The genre's mob families ("Undead", "Arthropod", "Ender"...), decided from what a sky mob IS.
 * A mob module can state it outright with {@code MobDef.extra.types = ["undead", "spooky"]};
 * otherwise the entity type, slayer category, flags and id decide.
 */
final class MobTypes {

    private MobTypes() {}

    static final Set<EntityType> UNDEAD = EnumSet.of(EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK,
            EntityType.DROWNED, EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.BOGGED,
            EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN, EntityType.PHANTOM, EntityType.WITHER,
            EntityType.SKELETON_HORSE, EntityType.ZOMBIE_HORSE);
    static final Set<EntityType> ARTHROPOD = EnumSet.of(EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.SILVERFISH,
            EntityType.ENDERMITE, EntityType.BEE);
    static final Set<EntityType> ENDER = EnumSet.of(EntityType.ENDERMAN, EntityType.ENDERMITE, EntityType.ENDER_DRAGON, EntityType.SHULKER);
    static final Set<EntityType> SKELETAL = EnumSet.of(EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.BOGGED);
    static final Set<EntityType> INFERNAL = EnumSet.of(EntityType.BLAZE, EntityType.MAGMA_CUBE, EntityType.GHAST,
            EntityType.PIGLIN_BRUTE, EntityType.HOGLIN);
    static final Set<EntityType> ANIMAL = EnumSet.of(EntityType.WOLF, EntityType.COW, EntityType.PIG, EntityType.SHEEP,
            EntityType.CHICKEN, EntityType.RABBIT, EntityType.MOOSHROOM, EntityType.HORSE, EntityType.LLAMA, EntityType.FOX,
            EntityType.OCELOT, EntityType.CAT, EntityType.POLAR_BEAR, EntityType.GOAT, EntityType.PANDA, EntityType.DONKEY,
            EntityType.MULE, EntityType.CAMEL, EntityType.SNIFFER, EntityType.ARMADILLO);
    static final Set<EntityType> SLIME = EnumSet.of(EntityType.SLIME, EntityType.MAGMA_CUBE);
    static final Set<EntityType> SQUID = EnumSet.of(EntityType.SQUID, EntityType.GLOW_SQUID, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN);

    static boolean is(String type, LivingEntity e) {
        if (type == null || e == null) return false;
        MobDef d = null;
        try { if (Sky.mobs().isSkyMob(e)) d = Sky.mobs().defOf(e); } catch (RuntimeException ignored) { }
        return is(type, e.getType(), d);
    }

    /** Same test from a mob's def alone (loot hooks get the def, not the entity). */
    static boolean isDef(String type, MobDef d) {
        if (type == null || d == null) return false;
        EntityType et = null;
        try { if (d.entity != null) et = EntityType.valueOf(d.entity.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { }
        return is(type, et, d);
    }

    static boolean is(String type, EntityType et, MobDef d) {
        if (d != null && d.extra != null && d.extra.has("types") && d.extra.get("types").isJsonArray()) {
            for (JsonElement el : d.extra.getAsJsonArray("types")) {
                if (el.isJsonPrimitive() && type.equalsIgnoreCase(el.getAsString())) return true;
            }
        }
        // a mob module may also flag its mobs with the family word itself (mayors: flags ["mythological"], ["spooky"])
        try { if (d != null && d.has(type)) return true; } catch (RuntimeException ignored) { }
        String id = d == null ? "" : (d.id + " " + d.familyId()).toLowerCase(Locale.ROOT);
        String slayer = d == null || d.slayer == null ? "" : d.slayer.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "undead" -> UNDEAD.contains(et) || slayer.equals("zombie") || id.contains("zombie") || id.contains("ghoul")
                    || id.contains("skeleton") || id.contains("revenant") || id.contains("wraith");
            case "zombie" -> et == EntityType.ZOMBIE || et == EntityType.HUSK || et == EntityType.DROWNED
                    || et == EntityType.ZOMBIE_VILLAGER || slayer.equals("zombie") || id.contains("zombie") || id.contains("revenant");
            case "arthropod" -> ARTHROPOD.contains(et) || slayer.equals("spider") || id.contains("spider") || id.contains("silverfish");
            case "ender" -> ENDER.contains(et) || slayer.equals("enderman") || id.contains("zealot") || id.contains("ender");
            case "enderman" -> et == EntityType.ENDERMAN || slayer.equals("enderman") || id.contains("zealot");
            case "wither" -> et == EntityType.WITHER_SKELETON || et == EntityType.WITHER || id.contains("wither");
            case "skeletal" -> SKELETAL.contains(et) || id.contains("skeleton");
            case "infernal" -> INFERNAL.contains(et) || slayer.equals("blaze") || id.contains("blaze") || id.contains("demon")
                    || id.contains("inferno");
            case "animal" -> ANIMAL.contains(et) || slayer.equals("wolf");
            case "wolf" -> et == EntityType.WOLF || slayer.equals("wolf") || id.contains("wolf");
            case "sea_creature" -> d != null && (d.has("sea_creature") || d.has("sea-creature"));
            case "spooky" -> id.contains("spooky") || id.contains("scary") || id.contains("trick_or_treat") || id.contains("jack_o")
                    || id.contains("primal_fear");
            case "mythological" -> id.contains("mytholog") || id.contains("minos") || id.contains("gaia") || id.contains("griffin")
                    || id.contains("minotaur") || id.contains("siamese");
            case "dragon" -> et == EntityType.ENDER_DRAGON || id.contains("dragon");
            case "egg_sac" -> id.contains("egg_sac") || id.contains("eggsac");
            case "slime" -> SLIME.contains(et) || id.contains("slime");
            case "squid" -> SQUID.contains(et);
            default -> false;
        };
    }
}
