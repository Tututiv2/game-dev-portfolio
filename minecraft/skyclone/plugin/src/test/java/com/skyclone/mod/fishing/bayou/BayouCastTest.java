package com.skyclone.mod.fishing.bayou;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.MemoryCanvas;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Casts a bobber, with vanilla FishingHook launch and flight maths, across the generated Backwater
 * Bayou and checks where it comes down. Playtest (wave4-loops, open issue 9): the dock was a long
 * fenced pier whose boardwalk ran straight on from the path out of spawn, so every forward cast hit
 * the deck - 0 bites in 15 casts. This is the measurement that failed.
 */
class BayouCastTest {

    enum Lands { WATER, BLOCK, NOWHERE }

    static boolean DEBUG = Boolean.getBoolean("baycast.debug");
    static MemoryCanvas c;
    static Layout layout;

    @BeforeAll
    static void build() {
        IslandRun.Result r = IslandRun.run(new BayouGenerator(), IslandRun.seedOf("backwater_bayou"));
        c = r.canvas;
        layout = r.layout;
    }

    /** Vanilla FishingHook: launch from the eyes, 0.3 ahead; v = (-sin yaw, -tan pitch, cos yaw) scaled to ~1.1. */
    static Lands cast(double fx, double fy, double fz, float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double dx = -Math.sin(yaw), dz = Math.cos(yaw), dy = Math.max(-5, Math.min(5, -Math.tan(pitch)));
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double k = 0.6 / len + 0.5;
        double vx = dx * k, vy = dy * k, vz = dz * k;
        double x = fx + dx * 0.3, y = fy + 1.62, z = fz + dz * 0.3;
        for (int tick = 0; tick < 400; tick++) {
            if (inWater(x, y, z)) return Lands.WATER;
            vy -= 0.03;
            // move in small steps, axis by axis, like Entity.move against block collision boxes
            int steps = 8;
            boolean onGround = false, hitWall = false;
            for (int s = 0; s < steps; s++) {
                double ny = y + vy / steps;
                if (blocked(x, ny, z)) { if (vy < 0) onGround = true; vy = 0; } else y = ny;
                double nx = x + vx / steps;
                if (blocked(nx, y, z)) { hitWall = true; vx = 0; } else x = nx;
                double nz = z + vz / steps;
                if (blocked(x, y, nz)) { hitWall = true; vz = 0; } else z = nz;
                if (inWater(x, y, z)) return Lands.WATER;
            }
            if (onGround) {
                // resting on a lily pad (or the edge of one, centre over air) above water counts as
                // water - the same rule as Casting.liquidAt
                int bx = fl(x), by = fl(y), bz = fl(z);
                String at = c.get(bx, by, bz);
                if ((at.startsWith("lily_pad") || BlockInfo.of(at).isAir()) && BlockInfo.of(c.get(bx, by - 1, bz)).water) return Lands.WATER;
                if (DEBUG) System.out.println("  landed at " + x + "," + y + "," + z + " on " + c.get(bx, by, bz) + " / " + c.get(bx, by - 1, bz));
                return Lands.BLOCK;
            }
            if (hitWall) { vx = 0; vz = 0; }
            vx *= 0.92; vy *= 0.92; vz *= 0.92;
            if (y < 0) return Lands.NOWHERE;
        }
        return Lands.NOWHERE;
    }

    static int fl(double v) { return (int) Math.floor(v); }

    static boolean inWater(double x, double y, double z) {
        return BlockInfo.of(c.get(fl(x), fl(y), fl(z))).water;
    }

    /** Does a 0.25-wide bobber box with its bottom at (x,y,z) overlap any block's collision height? */
    static boolean blocked(double x, double y, double z) {
        double h = 0.125;
        for (int bx = fl(x - h); bx <= fl(x + h); bx++)
            for (int bz = fl(z - h); bz <= fl(z + h); bz++)
                for (int by = fl(y) - 1; by <= fl(y + 0.25); by++) {
                    BlockInfo i = BlockInfo.of(c.get(bx, by, bz));
                    if (i.isAir() || i.liquid() || i.top16 == 0) continue;
                    double top = by + (i.top16 < 0 ? 1.0 : i.top16 / 16.0);
                    double bottom = by;
                    if (y < top - 1e-6 && y + 0.25 > bottom + 1e-6) return true;
                }
        return false;
    }

    @Test
    void dockAnchorSitsAtTheOpenWestEdge() {
        Layout.Anchor a = layout.anchor("fishing.spot.docks");
        assertNotNull(a, "fishing.spot.docks anchor");
        int x = fl(a.x()), y = fl(a.y()), z = fl(a.z());
        assertEquals(BayouGenerator.DECK + 1, y, "stands on the deck");
        String rail = c.get(x - 1, y, z);
        assertTrue(rail.startsWith("spruce_slab") && rail.contains("type=top"), "a low rail just west, got " + rail);
        for (int d = 2; d <= 12; d++)
            assertTrue(BlockInfo.of(c.get(x - d, BayouGenerator.W, z)).water, "open water " + d + " blocks ahead, got " + c.get(x - d, BayouGenerator.W, z));
    }

    /** The casts a player makes from the end of the walk out of spawn; returns the ones that missed. */
    static List<String> dockMisses() {
        Layout.Anchor a = layout.anchor("fishing.spot.docks");
        Layout.Anchor spawn = layout.anchor("spawn");
        List<String> bad = new ArrayList<>();
        // At the rail (where the walk from spawn stops you): straight ahead and up to 25 degrees
        // either side (the boardwalk is off to the north), from a little up to well down. A step and
        // a half back: level and a little up (looking DOWN from back there lands on the rail top, as
        // it would on any real rail; Casting says so on the action bar).
        for (float dYaw : new float[]{-25, -10, 0, 10, 25})
            for (float pitch : new float[]{-15, 0, 10, 20}) {
                if (pitch == 20 && Math.abs(dYaw) > 10) continue;   // steep AND sideways crosses a block of rail
                Lands l = cast(a.x(), a.y(), a.z(), spawn.yaw() + dYaw, pitch);
                if (l != Lands.WATER) bad.add("at rail yaw " + (spawn.yaw() + dYaw) + " pitch " + pitch + " -> " + l);
            }
        for (float dYaw : new float[]{-25, -10, 0, 10, 25})
            for (float pitch : new float[]{-15, 0}) {
                Lands l = cast(a.x() + 1.5, a.y(), a.z(), spawn.yaw() + dYaw, pitch);
                if (l != Lands.WATER) bad.add("1.5 back yaw " + (spawn.yaw() + dYaw) + " pitch " + pitch + " -> " + l);
            }
        return bad;
    }

    static final int DOCK_CASTS = 28;

    @Test
    void forwardCastsFromTheDockReachWater() {
        List<String> bad = dockMisses();
        if (DEBUG) System.out.println("low rails: " + bad.size() + " of " + DOCK_CASTS + " missed");
        assertTrue(bad.isEmpty(), bad.size() + " of " + DOCK_CASTS + " forward casts missed the water: " + bad);
    }

    /** Counterfactual: the same casts with the old 1.5-tall fence rails. Proves the rail height matters. */
    @Test
    void fenceRailsWouldHaveCaughtThem() {
        MemoryCanvas real = c;
        try {
            MemoryCanvas fenced = IslandRun.run(new BayouGenerator(), IslandRun.seedOf("backwater_bayou")).canvas;
            Layout.Anchor a = layout.anchor("fishing.spot.docks");
            for (int x = fl(a.x()) - 80; x <= fl(a.x()) + 30; x++)
                for (int z = -30; z <= 30; z++)
                    for (int y = BayouGenerator.DECK; y <= BayouGenerator.DECK + 2; y++)
                        if (fenced.get(x, y, z).equals(BayouGenerator.RAIL)) fenced.set(x, y, z, "spruce_fence");
            c = fenced;
            int missed = dockMisses().size();
            if (DEBUG) System.out.println("fence rails: " + missed + " of " + DOCK_CASTS + " missed");
            assertTrue(missed >= DOCK_CASTS / 3, "fence rails caught 13 of 28 of these casts when measured; now " + missed);
        } finally {
            c = real;
        }
    }

    @Test
    void sidewaysCastsFromTheBoardwalkClearTheRail() {
        Layout.Anchor a = layout.anchor("fishing.spot.docks");
        double bx = a.x() - 12, by = a.y(), bz = BayouGenerator.BW_Z + 0.5;
        assertTrue(BlockInfo.of(c.get(fl(bx), fl(by) - 1, fl(bz))).top16 >= 16, "boardwalk deck under " + bx + "," + bz);
        // negative control: along the boardwalk you land on the boardwalk - the simulator sees decks
        assertEquals(Lands.BLOCK, cast(bx, by, bz, 90f, 10f));
        List<String> bad = new ArrayList<>();
        for (float[] yp : new float[][]{{0, 0}, {0, 10}, {180, 0}, {180, 10}, {30, 0}, {150, 0}}) {
            float yaw = yp[0], pitch = yp[1];
            Lands l = cast(bx, by, bz, yaw, pitch);
            if (l != Lands.WATER) bad.add("yaw " + yaw + " pitch " + pitch + " -> " + l);
        }
        assertTrue(bad.isEmpty(), "sideways casts off the boardwalk missed: " + bad);
    }

    @Test
    void ladderLeadsBackOntoTheDock() {
        Layout.Anchor a = layout.anchor("fishing.spot.docks");
        int railX = fl(a.x()) - 1;
        int ladders = 0;
        for (int y = BayouGenerator.W - 1; y <= BayouGenerator.DECK; y++) if (c.get(railX - 1, y, 8).startsWith("ladder")) ladders++;
        assertTrue(ladders >= 3, "ladder rungs down the dock's west face: " + ladders);
        assertTrue(c.isAir(railX, BayouGenerator.DECK + 1, 8), "gap in the rail above the ladder");
    }
}
