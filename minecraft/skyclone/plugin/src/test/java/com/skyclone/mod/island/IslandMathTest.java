package com.skyclone.mod.island;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Numbers from research/05-economy-events-mayors.md §6.4 (Island Size 161 -> 241 over ten tiers). */
class IslandMathTest {

    @Test
    void islandSizeMatchesResearch() {
        assertEquals(161, IslandMath.sizeForTier(0, IslandMath.BASE_SIZE, IslandMath.SIZE_PER_TIER));
        assertEquals(169, IslandMath.sizeForTier(1, IslandMath.BASE_SIZE, IslandMath.SIZE_PER_TIER));
        assertEquals(241, IslandMath.sizeForTier(10, IslandMath.BASE_SIZE, IslandMath.SIZE_PER_TIER));
        assertEquals(241, IslandMath.sizeForTier(99, IslandMath.BASE_SIZE, IslandMath.SIZE_PER_TIER), "clamped at tier X");
        assertEquals(161, IslandMath.sizeForTier(-3, IslandMath.BASE_SIZE, IslandMath.SIZE_PER_TIER));
        assertNull(IslandChecks.sizeProblems(null));
    }

    @Test
    void tableOverridesFormula() {
        int[] table = {169, 177, 185, 193, 201, 209, 217, 225, 233, 241};
        assertEquals(161, IslandMath.sizeForTier(0, 161, 8, table));
        assertEquals(185, IslandMath.sizeForTier(3, 161, 8, table));
        assertNull(IslandChecks.sizeProblems(table));
        assertTrue(IslandChecks.sizeProblems(new int[]{169, 160}) != null);
    }

    @Test
    void halfWidthAndBuildSquare() {
        assertEquals(80, IslandMath.half(161));
        assertEquals(120, IslandMath.half(241));
        assertTrue(IslandMath.insideBuild(80.9, 70, -80.0, 161, 0, 255));
        assertFalse(IslandMath.insideBuild(81.0, 70, 0, 161, 0, 255));
        assertFalse(IslandMath.insideBuild(-81.1, 70, 0, 161, 0, 255));
        assertTrue(IslandMath.insideBuild(-81.1, 70, 0, 169, 0, 255));
        assertFalse(IslandMath.insideBuild(0, 257, 0, 161, 0, 255));
        assertFalse(IslandMath.insideBuild(0, -1, 0, 161, 0, 255));
    }

    @Test
    void gridOriginRounding() {
        assertEquals(0, IslandMath.originOf(10.5, 1024));
        assertEquals(0, IslandMath.originOf(-511.9, 1024));
        assertEquals(1024, IslandMath.originOf(900, 1024));
        assertEquals(-2048, IslandMath.originOf(-2100.2, 1024));
        // the largest island never reaches the next cell
        assertEquals(1024, IslandMath.originOf(1024 + 120.9, 1024));
        assertEquals(1024, IslandMath.originOf(1024 - 120.9, 1024));
    }

    @Test
    void visitorModes() {
        assertEquals("anyone", IslandMath.normalizeVisitors(null));
        assertEquals("nobody", IslandMath.normalizeVisitors(" NOBODY "));
        assertEquals("anyone", IslandMath.normalizeVisitors("friends"));
        assertEquals("nobody", IslandMath.stricter("anyone", "nobody"));
        assertEquals("coop", IslandMath.stricter("coop", "anyone"));
        assertTrue(IslandMath.guestsAllowed("anyone"));
        assertFalse(IslandMath.guestsAllowed("coop"));
        assertFalse(IslandMath.guestsAllowed("nobody"));
        assertEquals(7, IslandMath.guestLimit(5, 2));
        assertEquals(5, IslandMath.guestLimit(5, -1));
    }

    @Test
    void starterKitParsing() {
        List<IslandMath.KitEntry> k = IslandMath.parseKit("rookie_pickaxe:1, BREAD:8,,COBBLESTONE_GENERATOR_1, INK_SACK:3:16, X:999, Y:abc");
        assertEquals("ROOKIE_PICKAXE", k.get(0).id());
        assertEquals(8, k.get(1).amount());
        assertEquals(1, k.get(2).amount());
        assertEquals("INK_SACK:3", k.get(3).id());
        assertEquals(16, k.get(3).amount());
        assertEquals(64, k.get(4).amount());
        assertEquals("Y:ABC", k.get(5).id());
        assertTrue(IslandMath.parseKit(null).isEmpty());
    }

    @Test
    void quarryRegrowsOnlyRock() {
        assertTrue(IslandMath.regrows("COBBLESTONE"));
        assertTrue(IslandMath.regrows("coal_ore"));
        assertFalse(IslandMath.regrows("OAK_LOG"));
        assertFalse(IslandMath.regrows("CHEST"));
    }
}
