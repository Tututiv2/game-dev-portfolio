package com.skyclone.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Named stash notices given in a burst are one chat line (wave4-endgame: 17 unique axes printed 17 lines). */
class StashBatchTest {

    @Test
    void oneItemKeepsTheOldLine() {
        StashBatch b = new StashBatch();
        b.add("&6Voidstep Blade", 1);
        assertEquals("&cYour inventory is full! &6Voidstep Blade &ewent to your item stash. ", b.line());
        assertTrue(b.hover().isEmpty());
        b = new StashBatch();
        b.add("&aEnchanted Diamond", 384);
        assertEquals("&cYour inventory is full! &aEnchanted Diamond &8x384 &ewent to your item stash. ", b.line());
    }

    @Test
    void seventeenUniquesAreOneLineWithAHoverList() {
        StashBatch b = new StashBatch();
        for (int i = 1; i <= 17; i++) b.add("&fAxe " + i, 1);
        assertEquals(17, b.kinds());
        assertEquals(17, b.total());
        String line = b.line();
        assertTrue(line.startsWith("&cYour inventory is full! &e17 items went to your item stash: "), line);
        assertTrue(line.contains("&fAxe 1&e, &fAxe 2&e, &fAxe 3 &7and 14 more"), line);
        assertFalse(line.contains("Axe 4"), "only the first three are spelled out");
        assertEquals(1, line.split("Your inventory is full").length - 1, "one notice, not seventeen");
        // hover: header + 17 names + blank + click hint
        assertEquals(20, b.hover().size());
        assertTrue(b.hover().contains("&7- &fAxe 17"));
    }

    @Test
    void sameNameAddsUpAndHoverIsCapped() {
        StashBatch b = new StashBatch();
        b.add("&fWheat", 64);
        b.add("&fWheat", 36);
        assertEquals(1, b.kinds());
        assertTrue(b.line().contains("&fWheat &8x100"), b.line());
        StashBatch big = new StashBatch();
        for (int i = 0; i < 30; i++) big.add("&fItem " + i, 1);
        assertTrue(big.hover().contains("&7...and 10 more"));
        assertTrue(big.line().contains("and 27 more"));
    }

    @Test
    void ignoresNothingAndBadAmounts() {
        StashBatch b = new StashBatch();
        b.add(null, 3);
        b.add("&fX", 0);
        assertTrue(b.isEmpty());
        assertEquals("", b.line());
    }

    @Test
    void delayIsATickThenRespectsTheWindow() {
        assertEquals(50, StashBatch.delayMs(null, 10_000, 2000), "first burst: next tick");
        assertEquals(50, StashBatch.delayMs(5_000L, 10_000, 2000), "window long past: next tick");
        assertEquals(1500, StashBatch.delayMs(9_500L, 10_000, 2000), "inside the window: wait it out");
        assertEquals(50, StashBatch.delayMs(9_990L, 10_000, 0), "window 0: next tick");
    }
}
