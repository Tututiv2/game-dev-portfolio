package com.skyclone.mod.auction;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "What is this worth?" - for the Create Auction price helper, the Auction Agents' stock and the
 * NPC buyers. Four sources, most trusted first:
 * <ol>
 *   <li><b>This server's sales</b>: the median of the last player-to-player sales of the id (only
 *       player sales: an NPC sale priced from this guide would feed the guide its own number).</li>
 *   <li><b>Market reference</b>: the lower-quartile unit price of the id in the reference game's
 *       live Auction House sample ({@code data/auction/prices.json}, generated).</li>
 *   <li><b>Crafted cost</b>: walk the recipe registry and price every ingredient at its Bazaar
 *       instant-buy (live service, else the research snapshot), its market reference, or its own
 *       crafted cost, recursively. Coins in a recipe (NPC shops) count as coins.</li>
 *   <li>Nothing: the guide says it does not know, and no NPC will touch the item.</li>
 * </ol>
 */
public final class PriceGuide {

    public record Ref(String id, double ref, double min, int samples, String tier) {}

    public record Guide(double lowestBin, double serverMedian, int serverSales, double reference,
                        double crafted, double suggested, String source) {}

    private final Map<String, Ref> refs = new HashMap<>();
    private final Map<String, Double> bazaarRef = new HashMap<>();
    /** item id -> recent player sale unit prices, oldest first (bounded). */
    private final Map<String, Deque<Double>> sales = new HashMap<>();
    private final Map<String, Double> craftCache = new HashMap<>();
    private long craftCacheAt;

    static final int SALES_KEPT = 30;
    static final int MAX_DEPTH = 7;

    // ---- loading ----------------------------------------------------------------------------

    public void load() {
        refs.clear();
        bazaarRef.clear();
        JsonElement p = read("prices.json");
        if (p instanceof JsonArray arr) {
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                String id = o.get("id").getAsString();
                refs.put(id, new Ref(id, o.get("ref").getAsDouble(), o.get("min").getAsDouble(),
                        o.get("n").getAsInt(), o.has("tier") ? o.get("tier").getAsString() : "COMMON"));
            }
        }
        JsonElement b = read("bazaar_ref.json");
        if (b instanceof JsonObject obj) {
            for (var en : obj.entrySet()) bazaarRef.put(en.getKey(), en.getValue().getAsDouble());
        }
    }

    static JsonElement read(String file) {
        try (InputStream in = Sky.plugin().getResource("data/auction/" + file)) {
            if (in == null) { Sky.log().warning("[auction] missing data/auction/" + file); return null; }
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonElement.class);
        } catch (Exception e) {
            Sky.log().warning("[auction] could not read data/auction/" + file + ": " + e);
            return null;
        }
    }

    public Map<String, Ref> refs() { return Collections.unmodifiableMap(refs); }

    public int bazaarRefCount() { return bazaarRef.size(); }

    // ---- server sales -------------------------------------------------------------------------

    public void recordSale(String itemId, double unitPrice) {
        if (itemId == null || !(unitPrice > 0)) return;
        Deque<Double> d = sales.computeIfAbsent(itemId, k -> new ArrayDeque<>());
        d.addLast(unitPrice);
        while (d.size() > SALES_KEPT) d.removeFirst();
    }

    public int salesCount(String itemId) {
        Deque<Double> d = sales.get(itemId);
        return d == null ? 0 : d.size();
    }

    public double serverMedian(String itemId) {
        Deque<Double> d = sales.get(itemId);
        if (d == null || d.isEmpty()) return 0;
        return median(new ArrayList<>(d));
    }

    static double median(List<Double> xs) {
        if (xs.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    // ---- lookups --------------------------------------------------------------------------------

    public double reference(String itemId) {
        Ref r = refs.get(itemId);
        return r == null ? 0 : r.ref();
    }

    /**
     * Bazaar instant-buy for an ingredient: the live Bazaar when it knows the item, else the snapshot. When both
     * exist the LOWER wins: on a thin order book one player's sell offer IS the instant-buy price, and a crafted
     * cost built from it feeds the Agents' buy limit.
     */
    public double bazaarBuy(String itemId) {
        Double snap = bazaarRef.get(itemId);
        double ref = snap == null ? 0 : snap;
        try {
            Features.Bazaar bz = Sky.service(Features.Bazaar.class);
            if (bz.isBazaarItem(itemId)) {
                double v = bz.instantBuy(itemId);
                if (v > 0) return ref > 0 ? Math.min(v, ref) : v;
            }
        } catch (Throwable ignored) { }
        return ref;
    }

    /** Unit value of an INGREDIENT: bazaar, market reference, crafted cost, NPC value. 0 = unknown. */
    double ingredientValue(String id, int depth, Set<String> visiting) {
        double v = bazaarBuy(id);
        if (v > 0) return v;
        v = reference(id);
        if (v > 0) return v;
        v = crafted(id, depth, visiting);
        if (v > 0) return v;
        ItemDef d = Sky.items().def(id);
        if (d != null && d.npcSell > 0) return d.npcSell;   // raw resources: what an NPC pays is a floor on their worth
        return 0;
    }

    /** Cost to craft one {@code id} from its cheapest recipe; 0 when it has none or an input is unpriceable. */
    public double crafted(String id) {
        long now = System.currentTimeMillis();
        if (now - craftCacheAt > 10 * 60_000L) { craftCache.clear(); craftCacheAt = now; }
        return crafted(id, 0, new HashSet<>());
    }

    private double crafted(String id, int depth, Set<String> visiting) {
        Double cached = craftCache.get(id);
        if (cached != null) return cached;
        if (depth > MAX_DEPTH || !visiting.add(id)) return 0;
        double best = Double.POSITIVE_INFINITY;
        try {
            for (Recipe r : Sky.recipes().producing(id)) {
                double cost = recipeCost(r, depth, visiting);
                if (cost > 0) best = Math.min(best, cost / Math.max(1, r.amount));
            }
        } catch (Throwable ignored) {
            best = Double.POSITIVE_INFINITY;
        } finally {
            visiting.remove(id);
        }
        double out = Double.isFinite(best) ? best : 0;
        // cache every answer, zeros included: without that an unpriceable ingredient is re-explored once
        // per parent and the walk goes exponential on deep recipe trees (a zero caused by the cycle guard
        // is path-dependent, but a cycle-only recipe is no way to make the item anyway)
        craftCache.put(id, out);
        return out;
    }

    private double recipeCost(Recipe r, int depth, Set<String> visiting) {
        List<String> cells = new ArrayList<>();
        if (r.grid != null) for (String c : r.grid) if (c != null && !c.isBlank()) cells.add(c);
        if (r.inputs != null) cells.addAll(r.inputs);
        if (cells.isEmpty()) return 0;
        double total = 0;
        for (String cell : cells) {
            String[] parsed = parseCell(cell);
            if (parsed == null) return 0;
            String ing = parsed[0];
            double amt = Double.parseDouble(parsed[1]);
            if ("COINS".equals(ing)) { total += amt; continue; }
            if (ing.equals(r.result)) return 0;
            double unit = ingredientValue(ing, depth + 1, visiting);
            if (unit <= 0) return 0;
            total += unit * amt;
        }
        return total;
    }

    /** "ID:amount" with ids that may contain ':' (core's rule: a cell that IS an id means x1). */
    static String[] parseCell(String cell) {
        String c = cell.trim();
        if (c.isEmpty()) return null;
        try {
            if (Sky.items().exists(c)) return new String[]{c, "1"};
        } catch (Throwable ignored) { }
        int i = c.lastIndexOf(':');
        if (i <= 0) return new String[]{c, "1"};
        String tail = c.substring(i + 1);
        try {
            double amt = Double.parseDouble(tail);
            return new String[]{c.substring(0, i), String.valueOf(amt)};
        } catch (NumberFormatException e) {
            return new String[]{c, "1"};
        }
    }

    // ---- guide ------------------------------------------------------------------------------------

    /**
     * What NPC BUYERS consider the item worth, per unit; 0 = unknown, and then no Agent will buy it.
     *
     * <p>This number turns into coins created from nothing, so only sources a player cannot write are allowed
     * to RAISE it: the market reference, else the crafting cost. Server sales may only LOWER it. (An earlier
     * version let recent sales lift it to 1.5x the anchor, and used the sales median outright for items with
     * no anchor - two alts selling junk to each other three times at 4.9M each then had the Agents paying
     * 4.4M for it, every day, per profile.) See {@link #fairValue(double, double, int, double)}.
     */
    public double fairValue(String itemId) {
        double r = reference(itemId);
        double anchor = r > 0 ? r : crafted(itemId);
        return fairValue(anchor, serverMedian(itemId), salesCount(itemId), 3);
    }

    /** Pure: the anchor, lowered (never raised) by the median of at least {@code minSales} server sales. */
    static double fairValue(double anchor, double median, int sales, int minSales) {
        if (!(anchor > 0)) return 0;
        if (sales >= minSales && median > 0) return Math.min(anchor, median);
        return anchor;
    }

    /** What an Auction Agent would ask, per unit: market reference first, else crafted cost; 0 = do not stock. */
    public double agentValue(String itemId) {
        double r = reference(itemId);
        if (r > 0) return r;
        return crafted(itemId);
    }

    public Guide guide(String itemId, double lowestBin) {
        int n = salesCount(itemId);
        double med = serverMedian(itemId);
        double ref = reference(itemId);
        double cr = crafted(itemId);
        double sug;
        String src;
        if (n >= 3) { sug = med; src = "recent sales"; }
        else if (lowestBin > 0) { sug = Math.max(1, Math.floor(lowestBin) - 1); src = "lowest BIN"; }
        else if (ref > 0) { sug = ref; src = "market reference"; }
        else if (cr > 0) { sug = cr; src = "crafting cost"; }
        else { sug = 0; src = "unknown"; }
        return new Guide(lowestBin, med, n, ref, cr, sug, src);
    }
}
