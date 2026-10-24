package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.Recipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Server market makers (research 05 s2.9): ordinary participants on every priced product's book, so a server with
 * two players still has something to buy and somewhere to sell. One virtual trader per product with a fair value
 * that drifts (mean-reverting random walk toward an anchor = reference price, pulled by real player trades), a
 * virtual stock that restocks toward a target, and quotes from {@link MmMath} with the safety invariants there.
 *
 * <p>Two more things make it feel alive and both are bounded:
 * <ul>
 *   <li><b>Crossing:</b> when a re-quote lands on a player's order (a buy order at or above the new ask), the market
 *       maker fills it at the PLAYER's price, exactly as an instant trade would.</li>
 *   <li><b>Simulated flow:</b> a trickle of fictional traders (reference weekly volume x {@code bazaar.flow-scale})
 *       sells into player buy orders that OUTBID the market maker and buys from player offers that UNDERCUT it,
 *       never outside the market maker's own fair band. That is what lets a patient order fill on a quiet server;
 *       it never touches market-maker orders, and only runs on products whose price is trusted on both sides.</li>
 * </ul>
 * Refreshes are spread over ticks (a slice per tick) so 1,800 products never land in one tick.
 *
 * <p><b>Money-printer guards</b> (each one closes a loop a player could otherwise run against the bots):
 * <ol>
 *   <li>bid &lt; ask, always (MmMath);</li>
 *   <li>ask &le; any NPC shop price, bid &le; 97% of it (buy from the NPC, dump on the bots);</li>
 *   <li>bid &le; the cheapest way to MAKE the item from things the bots or NPC shops sell: crafting and forge
 *       recipes, recursively, and enchanted-book ladders (two level-N books combine into N+1);</li>
 *   <li>bid &le; the reference price x {@code bazaar.mm-bid-max-ref}, however players trade (two accounts trading a
 *       thin product at 10x can pull the anchor, but never the bid above reference);</li>
 *   <li>products whose snapshot price is untrustworthy quote one side only (see {@link Catalogue.Product#mmSides}).</li>
 * </ol>
 */
final class MarketMaker {

    static final class State {
        double fair;
        double inv;
        long pnl;          // tenths: coins in from selling minus coins out from buying
        double vwap;       // player-trade VWAP (coins), decayed
        double pvol;       // player volume in the decay window
        long updated;
        boolean dirty;
        // last quote, for display, recipe caps and self-tests
        transient long bid, ask;
        transient int depth;
        transient double target, spread, bidCap, shop;
        /** Event-shock multiplier currently applied to {@link #fair} (glides toward its target; 1 = none). */
        transient double shock = 1.0;
        /** Fair value as quoted: the stored fair times the event shock. The stored fair never carries a shock. */
        double quotedFair() { return fair * (shock > 0 ? shock : 1.0); }
    }

    /** Re-clamps (re-quotes of a product whose bid an input's cheaper ask just undercut) per tick, at most. */
    private static final int RECLAMP_PER_TICK = 200;

    private final Market market;
    private final Catalogue cat;
    private final Store store;
    private final Random rng = new Random();
    final Map<String, State> states = new LinkedHashMap<>();
    /** Cheapest way to get/make each item and the recipe caps (null until the first computeCaps). */
    private CostGraph graph;
    /** {@link #registryKey()} the graph was built from: a late module's recipes force a rebuild. */
    private long graphKey = Long.MIN_VALUE;
    /** Products whose recipe cap may have dropped below their bid on the book since they were quoted. */
    private final Set<String> reclamp = new LinkedHashSet<>();
    private final Deque<String> queue = new ArrayDeque<>();
    private long lastFull = 0;
    /** The Merchant Guild's supply offers (set by the module): our bid stays under its ask, its ask feeds the caps. */
    Seeder seeder;
    long refreshes, flowUnits;

    MarketMaker(Market market, Catalogue cat, Store store) {
        this.market = market;
        this.cat = cat;
        this.store = store;
    }

    // ---- setup -------------------------------------------------------------------------------------------------

    void load() {
        for (Store.MmRow r : store.loadMm()) {
            State s = new State();
            s.fair = r.fair(); s.inv = r.inv(); s.pnl = r.pnl(); s.vwap = r.vwap(); s.pvol = r.pvol(); s.updated = r.updated();
            states.put(r.product(), s);
        }
    }

    /** At postEnable: NPC shop prices from the live recipe registry, a dry quote pass, caps, then real quotes. */
    void start() {
        scanShops();
        long now = System.currentTimeMillis();
        for (Catalogue.Product p : cat.products.values()) if (p.live && p.mm) quote(p, state(p), now, false);
        computeCaps();
        for (Catalogue.Product p : cat.products.values()) if (p.live) refresh(p.id, now);
        // the refresh pass evolved every product (hours of drift since the last boot): re-clamp what that undercut
        drainReclamp(now, Integer.MAX_VALUE);
        lastFull = now;
        flush();
    }

    /** NPC shop prices (coins per unit) from the live recipe registry; only ever lowers a known price. */
    private void scanShops() {
        for (Recipe r : Sky.recipes().byType(Recipe.Type.NPC_SHOP)) {
            if (r == null || r.result == null || r.inputs == null) continue;
            Catalogue.Product p = cat.product(r.result);
            if (p == null) continue;
            long coins = 0;
            boolean other = false;
            for (String cell : r.inputs) {
                if (cell == null || cell.isBlank()) continue;
                String[] c = cell(cell);
                if (c[0].equals("COINS") || c[0].equals("SKYBLOCK_COIN")) coins += parseLong(c[1]);
                else other = true;
            }
            if (other || coins <= 0 || r.amount <= 0) continue;
            double unit = (double) coins / r.amount;
            if (p.shopPrice <= 0 || unit < p.shopPrice) p.shopPrice = unit;
        }
    }

    State state(Catalogue.Product p) {
        return states.computeIfAbsent(p.id, k -> {
            State s = new State();
            s.fair = Math.max(0.1, p.ref * BzDials.mmPriceScale.get());
            s.inv = -1;   // set to target on first quote
            s.dirty = true;
            return s;
        });
    }

    // ---- per-tick slice ----------------------------------------------------------------------------------------

    /** Called every tick: refresh a slice so each product is re-quoted about once per refresh period. */
    void tick() {
        long now = System.currentTimeMillis();
        int period = Math.max(5, BzDials.mmRefreshSeconds.get());
        if (graph != null && registryKey() != graphKey) {
            // modules enabled after us registered recipes/items in their postEnable: rebuild and re-clamp everything now
            computeCaps();
            drainReclamp(now, Integer.MAX_VALUE);
        } else {
            drainReclamp(now, RECLAMP_PER_TICK);
        }
        if (queue.isEmpty()) {
            if (now - lastFull < period * 1000L) return;
            computeCaps();
            for (Catalogue.Product p : cat.products.values()) if (p.live) queue.add(p.id);
            lastFull = now;
        }
        int ticks = period * 20;
        int slice = Math.max(1, (int) Math.ceil(cat.liveProducts / (double) ticks) + 1);
        for (int i = 0; i < slice && !queue.isEmpty(); i++) {
            String id = queue.poll();
            try { refresh(id, now); } catch (RuntimeException e) { Sky.log().warning("[bazaar] market maker " + id + " failed: " + e); }
        }
        // this slice's cheaper asks queued their outputs: re-clamp them in the same tick, so no bid outlives its cap
        drainReclamp(now, RECLAMP_PER_TICK);
        if (queue.isEmpty()) { flush(); market.store.flush(); }
    }

    void flush() {
        List<Store.MmRow> rows = new ArrayList<>();
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            if (!s.dirty) continue;
            s.dirty = false;
            rows.add(new Store.MmRow(e.getKey(), s.fair, Math.max(0, s.inv), s.pnl, s.vwap, s.pvol, s.updated));
        }
        store.saveMm(rows);
    }

    // ---- quoting -----------------------------------------------------------------------------------------------

    static boolean bidsOnly(Catalogue.Product p) { return "bid".equalsIgnoreCase(p.mmSides); }

    static boolean asksOnly(Catalogue.Product p) { return "ask".equalsIgnoreCase(p.mmSides); }

    /** Both sides trusted: the only products simulated traders touch. */
    static boolean twoSided(Catalogue.Product p) { return !bidsOnly(p) && !asksOnly(p); }

    double ref(Catalogue.Product p) { return Math.max(0.1, p.ref * BzDials.mmPriceScale.get()); }

    /** Compute (and remember) the quotes for a product from its current state. {@code evolve} = apply drift/restock. */
    MmMath.Quotes quote(Catalogue.Product p, State s, long now, boolean evolve) {
        long weekly = Math.max(0, p.weekly);
        double anchor = anchor(p, s);
        if (evolve) {
            double hours = s.updated <= 0 ? 0 : Math.min(48, (now - s.updated) / 3_600_000.0);
            double rev = Math.max(0.1, BzDials.mmReversionHours.get());
            s.fair = MmMath.drift(s.fair, anchor, hours, 1.0 / rev, Math.max(0, BzDials.mmVolatility.get()), rng.nextGaussian());
            s.pvol *= Math.pow(0.5, hours / 6.0);   // research: player VWAP half-life ~6h
            // event days (research s2.9): glide toward the active events' multiplier, and back to 1 after them
            double tgt = EventShocks.current().target(p.id, EventShocks::active);
            if (tgt != 1.0 || s.shock != 1.0) {
                double mins = s.updated <= 0 ? BzDials.eventShockGlideMinutes.get() * 4.0 : hours * 60.0;
                s.shock = EventShocks.glide(s.shock, tgt, mins, BzDials.eventShockGlideMinutes.get());
            }
        }
        int depth0 = MmMath.depthFor(weekly, s.fair, BzDials.mmDepthScale.get(), BzDials.mmDepthMin.get(), BzDials.mmDepthMax.get(),
                BzDials.mmLevelMaxValue.get());
        int depth = MmMath.fade(depth0, s.pvol, BzDials.mmFadeMax.get(), BzDials.mmDepthMin.get());
        double target = depth0 * Math.max(1, BzDials.mmInventoryHours.get());
        if (s.inv < 0) s.inv = target;
        if (evolve && s.updated > 0) {
            double hours = Math.min(48, (now - s.updated) / 3_600_000.0);
            s.inv = MmMath.restock(s.inv, target, hours, BzDials.mmRestockHours.get());
        }
        MmMath.Params m = new MmMath.Params();
        m.fair = s.quotedFair();
        m.inventory = s.inv;
        m.target = target;
        m.spread = MmMath.spreadFor(weekly, BzDials.mmSpreadMult.get());
        m.depth = depth;
        m.levels = Math.max(1, Math.min(8, BzDials.mmLevels.get()));
        m.step = Math.max(0, BzDials.mmStepPercent.get()) / 100.0;
        m.skewK = BzDials.mmSkew.get();
        m.shopPrice = shopPrice(p);
        m.npcSell = BzDials.mmNpcFloor.get() ? guildFloor(p, m.shopPrice) : 0;
        double cap = recipeCap(p);   // read fresh: inputs re-quoted since the last cycle count
        double refCap = ref(p) * Math.max(0.01, BzDials.mmBidMaxRef.get());
        cap = cap > 0 ? Math.min(cap, refCap) : refCap;
        if (bidsOnly(p) || asksOnly(p)) {
            // one-sided products never bid above what an NPC pays anyway (the NPC floor is no faucet)
            if (p.npcSell > 0) cap = Math.min(cap, p.npcSell);
            else m.quoteBids = false;
        }
        if (bidsOnly(p)) m.quoteAsks = false;
        // never bid at or above the Merchant Guild's standing offer: buy from the guild, sell to us would print coins
        long guild = seeder == null ? 0 : seeder.restingPrice(p.id);
        if (guild > 0) cap = Math.min(cap, (guild - 1) / 10.0);
        m.bidCap = cap;
        MmMath.Quotes q = MmMath.quote(m);
        s.bid = q.bestBid();
        s.ask = q.bestAsk();
        // a cheaper ask makes everything crafted from this product cheaper to make: queue their bids for a re-clamp
        if (graph != null && s.ask > 0) reclamp.addAll(graph.lower(p.id, s.ask / 10.0));
        s.depth = depth;
        s.target = target;
        s.spread = m.spread;
        s.bidCap = m.bidCap;
        s.shop = m.shopPrice;
        return q;
    }

    /**
     * The bid floor. Research s2.9's "Merchant Guild": standing buy orders a little ABOVE the NPC sell price for
     * every farmable (two-sided FARMING products), so a new player's first harvest always has a better buyer than
     * the NPC. Never applied where it would reach the NPC shop price (no buy-from-shop, sell-to-guild loop), and
     * every other product keeps the plain NPC floor.
     */
    static double guildFloor(Catalogue.Product p, double shop) {
        double base = p.npcSell;
        if (base <= 0 || !twoSided(p) || !"FARMING".equalsIgnoreCase(p.category)) return base;
        double prem = Math.max(0, Math.min(25, BzDials.mmGuildPremium.get())) / 100.0;
        double floor = base * (1 + prem);
        if (shop > 0 && floor >= shop * 0.99) return base;
        return floor;
    }

    static double shopPrice(Catalogue.Product p) {
        double a = p.npcBuy > 0 ? p.npcBuy : 0, b = p.shopPrice > 0 ? p.shopPrice : 0;
        // The raw data has a few "printers" (Farm Merchant wheat 2.33 vs a 6-coin NPC sell price; carrot,
        // potato, pumpkin). The shops module never sells below the NPC sell price, so neither may this
        // ceiling: capped at 2.3, the bots sold wheat at 2.3 to players who sold it straight on for 6.
        if (a > 0 && p.npcSell > a) a = p.npcSell;
        if (a > 0 && b > 0) return Math.min(a, b);
        return Math.max(a, b);
    }

    /**
     * Where fair value reverts to: the reference price, pulled toward the player-trade VWAP by at most
     * {@code mm-player-weight}. The VWAP is clamped to a third..three times the reference first, so a handful of
     * silly trades cannot drag a product anywhere; and the BID is separately capped at the reference (quote()).
     */
    double anchor(Catalogue.Product p, State s) {
        double ref = ref(p);
        if (s.vwap <= 0 || s.pvol <= 0) return ref;
        double v = Math.max(ref / 3, Math.min(ref * 3, s.vwap));
        double w = Math.min(Math.max(0, BzDials.mmPlayerWeight.get()), s.pvol / Math.max(1.0, s.depth * 24.0));
        return Math.exp((1 - w) * Math.log(ref) + w * Math.log(v));
    }

    /** Re-quote one product: drift, restock, pull old bot orders, cross player orders the new quote reaches, rest. */
    void refresh(String pid, long now) {
        Catalogue.Product p = cat.product(pid);
        OrderBook book = market.book(pid);
        book.clearBots();
        if (p == null || !p.live || !p.mm || !BzDials.mmEnabled.get()) return;
        State s = state(p);
        // the minutes since the last quote drive the simulated flow, before the new quote goes up
        double minutes = s.updated <= 0 ? 0 : Math.min(10, (now - s.updated) / 60_000.0);
        MmMath.Quotes q = quote(p, s, now, true);
        // crossing a resting player order counts toward its owner's hourly market-maker allowance, exactly like an
        // instant trade would (an owner already at the cap is skipped), so resting orders cannot bypass the cap
        java.util.function.Predicate<Order> crossable = o -> !o.bot && market.allowanceOf(o.owner, pid) > 0;
        for (MmMath.Level l : q.asks()) {
            List<OrderBook.Fill> f = book.take(Order.Side.SELL, l.qty(), l.price(), crossable, 0, market::makerRate);
            int sold = 0;
            for (OrderBook.Fill x : f) { sold += x.qty(); s.pnl += x.gross(); market.noteBotUse(x.maker().owner, pid, x.qty()); }
            s.inv -= sold;
            market.afterFills(pid, f, null);
            int rest = l.qty() - sold;
            if (rest > 0) book.add(new Order(market.nextBotId(), market.nextSeq(), pid, Order.Side.SELL, null, "Market Maker", null,
                    l.price(), rest, now, Long.MAX_VALUE, true));
        }
        for (MmMath.Level l : q.bids()) {
            List<OrderBook.Fill> f = book.take(Order.Side.BUY, l.qty(), l.price(), crossable, 0, market::makerRate);
            int bought = 0;
            for (OrderBook.Fill x : f) { bought += x.qty(); s.pnl -= x.gross(); market.noteBotUse(x.maker().owner, pid, x.qty()); }
            s.inv += bought;
            market.afterFills(pid, f, null);
            int rest = l.qty() - bought;
            if (rest > 0) book.add(new Order(market.nextBotId(), market.nextSeq(), pid, Order.Side.BUY, null, "Market Maker", null,
                    l.price(), rest, now, Long.MAX_VALUE, true));
        }
        if (q.bestBid() > 0 && q.bestAsk() > 0) market.history(pid).quoteMid((q.bestBid() + q.bestAsk()) / 2, now);
        if (minutes > 0 && twoSided(p)) flow(p, s, minutes, now);
        s.updated = now;
        s.dirty = true;
        refreshes++;
    }

    /** Simulated traders: fill player orders that beat the market maker. */
    void flow(Catalogue.Product p, State s, double minutes, long now) {
        if (!BzDials.flowEnabled.get()) return;
        double mu = p.weekly / 10_080.0 * Math.max(0, BzDials.flowScale.get()) * minutes;
        int cap = (int) Math.max(0, BzDials.flowMaxPerMinute.get() * minutes);
        int nSell = Math.min(cap, poisson(mu / 2));
        int nBuy = Math.min(cap, poisson(mu / 2));
        flowNow(p.id, nSell, nBuy, now);
    }

    /**
     * Push {@code sell} simulated units into outbidding player buy orders and buy {@code buy} from undercutting
     * offers. Simulated sellers never sell below the market maker's own fair bid, and simulated buyers never pay
     * more than its fair ask: they only ever trade INSIDE the spread, so nobody can use them to buy an item for
     * pennies or sell one for a fortune.
     */
    int flowNow(String pid, int sell, int buy, long now) {
        OrderBook book = market.book(pid);
        State s = states.get(pid);
        Catalogue.Product p = cat.product(pid);
        if (s == null || p == null || !twoSided(p)) return 0;
        long fairBid = Pricing.tenthsUp(s.quotedFair() * (1 - s.spread / 2));
        long fairAsk = Pricing.tenthsDown(s.quotedFair() * (1 + s.spread / 2));
        int moved = 0;
        if (sell > 0) {
            long botBid = book.best(Order.Side.BUY, o -> o.bot);
            long floor = Math.max(botBid > 0 ? botBid + 1 : 0, fairBid);
            if (floor > 0) {
                List<OrderBook.Fill> f = book.take(Order.Side.SELL, sell, floor, o -> !o.bot, 0, market::makerRate);
                for (OrderBook.Fill x : f) moved += x.qty();
                market.afterFills(pid, f, null);
            }
        }
        if (buy > 0) {
            long botAsk = book.best(Order.Side.SELL, o -> o.bot);
            long ceil = botAsk > 0 ? Math.min(botAsk - 1, fairAsk) : fairAsk;
            if (ceil > 0) {
                List<OrderBook.Fill> f = book.take(Order.Side.BUY, buy, ceil, o -> !o.bot, 0, market::makerRate);
                for (OrderBook.Fill x : f) moved += x.qty();
                market.afterFills(pid, f, null);
            }
        }
        flowUnits += moved;
        return moved;
    }

    private int poisson(double mean) {
        if (mean <= 0) return 0;
        if (mean > 30) return (int) Math.max(0, Math.round(mean + Math.sqrt(mean) * rng.nextGaussian()));
        double l = Math.exp(-mean), pr = 1;
        int k = 0;
        do { k++; pr *= rng.nextDouble(); } while (pr > l);
        return k - 1;
    }

    // ---- callbacks from the market -----------------------------------------------------------------------------

    void botFilled(String pid, Order.Side botSide, int qty, long gross) {
        State s = states.get(pid);
        if (s == null) return;
        if (botSide == Order.Side.SELL) { s.inv -= qty; s.pnl += gross; } else { s.inv += qty; s.pnl -= gross; }
        s.dirty = true;
    }

    void playerTrade(String pid, long priceTenths, int qty) {
        State s = states.get(pid);
        if (s == null || qty <= 0) return;
        double px = priceTenths / 10.0;
        double tot = s.pvol + qty;
        s.vwap = s.vwap <= 0 ? px : (s.vwap * s.pvol + px * qty) / tot;
        s.pvol = tot;
        s.dirty = true;
    }

    // ---- arbitrage caps ----------------------------------------------------------------------------------------

    /**
     * For every market-made product: its bid may not exceed 99% of the cheapest way to MAKE one from things the bots
     * or NPC shops sell (crafting/forge recipes, chained, and two books of the level below). Otherwise "buy the
     * inputs from the bots, craft, sell to the bots" prints money.
     *
     * <p>The costs live in a {@link CostGraph}: rebuilt from every bot ask and NPC shop price here (once per refresh
     * cycle), then kept current between cycles by {@link #quote}, which lowers a product's cost the moment its ask
     * drops and queues every product made from it for a re-clamp ({@link #drainReclamp}). The cap is read FRESH at
     * quote time, never from a snapshot. Every product with a bid is re-checked after a rebuild.
     *
     * <p>The recipe graph itself is rebuilt whenever the recipe or item registry has grown: this runs at our
     * postEnable, and modules enabled after us (crafting's reference recipes, minions, forge) register recipes in
     * THEIR postEnable. A graph frozen at our postEnable never saw those recipes, so their outputs had no cap at all
     * (BEJEWELED_HANDLE and METEOR_SHARD, depending on module order).
     */
    void computeCaps() {
        long key = registryKey();
        if (graph == null || key != graphKey) {
            if (graph != null) scanShops();   // a late module may also have added NPC shop recipes
            graph = buildGraph();
            graphKey = key;
        }
        Map<String, Double> direct = new HashMap<>();
        for (Catalogue.Product p : cat.products.values()) {
            if (!p.live) continue;
            double best = Double.MAX_VALUE;
            State s = states.get(p.id);
            if (p.mm && s != null && s.ask > 0) best = s.ask / 10.0;
            double shop = shopPrice(p);
            if (shop > 0) best = Math.min(best, shop);
            long guild = seeder == null ? 0 : seeder.restingPrice(p.id);
            if (guild > 0) best = Math.min(best, guild / 10.0);
            if (best < Double.MAX_VALUE) direct.put(p.id, best);
        }
        graph.reset(direct);
        // costs may have moved either way: every bid on the book is re-checked against its fresh cap
        for (Map.Entry<String, State> e : states.entrySet()) if (e.getValue().bid > 0) reclamp.add(e.getKey());
    }

    /** Changes whenever another module registers a recipe or an item (cells like "INK_SACK:3" parse by registry). */
    private static long registryKey() {
        return ((long) Sky.recipes().all().size() << 32) ^ (Sky.items().all().size() & 0xffffffffL);
    }

    private CostGraph buildGraph() {
        List<CostGraph.Recipe> rs = new ArrayList<>();
        for (Recipe r : Sky.recipes().all()) {
            if (r == null || r.result == null || r.amount <= 0 || (r.type != Recipe.Type.CRAFTING && r.type != Recipe.Type.FORGE)) continue;
            Map<String, Long> need = needs(r);
            if (need != null) rs.add(new CostGraph.Recipe(r.result, r.amount, need));
        }
        // enchanted-book ladders: two level-(N-1) books make one level-N book, where the enchant combines that far
        for (Catalogue.Product p : cat.products.values()) {
            if (p.bookEnchant == null || p.bookLevel <= 1 || !combinable(p.bookEnchant, p.bookLevel)) continue;
            String below = cat.bookLadder.getOrDefault(p.bookEnchant, Map.of()).get(p.bookLevel - 1);
            if (below != null) rs.add(new CostGraph.Recipe(p.id, 1, Map.of(below, 2L)));
        }
        return new CostGraph(rs);
    }

    /** Were the caps built from the recipe/item registry as it stands now (not before a late module's recipes)? */
    boolean capsCurrent() { return graph != null && graphKey == registryKey(); }

    /** The recipe cap (coins) the bots' bid must stay under right now; 0 = none. */
    private double recipeCap(Catalogue.Product p) {
        return BzDials.mmRecipeCaps.get() && graph != null ? graph.cap(p.id) : 0;
    }

    /**
     * Re-clamp queued products whose bid on the book now sits above their (fresh) recipe cap, by re-quoting them.
     * A re-quote can lower that product's own ask and queue its own outputs; this converges because a re-quote seconds
     * after the last one does not move the ask. At most {@code budget} re-quotes; the rest wait for the next tick.
     */
    void drainReclamp(long now, int budget) {
        int requoted = 0, guard = 0;
        while (!reclamp.isEmpty() && requoted < budget && guard++ < 50_000) {
            String id = reclamp.iterator().next();
            reclamp.remove(id);
            State s = states.get(id);
            Catalogue.Product p = cat.product(id);
            if (s == null || p == null || !p.live || !p.mm || s.bid <= 0) continue;
            double cap = recipeCap(p);
            if (cap <= 0 || s.bid <= Pricing.tenthsDown(cap)) continue;
            try { refresh(id, now); } catch (RuntimeException e) { Sky.log().warning("[bazaar] market maker re-clamp " + id + " failed: " + e); }
            requoted++;
        }
    }

    /**
     * Another seller (the Merchant Guild) now offers this product at {@code coins}: lower its cost in the graph and
     * re-clamp everything made from it, and re-quote the product itself if our bid on the book reached that ask.
     */
    void lowerExternal(String pid, double coins) {
        if (graph != null && coins > 0) reclamp.addAll(graph.lower(pid, coins));
        State s = states.get(pid);
        Catalogue.Product p = cat.product(pid);
        if (s != null && p != null && p.live && p.mm && s.bid > 0 && s.bid >= Pricing.tenthsDown(coins)) {
            try { refresh(pid, System.currentTimeMillis()); } catch (RuntimeException e) { Sky.log().warning("[bazaar] market maker " + pid + " failed: " + e); }
        }
    }

    /** Cheapest known cost (coins) of ONE unit from bots, NPC shops or making it; -1 = none. */
    double acquire(String id) {
        return graph == null ? -1 : graph.acquire(id);
    }

    /**
     * Can two level-(N-1) books of this enchant combine into level N? Unknown enchants are assumed combinable: a cap
     * that is not needed only makes the bots bid less, a missing one prints coins.
     */
    static boolean combinable(String enchant, int level) {
        EnchantDef d;
        try { d = Sky.items().enchant(enchant.toLowerCase(Locale.ROOT)); } catch (RuntimeException e) { d = null; }
        if (d == null) return true;
        if (d.notes != null) for (String n : d.notes) if (n != null && n.toLowerCase(Locale.ROOT).contains("cannot be combined")) return false;
        return d.maxLevel <= 0 || level <= d.maxLevel;
    }

    /** Ingredient id -> amount; null for an empty recipe or one that consumes its own result. */
    private static Map<String, Long> needs(Recipe r) {
        Map<String, Long> need = new HashMap<>();
        if (r.grid != null) for (String c : r.grid) if (c != null && !c.isBlank()) { String[] x = cell(c); need.merge(x[0], parseLong(x[1]), Long::sum); }
        if (r.inputs != null) for (String c : r.inputs) if (c != null && !c.isBlank()) { String[] x = cell(c); need.merge(x[0], parseLong(x[1]), Long::sum); }
        if (need.isEmpty() || need.containsKey(r.result)) return null;
        return need;
    }

    /** Cost (coins) of a recipe's ingredients bought the cheapest known way, or -1 if any ingredient has none. */
    double recipeCost(Recipe r) {
        Map<String, Long> need = r == null ? null : needs(r);
        return need == null || graph == null ? -1 : graph.cost(need);
    }

    /** "ID:amount" with ids that may contain ':' (core rule: a cell that IS a registered id means x1). */
    static String[] cell(String cell) {
        String c = cell.trim();
        if (c.startsWith("COINS:")) return new String[]{"COINS", c.substring(6)};
        if (Sky.items().exists(c)) return new String[]{c, "1"};
        int i = c.lastIndexOf(':');
        if (i <= 0) return new String[]{c, "1"};
        return new String[]{c.substring(0, i), c.substring(i + 1)};
    }

    static long parseLong(String s) {
        try { return Math.max(0, (long) Double.parseDouble(s.trim())); } catch (RuntimeException e) { return 0; }
    }

    double cap(String pid) { return graph == null ? 0 : graph.cap(pid); }

    long totalPnl() { long t = 0; for (State s : states.values()) t += s.pnl; return t; }

    /** Reset one product to its reference (admin). */
    void reset(String pid) {
        Catalogue.Product p = cat.product(pid);
        if (p == null) return;
        State s = new State();
        s.fair = ref(p);
        s.inv = -1;
        s.dirty = true;
        states.put(pid, s);
        refresh(pid, System.currentTimeMillis());
    }

    void setFair(String pid, double fair) {
        Catalogue.Product p = cat.product(pid);
        if (p == null) return;
        State s = state(p);
        s.fair = Math.max(0.1, fair);
        s.dirty = true;
        refresh(pid, System.currentTimeMillis());
    }
}
