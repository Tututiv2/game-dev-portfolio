package com.skyclone.mod.weapons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wave 5 formulas: bestiary tiers, Fear, souls, dungeon class blades, floor rolls. Pure: no server needed. */
class WeaponWave5MathTest {

    private static final double E = 1e-9;

    @Test
    void bestiaryTierCountsThresholdsUnderCapAndMaxTier() {
        int[] thr = {10, 25, 50, 100};
        assertEquals(0, WeaponMath.bestiaryTier(0, thr, 0, 0));
        assertEquals(0, WeaponMath.bestiaryTier(9, thr, 0, 0));
        assertEquals(1, WeaponMath.bestiaryTier(10, thr, 0, 0));
        assertEquals(3, WeaponMath.bestiaryTier(99, thr, 0, 0));
        assertEquals(2, WeaponMath.bestiaryTier(5000, thr, 25, 0));    // thresholds above the kill cap never count
        assertEquals(3, WeaponMath.bestiaryTier(5000, thr, 0, 3));     // maxTier clamps
        assertEquals(0, WeaponMath.bestiaryTier(50, null, 0, 0));
    }

    @Test
    void labyrinthBladePerTier() {
        assertEquals(12, WeaponMath.perTier(12, 1), E);                // +1% damage per tier
        assertEquals(2.4, WeaponMath.perTier(12, 0.2), E);             // +0.2 Magic Find per tier
        assertEquals(0, WeaponMath.perTier(-3, 1), E);
    }

    @Test
    void fearAddsEverySourceAndCapsFearcreasing() {
        assertEquals(20, WeaponMath.fearSum(20, 0, 0, 0, 0));          // 4 Great Spook armour pieces
        assertEquals(40, WeaponMath.fearSum(40, 0, 0, 0, 0));          // + belt, cloak, necklace, gloves
        assertEquals(15, WeaponMath.fearSum(0, 0, 0, 150, 1));         // Fearcreasing: +1 per 10, 150 kills max
        assertEquals(15, WeaponMath.fearSum(0, 0, 0, 10_000, 1));
        assertEquals(11, WeaponMath.fearSum(0, 0, 0, 119, 1));
        assertEquals(43, WeaponMath.fearSum(20, 5, 3, 150, 1));
        assertEquals(20, WeaponMath.fearTotal(4, 0, 0, 0));            // the old entry point still agrees
        assertEquals(6, WeaponMath.bestOf(2, 6, 4));
        assertEquals(0, WeaponMath.bestOf());
        assertEquals(8.0, WeaponMath.fearama(20, 0.4), E);             // Eerie: 0.4% per Fear
    }

    @Test
    void ghoulSoulsAndChance() {
        assertEquals(2, WeaponMath.soulCapacity(2, false));
        assertEquals(4, WeaponMath.soulCapacity(2, true));             // Army of the Dead +2
        assertEquals(0, WeaponMath.soulCapacity(0, true));             // not a soul blade: still none
        assertEquals(1.0, WeaponMath.soulChance(100, 0), E);
        assertEquals(0.6, WeaponMath.soulChance(50, 20), E);
        assertEquals(1.0, WeaponMath.soulChance(90, 50), E);           // capped at 100%
    }

    @Test
    void adaptiveBladeClassBonuses() {
        assertEquals(3.75, WeaponMath.adaptiveMageMana(1000, 25), E);  // 25% of (5 + 1% max mana)
        assertEquals(1.25, WeaponMath.adaptiveMageMana(0, 25), E);
        assertEquals(99, WeaponMath.healerShare(300, 33), E);
        assertEquals(0, WeaponMath.healerShare(0, 33), E);
        assertEquals(0, WeaponMath.healerShare(-40, 33), E);
        assertEquals(50, WeaponMath.healerShare(50, 250), E);          // never more than the heal itself
    }

    @Test
    void floorRollsAddOverTheLowestRoll() {
        double[] zks = {82, 86, 91, 95, 101, 106, 111, 117, 123, 130}; // Zombie Knight Sword damage
        assertEquals(0, WeaponMath.tierDelta(zks, 0), E);
        assertEquals(9, WeaponMath.tierDelta(zks, 2), E);
        assertEquals(48, WeaponMath.tierDelta(zks, 9), E);
        assertEquals(48, WeaponMath.tierDelta(zks, 40), E);            // past the end = last roll
        assertEquals(0, WeaponMath.tierDelta(new double[]{50}, 3), E);
        assertEquals("Entrance", WeaponMath.floorName(0));
        assertEquals("Floor 7", WeaponMath.floorName(7));
        assertEquals("Master 1", WeaponMath.floorName(8));
    }
}
