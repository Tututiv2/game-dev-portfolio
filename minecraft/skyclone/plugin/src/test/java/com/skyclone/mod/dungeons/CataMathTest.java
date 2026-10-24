package com.skyclone.mod.dungeons;

import com.skyclone.mod.dungeons.model.CataMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Research 04 sec 3.3 / 3.12 / 3.16 numbers. */
class CataMathTest {

    @Test
    void xpTableMatchesResearch() {
        assertEquals(50, CataMath.cumulative(1));
        assertEquals(4_385, CataMath.cumulative(10));
        assertEquals(488_640, CataMath.cumulative(24));
        assertEquals(569_809_640L, CataMath.cumulative(50));
        assertEquals(0, CataMath.level(49));
        assertEquals(1, CataMath.level(50));
        assertEquals(24, CataMath.level(488_640));
        assertEquals(50, CataMath.level(1e12));
        assertEquals(52, CataMath.level(569_809_640L + 400_000_000L, 60));
    }

    @Test
    void statBoostMatchesResearch() {
        assertEquals(0, CataMath.statBoostPercent(0));
        assertEquals(15, CataMath.statBoostPercent(1));
        assertEquals(31, CataMath.statBoostPercent(4));
        assertEquals(85, CataMath.statBoostPercent(10));
        assertEquals(225, CataMath.statBoostPercent(24));
        assertEquals(485, CataMath.statBoostPercent(50));
    }

    @Test
    void scoreParts() {
        assertEquals(100, CataMath.skillScore(0, 0, false, 20));
        assertEquals(82, CataMath.skillScore(2, 1, false, 20));
        assertEquals(20, CataMath.skillScore(10, 5, false, 20));
        assertEquals(100, CataMath.explorationScore(20, 20, 30, 100, 0.3));
        assertEquals(60 + 20, CataMath.explorationScore(20, 20, 15, 100, 0.3));
        assertEquals(30 + 40, CataMath.explorationScore(10, 20, 50, 50, 1.0));
        assertEquals(100, CataMath.speedScore(600, 600));
        assertEquals(90, CataMath.speedScore(720, 600));
        assertEquals(80, CataMath.speedScore(960, 600));
        assertEquals(70, CataMath.speedScore(1260, 600));
        assertEquals(69, CataMath.speedScore(1300, 600));
        assertEquals(17, CataMath.bonusScore(9, true, 10, true));
        assertEquals(7, CataMath.bonusScore(5, true, 0, false));
        // finish wave 2026-10-03: the Prince and the Spirit-pet first death are now fed by the run
        assertEquals(8, CataMath.bonusScore(5, true, 0, true));
        assertEquals(99, CataMath.skillScore(1, 0, true, 20));
        assertEquals(97, CataMath.skillScore(2, 0, true, 20));
        assertEquals("S+", CataMath.grade(300));
        assertEquals("S", CataMath.grade(270));
        assertEquals("A", CataMath.grade(230));
        assertEquals("B", CataMath.grade(160));
        assertEquals("C", CataMath.grade(100));
        assertEquals("D", CataMath.grade(99));
    }

    @Test
    void runXp() {
        assertEquals(30_000, CataMath.runXp(30_000, 300, false, 0.1), 1e-6);
        assertEquals(42_000, CataMath.runXp(30_000, 317, true, 0.1), 1e-6);
        assertEquals(15_000, CataMath.runXp(30_000, 150, false, 0.1), 1e-6);
        assertEquals(3_000, CataMath.runXp(30_000, 0, false, 0.1), 1e-6);
    }
}
