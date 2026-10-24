package com.skyclone.mod.armor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure checks: the set formulas give research 06's numbers, and the generated data hangs together. */
class ArmorPureTest {

    static Path res(String file) {
        Path p = Path.of("src/main/resources/data/armor", file);
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data/armor", file);
        return p;
    }

    static JsonArray json(String file) throws Exception {
        try (Reader r = new InputStreamReader(Files.newInputStream(res(file)), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonArray();
        }
    }

    // ---- formulas --------------------------------------------------------------------------

    @Test void protectiveBlood() {
        assertEquals(300, SetMath.protectorBonus(600, 500, 1000, 1), 1e-9);   // 50% missing -> +50% of 600
        assertEquals(0, SetMath.protectorBonus(600, 1000, 1000, 1), 1e-9);
        assertEquals(600, SetMath.protectorBonus(600, 0, 1000, 1), 1e-9);
    }

    @Test void emeraldTank() {
        assertEquals(100, SetMath.emeraldBonus(300_000, 3000, 350));
        assertEquals(350, SetMath.emeraldBonus(5_000_000, 3000, 350));
        assertEquals(0, SetMath.emeraldBonus(2_999, 3000, 350));
    }

    @Test void blazingAura() {
        assertEquals(300, SetMath.blazeAuraDamage(10_000, 3, 0, 500), 1e-9);
        assertEquals(500, SetMath.blazeAuraDamage(1e9, 3, 0, 500), 1e-9);
        assertEquals(450, SetMath.blazeAuraDamage(5_000, 3, 300, 500), 1e-9);
        assertEquals(500, SetMath.blazeAuraCap(4_999, 500, 100), 1e-9);
        assertEquals(600, SetMath.blazeAuraCap(5_000, 500, 100), 1e-9);
    }

    @Test void tieredAndPieces() {
        assertEquals(0, SetMath.tieredThirds(1, 45), 1e-9);
        assertEquals(15, SetMath.tieredThirds(2, 45), 1e-9);
        assertEquals(30, SetMath.tieredThirds(3, 45), 1e-9);
        assertEquals(45, SetMath.tieredThirds(4, 45), 1e-9);
        // research 08: full-set Farming Fortune 135 / 170 / 205 / 225
        assertEquals(135, 90 + SetMath.tieredThirds(4, BonusStats.farmingFortuneFull(SetIds.CROPIE)), 1e-9);
        assertEquals(170, 110 + SetMath.tieredThirds(4, BonusStats.farmingFortuneFull(SetIds.SQUASH)), 1e-9);
        assertEquals(205, 130 + SetMath.tieredThirds(4, BonusStats.farmingFortuneFull(SetIds.FERMENTO)), 1e-9);
        assertEquals(225, 150 + SetMath.tieredThirds(4, BonusStats.farmingFortuneFull(SetIds.HELIANTHUS)), 1e-9);
        assertEquals(12, SetMath.perPiece(4, 3), 1e-9);
    }

    @Test void misc() {
        assertEquals(2.0, SetMath.crystalFactor(15), 1e-9);
        assertEquals(14 / 15.0, SetMath.crystalFactor(7), 1e-9);
        assertEquals(10, SetMath.goblinMiningSpeed(150), 1e-9);
        assertEquals(0, SetMath.goblinMiningSpeed(-20), 1e-9);
        assertEquals(12, SetMath.vindicateSpeed(600), 1e-9);
        assertEquals(0.075, SetMath.tuxedoHealthFactor(1000, 75), 1e-9);
        assertEquals(1.0, SetMath.tuxedoHealthFactor(60, 75), 1e-9);
        assertEquals(0.5, SetMath.berserkFactor(4), 1e-9);
        assertEquals(200, SetMath.perKills(5_000, 10, 200), 1e-9);
        assertEquals(500, SetMath.perKills(10_000, 5, 500), 1e-9);
        assertEquals(1, SetMath.staticCharges(45, 30, 2));
        assertEquals(2, SetMath.staticCharges(600, 30, 2));
        assertEquals(400, SetMath.capHit(10_000, 1000, 0.4), 1e-9);
        assertEquals(0.0003, SetMath.rareCropChance(0.03, 4, 1), 1e-12);
        assertEquals(0.00015, SetMath.rareCropChance(0.03, 2, 1), 1e-12);
        // audit 2026-10-06 G5/G6 checked against NEU lore: Squash 0.01% (Cropie set), Fermento 0.005% (Squash set)
        assertEquals(0.0001, SetMath.rareCropChance(SetMath.SQUASH_PCT, 4, 1), 1e-12);
        assertEquals(0.00005, SetMath.rareCropChance(SetMath.FERMENTO_PCT, 4, 1), 1e-12);
        assertEquals(5, SetMath.stacks(9, 5));
    }

    // ---- data ------------------------------------------------------------------------------

    @Test void setsParseAndCoverEveryPiece() throws Exception {
        ArmorSets sets;
        try (Reader r = new InputStreamReader(Files.newInputStream(res("sets.json")), StandardCharsets.UTF_8)) {
            sets = ArmorSets.parse(r);
        }
        JsonArray items = json("items.json");
        assertTrue(items.size() >= 500, "items: " + items.size());
        Set<String> ids = new HashSet<>();
        for (JsonElement e : items) ids.add(e.getAsJsonObject().get("id").getAsString());
        int multi = 0;
        for (ArmorSets.SetInfo s : sets.all()) {
            if (s.isSet) multi++;
            for (String p : s.pieces) assertTrue(ids.contains(p), "set " + s.id + " names unknown piece " + p);
        }
        assertEquals(ids.size(), sets.pieceCount(), "every item belongs to exactly one set entry");
        assertTrue(multi >= 100, "multi-piece sets: " + multi);
        for (String id : SetIds.ALL_SETS) assertTrue(sets.has(id), "bonus code names unknown set " + id);
        for (String id : SetIds.ALL_ITEMS) assertTrue(ids.contains(id), "bonus code names unknown item " + id);
    }

    @Test void itemsAreSane() throws Exception {
        Set<String> slots = Set.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");
        Map<String, Map<String, Double>> totals = new HashMap<>();
        for (JsonElement e : json("items.json")) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            assertTrue(slots.contains(o.get("category").getAsString()), id);
            assertFalse(o.get("material").getAsString().startsWith("GOLD_"), id + " legacy material");
            assertNotEquals("SKULL_ITEM", o.get("material").getAsString(), id);
            assertTrue(o.has("name") && !o.get("name").getAsString().isBlank(), id);
            // excluded owners never leak in
            assertFalse(id.startsWith("STARRED_") || id.startsWith("REVENANT_") || id.startsWith("TARANTULA_")
                    || id.startsWith("POWER_WITHER") || id.startsWith("INFERNAL_") || id.startsWith("CRIMSON_HELMET"), id + " belongs to another module");
            if (o.has("setId") && o.has("stats")) {
                Map<String, Double> t = totals.computeIfAbsent(o.get("setId").getAsString(), k -> new HashMap<>());
                for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("stats").entrySet()) t.merge(s.getKey(), s.getValue().getAsDouble(), Double::sum);
            }
        }
        // research 06 §3.2
        assertEquals(600, totals.get("superior_dragon").get("DEFENSE"), 1e-9);
        assertEquals(450, totals.get("superior_dragon").get("HEALTH"), 1e-9);
        assertEquals(12, totals.get("superior_dragon").get("SPEED"), 1e-9);
        assertEquals(330, totals.get("hardened_diamond").get("DEFENSE"), 1e-9);
        assertEquals(80, totals.get("lapis_armor").get("MINING_SPEED"), 1e-9);
        assertEquals(750, totals.get("thunder").get("DEFENSE"), 1e-9);
    }

    @Test void recipesAreWellFormed() throws Exception {
        JsonArray rs = json("recipes.json");
        assertTrue(rs.size() >= 250, "recipes: " + rs.size());
        Set<String> seen = new HashSet<>();
        for (JsonElement e : rs) {
            JsonObject r = e.getAsJsonObject();
            String id = r.get("id").getAsString();
            assertTrue(seen.add(id), "duplicate recipe id " + id);
            assertTrue(id.startsWith("armor:"), id);
            if (r.has("grid")) assertEquals(9, r.getAsJsonArray("grid").size(), id);
            if (r.has("unlock")) assertTrue(r.get("unlock").getAsString().matches("collection:.+:\\d+"), id);
        }
    }

    // ---- right-click equip (round 1: leggings/boots stayed in the hotbar) ---------------------

    @Test void everyArmourCategoryHasItsOwnSlot() throws Exception {
        assertEquals(0, ArmorUtil.armorIndex(com.skyclone.api.item.ItemCategory.BOOTS));
        assertEquals(1, ArmorUtil.armorIndex(com.skyclone.api.item.ItemCategory.LEGGINGS));
        assertEquals(2, ArmorUtil.armorIndex(com.skyclone.api.item.ItemCategory.CHESTPLATE));
        assertEquals(3, ArmorUtil.armorIndex(com.skyclone.api.item.ItemCategory.HELMET));
        assertEquals(-1, ArmorUtil.armorIndex(null));
        for (com.skyclone.api.item.ItemCategory c : com.skyclone.api.item.ItemCategory.values())
            assertEquals(c.isArmor(), ArmorUtil.armorIndex(c) >= 0, c.name());
        // every generated piece is right-click wearable: its category maps to a real slot
        for (JsonElement el : json("items.json")) {
            JsonObject o = el.getAsJsonObject();
            String cat = o.get("category").getAsString();
            assertTrue(ArmorUtil.armorIndex(com.skyclone.api.item.ItemCategory.valueOf(cat)) >= 0, o.get("id").getAsString() + " " + cat);
        }
    }

    @Test void equipDedupeSwallowsOnlyTheSameClick() {
        assertFalse(SetMath.sameEquipClick(null, 100, 1));                       // first click ever
        assertTrue(SetMath.sameEquipClick(new int[]{100, 1}, 100, 1));           // block + air packet, same tick
        assertTrue(SetMath.sameEquipClick(new int[]{100, 1}, 101, 1));           // packets straddling a tick
        assertFalse(SetMath.sameEquipClick(new int[]{100, 2}, 100, 1));          // chestplate then leggings: NOT swallowed
        assertFalse(SetMath.sameEquipClick(new int[]{100, 1}, 100, 0));          // leggings then boots, same tick
        assertFalse(SetMath.sameEquipClick(new int[]{100, 1}, 102, 1));          // a real second click
    }
}
