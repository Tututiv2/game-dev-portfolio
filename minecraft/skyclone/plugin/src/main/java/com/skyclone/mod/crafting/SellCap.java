package com.skyclone.mod.crafting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * The economy law behind {@code crafting.no-coin-loops}, applied instead of only reported: no grid recipe's
 * result sells to an NPC for more than {@code crafting.sell-cap-factor} x what one craft's inputs sell for.
 *
 * <p>Why it lives here: every module prices its own items from the reference game, but a recipe's inputs are
 * usually priced by OTHER modules, so a reference sell price can sit far above what the inputs here are worth
 * (integration round 2: Armor of Yog 4,000 of inputs selling for 38,000; Ember armour from unsellable inputs
 * selling for 1,000; a Jungle Axe; Soulflow). Crafting owns the recipe index, so it is the one place that sees
 * every recipe after every module registered. The accessories module caps its own items the same way earlier;
 * both only ever LOWER a price, so the order they run in does not matter.
 *
 * <p>PURE (no Bukkit): a fixpoint over the recipes. Each pass lowers every result for which ANY recipe breaks
 * the rule, using the prices as lowered so far, until nothing moves; prices only fall, so it ends. An
 * ingredient cell that accepts several ids counts at its cheapest PRICED id. A cell with no priced id at all
 * (an unsellable drop, an unregistered id) makes the recipe unjudgeable and it is skipped: npcSell 0 means
 * "cannot be sold", not "free", and counting it as free zeroed iron ingots (9 nuggets), planks and every
 * armour made of mob drops on the first try. {@link #inputValue} is shared with the self-test, so the
 * self-test passes by construction after this runs. Coins cells are not ingredients and count nothing.
 */
final class SellCap {

    private SellCap() {}

    /** One recipe: what one craft consumes (each cell's accepted ids -> count) and how many it makes. */
    record Way(String result, int amount, List<Map.Entry<Set<String>, Integer>> inputs) {}

    /** Most passes before giving up (a chain of N recipes needs at most N; cycles cannot grow a price). */
    static final int MAX_PASSES = 64;

    /**
     * Returns id -> lowered price for every result that had to drop. {@code price} gives the registered npcSell
     * (0 = unknown or unsellable). {@code factor} &lt;= 0 means off.
     */
    static Map<String, Double> compute(Collection<Way> ways, ToDoubleFunction<String> price, double factor) {
        Map<String, Double> now = new HashMap<>();
        Map<String, Double> lowered = new TreeMap<>();
        if (factor <= 0) return lowered;
        ToDoubleFunction<String> cur = id -> {
            Double v = now.get(id);
            return v != null ? v : Math.max(0, price.applyAsDouble(id));
        };
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean moved = false;
            for (Way w : ways) {
                double out = cur.applyAsDouble(w.result());
                if (out <= 0) continue;
                double in = inputValue(w.inputs(), cur);
                if (in < 0) continue;                                       // an unpriced input: cannot judge
                int amt = Math.max(1, w.amount());
                if (!breaks(out, amt, in, factor)) continue;
                double cap = Math.floor((in * factor + 0.5) / amt * 10.0) / 10.0;   // 0.1-coin ticks, never above the rule
                if (cap < out) {
                    now.put(w.result(), cap);
                    lowered.put(w.result(), cap);
                    moved = true;
                }
            }
            if (!moved) break;
        }
        return lowered;
    }

    /**
     * What one craft's inputs sell for: each cell at its cheapest priced id times its count. Returns -1 when
     * some cell has no priced id (the recipe cannot be judged).
     */
    static double inputValue(List<Map.Entry<Set<String>, Integer>> inputs, ToDoubleFunction<String> price) {
        double in = 0;
        for (Map.Entry<Set<String>, Integer> e : inputs) {
            double best = Double.MAX_VALUE;
            for (String id : e.getKey()) {
                double v = price.applyAsDouble(id);
                if (v > 0) best = Math.min(best, v);
            }
            if (best == Double.MAX_VALUE) return -1;
            in += best * e.getValue();
        }
        return in;
    }

    /** The crafting.no-coin-loops rule: {@code amount} results selling at {@code out} each beat factor x inputs. */
    static boolean breaks(double out, int amount, double in, double factor) {
        return out > 0 && out * Math.max(1, amount) > in * factor + 0.5;
    }

    /** The crafting index as Ways (gated recipes too when {@code gated}: a loop behind an unlock is still a loop). */
    static List<Way> ways(List<RecipeIndex.Entry> entries, boolean gated) {
        List<Way> out = new ArrayList<>();
        for (RecipeIndex.Entry e : entries) {
            if (!gated && e.recipe.unlock != null) continue;
            List<Map.Entry<Set<String>, Integer>> in = new ArrayList<>();
            for (Map.Entry<Pattern.Ing, Integer> t : e.pattern.totals().entrySet()) in.add(Map.entry(t.getKey().ids(), t.getValue()));
            out.add(new Way(e.pattern.result, e.pattern.amount, in));
        }
        return out;
    }
}
