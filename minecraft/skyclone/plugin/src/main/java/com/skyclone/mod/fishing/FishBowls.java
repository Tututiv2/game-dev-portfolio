package com.skyclone.mod.fishing;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fish Bowls (accessories' SMALL / MEDIUM / LARGE_FISH_BOWL: "Rare shards are +30 / 60 / 100% more likely when
 * fishing"). The shard roll itself is hunting's ({@code hunting.fish-shard-chance}: a plain catch has that chance
 * to bring up a Water Shard of the island). Until hunting reads the bowls itself, a bowl here gives a SECOND roll
 * at {@code chance x bonus} that can only bring up a Rare-or-better water shard of the island - so the rare shards
 * come up {@code (1 + bonus)} times as often and the common ones are untouched, which is what the lore says.
 * Shard pools are read from hunting's own data ({@code data/hunting/shards.json} + {@code habitats.json});
 * the shard is given through {@code Features.Hunting.addShards}. Dial {@code fishing.fish-bowls} (turn it off
 * the day hunting applies the bowls itself - never both). TUNE: the distribution among rare shards is uniform.
 */
final class FishBowls {

    private FishBowls() {}

    static final Set<String> RARE = Set.of("RARE", "EPIC", "LEGENDARY", "MYTHIC");
    private static Map<String, List<String>> rareWaterShards;
    private static Dials.Dial<Double> huntingChance;

    /** Best bowl owned: +0.3 / 0.6 / 1.0 (accessories' lore). */
    static double bonus(Player p) {
        if (FishEffects.has(p, "LARGE_FISH_BOWL")) return 1.0;
        if (FishEffects.has(p, "MEDIUM_FISH_BOWL")) return 0.6;
        if (FishEffects.has(p, "SMALL_FISH_BOWL")) return 0.3;
        return 0;
    }

    /** Hunting's own dial (registered lazily with the same default, so this returns hunting's live value). */
    static double baseChance() {
        if (huntingChance == null)
            huntingChance = Sky.dials().number("hunting.fish-shard-chance", 0.01,
                    "Chance a plain fishing catch also brings up a Water Shard of that island (also read by fishing's Fish Bowls)");
        Double v = huntingChance.get();
        return v == null ? 0.01 : Math.max(0, v);
    }

    /** Rare-or-better water shards per island (lazy, from hunting's data files). */
    static synchronized Map<String, List<String>> pools() {
        if (rareWaterShards != null) return rareWaterShards;
        Map<String, List<String>> out = new HashMap<>();
        try {
            JsonArray habitats = read("data/hunting/habitats.json");
            JsonArray shards = read("data/hunting/shards.json");
            if (habitats != null && shards != null) {
                Map<String, String> islandOfHabitat = new HashMap<>();
                Map<String, Set<String>> crittersOfIsland = new HashMap<>();
                for (JsonElement e : habitats) {
                    JsonObject h = e.getAsJsonObject();
                    if (!h.has("kind") || !"water".equals(h.get("kind").getAsString()) || !h.has("island")) continue;
                    String island = h.get("island").getAsString();
                    islandOfHabitat.put(h.get("id").getAsString(), island);
                    if (h.has("critters")) for (JsonElement c : h.getAsJsonArray("critters"))
                        crittersOfIsland.computeIfAbsent(island, k -> new HashSet<>()).add(c.getAsString());
                }
                for (JsonElement e : shards) {
                    JsonObject s = e.getAsJsonObject();
                    String rarity = s.has("rarity") ? s.get("rarity").getAsString() : "";
                    if (!RARE.contains(rarity) || !s.has("sources")) continue;
                    String id = s.get("id").getAsString();
                    Set<String> islands = new HashSet<>();
                    for (JsonElement se : s.getAsJsonArray("sources")) {
                        JsonObject src = se.getAsJsonObject();
                        if (!src.has("type") || !"critter".equals(src.get("type").getAsString())) continue;
                        if (src.has("habitats")) for (JsonElement hb : src.getAsJsonArray("habitats")) {
                            String isl = islandOfHabitat.get(hb.getAsString());
                            if (isl != null) islands.add(isl);
                        }
                        String mob = src.has("mob") ? src.get("mob").getAsString() : null;
                        if (mob != null) for (Map.Entry<String, Set<String>> c : crittersOfIsland.entrySet())
                            if (c.getValue().contains(mob)) islands.add(c.getKey());
                    }
                    for (String isl : islands) out.computeIfAbsent(isl, k -> new ArrayList<>()).add(id);
                }
            }
        } catch (RuntimeException e) {
            Sky.log().warning("[fishing] fish bowls: could not read hunting's shard data: " + e);
        }
        rareWaterShards = out;
        return out;
    }

    private static JsonArray read(String path) {
        try (InputStream in = Sky.plugin().getResource(path)) {
            if (in == null) return null;
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonArray.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** The bowl's extra roll after a plain (not sea creature) catch. Returns the shard given, or null. */
    static String roll(Player p, String island) {
        if (!FishDials.FISH_BOWLS.get() || island == null || !Sky.has(Features.Hunting.class)) return null;
        double b = bonus(p);
        if (b <= 0) return null;
        List<String> pool = pools().get(island);
        if (pool == null || pool.isEmpty()) return null;
        if (ThreadLocalRandom.current().nextDouble() >= baseChance() * b) return null;
        String shard = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        try {
            Sky.service(Features.Hunting.class).addShards(p, shard, 1, "fishing:fish_bowl");
        } catch (RuntimeException e) {
            return null;
        }
        Msg.send(p, "&bYour Fish Bowl caught the glint of a rare shard!");
        return shard;
    }
}
