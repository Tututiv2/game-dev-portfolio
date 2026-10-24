package com.skyclone.mod.chocolate;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Chocolate Factory's data and formulas against research/08 6.6, NEU hoppity.json and SkyHanni's tables. */
class ChocMathTest {

    static ChocCatalog catalog() {
        return ChocCatalog.load(f -> {
            InputStream in = ChocMathTest.class.getResourceAsStream("/data/chocolate/" + f);
            assertNotNull(in, "missing resource data/chocolate/" + f);
            return in;
        });
    }

    @Test
    void rosterMatchesNeu() {
        ChocCatalog c = catalog();
        assertEquals(577, c.rabbits.size());      // SkyHanni maxRabbits
        int[] want = {244, 142, 94, 53, 28, 11, 5};
        for (int i = 0; i < want.length; i++) assertEquals(want[i], c.ofRarity(ChocMath.RARITIES[i]).size(), ChocMath.RARITIES[i]);
        assertEquals(1, c.rabbit("aaron").cps);
        assertEquals(0.002, c.rabbit("aaron").mult, 1e-12);
        assertEquals(10, c.ofRarity("EPIC").get(0).cps);
        assertEquals(50, c.rabbit("dante").cps);                 // NEU special
        assertEquals(0.05, c.rabbit("galaxy").mult, 1e-12);       // NEU special
        assertEquals(0.025, c.rabbit("vega").mult, 1e-12);        // divine
    }

    @Test
    void everyNameIsOursAndUnique() {
        ChocCatalog c = catalog();
        Set<String> greek = Set.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda",
                "mu", "nu", "xi", "omicron", "pi", "rho", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega");
        Set<String> refs = new HashSet<>();
        for (ChocCatalog.Rabbit r : c.rabbits) refs.add(r.ref.toLowerCase());
        Set<String> seen = new HashSet<>();
        for (ChocCatalog.Rabbit r : c.rabbits) {
            assertTrue(seen.add(r.name.toLowerCase()), "duplicate name " + r.name);
            if (!greek.contains(r.id)) assertFalse(refs.contains(r.name.toLowerCase()), r.id + " kept a reference name: " + r.name);
            assertNotNull(r.texture, r.id);
        }
    }

    @Test
    void milestonesAndResidents() {
        ChocCatalog c = catalog();
        assertEquals(26, c.factory.factoryMilestones.size());
        assertEquals(24, c.factory.shopMilestones.size());
        assertEquals(1000, c.factory.factoryMilestones.get(0).amount, 0);
        assertEquals(700_000_000_000.0, c.factory.factoryMilestones.get(25).amount, 0);
        assertEquals("zest_zephyr", c.factory.factoryMilestones.get(25).rabbit);
        assertEquals(28, c.factory.hitmanCosts.size());
        assertEquals(5, c.residents("hub").size());
        assertEquals(5, c.residents("barn").size());              // the farming islands share theirs
        assertTrue(c.residents("hub").stream().anyMatch(r -> r.id.equals("castle")));
        assertEquals("stray", c.rabbit("el_dorado").source);
        for (String f : c.factory.factionRabbits) assertNotNull(c.rabbit(f), f);
    }

    @Test
    void prestigeAndUpgradeNumbers() {
        ChocCatalog c = catalog();
        ChocCatalog.Factory f = c.factory;
        assertEquals(0.1, ChocMath.prestigeBonus(c, 2), 1e-12);
        assertEquals(1.5, ChocMath.prestigeBonus(c, 6), 1e-12);
        assertEquals(150_000_000, ChocMath.prestigeRequirement(c, 2), 0);
        assertEquals(30_000_000_000.0, ChocMath.prestigeRequirement(c, 6), 0);
        assertEquals(-1, ChocMath.prestigeRequirement(c, 7), 0);
        assertEquals(500, ChocMath.handBakedCost(f.handBaked, 1), 0);       // L2 = 500 x (2-1)
        assertEquals(4500, ChocMath.handBakedCost(f.handBaked, 9), 0);      // L10 = 500 x 9
        assertEquals(5000, ChocMath.barnCost(f.barn, 1), 0);                // research: barn L2 costs 5,000
        assertEquals(20, ChocMath.barnCapacity(f.barn, 1));
        assertEquals(22, ChocMath.barnCapacity(f.barn, 2));
        ChocCatalog.Employee bro = c.employee("bro");
        assertEquals(bro.baseCost, ChocMath.employeeCost(bro, f.employeeGrowth, 0), 0);
        assertTrue(ChocMath.employeeCost(bro, f.employeeGrowth, 50) > ChocMath.employeeCost(bro, f.employeeGrowth, 49));
        assertEquals(120, ChocMath.employeeCap(f, bro, 1, 0));
        assertEquals(220, ChocMath.employeeCap(f, bro, 6, 0));
        assertEquals(223, ChocMath.employeeCap(f, bro, 6, 3));                 // Rabbit Youth
        assertEquals(220, ChocMath.employeeCap(f, c.employee("dog"), 6, 3));   // ... only Bro / Cousin / Sis
        assertEquals(1.5, f.timeTower.max * f.timeTower.perLevel, 1e-12);
        assertEquals(0.2, f.coach.max * f.coach.perLevel, 1e-12);
        assertEquals(1000, ChocMath.hitmanSlotCost(f, 0), 0);
        assertEquals(-1, ChocMath.hitmanSlotCost(f, 28), 0);
    }

    @Test
    void productionAndTowerWindow() {
        ChocCatalog c = catalog();
        Map<String, Integer> lv = new HashMap<>();
        lv.put("bro", 10);
        lv.put("dog", 2);
        assertEquals(24, ChocMath.employeeCps(c.factory, lv), 0);
        double[] rb = ChocMath.rabbitBonus(c, List.of("aaron", "dante", "galaxy"));
        assertEquals(51, rb[0], 0);
        assertEquals(0.052, rb[1], 1e-12);
        // base 100, 1.0x + tower 0.5
        ChocMath.Production p = new ChocMath.Production(100, 0, 0, 0, 0, 0, 0, 0, 0.5, 0, 0, 0, 1);
        assertEquals(150, p.cps(), 1e-9);
        assertEquals(100, p.cpsWithoutTower(), 1e-9);
        // 10 s window, tower ends after 4 s -> 4 x 150 + 6 x 100
        assertEquals(1200, ChocMath.produce(p, 0, 10_000, 4_000), 1e-9);
        assertEquals(1000, ChocMath.produce(p, 0, 10_000, 0), 1e-9);
        assertEquals(1500, ChocMath.produce(p, 0, 10_000, 99_000), 1e-9);
        assertEquals(0, ChocMath.produce(p, 10_000, 10_000, 0), 1e-9);
        // speed attribute multiplies the whole thing
        ChocMath.Production s = new ChocMath.Production(100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.01, 2);
        assertEquals(202, s.cps(), 1e-9);
    }

    @Test
    void eggClock() {
        long sixFiftyNine = ChocMath.absMinute(1, 1, 1, 6, 59);
        assertEquals(-1, ChocMath.mealInstance(sixFiftyNine, 7));
        assertEquals(0, ChocMath.mealInstance(sixFiftyNine + 1, 7));
        assertEquals(0, ChocMath.mealInstance(sixFiftyNine + 1, 0));   // Supper of day 0 spawned at midnight
        assertEquals(1, ChocMath.minutesUntilNext(sixFiftyNine, 7));
        assertTrue(ChocMath.springAt(ChocMath.absMinute(3, 3, 31, 23, 59)));
        assertFalse(ChocMath.springAt(ChocMath.absMinute(3, 4, 1, 0, 0)));
        assertEquals(12, ChocMath.monthOf(ChocMath.absMinute(2, 12, 31, 23, 59)));
        assertEquals(1, ChocMath.monthOf(ChocMath.absMinute(3, 1, 1, 0, 0)));
    }

    @Test
    void shrineAndLuckWeights() {
        ChocCatalog c = catalog();
        double[] base = ChocMath.eggWeights(c.factory.eggOdds, 0, 0, 0, 0);
        double[] shrine = ChocMath.eggWeights(c.factory.eggOdds, 0.2, 0, 0, 0);
        assertEquals(base[0], shrine[0], 0);
        assertEquals(base[1], shrine[1], 0);
        assertEquals(base[2] * 1.2, shrine[2], 1e-9);
        assertEquals(base[6] * 1.2, shrine[6], 1e-9);
        double[] luck = ChocMath.eggWeights(c.factory.eggOdds, 0, 0.05, 0, 0);
        assertEquals(base[4] * 1.05, luck[4], 1e-9);
        assertEquals(base[5], luck[5], 0);
        assertEquals(0, ChocMath.pickWeighted(new double[]{1, 1}, 0.0));
        assertEquals(1, ChocMath.pickWeighted(new double[]{1, 1}, 0.99));
        assertEquals(1, ChocMath.pickWeighted(new double[]{0, 1}, 0.0));
    }

    @Test
    void eggsHoldTheRightRabbits() {
        ChocCatalog c = catalog();
        double[] w = ChocMath.eggWeights(c.factory.eggOdds, 0, 0, 0, 0);
        Random rng = new Random(1);
        Set<String> islands = Set.of("hub", "barn", "the_end");
        Set<String> owned = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            ChocCatalog.Rabbit r = ChocMath.rollEgg(c, null, islands::contains, owned, w, 0.5, 0, rng::nextDouble);
            assertNotNull(r);
            assertTrue(r.fromEggs(), r.id);
            if (r.resident()) assertFalse(r.islands.stream().anyMatch(islands::contains), "resident " + r.id + " off its island");
        }
        // on the hub with residentChance 1, only hub residents
        for (int i = 0; i < 200; i++) {
            ChocCatalog.Rabbit r = ChocMath.rollEgg(c, "hub", islands::contains, owned, w, 1.0, 0, rng::nextDouble);
            assertTrue(r.resident() && r.islands.contains("hub"), r.id);
        }
        // unique bonus 1.0: while unfound rabbits remain, a duplicate pick always re-rolls into a new one
        Set<String> got = new HashSet<>();
        double[] onlyCommon = {1, 0, 0, 0, 0, 0, 0};
        int commons = (int) c.ofRarity("COMMON").stream().filter(r -> r.fromEggs() && !r.resident()).count();
        for (int i = 0; i < commons; i++) {
            ChocCatalog.Rabbit r = ChocMath.rollEgg(c, null, x -> true, got, onlyCommon, 0, 1.0, rng::nextDouble);
            assertTrue(got.add(r.id), "repeat " + r.id + " at " + i);
        }
        // milestone rabbits never come from eggs
        assertNotEquals("egg", c.rabbit("almond_amaretto").source);
    }

    @Test
    void duplicatesPayAtLeastTheFloor() {
        ChocCatalog.Factory f = catalog().factory;
        assertEquals(50, ChocMath.duplicateReward(f, "COMMON", 0, 0), 0);
        assertEquals(1200, ChocMath.duplicateReward(f, "COMMON", 10, 0), 0);          // 120 s x 10
        assertEquals(1440, ChocMath.duplicateReward(f, "COMMON", 10, 0.2), 0);        // +20% (Masked Hopper)
        assertTrue(ChocMath.duplicateReward(f, "DIVINE", 0, 0) > ChocMath.duplicateReward(f, "MYTHIC", 0, 0));
        assertEquals(5, ChocMath.straySeconds(0));
        assertEquals(50, ChocMath.straySeconds(0.999));
        assertEquals(480, ChocMath.goldenSeconds("jackpot"), 0);
        assertEquals(1500, ChocMath.goldenSeconds("mountain"), 0);
    }
}
