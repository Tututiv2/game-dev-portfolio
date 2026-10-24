package com.skyclone.mod.armor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Wave 3 (2026-10-04): the bonuses that used to be lore only, and the requirements put back on. */
class ArmorWave3Test {

    // ---- formulas --------------------------------------------------------------------------

    @Test void rekindleMatchesResearch() {
        // research 06: +200% burning damage at 4/4, +1.2% per second on fire up to 50%.
        assertEquals(200, SetMath.rekindleBonus(4, 50, 0, 1.2, 50), 1e-9);
        assertEquals(212, SetMath.rekindleBonus(4, 50, 10, 1.2, 50), 1e-9);
        assertEquals(250, SetMath.rekindleBonus(4, 50, 1000, 1.2, 50), 1e-9);
        assertEquals(100, SetMath.rekindleBonus(2, 50, 0, 1.2, 50), 1e-9);
        assertEquals(0, SetMath.rekindleBonus(0, 50, 100, 1.2, 50), 1e-9);
        assertEquals(50, SetMath.rekindleBonus(1, 50, -5, 1.2, 50), 1e-9);   // negative clock never subtracts
    }

    @Test void bounceNeedsAHeightAndIsCapped() {
        assertEquals(0, SetMath.bounceVelocity(3, 4, 0.12), 1e-9);
        assertEquals(0.6, SetMath.bounceVelocity(5, 4, 0.12), 1e-9);
        assertEquals(2.5, SetMath.bounceVelocity(500, 4, 0.12), 1e-9);
        assertEquals(0, SetMath.bounceVelocity(50, 4, 0), 1e-9);
    }

    @Test void breathIsVanillaPlusFifteenSecondsPerLevel() {
        assertEquals(300, SetMath.maxAirTicks(0, 15));
        assertEquals(600, SetMath.maxAirTicks(1, 15));
        assertEquals(300 + 20 * 15 * 20, SetMath.maxAirTicks(20, 15));    // full Abyssal: 4 x 5 levels
        assertEquals(12000, SetMath.maxAirTicks(1_000, 15));               // capped at ten minutes
        assertEquals(300, SetMath.maxAirTicks(-3, 15));
    }

    @Test void crownsTakeTheBestMultiplier() {
        assertEquals(1, SetMath.crownCoins(false, false, false, 2, 5, 2), 1e-9);
        assertEquals(2, SetMath.crownCoins(true, false, false, 2, 5, 2), 1e-9);
        assertEquals(5, SetMath.crownCoins(false, true, false, 2, 5, 2), 1e-9);
        assertEquals(2, SetMath.crownCoins(false, false, true, 2, 5, 2), 1e-9);
        assertEquals(1, SetMath.crownCoins(true, false, false, 0.5, 5, 2), 1e-9);   // a dial below 1 never costs coins
    }

    // ---- generated data -----------------------------------------------------------------------

    static final Pattern REQ = Pattern.compile(
            "skill:[a-z_]+:\\d+|collection:[A-Z0-9_:]+:\\d+|hotm:\\d+|garden:\\d+|catacombs:\\d+|dungeon_completion:(E|F[1-7])|trophy:TITLE:[1-4]");

    static Map<String, JsonObject> items() throws Exception {
        Map<String, JsonObject> out = new HashMap<>();
        for (JsonElement e : ArmorPureTest.json("items.json")) out.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        return out;
    }

    @Test void everyRequirementHasAKnownShape() throws Exception {
        for (JsonObject it : items().values()) {
            if (!it.has("requirements")) continue;
            for (JsonElement r : it.getAsJsonArray("requirements"))
                assertTrue(REQ.matcher(r.getAsString()).matches(), it.get("id").getAsString() + " has odd requirement " + r.getAsString());
        }
    }

    @Test void dungeonAndTrophyRequirementsAreRealNow() throws Exception {
        Map<String, JsonObject> items = items();
        int dungeon = 0, trophy = 0;
        for (JsonObject it : items.values()) {
            JsonObject extra = it.getAsJsonObject("extra");
            if (extra == null) continue;
            JsonArray reqs = it.has("requirements") ? it.getAsJsonArray("requirements") : new JsonArray();
            if (extra.has("catacombsReq")) {
                String want = "catacombs:" + extra.get("catacombsReq").getAsInt();
                assertTrue(reqs.toString().contains(want), it.get("id").getAsString() + " lacks " + want);
                dungeon++;
            }
            if (extra.has("trophyReq")) {
                String tier = extra.get("trophyReq").getAsString().split(":")[1];
                int lvl = switch (tier) { case "bronze" -> 1; case "silver" -> 2; case "gold" -> 3; default -> 4; };
                assertTrue(reqs.toString().contains("trophy:TITLE:" + lvl), it.get("id").getAsString() + " lacks its trophy title");
                trophy++;
            }
        }
        assertTrue(dungeon >= 20, "dungeon-gated pieces: " + dungeon);
        assertEquals(16, trophy, "four Hunter sets of four");
        assertTrue(items.get("SUPER_HEAVY_HELMET").getAsJsonArray("requirements").toString().contains("catacombs:15"));
    }

    @Test void respirationSurvivesAsExtra() throws Exception {
        Map<String, JsonObject> items = items();
        assertEquals(5, items.get("ABYSSAL_HELMET").getAsJsonObject("extra").get("respiration").getAsInt());
        assertEquals(3, items.get("DIVER_BOOTS").getAsJsonObject("extra").get("respiration").getAsInt());
    }

    @Test void wave3IdsExist() throws Exception {
        Map<String, JsonObject> items = items();
        for (String id : new String[]{SetIds.SLUG_BOOTS, SetIds.MUSIC_PANTS, SetIds.WITCH_MASK, SetIds.CROWN_GREED})
            assertTrue(items.containsKey(id), id);
        java.util.Set<String> sets = new java.util.HashSet<>();
        for (JsonElement e : ArmorPureTest.json("items.json")) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("setId")) sets.add(o.get("setId").getAsString());
        }
        for (String s : new String[]{SetIds.ROTTEN, SetIds.REKINDLED, SetIds.GREAT_SPOOK, SetIds.SNORKELING, SetIds.STARLIGHT})
            assertTrue(sets.contains(s), s);
    }
}
