package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;

/**
 * A dense byte grid for a cave world, worked on BEFORE anything is written to the canvas.
 * Carving 30 million cells through block strings would be slow and would make "is this rock?"
 * questions expensive; here every cell is one byte and every operation is an array access.
 *
 * <p>Cell kinds: {@link #AIR}, {@link #ROCK}, {@link #BEDROCK}, {@link #LAVA}, {@link #WATER}.
 * The {@link #LOCK} bit marks a cell later passes may not change: walkways, platforms and the air
 * reserved for structures. Pure Java (no Bukkit).
 */
public final class Grid {

    public static final byte AIR = 0, ROCK = 1, BEDROCK = 2, LAVA = 3, WATER = 4;
    public static final byte LOCK = 0x40;
    private static final byte KIND = 0x0F;

    public final int minX, minY, minZ, w, h, d;
    private final byte[] cells;

    public Grid(Box b) {
        minX = b.minX(); minY = b.minY(); minZ = b.minZ();
        w = b.maxX() - minX + 1; h = b.maxY() - minY + 1; d = b.maxZ() - minZ + 1;
        cells = new byte[w * h * d];
    }

    public int maxX() { return minX + w - 1; }
    public int maxY() { return minY + h - 1; }
    public int maxZ() { return minZ + d - 1; }

    public boolean in(int x, int y, int z) {
        return x >= minX && y >= minY && z >= minZ && x < minX + w && y < minY + h && z < minZ + d;
    }

    private int idx(int x, int y, int z) { return ((y - minY) * d + (z - minZ)) * w + (x - minX); }

    /** Cell kind (lock bit stripped); outside the grid reads as BEDROCK. */
    public byte kind(int x, int y, int z) { return in(x, y, z) ? (byte) (cells[idx(x, y, z)] & KIND) : BEDROCK; }

    public boolean locked(int x, int y, int z) { return in(x, y, z) && (cells[idx(x, y, z)] & LOCK) != 0; }

    public boolean air(int x, int y, int z) { return kind(x, y, z) == AIR; }

    /** Rock or bedrock: something to stand on / something that blocks. */
    public boolean solid(int x, int y, int z) { byte k = kind(x, y, z); return k == ROCK || k == BEDROCK; }

    public boolean fluid(int x, int y, int z) { byte k = kind(x, y, z); return k == LAVA || k == WATER; }

    /** Unconditional write (generation passes that own the cell). */
    public void put(int x, int y, int z, byte kind, boolean lock) {
        if (!in(x, y, z)) return;
        cells[idx(x, y, z)] = (byte) (kind | (lock ? LOCK : 0));
    }

    /** Write unless the cell is locked or bedrock. */
    public boolean set(int x, int y, int z, byte kind) {
        if (!in(x, y, z)) return false;
        int i = idx(x, y, z);
        byte c = cells[i];
        if ((c & LOCK) != 0 || (c & KIND) == BEDROCK) return false;
        cells[i] = kind;
        return true;
    }

    public boolean carve(int x, int y, int z) { return set(x, y, z, AIR); }

    /** Fill the whole grid with rock and wrap it in a bedrock shell {@code shell} thick. */
    public void fillRock(int shell) {
        java.util.Arrays.fill(cells, ROCK);
        for (int y = minY; y <= maxY(); y++)
            for (int z = minZ; z <= maxZ(); z++)
                for (int x = minX; x <= maxX(); x++) {
                    boolean edge = y - minY < shell || maxY() - y < shell || x - minX < shell || maxX() - x < shell
                            || z - minZ < shell || maxZ() - z < shell;
                    if (edge) cells[idx(x, y, z)] = BEDROCK;
                }
    }

    // ---- shapes ---------------------------------------------------------------------------------

    /** Carve an ellipsoid. */
    public void carveEllipsoid(double cx, double cy, double cz, double rx, double ry, double rz) {
        int x0 = (int) Math.floor(cx - rx), x1 = (int) Math.ceil(cx + rx);
        int y0 = (int) Math.floor(cy - ry), y1 = (int) Math.ceil(cy + ry);
        int z0 = (int) Math.floor(cz - rz), z1 = (int) Math.ceil(cz + rz);
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    double dx = (x + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry, dz = (z + 0.5 - cz) / rz;
                    if (dx * dx + dy * dy + dz * dz <= 1) carve(x, y, z);
                }
    }

    /** Fill (kind) a box, honouring locks. */
    public void fill(Box b, byte kind) {
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++) set(x, y, z, kind);
    }

    /** Write (kind | lock) over a box, ignoring existing locks but never bedrock. */
    public void lockBox(Box b, byte kind) {
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++)
                    if (in(x, y, z) && kind(x, y, z) != BEDROCK) put(x, y, z, kind, true);
    }

    /** Is any of the 6 neighbours air or fluid (a visible face)? */
    public boolean exposed(int x, int y, int z) {
        return !solid(x + 1, y, z) || !solid(x - 1, y, z) || !solid(x, y + 1, z) || !solid(x, y - 1, z)
                || !solid(x, y, z + 1) || !solid(x, y, z - 1);
    }

    /** Highest solid y at or below {@code fromY} in a column (the floor someone would land on), or Integer.MIN_VALUE. */
    public int floorBelow(int x, int fromY, int z) {
        for (int y = Math.min(fromY, maxY()); y >= minY; y--) if (solid(x, y, z)) return y;
        return Integer.MIN_VALUE;
    }

    /**
     * Turn every unlocked rock cell that is not connected (through faces) to the bedrock shell into
     * air: carving leaves crumbs — a single block hanging in a cave, a sliver between two bores —
     * and in game those are floating blocks. Locked cells (walkways, platforms) are kept.
     * Returns the number of cells removed.
     */
    public int dropLooseRock() {
        int n = cells.length;
        java.util.BitSet seen = new java.util.BitSet(n);
        int[] q = new int[1 << 16];
        int head = 0, tail = 0, size = 0;
        // seed with every bedrock cell
        for (int i = 0; i < n; i++) {
            if ((cells[i] & KIND) != BEDROCK) continue;
            seen.set(i);
            if (size == q.length) { q = grow(q, head, size); head = 0; tail = size; }
            q[tail] = i; tail = (tail + 1) & (q.length - 1); size++;
        }
        int plane = w * d;
        while (size > 0) {
            int i = q[head]; head = (head + 1) & (q.length - 1); size--;
            int x = i % w, rest = i / w, z = rest % d, y = rest / d;
            for (int k = 0; k < 6; k++) {
                int j;
                switch (k) {
                    case 0 -> { if (x + 1 >= w) continue; j = i + 1; }
                    case 1 -> { if (x == 0) continue; j = i - 1; }
                    case 2 -> { if (z + 1 >= d) continue; j = i + w; }
                    case 3 -> { if (z == 0) continue; j = i - w; }
                    case 4 -> { if (y + 1 >= h) continue; j = i + plane; }
                    default -> { if (y == 0) continue; j = i - plane; }
                }
                if (seen.get(j)) continue;
                byte kd = (byte) (cells[j] & KIND);
                if (kd != ROCK && kd != BEDROCK) continue;
                seen.set(j);
                if (size == q.length) { q = grow(q, head, size); head = 0; tail = size; }
                q[tail] = j; tail = (tail + 1) & (q.length - 1); size++;
            }
        }
        int removed = 0;
        for (int i = 0; i < n; i++) {
            byte c = cells[i];
            if ((c & KIND) == ROCK && (c & LOCK) == 0 && !seen.get(i)) { cells[i] = AIR; removed++; }
        }
        return removed;
    }

    private static int[] grow(int[] q, int head, int size) {
        int[] nq = new int[q.length * 2];
        for (int k = 0; k < size; k++) nq[k] = q[(head + k) & (q.length - 1)];
        return nq;
    }

    public long count(byte kind) {
        long n = 0;
        for (byte c : cells) if ((c & KIND) == kind) n++;
        return n;
    }
}
