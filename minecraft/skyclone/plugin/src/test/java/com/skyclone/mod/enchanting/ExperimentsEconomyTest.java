package com.skyclone.mod.enchanting;

import com.google.gson.Gson;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-game audit 2026-10-06, row G12: the Experimentation Table economy (start fees, banked charges, extra
 * games for levels + bits, the Supreme+ ultra-rare Endcap pool and the Enchanting gates), and the anvil step
 * that gives those Endcap Upgrades a use. Runs against the SHIPPING data files.
 */
class ExperimentsEconomyTest {

    static EnchantRegistry reg;
    static ExperimentSpec ex;
    static AnvilRules.Endcap[] endcaps;

    static final long DAY = 24L * 3_600_000L;

    @BeforeAll
    static void load() throws Exception {
        try (Reader r = Files.newBufferedReader(EnchantingPureTest.data("enchants.json"), StandardCharsets.UTF_8)) { reg = EnchantRegistry.read(r); }
        try (Reader r = Files.newBufferedReader(EnchantingPureTest.data("experiments.json"), StandardCharsets.UTF_8)) { ex = new Gson().fromJson(r, ExperimentSpec.class); }
        try (Reader r = Files.newBufferedReader(EnchantingPureTest.data("anvil_upgrades.json"), StandardCharsets.UTF_8)) { endcaps = new Gson().fromJson(r, AnvilRules.Endcap[].class); }
    }

    // ---- data -------------------------------------------------------------------------------------

    @Test void tierGatesAndFeesAreTheWikis() {
        String[] ids = {"beginner", "high", "grand", "supreme", "transcendent", "metaphysical"};
        int[] gates = {10, 20, 25, 30, 40, 50};
        long[] fees = {50_000, 75_000, 100_000, 200_000, 300_000, 400_000};
        for (int i = 0; i < ids.length; i++) {
            ExperimentSpec.Tier t = ex.tier(ids[i]);
            assertNotNull(t, ids[i]);
            assertEquals(gates[i], t.skill, ids[i] + " gate");
            assertEquals(fees[i], t.fee, ids[i] + " fee");
            assertEquals(i >= 3, t.ultraRareChance > 0, ids[i] + ": ultra-rares are Supreme and up only");
        }
        // 0.027% per item x 7 items at Metaphysical
        assertEquals(0.19, ex.tier("metaphysical").ultraRareChance, 0.005);
    }

    @Test void ultraRarePoolAndExtraRuns() {
        assertEquals(List.of("ENDSTONE_IDOL", "GOLDEN_BOUNTY", "GOLD_BOTTLE_CAP", "SEVERED_HAND", "TROUBLED_BUBBLE",
                "FATEFUL_STINGER", "PESTHUNTING_GUIDE"), ex.ultraRare);
        assertEquals(3, ex.extraRuns.size());
        assertEquals(50, ex.extraRun(0).levels);
        assertEquals(150, ex.extraRun(0).bits);
        assertEquals(100, ex.extraRun(1).levels);
        assertEquals(300, ex.extraRun(1).bits);
        assertEquals(200, ex.extraRun(2).levels);
        assertEquals(500, ex.extraRun(2).bits);
        assertNull(ex.extraRun(3));
    }

    // ---- charges ----------------------------------------------------------------------------------

    @Test void chargesBankUpToThreeAndRefillOnePerDay() {
        PlayerData d = new PlayerData();
        long t0 = 1_000_000L;
        ExperimentCharges.refresh(d, t0, 3, DAY);
        assertEquals(3, d.charges, "a new player starts full");
        assertEquals(0, d.chargeAt);
        assertTrue(ExperimentCharges.spend(d, t0, 3, DAY));
        assertTrue(ExperimentCharges.spend(d, t0 + 10, 3, DAY));
        assertTrue(ExperimentCharges.spend(d, t0 + 20, 3, DAY));
        assertFalse(ExperimentCharges.spend(d, t0 + 30, 3, DAY), "no fourth game");
        assertEquals(0, d.charges);
        // the clock started at the FIRST spend (bank was full) and does not restart on later spends
        assertEquals(t0 + DAY, d.chargeAt);
        ExperimentCharges.refresh(d, t0 + DAY - 1, 3, DAY);
        assertEquals(0, d.charges);
        ExperimentCharges.refresh(d, t0 + DAY, 3, DAY);
        assertEquals(1, d.charges, "one charge per 24 h");
        ExperimentCharges.refresh(d, t0 + 10 * DAY, 3, DAY);
        assertEquals(3, d.charges, "banked up to three, never more");
        assertEquals(0, d.chargeAt);
        assertEquals(0, ExperimentCharges.untilNext(d, t0 + 10 * DAY));
    }

    @Test void extraGamesOnlyWhenEmptyAndThreeADay() {
        PlayerData d = new PlayerData();
        ExperimentCharges.refresh(d, 0, 3, DAY);
        assertNull(ExperimentCharges.nextExtra(d, ex, 3), "charges left: nothing to buy");
        d.charges = 0;
        for (int i = 0; i < 3; i++) {
            ExperimentSpec.ExtraRun run = ExperimentCharges.nextExtra(d, ex, 3);
            assertNotNull(run, "extra " + i);
            assertSame(ex.extraRun(i), run, "each dearer than the last");
            ExperimentCharges.bought(d);
            assertEquals(1, d.charges);
            assertTrue(ExperimentCharges.spend(d, 5, 3, DAY));
        }
        assertNull(ExperimentCharges.nextExtra(d, ex, 3), "a fourth extra game is refused");
        assertNull(ExperimentCharges.nextExtra(d, ex, 0), "dial 0 turns extras off");
    }

    @Test void loweringTheMaximumClampsTheBank() {
        PlayerData d = new PlayerData();
        ExperimentCharges.refresh(d, 0, 3, DAY);
        ExperimentCharges.refresh(d, 0, 1, DAY);
        assertEquals(1, d.charges);
    }

    // ---- ultra-rare pool ------------------------------------------------------------------------------

    @Test void ultraRareChanceAndDeal() {
        ExperimentSpec.Tier meta = ex.tier("metaphysical"), grand = ex.tier("grand");
        assertEquals(0.0019, SuperpairsBoard.ultraRareChance(meta, 0, 1), 1e-9);
        assertEquals(0.0019 * 1.22, SuperpairsBoard.ultraRareChance(meta, 22, 1), 1e-9, "Lucky Seven 7% + Enderman 8 15%");
        assertEquals(0, SuperpairsBoard.ultraRareChance(grand, 100, 1000), "Grand never deals one");
        // forced to 100%: exactly one ultra-rare pair, matched by perfect play like any other pair
        List<String> pool = ex.ultraRare;
        double force = 1.0 / SuperpairsBoard.ultraRareChance(meta, 0, 1);
        SuperpairsBoard b = SuperpairsBoard.deal(meta, 1000, new Random(3), 0, pool, force);
        int ultra = 0;
        for (SuperpairsBoard.Tile t : b.tiles) if (t.reward != null && pool.contains(t.reward.item)) ultra++;
        assertEquals(2, ultra, "one pair");
        // statistical: ~0.19% of Metaphysical games
        Random rnd = new Random(11);
        int hits = 0, n = 200_000;
        for (int i = 0; i < n; i++) if (SuperpairsBoard.rollUltraRare(meta, rnd, 0, pool, 1) != null) hits++;
        assertEquals(0.0019, hits / (double) n, 0.0004);
        // no pool, no change to the board and no extra random draw
        SuperpairsBoard x = SuperpairsBoard.deal(meta, 10, new Random(5), 0), y = SuperpairsBoard.deal(meta, 10, new Random(5), 0, List.of(), 1);
        for (int i = 0; i < x.tiles.size(); i++) assertEquals(x.tiles.get(i).key(), y.tiles.get(i).key());
    }

    // ---- anvil: Endcap Upgrades -------------------------------------------------------------------------

    static AnvilRules.Endcap endcap(String item) {
        for (AnvilRules.Endcap e : endcaps) if (item.equals(e.item)) return e;
        return null;
    }

    @Test void everyEndcapResolves() {
        assertTrue(endcaps.length >= 7, "endcaps " + endcaps.length);
        for (String id : ex.ultraRare) assertNotNull(endcap(id), id + " has no anvil use");
        for (AnvilRules.Endcap e : endcaps) {
            EnchantSpec s = reg.spec(e.enchant);
            assertNotNull(s, e.item + " -> " + e.enchant);
            assertTrue(e.requires + 1 <= s.maxLevel, e.item + " raises " + e.enchant + " past its max");
        }
        assertEquals("ender_slayer", endcap("ENDSTONE_IDOL").enchant);
        assertEquals(6, endcap("ENDSTONE_IDOL").requires);
        assertEquals("scavenger", endcap("GOLDEN_BOUNTY").enchant);
        assertEquals(5, endcap("GOLDEN_BOUNTY").requires);
        assertEquals("pesterminator", endcap("PESTHUNTING_GUIDE").enchant);
    }

    @Test void endcapNeedsExactLevelAndOnlyOnce() {
        ItemDef sword = ItemDef.of("TEST_SWORD", "x", "STONE", Rarity.COMMON, ItemCategory.SWORD);
        ItemDef helm = ItemDef.of("TEST_HELM", "x", "STONE", Rarity.COMMON, ItemCategory.HELMET);
        AnvilRules.Endcap idol = endcap("ENDSTONE_IDOL");
        Map<String, Integer> six = new HashMap<>(Map.of("ender_slayer", 6, "sharpness", 5));
        AnvilRules.Result r = AnvilRules.endcap(reg, sword, six, idol, false);
        assertTrue(r.ok, r.reason);
        assertEquals(7, r.enchants.get("ender_slayer"));
        assertEquals(5, r.enchants.get("sharpness"), "other enchants untouched");
        assertFalse(AnvilRules.endcap(reg, sword, Map.of("ender_slayer", 5), idol, false).ok, "needs VI");
        assertFalse(AnvilRules.endcap(reg, sword, Map.of(), idol, false).ok, "needs the enchant at all");
        assertFalse(AnvilRules.endcap(reg, sword, six, idol, true).ok, "can be applied once");
        assertFalse(AnvilRules.endcap(reg, helm, Map.of("ender_slayer", 6), idol, false).ok, "weapon enchant on a helmet");
        assertTrue(AnvilRules.endcap(reg, helm, Map.of("pesterminator", 5), endcap("PESTHUNTING_GUIDE"), false).ok);
    }
}
