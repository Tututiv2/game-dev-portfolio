package com.skyclone.mod.accessories;

import com.skyclone.api.item.Rarity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the accessory formulas to the numbers in research/01 section 20 and research/06 section 7. */
class AccMathTest {

    @Test
    void multiplierMatchesResearchTable() {
        // research/01 20.2 table
        assertEquals(0.0, AccMath.multiplier(0), 1e-9);
        assertEquals(1.68, AccMath.multiplier(50), 0.01);
        assertEquals(3.675, AccMath.multiplier(100), 0.005);
        assertEquals(9.642, AccMath.multiplier(250), 0.002);
        assertEquals(18.462, AccMath.multiplier(500), 0.005);
        assertEquals(32.312, AccMath.multiplier(1000), 0.005);
        assertEquals(51.441, AccMath.multiplier(2000), 0.005);
        assertEquals(56.74, AccMath.multiplier(2361), 0.02);
    }

    @Test
    void powerAt250ReproducesStoneLore() {
        // Displaced Leech lore: +138.85 Strength at 250 power; generator stores 14.4005 per 1.00 multiplier
        assertEquals(138.85, 14.4005 * AccMath.multiplier(250), 0.01);
        // starter Fortuitous: 3.35 HP per 1.00 -> 32 HP at 250 (research/01 20.3)
        assertEquals(32.3, 3.35 * AccMath.multiplier(250), 0.1);
    }

    @Test
    void tuningPointsOnePerTen() {
        assertEquals(0, AccMath.tuningPoints(9, 10));
        assertEquals(25, AccMath.tuningPoints(250, 10));
        assertEquals(236, AccMath.tuningPoints(2361, 10));
        assertEquals(0, AccMath.tuningPoints(500, 0));
    }

    @Test
    void accessoryPowerPerRarity() {
        assertEquals(3, AccMath.mpFor(Rarity.COMMON, AccMath.DEFAULT_MP));
        assertEquals(5, AccMath.mpFor(Rarity.UNCOMMON, AccMath.DEFAULT_MP));
        assertEquals(8, AccMath.mpFor(Rarity.RARE, AccMath.DEFAULT_MP));
        assertEquals(12, AccMath.mpFor(Rarity.EPIC, AccMath.DEFAULT_MP));
        assertEquals(16, AccMath.mpFor(Rarity.LEGENDARY, AccMath.DEFAULT_MP));
        assertEquals(22, AccMath.mpFor(Rarity.MYTHIC, AccMath.DEFAULT_MP));
        assertEquals(28, AccMath.mpFor(Rarity.DIVINE, AccMath.DEFAULT_MP));
        assertEquals(3, AccMath.mpFor(Rarity.SPECIAL, AccMath.DEFAULT_MP));
        assertEquals(5, AccMath.mpFor(Rarity.VERY_SPECIAL, AccMath.DEFAULT_MP));
        // recombobulating a Legendary makes it Mythic: 16 -> 22
        assertEquals(22, AccMath.mpFor(Rarity.LEGENDARY.upgrade(), AccMath.DEFAULT_MP));
    }

    @Test
    void bagSlots() {
        assertEquals(9, AccMath.bagSlots(9, 0, 6, 0, 2, false, 4, 0));
        assertEquals(9, AccMath.bagSlots(9, 1, 6, 0, 2, false, 4, 0));       // tier I gives nothing
        assertEquals(15, AccMath.bagSlots(9, 2, 6, 0, 2, false, 4, 0));      // tier II: +6
        assertEquals(57, AccMath.bagSlots(9, 9, 6, 0, 2, false, 4, 0));      // tiers II..IX: +48
        assertEquals(57, AccMath.bagSlots(9, 12, 6, 0, 2, false, 4, 0));     // no tier X bonus
        assertEquals(271, AccMath.bagSlots(9, 9, 6, 6, 2, true, 4, 99));    // research max without the shard: 271
    }

    @Test
    void pairCostsAddUpToResearchTotal() {
        long total = 0;
        for (int n = 1; n <= 99; n++) total += AccMath.pairCost(n, 5360);
        assertEquals(1.76e9, total, 0.01e9);
        assertTrue(AccMath.pairCost(2, 5360) > AccMath.pairCost(1, 5360));
        assertEquals(0, AccMath.pairCost(0, 5360));
    }

    @Test
    void digitsAndGravity() {
        assertEquals(0, AccMath.digits(0));
        assertEquals(1, AccMath.digits(9));
        assertEquals(7, AccMath.digits(1_234_567));
        assertEquals(10, AccMath.gravity(0, 1, 10, 10), 1e-9);
        assertEquals(9, AccMath.gravity(15, 1, 10, 10), 1e-9);
        assertEquals(1, AccMath.gravity(5000, 1, 10, 10), 1e-9);
    }

    @Test
    void onlyBestOfFamilyCounts() {
        List<AccMath.Candidate<String>> c = new ArrayList<>();
        c.add(new AccMath.Candidate<>("ZOMBIE_TALISMAN", 0, 0, "zombie talisman"));
        c.add(new AccMath.Candidate<>("ZOMBIE_TALISMAN", 2, 2, "zombie artifact"));
        c.add(new AccMath.Candidate<>("ZOMBIE_TALISMAN", 1, 1, "zombie ring"));
        c.add(new AccMath.Candidate<>("SPEED_TALISMAN", 0, 0, "speed talisman"));
        c.add(new AccMath.Candidate<>("SPEED_TALISMAN", 0, 1, "recombobulated speed talisman"));
        c.add(new AccMath.Candidate<>("SPEED_TALISMAN", 0, 0, "duplicate speed talisman"));
        List<String> best = AccMath.pickBest(c);
        assertEquals(List.of("zombie artifact", "recombobulated speed talisman"), best);
    }

    @Test
    void tuningClampsToAvailablePoints() {
        Map<String, Integer> alloc = new LinkedHashMap<>();
        alloc.put("STRENGTH", 10);
        alloc.put("HEALTH", 10);
        Map<String, Integer> out = AccMath.clampTuning(alloc, List.of("HEALTH", "DEFENSE", "STRENGTH"), 15);
        assertEquals(10, out.get("HEALTH"));
        assertEquals(5, out.get("STRENGTH"));
        assertFalse(out.containsKey("DEFENSE"));
    }

    @Test
    void conditions() {
        Cond.Ctx day = ctx(false, false, "dwarven_mines", "village", 0);
        Cond.Ctx night = ctx(true, true, "garden", null, 1);
        assertTrue(Cond.parse("day").test(day));
        assertFalse(Cond.parse("day").test(night));
        assertTrue(Cond.parse("night").test(night));
        assertTrue(Cond.parse("burning").test(night));
        assertTrue(Cond.parse("island:gold_mine|dwarven_mines").test(day));
        assertFalse(Cond.parse("island:gold_mine|dwarven_mines").test(night));
        assertTrue(Cond.parse("zone:forest|village").test(day));
        assertTrue(Cond.parse("island:garden&season:1").test(night));
        assertFalse(Cond.parse("island:garden&season:0").test(night));
        assertTrue(Cond.parse(null).test(day));
        assertTrue(Cond.parse("always").test(day));
        assertFalse(Cond.parse("bogus:1").valid);
        assertFalse(Cond.parse("bogus:1").test(day));
    }

    private static Cond.Ctx ctx(boolean night, boolean burning, String island, String zone, int season) {
        return new Cond.Ctx() {
            @Override public boolean night() { return night; }
            @Override public boolean burning() { return burning; }
            @Override public boolean inDungeon() { return false; }
            @Override public String island() { return island; }
            @Override public String zone() { return zone; }
            @Override public int season() { return season; }
        };
    }
}
