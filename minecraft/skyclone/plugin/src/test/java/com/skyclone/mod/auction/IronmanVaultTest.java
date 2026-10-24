package com.skyclone.mod.auction;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wave 5 (2026-10-06): the Ironman Vault rules (research 05 5.2 + 9.3), the Museum Milestone gates with their
 * fallback, the Regalia Room ledger, the co-op soulbound refusal and the browse delisting window.
 */
class IronmanVaultTest {

    // ---- tracks ------------------------------------------------------------------------------------------

    @Test void ironmanSeesOnlyIronmanLotsAndClassicNeverSeesThem() {
        for (String t : List.of(VaultRules.MAIN, VaultRules.SPECIAL)) {
            assertTrue(VaultRules.visible(t, false), t);
            assertFalse(VaultRules.visible(t, true), t);
        }
        for (String t : List.of(VaultRules.IRONMAN, VaultRules.IRONMAN_SPECIAL)) {
            assertTrue(VaultRules.visible(t, true), t);
            assertFalse(VaultRules.visible(t, false), t);
        }
        assertTrue(VaultRules.specialTrack(VaultRules.IRONMAN_SPECIAL));      // gated like Special (Milestone 20)
        assertFalse(VaultRules.specialTrack(VaultRules.IRONMAN));
        assertEquals(VaultRules.IRONMAN, VaultRules.ironmanCopyOf(VaultRules.MAIN));
        assertEquals(VaultRules.IRONMAN_SPECIAL, VaultRules.ironmanCopyOf(VaultRules.SPECIAL));
        assertNull(VaultRules.ironmanCopyOf(VaultRules.IRONMAN));             // never a copy of a copy
    }

    @Test void trackOrderIsATotalOrderOverAllFourTracks() {
        List<String> tracks = List.of(VaultRules.MAIN, VaultRules.IRONMAN, VaultRules.SPECIAL, VaultRules.IRONMAN_SPECIAL, "bogus");
        Set<Integer> seen = new java.util.HashSet<>();
        for (String t : tracks) assertTrue(seen.add(VaultRules.trackOrder(t)), "two tracks share a sort key: " + t);
        assertTrue(VaultRules.trackOrder(VaultRules.MAIN) < VaultRules.trackOrder(VaultRules.SPECIAL));
    }

    @Test void ironmanCopiesCarryHalfOfEveryClassicLotIncludingTheVolumePerk() {
        List<VaultRules.Spec> classic = List.of(new VaultRules.Spec("ARTIFACT_OF_CONTROL", 3, 10e6),
                new VaultRules.Spec("PANDORAS_BOX", 5, 1e6), new VaultRules.Spec("SHENS_REGALIA", 3, 5e6),
                new VaultRules.Spec("SOME_ROD", 2, 3e6));
        List<VaultRules.Spec> iron = VaultRules.ironmanCopies(classic, 0.5);
        assertEquals(List.of(2, 3, 2, 1), iron.stream().map(VaultRules.Spec::stock).toList());
        for (int i = 0; i < classic.size(); i++) {
            assertEquals(classic.get(i).itemId(), iron.get(i).itemId());
            assertEquals(classic.get(i).minBid(), iron.get(i).minBid(), 1e-9);  // same minimums
        }
        // under the volume perk the classic stock doubles first, then Ironman takes half of THAT
        List<VaultRules.Spec> doubled = new ArrayList<>();
        for (VaultRules.Spec s : classic) doubled.add(new VaultRules.Spec(s.itemId(), s.stock() * 2, s.minBid()));
        assertEquals(List.of(3, 5, 3, 2), VaultRules.ironmanCopies(doubled, 0.5).stream().map(VaultRules.Spec::stock).toList());
        assertTrue(VaultRules.ironmanCopies(null, 0.5).isEmpty());
    }

    @Test void ironmanGetsTheVaultWhateverTheMainAuctionHouseDoesButBingoAndStrandedDoNot() {
        assertNull(VaultRules.modeRefusal("normal", false, true));
        assertNull(VaultRules.modeRefusal(null, false, false));
        assertNull(VaultRules.modeRefusal("ironman", false, true));
        assertNotNull(VaultRules.modeRefusal("ironman", false, false));         // its track switched off
        assertTrue(VaultRules.modeRefusal("bingo", false, true).contains("Bingo"));
        assertNotNull(VaultRules.modeRefusal("stranded", false, true));
        assertNull(VaultRules.modeRefusal("stranded", true, false));            // special-modes opens everything
    }

    // ---- gates ------------------------------------------------------------------------------------------------

    @Test void museumMilestoneGateFallsBackOnlyWhenNothingResolvesIt() {
        assertEquals("museum_milestone:10", VaultRules.gate("museum_milestone:10", "museum:60", p -> true));
        assertEquals("museum:60", VaultRules.gate("museum_milestone:10", "museum:60", p -> false));
        // one unknown part of a compound key is enough to fall back
        assertEquals("museum:150", VaultRules.gate("sblevel:5 & museum_milestone:20", "museum:150", p -> p.equals("sblevel")));
        assertEquals("", VaultRules.gate("", "museum:60", p -> false));           // blank = no gate, never the fallback
        assertEquals("museummilestone:3", VaultRules.gate("MuseumMilestone:3", null, p -> false).toLowerCase());
    }

    // ---- the Regalia Room ledger -----------------------------------------------------------------------------

    @Test void ledgerSummarisesWinningBids() {
        VaultRules.Result r = VaultRules.result(5, List.of(2e6, 4e6, 3e6));
        assertEquals(3, r.sold());
        assertEquals(2, r.unsold());
        assertEquals(2e6, r.lowest(), 1e-9);
        assertEquals(4e6, r.highest(), 1e-9);
        assertEquals(3e6, r.mean(), 1e-9);
        VaultRules.Result none = VaultRules.result(3, List.of());
        assertEquals(0, none.sold());
        assertEquals(3, none.unsold());
        assertEquals(0, none.lowest(), 1e-9);
        // junk amounts are ignored, never counted as sales
        assertEquals(1, VaultRules.result(2, java.util.Arrays.asList(null, Double.NaN, -1d, 9d)).sold());
    }

    // ---- item + browse rules ---------------------------------------------------------------------------------

    @Test void instanceSoulbindingIsRefused() {
        assertTrue(AuctionRules.boundKeys(Set.of("museum_soulbound")));
        assertTrue(AuctionRules.boundKeys(Set.of("rarity_upgrades", "coop_soulbound")));
        assertTrue(AuctionRules.boundKeys(Set.of("soulbound")));
        assertFalse(AuctionRules.boundKeys(Set.of("ah_agent_stock", "hot_potato_count")));
        assertFalse(AuctionRules.boundKeys(null));
    }

    @Test void escrowOwnerIsWhoeverTheItemGoesToNext() {
        Listing l = AuctionPureTest.listing("x", AhTab.WEAPONS, true, 10, 0, 5_000, "Sword", "RARE");
        l.sellerProfile = "seller";
        assertEquals("seller", l.escrowOwner());                       // listed: the seller could cancel it
        l.state = Listing.State.SOLD;
        l.topBidderProfile = "buyer";
        assertEquals("buyer", l.escrowOwner());                        // sold: waits for the buyer
        l.buyerClaimed = true;
        assertNull(l.escrowOwner());                                   // delivered: no longer held
        l.state = Listing.State.EXPIRED;
        l.buyerClaimed = false;
        assertEquals("seller", l.escrowOwner());                       // expired: returns to the seller
        l.sellerClaimed = true;
        assertFalse(l.itemInEscrow());
        Listing agent = AuctionPureTest.listing("y", AhTab.WEAPONS, true, 10, 0, 5_000, "Sword", "RARE");
        agent.npc = true;
        agent.sellerProfile = "npc";
        assertNull(agent.escrowOwner());                               // Agent stock belongs to no player
    }

    @Test void endingListingsLeaveBrowseButNotTheSellersPage() {
        long now = 1_000_000L;
        List<Listing> all = new ArrayList<>();
        all.add(AuctionPureTest.listing("soon", AhTab.WEAPONS, true, 10, 0, now + 200_000, "Soon Sword", "COMMON"));
        all.add(AuctionPureTest.listing("later", AhTab.WEAPONS, true, 10, 0, now + 400_000, "Later Sword", "COMMON"));
        BrowseQuery q = new BrowseQuery();
        assertEquals(2, q.apply(all, now).size());                                // off by default
        q.hideBeforeEndMs = 285_000;                                               // research 4m45s
        assertEquals(List.of("later"), q.apply(all, now).stream().map(l -> l.id).toList());
        q.search = "sword";
        assertEquals(1, q.apply(all, now).size());                                 // search hides it too
        BrowseQuery seller = new BrowseQuery();
        seller.hideBeforeEndMs = 285_000;
        seller.sellerName = "Sellersoon";
        assertEquals(1, seller.apply(all, now).size());                            // /ah <player> still shows it
    }
}
