package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

/**
 * Fixtures are cut-down copies of the real payloads fetched on 2026-10-07 -
 * same field names, same nesting, real numbers for ENCHANTED_DIAMOND.
 */
class PriceDataTest {

    static final String BAZAAR = """
            {"success":true,"lastUpdated":1791391902745,"products":{
              "ENCHANTED_DIAMOND":{"product_id":"ENCHANTED_DIAMOND",
                "sell_summary":[{"amount":75864,"pricePerUnit":1268.6,"orders":2},{"amount":64,"pricePerUnit":1268.4,"orders":1}],
                "buy_summary":[{"amount":434,"pricePerUnit":1368.4,"orders":1},{"amount":136,"pricePerUnit":1368.5,"orders":1}],
                "quick_status":{"productId":"ENCHANTED_DIAMOND","sellPrice":1268.6000000000001,"sellVolume":2122399,
                  "sellMovingWeek":20420438,"sellOrders":69,"buyPrice":1389.8507737019872,"buyVolume":618417,
                  "buyMovingWeek":3999369,"buyOrders":126}},
              "ENCHANTMENT_ULTIMATE_WISE_5":{"product_id":"ENCHANTMENT_ULTIMATE_WISE_5",
                "sell_summary":[],"buy_summary":[],
                "quick_status":{"sellPrice":1900000.5,"buyPrice":2100000.0}},
              "DEAD_PRODUCT":{"product_id":"DEAD_PRODUCT","sell_summary":[],"buy_summary":[],
                "quick_status":{"sellPrice":0,"buyPrice":0}},
              "BROKEN":"not an object"
            }}""";

    static final String ITEMS = """
            {"success":true,"lastUpdated":1791302445914,"items":[
              {"material":"DIAMOND","name":"Enchanted Diamond","glowing":true,"tier":"UNCOMMON","npc_sell_price":1280,"id":"ENCHANTED_DIAMOND"},
              {"material":"INK_SACK","durability":3,"name":"Cocoa Beans","npc_sell_price":3,"id":"INK_SACK:3"},
              {"material":"PAPER","name":"\\u00a7dFairy Souls Pamphlet","id":"FAIRY_PAMPHLET"},
              {"material":"LEATHER_HELMET","name":"Salmon Hat","id":"SALMON_HAT"},
              {"material":"RAW_FISH","name":"Salmon Hat","npc_sell_price":5,"id":"SALMON_HAT_FOOD"},
              {"material":"DIAMOND_SWORD","name":"Hyperion","id":"HYPERION","npc_sell_price":250000},
              {"no_id":true}
            ]}""";

    @Test
    void bazaarUsesTopOfBookAndNamesTheSidesTheWayAPlayerDoes() {
        Map<String, PriceData.Bazaar> bz = PriceData.parseBazaar(BAZAAR);
        assertNotNull(bz);
        PriceData.Bazaar d = bz.get("ENCHANTED_DIAMOND");
        // buy_summary = sell offers: what you PAY to insta-buy (lowest offer).
        assertEquals(1368.4, d.instabuy(), 1e-9);
        // sell_summary = buy orders: what you GET to insta-sell (highest order).
        assertEquals(1268.6, d.instasell(), 1e-9);
    }

    @Test
    void emptyBookFallsBackToQuickStatus() {
        PriceData.Bazaar b = PriceData.parseBazaar(BAZAAR).get("ENCHANTMENT_ULTIMATE_WISE_5");
        assertEquals(2_100_000.0, b.instabuy(), 1e-9);
        assertEquals(1_900_000.5, b.instasell(), 1e-9);
        PriceData.Bazaar dead = PriceData.parseBazaar(BAZAAR).get("DEAD_PRODUCT");
        assertEquals(0, dead.instabuy());
        assertEquals(0, dead.instasell());
    }

    @Test
    void oneBrokenProductDoesNotCostTheRest() {
        Map<String, PriceData.Bazaar> bz = PriceData.parseBazaar(BAZAAR);
        assertEquals(3, bz.size());
    }

    @Test
    void badBazaarPayloadsAreNullNeverAThrow() {
        assertNull(PriceData.parseBazaar("{\"success\":false,\"cause\":\"Key throttle\"}"));
        assertNull(PriceData.parseBazaar("<html>525 SSL handshake failed</html>"));
        assertNull(PriceData.parseBazaar(""));
        assertNull(PriceData.parseBazaar("{\"success\":true,\"products\":"));
    }

    @Test
    void lowestBinIsAFlatObject() {
        Map<String, Double> lb = PriceData.parseLowestBin(
                "{\"HYPERION\":442000000,\"LVL_100_LEGENDARY_PHOENIX\":105000000,\"ZERO\":0,\"STR\":\"x\",\"ZAP_RUNE_3\":88000}");
        assertEquals(3, lb.size());
        assertEquals(442_000_000, lb.get("HYPERION"), 1e-9);
        assertNull(lb.get("ZERO"));
        assertNull(PriceData.parseLowestBin("{}"));
        assertNull(PriceData.parseLowestBin("[1,2]"));
        assertNull(PriceData.parseLowestBin("error code: 525"));
    }

    @Test
    void itemsGiveNpcPricesAndAColourFreeNameIndex() {
        PriceData.Items it = PriceData.parseItems(ITEMS);
        assertNotNull(it);
        assertEquals(1280, it.npc().get("ENCHANTED_DIAMOND"), 1e-9);
        assertEquals(3, it.npc().get("INK_SACK:3"), 1e-9);
        assertNull(it.npc().get("FAIRY_PAMPHLET"));
        PriceData d = PriceData.EMPTY.withItems(it, 1);
        assertEquals("INK_SACK:3", d.idForName("Cocoa Beans"));
        assertEquals("FAIRY_PAMPHLET", d.idForName("Fairy Souls Pamphlet"));
        assertEquals("ENCHANTED_DIAMOND", d.idForName("§aenchanted diamond "));
        assertNull(d.idForName("Nonexistent Thing"));
        assertNull(PriceData.parseItems("{\"success\":false}"));
    }

    @Test
    void aSharedNamePrefersTheItemThatActuallyTrades() {
        // Live 2026-10-08: "Enchanted Hay Bale" = ENCHANTED_HAY_BALE (~150k) and the legacy
        // ENCHANTED_HAY_BLOCK, still a bazaar key with no orders.
        PriceData d = PriceData.EMPTY
                .withItems(new PriceData.Items(Map.of(),
                        Map.of("enchanted hay bale", java.util.List.of("ENCHANTED_HAY_BLOCK", "ENCHANTED_HAY_BALE"),
                                "hay bale", java.util.List.of("HAY_BALE", "HAY_BLOCK"))), 1)
                .withBazaar(Map.of(
                        "ENCHANTED_HAY_BLOCK", new PriceData.Bazaar(0, 0),
                        "ENCHANTED_HAY_BALE", new PriceData.Bazaar(152_998.9, 150_999.0),
                        "HAY_BLOCK", new PriceData.Bazaar(0, 0)), 1);
        assertEquals("ENCHANTED_HAY_BALE", d.idForName("Enchanted Hay Bale"));
        assertEquals("HAY_BLOCK", d.idForName("Hay Bale"));   // a bare bazaar key still beats furniture
    }

    @Test
    void namesWithModernIconGlyphsStillResolve() {
        // Hypixel's modern font puts a private-use icon before gemstone names; the items API has none.
        PriceData d = PriceData.EMPTY.withItems(new PriceData.Items(Map.of(),
                Map.of("rough ruby gemstone", java.util.List.of("ROUGH_RUBY_GEM"))), 1);
        assertEquals("ROUGH_RUBY_GEM", d.idForName("\uE010 Rough Ruby Gemstone"));
        assertEquals("ROUGH_RUBY_GEM", d.idForName("§c\u2764 Rough Ruby Gemstone"));
        assertEquals("ROUGH_RUBY_GEM", d.idForName("Rough\u00A0Ruby  Gemstone"));
    }

    @Test
    void duplicateNamesPreferTheBazaarProduct() {
        PriceData.Items it = PriceData.parseItems(ITEMS);
        PriceData noBz = PriceData.EMPTY.withItems(it, 1);
        assertEquals("SALMON_HAT", noBz.idForName("Salmon Hat"));
        PriceData withBz = noBz.withBazaar(Map.of("SALMON_HAT_FOOD", new PriceData.Bazaar(6, 5)), 1);
        assertEquals("SALMON_HAT_FOOD", withBz.idForName("Salmon Hat"));
    }

    @Test
    void cacheRoundTripsEverything() {
        PriceData d = PriceData.EMPTY
                .withBazaar(PriceData.parseBazaar(BAZAAR), 111)
                .withLbin(Map.of("HYPERION", 4.42e8), 222, "hysky.de")
                .withItems(PriceData.parseItems(ITEMS), 333);
        PriceData back = PriceData.fromCacheJson(d.toCacheJson());
        assertEquals(111, back.bazaarAt());
        assertEquals(222, back.lbinAt());
        assertEquals(333, back.itemsAt());
        assertEquals("hysky.de", back.lbinSource());
        assertEquals(1368.4, back.bazaar("ENCHANTED_DIAMOND").instabuy(), 1e-9);
        assertEquals(4.42e8, back.lbin("HYPERION"), 1e-9);
        assertEquals(250_000, back.npc("HYPERION"), 1e-9);
        assertEquals("INK_SACK:3", back.idForName("Cocoa Beans"));
        assertEquals("Cocoa Beans", back.nameForId("INK_SACK:3"));
        assertEquals("Fairy Souls Pamphlet", back.nameForId("FAIRY_PAMPHLET"));
    }

    @Test
    void aCacheFromBeforeTheDisplayNamesStillLoads() {
        PriceData d = PriceData.EMPTY.withItems(PriceData.parseItems(ITEMS), 333);
        String old = d.toCacheJson().replaceAll(",\"display\":\\{[^}]*\\}", "");
        PriceData back = PriceData.fromCacheJson(old);
        assertEquals("INK_SACK:3", back.idForName("Cocoa Beans"));
        assertNull(back.nameForId("INK_SACK:3"));
    }

    @Test
    void unreadableCacheIsEmpty() {
        assertSame(PriceData.EMPTY, PriceData.fromCacheJson("{\"v\":1"));
        assertSame(PriceData.EMPTY, PriceData.fromCacheJson(""));
        assertTrue(PriceData.EMPTY.isEmpty());
        assertTrue(Double.isNaN(PriceData.EMPTY.lbin("X")));
    }

    @Test
    void gzipBodiesAreDecodedWhetherOrNotTheHeaderSaysSo() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write("{\"A\":1}".getBytes(StandardCharsets.UTF_8));
        }
        byte[] zipped = bos.toByteArray();
        assertEquals("{\"A\":1}", PriceService.decode(zipped, true));
        assertEquals("{\"A\":1}", PriceService.decode(zipped, false));
        assertEquals("plain", PriceService.decode("plain".getBytes(StandardCharsets.UTF_8), false));
    }
}
