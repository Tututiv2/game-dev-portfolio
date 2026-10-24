package com.skyclone.mod.storage;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageMathTest {

    @Test
    void enderChestIsEightPagesOf45AndOneOf36() {
        // research 05 account upgrades: pages +45 each, the last (VIII) +36
        assertEquals(45, StorageMath.enderPageSize(0, 9, 36));
        assertEquals(36, StorageMath.enderPageSize(8, 9, 36));
        assertEquals(0, StorageMath.enderPageSize(9, 9, 36));
        assertEquals(396, StorageMath.enderSlots(9, 9, 36));
        assertEquals(90, StorageMath.enderSlots(2, 9, 36));
    }

    @Test
    void liningsAddTwoHundredPercentOfTheOriginalUpToThree() {
        assertEquals(20160, StorageMath.sackCapacity(20160, 0, 3, 2.0));
        assertEquals(60480, StorageMath.sackCapacity(20160, 1, 3, 2.0));
        assertEquals(141120, StorageMath.sackCapacity(20160, 3, 3, 2.0));
        assertEquals(141120, StorageMath.sackCapacity(20160, 9, 3, 2.0));
        assertEquals(640, StorageMath.sackCapacity(640, -2, 3, 2.0));
    }

    @Test
    void fitsRespectsWeightAndRoom() {
        assertEquals(10, StorageMath.fits(640, 0, 10, 1));
        assertEquals(40, StorageMath.fits(640, 600, 100, 1));
        assertEquals(0, StorageMath.fits(640, 640, 1, 1));
        assertEquals(0, StorageMath.fits(640, 700, 1, 1));
        // 128,000 rough-equivalents hold 20 fine gems (6,400 each) and not 21
        assertEquals(20, StorageMath.fits(128000, 0, 50, 6400));
        assertEquals(1600, StorageMath.fits(128000, 0, 5000, 80));
    }

    @Test
    void thresholdsFailClosed() {
        int[] t = StorageMath.thresholds("0, 5,abc,,10");
        assertEquals(5, t.length);
        assertEquals(0, t[0]);
        assertEquals(5, t[1]);
        assertEquals(Integer.MAX_VALUE, t[2]);
        assertEquals(Integer.MAX_VALUE, t[3]);
        assertEquals(3, StorageMath.unlockedCount(t, 10));
        assertTrue(StorageMath.unlocked(t, 1, 5));
        assertFalse(StorageMath.unlocked(t, 2, 1_000_000));
        assertFalse(StorageMath.unlocked(t, 9, 1_000_000));
    }

    @Test
    void sackOfSacksFollowsTropicalFishCollection() {
        // research 02: IV unlock, +4 at V, VI, VII, VIII, +5 at IX
        assertEquals(2, StorageMath.sackBagSlots(2, 3, 3));
        assertEquals(5, StorageMath.sackBagSlots(2, 4, 3));
        assertEquals(9, StorageMath.sackBagSlots(2, 5, 3));
        assertEquals(26, StorageMath.sackBagSlots(2, 9, 3));
        assertEquals(45, StorageMath.sackBagSlots(40, 9, 3));
    }

    @Test
    void quiverFollowsStringCollection() {
        assertEquals(0, StorageMath.quiverSlots(2, 27));
        assertEquals(27, StorageMath.quiverSlots(3, 27));
        assertEquals(27, StorageMath.quiverSlots(5, 27));
        assertEquals(36, StorageMath.quiverSlots(6, 27));
        assertEquals(45, StorageMath.quiverSlots(9, 27));
    }

    @Test
    void tieredValuePicksTheBestReached() {
        String spec = "0:9,100000000:18,250000000:27";
        assertEquals(9, StorageMath.tieredValue(spec, 50_000_000));
        assertEquals(18, StorageMath.tieredValue(spec, 100_000_000));
        assertEquals(27, StorageMath.tieredValue(spec, 6e9));
        assertEquals(0, StorageMath.tieredValue("junk", 1));
    }

    @Test
    void rowsAndStacks() {
        assertEquals(2, StorageMath.rowsFor(9));
        assertEquals(3, StorageMath.rowsFor(18));
        assertEquals(5, StorageMath.rowsFor(36));
        assertEquals(6, StorageMath.rowsFor(45));
        assertEquals(List.of(64, 64, 2), StorageMath.stacks(130, 64));
        assertEquals(List.of(), StorageMath.stacks(0, 64));
        assertEquals(4, StorageMath.sizeIndex(45));
        assertEquals(-1, StorageMath.sizeIndex(10));
    }

    @Test
    void generatedCatalogueHangsTogether() throws Exception {
        Catalog c = new Catalog();
        try (InputStream s = getClass().getResourceAsStream("/data/storage/sacks.json");
             InputStream b = getClass().getResourceAsStream("/data/storage/backpacks.json")) {
            assertNotNull(s, "sacks.json on the classpath");
            assertNotNull(b, "backpacks.json on the classpath");
            c.loadSacks(s);
            c.loadBackpacks(b);
        }
        assertEquals(34, c.families.size());
        assertEquals(640, c.sacks.get("BEGINNER_AGRONOMY_SACK").capacity());
        assertEquals(2240, c.sacks.get("SMALL_AGRONOMY_SACK").capacity());
        assertEquals(6720, c.sacks.get("MEDIUM_AGRONOMY_SACK").capacity());
        assertEquals(20160, c.sacks.get("LARGE_AGRONOMY_SACK").capacity());
        assertEquals("AGRONOMY", c.sacks.get("SMALL_AGRONOMY_SACK").family());
        assertTrue(c.sackable("WHEAT"));
        assertTrue(c.sackable("ENCHANTED_DIAMOND"));
        assertTrue(c.isArrow("ARROW"));
        assertEquals(9, c.backpackSlots("SMALL_BACKPACK"));
        assertEquals(45, c.backpackSlots("JUMBO_BACKPACK"));
        assertEquals(9, c.backpackSlots("PINK_SMALL_BACKPACK"));
        assertEquals("SMALL", c.backpackSize("PINK_SMALL_BACKPACK"));
        assertEquals("LARGE", c.backpackSize("LIGHT_BLUE_LARGE_BACKPACK"));
        assertEquals("GREATER", c.backpackSize("GREATER_BACKPACK"));
        Catalog.Family gem = c.familyById.get("GEMSTONE");
        assertTrue(gem.weighted());
        assertEquals(6400, gem.weights().get("FINE_RUBY_GEM").weight());
        assertEquals(1, gem.weights().get("ROUGH_RUBY_GEM").weight());
        for (Catalog.Sack sk : c.sacks.values()) {
            assertTrue(sk.capacity() > 0, sk.id());
            assertTrue(c.familyById.containsKey(sk.family()), sk.id());
        }
    }

    @Test
    void gfsResolvesIdsNamesAndAmbiguity() {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("ENCHANTED_DIAMOND", "Enchanted Diamond");
        names.put("DIAMOND", "Diamond");
        names.put("ENCHANTED_DIAMOND_BLOCK", "Enchanted Diamond Block");
        names.put("WHEAT", "Wheat");
        assertEquals(List.of("ENCHANTED_DIAMOND"), StorageMath.matchItem("enchanted_diamond", names));
        assertEquals(List.of("ENCHANTED_DIAMOND"), StorageMath.matchItem("Enchanted Diamond", names));
        assertEquals(List.of("ENCHANTED_DIAMOND"), StorageMath.matchItem("enchanteddiamond", names));
        assertEquals(List.of("DIAMOND"), StorageMath.matchItem("diamond", names));
        assertEquals(List.of("WHEAT"), StorageMath.matchItem("WHE", names));
        assertEquals(2, StorageMath.matchItem("enchanted diam", names).size());
        assertTrue(StorageMath.matchItem("carrot", names).isEmpty());
        assertTrue(StorageMath.matchItem("  ", names).isEmpty());
    }

    @Test
    void gfsAmounts() {
        assertEquals(64, StorageMath.gfsAmount("64", 1));
        assertEquals(2000, StorageMath.gfsAmount("2k", 1));
        assertEquals(1200, StorageMath.gfsAmount("1,200", 1));
        assertEquals(Long.MAX_VALUE, StorageMath.gfsAmount("all", 1));
        assertEquals(1, StorageMath.gfsAmount("0", 1));
        assertEquals(-1, StorageMath.gfsAmount("diamond", -1));
        assertEquals(7, StorageMath.gfsAmount(null, 7));
    }

    @Test
    void runeFamilyCanBeExtendedAtRuntime() throws Exception {
        Catalog c = new Catalog();
        try (InputStream s = getClass().getResourceAsStream("/data/storage/sacks.json")) { c.loadSacks(s); }
        assertTrue(c.familyById.get("RUNE").contents().isEmpty());
        assertEquals(2, c.extend("RUNE", List.of("BITE_RUNE", "BITE_RUNE_2", "BITE_RUNE")));
        assertEquals(0, c.extend("RUNE", List.of("BITE_RUNE")));
        assertEquals("RUNE", c.holdersOf("BITE_RUNE_2").get(0).id());
        assertTrue(c.sackable("BITE_RUNE"));
        assertTrue(c.families.contains(c.familyById.get("RUNE")));
        assertTrue(c.allContents().contains("BITE_RUNE"));
    }

    // ---- wave 5 ------------------------------------------------------------------------------------

    @Test
    void sackSizeRaisesCapacityByPercentAndFloors() {
        // NEU ATTRIBUTE_SHARD_SACK_SIZE: "Your sacks can hold +1% more items" per level
        assertEquals(640, StorageMath.withPercent(640, 0));
        assertEquals(646, StorageMath.withPercent(640, 1));       // 646.4 floors: never claims room it lacks
        assertEquals(704, StorageMath.withPercent(640, 10));
        assertEquals(2352, StorageMath.withPercent(2240, 5));     // float noise must not cost an item
        assertEquals(20160, StorageMath.withPercent(20160, -3));
        assertEquals(20160, StorageMath.withPercent(20160, Double.NaN));
        assertEquals(0, StorageMath.withPercent(0, 50));
        assertTrue(StorageMath.withPercent(Long.MAX_VALUE / 2, 100) > 0);
    }

    @Test
    void runeTiersGroupUnderTheirRune() {
        java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("BITE_RUNE", "BITE:1");
        tags.put("BLOOD_2_RUNE_3", "BLOOD_2:3");
        tags.put("BITE_RUNE_2", "BITE:2");
        tags.put("BITE_RUNE_3", "BITE:3");
        tags.put("ODD", "nonsense");
        tags.put("TOO_HIGH", "BITE:4");
        java.util.LinkedHashMap<String, String[]> g = StorageMath.runeGroups(tags, 3);
        assertEquals(List.of("BITE", "BLOOD_2"), List.copyOf(g.keySet()));
        assertEquals(List.of("BITE_RUNE", "BITE_RUNE_2", "BITE_RUNE_3"), java.util.Arrays.asList(g.get("BITE")));
        assertEquals(java.util.Arrays.asList(null, null, "BLOOD_2_RUNE_3"), java.util.Arrays.asList(g.get("BLOOD_2")));
        assertTrue(StorageMath.runeGroups(null, 3).isEmpty());
    }

    @Test
    void communityShopTiersAddSlotsOnTopOfTheLevelLadder() {
        int[] lv = StorageMath.thresholds("0,0,10,15,20");
        assertEquals(2, StorageMath.openCount(lv, 5, 0, 18));
        assertEquals(5, StorageMath.openCount(lv, 5, 3, 18));     // research 05: each tier +1 slot
        assertEquals(18, StorageMath.openCount(lv, 99, 40, 18));  // never past the slot count
        // the level shown on a locked slot moves down one rung per tier owned
        assertEquals(10, StorageMath.levelFor(lv, 2, 0));
        assertEquals(0, StorageMath.levelFor(lv, 2, 2));
        assertEquals(15, StorageMath.levelFor(lv, 4, 1));
        assertEquals(Integer.MAX_VALUE, StorageMath.levelFor(lv, 9, 0));
    }

    @Test
    void backpackSkinsAreTheTwentyNineReferenceSkins() throws Exception {
        com.google.gson.JsonArray skins, items;
        try (InputStream s = getClass().getResourceAsStream("/data/storage/backpack_skins.json")) {
            assertNotNull(s);
            skins = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(s, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        }
        try (InputStream s = getClass().getResourceAsStream("/data/storage/items.json")) {
            items = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(s, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        }
        java.util.Set<String> ours = new java.util.HashSet<>();
        for (var e : items) ours.add(e.getAsJsonObject().get("id").getAsString());
        java.util.Set<String> ids = new java.util.HashSet<>();
        assertEquals(29, skins.size());
        for (var e : skins) {
            var o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            assertTrue(ids.add(id), "duplicate " + id);
            assertFalse(ours.contains(id), id + " clashes with a sack/backpack id");
            assertEquals("COSMETIC", o.get("category").getAsString());
            assertEquals(1, o.get("maxStack").getAsInt());
            assertFalse(o.get("skull").getAsString().isBlank(), id + " has no texture");
            assertTrue(o.get("tags").toString().contains("backpack_skin"));
        }
        assertTrue(ids.contains("FAIRY_BACKPACK") && ids.contains("BAG_OF_COAL_BACKPACK") && ids.contains("NORTH_STAR_BACKPACK"));
    }

    @Test
    void equipmentWardrobeLadderHasEighteenEntriesFirstFree() {
        int[] eq = StorageMath.thresholds("0,0,10,15,20,30,40,50,60,75,90,105,120,135,150,175,200,250");
        assertEquals(18, eq.length);
        assertEquals(0, eq[0]);
        assertEquals(2, StorageMath.unlockedCount(eq, 0));
        assertEquals(18, StorageMath.unlockedCount(eq, 250));
    }
}
