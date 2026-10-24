package com.skyclone.mod.rift.gen;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Hash;
import com.skyclone.mod.rift.RiftCatalog;
import com.skyclone.mod.rift.RiftEnigma;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.skyclone.mod.rift.gen.RiftGenerator.*;

/**
 * Wave 5: the props every Riddle Soul puzzle needs, placed around the soul the district builders already put
 * down (so a soul keeps its spot and its hint; this pass only adds what its riddle asks for), plus the riddle
 * NPCs, the eight wooden buttons, the nine pieces of the lost cat and the Warden's seven eyes.
 *
 * <p>Anchor names (all under prefixes the walk check skips, except the NPCs, which it checks):
 * {@code soul.N.pot.k}, {@code soul.N.balloon.k}, {@code soul.N.spook}, {@code soul.N.rune.k},
 * {@code soul.N.cake}, {@code soul.N.plate.1/2}, {@code soul.N.beacon}, {@code soul.N.hopper},
 * {@code riddle.button.k}, {@code cat.N}, {@code eye.N}, {@code npc.riddle_<key>}. A prop that finds no room
 * is skipped; the module copes with fewer (a riddle with none of its props falls back to a plain spot).
 * PURE, like the rest of the generator.
 */
final class RiftProps {

    private RiftProps() {}

    static final String[] POTTED = {"potted_poppy", "potted_allium", "potted_blue_orchid", "potted_azure_bluet",
            "potted_cornflower", "potted_oxeye_daisy", "potted_red_tulip", "potted_lily_of_the_valley"};
    static final String[] FACING = {"north", "east", "south", "west"};
    static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};   // north, east, south, west
    static final String WALL_BLOCK = "chiseled_tuff_bricks";
    static final int BUTTONS = 8;

    /** One placement run: road cells and the columns already taken. */
    private static final class Run {
        final RiftCtx g;
        final List<int[]> road = new ArrayList<>();         // x, y, z
        final Set<Long> used = new HashSet<>();
        Run(RiftCtx g) { this.g = g; }
        boolean free(int x, int z) { return !used.contains(key(x, z)); }
        void take(int x, int z) { used.add(key(x, z)); }
        static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    }

    static void place(RiftCtx g) {
        Run run = new Run(g);
        // only road cells a player can stand on (a path's step is a stair, and an NPC on a stair is inside a block)
        g.paths.cells.forEach((k, y) -> {
            int x = (int) (k >> 32), z = (int) k;
            int gy = standable(g, x, z, y, 0);
            if (gy != Integer.MIN_VALUE && g.h.has(x, z) && g.h.inland(x, z, 2)) run.road.add(new int[]{x, gy, z});
        });
        run.road.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[2], b[2]));
        for (Layout.Anchor a : g.l.anchors().values()) run.take((int) Math.floor(a.x()), (int) Math.floor(a.z()));

        Set<String> npcs = new HashSet<>();
        for (int n = 1; n <= RiftCatalog.totalSouls(); n++) {
            Layout.Anchor a = g.l.anchor("soul." + n);
            RiftEnigma.Riddle r = RiftEnigma.riddle(g.l.data().get("soul." + n + ".riddle"));
            if (a == null || r == null) continue;
            int sx = (int) Math.floor(a.x()), sy = (int) Math.round(a.y()), sz = (int) Math.floor(a.z());
            String p = "soul." + n;
            switch (r.kind()) {
                case PLATES -> { cage(g, p, sx, sy, sz, "glass"); pair(run, p, sx, sy, sz, "plate", "heavy_weighted_pressure_plate[power=0]", "heavy_weighted_pressure_plate[power=0]"); }
                case WALL -> cage(g, p, sx, sy, sz, WALL_BLOCK);
                case POTS -> pots(run, p, sx, sy, sz, r.count(), n);
                case BALLOONS -> balloons(run, p, sx, sy, sz, r.count(), n);
                case SPOOK -> spook(run, p, sx, sy, sz);
                case RUNES -> runes(run, p, sx, sy, sz, n);
                case CAKE -> cake(run, p, sx, sy, sz);
                case BEACON -> pair(run, p, sx, sy, sz, "beacon", "beacon", "hopper[facing=down,enabled=true]");
                case FALL -> pillar(run, p, sx, sy, sz);
                default -> { }
            }
            if (r.npc() != null && npcs.add(r.npc())) npcNear(run, RiftEnigma.npcAnchor(r.npc()), sx, sy - 1, sz);
        }
        // the lost cat's owner waits at the foot of the Great Tree's road
        int[] t = nearestRoad(run, -42, 58, 30);
        if (t != null) npcNear(run, RiftEnigma.npcAnchor("maribel"), t[0], t[1], t[2]);
        buttons(run);
        cats(run);
        eyes(run);
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    /** Ground block y at (x, z) within {@code tol} of {@code level} with two blocks of air above it, else MIN_VALUE. */
    private static int standable(RiftCtx g, int x, int z, int level, int tol) {
        int gy = com.skyclone.gen.Builders.ground(g.c, x, z, level + tol, level - tol);   // NOT g.groundBelow: it answers BASE for an empty column
        if (gy == Integer.MIN_VALUE || gy < level - tol || gy > level + tol) return Integer.MIN_VALUE;
        if (!g.c.isAir(x, gy + 1, z) || !g.c.isAir(x, gy + 2, z)) return Integer.MIN_VALUE;
        return gy;
    }

    private static int[] nearestRoad(Run run, int x, int z, int maxDist) {
        int[] best = null;
        double bd = maxDist;
        for (int[] c : run.road) {
            double d = Math.hypot(c[0] - x, c[2] - z);
            if (d < bd && run.free(c[0], c[2])) { bd = d; best = c; }
        }
        return best;
    }

    /** The road cell whose distance to (x, z) is nearest {@code want}, within [lo, hi]. */
    private static int[] roadAtDistance(Run run, int x, int z, double want, double lo, double hi) {
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int[] c : run.road) {
            double d = Math.hypot(c[0] - x, c[2] - z);
            if (d < lo || d > hi || !run.free(c[0], c[2])) continue;
            double e = Math.abs(d - want);
            if (e < bd) { bd = e; best = c; }
        }
        return best;
    }

    /** A free standable spot within [rMin, rMax] of (x, z) near ground level gy: {x, y, z} or null. */
    private static int[] ring(Run run, int x, int gy, int z, int rMin, int rMax, int tol) {
        for (int r = rMin; r <= rMax; r++)
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                int px = x + (int) Math.round(Math.cos(a) * r), pz = z + (int) Math.round(Math.sin(a) * r);
                if (!run.free(px, pz) || run.g.paths.covers(px, pz)) continue;
                int py = standable(run.g, px, pz, gy, tol);
                if (py != Integer.MIN_VALUE) return new int[]{px, py, pz};
            }
        return null;
    }

    private static float yawTowards(int x, int z, int tx, int tz) {
        return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
    }

    // =========================================================================================
    // riddle props
    // =========================================================================================

    /** A shell around the soul (feet and head ring, then a roof) in {@code block}, only where it is air. */
    private static void cage(RiftCtx g, String p, int sx, int sy, int sz, String block) {
        for (int dy = 0; dy <= 2; dy++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    if (dy < 2 && dx == 0 && dz == 0) continue;
                    if (g.c.isAir(sx + dx, sy + dy, sz + dz)) g.c.set(sx + dx, sy + dy, sz + dz, block);
                }
        g.l.data(p + ".cage", sx + "," + sy + "," + sz);
    }

    /** Two blocks either side of the soul (plates, or the beacon and the hopper). */
    private static void pair(Run run, String p, int sx, int sy, int sz, String what, String first, String second) {
        int gy = sy - 1;
        for (int axis = 0; axis < 2; axis++) {
            for (int r = 3; r <= 5; r++) {
                int ax = axis == 0 ? r : 0, az = axis == 0 ? 0 : r;
                int y1 = run.free(sx + ax, sz + az) ? standable(run.g, sx + ax, sz + az, gy, 1) : Integer.MIN_VALUE;
                int y2 = run.free(sx - ax, sz - az) ? standable(run.g, sx - ax, sz - az, gy, 1) : Integer.MIN_VALUE;
                if (y1 == Integer.MIN_VALUE || y2 == Integer.MIN_VALUE) continue;
                put(run, p + "." + (what.equals("plate") ? "plate.1" : "beacon"), sx + ax, y1, sz + az, first);
                put(run, p + "." + (what.equals("plate") ? "plate.2" : "hopper"), sx - ax, y2, sz - az, second);
                return;
            }
        }
    }

    /** Set a prop block on top of ground block (x, y, z) and anchor it (the anchor's y is the prop's block). */
    private static void put(Run run, String anchor, int x, int gy, int z, String block) {
        run.g.c.set(x, gy + 1, z, block);
        run.g.l.standAt(anchor, x, gy, z, 0f);
        run.take(x, z);
    }

    /** A trail of flower pots from a road towards the soul; pot 1 is the farthest. */
    private static void pots(Run run, String p, int sx, int sy, int sz, int count, int n) {
        RiftCtx g = run.g;
        int gy = sy - 1;
        int[] start = roadAtDistance(run, sx, sz, 24, 14, 34);
        int ax, ay, az;
        if (start != null) { ax = start[0]; ay = start[1]; az = start[2]; }
        else { ax = sx + 14; ay = gy; az = sz; }
        int placed = 0;
        for (int k = 0; k < count; k++) {
            double t = count == 1 ? 0.8 : 0.86 * k / (count - 1);
            int px = (int) Math.round(ax + (sx - ax) * t), pz = (int) Math.round(az + (sz - az) * t);
            int ly = (int) Math.round(ay + (gy - ay) * t);
            int[] spot = null;
            for (int r = 0; r <= 3 && spot == null; r++)
                for (int i = 0; i < (r == 0 ? 1 : 8) && spot == null; i++) {
                    int qx = px + (r == 0 ? 0 : (int) Math.round(Math.cos(i * Math.PI / 4) * r));
                    int qz = pz + (r == 0 ? 0 : (int) Math.round(Math.sin(i * Math.PI / 4) * r));
                    if (!run.free(qx, qz) || (qx == sx && qz == sz) || g.paths.covers(qx, qz)) continue;
                    int qy = standable(g, qx, qz, ly, 4);
                    if (qy != Integer.MIN_VALUE && (!com.skyclone.gen.Builders.natural(g.c.get(qx, qy, qz))
                            || com.skyclone.gen.BlockState.name(g.c.get(qx, qy, qz)).equals("dirt_path"))) qy = Integer.MIN_VALUE;
                    if (qy != Integer.MIN_VALUE) spot = new int[]{qx, qy, qz};
                }
            if (spot == null) continue;
            placed++;
            put(run, p + ".pot." + placed, spot[0], spot[1], spot[2], POTTED[(n + k) % POTTED.length]);
        }
        g.l.data(p + ".pots", String.valueOf(placed));
    }

    /** Balloons in the air around the soul: anchors only (the module draws them per player). */
    private static void balloons(Run run, String p, int sx, int sy, int sz, int count, int n) {
        RiftCtx g = run.g;
        int placed = 0;
        for (int k = 0; k < count; k++) {
            double a = k * Math.PI * 2 / count + Hash.unit(g.seed, n, k, 71) * 0.6;
            for (int tryR = 0; tryR < 4; tryR++) {
                double r = 7 + Hash.unit(g.seed, n, k, 73) * 5 - tryR * 1.5;
                int bx = sx + (int) Math.round(Math.cos(a) * r), bz = sz + (int) Math.round(Math.sin(a) * r);
                int top = g.ground(bx, bz);
                int by = Math.max(top + 1, sy) + 4 + (k % 3) * 2;
                if (!g.c.isAir(bx, by, bz) || !g.c.isAir(bx, by + 1, bz) || !g.c.isAir(bx, by - 1, bz)) continue;
                placed++;
                g.l.anchor(p + ".balloon." + placed, bx + 0.5, by, bz + 0.5, 0f);
                break;
            }
        }
        g.l.data(p + ".balloons", String.valueOf(placed));
    }

    /** Where the Flitwraith waits: a road cell ~32 blocks away (always reachable). */
    private static void spook(Run run, String p, int sx, int sy, int sz) {
        int[] s = roadAtDistance(run, sx, sz, 32, 20, 46);
        if (s == null) s = ring(run, sx, sy - 1, sz, 14, 22, 6);
        if (s == null) return;
        run.g.l.standAt(p + ".spook", s[0], s[1], s[2], 0f);
        run.take(s[0], s[2]);
    }

    /** Four rune stones around the soul, each turned away from it (the puzzle turns them back). */
    private static void runes(Run run, String p, int sx, int sy, int sz, int n) {
        int gy = sy - 1, k = 0;
        for (int d = 0; d < 4; d++)
            for (int r = 3; r <= 6; r++) {
                int x = sx + DIRS[d][0] * r, z = sz + DIRS[d][1] * r;
                if (!run.free(x, z)) continue;
                int y = standable(run.g, x, z, gy, 2);
                if (y == Integer.MIN_VALUE) continue;
                int target = RiftEnigma.facingTowards(x, z, sx, sz);
                int start = (target + 1 + (int) (Hash.unit(run.g.seed, n, d, 79) * 3)) % 4;
                k++;
                put(run, p + ".rune." + k, x, y, z, "purple_glazed_terracotta[facing=" + FACING[start] + "]");
                break;
            }
    }

    private static void cake(Run run, String p, int sx, int sy, int sz) {
        int[] s = ring(run, sx, sy - 1, sz, 1, 3, 1);
        if (s != null) put(run, p + ".cake", s[0], s[1], s[2], "cake[bites=0]");
    }

    /** Leap of Faith: a ten-high pillar with a ladder two blocks from the soul, to jump off. */
    private static void pillar(Run run, String p, int sx, int sy, int sz) {
        RiftCtx g = run.g;
        int gy = sy - 1;
        for (int d = 0; d < 4; d++)
            for (int r = 2; r <= 4; r++) {
                int x = sx + DIRS[d][0] * r, z = sz + DIRS[d][1] * r;
                int lx = x + DIRS[d][0], lz = z + DIRS[d][1];
                if (!run.free(x, z) || !run.free(lx, lz) || g.paths.covers(x, z) || g.paths.covers(lx, lz)) continue;
                int y = standable(g, x, z, gy, 1), ly = standable(g, lx, lz, gy, 1);
                if (y == Integer.MIN_VALUE || ly != y) continue;
                boolean clear = true;
                for (int k = 1; k <= 12 && clear; k++) clear = g.c.isAir(x, y + k, z) && g.c.isAir(lx, y + k, lz);
                if (!clear) continue;
                for (int k = 1; k <= 10; k++) {
                    g.c.set(x, y + k, z, k % 4 == 0 ? "chiseled_stone_bricks" : "mossy_stone_bricks");
                    g.c.set(lx, y + k, lz, "ladder[facing=" + FACING[d] + ",waterlogged=false]");
                }
                g.l.data(p + ".pillar", x + "," + (y + 11) + "," + z);
                run.take(x, z);
                run.take(lx, lz);
                return;
            }
    }

    /** A riddle NPC: beside the soul when there is room, else on the nearest road, else on the soul's spot. */
    private static void npcNear(Run run, String anchor, int sx, int gy, int sz) {
        int[] s = ring(run, sx, gy, sz, 2, 6, 1);
        if (s == null) s = nearestRoad(run, sx, sz, 30);
        if (s == null) s = new int[]{sx, gy, sz};
        run.g.stand(anchor, s[0], s[1], s[2], yawTowards(s[0], s[2], sx, sz));
        run.take(s[0], s[2]);
    }

    // =========================================================================================
    // buttons, the cat's pieces, the eyes
    // =========================================================================================

    /** Eight wooden buttons beside the roads: four in the West Village, four in Dread Acres. */
    private static void buttons(Run run) {
        RiftCtx g = run.g;
        List<int[]> chosen = new ArrayList<>();
        for (int district = 0; district < 2; district++) {
            List<int[]> cells = new ArrayList<>();
            for (int[] c : run.road) {
                boolean west = c[0] <= -80 && c[2] <= -24;
                boolean dread = c[0] >= -70 && c[0] <= 70 && c[2] <= -70;
                if (district == 0 ? west : dread) cells.add(c);
            }
            cells.sort((a, b) -> Long.compare(Hash.hash(g.seed, a[0], 401, a[2]), Hash.hash(g.seed, b[0], 401, b[2])));
            int got = 0;
            for (int[] c : cells) {
                if (got >= BUTTONS / 2) break;
                boolean near = false;
                for (int[] o : chosen) if (Math.hypot(o[0] - c[0], o[2] - c[2]) < 16) { near = true; break; }
                if (near) continue;
                int[] s = ring(run, c[0], c[1], c[2], 2, 3, 1);
                if (s == null) continue;
                chosen.add(s);
                got++;
                put(run, "riddle.button." + chosen.size(), s[0], s[1], s[2], "oak_button[face=floor,facing=north,powered=false]");
            }
        }
        g.l.data("riddle.buttons", String.valueOf(chosen.size()));
    }

    private static void hidden(Run run, String anchor, int[] near, int tol) {
        if (near == null) return;
        int[] s = ring(run, near[0], near[1], near[2], 2, 4, tol);
        if (s == null) s = near;
        run.g.l.standAt(anchor, s[0], s[1], s[2], 0f);
        run.take(s[0], s[2]);
    }

    private static int[] roadNear(Run run, int x, int z) {
        int[] r = nearestRoad(run, x, z, 40);
        if (r != null) return r;
        int gy = run.g.ground(x, z);                     // no road near: open ground at the spot itself
        return ring(run, x, gy, z, 0, 8, 3);
    }

    /** The lost cat's nine pieces (RiftEnigma.CAT_PIECES): beside roads in their districts, one in the cave. */
    private static void cats(Run run) {
        RiftCtx g = run.g;
        int[][] at = {
                {-20, 120}, {GALLERY[0] - 10, GALLERY[1] + 7}, {HUT[0] + 8, HUT[1] + 10}, {WEST[0] + 14, WEST[1] - 10},
                {DREAD[0] + 20, DREAD[1] + 10}, {PLAZA[0] - 14, PLAZA[1] - 12}, {PLAZA[0] + 34, PLAZA[1] + 10}, null, {92, 132}};
        for (int i = 0; i < at.length; i++) {
            String a = "cat." + (i + 1);
            if (at[i] == null) {
                // under the frost of the Living Stillness: beside the cave's back soul
                Layout.Anchor cave = null;
                for (int n = 1; n <= RiftCatalog.totalSouls(); n++)
                    if ("rift_cave_flowers".equals(g.l.data().get("soul." + n + ".riddle"))) cave = g.l.anchor("soul." + n);
                if (cave != null) {
                    int cx = (int) Math.floor(cave.x()), cy = (int) Math.round(cave.y()) - 1, cz = (int) Math.floor(cave.z());
                    int[] s = ring(run, cx, cy, cz, 2, 6, 2);
                    hidden(run, a, s != null ? s : new int[]{cx, cy, cz}, 0);
                }
                if (g.l.anchor(a) == null) hidden(run, a, roadNear(run, LIVING[0] - LIVING_R - 3, LIVING[1]), 1);
            } else hidden(run, a, roadNear(run, at[i][0], at[i][1]), 1);
            RiftEnigma.Hidden h = RiftEnigma.CAT_PIECES.get(i);
            g.l.data(a + ".hint", h.hint());
        }
        // "between the pages of a book": a lectern beside piece six
        Layout.Anchor six = g.l.anchor("cat.6");
        if (six != null) {
            int x = (int) Math.floor(six.x()) + 1, y = (int) Math.round(six.y()), z = (int) Math.floor(six.z());
            if (g.c.isAir(x, y, z) && !g.c.isAir(x, y - 1, z) && run.free(x, z) && !g.paths.covers(x, z)) g.c.set(x, y, z, "lectern[facing=west,has_book=true,powered=false]");
        }
    }

    /** The Warden's seven eyes (RiftEnigma.EYES): beside the roads, so a fast-travel landing is always walkable. */
    private static void eyes(Run run) {
        RiftCtx g = run.g;
        int[] summit = null;
        String end = g.l.data().get("mountain.road.end");
        if (end != null) {
            String[] xz = end.split(",");
            summit = new int[]{Integer.parseInt(xz[0]), Integer.parseInt(xz[1])};
        }
        int[][] at = {{TOWER[0] + 9, TOWER[1]}, {HUT[0] + 4, HUT[1] + 6}, {DREAD[0], DREAD[1] + 26}, {PLAZA[0] - 18, PLAZA[1]},
                {COLOSSEUM[0], COLOSSEUM[1] - 36}, {CHATEAU[0] - 30, CHATEAU[1]}, summit};
        for (int i = 0; i < at.length; i++) {
            if (at[i] == null) continue;
            int[] r = roadNear(run, at[i][0], at[i][1]);
            if (r == null) continue;
            String a = "eye." + (i + 1);
            int[] s = ring(run, r[0], r[1], r[2], 1, 3, 1);
            if (s == null) s = r;
            g.l.standAt(a, s[0], s[1], s[2], 0f);
            run.take(s[0], s[2]);
            g.l.data(a + ".hint", RiftEnigma.EYES.get(i).hint());
        }
    }
}
