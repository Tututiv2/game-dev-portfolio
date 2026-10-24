package com.skyclone.mod.crafting;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Small PURE formulas: cell parsing, Carpentry XP, the raw-material tree. Unit tested. */
public final class CraftMath {

    private CraftMath() {}

    public record Cell(String id, int amount) {}

    /** "garden:analyzer:5" -&gt; "&amp;aCrop Analyzer Milestone V"; null for any other key (wave 5, Mutations sacks). */
    public static String analyzerText(String key) {
        if (key == null || !key.startsWith("garden:analyzer:")) return null;
        try {
            int n = Integer.parseInt(key.substring("garden:analyzer:".length()).trim());
            return n <= 0 ? null : "&aCrop Analyzer Milestone " + roman(n);
        } catch (NumberFormatException e) { return null; }
    }

    static String roman(int n) {
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { n -= v[i]; b.append(s[i]); }
        return b.toString();
    }

    /**
     * "ENCHANTED_DIAMOND:32" -> (ENCHANTED_DIAMOND, 32); "INK_SACK:3:16" -> (INK_SACK:3, 16) when INK_SACK:3 is
     * known; a cell that IS a known id is x1. Same rules as core's recipe parser, so both read a recipe the same way.
     */
    public static Cell parse(String cell, Predicate<String> known) {
        if (cell == null) return null;
        String s = cell.trim();
        if (s.isEmpty()) return null;
        if (known != null && known.test(s)) return new Cell(s, 1);
        int c = s.lastIndexOf(':');
        if (c > 0 && c < s.length() - 1) {
            String suffix = s.substring(c + 1);
            if (suffix.length() <= 9 && suffix.chars().allMatch(Character::isDigit)) {
                int n = Integer.parseInt(suffix);
                if (n > 0) return new Cell(s.substring(0, c), n);
            }
        }
        return new Cell(s, 1);
    }

    /**
     * Carpentry XP for ONE craft: {@code rate} (research: 3%) of the summed NPC sell value of the
     * ingredients. research/02 §2.4.8: "XP per craft = 3% of the combined NPC sell price of the ingredients".
     */
    public static double carpentryXp(Map<String, Integer> ingredients, ToDoubleFunction<String> npcSell, double rate) {
        double v = 0;
        for (Map.Entry<String, Integer> e : ingredients.entrySet()) v += Math.max(0, npcSell.applyAsDouble(e.getKey())) * e.getValue();
        return v * rate;
    }

    /**
     * How much has been put into one unique item (enchants, reforge, books, stars, gems, module data). Supercraft
     * uses it to pick WHICH copy of a unique ingredient to take: the most upgraded one when the craft carries the
     * upgrades over, the plainest one otherwise, so a player's best gear is never eaten by accident.
     */
    public static int upgradeWorth(com.skyclone.api.item.ItemData d) {
        if (d == null) return 0;
        int w = 0;
        for (int lvl : d.enchants.values()) w += Math.max(1, lvl);
        if (d.reforge != null) w += 3;
        w += d.potatoBooks + d.stars * 2 + d.gems.size() * 2 + d.unlockedGemSlots.size();
        if (d.recombobulated) w += 5;
        if (d.extra != null) w += d.extra.size();
        return w;
    }

    /** Items per trade per day: the dial, times the Shopping Spree multiplier while that mayor perk is on. */
    public static int tradeLimit(int base, boolean spree, double multiplier) {
        if (base <= 0) return 0;
        if (!spree || multiplier <= 1) return base;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(base * multiplier));
    }

    /**
     * Recipe ids that can be undone by another recipe (A -&gt; B and B -&gt; A, e.g. 9 Diamonds -&gt; Diamond Block
     * -&gt; 9 Diamonds), or that consume their own result (re-dyeing). Such recipes grant NO Carpentry XP:
     * otherwise crafting back and forth is an endless source of skill XP and of the skill's coin rewards.
     * The reference game avoids this because those pairs are vanilla recipes (which pay nothing); here
     * modules register some of them as their own recipes, so the loop has to be cut by structure.
     */
    public static Set<String> reversible(Iterable<Pattern> patterns) {
        // Wave 3: any CYCLE, not only direct pairs. Items are nodes, every recipe is an edge from each ingredient
        // to its result; a recipe whose result shares a strongly connected component with one of its ingredients
        // can be walked round back to where it started (A -> B -> C -> A), which prints XP exactly like a pair.
        Map<String, Set<String>> adj = new java.util.HashMap<>();
        Map<String, Set<String>> ingIds = new java.util.HashMap<>();
        java.util.List<Pattern> all = new java.util.ArrayList<>();
        for (Pattern p : patterns) {
            all.add(p);
            Set<String> s = new HashSet<>();
            for (Pattern.Ing i : p.cells) if (i != null) s.addAll(i.ids());
            ingIds.put(p.recipeId, s);
            for (String i : s) adj.computeIfAbsent(i, k -> new HashSet<>()).add(p.result);
        }
        Map<String, Integer> comp = components(adj);
        Set<String> out = new HashSet<>();
        for (Pattern p : all) {
            Set<String> mine = ingIds.get(p.recipeId);
            if (mine == null) continue;
            Integer rc = comp.get(p.result);
            for (String ing : mine) {
                if (ing.equals(p.result) || (rc != null && rc.equals(comp.get(ing)))) { out.add(p.recipeId); break; }
            }
        }
        return out;
    }

    /** A recipe the grid's items almost make: how many of its ingredient kinds are not in the grid at all. */
    public record NearMiss(Pattern pattern, int missingKinds) {}

    /**
     * Wave 3 "did you mean": the recipes that use EVERY item in the grid (each grid id is accepted by some cell),
     * ranked by how many ingredient kinds are still missing, then by how many cells the recipe has; one per result.
     * missingKinds 0 = the right items in the wrong shape or amounts. Pure, unit tested.
     */
    public static java.util.List<NearMiss> nearMisses(Set<String> gridIds, Iterable<Pattern> candidates, int max) {
        java.util.List<NearMiss> out = new java.util.ArrayList<>();
        if (gridIds == null || gridIds.isEmpty() || max <= 0) return out;
        Map<String, NearMiss> best = new LinkedHashMap<>();
        for (Pattern p : candidates) {
            if (p == null) continue;
            boolean all = true;
            for (String g : gridIds) {
                boolean covered = false;
                for (Pattern.Ing i : p.cells) if (i != null && i.ids().contains(g)) { covered = true; break; }
                if (!covered) { all = false; break; }
            }
            if (!all) continue;
            Set<Set<String>> kinds = new java.util.LinkedHashSet<>();
            for (Pattern.Ing i : p.cells) if (i != null) kinds.add(i.ids());
            int missing = 0;
            for (Set<String> k : kinds) {
                boolean have = false;
                for (String g : gridIds) if (k.contains(g)) { have = true; break; }
                if (!have) missing++;
            }
            NearMiss nm = new NearMiss(p, missing);
            NearMiss prev = best.get(p.result);
            if (prev == null || missing < prev.missingKinds()) best.put(p.result, nm);
        }
        out.addAll(best.values());
        out.sort(java.util.Comparator.comparingInt(NearMiss::missingKinds)
                .thenComparingInt(n -> n.pattern().cellCount())
                .thenComparing(n -> n.pattern().result));
        return out.size() > max ? new java.util.ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * What one craft of a pattern is worth for ranking: the better of its result's value (x amount) and what its
     * ingredients are worth (each cell at its cheapest PRICED id, as {@link SellCap} reads them). A result that
     * cannot be sold (a minion, a fresh tool: npcSell 0, not on the bazaar) is ranked by what goes into it instead
     * of sinking to 0.
     */
    public static double craftWorth(Pattern p, ToDoubleFunction<String> value) {
        if (p == null) return 0;
        double out = Math.max(0, value.applyAsDouble(p.result)) * Math.max(1, p.amount);
        double in = 0;
        for (Map.Entry<Pattern.Ing, Integer> e : p.totals().entrySet()) {
            double cheapest = 0;
            for (String id : e.getKey().ids()) {
                double v = value.applyAsDouble(id);
                if (v > 0 && (cheapest == 0 || v < cheapest)) cheapest = v;
            }
            in += cheapest * e.getValue();
        }
        return Math.max(out, in);
    }

    /**
     * Craftable Now order (wave-3 playtest: with 88 Oak Logs and a Wooden Axe the Oak Minion I sat on the LAST page,
     * behind every plank, slab, stair and fence the same logs make, because it has no sell price and the list was
     * sorted by result value alone). SkyBlock recipes first, vanilla last; within each, by {@link #craftWorth}
     * (descending), then result id so the order is stable. Pure, unit tested.
     */
    public static <T> void sortCraftable(java.util.List<T> list, Function<T, Pattern> pattern, ToDoubleFunction<String> value) {
        Map<T, Double> worth = new java.util.IdentityHashMap<>();
        for (T t : list) worth.put(t, craftWorth(pattern.apply(t), value));
        list.sort(java.util.Comparator.comparing((T t) -> pattern.apply(t).vanilla)
                .thenComparing((T t) -> -worth.getOrDefault(t, 0.0))
                .thenComparing((T t) -> pattern.apply(t).result));
    }

    /**
     * Strongly connected components of a directed graph (iterative Tarjan, so a deep recipe chain cannot overflow
     * the stack). Returns node -&gt; component number; only nodes that appear as a key or a target are numbered.
     */
    public static Map<String, Integer> components(Map<String, Set<String>> adj) {
        Map<String, Integer> index = new java.util.HashMap<>(), low = new java.util.HashMap<>(), comp = new java.util.HashMap<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        Set<String> nodes = new java.util.LinkedHashSet<>(adj.keySet());
        for (Set<String> t : adj.values()) nodes.addAll(t);
        int[] counter = {0, 0};
        for (String start : nodes) {
            if (index.containsKey(start)) continue;
            java.util.ArrayDeque<Object[]> work = new java.util.ArrayDeque<>();   // {node, iterator}
            index.put(start, counter[0]); low.put(start, counter[0]); counter[0]++;
            stack.push(start); onStack.add(start);
            work.push(new Object[]{start, adj.getOrDefault(start, Set.of()).iterator()});
            while (!work.isEmpty()) {
                Object[] top = work.peek();
                String v = (String) top[0];
                @SuppressWarnings("unchecked") java.util.Iterator<String> it = (java.util.Iterator<String>) top[1];
                if (it.hasNext()) {
                    String w = it.next();
                    if (!index.containsKey(w)) {
                        index.put(w, counter[0]); low.put(w, counter[0]); counter[0]++;
                        stack.push(w); onStack.add(w);
                        work.push(new Object[]{w, adj.getOrDefault(w, Set.of()).iterator()});
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                    continue;
                }
                work.pop();
                if (!work.isEmpty()) {
                    String parent = (String) work.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(v)));
                }
                if (low.get(v).equals(index.get(v))) {
                    String w;
                    do { w = stack.pop(); onStack.remove(w); comp.put(w, counter[1]); } while (!w.equals(v));
                    counter[1]++;
                }
            }
        }
        return comp;
    }

    /**
     * Wave 5: WHICH recipe the raw-material tree follows when an item has several. It used to take the first one,
     * so the answer depended on registration order. Now: vanilla recipes are never followed (a Diamond is not
     * "made of" a Diamond Block); a recipe on a crafting cycle that turns FEWER items into MORE (an unpacking:
     * 1 block -&gt; 9 ingots) is never followed either, while the packing direction is; among what is left, the one
     * whose inputs are worth least per item made wins ({@code unitCost} &lt; 0 = cannot be priced: those come after
     * every priced one), ties keep the original order. Null = the item is raw.
     */
    public static Pattern treePick(java.util.List<Pattern> candidates, Predicate<String> onCycle, ToDoubleFunction<Pattern> unitCost) {
        Pattern best = null;
        double bestCost = 0;
        for (Pattern p : candidates) {
            if (p == null || p.vanilla) continue;
            if (onCycle != null && onCycle.test(p.recipeId)) {
                long in = 0;
                for (int n : p.totals().values()) in += n;
                if (Math.max(1, p.amount) > in) continue;                 // unpacking: 1 block -> 9 ingots
            }
            double c = unitCost == null ? 0 : unitCost.applyAsDouble(p);
            double key = c < 0 ? Double.MAX_VALUE : c;
            if (best == null || key < bestCost) { best = p; bestCost = key; }
        }
        return best;
    }

    /**
     * Expand an item into the raw materials it is ultimately made of, following {@code recipeFor} (null =
     * the item is raw). Cycles (block <-> ingot) and anything deeper than {@code maxDepth} stop the expansion
     * at that item. Wave 3: leftovers are tracked, so a recipe that makes 4 and is needed once in two branches
     * is crafted ONCE (the second branch takes the spare from the first), the way a player would actually do it.
     */
    public static Map<String, Long> raw(String id, long qty, Function<String, Pattern> recipeFor, int maxDepth) {
        Map<String, Long> out = new LinkedHashMap<>();
        expand(id, qty, recipeFor, maxDepth, new HashSet<>(), out, new java.util.HashMap<>());
        return out;
    }

    /** {@link #raw} for several items at once, sharing leftovers between them (one recipe's ingredient list). */
    public static Map<String, Long> rawAll(Map<String, Long> wants, Function<String, Pattern> recipeFor, int maxDepth) {
        Map<String, Long> out = new LinkedHashMap<>();
        Map<String, Long> spare = new java.util.HashMap<>();
        for (Map.Entry<String, Long> w : wants.entrySet()) expand(w.getKey(), w.getValue(), recipeFor, maxDepth, new HashSet<>(), out, spare);
        return out;
    }

    private static void expand(String id, long qty, Function<String, Pattern> recipeFor, int depth, Set<String> path,
                               Map<String, Long> out, Map<String, Long> spare) {
        if (qty <= 0) return;
        Pattern p = (depth <= 0 || path.contains(id)) ? null : recipeFor.apply(id);
        if (p == null) {          // raw (or the top item itself when it has no recipe)
            out.merge(id, qty, Long::sum);
            return;
        }
        long have = spare.getOrDefault(id, 0L);
        long use = Math.min(have, qty);
        if (use > 0) spare.put(id, have - use);
        long need = qty - use;
        if (need <= 0) return;
        int per = Math.max(1, p.amount);
        long crafts = (need + per - 1) / per;
        long extra = crafts * per - need;
        if (extra > 0) spare.merge(id, extra, Long::sum);
        path.add(id);
        for (Map.Entry<Pattern.Ing, Integer> e : p.totals().entrySet()) {
            expand(e.getKey().display(), crafts * e.getValue(), recipeFor, depth - 1, path, out, spare);
        }
        path.remove(id);
    }
}
