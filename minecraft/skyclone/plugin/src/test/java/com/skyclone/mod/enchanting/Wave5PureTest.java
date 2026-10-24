package com.skyclone.mod.enchanting;

import com.google.gson.Gson;
import com.skyclone.api.item.EnchantDef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Wave 5 (2026-10-06): Vitality pool, Stealth, Scuba, Green Thumb, multi-number descriptions, pet perks. */
class Wave5PureTest {

    static EnchantRegistry reg;
    static ExperimentSpec ex;

    static Path res(String rel) {
        Path p = Path.of("src/main/resources/data", rel);
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data", rel);
        return p;
    }

    @BeforeAll
    static void load() throws Exception {
        try (Reader r = Files.newBufferedReader(res("enchanting/enchants.json"), StandardCharsets.UTF_8)) { reg = EnchantRegistry.read(r); }
        try (Reader r = Files.newBufferedReader(res("enchanting/experiments.json"), StandardCharsets.UTF_8)) { ex = new Gson().fromJson(r, ExperimentSpec.class); }
    }

    // ---- Vitality ------------------------------------------------------------------------------------------

    @Test void vitalityRegenIsFivePercentOfMaxAndNeverOverfills() {
        assertEquals(5, VitalityMath.regen(0, 100, 5, 1), 1e-9);
        assertEquals(10, VitalityMath.regen(0, 200, 5, 1), 1e-9, "research: 200 max -> 10/s");
        assertEquals(100, VitalityMath.regen(98, 100, 5, 1), 1e-9);
        assertEquals(60, VitalityMath.clamp(140, 60), 1e-9, "taking off a Vitality piece shrinks the pool");
        assertEquals(0, VitalityMath.regen(50, 0, 5, 1), 1e-9);
    }

    @Test void spendingTakesWhatIsThereAndConvertersCapPerPiece() {
        assertEquals(25, VitalityMath.spendable(100, 25), 1e-9);
        assertEquals(10, VitalityMath.spendable(10, 25), 1e-9);
        assertEquals(0, VitalityMath.spendable(0, 25), 1e-9);
        EnchantSpec hard = reg.spec("hardened_mana");
        assertEquals(40, hard.v(10), 1e-9);
        // a 25-Vitality wand with Hardened Vitality X on one piece: 40% of 25 = 10 Defense; twice = 20; thrice capped 25
        double d = VitalityMath.convert(0, 25, hard.v(10), 25);
        assertEquals(10, d, 1e-9);
        d = VitalityMath.convert(d, 25, hard.v(10), 25);
        assertEquals(20, d, 1e-9);
        assertEquals(25, VitalityMath.convert(d, 25, hard.v(10), 25), 1e-9);
        assertEquals(1, reg.spec("mana_vampire").v(10), 1e-9, "Vampiric Vitality X: 1 HP per Vitality");
    }

    @Test void drainAndLastStandRefillAtResearchNumbers() {
        EnchantSpec drain = reg.spec("syphon");
        assertEquals(6, drain.maxLevel, "Drain I-VI (research 01 section 14.3)");
        assertArrayEquals(new double[]{0.5, 1, 1.5, 2, 2.5, 3}, drain.values[0], 1e-9);
        assertTrue(drain.describe(6).contains("3") && drain.describe(6).contains("Vitality"));
        EnchantSpec ls = reg.spec("ultimate_last_stand");
        assertEquals(10, ls.v(1, 5), 1e-9, "Last Stand V: Regen 10 Vitality");
        assertEquals(12.5, ls.v(0, 5), 1e-9);
    }

    @Test void costDialParsesAndSkipsJunk() {
        Map<String, Double> c = VitalityMath.parseCosts("Weapons_Wand_Heal_Healing=25, slayer_orb = 50; bad, =3, x=, y=-2, z=abc");
        assertEquals(Map.of("weapons_wand_heal_healing", 25.0, "slayer_orb", 50.0), c);
        Map<String, Double> def = VitalityMath.parseCosts(VitalityMath.DEFAULT_COSTS);
        assertEquals(25, def.get("weapons_wand_heal_healing"), 1e-9, "wands 25 (research 01 section 6.2)");
        assertEquals(50, def.get("slayer_orb"), 1e-9, "power orbs 50");
        assertEquals(10, def.get("slayer_flare"), 1e-9, "flares 10");
        assertEquals(60, def.get("slayer_grimoire_heal"), 1e-9, "grimoire 60");
        assertEquals(50, VitalityMath.parseCosts(VitalityMath.DEFAULT_SOFT_COSTS).get("dungeons_wither_impact"), 1e-9);
        assertTrue(VitalityMath.bar(80.6, 100).contains("80/100"));
    }

    // ---- Stealth / Scuba / Green Thumb ------------------------------------------------------------------

    @Test void stealthDataAndTimidRules() {
        EnchantSpec st = reg.spec("stealth");
        assertEquals(6, st.maxLevel);
        assertEquals(10, st.v(1), 1e-9);
        assertEquals(60, st.v(6), 1e-9);
        assertTrue(st.categories.contains("BOOTS"));
        assertTrue(Wave5Math.timidDef(List.of("passive", "flying"), false, true), "hunting critter");
        assertFalse(Wave5Math.timidDef(List.of("aggressive"), false, true), "an aggressive critter fights, it does not flee");
        assertTrue(Wave5Math.timidDef(List.of("Skittish"), false, false));
        assertTrue(Wave5Math.timidDef(null, true, false), "extra.timid");
        assertFalse(Wave5Math.timidDef(Set.of("passive"), false, false), "a plain passive mob is not timid");
        assertTrue(Wave5Math.vanillaAvoider("fox"));
        assertFalse(Wave5Math.vanillaAvoider("ZOMBIE"));
    }

    @Test void stealthRollMatchesItsPercent() {
        assertTrue(Wave5Math.holdsStill(60, 0.59));
        assertFalse(Wave5Math.holdsStill(60, 0.60));
        assertFalse(Wave5Math.holdsStill(0, 0.0));
        assertTrue(Wave5Math.holdsStill(250, 0.999), "over 100% always holds");
        Random r = new Random(3);
        int held = 0;
        for (int i = 0; i < 100_000; i++) if (Wave5Math.holdsStill(30, r.nextDouble())) held++;
        assertEquals(0.30, held / 100_000.0, 0.01);
    }

    @Test void scubaPressureAndThresholdParsing() {
        EnchantSpec sc = reg.spec("scuba");
        assertEquals(6, sc.maxLevel, "Scuba I-VI (research 01: +1->6)");
        assertEquals(6, sc.v(6), 1e-9);
        assertTrue(sc.categories.containsAll(List.of("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")));
        assertEquals(20, Wave5Math.threshold(new String[]{"pressure_resistance", "20"}), 1e-9);
        assertEquals(1, Wave5Math.threshold(new String[]{"pressure_resistance"}), 1e-9);
        assertEquals(Double.MAX_VALUE, Wave5Math.threshold(new String[]{"pressure_resistance", "x"}), "closed by default");
    }

    @Test void greenThumbReadsTheCountBackByBinarySearch() {
        for (int served : new int[]{0, 1, 2, 37, 100, 255}) {
            int[] checks = {0};
            int got = Wave5Math.largestTrue(n -> { checks[0]++; return n <= served; }, 255);
            assertEquals(served, got);
            assertTrue(checks[0] <= 10, "at most ~log2(255)+1 requirement checks, was " + checks[0]);
        }
        assertEquals(255, Wave5Math.largestTrue(n -> true, 255), "capped");
        EnchantSpec gt = reg.spec("green_thumb");
        assertEquals(0.25, gt.v(5), 1e-9);
    }

    @Test void rendCountsArrowsPerTargetAndCapsAtFive() {
        EnchantSpec rend = reg.spec("ultimate_rend");
        assertEquals(25, rend.v(5), 1e-9);
        assertEquals(250, Wave5Math.rendDamage(1000, rend.v(5), 1, Rend.MAX_ARROWS), 1e-9);
        assertEquals(750, Wave5Math.rendDamage(1000, rend.v(5), 3, Rend.MAX_ARROWS), 1e-9);
        assertEquals(1250, Wave5Math.rendDamage(1000, rend.v(5), 9, Rend.MAX_ARROWS), 1e-9, "up to 5 arrows");
        assertEquals(0, Wave5Math.rendDamage(0, rend.v(5), 3, Rend.MAX_ARROWS), 1e-9, "never crit that target: nothing");
        assertEquals(0, Wave5Math.rendDamage(1000, rend.v(5), 0, Rend.MAX_ARROWS), 1e-9, "no arrow in it: nothing");
    }

    @Test void paleontologistRaisesTheScrapChanceByItsPercent() {
        EnchantSpec pal = reg.spec("paleontologist");
        assertEquals(12.5, pal.v(5), 1e-9);
        assertEquals(0.000125, Wave5Math.paleontologistChance(0.001, pal.v(5)), 1e-12);
        assertEquals(0, Wave5Math.paleontologistChance(0.001, 0), 1e-12);
        assertEquals(0, Wave5Math.paleontologistChance(-1, 50), 1e-12);
    }

    // ---- multi-number descriptions -------------------------------------------------------------------------

    @Test void levelledDefFillsEveryNumberAtFullPrecision() {
        int multi = 0;
        for (EnchantSpec s : reg.all()) {
            EnchantDef d = s.toDef();
            assertInstanceOf(LevelledEnchantDef.class, d, s.id);
            LevelledEnchantDef ld = (LevelledEnchantDef) d;
            if (s.desc.contains("{1}")) multi++;
            for (int l = s.minLevel; l <= s.maxLevel; l++) {
                String t = ld.describe(l);
                assertFalse(t.contains("{") || t.contains("%s"), s.id + " " + l + ": " + t);
            }
            assertTrue(d.description.isEmpty() || !d.description.contains("{"), s.id + " core template keeps a {n}");
        }
        assertTrue(multi >= 10, "multi-number enchants: " + multi);
        LevelledEnchantDef ls = (LevelledEnchantDef) reg.spec("ultimate_last_stand").toDef();
        assertTrue(ls.describe(5).contains("12.5") && ls.describe(5).contains("10"), ls.describe(5));
        LevelledEnchantDef ms = (LevelledEnchantDef) reg.spec("mana_steal").toDef();
        assertTrue(ms.describe(1).contains("0.25"), "core's renderer rounds this to 0.3; describe() must not");
        LevelledEnchantDef cf = (LevelledEnchantDef) reg.spec("ultimate_crop_fever").toDef();
        assertTrue(cf.describe(5).contains("0.005"), cf.describe(5));
        // the api does not have describe(int) yet (core request 1); when it does, ours overrides it unchanged
        assertEquals(LevelledEnchantDef.coreRendersFullText(), hasDescribe());
    }

    private static boolean hasDescribe() {
        for (var m : EnchantDef.class.getDeclaredMethods()) if (m.getName().equals("describe")) return true;
        return false;
    }

    // ---- pet perks -------------------------------------------------------------------------------------------

    @Test void luckySevenRaisesOnlyTheUltraRareBooks() {
        ExperimentSpec.Tier t = ex.tier("metaphysical");
        int floor = t.ultraRareWeight();
        assertTrue(floor > 0);
        int base = 0, boosted = 0, baseBottles = 0, boostedBottles = 0;
        Random a = new Random(11), b = new Random(11);
        for (int i = 0; i < 60_000; i++) {
            ExperimentSpec.Reward x = t.roll(a, 0), y = t.roll(b, 120);   // Guardian Mythic L100: +120%
            if (ExperimentSpec.Tier.isBook(x) && x.weight == floor) base++;
            if (ExperimentSpec.Tier.isBook(y) && y.weight == floor) boosted++;
            if (!ExperimentSpec.Tier.isBook(x)) baseBottles++;
            if (!ExperimentSpec.Tier.isBook(y)) boostedBottles++;
        }
        assertTrue(boosted > base * 1.8, base + " -> " + boosted);
        assertTrue(boostedBottles < baseBottles, "the rest of the pool gives way");
        // roll(rnd) == roll(rnd, 0): the old path is unchanged in distribution
        Random c = new Random(5), e = new Random(5);
        for (int i = 0; i < 200; i++) assertSame(t.roll(c), t.roll(e, 0));
    }

    @Test void superpairsDealWithBoostStillDealsFullBoards() {
        for (ExperimentSpec.Tier t : ex.tiers) {
            SuperpairsBoard b = SuperpairsBoard.deal(t, 10, new Random(2), 120);
            assertEquals(t.tiles(), b.tiles.size(), t.id);
        }
    }
}
