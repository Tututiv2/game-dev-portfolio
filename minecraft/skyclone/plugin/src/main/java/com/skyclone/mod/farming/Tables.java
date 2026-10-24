package com.skyclone.mod.farming;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generated tables (data/farming/tables.json and shops.json). Pure: built from a Reader so the unit
 * tests read the same files the plugin ships.
 */
final class Tables {

    int[] toolLevelXp = new int[0];
    final Map<String, Integer> personalBestStep = new HashMap<>();
    /** index = tier-1: {gold, tickets}. */
    final List<int[]> anitaFortune = new ArrayList<>();
    /** index = step-1 (cap 51..60): {gold, tickets}. */
    final List<int[]> farmingCap = new ArrayList<>();
    final Map<String, int[]> cropMilestones = new HashMap<>();
    final Map<String, String> npcSkins = new HashMap<>();
    /** Shiny Pig bestiary kill thresholds (NEU bracket 5 up to the family cap) - tier N = N thresholds reached. */
    int[] shinyPigTiers = new int[0];
    /** shop id -> entries. */
    final Map<String, List<ShopEntry>> shops = new LinkedHashMap<>();

    static final class ShopEntry {
        String result;
        int amount = 1;
        double coins;
        int bronze, silver, gold;
        long pelts;
        /** "ITEM:amount". */
        final List<String> items = new ArrayList<>();
        /** non-null when the entry pays a medal instead of an item. */
        String medal;
    }

    static Tables read(Reader tables, Reader shops) {
        Gson g = new Gson();
        Tables t = new Tables();
        JsonObject o = g.fromJson(tables, JsonObject.class);
        JsonArray lv = o.getAsJsonArray("toolLevelXp");
        t.toolLevelXp = new int[lv.size()];
        for (int i = 0; i < lv.size(); i++) t.toolLevelXp[i] = lv.get(i).getAsInt();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("personalBestStep").entrySet())
            t.personalBestStep.put(e.getKey(), e.getValue().getAsInt());
        for (JsonElement e : o.getAsJsonArray("anitaFortune"))
            t.anitaFortune.add(new int[]{e.getAsJsonObject().get("gold").getAsInt(), e.getAsJsonObject().get("tickets").getAsInt()});
        for (JsonElement e : o.getAsJsonArray("farmingCap"))
            t.farmingCap.add(new int[]{e.getAsJsonObject().get("gold").getAsInt(), e.getAsJsonObject().get("tickets").getAsInt()});
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("cropMilestones").entrySet()) {
            JsonArray a = e.getValue().getAsJsonArray();
            int[] v = new int[a.size()];
            for (int i = 0; i < v.length; i++) v[i] = a.get(i).getAsInt();
            t.cropMilestones.put(e.getKey(), v);
        }
        if (o.has("npcSkins"))
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("npcSkins").entrySet()) t.npcSkins.put(e.getKey(), e.getValue().getAsString());
        if (o.has("shinyPigTiers")) {
            JsonArray a = o.getAsJsonArray("shinyPigTiers");
            t.shinyPigTiers = new int[a.size()];
            for (int i = 0; i < a.size(); i++) t.shinyPigTiers[i] = a.get(i).getAsInt();
        }

        JsonObject s = g.fromJson(shops, JsonObject.class);
        for (Map.Entry<String, JsonElement> e : s.entrySet()) {
            List<ShopEntry> list = new ArrayList<>();
            for (JsonElement el : e.getValue().getAsJsonArray()) {
                JsonObject eo = el.getAsJsonObject();
                ShopEntry se = new ShopEntry();
                se.result = eo.get("result").getAsString();
                se.amount = eo.has("amount") ? eo.get("amount").getAsInt() : 1;
                if (eo.has("medal")) se.medal = eo.get("medal").getAsString();
                JsonObject c = eo.getAsJsonObject("cost");
                if (c != null) {
                    se.coins = c.has("coins") ? c.get("coins").getAsDouble() : 0;
                    se.bronze = c.has("bronze") ? c.get("bronze").getAsInt() : 0;
                    se.silver = c.has("silver") ? c.get("silver").getAsInt() : 0;
                    se.gold = c.has("gold") ? c.get("gold").getAsInt() : 0;
                    se.pelts = c.has("pelts") ? c.get("pelts").getAsLong() : 0;
                    if (c.has("items")) for (JsonElement ie : c.getAsJsonArray("items")) se.items.add(ie.getAsString());
                }
                list.add(se);
            }
            t.shops.put(e.getKey(), list);
        }
        return t;
    }

    static Tables fromJar(ClassLoader cl) {
        try (InputStream a = cl.getResourceAsStream("data/farming/tables.json");
             InputStream b = cl.getResourceAsStream("data/farming/shops.json")) {
            if (a == null || b == null) throw new IllegalStateException("data/farming/tables.json or shops.json missing from the jar");
            return read(new InputStreamReader(a, StandardCharsets.UTF_8), new InputStreamReader(b, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    int maxToolLevel() { return toolLevelXp.length + 1; }

    int[] milestones(Crop c) { return cropMilestones.getOrDefault(c.milestoneKey, new int[0]); }

    /** Personal Bests step for a crop (skyhanni personal_best_increment), 0 when unknown. */
    int pbStep(Crop c) { return personalBestStep.getOrDefault(c.milestoneKey, 0); }
}
