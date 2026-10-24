package com.skyclone.mod.shops;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The rules of the Island Decorator's minigames, with no Bukkit in them so the unit tests run the real code. */
public final class Games {

    private Games() {}

    /** A w x h board where {@code need} in a line (row, column or diagonal) wins. Cells: 0 empty, 1/2 a player. */
    public static final class Grid {
        public final int w, h, need;
        private final int[] cells;

        public Grid(int w, int h, int need) {
            this.w = w;
            this.h = h;
            this.need = need;
            this.cells = new int[w * h];
        }

        public int at(int x, int y) { return x < 0 || y < 0 || x >= w || y >= h ? -1 : cells[y * w + x]; }

        public void set(int x, int y, int v) { if (at(x, y) >= 0) cells[y * w + x] = v; }

        /** Four in a Row: the row a disc dropped into column {@code x} lands on (row 0 is the top), or -1 if full. */
        public int dropRow(int x) {
            if (x < 0 || x >= w) return -1;
            for (int y = h - 1; y >= 0; y--) if (at(x, y) == 0) return y;
            return -1;
        }

        public boolean full() {
            for (int c : cells) if (c == 0) return false;
            return true;
        }

        /** 1 or 2 when that player has {@code need} in a line, else 0. */
        public int winner() {
            int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int v = at(x, y);
                if (v <= 0) continue;
                for (int[] d : dirs) {
                    int n = 1;
                    while (n < need && at(x + d[0] * n, y + d[1] * n) == v) n++;
                    if (n >= need) return v;
                }
            }
            return 0;
        }
    }

    public enum Sign {
        ROCK("Rock"), PAPER("Paper"), SHEARS("Shears");
        private final String label;
        Sign(String label) { this.label = label; }
        public String label() { return label; }

        /** Classic rules: rock blunts shears, shears cut paper, paper wraps rock. */
        public boolean beats(Sign o) {
            return (this == ROCK && o == SHEARS) || (this == SHEARS && o == PAPER) || (this == PAPER && o == ROCK);
        }
    }

    /**
     * Who goes through one round of Rock Paper Snips.
     * <ul>
     *   <li>A player who did not pick is out (if NOBODY picked, nobody is).</li>
     *   <li>Two players left: classic rules; a tie (or one silent player's opponent alone) decides as you'd expect.</li>
     *   <li>More: the least picked sign(s) among those picked go through; if only one sign was picked, or every
     *       picked sign is tied, nobody goes out this round.</li>
     * </ul>
     * Never returns an empty set while somebody was alive.
     */
    public static <K> Set<K> rpsRound(Collection<K> alive, Map<K, Sign> picks) {
        Set<K> pickers = new HashSet<>();
        for (K k : alive) if (picks.get(k) != null) pickers.add(k);
        if (pickers.isEmpty()) return new HashSet<>(alive);
        if (alive.size() == 2) {
            if (pickers.size() == 1) return pickers;
            java.util.Iterator<K> it = pickers.iterator();
            K a = it.next(), b = it.next();
            Sign sa = picks.get(a), sb = picks.get(b);
            if (sa.beats(sb)) return Set.of(a);
            if (sb.beats(sa)) return Set.of(b);
            return pickers;
        }
        Map<Sign, Integer> count = new EnumMap<>(Sign.class);
        for (K k : pickers) count.merge(picks.get(k), 1, Integer::sum);
        int min = Integer.MAX_VALUE, max = 0;
        for (int c : count.values()) { min = Math.min(min, c); max = Math.max(max, c); }
        if (count.size() == 1 || min == max) return pickers;
        Set<K> out = new HashSet<>();
        for (K k : pickers) if (count.get(picks.get(k)) == min) out.add(k);
        return out;
    }
}
