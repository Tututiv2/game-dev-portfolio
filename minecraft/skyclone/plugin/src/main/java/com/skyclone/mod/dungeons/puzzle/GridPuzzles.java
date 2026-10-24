package com.skyclone.mod.dungeons.puzzle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The generators and solvers of the logic puzzles (research 04 sec 3.9), all pure and unit-tested: Water Board,
 * Teleport Maze, Boulder, Ice Path and Ice Fill. Each generator only returns layouts its own solver can solve.
 */
public final class GridPuzzles {

    private GridPuzzles() {}

    // =========================================================================================
    // Water Board: 6 coloured levers, 5 doors. Pulling the water lever sends water down the routes of every lever that is
    // ON; each door whose outlet gets water toggles. Doors start with 3 closed; open all 5 at once. No board is solved by
    // a single lever: with 6 levers that was a 1-in-6 guess, and the endgame playtest's first probe hit one.
    // =========================================================================================

    public record Water(int[] masks, int closed) {
        /** Doors (bit set = open) after releasing water with these levers ON. */
        public int doorsAfter(boolean[] on) {
            int flow = 0;
            for (int i = 0; i < masks.length; i++) if (on[i]) flow ^= masks[i];
            return (~closed & 0x1F) ^ flow;
        }

        /** A lever combination that opens every door (one with the fewest levers), or null. */
        public boolean[] solve() {
            boolean[] best = null;
            int bestN = Integer.MAX_VALUE;
            for (int s = 0; s < (1 << masks.length); s++) {
                boolean[] on = new boolean[masks.length];
                for (int i = 0; i < masks.length; i++) on[i] = (s >> i & 1) == 1;
                if (doorsAfter(on) == 0x1F && Integer.bitCount(s) < bestN) { best = on; bestN = Integer.bitCount(s); }
            }
            return best;
        }

        /** Fewest levers that solve the board (0 = already solved, -1 = unsolvable). */
        public int minLevers() {
            boolean[] on = solve();
            if (on == null) return -1;
            int n = 0;
            for (boolean b : on) if (b) n++;
            return n;
        }
    }

    /** Fewest levers a generated board may need (playtest-endgame: a one-lever board was solved by the first probe). */
    public static final int WATER_MIN_LEVERS = 2;

    public static Water water(long seed) {
        Random r = new Random(seed);
        while (true) {
            int[] masks = new int[6];
            for (int i = 0; i < 6; i++) {
                int m;
                do { m = 1 + r.nextInt(31); } while (Integer.bitCount(m) > 3 || contains(masks, i, m));
                masks[i] = m;
            }
            int subset = 1 + r.nextInt(63);
            int closed = 0;
            for (int i = 0; i < 6; i++) if ((subset >> i & 1) == 1) closed ^= masks[i];
            if (Integer.bitCount(closed) != 3) continue;
            Water w = new Water(masks, closed);
            if (w.minLevers() >= WATER_MIN_LEVERS) return w;
        }
    }

    private static boolean contains(int[] a, int n, int v) {
        for (int i = 0; i < n; i++) if (a[i] == v) return true;
        return false;
    }

    // =========================================================================================
    // Teleport Maze: 3x3 chambers (index = cu + 3*cv), entrance 1, goal 4 (the sealed centre). Every non-goal chamber has
    // 2-3 pads, each teleporting to a chamber; exactly one pad leads to the goal, at most 5 hops from the entrance.
    // =========================================================================================

    public record Teleport(int[][] pads, int[][] dest) {
        /** Fewest pads from the entrance to the goal (-1 if unreachable). */
        public int hops() {
            int[] d = new int[9];
            java.util.Arrays.fill(d, -1);
            d[ENTRANCE] = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>(List.of(ENTRANCE));
            while (!q.isEmpty()) {
                int c = q.poll();
                if (c == GOAL) return d[c];
                for (int t : dest[c]) if (d[t] < 0) { d[t] = d[c] + 1; q.add(t); }
            }
            return -1;
        }
    }

    public static final int ENTRANCE = 1, GOAL = 4;
    /** Pad spots inside an 8x8 chamber (local 0..7). */
    public static final int[][] PAD_SPOTS = {{1, 1}, {6, 1}, {1, 6}, {6, 6}};

    public static Teleport teleport(long seed) {
        Random r = new Random(seed);
        while (true) {
            int[][] pads = new int[9][];
            int[][] dest = new int[9][];
            List<int[]> all = new ArrayList<>();
            for (int c = 0; c < 9; c++) {
                if (c == GOAL) { pads[c] = new int[0]; dest[c] = new int[0]; continue; }
                int n = 2 + r.nextInt(2);
                pads[c] = new int[n];
                dest[c] = new int[n];
                List<Integer> spots = new ArrayList<>(List.of(0, 1, 2, 3));
                java.util.Collections.shuffle(spots, r);
                for (int i = 0; i < n; i++) {
                    pads[c][i] = spots.get(i);
                    int t;
                    do { t = r.nextInt(9); } while (t == GOAL || t == c);
                    dest[c][i] = t;
                    all.add(new int[]{c, i});
                }
            }
            int[] winner = all.get(r.nextInt(all.size()));
            if (winner[0] == ENTRANCE) continue;            // never a one-hop solve
            dest[winner[0]][winner[1]] = GOAL;
            Teleport t = new Teleport(pads, dest);
            int h = t.hops();
            if (h >= 2 && h <= 5) return t;
        }
    }

    // =========================================================================================
    // Boulder: 7 columns x 6 rows of 3x3 boulders. The party enters below row 0; clicking a boulder pushes it one cell
    // away from you (you must stand in the cell behind it, or below the grid for row 0). Clear a path to row 5.
    // =========================================================================================

    public static final int BC = 7, BR = 6;

    public static boolean boulderAt(long grid, int c, int r) { return (grid >> (r * BC + c) & 1L) == 1L; }

    public static long boulderSet(long grid, int c, int r, boolean on) {
        long bit = 1L << (r * BC + c);
        return on ? grid | bit : grid & ~bit;
    }

    /** Free cells reachable from the entrance strip (bitmask). */
    public static long reachable(long grid) {
        long seen = 0;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int c = 0; c < BC; c++) if (!boulderAt(grid, c, 0)) { seen |= 1L << c; q.add(new int[]{c, 0}); }
        while (!q.isEmpty()) {
            int[] p = q.poll();
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                int c = p[0] + d[0], r = p[1] + d[1];
                if (c < 0 || r < 0 || c >= BC || r >= BR || boulderAt(grid, c, r)) continue;
                long bit = 1L << (r * BC + c);
                if ((seen & bit) != 0) continue;
                seen |= bit;
                q.add(new int[]{c, r});
            }
        }
        return seen;
    }

    public static boolean boulderSolved(long grid) {
        long reach = reachable(grid);
        for (int c = 0; c < BC; c++) if ((reach >> ((BR - 1) * BC + c) & 1L) == 1L) return true;
        return false;
    }

    /** The grid after pushing the boulder at (c, r) in (dc, dr), or -1 if the push is impossible from where players can stand. */
    public static long boulderPush(long grid, int c, int r, int dc, int dr) {
        if (!boulderAt(grid, c, r)) return -1;
        int tc = c + dc, tr = r + dr;
        if (tc < 0 || tr < 0 || tc >= BC || tr >= BR || boulderAt(grid, tc, tr)) return -1;
        int sc = c - dc, sr = r - dr;
        boolean stand;
        if (sr == -1) stand = true;                                   // the entrance strip
        else if (sc < 0 || sr < 0 || sc >= BC || sr >= BR) stand = false;
        else stand = !boulderAt(grid, sc, sr) && (reachable(grid) >> (sr * BC + sc) & 1L) == 1L;
        if (!stand) return -1;
        return boulderSet(boulderSet(grid, c, r, false), tc, tr, true);
    }

    /** Fewest pushes to solve (BFS, capped at {@code limit} states); -1 if not found. */
    public static int boulderSolve(long grid, int limit) {
        if (boulderSolved(grid)) return 0;
        Map<Long, Integer> d = new HashMap<>();
        ArrayDeque<Long> q = new ArrayDeque<>();
        d.put(grid, 0);
        q.add(grid);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && d.size() < limit) {
            long g = q.poll();
            int dist = d.get(g);
            for (int r = 0; r < BR; r++)
                for (int c = 0; c < BC; c++) {
                    if (!boulderAt(g, c, r)) continue;
                    for (int[] dd : dirs) {
                        long n = boulderPush(g, c, r, dd[0], dd[1]);
                        if (n < 0 || d.containsKey(n)) continue;
                        if (boulderSolved(n)) return dist + 1;
                        d.put(n, dist + 1);
                        q.add(n);
                    }
                }
        }
        return -1;
    }

    public static long boulder(long seed) {
        Random r = new Random(seed);
        for (int attempt = 0; attempt < 400; attempt++) {
            long g = 0;
            for (int row = 0; row < BR; row++)
                for (int c = 0; c < BC; c++)
                    if (r.nextDouble() < 0.62) g = boulderSet(g, c, row, true);
            // keep the entrance usable: at least two free cells in row 0
            int free0 = 0;
            for (int c = 0; c < BC; c++) if (!boulderAt(g, c, 0)) free0++;
            if (free0 < 2 || boulderSolved(g)) continue;
            int s = boulderSolve(g, 60_000);
            if (s >= 2 && s <= 12) return g;
        }
        // fallback: one wall of boulders in row 3 with a gap that must be pushed open
        long g = 0;
        for (int c = 0; c < BC; c++) g = boulderSet(g, c, 3, true);
        g = boulderSet(g, 3, 4, true);
        return g;
    }

    // =========================================================================================
    // Ice Path: a silverfish on a 13x13 ice grid slides until it hits an obstacle or the edge; push it to the goal.
    // =========================================================================================

    public static final int IP = 13;

    public record IcePath(boolean[] blocked, int start, int goal) {
        /** Where a slide from {@code pos} in (dc, dr) stops. */
        public int slide(int pos, int dc, int dr) {
            int c = pos % IP, r = pos / IP;
            while (true) {
                int nc = c + dc, nr = r + dr;
                if (nc < 0 || nr < 0 || nc >= IP || nr >= IP || blocked[nr * IP + nc]) return r * IP + c;
                c = nc; r = nr;
                if (r * IP + c == goal) return goal;      // the goal catches the fish
            }
        }

        public int solve() {
            int[] d = new int[IP * IP];
            java.util.Arrays.fill(d, -1);
            d[start] = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>(List.of(start));
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!q.isEmpty()) {
                int p = q.poll();
                if (p == goal) return d[p];
                for (int[] dd : dirs) {
                    int n = slide(p, dd[0], dd[1]);
                    if (d[n] < 0) { d[n] = d[p] + 1; q.add(n); }
                }
            }
            return -1;
        }
    }

    public static IcePath icePath(long seed) {
        Random r = new Random(seed);
        while (true) {
            boolean[] b = new boolean[IP * IP];
            int obstacles = 22 + r.nextInt(10);
            for (int i = 0; i < obstacles; i++) b[r.nextInt(IP * IP)] = true;
            int start = 6;                        // bottom row centre (row 0 is the door side)
            int goal = (IP - 1) * IP + r.nextInt(IP);
            b[start] = false;
            b[goal] = false;
            IcePath ip = new IcePath(b, start, goal);
            int s = ip.solve();
            if (s >= 4 && s <= 14) return ip;
        }
    }

    // =========================================================================================
    // Ice Fill: n x n boards; walk every ice tile exactly once from the start (bottom middle) to the exit (top middle).
    // =========================================================================================

    /** A board plus one known solution (the walk that generated it). */
    public record IceFill(int n, boolean[] blocked, int start, int exit, List<Integer> solution) {
        public int free() {
            int f = 0;
            for (boolean x : blocked) if (!x) f++;
            return f;
        }

        /** True when {@code walk} is a legal full solution: start -> exit, 4-adjacent steps, every free tile exactly once. */
        public boolean valid(List<Integer> walk) {
            if (walk == null || walk.isEmpty() || walk.get(0) != start || walk.get(walk.size() - 1) != exit) return false;
            if (walk.size() != free()) return false;
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int i = 0; i < walk.size(); i++) {
                int c = walk.get(i);
                if (c < 0 || c >= n * n || blocked[c] || !seen.add(c)) return false;
                if (i > 0) {
                    int p = walk.get(i - 1);
                    if (Math.abs(p % n - c % n) + Math.abs(p / n - c / n) != 1) return false;
                }
            }
            return true;
        }
    }

    /**
     * Constructive: a randomised (Warnsdorff-ordered) self-avoiding walk from the start that reaches the exit having
     * covered all but 2..6 tiles; the tiles it skipped become the obstacles. Grid parity makes the obstacle count even
     * (3x3: exactly 2), and the walk itself is a guaranteed solution.
     */
    public static IceFill iceFill(long seed, int n) {
        Random r = new Random(seed);
        int start = n / 2, exit = (n - 1) * n + n / 2;
        int minLen = n == 3 ? 7 : n * n - (n == 5 ? 4 : 6);
        int maxLen = n == 3 ? 7 : n * n - 2;
        for (int attempt = 0; attempt < 500; attempt++) {
            boolean[] seen = new boolean[n * n];
            List<Integer> path = new ArrayList<>();
            path.add(start);
            seen[start] = true;
            int[] budget = {200_000};
            if (walk(n, start, exit, seen, path, minLen, maxLen, r, budget)) {
                boolean[] blocked = new boolean[n * n];
                for (int i = 0; i < n * n; i++) blocked[i] = !seen[i];
                return new IceFill(n, blocked, start, exit, List.copyOf(path));
            }
        }
        throw new IllegalStateException("ice fill " + n + "x" + n + " generation failed");
    }

    private static boolean walk(int n, int cur, int exit, boolean[] seen, List<Integer> path, int minLen, int maxLen, Random r, int[] budget) {
        if (--budget[0] < 0) return false;
        if (cur == exit) return path.size() >= minLen && path.size() <= maxLen;
        if (path.size() >= maxLen) return false;
        int c = cur % n, row = cur / n;
        List<int[]> next = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nc = c + d[0], nr = row + d[1];
            if (nc < 0 || nr < 0 || nc >= n || nr >= n) continue;
            int nx = nr * n + nc;
            if (seen[nx]) continue;
            int onward = 0;
            for (int[] e : dirs) {
                int mc = nc + e[0], mr = nr + e[1];
                if (mc >= 0 && mr >= 0 && mc < n && mr < n && !seen[mr * n + mc]) onward++;
            }
            next.add(new int[]{nx, onward * 4 + r.nextInt(4)});
        }
        next.sort((a, b) -> Integer.compare(a[1], b[1]));
        for (int[] nx : next) {
            seen[nx[0]] = true;
            path.add(nx[0]);
            if (walk(n, nx[0], exit, seen, path, minLen, maxLen, r, budget)) return true;
            path.remove(path.size() - 1);
            seen[nx[0]] = false;
        }
        return false;
    }

    /** A Hamiltonian path start -> exit through every free cell (as a cell list), or null. Node-capped DFS. */
    public static List<Integer> hamiltonian(IceFill f, int nodeLimit) {
        int n = f.n;
        boolean[] seen = new boolean[n * n];
        List<Integer> path = new ArrayList<>();
        int total = f.free();
        int[] budget = {nodeLimit};
        path.add(f.start);
        seen[f.start] = true;
        if (dfs(f, f.start, seen, path, total, budget)) return path;
        return null;
    }

    private static boolean dfs(IceFill f, int cur, boolean[] seen, List<Integer> path, int total, int[] budget) {
        if (--budget[0] < 0) return false;
        if (path.size() == total) return cur == f.exit;
        if (cur == f.exit) return false;
        int n = f.n;
        int c = cur % n, r = cur / n;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int nc = c + d[0], nr = r + d[1];
            if (nc < 0 || nr < 0 || nc >= n || nr >= n) continue;
            int nx = nr * n + nc;
            if (f.blocked[nx] || seen[nx]) continue;
            seen[nx] = true;
            path.add(nx);
            if (dfs(f, nx, seen, path, total, budget)) return true;
            path.remove(path.size() - 1);
            seen[nx] = false;
        }
        return false;
    }
}
