package com.skyclone.mod.crafting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid matching and inventory planning. PURE: no Bukkit, so the whole engine is unit tested.
 *
 * <ul>
 *   <li><b>Shaped</b>: the grid's occupied bounding box must equal the recipe's, at any offset
 *       (vanilla behaviour), optionally mirrored left-right.</li>
 *   <li><b>Shapeless</b>: every occupied slot is assigned to a distinct ingredient (augmenting-path
 *       matching, so overlapping tag sets never produce a false "no match").</li>
 *   <li><b>Amounts</b>: a slot must hold at least the cell's amount; one craft consumes exactly that
 *       amount from that slot, the rest stays in the grid.</li>
 * </ul>
 */
public final class Matcher {

    private Matcher() {}

    /** A grid match: how much to take from each of the 9 slots per craft, and how many crafts fit. */
    public record Match(Pattern pattern, int[] consume, int maxTimes) {}

    public static Match match(Pattern p, String[] ids, int[] amts, boolean allowMirror) {
        if (p == null || ids == null || ids.length != 9) return null;
        if (p.cells.length == 0) return null;
        return p.shapeless ? shapeless(p, ids, amts) : shaped(p, ids, amts, allowMirror);
    }

    private static Match shaped(Pattern p, String[] ids, int[] amts, boolean allowMirror) {
        int minR = 3, maxR = -1, minC = 3, maxC = -1;
        for (int i = 0; i < 9; i++) {
            if (ids[i] == null) continue;
            int r = i / 3, c = i % 3;
            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
            minC = Math.min(minC, c); maxC = Math.max(maxC, c);
        }
        if (maxR < 0) return null;
        if (maxC - minC + 1 != p.w || maxR - minR + 1 != p.h) return null;
        for (int pass = 0; pass < (allowMirror ? 2 : 1); pass++) {
            boolean mirror = pass == 1;
            int[] consume = new int[9];
            int times = Integer.MAX_VALUE;
            boolean ok = true;
            for (int r = 0; r < p.h && ok; r++) {
                for (int c = 0; c < p.w; c++) {
                    Pattern.Ing ing = p.cells[r * p.w + (mirror ? p.w - 1 - c : c)];
                    int slot = (minR + r) * 3 + minC + c;
                    String have = ids[slot];
                    if (ing == null) {
                        if (have != null) { ok = false; break; }
                        continue;
                    }
                    if (have == null || !ing.accepts(have) || amts[slot] < ing.amount()) { ok = false; break; }
                    consume[slot] = ing.amount();
                    times = Math.min(times, amts[slot] / ing.amount());
                }
            }
            if (ok && times > 0 && times != Integer.MAX_VALUE) return new Match(p, consume, times);
        }
        return null;
    }

    private static Match shapeless(Pattern p, String[] ids, int[] amts) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (ids[i] != null) slots.add(i);
        int n = p.cells.length;
        if (slots.size() != n) return null;
        int[] ingOfSlot = new int[n];       // slot index (in `slots`) -> ingredient index
        int[] slotOfIng = new int[n];
        Arrays.fill(ingOfSlot, -1);
        Arrays.fill(slotOfIng, -1);
        for (int s = 0; s < n; s++) {
            if (!augment(s, slots, ids, amts, p.cells, ingOfSlot, slotOfIng, new boolean[n])) return null;
        }
        int[] consume = new int[9];
        int times = Integer.MAX_VALUE;
        for (int s = 0; s < n; s++) {
            Pattern.Ing ing = p.cells[ingOfSlot[s]];
            int slot = slots.get(s);
            consume[slot] = ing.amount();
            times = Math.min(times, amts[slot] / ing.amount());
        }
        return times > 0 ? new Match(p, consume, times) : null;
    }

    private static boolean augment(int s, List<Integer> slots, String[] ids, int[] amts, Pattern.Ing[] ings,
                                   int[] ingOfSlot, int[] slotOfIng, boolean[] seen) {
        int slot = slots.get(s);
        for (int i = 0; i < ings.length; i++) {
            if (seen[i]) continue;
            Pattern.Ing ing = ings[i];
            if (!ing.accepts(ids[slot]) || amts[slot] < ing.amount()) continue;
            seen[i] = true;
            if (slotOfIng[i] < 0 || augment(slotOfIng[i], slots, ids, amts, ings, ingOfSlot, slotOfIng, seen)) {
                slotOfIng[i] = s;
                ingOfSlot[s] = i;
                return true;
            }
        }
        return false;
    }

    // ---- planning crafts straight from a stock (inventory + sacks) -------------------------------

    /**
     * How to take {@code times} crafts' worth of ingredients from {@code stock} (id -> available).
     * Returns id -> amount to take, or null if the stock is short. Ingredients with the narrowest id
     * set allocate first, and inside a set the id with the most stock is drained first, so tag
     * ingredients ("any planks") never starve a specific one.
     */
    public static Map<String, Integer> plan(Pattern p, Map<String, Integer> stock, int times) {
        if (times <= 0) return null;
        Map<Pattern.Ing, Integer> totals = p.totals();
        List<Map.Entry<Pattern.Ing, Integer>> order = new ArrayList<>(totals.entrySet());
        order.sort(Comparator.comparingInt(e -> e.getKey().ids().size()));
        Map<String, Integer> left = new HashMap<>(stock);
        Map<String, Integer> take = new HashMap<>();
        for (Map.Entry<Pattern.Ing, Integer> e : order) {
            long need = (long) e.getValue() * times;
            if (need > Integer.MAX_VALUE) return null;
            List<String> ids = new ArrayList<>(e.getKey().ids());
            ids.sort(Comparator.comparingInt((String id) -> left.getOrDefault(id, 0)).reversed());
            for (String id : ids) {
                if (need <= 0) break;
                int have = left.getOrDefault(id, 0);
                if (have <= 0) continue;
                int t = (int) Math.min(have, need);
                left.put(id, have - t);
                take.merge(id, t, Integer::sum);
                need -= t;
            }
            if (need > 0) return null;
        }
        return take;
    }

    /** The largest number of crafts the stock covers (0 if none), capped at {@code cap}. */
    public static int maxTimes(Pattern p, Map<String, Integer> stock, int cap) {
        if (cap <= 0) return 0;
        long upper = cap;
        for (Map.Entry<Pattern.Ing, Integer> e : p.totals().entrySet()) {
            long avail = 0;
            for (String id : e.getKey().ids()) avail += stock.getOrDefault(id, 0);
            upper = Math.min(upper, avail / e.getValue());
        }
        if (upper <= 0) return 0;
        if (plan(p, stock, (int) upper) != null) return (int) upper;
        int lo = 0, hi = (int) upper;          // overlapping tag sets: binary search the greedy plan
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (plan(p, stock, mid) != null) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    /** Every id a pattern could consume (for deciding which stock counts to look up). */
    public static Set<String> allIds(Pattern p) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
        for (Pattern.Ing i : p.cells) if (i != null) s.addAll(i.ids());
        return s;
    }
}
