package com.skyclone.mod.armor;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Wave 5 (2026-10-06): raised caps, Fear, the Blaze pet's ability half, and the upgrade graph. */
class ArmorWave5Test {

    static ArmorSets sets() throws Exception {
        try (Reader r = Files.newBufferedReader(ArmorPureTest.res("sets.json"), StandardCharsets.UTF_8)) {
            return ArmorSets.parse(r);
        }
    }

    // ---- formulas --------------------------------------------------------------------------

    @Test void youngBloodLetsSpeedThroughToFiveHundred() {
        assertEquals(0, SetMath.speedCapScalar(350, 400, 100), 1e-9);    // under the cap: core already applies it
        assertEquals(0, SetMath.speedCapScalar(400, 400, 100), 1e-9);
        assertEquals(0.125, SetMath.speedCapScalar(450, 400, 100), 1e-9); // 450 / 400 - 1
        assertEquals(0.25, SetMath.speedCapScalar(800, 400, 100), 1e-9);  // capped at 500
        assertEquals(0, SetMath.speedCapScalar(800, 400, 0), 1e-9);       // dial 0 = off
    }

    @Test void hitWindowMatchesCoreAndNewtonShortensIt() {
        // core: combat.mob-invuln-ticks 10, attack-speed-cap 100, min 2
        assertEquals(10, SetMath.hitWindow(10, 0, 100, 2));
        assertEquals(5, SetMath.hitWindow(10, 100, 100, 2));
        assertEquals(5, SetMath.hitWindow(10, 150, 100, 2));    // core caps 150 AS at 100
        assertEquals(4, SetMath.hitWindow(10, 150, 150, 2));    // +50 cap: 10 / 2.5
        assertEquals(2, SetMath.hitWindow(3, 500, 1000, 2));    // floor
    }

    @Test void newtonOnlyLandsWhatCoreRefusedForItsOwnRate() {
        assertTrue(SetMath.newtonMayLand(4, 4, 5, 4));           // core refused (4 < 5), ours has passed (4 >= 4)
        assertFalse(SetMath.newtonMayLand(5, 5, 5, 4));          // core would have accepted: not its rate limit
        assertFalse(SetMath.newtonMayLand(4, 3, 5, 4));          // our own window not over yet
        assertFalse(SetMath.newtonMayLand(-1, 99, 5, 4));        // core never landed one: nothing to compare
        assertFalse(SetMath.newtonMayLand(4, 4, 5, 5));          // no gain from the set
    }

    @Test void greaterSpookReforgeFear() {
        assertEquals(4, SetMath.reforgeFear("greater_spook", 3), 1e-9);  // EPIC
        assertEquals(5, SetMath.reforgeFear("greater_spook", 4), 1e-9);  // LEGENDARY
        assertEquals(5, SetMath.reforgeFear("GREATER_SPOOK", 5), 1e-9);  // recombobulated: nearest lower defined
        assertEquals(0, SetMath.reforgeFear("clean", 4), 1e-9);
        assertEquals(0, SetMath.reforgeFear(null, 4), 1e-9);
    }

    @Test void blazePetBoostIsAPlainPercentage() {
        assertEquals(140, SetMath.petBoosted(100, 40), 1e-9);
        assertEquals(100, SetMath.petBoosted(100, 0), 1e-9);
        assertEquals(100, SetMath.petBoosted(100, -5), 1e-9);
    }

    // ---- generated data -----------------------------------------------------------------------

    @Test void otherStatsKeptForFearAndPressure() throws Exception {
        var items = ArmorWave3Test.items();
        double fear = 0;
        for (String id : List.of("GREAT_SPOOK_HELMET", "GREAT_SPOOK_CHESTPLATE", "GREAT_SPOOK_LEGGINGS", "GREAT_SPOOK_BOOTS"))
            fear += items.get(id).getAsJsonObject("extra").getAsJsonObject("otherStats").get("FEAR").getAsDouble();
        assertEquals(20, fear, 1e-9);   // research 06: Great Spook Fear +20
        assertEquals(15, items.get("DIVER_HELMET").getAsJsonObject("extra").getAsJsonObject("otherStats")
                .get("PRESSURE_RESISTANCE").getAsDouble(), 1e-9);
        for (JsonObject o : items.values()) {
            JsonObject x = o.getAsJsonObject("extra");
            if (x == null || !x.has("otherStats")) continue;
            for (var e : x.getAsJsonObject("otherStats").entrySet())
                assertTrue(List.of("FEAR", "PRESSURE_RESISTANCE").contains(e.getKey()), o.get("id") + " " + e.getKey());
        }
    }

    @Test void anglerUpgradesIntoBackwaterAtTheScrapper() throws Exception {
        ArmorSets s = sets();
        ArmorSets.SetInfo back = s.get("backwater");
        assertEquals(List.of("angler"), back.upgradeFrom);
        assertTrue(back.upgradeInto.isEmpty(), "Backwater is the top of its line: " + back.upgradeInto);
        assertEquals(List.of("angler", "backwater"), s.upgradeLine("backwater"));
        assertEquals(4, back.shops.size());
        for (ArmorSets.Shop sh : back.shops) {
            assertTrue(back.pieces.contains(sh.piece));
            String angler = sh.piece.replace("BACKWATER", "ANGLER");
            assertTrue(sh.items.contains(angler + ":1"), sh.items.toString());
            assertTrue(sh.items.contains("RUSTY_COIN:4"), sh.items.toString());
            assertFalse(sh.npc.toLowerCase().contains("joel"), "reference NPC name leaked: " + sh.npc);
        }
    }

    @Test void upgradeGraphIsConsistent() throws Exception {
        ArmorSets s = sets();
        assertEquals(List.of("cropie", "squash", "fermento", "helianthus"),
                s.upgradeLine("squash").subList(s.upgradeLine("squash").indexOf("cropie"), s.upgradeLine("squash").size()));
        int edges = 0;
        for (ArmorSets.SetInfo x : s.all()) {
            for (String up : x.upgradeInto) {
                edges++;
                assertNotNull(s.get(up), x.id + " -> " + up);
                assertTrue(s.get(up).upgradeFrom.contains(x.id), x.id + " -> " + up + " not mirrored");
                assertNotEquals(x.id, up);
            }
            List<String> line = s.upgradeLine(x.id);
            assertTrue(line.contains(x.id), x.id + " " + line);
            assertEquals(line.size(), new java.util.HashSet<>(line).size(), "cycle in " + line);
        }
        assertTrue(edges >= 30, "upgrade edges " + edges);
    }

    @Test void shopOffersArePiecesOfTheirOwnSet() throws Exception {
        int n = 0;
        for (ArmorSets.SetInfo x : sets().all()) for (ArmorSets.Shop sh : x.shops) {
            n++;
            assertTrue(x.pieces.contains(sh.piece), x.id + " sells " + sh.piece);
            assertNotNull(sh.npc);
            assertTrue(sh.coins >= 0);
            for (String c : sh.items) assertTrue(c.matches("[A-Z0-9_;]+:\\d+"), c);
        }
        assertTrue(n > 20, "offers " + n);
    }

    @Test void setsJsonStillParsesWithoutTheNewFields() {
        ArmorSets s = ArmorSets.parse(new java.io.StringReader("[{\"id\":\"x\",\"pieces\":[\"A\"],\"shops\":[null]}]"));
        assertTrue(s.get("x").shops.isEmpty());
        assertTrue(s.get("x").upgradeFrom.isEmpty());
        assertEquals(List.of("x"), s.upgradeLine("x"));
    }
}
