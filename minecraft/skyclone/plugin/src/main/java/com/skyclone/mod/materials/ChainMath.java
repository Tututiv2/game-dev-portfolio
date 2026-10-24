package com.skyclone.mod.materials;

import com.skyclone.api.item.Recipe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pure arithmetic of compression chains: recipe cells, ingredient sums, the raw cost of a
 * multi-step item ("Enchanted Diamond Block = 25,600 Diamond"), and NPC value in vs out.
 * No Bukkit, no Sky: everything it needs is passed in, so unit tests run it on the shipped JSON.
 */
public final class ChainMath {

    private ChainMath() {}

    /** One parsed cell. */
    public record Cell(String id, int amount) {}

    /**
     * Same rule as core's recipe parser: the whole cell is a known id -> x1; otherwise split at the
     * LAST ':' when the suffix is a positive integer; otherwise the whole cell x1.
     */
    public static Cell cell(String raw, Predicate<String> known) {
        if (raw == null) return null;
        String s = raw.trim();
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

    /** Ingredient id -> total amount (grid + inputs), insertion ordered. Coins are skipped. */
    public static Map<String, Integer> ingredients(Recipe r, Predicate<String> known) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (r == null) return m;
        if (r.grid != null) for (String g : r.grid) add(m, cell(g, known));
        if (r.inputs != null) for (String g : r.inputs) add(m, cell(g, known));
        return m;
    }

    private static void add(Map<String, Integer> m, Cell c) {
        if (c == null || "COINS".equals(c.id()) || "SKYBLOCK_COIN".equals(c.id())) return;
        m.merge(c.id(), c.amount(), Integer::sum);
    }

    /** Total items consumed by one craft. */
    public static int totalInputs(Recipe r, Predicate<String> known) {
        int t = 0;
        for (int n : ingredients(r, known).values()) t += n;
        return t;
    }

    /** The ingredient with the largest count (the "base" of a compression). */
    public static String mainInput(Recipe r, Predicate<String> known) {
        String best = null;
        int bn = -1;
        for (Map.Entry<String, Integer> e : ingredients(r, known).entrySet()) {
            if (e.getValue() > bn) { best = e.getKey(); bn = e.getValue(); }
        }
        return best;
    }

    /**
     * Raw cost of ONE {@code itemId}, expanded through {@code recipeOf} until items with no recipe
     * (or ids in {@code stopAt}) are reached. Fractions are kept (a recipe that makes 9 costs 1/9 each).
     * Cycles are cut: an id already on the current path is treated as raw.
     */
    public static Map<String, Double> rawCost(String itemId, Function<String, Recipe> recipeOf,
                                              Predicate<String> known, Set<String> stopAt) {
        Map<String, Double> out = new LinkedHashMap<>();
        expand(itemId, 1.0, recipeOf, known, stopAt == null ? Set.of() : stopAt, new HashSet<>(), out, 0);
        return out;
    }

    private static void expand(String id, double qty, Function<String, Recipe> recipeOf, Predicate<String> known,
                               Set<String> stopAt, Set<String> path, Map<String, Double> out, int depth) {
        Recipe r = (depth == 0 || !stopAt.contains(id)) && !path.contains(id) && depth < 12 ? recipeOf.apply(id) : null;
        if (r == null || r.amount <= 0) {
            out.merge(id, qty, Double::sum);
            return;
        }
        path.add(id);
        double per = qty / r.amount;
        for (Map.Entry<String, Integer> e : ingredients(r, known).entrySet()) {
            expand(e.getKey(), per * e.getValue(), recipeOf, known, stopAt, path, out, depth + 1);
        }
        path.remove(id);
    }

    /** Sum of NPC value of the inputs of one craft. */
    public static double valueIn(Recipe r, Predicate<String> known, Function<String, Double> price) {
        double v = 0;
        for (Map.Entry<String, Integer> e : ingredients(r, known).entrySet()) {
            Double p = price.apply(e.getKey());
            v += (p == null ? 0 : p) * e.getValue();
        }
        return v;
    }

    /** NPC value of what one craft produces. */
    public static double valueOut(Recipe r, Function<String, Double> price) {
        Double p = price.apply(r.result);
        return (p == null ? 0 : p) * Math.max(1, r.amount);
    }

    /**
     * True when crafting turns coins into MORE coins at an NPC: the output sells for more than the
     * inputs do (only meaningful when every input has a price).
     */
    public static boolean isCoinPrinter(Recipe r, Predicate<String> known, Function<String, Double> price) {
        double in = 0;
        for (Map.Entry<String, Integer> e : ingredients(r, known).entrySet()) {
            Double p = price.apply(e.getKey());
            if (p == null || p <= 0) return false;
            in += p * e.getValue();
        }
        return valueOut(r, price) > in + 1e-6;
    }

    /**
     * The per-item NPC price the result of {@code r} may have so selling it never beats selling its inputs, or
     * {@code -1} when no cap is needed. Every input priced: the inputs' value per result item, rounded DOWN to a
     * tenth (the economy's unit). No input priced at all: 0 (a compression of unsellable things is unsellable).
     * Some priced, some not (a Dispenser's bow): no opinion, -1. Wave 5: the live sell cap.
     */
    public static double cappedPrice(Recipe r, Predicate<String> known, Function<String, Double> price) {
        Map<String, Integer> ing = ingredients(r, known);
        if (ing.isEmpty()) return -1;
        double in = 0;
        int priced = 0;
        for (Map.Entry<String, Integer> e : ing.entrySet()) {
            Double p = price.apply(e.getKey());
            if (p != null && p > 0) { priced++; in += p * e.getValue(); }
        }
        Double out = price.apply(r.result);
        if (out == null || out <= 0) return -1;
        int amount = Math.max(1, r.amount);
        if (priced == 0) return 0;
        if (priced < ing.size()) return -1;
        if (out * amount <= in + 1e-6) return -1;
        return Math.floor(in / amount * 10 + 1e-9) / 10.0;
    }

    /**
     * How many times a recipe can be crafted from what is available, capped at {@code max}.
     * {@code have} returns the available amount of an id.
     */
    public static int timesCraftable(Recipe r, Predicate<String> known, Function<String, Integer> have, int max) {
        Map<String, Integer> ing = ingredients(r, known);
        if (ing.isEmpty()) return 0;
        long times = Long.MAX_VALUE;
        for (Map.Entry<String, Integer> e : ing.entrySet()) {
            Integer h = have.apply(e.getKey());
            long t = (h == null ? 0 : h) / Math.max(1, e.getValue());
            times = Math.min(times, t);
        }
        return (int) Math.max(0, Math.min(times, Math.max(0, max)));
    }

    /** Compression chain upward from {@code base}: base, next, next-next... following {@code next}. */
    public static List<String> chainUp(String base, Function<String, List<String>> next, int maxLen) {
        List<String> out = new ArrayList<>();
        String cur = base;
        Set<String> seen = new HashSet<>();
        while (cur != null && seen.add(cur) && out.size() < maxLen) {
            out.add(cur);
            List<String> n = next.apply(cur);
            cur = n == null || n.isEmpty() ? null : n.get(0);
        }
        return out;
    }

    /** "IV" -> 4; garbage -> 0. */
    public static int roman(String s) {
        if (s == null || s.isEmpty()) return 0;
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1; case 'V' -> 5; case 'X' -> 10; case 'L' -> 50; case 'C' -> 100;
                default -> -1;
            };
            if (v < 0) return 0;
            if (v < prev) total -= v; else { total += v; prev = v; }
        }
        return total;
    }

    /**
     * Splits a requirement key "collection:INK_SACK:3:6" into {prefix, id, tier}; the id may contain
     * ':' itself, so the tier is the LAST part. Null for a malformed key.
     */
    public static String[] splitUnlock(String key) {
        if (key == null) return null;
        String[] parts = key.split(":");
        if (parts.length < 2) return null;
        if (parts.length == 2) return new String[]{parts[0], parts[1], ""};
        String id = String.join(":", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
        return new String[]{parts[0], id, parts[parts.length - 1]};
    }
}
