package com.testpilot;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walking geometry against REAL block shapes. Ground is grass up to y=63, so a player
 * standing on it has feet at y=64. Each case is one of the stalls the wave-3 playtest hit.
 */
class NavTest {

    static class World implements BlockGetter {
        final Map<Long, BlockState> blocks = new HashMap<>();

        World set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
            return this;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState s = blocks.get(pos.asLong());
            if (s != null) {
                return s;
            }
            return pos.getY() <= 63 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
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
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** The player's box standing at (x, y, z) feet-centre. */
    static AABB player(double x, double y, double z) {
        return new AABB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    static World wallAcrossZ5(int fromX, int toX) {
        World w = new World();
        for (int x = fromX; x <= toX; x++) {
            for (int y = 64; y <= 66; y++) {
                w.set(x, y, 5, Blocks.STONE.defaultBlockState());
            }
        }
        return w;
    }

    @Test
    void dirtPathStepIsJumpedNotPushedInto() {
        // The Barn-square stall: a 15/16 path one level up. 0.94 > the 0.6 auto-step.
        World w = new World();
        for (int z = 5; z <= 12; z++) {
            w.set(0, 64, z, Blocks.DIRT_PATH.defaultBlockState());
        }
        double lift = Nav.jumpNeeded(w, player(0.5, 64, 4.69), 0.6, 0, 1, 0.35);
        assertFalse(Double.isNaN(lift), "a dirt_path step must be a jump");
        assertTrue(lift > 0.9 && lift <= Nav.MAX_JUMP, "lift " + lift);

        Nav.Plan plan = Nav.plan(w, new Vec3(0.5, 64, 0.5), new Vec3(0.5, 65, 10.5), 0.75, 0.6, Set.of(), 4000);
        assertNotNull(plan);
        assertTrue(plan.complete);
        assertTrue(plan.path.stream().anyMatch(p -> p.jump && Math.abs(p.y - 64.9375) < 1e-6),
                "the path climbs onto the path block with a jump");
    }

    @Test
    void stepUnderTheEdgeOfTheHitboxIsSeen() {
        // The old probe was ONE point on the centre line. Walking past the corner of a raised path
        // with only the side of the 0.6-wide box against it, the point landed in open air, the
        // walker decided "not a step", and sidestepped in alternation instead of jumping.
        World w = new World();
        for (int z = 5; z <= 12; z++) {
            w.set(1, 64, z, Blocks.DIRT_PATH.defaultBlockState());
        }
        double lift = Nav.jumpNeeded(w, player(0.85, 64, 4.69), 0.6, 0, 1, 0.35);
        assertFalse(Double.isNaN(lift), "box edge against the path block: jump");
    }

    @Test
    void halfSlabIsAutoStepNotAJump() {
        World w = new World().set(0, 64, 5, Blocks.OAK_SLAB.defaultBlockState());
        assertTrue(Double.isNaN(Nav.jumpNeeded(w, player(0.5, 64, 4.69), 0.6, 0, 1, 0.35)));
    }

    @Test
    void wallAndFenceAreNeverJumped() {
        World w = wallAcrossZ5(-3, 3);
        assertTrue(Double.isNaN(Nav.jumpNeeded(w, player(0.5, 64, 4.69), 0.6, 0, 1, 0.35)), "2+ high wall");
        World f = new World().set(0, 64, 5, Blocks.OAK_FENCE.defaultBlockState());
        assertTrue(Double.isNaN(Nav.jumpNeeded(f, player(0.5, 64, 4.69), 0.6, 0, 1, 0.35)), "1.5 high fence");
    }

    @Test
    void plansAroundAWall() {
        // The Library's outer wall: straight-line steering pushes into it for ever.
        World w = wallAcrossZ5(-6, 6);
        Nav.Plan plan = Nav.plan(w, new Vec3(0.5, 64, 0.5), new Vec3(0.5, 64, 10.5), 0.75, 0.6, Set.of(), 8000);
        assertNotNull(plan);
        assertTrue(plan.complete);
        assertTrue(plan.path.stream().anyMatch(p -> p.bx < -6 || p.bx > 6), "goes round an end of the wall");
        assertTrue(plan.path.stream().noneMatch(p -> p.bz == 5 && p.bx >= -6 && p.bx <= 6), "never through it");
        Nav.Waypoint last = plan.path.get(plan.path.size() - 1);
        assertEquals(10, last.bz);
    }

    @Test
    void doubleDoorIsAPathAndBothLeavesAreFound() {
        World w = wallAcrossZ5(-8, 8);
        // a closed double door at x=0 (hinge left) and x=1 (hinge right), facing north
        for (int x = 0; x <= 1; x++) {
            DoorHingeSide hinge = x == 0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT;
            BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                    .setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.OPEN, false);
            w.set(x, 64, 5, lower).set(x, 65, 5, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        Nav.Plan plan = Nav.plan(w, new Vec3(0.5, 64, 0.5), new Vec3(0.5, 64, 10.5), 0.75, 0.6, Set.of(), 8000);
        assertNotNull(plan);
        assertTrue(plan.complete);
        Nav.Waypoint doorCell = plan.path.stream().filter(p -> p.bz == 5).findFirst().orElseThrow();
        assertTrue(doorCell.bx == 0 || doorCell.bx == 1, "through the door, not round the 17-wide wall");
        assertEquals(1, doorCell.doors.size());
        BlockPos leaf = doorCell.doors.get(0);
        assertEquals(64, leaf.getY(), "the lower half is the one clicked");
        BlockPos partner = Nav.partnerLeaf(w, leaf);
        assertNotNull(partner, "the other leaf of the double door");
        assertEquals(leaf.getX() == 0 ? 1 : 0, partner.getX());

        // A door that refused to open three times is a wall: the walker routes round instead.
        Nav.Plan round = Nav.plan(w, new Vec3(0.5, 64, 0.5), new Vec3(0.5, 64, 10.5), 0.75, 0.6,
                Set.of(BlockPos.asLong(0, 64, 5), BlockPos.asLong(1, 64, 5)), 8000);
        assertNotNull(round);
        assertTrue(round.path.stream().noneMatch(p -> p.bz == 5 && p.bx >= -8 && p.bx <= 8));
    }

    @Test
    void openDoorLeavesRoomForThePlayerAtTheCellCentre() {
        BlockState open = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.OPEN, true);
        World w = new World().set(0, 64, 5, open).set(0, 65, 5, open.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        assertTrue(Nav.boxFree(w, Nav.standBox(0, 64, 5), true, null));
    }

    @Test
    void partialPlanWhenTheGoalIsOutOfBudget() {
        World w = new World();
        Nav.Plan plan = Nav.plan(w, new Vec3(0.5, 64, 0.5), new Vec3(0.5, 64, 400.5), 0.75, 0.6, Set.of(), 300);
        assertNotNull(plan);
        assertFalse(plan.complete);
        Nav.Waypoint last = plan.path.get(plan.path.size() - 1);
        assertTrue(last.bz > 20, "heads toward the goal: " + last.bz);
    }

    @Test
    void fullBudgetSearchIsCheapEnoughForTheClientThread() {
        // Worst case: the goal sits behind a 61-wide wall, so A* floods the near side first.
        // The plan runs inside one client tick; it must not freeze the frame for long.
        World w = wallAcrossZ5(-30, 30);
        Nav.plan(w, new Vec3(0.5, 64, 0.5), new Vec3(0.5, 64, 10.5), 0.75, 0.6, Set.of(), 8000);   // warm-up
        long t0 = System.nanoTime();
        Nav.Plan plan = Nav.plan(w, new Vec3(0.5, 64, 0.5), new Vec3(0.5, 64, 10.5), 0.75, 0.6, Set.of(), 8000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("8000-node plan: " + ms + " ms, expanded " + plan.expanded + ", complete " + plan.complete);
        assertTrue(ms < 300, ms + " ms");
    }

    @Test
    void laddersAreClimbedUpAndDown() {
        // A two-storey house: the upper floor (planks at y=68, feet 69) is reached ONLY by a ladder
        // through a hole in it, which is how every hub house, tower, well and mine scaffold is built.
        // The planner used to see no way up or down at all (wave-4: a walker upstairs in the Tavern).
        World w = new World();
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                w.set(x, 68, z, Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING, Direction.SOUTH);
        for (int y = 64; y <= 68; y++) {
            w.set(0, y, -1, Blocks.STONE.defaultBlockState());
            w.set(0, y, 0, ladder);
        }
        Nav.Plan up = Nav.plan(w, new Vec3(3.5, 64, 3.5), new Vec3(3.5, 69, 3.5), 0.75, 0.6, Set.of(), 8000);
        assertNotNull(up);
        assertTrue(up.complete, "up the ladder");
        assertTrue(up.path.stream().anyMatch(p -> p.climb && p.bx == 0 && p.bz == 0 && p.y > 65), "climbs the ladder column");
        assertEquals(69.0, up.path.get(up.path.size() - 1).y, 1e-6);

        Nav.Plan down = Nav.plan(w, new Vec3(3.5, 69, 3.5), new Vec3(3.5, 64, 3.5), 0.75, 0.6, Set.of(), 8000);
        assertNotNull(down);
        assertTrue(down.complete, "down the ladder");
        assertTrue(down.path.stream().anyMatch(p -> p.climb && p.bx == 0 && p.bz == 0 && p.y < 67), "slides down the column");
        assertEquals(64.0, down.path.get(down.path.size() - 1).y, 1e-6);

        // a ladder is not a floor: the top of one is not a place to stand
        assertTrue(Double.isNaN(Nav.standAt(w, 0, 0, 69, 0.01, 0.01, true, null)));
    }

    /** An 11x11 grass island (x, z in -5..5, top at y=63) over the void: nothing else anywhere. */
    static World island() {
        return new World() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                BlockState s = blocks.get(pos.asLong());
                if (s != null) {
                    return s;
                }
                boolean on = Math.abs(pos.getX()) <= 5 && Math.abs(pos.getZ()) <= 5 && pos.getY() <= 63 && pos.getY() >= 60;
                return on ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
        };
    }

    @Test
    void edgeGuardSeesTheVoidAndNotAnOrdinaryStepDown() {
        // Wave-4 endgame: legs aimed at a Hub coordinate from The End walked off the island ~20 times.
        World w = island();
        // the island's east edge is x = 6.0 and a box falls once its centre passes 6.3: a player at
        // x=5.75 heading east must be stopped (a walking slide is ~0.26, a sprinting one ~0.34)
        assertTrue(Nav.edgeAhead(w, 5.75, 0.5, 64, 1, 0, 0.22), "walking east off the edge");
        assertTrue(Nav.edgeAhead(w, 5.6, 0.5, 64, 1, 0, 0.28), "sprinting east off the edge");
        assertFalse(Nav.edgeAhead(w, 4.5, 0.5, 64, 1, 0, 0.22), "a block and a half from the edge, walking: fine");
        assertFalse(Nav.edgeAhead(w, 5.5, 0.5, 64, -1, 0, 0.28), "walking back inland");
        assertFalse(Nav.edgeAhead(w, 5.5, 0.5, 64, 0, 1, 0.28), "walking ALONG the edge");
        // a 3-block step down is a drop the planner takes, so the guard must allow it
        World step = new World();
        for (int x = 1; x <= 20; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = 61; y <= 63; y++) {
                    step.set(x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
        }
        assertFalse(Nav.edgeAhead(step, 0.5, 0.5, 64, 1, 0, 0.28), "a 3-block step down is not an edge");
        // water breaks the fall, a ladder down a hole is a way down; lava is a drop like the void
        World pool = island().set(6, 63, 0, Blocks.WATER.defaultBlockState()).set(7, 63, 0, Blocks.WATER.defaultBlockState());
        assertFalse(Nav.edgeAhead(pool, 5.75, 0.5, 64, 1, 0, 0.22), "water beyond the edge");
        assertTrue(Nav.groundBelow(island(), 0.5, 0.5, 64, Nav.SAFE_DROP));
        assertFalse(Nav.groundBelow(island(), 20.5, 0.5, 64, Nav.SAFE_DROP));
        // a footprint that still overlaps the island holds the player up (as in vanilla)
        assertTrue(Nav.groundBelow(island(), 6.2, 0.5, 64, Nav.SAFE_DROP), "0.1 of the box still on the block");
        assertFalse(Nav.groundBelow(island(), 6.31, 0.5, 64, Nav.SAFE_DROP), "box entirely past the edge");
    }

    @Test
    void edgeLookaheadOutrunsTheSlideAndStillTurnsABridgeCorner() {
        // Letting go of every key, a box slides ~1.2x its speed; the look-ahead is the margin.
        for (double v : new double[] {0.1, 0.216, 0.28}) {
            assertTrue(Nav.edgeLookahead(v) > 1.2 * v + 0.3, "speed " + v);
        }
        // A one-wide bridge going east then turning north at x=0: the walker advances to the north
        // leg once within 0.4 of the corner cell's centre, still carrying its sprint east. That
        // momentum must not read as an edge (it would stop every sprint round a corner).
        World w = new World() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                BlockState s = blocks.get(pos.asLong());
                if (s != null) {
                    return s;
                }
                boolean bridge = pos.getY() == 63 && ((pos.getZ() == 0 && pos.getX() >= -10 && pos.getX() <= 0)
                        || (pos.getX() == 0 && pos.getZ() <= 0 && pos.getZ() >= -10));
                return bridge ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
        };
        for (double x = 0.1; x <= 0.5; x += 0.04) {
            assertFalse(Nav.edgeAhead(w, x, 0.5, 64, 1, 0, 0.28), "sprint momentum at the corner, x=" + x);
        }
        // but sprinting straight on past the corner block IS an edge
        assertTrue(Nav.edgeAhead(w, 0.8, 0.5, 64, 1, 0, 0.28));
    }

    @Test
    void planToAPointOffTheIslandStopsAtTheShore() {
        // The planner never steps into the void: a target 40 blocks off the island gets a partial
        // plan whose every waypoint stands on the island.
        World w = island();
        Nav.Plan plan = Nav.plan(w, new Vec3(0.5, 64, 0.5), new Vec3(40.5, 64, 0.5), 0.75, 0.6, Set.of(), 8000);
        assertNotNull(plan);
        assertFalse(plan.complete);
        for (Nav.Waypoint p : plan.path) {
            assertTrue(Math.abs(p.bx) <= 5 && Math.abs(p.bz) <= 5, "waypoint off the island: " + p.bx + "," + p.bz);
            assertTrue(Nav.groundBelow(w, p.cx(), p.cz(), p.y, Nav.SAFE_DROP));
        }
        assertEquals(5, plan.path.get(plan.path.size() - 1).bx, "ends at the shore nearest the target");
        // and from the shore, there is nowhere better: the walker reports no route instead of walking on
        assertTrue(Nav.leadsNowhere(Nav.plan(w, new Vec3(5.5, 64, 0.5), new Vec3(40.5, 64, 0.5), 0.75, 0.6, Set.of(), 8000),
                new Vec3(5.5, 64, 0.5)));
    }

    @Test
    void clearanceStopsAtAWall() {
        World w = wallAcrossZ5(-3, 3);
        assertEquals(4.0, Nav.clearance(w, new Vec3(0.5, 64, 0.5), 0, 1, 16, 0.6), 0.51);
        assertEquals(16.0, Nav.clearance(w, new Vec3(0.5, 64, 0.5), 0, -1, 16, 0.6), 1e-9);
    }

    // ------------------------------------------------------------------ wave-5 regression

    @Test
    void aPlayerSunkIntoACarpetStillHasAStartCell() {
        // Floor I's start room: a carpet runner, and the party placed at y 71.0 INSIDE it (top
        // 71.0625). Every move out of the start was swept from 71.0, hit the carpet under the feet,
        // and /walkto answered noRoute with 0 nodes expanded - 2 runs of 2.
        World w = new World();
        for (int x = -1; x <= 1; x++) {
            for (int z = 0; z <= 10; z++) {
                w.set(x, 64, z, Blocks.GREEN_CARPET.defaultBlockState());
            }
        }
        Vec3 sunk = new Vec3(0.5, 64.0, 0.5);
        assertFalse(Nav.boxFree(w, player(0.5, 64.0 + 1e-3, 0.5), false, null), "the cause: his own box overlaps the carpet");
        assertEquals(64.0625, Nav.startY(w, sunk, 0.6, Set.of()), 1e-9);
        Nav.Plan along = Nav.plan(w, sunk, new Vec3(0.5, 64.0625, 8.5), 0.75, 0.6, Set.of(), 4000);
        assertNotNull(along, "a plan along the runner");
        assertTrue(along.complete);
        Nav.Plan off = Nav.plan(w, sunk, new Vec3(6.5, 64, 0.5), 0.75, 0.6, Set.of(), 4000);
        assertNotNull(off, "a plan off the runner onto the grass");
        assertTrue(off.complete);

        // a snow layer (0.25 high) the same; a player standing ON things, or mid-jump, is untouched
        World snow = new World().set(0, 64, 0, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        assertEquals(64.25, Nav.startY(snow, new Vec3(0.5, 64.0, 0.5), 0.6, Set.of()), 1e-9);
        assertNotNull(Nav.plan(snow, new Vec3(0.5, 64.0, 0.5), new Vec3(0.5, 64, 6.5), 0.75, 0.6, Set.of(), 4000));
        assertEquals(64.0625, Nav.startY(w, new Vec3(0.5, 64.0625, 0.5), 0.6, Set.of()), 1e-9);
        assertEquals(64.7, Nav.startY(w, new Vec3(0.5, 64.7, 0.5), 0.6, Set.of()), 1e-9);
        assertEquals(64.0, Nav.startY(new World(), new Vec3(0.5, 64.0, 0.5), 0.6, Set.of()), 1e-9);
        // a wall is not climbed into: the stand height is never lifted by more than half a block
        World wall = new World().set(0, 64, 0, Blocks.OAK_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
        assertEquals(64.0, Nav.startY(wall, new Vec3(0.5, 64.0, 0.5), 0.6, Set.of()), 1e-9);
    }

    /**
     * A 3-deep pool (water y 61..63, bed top 61, surface ~63.9) at x 0..6, z 0..12 with a plank
     * deck laid straight on it (y 64, top 65) over z 0..5: no air between water and deck - the
     * Bayou dock where the bot drowned. Shore all round at 64.
     */
    static World dockPool() {
        World w = new World();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 12; z++) {
                for (int y = 61; y <= 63; y++) {
                    w.set(x, y, z, Blocks.WATER.defaultBlockState());
                }
                if (z <= 5) {
                    w.set(x, 64, z, Blocks.OAK_PLANKS.defaultBlockState());
                }
            }
        }
        return w;
    }

    static boolean breathless(BlockGetter w, Nav.Waypoint p) {
        return Nav.eyesInWater(w, p.cx(), p.y, p.cz()) && !Nav.breathableAbove(w, p.bx, p.y, p.bz);
    }

    static boolean wet(BlockGetter w, Nav.Waypoint p) {
        return Nav.isWater(w.getBlockState(BlockPos.containing(p.cx(), p.y + 1e-3, p.cz())))
                || Nav.eyesInWater(w, p.cx(), p.y, p.cz());
    }

    @Test
    void waterUnderADockHasNoAirAndIsNeverPlanned() {
        World w = dockPool();
        assertTrue(Nav.eyesInWater(w, 3.5, 61, 2.5), "on the bed the head is under water");
        assertFalse(Nav.breathableAbove(w, 3, 61, 2), "under the deck: no air to come up for");
        assertTrue(Nav.breathableAbove(w, 3, 61, 9), "open water: air above");
        assertFalse(Nav.eyesInWater(w, -0.5, 64, 2.5), "on the shore: dry");

        // A sea creature under the deck (Trash Gobbler, y 62-64). The old planner swam the player
        // along the bed under the deck to it (water was only expensive). Now: never a breathless cell.
        // From the shore of a one-plank deck the answer is the deck right above it...
        Nav.Plan chase = Nav.plan(w, new Vec3(-0.5, 64, 2.5), new Vec3(3.5, 61, 2.5), 0.75, 0.6, Set.of(), 8000);
        assertNotNull(chase);
        assertFalse(chase.complete, "the creature under the deck is out of reach on foot");
        for (Nav.Waypoint p : chase.path) {
            assertFalse(breathless(w, p), "waypoint under the deck: " + p.bx + "," + p.y + "," + p.bz);
        }
        // ...and from open water beside a two-plank deck (no deck within a water-exit of the surface
        // to climb onto instead) the bed under it is the only way in, and it is refused
        World thick = dockPool();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 5; z++) {
                thick.set(x, 65, z, Blocks.OAK_PLANKS.defaultBlockState());
            }
        }
        Nav.Plan under = Nav.plan(thick, new Vec3(3.5, 61, 9.5), new Vec3(3.5, 61, 2.5), 0.75, 0.6, Set.of(), 8000);
        assertNotNull(under);
        assertFalse(under.complete, "swimming under the deck to it is not a route");
        for (Nav.Waypoint p : under.path) {
            assertFalse(breathless(thick, p), "waypoint under the deck: " + p.bx + "," + p.y + "," + p.bz);
        }
        assertTrue(under.path.get(under.path.size() - 1).bz >= 6, "ends in the open water by the deck");
        // open water with air above is still swimmable
        Nav.Plan swim = Nav.plan(w, new Vec3(-0.5, 64, 9.5), new Vec3(3.5, 61, 9.5), 0.75, 0.6, Set.of(), 8000);
        assertNotNull(swim);
        assertTrue(swim.complete);
    }

    @Test
    void aDryPlanNeverEntersWaterButSwimsOutOfIt() {
        World w = dockPool();
        // kill_nearest: the creature in open water is fought from the shore, not followed in
        Nav.Plan dry = Nav.plan(w, new Vec3(-1.5, 64, 9.5), new Vec3(3.5, 61, 9.5), 0.75, 0.6, Set.of(), 8000, null, true);
        if (dry != null) {
            assertFalse(dry.complete);
            for (Nav.Waypoint p : dry.path) {
                assertFalse(wet(w, p), "dry plan stepped into water at " + p.bx + "," + p.y + "," + p.bz);
            }
        }
        // from the deck toward a creature under it: the deck's own edge, never the water
        Nav.Plan deck = Nav.plan(w, new Vec3(3.5, 65, 1.5), new Vec3(3.5, 61, 7.5), 0.75, 0.6, Set.of(), 8000, null, true);
        if (deck != null) {
            for (Nav.Waypoint p : deck.path) {
                assertFalse(wet(w, p), "dry plan off the deck into water at " + p.bx + "," + p.y + "," + p.bz);
            }
        }
        // already in the water (knocked in): a dry walk still swims OUT to the shore
        Nav.Plan out = Nav.plan(w, new Vec3(3.5, 61, 9.5), new Vec3(-1.5, 64, 9.5), 0.75, 0.6, Set.of(), 8000, null, true);
        assertNotNull(out, "a dry walk that starts in water swims out");
        assertTrue(out.complete);
    }

    @Test
    void nearestAirFromUnderTheDockIsTheOpenWaterBesideIt() {
        World w = dockPool();
        Vec3 air = Nav.nearestAir(w, new Vec3(3.5, 61, 2.5), 10, 0.3, 1.8);
        assertNotNull(air);
        assertEquals(3.5, air.x, 1e-9);
        assertEquals(6.5, air.z, 1e-9, "straight out from under the deck to the first open column");
        assertTrue(Nav.breathableAbove(w, Mth.floor(air.x), 61, Mth.floor(air.z)));
        // a row of posts across the way is swum round, not through
        World posts = dockPool();
        for (int x = 0; x <= 6; x++) {
            if (x != 0) {
                for (int y = 61; y <= 63; y++) {
                    posts.set(x, y, 4, Blocks.OAK_LOG.defaultBlockState());
                }
            }
        }
        Vec3 round = Nav.nearestAir(posts, new Vec3(3.5, 61, 2.5), 10, 0.3, 1.8);
        assertTrue(round == null || Math.abs(round.z - 6.5) > 1e-9 || Math.abs(round.x - 3.5) > 1e-9,
                "not straight through the posts: " + round);
        // no air anywhere in range (a sealed tank): null, the walker then heads back the way it came
        World tank = new World();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = 61; y <= 63; y++) {
                    tank.set(x, y, z, Blocks.WATER.defaultBlockState());
                }
                tank.set(x, 64, z, Blocks.STONE.defaultBlockState());
            }
        }
        assertEquals(null, Nav.nearestAir(tank, new Vec3(0.5, 61, 0.5), 10, 0.3, 1.8));
    }
}
