package com.skyclone.mod.crafting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The crafting sell cap: after it runs, no recipe passes the crafting.no-coin-loops rule's limit. */
class SellCapTest {

    private static SellCap.Way way(String result, int amount, Object... idCount) {
        java.util.List<Map.Entry<Set<String>, Integer>> in = new java.util.ArrayList<>();
        for (int i = 0; i < idCount.length; i += 2) in.add(Map.entry(Set.of((String) idCount[i]), (Integer) idCount[i + 1]));
        return new SellCap.Way(result, amount, in);
    }

    private static boolean breaks(SellCap.Way w, Map<String, Double> price, double f) {
        double in = 0;
        for (Map.Entry<Set<String>, Integer> e : w.inputs()) {
            double best = Double.MAX_VALUE;
            for (String id : e.getKey()) if (price.getOrDefault(id, 0.0) > 0) best = Math.min(best, price.get(id));
            if (best == Double.MAX_VALUE) return false;
            in += best * e.getValue();
        }
        return price.getOrDefault(w.result(), 0.0) * w.amount() > in * f + 0.5;
    }

    @Test
    void lowersOnlyWhatBreaksTheRuleAndChainsThroughLoweredInputs() {
        Map<String, Double> price = new java.util.HashMap<>(Map.of(
                "YOGGIE", 500.0, "YOG_HELM", 38_000.0, "YOG_SET_BOX", 200_000.0, "FINE", 10.0, "FINE_BLOCK", 90.0));
        List<SellCap.Way> ways = List.of(
                way("YOG_HELM", 1, "YOGGIE", 8),          // 4,000 in -> 38,000 out: must drop to ~8,000
                way("YOG_SET_BOX", 1, "YOG_HELM", 4),     // only breaks once the helm has dropped
                way("FINE_BLOCK", 1, "FINE", 9));         // 90 vs 90: untouched
        Map<String, Double> low = SellCap.compute(ways, id -> price.getOrDefault(id, 0.0), 2.0);
        assertEquals(8_000.5, low.get("YOG_HELM"));   // floor to 0.1 of (4,000 x 2 + 0.5) / 1
        assertEquals(64_004.5, low.get("YOG_SET_BOX")); // (4 x 8,000.5) x 2 + 0.5
        assertFalse(low.containsKey("FINE_BLOCK"));
        price.putAll(low);
        for (SellCap.Way w : ways) assertFalse(breaks(w, price, 2.0), w.result());
    }

    @Test
    void unsellableInputsAreNotJudgedAndCyclesEnd() {
        // npcSell 0 = cannot be sold, not free: 9 unsellable nuggets -> 1 ingot must NOT zero the ingot.
        Map<String, Double> price = new java.util.HashMap<>(Map.of("EMBER_HELM", 1_000.0, "INGOT", 5.0, "BLOCK", 45.0));
        List<SellCap.Way> ways = List.of(
                way("EMBER_HELM", 1, "MAGMA_FRAGMENT", 5),
                way("INGOT", 1, "NUGGET", 9),
                way("BLOCK", 1, "INGOT", 9), way("INGOT", 9, "BLOCK", 1));
        Map<String, Double> low = SellCap.compute(ways, id -> price.getOrDefault(id, 0.0), 2.0);
        assertTrue(low.isEmpty(), "nothing judgeable breaks the rule: " + low);
        assertTrue(SellCap.compute(List.of(way("X", 1, "INGOT", 1)), id -> id.equals("X") ? 99.0 : price.getOrDefault(id, 0.0), 0).isEmpty(),
                "factor 0 = off");
    }

    @Test
    void alternativesCountAtTheirCheapest() {
        Map<String, Double> price = Map.of("OAK", 2.0, "DARK_OAK", 50.0, "AXE", 100.0);
        List<Map.Entry<Set<String>, Integer>> in = List.of(Map.entry(Set.of("OAK", "DARK_OAK"), 3));
        Map<String, Double> low = SellCap.compute(List.of(new SellCap.Way("AXE", 1, in)), id -> price.getOrDefault(id, 0.0), 2.0);
        assertEquals(12.5, low.get("AXE"));
    }
}
