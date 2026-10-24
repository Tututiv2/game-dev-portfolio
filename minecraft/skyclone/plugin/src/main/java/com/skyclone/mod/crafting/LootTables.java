package com.skyclone.mod.crafting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * PURE (Gson only, no Bukkit): reads the loot tables other modules publish as DATA - slayer boss loot, dungeon reward
 * chests, Kuudra reward chests, fishing catches and treasure - into "where does this item come from" lines for the
 * How to Get page.
 *
 * <p>Wave 5. How to Get's "Mob Drop" read {@code MobDef.drops} only, and the bosses roll their loot in code, so
 * Revenant Flesh, a Necron's Handle or a Burning Kuudra Core all answered "No Known Source". Core declined a
 * {@code LootService.sourcesOf} query (core-wave5, "Not done"), so this reads the owners' published JSON instead -
 * read only, never imported code, and every reader tolerates a missing file or a changed shape (it just finds
 * nothing). Lines are display text; nothing here rolls loot.
 */
final class LootTables {

    private LootTables() {}

    /** One way to get an item: {@code order} sorts best first (lower = better), {@code text} is a lore line. */
    record Line(double order, String text) {}

    /** kind ("slayer", "dungeon", "kuudra", "fishing") -&gt; item id (canon) -&gt; lines. */
    static final class Index {
        final Map<String, Map<String, List<Line>>> byKind = new LinkedHashMap<>();

        void add(String kind, String item, double order, String text) {
            if (item == null || item.isBlank()) return;
            byKind.computeIfAbsent(kind, k -> new HashMap<>()).computeIfAbsent(item, k -> new ArrayList<>()).add(new Line(order, text));
        }

        List<Line> get(String kind, String item) {
            Map<String, List<Line>> m = byKind.get(kind);
            if (m == null) return List.of();
            List<Line> l = m.get(item);
            if (l == null) return List.of();
            List<Line> out = new ArrayList<>(l);
            out.sort(Comparator.comparingDouble(Line::order));
            return out;
        }

        int items(String kind) { Map<String, List<Line>> m = byKind.get(kind); return m == null ? 0 : m.size(); }
    }

    // ---- slayer: data/slayer/slayers.json ------------------------------------------------------------------

    /**
     * {@code types[].loot[]}: item, per-tier {@code chance} (PERCENT, 100 = always), {@code min}/{@code max} per tier,
     * {@code minLevel} (slayer level needed). One line per boss: first tier that can drop it, best chance, level.
     */
    static void slayer(JsonElement root, UnaryOperator<String> canon, Index out) {
        JsonObject o = obj(root);
        if (o == null) return;
        for (JsonElement te : arr(o.get("types"))) {
            JsonObject t = obj(te);
            if (t == null) continue;
            String boss = str(t.get("name"), str(t.get("id"), "Slayer Boss"));
            for (JsonElement le : arr(t.get("loot"))) {
                JsonObject l = obj(le);
                if (l == null) continue;
                String item = str(l.get("item"), null);
                if (item == null) continue;
                JsonArray ch = arr(l.get("chance"));
                int first = -1;
                double best = 0;
                for (int i = 0; i < ch.size(); i++) {
                    double c = num(ch.get(i), 0);
                    if (c > 0 && first < 0) first = i;
                    best = Math.max(best, c);
                }
                if (first < 0) continue;
                int lvl = (int) num(l.get("minLevel"), 0);
                int max = 0;
                for (JsonElement m : arr(l.get("max"))) max = Math.max(max, (int) num(m, 0));
                String amt = max > 1 ? "up to " + max + "x, " : "";
                String text = "&c" + boss + " &7Tier " + CraftMath.roman(first + 1) + "+ &8(" + amt + pct(best / 100.0)
                        + (lvl > 0 ? ", LVL " + lvl : "") + ")";
                out.add("slayer", canon.apply(item), -best, text);
            }
        }
    }

    // ---- dungeons: data/dungeons/loot.json ----------------------------------------------------------------

    static final List<String> FLOOR_ORDER = List.of("E", "F1", "F2", "F3", "F4", "F5", "F6", "F7",
            "M1", "M2", "M3", "M4", "M5", "M6", "M7");

    /** "F7" -&gt; "Floor VII", "M3" -&gt; "Master Mode Floor III", "E" -&gt; "Entrance". */
    static String floorName(String key) {
        if (key == null || key.isEmpty()) return "?";
        if (key.equals("E")) return "Entrance";
        try {
            int n = Integer.parseInt(key.substring(1));
            return (key.charAt(0) == 'M' ? "Master Mode Floor " : "Floor ") + CraftMath.roman(n);
        } catch (NumberFormatException e) { return key; }
    }

    /** "BEDROCK" -&gt; "Bedrock Chest". */
    static String chestName(String key) {
        if (key == null || key.isBlank()) return "Reward Chest";
        String k = key.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(k.charAt(0)) + k.substring(1) + " Chest";
    }

    /** {@code floors.<F>.entries[]}: item, minChest (the cheapest chest that can roll it), cost (coins to claim). */
    static void dungeons(JsonElement root, UnaryOperator<String> canon, Index out) {
        JsonObject o = obj(root);
        if (o == null) return;
        JsonObject floors = obj(o.get("floors"));
        if (floors == null) return;
        for (Map.Entry<String, JsonElement> f : floors.entrySet()) {
            JsonObject fl = obj(f.getValue());
            if (fl == null) continue;
            int order = FLOOR_ORDER.indexOf(f.getKey());
            Map<String, String> seen = new HashMap<>();
            for (JsonElement ee : arr(fl.get("entries"))) {
                JsonObject e = obj(ee);
                if (e == null) continue;
                String item = str(e.get("item"), null);
                if (item == null || seen.containsKey(item)) continue;
                seen.put(item, item);
                long cost = (long) num(e.get("cost"), 0);
                String text = "&5The Catacombs &7" + floorName(f.getKey()) + " &8(" + chestName(str(e.get("minChest"), null)) + "+"
                        + (cost > 0 ? ", " + commas(cost) + " coins" : "") + ")";
                out.add("dungeon", canon.apply(item), order < 0 ? 99 : order, text);
            }
        }
    }

    // ---- kuudra: data/kuudra/loot.json --------------------------------------------------------------------

    /**
     * One object per tier: {@code armorPrefix + set + "_" + piece} armour, {@code weapons}, {@code equipment} (paid
     * chest), {@code rares[]} with an absolute chance. Tier names are plain words (kuudra keeps them).
     */
    static void kuudra(JsonElement root, String bossName, UnaryOperator<String> canon, Index out) {
        JsonArray tiers = arr(root);
        for (int ti = 0; ti < tiers.size(); ti++) {
            JsonObject t = obj(tiers.get(ti));
            if (t == null) continue;
            String tier = cap(str(t.get("tier"), "tier " + (ti + 1)));
            String where = "&6" + bossName + " &7" + tier + " tier";
            String prefix = str(t.get("armorPrefix"), "");
            for (JsonElement s : arr(t.get("armorSets"))) {
                for (JsonElement pc : arr(t.get("armorPieces"))) {
                    String id = prefix + str(s, "") + "_" + str(pc, "");
                    out.add("kuudra", canon.apply(id), ti, where + " &8(reward chest)");
                }
            }
            for (String key : new String[]{"weapons", "equipment"}) {
                for (JsonElement w : arr(t.get(key))) out.add("kuudra", canon.apply(str(w, "")), ti, where + " &8(reward chest)");
            }
            for (JsonElement re : arr(t.get("rares"))) {
                JsonObject r = obj(re);
                if (r == null) continue;
                double c = num(r.get("chance"), 0);
                if (c <= 0) continue;
                out.add("kuudra", canon.apply(str(r.get("item"), "")), ti - c, where + " &8(rare, " + pct(c) + ")");
            }
        }
    }

    // ---- fishing: data/fishing/fishing.json ---------------------------------------------------------------

    /**
     * {@code fish[]} (plain catches, by weight) and {@code treasure.<water|lava>.<good|great|outstanding>[]}.
     * The odds of a treasure catch depend on Treasure Chance, so only the pool share is shown.
     */
    static void fishing(JsonElement root, UnaryOperator<String> canon, Index out) {
        JsonObject o = obj(root);
        if (o == null) return;
        for (String key : new String[]{"fish", "lavaFish"}) {
            JsonArray fish = arr(o.get(key));
            double total = 0;
            for (JsonElement fe : fish) { JsonObject f = obj(fe); if (f != null) total += num(f.get("weight"), 0); }
            for (JsonElement fe : fish) {
                JsonObject f = obj(fe);
                if (f == null) continue;
                double share = total > 0 ? num(f.get("weight"), 0) / total : 0;
                out.add("fishing", canon.apply(str(f.get("item"), "")), -share,
                        "&b" + (key.equals("lavaFish") ? "Lava fishing" : "Fishing") + " &7catch &8(" + pct(share) + " of fish)");
            }
        }
        JsonObject tr = obj(o.get("treasure"));
        if (tr == null) return;
        JsonObject qw = obj(tr.get("weights"));
        for (String liquid : new String[]{"water", "lava"}) {
            JsonObject pools = obj(tr.get(liquid));
            if (pools == null) continue;
            for (Map.Entry<String, JsonElement> pe : pools.entrySet()) {
                JsonArray pool = arr(pe.getValue());
                double total = 0;
                for (JsonElement ie : pool) { JsonObject i = obj(ie); if (i != null) total += num(i.get("weight"), 0); }
                double qual = qw == null ? 1 : num(qw.get(pe.getKey()), 0);
                double qualTotal = 0;
                if (qw != null) for (Map.Entry<String, JsonElement> q : qw.entrySet()) qualTotal += num(q.getValue(), 0);
                for (JsonElement ie : pool) {
                    JsonObject i = obj(ie);
                    if (i == null) continue;
                    String item = str(i.get("item"), null);
                    if (item == null || item.equalsIgnoreCase("COINS")) continue;
                    double share = total > 0 ? num(i.get("weight"), 0) / total : 0;
                    double overall = share * (qualTotal > 0 ? qual / qualTotal : 1);
                    int min = (int) num(i.get("min"), 1), max = (int) num(i.get("max"), min);
                    String amt = max > 1 ? (min == max ? min + "x, " : min + "-" + max + "x, ") : "";
                    out.add("fishing", canon.apply(item), -overall, "&b" + cap(pe.getKey()) + " Treasure &7("
                            + (liquid.equals("lava") ? "lava" : "water") + ") &8(" + amt + pct(overall) + " of treasure)");
                }
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    static String pct(double c) {
        if (c >= 1) return "always";
        double p = c * 100;
        if (p >= 10) return Math.round(p) + "%";
        if (p >= 1) return Math.round(p * 10) / 10.0 + "%";
        if (p < 0.001) return "<0.001%";
        if (p < 0.01) return Math.round(p * 1000) / 1000.0 + "%";
        return Math.round(p * 100) / 100.0 + "%";
    }

    private static String commas(long n) { return String.format(Locale.ROOT, "%,d", n); }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static JsonObject obj(JsonElement e) { return e != null && e.isJsonObject() ? e.getAsJsonObject() : null; }

    private static JsonArray arr(JsonElement e) { return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray(); }

    private static String str(JsonElement e, String def) {
        try { return e != null && e.isJsonPrimitive() ? e.getAsString() : def; } catch (RuntimeException ex) { return def; }
    }

    private static double num(JsonElement e, double def) {
        try { return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def; } catch (RuntimeException ex) { return def; }
    }
}
