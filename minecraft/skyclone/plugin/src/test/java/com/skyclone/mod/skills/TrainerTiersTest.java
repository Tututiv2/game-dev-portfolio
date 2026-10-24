package com.skyclone.mod.skills;

import com.skyclone.api.skill.SkillType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Wave 5: the Pet Trainer's sessions, the Runebook digits, the NPC spot search, furniture lines, Hunting average. */
class TrainerTiersTest {

    static SkillTable t;

    @BeforeAll
    static void load() throws Exception {
        Path p = Path.of("src/main/resources/data/skills/skills.json");
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data/skills/skills.json");
        try (var r = new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8)) { t = SkillTable.load(r); }
    }

    @Test void sessionsClimbTheTamingRoad() {
        List<TrainerTiers.Tier> ts = TrainerTiers.TIERS;
        assertEquals(6, ts.size());
        for (int i = 0; i < ts.size(); i++) {
            assertEquals(10 * (i + 1), ts.get(i).taming(), ts.get(i).id());
            assertTrue(ts.get(i).xp() > 0 && ts.get(i).minutes() > 0);
            if (i > 0) assertTrue(ts.get(i).xp() >= ts.get(i - 1).xp(), ts.get(i).id() + " gives less XP");
        }
        assertEquals(0, ts.get(0).coins(), "the Free session is free");
        assertEquals(500, ts.get(5).bits(), "Turbo costs 500 Bits (research 05)");
        // paid coin sessions get better value as Taming rises
        for (int i = 2; i < 5; i++) assertTrue(ts.get(i).coinsPerXp() <= ts.get(i - 1).coinsPerXp() + 1e-12, ts.get(i).id());
        assertSame(ts.get(3), TrainerTiers.byId("EXPERT"));
        assertNull(TrainerTiers.byId("nope"));
    }

    @Test void tamingRoadNamesEverySession() {
        int lines = 0;
        for (int L = 1; L <= t.maxLevel(SkillType.TAMING); L++)
            for (SkillTable.Unlock u : t.level(SkillType.TAMING, L).unlocks)
                if (u.name != null && u.name.contains("Pet Trainer")) {
                    lines++;
                    assertEquals(0, L % 10, "training unlock at Taming " + L);
                }
        assertEquals(TrainerTiers.TIERS.size(), lines);
    }

    @Test void sessionTiming() {
        assertEquals(0, TrainerTiers.progress(1000, 2000, 500), 1e-9);
        assertEquals(0.5, TrainerTiers.progress(1000, 2000, 1500), 1e-9);
        assertEquals(1, TrainerTiers.progress(1000, 2000, 9999), 1e-9);
        assertEquals(1, TrainerTiers.progress(2000, 2000, 0), 1e-9);
        assertEquals(0, TrainerTiers.remaining(1000, 5000));
        assertEquals(60 * 60_000L, TrainerTiers.durationMillis(TrainerTiers.TIERS.get(0), 1.0));
        assertEquals(30 * 60_000L, TrainerTiers.durationMillis(TrainerTiers.TIERS.get(0), 0.5));
        assertEquals(1000L, TrainerTiers.durationMillis(TrainerTiers.TIERS.get(0), 0), "never instant");
        assertEquals(60 * 60_000L, TrainerTiers.durationMillis(TrainerTiers.TIERS.get(0), Double.NaN));
    }

    @Test void runebookDigits() {
        assertEquals(0, TrainerTiers.digits(0));
        assertEquals(0, TrainerTiers.digits(-5));
        assertEquals(1, TrainerTiers.digits(1));
        assertEquals(1, TrainerTiers.digits(9));
        assertEquals(2, TrainerTiers.digits(10));
        assertEquals(3, TrainerTiers.digits(999));
        assertEquals(4, TrainerTiers.digits(1000));
        assertEquals(7, TrainerTiers.digits(9_999_999));
    }

    @Test void spotSearchIsNearestFirstAndStable() {
        // only (3, 0, 4) and (-2, 1, 0) are standable: the nearer one (distance 2) wins, at dy +1
        int[] o = TrainerTiers.findSpot(2, 6, (dx, dy, dz) -> (dx == 3 && dy == 0 && dz == 4) || (dx == -2 && dy == 1 && dz == 0));
        assertArrayEquals(new int[]{-2, 1, 0}, o);
        // nothing inside minR, nothing outside maxR
        assertNull(TrainerTiers.findSpot(2, 6, (dx, dy, dz) -> dx == 0 && dz == 1));
        assertNull(TrainerTiers.findSpot(2, 6, (dx, dy, dz) -> dx == 7 && dz == 0));
        // same answer every time (a restart puts the NPC on the same block)
        TrainerTiers.Standable any = (dx, dy, dz) -> true;
        assertArrayEquals(TrainerTiers.findSpot(2, 6, any), TrainerTiers.findSpot(2, 6, any));
        int[] first = TrainerTiers.findSpot(2, 6, any);
        assertEquals(4, first[0] * first[0] + first[2] * first[2]);
    }

    @Test void carpentryRoadNamesTheCraftingFurniture() throws Exception {
        Path p = Path.of("src/main/resources/data/crafting/furniture.json");
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data/crafting/furniture.json");
        Set<String> ids = new HashSet<>();
        try (var r = new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8)) {
            for (var e : com.google.gson.JsonParser.parseReader(r).getAsJsonArray()) ids.add(e.getAsJsonObject().get("id").getAsString());
        }
        int n = 0;
        for (int L = 1; L <= t.maxLevel(SkillType.CARPENTRY); L++)
            for (SkillTable.Unlock u : t.level(SkillType.CARPENTRY, L).unlocks)
                if ("furniture".equals(u.type)) { n++; assertTrue(ids.contains(u.id), u.id + " is not crafting furniture"); }
                else assertFalse(u.name != null && u.name.contains("Furniture"), "furniture still as a text line: " + u.name);
        assertEquals(37, n);
    }

    @Test void huntingAverageIsADecision() {
        Map<SkillType, Integer> lv = new EnumMap<>(SkillType.class);
        for (SkillType s : SkillType.values()) lv.put(s, 20);
        lv.put(SkillType.HUNTING, 0);
        double without = SkillTable.average(lv);                                   // API default: Carpentry + Hunting cosmetic
        // research 02: every skill except Social and Runecrafting = 10 skills (Hunting at 0)
        double research = SkillTable.average(lv, s -> s != SkillType.SOCIAL && s != SkillType.RUNECRAFTING);
        assertEquals(20, without, 1e-9);
        assertEquals(18, research, 1e-9, "10 skills, one at 0");
    }
}
