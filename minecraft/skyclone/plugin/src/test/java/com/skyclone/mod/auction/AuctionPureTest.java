package com.skyclone.mod.auction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.item.ItemCategory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Auction House's pure rules against research 05 section 3, the browser's filter/sort, the
 * Agents' price bands, and the shipped data files - no server needed.
 */
class AuctionPureTest {

    static final AuctionRules.Settings S = new AuctionRules.Settings();

    static Path data() {
        Path p = Path.of("src/main/resources/data/auction");
        if (Files.isDirectory(p)) return p;
        return Path.of("plugin/src/main/resources/data/auction");
    }

    static JsonElement read(String f) throws IOException {
        return JsonParser.parseString(Files.readString(data().resolve(f), StandardCharsets.UTF_8));
    }

    // ---- fees ---------------------------------------------------------------------------------

    @Test void durationFeesAreTheResearchTable() {
        assertEquals(20, AuctionRules.durationFee(S, 60), 1e-9);
        assertEquals(45, AuctionRules.durationFee(S, 6 * 60), 1e-9);
        assertEquals(100, AuctionRules.durationFee(S, 12 * 60), 1e-9);
        assertEquals(350, AuctionRules.durationFee(S, 24 * 60), 1e-9);
        assertEquals(1200, AuctionRules.durationFee(S, 48 * 60), 1e-9);
        assertEquals(55200, AuctionRules.durationFee(S, 336 * 60), 1e-9);
        assertEquals(20, AuctionRules.durationFee(S, 10), 1e-9, "under an hour costs the 1h fee");
        assertEquals(55200, AuctionRules.durationFee(S, 999 * 60), 1e-9, "clamped at 14 days");
    }

    @Test void durationFeeIsMonotonic() {
        double prev = 0;
        for (long m = 5; m <= 336 * 60; m += 5) {
            double f = AuctionRules.durationFee(S, m);
            assertTrue(f >= prev, "fee dropped at " + m + " minutes");
            prev = f;
        }
    }

    @Test void binFeeBands() {
        assertEquals(50_000, AuctionRules.listingFee(S, true, 5_000_000), 1e-6);
        assertEquals(99_999.99, AuctionRules.listingFee(S, true, 9_999_999), 1e-6);
        assertEquals(200_000, AuctionRules.listingFee(S, true, 10_000_000), 1e-6);
        assertEquals(1_999_999.98, AuctionRules.listingFee(S, true, 99_999_999), 1e-6, "just under 100M is 2%");
        assertEquals(2_500_000, AuctionRules.listingFee(S, true, 100_000_000), 1e-6);
        assertEquals(1_250_000_000, AuctionRules.listingFee(S, true, 50_000_000_000d), 1e-3, "max price BIN fee (research: 1.25B)");
    }

    @Test void auctionFeeIsFivePercent() {
        assertEquals(50_000, AuctionRules.listingFee(S, false, 1_000_000), 1e-6);
        assertEquals(2_500_000_000d, AuctionRules.listingFee(S, false, 50_000_000_000d), 1e-3, "max price auction fee (research: 2.5B)");
    }

    @Test void totalFeeRoundsUpToATenth() {
        assertEquals(50_350, AuctionRules.totalFee(S, true, 5_000_000, 24 * 60), 1e-9);
        assertEquals(45.1, AuctionRules.totalFee(S, true, 7, 6 * 60), 1e-9);   // 0.07 + 45 -> 45.1
    }

    // ---- claim tax ------------------------------------------------------------------------------

    @Test void claimTaxNeverBelowOneMillion() {
        assertEquals(500_000, AuctionRules.claimPayout(S, 500_000), 1e-9);
        assertEquals(1_000_000, AuctionRules.claimPayout(S, 1_000_000), 1e-9);
        assertEquals(1_000_000, AuctionRules.claimPayout(S, 1_005_000), 1e-9);
        assertEquals(1_980_000, AuctionRules.claimPayout(S, 2_000_000), 1e-9);
        assertEquals(99_000_000, AuctionRules.claimPayout(S, 100_000_000), 1e-9);
    }

    @Test void quadTaxesQuadruple() {
        AuctionRules.Settings q = new AuctionRules.Settings();
        q.claimTaxMultiplier = 4;
        assertEquals(9_600_000, AuctionRules.claimPayout(q, 10_000_000), 1e-9);
        assertEquals(1_000_000, AuctionRules.claimPayout(q, 1_020_000), 1e-9);
    }

    // ---- bidding ----------------------------------------------------------------------------------

    @Test void bidIncrements() {
        assertEquals(500, AuctionRules.minNextBid(S, 500, 0));
        assertEquals(1025, AuctionRules.minNextBid(S, 500, 1000));
        assertEquals(2, AuctionRules.minNextBid(S, 1, 1), "a tiny bid still has to go up by a coin");
        AuctionRules.Settings old = new AuctionRules.Settings();
        old.minIncrement = 0.15;
        assertEquals(1150, AuctionRules.minNextBid(old, 500, 1000));
    }

    @Test void antiSnipe() {
        long now = 1_000_000_000L;
        assertEquals(now + 120_000, AuctionRules.endAfterBid(S, now + 5_000, now));
        assertEquals(now + 600_000, AuctionRules.endAfterBid(S, now + 600_000, now));
    }

    // ---- limits & prices -----------------------------------------------------------------------------

    @Test void listingLimits() {
        assertEquals(14, AuctionRules.maxListings(S, 1));
        assertEquals(17, AuctionRules.maxListings(S, 2));
        assertEquals(35, AuctionRules.maxListings(S, 8));
        assertEquals(35, AuctionRules.maxListings(S, 20));
        assertEquals(14, AuctionRules.maxListings(S, 0));
    }

    @Test void commonCap() {
        assertEquals(3200, AuctionRules.commonValueCap(S, "COMMON", 10, 64), 1e-9);
        assertTrue(Double.isInfinite(AuctionRules.commonCap(S, "RARE", 10, 1, true, 60)));
        assertTrue(Double.isInfinite(AuctionRules.commonCap(S, "COMMON", 0, 1, true, 60)));
        // 3200 net + 1h fee 20 + 1% BIN fee -> 3253 is the smallest price that still nets 3200
        double cap = AuctionRules.commonCap(S, "COMMON", 10, 64, true, 60);
        assertEquals(3253, cap, 1e-9);
        assertNull(AuctionRules.checkPrice(S, cap, "COMMON", 10, 64, true, 60));
        assertNotNull(AuctionRules.checkPrice(S, cap + 1, "COMMON", 10, 64, true, 60));
        assertNotNull(AuctionRules.checkPrice(S, 0, "RARE", 0, 1, true, 60));
        assertNotNull(AuctionRules.checkPrice(S, 60_000_000_000d, "RARE", 0, 1, true, 60));
        assertNotNull(AuctionRules.checkPrice(S, Double.NaN, "RARE", 0, 1, true, 60));
    }

    /** Playtest 2026-10: a Wooden Sword (NPC value 1) was capped at 5 coins against a 20-coin fee, so every COMMON listing lost money. */
    @Test void commonCapNeverForcesALoss() {
        for (double npc : new double[]{0.5, 1, 3, 10, 250, 40_000})
            for (int amount : new int[]{1, 16, 64})
                for (boolean bin : new boolean[]{true, false})
                    for (long minutes : new long[]{5, 60, 360, 720, 1440, 2880, 7 * 1440, 336 * 60}) {
                        double cap = AuctionRules.commonCap(S, "COMMON", npc, amount, bin, minutes);
                        double want = AuctionRules.commonValueCap(S, "COMMON", npc, amount);
                        double net = AuctionRules.netIfSold(S, bin, cap, minutes);
                        String at = npc + "x" + amount + (bin ? " BIN " : " AUC ") + minutes + "m";
                        assertTrue(net >= want - 1e-9, "nets " + net + " < " + want + " at " + at);
                        // tight: one coin less no longer nets the value cap (so the cap opens no extra laundering)
                        assertTrue(AuctionRules.netIfSold(S, bin, cap - 1, minutes) < want, "loose at " + at);
                        assertNull(AuctionRules.checkPrice(S, cap, "COMMON", npc, amount, bin, minutes), at);
                    }
        // the reported case: one Wooden Sword, cheapest duration, BIN
        double sword = AuctionRules.commonCap(S, "COMMON", 1, 1, true, 60);
        assertTrue(AuctionRules.netIfSold(S, true, sword, 60) >= 5);
    }

    @Test void durations() {
        assertEquals(180, AuctionRules.parseDuration("3h"));
        assertEquals(90, AuctionRules.parseDuration("90m"));
        assertEquals(2880, AuctionRules.parseDuration("2d"));
        assertEquals(2160, AuctionRules.parseDuration("1d12h"));
        assertEquals(36 * 60, AuctionRules.parseDuration("36"));
        assertEquals(90, AuctionRules.parseDuration("1.5h"));
        assertEquals(-1, AuctionRules.parseDuration("soon"));
        assertEquals(-1, AuctionRules.parseDuration("h"));
        assertEquals(-1, AuctionRules.parseDuration(""));
        assertNull(AuctionRules.checkDuration(S, 60));
        assertNotNull(AuctionRules.checkDuration(S, 1));
        assertNotNull(AuctionRules.checkDuration(S, 337 * 60));
        assertEquals("6 Hours", AuctionRules.durationWords(360));
        assertEquals("1 Hour", AuctionRules.durationWords(60));
        assertEquals("2 Days", AuctionRules.durationWords(2880));
        assertEquals("90 Minutes", AuctionRules.durationWords(90));
    }

    @Test void words() {
        assertEquals("12,500,000 (12.5M)", AuctionRules.coinsWords(12_500_000));
        assertEquals("1,250,000 (1.2M)", AuctionRules.coinsWords(1_250_000));
        assertEquals("999", AuctionRules.coinsWords(999));
        assertEquals("1,234.5", AuctionRules.fmt(1234.5));
        assertEquals("1d 4h", AuctionRules.timeLeft((86400 + 4 * 3600) * 1000L));
        assertEquals("3m 5s", AuctionRules.timeLeft(185_000));
        assertEquals("0s", AuctionRules.timeLeft(-5));
    }

    // ---- browser ------------------------------------------------------------------------------------

    static Listing listing(String id, AhTab tab, boolean bin, double price, int bids, long ends, String name, String rarity) {
        Listing l = new Listing();
        l.id = id; l.tab = tab; l.bin = bin; l.startingBid = price; l.bidCount = bids; l.endsAt = ends; l.created = ends - 1000;
        l.itemName = "&f" + name; l.search = name.toLowerCase() + "\nsharpness v"; l.rarity = rarity; l.amount = 1;
        l.sellerName = "Seller" + id;
        if (bids > 0) l.topBid = price * 2;
        return l;
    }

    @Test void browserFiltersAndSorts() {
        long now = 1_000L;
        List<Listing> all = new ArrayList<>();
        all.add(listing("a", AhTab.WEAPONS, true, 500, 0, 5_000, "Iron Sword", "COMMON"));
        all.add(listing("b", AhTab.WEAPONS, false, 100, 3, 3_000, "Gold Sword", "RARE"));
        all.add(listing("c", AhTab.WEAPONS, true, 50, 0, 9_000, "Wood Sword", "COMMON"));
        all.add(listing("d", AhTab.ARMOR, true, 900, 0, 4_000, "Iron Helmet", "EPIC"));
        Listing gone = listing("e", AhTab.WEAPONS, true, 1, 0, 500, "Old Sword", "COMMON");
        all.add(gone);
        Listing sold = listing("f", AhTab.WEAPONS, true, 1, 0, 9_000, "Sold Sword", "COMMON");
        sold.state = Listing.State.SOLD;
        all.add(sold);

        BrowseQuery q = new BrowseQuery();
        q.tab = AhTab.WEAPONS;
        q.sort = BrowseQuery.Sort.HIGHEST;
        assertEquals(List.of("a", "b", "c"), ids(q.apply(all, now)), "ended and sold listings are hidden; price = top bid");
        q.sort = BrowseQuery.Sort.LOWEST;
        assertEquals(List.of("c", "b", "a"), ids(q.apply(all, now)));
        q.sort = BrowseQuery.Sort.ENDING;
        assertEquals(List.of("b", "a", "c"), ids(q.apply(all, now)));
        q.sort = BrowseQuery.Sort.MOST_BIDS;
        assertEquals("b", q.apply(all, now).get(0).id);
        q.binFilter = BrowseQuery.BinFilter.AUCTIONS_ONLY;
        assertEquals(List.of("b"), ids(q.apply(all, now)));
        q.binFilter = BrowseQuery.BinFilter.BIN_ONLY;
        q.rarity = "COMMON";
        q.sort = BrowseQuery.Sort.LOWEST;
        assertEquals(List.of("c", "a"), ids(q.apply(all, now)));

        BrowseQuery s = new BrowseQuery();
        s.search = "iron";
        assertEquals(2, s.apply(all, now).size(), "search crosses tabs");
        s.search = "iron helm";
        assertEquals(List.of("d"), ids(s.apply(all, now)), "every word must match");
        s.search = "sharpness";
        assertEquals(4, s.apply(all, now).size(), "lore is searchable");

        BrowseQuery by = new BrowseQuery();
        by.sellerName = "sellerd";
        assertEquals(List.of("d"), ids(by.apply(all, now)), "seller filter is case-insensitive and ignores the tab");
    }

    @Test void rarityFilterCycles() {
        BrowseQuery q = new BrowseQuery();
        assertNull(q.rarity);
        q.rarity = q.nextRarity(1);
        assertEquals("COMMON", q.rarity);
        q.rarity = q.nextRarity(-1);
        assertNull(q.rarity);
        q.rarity = q.nextRarity(-1);
        assertEquals("VERY_SPECIAL", q.rarity);
    }

    static List<String> ids(List<Listing> ls) {
        List<String> out = new ArrayList<>();
        for (Listing l : ls) out.add(l.id);
        return out;
    }

    // ---- tabs, settlement, agents ---------------------------------------------------------------------

    @Test void everyTabIsReachable() {
        Set<AhTab> hit = EnumSet.noneOf(AhTab.class);
        for (ItemCategory c : ItemCategory.values()) hit.add(AhTab.of(c, false));
        hit.add(AhTab.of(ItemCategory.MATERIAL, true));
        assertEquals(EnumSet.allOf(AhTab.class), hit);
        assertEquals(AhTab.WEAPONS, AhTab.of(ItemCategory.BOW, false));
        assertEquals(AhTab.ARMOR, AhTab.of(ItemCategory.CLOAK, false));
        assertEquals(AhTab.CONSUMABLES, AhTab.of(ItemCategory.ENCHANTED_BOOK, false));
        assertEquals(AhTab.BLOCKS, AhTab.of(ItemCategory.MATERIAL, true));
        assertEquals(AhTab.TOOLS_MISC, AhTab.of(ItemCategory.MATERIAL, false));
        assertEquals(AhTab.TOOLS_MISC, AhTab.of(ItemCategory.DRILL, true), "a tool is a tool even if its material is a block");
    }

    @Test void settlement() {
        Listing l = listing("x", AhTab.WEAPONS, true, 10, 0, 10, "X", "COMMON");
        assertFalse(l.settled());
        l.state = Listing.State.SOLD;
        assertFalse(l.settled());
        l.sellerClaimed = true;
        assertFalse(l.settled());
        l.buyerClaimed = true;
        assertTrue(l.settled());
        Listing e = listing("y", AhTab.WEAPONS, true, 10, 0, 10, "Y", "COMMON");
        e.state = Listing.State.EXPIRED;
        assertFalse(e.settled());
        e.sellerClaimed = true;
        assertTrue(e.settled());
    }

    @Test void agentBandsAndRounding() {
        assertEquals(0, NpcMarket.band(9_999));
        assertEquals(1, NpcMarket.band(10_000));
        assertEquals(3, NpcMarket.band(5_000_000));
        assertEquals(5, NpcMarket.band(5e9));
        assertEquals(NpcMarket.BAND_TOP.length, NpcMarket.BAND_WEIGHT.length);
        assertEquals(1_230_000, NpcMarket.nice(1_234_567), 1e-6);
        assertEquals(45, NpcMarket.nice(45.4), 1e-6);
        assertEquals(1, NpcMarket.nice(0.2), 1e-6);
        assertEquals(998, NpcMarket.nice(998.2), 1e-6);
    }

    @Test void medianOfSales() {
        assertEquals(0, PriceGuide.median(List.of()), 1e-9);
        assertEquals(5, PriceGuide.median(List.of(9.0, 1.0, 5.0)), 1e-9);
        assertEquals(4, PriceGuide.median(List.of(1.0, 3.0, 5.0, 9.0)), 1e-9);
    }

    // ---- shipped data ---------------------------------------------------------------------------------

    @Test void priceTableIsSane() throws IOException {
        JsonArray arr = read("prices.json").getAsJsonArray();
        assertTrue(arr.size() >= 400, "prices.json has " + arr.size());
        Set<String> ids = new HashSet<>();
        int pets = 0;
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            assertTrue(ids.add(id), "duplicate " + id);
            assertTrue(id.matches("[A-Z0-9_:\\-]+(;[0-5])?"), "odd id " + id);
            double ref = o.get("ref").getAsDouble(), min = o.get("min").getAsDouble();
            assertTrue(ref > 0 && min > 0 && ref >= min - 1e-6, id + " ref " + ref + " min " + min);
            if (id.contains(";")) pets++;
        }
        assertTrue(pets >= 20, "pets in the table: " + pets);
        assertTrue(ids.contains("ASPECT_OF_THE_END"), "a staple weapon is priced");
    }

    @Test void bazaarSnapshotAndAgents() throws IOException {
        JsonObject bz = read("bazaar_ref.json").getAsJsonObject();
        assertTrue(bz.size() >= 1000);
        assertTrue(bz.get("ENCHANTED_DIAMOND").getAsDouble() > 0);
        JsonObject ag = read("agents.json").getAsJsonObject();
        assertTrue(ag.getAsJsonArray("sellers").size() >= 8);
        for (String k : List.of("master", "agent_1", "agent_2")) assertTrue(ag.has(k), k);
        String text = ag.toString();
        for (String banned : List.of("Hypixel", "Elizabeth", "Sirius")) assertFalse(text.contains(banned), "reference proper noun " + banned);
    }

    @Test void controlCharacterFreeSources() throws IOException {
        Path src = Path.of("src/main/java/com/skyclone/mod/auction");
        if (!Files.isDirectory(src)) src = Path.of("plugin/src/main/java/com/skyclone/mod/auction");
        try (var files = Files.list(src)) {
            for (Path p : files.toList()) {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    assertFalse(c < 0x20 && c != '\n' && c != '\r' && c != '\t', p + " has control char " + (int) c + " at " + i);
                }
            }
        }
    }

    // ---- the view after an action (PLAYTEST wave5 #6: "Auction not found" right after a purchase) ----

    static Listing settledBin() {
        Listing l = listing("v", AhTab.WEAPONS, true, 100, 0, 9_000, "Sword", "COMMON");
        l.sellerProfile = "seller"; l.state = Listing.State.SOLD; l.topBid = 100;
        l.topBidderProfile = "buyer"; l.topBidderName = "Buyer";
        l.sellerClaimed = true; l.buyerClaimed = true;
        return l;
    }

    @Test void viewKeepsASettledPurchaseItHolds() {
        Listing l = settledBin();
        assertTrue(l.settled());
        assertSame(l, Listing.forView(null, l), "bought, delivered and evicted in one click: the view draws what it holds");
        Listing live = settledBin();
        assertSame(live, Listing.forView(live, l), "the live row always wins");
        assertNull(Listing.forView(null, null), "an id that never resolved is still 'not found'");
    }

    @Test void viewDoesNotDrawAStaleUnsettledCopy() {
        Listing l = settledBin();
        l.buyerClaimed = false;                       // the item is still owed: the store must hold it, so trust the store
        assertNull(Listing.forView(null, l));
        l.state = Listing.State.ACTIVE;
        assertNull(Listing.forView(null, l));
    }

    @Test void outcomeReadsFromEachSide() {
        Listing l = settledBin();
        assertEquals(Listing.Outcome.BOUGHT, l.outcomeFor("buyer"));
        assertEquals(Listing.Outcome.SOLD_COLLECTED, l.outcomeFor("seller"));
        assertEquals(Listing.Outcome.NONE, l.outcomeFor("someone-else"));
        l.bin = false;
        assertEquals(Listing.Outcome.WON, l.outcomeFor("buyer"));
        Listing back = listing("w", AhTab.WEAPONS, true, 100, 0, 9_000, "Sword", "COMMON");
        back.sellerProfile = "seller"; back.state = Listing.State.CANCELLED; back.sellerClaimed = true;
        assertEquals(Listing.Outcome.ITEM_RETURNED, back.outcomeFor("seller"));
        back.sellerClaimed = false;
        assertEquals(Listing.Outcome.NONE, back.outcomeFor("seller"), "not settled yet: the Collect button is shown instead");
    }
}
