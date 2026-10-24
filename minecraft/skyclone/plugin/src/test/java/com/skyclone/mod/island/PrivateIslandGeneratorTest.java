package com.skyclone.mod.island;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.island.gen.IslandBridge;
import com.skyclone.mod.island.gen.PrivateIslandGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped generator, offline: walkable, anchored, inside its bounds, deterministic. */
class PrivateIslandGeneratorTest {

    private static IslandRun.Result run(long seed) { return IslandRun.run(new PrivateIslandGenerator(), seed); }

    @Test
    void defaultSeedIsWalkableAndComplete() {
        IslandRun.Result r = run(IslandRun.seedOf("private_island"));
        Walkability.Report w = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
        assertTrue(w.ok(), () -> "unreachable: " + w.failed() + " portals " + w.failedPortals() + " spawn " + w.spawnProblem);
        assertEquals(0, r.outsideBounds, () -> "outside bounds: " + r.outsideSamples);
        assertNull(IslandChecks.layoutProblems(r.layout));
        assertTrue(w.floating.stream().allMatch(Walkability.Floating::decorative), () -> "floating: " + w.floating);
        assertTrue(w.reachableCells > 3000, "a real island, not a ledge: " + w.reachableCells);
    }

    @Test
    void otherSeedsStillWork() {
        for (long seed : new long[]{1L, 42L, 12345L, -7L}) {
            IslandRun.Result r = run(seed);
            Walkability.Report w = Walkability.analyse(r.canvas, r.layout, new Walkability.Options());
            assertTrue(w.ok(), () -> "seed " + seed + ": " + w.failed() + " " + w.failedPortals() + " " + w.spawnProblem);
            assertNull(IslandChecks.layoutProblems(r.layout), "seed " + seed);
        }
    }

    @Test
    void deterministic() {
        Layout a = run(99L).layout, b = run(99L).layout;
        assertEquals(a.anchors(), b.anchors());
        assertEquals(a.areas(), b.areas());
    }

    /** v4: ten destination portals, each a VALID 2x3 obsidian frame (so a lit portal never pops) with an empty opening. */
    @Test
    void portalIsleFramesAreValid() {
        for (long seed : new long[]{IslandRun.seedOf("private_island"), 1L, 42L}) {
            IslandRun.Result r = run(seed);
            assertEquals(10, PrivateIslandGenerator.PORTALS.length);
            assertEquals(14, PrivateIslandGenerator.ALL_PORTALS.length);                // v6: + the Far Court's four
            for (String[] d : PrivateIslandGenerator.ALL_PORTALS) {
                var b = r.layout.area(PrivateIslandGenerator.PORTAL_AREA + d[0]);
                assertTrue(b != null, "seed " + seed + " no area for " + d[0]);
                int z = b.minZ();
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    assertEquals("obsidian", name(r.canvas.get(b.minX() - 1, y, z)), "seed " + seed + " " + d[0] + " west side y" + y);
                    assertEquals("obsidian", name(r.canvas.get(b.maxX() + 1, y, z)), "seed " + seed + " " + d[0] + " east side y" + y);
                    for (int x = b.minX(); x <= b.maxX(); x++)
                        assertTrue(r.canvas.isAir(x, y, z), "seed " + seed + " " + d[0] + " opening blocked at " + x + "," + y);
                }
                for (int x = b.minX(); x <= b.maxX(); x++) {
                    assertEquals("obsidian", name(r.canvas.get(x, b.minY() - 1, z)), "seed " + seed + " " + d[0] + " sill");
                    assertEquals("obsidian", name(r.canvas.get(x, b.maxY() + 1, z)), "seed " + seed + " " + d[0] + " lintel");
                }
            }
        }
    }

    /**
     * v6: the Far Court lies entirely inside the retrofit box that is pasted onto older islands, and nothing of the
     * main island or the Portal Isle's portals is inside it - so a retrofit can only ever fill empty sky west of the isle.
     */
    @Test
    void farCourtFitsItsRetrofitBox() {
        IslandRun.Result r = run(IslandRun.seedOf("private_island"));
        var box = IslandPortals.RETRO_FAR;
        for (String[] d : PrivateIslandGenerator.FAR_PORTALS) {
            var a = r.layout.area(PrivateIslandGenerator.PORTAL_AREA + d[0]);
            assertTrue(box.contains(a.minX(), a.minY(), a.minZ()) && box.contains(a.maxX(), a.maxY(), a.maxZ()), d[0] + " outside RETRO_FAR");
        }
        for (String[] d : PrivateIslandGenerator.PORTALS) {
            var a = r.layout.area(PrivateIslandGenerator.PORTAL_AREA + d[0]);
            assertTrue(!box.contains(a.minX(), a.minY(), a.minZ()), d[0] + " (Portal Isle) inside RETRO_FAR");
        }
        var far = r.layout.area("far_court");
        assertTrue(far != null && box.contains(far.minX(), far.minY(), far.minZ()) && box.contains(far.maxX(), far.maxY(), far.maxZ()));
        int[] br = IslandBridge.decode(r.layout.data().get(IslandBridge.DATA_FAR_COURT));
        assertTrue(br != null && box.containsXZ(br[0], br[2]) && box.containsXZ(br[3], br[5]), "far bridge outside RETRO_FAR");
        // every block the generator writes west of the Portal Isle's coast is inside the box (a retrofit cuts nothing off)
        var b = new PrivateIslandGenerator().bounds();
        int outside = 0;
        for (int x = b.minX(); x <= -72; x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int y = b.minY(); y <= b.maxY(); y++)
                    if (!r.canvas.isAir(x, y, z) && !box.contains(x, y, z)) outside++;
        assertEquals(0, outside, "blocks west of x=-72 outside RETRO_FAR");
    }

    private static String name(String block) {
        if (block == null) return "air";
        String n = block.startsWith("minecraft:") ? block.substring(10) : block;
        int i = n.indexOf('[');
        return i >= 0 ? n.substring(0, i) : n;
    }

    @Test
    void fairySoulsCounted() {
        Layout l = run(IslandRun.seedOf("private_island")).layout;
        long n = l.anchors().keySet().stream().filter(k -> k.startsWith("fairy_soul.")).count();
        assertTrue(n >= 10 && n <= PrivateIslandGenerator.FAIRY_SOULS, "souls: " + n);
        assertEquals(Long.toString(n), l.data().get("fairy_souls"));
    }

    /**
     * v5: walking either bridge in a straight line never puts you over the void or into the rail. The wave-3
     * playtest fell off the Portal Isle bridge twice at a diagonal step, where the old axis-strip deck was ~1.4
     * blocks wide. A player whose centre stays within 1 block of the centre line must always have deck underfoot,
     * no fence or lantern in that cell, and never need more than a half-block step (slabs, not jumps).
     */
    @Test
    void bridgesAreWideOnEveryHeading() {
        for (long seed : new long[]{IslandRun.seedOf("private_island"), 1L, 42L, 12345L, -7L}) {
            IslandRun.Result r = run(seed);
            for (String key : new String[]{IslandBridge.DATA_ISLET, IslandBridge.DATA_PORTAL_ISLE, IslandBridge.DATA_FAR_COURT}) {
                int[] s = IslandBridge.decode(r.layout.data().get(key));
                assertTrue(s != null, "seed " + seed + " no " + key);
                assertNull(bridgeProblem(r.canvas, s), () -> "seed " + seed + " " + key + " " + java.util.Arrays.toString(s));
            }
        }
    }

    /**
     * Islands built before v5 keep their blocks, so {@code IslandBridges} re-lays the old bridge in place. Stage a v4
     * bridge (the new one taken out, Builders.bridge laid at the recorded v4 ends) plus two blocks a player placed,
     * run the pure plan, and the result must walk clean, leave the player's blocks alone, and be a no-op the second
     * time. A fresh v5 island must get no plan at all.
     */
    @Test
    void oldBridgesAreRelaidSafely() {
        IslandRun.Result r = run(IslandRun.seedOf("private_island"));
        for (String key : new String[]{IslandBridge.DATA_ISLET, IslandBridge.DATA_PORTAL_ISLE}) {
            int[] s = IslandBridge.decode(r.layout.data().get(key)), old = IslandBridge.decode(r.layout.data().get(key + IslandBridge.LEGACY));
            assertTrue(s != null && old != null, key);
            com.skyclone.api.world.gen.Box b = IslandBridge.box(s, old);
            assertTrue(!IslandBridge.repairPlan(copy(r.canvas, b), s, old, b).oldPresent(), key + ": a v5 island has no old bridge to repair");

            com.skyclone.gen.MemoryCanvas world = copy(r.canvas, b);
            com.skyclone.gen.MemoryCanvas fresh = new com.skyclone.gen.MemoryCanvas();
            IslandBridge.build(fresh, s);
            each(b, (x, y, z) -> { if (!fresh.isAir(x, y, z) && IslandBridge.PARTS.contains(IslandBridge.name(world.get(x, y, z)))) world.set(x, y, z, "air"); });
            IslandBridge.legacy(world, old);
            assertTrue(bridgeProblem(world, s) != null, key + ": the staged v4 bridge should be the one you fall off");
            // a player's blocks: one in the void beside the bridge, one spruce plank where no old-bridge block ever was
            int mx = (s[0] + s[3]) / 2, mz = (s[2] + s[5]) / 2, my = Math.min(s[1], s[4]) - 2;
            world.set(mx, my, mz, "gold_block");
            int px = Math.min(s[0], s[3]) - 5, pz = Math.min(s[2], s[5]) - 5;
            world.set(px, s[1], pz, "spruce_planks");

            IslandBridge.Plan plan = IslandBridge.repairPlan(world, s, old, b);
            assertTrue(plan.oldPresent() && !plan.cells.isEmpty(), key + ": old bridge found and a plan made");
            for (int i = 0; i < plan.cells.size(); i++) { int[] c = plan.cells.get(i); world.set(c[0], c[1], c[2], plan.states.get(i)); }
            assertNull(bridgeProblem(world, s), key + ": the re-laid bridge walks clean");
            assertEquals("gold_block", IslandBridge.name(world.get(mx, my, mz)), key + ": player block untouched");
            assertEquals("spruce_planks", IslandBridge.name(world.get(px, s[1], pz)), key + ": player plank untouched");
            IslandBridge.Plan again = IslandBridge.repairPlan(world, s, old, b);
            assertTrue(!again.oldPresent() || again.cells.isEmpty(), key + ": a second pass changes nothing (" + again.cells.size() + ")");
        }
    }

    private interface Cell { void at(int x, int y, int z); }

    private static void each(com.skyclone.api.world.gen.Box b, Cell f) {
        for (int y = b.minY(); y <= b.maxY(); y++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int x = b.minX(); x <= b.maxX(); x++) f.at(x, y, z);
    }

    private static com.skyclone.gen.MemoryCanvas copy(com.skyclone.gen.MemoryCanvas from, com.skyclone.api.world.gen.Box b) {
        com.skyclone.gen.MemoryCanvas out = new com.skyclone.gen.MemoryCanvas();
        each(b, (x, y, z) -> { if (!from.isAir(x, y, z)) out.set(x, y, z, from.get(x, y, z)); });
        return out;
    }

    /** Null when the bridge is a clear walk; else what is wrong and where. Package-visible for the legacy check. */
    static String bridgeProblem(com.skyclone.api.world.gen.VoxelCanvas c, int[] s) {
        double ax = s[0] + 0.5, az = s[2] + 0.5, bx = s[3] + 0.5, bz = s[5] + 0.5;
        double len = Math.hypot(bx - ax, bz - az), ux = (bx - ax) / len, uz = (bz - az) / len;
        int ym = Math.min(s[1], s[4]) - 3;
        for (double off = -1.0; off <= 1.0 + 1e-9; off += 0.25) {
            int px0 = Integer.MIN_VALUE, pz0 = 0;
            for (double t = 0.5; t <= len - 0.5 + 1e-9; t += 0.2) {        // the last half block each end is land
                double px = ax + ux * t - uz * off, pz = az + uz * t + ux * off;
                int x = (int) Math.floor(px), z = (int) Math.floor(pz);
                String at = String.format("at t=%.1f off=%.2f block %d,%d", t, off, x, z);
                int top = (int) Math.round(s[1] + (s[4] - s[1]) * t / len) + 2;
                String bad = walkCell(c, x, z, ym, top);
                if (bad != null) return bad + " " + at;
                if (px0 != Integer.MIN_VALUE && (x != px0 || z != pz0)) {
                    double from = surface(c, px0, pz0, ym, top), to = surface(c, x, z, ym, top);
                    boolean ok;
                    if (x != px0 && z != pz0) {
                        // a walker never passes exactly through a corner: one of the two side cells must carry the move
                        ok = false;
                        for (int[] m : new int[][]{{x, pz0}, {px0, z}}) {
                            if (walkCell(c, m[0], m[1], ym, top) != null) continue;
                            double mid = surface(c, m[0], m[1], ym, top);
                            if (Math.abs(mid - from) <= 0.5 + 1e-9 && Math.abs(to - mid) <= 0.5 + 1e-9) ok = true;
                        }
                    } else ok = Math.abs(to - from) <= 0.5 + 1e-9;
                    if (!ok) return "a " + Math.abs(to - from) + "-block step from " + px0 + "," + pz0 + " (" + from + ") to " + to + " " + at;
                }
                px0 = x; pz0 = z;
            }
        }
        return null;
    }

    /** Null when the block column is a deck you can stand on (with head room); else what is there instead. */
    private static String walkCell(com.skyclone.api.world.gen.VoxelCanvas c, int x, int z, int ym, int yM) {
        int y = floorY(c, x, z, ym, yM);
        if (y == Integer.MIN_VALUE) return "void underfoot";
        String n = com.skyclone.gen.BlockInfo.of(c.get(x, y, z)).name;
        if (n.contains("fence") || n.contains("lantern") || n.endsWith("_wall")) return n + " in the walkway";
        for (int a = 1; a <= 2; a++)
            if (!com.skyclone.gen.BlockInfo.of(c.get(x, y + a, z)).passable()) return "no head room under " + c.get(x, y + a, z);
        return null;
    }

    /** The first solid block from {@code yM} down (callers pass the deck height + 2, so what hangs above is not the floor). */
    private static int floorY(com.skyclone.api.world.gen.VoxelCanvas c, int x, int z, int ym, int yM) {
        for (int y = yM; y >= ym; y--) {
            com.skyclone.gen.BlockInfo info = com.skyclone.gen.BlockInfo.of(c.get(x, y, z));
            if (info.passable()) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static double surface(com.skyclone.api.world.gen.VoxelCanvas c, int x, int z, int ym, int yM) {
        int y = floorY(c, x, z, ym, yM);
        return y == Integer.MIN_VALUE ? Double.NaN : y + com.skyclone.gen.BlockInfo.of(c.get(x, y, z)).top16 / 16.0;
    }
}
