package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PricingTest {

    private static final PriceData DATA = PriceData.EMPTY
            .withBazaar(Map.of(
                    "ENCHANTED_DIAMOND", new PriceData.Bazaar(1368.4, 1268.6),
                    "ENCHANTMENT_ULTIMATE_WISE_5", new PriceData.Bazaar(2_100_000, 1_900_000)), 1)
            .withLbin(Map.of(
                    "HYPERION", 442_000_000.0,
                    "LVL_1_LEGENDARY_PHOENIX", 40_000_000.0,
                    "LVL_100_LEGENDARY_PHOENIX", 105_000_000.0,
                    "LVL_1_EPIC_PHOENIX", 20_000_000.0,
                    "ZAP_RUNE_3", 88_000.0,
                    "NEW_YEAR_CAKE_330", 1_500_000.0,
                    "ABSORPTION_POTION_2_ENHANCED", 900.0), 1, "hysky.de")
            .withItems(new PriceData.Items(Map.of("ENCHANTED_DIAMOND", 1280.0, "HYPERION", 250_000.0, "WOOD_SWORD", 1.0),
                    Map.of()), 1);

    private static SbItem pet(String petInfo, String name) {
        return new SbItem("PET", null, petInfo, null, 0, null, 0, false, false, false, name, 1);
    }

    private static SbItem book(Map<String, Integer> ench) {
        return new SbItem("ENCHANTED_BOOK", ench, null, null, 0, null, 0, false, false, false, "Enchanted Book", 1);
    }

    @Test
    void singleEnchantBookIsItsBazaarProduct() {
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", Pricing.bazaarId(book(Map.of("ultimate_wise", 5))));
        Map<String, Integer> two = new LinkedHashMap<>();
        two.put("sharpness", 6);
        two.put("smite", 6);
        assertNull(Pricing.bazaarId(book(two)), "a two-enchant book has no single price");
        assertNull(Pricing.bazaarId(book(Map.of())));
        Pricing.Quote q = Pricing.quote(DATA, book(Map.of("ultimate_wise", 5)));
        assertEquals(1_900_000, q.bazaar().instasell(), 1e-9);
    }

    @Test
    void plainItemsUseTheirOwnId() {
        Pricing.Quote q = Pricing.quote(DATA, SbItem.of("ENCHANTED_DIAMOND", 64));
        assertEquals(1368.4, q.bazaar().instabuy(), 1e-9);
        assertEquals(1280, q.npc(), 1e-9);
        assertEquals(64, q.count());
        Pricing.Quote h = Pricing.quote(DATA, SbItem.of("HYPERION", 1));
        assertNull(h.bazaar());
        assertEquals(442_000_000, h.lbin(), 1e-9);
        assertTrue(Pricing.quote(DATA, SbItem.of("NOTHING_KNOWN", 1)).isEmpty());
    }

    @Test
    void petsPriceByRarityAndTypeInLevelBrackets() {
        String info = "{\"type\":\"PHOENIX\",\"active\":false,\"exp\":0.0,\"tier\":\"LEGENDARY\",\"hideInfo\":false,\"candyUsed\":0}";
        Pricing.Quote q = Pricing.quote(DATA, pet(info, "[Lvl 57] Phoenix"));
        assertEquals(List.of(new Pricing.PetPrice(1, 40_000_000), new Pricing.PetPrice(100, 105_000_000)), q.petPrices());
        // A level 57 pet is worth the level-1 floor, never the level-100 price.
        assertEquals(40_000_000, Pricing.unitValue(DATA, pet(info, "[Lvl 57] Phoenix")), 1e-9);
        assertEquals(105_000_000, Pricing.unitValue(DATA, pet(info, "[Lvl 100] Phoenix")), 1e-9);
    }

    @Test
    void tierBoostedPetIsPricedAtItsRealRarity() {
        String boosted = "{\"type\":\"PHOENIX\",\"tier\":\"LEGENDARY\",\"heldItem\":\"PET_ITEM_TIER_BOOST\"}";
        Pricing.Pet p = Pricing.pet(boosted);
        assertEquals("EPIC", p.tier());
        assertEquals(20_000_000, Pricing.quote(DATA, pet(boosted, "[Lvl 1] Phoenix")).petPrices().get(0).price(), 1e-9);
    }

    @Test
    void coflnetPetKeysAreTheFallback() {
        PriceData cofl = PriceData.EMPTY.withLbin(Map.of("PHOENIX;4", 99.0), 1, "coflnet");
        List<Pricing.PetPrice> pp = Pricing.petPrices(cofl, new Pricing.Pet("PHOENIX", "LEGENDARY", null));
        assertEquals(List.of(new Pricing.PetPrice(0, 99)), pp);
    }

    @Test
    void badPetInfoIsNoPetNotACrash() {
        assertNull(Pricing.pet(null));
        assertNull(Pricing.pet("{not json"));
        assertNull(Pricing.pet("{\"tier\":\"RARE\"}"));
        assertTrue(Pricing.quote(DATA, pet("garbage", "x")).isEmpty());
    }

    @Test
    void petLevelComesFromTheName() {
        assertEquals(57, Pricing.petLevel("[Lvl 57] Phoenix"));
        assertEquals(200, Pricing.petLevel("[Lvl 200] Golden Dragon"));
        assertEquals(0, Pricing.petLevel("Phoenix"));
        assertEquals(0, Pricing.petLevel(null));
    }

    @Test
    void specialIdsGetTheirSpecialKeys() {
        SbItem rune = new SbItem("RUNE", null, null, Map.of("ZAP", 3), 0, null, 0, false, false, false, "", 1);
        assertEquals(List.of("ZAP_RUNE_3", "RUNE_ZAP_3"), Pricing.lbinKeys(rune));
        assertEquals(88_000, Pricing.quote(DATA, rune).lbin(), 1e-9);

        SbItem cake = new SbItem("NEW_YEAR_CAKE", null, null, null, 330, null, 0, false, false, false, "", 1);
        assertEquals(1_500_000, Pricing.quote(DATA, cake).lbin(), 1e-9);

        SbItem potion = new SbItem("POTION", null, null, null, 0, "absorption", 2, true, false, false, "", 1);
        assertEquals(List.of("ABSORPTION_POTION_2_ENHANCED"), Pricing.lbinKeys(potion));
        assertEquals(900, Pricing.quote(DATA, potion).lbin(), 1e-9);
    }

    @Test
    void incomeValuePrefersInstaSellThenBinThenNpc() {
        assertEquals(1268.6, Pricing.unitValue(DATA, SbItem.of("ENCHANTED_DIAMOND", 1)), 1e-9);
        assertEquals(442_000_000, Pricing.unitValue(DATA, SbItem.of("HYPERION", 1)), 1e-9);
        assertEquals(1, Pricing.unitValue(DATA, SbItem.of("WOOD_SWORD", 1)), 1e-9);
        assertTrue(Double.isNaN(Pricing.unitValue(DATA, SbItem.of("SKYBLOCK_MENU", 1))));
    }

    @Test
    void valueKeysSeparateWhatIsDifferentAndJoinWhatIsNot() {
        assertEquals("ENCHANTED_DIAMOND", Pricing.valueKey(SbItem.of("ENCHANTED_DIAMOND", 5)));
        assertEquals("ENCHANTMENT_ULTIMATE_WISE_5", Pricing.valueKey(book(Map.of("ultimate_wise", 5))));
        String info = "{\"type\":\"PHOENIX\",\"tier\":\"LEGENDARY\"}";
        assertEquals("PET:PHOENIX:LEGENDARY:57", Pricing.valueKey(pet(info, "[Lvl 57] Phoenix")));
        assertNull(Pricing.valueKey(null));
    }
}
