package com.skyclone.mod.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A recipe compiled for matching. PURE (no Bukkit): ids are already canonical sky ids, so the
 * matcher only ever compares strings.
 *
 * <p>Shaped recipes keep their bounding box ({@code w} x {@code h}, cells row-major, null = empty);
 * shapeless ones keep a flat list of ingredients. Each cell accepts a SET of ids (vanilla item
 * tags like "any planks") and needs an AMOUNT ("32 Diamonds in this slot").
 */
public final class Pattern {

    /** One grid cell's requirement. {@code ids} is never empty; {@code display} is the id shown in previews. */
    public record Ing(Set<String> ids, int amount, String display) {
        public Ing {
            ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
            if (amount <= 0) amount = 1;
        }
        public static Ing of(String id, int amount) { return new Ing(Set.of(id), amount, id); }
        public boolean accepts(String id) { return id != null && ids.contains(id); }
    }

    public final String recipeId;
    public final String result;
    public final int amount;
    public final boolean shapeless;
    public final int w, h;
    /** Shaped: w*h cells (null = must be empty). Shapeless: every ingredient, no nulls. */
    public final Ing[] cells;
    /** "COINS:x" cells are not placed in a grid; they are charged on top of the items. */
    public final double coins;
    public final String unlock;
    public final boolean vanilla;

    public Pattern(String recipeId, String result, int amount, boolean shapeless, int w, int h, Ing[] cells,
                   double coins, String unlock, boolean vanilla) {
        this.recipeId = recipeId;
        this.result = result;
        this.amount = Math.max(1, amount);
        this.shapeless = shapeless;
        this.w = w;
        this.h = h;
        this.cells = cells;
        this.coins = coins;
        this.unlock = unlock;
        this.vanilla = vanilla;
    }

    /**
     * Build from a 3x3 grid (index 0..8, null = empty). Shaped recipes are trimmed to their
     * bounding box so they match anywhere in the grid, like vanilla.
     */
    public static Pattern fromGrid(String recipeId, String result, int amount, boolean shapeless, Ing[] grid9,
                                   double coins, String unlock, boolean vanilla) {
        if (shapeless) {
            List<Ing> l = new ArrayList<>();
            for (Ing i : grid9) if (i != null) l.add(i);
            return new Pattern(recipeId, result, amount, true, 0, 0, l.toArray(new Ing[0]), coins, unlock, vanilla);
        }
        int minR = 3, maxR = -1, minC = 3, maxC = -1;
        for (int i = 0; i < 9; i++) {
            if (grid9[i] == null) continue;
            int r = i / 3, c = i % 3;
            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
            minC = Math.min(minC, c); maxC = Math.max(maxC, c);
        }
        if (maxR < 0) return new Pattern(recipeId, result, amount, false, 0, 0, new Ing[0], coins, unlock, vanilla);
        int w = maxC - minC + 1, h = maxR - minR + 1;
        Ing[] cells = new Ing[w * h];
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) cells[r * w + c] = grid9[(minR + r) * 3 + minC + c];
        return new Pattern(recipeId, result, amount, false, w, h, cells, coins, unlock, vanilla);
    }

    /** The recipe as a 3x3 grid again (top-left aligned), for previews. */
    public Ing[] grid9() {
        Ing[] g = new Ing[9];
        if (shapeless) {
            for (int i = 0; i < cells.length && i < 9; i++) g[i] = cells[i];
            return g;
        }
        for (int r = 0; r < h; r++) for (int c = 0; c < w; c++) g[r * 3 + c] = cells[r * w + c];
        return g;
    }

    /** Ingredients merged by identical id-set: what one craft consumes in total. */
    public Map<Ing, Integer> totals() {
        Map<Set<String>, Integer> byIds = new LinkedHashMap<>();
        Map<Set<String>, String> disp = new LinkedHashMap<>();
        for (Ing i : cells) {
            if (i == null) continue;
            byIds.merge(i.ids(), i.amount(), Integer::sum);
            disp.putIfAbsent(i.ids(), i.display());
        }
        Map<Ing, Integer> out = new LinkedHashMap<>();
        byIds.forEach((ids, n) -> out.put(new Ing(ids, 1, disp.get(ids)), n));
        return out;
    }

    public int cellCount() {
        int n = 0;
        for (Ing i : cells) if (i != null) n++;
        return n;
    }

    @Override public String toString() { return recipeId + " -> " + result + " x" + amount; }
}
