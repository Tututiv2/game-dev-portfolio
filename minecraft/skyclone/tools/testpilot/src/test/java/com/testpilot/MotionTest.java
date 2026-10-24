package com.testpilot;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The edge guard's physics ({@link Motion}) and a headless copy of the walker's steering run on
 * it: the island edges and the Tavern ladder that the wave-4 final verification found broken.
 * The physics numbers are checked against vanilla's known ones first, since everything else here
 * is only as true as they are.
 */
class MotionTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static Motion.Body at(double x, double y, double z) {
        Motion.Body b = new Motion.Body();
        b.x = x;
        b.y = y;
        b.z = z;
        b.onGround = true;
        b.supportY = y;
        return b;
    }

    static Motion.Keys keys(float yaw, boolean forward, boolean sprint, boolean jump) {
        Motion.Keys k = new Motion.Keys();
        k.yaw = yaw;
        k.forward = forward;
        k.sprint = sprint;
        k.jump = jump;
        return k;
    }

    // ------------------------------------------------------------------ physics against vanilla's numbers

    @Test
    void walkSprintAndJumpMatchVanilla() {
        NavTest.World flat = new NavTest.World();
        // facing south (+z): walk 4.317 m/s, sprint 5.612 m/s - per tick 0.2158 and 0.2806
        for (boolean sprint : new boolean[] {false, true}) {
            Motion.Body b = at(0.5, 64, 0.5);
            double last = b.z;
            double step = 0;
            for (int t = 0; t < 60; t++) {
                Motion.tick(flat, b, keys(0, true, sprint, false));
                step = b.z - last;
                last = b.z;
            }
            assertEquals(sprint ? 0.2806 : 0.2158, step, 0.002, (sprint ? "sprint" : "walk") + " speed per tick");
            assertEquals(64.0, b.y, 1e-9);
            assertTrue(b.onGround);
        }
        // a standing jump peaks at 1.2522 blocks and lands 12 ticks later
        Motion.Body b = at(0.5, 64, 0.5);
        double top = 64;
        int air = 0;
        Motion.tick(flat, b, keys(0, false, false, true));
        for (int t = 0; t < 30 && !b.onGround; t++) {
            top = Math.max(top, b.y);
            air++;
            Motion.tick(flat, b, keys(0, false, false, false));
        }
        assertEquals(1.2522, top - 64, 0.002, "jump apex");
        assertEquals(64.0, b.y, 1e-9);
        assertTrue(air >= 10 && air <= 12, "ticks in the air " + air);
        // a 1-block step: auto-step handles a slab, a full block needs the jump
        NavTest.World slab = new NavTest.World().set(0, 64, 2, Blocks.OAK_SLAB.defaultBlockState());
        Motion.Body s = at(0.5, 64, 0.5);
        double high = 64;
        for (int t = 0; t < 20; t++) {
            Motion.tick(slab, s, keys(0, true, false, false));
            high = Math.max(high, s.y);
        }
        assertEquals(64.5, high, 1e-6, "stepped up onto the slab without a jump");
        assertTrue(s.z > 3, "and walked on over it: " + s);
        NavTest.World wall = new NavTest.World().set(0, 64, 2, Blocks.STONE.defaultBlockState());
        Motion.Body w = at(0.5, 64, 0.5);
        for (int t = 0; t < 20; t++) {
            Motion.tick(wall, w, keys(0, true, false, false));
        }
        assertEquals(1.7, w.z, 1e-6, "stopped by a full block, box against it");
        assertTrue(w.horizontalCollision);
    }

    @Test
    void ladderClimbsWithJumpAndHoldsWithSneak() {
        NavTest.World w = new NavTest.World();
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
        for (int y = 64; y <= 70; y++) {
            w.set(0, y, -1, Blocks.STONE.defaultBlockState());
            w.set(0, y, 0, ladder);
        }
        Motion.Body b = at(0.5, 64, 0.6);
        for (int t = 0; t < 20; t++) {
            Motion.tick(w, b, keys(180, false, false, true));
        }
        assertTrue(b.y > 66.3, "holding jump climbs ~0.118/tick (2.35 m/s): " + b);
        double y = b.y;
        Motion.Keys sneak = keys(180, false, false, false);
        sneak.sneak = true;
        for (int t = 0; t < 20; t++) {
            Motion.tick(w, b, sneak);
        }
        assertEquals(y, b.y, 0.25, "sneak holds on the ladder");
        for (int t = 0; t < 10; t++) {
            Motion.tick(w, b, keys(180, false, false, false));
        }
        assertTrue(b.y < y - 1.0, "letting go slides down at 0.15/tick: " + b);
    }

    // ------------------------------------------------------------------ the guard on a plain island

    /** Hold {@code want} (as long as the guard lets it) for {@code ticks}; returns the lowest feet height. */
    static double holdWithGuard(BlockGetter level, Motion.Body b, Motion.Keys want, int ticks) {
        double low = b.y;
        for (int t = 0; t < ticks; t++) {
            Motion.Choice ch = Motion.choose(level, b, want);
            Motion.tick(level, b, ch.keys);
            if (b.onGround || Motion.onClimbable(level, b)) {
                b.supportY = b.y;
            }
            low = Math.min(low, b.y);
        }
        return low;
    }

    @Test
    void guardNeverLetsARunOffTheIsland() {
        NavTest.World island = NavTest.island();
        // sprinting, walking, sprint-jumping (bunny hop) east, and diagonally at a corner
        Object[][] runs = {{-90f, true, false}, {-90f, false, false}, {-90f, true, true}, {-135f, true, true}, {-45f, true, false}};
        for (Object[] r : runs) {
            Motion.Body b = at(-4.5, 64, 0.5);
            Motion.Keys want = keys((float) r[0], true, (boolean) r[1], (boolean) r[2]);
            double low = holdWithGuard(island, b, want, 200);
            assertEquals(64.0, low, 1e-6, "never left the island top: " + want + " ended " + b);
            assertTrue(Math.max(Math.abs(b.x), Math.abs(b.z)) > 4.4, "but got right up to the edge: " + b);
            assertTrue(Math.abs(b.x) < 6.3 && Math.abs(b.z) < 6.3, "still standing on it: " + b);
        }
        // and without the guard the same sprint goes over
        Motion.Body b = at(-4.5, 64, 0.5);
        for (int t = 0; t < 80; t++) {
            Motion.tick(island, b, keys(-90f, true, true, false));
        }
        assertTrue(b.y < 60, "unguarded sprint falls: " + b);
    }

    @Test
    void guardIsCheapEnoughForEveryTick() {
        // it runs on the client thread every tick of every walk: worst case is right at an edge,
        // where every candidate is played out to the end and refused
        NavWorldTest.Fixture f = NavWorldTest.load("private_island_nw");
        Motion.Body b = at(-51.9, 72, -38.5);
        Motion.Keys want = keys(90f, true, true, true);
        for (int i = 0; i < 200; i++) {
            Motion.choose(f, b, want);
        }
        long t0 = System.nanoTime();
        int n = 2000;
        for (int i = 0; i < n; i++) {
            Motion.choose(f, b, want);
        }
        double us = (System.nanoTime() - t0) / 1000.0 / n;
        System.out.println("Motion.choose at an edge: " + Math.round(us) + " us");
        assertTrue(us < 2000, us + " us a tick");
    }

    @Test
    void stepDownOntoAShortLedgeIsCrept() {
        // The Private Island's islet rim in miniature: a 1-wide path at y=63 going east, a 1-block step
        // down at x=5 onto ONE block at y=62 (x=5), then the void. A sprint off the step lands ~1.4
        // past the lip - off the far end of the single block. The guard must make it creep down and
        // stop on it.
        NavTest.World w = new NavTest.World() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                boolean path = pos.getZ() == 0 && ((pos.getY() == 63 && pos.getX() >= -5 && pos.getX() <= 4)
                        || (pos.getY() == 62 && pos.getX() == 5));
                return path ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
        };
        Motion.Body raw = at(-4.5, 64, 0.5);
        for (int t = 0; t < 60; t++) {
            Motion.tick(w, raw, keys(-90f, true, true, false));
        }
        assertTrue(raw.y < 55, "unguarded: off the end of the ledge " + raw);
        Motion.Body b = at(-4.5, 64, 0.5);
        double low = holdWithGuard(w, b, keys(-90f, true, true, false), 300);
        assertTrue(low > 62.99, "guarded: never below the ledge top, lowest " + low + " ended " + b);
        assertTrue(b.x > 4.0, "and it did get as far as the edge allows: " + b);
    }

    // ------------------------------------------------------------------ a headless walker

    /** What happened to one headless walk. */
    static final class Walk {
        boolean arrived, fell, edge, noRoute;
        int ticks, guardTicks, edgeStops;
        Motion.Body end;
        String why = "";

        @Override
        public String toString() {
            return (arrived ? "arrived" : fell ? "FELL" : edge ? "edge" : noRoute ? "noRoute" : "timeout") + " after " + ticks
                    + " ticks at " + end + " guard " + guardTicks + " edgeStops " + edgeStops + " " + why;
        }
    }

    /**
     * The walker's steering (Actions.walkTo: plan, {@link Nav#advance}, ladder-hole entry with
     * {@link Motion#creep}, jumps, the guard and its edge stops) on {@link Motion}'s physics instead of
     * a client - the same code for the parts that decide a fall, a copy for the bookkeeping.
     */
    static Walk walk(BlockGetter level, Vec3 from, Vec3 target, boolean sprint, boolean guard, int maxTicks) {
        return walk(level, from, target, sprint, guard, maxTicks, null, false);
    }

    /**
     * {@code shape}: the body's size and speed (null = a plain player). {@code guardSeesAPlayer}:
     * the guard plays its predictions with a plain 0.6 x 1.8 body whatever the real one is - the
     * wave-5 bug, kept to prove the fixtures reproduce it.
     */
    static Walk walk(BlockGetter level, Vec3 from, Vec3 target, boolean sprint, boolean guard, int maxTicks,
            Motion.Body shape, boolean guardSeesAPlayer) {
        Walk r = new Walk();
        Motion.Body b = at(from.x, from.y, from.z);
        if (shape != null) {
            b.halfW = shape.halfW;
            b.height = shape.height;
            b.speed = shape.speed;
        }
        Set<Long> noGo = new HashSet<>();
        double supportY = b.y;
        List<Nav.Waypoint> path = null;
        boolean complete = false;
        int idx = 0, jumpCooldown = 0, edgeHold = 0, plannedStops = 0, stuck = 0;
        Vec3 lastProgress = from;
        Nav.Waypoint grabFor = null;
        Vec3 grab = null;
        float yaw = Motion.yawOf(target.x - from.x, target.z - from.z);
        for (int t = 0; t < maxTicks; t++) {
            r.ticks = t;
            r.end = b;
            Vec3 pos = new Vec3(b.x, b.y, b.z);
            if (b.y < supportY - Nav.SAFE_DROP - 0.5) {
                r.fell = true;
                return r;
            }
            double dx = target.x - b.x, dz = target.z - b.z, dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= 0.6 && Math.abs(b.y - target.y) <= Actions.ARRIVE_DY) {
                r.arrived = true;
                return r;
            }
            boolean onLadder = Motion.onClimbable(level, b);
            if (path == null || (idx >= path.size() && !complete) || stuck >= 30) {
                stuck = 0;
                lastProgress = pos;
                Nav.Plan plan = Nav.plan(level, pos, target, 0.75, 0.6, Set.of(), 8000, noGo);
                if (Nav.leadsNowhere(plan, pos)) {
                    plan = Nav.plan(level, pos, target, 0.75, 0.6, Set.of(), 32000, noGo);
                }
                if (Nav.leadsNowhere(plan, pos)) {
                    if (b.onGround && b.hspeed() < 0.01) {
                        r.noRoute = true;
                        return r;
                    }
                    path = List.of();
                    complete = true;
                } else {
                    path = plan.path;
                    complete = plan.complete;
                }
                idx = 0;
            }
            Nav.Waypoint wp = null;
            boolean straight = false;
            idx = Nav.advance(path, idx, b.x, b.y, b.z, onLadder);
            if (idx < path.size()) {
                wp = path.get(idx);
            } else {
                straight = true;
            }
            boolean entry = wp != null && wp.hang && !onLadder && b.y > wp.y - 0.3;
            double sx = target.x, sz = target.z;
            if (entry) {
                if (grabFor != wp) {
                    grab = Nav.grabPoint(level, wp.bx, wp.y, wp.bz);
                    grabFor = wp;
                }
                sx = grab.x;
                sz = grab.z;
            } else if (wp != null) {
                sx = wp.cx();
                sz = wp.cz();
            }
            double ax = sx - b.x, az = sz - b.z, ad = Math.max(1e-6, Math.sqrt(ax * ax + az * az));
            boolean column = wp != null && wp.climb && !entry && ad < 0.3;
            boolean climbUp = wp != null && (onLadder || wp.climb) && wp.y > b.y + 0.05;
            boolean slideDown = onLadder && wp != null && wp.y < b.y - 0.3
                        && (ad < 0.6 || Nav.slideFirst(level, b.box(), ax / ad, az / ad));
            Motion.Keys want = new Motion.Keys();
            want.yaw = yaw;
            if (!column && !(entry && ad < 0.15)) {
                want.yaw = Motion.yawOf(ax, az);
            }
            if (entry && ad < 1.5) {
                Motion.creep(want, b.vx, b.vz, ax, az, ad);
            } else {
                want.forward = !column && !slideDown;
            }
            want.sprint = sprint && dist > 3 && !onLadder && !entry && !Motion.nearVoid(level, b.x, b.z, b.y, 1.5, b.halfW);
            boolean jump = false;
            if (climbUp) {
                jump = true;
            } else if (jumpCooldown > 0) {
                jumpCooldown--;
            } else if (b.onGround && !column && !entry) {
                double nx = ax / ad, nz = az / ad;
                boolean boxJump = !Double.isNaN(Nav.jumpNeeded(level, b.box(), 0.6, nx, nz, 0.35));
                boolean planJump = wp != null && wp.jump && ad < 1.3 && wp.y > b.y + 0.6 + 1e-3;
                if (boxJump || planJump) {
                    jump = true;
                    jumpCooldown = 6;
                }
            }
            want.jump = jump;
            if (b.onGround || onLadder) {
                supportY = b.y;
            }
            Motion.Keys use = want;
            if (guard) {
                b.supportY = supportY;
                Motion.Body seen = b;
                if (guardSeesAPlayer) {
                    seen = b.copy();
                    seen.halfW = Motion.HALF_W;
                    seen.height = Motion.HEIGHT;
                    seen.margin = Motion.CORE;          // and the old fixed margin
                }
                Motion.Choice ch = Motion.choose(level, seen, want);
                use = ch.keys;
                if (ch.level > 0) {
                    r.guardTicks++;
                }
                boolean resting = b.hspeed() < 0.03 && (b.onGround || onLadder);
                if (!ch.holding()) {
                    edgeHold = 0;
                } else if (resting && ++edgeHold >= 10) {
                    edgeHold = 0;
                    r.edgeStops++;
                    if (straight || wp == null) {
                        r.edge = true;
                        r.why = "final approach: " + ch.risk;
                        return r;
                    }
                    noGo.add(Nav.column(wp.bx, wp.bz));
                    if (++plannedStops >= 4) {
                        r.edge = true;
                        r.why = "planned: " + ch.risk;
                        return r;
                    }
                    path = null;
                    continue;
                }
            }
            yaw = use.yaw;
            if (DEBUG) {
                System.out.println("T" + t + " " + b + " idx " + idx + (wp == null ? "" : " wp " + wp.bx + "," + wp.y + "," + wp.bz + (wp.hang ? " hang" : ""))
                        + (entry ? " entry ad=" + String.format("%.3f", ad) : "") + " keys " + use);
            }
            Motion.tick(level, b, use);
            if (pos.distanceTo(lastProgress) > 0.6) {
                lastProgress = pos;
                stuck = 0;
            } else {
                stuck++;
            }
        }
        r.end = b;
        return r;
    }

    static boolean DEBUG;

    @Test
    void privateIslandIsletRimPlaytestWalkNeverFalls() {
        // Wave-4 final verification #16: W4Verify's island origin was (1024, ?, -2048); the ledge
        // (979.5, 72, -2086.3) is (-44.5, 72, -38.3) here, spawn is (-24.5, 73, 0.5). The walk was
        // toward (spawn.x - 300, spawn.z + 40) with sprint, and it fell off at (973, 73, -2086.5) =
        // (-51, 73, -38.5): the one-wide staircase ledge along the islet's north rim.
        NavWorldTest.Fixture f = NavWorldTest.load("private_island_nw");
        Vec3 spawn = f.anchor("spawn");
        Vec3 ledge = new Vec3(-44.5, 72, -38.3);
        assertTrue(NavWorldTest.standable(f, ledge), "the ledge is where the playtest stood");
        Vec3 target = new Vec3(spawn.x - 300, spawn.y, spawn.z + 40);
        Walk raw = walk(f, ledge, target, true, false, 1200);
        System.out.println("islet rim, no guard: " + raw);
        assertTrue(raw.fell, "the fixture reproduces the fall without the guard: " + raw);
        Walk w = walk(f, ledge, target, true, true, 1200);
        System.out.println("islet rim, guard: " + w);
        assertFalse(w.fell, "the guard keeps the player on the island: " + w);
        assertTrue(w.end.x < -50, "and still walks the ledge as far as it goes: " + w);
    }

    @Test
    void privateIslandWalksTowardTheVoidNeverFall() {
        // Ten walks from spawn and from the islet toward points out in the void past the west and
        // north rims, sprinting: every one must end standing on the island.
        NavWorldTest.Fixture f = NavWorldTest.load("private_island_nw");
        Vec3 spawn = f.anchor("spawn");
        List<Vec3[]> walks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(100 + i * 25);     // west-south-west round to north-north-west
            walks.add(new Vec3[] {spawn, new Vec3(spawn.x + Math.cos(a) * 300, spawn.y, spawn.z - Math.sin(a) * 300)});
        }
        Vec3 islet = new Vec3(-44.5, 77, -33.5);
        assertTrue(NavWorldTest.standable(f, islet), "islet start");
        walks.add(new Vec3[] {islet, new Vec3(-320, 77, -40)});
        walks.add(new Vec3[] {islet, new Vec3(-60, 77, -320)});
        walks.add(new Vec3[] {new Vec3(-44.5, 72, -38.3), new Vec3(-324.5, 73, 40.5)});
        walks.add(new Vec3[] {new Vec3(-44.5, 72, -38.3), spawn});
        int falls = 0;
        for (Vec3[] wk : walks) {
            Walk w = walk(f, wk[0], wk[1], true, true, 1600);
            System.out.println("from " + wk[0] + " to " + wk[1] + ": " + w);
            if (w.fell) {
                falls++;
            }
        }
        assertEquals(0, falls, "walks that fell off the island");
    }

    @Test
    void tavernLadderIsDescendedAndClimbed() {
        // Wave-4 #7: from the Tavern's upper floor the walker circled the ladder hole at (59, 80, 17)
        // for four minutes. The hole is one block; the ladder's panel on its north side leaves a
        // 0.21-wide window on z where the whole 0.6-wide body is over the hole.
        NavWorldTest.Fixture f = NavWorldTest.load("hub_tavern_graveyard");
        Vec3 g = Nav.grabPoint(f, 59, 80, 17);
        // the ladder faces east: its panel is on the hole's west side (x 59..59.1875), so x must be
        // 59.4875..59.7 - the cell centre is 0.0125 from standing on the ladder's top edge
        assertTrue(g.x >= 59.4875 + 0.05 && g.x <= 59.7 - 0.05, "grab point x inside the window with room: " + g);
        assertTrue(g.z >= 17.35 && g.z <= 17.65, "grab point z: " + g);
        assertTrue(Nav.dropsAt(f, g.x, 80, g.z));
        assertFalse(Nav.dropsAt(f, 59.45, 80, 17.5), "the ladder's top edge holds a body that is not clear of it");

        Vec3 below = new Vec3(61.5, 75, 19.5);
        Vec3[] upstairs = {new Vec3(61.5, 80, 19.5), new Vec3(63.5, 80, 17.5), new Vec3(60.5, 80, 20.5)};
        for (Vec3 s : upstairs) {
            assertTrue(NavWorldTest.standable(f, s), "upstairs spot " + s);
            Walk down = walk(f, s, below, true, true, 600);
            System.out.println("tavern down from " + s + ": " + down);
            assertTrue(down.arrived, "down the ladder from " + s + ": " + down);
            assertTrue(down.ticks < 300, "in under 15 s: " + down);
        }
        Walk up = walk(f, below, upstairs[0], true, true, 600);
        System.out.println("tavern up: " + up);
        assertTrue(up.arrived, "up the ladder: " + up);
    }

    // ------------------------------------------------------------------ wave 5: Rat form

    /** The pets module's Rat "Shapeshift" + "Turbo Tail": SCALE 0.3 (0.18 x 0.54), speed x2. */
    static Motion.Body rat() {
        Motion.Body r = new Motion.Body();
        r.halfW = 0.09;
        r.height = 0.54;
        r.speed = 0.2;
        return r;
    }

    static Motion.Body shaped(Motion.Body shape, double x, double y, double z) {
        Motion.Body b = at(x, y, z);
        b.halfW = shape.halfW;
        b.height = shape.height;
        b.speed = shape.speed;
        return b;
    }

    @Test
    void ratBodyIsItsOwnSizeAndSpeed() {
        NavTest.World flat = new NavTest.World();
        // twice the speed: sprint 0.561 a tick instead of 0.281
        Motion.Body b = shaped(rat(), 0.5, 64, 0.5);
        double last = b.z, step = 0;
        for (int t = 0; t < 60; t++) {
            Motion.tick(flat, b, keys(0, true, true, false));
            step = b.z - last;
            last = b.z;
        }
        assertEquals(0.2806 * 2, step, 0.004, "rat sprint per tick");
        // two lone wall posts a block apart leave a 0.5 slot (x 0.75..1.25): it stops a person and
        // lets a rat through - a guard that plays a person's body sees a wall where the rat sees a way on
        NavTest.World slot = new NavTest.World()
                .set(0, 64, 3, Blocks.COBBLESTONE_WALL.defaultBlockState())
                .set(1, 64, 3, Blocks.COBBLESTONE_WALL.defaultBlockState());
        Motion.Body person = at(1.0, 64, 0.5);
        Motion.Body small = shaped(rat(), 1.0, 64, 0.5);
        for (int t = 0; t < 30; t++) {
            Motion.tick(slot, person, keys(0, true, false, false));
            Motion.tick(slot, small, keys(0, true, false, false));
        }
        assertTrue(person.z < 3.0 && person.horizontalCollision, "the slot stops a person: " + person);
        assertTrue(small.z > 4.5, "a rat walks through it: " + small);
        // over an edge: the 0.18 box falls once its centre is 0.09 past, where a person still stands
        NavTest.World island = NavTest.island();
        Motion.Body r = shaped(rat(), 6.12, 64, 0.5);
        Motion.Body p = at(6.12, 64, 0.5);
        Motion.Keys none = keys(0, false, false, false);
        for (int t = 0; t < 10; t++) {
            Motion.tick(island, r, none);
            Motion.tick(island, p, none);
        }
        assertTrue(r.y < 63, "a rat whose centre is 0.12 past the edge falls: " + r);
        assertEquals(64.0, p.y, 1e-9, "a person there still stands on the rim: " + p);
    }

    @Test
    void edgeMarginIsUnchangedForAPlayerAndWiderForASmallFastBody() {
        NavTest.World island = NavTest.island();
        // a plain player: exactly the old rule (the 0.15 core over ground)
        for (double x : new double[] {5.5, 6.0, 6.1, 6.14, 6.16, 6.2, 6.29}) {
            Motion.Body p = at(x, 64, 0.5);
            assertEquals(Motion.supportUnder(island, x, 0.5, Motion.CORE, 64, Nav.SAFE_DROP), Motion.stands(island, p), "player at x=" + x);
        }
        assertEquals(0.15, Motion.edgeMargin(0.1), 1e-12);
        assertEquals(0.30, Motion.edgeMargin(0.2), 1e-12, "Turbo Tail: twice the speed, twice the margin");
        assertEquals(0.35, Motion.edgeMargin(0.4), 1e-12, "capped");
        assertEquals(0.15, Motion.edgeMargin(0.05), 1e-12, "never under the tuned one");
        // a rat (half-width 0.09, margin 0.30): its centre must stay 0.21 INSIDE the island (x <= 5.79)
        Motion.Body in = shaped(rat(), 5.75, 64, 0.5);
        Motion.Body out = shaped(rat(), 5.85, 64, 0.5);
        Motion.Body rim = shaped(rat(), 6.05, 64, 0.5);
        assertTrue(Motion.stands(island, in), "0.25 inside");
        assertFalse(Motion.stands(island, out), "0.15 inside: inside its margin");
        assertFalse(Motion.stands(island, rim), "past the edge (the old 0.15 core says yes here)");
        assertTrue(Motion.supportUnder(island, 6.05, 0.5, Motion.CORE, 64, Nav.SAFE_DROP), "the old rule, for contrast");
        // and at a corner, both axes
        assertFalse(Motion.stands(island, shaped(rat(), 5.75, 64, 5.85)), "inside on x, too close on z");
    }

    @Test
    void guardKeepsASprintingRatOnAnIsland() {
        NavTest.World island = NavTest.island();
        Object[][] runs = {{-90f, true, false}, {-90f, false, false}, {-90f, true, true}, {-135f, true, true}, {-45f, true, false}};
        for (Object[] r : runs) {
            Motion.Body b = shaped(rat(), -4.5, 64, 0.5);
            Motion.Keys want = keys((float) r[0], true, (boolean) r[1], (boolean) r[2]);
            double low = holdWithGuard(island, b, want, 200);
            assertEquals(64.0, low, 1e-6, "rat never left the island top: " + want + " ended " + b);
            assertTrue(Math.max(Math.abs(b.x), Math.abs(b.z)) > 4.4, "but got up to the edge: " + b);
        }
    }

    @Test
    void ratPushingIntoTheDockEndFenceDoesNotDropThroughTheStrip() {
        // Wave-5 newcontent playtest: in Rat form the pilot went off the NORTH END of the Leviathan dock
        // (Crimson Isle). The deck ends at z = -185.0, but the end fence row stands one block further
        // out (block z -186, arms at z -185.625..-185.375), over the void: a 0.375-wide strip of air
        // between deck and fence. A person (0.6) pressed against that fence still has 0.225 of the deck
        // under it; a rat (0.18) slides along the fence into the strip and drops. The old guard
        // simulated a person: "W into the fence ends standing" - so it let the rat walk in.
        NavWorldTest.Fixture f = NavWorldTest.load("crimson_leviathan_dock");
        Vec3 s = new Vec3(156.5, 71, -182.5);
        assertTrue(NavWorldTest.standable(f, s));
        for (boolean sprint : new boolean[] {false, true}) {
            Motion.Body raw = shaped(rat(), s.x, s.y, s.z);
            for (int t = 0; t < 60; t++) {
                Motion.tick(f, raw, keys(180f, true, sprint, false));
            }
            assertTrue(raw.y < 65, "unguarded, W north: the rat drops between deck and fence: " + raw);

            Motion.Body person = at(s.x, s.y, s.z);
            for (int t = 0; t < 60; t++) {
                Motion.tick(f, person, keys(180f, true, sprint, false));
            }
            assertEquals(71.0, person.y, 1e-9, "a person is held by the fence with the deck under it: " + person);

            Motion.Body old = shaped(rat(), s.x, s.y, s.z);
            double oldLow = old.y;
            for (int t = 0; t < 60; t++) {
                Motion.Body seen = old.copy();
                seen.halfW = Motion.HALF_W;
                seen.height = Motion.HEIGHT;
                seen.margin = Motion.CORE;
                Motion.tick(f, old, Motion.choose(f, seen, keys(180f, true, sprint, false)).keys);
                if (old.onGround) {
                    old.supportY = old.y;
                }
                oldLow = Math.min(oldLow, old.y);
            }
            assertTrue(oldLow < 65, "a guard that plays a person's body lets the rat drop (the playtest): " + old);

            Motion.Body b = shaped(rat(), s.x, s.y, s.z);
            double low = holdWithGuard(f, b, keys(180f, true, sprint, false), 200);
            assertEquals(71.0, low, 1e-9, "the guard with the rat's own body holds it on the deck: " + b);
            // at twice the speed three committed ticks are ~1.3 blocks, so it stops about a block short
            System.out.println("dock end, rat " + (sprint ? "sprint" : "walk") + ": stopped at " + b);
            assertTrue(b.z < -183.5, "and it still got near the end of the deck: " + b);
        }
    }

    @Test
    void ratWalksOffTheLeviathanDockNeverFall() {
        // The same dock as the walker meets it: from the landward end, the middle and the far corner
        // toward points out in the void past the end and both sides, sprinting. (The rails also leave
        // 0.75 gaps beside every fourth post - crimson and nether brick fences do not connect - which
        // a person fits through as well.)
        NavWorldTest.Fixture f = NavWorldTest.load("crimson_leviathan_dock");
        Vec3 npc = f.anchor("npc.leviathan_dock");
        Vec3[] starts = {new Vec3(156.5, 71, -170.5), new Vec3(156.5, 71, -178.5), new Vec3(155.5, 71, -184.3)};
        for (Vec3 s : starts) {
            assertTrue(NavWorldTest.standable(f, s), "dock start " + s);
        }
        List<Vec3> targets = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            double a = Math.toRadians(i * 22.5);             // east, round through north, to west
            targets.add(new Vec3(npc.x + Math.cos(a) * 200, 71, npc.z - Math.sin(a) * 200));
        }
        int falls = 0, oldFalls = 0, runs = 0;
        for (Vec3 s : starts) {
            for (Vec3 t : targets) {
                runs++;
                Walk w = walk(f, s, t, true, true, 900, rat(), false);
                if (w.fell) {
                    falls++;
                    System.out.println("RAT FELL from " + s + " to " + t + ": " + w);
                }
                Walk old = walk(f, s, t, true, true, 900, rat(), true);
                if (old.fell) {
                    oldFalls++;
                }
            }
        }
        System.out.println("leviathan dock, rat: " + falls + "/" + runs + " fell with the guard; " + oldFalls
                + "/" + runs + " fell with a guard that simulates a 0.6-wide player");
        assertEquals(0, falls, "rat walks that fell off the dock");
        assertTrue(oldFalls > 0, "the fixture reproduces the playtest: a person-sized guard lets the rat fall");
    }

    @Test
    void jumpGateNeverDoubleTapsWhileThePlayerMayFly() {
        // the walker's own rhythm: a 1-tick press, then a 6-tick cooldown - the next press lands 7
        // ticks later, inside vanilla's 7-tick double-tap window
        Motion.JumpGate g = new Motion.JumpGate();
        boolean held = false;
        long lastEdge = -1000;
        int presses = 0;
        for (long t = 100; t < 400; t++) {
            boolean want = (t - 100) % 7 == 0;
            boolean down = g.press(t, held, true, want);
            if (down && !held) {
                assertTrue(t - lastEdge > Motion.JumpGate.DOUBLE_TAP, "two presses " + (t - lastEdge) + " ticks apart at " + t);
                lastEdge = t;
                presses++;
            }
            held = down;
        }
        assertTrue(presses >= 20, "still jumps (every other press, 14 ticks apart): " + presses);
        // a player who cannot fly is never held back; nor is a key that is already down (ladder, swim)
        Motion.JumpGate free = new Motion.JumpGate();
        held = false;
        for (long t = 0; t < 50; t++) {
            boolean want = t % 2 == 0;
            assertEquals(want, free.press(t, held, false, want), "no mayfly, tick " + t);
            held = want;
        }
        Motion.JumpGate climb = new Motion.JumpGate();
        assertTrue(climb.press(10, false, true, true));
        for (long t = 11; t < 40; t++) {
            assertTrue(climb.press(t, true, true, true), "held jump stays held, tick " + t);
        }
        // a new player entity (respawn, world change) restarts tickCount: the gate must not lock up
        Motion.JumpGate reset = new Motion.JumpGate();
        assertTrue(reset.press(5000, false, true, true));
        assertTrue(reset.press(3, false, true, true), "tick counter went backwards: a fresh gate");
    }

    @Test
    void aDryWalkStopsOnTheShoreAndAnOrdinaryOneMayWadeIn() {
        // kill_nearest from a dock or a shore (wave-5 regression, the Bayou): the final approach and
        // the "wiggle free" just hold W toward the creature; with dry set, water is refused like a fall.
        NavTest.World pool = NavTest.dockPool();
        for (boolean sprint : new boolean[] {false, true}) {
            Motion.Body b = at(-2.5, 64, 9.5);
            b.dry = true;
            double low = holdWithGuard(pool, b, keys(-90f, true, sprint, false), 160);
            assertEquals(64.0, low, 1e-6, "never stepped off the shore: " + b);
            assertTrue(b.x > -1.2, "but walked right up to the water: " + b);
            assertTrue(b.x < 0.3, "and stands on the bank: " + b);
        }
        // the same walk off the deck's end into open water
        Motion.Body d = at(3.5, 65, 3.5);
        d.dry = true;
        assertEquals(65.0, holdWithGuard(pool, d, keys(0f, true, true, false), 160), 1e-6, "stayed on the deck: " + d);
        assertTrue(d.z > 5.0 && d.z < 6.3, "at the deck's end: " + d);
        // without dry, water still breaks a fall: the guard lets an ordinary walk wade in
        Motion.Body w = at(-2.5, 64, 9.5);
        assertTrue(holdWithGuard(pool, w, keys(-90f, true, false, false), 160) < 63.9, "walked into the pool: " + w);
    }
}
