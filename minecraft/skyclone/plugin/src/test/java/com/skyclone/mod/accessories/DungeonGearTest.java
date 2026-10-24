package com.skyclone.mod.accessories;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.stat.Stat;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 3 fixer: the Adaptive Belt's per-class stats, the Ancient Cloak's health-cost abilities and the
 * Catacombs / class XP accessories ship as typed effects with the reference's numbers.
 */
class DungeonGearTest {

    private static final Map<String, JsonObject> BY_ID = new HashMap<>();

    private static Map<String, JsonObject> all() throws Exception {
        if (!BY_ID.isEmpty()) return BY_ID;
        for (String f : new String[]{"items.json", "equipment.json"}) {
            try (InputStream in = DungeonGearTest.class.getClassLoader().getResourceAsStream("data/accessories/" + f)) {
                assertNotNull(in, f);
                JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
                for (JsonElement e : arr) BY_ID.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            }
        }
        return BY_ID;
    }

    private static List<Fx> fx(String id) throws Exception {
        JsonObject o = all().get(id);
        assertNotNull(o, id + " is shipped");
        List<Fx> flat = new ArrayList<>();
        for (Fx f : Fx.parseAll(o.getAsJsonObject("extra"), id)) f.flatten(flat);
        return flat;
    }

    private static boolean hooked(String id) throws Exception {
        JsonObject extra = all().get(id).getAsJsonObject("extra");
        return extra != null && extra.has("hook");
    }

    private static Cond.Ctx asClass(String cls) {
        return new Cond.Ctx() {
            @Override public boolean night() { return false; }
            @Override public boolean burning() { return false; }
            @Override public boolean inDungeon() { return false; }
            @Override public String island() { return null; }
            @Override public String zone() { return null; }
            @Override public int season() { return 0; }
            @Override public String dungeonClass() { return cls; }
        };
    }

    /** What the belt's stat_if effects grant for one selected class (the engine's own condition test). */
    private static Map<Stat, Double> beltFor(String id, String cls) throws Exception {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        for (Fx f : fx(id)) {
            assertTrue(f.known && f.when.valid, id + " " + f.type + " " + f.when.text);
            if (f.type.equals("stat_if") && f.when.test(asClass(cls))) out.merge(f.stat, f.value, Double::sum);
        }
        return out;
    }

    @Test
    void adaptiveBeltGivesEachClassItsReferenceStats() throws Exception {
        for (String belt : new String[]{"ADAPTIVE_BELT", "STARRED_ADAPTIVE_BELT"}) {
            assertEquals(Map.of(Stat.STRENGTH, 10.0), beltFor(belt, "BERSERK"));
            assertEquals(Map.of(Stat.HEALTH, 10.0, Stat.MENDING, 5.0), beltFor(belt, "HEALER"));
            assertEquals(Map.of(Stat.INTELLIGENCE, 25.0), beltFor(belt, "MAGE"));
            assertEquals(Map.of(Stat.HEALTH, 5.0, Stat.DEFENSE, 10.0), beltFor(belt, "TANK"));
            assertEquals(Map.of(Stat.CRIT_CHANCE, 2.0, Stat.CRIT_DAMAGE, 5.0), beltFor(belt, "ARCHER"));
            assertEquals(Map.of(Stat.STRENGTH, 10.0), beltFor(belt, "berserk"), "class match is case-blind");
            assertTrue(beltFor(belt, null).isEmpty(), "no class known -> no class stats");
            assertFalse(hooked(belt));
            String lore = all().get(belt).get("lore").toString();
            assertTrue(lore.contains("&bMage&7: &b+25"), lore);
        }
    }

    /** Wave 4: the /equipment stats icon names the live class bonus (dungeons returns DClass.name(), stores key()). */
    @Test
    void classBonusLinesNameTheLiveBonus() throws Exception {
        List<Fx> belt = Fx.parseAll(all().get("ADAPTIVE_BELT").getAsJsonObject("extra"), "ADAPTIVE_BELT");
        assertEquals(List.of("&b+25 ✎ Intelligence"), Fx.classBonusLines(belt, "MAGE"));
        assertEquals(List.of("&b+25 ✎ Intelligence"), Fx.classBonusLines(belt, "mage"));
        assertEquals(List.of("&c+5 ❤ Health", "&a+10 ❈ Defense"), Fx.classBonusLines(belt, "TANK"));
        assertEquals(List.of("&9+2% ☣ Crit Chance", "&9+5% ☠ Crit Damage"), Fx.classBonusLines(belt, "ARCHER"));
        assertEquals(List.of(), Fx.classBonusLines(belt, null), "worn, no class known: empty, not null");
        List<Fx> plain = Fx.parseAll(all().get("CATACOMBS_EXPERT_RING").getAsJsonObject("extra"), "CATACOMBS_EXPERT_RING");
        assertEquals(null, Fx.classBonusLines(plain, "MAGE"), "nothing class-based worn");
    }

    @Test
    void classConditionParses() {
        Cond c = Cond.parse("class:mage|tank");
        assertTrue(c.valid);
        assertTrue(c.uses("class"));
        assertTrue(c.test(asClass("MAGE")));
        assertTrue(c.test(asClass("Tank")));
        assertFalse(c.test(asClass("ARCHER")));
        assertFalse(Cond.parse("night").uses("class"));
    }

    @Test
    void ancientCloakKnowsTheHealthCostAbilities() throws Exception {
        Fx hp = null;
        for (Fx f : fx("ANCIENT_CLOAK")) if (f.type.equals("health_ability")) hp = f;
        assertNotNull(hp, "ANCIENT_CLOAK has a health_ability effect");
        assertTrue(hp.known);
        assertEquals(10, hp.pct);
        for (String id : new String[]{"STAFF_OF_THE_VOLCANO", "BOUQUET_OF_LIES", "FLOWER_OF_TRUTH"}) {
            assertTrue(hp.members.contains(id), id + " costs health in the research");
        }
        assertFalse(hp.members.contains("ASPECT_OF_THE_END"));
        assertFalse(hooked("ANCIENT_CLOAK"));
    }

    @Test
    void healthAbilityHitWindow() {
        long t = 1_000_000;
        // Eruption: tagged MAGIC damage inside the handler, right after the cost check armed it
        assertTrue(AccMath.healthAbilityHit(true, "STAFF_OF_THE_VOLCANO", "STAFF_OF_THE_VOLCANO", t, 0, t + 5, 1000));
        // the same tagged hit long after the arm, never confirmed: no
        assertFalse(AccMath.healthAbilityHit(true, "STAFF_OF_THE_VOLCANO", "STAFF_OF_THE_VOLCANO", t, 0, t + 2000, 1000));
        // a plain swing right after arming but before the ability was confirmed: no
        assertFalse(AccMath.healthAbilityHit(false, "BOUQUET_OF_LIES", "BOUQUET_OF_LIES", t, 0, t + 5, 1000));
        // rose volleys a few ticks after core confirmed the ability: yes, until the window runs out
        assertTrue(AccMath.healthAbilityHit(false, "BOUQUET_OF_LIES", "BOUQUET_OF_LIES", t, t + 1, t + 400, 1000));
        assertFalse(AccMath.healthAbilityHit(false, "BOUQUET_OF_LIES", "BOUQUET_OF_LIES", t, t + 1, t + 1500, 1000));
        // a different weapon in hand never counts
        assertFalse(AccMath.healthAbilityHit(true, "HYPERION", "STAFF_OF_THE_VOLCANO", t, t, t + 5, 1000));
        assertFalse(AccMath.healthAbilityHit(true, null, null, 0, 0, t, 1000));
    }

    @Test
    void dungeonXpAccessoriesCarryTheirPercent() throws Exception {
        Map<String, double[]> want = Map.of(
                "CATACOMBS_EXPERT_RING", new double[]{0, 10},
                "SCARF_STUDIES", new double[]{1, 2},
                "SCARF_THESIS", new double[]{1, 4},
                "SCARF_GRIMOIRE", new double[]{1, 6});
        for (Map.Entry<String, double[]> e : want.entrySet()) {
            Fx x = null;
            for (Fx f : fx(e.getKey())) if (f.type.equals("dungeon_xp")) x = f;
            assertNotNull(x, e.getKey() + " has a dungeon_xp effect");
            assertTrue(x.known);
            assertEquals(e.getValue()[0] == 0 ? "catacombs" : "class", x.skill, e.getKey());
            assertEquals(e.getValue()[1], x.pct, e.getKey());
            assertFalse(hooked(e.getKey()), e.getKey());
        }
        assertEquals(110.0, AccMath.dungeonXp(100, 10), 1e-9);
        assertEquals(106.0, AccMath.dungeonXp(100, 6), 1e-9);
        assertEquals(100.0, AccMath.dungeonXp(100, 0), 1e-9);
        assertEquals(0.0, AccMath.dungeonXp(0, 10), 1e-9);
    }

    /** Stand-in with the shape the hook asks the API to give SkyEvents.DungeonXpGain. */
    public static final class FakeXpGain {
        public final org.bukkit.entity.Player player = null;
        public final String track;
        public double amount;
        FakeXpGain(String track, double amount) { this.track = track; this.amount = amount; }
    }

    public static final class WrongShape {
        public final org.bukkit.entity.Player player = null;
        public final String track = "catacombs";
        public final double amount = 1;
    }

    @Test
    void dungeonXpHookScalesTheRightTrack() throws Exception {
        assertEquals(null, DungeonXpHook.shapeError(FakeXpGain.class));
        assertNotNull(DungeonXpHook.shapeError(WrongShape.class), "a final amount cannot be scaled");
        assertNotNull(DungeonXpHook.shapeError(String.class));
        DungeonXpHook.PctSource src = (p, classXp) -> classXp ? 6 : 10;   // a Grimoire and the Expert Ring
        FakeXpGain cata = new FakeXpGain("catacombs", 1000);
        DungeonXpHook.scale(cata, src);
        assertEquals(1100, cata.amount, 1e-9);
        FakeXpGain cls = new FakeXpGain("MAGE", 1000);
        DungeonXpHook.scale(cls, src);
        assertEquals(1060, cls.amount, 1e-9);
        FakeXpGain none = new FakeXpGain("MAGE", 1000);
        DungeonXpHook.scale(none, (p, classXp) -> 0);
        assertEquals(1000, none.amount, 1e-9);
    }

    /** The live self-test: our half is checked on the probe; only a present-but-unbound event is our failure. */
    @Test
    void dungeonXpSelfCheckBlamesOnlyThisModule() {
        assertEquals(null, DungeonXpHook.selfCheck(false, false, "the API has no event yet"), "no API event is not our fault");
        assertEquals(null, DungeonXpHook.selfCheck(true, true, null));
        String err = DungeonXpHook.selfCheck(true, false, "wrong field types");
        assertNotNull(err, "event present but unbound is a failure");
        assertTrue(err.contains("wrong field types"), err);
    }
}
