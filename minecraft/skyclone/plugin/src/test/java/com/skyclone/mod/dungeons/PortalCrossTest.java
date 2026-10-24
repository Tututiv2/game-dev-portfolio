package com.skyclone.mod.dungeons;

import com.skyclone.api.world.gen.Box;
import com.skyclone.mod.dungeons.gen.Geo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixer (integrate-w2-1): the boss portal counts a move THROUGH its box, not only a sample that lands inside it. */
class PortalCrossTest {

    // the blood room's boss_portal box: 5 wide, 6 high, 3 deep (local coords as the room lays it out)
    static final Box PORTAL = Box.of(12, 101, 14, 16, 106, 16);

    @Test
    void standingInsideCounts() {
        assertTrue(Geo.crossed(PORTAL, null, new double[]{14.5, 101, 15.5}, 12));
    }

    @Test
    void fastMoveAcrossTheFrameCounts() {
        // 5 blocks in one sample (a speed dash or a short teleport): both ends outside, the path inside
        assertTrue(Geo.crossed(PORTAL, new double[]{14.5, 101, 12.5}, new double[]{14.5, 101, 17.5}, 12));
    }

    @Test
    void walkingPastOrBesideDoesNot() {
        assertFalse(Geo.crossed(PORTAL, new double[]{10.5, 101, 12.5}, new double[]{10.5, 101, 18.5}, 12));
        assertFalse(Geo.crossed(PORTAL, null, new double[]{14.5, 101, 13.5}, 12));
        assertFalse(Geo.crossed(null, null, new double[]{14.5, 101, 15.5}, 12));
    }

    @Test
    void aLongWarpOnlyTestsWhereItLands() {
        assertFalse(Geo.crossed(PORTAL, new double[]{14.5, 101, -40}, new double[]{14.5, 101, 60}, 12));
    }
}
