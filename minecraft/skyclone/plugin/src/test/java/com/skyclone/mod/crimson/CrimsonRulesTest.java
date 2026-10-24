package com.skyclone.mod.crimson;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The isle's pure rules and generated tables against the research (03 §15, 04 §4.3, 02 SkyBlock XP). */
class CrimsonRulesTest {

    static CrimsonTables tables() {
        return CrimsonTables.load(f -> {
            InputStream in = CrimsonRulesTest.class.getResourceAsStream("/data/crimson/" + f);
            assertNotNull(in, "missing resource data/crimson/" + f);
            return in;
        });
    }

    @Test
    void factionNamesParse() {
        assertEquals("mage", CrimsonRules.faction("Mages"));
        assertEquals("barbarian", CrimsonRules.faction("BARBARIANS"));
        assertEquals("barbarian", CrimsonRules.faction("barbarian"));
        assertNull(CrimsonRules.faction("pirates"));
        assertEquals("mage", CrimsonRules.rival("barbarian"));
    }

    @Test
    void ranksMatchResearch() {
        List<CrimsonTables.Rank> r = tables().factions.ranks;
        int[] th = {0, 1000, 3000, 7000, 12000};
        assertEquals(5, r.size());
        for (int i = 0; i < 5; i++) assertEquals(th[i], r.get(i).rep);
        assertEquals(0, CrimsonRules.rankIndex(r, 999));
        assertEquals(1, CrimsonRules.rankIndex(r, 1000));
        assertEquals(1, CrimsonRules.rankIndex(r, 2999));
        assertEquals(2, CrimsonRules.rankIndex(r, 3000));
        assertEquals(4, CrimsonRules.rankIndex(r, 50_000));
        assertEquals(-1, CrimsonRules.toNext(r, 12_000));
        assertEquals(1, CrimsonRules.toNext(r, 6_999));
        int sb = r.stream().mapToInt(x -> x.sbxp).sum();
        assertEquals(75, sb, "research 02: faction reputation is worth 75 SkyBlock XP per faction");
    }

    @Test
    void questBoardIsDeterministicAndEligible() {
        CrimsonTables t = tables();
        for (String f : CrimsonRules.FACTIONS) {
            for (int rank = 0; rank <= 4; rank++) {
                long seed = CrimsonRules.questSeed("00000000-0000-0000-0000-000000000001", "2026-10-02");
                List<CrimsonTables.Quest> a = CrimsonRules.rollQuests(t.quests, f, rank, seed, 5);
                List<CrimsonTables.Quest> b = CrimsonRules.rollQuests(t.quests, f, rank, seed, 5);
                assertEquals(5, a.size());
                assertEquals(a, b);
                Set<String> ids = new HashSet<>();
                for (CrimsonTables.Quest q : a) {
                    assertTrue(ids.add(q.id), "duplicate " + q.id);
                    assertTrue(q.forFaction(f), q.id + " offered to " + f);
                    assertTrue(q.minRank <= rank, q.id + " offered at rank " + rank);
                }
            }
        }
        // a different day gives (almost always) a different board
        List<CrimsonTables.Quest> d1 = CrimsonRules.rollQuests(t.quests, "mage", 2, CrimsonRules.questSeed("u", "2026-10-02"), 5);
        List<CrimsonTables.Quest> d2 = CrimsonRules.rollQuests(t.quests, "mage", 2, CrimsonRules.questSeed("u", "2026-10-03"), 5);
        assertFalse(d1.equals(d2));
    }

    @Test
    void dojoGradesAndBelts() {
        CrimsonTables t = tables();
        assertEquals(7, t.dojo.trials.size());
        assertEquals("S", CrimsonRules.grade(t.dojo.ranks, 1000));
        assertEquals("A", CrimsonRules.grade(t.dojo.ranks, 850));
        assertEquals("C", CrimsonRules.grade(t.dojo.ranks, 400));
        assertEquals("F", CrimsonRules.grade(t.dojo.ranks, 0));
        assertNull(CrimsonRules.belt(t.dojo.belts, 999));
        assertEquals("DOJO_WHITE_BELT", CrimsonRules.belt(t.dojo.belts, 1000).id);
        assertEquals("DOJO_BLACK_BELT", CrimsonRules.belt(t.dojo.belts, 7000).id);
        int[] sbxp = {20, 30, 50, 75, 100, 150};
        for (int i = 0; i < sbxp.length; i++) assertEquals(sbxp[i], t.dojo.belts.get(i).sbxp, "research 02 belt XP");
        assertTrue(CrimsonRules.swiftnessStep(0) > CrimsonRules.swiftnessStep(2000));
        assertEquals(1000, CrimsonRules.control(80, 100, 1000));
        assertEquals(0, CrimsonRules.control(0, 100, 1000));
    }

    @Test
    void prestigeLadder() {
        assertEquals(0, CrimsonRules.tierOf("CRIMSON_HELMET"));
        assertEquals(1, CrimsonRules.tierOf("HOT_TERROR_BOOTS"));
        assertEquals(4, CrimsonRules.tierOf("INFERNAL_HOLLOW_LEGGINGS"));
        assertEquals("BURNING_FERVOR_CHESTPLATE", CrimsonRules.nextPrestige("HOT_FERVOR_CHESTPLATE"));
        assertNull(CrimsonRules.nextPrestige("INFERNAL_AURORA_HELMET"));
        Object[] c = CrimsonRules.cell("SAND:1:160");
        assertEquals("SAND:1", c[0]);
        assertEquals(160, c[1]);
    }

    @Test
    void bossDamageDialNeverHeals() {
        assertEquals(1.0, CrimsonRules.damageScale(1.0));
        assertEquals(0.05, CrimsonRules.damageScale(0.05));
        assertEquals(0.0, CrimsonRules.damageScale(0.0));
        assertEquals(3.0, CrimsonRules.damageScale(3.0));
        assertEquals(1.0, CrimsonRules.damageScale(-0.5));
        assertEquals(1.0, CrimsonRules.damageScale(Double.NaN));
        assertEquals(1.0, CrimsonRules.damageScale(Double.POSITIVE_INFINITY));
    }

    @Test
    void shopAndQuestDataAreSane() {
        CrimsonTables t = tables();
        assertTrue(t.quests.size() >= 30);
        for (CrimsonTables.Quest q : t.quests) {
            assertTrue(q.amount > 0, q.id);
            assertTrue(q.rep > 0, q.id);
            assertTrue(List.of("KILL", "BOSS", "FETCH", "FISH", "FISH_CREATURE", "DOJO", "KUUDRA").contains(q.type), q.id);
            assertTrue(List.of("any", "mage", "barbarian").contains(q.faction), q.id);
        }
        for (CrimsonTables.ShopEntry e : t.shop) assertTrue(List.of("mage", "barbarian").contains(e.faction), e.item);
        assertEquals(14, t.npcs.size());
        for (CrimsonTables.Npc n : t.npcs) assertTrue(n.anchor.startsWith("npc."), n.id);
    }
}
