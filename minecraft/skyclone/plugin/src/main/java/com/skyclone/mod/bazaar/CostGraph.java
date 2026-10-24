package com.skyclone.mod.bazaar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cheapest known way to GET one unit of each item (buy it from the bots, buy it from an NPC shop, or make it from
 * things got the same way) and, from that, every market-made product's recipe cap: the bots never bid more than 99%
 * of what the inputs cost. PURE (no Bukkit, no Sky) so the money-printer guard can be unit tested.
 *
 * <p>Two operations, and the second is the one that matters:
 * <ul>
 *   <li>{@link #reset}: replace every direct source and relax to the fixed point (once per refresh cycle);</li>
 *   <li>{@link #lower}: one product's ask just dropped, so its cost drops, and so does the cost of everything made
 *       from it, transitively. Returns every product whose cap may now be lower, so the caller can re-clamp a bid
 *       that is ALREADY on the book. Without this, an input re-quoted cheaper mid-cycle left its outputs' bids
 *       above the new input cost until the next cycle: craft-and-sell was free money in between.</li>
 * </ul>
 * Costs only ever go DOWN between resets, which is what keeps a quoted bid valid: a bid clamped against these costs
 * stays under them until a lowering says otherwise, and every lowering names who to re-check.
 */
final class CostGraph {

    /** A recipe reduced to what the caps need. A two-books-make-one ladder step is a recipe too. */
    record Recipe(String result, int amount, Map<String, Long> need) {}

    /** A bid may be at most this fraction of the cheapest way to make the item. */
    static final double CAP_FRACTION = 0.99;
    /**
     * A cycle that makes more than it eats (A -> 2B, B -> A) halves its costs for ever; each id may be lowered at most
     * this many times per relaxation (the old fixed-round search stopped after 10 rounds for the same reason).
     */
    static final int MAX_LOWERINGS = 24;

    private final Map<String, List<Recipe>> byResult = new HashMap<>();
    private final Map<String, List<Recipe>> byInput = new HashMap<>();
    private final List<Recipe> coinOnly = new ArrayList<>();
    private final Map<String, Double> acq = new HashMap<>();

    CostGraph(Collection<Recipe> recipes) {
        for (Recipe r : recipes) {
            if (r == null || r.result() == null || r.amount() <= 0 || r.need() == null || r.need().isEmpty()
                    || r.need().containsKey(r.result())) continue;
            byResult.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
            boolean items = false;
            for (String in : r.need().keySet()) {
                if (isCoins(in)) continue;
                items = true;
                List<Recipe> l = byInput.computeIfAbsent(in, k -> new ArrayList<>());
                if (!l.contains(r)) l.add(r);
            }
            if (!items) coinOnly.add(r);
        }
    }

    static boolean isCoins(String id) { return "COINS".equals(id) || "SKYBLOCK_COIN".equals(id); }

    /** Replace every direct source (coins per unit; non-positive = none) and relax to the fixed point. */
    void reset(Map<String, Double> direct) {
        acq.clear();
        Map<String, Integer> n = new HashMap<>();
        Deque<String> work = new ArrayDeque<>();
        for (Map.Entry<String, Double> e : direct.entrySet()) {
            if (lowerOne(e.getKey(), e.getValue() == null ? -1 : e.getValue(), n)) work.add(e.getKey());
        }
        for (Recipe r : coinOnly) {
            double c = cost(r.need());
            if (c > 0 && lowerOne(r.result(), c / r.amount(), n)) work.add(r.result());
        }
        relax(work, null, n);
    }

    /**
     * {@code id} can now be got for {@code unit} coins. If that is cheaper than known, lower it and everything made
     * from it. Returns the results of every recipe that consumes a lowered item (their caps may have dropped);
     * empty when nothing got cheaper.
     */
    Set<String> lower(String id, double unit) {
        Map<String, Integer> n = new HashMap<>();
        if (!lowerOne(id, unit, n)) return Set.of();
        Set<String> touched = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>();
        work.add(id);
        relax(work, touched, n);
        return touched;
    }

    private void relax(Deque<String> work, Set<String> touched, Map<String, Integer> n) {
        while (!work.isEmpty()) {
            String x = work.poll();
            for (Recipe r : byInput.getOrDefault(x, List.of())) {
                if (touched != null) touched.add(r.result());
                double c = cost(r.need());
                if (c > 0 && lowerOne(r.result(), c / r.amount(), n)) work.add(r.result());
            }
        }
    }

    private boolean lowerOne(String id, double unit, Map<String, Integer> n) {
        if (id == null || !(unit > 0) || Double.isInfinite(unit)) return false;
        Double cur = acq.get(id);
        if (cur != null && cur <= unit * (1 + 1e-9)) return false;
        if (cur != null && n.merge(id, 1, Integer::sum) > MAX_LOWERINGS) return false;
        acq.put(id, unit);
        return true;
    }

    /** Cheapest known cost (coins) of ONE unit; -1 = no known way. */
    double acquire(String id) {
        Double c = acq.get(id);
        return c == null ? -1 : c;
    }

    /** Total cost (coins) of a set of ingredients bought the cheapest known way; -1 if any has no known source. */
    double cost(Map<String, Long> need) {
        double cost = 0;
        for (Map.Entry<String, Long> e : need.entrySet()) {
            long q = e.getValue() == null ? 0 : e.getValue();
            if (isCoins(e.getKey())) { cost += q; continue; }
            double unit = acquire(e.getKey());
            if (unit <= 0) return -1;
            cost += unit * q;
        }
        return cost;
    }

    /** Cheapest way to MAKE one (its own ask does not count: bid &lt; ask already); -1 = cannot be made. */
    double makeCost(String id) {
        double best = Double.MAX_VALUE;
        for (Recipe r : byResult.getOrDefault(id, List.of())) {
            double c = cost(r.need());
            if (c > 0) best = Math.min(best, c / r.amount());
        }
        return best < Double.MAX_VALUE ? best : -1;
    }

    /** The highest bid the bots may post (coins), or 0 = no recipe cap. */
    double cap(String id) {
        double m = makeCost(id);
        return m > 0 ? m * CAP_FRACTION : 0;
    }

    /** Can this item be made at all (has a recipe or ladder step)? */
    boolean craftable(String id) { return byResult.containsKey(id); }
}
