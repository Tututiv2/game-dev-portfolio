package com.skyclone.mod.dungeons;

import com.skyclone.gen.MemoryCanvas;
import com.skyclone.mod.dungeons.boss.Devices;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.gen.Geo;
import com.skyclone.mod.dungeons.gen.arena.ArenaPreviewGenerator;
import com.skyclone.mod.dungeons.loot.LootTables;
import com.skyclone.mod.dungeons.model.FloorDef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Part B: reward-chest rules (research 04 sec 3.14), every arena's points, and the F7 devices are always solvable. */
class BossLootTest {

    @BeforeAll
    static void load() throws Exception {
        try (InputStreamReader r = new InputStreamReader(BossLootTest.class.getClassLoader().getResourceAsStream("data/dungeons/loot.json"), StandardCharsets.UTF_8)) {
            LootTables.load(r);
        }
    }

    @Test
    void chestUnlockRules() {
        LootTables.FloorLoot f7 = LootTables.floor("F7");
        assertNotNull(f7);
        assertEquals(List.of(LootTables.Chest.GOLD, LootTables.Chest.DIAMOND), LootTables.unlocked(f7, false, 150, 1));
        assertTrue(LootTables.unlocked(f7, false, 150, 2).contains(LootTables.Chest.WOOD));
        assertTrue(LootTables.unlocked(f7, false, 230, 2).contains(LootTables.Chest.EMERALD));
        assertFalse(LootTables.unlocked(f7, false, 269, 2).contains(LootTables.Chest.OBSIDIAN));
        assertTrue(LootTables.unlocked(f7, false, 300, 2).contains(LootTables.Chest.BEDROCK));
        assertEquals(6, LootTables.unlocked(LootTables.floor("M7"), true, 0, 0).size());
        // F1 has every chest but Bedrock (wiki floor page prices Diamond 50k / Emerald 100k / Obsidian 250k on F1;
        // research 04's "F2+/F3+/F4+" were (?) guesses). Bedrock is F5+.
        LootTables.FloorLoot f1 = LootTables.floor("F1");
        assertEquals(List.of(LootTables.Chest.WOOD, LootTables.Chest.GOLD, LootTables.Chest.DIAMOND, LootTables.Chest.EMERALD,
                LootTables.Chest.OBSIDIAN), LootTables.unlocked(f1, false, 317, 9));
        assertFalse(LootTables.unlocked(f1, false, 269, 9).contains(LootTables.Chest.OBSIDIAN), "Obsidian still needs S");
        assertEquals(250_000, f1.chest(LootTables.Chest.OBSIDIAN).cost);
        assertEquals(50_000, f1.chest(LootTables.Chest.DIAMOND).cost);
        assertEquals(2, LootTables.unlocked(LootTables.floor("E"), false, 317, 9).size(), "the Entrance keeps Wood + Gold");
        assertEquals(6, LootTables.unlocked(LootTables.floor("F5"), false, 317, 9).size());
    }

    @Test
    void researchCosts() {
        LootTables.FloorLoot f7 = LootTables.floor("F7");
        assertEquals(2_000_000, f7.chest(LootTables.Chest.BEDROCK).cost);
        assertEquals(1_000_000, f7.chest(LootTables.Chest.OBSIDIAN).cost);
        assertEquals(100_000, f7.chest(LootTables.Chest.GOLD).cost);
        assertEquals(25_000, LootTables.floor("F1").chest(LootTables.Chest.GOLD).cost);
        assertEquals(98_000_000, f7.entry("NECRON_HANDLE").cost);
        assertEquals(10, f7.chest(LootTables.Chest.WOOD).undead);
        assertEquals(70, f7.chest(LootTables.Chest.BEDROCK).undead);
        // Necron's Handle + Bedrock = 100M (research's worked example)
        LootTables.Rolled r = LootTables.roll(f7, LootTables.Chest.BEDROCK, new Random(3), e -> true, 0, 0, "NECRON_HANDLE", 1);
        assertTrue(r.items.contains("NECRON_HANDLE:1"));
        assertTrue(r.cost >= 100_000_000);
        assertEquals("NECRON_HANDLE", r.rngItem);
    }

    @Test
    void rollsAreDeterministicAndRespectChestTier() {
        for (String fid : List.of("E", "F1", "F3", "F5", "F7", "M3", "M7")) {
            LootTables.FloorLoot f = LootTables.floor(fid);
            assertNotNull(f, fid);
            for (LootTables.Chest c : LootTables.Chest.values()) {
                if (f.chest(c) == null) continue;
                LootTables.Rolled a = LootTables.roll(f, c, new Random(42), e -> true, 5, 0.02, null, 1);
                LootTables.Rolled b = LootTables.roll(f, c, new Random(42), e -> true, 5, 0.02, null, 1);
                assertEquals(a.items, b.items);
                assertFalse(a.items.isEmpty(), fid + " " + c);
                for (String cell : a.items) assertTrue(f.entry(LootTables.idOf(cell)).min().ordinal() <= c.ordinal(), cell + " too good for " + c);
            }
        }
        // a Wood chest can never hold a Bedrock-only prize
        LootTables.FloorLoot f7 = LootTables.floor("F7");
        Random r = new Random(1);
        for (int i = 0; i < 500; i++)
            assertFalse(LootTables.roll(f7, LootTables.Chest.WOOD, r, e -> true, 0, 0, "NECRON_HANDLE", 1).items.contains("NECRON_HANDLE:1"));
    }

    @Test
    void meter() {
        assertEquals(0, LootTables.meterGain(269, 270, 1));
        assertEquals(300, LootTables.meterGain(300, 270, 1));
    }

    @Test
    void everyArenaHasItsPoints() {
        for (int n = 1; n <= 7; n++) {
            for (boolean master : new boolean[]{false, true}) {
                FloorDef f = new FloorDef();
                f.number = n;
                f.master = master;
                f.id = (master ? "M" : "F") + n;
                BossArena a = ArenaPreviewGenerator.ARENAS[n - 1];
                assertTrue(a.sizeX() <= Geo.ARENA_MAX_X && a.sizeZ() <= Geo.ARENA_MAX_Z, a.id());
                BossArena.Points pts = new BossArena.Points();
                MemoryCanvas c = new MemoryCanvas();
                a.build(c, 0, Geo.FLOOR_Y, 0, 9, f, pts);
                for (String need : PartB.NEEDS.get("F" + n)) assertNotNull(pts.get(need), a.id() + " lacks " + need);
                for (Map.Entry<String, int[]> e : pts.map().entrySet()) {
                    int[] p = e.getValue();
                    assertTrue(p[0] >= 0 && p[0] < a.sizeX() && p[2] >= 0 && p[2] < a.sizeZ(), a.id() + " point outside: " + e.getKey());
                }
                // where players arrive must be air with a floor under it
                int[] s = pts.get("spawn");
                assertTrue(c.isAir(s[0], s[1], s[2]) && c.isAir(s[0], s[1] + 1, s[2]) && !c.isAir(s[0], s[1] - 1, s[2]), a.id() + " spawn is not standable");
                int[] ch = pts.get("chests");
                assertTrue(c.isAir(ch[0], ch[1], ch[2]) && !c.isAir(ch[0], ch[1] - 1, ch[2]), a.id() + " chest spot is not open floor");
            }
        }
    }

    @Test
    void devicesAreAlwaysSolvable() {
        Random r = new Random(11);
        for (int i = 0; i < 1000; i++) {
            int[] sets = Devices.lampSets(r);
            int start = Devices.startState(sets, r);
            assertTrue(start != 0x1FF);
            boolean ok = false;
            for (int combo = 0; combo < 32; combo++) {
                int st = start;
                for (int k = 0; k < 5; k++) if ((combo >> k & 1) == 1) st ^= sets[k];
                if (st == 0x1FF) ok = true;
            }
            assertTrue(ok, "lights board " + i);
            List<int[]> p = Devices.path(r);
            assertEquals(0, p.get(0)[0]);
            assertEquals(4, p.get(p.size() - 1)[0]);
            for (int k = 1; k < p.size(); k++) {
                int[] a = p.get(k - 1), b = p.get(k);
                int dx = b[0] - a[0], dy = b[1] - a[1];
                int want = dx == 1 ? 0 : dy == 1 ? 1 : 2;
                assertEquals(want, a[2], "path step " + k + " of board " + i);
                assertEquals(1, Math.abs(dx) + Math.abs(dy));
            }
        }
    }
}
