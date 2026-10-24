package com.testpilot;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planner against REAL generated geometry: each fixture is a box cut out of an island by
 * {@code tools/navdump.sh} (the same generator and seed the server builds), loaded with real
 * Minecraft block states. Every case is a place the wave-4 playtest's walker got stuck in.
 */
class NavWorldTest {

    /** A dense box of block states; everything outside it is air (the islands float in a void). */
    static final class Fixture implements BlockGetter {
        final int x0, y0, z0, sx, sy, sz;
        final BlockState[] palette;
        final short[] ids;
        final Map<String, Vec3> anchors = new HashMap<>();

        Fixture(int x0, int y0, int z0, int x1, int y1, int z1, BlockState[] palette) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.sx = x1 - x0 + 1;
            this.sy = y1 - y0 + 1;
            this.sz = z1 - z0 + 1;
            this.palette = palette;
            this.ids = new short[sx * sy * sz];
        }

        int index(int x, int y, int z) {
            int ix = x - x0, iy = y - y0, iz = z - z0;
            if (ix < 0 || iy < 0 || iz < 0 || ix >= sx || iy >= sy || iz >= sz) {
                return -1;
            }
            return (iy * sz + iz) * sx + ix;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int i = index(pos.getX(), pos.getY(), pos.getZ());
            return i < 0 ? Blocks.AIR.defaultBlockState() : palette[ids[i]];
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getHeight() {
            return 2032;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        Vec3 anchor(String name) {
            Vec3 a = anchors.get(name);
            if (a == null) {
                throw new AssertionError("no anchor " + name + " in the fixture (have " + anchors.keySet() + ")");
            }
            return a;
        }
    }

    static Fixture load(String name) {
        String res = "/nav/" + name + ".txt.gz";
        try (InputStream raw = NavWorldTest.class.getResourceAsStream(res)) {
            if (raw == null) {
                throw new AssertionError("missing fixture " + res + " (tools/navdump.sh writes it)");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
            String[] h = r.readLine().split(" ");
            int x1 = Integer.parseInt(h[4]), y1 = Integer.parseInt(h[5]), z1 = Integer.parseInt(h[6]);
            int x2 = Integer.parseInt(h[7]), y2 = Integer.parseInt(h[8]), z2 = Integer.parseInt(h[9]);
            List<BlockState> pal = new ArrayList<>();
            pal.add(Blocks.AIR.defaultBlockState());
            List<String> rest = new ArrayList<>();
            Map<String, Vec3> anchors = new HashMap<>();
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("P ")) {
                    String[] p = line.split(" ", 3);
                    int id = Integer.parseInt(p[1]);
                    while (pal.size() <= id) {
                        pal.add(Blocks.AIR.defaultBlockState());
                    }
                    pal.set(id, parse(p[2]));
                } else if (line.startsWith("A ")) {
                    String[] p = line.split(" ");
                    anchors.put(p[1], new Vec3(Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4])));
                } else if (line.startsWith("C ")) {
                    rest.add(line);
                }
            }
            Fixture f = new Fixture(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                    Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2), pal.toArray(new BlockState[0]));
            f.anchors.putAll(anchors);
            for (String line : rest) {
                String[] p = line.split(" ");
                int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                for (int k = 3; k < p.length; k++) {
                    String[] run = p[k].split(":");
                    int y = Integer.parseInt(run[0]), id = Integer.parseInt(run[1]), n = Integer.parseInt(run[2]);
                    for (int j = 0; j < n; j++) {
                        int i = f.index(x, y + j, z);
                        if (i >= 0) {
                            f.ids[i] = (short) id;
                        }
                    }
                }
            }
            return f;
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    static BlockState parse(String s) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, s, false).blockState();
        } catch (CommandSyntaxException e) {
            // a block this client version does not know (the server is a version ahead): solid is
            // the conservative reading for a walker
            System.out.println("fixture: unknown block '" + s + "', read as stone");
            return Blocks.STONE.defaultBlockState();
        }
    }

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static String describe(Nav.Plan p) {
        if (p == null) {
            return "null";
        }
        Nav.Waypoint last = p.path.isEmpty() ? null : p.path.get(p.path.size() - 1);
        return "complete=" + p.complete + " waypoints=" + p.path.size() + " expanded=" + p.expanded
                + (last == null ? "" : " end=(" + last.bx + "," + last.y + "," + last.bz + ")");
    }

    /**
     * Every place a player can physically stand in {@code x0..x1, z0..z1} near feet height
     * {@code y}: a seeded sample, real box, real floor.
     */
    static List<Vec3> standingSpots(Fixture f, double x0, double z0, double x1, double z1, double y, int n, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        List<Vec3> out = new ArrayList<>();
        for (int tries = 0; out.size() < n && tries < n * 50; tries++) {
            double x = x0 + rnd.nextDouble() * (x1 - x0), z = z0 + rnd.nextDouble() * (z1 - z0);
            // snap a third of them onto an edge: pressed against something is where the walker died
            if (rnd.nextInt(3) == 0) {
                x = Math.floor(x) + (rnd.nextBoolean() ? 0.301 : 0.699);
            }
            double fy = Nav.standAtBox(f, x, z, y + 0.5, 0.6, 1.0, true, null);
            if (!Double.isNaN(fy)) {
                out.add(new Vec3(x, fy, z));
            }
        }
        return out;
    }

    static final int BUDGET = 8000;

    /** The old planner swept every move from the start CELL's centre; planning from there replays it. */
    static Vec3 cellCentre(Vec3 p) {
        return new Vec3(Math.floor(p.x) + 0.5, p.y, Math.floor(p.z) + 0.5);
    }

    /** True when the player's real box can stand at {@code p} (a floor under it, room above it). */
    static boolean standable(Fixture f, Vec3 p) {
        double fy = Nav.standAtBox(f, p.x, p.z, p.y, 0.01, 0.01, true, null);
        return !Double.isNaN(fy) && Math.abs(fy - p.y) < 1e-6;
    }

    /**
     * What the walker does with plans, without a client: plan, walk to the end of the path, plan the
     * next leg - with the same "leads nowhere -> four times the budget" retry as Actions.walkTo.
     * Returns the final position; {@code reached[0]} says whether a plan ever completed.
     */
    static Vec3 walkLegs(Fixture f, Vec3 from, Vec3 target, boolean[] reached) {
        Vec3 at = from;
        for (int leg = 0; leg < 40; leg++) {
            Nav.Plan plan = Nav.plan(f, at, target, 0.75, 0.6, Set.of(), BUDGET);
            if (Nav.leadsNowhere(plan, at)) {
                plan = Nav.plan(f, at, target, 0.75, 0.6, Set.of(), BUDGET * 4);
            }
            if (Nav.leadsNowhere(plan, at)) {
                reached[0] = false;
                return at;                                      // the walker reports "no route" here
            }
            assertGuardLetsThePlanThrough(f, at, plan);
            if (!plan.path.isEmpty()) {
                Nav.Waypoint end = plan.path.get(plan.path.size() - 1);
                at = new Vec3(end.cx(), end.y, end.cz());
            }
            if (plan.complete) {
                reached[0] = true;
                return at;
            }
        }
        throw new AssertionError("40 legs and still walking from " + from);
    }

    /**
     * The edge guard (wave-4 endgame: walked off The End's island ~20 times) must never fire on a
     * plan's own steps, or every walk along a real ledge, bridge or balcony would stop. Steers each
     * move the way the walker does - from the last point toward the next cell centre, at sprint
     * look-ahead, until within 0.4 of it - and asks the guard at every 0.1 blocks.
     */
    static void assertGuardLetsThePlanThrough(Fixture f, Vec3 from, Nav.Plan plan) {
        double x = from.x, y = from.y, z = from.z;
        for (Nav.Waypoint w : plan.path) {
            double dx = w.cx() - x, dz = w.cz() - z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (!w.climb && d > 0.4) {
                for (double s = 0; s <= d - 0.4; s += 0.1) {
                    double px = x + dx / d * s, pz = z + dz / d * s;
                    assertFalse(Nav.edgeAhead(f, px, pz, y, dx, dz, 0.28),
                            "edge guard fires on a planned step at " + px + "," + y + "," + pz + " toward "
                                    + w.bx + "," + w.y + "," + w.bz);
                }
            }
            x = w.cx();
            y = w.y;
            z = w.cz();
        }
    }

    @Test
    void liftCageSillIsNotATrap() {
        // Wave-4: "stuck inside the Pigmen's Den lift cage (clearance 0 in all 16 directions) for 7.5
        // minutes". The screenshot (036) has the player at -3,100,-2 facing south with the bars on his
        // left: standing on the cage's sill (a slab on a polished-andesite course) OUTSIDE the iron
        // bars, in the ring cell whose centre is inside the bars.
        Fixture f = load("deep_lift3");
        assertTrue(f.getBlockState(new BlockPos(-3, 100, -2)).is(Blocks.STONE_BRICK_SLAB));
        assertTrue(f.getBlockState(new BlockPos(-3, 101, -2)).is(Blocks.IRON_BARS));
        Vec3 sill = new Vec3(-2.9, 100.5, -1.5);
        assertTrue(standable(f, sill), "a player really can stand there");
        Vec3 out = new Vec3(-12.5, Double.NaN, 8.5);
        assertNull(Nav.plan(f, cellCentre(sill), out, 0.75, 0.6, Set.of(), BUDGET), "the old planner: no plan at all");
        Nav.Plan plan = Nav.plan(f, sill, out, 0.75, 0.6, Set.of(), BUDGET);
        assertNotNull(plan);
        assertTrue(plan.complete, "off the sill and out across the station");

        double best = 0;
        for (int i = 0; i < 16; i++) {
            double rad = Math.toRadians(i * 22.5);
            best = Math.max(best, Nav.clearance(f, sill, -Math.sin(rad), Math.cos(rad), 16, 0.6));
        }
        assertTrue(best >= 1.0, "clearance along the sill, not 0 in every direction: " + best);

        // and from where the lift really puts you, unchanged
        Nav.Plan station = Nav.plan(f, f.anchor("lift.3"), out, 0.75, 0.6, Set.of(), BUDGET);
        assertNotNull(station);
        assertTrue(station.complete);
    }

    @Test
    void graveyardWestWallCellHasAWayOut() {
        // Wave-4: repeated "0 waypoints" from (140,76,-15) to (160,76,-20), "the same leg had worked an
        // hour earlier". x=140 is the Graveyard's west wall (mossy cobblestone wall, arm in the middle
        // of the cell): a player pressed against either face of it is IN cell 140.
        Fixture f = load("hub_tavern_graveyard");
        assertTrue(f.getBlockState(new BlockPos(140, 76, -15)).is(Blocks.MOSSY_COBBLESTONE_WALL));
        Vec3 target = new Vec3(160.5, 76, -20.5);
        for (Vec3 s : new Vec3[] {new Vec3(140.01, 76, -14.5), new Vec3(140.99, 76, -14.5), new Vec3(140.01, 76, -45.5)}) {
            assertTrue(standable(f, s), "a player can stand at " + s);
            assertNull(Nav.plan(f, cellCentre(s), target, 0.75, 0.6, Set.of(), BUDGET), "old planner from " + s);
            boolean[] reached = new boolean[1];
            walkLegs(f, s, target, reached);
            assertTrue(reached[0], "into the Graveyard from " + s);
        }
    }

    @Test
    void tavernToGraveyardFromAnywhereInside() {
        // Wave-4: "from inside the Tavern to the Graveyard: 57 replans, 0 waypoints, 90 s". Every spot
        // a player can stand on either floor - a third of them pressed against something - must get
        // a plan, and the legs must reach the Graveyard (~95 blocks, round the building).
        Fixture f = load("hub_tavern_graveyard");
        Vec3 target = new Vec3(151.5, 76, -20.5);      // gravel; 150,-21 is a lamp post
        int oldFailures = 0, n = 0;
        long worstMs = 0;
        for (double fy : new double[] {75, 80}) {
            List<Vec3> spots = standingSpots(f, 58, 16, 78, 33, fy, 40, 7 + (long) fy);
            assertTrue(spots.size() >= 30, "found standing spots on floor " + fy);
            for (Vec3 s : spots) {
                n++;
                if (Nav.plan(f, cellCentre(s), target, 0.75, 0.6, Set.of(), BUDGET) == null) {
                    oldFailures++;
                }
                long t0 = System.nanoTime();
                Nav.Plan first = Nav.plan(f, s, target, 0.75, 0.6, Set.of(), BUDGET);
                worstMs = Math.max(worstMs, (System.nanoTime() - t0) / 1_000_000);
                assertFalse(Nav.leadsNowhere(first, s), "a first leg from " + s);
                boolean[] reached = new boolean[1];
                Vec3 end = walkLegs(f, s, target, reached);
                assertTrue(reached[0], "reached the Graveyard from " + s + " (stopped at " + end + ")");
            }
        }
        System.out.println("tavern: " + n + " spots, old planner had no plan from " + oldFailures + ", worst plan " + worstMs + " ms");
        // the guest rooms upstairs are reached only by the ladder through the floor: going down it
        // is part of the route (the old planner had no ladders, so upstairs was a dead end)
        Vec3 upstairs = standingSpots(f, 58, 16, 78, 33, 80, 1, 3).get(0);
        Nav.Plan down = Nav.plan(f, upstairs, target, 0.75, 0.6, Set.of(), BUDGET * 4);
        assertNotNull(down);
        assertTrue(down.path.stream().anyMatch(w -> w.climb && w.y < 79), "down the ladder from " + upstairs);
        assertTrue(worstMs < 300, "one plan stays inside a client tick budget: " + worstMs + " ms");
    }

    @Test
    void crystalCavernsBalconyHasAWayOutAndAnHonestNoRoute() {
        // Wave-4: "Crystal Caverns entrance: gems 15 blocks above the walkway, no route out of the
        // entrance hall found". The way out is the ledge spiralling down from the balcony's east end.
        Fixture f = load("hollows_entrance");
        Vec3 floor = new Vec3(0.5, 101, -20.5);
        for (Vec3 s : standingSpots(f, -12, -53, 13, -36, 137, 25, 11)) {
            boolean[] reached = new boolean[1];
            Vec3 end = walkLegs(f, s, floor, reached);
            assertTrue(reached[0], "balcony " + s + " -> Nucleus floor, stopped at " + end);
        }
        // A target up the dome wall cannot be walked to. The walker must get as close as the ground
        // goes and then STOP with "no route" - not loop until its timeout.
        Vec3 high = new Vec3(20.5, 152, -25.5);
        Vec3 spawn = f.anchor("spawn");
        boolean[] reached = new boolean[1];
        Vec3 end = walkLegs(f, spawn, high, reached);
        assertFalse(reached[0]);
        assertTrue(end.distanceTo(high) < spawn.distanceTo(high), "got closer before giving up: " + end);
    }
}
