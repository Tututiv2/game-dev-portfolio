package com.skyclone.mod.galatea;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrogMathTest {

    @Test void twelveSpeciesFortyEightItemsMatchingStorageSacks() {
        assertEquals(12, FrogMath.SPECIES.size());
        Set<String> ids = new HashSet<>();
        for (FrogMath.Species s : FrogMath.SPECIES) for (FrogMath.Tier t : FrogMath.Tier.values()) ids.add(s.item(t));
        assertEquals(48, ids.size());
        // storage's FROG_BRONZE sack family lists exactly these (data/storage/sacks.json)
        for (String id : new String[]{"COMMON_FROG_BRONZE", "LEAP_FROG_BRONZE", "EXPLODING_FROG_BRONZE", "REALITY_HOPPER_BRONZE", "WETLANDS_FROG_BRONZE",
                "SEA_FROG_BRONZE", "BULLFROG_BRONZE", "BLESSED_FROG_BRONZE", "TREE_FROG_BRONZE", "CAVE_FROG_BRONZE", "HIGHLANDS_FROG_BRONZE", "PUDDLE_JUMPER_BRONZE"})
            assertTrue(ids.contains(id), id);
    }

    @Test void conditionsGateSpecies() {
        FrogMath.Context c = new FrogMath.Context();
        assertEquals(1, FrogMath.eligible(c).size(), "plain cast: only the Common Frog");
        c.raining = true; c.inCave = true;
        assertEquals(3, FrogMath.eligible(c).size());
        c.onAtoll = false;
        assertEquals(0, FrogMath.eligible(c).size(), "nothing bites off the Atoll");
    }

    @Test void tierTableAndPity() {
        assertEquals(FrogMath.Tier.DIAMOND, FrogMath.tier(0.0019, 0, 0, 0, 100, 600));
        assertEquals(FrogMath.Tier.GOLD, FrogMath.tier(0.02, 0, 0, 0, 100, 600));
        assertEquals(FrogMath.Tier.SILVER, FrogMath.tier(0.25, 0, 0, 0, 100, 600));
        assertEquals(FrogMath.Tier.BRONZE, FrogMath.tier(0.5, 0, 0, 0, 100, 600));
        assertEquals(FrogMath.Tier.GOLD, FrogMath.tier(0.99, 0, 100, 0, 100, 600));
        assertEquals(FrogMath.Tier.DIAMOND, FrogMath.tier(0.99, 0, 0, 600, 100, 600));
        // Trophy Chance 100 doubles every band
        assertEquals(FrogMath.Tier.SILVER, FrogMath.tier(0.5, 100, 0, 0, 100, 600));
        assertEquals(0.2, FrogMath.frogChance(0.1, 100), 1e-9);
        assertEquals(1.0, FrogMath.frogChance(0.9, 500), 1e-9);
    }

    @Test void titlesNeedEverySpecies() {
        Map<String, Integer> m = new HashMap<>();
        for (FrogMath.Species s : FrogMath.SPECIES) m.put(s.item(FrogMath.Tier.GOLD), 1);
        assertEquals(3, FrogMath.title(m), "every species at Gold = Expert");
        m.remove("PUDDLE_JUMPER_GOLD");
        assertEquals(0, FrogMath.title(m), "one species missing = no title");
        m.put("PUDDLE_JUMPER_BRONZE", 1);
        assertEquals(1, FrogMath.title(m));
    }

    @Test void lotusDenominationsFollowTheRecipes() {
        assertEquals(80, FrogMath.SILVER);
        assertEquals(6400, FrogMath.GOLD);
        assertEquals(128000, FrogMath.DIAMOND);
        long[] d = FrogMath.denominations(128000 + 6400 * 3 + 80 * 2 + 5);
        assertEquals(1, d[0]); assertEquals(3, d[1]); assertEquals(2, d[2]); assertEquals(5, d[3]);
        assertEquals(4096, FrogMath.lotusValue(FrogMath.species("PUDDLE_JUMPER"), FrogMath.Tier.DIAMOND));
        assertEquals(4, FrogMath.lotusValue(FrogMath.species("COMMON_FROG"), FrogMath.Tier.BRONZE));
    }

    @Test void parseAndRoll() {
        Object[] p = FrogMath.parse("REALITY_HOPPER_SILVER");
        assertNotNull(p);
        assertEquals("REALITY_HOPPER", ((FrogMath.Species) p[0]).id());
        assertNull(FrogMath.parse("LOTUS_SILVER"));
        FrogMath.Context c = new FrogMath.Context();
        Random r = new Random(7);
        int frogs = 0;
        for (int i = 0; i < 10_000; i++) if (FrogMath.roll(c, r, 0.1, 0, 0, 0, 100, 600) != null) frogs++;
        assertTrue(frogs > 850 && frogs < 1150, "10% base gave " + frogs);
    }
}
