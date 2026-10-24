package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The exchange. Authoritative order books in memory, MAIN THREAD ONLY (one writer, so matching needs no locks and
 * a check-then-act can never race). Every operation follows the same money-safe sequence:
 * <ol>
 *   <li>validate everything (product live, amounts, limits, coins/items present);</li>
 *   <li>take the player's side first: coins via {@code economy.withdraw} (false = nothing happens), items via
 *       removal + an immediate player-data save (so a crash cannot hand them back while the order also exists);</li>
 *   <li>match in memory;</li>
 *   <li>queue the order rows ({@link Store#flush()}) BEFORE crediting anyone, so the database never owes less than
 *       it paid;</li>
 *   <li>credit the player (coins, items through {@code Sky.loot()}).</li>
 * </ol>
 */
final class Market {

    record Result(boolean ok, String message) {
        static Result ok(String m) { return new Result(true, m); }
        static Result fail(String m) { return new Result(false, m); }
    }

    final Catalogue cat;
    final Store store;
    MarketMaker mm;
    /** Merchant Guild supply offers (null in tests): fills of guild orders are booked there. */
    Seeder seeder;

    final Map<String, OrderBook> books = new HashMap<>();
    /** Every player order that is not DONE, by id. */
    final Map<Long, Order> orders = new LinkedHashMap<>();
    final Map<UUID, List<Order>> byOwner = new HashMap<>();
    final Map<String, PriceHistory> history = new HashMap<>();
    private long nextId = 1;
    private long seq = 1;
    private long botIds = 0;
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final Map<String, Integer> botUse = new HashMap<>();
    private long botUseHour = -1;
    private final Map<Long, Long> lastPartial = new HashMap<>();

    Market(Catalogue cat, Store store) {
        this.cat = cat;
        this.store = store;
    }

    // ---- setup -------------------------------------------------------------------------------------------------

    void load() {
        nextId = store.maxOrderId() + 1;
        int open = 0;
        for (Store.OrderRow r : store.loadOrders()) {
            Order.Side side;
            try { side = Order.Side.valueOf(r.side()); } catch (IllegalArgumentException e) { continue; }
            UUID owner;
            try { owner = UUID.fromString(r.owner()); } catch (IllegalArgumentException e) { continue; }
            Order o = new Order(r.id(), seq++, r.product(), side, owner, r.ownerName(), r.profileId(), r.price(), r.amount(),
                    r.created(), r.expires(), false);
            o.filled = r.filled();
            o.itemsPending = r.itemsPending();
            o.coinsPending = r.coinsPending();
            o.taxPaid = r.taxPaid();
            o.taxBase = r.taxBase();
            try { o.state = Order.State.valueOf(r.state()); } catch (IllegalArgumentException e) { o.state = Order.State.CLOSED; }
            index(o);
            if (o.open()) { book(o.product).add(o); open++; }
        }
        long fromHour = PriceHistory.hourOf(System.currentTimeMillis()) - PriceHistory.KEEP_HOURS - 1;
        for (Map.Entry<String, List<PriceHistory.Bar>> e : store.loadBars(fromHour).entrySet()) {
            PriceHistory h = history(e.getKey());
            for (PriceHistory.Bar b : e.getValue()) h.load(b);
        }
        Sky.log().info("[bazaar] loaded " + orders.size() + " player orders (" + open + " on the book), next id " + nextId);
    }

    long nextSeq() { return seq++; }

    long nextBotId() { return -(++botIds); }

    OrderBook book(String pid) { return books.computeIfAbsent(pid, OrderBook::new); }

    PriceHistory history(String pid) { return history.computeIfAbsent(pid, k -> new PriceHistory()); }

    private void index(Order o) {
        orders.put(o.id, o);
        byOwner.computeIfAbsent(o.owner, k -> new ArrayList<>()).add(o);
    }

    private void unindex(Order o) {
        orders.remove(o.id);
        List<Order> l = byOwner.get(o.owner);
        if (l != null) { l.remove(o); if (l.isEmpty()) byOwner.remove(o.owner); }
        lastPartial.remove(o.id);
    }

    // ---- queries -----------------------------------------------------------------------------------------------

    /** Instant-buy unit price (cheapest sell offer), tenths; 0 when none. */
    long askPrice(String pid) { OrderBook b = books.get(pid); Order o = b == null ? null : b.bestAsk(); return o == null ? 0 : o.price; }

    /** Instant-sell unit price (highest buy order), tenths; 0 when none. */
    long bidPrice(String pid) { OrderBook b = books.get(pid); Order o = b == null ? null : b.bestBid(); return o == null ? 0 : o.price; }

    long avg7d(String pid) {
        long a = history(pid).avg7d(System.currentTimeMillis());
        if (a > 0) return a;
        Catalogue.Product p = cat.product(pid);
        return p == null ? 0 : Pricing.tenths(p.ref * BzDials.mmPriceScale.get());
    }

    List<Order> ordersOf(UUID u) { return byOwner.getOrDefault(u, List.of()); }

    int activeCount(UUID u) { return ordersOf(u).size(); }

    int claimableCount(UUID u) {
        int n = 0;
        for (Order o : ordersOf(u)) if (o.owes()) n++;
        return n;
    }

    /** The default tax rate right now (percent): base rate x the mayor multiplier. For display and tests. */
    double taxRate() { return clampTax(BzDials.taxPercent.get() * mayorMult()); }

    /** What THIS player pays on an instant sell right now: their Bazaar Flipper rate x the mayor multiplier. */
    double taxRate(Player p) { return clampTax(baseRate(p) * mayorMult()); }

    /** What a resting sell offer pays when it fills: its owner's rate when placed x the mayor multiplier now. */
    double makerRate(Order o) {
        double base = o.taxBase >= 0 ? o.taxBase : BzDials.taxPercent.get();
        return clampTax(base * mayorMult());
    }

    private static double clampTax(double t) { return Math.max(0, Math.min(50, t)); }

    double mayorMult() {
        String perk = BzDials.mayorTaxPerk.get();
        if (perk == null || perk.isBlank()) return 1;
        try {
            if (Sky.service(Features.Mayors.class).perkActive(perk.trim())) return Math.max(0, BzDials.mayorTaxMult.get());
        } catch (RuntimeException ignored) { }
        return 1;
    }

    /** Bazaar Flipper tier 0..2 (research s2.3): each tier is a requirement key the community-shop owner provides. */
    int flipperTier(Player p) {
        if (p == null) return 0;
        if (!met(p, BzDials.flipper1Requirement.get())) return 0;
        return met(p, BzDials.flipper2Requirement.get()) ? 2 : 1;
    }

    private static boolean met(Player p, String req) {
        if (req == null || req.isBlank()) return false;
        try { return Sky.requirements().check(p, req.trim()); } catch (RuntimeException e) { return false; }
    }

    /** The player's tax rate before any mayor: 1.25% minus 0.125% per Flipper tier (research: 1.0% at tier II). */
    double baseRate(Player p) { return Math.max(0, BzDials.taxPercent.get() - BzDials.flipperTaxStep.get() * flipperTier(p)); }

    /** Order slots: 14, +7 per Bazaar Flipper tier (research: 28 at tier II). */
    int maxOrders(Player p) { return Math.max(1, BzDials.maxOrders.get() + Math.max(0, BzDials.flipperSlots.get()) * flipperTier(p)); }

    /**
     * Instant Buy cap per click (research s2.3): 2,240 for a 64-stack item = 35 stacks, so 560 for 16-stack ender
     * pearls; essence goes up to the order maximum.
     */
    /** Max units in one Buy Order / Sell Offer (research s2.3: 71,680 for stackables, 256 for unstackables). */
    int maxOrder(String pid) {
        return Pricing.orderCap(Inv.maxStack(pid), BzDials.maxOrderAmount.get(), BzDials.maxOrderUnstackable.get());
    }

    int maxInstant(String pid) {
        if (pid != null && pid.startsWith("ESSENCE_")) return Math.max(1, BzDials.maxOrderAmount.get());
        int stack = Inv.maxStack(pid);
        return (int) Math.max(1, Math.round(BzDials.maxInstantAmount.get() * Math.min(64, stack) / 64.0));
    }

    /**
     * Profile-mode rules (research s2.1, s9.3-9.4): Bingo is locked out (dial); the Special Bazaar modes (Ironman,
     * Stranded) may only Instant Buy the listed products (Booster Cookies) and never sell or place orders. Claiming
     * and cancelling stay open to all so
     * nothing can ever be stranded in escrow. Null = allowed.
     */
    String modeBlock(Player p, String action, String pid) {
        String mode;
        try { mode = Sky.profiles().mode(p); } catch (RuntimeException e) { mode = null; }
        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("normal")) return null;
        String m = mode.toLowerCase(java.util.Locale.ROOT);
        for (String b : BzDials.blockedModes.get().split(",")) {
            if (b.trim().equalsIgnoreCase(m)) return "The Bazaar is not available on " + Text.titleCase(m) + " profiles!";
        }
        if (Bz.inList(BzDials.specialModes.get(), m)) {
            String who = Text.titleCase(m) + " profiles";
            if (!action.equals("buy") || pid == null) return who + " can only Instant Buy from the Special Bazaar!";
            if (Bz.specialProduct(pid)) return null;
            return who + " can only buy " + ironmanList() + " from the Special Bazaar!";
        }
        return null;
    }

    private static String ironmanList() {
        List<String> names = new ArrayList<>();
        for (String id : BzDials.ironmanProducts.get().split(",")) if (!id.isBlank()) names.add(Catalogue.plainName(id.trim()));
        return names.isEmpty() ? "nothing" : String.join(", ", names);
    }

    void forget(UUID u) { lastAction.remove(u); }

    private Predicate<Order> eligible(Player p) {
        if (!BzDials.blockSelfTrade.get()) return o -> true;
        UUID u = p.getUniqueId();
        return o -> o.bot || !u.equals(o.owner);
    }

    int botAllowance(Player p, String pid) {
        rollBotHour();
        int cap = BzDials.mmPlayerHourlyCap.get();
        if (cap <= 0) return Integer.MAX_VALUE;
        return Math.max(0, cap - botUse.getOrDefault(p.getUniqueId() + "|" + pid, 0));
    }

    private void useBot(UUID u, String pid, int n) {
        rollBotHour();
        botUse.merge(u + "|" + pid, n, Integer::sum);
    }

    /** Units this owner may still trade against market makers on this product this hour. */
    int allowanceOf(UUID u, String pid) {
        if (u == null) return 0;
        rollBotHour();
        int cap = BzDials.mmPlayerHourlyCap.get();
        if (cap <= 0) return Integer.MAX_VALUE;
        return Math.max(0, cap - botUse.getOrDefault(u + "|" + pid, 0));
    }

    /** A market maker crossed a resting order: that counts toward its owner's hourly allowance too. */
    void noteBotUse(UUID u, String pid, int n) { if (u != null && n > 0) useBot(u, pid, n); }

    private void rollBotHour() {
        long h = PriceHistory.hourOf(System.currentTimeMillis());
        if (h != botUseHour) { botUse.clear(); botUseHour = h; }
    }

    // ---- guards ------------------------------------------------------------------------------------------------

    /** {@code action}: "buy", "sell", "order" or null (claim/cancel housekeeping, never mode-blocked). */
    private Result guard(Player p, String pid, String action) {
        if (!BzDials.enabled.get()) return Result.fail("The Bazaar is closed for maintenance right now.");
        if (!Sky.profiles().isReady(p)) return Result.fail("Your profile is still loading!");
        if (pid != null && !cat.isLive(pid)) return Result.fail("That item is not traded on the Bazaar.");
        if (action != null) {
            String block = modeBlock(p, action, pid);
            if (block != null) return Result.fail(block);
        }
        long now = System.currentTimeMillis();
        Long last = lastAction.get(p.getUniqueId());
        if (last != null && now - last < BzDials.actionCooldownMs.get()) return Result.fail("Slow down! You're trading too fast.");
        lastAction.put(p.getUniqueId(), now);
        return null;
    }

    // ---- instant trades ----------------------------------------------------------------------------------------

    /** Quote an instant buy for display (respecting inventory room, per-click cap and the bot allowance). */
    OrderBook.Quote quoteBuy(Player p, String pid, int qty) {
        OrderBook b = books.get(pid);
        if (b == null || qty <= 0) return new OrderBook.Quote(0, 0, 0, 0);
        return b.quote(Order.Side.BUY, qty, 0, p == null ? null : eligible(p), p == null ? Integer.MAX_VALUE : botAllowance(p, pid));
    }

    OrderBook.Quote quoteSell(Player p, String pid, int qty) {
        OrderBook b = books.get(pid);
        if (b == null || qty <= 0) return new OrderBook.Quote(0, 0, 0, 0);
        return b.quote(Order.Side.SELL, qty, 0, p == null ? null : eligible(p), p == null ? Integer.MAX_VALUE : botAllowance(p, pid));
    }

    /** How much of a limit order at {@code limit} would fill immediately (price improvement preview). */
    OrderBook.Quote quoteLimit(Player p, String pid, Order.Side side, int qty, long limit) {
        OrderBook b = books.get(pid);
        if (b == null || qty <= 0 || limit <= 0) return new OrderBook.Quote(0, 0, 0, 0);
        return b.quote(side, qty, limit, eligible(p), botAllowance(p, pid));
    }

    Result instantBuy(Player p, String pid, int want) {
        Result g = guard(p, pid, "buy");
        if (g != null) return g;
        String eb = Bz.essenceBlock(p, pid);
        if (eb != null) return Result.fail(eb);
        int qty = Math.min(want, maxInstant(pid));
        if (qty <= 0) return Result.fail("Pick an amount first.");
        int room = Inv.capacity(p, pid);
        if (room <= 0) return Result.fail("You don't have any inventory space for this!");
        qty = Math.min(qty, room);
        OrderBook b = book(pid);
        Predicate<Order> el = eligible(p);
        int allow = botAllowance(p, pid);
        OrderBook.Quote q = b.quote(Order.Side.BUY, qty, 0, el, allow);
        if (q.qty() <= 0) {
            return Result.fail(allow <= 0 ? "You've bought all the market makers will sell you this hour. Try a Buy Order!"
                    : "There are no Sell Offers for " + Text.strip(Catalogue.plainName(pid)) + "!");
        }
        String name = Catalogue.plainName(pid);
        // research s2.3: charged up front a little above the current offers (bazaar.instant-buy-reserve-percent, 4%),
        // the difference refunded after the fill, so the offers moving mid-click can never under-charge
        long hold = Pricing.instantBuyHold(q.cost(), BzDials.instantBuyReservePercent.get());
        if (!Sky.economy().withdraw(p, Pricing.coins(hold), "bazaar: instant buy " + pid + " x" + q.qty())) {
            return Result.fail("You don't have enough coins! You need " + Pricing.fmtTotal(hold) + " coins.");
        }
        List<OrderBook.Fill> fills = b.take(Order.Side.BUY, q.qty(), 0, el, allow, this::makerRate);
        int got = 0;
        long paid = 0;
        for (OrderBook.Fill f : fills) { got += f.qty(); paid += f.gross(); }
        afterFills(pid, fills, p);
        store.flush();
        if (paid < hold) Sky.economy().deposit(p, Pricing.coins(hold - paid), "bazaar: instant buy price difference " + pid);
        Inv.give(p, pid, got);
        PlayerData d = PlayerData.of(p);
        d.coinsSpent += paid;
        d.itemsBought += got;
        d.trades++;
        logRow(p, "BUY", pid, got, got == 0 ? 0 : paid / got, paid);
        return Result.ok("&6[Bazaar] &7Bought &a" + Text.commasInt(got) + "&7x &f" + name + " &7for &6" + Pricing.fmtTotal(paid) + " coins&7!");
    }

    Result instantSell(Player p, String pid, int want, boolean sacks) {
        Result g = guard(p, pid, "sell");
        if (g != null) return g;
        int have = Inv.inInventory(p, pid) + (sacks ? Inv.inSacks(p, pid) : 0);
        int qty = Math.min(want, have);
        if (qty <= 0) return Result.fail("You don't have any " + Catalogue.plainName(pid) + " to sell!");
        OrderBook b = book(pid);
        Predicate<Order> el = eligible(p);
        int allow = botAllowance(p, pid);
        OrderBook.Quote q = b.quote(Order.Side.SELL, qty, 0, el, allow);
        if (q.qty() <= 0) {
            return Result.fail(allow <= 0 ? "The market makers won't buy more of this from you this hour. Try a Sell Offer!"
                    : "There are no Buy Orders for " + Catalogue.plainName(pid) + "!");
        }
        boolean fromSacks = Inv.inInventory(p, pid) < q.qty();
        int taken = Inv.take(p, pid, q.qty(), sacks);
        if (taken <= 0) return Result.fail("Couldn't take the items from your inventory.");
        Inv.persist(p, fromSacks);
        List<OrderBook.Fill> fills = b.take(Order.Side.SELL, taken, 0, el, allow, this::makerRate);
        int sold = 0;
        long gross = 0;
        for (OrderBook.Fill f : fills) { sold += f.qty(); gross += f.gross(); }
        afterFills(pid, fills, p);
        store.flush();
        if (sold < taken) giveBack(p, pid, taken - sold, fromSacks);
        long tax = Pricing.tax(gross, taxRate(p));
        long net = gross - tax;
        if (net > 0) Sky.economy().deposit(p, Pricing.coins(net), "bazaar: instant sell " + pid + " x" + sold);
        PlayerData d = PlayerData.of(p);
        d.coinsEarned += net;
        d.taxPaid += tax;
        d.itemsSold += sold;
        d.trades++;
        logRow(p, "SELL", pid, sold, sold == 0 ? 0 : gross / sold, net);
        return Result.ok("&6[Bazaar] &7Sold &a" + Text.commasInt(sold) + "&7x &f" + Catalogue.plainName(pid) + " &7for &6"
                + Pricing.fmtTotal(net) + " coins&7!" + (tax > 0 ? " &8(-" + Pricing.fmtTotal(tax) + " tax)" : ""));
    }

    /** Unsold items go back where they came from: sacks first when they were sold out of sacks, then the inventory. */
    private static void giveBack(Player p, String pid, int n, boolean sacksFirst) {
        if (n <= 0) return;
        int back = sacksFirst ? Inv.toSacks(p, pid, n) : 0;
        if (back < n) Inv.give(p, pid, n - back);
    }

    // ---- resting orders ----------------------------------------------------------------------------------------

    String validatePrice(String pid, Order.Side side, long price, int qty) {
        if (price < 1) return "The price must be at least 0.1 coins.";
        if (side == Order.Side.BUY && price > Pricing.tenths(BzDials.maxUnitPrice.get())) {
            return "The unit price can't be more than " + Pricing.fmtTotal(Pricing.tenths(BzDials.maxUnitPrice.get())) + " coins!";
        }
        if (qty < 1) return "The amount must be at least 1.";
        if (qty > maxOrder(pid)) return "You can't order more than " + Text.commasInt(maxOrder(pid)) + " at once!";
        if (side == Order.Side.SELL) {
            long avg = avg7d(pid);
            double mult = BzDials.offerCeilingMultiple.get();
            if (avg > 0 && mult > 0 && price > avg * mult) {
                return "You can't list this above " + Pricing.fmt((long) (avg * mult)) + " coins (" + Text.commas(mult) + "x the weekly average)!";
            }
            if (Pricing.total(price, qty) > Pricing.tenths(BzDials.maxOfferValue.get())) {
                return "A Sell Offer can't be worth more than " + Text.shortNum(BzDials.maxOfferValue.get()) + " coins!";
            }
        }
        return null;
    }

    Result placeBuy(Player p, String pid, int qty, long price) {
        Result g = guard(p, pid, "order");
        if (g != null) return g;
        String eb = Bz.essenceBlock(p, pid);
        if (eb != null) return Result.fail(eb);
        String bad = validatePrice(pid, Order.Side.BUY, price, qty);
        if (bad != null) return Result.fail(bad);
        int slots = maxOrders(p);
        if (activeCount(p.getUniqueId()) >= slots) {
            return Result.fail("You can't have more than " + slots + " orders at once! Claim or cancel some first.");
        }
        long escrow = Pricing.total(price, qty);
        if (!Sky.economy().withdraw(p, Pricing.coins(escrow), "bazaar: buy order " + pid + " x" + qty + " @" + Pricing.fmt(price))) {
            return Result.fail("You don't have enough coins! This order needs " + Pricing.fmtTotal(escrow) + " coins.");
        }
        long now = System.currentTimeMillis();
        Order o = new Order(nextId++, seq++, pid, Order.Side.BUY, p.getUniqueId(), p.getName(), Sky.profiles().profileId(p),
                price, qty, now, now + (long) (BzDials.expiryDays.get() * 86_400_000L), false);
        o.taxBase = baseRate(p);
        index(o);
        OrderBook b = book(pid);
        List<OrderBook.Fill> fills = b.take(Order.Side.BUY, qty, price, eligible(p), botAllowance(p, pid), this::makerRate);
        int got = 0;
        long paid = 0;
        for (OrderBook.Fill f : fills) { got += f.qty(); paid += f.gross(); }
        if (got > 0) {
            o.filled += got;
            o.itemsPending += got;
            o.coinsPending += Pricing.total(price, got) - paid;   // price improvement goes back to the buyer
            afterFills(pid, fills, p);
        }
        if (o.remaining() > 0) b.add(o); else o.state = Order.State.CLOSED;
        store.dirty(o);
        store.flush();
        PlayerData d = PlayerData.of(p);
        d.ordersPlaced++;
        d.coinsSpent += paid;
        d.itemsBought += got;
        logRow(p, "BUY_ORDER", pid, qty, price, escrow);
        String msg = "&6[Bazaar] &7Buy Order set up! &a" + Text.commasInt(qty) + "&7x &f" + Catalogue.plainName(pid)
                + " &7for &6" + Pricing.fmtTotal(escrow) + " coins&7.";
        if (got > 0) {
            long saved = Pricing.improvement(true, price, got, paid);
            msg += "\n&6[Bazaar] &a" + Text.commasInt(got) + "&7 filled instantly "
                    + (saved > 0 ? "at a better price, saving &6" + Pricing.fmtTotal(saved) + " coins&7!" : "at your price.");
            Result c = claim(p, o, true);
            msg += "\n" + (c.ok() ? c.message() : "&6[Bazaar] &c" + c.message() + " &7(it waits in Manage Orders)");
        }
        return Result.ok(msg);
    }

    Result placeSell(Player p, String pid, int qty, long price, boolean sacks) {
        Result g = guard(p, pid, "order");
        if (g != null) return g;
        String bad = validatePrice(pid, Order.Side.SELL, price, qty);
        if (bad != null) return Result.fail(bad);
        int slots = maxOrders(p);
        if (activeCount(p.getUniqueId()) >= slots) {
            return Result.fail("You can't have more than " + slots + " orders at once! Claim or cancel some first.");
        }
        int inInv = Inv.inInventory(p, pid);
        int have = inInv + (sacks ? Inv.inSacks(p, pid) : 0);
        if (have < qty) return Result.fail("You only have " + Text.commasInt(have) + "x " + Catalogue.plainName(pid) + "!");
        int taken = Inv.take(p, pid, qty, sacks);
        if (taken < qty) {
            if (taken > 0) Inv.give(p, pid, taken);
            return Result.fail("Couldn't take the items from your inventory.");
        }
        Inv.persist(p, inInv < qty);
        long now = System.currentTimeMillis();
        Order o = new Order(nextId++, seq++, pid, Order.Side.SELL, p.getUniqueId(), p.getName(), Sky.profiles().profileId(p),
                price, qty, now, now + (long) (BzDials.expiryDays.get() * 86_400_000L), false);
        o.taxBase = baseRate(p);
        index(o);
        OrderBook b = book(pid);
        List<OrderBook.Fill> fills = b.take(Order.Side.SELL, qty, price, eligible(p), botAllowance(p, pid), this::makerRate);
        int got = 0;
        long gross = 0;
        for (OrderBook.Fill f : fills) { got += f.qty(); gross += f.gross(); }
        if (got > 0) {
            long tax = Pricing.tax(gross, makerRate(o));
            o.filled += got;
            o.coinsPending += gross - tax;
            o.taxPaid += tax;
            afterFills(pid, fills, p);
        }
        if (o.remaining() > 0) b.add(o); else o.state = Order.State.CLOSED;
        store.dirty(o);
        store.flush();
        PlayerData d = PlayerData.of(p);
        d.ordersPlaced++;
        d.itemsSold += got;
        logRow(p, "SELL_OFFER", pid, qty, price, Pricing.total(price, qty));
        String msg = "&6[Bazaar] &7Sell Offer set up! &a" + Text.commasInt(qty) + "&7x &f" + Catalogue.plainName(pid)
                + " &7at &6" + Pricing.fmt(price) + " coins &7each.";
        if (got > 0) {
            long extra = Pricing.improvement(false, price, got, gross);
            msg += "\n&6[Bazaar] &a" + Text.commasInt(got) + "&7 sold instantly "
                    + (extra > 0 ? "at a better price, earning &6" + Pricing.fmtTotal(extra) + " coins&7 more!" : "at your price.");
            Result c = claim(p, o, true);
            msg += "\n" + (c.ok() ? c.message() : "&6[Bazaar] &c" + c.message() + " &7(it waits in Manage Orders)");
        }
        return Result.ok(msg);
    }

    private void closeRemainder(Order o) {
        int r = o.remaining();
        if (r > 0) {
            if (o.side == Order.Side.BUY) o.coinsPending += Pricing.total(o.price, r);
            else o.itemsPending += r;
        }
        o.state = Order.State.CLOSED;
    }

    Result cancel(Player p, Order o) {
        Result g = guard(p, null, null);
        if (g != null) return g;
        if (o == null || !p.getUniqueId().equals(o.owner) || !orders.containsKey(o.id)) return Result.fail("That order doesn't exist anymore.");
        if (!o.open()) return Result.fail("That order isn't active anymore.");
        book(o.product).remove(o);
        int left = o.remaining();
        closeRemainder(o);
        store.dirty(o);
        store.flush();
        logRow(p, "CANCEL", o.product, left, o.price, o.side == Order.Side.BUY ? Pricing.total(o.price, left) : 0);
        Result c = claim(p, o, true);
        String what = o.side == Order.Side.BUY ? "&6" + Pricing.fmtTotal(Pricing.total(o.price, left)) + " coins" : "&a" + Text.commasInt(left) + "&7x &f" + Catalogue.plainName(o.product);
        return Result.ok("&6[Bazaar] &7Cancelled! Refunded " + what + "&7." + (o.owes() ? " &8(" + Text.strip(c.message()) + ")" : ""));
    }

    /** Pay out what an order holds for its owner, as far as inventory (and sacks) allow. */
    Result claim(Player p, Order o, boolean quiet) {
        if (o == null || !p.getUniqueId().equals(o.owner) || !orders.containsKey(o.id)) return Result.fail("That order doesn't exist anymore.");
        if (!Objects.equals(o.profileId, Sky.profiles().profileId(p))) return Result.fail("This order belongs to another of your profiles!");
        long coins = o.coinsPending;
        int items = o.itemsPending;
        if (coins <= 0 && items <= 0) return Result.fail("There is nothing to claim yet!");
        if (items > 0 && !Sky.items().exists(o.product)) {
            // the item was removed from the game since the order was placed: never mark it claimed into nothing
            return Result.fail("That item can't be handed out right now - ask an admin.");
        }
        int give = 0;
        if (items > 0) give = Math.min(items, Inv.capacity(p, o.product));
        o.coinsPending = 0;
        o.itemsPending -= give;
        int sacked = 0;
        if (o.itemsPending > 0 && PlayerData.of(p).claimToSacks) {
            sacked = Inv.toSacks(p, o.product, o.itemsPending);
            o.itemsPending -= sacked;
        }
        if (coins <= 0 && give <= 0 && sacked <= 0) return Result.fail("Your inventory is full! Make room to claim your items.");
        boolean done = o.state != Order.State.OPEN && !o.owes();
        if (done) o.state = Order.State.DONE;
        store.dirty(o);
        store.flush();
        if (done) unindex(o);
        if (coins > 0) {
            Sky.economy().deposit(p, Pricing.coins(coins), "bazaar: claim order #" + o.id + " " + o.product);
            PlayerData d = PlayerData.of(p);
            if (o.side == Order.Side.SELL) d.coinsEarned += coins;
        }
        if (give > 0) Inv.give(p, o.product, give);
        List<String> parts = new ArrayList<>();
        if (coins > 0) parts.add("&6" + Pricing.fmtTotal(coins) + " coins");
        if (give + sacked > 0) parts.add("&a" + Text.commasInt(give + sacked) + "&7x &f" + Catalogue.plainName(o.product) + (sacked > 0 ? " &8(" + Text.commasInt(sacked) + " to sacks)" : ""));
        String msg = "&6[Bazaar] &7Claimed " + String.join("&7 and ", parts) + "&7!";
        if (o.itemsPending > 0) msg += " &c" + Text.commasInt(o.itemsPending) + " more waiting - make room in your inventory!";
        if (!quiet) Msg.send(p, msg);
        return Result.ok(msg);
    }

    Result claimAll(Player p) {
        int n = 0, failed = 0;
        String pid = Sky.profiles().profileId(p);
        for (Order o : new ArrayList<>(ordersOf(p.getUniqueId()))) {
            if (!o.owes() || !Objects.equals(o.profileId, pid)) continue;
            Result r = claim(p, o, false);
            if (r.ok()) n++; else failed++;
        }
        if (n == 0) return Result.fail(failed > 0 ? "Couldn't claim anything - is your inventory full?" : "There is nothing to claim!");
        return Result.ok("&6[Bazaar] &7Claimed from &a" + n + " &7order" + (n == 1 ? "" : "s") + "!");
    }

    /** Sell every live product found in the inventory (and/or sacks) into the buy orders. */
    Result sellAll(Player p, boolean inventory, boolean sacks) {
        Result g = guard(p, null, "sell");
        if (g != null) return g;
        Map<String, Integer> found = sellable(p, inventory, sacks);
        if (found.isEmpty()) return Result.fail("You don't have anything the Bazaar is buying!");
        long total = 0;
        int kinds = 0, items = 0;
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            lastAction.remove(p.getUniqueId());
            int before = Inv.inInventory(p, e.getKey()) + Inv.inSacks(p, e.getKey());
            long purse = Pricing.tenths(Sky.economy().purse(p));
            Result r = instantSellFrom(p, e.getKey(), e.getValue(), inventory, sacks);
            if (r.ok()) {
                kinds++;
                items += before - (Inv.inInventory(p, e.getKey()) + Inv.inSacks(p, e.getKey()));
                total += Math.max(0, Pricing.tenths(Sky.economy().purse(p)) - purse);
            }
        }
        if (kinds == 0) return Result.fail("Nobody is buying what you have right now.");
        return Result.ok("&6[Bazaar] &7Sold &a" + Text.commasInt(items) + " &7items (" + kinds + " kinds) for &6" + Pricing.fmtTotal(total) + " coins&7!");
    }

    /** Instant-sell restricted to a source: inventory only, sacks only, or both. */
    private Result instantSellFrom(Player p, String pid, int qty, boolean inventory, boolean sacks) {
        if (inventory) return instantSell(p, pid, qty, sacks);
        // sacks only: pull from sacks into a sale without touching the inventory
        Result g = guard(p, pid, "sell");
        if (g != null) return g;
        int have = Inv.inSacks(p, pid);
        qty = Math.min(qty, have);
        OrderBook b = book(pid);
        Predicate<Order> el = eligible(p);
        int allow = botAllowance(p, pid);
        OrderBook.Quote q = b.quote(Order.Side.SELL, qty, 0, el, allow);
        if (q.qty() <= 0) return Result.fail("No buy orders.");
        int taken;
        try { taken = Math.max(0, Sky.service(Features.Sacks.class).remove(p, pid, q.qty())); } catch (RuntimeException e) { taken = 0; }
        if (taken <= 0) return Result.fail("Couldn't take from your sacks.");
        Sky.profiles().saveNow(p);
        List<OrderBook.Fill> fills = b.take(Order.Side.SELL, taken, 0, el, allow, this::makerRate);
        int sold = 0;
        long gross = 0;
        for (OrderBook.Fill f : fills) { sold += f.qty(); gross += f.gross(); }
        afterFills(pid, fills, p);
        store.flush();
        if (sold < taken) { int back = Inv.toSacks(p, pid, taken - sold); if (back < taken - sold) Inv.give(p, pid, taken - sold - back); }
        long tax = Pricing.tax(gross, taxRate(p));
        if (gross - tax > 0) Sky.economy().deposit(p, Pricing.coins(gross - tax), "bazaar: sell sacks " + pid + " x" + sold);
        PlayerData d = PlayerData.of(p);
        d.coinsEarned += gross - tax;
        d.taxPaid += tax;
        d.itemsSold += sold;
        d.trades++;
        logRow(p, "SELL", pid, sold, sold == 0 ? 0 : gross / sold, gross - tax);
        return Result.ok("sold");
    }

    /** What a Sell-All would sell: product -> amount, only products with at least one buy order. */
    Map<String, Integer> sellable(Player p, boolean inventory, boolean sacks) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (inventory) {
            for (org.bukkit.inventory.ItemStack s : p.getInventory().getStorageContents()) {
                if (s == null || s.getType().isAir()) continue;
                String id = Sky.items().idOf(s);
                if (id == null || !cat.isLive(id) || bidPrice(id) <= 0) continue;
                if (Sky.items().data(s).uuid != null) continue;
                out.merge(id, s.getAmount(), Integer::sum);
            }
        }
        if (sacks && Inv.hasSacks()) {
            for (Catalogue.Product pr : cat.products.values()) {
                if (!pr.live || bidPrice(pr.id) <= 0) continue;
                int n = Inv.inSacks(p, pr.id);
                if (n > 0) out.merge(pr.id, n, Integer::sum);
            }
        }
        return out;
    }

    // ---- fills: bookkeeping shared by every path ---------------------------------------------------------------

    /** After any matching: history, maker rows, market-maker inventory, notifications, bot allowance. */
    void afterFills(String pid, List<OrderBook.Fill> fills, Player taker) {
        if (fills.isEmpty()) return;
        long now = System.currentTimeMillis();
        PriceHistory h = history(pid);
        for (OrderBook.Fill f : fills) {
            Order m = f.maker();
            h.trade(f.price(), f.qty(), now);
            if (m.bot) {
                if (m.guild) { if (seeder != null) seeder.filled(pid, f.qty(), f.gross()); }
                else if (mm != null) mm.botFilled(pid, m.side, f.qty(), f.gross());
                if (taker != null) useBot(taker.getUniqueId(), pid, f.qty());
            } else {
                store.dirty(m);
                notifyFill(m, f.qty());
                if (taker != null && mm != null) mm.playerTrade(pid, f.price(), f.qty());
            }
        }
    }

    private void notifyFill(Order o, int qty) {
        Player p = Bukkit.getPlayer(o.owner);
        if (p == null || !p.isOnline() || !Sky.profiles().isReady(p)) return;
        PlayerData d = PlayerData.of(p);
        if (!d.notifyFills || !globalFillAlerts(p)) return;
        String kind = o.side == Order.Side.BUY ? "&aBuy Order" : "&6Sell Offer";
        if (o.remaining() <= 0) {
            d.ordersFilled++;
            sendClickable(p, "&6[Bazaar] &eYour " + kind + " &efor &a" + Text.commasInt(o.amount) + "&ex &f" + Catalogue.plainName(o.product)
                    + " &ewas filled! &7(click to claim)");
            if (d.sounds) Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
            return;
        }
        if (!d.notifyPartial) return;
        long now = System.currentTimeMillis();
        Long last = lastPartial.get(o.id);
        if (last != null && now - last < BzDials.partialNotifySeconds.get() * 1000L) return;
        lastPartial.put(o.id, now);
        sendClickable(p, "&6[Bazaar] &7Your " + kind + " &7for &f" + Catalogue.plainName(o.product) + " &7is &a"
                + Math.round(o.fillFraction() * 100) + "% &7filled (&a" + Text.commasInt(o.filled) + "&7/" + Text.commasInt(o.amount) + ").");
    }

    private static final org.bukkit.NamespacedKey FILL_ALERTS = new org.bukkit.NamespacedKey("skyclone", "setting.notify.bazaar_filled");

    /**
     * The SkyBlock Settings menu's "Bazaar Order Alerts" toggle (the menus module mirrors every setting onto the
     * player's PDC as {@code skyclone:setting.<key>}). Missing = on. The Bazaar's own setting must be on as well.
     */
    static boolean globalFillAlerts(Player p) {
        try {
            String v = p.getPersistentDataContainer().get(FILL_ALERTS, org.bukkit.persistence.PersistentDataType.STRING);
            return v == null || !v.equalsIgnoreCase("false");
        } catch (RuntimeException e) {
            return true;
        }
    }

    static void sendClickable(Player p, String legacy) {
        p.sendMessage(Text.c(legacy).clickEvent(ClickEvent.runCommand("/bz orders"))
                .hoverEvent(HoverEvent.showText(Text.c("&eClick to manage your orders!"))));
    }

    // ---- housekeeping ------------------------------------------------------------------------------------------

    /** Expire old orders: off the book, remainder pending for the owner. Returns how many expired. */
    int expire(long now) {
        int n = 0;
        for (Order o : new ArrayList<>(orders.values())) {
            if (!o.open() || o.expires > now) continue;
            book(o.product).remove(o);
            closeRemainder(o);
            store.dirty(o);
            n++;
            Player p = Bukkit.getPlayer(o.owner);
            if (p != null && p.isOnline()) {
                sendClickable(p, "&6[Bazaar] &cYour " + (o.side == Order.Side.BUY ? "Buy Order" : "Sell Offer") + " for &f"
                        + Catalogue.plainName(o.product) + " &cexpired. Click to claim what's left.");
            }
        }
        if (n > 0) store.flush();
        return n;
    }

    void logRow(Player p, String kind, String pid, long qty, long price, long total) {
        store.log(new Store.LogRow(p.getUniqueId(), Sky.profiles().profileId(p), System.currentTimeMillis(), kind, pid, qty, price, total));
    }

    /** Self-consistency: every OPEN order is on its book and every player order on a book is OPEN. Null = fine. */
    String audit() {
        int onBooks = 0;
        for (OrderBook b : books.values()) {
            for (Order o : b.bids()) { if (!o.bot) { onBooks++; if (!o.open() || !orders.containsKey(o.id)) return "order " + o + " on the book but not open/indexed"; } }
            for (Order o : b.asks()) { if (!o.bot) { onBooks++; if (!o.open() || !orders.containsKey(o.id)) return "order " + o + " on the book but not open/indexed"; } }
        }
        int open = 0;
        for (Order o : orders.values()) {
            if (o.filled < 0 || o.filled > o.amount || o.itemsPending < 0 || o.coinsPending < 0) return "bad numbers on " + o;
            if (o.open()) open++;
            if (o.state == Order.State.DONE) return "DONE order still indexed: " + o;
        }
        if (open != onBooks) return open + " open orders but " + onBooks + " player orders on the books";
        return null;
    }

    long escrowCoins() { long t = 0; for (Order o : orders.values()) t += o.escrowCoins(); return t; }

    long escrowItems() { long t = 0; for (Order o : orders.values()) t += o.escrowItems(); return t; }

    Order order(long id) { return orders.get(id); }
}
