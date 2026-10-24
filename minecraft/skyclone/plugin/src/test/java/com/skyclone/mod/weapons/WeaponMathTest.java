package com.skyclone.mod.weapons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WeaponMath against the research 06 numbers. Pure: no server needed. */
class WeaponMathTest {

    private static final double E = 1e-9;

    @Test
    void shortbowCooldownShrinksWithAttackSpeedAndCaps() {
        assertEquals(0.5, WeaponMath.shortbowCooldown(0.5, 0, 0.1), E);
        assertEquals(0.25, WeaponMath.shortbowCooldown(0.5, 100, 0.1), E);
        assertEquals(0.25, WeaponMath.shortbowCooldown(0.5, 999, 0.1), E);        // Bonus Attack Speed caps at 100
        assertEquals(0.3, WeaponMath.shortbowCooldown(0.5, 100, 0.3), E);          // dial floor wins
        assertEquals(0.75 / 1.4, WeaponMath.shortbowCooldown(0.75, 40, 0.1), E);
    }

    @Test
    void gildedSwordCurveHitsItsEndpoints() {
        assertEquals(0, WeaponMath.midasSwordBonus(0), E);
        assertEquals(20, WeaponMath.midasSwordBonus(1_000_000), E);
        assertEquals(35, WeaponMath.midasSwordBonus(2_500_000), E);
        assertEquals(60, WeaponMath.midasSwordBonus(7_500_000), E);
        assertEquals(95, WeaponMath.midasSwordBonus(25_000_000), E);
        assertEquals(120, WeaponMath.midasSwordBonus(50_000_000), E);
        assertEquals(120, WeaponMath.midasSwordBonus(500_000_000), E);
        assertEquals(26_000, WeaponMath.midasStaffBonus(100_000_000), E);
        assertEquals(13_000, WeaponMath.midasStaffBonus(50_000_000), E);
    }

    @Test
    void emeraldBladeIsFourthRootOfPurseCapped() {
        assertEquals(0, WeaponMath.emeraldBlade(0, 2.5, 2e9), E);
        assertEquals(2.5 * 10, WeaponMath.emeraldBlade(10_000, 2.5, 2e9), 1e-6);
        assertEquals(WeaponMath.emeraldBlade(2e9, 2.5, 2e9), WeaponMath.emeraldBlade(9e9, 2.5, 2e9), E);
    }

    @Test
    void hurricaneMilestones() {
        assertEquals(1, WeaponMath.hurricaneArrows(19));
        assertEquals(2, WeaponMath.hurricaneArrows(20));
        assertEquals(3, WeaponMath.hurricaneArrows(50));
        assertEquals(4, WeaponMath.hurricaneArrows(100));
        assertEquals(5, WeaponMath.hurricaneArrows(250));
        assertEquals(20, WeaponMath.hurricaneNext(0));
        assertEquals(250, WeaponMath.hurricaneNext(100));
        assertEquals(-1, WeaponMath.hurricaneNext(250));
    }

    @Test
    void counterWeapons() {
        assertEquals(0, WeaponMath.raiderDamage(499));
        assertEquals(1, WeaponMath.raiderDamage(500));
        assertEquals(35, WeaponMath.raiderDamage(100_000));
        assertEquals(100, WeaponMath.raiderStrength(1_000_000));
        assertEquals(165, WeaponMath.tacticianDamage(11));
        assertEquals(20, WeaponMath.perHealth(1_049, 50), E);
        assertEquals(100, WeaponMath.killsDamage(1_000_000, 100, 100));
        assertEquals(3, WeaponMath.killsDamage(399, 100, 100));
    }

    @Test
    void healthDrivenBonuses() {
        assertEquals(100, WeaponMath.missingHealthBonus(50, 100, 2), E);   // Spirit Sword: +2% per 1% missing
        assertEquals(0, WeaponMath.missingHealthBonus(100, 100, 2), E);
        assertEquals(100, WeaponMath.badHealthStrength(1000, 1000));
        assertEquals(50, WeaponMath.badHealthStrength(520, 1000));
        assertEquals(10, WeaponMath.petalBonus(50, 100), E);               // +2% per 10% missing
    }

    @Test
    void focusBurnCaps() {
        assertArrayEquals(new double[]{0, 0}, WeaponMath.focusBurn(99), E);
        assertArrayEquals(new double[]{5, 125}, WeaponMath.focusBurn(560), E);
        assertArrayEquals(new double[]{20, 500}, WeaponMath.focusBurn(100_000), E);
    }

    @Test
    void multipliers() {
        assertEquals(1.5, WeaponMath.strengthMultiplier(0, 50), E);
        assertEquals(3.0 / 2.0, WeaponMath.strengthMultiplier(100, 100), E);
        assertEquals(1, WeaponMath.defenseIgnoreMultiplier(0, 5), E);
        assertEquals(200.0 / 195.0, WeaponMath.defenseIgnoreMultiplier(100, 5), E);
        assertEquals(16, WeaponMath.doubling(4), E);
        assertEquals(16, WeaponMath.doubling(99), E);
        assertEquals(90, WeaponMath.streakCost(30, 2, 30), E);
        assertEquals(500, WeaponMath.pearlDamage(1e9), E);
        assertEquals(10, WeaponMath.pearlDamage(100), E);
        assertEquals(0.85 * 0.85, WeaponMath.bounceMultiplier(2), E);
        assertEquals(1.3, WeaponMath.skewerMultiplier(9), E);
        assertEquals(2.5, WeaponMath.dividedCrit(10, 4), E);
    }

    @Test
    void geometry() {
        assertArrayEquals(new double[]{-7, 0, 7}, WeaponMath.spread(3, 7), E);
        assertArrayEquals(new double[]{0}, WeaponMath.spread(1, 7), E);
        assertTrue(WeaponMath.inCone(1, 0.2, 1, 0, 45));
        assertFalse(WeaponMath.inCone(-1, 0, 1, 0, 45));
        assertFalse(WeaponMath.inCone(0, 1, 1, 0, 45));
        assertEquals(1, WeaponMath.distToSegment(5, 1, 0, 0, 0, 0, 10, 0, 0), E);
        assertEquals(5, WeaponMath.distToSegment(-3, 4, 0, 0, 0, 0, 10, 0, 0), E);
    }

    /** Playtest: Catacombs requirements were enforced in the Graveyard. Reference: dungeon runs only. */
    @Test
    void catacombsRequirementOnlyAppliesInsideDungeonsByDefault() {
        assertFalse(WeaponMath.catacombsApplies("dungeon", false));
        assertTrue(WeaponMath.catacombsApplies("dungeon", true));
        assertFalse(WeaponMath.catacombsApplies("combat", false));     // retired dial value reads as the reference rule
        assertTrue(WeaponMath.catacombsApplies("combat", true));
        assertFalse(WeaponMath.catacombsApplies(null, false));
        assertTrue(WeaponMath.catacombsApplies("everywhere", false));
        assertTrue(WeaponMath.catacombsApplies(" Everywhere ", false));
        assertFalse(WeaponMath.catacombsApplies("none", true));
    }

    /** Research 01: Infinite Quiver is 3% per level, X = 30%. */
    @Test
    void infiniteQuiverChance() {
        assertEquals(0, WeaponMath.infiniteQuiverPct(0, 3), E);
        assertEquals(15, WeaponMath.infiniteQuiverPct(5, 3), E);
        assertEquals(30, WeaponMath.infiniteQuiverPct(10, 3), E);
        assertEquals(100, WeaponMath.infiniteQuiverPct(50, 3), E);
        assertEquals(0, WeaponMath.infiniteQuiverPct(10, 0), E);
    }

    /** Research 01: Transmission Tuner +1 block each, max 4; research 06: Ether Transmission 57 blocks. */
    @Test
    void transmissionTunersAddOneBlockEachUpToFour() {
        assertEquals(8, WeaponMath.teleportRange(8, 0), E);
        assertEquals(12, WeaponMath.teleportRange(8, 4), E);
        assertEquals(12, WeaponMath.teleportRange(8, 9), E);
        assertEquals(61, WeaponMath.teleportRange(57, 4), E);
        assertEquals(57, WeaponMath.teleportRange(57, -2), E);
    }

    /** Research 06: Burning Vengeance repeats the last 8 hits; Wraith Drain heals 200 + 4%. */
    @Test
    void bladeDaggerSecondAbilities() {
        assertEquals(36, WeaponMath.vengeance(java.util.List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)), E);
        assertEquals(44, WeaponMath.vengeance(java.util.List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0)), E);   // oldest dropped
        assertEquals(0, WeaponMath.vengeance(java.util.List.of()), E);
        assertEquals(600, WeaponMath.wraithDrain(10_000), E);
        assertEquals(200, WeaponMath.wraithDrain(-5), E);
    }

    /** Soul Howl's Endermen-only Ferocity: 100 per strike, remainder as a chance. */
    @Test
    void ferocityStrikes() {
        assertEquals(2, WeaponMath.ferocityStrikes(200, 0.99));
        assertEquals(4, WeaponMath.ferocityStrikes(400, 0.0));
        assertEquals(1, WeaponMath.ferocityStrikes(150, 0.51));
        assertEquals(2, WeaponMath.ferocityStrikes(150, 0.4));
        assertEquals(0, WeaponMath.ferocityStrikes(0, 0.0));
    }

    /** Raise Souls: mana grows with the monster's level and is capped. */
    @Test
    void soulManaScalesWithLevelAndCaps() {
        assertEquals(24, WeaponMath.soulMana(1), E);
        assertEquals(24, WeaponMath.soulMana(0), E);
        assertEquals(220, WeaponMath.soulMana(50), E);
        assertEquals(500, WeaponMath.soulMana(300), E);
    }

    /** Flaming Sword: core cancels vanilla fire on sky mobs, so the burn is a share of the hit per second. */
    @Test
    void flamingSwordBurn() {
        double[] b = WeaponMath.burn(1000, 3, 3);
        assertEquals(30, b[0], E);
        assertEquals(3, b[1], E);
        assertEquals(0, WeaponMath.burn(-5, 3, 3)[0], E);
        assertEquals(0, WeaponMath.burn(100, 3, 0.5)[1], E);
    }
}
