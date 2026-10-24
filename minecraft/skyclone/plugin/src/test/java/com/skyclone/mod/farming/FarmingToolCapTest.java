package com.skyclone.mod.farming;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Follow-up 2026-10-06 (garden Request 13): Mk I/II/III tool level caps 15/30/40, Overclocker 3000s raise a Mk III to 50. */
class FarmingToolCapTest {

    private static Reader res(String file) {
        return new InputStreamReader(FarmingToolCapTest.class.getResourceAsStream("/data/farming/" + file), StandardCharsets.UTF_8);
    }

    private static int cap(int mk, int oc) { return FortuneMath.levelCap(mk, oc, 15, 30, 40, 10, 50); }

    @Test
    void capsPerMk() {
        assertEquals(15, cap(1, 0));
        assertEquals(30, cap(2, 0));
        assertEquals(40, cap(3, 0));
        assertEquals(43, cap(3, 3));
        assertEquals(50, cap(3, 10));
        assertEquals(50, cap(3, 99), "at most 10 Overclockers");
        assertEquals(15, cap(1, 10), "Overclockers do nothing below Mk III");
        assertEquals(50, cap(0, 0), "a tool with no Mk levels to the table's end");
    }

    @Test
    void levelAndXpStopAtTheCap() {
        Tables t = Tables.read(res("tables.json"), res("shops.json"));
        int[] tab = t.toolLevelXp;
        double to15 = FortuneMath.xpToReach(15, tab);
        assertEquals(15, FortuneMath.toolLevel(to15, tab));
        assertEquals(15, FortuneMath.toolLevel(1e12, tab, 15), "a Mk I never shows past 15");
        assertEquals(0, FortuneMath.toolProgress(to15, tab, 15)[1], "MAXED at the cap");
        // XP stops at the cap, so a Mk II upgrade levels from 15 instead of jumping
        assertEquals(to15, FortuneMath.cappedXp(to15 - 10, 1_000_000, 15, tab), 1e-9);
        assertEquals(5_000, FortuneMath.cappedXp(0, 5_000, 15, tab), 1e-9);
        assertEquals(to15 + 50, FortuneMath.cappedXp(to15 + 50, 1_000, 15, tab), 1e-9, "old XP past the cap is never lowered");
        assertEquals(1e9 + 5, FortuneMath.cappedXp(1e9, 5, t.maxToolLevel(), tab), 1e-9, "no clamp at the table's end");
    }

    @Test
    void overclockerRules() {
        assertNotNull(FortuneMath.overclockRefusal(2, 30, 0, 40, 10), "Mk II cannot take one");
        assertNotNull(FortuneMath.overclockRefusal(3, 39, 0, 40, 10), "needs level 40");
        assertNotNull(FortuneMath.overclockRefusal(3, 50, 10, 40, 10), "10 is the most");
        assertNull(FortuneMath.overclockRefusal(3, 40, 0, 40, 10));
        assertNull(FortuneMath.overclockRefusal(3, 41, 1, 40, 10));
    }
}
