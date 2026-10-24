package com.skyclone.mod.auction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The Auction Agents: a market maker so the Auction House works with two players online.
 *
 * <p><b>Sellers.</b> Keep about {@code auction.npc.listings} consignment listings of real, registered,
 * auction-type items (gear, accessories, pets, tools - never Bazaar goods), priced from the price
 * guide (market reference, else crafting cost) with a little spread. Stock is drawn across price
 * bands so a new player always finds something affordable and a rich one something to aim at.
 * Coins paid for Agent stock leave the game (a sink); the item is created fresh at listing time.
 *
 * <p><b>Buyers.</b> Every pass, an Agent may buy a player's BIN that is priced at or under
 * {@code auction.npc.bin-fraction} of its fair value, or bid late on a player's auction up to
 * {@code auction.npc.bid-fraction}. They never buy above fair value (so buying from an Agent and
 * reselling to one always loses money) and never pay one profile more than {@code auction.npc.daily-cap}
 * per UTC day (a faucet cap). Items an Agent buys leave the game.
 */
public final class NpcMarket {

    /** ItemData.extra flag on every unique item an Agent ever listed. */
    static final String AGENT_TAG = "ah_agent_stock";

    /** Was this listing's item once Agent stock? Cached on the listing (the item never changes while listed). */
    static boolean agentOrigin(Listing l) {
        if (l.agentOrigin == null) {
            boolean v = false;
            ItemStack it = AuctionHouse.decodeQuiet(l);
            if (it != null) {
                try {
                    var d = Sky.items().data(it);
                    v = d.extra != null && d.extra.has(AGENT_TAG);
                } catch (Throwable ignored) { }
            }
            l.agentOrigin = v;
        }
        return l.agentOrigin;
    }

    /** Price bands (upper bounds) and how much of the stock each gets. TUNE. */
    static final double[] BAND_TOP = {10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, Double.MAX_VALUE};
    static final double[] BAND_WEIGHT = {24, 26, 22, 15, 9, 4};

    private final AuctionHouse house;
    private final Random rnd = new Random();
    private List<String> sellerNames = List.of("Auction Agent");
    private List<Candidate> candidates;
    private long candidatesAt;

    record Candidate(String id, double unit, int band) {}

    NpcMarket(AuctionHouse house) {
        this.house = house;
        JsonElement e = PriceGuide.read("agents.json");
        if (e instanceof JsonObject o && o.get("sellers") instanceof JsonArray arr && !arr.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonElement n : arr) names.add(n.getAsString());
            sellerNames = names;
        }
    }

    String randomName() { return sellerNames.get(rnd.nextInt(sellerNames.size())); }

    List<String> sellerNames() { return sellerNames; }

    static int band(double price) {
        for (int i = 0; i < BAND_TOP.length; i++) if (price < BAND_TOP[i]) return i;
        return BAND_TOP.length - 1;
    }

    /** Is this def something an Agent may stock? (Exists, auctionable, an auction-house kind of item.) */
    static boolean stockable(ItemDef d, boolean bazaarProduct) {
        if (d == null || d.id == null) return false;
        if (d.has("no_ah") || d.has("soulbound") || d.has("no_trade") || d.has("no_auction") || d.has("bazaar")) return false;
        if (d.rarity == Rarity.ADMIN || d.rarity == Rarity.SPECIAL || d.rarity == Rarity.VERY_SPECIAL) return false;
        if (bazaarProduct) return false;
        if (d.category == ItemCategory.NONE) return false;
        return d.isUnique() || d.category == ItemCategory.PET || d.category == ItemCategory.PET_ITEM
                || d.category == ItemCategory.REFORGE_STONE || d.category == ItemCategory.POWER_STONE;
    }

    /**
     * Every stockable item with a known value. Cached for 2 hours: it walks every item def and its recipe tree
     * on the main thread, and stock priced from a 2-hour-old guide is still fair (the Agents' spread is wider).
     */
    List<Candidate> candidates() {
        long now = System.currentTimeMillis();
        if (candidates != null && now - candidatesAt < 2 * 60 * 60_000L) return candidates;
        List<Candidate> out = new ArrayList<>();
        Features.Bazaar bz = Sky.service(Features.Bazaar.class);
        double max = house.dials.npcMaxPrice.get();
        for (ItemDef d : new ArrayList<>(Sky.items().all())) {
            boolean product;
            try { product = bz.isBazaarItem(d.id) || house.guide.bazaarBuy(d.id) > 0; } catch (Throwable t) { product = false; }
            if (!stockable(d, product)) continue;
            double v;
            try { v = house.guide.agentValue(d.id); } catch (Throwable t) { v = 0; }
            if (!(v >= 1) || v > max) continue;
            out.add(new Candidate(d.id, v, band(v)));
        }
        candidates = out;
        candidatesAt = now;
        return out;
    }

    void invalidate() { candidates = null; }

    /** Top the Agents' stock up; returns how many listings were added. {@code spread}: stagger end times (first fill). */
    int restock(int maxAdd, boolean spread) {
        if (!house.dials.npcSellers.get()) return 0;
        int target = Math.max(0, house.dials.npcListings.get());
        int active = 0;
        Map<String, Integer> perId = new HashMap<>();
        for (Listing l : house.store.all()) {
            if (l.npc && l.active()) { active++; perId.merge(l.itemId, 1, Integer::sum); }
        }
        int want = Math.min(maxAdd, target - active);
        if (want <= 0) return 0;
        List<Candidate> pool = candidates();
        if (pool.isEmpty()) return 0;
        List<List<Candidate>> bands = new ArrayList<>();
        for (int i = 0; i < BAND_TOP.length; i++) bands.add(new ArrayList<>());
        for (Candidate c : pool) bands.get(c.band()).add(c);
        int perItem = Math.max(1, house.dials.npcPerItem.get());
        int added = 0, tries = 0;
        while (added < want && tries++ < want * 20) {
            List<Candidate> b = bands.get(pickBand(bands));
            if (b.isEmpty()) continue;
            Candidate c = b.get(rnd.nextInt(b.size()));
            if (perId.getOrDefault(c.id(), 0) >= perItem) continue;
            if (list(c, spread) != null) {
                perId.merge(c.id(), 1, Integer::sum);
                added++;
            }
        }
        if (added > 0) house.invalidateLowestBin();
        return added;
    }

    private int pickBand(List<List<Candidate>> bands) {
        double total = 0;
        for (int i = 0; i < bands.size(); i++) if (!bands.get(i).isEmpty()) total += BAND_WEIGHT[i];
        double r = rnd.nextDouble() * total;
        for (int i = 0; i < bands.size(); i++) {
            if (bands.get(i).isEmpty()) continue;
            r -= BAND_WEIGHT[i];
            if (r <= 0) return i;
        }
        return bands.size() - 1;
    }

    /** List one Agent consignment of {@code c}. Null when the item could not be made. */
    Listing list(Candidate c, boolean spread) {
        ItemStack item;
        try {
            item = Sky.items().create(c.id(), 1);
            // marked for life: Agents never buy their own stock back (else win-cheap / sell-to-Agent is a coin loop)
            if (AuctionHouse.uuidOf(item) != null) Sky.items().update(item, d -> d.extra.addProperty(AGENT_TAG, true));
        } catch (Throwable t) {
            return null;
        }
        if (item == null || AuctionHouse.auctionable(item) != null) return null;
        // bid auctions (which can go cheap) only for unique items, which carry the Agent mark
        boolean bin = AuctionHouse.uuidOf(item) == null || rnd.nextDouble() >= house.dials.npcAuctionShare.get();
        double mult = house.dials.npcPriceMult.get();
        // TUNE: Agents ask 92%..130% of value for BIN; auctions open at 55%..75% and are left to the bidders
        double price = bin ? c.unit() * mult * (0.92 + rnd.nextDouble() * 0.38)
                : c.unit() * mult * (0.55 + rnd.nextDouble() * 0.20);
        price = nice(price);
        long[] hours = {6, 12, 24, 48};
        long minutes = hours[rnd.nextInt(hours.length)] * 60;
        if (spread) minutes = Math.max(30, (long) (minutes * (0.15 + rnd.nextDouble() * 0.85)));
        long now = System.currentTimeMillis();
        Listing l = AuctionHouse.build(item, c.id(), bin, price, now, minutes);
        l.npc = true;
        l.sellerName = randomName();
        l.buyableAt = now;   // Agent stock needs no anti-snipe grace
        house.store.add(l);
        house.store.save(l);
        return l;
    }

    /** Round to 3 significant figures, the way people price things. */
    static double nice(double v) {
        if (v < 100) return Math.max(1, Math.round(v));
        double mag = Math.pow(10, Math.floor(Math.log10(v)) - 2);
        return Math.round(v / mag) * mag;
    }

    /** One buyer pass over player listings. Returns {bought, bid}. */
    int[] buyerPass(boolean force) {
        int bought = 0, bid = 0;
        if (!house.dials.npcBuyers.get() && !force) return new int[]{0, 0};
        long now = System.currentTimeMillis();
        long minAge = house.dials.npcBuyerMinAge.get() * 1000L;
        double binFrac = house.dials.npcBinFraction.get();
        double bidFrac = house.dials.npcBidFraction.get();
        double chance = house.dials.npcBuyerChance.get();
        double cap = house.dials.npcDailyCap.get();
        AuctionRules.Settings s = house.rules();
        // coins already PROMISED to each seller by an Agent leading one of their auctions count against the cap
        // too: without this, fourteen auctions each under the cap could all be won by Agents on the same day
        Map<String, Double> promised = new HashMap<>();
        for (Listing l : house.store.all()) {
            if (l.active() && !l.npc && l.sellerProfile != null && l.topBidder == null && l.topBid > 0) {
                promised.merge(l.sellerProfile, l.topBid, Double::sum);
            }
        }
        for (Listing l : new ArrayList<>(house.store.all())) {
            if (l.npc || !l.active() || l.endsAt <= now || l.sellerProfile == null) continue;
            if (agentOrigin(l)) continue;
            if (!force && now - l.created < minAge) continue;
            double value;
            try { value = house.guide.fairValue(l.itemId) * Math.max(1, l.amount); } catch (Throwable t) { value = 0; }
            if (!(value > 0)) continue;
            double room = cap - house.store.npcPaidToday(l.sellerProfile) - promised.getOrDefault(l.sellerProfile, 0.0);
            if (l.bin) {
                double limit = value * binFrac;
                double price = l.startingBid;
                if (now < l.buyableAt || price > limit || price > room) continue;
                // the cheaper it is against value, the sooner it goes
                double p = Math.min(0.95, chance * (1 + (1 - price / limit) * 2));
                if (!force && rnd.nextDouble() > p) continue;
                house.markSold(l, null, null, randomName(), price, true, now);
                house.store.addNpcPaid(l.sellerProfile, price);
                house.store.log("npc_buy", l.id, "npc", price, l.itemId);
                bought++;
            } else {
                if (l.topBidder == null && l.topBid > 0) continue;            // an Agent already leads
                if (!force && l.endsAt - now > 10 * 60_000L) continue;        // Agents bid late. TUNE
                double limit = value * bidFrac;
                double min = AuctionRules.minNextBid(s, l.startingBid, l.topBid);
                if (min > limit || min > room) continue;
                if (!force && rnd.nextDouble() > chance) continue;
                double amount = Math.min(limit, Math.min(room, min * (1 + rnd.nextDouble() * 0.05)));
                amount = Math.max(min, Math.ceil(amount));
                house.placeBid(l, null, null, randomName(), amount, true, now);
                promised.merge(l.sellerProfile, amount, Double::sum);
                bid++;
            }
        }
        if (bought > 0) house.store.evictSettled();
        return new int[]{bought, bid};
    }

    /** Remove every active Agent listing (admin). */
    int purge() {
        int n = 0;
        for (Listing l : new ArrayList<>(house.store.all())) {
            if (!l.npc || !l.active()) continue;
            if (l.topBidder != null && l.topBid > 0) { house.adminCancel(l, "npc purge"); n++; continue; }
            l.state = Listing.State.CANCELLED;
            l.sellerClaimed = true;
            house.store.save(l);
            n++;
        }
        house.store.evictSettled();
        house.invalidateLowestBin();
        return n;
    }
}
