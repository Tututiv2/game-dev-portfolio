package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Sparse in-memory voxel buffer: 16x16x16 sections of short indexes into one palette of block
 * strings. Index 0 means "never written" (reads as air); writing "air" is recorded, because a
 * carving pass must clear whatever the real world had there.
 *
 * <p>Built for tens of millions of writes: the raw string -> palette index lookup is a plain
 * HashMap hit on an interned literal, section lookup is an open-addressed long map with a
 * one-entry cache, and nothing is boxed per block. Not thread-safe for writes; safe to read from
 * many threads once the generator has finished.
 */
public final class MemoryCanvas implements VoxelCanvas {

    /** One 16^3 section. {@code ids[(y&15)<<8 | (z&15)<<4 | (x&15)]}. */
    public static final class Section {
        public final int sx, sy, sz;
        public final short[] ids = new short[4096];
        int count;
        Section(int sx, int sy, int sz) { this.sx = sx; this.sy = sy; this.sz = sz; }
        /** Number of written cells. */
        public int count() { return count; }
    }

    private final List<String> palette = new ArrayList<>();
    private final HashMap<String, Integer> rawIndex = new HashMap<>();
    private final HashMap<String, Integer> normIndex = new HashMap<>();
    private boolean[] airId = new boolean[64];
    private BlockInfo[] infos = new BlockInfo[64];

    // sections: open-addressing long -> Section
    private long[] keys;
    private Section[] vals;
    private int size, mask, limit;
    private static final long EMPTY = Long.MIN_VALUE;

    /** One-entry cache; an immutable pair so concurrent readers can never see a torn key/section. */
    private record Hit(long key, Section section) {}
    private Hit last = new Hit(EMPTY, null);

    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    private long written;

    public MemoryCanvas() {
        palette.add(null);            // 0 = unwritten
        alloc(1024);
    }

    private void alloc(int cap) {
        keys = new long[cap];
        Arrays.fill(keys, EMPTY);
        vals = new Section[cap];
        mask = cap - 1;
        limit = (int) (cap * 0.6);
        size = 0;
    }

    private static long skey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
    }

    private static int mix(long k) {
        k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33;
        return (int) k;
    }

    private Section find(long k) {
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == k) return vals[i];
            if (c == EMPTY) return null;
            i = (i + 1) & mask;
        }
    }

    private void insert(long k, Section s) {
        if (size >= limit) {
            long[] ok = keys; Section[] ov = vals;
            alloc(ok.length * 2);
            for (int i = 0; i < ok.length; i++) if (ok[i] != EMPTY) insert(ok[i], ov[i]);
        }
        int i = mix(k) & mask;
        while (keys[i] != EMPTY) i = (i + 1) & mask;
        keys[i] = k; vals[i] = s; size++;
    }

    private Section section(int x, int y, int z, boolean create) {
        int sx = x >> 4, sy = y >> 4, sz = z >> 4;
        long k = skey(sx, sy, sz);
        Hit h = last;
        if (h.key == k && h.section != null) return h.section;
        Section s = find(k);
        if (s == null) {
            if (!create) return null;
            s = new Section(sx, sy, sz);
            insert(k, s);
        }
        last = new Hit(k, s);
        return s;
    }

    // ---- palette --------------------------------------------------------------------------

    /** Palette index for a block string (adds it). */
    public int paletteId(String block) {
        Integer id = rawIndex.get(block);
        if (id != null) return id;
        String n = BlockState.normalise(block);
        id = normIndex.get(n);
        if (id == null) {
            id = palette.size();
            if (id > Short.MAX_VALUE) throw new IllegalStateException("More than 32767 distinct block states in one canvas");
            palette.add(n);
            normIndex.put(n, id);
            if (id >= airId.length) {
                airId = Arrays.copyOf(airId, airId.length * 2);
                infos = Arrays.copyOf(infos, infos.length * 2);
            }
            BlockInfo info = BlockInfo.of(n);
            infos[id] = info;
            airId[id] = info.isAir();
        }
        if (block != null) rawIndex.put(block, id);
        return id;
    }

    /** Block string for a palette index ("air" for 0). */
    public String palette(int id) { return id == 0 ? "air" : palette.get(id); }

    public int paletteSize() { return palette.size(); }

    /** All distinct block strings written (excluding the "unwritten" slot). */
    public List<String> paletteEntries() { return palette.subList(1, palette.size()); }

    public BlockInfo info(int id) { return id == 0 ? BlockInfo.AIR : infos[id]; }

    public boolean isAirId(int id) { return id == 0 || airId[id]; }

    // ---- VoxelCanvas ----------------------------------------------------------------------

    @Override
    public void set(int x, int y, int z, String block) {
        setId(x, y, z, paletteId(block));
    }

    public void setId(int x, int y, int z, int id) {
        Section s = section(x, y, z, true);
        int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        short old = s.ids[i];
        if (old == 0) {
            if (id == 0) return;
            s.count++;
            written++;
        } else if (id == 0) {
            s.count--;
            written--;
        }
        s.ids[i] = (short) id;
        if (id != 0) {
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
        }
    }

    /** Forget a cell entirely (it will not be placed at all, not even as air). */
    public void unset(int x, int y, int z) { setId(x, y, z, 0); }

    @Override
    public String get(int x, int y, int z) { return palette(getId(x, y, z)); }

    /** Palette index at a position, 0 if never written. */
    public int getId(int x, int y, int z) {
        Section s = section(x, y, z, false);
        if (s == null) return 0;
        return s.ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    public BlockInfo infoAt(int x, int y, int z) { return info(getId(x, y, z)); }

    public boolean isWritten(int x, int y, int z) { return getId(x, y, z) != 0; }

    @Override
    public boolean isAir(int x, int y, int z) { return isAirId(getId(x, y, z)); }

    @Override
    public int topY(int x, int z, int minY, int maxY) {
        int lo = Math.max(minY, this.minY), hi = Math.min(maxY, this.maxY);
        for (int y = hi; y >= lo; y--) if (!isAirId(getId(x, y, z))) return y;
        return Integer.MIN_VALUE;
    }

    @Override
    public void fill(Box b, String block) {
        int id = paletteId(block);
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++)
                    setId(x, y, z, id);
    }

    // ---- inspection -----------------------------------------------------------------------

    /** Number of written cells (including written air). */
    public long written() { return written; }

    /** Number of written non-air cells. */
    public long blockCount() {
        long n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == EMPTY) continue;
            short[] ids = vals[i].ids;
            for (short id : ids) if (id != 0 && !airId[id]) n++;
        }
        return n;
    }

    /** Bounds of everything written, or null when empty. */
    public Box bounds() {
        if (written == 0 && minX == Integer.MAX_VALUE) return null;
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public int sectionCount() { return size; }

    public interface BlockVisitor { void visit(int x, int y, int z, String block); }

    public interface IdVisitor { void visit(int x, int y, int z, int id); }

    public interface SectionVisitor { void visit(Section s); }

    /** Every written cell (air included) with its block string. */
    public void forEach(BlockVisitor v) {
        forEachId((x, y, z, id) -> v.visit(x, y, z, palette(id)));
    }

    public void forEachId(IdVisitor v) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == EMPTY) continue;
            Section s = vals[i];
            if (s.count == 0) continue;
            int bx = s.sx << 4, by = s.sy << 4, bz = s.sz << 4;
            short[] ids = s.ids;
            for (int j = 0; j < 4096; j++) {
                int id = ids[j];
                if (id == 0) continue;
                v.visit(bx + (j & 15), by + (j >> 8), bz + ((j >> 4) & 15), id);
            }
        }
    }

    public void forEachSection(SectionVisitor v) {
        for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY && vals[i].count > 0) v.visit(vals[i]);
    }

    /** Sections grouped by chunk column, key = Pack.column(cx, cz). Used by core to place chunk by chunk. */
    public java.util.Map<Long, List<Section>> sectionsByChunk() {
        java.util.Map<Long, List<Section>> m = new java.util.LinkedHashMap<>();
        forEachSection(s -> m.computeIfAbsent(com.skyclone.gen.util.Pack.column(s.sx, s.sz), k -> new ArrayList<>()).add(s));
        return m;
    }
}
