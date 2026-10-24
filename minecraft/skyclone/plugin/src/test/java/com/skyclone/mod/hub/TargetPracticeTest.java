package com.skyclone.mod.hub;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Wave 3: the Archery Range's Target Practice rules (medals, which target lights next). */
class TargetPracticeTest {

    @Test
    void medalsClimbWithTheScore() {
        assertEquals(0, TargetPractice.tierFor(0));
        assertEquals(0, TargetPractice.tierFor(TargetPractice.TIERS.get(0).score() - 1));
        for (int i = 0; i < TargetPractice.TIERS.size(); i++) {
            assertEquals(i + 1, TargetPractice.tierFor(TargetPractice.TIERS.get(i).score()));
            if (i > 0) assertTrue(TargetPractice.TIERS.get(i).score() > TargetPractice.TIERS.get(i - 1).score());
            if (i > 0) assertTrue(TargetPractice.TIERS.get(i).coins() > TargetPractice.TIERS.get(i - 1).coins());
        }
        assertEquals(TargetPractice.TIERS.size(), TargetPractice.tierFor(1000));
    }

    /** The lit target always moves (never the same one twice), every other one can come up, and rolls near 1 stay in range. */
    @Test
    void nextTargetNeverRepeatsAndReachesEveryOther() {
        for (int prev = -1; prev < 4; prev++) {
            Set<Integer> seen = new HashSet<>();
            for (int k = 0; k < 1000; k++) {
                int n = TargetPractice.nextTarget(prev, 4, k / 1000.0);
                assertNotEquals(prev, n);
                assertTrue(n >= 0 && n < 4, "out of range " + n);
                seen.add(n);
            }
            assertEquals(prev < 0 ? 4 : 3, seen.size(), "from " + prev + " reached " + seen);
            assertTrue(TargetPractice.nextTarget(prev, 4, 0.9999999) < 4);
        }
        assertEquals(0, TargetPractice.nextTarget(0, 1, 0.5));
    }

    @Test
    void aDarkTargetCostsAPointAndMovesTheLight() {
        TargetPractice.Step s = TargetPractice.step(4, 1, 1, 4, 0.5, 1);
        assertTrue(s.right());
        assertEquals(5, s.score());
        assertNotEquals(1, s.active());
        s = TargetPractice.step(4, 1, 3, 4, 0.5, 1);
        assertFalse(s.right());
        assertEquals(3, s.score());
        assertNotEquals(3, s.active(), "the light never lands on the dark target just hit");
        assertEquals(0, TargetPractice.step(0, 1, 3, 4, 0.5, 1).score(), "never below zero");
        assertEquals(4, TargetPractice.step(4, 1, 3, 4, 0.5, 0).score(), "penalty 0 = the old rule");
    }

    /**
     * Wave-3 playtest: shooting the four targets in turn without looking at the light reached Gold (17).
     * Replay that blind rotation, 40 arrows a round (about what reached 17), over 2,000 seeded rounds: it
     * must almost never reach Bronze and never Silver. And an archer who aims (the lit one 9 times in 10,
     * the rest into the air) still reaches Gold with the same 40 arrows.
     */
    @Test
    void blindRotationEarnsNothingAimingStillWins() {
        java.util.Random rnd = new java.util.Random(20261004L);
        int rounds = 2000, bronze = 0, silver = 0;
        for (int r = 0; r < rounds; r++) {
            int score = 0, active = TargetPractice.nextTarget(-1, 4, rnd.nextDouble());
            for (int arrow = 0; arrow < 40; arrow++) {
                TargetPractice.Step st = TargetPractice.step(score, active, arrow % 4, 4, rnd.nextDouble(), 1);
                score = st.score();
                active = st.active();
            }
            int tier = TargetPractice.tierFor(score);
            if (tier >= 1) bronze++;
            if (tier >= 2) silver++;
        }
        assertTrue(bronze < rounds * 0.08, "blind rotation reached Bronze in " + bronze + " of " + rounds + " rounds");
        assertTrue(silver <= rounds * 0.002, "blind rotation reached Silver in " + silver + " of " + rounds + " rounds");

        // the old rule (no penalty, the light stays) for contrast: the same rotation wins every time
        int oldScore = 0, act = 2;
        for (int arrow = 0; arrow < 40; arrow++) {
            int hit = arrow % 4;
            if (hit == act) { oldScore++; act = TargetPractice.nextTarget(act, 4, rnd.nextDouble()); }
        }
        assertTrue(oldScore >= TargetPractice.TIERS.get(1).score(), "the old rule gave the rotation " + oldScore);

        int aimed = 0, active = 0;
        for (int arrow = 0; arrow < 40; arrow++) {
            if (rnd.nextDouble() >= 0.9) continue;                       // a clean miss: no target touched
            TargetPractice.Step st = TargetPractice.step(aimed, active, active, 4, rnd.nextDouble(), 1);
            aimed = st.score();
            active = st.active();
        }
        assertTrue(TargetPractice.tierFor(aimed) >= 3, "an aiming archer scored " + aimed);
    }
}
