package com.skyclone.mod.garden;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lanes 9 / 12 / 13 of the real-game audit (2026-10-06): the pest economy (G1-G4, G7, G8, G9, G14), the Green
 * Bandana, Sowdust and chip levels. Pure: the numbers and the generated data files, no server.
 */
class GardenLane9Test {

    private static GardenTables t() { return GardenPureTest.tables(); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> json(String name) {
        InputStream in = GardenLane9Test.class.getResourceAsStream("/data/garden/" + name);
        assertNotNull(in, name);
        return new com.google.gson.Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), List.class);
    }

    @Test
    void pestCropDropIsBasePlusFortuneOverScale() {
        // wiki: "At 2,520 Farming Fortune, Fly drops 49 Enchanted Wheat"
        GardenTables.Pest fly = t().pest("fly");
        assertEquals(49, EffectMath.pestEnchantedCrops(fly.cropBase, fly.cropScale, 2520, 1, 0.99));
        // research farming_passive 1.3 at 1,200 fortune: Cricket 3 + 1200/15.75 = 79.2 (x160 x3 coins = 38.0k)
        GardenTables.Pest cricket = t().pest("cricket");
        assertEquals(79, EffectMath.pestEnchantedCrops(cricket.cropBase, cricket.cropScale, 1200, 1, 0.99));
        assertEquals(80, EffectMath.pestEnchantedCrops(cricket.cropBase, cricket.cropScale, 1200, 1, 0.01));
        // the fraction is a chance: the average over many rolls is exact
        double sum = 0;
        for (int i = 0; i < 1000; i++) sum += EffectMath.pestEnchantedCrops(3, 18, 1200, 1, (i + 0.5) / 1000.0);
        assertEquals(3 + 1200 / 18.0, sum / 1000, 0.01);
        Map<String, double[]> rows = Map.of("fly", new double[]{1, 52.5}, "rat", new double[]{1, 52.5}, "cricket", new double[]{3, 15.75},
                "locust", new double[]{3, 15.75}, "beetle", new double[]{3, 18}, "moth", new double[]{3, 18}, "earthworm", new double[]{5, 10.5},
                "mite", new double[]{2, 26.25}, "mosquito", new double[]{2, 26.25}, "firefly", new double[]{2, 26.25});
        for (Map.Entry<String, double[]> e : rows.entrySet()) {
            GardenTables.Pest p = t().pest(e.getKey());
            assertEquals(e.getValue()[0], p.cropBase, 1e-9, e.getKey());
            assertEquals(e.getValue()[1], p.cropScale, 1e-9, e.getKey());
        }
        // the Field Mouse borrows its random crop's row
        assertEquals(10.5, t().garden.pests.cropRows.get("MELON").scale, 1e-9);
        assertEquals(0, EffectMath.pestEnchantedCrops(0, 0, 5000, 1, 0));
    }

    @Test
    void pestDropTables() {
        GardenTables tt = t();
        Map<String, Map<String, Object>> mobs = new HashMap<>();
        for (Map<String, Object> m : json("mobs.json")) mobs.put((String) m.get("id"), m);
        for (GardenTables.Pest p : tt.pests()) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> drops = (List<Map<String, Object>>) mobs.get(p.mob).get("drops");
            double rare = 0, compacted = 0;
            for (Map<String, Object> d : drops) {
                double ch = ((Number) d.get("chance")).doubleValue();
                if (d.get("item").equals(p.rareCrop)) rare = ch;
                if (((Number) d.get("min")).intValue() >= 3 && ch < 0.01) compacted += ch;
                assertFalse(d.get("item").equals("PESTHUNTING_GUIDE"), p.id + " drops a Guide (Phillip sells it)");
            }
            boolean real = p.rareCrop != null && !p.rareCrop.equals("RANDOM");
            if (real) {
                assertEquals(0.15, rare, 1e-9, p.id + " rare crop (G2)");
                assertEquals(0.0075, compacted, 1e-9, p.id + " compacted (G3)");
            }
            double coins = ((Number) mobs.get(p.mob).get("coins")).doubleValue();
            assertEquals(p.id.equals("field_mouse") ? 10_000 : 1_000, coins, 1e-9, p.id + " coins");
        }
        assertEquals(13, tt.garden.pests.rareCrops.size());
        assertEquals("RANDOM", tt.pest("field_mouse").rareCrop);
        assertEquals(0.30, tt.pest("field_mouse").rareChance, 1e-9);
        assertEquals(4, tt.pest("field_mouse").xpMult, 1e-9);
        // G8: the Firefly is the only night-only pest
        for (GardenTables.Pest p : tt.pests()) assertEquals(p.id.equals("firefly"), p.nightOnly, p.id);
    }

    @Test
    void sprayersVacuumsAndShops() {
        GardenTables tt = t();
        assertEquals(1, tt.sprayer("SPRAYONATOR").uses);
        assertEquals(3, tt.sprayer("JUICY_SPRAYONATOR").uses);
        assertEquals(5, tt.sprayer("SALTY_SPRAYONATOR").uses);
        assertEquals(75, tt.sprayer("SALTY_SPRAYONATOR").bpc, 1e-9);
        // no vacuum gives Bonus Pest Chance (NEU lore; the Hooverius is +25 FF / 400 damage only)
        for (GardenTables.Vacuum v : tt.garden.pests.vacuums) assertEquals(0, v.bpc, 1e-9, v.id);
        Map<String, GardenTables.ShopEntry> shop = new HashMap<>();
        for (GardenTables.ShopEntry e : tt.shop) shop.put(e.item, e);
        assertFalse(shop.containsKey("PEST_TRAP"), "G7: the Pest Trap is the Pestwarden's, for Pests");
        assertEquals(1000, shop.get("JUICY_SPRAYONATOR").price, 1e-9);
        assertEquals(Map.of("SPRAYONATOR", 1, "JUICY_NOZZLE", 1), shop.get("JUICY_SPRAYONATOR").needs);
        assertEquals(10000, shop.get("SALTY_SPRAYONATOR").price, 1e-9);
        assertEquals(Map.of("JUICY_SPRAYONATOR", 1, "SALTY_NOZZLE", 1), shop.get("SALTY_SPRAYONATOR").needs);
        // Copper Talisman line 5,000 / +20,000 / +50,000
        assertEquals(5000, shop.get("COPPER_TALISMAN").price, 1e-9);
        assertEquals(20000, shop.get("COPPER_RING").price, 1e-9);
        assertEquals(Map.of("COPPER_TALISMAN", 1), shop.get("COPPER_RING").needs);
        assertEquals(50000, shop.get("COPPER_ARTIFACT").price, 1e-9);
        assertEquals(Map.of("COPPER_RING", 1), shop.get("COPPER_ARTIFACT").needs);
        // the Pestwarden (Phillip): wiki prices in Pests
        Map<String, GardenTables.PestShopEntry> ph = new HashMap<>();
        for (GardenTables.PestShopEntry e : tt.garden.pesthunterShop) ph.put(e.item, e);
        assertEquals(50, ph.get("PEST_TRAP").pests);
        assertEquals(75, ph.get("MOUSE_PEST_TRAP").pests);
        assertEquals(Map.of("PEST_TRAP", 1), ph.get("MOUSE_PEST_TRAP").tradeIn);
        assertEquals(200, ph.get("VERMIN_PEST_TRAP").pests);
        assertEquals(500, ph.get("PESTHUNTING_GUIDE").pests);
        assertEquals(500, ph.get("HEDGEHOG;4").pests);
        assertEquals(40, ph.get("PESTHUNTERS_CLOAK").pests);
        assertEquals(375, ph.get("PESTHUNTER_BADGE").pests + ph.get("PESTHUNTER_RING").pests + ph.get("PESTHUNTER_ARTIFACT").pests
                + ph.get("PESTHUNTER_RELIC").pests, "the Relic chain is 375 Pests (garden_max 3.3)");
        assertEquals(1500, ph.get("skin:pestwarden_lair").pests);
        assertFalse(ph.containsKey("PEST_VEST"), "the Pest Vest is Talbot's (Pelts), not Phillip's");
        // Pesthunter's Bonus: 5 FF a Pest, at most 40 Pests = 200 FF
        assertEquals(40, EffectMath.pestsToHandIn(500, 40));
        assertEquals(12, EffectMath.pestsToHandIn(12, 40));
        assertEquals(200, EffectMath.pesthunterBonus(40, 5, 200), 1e-9);
        assertEquals(60, EffectMath.pesthunterBonus(12, 5, 200), 1e-9);
    }

    @Test
    void chipsLevelWithSowdust() {
        GardenTables.ChipRules r = t().garden.chipRules;
        // wiki: 100k for level 2 ... 2.65M for level 20; cumulative 5.25M to L10, 13.25M to L15, 25M to L20
        assertEquals(100_000, EffectMath.chipLevelCost(1, r.sowdustCost));
        assertEquals(2_650_000, EffectMath.chipLevelCost(19, r.sowdustCost));
        assertEquals(-1, EffectMath.chipLevelCost(20, r.sowdustCost));
        assertEquals(5_250_000, EffectMath.chipCostTo(10, r.sowdustCost));
        assertEquals(13_250_000, EffectMath.chipCostTo(15, r.sowdustCost));
        assertEquals(25_000_000, EffectMath.chipCostTo(20, r.sowdustCost));
        // rarity: 1 chip Rare, 5 Epic, 21 Legendary; caps 10 / 15 / 20
        assertEquals(-1, EffectMath.chipRarity(0, r.chipsFor));
        assertEquals(0, EffectMath.chipRarity(4, r.chipsFor));
        assertEquals(1, EffectMath.chipRarity(5, r.chipsFor));
        assertEquals(1, EffectMath.chipRarity(20, r.chipsFor));
        assertEquals(2, EffectMath.chipRarity(21, r.chipsFor));
        assertEquals(0, EffectMath.chipCap(-1, r.caps));
        assertEquals(15, EffectMath.chipCap(1, r.caps));
        // per-rarity values: a chip's value at each rarity's cap (wiki)
        Map<String, double[]> atCap = Map.of("VERMIN_VAPORIZER_GARDEN_CHIP", new double[]{30, 60, 100}, "SYNTHESIS_GARDEN_CHIP", new double[]{10, 22.5, 40},
                "SOWLEDGE_GARDEN_CHIP", new double[]{10, 18.75, 30}, "OVERDRIVE_GARDEN_CHIP", new double[]{50, 90, 140},
                "EVERGREEN_GARDEN_CHIP", new double[]{20, 37.5, 60}, "RAREFINDER_GARDEN_CHIP", new double[]{15, 30, 50});
        for (Map.Entry<String, double[]> e : atCap.entrySet()) {
            GardenTables.Chip c = t().garden.chips.get(e.getKey());
            for (int i = 0; i < 3; i++) assertEquals(e.getValue()[i], c.perAt(i) * r.caps[i], 1e-9, e.getKey() + " rarity " + i);
        }
        // an old save (one chip = one level) reads its level as the chips that went in
        GardenData d = new GardenData();
        d.chips.put("CROPSHOT_GARDEN_CHIP", 7);
        assertEquals(7, d.redeemed("CROPSHOT_GARDEN_CHIP"));
        assertEquals(1, EffectMath.chipRarity(d.redeemed("CROPSHOT_GARDEN_CHIP"), r.chipsFor));
    }

    @Test
    void sowdustFormulas() {
        // ceil(FF / 100) a block, halved for Sugar Cane and Cactus, +% boosts
        assertEquals(13, EffectMath.cropSowdust(1250, false, 0), 1e-9);
        assertEquals(6.5, EffectMath.cropSowdust(1250, true, 0), 1e-9);
        assertEquals(13 * 1.25, EffectMath.cropSowdust(1250, false, 25), 1e-9);     // Elephant 20% + Earthy 5%
        assertEquals(1, EffectMath.cropSowdust(0.5, false, 0), 1e-9);
        // greenhouse: 5 x round(base / 5 x mult) x (1 + FF/100)
        assertEquals(5 * Math.round(175 / 5.0 * 3.5) * 13.0, EffectMath.greenhouseSowdust(175, 3.5, 1200, 0), 1e-6);
        GardenTables.Sowdust s = t().garden.sowdust;
        assertTrue(s.halved.contains("SUGAR_CANE") && s.halved.contains("CACTUS") && s.halved.size() == 2);
        assertEquals(13, s.greenhouseMult.size());
        assertEquals(5, s.earthyPct, 1e-9);
    }

    @Test
    void greenhouseSquashAndAnalyzer() {
        GardenTables tt = t();
        double[] pct = tt.garden.greenhouseSquash.pct;
        assertEquals(0, EffectMath.greenhouseSquash(1, pct), 1e-9);          // pieces required: 2+
        assertEquals(0.04, EffectMath.greenhouseSquash(2, pct), 1e-9);
        assertEquals(0.08, EffectMath.greenhouseSquash(3, pct), 1e-9);
        assertEquals(0.12, EffectMath.greenhouseSquash(4, pct), 1e-9);
        assertEquals("SQUASH", tt.garden.greenhouseSquash.item);
        assertEquals(List.of(1, 10, 15, 20, 30, 40), java.util.Arrays.stream(tt.greenhouse.analyzerLevels).boxed().toList());
        assertEquals(1, GardenMath.analyzerLevel(1, tt.greenhouse.analyzerLevels));
        assertEquals(6, GardenMath.analyzerLevel(40, tt.greenhouse.analyzerLevels));
        assertEquals(750, tt.mutation("SOGGYBUD").copper);
        assertEquals(250, tt.mutation("LONELILY").copper);
        assertEquals(15, tt.greenhouse.analyzerReward.skyblockXp);
    }

    @Test
    void greenBandanaIsRegistered() {
        Map<String, Object> bandana = null;
        for (Map<String, Object> it : json("items.json")) if ("GREEN_BANDANA".equals(it.get("id"))) bandana = it;
        assertNotNull(bandana, "GREEN_BANDANA must be a garden item (lane 12)");
        assertEquals("PET_ITEM", bandana.get("category"));
        assertEquals("EPIC", bandana.get("rarity"));
        @SuppressWarnings("unchecked") Map<String, Object> extra = (Map<String, Object>) bandana.get("extra");
        assertEquals(4.0, ((Number) extra.get("fortunePerGardenLevel")).doubleValue(), 1e-9);
    }
}
