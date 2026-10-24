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
 * The 2026-10-09 wheat session: "started farming and I was getting negative
 * money, if it goes into my sack it thinks I'm losing money". On Hypixel a
 * harvested crop lands in the inventory and is moved into the sack ~0.5 s
 * later, so every crop was an inventory "+N" followed by a "-N", and the
 * [Sacks] summary counted it again 30 s later. Sackable items are now held
 * and only counted if they STAY.
 */
class SackableInventoryTest {

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
    void cropsPassingThroughTheInventoryIntoSacksAreCountedOnceByTheSummary() {
        IncomeEngine e = engine();
        e.setCollecting(Set.of("WHEAT", "ENCHANTED_WHEAT", "ENCHANTED_HAY_BALE"));
        e.inventory(0, inv());
        // 30 s of farming: +120 lands, ~0.5 s later it is moved into the sack.
        for (int i = 0; i < 30; i++) {
            long t = i * S;
            e.rate().activity(t);
            e.inventory(t + 200, inv("WHEAT", 120, 6.0));
            assertTrue(e.rate().total() >= 0, "never negative at " + t);
            e.inventory(t + 700, inv());
            assertTrue(e.rate().total() >= 0, "never negative at " + (t + 500));
        }
        assertEquals(0, e.rate().total(), 1e-9);                       // the inventory said nothing
        assertTrue(log.stream().allMatch(v -> !"inv".equals(v.channel()) || !v.counted()));
        e.sackGain(30 * S, "WHEAT", 3600, 6.0);
        e.sackSummary(30 * S);
        assertEquals(3600 * 6.0, e.rate().total(), 1e-9);              // once, from the summary
        // A summary arriving mid-churn (120 on their way) still settles to nothing.
        e.inventory(31 * S, inv("WHEAT", 120, 6.0));
        e.sackSummary(31 * S + 100);
        e.inventory(32 * S, inv());
        e.sackSummary(60 * S);
        assertEquals(3600 * 6.0, e.rate().total(), 1e-9);
        assertEquals(0, e.fromItems(), 1e-9);
    }

    @Test
    void withFullSacksTheCropsStayAndAreCountedAfterAWindow() {
        IncomeEngine e = engine();
        e.inventory(0, inv());
        e.sackGain(1 * S, "ENCHANTED_WHEAT", 5, 1000.0);                // wheat IS flowing into sacks...
        e.sackSummary(1 * S);
        e.inventory(5 * S, inv("WHEAT", 200, 6.0));                     // ...then the sack fills up
        e.inventory(20 * S, inv("WHEAT", 500, 6.0));
        e.sackSummary(31 * S);                                         // 200 and 500 arrived mid-window: not yet
        assertEquals(5_000, e.rate().total(), 1e-9);
        e.inventory(45 * S, inv("WHEAT", 800, 6.0));
        e.sackSummary(61 * S);                                         // 500 stayed through a whole window
        assertEquals(5_000 + 500 * 6.0, e.rate().total(), 1e-9);
        e.inventory(70 * S, inv("WHEAT", 800, 6.0));
        e.inventory(102 * S, inv("WHEAT", 800, 6.0));                  // no summary for 40 s: timer commit
        assertEquals(5_000 + 800 * 6.0, e.rate().total(), 1e-9);
        // Emptied into a sack after being counted: reversed, then the summary counts it.
        e.inventory(103 * S, inv());
        e.sackGain(110 * S, "WHEAT", 800, 6.0);
        e.sackSummary(110 * S);
        assertEquals(5_000 + 800 * 6.0, e.rate().total(), 1e-9);
    }

    @Test
    void itemsThatNeverReachASackAreSettledLikeAnyOtherItem() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv("COBBLESTONE", 160, 1.0));
        e.inventory(2 * S, inv("ENCHANTED_COBBLESTONE", 1, 160.0));    // a compactor, no sack involved
        e.inventory(42 * S, inv("ENCHANTED_COBBLESTONE", 1, 160.0));   // timer commit: -160 +160
        assertEquals(0, e.rate().total(), 1e-9);
        assertTrue(log.stream().anyMatch(v -> v.counted() && v.what().startsWith("COBBLESTONE -160")));
        assertTrue(log.stream().anyMatch(v -> v.counted() && v.what().startsWith("ENCHANTED_COBBLESTONE +1")));
    }

    @Test
    void aPestDropThatLandsInTheInventoryIsCountedOnce() {
        IncomeEngine e = engine();
        e.inventory(0, inv());
        e.sackGain(1 * S, "ENCHANTED_SUNFLOWER", 1, 620.0);            // sunflower goes to sacks
        e.sackSummary(1 * S);
        e.inventory(9 * S, inv("ENCHANTED_SUNFLOWER", 44, 620.0));       // lands first...
        e.drop(10 * S, "ENCHANTED_SUNFLOWER", 44, 620.0, IncomeSource.PEST, "Enchanted Sunflower", "pest-kill");
        e.sackSummary(31 * S);
        e.inventory(40 * S, inv("ENCHANTED_SUNFLOWER", 44, 620.0));      // ...and stays
        e.sackSummary(61 * S);
        assertEquals(620 + 44 * 620.0, e.rate().total(), 1e-9);
        assertTrue(log.stream().anyMatch(v -> !v.counted() && v.rule().startsWith("netted")));
    }

    @Test
    void movingSackItemsToTheInventoryIsNotIncome() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv());
        e.acquired(2 * S, "WHEAT", 640, "Wheat", "moved-from-sacks");   // "Moved 640 Wheat from your Sacks"
        e.sackRemoved(2 * S, "WHEAT", -640, 6.0, "Wheat");
        e.inventory(3 * S, inv("WHEAT", 640, 6.0));
        e.sackSummary(30 * S);
        e.sackSummary(60 * S);
        assertEquals(0, e.rate().total(), 1e-9);
        assertEquals(0, e.pendingCount("WHEAT"));
    }

    @Test
    void sackRemovalsAreNeverIncomeOrLossAndMarkTheItemSackable() {
        PriceData d = PriceData.EMPTY
                .withItems(new PriceData.Items(Map.of(), Map.of("compost", List.of("COMPOST"), "wheat", List.of("WHEAT"))), 1)
                .withBazaar(Map.of("COMPOST", new PriceData.Bazaar(4000, 3900), "WHEAT", new PriceData.Bazaar(7, 6)), 1);
        IncomeEngine e = engine();
        e.inventory(0, inv("COMPOST", 10, 3900.0));
        assertFalse(e.sackable("COMPOST"));                            // not a family, not seen yet
        MoneyRouter.sackSummary(e, d, 5 * S, List.of(
                "Removed items:\n  -10 Compost (Garden Sack)\n  -160 Wheat (Agronomy Sack)"));
        assertEquals(0, e.rate().total(), 1e-9);
        assertTrue(log.stream().anyMatch(v -> !v.counted() && v.rule().startsWith("sack removal")));
        assertTrue(log.stream().noneMatch(v -> v.counted()));
        assertTrue(e.sackable("COMPOST"));
        // A "-N" line inside an Added hover is a removal too.
        MoneyRouter.sackSummary(e, d, 6 * S, List.of("Added items:\n  -5 Compost (Garden Sack)"));
        assertEquals(0, e.rate().total(), 1e-9);
        // Now held like any sack item: the 10 leaving the inventory are a move, not a loss.
        e.inventory(7 * S, inv());
        e.sackSummary(37 * S);
        assertEquals(0, e.rate().total(), 1e-9);
    }

    @Test
    void anItemCountedBeforeItWasKnownSackableIsReversedWhenItLeaves() {
        IncomeEngine e = engine();
        e.inventory(0, inv());
        e.inventory(1 * S, inv("PLANT_MATTER", 2, 20_000.0));          // not known as a sack item: counted at once
        assertEquals(40_000, e.rate().total(), 1e-9);
        e.sackGain(1500, "PLANT_MATTER", 1, 20_000.0);                 // a summary names it: now sackable
        e.sackSummary(1500);
        assertEquals(60_000, e.rate().total(), 1e-9);
        e.inventory(2 * S, inv());                                      // the 2 move into a sack: held
        assertEquals(60_000, e.rate().total(), 1e-9);
        e.sackGain(30 * S, "PLANT_MATTER", 2, 20_000.0);               // the summary reports them...
        e.sackSummary(30 * S);                                         // ...and the early credit is reversed
        assertEquals(60_000, e.rate().total(), 1e-9);                  // 3 found, counted once each
    }

    @Test
    void aMenuMovesTheBaselineAndAnythingFarmedAfterIsHeldThenCounted() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv("WHEAT", 64, 6.0));
        e.menu(2 * S, true, false);
        e.inventory(3 * S, inv());                                      // stored in a chest
        e.menu(4 * S, false, false);
        e.inventory(5 * S, inv());
        e.inventory(8 * S, inv("WHEAT", 10, 6.0));                      // farmed more after the grace
        assertEquals(0, e.rate().total(), 1e-9);                       // held: wheat goes to sacks
        e.inventory(41 * S, inv("WHEAT", 10, 6.0));                     // no summary: timer commit
        assertEquals(60, e.rate().total(), 1e-9);
    }
}
