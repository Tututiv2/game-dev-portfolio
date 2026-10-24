package com.skyclone.mod.crimson;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-game audit 2026-10-06, lane 2 (docs/AUDIT_REAL_GAME_2026-10-06.md): K13 quest reputation, K14 prestige coin
 * part, K16 once-a-day Leviathan reputation, K17 Emissary key discount, K18 Rollim, A1 star coin costs - all
 * against the shipping JSON.
 */
class CrimsonAuditTest {

    private static JsonElement json(String path) {
        InputStream in = CrimsonAuditTest.class.getResourceAsStream(path);
        assertNotNull(in, "missing resource " + path);
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static Map<String, JsonObject> items() {
        Map<String, JsonObject> out = new HashMap<>();
        for (JsonElement e : json("/data/crimson/items.json").getAsJsonArray()) out.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        return out;
    }

    @Test
    void k13QuestRepByRarity() {
        Map<String, Integer> want = Map.of("easy", 30, "medium", 50, "hard", 75, "elite", 100, "legendary", 125);
        int legendary = 0;
        for (CrimsonTables.Quest q : CrimsonRulesTest.tables().quests) {
            assertEquals(want.get(q.difficulty), q.rep, q.id + " (" + q.difficulty + ")");
            if (q.difficulty.equals("legendary")) legendary++;
        }
        assertTrue(legendary > 0, "some quest is Legendary (125)");
    }

    @Test
    void noBoardClearBonusInTheReferenceGame() {
        assertEquals(0, CrimsonRulesTest.tables().factions.daily_bonus_rep, "crimson.daily-bonus-rep defaults to 0 (follow-up 2026-10-06)");
    }

    @Test
    void k14PrestigeCoinsAndTheTierShiftIsTheResearchs() {
        Map<String, JsonObject> it = items();
        // essence + teeth stay the Hypixel API's (SkyHanni agrees): the 4,500 + 50 row is Burning -> Fiery, not Hot -> Burning
        String[] from = {"CRIMSON_HELMET", "HOT_CRIMSON_HELMET", "BURNING_CRIMSON_HELMET", "FIERY_CRIMSON_HELMET"};
        String[] ess = {"ESSENCE_CRIMSON:150", "ESSENCE_CRIMSON:800", "ESSENCE_CRIMSON:4500", "ESSENCE_CRIMSON:25500"};
        String[] teeth = {"KUUDRA_TEETH:10", "KUUDRA_TEETH:20", "KUUDRA_TEETH:50", "KUUDRA_TEETH:80"};
        long[] coins = {2_000_000, 5_000_000, 10_000_000, 20_000_000};
        for (int i = 0; i < from.length; i++) {
            JsonObject x = it.get(from[i]).getAsJsonObject("extra");
            JsonArray pc = x.getAsJsonArray("prestigeCost");
            assertEquals(ess[i], pc.get(0).getAsString(), from[i]);
            assertEquals(teeth[i], pc.get(1).getAsString(), from[i]);
            assertEquals(coins[i], x.get("prestigeCoins").getAsLong(), from[i]);
        }
        assertFalse(it.get("INFERNAL_CRIMSON_HELMET").getAsJsonObject("extra").has("prestigeCoins"));
        // every one of the 80 prestigeable pieces carries the coin part
        int n = 0;
        for (JsonObject o : it.values()) {
            JsonObject x = o.getAsJsonObject("extra");
            if (x != null && x.has("prestigeTo")) { assertTrue(x.has("prestigeCoins"), o.get("id").getAsString()); n++; }
        }
        assertEquals(80, n);
    }

    @Test
    void k16LeviathanRepOncePerDayPerTier() {
        List<Integer> t = CrimsonRulesTest.tables().factions.kuudra_rep;
        for (int i = 0; i < 5; i++) assertEquals(10 * (i + 1), CrimsonRules.kuudraRep(t, i));
        assertEquals(0, CrimsonRules.kuudraRep(t, 5));
        assertEquals(0, CrimsonRules.kuudraRep(t, -1));
        assertFalse(CrimsonRules.kuudraRepDue(-1, 7, null, "2026-10-06"), "first look only takes the baseline");
        assertTrue(CrimsonRules.kuudraRepDue(7, 8, null, "2026-10-06"), "a new clear pays");
        assertTrue(CrimsonRules.kuudraRepDue(7, 8, "2026-10-05", "2026-10-06"), "paid yesterday: pays again today");
        assertFalse(CrimsonRules.kuudraRepDue(8, 9, "2026-10-06", "2026-10-06"), "second clear of the tier today");
        assertFalse(CrimsonRules.kuudraRepDue(8, 8, null, "2026-10-06"), "no new clear");
        assertEquals(List.of(10, 20, 30, 40, 50), CrimsonRules.parseInts("10/20/30/40/50"));
        assertEquals(List.of(1, 2), CrimsonRules.parseInts(" 1, 2 "));
        assertNull(CrimsonRules.parseInts("10/x"));
        assertNull(CrimsonRules.parseInts(""));
    }

    @Test
    void k17EmissaryDiscount() {
        Map<String, Integer> t = CrimsonRulesTest.tables().factions.emissary_discount;
        assertEquals(0, CrimsonRules.emissaryDiscount(t, 999));
        assertEquals(5, CrimsonRules.emissaryDiscount(t, 1000));
        assertEquals(10, CrimsonRules.emissaryDiscount(t, 3000));
        assertEquals(10, CrimsonRules.emissaryDiscount(t, 6999));
        assertEquals(15, CrimsonRules.emissaryDiscount(t, 7000));
        assertEquals(20, CrimsonRules.emissaryDiscount(t, 12000));
        assertEquals(20, CrimsonRules.emissaryDiscount(t, 99999));
        assertEquals(190_000, CrimsonRules.discounted(200_000, 5));
        assertEquals(2_400_000, CrimsonRules.discounted(3_000_000, 20));
        assertEquals(400_000, CrimsonRules.discounted(400_000, 0));
        assertEquals(t, CrimsonRules.parseThresholds(CrimsonRules.thresholdsText(t)), "dial text round-trips");
        assertNull(CrimsonRules.parseThresholds("1000-5"));
        // only the ten Leviathan keys are Emissary goods, at the reference coin prices
        Map<String, Double> price = Map.of("KUUDRA_TIER_KEY", 200_000.0, "KUUDRA_HOT_TIER_KEY", 400_000.0,
                "KUUDRA_BURNING_TIER_KEY", 750_000.0, "KUUDRA_FIERY_TIER_KEY", 1_500_000.0, "KUUDRA_INFERNAL_TIER_KEY", 3_000_000.0);
        int keys = 0;
        for (CrimsonTables.ShopEntry e : CrimsonRulesTest.tables().shop) {
            assertEquals(price.containsKey(e.item), e.emissary, e.item);
            if (e.emissary) { keys++; assertEquals(price.get(e.item), e.coins, e.item); }
        }
        assertEquals(10, keys);
    }

    @Test
    void k18Rollim() {
        CrimsonTables.Rollim r = CrimsonRulesTest.tables().factions.rollim;
        assertEquals(25, r.rep);
        assertEquals(7, r.days);
        assertEquals(24, r.hours);
        long h = 3_600_000L, now = 1_000_000_000_000L;
        assertTrue(CrimsonRules.rollimDue(0, 0, now, 7, 24), "first claim");
        assertFalse(CrimsonRules.rollimDue(1, now - 23 * h, now, 7, 24), "23 h later");
        assertTrue(CrimsonRules.rollimDue(1, now - 24 * h, now, 7, 24), "24 h later");
        assertFalse(CrimsonRules.rollimDue(7, 0, now, 7, 24), "a week of claims is the lot");
    }

    @Test
    void a1StarCoinCosts() {
        // crimson's own copy of the ladder and the reforges module's stars.json (what the Essence menu charges)
        JsonArray mine = items().get("HOT_CRIMSON_HELMET").getAsJsonObject("extra").getAsJsonArray("starCosts");
        long sum = 0;
        for (int s = 7; s < 10; s++)
            for (JsonElement c : mine.get(s).getAsJsonArray()) if (c.getAsString().startsWith("COINS:")) sum += Long.parseLong(c.getAsString().substring(6));
        assertEquals(1_750_000, sum, "stars 8-10 cost 250k + 500k + 1M coins");
        JsonObject stars = json("/data/reforges/stars.json").getAsJsonObject();
        for (String id : List.of("CRIMSON_HELMET", "HOT_TERROR_CHESTPLATE", "BURNING_AURORA_LEGGINGS", "FIERY_FERVOR_BOOTS", "INFERNAL_HOLLOW_HELMET")) {
            JsonArray costs = stars.getAsJsonObject(id).getAsJsonArray("costs");
            double[] want = {0, 0, 0, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000, 25_000_000, 50_000_000};
            for (int s = 0; s < costs.size(); s++) {
                double coins = 0;
                for (JsonElement c : costs.get(s).getAsJsonArray()) if (c.getAsJsonObject().has("coins")) coins += c.getAsJsonObject().get("coins").getAsDouble();
                assertEquals(want[s], coins, id + " star " + (s + 1));
            }
        }
    }
}
