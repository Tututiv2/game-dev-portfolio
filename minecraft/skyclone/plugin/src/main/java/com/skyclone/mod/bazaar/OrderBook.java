package com.skyclone.mod.bazaar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The order book of ONE product: price-time priority on both sides (research 05 s2.4). PURE (no Bukkit), main
 * thread only in game (no locking here by design: the market owns one thread).
 *
 * <p>Naming follows the trader, not the API: {@link #bids} are Buy Orders (what an instant SELL hits, best = highest),
 * {@link #asks} are Sell Offers (what an instant BUY hits, best = lowest). Within one price the older order fills
 * first.
 *
 * <p>Fills are applied to the MAKER here, tax included, so the arithmetic lives in one testable place:
 * a maker BUY gains {@code itemsPending}; a maker SELL gains {@code coinsPending} = gross - tax. Market-maker (bot)
 * makers gain nothing pending: the caller moves the market maker's virtual inventory from the returned fills.
 */
public final class OrderBook {

    /** One executed piece of a trade. {@code price} is the maker's price; {@code net} is what the maker banked. */
    public record Fill(Order maker, int qty, long price, long gross, long tax) {}

    /** One aggregated price level for display: "1,280x @ 4.2 coins (3 orders)". */
    public record Level(long price, long amount, int orders, boolean hasBot) {}

    /** A non-mutating price walk. */
    public record Quote(int qty, long cost, long worst, long best) {
        public long avg() { return qty <= 0 ? 0 : cost / qty; }
    }

    static final Comparator<Order> ASK_ORDER = Comparator.<Order>comparingLong(o -> o.price).thenComparingLong(o -> o.seq);
    static final Comparator<Order> BID_ORDER = Comparator.<Order>comparingLong(o -> -o.price).thenComparingLong(o -> o.seq);

    public final String product;
    private final TreeSet<Order> bids = new TreeSet<>(BID_ORDER);
    private final TreeSet<Order> asks = new TreeSet<>(ASK_ORDER);

    public OrderBook(String product) { this.product = product; }

    public Iterable<Order> bids() { return bids; }
    public Iterable<Order> asks() { return asks; }
    public int size() { return bids.size() + asks.size(); }

    private TreeSet<Order> side(Order.Side s) { return s == Order.Side.BUY ? bids : asks; }

    public void add(Order o) {
        if (!o.open() || o.remaining() <= 0) return;
        side(o.side).add(o);
    }

    public boolean remove(Order o) { return side(o.side).remove(o); }

    public Order bestBid() { return bids.isEmpty() ? null : bids.first(); }
    public Order bestAsk() { return asks.isEmpty() ? null : asks.first(); }

    /** Best price on a side, optionally only among orders matching {@code which}; 0 when none. */
    public long best(Order.Side s, Predicate<Order> which) {
        for (Order o : side(s)) if (which == null || which.test(o)) return o.price;
        return 0;
    }

    public long volume(Order.Side s) {
        long v = 0;
        for (Order o : side(s)) v += o.remaining();
        return v;
    }

    public int count(Order.Side s) { return side(s).size(); }

    /** Top {@code n} price levels of a side, aggregated. */
    public List<Level> levels(Order.Side s, int n) {
        List<Level> out = new ArrayList<>();
        long curPrice = -1, amt = 0;
        int cnt = 0;
        boolean bot = false;
        for (Order o : side(s)) {
            if (o.price != curPrice) {
                if (curPrice >= 0) {
                    out.add(new Level(curPrice, amt, cnt, bot));
                    if (out.size() >= n) return out;
                }
                curPrice = o.price; amt = 0; cnt = 0; bot = false;
            }
            amt += o.remaining();
            cnt++;
            bot |= o.bot;
        }
        if (curPrice >= 0 && out.size() < n) out.add(new Level(curPrice, amt, cnt, bot));
        return out;
    }

    /**
     * What taking {@code qty} from the book would cost (a BUY taker walks the asks) or pay (a SELL taker walks
     * the bids), without changing anything. {@code limit} 0 = no limit. {@code botAllowance} caps how much may come
     * from market-maker orders.
     */
    public Quote quote(Order.Side taker, int qty, long limit, Predicate<Order> eligible, int botAllowance) {
        TreeSet<Order> book = taker == Order.Side.BUY ? asks : bids;
        int got = 0;
        long cost = 0, worst = 0, best = 0;
        int bots = Math.max(0, botAllowance);
        for (Order o : book) {
            if (got >= qty) break;
            if (limit > 0 && (taker == Order.Side.BUY ? o.price > limit : o.price < limit)) break;
            if (eligible != null && !eligible.test(o)) continue;
            int can = Math.min(qty - got, o.remaining());
            if (o.bot) { can = Math.min(can, bots); }
            if (can <= 0) continue;
            if (o.bot) bots -= can;
            if (best == 0) best = o.price;
            worst = o.price;
            got += can;
            cost = Math.addExact(cost, Pricing.total(o.price, can));
        }
        return new Quote(got, cost, worst, best);
    }

    /**
     * Execute: take up to {@code qty} at prices no worse than {@code limit} (0 = any). Makers are charged/credited
     * here (see class doc); filled-out makers leave the book and become CLOSED. Returns the fills in order.
     */
    public List<Fill> take(Order.Side taker, int qty, long limit, Predicate<Order> eligible, int botAllowance, double taxPercent) {
        return take(taker, qty, limit, eligible, botAllowance, o -> taxPercent);
    }

    /** As above, with each MAKER's own tax rate (percent): a sell offer pays the rate its owner placed it at. */
    public List<Fill> take(Order.Side taker, int qty, long limit, Predicate<Order> eligible, int botAllowance,
                           java.util.function.ToDoubleFunction<Order> makerTax) {
        TreeSet<Order> book = taker == Order.Side.BUY ? asks : bids;
        List<Fill> fills = new ArrayList<>();
        int got = 0;
        int bots = Math.max(0, botAllowance);
        Iterator<Order> it = book.iterator();
        List<Order> done = new ArrayList<>();
        while (it.hasNext() && got < qty) {
            Order o = it.next();
            if (limit > 0 && (taker == Order.Side.BUY ? o.price > limit : o.price < limit)) break;
            if (eligible != null && !eligible.test(o)) continue;
            int can = Math.min(qty - got, o.remaining());
            if (o.bot) can = Math.min(can, bots);
            if (can <= 0) continue;
            if (o.bot) bots -= can;
            long gross = Pricing.total(o.price, can);
            long tax = 0;
            o.filled += can;
            if (!o.bot) {
                if (o.side == Order.Side.BUY) {
                    o.itemsPending += can;
                } else {
                    tax = Pricing.tax(gross, makerTax.applyAsDouble(o));
                    o.coinsPending += gross - tax;
                    o.taxPaid += tax;
                }
            }
            fills.add(new Fill(o, can, o.price, gross, tax));
            got += can;
            if (o.remaining() <= 0) done.add(o);
        }
        for (Order o : done) {
            book.remove(o);
            o.state = Order.State.CLOSED;
        }
        return fills;
    }

    /** Remove every market-maker order (before a re-quote); Merchant Guild offers stay. Returns how many. */
    public int clearBots() {
        int n = 0;
        for (TreeSet<Order> s : List.of(bids, asks)) {
            Iterator<Order> it = s.iterator();
            while (it.hasNext()) { Order o = it.next(); if (o.bot && !o.guild) { it.remove(); n++; } }
        }
        return n;
    }

    /** Would an order at this price cross the opposite side (excluding orders {@code ignore} rejects)? */
    public boolean crosses(Order.Side side, long price, Predicate<Order> eligible) {
        long opp = best(side == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY, eligible);
        if (opp <= 0) return false;
        return side == Order.Side.BUY ? price >= opp : price <= opp;
    }
}
