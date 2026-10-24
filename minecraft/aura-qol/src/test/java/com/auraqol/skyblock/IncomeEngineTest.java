package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class IncomeEngineTest {

    private static final long S = 1000;

    private static IncomeEngine engine() {
        IncomeEngine e = new IncomeEngine(new RateTracker(60 * S), 3 * S, 90 * S);
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
    void firstPurseReadingIsOnlyABaseline() {
        IncomeEngine e = engine();
        e.purse(1 * S, 50_000_000.0);
        assertEquals(0, e.rate().total(), 1e-9);
        e.purse(2 * S, 50_000_500.0);
        assertEquals(500, e.rate().total(), 1e-9);
        assertEquals(500, e.fromPurse(), 1e-9);
    }

    @Test
    void missingPurseIsIgnoredNotZero() {
        IncomeEngine e = engine();
        e.purse(1 * S, 1000.0);
        e.purse(2 * S, null); // sidebar mid-redraw
        e.purse(3 * S, 1100.0);
        assertEquals(100, e.rate().total(), 1e-9);
    }

    @Test
    void sellingToAnNpcIsNotIncomeEvenWhenThePurseRedrawsLate() {
        IncomeEngine e = engine();
        e.purse(1 * S, 1000.0);
        e.menu(2 * S, true, false);         // open the NPC shop
        e.purse(3 * S, 1000.0);
        e.menu(4 * S, false, false);        // close it
        e.purse(5 * S, 9000.0);             // sidebar catches up 1 s later
        assertEquals(0, e.rate().total(), 1e-9);
        e.purse(8 * S, 9100.0);             // a real kill after the grace
        assertEquals(100, e.rate().total(), 1e-9);
    }

    @Test
    void tradeChatOpensTheSameGrace() {
        IncomeEngine e = engine();
        e.purse(1 * S, 1000.0);
        e.tradeMessage(10 * S);
        assertTrue(e.excluded(12 * S));
        e.purse(12 * S, 500.0);             // bought something
        assertFalse(e.excluded(13 * S + 1));
        e.purse(14 * S, 600.0);
        assertEquals(100, e.rate().total(), 1e-9);
    }

    @Test
    void inventoryGainsAndLossesAreBothCounted() {
        // An item that never goes to sacks (sackable ones: SackableInventoryTest).
        IncomeEngine e = engine();
        e.inventory(1 * S, inv("SUMMONING_EYE", 1, 1268.6));
        e.inventory(2 * S, inv("SUMMONING_EYE", 3, 1268.6));
        assertEquals(2 * 1268.6, e.rate().total(), 1e-6);
        e.inventory(3 * S, inv("SUMMONING_EYE", 2, 1268.6)); // dropped one
        assertEquals(1268.6, e.rate().total(), 1e-6);
        assertEquals(1268.6, e.fromItems(), 1e-6);
    }

    @Test
    void aCompactorIsNotProfit() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv("COBBLESTONE", 160, 1.0));
        e.inventory(2 * S, inv("ENCHANTED_COBBLESTONE", 1, 160.0));
        e.inventory(45 * S, inv("ENCHANTED_COBBLESTONE", 1, 160.0)); // sackable: settled at the commit
        assertEquals(0, e.rate().total(), 1e-9);
    }

    @Test
    void unknownValuesAreSkippedAndAKnownSideIsUsed() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv("SKYBLOCK_MENU", 1, Double.NaN, "X", 1, 10.0));
        e.inventory(2 * S, inv("SKYBLOCK_MENU", 1, Double.NaN));
        // X disappeared: its value comes from the old snapshot.
        assertEquals(-10, e.rate().total(), 1e-9);
    }

    @Test
    void menuInventoryChangesOnlyMoveTheBaseline() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv("SUMMONING_EYE", 64, 6.0));
        e.menu(2 * S, true, false);
        e.inventory(3 * S, inv());                 // stored in a chest
        e.menu(4 * S, false, false);
        e.inventory(5 * S, inv());
        e.inventory(8 * S, inv("SUMMONING_EYE", 10, 6.0)); // found more after the grace
        assertEquals(60, e.rate().total(), 1e-9);
    }

    @Test
    void puttingItemsIntoSacksIsAMoveNotAFind() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv("WHEAT", 640, 6.0));
        e.menu(2 * S, true, true);                 // Agronomy Sack menu
        e.inventory(3 * S, inv());                 // "insert inventory"
        e.menu(4 * S, false, false);
        assertEquals(640, e.pendingCount("WHEAT"));
        // The [Sacks] summary then reports +700: 640 were ours already.
        double got = e.sackGain(20 * S, "WHEAT", 700, 6.0);
        assertEquals(60 * 6.0, got, 1e-9);
        assertEquals(360, e.rate().total(), 1e-9);
        assertEquals(0, e.pendingCount("WHEAT"));
    }

    @Test
    void bazaarDeliveriesToSacksAreNotIncome() {
        IncomeEngine e = engine();
        e.expectSackDeposit(1 * S, "ENCHANTED_DIAMOND", 64);
        assertEquals(0, e.sackGain(10 * S, "ENCHANTED_DIAMOND", 64, 1268.6), 1e-9);
        assertEquals(0, e.rate().total(), 1e-9);
    }

    @Test
    void pendingDepositsExpire() {
        IncomeEngine e = engine();
        e.expectSackDeposit(1 * S, "WHEAT", 100);
        assertEquals(600, e.sackGain(200 * S, "WHEAT", 100, 6.0), 1e-9);
        e.expectSackDeposit(300 * S, "WHEAT", 5);
        e.prune(1000 * S);
        assertEquals(0, e.pendingCount("WHEAT"));
    }

    @Test
    void sackLossesAndUnpricedGainsCountNothing() {
        IncomeEngine e = engine();
        assertEquals(0, e.sackGain(1 * S, "WHEAT", -50, 6.0), 1e-9);
        assertEquals(0, e.sackGain(1 * S, "MYSTERY", 50, Double.NaN), 1e-9);
        assertEquals(0, e.sackGain(1 * S, null, 50, 6.0), 1e-9);
        assertEquals(0, e.rate().total(), 1e-9);
    }

    @Test
    void rebaselineForgetsTheOldIslandsPurseAndInventory() {
        IncomeEngine e = engine();
        e.purse(1 * S, 1000.0);
        e.inventory(1 * S, inv("A", 1, 100.0));
        e.rebaseline(2 * S);
        e.purse(10 * S, 5000.0);           // a different profile's purse
        e.inventory(10 * S, inv());        // the Rift swaps your inventory
        assertEquals(0, e.rate().total(), 1e-9);
    }

    @Test
    void recentGainShowsABurstThenFades() {
        IncomeEngine e = engine();
        e.purse(1 * S, 0.0);
        e.purse(2 * S, 100.0);
        e.purse(3 * S, 150.0);
        assertEquals(150, e.recentGain(3 * S, 2500), 1e-9);
        assertEquals(0, e.recentGain(10 * S, 2500), 1e-9);
    }

    @Test
    void forgettingTheInventoryPreventsAPhantomDiffLater() {
        IncomeEngine e = engine();
        e.inventory(1 * S, inv("A", 1, 100.0));
        e.forgetInventory();                       // item valuation switched off
        e.inventory(500 * S, inv("A", 9, 100.0));  // ...and back on much later
        assertEquals(0, e.rate().total(), 1e-9);
    }
}
