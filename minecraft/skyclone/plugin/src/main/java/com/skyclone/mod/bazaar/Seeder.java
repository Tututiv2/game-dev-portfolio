package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Merchant Guild's supply drops — research 05 s2.9's second seeding trick: "periodic NPC sell offers for
 * progression-gating materials to keep early game moving". The generator marks the materials (slayer, dragon,
 * mining and fishing drops that early gear needs and that a small server may have nobody farming yet; see
 * {@link Catalogue.Product#seed}); every {@code bazaar.seed-interval-minutes} the guild replaces its standing Sell
 * Offer on each of them with a fresh one of exactly the batch size, priced off the market maker's un-skewed ask.
 *
 * <p>Why this is not just "more market maker": the market maker's stock runs down (a few buyers empty it, restock
 * has a 6 h half-life, and an emptied maker skews its price UP), and on a busy product it fades out so players set
 * the price. A player who buys up every offer and relists at ten times the price would then own the item. The
 * guild's drop comes back every interval at a fair price whatever happened, so nobody can corner a material
 * that gates progression. Bounds: it never accumulates, shares the per-player hourly allowance with the market
 * makers, never undercuts the maker's bid or the NPC sell price, never sits above an NPC shop, and its price feeds
 * the recipe caps (so a cheap input can never make a crafted item's bid profitable). Guild sales only REMOVE coins.
 */
final class Seeder {

    static final String NAME = "Merchant Guild";

    private final Market market;
    private final Catalogue cat;
    private final MarketMaker mm;

    /** product -> the guild's live offer (open or just filled). */
    final Map<String, Order> resting = new LinkedHashMap<>();
    /** product -> units sold since boot. */
    final Map<String, Long> soldBy = new LinkedHashMap<>();
    long lastDrop, drops, unitsSold, coinsIn, unitsPosted;
    /** Why each seeded product was skipped on the last drop (admin view). */
    final Map<String, String> skipped = new LinkedHashMap<>();

    Seeder(Market market, Catalogue cat, MarketMaker mm) {
        this.market = market;
        this.cat = cat;
        this.mm = mm;
    }

    /** The seeded list right now: the generator's materials edited by {@code bazaar.seed-products}. */
    Map<String, Integer> list() {
        Map<String, Integer> data = new LinkedHashMap<>();
        for (Catalogue.Product p : cat.products.values()) if (p.seed > 0) data.put(p.id, p.seed);
        return Seeding.merge(data, BzDials.seedProducts.get(), 64);
    }

    /** Called once a minute (and at boot): drop when due. */
    void tick() {
        long now = System.currentTimeMillis();
        if (!BzDials.seedEnabled.get()) {
            if (!resting.isEmpty()) withdrawAll();
            return;
        }
        if (Seeding.untilNext(lastDrop, now, BzDials.seedIntervalMinutes.get()) > 0) return;
        drop(now);
    }

    /** Re-stock every seeded product now. Returns how many offers are on the book afterwards. */
    int drop(long now) {
        if (!BzDials.seedEnabled.get()) { withdrawAll(); return 0; }
        lastDrop = now;
        drops++;
        skipped.clear();
        Map<String, Integer> want = list();
        // products dropped from the list since the last drop: take their offers down
        for (String id : new ArrayList<>(resting.keySet())) if (!want.containsKey(id)) withdraw(id);
        int posted = 0;
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            try {
                if (post(e.getKey(), e.getValue(), now)) posted++;
            } catch (RuntimeException ex) {
                skipped.put(e.getKey(), "error: " + ex);
                Sky.log().warning("[bazaar] merchant guild " + e.getKey() + " failed: " + ex);
            }
        }
        return posted;
    }

    private boolean post(String pid, int base, long now) {
        Catalogue.Product p = cat.product(pid);
        if (p == null) { skipped.put(pid, "not in the catalogue"); withdraw(pid); return false; }
        if (!p.live) { skipped.put(pid, "no module registers this item"); withdraw(pid); return false; }
        if (!p.mm || MarketMaker.bidsOnly(p)) { skipped.put(pid, "no trusted ask price (player-only or bid-only)"); withdraw(pid); return false; }
        if (!BzDials.mmEnabled.get()) { skipped.put(pid, "market makers are off (the guild prices off them)"); withdraw(pid); return false; }
        MarketMaker.State s = mm.states.get(pid);
        if (s == null || !(s.spread > 0)) { skipped.put(pid, "market maker has not quoted it yet"); return false; }
        OrderBook book = market.book(pid);
        withdraw(pid);   // a fresh offer: never accumulate, and re-price every drop
        double nominal = s.quotedFair() * (1 + s.spread / 2);
        long mmBid = book.best(Order.Side.BUY, o -> o.bot && !o.guild);
        long price = Seeding.price(nominal, mmBid, p.npcSell, MarketMaker.shopPrice(p), BzDials.seedPricePercent.get());
        if (price <= 0) { skipped.put(pid, "an NPC shop sells it below the guild's floor"); return false; }
        int qty = Seeding.batch(base, BzDials.seedBatchMult.get(), price / 10.0, BzDials.seedMaxValue.get());
        if (qty <= 0) { skipped.put(pid, "batch is 0"); return false; }
        // a player Buy Order at or above the guild's price fills at the PLAYER's price, exactly as an instant sell
        // would; it counts toward the owner's hourly allowance (an owner at the cap is skipped)
        java.util.function.Predicate<Order> crossable = o -> !o.bot && market.allowanceOf(o.owner, pid) > 0;
        List<OrderBook.Fill> f = book.take(Order.Side.SELL, qty, price, crossable, 0, market::makerRate);
        int sold = 0;
        for (OrderBook.Fill x : f) { sold += x.qty(); market.noteBotUse(x.maker().owner, pid, x.qty()); record(pid, x.qty(), x.gross()); }
        market.afterFills(pid, f, null);
        int rest = qty - sold;
        unitsPosted += qty;
        if (rest > 0) {
            Order o = new Order(market.nextBotId(), market.nextSeq(), pid, Order.Side.SELL, null, NAME, null,
                    price, rest, now, Long.MAX_VALUE, true, true);
            book.add(o);
            resting.put(pid, o);
        }
        // the guild's ask is a way to GET the item: crafted products' bids must stay under it too
        mm.lowerExternal(pid, price / 10.0);
        return rest > 0;
    }

    /** A player bought from a guild offer (Market.afterFills routes guild fills here). */
    void filled(String pid, int qty, long gross) {
        record(pid, qty, gross);
        Order o = resting.get(pid);
        if (o != null && o.remaining() <= 0) resting.remove(pid);
    }

    private void record(String pid, int qty, long gross) {
        unitsSold += qty;
        coinsIn += gross;
        soldBy.merge(pid, (long) qty, Long::sum);
    }

    /** The guild's open ask on a product in tenths, 0 = none. The market maker keeps its bid under this. */
    long restingPrice(String pid) {
        Order o = resting.get(pid);
        return o != null && o.open() && o.remaining() > 0 ? o.price : 0;
    }

    int restingQty(String pid) {
        Order o = resting.get(pid);
        return o != null && o.open() ? o.remaining() : 0;
    }

    void withdraw(String pid) {
        Order o = resting.remove(pid);
        if (o == null) return;
        market.book(pid).remove(o);
        o.state = Order.State.DONE;
    }

    void withdrawAll() { for (String id : new ArrayList<>(resting.keySet())) withdraw(id); }

    /** Millis until the next drop (0 = due / disabled). */
    long untilNext() {
        return BzDials.seedEnabled.get() ? Seeding.untilNext(lastDrop, System.currentTimeMillis(), BzDials.seedIntervalMinutes.get()) : 0;
    }
}
