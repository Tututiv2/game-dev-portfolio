package com.skyclone.mod.hub;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.world.gen.Box;
import com.skyclone.mod.hub.gen.HubPlan;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static com.skyclone.mod.hub.GraveyardPack.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration round: a fresh player teleported into the Graveyard at night died in ~3 s, even at
 * 320 Defense, because vanilla's 35-block zombie follow range pulled most of 24 mobs onto him and
 * nothing limited how many swung at once. These pin the rule that fixes it.
 */
class GraveyardPackTest {

    static final double AGGRO = 16;
    static final int CAP = 3;

    @Test
    void nearestFewChaseTheRestWait() {
        assertEquals(CHASE, GraveyardPack.decide(5, false, false, 0, AGGRO, CAP));
        assertEquals(CHASE, GraveyardPack.decide(5, false, false, CAP - 1, AGGRO, CAP));
        assertEquals(HOLD, GraveyardPack.decide(5, false, false, CAP, AGGRO, CAP));
        assertEquals(HOLD, GraveyardPack.decide(5, false, false, 20, AGGRO, CAP));
        assertEquals(CHASE, GraveyardPack.decide(5, false, false, 20, AGGRO, 0), "cap 0 = no limit");
    }

    @Test
    void aMobYouHitAlwaysFightsBack() {
        assertEquals(CHASE, GraveyardPack.decide(5, false, true, 20, AGGRO, CAP));
        assertEquals(CHASE, GraveyardPack.decide(5, true, true, 0, AGGRO, CAP), "even from the gate, if you started it");
    }

    @Test
    void theArrivalGateIsQuiet() {
        assertEquals(HOLD, GraveyardPack.decide(2, true, false, 0, AGGRO, CAP));
    }

    @Test
    void chasesEndPastTheGiveUpRange() {
        double give = GraveyardPack.giveUpRange(AGGRO);
        assertTrue(give > AGGRO);
        assertEquals(DROP, GraveyardPack.decide(give + 0.1, false, false, 0, AGGRO, CAP));
        assertEquals(DROP, GraveyardPack.decide(give + 0.1, false, true, 0, AGGRO, CAP), "engaged mobs still give up eventually");
        assertEquals(CHASE, GraveyardPack.decide(give - 0.1, false, false, 0, AGGRO, CAP));
    }

    /** The shipped night population, spread over the shipped field, keeps one aggro circle at or under the cap. */
    @Test
    void nightDensityFitsUnderTheCap() throws Exception {
        int day = 0, night = 0;
        try (InputStream in = GraveyardPackTest.class.getResourceAsStream("/data/hub/spawns.json")) {
            assertNotNull(in, "data/hub/spawns.json on the test classpath");
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject r = el.getAsJsonObject();
                if (!"graveyard".equals(r.get("area").getAsString())) continue;
                int max = r.get("max").getAsInt();
                String cond = r.has("condition") ? r.get("condition").getAsString() : null;
                if (cond == null) { day += max; night += max; }
                else if (cond.equals("night")) night += max;
            }
        }
        assertTrue(day > 0 && night > day, "more at night than by day (research 03 4.3)");
        Box g = HubPlan.GRAVEYARD;
        double area = (double) (g.maxX() - g.minX() - 1) * (g.maxZ() - g.minZ() - 1);   // the spawn area is the field inside the wall
        double near = GraveyardPack.expectedWithin(night, area, AGGRO);
        assertTrue(near <= CAP, String.format("%d night mobs over %.0f blocks put %.2f in one aggro circle", night, area, near));
        // and the old vanilla range is exactly what this guards against
        assertTrue(GraveyardPack.expectedWithin(24, area, 35) > CAP * 2);
    }

    @Test
    void graveyardMobsCarryThePackBehaviour() throws Exception {
        try (InputStream in = GraveyardPackTest.class.getResourceAsStream("/data/hub/mobs.json")) {
            assertNotNull(in);
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            int found = 0;
            for (JsonElement el : arr) {
                JsonObject m = el.getAsJsonObject();
                String id = m.get("id").getAsString();
                if (!id.equals("hub_graveyard_zombie") && !id.equals("hub_zombie_villager")) continue;
                found++;
                assertTrue(m.has("behaviours") && m.get("behaviours").toString().contains(GraveyardPack.ID), id + " lacks " + GraveyardPack.ID);
            }
            assertEquals(2, found);
        }
    }

    /** Playtest "loops": both Crypt mobs run under the Crypt's own pack, not the Graveyard's. */
    @Test
    void cryptMobsCarryTheCryptPack() throws Exception {
        try (InputStream in = GraveyardPackTest.class.getResourceAsStream("/data/hub/mobs.json")) {
            assertNotNull(in);
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            int found = 0;
            for (JsonElement el : arr) {
                JsonObject m = el.getAsJsonObject();
                String id = m.get("id").getAsString();
                if (!id.equals("hub_crypt_ghoul") && !id.equals("hub_golden_ghoul")) continue;
                found++;
                String b = m.has("behaviours") ? m.get("behaviours").toString() : "";
                assertTrue(b.contains(GraveyardPack.CRYPT_ID), id + " lacks " + GraveyardPack.CRYPT_ID);
                assertFalse(b.contains("\"" + GraveyardPack.ID + "\""), id + " is in the Graveyard's pack");
            }
            assertEquals(2, found);
        }
        assertNotSame(GraveyardPack.INSTANCE, GraveyardPack.CRYPT);
        assertEquals("warp.crypt", GraveyardPack.CRYPT.gateAnchor);
    }

    /**
     * Wave-3 playtest: six deaths in the Graveyard for a new player, three chasers at a time. A newcomer
     * meets one at a time and the allowance climbs to the full cap by Combat 5 (the shipped dials 1 / 5).
     */
    @Test
    void newcomersFaceOneZombieAndTheCapClimbsToCombatFive() {
        int[] want = {1, 1, 1, 2, 2, 3, 3, 3};
        for (int lvl = 0; lvl < want.length; lvl++)
            assertEquals(want[lvl], GraveyardPack.capFor(CAP, 1, 5, lvl), "Combat " + lvl);
        assertEquals(CAP, GraveyardPack.capFor(CAP, 1, 5, 60));
        assertEquals(0, GraveyardPack.capFor(0, 1, 5, 0), "an admin's 'no limit' stays no limit");
        assertEquals(CAP, GraveyardPack.capFor(CAP, 0, 5, 0), "newcomer allowance 0 = off");
        assertEquals(CAP, GraveyardPack.capFor(CAP, 1, 0, 0), "ramp level 0 = off");
        assertEquals(2, GraveyardPack.capFor(2, 5, 5, 0), "a newcomer allowance above the cap is clipped to it");
        // with the newcomer cap the pure rule holds the second zombie
        assertEquals(HOLD, GraveyardPack.decide(5, false, false, 1, AGGRO, GraveyardPack.capFor(CAP, 1, 5, 0)));
        assertEquals(CHASE, GraveyardPack.decide(5, false, true, 1, AGGRO, GraveyardPack.capFor(CAP, 1, 5, 0)), "one you hit still fights back");
    }

    /** The playtest's suggestion, said at the gate: bank first, then armour and a sword, only what is missing. */
    @Test
    void graveyardTipSaysOnlyWhatIsMissing() {
        var all = GraveyardPack.tipLines(652, 0, false);
        String text = String.join("\n", all);
        assertTrue(text.contains("half your purse") && text.contains("326"), text);
        assertTrue(text.contains("Banker") && text.contains("Armorsmith") && text.contains("Weaponsmith"), text);
        var armed = String.join("\n", GraveyardPack.tipLines(0, 75, true));
        assertTrue(armed.isEmpty(), "nothing to say to an armed player with an empty purse: " + armed);
        var richArmed = String.join("\n", GraveyardPack.tipLines(500, 75, true));
        assertTrue(richArmed.contains("Banker") && !richArmed.contains("Armorsmith") && !richArmed.contains("Weaponsmith"), richArmed);
        for (String l : all) assertFalse(l.indexOf('§') >= 0, "legacy '&' codes only: " + l);
    }
}
