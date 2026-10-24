package com.skyclone.mod.skills;

import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the SHIPPING skill table (data/skills/skills.json) against research 02's numbers. */
class SkillTableTest {

    static SkillTable t;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = SkillTableTest.class.getResourceAsStream("/data/skills/skills.json")) {
            assertNotNull(in, "data/skills/skills.json on the classpath");
            t = SkillTable.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test void tableIsSelfConsistent() { assertNull(t.validate()); }

    @Test void researchNumbers() { assertNull(SkillSelfTests.researchNumbers(t)); }

    @Test void everySkillHasEveryLevel() {
        for (SkillType s : SkillType.values()) {
            for (int L = 1; L <= t.maxLevel(s); L++) assertNotNull(t.level(s, L), s + " " + L);
            assertNull(t.level(s, t.maxLevel(s) + 1));
            assertTrue(t.defaultCap(s) <= t.maxLevel(s));
        }
        assertEquals(57, t.maxLevel(SkillType.FORAGING));
        assertEquals(25, t.maxLevel(SkillType.RUNECRAFTING));
    }

    @Test void capsClampLevels() {
        double xp = t.total(SkillType.FARMING, 55) + 10;
        assertEquals(55, t.levelFor(SkillType.FARMING, xp));
        assertEquals(50, t.levelFor(SkillType.FARMING, xp, 50));
        assertEquals(0, t.levelFor(SkillType.FARMING, Double.NaN));
        assertEquals(0, t.levelFor(SkillType.FARMING, 49.9));
        assertEquals(60, t.levelFor(SkillType.FARMING, 1e12));
    }

    @Test void progressAndNeeded() {
        // level 4 -> 5 needs 500 (cumulative 675 -> 1,175)
        assertEquals(500, t.needed(SkillType.MINING, 4), 1e-9);
        assertEquals(100, t.progress(SkillType.MINING, 775, 4), 1e-9);
        assertEquals(0, t.needed(SkillType.MINING, 60), 1e-9);
    }

    @Test void statsAccumulateAndPerkIsSeparate() {
        // Farming 15: 14 x 2 + 1 x 3 = 31 Health; perk = 60 Farming Fortune, never in statsAt
        assertEquals(31, t.statsAt(SkillType.FARMING, 15).get(Stat.HEALTH), 1e-9);
        assertEquals(60, t.perk(SkillType.FARMING, 15), 1e-9);
        assertNull(t.statsAt(SkillType.FARMING, 15).get(Stat.FARMING_FORTUNE));
        assertEquals(Stat.FARMING_FORTUNE, t.perkStat(SkillType.FARMING));
        assertNull(t.perkStat(SkillType.COMBAT));
        assertTrue(t.statsAt(SkillType.FARMING, 0).isEmpty());
    }

    @Test void averageSkipsCosmeticSkills() {
        EnumMap<SkillType, Integer> lv = new EnumMap<>(SkillType.class);
        for (SkillType s : SkillType.values()) lv.put(s, s.cosmetic ? 50 : 10);
        assertEquals(10, SkillTable.average(lv), 1e-9);
    }
}
