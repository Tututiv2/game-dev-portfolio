package com.skyclone.mod.crimson;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.IslandRun;
import com.skyclone.mod.crimson.gen.CrimsonIsleGenerator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wave 5: mini-boss shares and faction spoils, the attribute maths, Synthesizers, and the v4 island's minerals. */
class CrimsonWave5Test {

    @Test
    void shareThreshold() {
        assertTrue(CrimsonRules.sharesKill(0, 1_000_000, 1, true), "the killer always shares");
        assertFalse(CrimsonRules.sharesKill(9_999, 1_000_000, 1, false), "just under 1%");
        assertTrue(CrimsonRules.sharesKill(10_000, 1_000_000, 1, false), "exactly 1%");
        assertFalse(CrimsonRules.sharesKill(Double.NaN, 1_000_000, 1, false));
        assertFalse(CrimsonRules.sharesKill(-5, 1_000_000, 1, false));
        assertTrue(CrimsonRules.sharesKill(1, 1_000_000, 0, false), "0% threshold: any real hit shares");
        assertFalse(CrimsonRules.sharesKill(0, 1_000_000, 0, false), "but a bystander never does");
    }

    @Test
    void rankingIsByDamage() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("a", 10.0); m.put("b", 300.0); m.put("c", 50.0);
        List<Map.Entry<String, Double>> r = CrimsonRules.ranking(m);
        assertEquals(List.of("b", "c", "a"), r.stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void rivalChampionTheRightWayRound() {
        CrimsonTables t = CrimsonRulesTest.tables();
        assertTrue(CrimsonRules.rivalChampion(t.factions.factions, "mage", "crimson_barbarian_duke"));
        assertTrue(CrimsonRules.rivalChampion(t.factions.factions, "barbarian", "crimson_mage_outlaw"));
        assertFalse(CrimsonRules.rivalChampion(t.factions.factions, "barbarian", "crimson_barbarian_duke"), "own champion");
        assertFalse(CrimsonRules.rivalChampion(t.factions.factions, "mage", "crimson_mage_outlaw"), "own champion");
        assertFalse(CrimsonRules.rivalChampion(t.factions.factions, null, "crimson_barbarian_duke"));
    }

    @Test
    void spoilsPerFaction() {
        CrimsonTables t = CrimsonRulesTest.tables();
        CrimsonTables.BossLoot bl = t.factions.boss_loot;
        assertNotNull(bl);
        assertTrue(bl.min_damage_percent > 0 && bl.min_damage_percent <= 10, "share threshold " + bl.min_damage_percent);
        // any boss: the faction's own key material, never the other faction's
        CrimsonRules.Spoils m = CrimsonRules.spoils(t.factions, "mage", "crimson_ashfang", false);
        assertFalse(m.champion());
        assertTrue(m.lines().stream().anyMatch(l -> l.item.equals("ENCHANTED_MYCELIUM")));
        assertTrue(m.lines().stream().noneMatch(l -> l.item.equals("ENCHANTED_RED_SAND")));
        CrimsonRules.Spoils b = CrimsonRules.spoils(t.factions, "barbarian", "crimson_ashfang", false);
        assertTrue(b.lines().stream().anyMatch(l -> l.item.equals("ENCHANTED_RED_SAND")));
        // the rival champion adds its own table; an open quest adds the key rolls
        CrimsonRules.Spoils champ = CrimsonRules.spoils(t.factions, "barbarian", "crimson_mage_outlaw", true);
        assertTrue(champ.champion() && champ.quest());
        assertEquals(bl.faction.get("barbarian").size() + bl.champion.get("crimson_mage_outlaw").size() + bl.quest.size(), champ.lines().size());
        assertTrue(champ.lines().stream().anyMatch(l -> l.item.equals("SPELL_POWDER")), "the Pyromancer's own drop");
        assertTrue(CrimsonRules.spoils(t.factions, null, "crimson_mage_outlaw", true).lines().isEmpty(), "unpledged: nothing");
        // every line is a sane drop
        for (List<CrimsonTables.LootLine> ls : bl.champion.values())
            for (CrimsonTables.LootLine l : ls) assertTrue(l.min >= 1 && l.max >= l.min && l.chance > 0 && l.chance <= 1, l.item);
        // each faction's rival boss has champion spoils
        for (String f : CrimsonRules.FACTIONS) assertTrue(bl.champion.containsKey(t.factions.factions.get(f).rival_boss), f);
        assertEquals("SHARD_BARBARIAN_DUKE_X", bl.shards.get("crimson_barbarian_duke"), "the hunting module's Duke shard id");
    }

    @Test
    void attributeMaths() {
        // Crimson Serendipity: "+2% odds" per level is relative
        assertEquals(0.002 * 1.2, CrimsonRules.vanquisherChance(0.002, 20), 1e-12);
        assertEquals(0.002, CrimsonRules.vanquisherChance(0.002, Double.NaN), 1e-12);
        assertEquals(1.0, CrimsonRules.vanquisherChance(0.5, 1000), 1e-12, "capped at certain");
        // Essence of Crimson: whole part always, the fraction as a chance
        assertEquals(2, CrimsonRules.percentBonus(10, 25, 0.5));
        assertEquals(3, CrimsonRules.percentBonus(10, 25, 0.49));
        assertEquals(0, CrimsonRules.percentBonus(10, -5, 0));
        assertEquals(0, CrimsonRules.percentBonus(0, 50, 0));
        int sum = 0;                                       // the expectation is exact over a uniform roll
        for (int i = 0; i < 1000; i++) sum += CrimsonRules.percentBonus(10, 25, i / 1000.0);
        assertEquals(2500, sum);
    }

    @Test
    void synthesizerCapsAreTheReferenceLore() {
        assertEquals(1, CrimsonRules.synthSlots("SYNTHESIZER_V1").size());
        assertEquals(30, CrimsonRules.synthSlots("SYNTHESIZER_V1").get(0).cap);
        assertEquals("HEALTH", CrimsonRules.synthSlots("SYNTHESIZER_V1").get(0).stat);
        assertEquals(List.of("X", "Y"), CrimsonRules.synthSlots("SYNTHESIZER_V2").stream().map(s -> s.item).toList());
        List<CrimsonTables.SynthSlot> v3 = CrimsonRules.synthSlots("SYNTHESIZER_V3");
        assertEquals(List.of(40, 40, 60), v3.stream().map(s -> s.cap).toList());
        assertEquals("INTELLIGENCE", v3.get(2).stat);
        assertTrue(CrimsonRules.synthSlots("DIAMOND_SWORD").isEmpty());
        assertEquals(2, CrimsonRules.synthTake(28, 30, 10));
        assertEquals(0, CrimsonRules.synthTake(30, 30, 10));
        assertEquals(0, CrimsonRules.synthTake(5, 30, -3));
        assertEquals("synth_x", CrimsonRules.synthKey("X"));
    }

    @Test
    void islandV4HasOpalVeinsAndSulphur() {
        CrimsonIsleGenerator gen = new CrimsonIsleGenerator();
        assertTrue(gen.version() >= 4, "v4+ (v5 added warp.skull)");
        IslandRun.Result r = IslandRun.run(gen, IslandRun.seedOf(gen.islandId()));
        Box opal = r.layout.area("gather.tomb_opal");
        assertNotNull(opal, "area gather.tomb_opal");
        int opals = 0, exposed = 0;
        for (int x = opal.minX(); x <= opal.maxX(); x++)
            for (int y = opal.minY(); y <= opal.maxY(); y++)
                for (int z = opal.minZ(); z <= opal.maxZ(); z++) {
                    if (!BlockState.name(r.canvas.get(x, y, z)).equals("white_stained_glass")) continue;
                    opals++;
                    if (r.canvas.isAir(x + 1, y, z) || r.canvas.isAir(x - 1, y, z) || r.canvas.isAir(x, y, z + 1) || r.canvas.isAir(x, y, z - 1)) exposed++;
                }
        assertTrue(opals >= 40, "opal blocks " + opals);
        assertEquals(opals, exposed, "every opal block faces open air (minable from the pit or the crypt)");
        // sulphur: deposits on the flanks and some on the pools' shores within reach of lava
        int sponge = 0, shore = 0;
        Box b = gen.bounds();
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int y = 60; y <= 130; y++) {
                    if (!BlockState.name(r.canvas.get(x, y, z)).equals("sponge")) continue;
                    sponge++;
                    assertTrue(r.canvas.isAir(x, y + 1, z), "sulphur ore buried at " + x + "," + y + "," + z);
                    boolean lava = false;
                    for (int dx = -3; dx <= 3 && !lava; dx++)
                        for (int dz = -3; dz <= 3 && !lava; dz++)
                            for (int dy = -1; dy <= 0 && !lava; dy++) lava = BlockState.name(r.canvas.get(x + dx, y + dy, z + dz)).equals("lava");
                    if (lava) shore++;
                }
        assertTrue(sponge >= 30, "sulphur ore blocks " + sponge);
        assertTrue(shore >= 6, "sulphur within 3 blocks of lava " + shore);
    }
}
