package com.skyclone.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Worked examples from research/01-stats-combat-itemmods.md sections 4-5. */
class DamageMathTest {

    @Test
    void fistIsFive() {
        assertEquals(5, DamageMath.melee(0, 0, 0, 0, 1, false, 0), 1e-9);
    }

    @Test
    void strengthScalesBase() {
        // (5 + 95) * (1 + 100/100) = 200
        assertEquals(200, DamageMath.melee(95, 0, 100, 0, 1, false, 0), 1e-9);
    }

    @Test
    void flatBonusAddsBeforeStrength() {
        // (5 + 10 + 5) * 1.5 = 30
        assertEquals(30, DamageMath.melee(10, 5, 50, 0, 1, false, 0), 1e-9);
    }

    @Test
    void additiveAndMultiplicativeWorkedExamples() {
        // research: 100 x (1 + 350/100) = 450; 100 x 4.025 = 402.5; combined 1,811.25 -> 1,811
        assertEquals(450, DamageMath.melee(95, 0, 0, 350, 1, false, 0), 1e-9);
        assertEquals(402.5, DamageMath.melee(95, 0, 0, 0, 1.15 * 3.5, false, 0), 1e-9);
        assertEquals(1811, DamageMath.mobSide(DamageMath.melee(95, 0, 0, 350, 4.025, false, 0), 0, 0, 0), 0);
    }

    @Test
    void critMultipliesByCritDamage() {
        assertEquals(5 * 1.5, DamageMath.melee(0, 0, 0, 0, 1, true, 50), 1e-9);
        assertEquals(5 * 3.0, DamageMath.melee(0, 0, 0, 0, 1, true, 200), 1e-9);
    }

    @Test
    void defenseWorkedExample() {
        // 1,000 into a 2,000-defense mob -> 47.62 -> 47
        assertEquals(47, DamageMath.mobSide(1000, 0, 0, 2000), 0);
        assertEquals(50, DamageMath.reduce(100, 100), 1e-9);
        assertEquals(0.5, DamageMath.reductionFraction(100), 1e-9);
        assertEquals(0.9, DamageMath.reductionFraction(900), 1e-9);
        assertEquals(20000, DamageMath.ehp(2000, 900), 1e-9);
    }

    @Test
    void bossCapWorkedExample() {
        // Apex dragon: 600M -> cap 100k -> 60,090,000 -> x 100/2600 = 2,311,153
        assertEquals(60_090_000, DamageMath.bossCap(600_000_000, 100_000), 1e-6);
        assertEquals(2_311_153, DamageMath.mobSide(600_000_000, 0, 100_000, 2500), 0);
        assertEquals(500, DamageMath.bossCap(500, 1000), 1e-9);
        assertEquals(500, DamageMath.bossCap(500, 0), 1e-9);
    }

    @Test
    void magicResistanceBeforeCap() {
        assertEquals(85, DamageMath.mobSide(100, 0.15, 0, 0), 0);
    }

    @Test
    void magicFormula() {
        assertEquals(12000, DamageMath.magic(12000, 0, 0.1, 0, 0, 1), 1e-6);
        assertEquals(18000, DamageMath.magic(12000, 500, 0.1, 0, 0, 1), 1e-6);
        assertEquals(18000 * 1.5, DamageMath.magic(12000, 500, 0.1, 50, 0, 1), 1e-6);
    }

    @Test
    void ferocity() {
        assertEquals(0, DamageMath.ferocityStrikes(0, 500, 0.0));
        assertEquals(3, DamageMath.ferocityStrikes(250, 500, 0.49));
        assertEquals(2, DamageMath.ferocityStrikes(250, 500, 0.50));
        assertEquals(2, DamageMath.ferocityStrikes(200, 500, 0.0));
        assertEquals(5, DamageMath.ferocityStrikes(900, 500, 0.0));
        assertEquals(1, DamageMath.ferocityStrikes(30, 500, 0.29));
        assertEquals(0, DamageMath.ferocityStrikes(30, 500, 0.30));
    }

    @Test
    void critRoll() {
        assertTrue(DamageMath.critRoll(100, 0.999));
        assertFalse(DamageMath.critRoll(0, 0.0));
        assertTrue(DamageMath.critRoll(30, 0.29));
        assertFalse(DamageMath.critRoll(30, 0.30));
    }

    @Test
    void invulnerabilityWindow() {
        assertEquals(10, DamageMath.invulnTicks(10, 0, 100, 2));
        assertEquals(5, DamageMath.invulnTicks(10, 100, 100, 2));
        assertEquals(5, DamageMath.invulnTicks(10, 400, 100, 2));
        assertEquals(2, DamageMath.invulnTicks(2, 100, 100, 2));
    }

    @Test
    void incomingIsNeverNegative() {
        assertEquals(0, DamageMath.incoming(-50, 0, 0, 1), 0);
        assertEquals(150, DamageMath.incoming(100, 0, 50, 1), 1e-9);
    }
}
