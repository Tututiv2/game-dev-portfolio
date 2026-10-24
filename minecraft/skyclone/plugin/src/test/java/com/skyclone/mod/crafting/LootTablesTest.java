package com.skyclone.mod.crafting;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: How to Get reads the loot other modules publish as data (slayer, dungeons, kuudra, fishing). */
class LootTablesTest {

    private static final UnaryOperator<String> ID = s -> s;

    @Test
    void slayerLineNamesFirstTierBestChanceAndLevel() {
        JsonElement j = JsonParser.parseString("{\"types\":[{\"id\":\"zombie\",\"name\":\"Ghoul Horror\",\"loot\":["
                + "{\"item\":\"REVENANT_FLESH\",\"max\":[3,18,50,64,64],\"chance\":[100,100,100,100,100],\"minLevel\":0},"
                + "{\"item\":\"BEHEADED_HORROR\",\"max\":[0,0,1,1,1],\"chance\":[0,0,0.08,0.16,0.1608],\"minLevel\":5},"
                + "{\"item\":\"NEVER\",\"chance\":[0,0,0,0,0]}]}]}");
        LootTables.Index idx = new LootTables.Index();
        LootTables.slayer(j, ID, idx);
        List<LootTables.Line> flesh = idx.get("slayer", "REVENANT_FLESH");
        assertEquals(1, flesh.size());
        assertTrue(flesh.get(0).text().contains("Ghoul Horror"), flesh.get(0).text());
        assertTrue(flesh.get(0).text().contains("Tier I+"), flesh.get(0).text());
        assertTrue(flesh.get(0).text().contains("always"), flesh.get(0).text());
        String head = idx.get("slayer", "BEHEADED_HORROR").get(0).text();
        assertTrue(head.contains("Tier III+") && head.contains("0.16%") && head.contains("LVL 5"), head);
        assertTrue(idx.get("slayer", "NEVER").isEmpty(), "an item no tier can drop is not a source");
    }

    @Test
    void dungeonFloorsAreNamedAndOrdered() {
        JsonElement j = JsonParser.parseString("{\"floors\":{"
                + "\"M7\":{\"entries\":[{\"item\":\"NECRON_HANDLE\",\"minChest\":\"BEDROCK\",\"cost\":100000000}]},"
                + "\"F7\":{\"entries\":[{\"item\":\"NECRON_HANDLE\",\"minChest\":\"BEDROCK\",\"cost\":100000000},"
                + "{\"item\":\"NECRON_HANDLE\",\"minChest\":\"OBSIDIAN\",\"cost\":1}]}}}");
        LootTables.Index idx = new LootTables.Index();
        LootTables.dungeons(j, ID, idx);
        List<LootTables.Line> l = idx.get("dungeon", "NECRON_HANDLE");
        assertEquals(2, l.size(), "one line per floor, duplicates within a floor folded");
        assertTrue(l.get(0).text().contains("Floor VII") && !l.get(0).text().contains("Master"), "normal floor first: " + l);
        assertTrue(l.get(0).text().contains("Bedrock Chest+") && l.get(0).text().contains("100,000,000 coins"), l.get(0).text());
        assertTrue(l.get(1).text().contains("Master Mode Floor VII"), l.get(1).text());
        assertEquals("Entrance", LootTables.floorName("E"));
    }

    @Test
    void kuudraBuildsArmourIdsFromPrefixAndRares() {
        JsonElement j = JsonParser.parseString("[{\"tier\":\"basic\",\"armorPrefix\":\"\",\"armorSets\":[\"CRIMSON\"],"
                + "\"armorPieces\":[\"HELMET\"],\"weapons\":[\"HOLLOW_WAND\"],\"rares\":[{\"item\":\"ENRAGER\",\"chance\":0.002},"
                + "{\"item\":\"HEAT_CORE\",\"chance\":0}]},{\"tier\":\"hot\",\"armorPrefix\":\"HOT_\",\"armorSets\":[\"CRIMSON\"],"
                + "\"armorPieces\":[\"HELMET\"]}]");
        LootTables.Index idx = new LootTables.Index();
        LootTables.kuudra(j, "Magma Leviathan", ID, idx);
        assertFalse(idx.get("kuudra", "CRIMSON_HELMET").isEmpty());
        assertTrue(idx.get("kuudra", "HOT_CRIMSON_HELMET").get(0).text().contains("Hot tier"));
        assertTrue(idx.get("kuudra", "ENRAGER").get(0).text().contains("0.2%"));
        assertTrue(idx.get("kuudra", "HEAT_CORE").isEmpty(), "a 0% rare is not a source");
        assertFalse(idx.get("kuudra", "HOLLOW_WAND").get(0).text().contains("Kuudra"), "the boss is shown by OUR name");
    }

    @Test
    void fishingSharesAreOfThePool() {
        JsonElement j = JsonParser.parseString("{\"fish\":[{\"item\":\"RAW_FISH\",\"weight\":75},{\"item\":\"RAW_FISH:1\",\"weight\":25}],"
                + "\"treasure\":{\"weights\":{\"good\":85,\"great\":15},\"water\":{\"good\":[{\"item\":\"COINS\",\"weight\":5},"
                + "{\"item\":\"SPONGE\",\"min\":1,\"max\":2,\"weight\":5}]}}}");
        LootTables.Index idx = new LootTables.Index();
        LootTables.fishing(j, ID, idx);
        assertTrue(idx.get("fishing", "RAW_FISH").get(0).text().contains("75%"));
        String sponge = idx.get("fishing", "SPONGE").get(0).text();
        assertTrue(sponge.contains("Good Treasure") && sponge.contains("1-2x") && sponge.contains("43%"), sponge);   // 0.5 * 0.85
        assertTrue(idx.get("fishing", "COINS").isEmpty(), "coins are not an item");
    }

    @Test
    void brokenOrMissingShapesFindNothing() {
        LootTables.Index idx = new LootTables.Index();
        LootTables.slayer(JsonParser.parseString("[1,2]"), ID, idx);
        LootTables.dungeons(JsonParser.parseString("{\"floors\":[]}"), ID, idx);
        LootTables.kuudra(JsonParser.parseString("{}"), "X", ID, idx);
        LootTables.fishing(JsonParser.parseString("\"nope\""), ID, idx);
        assertTrue(idx.byKind.isEmpty());
        assertNull(CraftMath.analyzerText("garden:5"));
        assertEquals("&aCrop Analyzer Milestone III", CraftMath.analyzerText("garden:analyzer:3"));
    }

    /** The shipped data, when the test can see it (gradle runs in plugin/, the javac script in src/test/java). */
    @Test
    void shippedTablesHaveTheHeadlineDrops() throws Exception {
        Path base = null;
        for (String c : new String[]{"src/main/resources/data", "../../main/resources/data", "plugin/src/main/resources/data"}) {
            if (Files.isDirectory(Path.of(c, "slayer"))) { base = Path.of(c); break; }
        }
        if (base == null) return;
        LootTables.Index idx = new LootTables.Index();
        LootTables.slayer(read(base.resolve("slayer/slayers.json")), ID, idx);
        LootTables.dungeons(read(base.resolve("dungeons/loot.json")), ID, idx);
        LootTables.kuudra(read(base.resolve("kuudra/loot.json")), "Magma Leviathan", ID, idx);
        LootTables.fishing(read(base.resolve("fishing/fishing.json")), ID, idx);
        assertFalse(idx.get("slayer", "REVENANT_FLESH").isEmpty(), "Revenant Flesh comes from the zombie slayer");
        assertTrue(idx.items("dungeon") > 50, "dungeon chest items: " + idx.items("dungeon"));
        assertFalse(idx.get("kuudra", "ENRAGER").isEmpty());
        assertFalse(idx.get("fishing", "SPONGE").isEmpty());
    }

    private static JsonElement read(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
    }
}
