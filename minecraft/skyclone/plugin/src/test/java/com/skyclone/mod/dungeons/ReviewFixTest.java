package com.skyclone.mod.dungeons;

import com.skyclone.mod.dungeons.loot.LootTables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reviewer's regression tests for the part-B fixes that are pure logic. */
class ReviewFixTest {

    @BeforeAll
    static void load() throws Exception {
        try (InputStreamReader r = new InputStreamReader(ReviewFixTest.class.getClassLoader().getResourceAsStream("data/dungeons/loot.json"), StandardCharsets.UTF_8)) {
            LootTables.load(r);
        }
    }

    /**
     * A Fate Feather reroll passes the chest's RNG-meter item back in as the forced entry (the meter was already spent
     * on it): every reroll of that chest must still hold it, exactly once, and keep the rngItem marker.
     */
    @Test
    void rerollKeepsTheRngMeterItem() {
        for (String floor : new String[]{"F7", "M7", "F5"}) {
            LootTables.FloorLoot f = LootTables.floor(floor);
            LootTables.Entry best = null;
            for (LootTables.Entry e : f.entries) if (e.min() == LootTables.Chest.BEDROCK && (best == null || e.rng > best.rng)) best = e;
            if (best == null) continue;
            LootTables.Rolled first = LootTables.roll(f, LootTables.Chest.BEDROCK, new Random(1), e -> true, 0, 0, best.item, 1);
            assertEquals(best.item, first.rngItem);
            for (int seed = 0; seed < 200; seed++) {
                LootTables.Rolled again = LootTables.roll(f, LootTables.Chest.BEDROCK, new Random(seed), e -> true, 0, 0, first.rngItem, 1);
                long n = again.items.stream().filter(c -> LootTables.idOf(c).equals(first.rngItem)).count();
                assertEquals(1, n, floor + " seed " + seed + ": " + again.items);
                assertEquals(first.rngItem, again.rngItem);
            }
        }
    }

    /** A forced item never appears in a chest below its minimum tier (a reroll cannot move it somewhere cheaper). */
    @Test
    void forcedItemRespectsItsChestTier() {
        LootTables.FloorLoot f7 = LootTables.floor("F7");
        LootTables.Rolled gold = LootTables.roll(f7, LootTables.Chest.GOLD, new Random(5), e -> true, 0, 0, "NECRON_HANDLE", 1);
        assertFalse(gold.items.contains("NECRON_HANDLE:1"));
        assertEquals(null, gold.rngItem);
    }

    /** A roll never repeats an entry inside one chest, and fills exactly its slot count when the pool allows. */
    @Test
    void chestsHoldDistinctEntries() {
        for (String floor : new String[]{"F1", "F3", "F7", "M4", "M7"}) {
            LootTables.FloorLoot f = LootTables.floor(floor);
            for (LootTables.Chest c : LootTables.Chest.values()) {
                if (f.chest(c) == null) continue;
                for (int seed = 0; seed < 100; seed++) {
                    LootTables.Rolled r = LootTables.roll(f, c, new Random(seed), e -> true, 5, 0.02, null, 1);
                    Set<String> ids = new HashSet<>();
                    for (String cell : r.items) assertTrue(ids.add(LootTables.idOf(cell)), floor + " " + c + " repeats " + cell);
                    assertTrue(r.items.size() <= Math.max(1, f.chest(c).slots));
                    assertTrue(r.undead >= 1 && r.wither >= 1);
                }
            }
        }
    }
}
