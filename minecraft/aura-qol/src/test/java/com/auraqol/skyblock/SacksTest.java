package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Sack GUI text (SkyHanni data.sacks.* patterns), the store, and the crop/ore families. */
class SacksTest {

    private static final long S = 1000;

    // ------------------------------------------------------------ SackText

    @Test
    void sackMenuTitles() {
        // data.sacks.sack: ^(?:.* Sack|Enchanted .* Sack)$
        assertTrue(SackText.isSackMenu("Agronomy Sack"));
        assertTrue(SackText.isSackMenu("§8Large Enchanted Agronomy Sack"));
        assertTrue(SackText.isSackMenu("Gemstones Sack"));
        assertTrue(SackText.isSackMenu("Sacks"));
        assertTrue(SackText.isSackMenu("(1/2) Mining Sack"));
        assertFalse(SackText.isSackMenu("Sack Upgrades Shop"));
        assertFalse(SackText.isSackMenu("Bazaar ➜ Farming"));
        assertFalse(SackText.isSackMenu(null));
    }

    @Test
    void storedLoreLine() {
        // data.sacks.number: (?:§7Stored:)? (?<color>§[0-9a-f])(?<stored>[0-9.,kKmMbB]+)§7/(?<total>...)
        assertEquals(12_345, SackText.stored(List.of("§7Agronomy Sack item", "§7Stored: §e12,345§7/60.4k", "", "§eClick to pickup!")));
        assertEquals(0, SackText.stored(List.of("§7Stored: §80§7/60.4k")));
        assertEquals(1_200_000, SackText.stored(List.of("§7Stored: §a1.2M§7/2.2M")));
        assertEquals(-1, SackText.stored(List.of("§7I: §e12§7/64")));          // rune levels: no single count
        assertEquals(-1, SackText.stored(List.of("§7Click to open")));
        assertEquals(-1, SackText.stored(null));
    }

    @Test
    void gemstoneSackItems() {
        // data.sacks.gemstone.name / data.sacks.gemstone.count
        assertEquals("RUBY", SackText.gemType("§c\u2764 Ruby Gemstones"));
        assertEquals("JADE", SackText.gemType("\uE053 Jade Gemstones"));
        assertNull(SackText.gemType("Wheat"));
        Map<String, Long> c = SackText.gemCounts(List.of(
                "§7Stored gems:",
                " §fRough: §e1,234 §8(1.2k)",
                " §aFlawed: §e56 §8(4.5k)",
                " §9Fine: §e0",
                "",
                "§eClick to view!"));
        assertEquals(Map.of("ROUGH", 1234L, "FLAWED", 56L, "FINE", 0L), c);
    }

    @Test
    void amounts() {
        assertEquals(60_400, SackText.amount("60.4k"));
        assertEquals(12_345, SackText.amount("12,345"));
        assertEquals(3_000_000_000L, SackText.amount("3b"));
        assertEquals(-1, SackText.amount("x"));
        assertEquals(-1, SackText.amount(""));
    }

    // ------------------------------------------------------------ SackStore

    @Test
    void unknownUntilSyncedThenFollowsTheChat() {
        SackStore s = new SackStore();
        s.change("WHEAT", 500, 1 * S);
        assertNull(s.count("WHEAT"));                         // a delta on top of nothing is not a count
        s.sync("WHEAT", 10_000, 100 * S);
        s.change("WHEAT", 600, 200 * S);                      // long after the sync: applied whole
        assertEquals(10_600, s.count("WHEAT"));
        s.change("WHEAT", -20_000, 210 * S);                  // never below zero
        assertEquals(0, s.count("WHEAT"));
    }

    @Test
    void theFirstSummaryAfterASyncOnlyAddsThePartAfterTheMenu() {
        SackStore s = new SackStore();
        s.sync("WHEAT", 10_000, 100 * S);                     // menu closed at 100 s
        s.change("WHEAT", 900, 110 * S);                      // summary covers 80..110 s: only 10 s of it is new
        assertEquals(10_300, s.count("WHEAT"));
        s.change("WHEAT", 900, 140 * S);                      // the next one is all new
        assertEquals(11_200, s.count("WHEAT"));
    }

    @Test
    void familyViewTotalsInRawItemsAndRates() {
        SackStore s = new SackStore();
        SackFamilies.Family wheat = SackFamilies.byName("Wheat");
        s.sync("WHEAT", 1_000, 0);
        s.sync("ENCHANTED_WHEAT", 2, 0);
        s.sync("ENCHANTED_HAY_BALE", 1, 0);
        SackStore.View v = s.view(wheat, 1 * S, 600 * S);
        assertEquals(1_000 + 2 * 160 + 25_600, v.rawTotal());
        assertFalse(v.partial());
        s.sync("CARROT_ITEM", 5, 0);
        assertTrue(s.view(SackFamilies.byName("Carrot"), 1 * S, 600 * S).partial());   // enchanted forms never seen
        assertEquals(0, v.perHour(), 1e-9);                   // no sack data yet
        // 10 minutes of summaries: 1,000 wheat every 30 s = 120k/h
        for (int t = 30; t <= 600; t += 30) {
            s.change("WHEAT", 1000, t * S);
        }
        v = s.view(wheat, 600 * S, 600 * S);
        assertEquals(120_000, v.perHour(), 1.0);
        assertNull(s.view(SackFamilies.byName("Potato"), 600 * S, 600 * S).rawTotal());
    }

    @Test
    void persistenceRoundTrip() {
        SackStore s = new SackStore();
        s.sync("WHEAT", 12_345, 1_700_000_000_000L);
        s.sync("INK_SACK:3", 7, 1_700_000_000_500L);
        SackStore back = SackStore.fromText(s.toText());
        assertEquals(12_345, back.count("WHEAT"));
        assertEquals(7, back.count("INK_SACK:3"));
        assertEquals(1_700_000_000_500L, back.syncedAt("INK_SACK:3"));
        assertEquals(0, SackStore.fromText("garbage\nWHEAT notanumber\n# comment").knownCount());
    }

    // ------------------------------------------------------------ SackFamilies

    @Test
    void familiesFromToolsAndBlocks() {
        assertEquals("Wheat", SackFamilies.forTool("THEORETICAL_HOE_WHEAT_3").name());
        assertEquals("Sugar Cane", SackFamilies.forTool("THEORETICAL_HOE_CANE_1").name());
        assertEquals("Nether Wart", SackFamilies.forTool("THEORETICAL_HOE_WARTS_2").name());
        assertEquals("Melon", SackFamilies.forTool("MELON_DICER_3").name());
        assertEquals("Cocoa Beans", SackFamilies.forTool("COCO_CHOPPER").name());
        assertNull(SackFamilies.forTool("ASPECT_OF_THE_END"));
        assertEquals("Wheat", SackFamilies.forBlock("minecraft:wheat", false).name());
        assertEquals("Ruby", SackFamilies.forBlock("minecraft:red_stained_glass_pane", true).name());
        assertEquals("Hard Stone", SackFamilies.forBlock("minecraft:stone", true).name());
        assertEquals("Cobblestone", SackFamilies.forBlock("minecraft:stone", false).name());
        assertEquals("Mithril", SackFamilies.forBlock("minecraft:gray_wool", true).name());
        assertNull(SackFamilies.forBlock("minecraft:oak_log", false));
    }

    @Test
    void familyTableIsConsistent() {
        Set<String> seen = new HashSet<>();
        for (SackFamilies.Family f : SackFamilies.all()) {
            assertEquals(1, f.members().get(0).mult(), f.name() + " must start with its raw item");
            long prev = 0;
            for (SackFamilies.Member m : f.members()) {
                assertTrue(seen.add(m.id()), m.id() + " is in two families");
                assertTrue(m.mult() > prev, f.name() + " members must grow");
                prev = m.mult();
                assertNotNull(SackFamilies.forItem(m.id()));
            }
        }
        // From the NEU recipes: 160 Enchanted Wheat per Hay Bale, 160 Wheat per Enchanted Wheat.
        assertEquals(25_600, SackFamilies.byName("Wheat").member("ENCHANTED_HAY_BALE").mult());
        assertEquals(6_400, SackFamilies.byName("Ruby").member("FINE_RUBY_GEM").mult());
    }

    // ------------------------------------------------------------ between summaries

    @Test
    void theCountRunsOnBetweenSummariesAndSnapsWhenOneLands() {
        SackStore st = new SackStore();
        st.sync("ENCHANTED_WHEAT", 1000, 0);
        st.change("ENCHANTED_WHEAT", 30, 100 * S);                    // first summary: 30 per 30 s
        assertEquals(1030L, st.count("ENCHANTED_WHEAT"));
        assertEquals(1030L, st.estimate("ENCHANTED_WHEAT", 100 * S, Long.MAX_VALUE));   // exact at the summary
        assertEquals(1040L, st.estimate("ENCHANTED_WHEAT", 110 * S, Long.MAX_VALUE));   // +1/s
        assertEquals(1055L, st.estimate("ENCHANTED_WHEAT", 125 * S, Long.MAX_VALUE));
        st.change("ENCHANTED_WHEAT", 24, 130 * S);                    // the real number lands: snap
        assertEquals(1054L, st.estimate("ENCHANTED_WHEAT", 130 * S, Long.MAX_VALUE));
        // Stopped collecting at 135 s: the estimate stops there too.
        assertEquals(st.estimate("ENCHANTED_WHEAT", 135 * S, 135 * S), st.estimate("ENCHANTED_WHEAT", 150 * S, 135 * S));
        // No summary for over 40 s: gains have stopped, show the real count.
        assertEquals(1054L, st.estimate("ENCHANTED_WHEAT", 171 * S, Long.MAX_VALUE));
    }

    @Test
    void anUnknownCountIsNeverEstimatedAndAnIdleItemDoesNotMove() {
        SackStore st = new SackStore();
        st.change("ENCHANTED_WHEAT", 30, 100 * S);
        assertNull(st.estimate("ENCHANTED_WHEAT", 110 * S, Long.MAX_VALUE));   // never synced
        st.sync("ENCHANTED_SEEDS", 50, 0);
        assertEquals(50L, st.estimate("ENCHANTED_SEEDS", 110 * S, Long.MAX_VALUE));  // not in any summary
        // A sack that is being emptied (compaction / Replenish) is not extrapolated downwards.
        st.sync("WHEAT", 500, 0);
        st.change("WHEAT", -160, 120 * S);
        assertEquals(340L, st.estimate("WHEAT", 130 * S, Long.MAX_VALUE));
    }
}
