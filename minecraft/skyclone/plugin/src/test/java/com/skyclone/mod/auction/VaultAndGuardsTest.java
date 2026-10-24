package com.skyclone.mod.auction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Vault Auction's ranked bidding (research 05 section 5.2) and the guards added in review: the Agents'
 * fair value can only be LOWERED by server sales (never talked up by wash trades), and nothing is worth
 * anything to an Agent without an anchor a player cannot write.
 */
class VaultAndGuardsTest {

    static VaultRules.Entry e(String who, double amount, long time) { return new VaultRules.Entry(who, amount, time); }

    // ---- vault: ranked bidding -------------------------------------------------------------------------

    @Test void aLotWithAFreeCopyTakesTheMinimum() {
        assertEquals(1_000_000, VaultRules.minNewBid(1_000_000, 3, List.of(), 0.025), 1e-9);
        assertEquals(1_000_000, VaultRules.minNewBid(1_000_000, 3, List.of(e("a", 5_000_000, 1), e("b", 2_000_000, 2)), 0.025), 1e-9);
        assertEquals(500, VaultRules.minNewBid(499.2, 1, List.of(), 0.025), 1e-9);   // whole coins
    }

    @Test void aFullLotNeedsTheLowestKeptBidPlusTheIncrement() {
        List<VaultRules.Entry> full = List.of(e("a", 5_000_000, 1), e("b", 2_000_000, 2), e("c", 3_000_000, 3));
        assertEquals(2_000_000, VaultRules.lowestKept(3, full), 1e-9);
        assertEquals(2_050_000, VaultRules.minNewBid(1_000_000, 3, full, 0.025), 1e-9);
        // a huge minimum still wins over a cheap lowest bid
        assertEquals(9_000_000, VaultRules.minNewBid(9_000_000, 3, full, 0.025), 1e-9);
        // tiny bids still need to go up by at least one coin
        assertEquals(11, VaultRules.minNewBid(1, 1, List.of(e("a", 10, 1)), 0.0), 1e-9);
    }

    @Test void theLowestRankedBidIsPushedOutAndTiesGoToTheEarlierBid() {
        List<VaultRules.Entry> bids = new ArrayList<>(List.of(e("a", 5, 1), e("b", 3, 2), e("c", 3, 3), e("d", 9, 4)));
        List<VaultRules.Entry> out = VaultRules.displaced(3, bids);
        assertEquals(1, out.size());
        assertEquals("c", out.get(0).who(), "equal amounts: the LATER bid drops out");
        assertEquals(1, VaultRules.place("d", bids));
        assertEquals(2, VaultRules.place("a", bids));
        assertEquals(3, VaultRules.place("b", bids));
        assertEquals(0, VaultRules.place("zz", bids));
        assertTrue(VaultRules.displaced(4, bids).isEmpty());
    }

    @Test void ironmanLotsCarryHalfTheStockRoundedUp() {
        assertEquals(2, VaultRules.ironmanStock(3, 0.5));    // research: 50%; 1.5 rounds up, a lot never shrinks to nothing early
        assertEquals(3, VaultRules.ironmanStock(5, 0.5));
        assertEquals(3, VaultRules.ironmanStock(6, 0.5));    // exact halves stay exact
        assertEquals(1, VaultRules.ironmanStock(1, 0.5));
        assertEquals(1, VaultRules.ironmanStock(0, 0.5));    // never below one copy
        assertEquals(2, VaultRules.ironmanStock(3, Double.NaN));   // a broken dial falls back to 50%
        assertEquals(2, VaultRules.ironmanStock(3, -1));
        assertEquals(3, VaultRules.ironmanStock(3, 7));       // never MORE than the classic stock
    }

    @Test void raisingYourOwnBid() {
        assertEquals(1_025_000, VaultRules.minRaise(1_000_000, 0.025), 1e-9);
        assertEquals(11, VaultRules.minRaise(10, 0.0), 1e-9);
    }

    @Test void dialParsing() {
        List<VaultRules.Spec> s = VaultRules.parseItems("ARTIFACT_OF_CONTROL:3:10m, pandoras_box:5:1.5m,BROKEN,X:0:1m,Y:2:-5,Z:2:250k");
        assertEquals(3, s.size(), "bad entries are skipped, good ones kept: " + s);
        assertEquals(new VaultRules.Spec("ARTIFACT_OF_CONTROL", 3, 10_000_000), s.get(0));
        assertEquals(new VaultRules.Spec("PANDORAS_BOX", 5, 1_500_000), s.get(1));
        assertEquals(new VaultRules.Spec("Z", 2, 250_000), s.get(2));
        assertTrue(VaultRules.parseItems(null).isEmpty());
        // the shipped default parses to the research's three main-track items
        assertEquals(3, VaultRules.parseItems("ARTIFACT_OF_CONTROL:3:10m,PANDORAS_BOX:5:1m,SHENS_REGALIA:3:5m").size());
    }

    @Test void timeLeftInTheYear() {
        long day = 20 * 60_000L;   // 20 real minutes per SkyBlock day
        assertEquals(372 * day, VaultRules.millisLeftInYear(1, 1, 0, 0, day));          // a year is 12 x 31 days = 124 h
        assertEquals(124L * 3_600_000L, VaultRules.millisLeftInYear(1, 1, 0, 0, day));
        assertEquals(day / 2, VaultRules.millisLeftInYear(12, 31, 12, 0, day));
        assertEquals(0, VaultRules.millisLeftInYear(13, 1, 0, 0, day));                 // never negative
    }

    @Test void lotsAreCentredInTheRow() {
        assertArrayEquals(new int[]{22}, VaultRules.centered(18, 1));
        assertArrayEquals(new int[]{20, 22, 24}, VaultRules.centered(18, 3));
        assertArrayEquals(new int[]{37, 39, 41, 43}, VaultRules.centered(36, 4));
        assertArrayEquals(new int[]{37, 38, 39, 40, 41, 42, 43}, VaultRules.centered(36, 9), "clamped to the 7 inner columns");
        assertEquals(0, VaultRules.centered(36, 0).length);
    }

    // ---- guards ----------------------------------------------------------------------------------------

    @Test void serverSalesCanOnlyLowerTheAgentsFairValue() {
        // wash trades at silly prices do not raise it
        assertEquals(100_000, PriceGuide.fairValue(100_000, 4_900_000, 3, 3), 1e-9);
        // a real glut lowers it
        assertEquals(60_000, PriceGuide.fairValue(100_000, 60_000, 5, 3), 1e-9);
        // two sales are not a market
        assertEquals(100_000, PriceGuide.fairValue(100_000, 10_000, 2, 3), 1e-9);
    }

    @Test void noAnchorMeansAgentsPayNothing() {
        // the old rule paid the sales median here, which two alts could set to anything
        assertEquals(0, PriceGuide.fairValue(0, 4_900_000, 30, 3), 1e-9);
        assertEquals(0, PriceGuide.fairValue(Double.NaN, 4_900_000, 30, 3), 1e-9);
    }
}
