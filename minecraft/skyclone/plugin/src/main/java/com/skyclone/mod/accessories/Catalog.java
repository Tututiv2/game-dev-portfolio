package com.skyclone.mod.accessories;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.stat.Stat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Static data of the module: our item ids, the 32 powers, parsed effects, enrichments, compactor recipes. */
final class Catalog {

    record Power(String id, String name, String stone, String requirement, boolean starter,
                 Map<Stat, Double> base, Map<Stat, Double> unique) {}

    final List<String> accessoryIds = new ArrayList<>();
    final List<String> equipmentIds = new ArrayList<>();
    final List<String> stoneIds = new ArrayList<>();
    final List<String> enrichmentIds = new ArrayList<>();
    final Map<String, Power> powers = new LinkedHashMap<>();
    final Map<String, Power> powerByStone = new HashMap<>();
    /** Enrichment stat -> value (from the enrichment items' extra). */
    final Map<Stat, Double> enrichValues = new EnumMap<>(Stat.class);
    final Map<Stat, String> enrichItemByStat = new EnumMap<>(Stat.class);
    final Map<String, Stat> enrichStatById = new HashMap<>();
    String swapperId = "TALISMAN_ENRICHMENT_SWAPPER";
    final List<String> recipeResults = new ArrayList<>();
    final List<String> recipeIngredients = new ArrayList<>();

    private final Map<String, List<Fx>> fx = new ConcurrentHashMap<>();
    private volatile Map<String, Compact> compactable;

    /** input id -> what it compacts into. */
    record Compact(String input, int need, String result, int amount) {}

    void load() {
        JsonArray items = read("items.json");
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            String cat = o.has("category") ? o.get("category").getAsString() : "";
            JsonObject extra = o.has("extra") && o.get("extra").isJsonObject() ? o.getAsJsonObject("extra") : new JsonObject();
            switch (cat) {
                case "ACCESSORY" -> accessoryIds.add(id);
                case "POWER_STONE" -> stoneIds.add(id);
                default -> {
                    if (extra.has("enrichStat") || extra.has("swapper")) enrichmentIds.add(id);
                    if (extra.has("swapper")) swapperId = id;
                }
            }
            if (extra.has("enrichStat")) {
                Stat s = Stat.parse(extra.get("enrichStat").getAsString());
                if (s != null) {
                    enrichValues.put(s, extra.has("enrichValue") ? extra.get("enrichValue").getAsDouble() : 1);
                    enrichItemByStat.put(s, id);
                    enrichStatById.put(id, s);
                }
            }
        }
        for (JsonElement e : read("equipment.json")) equipmentIds.add(e.getAsJsonObject().get("id").getAsString());
        for (JsonElement e : read("powers.json")) {
            JsonObject o = e.getAsJsonObject();
            String stone = o.has("stone") && !o.get("stone").isJsonNull() ? o.get("stone").getAsString() : null;
            String req = o.has("requirement") && !o.get("requirement").isJsonNull() ? o.get("requirement").getAsString() : null;
            Power p = new Power(o.get("id").getAsString(), o.get("name").getAsString(), stone, req,
                    o.has("starter") && o.get("starter").getAsBoolean(), stats(o.getAsJsonObject("base")),
                    stats(o.has("unique") ? o.getAsJsonObject("unique") : new JsonObject()));
            powers.put(p.id(), p);
            if (stone != null) powerByStone.put(stone, p);
        }
        for (JsonElement e : read("recipes.json")) {
            JsonObject o = e.getAsJsonObject();
            recipeResults.add(o.get("result").getAsString());
            List<String> cells = new ArrayList<>();
            if (o.has("grid")) for (JsonElement c : o.getAsJsonArray("grid")) cells.add(c.getAsString());
            if (o.has("inputs")) for (JsonElement c : o.getAsJsonArray("inputs")) cells.add(c.getAsString());
            for (String c : cells) {
                String id = cellId(c);
                if (id != null && !id.equals("COINS")) recipeIngredients.add(id);
            }
        }
    }

    /** "ID:amount" with ids that may contain ':' (INK_SACK:3:16). Mirrors core's cell rule, registry-aware. */
    static String cellId(String c) {
        if (c == null || c.isBlank()) return null;
        if (Sky.items().exists(c)) return c;
        int i = c.lastIndexOf(':');
        return i < 0 ? c : c.substring(0, i);
    }

    private static Map<Stat, Double> stats(JsonObject o) {
        Map<Stat, Double> m = new EnumMap<>(Stat.class);
        if (o == null) return m;
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            Stat s = Stat.parse(en.getKey());
            if (s != null) m.put(s, en.getValue().getAsDouble());
        }
        return m;
    }

    static JsonArray read(String file) {
        try (InputStream in = Sky.plugin().getResource("data/accessories/" + file)) {
            if (in == null) return new JsonArray();
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (Exception e) {
            Sky.log().warning("[accessories] could not read " + file + ": " + e);
            return new JsonArray();
        }
    }

    /** Parsed effects of an item def (cached per id). */
    List<Fx> effects(ItemDef def) {
        if (def == null) return List.of();
        return fx.computeIfAbsent(def.id, k -> Collections.unmodifiableList(Fx.parseAll(def.extra, def.id)));
    }

    void clearFx() { fx.clear(); }

    static String family(ItemDef d) {
        if (d.extra != null && d.extra.has("family")) return d.extra.get("family").getAsString();
        return d.id;
    }

    static int familyRank(ItemDef d) {
        return d.extra != null && d.extra.has("familyRank") ? d.extra.get("familyRank").getAsInt() : 0;
    }

    static boolean noPower(ItemDef d) {
        return d.extra != null && d.extra.has("noPower") && d.extra.get("noPower").getAsBoolean();
    }

    static boolean dungeonAccessory(ItemDef d) {
        return d.extra != null && d.extra.has("dungeon") && d.extra.get("dungeon").getAsBoolean();
    }

    static double mpMult(ItemDef d) {
        return d.extra != null && d.extra.has("mpMult") ? d.extra.get("mpMult").getAsDouble() : 1;
    }

    static boolean isAccessory(ItemDef d) {
        return d != null && d.category == ItemCategory.ACCESSORY;
    }

    static boolean isEquipment(ItemDef d) {
        return d != null && d.category != null && d.category.isEquipment();
    }

    /**
     * Single-ingredient crafting recipes, built lazily once every module registered its recipes:
     * "160 Cobblestone -> 1 Enchanted Cobblestone". The personal compactor uses these.
     */
    Map<String, Compact> compactable() {
        Map<String, Compact> c = compactable;
        if (c != null) return c;
        Map<String, Compact> out = new HashMap<>();
        for (Recipe r : Sky.recipes().byType(Recipe.Type.CRAFTING)) {
            if (r.result == null || r.grid == null) continue;
            String only = null;
            int total = 0;
            boolean ok = true;
            for (String cell : r.grid) {
                if (cell == null || cell.isBlank()) continue;
                String id = cellId(cell);
                int amt = 1;
                if (!cell.equals(id)) {
                    try { amt = (int) Math.round(Double.parseDouble(cell.substring(cell.lastIndexOf(':') + 1))); }
                    catch (NumberFormatException e) { ok = false; break; }
                }
                if (only == null) only = id;
                else if (!only.equals(id)) { ok = false; break; }
                total += amt;
            }
            if (!ok || only == null || total <= 1 || only.equals(r.result)) continue;
            ItemDef res = Sky.items().def(r.result);
            if (res == null || res.isUnique()) continue;
            Compact prev = out.get(only);
            // prefer the ENCHANTED_ form, then the cheapest
            boolean better = prev == null
                    || (r.result.startsWith("ENCHANTED_") && !prev.result().startsWith("ENCHANTED_"))
                    || (r.result.startsWith("ENCHANTED_") == prev.result().startsWith("ENCHANTED_") && total < prev.need());
            if (better) out.put(only, new Compact(only, total, r.result, Math.max(1, r.amount)));
        }
        compactable = out;
        return out;
    }

    void resetCompactable() { compactable = null; }
}
