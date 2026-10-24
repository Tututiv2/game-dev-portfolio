package com.skyclone.mod.mines.gen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntBinaryOperator;

/**
 * Lays out a cart network that can actually be railed: a Dijkstra over floor columns from one start (the depot's
 * platform throat) whose moves obey what vanilla rails can do. A step may rise or fall one block; a rise or a fall
 * must be ridden straight through (an ascending rail cannot turn), and a valley (down then up) is impossible. Turns
 * cost extra so the lines run in long straights like a laid track rather than a staircase. Every target gets the
 * cheapest legal line, and because every line grows from the same start the union of lines is a tree: a junction
 * where branches split, never two tracks side by side.
 *
 * <p>Pure Java. {@code floorAt} answers the floor block of a column a cart may roll over, or
 * {@link CaveGrid#NONE}; {@code extraCost} adds a per-cell cost (hug the middle of a passage, keep out of towns).
 */
public final class RailPlanner {

    public static final int STEP = 10, TURN = 40, SLOPE = 6;

    private final int minX, minZ, w, d;
    private final int[] floor, extra;

    public RailPlanner(int minX, int minZ, int maxX, int maxZ, IntBinaryOperator floorAt, IntBinaryOperator extraCost) {
        this.minX = minX; this.minZ = minZ;
        this.w = maxX - minX + 1; this.d = maxZ - minZ + 1;
        floor = new int[w * d];
        extra = new int[w * d];
        for (int iz = 0; iz < d; iz++)
            for (int ix = 0; ix < w; ix++) {
                floor[iz * w + ix] = floorAt.applyAsInt(minX + ix, minZ + iz);
                if (floor[iz * w + ix] != CaveGrid.NONE) extra[iz * w + ix] = Math.max(0, extraCost.applyAsInt(minX + ix, minZ + iz));
            }
    }

    private int cell(int x, int z) {
        int ix = x - minX, iz = z - minZ;
        return ix < 0 || iz < 0 || ix >= w || iz >= d ? -1 : iz * w + ix;
    }

    public boolean passable(int x, int z) { int c = cell(x, z); return c >= 0 && floor[c] != CaveGrid.NONE; }

    public int floorAt(int x, int z) { int c = cell(x, z); return c < 0 ? CaveGrid.NONE : floor[c]; }

    /**
     * A place the network must reach: the throat column (x, z) of a station, from which the line steps on in direction
     * {@code dir} (RailRoute.DX/DZ) onto the platform lane, whose floor is {@code laneY}. The line may come in straight
     * or turn into the throat from either side (a curve, so only on the level).
     */
    public record Target(String id, int x, int z, int dir, int laneY) {}

    /**
     * The cheapest legal line from (sx, sz), leaving in direction {@code sdir}, to each target (arriving in its
     * direction). The result lists cells {x, floorY, z} from start to target, or null for a target it cannot reach.
     */
    public List<List<int[]>> plan(int sx, int sz, int sdir, List<Target> targets) {
        List<List<int[]>> out = new ArrayList<>();
        int start = cell(sx, sz);
        if (start < 0 || floor[start] == CaveGrid.NONE) { for (int i = 0; i < targets.size(); i++) out.add(null); return out; }
        int n = w * d * 8;                          // state = (cell * 4 + dir) * 2 + mode
        int[] dist = new int[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        LongHeap heap = new LongHeap();
        int s0 = (start * 4 + sdir) * 2;
        dist[s0] = 0;
        heap.push(s0);
        while (!heap.isEmpty()) {
            long top = heap.pop();
            int st = (int) (top & 0xffffffffL), du = (int) (top >>> 32);
            if (du != dist[st]) continue;
            int mode = st & 1, dir = (st >> 1) & 3, c = st >> 3;
            int cx = c % w, cz = c / w;
            for (int nd = 0; nd < 4; nd++) {
                if (nd == (dir + 2) % 4) continue;
                if (mode == 1 && nd != dir) continue;                  // just came down: ride the slope out straight
                int nx = cx + RailRoute.DX[nd], nz = cz + RailRoute.DZ[nd];
                if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                int nc = nz * w + nx;
                if (floor[nc] == CaveGrid.NONE) continue;
                int dh = floor[nc] - floor[c];
                if (Math.abs(dh) > 1) continue;
                if (dh == 1 && (nd != dir || mode == 1)) continue;     // climb only straight on, never out of a dip
                int nm = dh == -1 ? 1 : 0;
                int cost = STEP + (nd != dir ? TURN : 0) + (dh != 0 ? SLOPE : 0) + extra[nc];
                int ns = (nc * 4 + nd) * 2 + nm;
                long nd2 = (long) du + cost;
                if (nd2 < dist[ns]) {
                    dist[ns] = (int) nd2;
                    prev[ns] = st;
                    heap.push((nd2 << 32) | ns);
                }
            }
        }
        for (Target t : targets) {
            int tc = cell(t.x(), t.z());
            if (tc < 0) { out.add(null); continue; }
            int best = -1;
            int dh = floor[tc] == CaveGrid.NONE ? 99 : t.laneY() - floor[tc];
            for (int ad = 0; ad < 4 && Math.abs(dh) <= 1; ad++) {
                if (ad == (t.dir() + 2) % 4) continue;
                for (int m = 0; m < 2; m++) {
                    if (ad == t.dir() ? (dh == 1 && m == 1) : (m == 1 || dh != 0)) continue;
                    int s = (tc * 4 + ad) * 2 + m;
                    if (dist[s] != Integer.MAX_VALUE && (best < 0 || dist[s] < dist[best])) best = s;
                }
            }
            if (best < 0) { out.add(null); continue; }
            List<int[]> path = new ArrayList<>();
            for (int s = best; s >= 0; s = prev[s]) {
                int c = s >> 3;
                path.add(new int[]{minX + c % w, floor[c], minZ + c / w});
                if (s == s0) break;
            }
            Collections.reverse(path);
            out.add(path);
        }
        return out;
    }

    /** A binary min-heap of longs (dist << 32 | state). */
    private static final class LongHeap {
        private long[] a = new long[1 << 16];
        private int size;

        boolean isEmpty() { return size == 0; }

        void push(long v) {
            if (size == a.length) a = Arrays.copyOf(a, a.length * 2);
            int i = size++;
            a[i] = v;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (a[p] <= a[i]) break;
                long t = a[p]; a[p] = a[i]; a[i] = t;
                i = p;
            }
        }

        long pop() {
            long r = a[0];
            a[0] = a[--size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1, rr = l + 1, m = i;
                if (l < size && a[l] < a[m]) m = l;
                if (rr < size && a[rr] < a[m]) m = rr;
                if (m == i) break;
                long t = a[m]; a[m] = a[i]; a[i] = t;
                i = m;
            }
            return r;
        }
    }
}
