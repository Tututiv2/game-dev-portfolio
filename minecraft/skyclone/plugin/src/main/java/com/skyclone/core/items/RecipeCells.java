package com.skyclone.core.items;

import com.skyclone.api.item.Recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Parses recipe cells ("ENCHANTED_DIAMOND:32", "INK_SACK:3:16", "COINS:1000"). Pure Java.
 *
 * <p>Ids may themselves contain one ':' (the reference game's "INK_SACK:3" is Cocoa Beans), so:
 * <ol>
 *   <li>the whole cell is a known id -> that id x1;</li>
 *   <li>otherwise split at the LAST ':' -> id x amount when the suffix is a positive integer;</li>
 *   <li>otherwise the whole cell is the id x1 (unknown — the self-test will report it).</li>
 * </ol>
 * So an id containing ':' must always be written WITH its amount when the amount is not 1
 * ("INK_SACK:3:16"), and "INK_SACK:3" alone means one Cocoa Beans when INK_SACK:3 is registered.
 */
public final class RecipeCells {

    private RecipeCells() {}

    public record Cell(String id, int amount) {}

    public static Cell parse(String cell, Predicate<String> known) {
        if (cell == null) return null;
        String s = cell.trim();
        if (s.isEmpty()) return null;
        if (known != null && known.test(s)) return new Cell(s, 1);
        int c = s.lastIndexOf(':');
        if (c > 0 && c < s.length() - 1) {
            String suffix = s.substring(c + 1);
            if (suffix.chars().allMatch(Character::isDigit) && suffix.length() <= 10) {
                try {
                    long n = Long.parseLong(suffix);
                    if (n > 0 && n <= Integer.MAX_VALUE) return new Cell(s.substring(0, c), (int) n);
                } catch (NumberFormatException ignored) {}
            }
        }
        return new Cell(s, 1);
    }

    /** Every ingredient of a recipe, summed per id (grid + inputs), insertion-ordered. */
    public static Map<String, Integer> ingredients(Recipe r, Predicate<String> known) {
        Map<String, Integer> m = new LinkedHashMap<>();
        if (r.grid != null) for (String g : r.grid) {
            Cell c = parse(g, known);
            if (c != null) m.merge(c.id(), c.amount(), Integer::sum);
        }
        if (r.inputs != null) for (String g : r.inputs) {
            Cell c = parse(g, known);
            if (c != null) m.merge(c.id(), c.amount(), Integer::sum);
        }
        return m;
    }

    public static List<String> ingredientIds(Recipe r, Predicate<String> known) {
        return new ArrayList<>(ingredients(r, known).keySet());
    }
}
