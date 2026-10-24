package com.skyclone.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The item stash's pure rules: identical items merge, unique ones never do, the cap only blocks NEW kinds. */
class StashDataTest {

    @Test
    void identicalItemsMergeIntoOneRow() {
        StashData d = new StashData();
        assertTrue(d.add("COBBLE", "COBBLESTONE", 64, true, 10, 1));
        assertTrue(d.add("COBBLE", "COBBLESTONE", 64, true, 10, 2));
        assertTrue(d.add("COBBLE", "COBBLESTONE", 5, true, 10, 3));
        assertEquals(1, d.liveEntries());
        assertEquals(133, d.total());
        assertEquals(1, d.items.get(0).at, "first-stashed time is kept");
    }

    @Test
    void unmergeableItemsGetTheirOwnRow() {
        StashData d = new StashData();
        // two identical-looking unique prizes (same bytes would still be two items)
        assertTrue(d.add("PRIZE", "SKULL", 1, false, 10, 0));
        assertTrue(d.add("PRIZE", "SKULL", 1, false, 10, 0));
        assertEquals(2, d.liveEntries());
        assertEquals(2, d.total());
    }

    @Test
    void capBlocksNewKindsButNotMerges() {
        StashData d = new StashData();
        assertTrue(d.add("A", "A", 1, true, 2, 0));
        assertTrue(d.add("B", "B", 1, true, 2, 0));
        assertFalse(d.add("C", "C", 1, true, 2, 0), "a full stash refuses a new kind");
        assertEquals(2, d.liveEntries(), "a refused add changes nothing");
        assertTrue(d.add("A", "A", 63, true, 2, 0), "but still takes more of what it holds");
        assertEquals(65, d.total());
        assertTrue(d.add("C", "C", 1, true, 0, 0), "0 = no cap (offline deliveries)");
    }

    @Test
    void countsNeverOverflow() {
        StashData d = new StashData();
        assertTrue(d.add("A", "A", Integer.MAX_VALUE - 1, true, 0, 0));
        assertTrue(d.add("A", "A", 10, true, 0, 0));
        assertEquals(2, d.liveEntries(), "a row near int max opens a second row instead of wrapping");
        assertEquals((long) Integer.MAX_VALUE - 1 + 10, d.total());
    }

    @Test
    void rewardsAndSmallPilesComeBackBeforeBulk() {
        // wave4-loops: oldest-first put 4,000 wheat and seeds in front of the Harvest Fair Tickets
        StashData d = new StashData();
        d.add("WHEAT", "WHEAT", 4000, true, 0, 1);
        d.add("SEEDS", "SEEDS", 900, true, 0, 2);
        d.add("ESS", "FOSSIL_ESSENCE", 3, true, 0, 3);
        d.add("TICKET", "JACOBS_TICKET", 5, true, 0, 4, true, 0);
        d.add("EWHEAT", "ENCHANTED_WHEAT", 20, true, 0, 5, false, 1);
        var order = d.pickupOrder();
        assertEquals(java.util.List.of("TICKET", "EWHEAT", "ESS", "SEEDS", "WHEAT"),
                order.stream().map(e -> e.item).toList());
    }

    @Test
    void mergingUpgradesPriorityAndRankNeverDowngrades() {
        StashData d = new StashData();
        d.add("T", "T", 1, true, 0, 0, true, 3);
        d.add("T", "T", 1, true, 0, 0, false, 0);
        assertTrue(d.items.get(0).priority);
        assertEquals(3, d.items.get(0).rank);
        d.add("W", "W", 1, true, 0, 0);
        d.add("W", "W", 1, true, 0, 0, true, 2);
        assertTrue(d.items.get(1).priority, "a reward merging into a bulk row lifts the row");
    }

    @Test
    void pickupOrderSkipsBrokenRowsAndOldRowsDefaultToBulk() {
        StashData d = new StashData();
        d.items.add(null);
        StashData.Entry old = new StashData.Entry();   // a row saved before the order fields existed
        old.item = "OLD"; old.id = "OLD"; old.amount = 2;
        d.items.add(old);
        StashData.Entry empty = new StashData.Entry();
        empty.item = "E"; empty.amount = 0;
        d.items.add(empty);
        var order = d.pickupOrder();
        assertEquals(1, order.size());
        assertFalse(order.get(0).priority);
        d.items = null;
        assertTrue(d.pickupOrder().isEmpty());
    }

    @Test
    void rejectsGarbageAndPrunesEmptyRows() {
        StashData d = new StashData();
        assertFalse(d.add(null, "X", 1, true, 0, 0));
        assertFalse(d.add("", "X", 1, true, 0, 0));
        assertFalse(d.add("X", "X", 0, true, 0, 0));
        assertFalse(d.add("X", "X", -5, true, 0, 0));
        d.add("X", "X", 3, true, 0, 0);
        d.items.get(0).amount = 0;
        d.items.add(null);
        d.prune();
        assertTrue(d.items.isEmpty());
        d.items = null;   // a profile JSON with no field
        assertEquals(0, d.total());
        assertTrue(d.add("Y", "Y", 1, true, 0, 0));
    }
}
