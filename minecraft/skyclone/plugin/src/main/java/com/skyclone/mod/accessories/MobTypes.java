package com.skyclone.mod.accessories;

import com.google.gson.JsonElement;
import com.skyclone.api.mob.MobDef;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which monster families a mob belongs to ("undead", "arthropod", "ender", ...), for the accessories
 * that reduce damage from / add damage to a family. A mob module can state it exactly with
 * {@code MobDef.extra.mobTypes = ["undead", "skeletal"]}; otherwise it is inferred from the entity
 * type, the slayer category, flags and the id.
 */
final class MobTypes {

    private MobTypes() {}

    private static final Map<String, Set<String>> CACHE = new ConcurrentHashMap<>();

    private static final Set<String> UNDEAD = Set.of("ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER", "ZOMBIFIED_PIGLIN",
            "ZOGLIN", "SKELETON", "STRAY", "BOGGED", "WITHER_SKELETON", "WITHER", "PHANTOM", "SKELETON_HORSE",
            "ZOMBIE_HORSE", "GIANT");
    private static final Set<String> SKELETAL = Set.of("SKELETON", "STRAY", "BOGGED", "WITHER_SKELETON", "SKELETON_HORSE");
    private static final Set<String> WITHER = Set.of("WITHER", "WITHER_SKELETON");
    private static final Set<String> ARTHROPOD = Set.of("SPIDER", "CAVE_SPIDER", "SILVERFISH", "ENDERMITE", "BEE");
    private static final Set<String> ENDER = Set.of("ENDERMAN", "ENDERMITE", "ENDER_DRAGON", "SHULKER");
    private static final Set<String> ANIMAL = Set.of("WOLF", "PIG", "COW", "SHEEP", "CHICKEN", "RABBIT", "HORSE",
            "MOOSHROOM", "LLAMA", "FOX", "OCELOT", "CAT", "POLAR_BEAR", "GOAT", "PANDA", "TURTLE", "HOGLIN", "CAMEL");
    private static final Set<String> INFERNAL = Set.of("BLAZE", "MAGMA_CUBE", "GHAST", "PIGLIN", "PIGLIN_BRUTE",
            "HOGLIN", "ZOMBIFIED_PIGLIN", "STRIDER");

    static Set<String> of(MobDef d) {
        if (d == null) return Set.of();
        return CACHE.computeIfAbsent(d.id, k -> compute(d));
    }

    static void clear() { CACHE.clear(); }

    private static Set<String> compute(MobDef d) {
        Set<String> out = new HashSet<>();
        if (d.extra != null && d.extra.has("mobTypes") && d.extra.get("mobTypes").isJsonArray()) {
            for (JsonElement e : d.extra.getAsJsonArray("mobTypes")) out.add(e.getAsString().toLowerCase(Locale.ROOT));
            return out;
        }
        String ent = d.entity == null ? "" : d.entity.toUpperCase(Locale.ROOT);
        String id = d.id == null ? "" : d.id.toLowerCase(Locale.ROOT);
        String slayer = d.slayer == null ? "" : d.slayer.toLowerCase(Locale.ROOT);
        if (UNDEAD.contains(ent) || slayer.equals("zombie") || slayer.equals("vampire")) out.add("undead");
        if (SKELETAL.contains(ent)) out.add("skeletal");
        if (WITHER.contains(ent) || id.contains("wither")) out.add("wither");
        if (ARTHROPOD.contains(ent) || slayer.equals("spider")) out.add("arthropod");
        if (ENDER.contains(ent) || slayer.equals("enderman") || id.startsWith("end_")) out.add("ender");
        if (ANIMAL.contains(ent) || slayer.equals("wolf")) out.add("animal");
        if (INFERNAL.contains(ent) || slayer.equals("blaze") || id.startsWith("crimson_")) out.add("infernal");
        if (d.has("sea_creature")) out.add("sea_creature");
        if (d.has("sea_creature") && (id.contains("lava") || id.contains("magma") || id.contains("crimson"))) out.add("magmatic");
        if (ent.equals("ENDER_DRAGON") || id.contains("dragon")) out.add("dragon");
        if (id.contains("kuudra") && !d.has("boss")) out.add("kuudra_follower");
        if (id.contains("minos") || id.contains("mythological") || id.contains("gaia") || id.contains("siamese")) out.add("mythological");
        if (id.startsWith("galatea_") || id.contains("woodland")) out.add("woodland");
        return out;
    }
}
