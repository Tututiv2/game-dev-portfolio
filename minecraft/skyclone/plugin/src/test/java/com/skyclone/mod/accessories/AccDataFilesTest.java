package com.skyclone.mod.accessories;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated data files (resources/data/accessories) hang together without a server. */
class AccDataFilesTest {

    private static JsonArray read(String f) throws Exception {
        try (InputStream in = AccDataFilesTest.class.getClassLoader().getResourceAsStream("data/accessories/" + f)) {
            assertNotNull(in, f + " is on the classpath");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        }
    }

    @Test
    void everyAccessoryIsWellFormed() throws Exception {
        JsonArray items = read("items.json");
        Set<String> ids = new HashSet<>();
        Map<String, Set<Integer>> ranks = new HashMap<>();
        int accessories = 0, stones = 0, effects = 0;
        List<String> problems = new ArrayList<>();
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            assertTrue(ids.add(id), "duplicate id " + id);
            ItemCategory cat = ItemCategory.parse(o.get("category").getAsString());
            Rarity.parse(o.get("rarity").getAsString());
            if (o.has("stats")) {
                for (String k : o.getAsJsonObject("stats").keySet()) if (Stat.parse(k) == null) problems.add(id + " stat " + k);
            }
            JsonObject extra = o.getAsJsonObject("extra");
            if (cat == ItemCategory.ACCESSORY) {
                accessories++;
                String fam = extra.has("family") ? extra.get("family").getAsString() : id;
                int rank = extra.has("familyRank") ? extra.get("familyRank").getAsInt() : 0;
                if (!ranks.computeIfAbsent(fam, k -> new HashSet<>()).add(rank)) problems.add(id + " duplicate rank in " + fam);
                for (Fx f : Fx.parseAll(extra, id)) {
                    List<Fx> flat = new ArrayList<>();
                    f.flatten(flat);
                    for (Fx x : flat) {
                        effects++;
                        if (!x.known) problems.add(id + " unknown effect " + x.type);
                        if (!x.when.valid) problems.add(id + " bad condition " + x.when.text);
                    }
                }
                for (JsonElement l : o.getAsJsonArray("lore")) {
                    String s = l.getAsString();
                    for (char c : s.toCharArray()) if (c < 0x20) problems.add(id + " control char in lore");
                }
            }
            if (cat == ItemCategory.POWER_STONE) stones++;
        }
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(423, accessories);
        assertEquals(22, stones);
        assertTrue(effects > 150, "effects parsed: " + effects);
        // family roots exist
        for (String fam : ranks.keySet()) assertTrue(ids.contains(fam), "family root " + fam);
        assertTrue(ranks.get("ZOMBIE_TALISMAN").containsAll(Set.of(0, 1, 2)));
    }

    @Test
    void equipmentFitsTheFourSlots() throws Exception {
        int n = 0;
        for (JsonElement e : read("equipment.json")) {
            JsonObject o = e.getAsJsonObject();
            ItemCategory cat = ItemCategory.parse(o.get("category").getAsString());
            assertTrue(cat.isEquipment(), o.get("id") + " is " + cat);
            n++;
            for (Fx f : Fx.parseAll(o.getAsJsonObject("extra"), o.get("id").getAsString())) {
                List<Fx> flat = new ArrayList<>();
                f.flatten(flat);
                for (Fx x : flat) assertTrue(x.known && x.when.valid, o.get("id") + " " + x.type);
            }
        }
        assertEquals(147, n);
    }

    @Test
    void thirtyTwoPowersMatchTheirStones() throws Exception {
        JsonArray powers = read("powers.json");
        assertEquals(32, powers.size());
        int stones = 0;
        for (JsonElement e : powers) {
            JsonObject o = e.getAsJsonObject();
            assertTrue(o.getAsJsonObject("base").size() > 0, o.get("id") + " has stats");
            if (!o.get("stone").isJsonNull()) {
                stones++;
                // base * M(250) must give back the stone's printed "at 250" numbers
                JsonObject at = o.getAsJsonObject("at250");
                for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("base").entrySet()) {
                    double back = s.getValue().getAsDouble() * AccMath.multiplier(250);
                    assertEquals(at.get(s.getKey()).getAsDouble(), back, 0.05, o.get("id") + " " + s.getKey());
                }
            }
        }
        assertEquals(22, stones);
    }

    @Test
    void recipesAreWellFormed() throws Exception {
        JsonArray r = read("recipes.json");
        assertTrue(r.size() > 200, "recipes: " + r.size());
        for (JsonElement e : r) {
            JsonObject o = e.getAsJsonObject();
            String type = o.get("type").getAsString();
            assertTrue(type.equals("CRAFTING") || type.equals("FORGE"));
            if (type.equals("CRAFTING")) assertEquals(9, o.getAsJsonArray("grid").size());
            else assertTrue(o.getAsJsonArray("inputs").size() > 0);
        }
    }
}
