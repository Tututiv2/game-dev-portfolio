package com.skyclone.mod.combatzones;

import com.skyclone.gen.IslandRun;
import com.skyclone.gen.Walkability;
import com.skyclone.mod.combatzones.gen.DenGenerator;
import com.skyclone.mod.combatzones.gen.EndGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave 5 (2026-10-06): Keeper Ixo's Draconic Altar + Dragon Essence shop, Wanderer Tavi's quests, Fenn and Lark's
 * mob types, and the SkyHanni/NEU dragon loot weights. Every assertion runs the shipping tables.
 */
class WaveFiveTest {

    /** NEU constants/essenceshops.json ESSENCE_DRAGON, copied here so a typo in the shipping table fails. */
    static final Map<String, int[]> NEU = Map.of(
            "flat_damage_vs_ender", new int[]{100, 200, 300, 400, 500},
            "mana_after_ender_kill", new int[]{100, 100, 100, 100, 100, 100, 100, 100, 100, 100},
            "fero_vs_dragons", new int[]{125, 250, 500, 1000, 1500},
            "inc_zealots_odds", new int[]{150, 500, 1250, 2000, 3000},
            "combat_wisdom_in_end", new int[]{250, 1250, 5000},
            "edrag_cd", new int[]{500, 1500, 2500, 3500, 4500},
            "dragon_reforges_buff", new int[]{1500, 2250, 3250, 4500, 6500},
            "increased_sup_chances", new int[]{5000},
            "unbridled_rage", new int[]{1000, 1500, 2000, 3000, 5000});

    @Test
    void perkCostsAreNeuAndSkyBlockXpIsTheResearchTotal() {
        assertEquals(NEU.size(), DraconicMath.PERKS.size());
        for (DraconicMath.Perk p : DraconicMath.PERKS) assertArrayEquals(NEU.get(p.id()), p.costs(), p.id());
        assertEquals(185, DraconicMath.totalSbXp(), "research 02: Dragon shop SkyBlock XP total");
        assertEquals(62, sum(DraconicMath.perk("mana_after_ender_kill")));
        assertEquals(19, sum(DraconicMath.perk("flat_damage_vs_ender")));
        assertEquals(1000, DraconicMath.costToMax(DraconicMath.perk("mana_after_ender_kill"), 0));
        for (DraconicMath.Perk p : DraconicMath.PERKS) {
            List<String> l = DraconicMath.effectLines(p, 1);
            assertFalse(l.isEmpty());
            for (String s : l) assertFalse(s.contains("%s"), p.id() + " left a %s in its lore");
        }
    }

    private static int sum(DraconicMath.Perk p) {
        int s = 0;
        for (int t = 1; t <= p.max(); t++) s += DraconicMath.sbXp(t, p.max());
        return s;
    }

    @Test
    void altarPaysByItemAndBonusIsLinearUnderTheCap() {
        assertEquals(3, DraconicMath.essenceFor("OLD_FRAGMENT", 1, 1));
        assertEquals(192, DraconicMath.essenceFor("YOUNG_FRAGMENT", 64, 1));
        assertEquals(15, DraconicMath.essenceFor("SUPERIOR_FRAGMENT", 1, 1));
        assertEquals(240, DraconicMath.essenceFor("SUPERIOR_DRAGON_CHESTPLATE", 1, 1));
        assertEquals(0, DraconicMath.essenceFor("DIAMOND_SWORD", 1, 1), "the altar refuses what is not dragon loot");
        assertEquals(0, DraconicMath.essenceFor("OLD_FRAGMENT", 0, 1));
        // every variant's fragment and four pieces are accepted
        for (String v : DraconicMath.VARIANTS) {
            assertTrue(DraconicMath.SACRIFICE.containsKey(v + "_FRAGMENT"));
            for (String piece : List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")) assertTrue(DraconicMath.SACRIFICE.containsKey(v + "_DRAGON_" + piece));
        }
        // splitting a sacrifice never beats offering it whole (below the cap)
        double whole = DraconicMath.bonusChance(160, 0.0005, 0.5, 0);
        double halves = 1 - Math.pow(1 - DraconicMath.bonusChance(80, 0.0005, 0.5, 0), 2);
        assertTrue(halves <= whole + 1e-12);
        assertEquals(0.5, DraconicMath.bonusChance(1_000_000, 0.0005, 0.5, 0), 1e-12);
        assertEquals(0.55, DraconicMath.bonusChance(1_000_000, 0.0005, 0.5, 10), 1e-12, "Sacrificer +10% of the chance");
    }

    @Test
    void wyrmCallerScalesWithTheHoldersShareOfEyes() {
        assertEquals(4, DraconicMath.superiorWeight(4, 25, 0), 1e-12);
        assertEquals(5, DraconicMath.superiorWeight(4, 25, 8), 1e-12);
        assertEquals(4.5, DraconicMath.superiorWeight(4, 25, 4), 1e-12);
        assertEquals(5, DraconicMath.superiorWeight(4, 25, 20), 1e-12, "capped at the eight frames");
        assertEquals(50, DraconicMath.dragonEssence(1000, 20));
        assertEquals(0, DraconicMath.dragonEssence(0, 20));
        assertEquals(1, DraconicMath.dragonEssence(5, 20), "anyone who took part gets at least one");
    }

    @Test
    void taviQuestsCountOnlyTheirMobsAndCap() {
        ZoneQuests.Quest q = ZoneQuests.at(0);
        assertNotNull(q);
        assertEquals(1, ZoneQuests.progress(q, 0, "end_enderman_42"));
        assertEquals(0, ZoneQuests.progress(q, 0, "end_zealot_55"), "a Zealot is not an Enderman for this task");
        assertEquals(q.count(), ZoneQuests.progress(q, q.count(), "end_enderman_50"));
        ZoneQuests.Quest z = ZoneQuests.QUESTS.stream().filter(x -> x.id().equals("zealots")).findFirst().orElseThrow();
        assertTrue(z.counts("end_zealot_55"));
        assertFalse(z.counts("end_zealot_bruiser"), "bruisers have their own task");
        assertNull(ZoneQuests.at(ZoneQuests.QUESTS.size()));
        assertTrue(ZoneQuests.allDone(ZoneQuests.QUESTS.size()));
        assertEquals(60_000, ZoneQuests.voidSwordPrice(60_000, 20, false));
        assertEquals(48_000, ZoneQuests.voidSwordPrice(60_000, 20, true));
    }

    @Test
    void mobTypesFollowTheStatedListElseInfer() {
        assertEquals(Set.of("ender"), MobTypeCatalog.of("ENDERMAN", "end_zealot_55", "enderman", false, List.of("ender", "enderman")),
                "a module's private tags are dropped");
        assertEquals(Set.of("cubic"), MobTypeCatalog.of("SLIME", "den_rain_slime", null, false, List.of("cubic", "slime")));
        assertTrue(MobTypeCatalog.of("SPIDER", "x", null, false, null).contains("arthropod"));
        assertTrue(MobTypeCatalog.of("SKELETON", "x", null, false, null).containsAll(Set.of("undead", "skeletal")));
        assertTrue(MobTypeCatalog.of("ENDER_DRAGON", "end_dragon_old", null, false, List.of()).containsAll(Set.of("ender", "airborne")));
        assertTrue(MobTypeCatalog.of("SQUID", "sea_squid", null, true, null).contains("aquatic"));
        for (MobTypeCatalog.Type t : MobTypeCatalog.TYPES) assertSame(t, MobTypeCatalog.type(t.id()));
    }

    @Test
    void dragonLootUsesTheTrackerWeights() {
        List<DragonMath.LootEntry> sup = DragonMath.lootTable("superior");
        assertEquals("DRAGON_HORN", sup.get(0).item());
        assertEquals(452, sup.get(0).weight());
        assertEquals(0.3, sup.get(0).flatChance(), 1e-12);
        assertEquals("DRAGON_CLAW", sup.get(1).item());
        assertTrue(DragonMath.lootTable("young").stream().noneMatch(e -> e.item().equals("DRAGON_HORN")), "the Horn is Superior only");
        // the Horn's 30% is flat: with 8 eyes it is still ~30%, not 100%
        int horns = 0;
        Random r = new Random(3);
        for (int i = 0; i < 20_000; i++) if ("DRAGON_HORN".equals(DragonMath.award("superior", 1000, 8, r, id -> true, 8).item())) horns++;
        assertEquals(0.3, horns / 20_000.0, 0.02);
    }

    @Test
    void fennsCribAndTheDraconicAltarAreBuilt() {
        DenGenerator den = new DenGenerator();
        IslandRun.Result d = IslandRun.run(den, IslandRun.seedOf(den.islandId()));
        Walkability.Options o = new Walkability.Options();
        o.skipFloating = true;
        Walkability.Report rep = Walkability.analyse(d.canvas, d.layout, o);
        for (String a : List.of("npc.ike", "npc.michael")) {
            assertNotNull(d.layout.anchor(a), a);
            Walkability.AnchorResult ar = rep.anchors.stream().filter(x -> x.name().equals(a)).findFirst().orElseThrow();
            assertNotEquals(Walkability.Status.UNREACHABLE, ar.status(), a);
            assertNotEquals(Walkability.Status.INSIDE_SOLID, ar.status(), a);
        }
        assertTrue(d.layout.zones().stream().anyMatch(z -> z.id().equals("fenns_crib") && z.flags().contains("SAFE")));

        EndGenerator end = new EndGenerator();
        IslandRun.Result e = IslandRun.run(end, IslandRun.seedOf(end.islandId()));
        String s = e.layout.data().get("altar.draconic");
        assertNotNull(s, "layout data altar.draconic");
        String[] p = s.split(",");
        int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
        assertFalse(e.canvas.isAir(x, y, z), "the altar pillar stands at altar.draconic");
        assertFalse(e.canvas.isAir(x, y + 2, z), "the dragon head crowns it");
        var keeper = e.layout.anchor("npc.void_keeper");
        assertNotNull(keeper);
        assertTrue(Math.hypot(keeper.x() - x, keeper.z() - z) < 10, "Keeper Ixo stands by the altar");
    }
}
