package com.skyclone.mod.accessories;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * No craft-and-sell coin printer: a crafted accessory, equipment piece or power stone never sells to an NPC for
 * more than {@code accessories.sell-cap-factor} x what its CHEAPEST recipe's inputs sell for.
 *
 * <p>The reference's sell prices assume the reference economy; our inputs are priced by other modules, so
 * e.g. 20 Gazing Pearls at 200 each crafted a Delirium Necklace that sold for 52,000 (crafting.no-coin-loops).
 * The generator already caps the shipped JSON against the data it could see; this re-applies the same rule at
 * postEnable against the prices the server REALLY registered, so a later price change in another module cannot
 * reopen a loop. It only ever lowers a price. Inputs nobody registered, and coins, count 0 (as the crafting
 * check counts them), so an unknown can only make the cap lower. Only CRAFTING and FORGE recipes count: those are
 * the ones a player can repeat at will.
 */
final class SellCap {

    private SellCap() {}

    /** One way to make an item: inputs per craft and how many it yields. */
    record Way(Map<String, Double> inputs, int amount) {}

    /**
     * The prices that must drop. {@code price} is the current npcSell of ANY id (0 if unknown); {@code ways} lists
     * the recipes of one of {@code ours}. Our own items used as inputs are valued at their capped price (an Emperor
     * Ring is worth four capped Emperor Talismans, not its reference price). A cycle back to an item still being
     * valued uses that item's current price, which can only be at or above the cap. Result: id -> new price.
     */
    static Map<String, Double> compute(Collection<String> ours, ToDoubleFunction<String> price,
                                       Function<String, List<Way>> ways, double factor) {
        Set<String> mine = new HashSet<>(ours);
        Map<String, Double> done = new HashMap<>();
        Set<String> busy = new HashSet<>();
        Map<String, Double> lowered = new TreeMap<>();
        for (String id : ours) value(id, mine, price, ways, factor, done, busy, lowered);
        return lowered;
    }

    private static double value(String id, Set<String> mine, ToDoubleFunction<String> price, Function<String, List<Way>> ways,
                                double factor, Map<String, Double> done, Set<String> busy, Map<String, Double> lowered) {
        Double have = done.get(id);
        if (have != null) return have;
        double ref = Math.max(0, price.applyAsDouble(id));
        if (ref <= 0 || busy.contains(id)) return ref;
        busy.add(id);
        double cheapest = Double.MAX_VALUE;
        List<Way> list = ways.apply(id);
        if (list != null) {
            for (Way w : list) {
                double cost = 0;
                for (Map.Entry<String, Double> in : w.inputs().entrySet()) {
                    String ing = in.getKey();
                    double each = mine.contains(ing) ? value(ing, mine, price, ways, factor, done, busy, lowered)
                            : Math.max(0, price.applyAsDouble(ing));
                    cost += each * in.getValue();
                }
                cheapest = Math.min(cheapest, cost / Math.max(1, w.amount()));
            }
        }
        busy.remove(id);
        double v = ref;
        if (cheapest != Double.MAX_VALUE) {
            double cap = Math.floor(cheapest * Math.max(0, factor));
            if (ref > cap) { v = cap; lowered.put(id, cap); }
        }
        done.put(id, v);
        return v;
    }

    /** "ID:amount" (amount optional; ids may contain ':' themselves, e.g. RAW_FISH:2:32). */
    static void addCell(Map<String, Double> into, String cell) {
        if (cell == null || cell.isBlank()) return;
        String c = cell.trim();
        String id = c;
        double amt = 1;
        int k = c.lastIndexOf(':');
        if (k > 0) {
            try { amt = Double.parseDouble(c.substring(k + 1)); id = c.substring(0, k); }
            catch (NumberFormatException ignored) { /* the whole cell is the id */ }
        }
        into.merge(id, amt, Double::sum);
    }

    static Way way(Recipe r) {
        Map<String, Double> in = new LinkedHashMap<>();
        if (r.grid != null) for (String c : r.grid) addCell(in, c);
        if (r.inputs != null) for (String c : r.inputs) addCell(in, c);
        return new Way(in, Math.max(1, r.amount));
    }

    static List<String> ids(Catalog cat) {
        List<String> ids = new ArrayList<>(cat.accessoryIds);
        ids.addAll(cat.equipmentIds);
        ids.addAll(cat.stoneIds);
        return ids;
    }

    static List<Way> liveWays(String id) {
        List<Way> out = new ArrayList<>();
        for (Recipe r : Sky.recipes().producing(id)) {
            if (r.type == Recipe.Type.CRAFTING || r.type == Recipe.Type.FORGE) out.add(way(r));
        }
        return out;
    }

    static double livePrice(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? 0 : d.npcSell;
    }

    /** Lower every live npcSell that breaks the rule. Returns what changed (id -> "old -> new"). */
    static Map<String, String> apply(Catalog cat) {
        Map<String, Double> low = compute(ids(cat), SellCap::livePrice, SellCap::liveWays, AccDials.SELL_CAP_FACTOR.get());
        Map<String, String> changed = new TreeMap<>();
        for (Map.Entry<String, Double> e : low.entrySet()) {
            ItemDef d = Sky.items().def(e.getKey());
            if (d == null) continue;
            changed.put(e.getKey(), Math.round(d.npcSell) + " -> " + Math.round(e.getValue()));
            d.npcSell = e.getValue();
        }
        if (!changed.isEmpty()) {
            Sky.plugin().getLogger().info("[accessories] lowered " + changed.size()
                    + " NPC sell prices to their recipe inputs (accessories.sell-cap-factor): " + changed);
        }
        return changed;
    }

    /** Self-test body: null when no CRAFTING/FORGE recipe of ours sells for more than factor x its inputs. */
    static String check(Catalog cat) {
        double f = AccDials.SELL_CAP_FACTOR.get();
        List<String> bad = new ArrayList<>();
        for (String id : ids(cat)) {
            double out = livePrice(id);
            if (out <= 0) continue;
            for (Way w : liveWays(id)) {
                double in = 0;
                for (Map.Entry<String, Double> e : w.inputs().entrySet()) in += Math.max(0, livePrice(e.getKey())) * e.getValue();
                in /= w.amount();
                if (out > in * f + 0.5) { bad.add(id + " (in " + Math.round(in) + ", sells " + Math.round(out) + ")"); break; }
            }
        }
        return bad.isEmpty() ? null : bad.size() + " of our items sell for more than " + f + "x their recipe inputs: "
                + String.join("; ", bad.subList(0, Math.min(8, bad.size())));
    }
}
