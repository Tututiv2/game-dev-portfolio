package com.auraqol.skyblock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One immutable snapshot of every price the mod knows. Pure: parsing and the
 * on-disk cache format live here, the network does not.
 *
 * <p><b>Immutable on purpose.</b> The fetcher runs on its own thread and the
 * tooltip reads on the render thread. Rather than lock, the fetcher builds a
 * whole new snapshot and swaps one volatile reference, so a reader always
 * sees a complete, consistent set - never a bazaar map that is half-filled.
 *
 * <p><b>Gson here, hand-parsing in HudConfig.</b> The config avoided Gson to
 * stay total over a file a human edits. These are 0.1-5 MB machine-written
 * payloads with nested arrays; a real parser is the right tool, and Gson is
 * always present because Minecraft itself depends on it. Every entry point
 * still catches everything: a bad payload yields {@code null}, never a throw.
 *
 * <h2>Field meanings, checked against the live API on 2026-10-07</h2>
 * Hypixel names the bazaar sides from the ORDER BOOK's point of view, which
 * is backwards from a player's:
 * <ul>
 *   <li>{@code sell_summary} = the buy orders. Its first entry (highest) is
 *       what you get when you <b>insta-sell</b>.</li>
 *   <li>{@code buy_summary} = the sell offers. Its first entry (lowest) is
 *       what you pay to <b>insta-buy</b>.</li>
 * </ul>
 * The top of each book is used rather than {@code quick_status}, because
 * quick_status.buyPrice is a weighted average of the top 2% of volume and
 * reads ~1.5% higher than the price the in-game Bazaar actually charges.
 * quick_status is the fallback when a book is empty.
 */
public final class PriceData {

    /** Per-unit bazaar prices. 0 means "no orders on that side right now". */
    public record Bazaar(double instabuy, double instasell) {
    }

    public static final PriceData EMPTY = new PriceData(
            Map.of(), 0, Map.of(), 0, "", Map.of(), Map.of(), Map.of(), 0);

    private final Map<String, Bazaar> bazaar;
    private final long bazaarAt;
    private final Map<String, Double> lbin;
    private final long lbinAt;
    private final String lbinSource;
    private final Map<String, Double> npc;
    /** lower-cased, colour-stripped display name -> item ids with that name. */
    private final Map<String, List<String>> names;
    /** item id -> colour-stripped display name, original case ("Enchanted Diamond"). */
    private final Map<String, String> display;
    private final long itemsAt;

    private PriceData(Map<String, Bazaar> bazaar, long bazaarAt,
                      Map<String, Double> lbin, long lbinAt, String lbinSource,
                      Map<String, Double> npc, Map<String, List<String>> names,
                      Map<String, String> display, long itemsAt) {
        this.bazaar = bazaar;
        this.bazaarAt = bazaarAt;
        this.lbin = lbin;
        this.lbinAt = lbinAt;
        this.lbinSource = lbinSource == null ? "" : lbinSource;
        this.npc = npc;
        this.names = names;
        this.display = display == null ? Map.of() : display;
        this.itemsAt = itemsAt;
    }

    // ------------------------------------------------------------ accessors

    public Bazaar bazaar(String productId) {
        return productId == null ? null : bazaar.get(productId);
    }

    public boolean isBazaarProduct(String id) {
        return id != null && bazaar.containsKey(id);
    }

    /** Lowest BIN for a key, or NaN. */
    public double lbin(String key) {
        Double v = key == null ? null : lbin.get(key);
        return v == null ? Double.NaN : v;
    }

    /** NPC sell price per unit, or NaN. */
    public double npc(String id) {
        Double v = id == null ? null : npc.get(id);
        return v == null ? Double.NaN : v;
    }

    /**
     * The item id behind a display name like "Enchanted Diamond", as the
     * [Sacks] chat summary writes it. When two items share a name, the one
     * that trades on the bazaar wins - sack contents are bazaar materials, and
     * the duplicates in the item list are armour sets and cosmetic hats.
     */
    public String idForName(String displayName) {
        if (displayName == null) {
            return null;
        }
        List<String> ids = names.get(Fmt.strip(displayName).trim().toLowerCase(Locale.ROOT));
        if (ids == null || ids.isEmpty()) {
            // Modern Hypixel puts private-use font icons in front of some
            // names ("<icon> Rough Ruby Gemstone"); the items API never does.
            ids = names.get(Fmt.plainName(displayName).toLowerCase(Locale.ROOT));
        }
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        if (ids.size() == 1) {
            return ids.get(0);
        }
        // Several items share the name. Being a bazaar product is not enough:
        // "Enchanted Hay Bale" is both ENCHANTED_HAY_BALE (traded, ~150k) and
        // the legacy ENCHANTED_HAY_BLOCK, which is still a bazaar KEY but has
        // no orders at all - picking it priced every hay bale at nothing
        // (live check, 2026-10-08). So: a bazaar product with a price, then
        // anything with a lowest BIN, then a bare bazaar key, then the first.
        String best = ids.get(0);
        int bestScore = -1;
        for (String id : ids) {
            Bazaar b = bazaar.get(id);
            int score = b != null && (b.instasell() > 0 || b.instabuy() > 0) ? 3
                    : lbin.containsKey(id) ? 2
                    : b != null ? 1 : 0;
            if (score > bestScore) {
                best = id;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * The item's own name as the items API spells it ("Enchanted Diamond"),
     * or null when unknown. This is what the in-game Bazaar and Auction House
     * searches match, so it beats whatever reforge or count the clicked
     * stack's display name carries.
     */
    public String nameForId(String id) {
        return id == null ? null : display.get(id);
    }

    public long bazaarAt() {
        return bazaarAt;
    }

    public long lbinAt() {
        return lbinAt;
    }

    public String lbinSource() {
        return lbinSource;
    }

    public long itemsAt() {
        return itemsAt;
    }

    public int bazaarSize() {
        return bazaar.size();
    }

    public int lbinSize() {
        return lbin.size();
    }

    public int npcSize() {
        return npc.size();
    }

    public boolean isEmpty() {
        return bazaar.isEmpty() && lbin.isEmpty() && npc.isEmpty();
    }

    // ------------------------------------------------------------ copy-on-write

    public PriceData withBazaar(Map<String, Bazaar> b, long at) {
        return new PriceData(Collections.unmodifiableMap(b), at, lbin, lbinAt, lbinSource, npc, names, display, itemsAt);
    }

    public PriceData withLbin(Map<String, Double> l, long at, String source) {
        return new PriceData(bazaar, bazaarAt, Collections.unmodifiableMap(l), at, source, npc, names, display, itemsAt);
    }

    public PriceData withItems(Items items, long at) {
        return new PriceData(bazaar, bazaarAt, lbin, lbinAt, lbinSource,
                Collections.unmodifiableMap(items.npc()), Collections.unmodifiableMap(items.names()),
                Collections.unmodifiableMap(items.display()), at);
    }

    // ------------------------------------------------------------ parsers

    /** NPC prices and the name indexes (both directions), parsed from /v2/resources/skyblock/items. */
    public record Items(Map<String, Double> npc, Map<String, List<String>> names, Map<String, String> display) {

        public Items {
            display = display == null ? Map.of() : display;
        }

        public Items(Map<String, Double> npc, Map<String, List<String>> names) {
            this(npc, names, Map.of());
        }
    }

    /**
     * {@code GET https://api.hypixel.net/v2/skyblock/bazaar}. Returns null if
     * the payload is not a successful bazaar response.
     */
    public static Map<String, Bazaar> parseBazaar(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                return null;
            }
            JsonObject products = root.getAsJsonObject("products");
            Map<String, Bazaar> out = new HashMap<>(products.size() * 2);
            for (Map.Entry<String, JsonElement> e : products.entrySet()) {
                try {
                    JsonObject p = e.getValue().getAsJsonObject();
                    JsonObject q = p.has("quick_status") ? p.getAsJsonObject("quick_status") : null;
                    double sell = top(p, "sell_summary");
                    double buy = top(p, "buy_summary");
                    if (!(sell > 0) && q != null) {
                        sell = num(q, "sellPrice");
                    }
                    if (!(buy > 0) && q != null) {
                        buy = num(q, "buyPrice");
                    }
                    out.put(e.getKey(), new Bazaar(clean(buy), clean(sell)));
                } catch (RuntimeException ignored) {
                    // One malformed product must not cost the other two thousand.
                }
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A flat {@code {"ITEM_ID": price, ...}} lowest-BIN object - the shape of
     * both hysky.de/api/auctions/lowestbins and sky.coflnet.com/api/prices/neu.
     * Returns null unless it parsed into at least one price.
     */
    public static Map<String, Double> parseLowestBin(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Double> out = new HashMap<>(root.size() * 2);
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonElement v = e.getValue();
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    double d = v.getAsDouble();
                    if (d > 0 && !Double.isInfinite(d)) {
                        out.put(e.getKey(), d);
                    }
                }
            }
            return out.isEmpty() ? null : out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code GET https://api.hypixel.net/v2/resources/skyblock/items}. Null on failure. */
    public static Items parseItems(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                return null;
            }
            JsonArray items = root.getAsJsonArray("items");
            Map<String, Double> npc = new HashMap<>(items.size() * 2);
            Map<String, List<String>> names = new HashMap<>(items.size() * 2);
            Map<String, String> display = new HashMap<>(items.size() * 2);
            for (JsonElement el : items) {
                try {
                    JsonObject it = el.getAsJsonObject();
                    String id = it.get("id").getAsString();
                    if (it.has("npc_sell_price")) {
                        double p = it.get("npc_sell_price").getAsDouble();
                        if (p > 0) {
                            npc.put(id, p);
                        }
                    }
                    if (it.has("name")) {
                        String shown = Fmt.strip(it.get("name").getAsString()).trim();
                        names.computeIfAbsent(shown.toLowerCase(Locale.ROOT), k -> new ArrayList<>(1)).add(id);
                        if (!shown.isEmpty()) {
                            display.put(id, shown);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // skip the one bad item
                }
            }
            return new Items(npc, names, display);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double top(JsonObject product, String side) {
        if (!product.has(side)) {
            return 0;
        }
        JsonArray arr = product.getAsJsonArray(side);
        if (arr.size() == 0) {
            return 0;
        }
        return num(arr.get(0).getAsJsonObject(), "pricePerUnit");
    }

    private static double num(JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v == null || !v.isJsonPrimitive()) {
            return 0;
        }
        try {
            return v.getAsDouble();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double clean(double d) {
        return d > 0 && !Double.isInfinite(d) ? d : 0;
    }

    // ------------------------------------------------------------ disk cache

    /**
     * The compact cache: only the numbers the mod uses, ~500 KB instead of
     * the ~8 MB the three raw responses add up to.
     */
    public String toCacheJson() {
        JsonObject root = new JsonObject();
        root.addProperty("v", 1);
        root.addProperty("bazaarAt", bazaarAt);
        JsonObject bz = new JsonObject();
        for (Map.Entry<String, Bazaar> e : bazaar.entrySet()) {
            JsonArray a = new JsonArray();
            a.add(e.getValue().instabuy());
            a.add(e.getValue().instasell());
            bz.add(e.getKey(), a);
        }
        root.add("bazaar", bz);
        root.addProperty("lbinAt", lbinAt);
        root.addProperty("lbinSource", lbinSource);
        JsonObject lb = new JsonObject();
        lbin.forEach(lb::addProperty);
        root.add("lbin", lb);
        root.addProperty("itemsAt", itemsAt);
        JsonObject np = new JsonObject();
        npc.forEach(np::addProperty);
        root.add("npc", np);
        JsonObject nm = new JsonObject();
        for (Map.Entry<String, List<String>> e : names.entrySet()) {
            JsonArray a = new JsonArray();
            e.getValue().forEach(a::add);
            nm.add(e.getKey(), a);
        }
        root.add("names", nm);
        JsonObject dn = new JsonObject();
        display.forEach(dn::addProperty);
        root.add("display", dn);
        return root.toString();
    }

    /** Inverse of {@link #toCacheJson}. Anything unreadable yields {@link #EMPTY}. */
    public static PriceData fromCacheJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Bazaar> bz = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("bazaar").entrySet()) {
                JsonArray a = e.getValue().getAsJsonArray();
                bz.put(e.getKey(), new Bazaar(a.get(0).getAsDouble(), a.get(1).getAsDouble()));
            }
            Map<String, Double> lb = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("lbin").entrySet()) {
                lb.put(e.getKey(), e.getValue().getAsDouble());
            }
            Map<String, Double> np = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("npc").entrySet()) {
                np.put(e.getKey(), e.getValue().getAsDouble());
            }
            Map<String, List<String>> nm = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) {
                List<String> ids = new ArrayList<>(1);
                for (JsonElement id : e.getValue().getAsJsonArray()) {
                    ids.add(id.getAsString());
                }
                nm.put(e.getKey(), ids);
            }
            // Added after the first cache format: an older cache simply has
            // none, and the items fetch on the first tick fills it in.
            Map<String, String> dn = new HashMap<>();
            if (root.has("display")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("display").entrySet()) {
                    dn.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return new PriceData(
                    Collections.unmodifiableMap(bz), root.get("bazaarAt").getAsLong(),
                    Collections.unmodifiableMap(lb), root.get("lbinAt").getAsLong(),
                    root.has("lbinSource") ? root.get("lbinSource").getAsString() : "",
                    Collections.unmodifiableMap(np), Collections.unmodifiableMap(nm),
                    Collections.unmodifiableMap(dn), root.get("itemsAt").getAsLong());
        } catch (RuntimeException e) {
            return EMPTY;
        }
    }
}
