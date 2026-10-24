package com.skyclone.mod.combatzones;

import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.combatzones.gen.EndGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Playtest 2026-10-03: /warp drag stood the player 2.8 blocks from a Lv55 Zealot, which killed an
 * 844 HP player in about 3 s. Runs the real End generator and checks every warp arrival stands in a
 * SAFE pad, that no mob anchor is within aggro reach of it, and that the pad does not swallow the
 * fighting ground (the crater floor and the dais stay outside it).
 */
class EndArrivalTest {

    static IslandRun.Result run;

    @BeforeAll
    static void generate() {
        EndGenerator gen = new EndGenerator();
        run = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
    }

    @Test
    void arrivalsAreSafeAndClear() {
        assertNull(EndGenerator.arrivalProblem(run.layout));
    }

    @Test
    void dragPadLeavesTheCraterFloorToTheFight() {
        var pad = EndGenerator.dragLanding();
        for (int[] f : EndGenerator.FRAMES)
            assertFalse(pad.contains(EndGenerator.NX + f[0], EndGenerator.FLOOR_Y + 2, EndGenerator.NZ + f[1]), "pad covers an eye frame");
        // every zealot anchor is on the crater floor, none on the pad
        long zealots = run.layout.anchors().keySet().stream().filter(k -> k.startsWith("spawn.mob.zealot.")).count();
        assertTrue(zealots >= 16, "only " + zealots + " zealot anchors left");
        Layout.Anchor a = run.layout.anchor("warp.drag");
        assertTrue(a.y() > EndGenerator.FLOOR_Y + 2, "warp.drag should stand up the ramp, above the floor: y=" + a.y());
    }

    /** Playtest wave4-endgame: from the old centre pillar 4 of the 8 frames were out of reach. */
    @Test
    void everyFrameIsInReachFromThePedestal() {
        EndGenerator.Solid built = (x, y, z) -> !run.canvas.isAir(x, y, z);
        assertNull(EndGenerator.altarReachProblem(run.layout, built));
        Layout.Anchor a = run.layout.anchor("altar.dragon");
        assertEquals(EndGenerator.NX + 0.5, a.x(), 1e-9);
        assertEquals(EndGenerator.NZ + 0.5, a.z(), 1e-9);
        assertEquals(EndGenerator.FLOOR_Y + 3, a.y(), 1e-9, "altar.dragon stands on the pedestal top");
        // the check bites: put the old bedrock post + dragon head back and the far frames are hidden
        EndGenerator.Solid oldPost = (x, y, z) -> built.at(x, y, z)
                || x == EndGenerator.NX && z == EndGenerator.NZ && (y == EndGenerator.FLOOR_Y + 3 || y == EndGenerator.FLOOR_Y + 4);
        String old = EndGenerator.altarReachProblem(run.layout, oldPost);
        assertNotNull(old, "the old centre post should fail the reach check");
        assertTrue(old.contains("hidden") || old.contains("inside"), old);
    }

    @Test
    void arrivalsAreWalkable() {
        Walkability.Options o = new Walkability.Options();
        o.skipFloating = true;
        Walkability.Report walk = Walkability.analyse(run.canvas, run.layout, o);
        for (Walkability.AnchorResult r : walk.anchors)
            if (r.name().equals("warp.drag") || r.name().equals("warp.void") || r.name().equals("altar.dragon"))
                assertEquals(Walkability.Status.REACHABLE, r.status(), r.name());
    }
}
