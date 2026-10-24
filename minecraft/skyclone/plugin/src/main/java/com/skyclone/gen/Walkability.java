package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.util.LongIntMap;
import com.skyclone.gen.util.LongQueue;
import com.skyclone.gen.util.LongSet;
import com.skyclone.gen.util.Pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Can a player actually WALK from {@code spawn} to everything that matters? Floods every
 * standable cell reachable from the "spawn" anchor with a simple model of player movement and
 * reports every anchor and portal area it cannot reach, anchors buried in blocks, standable
 * regions that are cut off, and blocks floating in the air.
 *
 * <p><b>Movement model</b> (heights in sixteenths of a block):
 * <ul>
 *   <li>A cell is standable when its feet space and the head space above are passable and there
 *       is something to stand on: a full block below, a fence/wall below (+0.5), a low block in the
 *       cell itself (slab, bottom stairs, carpet, farmland, snow layers...), water below (swimming),
 *       or a ladder/vine/scaffolding at the feet (climbing).</li>
 *   <li>Passable: air, plants, torches, signs, buttons, rails, open trapdoors, water, and DOORS and
 *       FENCE GATES (treated as open). Fences, walls, panes, bars, chains, hanging lanterns block.</li>
 *   <li>Moves go to the 4 horizontal neighbours: up at most 1.19 blocks (one jump, so a full block
 *       or a stair/slab step — but not a slab ON a block), down at most {@code fallMax} blocks, with
 *       head room checked for the jump and for the drop. Climbables connect vertically.</li>
 * </ul>
 * Anchors listed under {@code layout.data("walk.ignore")} (comma-separated name prefixes) are not
 * checked — use it for camera points, mob spawn markers inside walls, etc.
 */
public final class Walkability {

    private Walkability() {}

    public static final class Options {
        /** Largest drop counted as walkable (blocks). */
        public int fallMax = 4;
        /** An anchor whose own cell is not reachable still passes if a reachable cell is within this many blocks (NPC behind a counter). */
        public int nearRadius = 2;
        /** Extra anchor-name prefixes to skip. */
        public Set<String> ignore = new HashSet<>();
        /** Skip the floating-block search (it is the slowest part on a huge island). */
        public boolean skipFloating = false;
    }

    public enum Status { REACHABLE, NEAR, UNREACHABLE, INSIDE_SOLID, SKIPPED }

    public record AnchorResult(String name, double x, double y, double z, Status status, String detail) {}

    public record PortalResult(String name, Box box, boolean reachable) {}

    /** A connected group of blocks touching nothing else. {@code decorative} = only leaves/vines/lanterns/chains. */
    public record Floating(int size, Box box, String sample, boolean decorative) {}

    /** A connected group of standable cells that cannot be reached from spawn. */
    public record Region(int size, Box box) {}

    public static final class Report {
        public int standCells, reachableCells;
        public String spawnProblem;
        public int[] spawnCell;
        public final List<AnchorResult> anchors = new ArrayList<>();
        public final List<PortalResult> portals = new ArrayList<>();
        public final List<Floating> floating = new ArrayList<>();
        public int floatingComponents, decorativeFloating;
        public long mainMass;
        public final List<Region> unreachableRegions = new ArrayList<>();
        public int unreachableRegionCount;
        public long millis;
        /** Every standable cell (Pack.key -> floor height in 1/16) and the reachable subset. */
        public LongIntMap stand;
        public LongSet reachable;

        public List<AnchorResult> failed() {
            List<AnchorResult> out = new ArrayList<>();
            for (AnchorResult a : anchors) if (a.status == Status.UNREACHABLE || a.status == Status.INSIDE_SOLID) out.add(a);
            return out;
        }

        public List<PortalResult> failedPortals() {
            List<PortalResult> out = new ArrayList<>();
            for (PortalResult p : portals) if (!p.reachable) out.add(p);
            return out;
        }

        /** True when spawn exists and every checked anchor and portal is reachable. */
        public boolean ok() { return spawnProblem == null && failed().isEmpty() && failedPortals().isEmpty(); }

        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("WALKABILITY  ").append(ok() ? "OK" : "PROBLEMS").append(String.format(Locale.ROOT, "  (%d ms)%n", millis));
            if (spawnProblem != null) sb.append("  !! ").append(spawnProblem).append('\n');
            sb.append(String.format(Locale.ROOT, "  standable cells: %,d   reachable from spawn: %,d (%.1f%%)%n", standCells, reachableCells,
                    standCells == 0 ? 0.0 : 100.0 * reachableCells / standCells));
            sb.append("  anchors:\n");
            for (AnchorResult a : anchors) {
                String mark = switch (a.status) {
                    case REACHABLE -> "  ok   ";
                    case NEAR -> "  near ";
                    case SKIPPED -> "  skip ";
                    case UNREACHABLE -> "  FAIL ";
                    case INSIDE_SOLID -> "  SOLID";
                };
                sb.append(String.format(Locale.ROOT, "  %s %-34s (%.1f, %.1f, %.1f)%s%n", mark, a.name, a.x, a.y, a.z,
                        a.detail == null ? "" : "  " + a.detail));
            }
            if (!portals.isEmpty()) {
                sb.append("  portal areas:\n");
                for (PortalResult p : portals)
                    sb.append(String.format(Locale.ROOT, "  %s %-34s %s%n", p.reachable ? "  ok   " : "  FAIL ", p.name, boxStr(p.box)));
            }
            if (unreachableRegionCount > 0) {
                sb.append(String.format(Locale.ROOT, "  cut-off standable regions: %d (largest first; rooftops are normal, plazas are not)%n", unreachableRegionCount));
                for (Region r : unreachableRegions) sb.append(String.format(Locale.ROOT, "      %,7d cells  %s%n", r.size, boxStr(r.box)));
            }
            sb.append(String.format(Locale.ROOT, "  floating components: %d (main mass %,d blocks; %d are only leaves/vines/lanterns)%n",
                    floatingComponents, mainMass, decorativeFloating));
            for (Floating f : floating)
                sb.append(String.format(Locale.ROOT, "      %s%,7d blocks  %s  e.g. %s%n", f.size <= 8 ? "SMALL " : "      ", f.size, boxStr(f.box), f.sample));
            return sb.toString();
        }
    }

    static String boxStr(Box b) {
        return "[" + b.minX() + "," + b.minY() + "," + b.minZ() + " -> " + b.maxX() + "," + b.maxY() + "," + b.maxZ() + "]";
    }

    // =======================================================================================

    private static final int NONE = LongIntMap.MISSING;

    private final static class Ctx {
        final MemoryCanvas c;
        final int fallMax;
        int[] top;
        boolean[] water, lava, climb;
        LongIntMap stand;

        Ctx(MemoryCanvas c, int fallMax) {
            this.c = c; this.fallMax = fallMax;
            int n = c.paletteSize();
            top = new int[n]; water = new boolean[n]; lava = new boolean[n]; climb = new boolean[n];
            for (int i = 1; i < n; i++) {
                BlockInfo b = c.info(i);
                top[i] = b.top16; water[i] = b.water; lava[i] = b.lava; climb[i] = b.climb;
            }
        }

        int id(int x, int y, int z) { return c.getId(x, y, z); }

        boolean pass(int id) { return top[id] == 0 && !lava[id]; }

        int floorOf(int x, int y, int z) {
            int id = id(x, y, z);
            int base;
            if (climb[id]) base = y * 16;
            else if (top[id] >= 1 && top[id] <= 15) base = y * 16 + top[id];
            else if (pass(id)) {
                int below = id(x, y - 1, z);
                int tb = top[below];
                if (tb == 16) base = y * 16;
                else if (tb == 24) base = y * 16 + 8;
                else if (water[below] && !water[id]) base = y * 16;
                else return NONE;
            } else return NONE;
            int topCell = Math.floorDiv(base + 28, 16);
            for (int yy = y + 1; yy <= topCell; yy++) if (!pass(id(x, yy, z))) return NONE;
            return base;
        }

        interface Out { void accept(long key); }

        void neighbours(long key, int fa, Out out) {
            int x = Pack.x(key), y = Pack.y(key), z = Pack.z(key);
            for (int d = 0; d < 4; d++) {
                int nx = x + Blocks.DX[d], nz = z + Blocks.DZ[d];
                for (int yb = y + 2; yb >= y - fallMax - 1; yb--) {
                    long nk = Pack.key(nx, yb, nz);
                    int fb = stand.get(nk);
                    if (fb == NONE) continue;
                    int rise = fb - fa;
                    if (rise > 19 || -rise > fallMax * 16 + 8) continue;
                    boolean ok = true;
                    if (rise > 0) {
                        int hiCell = Math.floorDiv(fb + 28, 16);
                        for (int yy = Math.floorDiv(fa, 16) + 1; yy <= hiCell && ok; yy++) if (!pass(id(x, yy, z))) ok = false;
                    }
                    if (!ok) continue;
                    int hiT = Math.floorDiv(Math.max(fa, fb) + 28, 16);
                    for (int yy = Math.floorDiv(fb, 16) + 1; yy <= hiT && ok; yy++) if (!pass(id(nx, yy, nz))) ok = false;
                    if (ok) out.accept(nk);
                }
            }
            int here = id(x, y, z);
            long up = Pack.key(x, y + 1, z), dn = Pack.key(x, y - 1, z);
            if (climb[here] && stand.get(up) != NONE) out.accept(up);
            if (stand.get(dn) != NONE && climb[id(x, y - 1, z)]) out.accept(dn);
        }
    }

    public static Report analyse(MemoryCanvas c, Layout layout, Options o) {
        long t0 = System.currentTimeMillis();
        Report r = new Report();
        Ctx ctx = new Ctx(c, o.fallMax);
        // ---- 1. standable cells ----
        LongIntMap stand = new LongIntMap((int) Math.min(50_000_000, Math.max(1024, c.written() / 6)));
        ctx.stand = stand;
        c.forEachId((x, y, z, id) -> {
            int t = ctx.top[id];
            if (t >= 1 && t <= 15 || ctx.climb[id]) {
                long k = Pack.key(x, y, z);
                if (stand.get(k) == NONE) { int f = ctx.floorOf(x, y, z); if (f != NONE) stand.put(k, f); }
            }
            if (t == 16 || t == 24 || ctx.water[id]) {
                long k = Pack.key(x, y + 1, z);
                if (stand.get(k) == NONE) { int f = ctx.floorOf(x, y + 1, z); if (f != NONE) stand.put(k, f); }
            }
        });
        r.stand = stand;
        r.standCells = stand.size();
        // ---- 2. flood from spawn ----
        LongSet reach = new LongSet(Math.max(1024, stand.size()));
        r.reachable = reach;
        Layout.Anchor spawn = layout.anchor("spawn");
        long start = Long.MIN_VALUE;
        if (spawn == null) r.spawnProblem = "no 'spawn' anchor";
        else {
            start = locate(stand, spawn.x(), spawn.y(), spawn.z());
            if (start == Long.MIN_VALUE) r.spawnProblem = String.format(Locale.ROOT, "spawn (%.1f, %.1f, %.1f) is not on standable ground", spawn.x(), spawn.y(), spawn.z());
        }
        if (start != Long.MIN_VALUE) {
            r.spawnCell = new int[]{Pack.x(start), Pack.y(start), Pack.z(start)};
            LongQueue q = new LongQueue();
            reach.add(start); q.add(start);
            while (!q.isEmpty()) {
                long k = q.poll();
                ctx.neighbours(k, stand.get(k), nk -> { if (reach.add(nk)) q.add(nk); });
            }
        }
        r.reachableCells = reach.size();
        // ---- 3. anchors ----
        Set<String> ignore = new HashSet<>(o.ignore);
        String extra = layout.data().get("walk.ignore");
        if (extra != null) for (String s : extra.split(",")) if (!s.isBlank()) ignore.add(s.trim());
        for (Layout.Anchor a : layout.anchors().values()) r.anchors.add(checkAnchor(ctx, reach, a, ignore, o));
        // ---- 4. portals ----
        for (var e : layout.areas().entrySet()) {
            if (!e.getKey().startsWith("portal.")) continue;
            Box b = e.getValue();
            boolean ok = false;
            outer:
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int z = b.minZ(); z <= b.maxZ(); z++)
                    for (int y = b.minY() - 1; y <= b.maxY(); y++)
                        if (reach.contains(Pack.key(x, y, z))) { ok = true; break outer; }
            r.portals.add(new PortalResult(e.getKey(), b, ok));
        }
        // ---- 5. cut-off regions ----
        cutOff(ctx, r);
        // ---- 6. floating blocks ----
        if (!o.skipFloating) floating(c, r);
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }

    /** The standable cell for a point: its own column, feet cell first, then one below/above. */
    static long locate(LongIntMap stand, double x, double y, double z) {
        int fx = (int) Math.floor(x), fy = (int) Math.floor(y + 0.01), fz = (int) Math.floor(z);
        for (int dy : new int[]{0, -1, 1, -2}) {
            long k = Pack.key(fx, fy + dy, fz);
            if (stand.get(k) != NONE) return k;
        }
        return Long.MIN_VALUE;
    }

    private static AnchorResult checkAnchor(Ctx ctx, LongSet reach, Layout.Anchor a, Set<String> ignore, Options o) {
        for (String p : ignore) if (a.name().startsWith(p)) return new AnchorResult(a.name(), a.x(), a.y(), a.z(), Status.SKIPPED, null);
        int fx = (int) Math.floor(a.x()), fy = (int) Math.floor(a.y() + 0.01), fz = (int) Math.floor(a.z());
        int feet = ctx.id(fx, fy, fz), head = ctx.id(fx, fy + 1, fz);
        int tf = ctx.top[feet], th = ctx.top[head];
        double inCell = a.y() - fy;
        if (tf == 16 || tf == 24 || tf == -1 || tf > 2 && tf / 16.0 > inCell + 0.15 || th == 16 || th == 24 || ctx.lava[feet]) {
            String what = (tf == 16 || tf == 24 || tf == -1 || tf > 0) ? ctx.c.palette(feet) : ctx.c.palette(head);
            return new AnchorResult(a.name(), a.x(), a.y(), a.z(), Status.INSIDE_SOLID, "inside " + what);
        }
        for (int dy : new int[]{0, -1, 1, -2})
            if (reach.contains(Pack.key(fx, fy + dy, fz))) return new AnchorResult(a.name(), a.x(), a.y(), a.z(), Status.REACHABLE, null);
        int best = Integer.MAX_VALUE;
        for (int dz = -o.nearRadius; dz <= o.nearRadius; dz++)
            for (int dx = -o.nearRadius; dx <= o.nearRadius; dx++)
                for (int dy = -2; dy <= 1; dy++)
                    if (reach.contains(Pack.key(fx + dx, fy + dy, fz + dz))) best = Math.min(best, Math.max(Math.abs(dx), Math.abs(dz)));
        if (best != Integer.MAX_VALUE)
            return new AnchorResult(a.name(), a.x(), a.y(), a.z(), Status.NEAR, "own cell not walkable; reachable " + best + " block(s) away");
        boolean standable = locate(ctx.stand, a.x(), a.y(), a.z()) != Long.MIN_VALUE;
        return new AnchorResult(a.name(), a.x(), a.y(), a.z(), Status.UNREACHABLE,
                standable ? "standable but cut off from spawn" : "no floor here (standing on air / no head room)");
    }

    private static void cutOff(Ctx ctx, Report r) {
        LongSet seen = new LongSet(1024);
        List<Region> regions = new ArrayList<>();
        ctx.stand.forEach((k, f) -> {
            if (r.reachable.contains(k) || seen.contains(k) || onTree(ctx.c, k)) return;
            LongQueue q = new LongQueue();
            seen.add(k); q.add(k);
            int n = 0;
            int[] bb = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            while (!q.isEmpty()) {
                long cur = q.poll();
                n++;
                grow(bb, Pack.x(cur), Pack.y(cur), Pack.z(cur));
                ctx.neighbours(cur, ctx.stand.get(cur), nk -> {
                    if (!r.reachable.contains(nk) && seen.add(nk)) q.add(nk);
                });
            }
            regions.add(new Region(n, new Box(bb[0], bb[1], bb[2], bb[3], bb[4], bb[5])));
        });
        regions.sort(Comparator.comparingInt(Region::size).reversed());
        r.unreachableRegionCount = regions.size();
        for (int i = 0; i < Math.min(12, regions.size()); i++) r.unreachableRegions.add(regions.get(i));
    }

    /** A cell whose floor is a tree (leaves or log): canopy tops are standable but nobody cares that they are cut off. */
    public static boolean onTree(MemoryCanvas c, long k) {
        String n = BlockState.name(c.palette(c.getId(Pack.x(k), Pack.y(k) - 1, Pack.z(k))));
        return n.endsWith("_leaves") || n.endsWith("_log") || n.endsWith("mushroom_block");
    }

    private static void grow(int[] bb, int x, int y, int z) {
        if (x < bb[0]) bb[0] = x; if (y < bb[1]) bb[1] = y; if (z < bb[2]) bb[2] = z;
        if (x > bb[3]) bb[3] = x; if (y > bb[4]) bb[4] = y; if (z > bb[5]) bb[5] = z;
    }

    // ---- floating components ---------------------------------------------------------------

    private static void floating(MemoryCanvas c, Report r) {
        int n = c.paletteSize();
        boolean[] solid = new boolean[n], deco = new boolean[n];
        for (int i = 1; i < n; i++) {
            BlockInfo b = c.info(i);
            solid[i] = !b.isAir() && !b.invisible;
            deco[i] = b.floatOk;
        }
        Visited vis = new Visited();
        List<Floating> comps = new ArrayList<>();
        long[] biggest = {0};
        int[] ddx = {1, -1, 0, 0, 0, 0}, ddy = {0, 0, 1, -1, 0, 0}, ddz = {0, 0, 0, 0, 1, -1};
        c.forEachId((x, y, z, id) -> {
            if (!solid[id] || vis.test(x, y, z)) return;
            LongQueue q = new LongQueue();
            vis.set(x, y, z);
            q.add(Pack.key(x, y, z));
            int size = 0, real = 0;
            int[] bb = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            String sample = null;
            while (!q.isEmpty()) {
                long k = q.poll();
                int px = Pack.x(k), py = Pack.y(k), pz = Pack.z(k);
                int pid = c.getId(px, py, pz);
                size++;
                if (!deco[pid]) { real++; if (sample == null) sample = c.palette(pid) + " @ " + px + "," + py + "," + pz; }
                if (size <= 200_000) grow(bb, px, py, pz);
                for (int d = 0; d < 6; d++) {
                    int nx = px + ddx[d], ny = py + ddy[d], nz = pz + ddz[d];
                    int nid = c.getId(nx, ny, nz);
                    if (nid == 0 || !solid[nid] || vis.test(nx, ny, nz)) continue;
                    vis.set(nx, ny, nz);
                    q.add(Pack.key(nx, ny, nz));
                }
            }
            if (sample == null) sample = c.palette(id) + " @ " + x + "," + y + "," + z;
            if (size > biggest[0]) biggest[0] = size;
            comps.add(new Floating(size, new Box(bb[0], bb[1], bb[2], bb[3], bb[4], bb[5]), sample, real == 0));
        });
        comps.sort(Comparator.comparingInt(Floating::size).reversed());
        if (comps.isEmpty()) return;
        r.mainMass = comps.get(0).size();
        int listed = 0;
        for (int i = 1; i < comps.size(); i++) {
            Floating f = comps.get(i);
            r.floatingComponents++;
            if (f.decorative()) { r.decorativeFloating++; continue; }
            if (listed++ < 25) r.floating.add(f);
        }
    }

    /** One bit per cell, per 16^3 section, in an open-addressed table. */
    private static final class Visited {
        private long[] keys = new long[1024];
        private long[][] bits = new long[1024][];
        private int size;
        private long lastK = Long.MIN_VALUE;
        private long[] lastB;

        Visited() { Arrays.fill(keys, Long.MIN_VALUE); }

        private static long sk(int x, int y, int z) { return Pack.key(x >> 4, y >> 4, z >> 4); }

        private long[] get(long k, boolean create) {
            if (k == lastK) return lastB;
            int mask = keys.length - 1;
            int i = (int) (Hash.mix(k) & mask);
            while (keys[i] != Long.MIN_VALUE) {
                if (keys[i] == k) { lastK = k; lastB = bits[i]; return lastB; }
                i = (i + 1) & mask;
            }
            if (!create) return null;
            if (size * 2 >= keys.length) { rehash(); return get(k, true); }
            keys[i] = k; bits[i] = new long[64]; size++;
            lastK = k; lastB = bits[i];
            return lastB;
        }

        private void rehash() {
            long[] ok = keys; long[][] ob = bits;
            keys = new long[ok.length * 2]; bits = new long[ok.length * 2][];
            Arrays.fill(keys, Long.MIN_VALUE);
            size = 0; lastK = Long.MIN_VALUE; lastB = null;
            int mask = keys.length - 1;
            for (int j = 0; j < ok.length; j++) {
                if (ok[j] == Long.MIN_VALUE) continue;
                int i = (int) (Hash.mix(ok[j]) & mask);
                while (keys[i] != Long.MIN_VALUE) i = (i + 1) & mask;
                keys[i] = ok[j]; bits[i] = ob[j]; size++;
            }
        }

        boolean test(int x, int y, int z) {
            long[] b = get(sk(x, y, z), false);
            if (b == null) return false;
            int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            return (b[i >> 6] & (1L << (i & 63))) != 0;
        }

        void set(int x, int y, int z) {
            long[] b = get(sk(x, y, z), true);
            int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            b[i >> 6] |= 1L << (i & 63);
        }
    }
}
