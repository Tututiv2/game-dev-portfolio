package com.skyclone.mod.farming;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: what other modules' accessories, reforges and pets do inside farming, against their shipped data. */
class FarmingPerksTest {

    private static JsonElement res(String path) {
        InputStream in = FarmingPerksTest.class.getResourceAsStream(path);
        assertNotNull(in, "missing resource " + path);
        return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** The accessories module's shipped defs as PerkMath sees them, by id. */
    private static Map<String, PerkMath.Accessory> accessories() {
        Map<String, PerkMath.Accessory> out = new HashMap<>();
        for (JsonElement e : res("/data/accessories/items.json").getAsJsonArray()) {
            JsonObject it = e.getAsJsonObject();
            JsonObject ex = it.has("extra") ? it.getAsJsonObject("extra") : new JsonObject();
            out.put(it.get("id").getAsString(), new PerkMath.Accessory(it.get("id").getAsString(),
                    ex.has("family") ? ex.get("family").getAsString() : null,
                    ex.has("familyRank") ? ex.get("familyRank").getAsInt() : 0,
                    ex.has("otherStats") ? ex.getAsJsonObject("otherStats") : null));
        }
        return out;
    }

    @Test
    void cropFortuneReadsEveryCropFromTheAccessoryData() {
        Map<String, PerkMath.Accessory> acc = accessories();
        PerkMath.Accessory fermento = acc.get("FERMENTO_ARTIFACT");
        assertNotNull(fermento);
        // research 06: Fermento Artifact "+30 to every crop fortune" - melon is spelt MELON_SLICE, cocoa COCOA_BEANS
        for (Crop c : List.of(Crop.WHEAT, Crop.CARROT, Crop.POTATO, Crop.PUMPKIN, Crop.MELON, Crop.SUGAR_CANE, Crop.CACTUS,
                Crop.COCOA, Crop.MUSHROOM, Crop.NETHER_WART))
            assertEquals(30.0, PerkMath.cropFortune(fermento.otherStats(), c), 1e-9, c.name());
        assertEquals(0.0, PerkMath.cropFortune(fermento.otherStats(), Crop.SUNFLOWER), 1e-9);
        assertEquals(10.0, PerkMath.cropFortune(acc.get("CROPIE_TALISMAN").otherStats(), Crop.POTATO), 1e-9);
        assertEquals(0.0, PerkMath.cropFortune(acc.get("CROPIE_TALISMAN").otherStats(), Crop.MELON), 1e-9);
        assertEquals(20.0, PerkMath.cropFortune(acc.get("SQUASH_RING").otherStats(), Crop.COCOA), 1e-9);
        // an item carrying two spellings is paid once, the specific one
        JsonObject both = new JsonObject();
        both.addProperty("MELON_SLICE_FORTUNE", 5);
        both.addProperty("MELON_FORTUNE", 7);
        assertEquals(5.0, PerkMath.cropFortune(both, Crop.MELON), 1e-9);
    }

    @Test
    void onlyTheHighestOfAFamilyCounts() {
        Map<String, PerkMath.Accessory> acc = accessories();
        List<PerkMath.Accessory> owned = new ArrayList<>(List.of(acc.get("CROPIE_TALISMAN"), acc.get("SQUASH_RING"), acc.get("FERMENTO_ARTIFACT")));
        List<PerkMath.Accessory> counted = PerkMath.counted(owned);
        assertEquals(1, counted.size());
        assertEquals("FERMENTO_ARTIFACT", counted.get(0).id());
        // Helianthus Relic is the top of the same line and has no crop stats: with it, the crop fortune goes (as in the reference)
        owned.add(acc.get("HELIANTHUS_RELIC"));
        counted = PerkMath.counted(owned);
        assertEquals(1, counted.size());
        assertEquals("HELIANTHUS_RELIC", counted.get(0).id());
        // an accessory with no family is its own family
        counted = PerkMath.counted(List.of(acc.get("CROPIE_TALISMAN"), new PerkMath.Accessory("X", null, 0, null)));
        assertEquals(2, counted.size());
        assertTrue(PerkMath.farmingRelevant(acc.get("FRESHLY_BAKED_TALISMAN").otherStats()), "Overbloom accessory");
    }

    @Test
    void petExtraStatsScaleWithLevel() {
        JsonObject pig = null;
        for (JsonElement e : res("/data/pets/pets.json").getAsJsonArray())
            if (e.getAsJsonObject().get("id").getAsString().equals("PIG")) pig = e.getAsJsonObject();
        assertNotNull(pig);
        JsonObject leg = pig.getAsJsonObject("tiers").getAsJsonObject("LEGENDARY");
        double at1 = PerkMath.number(leg.getAsJsonObject("x1"), "POTATO_FORTUNE"), at100 = PerkMath.number(leg.getAsJsonObject("x100"), "POTATO_FORTUNE");
        assertEquals(20.0, PerkMath.petLerp(at1, at100, 100), 1e-9);
        assertEquals(at1, PerkMath.petLerp(at1, at100, 1), 1e-9);
        assertEquals(10.0, PerkMath.petLerp(0.2, 20, 50), 1e-9);   // 0.2 + 19.8 x 49/99
        // the level-200 dragons: 101..200 map onto 1..100, below that the level-1 numbers
        assertEquals(1, PerkMath.petStatLevel("101:200:1", 80));
        assertEquals(100, PerkMath.petStatLevel("101:200:1", 200));
        assertEquals(37, PerkMath.petStatLevel(null, 37));
    }

    @Test
    void overbloomComesFromTheReforgeTable() {
        JsonArray rows = res("/data/reforges/reforges.json").getAsJsonArray();
        Map<String, JsonObject> other = new HashMap<>();
        for (JsonElement e : rows) {
            JsonObject r = e.getAsJsonObject();
            if (r.has("otherStats")) other.put(r.get("id").getAsString(), r.getAsJsonObject("otherStats"));
        }
        // research 01: Overpriced L Overbloom +7, Thorny M +1.5
        assertEquals(7.0, PerkMath.number(other.get("overpriced").getAsJsonObject("LEGENDARY"), "OVERBLOOM"), 1e-9);
        assertEquals(1.5, PerkMath.number(other.get("thorny").getAsJsonObject("MYTHIC"), "overbloom"), 1e-9);
        assertEquals(1.0, PerkMath.overbloomMultiplier(0, 1.0), 1e-9);
        assertEquals(1.07, PerkMath.overbloomMultiplier(7, 1.0), 1e-9);
        assertEquals(1.0, PerkMath.overbloomMultiplier(-5, 1.0), 1e-9);
    }

    @Test
    void trapperPeltBonusesAndTheCape() {
        assertEquals(5, PerkMath.pelts(5, 0, 1.0));
        assertEquals(7, PerkMath.pelts(5, 2, 1.0));          // plushie + belt
        assertEquals(8, PerkMath.pelts(5, 2, 1.1));          // 7.7 rounds up
        assertEquals(11, PerkMath.pelts(10, 0, 1.1));
        assertEquals(10, PerkMath.pelts(10, 0, 0.5));        // a crest never lowers the pay
        assertEquals("gold", PerkMath.bonusMedal(1, 1, 0));  // Diamond = gold + silver: the cape pays a gold
        assertEquals("silver", PerkMath.bonusMedal(0, 1, 0));
        assertEquals("bronze", PerkMath.bonusMedal(0, 0, 1));
        assertNull(PerkMath.bonusMedal(0, 0, 0));
    }

    @Test
    void everyCropToolCarriesItsResearchGardenLevel() {
        Map<String, Integer> research = new HashMap<>();
        // a few anchors from research/data/items.json (GARDEN_LEVEL requirements)
        research.put("THEORETICAL_HOE_WHEAT_1", 1);
        research.put("THEORETICAL_HOE_WARTS_3", 10);
        research.put("THEORETICAL_HOE_WILD_ROSE_2", 12);
        research.put("FUNGI_CUTTER", 9);
        research.put("PUMPKIN_DICER_3", 4);
        int tools = 0;
        for (JsonElement e : res("/data/farming/items.json").getAsJsonArray()) {
            JsonObject it = e.getAsJsonObject();
            if (!it.has("extra") || !it.getAsJsonObject("extra").has("farmTool")) continue;
            ToolSpec s = ToolSpec.parse(it.getAsJsonObject("extra").getAsJsonObject("farmTool"));
            if (s.mk == 0) continue;
            tools++;
            assertTrue(s.gardenLevel >= 1 && s.gardenLevel <= 15, it.get("id") + " garden level " + s.gardenLevel);
            Integer want = research.get(it.get("id").getAsString());
            if (want != null) assertEquals(want.intValue(), s.gardenLevel, it.get("id").getAsString());
            // never written as a requirement in the data: an unknown prefix would lock the tool without the garden
            for (JsonElement r : it.getAsJsonArray("requirements")) assertTrue(!r.getAsString().startsWith("garden"), it.get("id") + " " + r);
        }
        assertEquals(36, tools);
    }
}
