package com.skyclone.mod.combatzones;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipping dragon / broodmother / colossus formulas against research 03 sec 13-14. */
class DragonMathTest {

    @Test
    void variantWeightsMatchResearch() {
        double sum = 0;
        for (DragonMath.Variant v : DragonMath.VARIANTS) sum += v.weight();
        assertEquals(100, sum, 1e-9);
        assertEquals(4, DragonMath.variant("superior").weight());
        assertEquals(15_000_000, DragonMath.variant("old").health());
        assertEquals(7_500_000, DragonMath.variant("young").health());
        assertEquals(2200, DragonMath.variant("wise").damage());
    }

    @Test
    void rollFollowsWeights() {
        Random r = new Random(42);
        Map<String, Integer> n = new HashMap<>();
        for (int i = 0; i < 100_000; i++) n.merge(DragonMath.roll(Map.of(), r).id(), 1, Integer::sum);
        assertEquals(0.04, n.getOrDefault("superior", 0) / 100_000.0, 0.005);
        assertEquals(0.16, n.getOrDefault("young", 0) / 100_000.0, 0.01);
        assertEquals(0, n.getOrDefault("holy", 0));
    }

    @Test
    void qualityAndAwards() {
        assertEquals(1000, DragonMath.quality(8, 1));
        assertEquals(300, DragonMath.quality(1, 1));
        assertEquals(125, DragonMath.quality(0, 4));
        // 1 eye + top damage buys the helmet (295), never the 410 chestplate
        assertEquals("YOUNG_DRAGON_HELMET", DragonMath.award("young", 300, 1, new Random(1), id -> true, 8).item());
        // nothing affordable -> fragments: 125 / 22 = 5
        DragonMath.Award a = DragonMath.award("young", 125, 0, new Random(1), id -> true, 8);
        assertNull(a.item());
        assertEquals(5, a.fragments());
        // a missing armour set falls through to the next affordable drop
        DragonMath.Award b = DragonMath.award("old", 1000, 8, new Random(7), id -> !id.startsWith("OLD_DRAGON"), 8);
        assertTrue(b.item() == null || !b.item().startsWith("OLD_DRAGON"));
    }

    @Test
    void placementsSortByDamage() {
        Map<String, Double> d = new LinkedHashMap<>();
        d.put("a", 10.0); d.put("b", 50.0); d.put("c", 0.0); d.put("e", 30.0);
        Map<String, Integer> p = DragonMath.placements(d);
        assertEquals(1, p.get("b"));
        assertEquals(2, p.get("e"));
        assertEquals(3, p.get("a"));
        assertNull(p.get("c"));
    }

    @Test
    void abilitiesEnrageBelowHalf() {
        assertEquals(550, DragonMath.abilityDamage(DragonMath.RUSH, DragonMath.variant("young"), 0.9), 1e-9);
        assertEquals(825, DragonMath.abilityDamage(DragonMath.RUSH, DragonMath.variant("young"), 0.4), 1e-9);
        assertEquals(400, DragonMath.abilityDamage(DragonMath.FIREBALL, DragonMath.variant("wise"), 0.9), 1e-9);
    }

    @Test
    void everyVariantNameIsReadableOnADarkSidebar() {
        // playtest-endgame: "Protector Dragon" in &8 was dark gray on the dark sidebar
        for (DragonMath.Variant v : DragonMath.VARIANTS) {
            char c = DragonMath.readable(v.color());
            assertTrue("0123458".indexOf(c) < 0, v.name() + " shows in dark colour &" + c);
        }
        assertEquals('7', DragonMath.readable('8'));
        assertEquals('6', DragonMath.readable('6'));
    }

    @Test
    void broodmotherClockAndColossusMeter() {
        assertEquals(0, DragonMath.broodStage(0, 600));
        assertEquals(2, DragonMath.broodStage(250, 600));
        assertEquals(4, DragonMath.broodStage(599, 600));
        assertEquals(5, DragonMath.broodStage(600, 600));
        assertEquals(0, DragonMath.protectorStage(149, 150));
        assertEquals(5, DragonMath.protectorStage(10_000, 150));
    }
}
