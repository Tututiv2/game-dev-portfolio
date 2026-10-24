package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The 2026-10-08 Hypixel report: claims counted as income, drops not counted.
 * Each test is one situation from a real Garden session.
 */
class IncomeEngineClaimsTest {

    private static final long S = 1000;

    private record Ev(String channel, String what, double value, boolean counted, String rule) {
    }

    private final List<Ev> log = new ArrayList<>();

    private IncomeEngine engine() {
        IncomeEngine e = new IncomeEngine(new RateTracker(60 * S), 3 * S, 90 * S);
        e.setListener((now, channel, what, value, counted, rule) -> log.add(new Ev(channel, what, value, counted, rule)));
        e.rate().activity(0);
        return e;
    }

    private static Map<String, IncomeEngine.Holding> inv(Object... kv) {
        Map<String, IncomeEngine.Holding> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.put((String) kv[i], new IncomeEngine.Holding((Integer) kv[i + 1], (Double) kv[i + 2]));
        }
        return m;
    }

    @Test
    void claimingABuyOrderIntoSacksWhileFarmingCountsOnlyTheFarming() {
        IncomeEngine e = engine();
        e.setCollecting(Set.of("WHEAT", "ENCHANTED_WHEAT", "ENCHANTED_HAY_BALE"));
        e.menu(10 * S, true, false);                                   // Bazaar -> Manage Orders
        e.acquired(11 * S, "ENCHANTED_COCOA", 640, "Enchanted Cocoa Beans", "bazaar-claim");
        e.menu(12 * S, false, false);
        // The next [Sacks] summary: the claim AND 30 s of farming.
        assertEquals(0, e.sackGain(30 * S, "ENCHANTED_COCOA", 640, 700.0), 1e-9);
        assertEquals(3000 * 6.0, e.sackGain(30 * S, "WHEAT", 3000, 6.0), 1e-9);
        assertEquals(18_000, e.rate().total(), 1e-9);
    }

    @Test
    void aClaimWhoseChatLineWasMissedIsStillNotIncome() {
        IncomeEngine e = engine();
        e.sackGain(5 * S, "WHEAT", 1000, 6.0);                         // farming before: wheat is "steady"
        e.menu(10 * S, true, false);                                   // some shop / claim menu
        e.menu(15 * S, false, false);
        assertTrue(e.inTradeWindow(40 * S));
        assertEquals(0, e.sackGain(40 * S, "ENCHANTED_COOKIE", 3, 250_000.0), 1e-9);
        assertEquals(1200, e.sackGain(40 * S, "WHEAT", 200, 6.0), 1e-9);
        assertTrue(log.stream().anyMatch(v -> !v.counted() && v.rule().contains("likely a claim")));
        // Once the window has passed, a new item counts again.
        assertFalse(e.inTradeWindow(60 * S));
        assertEquals(750_000, e.sackGain(60 * S, "ENCHANTED_COOKIE", 3, 250_000.0), 1e-9);
    }

    @Test
    void anInventoryGainSeenJustBeforeTheDeliveryLineIsReversed() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv());
        // A non-sack item from the stash (sack items are held instead: SackableInventoryTest).
        e.inventory(2 * S, inv("SUMMONING_EYE", 64, 6.0));             // items land first...
        assertEquals(384, e.rate().total(), 1e-9);
        e.acquired(3 * S, "SUMMONING_EYE", 64, "Summoning Eye", "stash");   // ...then the stash line
        assertEquals(0, e.rate().total(), 1e-9);
        assertEquals(0, e.fromItems(), 1e-9);
    }

    @Test
    void aDeliveryLineBeforeTheItemsNetsThem() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv());
        e.acquired(2 * S, "COMPOST", 16, "Compost", "shop-buy");
        e.inventory(3 * S, inv("COMPOST", 16, 4.0, "DUNG", 1, 100.0));
        assertEquals(100, e.rate().total(), 1e-9);                     // the dung is a find, the compost was bought
    }

    @Test
    void aRareDropIsCountedOnceWhereverItLands() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv());
        // Chat first, item second (Overclocker into the inventory).
        assertEquals(250_000, e.drop(2 * S, "OVERCLOCKER_3000", 1, 250_000.0, IncomeSource.RARE, "Overclocker 3000", "rare-drop"), 1e-9);
        e.inventory(3 * S, inv("OVERCLOCKER_3000", 1, 250_000.0));
        assertEquals(250_000, e.rate().total(), 1e-9);
        // Item first, chat second (a vinyl): the inventory credit moves to the drop.
        e.inventory(4 * S, inv("OVERCLOCKER_3000", 1, 250_000.0, "VINYL_PRETTY_FLY", 1, 50_000.0));
        e.drop(5 * S, "VINYL_PRETTY_FLY", 1, 50_000.0, IncomeSource.PEST, "Pretty Fly Vinyl", "rare-drop/pest");
        assertEquals(300_000, e.rate().total(), 1e-9);
        double[] by = e.sessionBySource();
        assertEquals(250_000, by[IncomeSource.RARE.ordinal()], 1e-9);
        assertEquals(50_000, by[IncomeSource.PEST.ordinal()], 1e-9);
        assertEquals(0, by[IncomeSource.OTHER.ordinal()], 1e-9);
    }

    @Test
    void aDropIntoTheItemStashIsStillCounted() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv());
        e.drop(2 * S, "VINYL_PRETTY_FLY", 1, 50_000.0, IncomeSource.PEST, "Pretty Fly Vinyl", "rare-drop/pest");
        e.inventory(3 * S, inv());                                     // full inventory: it went to the stash
        assertEquals(50_000, e.rate().total(), 1e-9);
    }

    @Test
    void pestLootThatGoesToSacksIsNotCountedTwice() {
        IncomeEngine e = engine();
        e.drop(1 * S, "DUNG", 7, 100.0, IncomeSource.PEST, "Dung", "pest-kill");
        assertEquals(100, e.sackGain(20 * S, "DUNG", 8, 100.0), 1e-9);  // 7 were the kill, 1 is new
        assertEquals(800, e.rate().total(), 1e-9);
    }

    @Test
    void unpricedDropsAreLoggedNotSilentlyZero() {
        IncomeEngine e = engine();
        assertEquals(0, e.drop(1 * S, null, 1, Double.NaN, IncomeSource.RARE, "Mystery Thing", "rare-drop"), 1e-9);
        assertEquals(Map.of("Mystery Thing", 1), e.unpriced());
        assertTrue(log.stream().anyMatch(v -> v.rule().startsWith("unpriced") && v.what().contains("Mystery Thing")));
        assertEquals(0, e.pendingCount("Mystery Thing"));
    }

    @Test
    void everyDecisionIsLoggedWithItsRule() {
        IncomeEngine e = engine();
        e.purse(1 * S, 1000.0);
        e.purse(2 * S, 1100.0);
        e.menu(3 * S, true, false);
        e.purse(4 * S, 500.0);
        assertTrue(log.stream().anyMatch(v -> v.counted() && v.rule().equals("purse-delta") && v.value() == 100));
        assertTrue(log.stream().anyMatch(v -> !v.counted() && v.rule().equals("menu-open") && v.value() == -600));
    }

    @Test
    void recentRateLooksAtTheWindowAndSessionAtEverything() {
        IncomeEngine e = engine();
        for (int t = 1; t <= 20; t++) {
            e.rate().activity(t * 60 * S);
        }
        e.purse(60 * S, 0.0);
        e.purse(61 * S, 600_000.0);                                    // a burst early on
        e.purse(19 * 60 * S, 600_100.0);                               // little since
        long now = 20 * 60 * S;
        double recent = e.recentPerHour(now, 10 * 60 * S, 15 * S);
        assertEquals(100 * 6, recent, 1e-6);                           // 100 coins in the last 10 min = 600/h
        assertEquals(600_100 * 3, e.rate().perHour(now, 15 * S), 1e-6); // 600,100 over 20 min
        assertEquals(100, e.recentBySource(now, 10 * 60 * S)[IncomeSource.PURSE.ordinal()], 1e-9);
    }
}
