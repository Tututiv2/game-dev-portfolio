package com.skyclone.mod.mines.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;

/**
 * A cave described column by column: every OPEN column has one air gap from {@code floor+1} to
 * {@code ceil-1} (floor = the block you stand on, ceil = the first rock overhead). Closed columns
 * are rock. {@link #write} turns it into blocks, filling only a shell of rock around the open
 * space (deep rock nobody can see is left out, so a 400-block cavern is not 20 million blocks).
 *
 * <p>Pure Java: the mines generators build every cave level of every island through this.
 */
public final class CaveGrid {

    public static final int NONE = Integer.MIN_VALUE;

    /** Picks the block for every rock cell and every floor-top cell. */
    public interface Painter {
        /** {@code exposed}: the cell touches the cave air (a wall face, a floor, a ceiling). */
        String rock(int x, int y, int z, int region, boolean exposed);
        /** The block players stand on in an open column. */
        String floorTop(int x, int y, int z, int region);
    }

    public final int minX, minZ, w, d;
    private final int[] floor, ceil;
    private final byte[] region;

    public CaveGrid(int minX, int minZ, int maxX, int maxZ) {
        this.minX = minX; this.minZ = minZ;
        this.w = maxX - minX + 1; this.d = maxZ - minZ + 1;
        floor = new int[w * d];
        ceil = new int[w * d];
        region = new byte[w * d];
        java.util.Arrays.fill(floor, NONE);
        java.util.Arrays.fill(ceil, NONE);
    }

    private int idx(int x, int z) {
        int ix = x - minX, iz = z - minZ;
        if (ix < 0 || iz < 0 || ix >= w || iz >= d) return -1;
        return iz * w + ix;
    }

    public boolean open(int x, int z) { int i = idx(x, z); return i >= 0 && floor[i] != NONE; }
    public int floor(int x, int z) { int i = idx(x, z); return i < 0 ? NONE : floor[i]; }
    public int ceil(int x, int z) { int i = idx(x, z); return i < 0 ? NONE : ceil[i]; }
    public int region(int x, int z) { int i = idx(x, z); return i < 0 ? 0 : region[i]; }

    /** Open a column (or reshape an open one). {@code ceil} is clamped to at least floor+3. */
    public void set(int x, int z, int floorY, int ceilY, int reg) {
        int i = idx(x, z);
        if (i < 0) return;
        floor[i] = floorY;
        ceil[i] = Math.max(ceilY, floorY + 3);
        region[i] = (byte) reg;
    }

    public void setRegion(int x, int z, int reg) { int i = idx(x, z); if (i >= 0) region[i] = (byte) reg; }

    public void close(int x, int z) { int i = idx(x, z); if (i >= 0) { floor[i] = NONE; ceil[i] = NONE; } }

    /** Is (x,y,z) inside the cave air? */
    public boolean air(int x, int y, int z) {
        int i = idx(x, z);
        return i >= 0 && floor[i] != NONE && y > floor[i] && y < ceil[i];
    }

    /** The bounding box of the open columns (feet-space y range: floor+1 .. ceil-1). */
    public Box openBox() {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (int iz = 0; iz < d; iz++)
            for (int ix = 0; ix < w; ix++) {
                int i = iz * w + ix;
                if (floor[i] == NONE) continue;
                x0 = Math.min(x0, minX + ix); x1 = Math.max(x1, minX + ix);
                z0 = Math.min(z0, minZ + iz); z1 = Math.max(z1, minZ + iz);
                y0 = Math.min(y0, floor[i]); y1 = Math.max(y1, ceil[i]);
            }
        return x0 > x1 ? null : new Box(x0, y0, z0, x1, y1, z1);
    }

    /** Min and max floor over the open columns of one region inside a box (x/z only); {NONE,NONE} if none. */
    public int[] floorRange(Box b, int reg) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++) {
                int i = idx(x, z);
                if (i < 0 || floor[i] == NONE || (reg >= 0 && region[i] != reg)) continue;
                lo = Math.min(lo, floor[i]); hi = Math.max(hi, floor[i]);
            }
        return lo > hi ? new int[]{NONE, NONE} : new int[]{lo, hi};
    }

    /**
     * Write the rock. Open columns get rock from {@code thickBelow} under the lowest floor in reach
     * up to their floor, and from their ceiling up {@code thickAbove} past the highest ceiling in
     * reach. Closed columns within {@code shell} of an open one become walls spanning the open
     * neighbours' full height. Everything else stays empty.
     */
    public void write(VoxelCanvas c, int shell, int thickBelow, int thickAbove, Painter p) {
        write(c, shell, thickBelow, thickAbove, p, java.util.List.of());
    }

    /**
     * As {@link #write(VoxelCanvas, int, int, int, Painter)}, but never puts rock into the air of
     * any grid in {@code keepClear} (stacked cave levels, tunnels between them).
     */
    public void write(VoxelCanvas c, int shell, int thickBelow, int thickAbove, Painter p, java.util.List<CaveGrid> keepClear) {
        int n = w * d;
        int[] lo = new int[n], hi = new int[n];
        for (int i = 0; i < n; i++) {
            lo[i] = floor[i] == NONE ? Integer.MAX_VALUE : floor[i];
            hi[i] = floor[i] == NONE ? Integer.MIN_VALUE : ceil[i];
        }
        lo = filter(lo, shell, true);
        hi = filter(hi, shell, false);
        for (int iz = 0; iz < d; iz++)
            for (int ix = 0; ix < w; ix++) {
                int i = iz * w + ix;
                if (lo[i] == Integer.MAX_VALUE) continue;            // nothing open within reach
                int x = minX + ix, z = minZ + iz;
                int bottom = lo[i] - thickBelow, top = hi[i] + thickAbove;
                int f = floor[i], cl = ceil[i];
                int reg = region[i];
                if (reg == 0) reg = nearestRegion(ix, iz, shell);
                for (int y = bottom; y <= top; y++) {
                    if (f != NONE && y > f && y < cl) continue;      // the cave air itself
                    if (clear(keepClear, x, y, z)) continue;
                    if (f != NONE && y == f) { c.set(x, y, z, p.floorTop(x, y, z, reg)); continue; }
                    boolean exposed = (f != NONE && y == cl) || exposedSide(x, y, z);
                    if (!exposed) for (CaveGrid o : keepClear)
                        if (o != this && (o.air(x + 1, y, z) || o.air(x - 1, y, z) || o.air(x, y, z + 1) || o.air(x, y, z - 1) || o.air(x, y + 1, z) || o.air(x, y - 1, z))) { exposed = true; break; }
                    c.set(x, y, z, p.rock(x, y, z, reg, exposed));
                }
            }
    }

    private boolean clear(java.util.List<CaveGrid> others, int x, int y, int z) {
        for (CaveGrid o : others) if (o != this && o.air(x, y, z)) return true;
        return false;
    }

    private boolean exposedSide(int x, int y, int z) {
        return air(x + 1, y, z) || air(x - 1, y, z) || air(x, y, z + 1) || air(x, y, z - 1) || air(x, y - 1, z) || air(x, y + 1, z);
    }

    private int nearestRegion(int ix, int iz, int r) {
        for (int k = 1; k <= r + 1; k++)
            for (int dz = -k; dz <= k; dz++)
                for (int dx = -k; dx <= k; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != k) continue;
                    int jx = ix + dx, jz = iz + dz;
                    if (jx < 0 || jz < 0 || jx >= w || jz >= d) continue;
                    int j = jz * w + jx;
                    if (floor[j] != NONE && region[j] != 0) return region[j];
                }
        return 0;
    }

    /** Separable square min (or max) filter of radius r. */
    private int[] filter(int[] src, int r, boolean min) {
        int[] tmp = new int[src.length], out = new int[src.length];
        for (int iz = 0; iz < d; iz++)
            for (int ix = 0; ix < w; ix++) {
                int best = min ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                for (int k = Math.max(0, ix - r); k <= Math.min(w - 1, ix + r); k++) {
                    int v = src[iz * w + k];
                    best = min ? Math.min(best, v) : Math.max(best, v);
                }
                tmp[iz * w + ix] = best;
            }
        for (int iz = 0; iz < d; iz++)
            for (int ix = 0; ix < w; ix++) {
                int best = min ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                for (int k = Math.max(0, iz - r); k <= Math.min(d - 1, iz + r); k++) {
                    int v = tmp[k * w + ix];
                    best = min ? Math.min(best, v) : Math.max(best, v);
                }
                out[iz * w + ix] = best;
            }
        return out;
    }
}
