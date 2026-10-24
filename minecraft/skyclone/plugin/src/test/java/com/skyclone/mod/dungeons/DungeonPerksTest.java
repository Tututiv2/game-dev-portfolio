package com.skyclone.mod.dungeons;

import com.skyclone.mod.dungeons.model.DungeonPerks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5 (2026-10-06): the pure rules behind stars, dungeon accessories, pet perks, class passives and the floor gate. */
class DungeonPerksTest {

    @Test
    void starsInsideTheCatacombs() {
        // research 04 sec 3.15: +10%/star in the Catacombs (2% of it is the reforges module's everywhere-bonus)
        assertEquals(0.0, DungeonPerks.starFraction(0, true, 0.10, 0.05), 1e-9);
        assertEquals(0.24, DungeonPerks.starFraction(3, false, 0.10, 0.05), 1e-9);
        assertEquals(0.40, DungeonPerks.starFraction(5, true, 0.10, 0.05), 1e-9);
        // master stars: nothing outside Master Mode, +5% each inside (research 01 sec 16.2)
        assertEquals(0.40, DungeonPerks.starFraction(10, false, 0.10, 0.05), 1e-9);
        assertEquals(0.65, DungeonPerks.starFraction(10, true, 0.10, 0.05), 1e-9);
        assertEquals(0.50, DungeonPerks.starFraction(7, true, 0.10, 0.05), 1e-9);
        // never more than 5 + 5
        assertEquals(0.65, DungeonPerks.starFraction(15, true, 0.10, 0.05), 1e-9);
    }

    @Test
    void generalsMedallionCountsDigits() {
        assertEquals(0, DungeonPerks.medallionPercent(0));
        assertEquals(1, DungeonPerks.medallionPercent(7));
        assertEquals(2, DungeonPerks.medallionPercent(10));
        assertEquals(4, DungeonPerks.medallionPercent(9_999));
        assertEquals(5, DungeonPerks.medallionPercent(12_345));
        assertEquals(5, DungeonPerks.medallionPercent(4_000_000_000L));
    }

    @Test
    void spiritHalfLifeIsLegendaryOnly() {
        assertTrue(DungeonPerks.halfLife("SPIRIT", "LEGENDARY"));
        assertTrue(DungeonPerks.halfLife("spirit", "mythic"));
        assertFalse(DungeonPerks.halfLife("SPIRIT", "EPIC"));
        assertFalse(DungeonPerks.halfLife("JELLYFISH", "LEGENDARY"));
        assertFalse(DungeonPerks.halfLife(null, "LEGENDARY"));
    }

    @Test
    void storedChargeTrimsWishInSteps() {
        assertEquals(0, DungeonPerks.storedChargeTrim(1_999, 2_000, 1, 30), 1e-9);
        assertEquals(1, DungeonPerks.storedChargeTrim(2_000, 2_000, 1, 30), 1e-9);
        assertEquals(5, DungeonPerks.storedChargeTrim(10_500, 2_000, 1, 30), 1e-9);
        assertEquals(30, DungeonPerks.storedChargeTrim(1e9, 2_000, 1, 30), 1e-9);
        assertEquals(0, DungeonPerks.storedChargeTrim(1e9, 2_000, 0, 30), 1e-9);
    }

    @Test
    void overhealIsCappedByAbsorption() {
        double cap = DungeonPerks.overhealCapFraction(0);
        assertEquals(0.20, cap, 1e-9);
        assertEquals(0.40, DungeonPerks.overhealCapFraction(50), 1e-9);
        // 900/1000 healed for 300: 200 overflow, all of it fits under the 200 cap
        assertEquals(200, DungeonPerks.overheal(900, 1000, 300, 0, cap), 1e-9);
        // 150 absorption already: only 50 more fits
        assertEquals(50, DungeonPerks.overheal(900, 1000, 300, 150, cap), 1e-9);
        // a heal that does not reach full health gives nothing
        assertEquals(0, DungeonPerks.overheal(500, 1000, 300, 0, cap), 1e-9);
    }

    @Test
    void restlessAndStrongBrewAndIndomitable() {
        assertEquals(20_000, DungeonPerks.restlessCooldown(20_000, 0));
        assertEquals(10_000, DungeonPerks.restlessCooldown(20_000, 50));
        assertEquals(1_000, DungeonPerks.restlessCooldown(2_000, 90));
        assertEquals(15.0, DungeonPerks.strongBrew(10, 50), 1e-9);
        assertEquals(10.0, DungeonPerks.strongBrew(10, -5), 1e-9);
        assertEquals(100.0, DungeonPerks.indomitable(1_000, 0), 1e-9);
        assertEquals(200.0, DungeonPerks.indomitable(1_000, 50), 1e-9);
        assertEquals(0.0, DungeonPerks.indomitable(-5, 10), 1e-9);
    }

    @Test
    void floorGate() {
        assertNull(DungeonPerks.previousFloor("E"));
        assertNull(DungeonPerks.previousFloor("F1"));
        assertNull(DungeonPerks.previousFloor("M1"));
        assertEquals("F1", DungeonPerks.previousFloor("F2"));
        assertEquals("F6", DungeonPerks.previousFloor("F7"));
        assertEquals("M6", DungeonPerks.previousFloor("M7"));
        assertNull(DungeonPerks.previousFloor("X3"));
        assertNull(DungeonPerks.previousFloor("F9"));
    }
}
