package com.skyclone.core.items;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The pure parts of the death respawn: floor detection and the instance slot of a position. */
class VitalsRespawnTest {

    @Test
    void floorFoundAtOrBelowTheFeet() {
        // ground at 71, feet at 72 (how anchors are stored)
        assertTrue(VitalsMath.hasFloor(72, -64, VitalsMath.FLOOR_SEARCH_DEPTH, y -> y == 71));
        // standing in water counts
        assertTrue(VitalsMath.hasFloor(72, -64, 8, y -> y == 72));
        // a 4-block drop is fine, 9 is not
        assertTrue(VitalsMath.hasFloor(72, -64, 8, y -> y == 68));
        assertFalse(VitalsMath.hasFloor(72, -64, 8, y -> y == 63));
    }

    @Test
    void voidHasNoFloor() {
        // the integration round 3 case: (0.5, 72, 56.5) in the void default world
        assertFalse(VitalsMath.hasFloor(72, -64, VitalsMath.FLOOR_SEARCH_DEPTH, y -> false));
        // the 7x7 limbo platform at y64 holds a player at y65
        Set<Integer> platform = Set.of(64);
        assertTrue(VitalsMath.hasFloor(65, -64, VitalsMath.FLOOR_SEARCH_DEPTH, platform::contains));
    }

    @Test
    void floorSearchStopsAtMinHeight() {
        int[] lowest = {Integer.MAX_VALUE};
        assertFalse(VitalsMath.hasFloor(-60, -64, 8, y -> { lowest[0] = Math.min(lowest[0], y); return false; }));
        assertEquals(-64, lowest[0]);
    }

    @Test
    void slotOriginMatchesTheInstanceGrid() {
        assertEquals(0, VitalsMath.slotOrigin(-29.5, 1024), 1e-9);
        assertEquals(1024, VitalsMath.slotOrigin(1024 - 29.5, 1024), 1e-9);
        assertEquals(-2048, VitalsMath.slotOrigin(-2048 + 400, 1024), 1e-9);
        assertEquals(-1024, VitalsMath.slotOrigin(-1024 - 400, 1024), 1e-9);
        // spacing below 16 is clamped like core.world does
        assertEquals(16, VitalsMath.slotOrigin(14, 4), 1e-9);
    }
}
