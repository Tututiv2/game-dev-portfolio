package com.skyclone.mod.dungeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.mod.dungeons.loot.LootTables;
import com.skyclone.mod.dungeons.loot.LootTables.Chest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/AUDIT_REAL_GAME_2026-10-06.md rows C9 (Catacombs base XP), C10 (F6 chest prices, Recombobulator chest), C11 (Wood
 * essence) and C17 (Giant's Sword first-roll odds), against the wiki floor pages (hypixelskyblock.minecraft.wiki).
 */
class RealGameAuditTest {

    static final Map<String, JsonObject> FLOORS = new HashMap<>();

    @BeforeAll
    static void load() throws Exception {
        try (InputStreamReader r = new InputStreamReader(RealGameAuditTest.class.getClassLoader().getResourceAsStream("data/dungeons/loot.json"), StandardCharsets.UTF_8)) {
            LootTables.load(r);
        }
        try (InputStreamReader r = new InputStreamReader(RealGameAuditTest.class.getClassLoader().getResourceAsStream("data/dungeons/floors.json"), StandardCharsets.UTF_8)) {
            for (JsonElement e : JsonParser.parseReader(r).getAsJsonArray()) FLOORS.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        }
    }

    @Test
    void c9BaseXpIsTheWikis() {
        Map<String, Double> wiki = Map.of("E", 50.0, "F1", 80.0, "F2", 160.0, "F3", 400.0, "F4", 2400.0, "F5", 2400.0,
                "F6", 4880.0, "F7", 30000.0);
        for (Map.Entry<String, Double> e : wiki.entrySet())
            assertEquals(e.getValue(), FLOORS.get(e.getKey()).get("baseXp").getAsDouble(), 0.001, e.getKey());
        assertEquals(10000, FLOORS.get("M1").get("baseXp").getAsDouble());
        assertEquals(15000, FLOORS.get("M2").get("baseXp").getAsDouble());
        assertEquals(60000, FLOORS.get("M3").get("baseXp").getAsDouble());
        assertEquals(70000, FLOORS.get("M5").get("baseXp").getAsDouble());
    }

    @Test
    void c10F6ChestPrices() {
        LootTables.FloorLoot f6 = LootTables.floor("F6");
        assertEquals(1_000_000, f6.chest(Chest.OBSIDIAN).cost);
        assertEquals(2_000_000, f6.chest(Chest.BEDROCK).cost);
        assertEquals(100_000, f6.chest(Chest.GOLD).cost);
        // Giant's Sword: Bedrock only, 2M + 23M = 25M
        LootTables.Entry gs = f6.entry("GIANTS_SWORD");
        for (Chest c : Chest.values()) assertEquals(c == Chest.BEDROCK, gs.inChest(c), "Giant's Sword in " + c);
        assertEquals(25_000_000, f6.chest(Chest.BEDROCK).cost + gs.costIn(Chest.BEDROCK));
        // Recombobulator: never Gold; Obsidian 1M + 5M = 6M, Bedrock 2M + 4M
        LootTables.Entry rec = f6.entry("RECOMBOBULATOR_3000");
        assertFalse(rec.inChest(Chest.GOLD));
        assertFalse(rec.inChest(Chest.EMERALD));
        assertEquals(Chest.OBSIDIAN, rec.min());
        assertEquals(6_000_000, f6.chest(Chest.OBSIDIAN).cost + rec.costIn(Chest.OBSIDIAN));
        assertEquals(6_000_000, f6.chest(Chest.BEDROCK).cost + rec.costIn(Chest.BEDROCK));
        // a rolled chest charges the per-chest price
        LootTables.Rolled r = LootTables.roll(f6, Chest.OBSIDIAN, new Random(1), e -> true, 0, 0, "RECOMBOBULATOR_3000", 1);
        assertEquals(6_000_000, r.cost);
        LootTables.Rolled b = LootTables.roll(f6, Chest.BEDROCK, new Random(1), e -> true, 0, 0, "GIANTS_SWORD", 1);
        assertEquals(25_000_000, b.cost);
        assertEquals(1, b.items.size(), "a per-chest floor rolls one item per chest");
    }

    @Test
    void c11WoodEssence() {
        LootTables.FloorLoot f6 = LootTables.floor("F6");
        assertEquals(10, f6.chest(Chest.WOOD).undead);
        assertEquals(12, f6.chest(Chest.WOOD).witherMin);
        assertEquals(12, f6.chest(Chest.WOOD).witherMax);
        assertEquals(15, LootTables.floor("F7").chest(Chest.WOOD).witherMin, "F7 Wood is 15 Wither, not 1-15");
        assertEquals(10, FLOORS.get("F6").get("woodUndead").getAsInt());
        assertEquals(12, FLOORS.get("F6").get("woodWitherMax").getAsInt());
        LootTables.Rolled w = LootTables.roll(f6, Chest.WOOD, new Random(9), e -> true, 0, 0, null, 1);
        assertEquals(10, w.undead);
        assertEquals(12, w.wither);
    }

    @Test
    void c17GiantsSwordFirstRollOdds() {
        LootTables.FloorLoot f6 = LootTables.floor("F6");
        // gated boss heads stay out (a fresh profile has no Sadan collection), as at roll time
        double p = LootTables.firstRollChance(f6, Chest.BEDROCK, "GIANTS_SWORD", e -> e.gate == null);
        assertEquals(1 / 533.4, p, 1 / 533.4 * 0.02, "wiki: 0.1875% = 1 in 533 (Summoning Ring is not registered, so +0.4%)");
        double rec = LootTables.firstRollChance(f6, Chest.OBSIDIAN, "RECOMBOBULATOR_3000", e -> e.gate == null);
        assertEquals(0.013393, rec, 0.013393 * 0.02);
        // and the dice agree with the arithmetic
        Random rnd = new Random(42);
        int hits = 0, n = 400_000;
        for (int i = 0; i < n; i++)
            if (LootTables.roll(f6, Chest.BEDROCK, rnd, e -> e.gate == null, 0, 0, null, 1).items.contains("GIANTS_SWORD:1")) hits++;
        double seen = hits / (double) n;
        assertTrue(Math.abs(seen - p) < p * 0.15, "rolled " + seen + " vs " + p);
    }
}
