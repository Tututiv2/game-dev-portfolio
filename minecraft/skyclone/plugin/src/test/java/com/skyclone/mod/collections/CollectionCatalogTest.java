package com.skyclone.mod.collections;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the SHIPPING data file through the pure catalog + math (no server needed). */
class CollectionCatalogTest {

    static CollectionCatalog cat;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = CollectionCatalogTest.class.getResourceAsStream("/data/collections/collections.json")) {
            assertNotNull(in, "data/collections/collections.json on the classpath");
            cat = CollectionCatalog.parse(in);
        }
    }

    @Test
    void breadthMatchesResearch() {
        assertEquals(90, cat.resourceCount());
        assertEquals(790, cat.resourceTiers());
        assertEquals(7, cat.categories().size());
        assertEquals(20, cat.inCategory("FARMING").size());
        assertEquals(25, cat.inCategory("MINING").size());
        assertEquals(11, cat.inCategory("COMBAT").size());
        assertEquals(15, cat.inCategory("FORAGING").size());
        assertEquals(12, cat.inCategory("FISHING").size());
        assertEquals(7, cat.inCategory("RIFT").size());
        assertEquals(8, cat.inCategory("BOSS").size());
    }

    @Test
    void thresholdsRiseAndPaySkyBlockXp() {
        for (CollectionCatalog.Coll c : cat.all()) {
            long prev = 0;
            for (int i = 0; i < c.tiers.size(); i++) {
                CollectionCatalog.Tier t = c.tiers.get(i);
                assertEquals(i + 1, t.tier, c.id);
                assertTrue(t.amount > prev, c.id + " " + t.tier);
                assertTrue(t.sbxp > 0, c.id + " " + t.tier);
                prev = t.amount;
            }
            if (!c.isBoss()) assertTrue(c.tiers.stream().allMatch(t -> t.sbxp == 4), c.id + " pays +4 SB XP per tier");
        }
    }

    @Test
    void wheatRoadAndTierMath() {
        CollectionCatalog.Coll wheat = cat.get("WHEAT");
        long[] want = {50, 100, 250, 500, 1000, 2500, 10000, 15000, 25000, 50000, 100000};
        assertEquals(want.length, wheat.maxTier());
        for (int i = 0; i < want.length; i++) assertEquals(want[i], wheat.tiers.get(i).amount);
        assertEquals(0, CollectionMath.tierFor(wheat, 0));
        assertEquals(1, CollectionMath.tierFor(wheat, 50));
        assertEquals(8, CollectionMath.tierFor(wheat, 22_600));
        assertEquals(11, CollectionMath.tierFor(wheat, 5_000_000));
        assertEquals(25_000, CollectionMath.nextThreshold(wheat, 8));
        assertEquals(-1, CollectionMath.nextThreshold(wheat, 11));
        assertEquals(0.904, CollectionMath.progress(wheat, 22_600), 1e-9);
        assertEquals(1.0, CollectionMath.progress(wheat, 200_000), 1e-9);
        // Wheat IX pays +25,000 Farming XP (research 02 section 3.2)
        assertTrue(wheat.tier(9).rewards.stream().anyMatch(r -> "skillxp".equals(r.type) && r.amount == 25_000 && "FARMING".equals(r.skill)));
    }

    @Test
    void feedsAndAliases() {
        assertEquals("INK_SACK:3", cat.feed("INK_SACK:3").coll().id);
        assertEquals("INK_SACK:3", cat.feed("COCOA_BEANS").coll().id);
        assertEquals("LOG", cat.feed("OAK_LOG").coll().id);
        assertEquals("RAW_FISH:1", cat.feed("SALMON").coll().id);
        assertEquals("MUSHROOM_COLLECTION", cat.feed("BROWN_MUSHROOM").coll().id);
        assertEquals(80, cat.feed("FLAWED_JADE_GEM").weight());
        assertEquals(1, cat.feed("ROUGH_JADE_GEM").weight());
        assertNull(cat.feed("ENCHANTED_WHEAT"), "enchanted forms never count");
        assertNull(cat.feed("CARVED_PUMPKIN"));
        assertNull(cat.feed("BOSS_BONZO"), "boss collections are not fed by items");
    }

    @Test
    void requirementKeys() {
        CollectionKey a = CollectionKey.parse(cat, "collection:INK_SACK:3:5");
        assertEquals("INK_SACK:3", a.coll().id);
        assertEquals(5, a.tier());
        CollectionKey b = CollectionKey.parse(cat, "collection:INK_SACK:3");
        assertEquals("INK_SACK", b.coll().id);
        assertEquals(3, b.tier());
        CollectionKey c = CollectionKey.parse(cat, "collection:wheat:VIII");
        assertEquals("WHEAT", c.coll().id);
        assertEquals(8, c.tier());
        CollectionKey d = CollectionKey.parse(cat, "collection:GEMSTONE_COLLECTION");
        assertEquals(0, d.tier());
        assertNull(CollectionKey.parse(cat, "collection:NOT_A_THING:3"));
        assertEquals("BOSS_BONZO", CollectionKey.parse(cat, "collection:BONZO:2").coll().id);
    }

    @Test
    void bossUnitsAndSkyBlockXp() {
        CollectionCatalog.Coll bonzo = cat.get("BOSS_BONZO");
        assertEquals(1, CollectionMath.bossUnits(bonzo, "catacombs_f1_bonzo", 2));
        assertEquals(2, CollectionMath.bossUnits(bonzo, "catacombs_m1_bonzo", 2));
        assertEquals(2, CollectionMath.bossUnits(bonzo, "bonzo_master", 2));
        assertEquals(0, CollectionMath.bossUnits(bonzo, "catacombs_f2_scarf", 2));
        assertEquals(0, CollectionMath.bossUnits(bonzo, "catacombs_f10_x", 2));
        CollectionCatalog.Coll kuudra = cat.get("BOSS_KUUDRA");
        assertEquals(5, CollectionMath.bossUnits(kuudra, "kuudra_infernal", 2));
        assertEquals(1, CollectionMath.bossUnits(kuudra, "kuudra_basic", 2));
        int sum = 0;
        for (CollectionCatalog.Coll c : cat.inCategory("BOSS")) sum += CollectionMath.sbxpThrough(c, c.maxTier());
        assertEquals(1015, sum, "Boss Collections SkyBlock XP task max (research 02 section 3.8)");
    }

    @Test
    void romanAndOverflow() {
        assertEquals("XII", CollectionMath.roman(12));
        assertEquals(12, CollectionMath.parseTier("XII"));
        assertEquals(4, CollectionMath.parseTier("4"));
        assertEquals(-1, CollectionMath.parseTier("IIII"));
        assertEquals(CollectionMath.MAX, CollectionMath.add(CollectionMath.MAX - 1, Long.MAX_VALUE));
        assertEquals(0, CollectionMath.add(5, -10));
        int[] road = CollectionsMenusLayout.road(12);
        assertEquals(12, road.length);
        assertEquals(18, road[0]);
        assertTrue(road[11] < 36);
    }
}
