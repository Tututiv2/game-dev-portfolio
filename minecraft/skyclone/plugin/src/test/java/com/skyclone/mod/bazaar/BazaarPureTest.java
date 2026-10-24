package com.skyclone.mod.bazaar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Bazaar's pure core with no server: money maths, price-time matching, market-maker invariants over thousands
 * of random inputs, price history, and the shipped catalogue JSON.
 */
class BazaarPureTest {

    static Order ord(long id, Order.Side side, long price, int amt) {
        return new Order(id, id, "X", side, UUID.randomUUID(), "p" + id, "prof", price, amt, 0, Long.MAX_VALUE, false);
    }

    static Order bot(long id, Order.Side side, long price, int amt) {
        return new Order(-id, id, "X", side, null, "mm", null, price, amt, 0, Long.MAX_VALUE, true);
    }

    @Test
    void taxMatchesResearch() {
        assertEquals(125, Pricing.tax(10_000, 1.25));          // 1.25% of 1,000 coins = 12.5
        assertEquals(9_875, Pricing.afterTax(10_000, 1.25));
        assertEquals(9_900, Pricing.afterTax(10_000, 1.0));    // fully upgraded flipper rate
        assertEquals(1, Pricing.tax(1, 1.25));                 // rounds up: never gives away a fraction
        assertEquals(0, Pricing.tax(0, 1.25));
    }

    @Test
    void parseAndFormat() {
        assertEquals(123, Pricing.parse("12.3"));
        assertEquals(15_000, Pricing.parse("1.5k"));
        assertEquals(20_000_000, Pricing.parse("2m"));
        assertEquals(12_345, Pricing.parse("1,234.5"));
        assertEquals(-1, Pricing.parse("abc"));
        assertEquals(-1, Pricing.parse("-5"));
        assertEquals(-1, Pricing.parse("NaN"));
        assertEquals("1,234.5", Pricing.fmt(12_345));
        assertEquals("1,234", Pricing.fmtTotal(12_340));
    }

    @Test
    void crossingAtOwnPriceIsNotABetterPrice() {
        // playtest 2.2: a sell offer at 2.2 that filled against a 2.2 bid was announced as "a better price".
        assertEquals(0, Pricing.improvement(false, 22, 13, Pricing.total(22, 13)));
        assertEquals(0, Pricing.improvement(true, 22, 13, Pricing.total(22, 13)));
        assertEquals(13, Pricing.improvement(false, 22, 13, Pricing.total(23, 13)));  // sold 0.1 higher x13
        assertEquals(13, Pricing.improvement(true, 22, 13, Pricing.total(21, 13)));   // bought 0.1 lower x13
        assertEquals(0, Pricing.improvement(true, 22, 0, 0));
    }

    @Test
    void priceTimePriorityAndPartialFills() {
        OrderBook b = new OrderBook("X");
        Order a1 = ord(1, Order.Side.SELL, 100, 10);
        Order a2 = ord(2, Order.Side.SELL, 100, 10);
        Order a3 = ord(3, Order.Side.SELL, 99, 5);
        Order a4 = ord(4, Order.Side.SELL, 120, 50);
        b.add(a1); b.add(a2); b.add(a3); b.add(a4);
        assertEquals(99, b.bestAsk().price);
        OrderBook.Quote q = b.quote(Order.Side.BUY, 20, 0, null, Integer.MAX_VALUE);
        assertEquals(20, q.qty());
        assertEquals(5 * 99 + 15 * 100, q.cost());
        List<OrderBook.Fill> f = b.take(Order.Side.BUY, 20, 0, null, Integer.MAX_VALUE, 1.25);
        assertEquals(List.of(a3, a1, a2), f.stream().map(OrderBook.Fill::maker).toList());
        assertEquals(Order.State.CLOSED, a3.state);
        assertEquals(Order.State.CLOSED, a1.state);
        assertEquals(5, a2.filled);
        assertEquals(Order.State.OPEN, a2.state);
        assertEquals(Pricing.afterTax(1000, 1.25), a1.coinsPending);
        assertEquals(5 + 50, b.volume(Order.Side.SELL));
        // limit stops the walk
        List<OrderBook.Fill> g = b.take(Order.Side.BUY, 100, 110, null, Integer.MAX_VALUE, 1.25);
        assertEquals(5, g.stream().mapToInt(OrderBook.Fill::qty).sum());
        assertEquals(50, b.volume(Order.Side.SELL));
    }

    @Test
    void buyMakersGetItemsAndBotAllowanceCaps() {
        OrderBook b = new OrderBook("X");
        Order mm = bot(1, Order.Side.BUY, 200, 1000);
        Order pl = ord(2, Order.Side.BUY, 150, 10);
        b.add(mm); b.add(pl);
        // a seller limited to 3 units from the market maker falls through to the player order
        List<OrderBook.Fill> f = b.take(Order.Side.SELL, 8, 0, null, 3, 1.25);
        assertEquals(3, f.get(0).qty());
        assertSame(mm, f.get(0).maker());
        assertEquals(5, f.get(1).qty());
        assertEquals(5, pl.itemsPending);
        assertEquals(0, mm.itemsPending, "bots never accrue claimables");
    }

    @Test
    void selfTradeFilter() {
        OrderBook b = new OrderBook("X");
        Order mine = ord(1, Order.Side.SELL, 10, 10);
        Order theirs = ord(2, Order.Side.SELL, 12, 10);
        b.add(mine); b.add(theirs);
        UUID me = mine.owner;
        OrderBook.Quote q = b.quote(Order.Side.BUY, 5, 0, o -> o.bot || !me.equals(o.owner), Integer.MAX_VALUE);
        assertEquals(60, q.cost());
    }

    @Test
    void levelsAggregate() {
        OrderBook b = new OrderBook("X");
        b.add(ord(1, Order.Side.BUY, 50, 10));
        b.add(ord(2, Order.Side.BUY, 50, 5));
        b.add(bot(3, Order.Side.BUY, 49, 100));
        List<OrderBook.Level> l = b.levels(Order.Side.BUY, 7);
        assertEquals(2, l.size());
        assertEquals(15, l.get(0).amount());
        assertEquals(2, l.get(0).orders());
        assertTrue(l.get(1).hasBot());
        assertEquals(1, b.clearBots());
        assertEquals(1, b.levels(Order.Side.BUY, 7).size());
    }

    @Test
    void marketMakerInvariantsHoldForRandomInputs() {
        Random r = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            MmMath.Params p = new MmMath.Params();
            p.fair = Math.exp(r.nextDouble() * 20 - 3);           // 0.05 .. 24M coins
            p.target = 1 + r.nextInt(5000);
            p.inventory = r.nextDouble() * p.target * 4;
            p.spread = MmMath.spreadFor((long) Math.exp(r.nextDouble() * 20), 0.5 + r.nextDouble());
            p.depth = 1 + r.nextInt(500);
            p.levels = 1 + r.nextInt(5);
            p.npcSell = r.nextBoolean() ? p.fair * (0.2 + r.nextDouble()) : 0;
            p.shopPrice = r.nextInt(4) == 0 ? p.fair * (0.5 + r.nextDouble() * 2) : 0;
            p.bidCap = r.nextInt(5) == 0 ? p.fair * (0.3 + r.nextDouble()) : 0;
            MmMath.Quotes q = MmMath.quote(p);
            if (q.bestBid() > 0 && q.bestAsk() > 0) assertTrue(q.bestBid() < q.bestAsk(), "crossed quote at i=" + i);
            for (MmMath.Level b : q.bids()) for (MmMath.Level a : q.asks()) assertTrue(b.price() < a.price());
            if (p.shopPrice > 0 && q.bestAsk() > 0) assertTrue(q.bestAsk() <= Math.max(2, Pricing.tenthsDown(p.shopPrice)), "ask above shop at i=" + i);
            if (p.bidCap > 0 && q.bestBid() > 1) assertTrue(q.bestBid() <= Pricing.tenthsDown(p.bidCap), "bid above recipe cap at i=" + i);
            if (p.npcSell > 0 && p.shopPrice <= 0 && p.bidCap <= 0 && q.bestBid() > 0)
                assertTrue(q.bestBid() >= Pricing.tenthsUp(p.npcSell), "bid below NPC floor at i=" + i);
            int asked = q.asks().stream().mapToInt(MmMath.Level::qty).sum();
            assertTrue(asked <= Math.floor(p.inventory), "asks more than the stock at i=" + i);
        }
    }

    @Test
    void skewMovesQuotesTheRightWay() {
        MmMath.Params p = new MmMath.Params();
        p.fair = 100; p.target = 100; p.spread = 0.08; p.depth = 10;
        p.inventory = 100;
        long mid0 = MmMath.quote(p).bestBid();
        p.inventory = 300;   // overstocked: sell more, so quote lower
        assertTrue(MmMath.quote(p).bestBid() < mid0);
        p.inventory = 10;    // short: quote higher
        assertTrue(MmMath.quote(p).bestBid() > mid0);
    }

    @Test
    void driftRevertsAndRestocks() {
        double f = 300;
        for (int i = 0; i < 500; i++) f = MmMath.drift(f, 100, 1, 1.0 / 24, 0, 0);
        assertEquals(100, f, 0.5);
        assertTrue(MmMath.drift(1e9, 100, 0, 0.1, 0, 0) <= 300.0001, "clamped to 3x the anchor");
        assertEquals(50, MmMath.restock(0, 100, 6, 6), 1e-9);   // one half-life
    }

    @Test
    void oneSidedQuotesStayOneSided() {
        MmMath.Params p = new MmMath.Params();
        p.fair = 1000; p.target = 100; p.inventory = 100; p.spread = 0.1; p.depth = 10; p.npcSell = 3;
        p.quoteBids = false;
        MmMath.Quotes askOnly = MmMath.quote(p);
        assertTrue(askOnly.bids().isEmpty(), "ask-only product must not bid");
        assertFalse(askOnly.asks().isEmpty());
        p.quoteBids = true; p.quoteAsks = false; p.bidCap = p.npcSell;
        MmMath.Quotes bidOnly = MmMath.quote(p);
        assertTrue(bidOnly.asks().isEmpty(), "bid-only product must not sell");
        assertEquals(30, bidOnly.bestBid(), "bid-only product bids exactly the NPC price");
    }

    @Test
    void fadeOutShrinksDepthAsPlayersTrade() {
        assertEquals(100, MmMath.fade(100, 0, 0.75, 2));
        int some = MmMath.fade(100, 2400, 0.75, 2);           // a day of the maker's depth in player volume: half
        assertEquals(50, some);
        assertEquals(25, MmMath.fade(100, 1e12, 0.75, 2), "never fades past the dial");
        assertEquals(2, MmMath.fade(2, 1e12, 0.75, 2), "never below the minimum");
    }

    @Test
    void eachMakerPaysItsOwnTaxRate() {
        OrderBook b = new OrderBook("X");
        Order flipper = ord(1, Order.Side.SELL, 100, 10);
        flipper.taxBase = 1.0;                                 // Bazaar Flipper II
        Order plain = ord(2, Order.Side.SELL, 100, 10);       // -1: the default rate
        b.add(flipper); b.add(plain);
        b.take(Order.Side.BUY, 20, 0, null, Integer.MAX_VALUE, o -> o.taxBase >= 0 ? o.taxBase : 1.25);
        assertEquals(Pricing.afterTax(1000, 1.0), flipper.coinsPending);
        assertEquals(Pricing.afterTax(1000, 1.25), plain.coinsPending);
        assertEquals(990, flipper.coinsPending, "1.0% of 100 coins is 1 coin");
    }

    @Test
    void untrustworthySnapshotPricesNeverBecomeCheapBotStock() throws IOException {
        for (JsonElement e : read("products.json").getAsJsonArray()) {
            JsonObject p = e.getAsJsonObject();
            String id = p.get("id").getAsString();
            boolean mm = !p.has("mm") || p.get("mm").getAsBoolean();
            String sides = p.has("mmSides") ? p.get("mmSides").getAsString() : null;
            double ref = p.get("ref").getAsDouble(), bid = p.get("refBid").getAsDouble(), ask = p.get("refAsk").getAsDouble();
            double npc = p.get("npcSell").getAsDouble();
            if ("ask".equals(sides)) assertTrue(ask > 0, id + " sells from an ask that does not exist");
            if ("bid".equals(sides)) assertTrue(npc > 0, id + " is bid-only with no NPC price to bid");
            if (!mm || sides != null) continue;
            // a two-sided market maker under one coin with no NPC value must come from a sane two-sided snapshot
            if (ref < 1 && npc <= 0) {
                assertTrue(bid > 0 && ask > 0 && ask <= bid * 1.5, id + " is quoted at " + ref + " from a lowball snapshot " + bid + "/" + ask);
            }
            // a lowball snapshot is re-derived (recipe / book neighbour), and never valued above what sellers asked
            if (bid > 0 && ask > 0 && bid < ask / 20) assertTrue(ref <= ask + 0.1, id + " re-derived above its own ask");
        }
    }

    @Test
    void priceHistory() {
        PriceHistory h = new PriceHistory();
        long t0 = 1_000L * 3_600_000L;
        h.trade(100, 10, t0);
        h.trade(200, 10, t0 + 1000);
        assertEquals(150, h.avg7d(t0 + 2000));
        h.quoteMid(500, t0 + 3_600_000L);           // a quiet hour shows its quoted mid
        assertEquals(List.of(150L, 500L), h.series(t0 + 3_600_000L, 2));
        assertEquals(150, h.avg7d(t0 + 3_600_000L), "trades outrank quotes in the average");
        assertEquals((500 - 150) / 150.0, h.change24h(t0 + 3_600_000L), 1e-9);
        assertEquals(20, h.volume(t0 + 3_600_000L, 24));
    }

    // ---- shipped data ------------------------------------------------------------------------------------------

    static JsonElement read(String f) throws IOException {
        Path p = Path.of("src/main/resources/data/bazaar/" + f);
        if (!Files.exists(p)) p = Path.of("plugin/src/main/resources/data/bazaar/" + f);
        return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
    }

    @Test
    void catalogueHangsTogether() throws IOException {
        JsonArray products = read("products.json").getAsJsonArray();
        JsonObject groups = read("groups.json").getAsJsonObject();
        assertEquals(2197, products.size(), "the research snapshot has 2,197 products");
        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonElement e : products) byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        assertEquals(6, groups.getAsJsonArray("categories").size());
        Set<String> seen = new HashSet<>();
        for (JsonElement ge : groups.getAsJsonArray("groups")) {
            JsonObject g = ge.getAsJsonObject();
            String key = g.get("key").getAsString();
            for (JsonElement pe : g.getAsJsonArray("products")) {
                String id = pe.getAsString();
                assertTrue(byId.containsKey(id), key + " lists unknown " + id);
                assertTrue(seen.add(id), id + " in two groups");
                assertEquals(key, byId.get(id).get("group").getAsString());
                assertEquals(g.get("category").getAsString(), byId.get(id).get("category").getAsString());
            }
            assertTrue(g.has("name") || g.has("titleIds"), key + " has no way to be named");
        }
        assertEquals(byId.keySet(), seen);
        for (JsonObject p : byId.values()) {
            double ref = p.get("ref").getAsDouble();
            assertTrue(ref > 0 && Double.isFinite(ref), p + " has a bad reference price");
            double npc = p.get("npcSell").getAsDouble();
            boolean shopBelow = p.has("npcBuy") && p.get("npcBuy").getAsDouble() < npc * 1.05;
            if (npc > 0 && !shopBelow) assertTrue(ref >= npc * 1.05 - 0.1, p.get("id") + " priced under its NPC floor");
        }
        // research numbers survive the generator
        assertEquals(1280.0, byId.get("ENCHANTED_DIAMOND").get("npcSell").getAsDouble());
        assertEquals("MINING", byId.get("ENCHANTED_DIAMOND").get("category").getAsString());
        assertEquals("FARMING", byId.get("ENCHANTED_WHEAT").get("category").getAsString());
        assertEquals("ENCHANTMENTS", byId.get("ENCHANTMENT_SHARPNESS_6").get("category").getAsString());
        assertEquals("GEM_RUBY", byId.get("PERFECT_RUBY_GEM").get("group").getAsString());
    }

    // ---- recipe caps (CostGraph) ----------------------------------------------------------------------------------

    static CostGraph meteorGraph() {
        // the live failure: METEOR_SHARD = 40 SILVER_FANG + 20 STARFALL + 10 TENTACLE_MEAT, and FANG is itself made
        return new CostGraph(List.of(
                new CostGraph.Recipe("METEOR_SHARD", 1, Map.of("SILVER_FANG", 40L, "STARFALL", 20L, "TENTACLE_MEAT", 10L)),
                new CostGraph.Recipe("SILVER_FANG", 1, Map.of("WOLF_TOOTH", 5L)),
                new CostGraph.Recipe("BOOK_2", 1, Map.of("BOOK_1", 2L)),
                new CostGraph.Recipe("BOOK_3", 1, Map.of("BOOK_2", 2L))));
    }

    @Test
    void recipeCapIsTheCheapestWayToMakeIt() {
        CostGraph g = meteorGraph();
        g.reset(Map.of("SILVER_FANG", 6000.0, "WOLF_TOOTH", 1000.0, "STARFALL", 3000.0, "TENTACLE_MEAT", 5000.0));
        assertEquals(5000.0, g.acquire("SILVER_FANG"), 1e-9, "5 teeth beat buying a fang");
        assertEquals(40 * 5000 + 20 * 3000 + 10 * 5000, g.makeCost("METEOR_SHARD"), 1e-6);
        assertEquals(g.makeCost("METEOR_SHARD") * CostGraph.CAP_FRACTION, g.cap("METEOR_SHARD"), 1e-6);
        assertEquals(0, g.cap("STARFALL"), "nothing makes starfall: no cap");
    }

    /** The bug: an input re-quoted cheaper mid-cycle left the output's bid above the new input cost. */
    @Test
    void aCheaperInputNamesEveryOutputToReclampTransitively() {
        CostGraph g = meteorGraph();
        g.reset(Map.of("SILVER_FANG", 6000.0, "WOLF_TOOTH", 1000.0, "STARFALL", 3000.0, "TENTACLE_MEAT", 5000.0,
                "BOOK_1", 100.0));
        double before = g.cap("METEOR_SHARD");
        Set<String> touched = g.lower("WOLF_TOOTH", 800.0);
        assertTrue(touched.contains("SILVER_FANG") && touched.contains("METEOR_SHARD"), "chain not followed: " + touched);
        assertTrue(g.cap("METEOR_SHARD") < before);
        assertEquals(40 * 4000 + 20 * 3000 + 10 * 5000, g.makeCost("METEOR_SHARD"), 1e-6);
        assertTrue(g.lower("WOLF_TOOTH", 900.0).isEmpty(), "a dearer ask lowers nothing");
        assertTrue(g.lower("BOOK_1", 50.0).containsAll(Set.of("BOOK_2", "BOOK_3")), "book ladders chain too");
        assertEquals(4 * 50.0 * CostGraph.CAP_FRACTION, g.cap("BOOK_3"), 1e-6);
    }

    /** End to end on the pure parts: whatever the inputs' asks do, a bid quoted under the fresh cap never pays. */
    @Test
    void quotesUnderTheFreshCapNeverPayForCraftAndSell() {
        Random r = new Random(7);
        CostGraph g = meteorGraph();
        Map<String, Double> asks = new HashMap<>(Map.of("SILVER_FANG", 6000.0, "WOLF_TOOTH", 1000.0, "STARFALL", 3000.0,
                "TENTACLE_MEAT", 5000.0));
        g.reset(asks);
        for (int i = 0; i < 2000; i++) {
            // an input re-quotes somewhere between -20% and +20%
            String in = List.of("SILVER_FANG", "WOLF_TOOTH", "STARFALL", "TENTACLE_MEAT").get(r.nextInt(4));
            g.lower(in, asks.get(in) * (0.8 + r.nextDouble() * 0.4));
            MmMath.Params p = new MmMath.Params();
            p.fair = 400_000 * (0.5 + r.nextDouble()); p.target = 10; p.inventory = 10; p.spread = 0.2; p.depth = 1;
            p.bidCap = g.cap("METEOR_SHARD");
            MmMath.Quotes q = MmMath.quote(p);
            double inputs = g.cost(Map.of("SILVER_FANG", 40L, "STARFALL", 20L, "TENTACLE_MEAT", 10L));
            assertTrue(q.bestBid() / 10.0 <= inputs, "bid " + q.bestBid() + " pays for inputs costing " + inputs + " at i=" + i);
        }
    }

    @Test
    void aCapUnderOneTickPostsNoBid() {
        MmMath.Params p = new MmMath.Params();
        p.fair = 1; p.target = 10; p.inventory = 10; p.spread = 0.1; p.depth = 5; p.bidCap = 0.04;
        assertTrue(MmMath.quote(p).bids().isEmpty(), "a 0.1 coin bid on something made for 0.04 is arbitrage");
        assertFalse(MmMath.quote(p).asks().isEmpty());
    }

    @Test
    void aCycleThatMakesMoreThanItEatsStillTerminates() {
        CostGraph g = new CostGraph(List.of(
                new CostGraph.Recipe("B", 2, Map.of("A", 1L)),
                new CostGraph.Recipe("A", 1, Map.of("B", 1L))));
        g.reset(Map.of("A", 100.0));
        assertTrue(g.acquire("A") > 0 && g.acquire("B") > 0);
        g.lower("A", 50.0);   // must return, not spin
        assertTrue(g.acquire("A") > 0);
    }

    @Test
    void eventShocksParseStackAndGlide() {
        EventShocks ev = EventShocks.parse(" jerry = white_gift , RED_GIFT : 0.75 ; spooky=GREEN_CANDY,RED_GIFT:0.5;"
                + "broken;nofactor=X:;neg=Y:-2;huge=Z:99");
        assertEquals(3, ev.all().size(), "malformed, factorless and negative entries are skipped, the rest kept: " + ev.all());
        assertTrue(ev.touches("WHITE_GIFT"), "ids are upper-cased");
        assertEquals(1.0, ev.target("WHITE_GIFT", e -> false), 1e-9, "no active event = no shock");
        assertEquals(0.75, ev.target("WHITE_GIFT", e -> e.equals("jerry")), 1e-9);
        assertEquals(0.375, ev.target("RED_GIFT", e -> true), 1e-9, "two active events multiply");
        assertEquals(EventShocks.MAX, ev.target("Z", e -> true), 1e-9, "factor clamped");
        assertEquals(1.0, ev.target("UNLISTED", e -> true), 1e-9);
        // glide: one half-life covers half the distance, never overshoots, and lands exactly on the target
        assertEquals(0.875, EventShocks.glide(1.0, 0.75, 20, 20), 1e-9);
        double s = 1.0;
        for (int i = 0; i < 200; i++) { double n = EventShocks.glide(s, 0.75, 5, 20); assertTrue(n <= s && n >= 0.75); s = n; }
        assertEquals(0.75, s, 1e-9);
        for (int i = 0; i < 200; i++) s = EventShocks.glide(s, 1.0, 5, 20);
        assertEquals(1.0, s, 1e-9, "after the event the shock glides back out");
        assertEquals(0.9, EventShocks.glide(0.9, 0.5, 0, 20), 1e-9, "no time, no move");
        assertEquals(0, EventShocks.parse(null).all().size());
    }

    /** Wave-3 playtest: "Empty = everyone" could not be typed, and "none" was stored literally and failed CLOSED. */
    @Test
    void accessGateWordsAndLevels() {
        for (String open : new String[] {null, "", "  ", "none", "NONE", "off", "everyone", "0", "-", "\"\"", "''", "\"none\""})
            assertEquals("", Gate.key(open), "'" + open + "' must mean everyone");
        assertEquals("sblevel:3", Gate.key("3"));
        assertEquals("sblevel:7", Gate.key(" 7 "));
        assertEquals("sblevel:7", Gate.key("sblevel:7"), "a real key passes through");
        assertEquals("sblevel:3&cookie", Gate.key("\"sblevel:3&cookie\""), "quotes stripped, key kept");
        assertEquals("everyone", Gate.label("off"));
        assertEquals("sblevel:2", Gate.label("2"));
    }

    @Test
    void itemDependentLimitsMatchResearch() {
        // research 05 s2.3: 71,680 per order for stackables, 256 for unstackables; presets 64/160/1,024 vs 1/4/64
        assertEquals(71_680, Pricing.orderCap(64, 71_680, 256));
        assertEquals(71_680, Pricing.orderCap(16, 71_680, 256));
        assertEquals(256, Pricing.orderCap(1, 71_680, 256));
        assertArrayEquals(new int[] {64, 160, 1024}, Pricing.orderPresets(64));
        assertArrayEquals(new int[] {64, 160, 1024}, Pricing.orderPresets(16));
        assertArrayEquals(new int[] {1, 4, 64}, Pricing.orderPresets(1));
        // Instant Buy holds 4% above the quote up front; the refund is hold - paid
        assertEquals(10_400, Pricing.instantBuyHold(10_000, 4));
        assertEquals(1_042, Pricing.instantBuyHold(1_001, 4));        // rounds the hold UP, never under-holds
        assertEquals(10_000, Pricing.instantBuyHold(10_000, 0));
        assertEquals(0, Pricing.instantBuyHold(0, 4));
    }

    @Test
    void essenceBuyGateCoversEssenceButForest() {
        // research 02 s8: buying essence needs Catacombs 20, Forest Essence exempt
        assertTrue(Gate.essenceGated("ESSENCE_WITHER", "ESSENCE_FOREST"));
        assertTrue(Gate.essenceGated("ESSENCE_UNDEAD", "ESSENCE_FOREST"));
        assertFalse(Gate.essenceGated("ESSENCE_FOREST", "ESSENCE_FOREST"));
        assertFalse(Gate.essenceGated("ESSENCE_FOREST", " essence_forest , ESSENCE_ICE"));
        assertFalse(Gate.essenceGated("ENCHANTED_DIAMOND", ""));
        assertFalse(Gate.essenceGated(null, null));
    }
}
