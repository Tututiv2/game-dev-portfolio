package com.skyclone.mod.farming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5 second pass: Shiny Pigs, the Dark Cacao Truffle and the pets' info perks farming applies, on shipped data. */
class FarmingShinyTest {

    private static JsonElement res(String path) {
        InputStream in = FarmingShinyTest.class.getResourceAsStream(path);
        assertNotNull(in, "missing resource " + path);
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static Tables tables() {
        return Tables.read(new InputStreamReader(FarmingShinyTest.class.getResourceAsStream("/data/farming/tables.json"), StandardCharsets.UTF_8),
                new InputStreamReader(FarmingShinyTest.class.getResourceAsStream("/data/farming/shops.json"), StandardCharsets.UTF_8));
    }

    @Test
    void shinyPigTiersAreTheBestiaryBracket() {
        int[] th = tables().shinyPigTiers;
        // NEU constants/bestiary.json: hub Shiny Pig, bracket 5, cap 400
        assertArrayEquals(new int[]{1, 2, 3, 5, 7, 10, 15, 20, 25, 30, 60, 120, 200, 300, 400}, th);
        assertEquals(0, ShinyMath.tier(0, th));
        assertEquals(1, ShinyMath.tier(1, th));
        assertEquals(3, ShinyMath.tier(4, th));
        assertEquals(10, ShinyMath.tier(59, th));
        assertEquals(15, ShinyMath.tier(400, th));
        assertEquals(15, ShinyMath.tier(1_000_000, th));
        // Pig pet Shining Stampede at Lv100 Epic/Legendary: +5 per tier -> +75 Potato Fortune at max
        assertEquals(75.0, ShinyMath.stampedeFortune(5, ShinyMath.tier(400, th)), 1e-9);
        assertEquals(0.0, ShinyMath.stampedeFortune(0, 15), 1e-9);
    }

    @Test
    void coinsStackHamfistedAndTheRod() {
        assertEquals(2000, ShinyMath.coins(2000, 100, 0, 0, 0));
        assertEquals(7000, ShinyMath.coins(2000, 100, 50, 0, 0));
        assertEquals(10500, ShinyMath.coins(2000, 100, 50, 50, 0));     // research 06: Hamfisted +50%
        assertEquals(13125, ShinyMath.coins(2000, 100, 50, 50, 25));    // NEU: Shiny Rod +25% from orbs
        assertEquals(0, ShinyMath.coins(-5, -1, 10, -50, -25));         // nothing negative ever pays or charges
    }

    @Test
    void fleeingPigRunsAwayButCirclesItsOrb() {
        double[] t = ShinyMath.fleeTarget(2, 0, 0, 0, 0, 0, 6, 18);
        assertEquals(8.0, t[0], 1e-9);
        assertEquals(0.0, t[1], 1e-9);
        // at the edge of the circle it never goes further out than it is
        for (int a = 0; a < 360; a += 15) {
            double r = Math.toRadians(a);
            double px = 17 * Math.cos(r), pz = 17 * Math.sin(r);
            double[] e = ShinyMath.fleeTarget(px, pz, px * 0.5, pz * 0.5, 0, 0, 6, 18);
            assertTrue(Math.hypot(e[0], e[1]) <= 18.0, "left the orb circle at " + a + " deg: " + Math.hypot(e[0], e[1]));
            // and it still moves (no standing still at the rim)
            assertTrue(Math.hypot(e[0] - px, e[1] - pz) > 3, "stalled at " + a);
        }
        // a player exactly on the pig still gives a direction
        double[] same = ShinyMath.fleeTarget(5, 5, 5, 5, 0, 0, 6, 18);
        assertFalse(Double.isNaN(same[0]) || Double.isNaN(same[1]));
        assertTrue(ShinyMath.delivered(1.5, 1, -1.5, 2.5));
        assertFalse(ShinyMath.delivered(2.0, 0, 2.0, 2.5));
        assertFalse(ShinyMath.delivered(0, 4, 0, 2.5));
    }

    @Test
    void truffleIsThirtyForAnHourPlusOnePermanentUpToFive() {
        assertEquals(30.0, ShinyMath.truffleFortune(0, 5, true, 30), 1e-9);
        assertEquals(31.0, ShinyMath.truffleFortune(1, 5, true, 30), 1e-9);
        assertEquals(5.0, ShinyMath.truffleFortune(5, 5, false, 30), 1e-9);
        assertEquals(5.0, ShinyMath.truffleFortune(12, 5, false, 30), 1e-9);
        assertEquals(0.0, ShinyMath.truffleFortune(3, 0, false, 30), 1e-9);
    }

    @Test
    void generatedShinyPigAndOrbMatchTheDesign() {
        JsonObject pig = null;
        for (JsonElement e : res("/data/farming/mobs.json").getAsJsonArray())
            if ("shiny_pig".equals(e.getAsJsonObject().get("id").getAsString())) pig = e.getAsJsonObject();
        assertNotNull(pig, "mobs.json has no shiny_pig");
        assertEquals("shiny_pig", pig.get("family").getAsString());                  // progression's curated family
        assertTrue(pig.getAsJsonArray("behaviours").toString().contains(Shiny.BEHAVIOUR));
        assertEquals(0, pig.getAsJsonArray("drops").size());
        assertEquals(0.0, pig.get("coins").getAsDouble(), 1e-9);
        boolean orb = false;
        for (JsonElement e : res("/data/farming/items.json").getAsJsonArray())
            if (Shiny.ORB.equals(e.getAsJsonObject().get("id").getAsString())) orb = e.getAsJsonObject().has("skull");
        assertTrue(orb, "SHINY_ORB with the reference head");
        // and the progression bestiary really has that family (if its data ships in this jar)
        InputStream best = FarmingShinyTest.class.getResourceAsStream("/data/progression/bestiary.json");
        if (best != null) {
            String text = new String(readAll(best), StandardCharsets.UTF_8);
            assertTrue(text.contains("\"shiny_pig\""), "progression bestiary lost its shiny_pig family");
        }
    }

    @Test
    void everyPetPerkFarmingReadsExistsInThePetData() {
        Set<String> keys = new HashSet<>();
        for (JsonElement p : res("/data/pets/pets.json").getAsJsonArray()) {
            JsonObject tiers = p.getAsJsonObject().getAsJsonObject("tiers");
            if (tiers == null) continue;
            for (var t : tiers.entrySet()) {
                JsonObject tier = t.getValue().getAsJsonObject();
                if (!tier.has("abilities")) continue;
                for (JsonElement ab : tier.getAsJsonArray("abilities")) keys.add(ab.getAsJsonObject().get("key").getAsString());
            }
        }
        for (String k : Perks.FARMING_PET_PERKS) assertTrue(keys.contains(k), "pets.json has no ability " + k);
    }

    private static byte[] readAll(InputStream in) {
        try (in) { return in.readAllBytes(); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
