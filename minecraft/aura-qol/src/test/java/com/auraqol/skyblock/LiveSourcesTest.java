package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Hits the REAL endpoints and checks the assumptions the parsers and the key
 * rules make about them. Off by default (a build must not depend on the
 * network); run with {@code gradlew test -Plive=true}.
 *
 * <p>This is the test that would catch Hypixel or hysky changing a field
 * name, or a source going dark - the fixture tests cannot.
 */
@EnabledIfSystemProperty(named = "auraqol.live", matches = "true")
class LiveSourcesTest {

    @Test
    void bazaarIsLiveAndTheSidesAreTheRightWayRound() {
        String body = PriceService.get(PriceService.BAZAAR_URL);
        assertNotNull(body, "bazaar endpoint unreachable");
        Map<String, PriceData.Bazaar> bz = PriceData.parseBazaar(body);
        assertNotNull(bz);
        assertTrue(bz.size() > 1000, "only " + bz.size() + " products");
        PriceData.Bazaar d = bz.get("ENCHANTED_DIAMOND");
        assertNotNull(d);
        // The spread: you always pay more to insta-buy than you get to insta-sell.
        assertTrue(d.instabuy() >= d.instasell(), d.toString());
        assertTrue(bz.containsKey("ENCHANTMENT_ULTIMATE_WISE_5"), "book product ids changed");
        System.out.println("[live] bazaar " + bz.size() + " products, E.DIAMOND " + d);
    }

    @Test
    void itemsGiveNpcPricesAndNames() {
        String body = PriceService.get(PriceService.ITEMS_URL);
        assertNotNull(body, "items endpoint unreachable");
        PriceData.Items it = PriceData.parseItems(body);
        assertNotNull(it);
        assertEquals(1280, it.npc().get("ENCHANTED_DIAMOND"), 1e-9);
        PriceData d = PriceData.EMPTY.withItems(it, 1);
        assertEquals("ENCHANTED_DIAMOND", d.idForName("Enchanted Diamond"));
        assertEquals("ENCHANTED_COBBLESTONE", d.idForName("Enchanted Cobblestone"));
        assertEquals("INK_SACK:3", d.idForName("Cocoa Beans"));
        System.out.println("[live] items: " + it.npc().size() + " npc prices, " + it.names().size() + " names");
    }

    @Test
    void dropNamesAndEverySackFamilyIdExist() {
        PriceData.Items it = PriceData.parseItems(PriceService.get(PriceService.ITEMS_URL));
        assertNotNull(it, "items endpoint unreachable");
        PriceData d = PriceData.EMPTY.withItems(it, 1);
        // The names the chat drop lines use (MoneyChat) must resolve to ids.
        assertEquals("OVERCLOCKER_3000", d.idForName("Overclocker 3000"));
        assertEquals("VINYL_PRETTY_FLY", d.idForName("Pretty Fly Vinyl"));
        assertEquals("DUNG", d.idForName("Dung"));
        assertEquals("ROUGH_RUBY_GEM", d.idForName("Rough Ruby Gemstone"));
        // Every id in the Sacks panel's family table is a real item (a typo would never show a count).
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (SackFamilies.Family f : SackFamilies.all()) {
            for (SackFamilies.Member m : f.members()) {
                if (!it.display().containsKey(m.id())) {
                    missing.add(m.id());
                }
            }
        }
        System.out.println("[live] sack family ids not in the items API: " + missing);
        assertTrue(missing.isEmpty(), "unknown ids: " + missing);
        // ...and the NAME a [Sacks] hover shows for each one maps back to that same id once the
        // bazaar is loaded (two items can share a name: "Enchanted Hay Bale" is also a dead legacy id).
        PriceData full = d.withBazaar(PriceData.parseBazaar(PriceService.get(PriceService.BAZAAR_URL)), 1);
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (SackFamilies.Family f : SackFamilies.all()) {
            for (SackFamilies.Member m : f.members()) {
                String back = full.idForName(it.display().get(m.id()));
                if (!m.id().equals(back)) {
                    wrong.add(m.id() + "->" + back);
                }
            }
        }
        System.out.println("[live] family names that resolve to another id: " + wrong);
        assertTrue(wrong.isEmpty(), "name round-trip: " + wrong);
    }

    @Test
    void hyskyLowestBinHasTheKeyShapesPricingExpects() {
        String body = PriceService.get(PriceService.LBIN_URL);
        assertNotNull(body, "hysky lowest-BIN unreachable");
        Map<String, Double> lb = PriceData.parseLowestBin(body);
        assertNotNull(lb);
        assertTrue(lb.size() > 2000, "only " + lb.size());
        assertTrue(lb.containsKey("HYPERION"));
        assertTrue(lb.keySet().stream().anyMatch(k -> k.startsWith("LVL_100_LEGENDARY_")), "pet bracket keys changed");
        assertTrue(lb.keySet().stream().anyMatch(k -> k.startsWith("LVL_1_")), "pet bracket keys changed");
        assertTrue(lb.keySet().stream().anyMatch(k -> k.matches("[A-Z_]+_RUNE_[123]")), "rune keys changed");
        assertTrue(lb.keySet().stream().anyMatch(k -> k.matches("NEW_YEAR_CAKE_\\d+")), "cake keys changed");
        PriceData d = PriceData.EMPTY.withLbin(lb, 1, "hysky.de");
        assertFalse(Pricing.petPrices(d, new Pricing.Pet("PHOENIX", "LEGENDARY", null)).isEmpty());
        System.out.println("[live] hysky " + lb.size() + " items, HYPERION " + lb.get("HYPERION"));
    }

    @Test
    void coflnetFallbackIsLiveWithNeuPetKeys() {
        String body = PriceService.get(PriceService.LBIN_FALLBACK_URL);
        assertNotNull(body, "coflnet unreachable");
        Map<String, Double> lb = PriceData.parseLowestBin(body);
        assertNotNull(lb);
        assertTrue(lb.size() > 1000, "only " + lb.size());
        assertTrue(lb.keySet().stream().anyMatch(k -> k.matches("[A-Z_]+;[0-5]")), "NEU pet keys changed");
        System.out.println("[live] coflnet " + lb.size() + " items");
    }

    @Test
    void wholeSnapshotCachesSmall() {
        PriceData d = PriceData.EMPTY
                .withBazaar(PriceData.parseBazaar(PriceService.get(PriceService.BAZAAR_URL)), 1)
                .withLbin(PriceData.parseLowestBin(PriceService.get(PriceService.LBIN_URL)), 1, "hysky.de")
                .withItems(PriceData.parseItems(PriceService.get(PriceService.ITEMS_URL)), 1);
        String cache = d.toCacheJson();
        PriceData back = PriceData.fromCacheJson(cache);
        assertEquals(d.bazaarSize(), back.bazaarSize());
        assertEquals(d.lbinSize(), back.lbinSize());
        assertEquals(d.npcSize(), back.npcSize());
        System.out.println("[live] cache " + cache.length() / 1024 + " KB");
    }
}
