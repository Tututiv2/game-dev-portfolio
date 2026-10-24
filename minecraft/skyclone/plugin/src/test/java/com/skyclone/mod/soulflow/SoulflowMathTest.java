package com.skyclone.mod.soulflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Soulstream arithmetic against the reference numbers (research 01/06 + NEU lore). */
class SoulflowMathTest {

    @Test
    void chargeIsAllOrNothingAndPoolGoesFirst() {
        SoulflowMath.Plan p = SoulflowMath.plan(10, true, 5, true, 12);
        assertTrue(p.ok());
        assertEquals(10, p.fromPool());
        assertEquals(2, p.fromRaw());

        SoulflowMath.Plan refused = SoulflowMath.plan(10, true, 5, true, 16);
        assertFalse(refused.ok());
        assertEquals(0, refused.fromPool());
        assertEquals(0, refused.fromRaw());

        // pool locked (no accessory): only loose raw pays
        assertFalse(SoulflowMath.plan(1000, false, 0, true, 1).ok());
        SoulflowMath.Plan rawOnly = SoulflowMath.plan(1000, false, 3, true, 3);
        assertTrue(rawOnly.ok());
        assertEquals(0, rawOnly.fromPool());
        assertEquals(3, rawOnly.fromRaw());

        // inventory fallback off: raw is ignored
        assertFalse(SoulflowMath.plan(0, true, 50, false, 1).ok());
        assertTrue(SoulflowMath.plan(0, true, 0, true, 0).ok());
        assertEquals(1005, SoulflowMath.available(1000, true, 5, true));
        assertEquals(5, SoulflowMath.available(1000, false, 5, true));
    }

    @Test
    void overflowConversionsMatchTheAccessories() {
        // Soulflow Pile: up to 5 into 200 Overflow
        SoulflowMath.Conversion pile = SoulflowMath.convert(200, 5, 0, 1000);
        assertEquals(5, pile.units());
        assertEquals(200, pile.overflowAfter(), 1e-9);
        // Battery: up to 4 into 400; Supercell: up to 4 into 600
        assertEquals(400, SoulflowMath.convert(400, 4, 0, 1000).overflowAfter(), 1e-9);
        SoulflowMath.Conversion cell = SoulflowMath.convert(600, 4, 0, 1000);
        assertEquals(4, cell.units());
        assertEquals(600, cell.overflowAfter(), 1e-9);
        // End Portal Fumes (+30%): 600 -> 780, the research's "current max"
        assertEquals(780, SoulflowMath.overflowTarget(600, 30), 1e-9);
        assertEquals(600, SoulflowMath.overflowTarget(600, 0), 1e-9);
    }

    @Test
    void conversionOnlyTopsUpAndNeverOverspends() {
        // already full: nothing burned
        SoulflowMath.Conversion full = SoulflowMath.convert(600, 4, 600, 50);
        assertFalse(full.happened());
        assertEquals(600, full.overflowAfter(), 1e-9);
        // 150 per unit; missing 100 burns one unit and stops at the target
        SoulflowMath.Conversion top = SoulflowMath.convert(600, 4, 500, 50);
        assertEquals(1, top.units());
        assertEquals(600, top.overflowAfter(), 1e-9);
        // pool shorter than the tier: partial conversion
        SoulflowMath.Conversion poor = SoulflowMath.convert(600, 4, 0, 2);
        assertEquals(2, poor.units());
        assertEquals(300, poor.overflowAfter(), 1e-9);
        // empty pool: nothing
        assertFalse(SoulflowMath.convert(600, 4, 0, 0).happened());
    }

    @Test
    void overflowPaysOnlyWhatManaLacks() {
        assertEquals(0, SoulflowMath.overflowDraw(500, 180, 600, 1000), 1e-9);    // enough mana
        assertEquals(80, SoulflowMath.overflowDraw(100, 180, 600, 1000), 1e-9);   // 80 missing
        assertEquals(50, SoulflowMath.overflowDraw(100, 180, 50, 1000), 1e-9);    // only 50 held
        assertEquals(20, SoulflowMath.overflowDraw(180, 1200, 600, 200), 1e-9);   // capped by max mana
        assertEquals(0, SoulflowMath.overflowDraw(0, 0, 600, 200), 1e-9);         // free ability
        // core's rule: a mana field in (0,1) is a fraction of max
        assertEquals(250, SoulflowMath.manaCost(0.25, 1000), 1e-9);
        assertEquals(180, SoulflowMath.manaCost(180, 1000), 1e-9);
    }

    @Test
    void poolCapAndClocks() {
        assertEquals(15, SoulflowMath.addCapped(10, 5, 0));
        assertEquals(12, SoulflowMath.addCapped(10, 5, 12));
        assertEquals(SoulflowMath.HARD_CAP, SoulflowMath.addCapped(SoulflowMath.HARD_CAP - 1, Long.MAX_VALUE / 2, 0));
        assertEquals(10, SoulflowMath.addCapped(10, -3, 0));

        assertTrue(SoulflowMath.due(0, 5, 1_000));
        assertFalse(SoulflowMath.due(10_000, 5, 14_999));
        assertTrue(SoulflowMath.due(10_000, 5, 15_000));

        assertTrue(SoulflowMath.inCombat(10_000, 8, 17_999));
        assertFalse(SoulflowMath.inCombat(10_000, 8, 18_000));
        assertFalse(SoulflowMath.inCombat(0, 8, 5));
        assertEquals("1,234⸎", SoulflowMath.units(1234));
    }
}
