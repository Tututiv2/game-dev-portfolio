package com.skyclone.core.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A change of max health / mana keeps the fill fraction instead of clamping (playtest-loops armour swap). */
class VitalsRescaleTest {

    @Test
    void fullStaysFullThroughAnArmourSwap() {
        // the reported case: 596/596, armour off -> max 117, a new set on -> max 700
        double off = VitalsMath.rescale(596, 596, 117);
        assertEquals(117, off);
        assertEquals(700, VitalsMath.rescale(off, 117, 700));
    }

    @Test
    void offAndBackOnReturnsTheSameHealth() {
        double hp = 250;
        double off = VitalsMath.rescale(hp, 596, 117);
        assertTrue(off < 117);
        assertEquals(hp, VitalsMath.rescale(off, 117, 596), 1e-9);
    }

    @Test
    void fractionIsKept() {
        assertEquals(50, VitalsMath.rescale(298, 596, 100), 1e-9);
        assertEquals(350, VitalsMath.rescale(50, 100, 700), 1e-9);
    }

    @Test
    void unknownOldMaxClamps() {
        assertEquals(117, VitalsMath.rescale(596, -1, 117));
        assertEquals(80, VitalsMath.rescale(80, -1, 117));
        assertEquals(80, VitalsMath.rescale(80, 0, 117));
    }

    @Test
    void emptyStaysEmptyAndBadInputIsSafe() {
        assertEquals(0, VitalsMath.rescale(0, 596, 117));
        assertEquals(0, VitalsMath.rescale(Double.NaN, 596, 117));
        assertEquals(0, VitalsMath.rescale(50, 596, 0));
        assertEquals(117, VitalsMath.rescale(596, Double.NaN, 117));
        assertEquals(100, VitalsMath.rescale(100, 100, 100));
    }

    @Test
    void neverExceedsTheNewMax() {
        // a value already above its recorded max (stale record) still lands at most at the new max
        assertEquals(117, VitalsMath.rescale(900, 596, 117));
    }
}
