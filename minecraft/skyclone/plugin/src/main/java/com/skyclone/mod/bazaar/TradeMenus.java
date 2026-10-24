package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The trading screens: Instant Buy / Instant Sell amount pickers, and the two-step Buy Order / Sell Offer wizards
 * (amount -> price -> confirm) with the genre's price helpers ("Top Order +0.1", "5% of Spread"...).
 * Price protection (research s2.3): instant buys above 2x and instant sells under 50% of the 7-day average ask for
 * confirmation, at most once per cooldown.
 */
final class TradeMenus {

    private TradeMenus() {}

    // ---- instant buy -------------------------------------------------------------------------------------------

    /** Profile-mode rules up front (Ironman/Bingo), so nobody fills in a wizard only to be refused at the end. */
    private static boolean blocked(Player p, String action, String id) {
        // "buyorder" = the Buy Order wizard: the profile-mode rule for orders, plus the buy-side essence gate
        String b = Bz.market.modeBlock(p, action.equals("buyorder") ? "order" : action, id);
        // buying essence needs Catacombs 20 (research 02 s8); selling it never does
        if (b == null && (action.equals("buy") || action.equals("buyorder"))) b = Bz.essenceBlock(p, id);
        if (b == null) return false;
        Msg.error(p, b);
        return true;
    }

    static void instantBuy(Player p, String id) {
        if (blocked(p, "buy", id)) return;
        Menu m = new Menu(BazaarMenus.clip(Catalogue.plainName(id) + Ui.ARROW + "Instant Buy"), 4);
        m.chrome();
        int stack = Inv.maxStack(id);
        int room = Inv.capacity(p, id);
        int max = Math.min(room, Bz.market.maxInstant(id));
        option(m, p, id, 10, Material.GOLD_NUGGET, "&aBuy only one!", 1);
        option(m, p, id, 12, Material.GOLD_INGOT, "&aBuy a stack!", stack);
        option(m, p, id, 14, Material.GOLD_BLOCK, "&aFill my inventory!", Math.max(1, max));
        m.set(16, Icons.of(Material.OAK_SIGN, "&aCustom Amount", "&7Buy up to &a" + Text.commasInt(max) + " &7at once.", "", "&eClick to type an amount!"),
                cl -> Sky.menus().inputNumber(cl.player(), "How many to buy?", n -> {
                    if (n == null) { instantBuy(cl.player(), id); return; }
                    int q = (int) Math.floor(n);
                    if (q < 1) { Msg.error(cl.player(), "Enter a whole number of at least 1."); return; }
                    doBuy(cl.player(), id, q);
                }));
        m.back(Catalogue.plainName(id), () -> BazaarMenus.product(p, id));
        m.open(p);
    }

    private static void option(Menu m, Player p, String id, int slot, Material mat, String title, int amount) {
        OrderBook.Quote q = Bz.market.quoteBuy(p, id, Math.min(amount, Math.max(1, Inv.capacity(p, id))));
        List<String> l = new ArrayList<>();
        l.add("&8" + Catalogue.plainName(id));
        l.add("");
        l.add("&7Amount: &a" + Text.commasInt(amount) + "&7x");
        if (q.qty() <= 0) {
            l.add("");
            l.add(Inv.capacity(p, id) <= 0 ? "&cYour inventory is full!" : "&cNothing for sale right now!");
        } else {
            if (q.qty() < amount) l.add("&7Available now: &e" + Text.commasInt(q.qty()) + "&7x");
            l.add("&7Per unit: &6" + Pricing.fmt(q.avg()) + " coins" + (q.worst() != q.best() ? " &8(avg)" : ""));
            l.add("&7Price: &6" + Pricing.fmtTotal(q.cost()) + " coins");
            l.add("");
            double purse = Sky.economy().purse(p);
            long hold = Pricing.instantBuyHold(q.cost(), BzDials.instantBuyReservePercent.get());
            if (hold > q.cost()) l.add("&8Held up front: " + Pricing.fmtTotal(hold) + " coins, the rest refunded");
            l.add(purse * 10 >= hold ? "&eClick to buy now!" : "&cYou can't afford this!");
        }
        ItemStack icon = Icons.amount(Icons.of(mat, title, l), Math.min(64, Math.max(1, amount)));
        m.set(slot, icon, cl -> doBuy(cl.player(), id, amount));
    }

    static void doBuy(Player p, String id, int qty) {
        int q = Math.min(qty, Math.min(Bz.market.maxInstant(id), Inv.capacity(p, id)));
        if (q <= 0) { Msg.error(p, "You don't have any inventory space for this!"); return; }
        OrderBook.Quote quote = Bz.market.quoteBuy(p, id, q);
        if (quote.qty() <= 0) { Ui.result(p, Bz.market.instantBuy(p, id, q)); return; }
        PlayerData d = PlayerData.of(p);
        long now = System.currentTimeMillis();
        long avg = Bz.market.avg7d(id);
        double mult = BzDials.warnBuyMultiple.get();
        if (d.priceWarnings && avg > 0 && mult > 0 && quote.avg() > avg * mult && now >= d.warnBuyUntil) {
            Sky.menus().confirm(p, "Price Warning!", List.of(
                    "&cThis is far above the usual price!",
                    "",
                    "&7You pay: &6" + Pricing.fmt(quote.avg()) + " coins &7each",
                    "&77-day average: &6" + Pricing.fmt(avg) + " coins",
                    "&7That's &c" + String.format(java.util.Locale.US, "%.1fx", quote.avg() / (double) avg) + " &7the average.",
                    "",
                    "&7Total: &6" + Pricing.fmtTotal(quote.cost()) + " coins",
                    "&8You won't be warned again for " + Text.duration(BzDials.warnCooldownSeconds.get()) + "."),
                    () -> { PlayerData.of(p).warnBuyUntil = System.currentTimeMillis() + BzDials.warnCooldownSeconds.get() * 1000L; execBuy(p, id, q); },
                    null);
            return;
        }
        if (d.confirmAbove > 0 && quote.cost() > Pricing.tenths(d.confirmAbove)) {
            Sky.menus().confirm(p, "Confirm Purchase", List.of(
                    "&7Buy &a" + Text.commasInt(quote.qty()) + "&7x &f" + Catalogue.plainName(id),
                    "&7for &6" + Pricing.fmtTotal(quote.cost()) + " coins&7?",
                    "",
                    "&8You confirm purchases above " + Text.shortNum(d.confirmAbove) + " coins.",
                    "&8Change this in Bazaar settings."),
                    () -> execBuy(p, id, q), null);
            return;
        }
        execBuy(p, id, q);
    }

    private static void execBuy(Player p, String id, int q) {
        Ui.result(p, Bz.market.instantBuy(p, id, q));
        BazaarMenus.product(p, id).open(p);
    }

    // ---- instant sell ------------------------------------------------------------------------------------------

    static void instantSell(Player p, String id) {
        if (blocked(p, "sell", id)) return;
        Menu m = new Menu(BazaarMenus.clip(Catalogue.plainName(id) + Ui.ARROW + "Instant Sell"), 4);
        m.chrome();
        int inv = Inv.inInventory(p, id), sk = Inv.inSacks(p, id);
        sellOption(m, p, id, 11, Material.CHEST, "&6Sell your inventory!", inv, false);
        if (Inv.hasSacks()) sellOption(m, p, id, 13, Material.BUNDLE, "&6Sell inventory and sacks!", inv + sk, true);
        m.set(15, Icons.of(Material.OAK_SIGN, "&6Custom Amount", "&7You have &a" + Text.commasInt(inv + sk) + "&7.", "", "&eClick to type an amount!"),
                cl -> Sky.menus().inputNumber(cl.player(), "How many to sell?", n -> {
                    if (n == null) { instantSell(cl.player(), id); return; }
                    int q = (int) Math.floor(n);
                    if (q < 1) { Msg.error(cl.player(), "Enter a whole number of at least 1."); return; }
                    doSell(cl.player(), id, q, true);
                }));
        m.back(Catalogue.plainName(id), () -> BazaarMenus.product(p, id));
        m.open(p);
    }

    private static void sellOption(Menu m, Player p, String id, int slot, Material mat, String title, int amount, boolean sacks) {
        List<String> l = new ArrayList<>();
        l.add("&8" + Catalogue.plainName(id));
        l.add("");
        l.add("&7Amount: &a" + Text.commasInt(amount) + "&7x");
        if (amount <= 0) {
            l.add("");
            l.add("&cYou don't have any!");
            m.set(slot, Icons.of(mat, title, l));
            return;
        }
        OrderBook.Quote q = Bz.market.quoteSell(p, id, amount);
        double tax = Bz.market.taxRate(p);
        if (q.qty() <= 0) {
            l.add("");
            l.add("&cNo one is buying right now!");
        } else {
            if (q.qty() < amount) l.add("&7Sellable now: &e" + Text.commasInt(q.qty()) + "&7x");
            l.add("&7Per unit: &6" + Pricing.fmt(q.avg()) + " coins" + (q.worst() != q.best() ? " &8(avg)" : ""));
            l.add("&7You get: &6" + Pricing.fmtTotal(Pricing.afterTax(q.cost(), tax)) + " coins");
            l.add("&8(after " + Text.commas(tax) + "% tax)");
            l.add("");
            l.add("&eClick to sell!");
        }
        m.set(slot, Icons.amount(Icons.of(mat, title, l), Math.min(64, Math.max(1, amount))), cl -> doSell(cl.player(), id, amount, sacks));
    }

    static void doSell(Player p, String id, int qty, boolean sacks) {
        OrderBook.Quote quote = Bz.market.quoteSell(p, id, qty);
        PlayerData d = PlayerData.of(p);
        long now = System.currentTimeMillis();
        long avg = Bz.market.avg7d(id);
        double frac = BzDials.warnSellFraction.get();
        if (quote.qty() > 0 && d.priceWarnings && avg > 0 && frac > 0 && quote.avg() < avg * frac && now >= d.warnSellUntil) {
            Sky.menus().confirm(p, "Price Warning!", List.of(
                    "&cThis is far below the usual price!",
                    "",
                    "&7You get: &6" + Pricing.fmt(quote.avg()) + " coins &7each",
                    "&77-day average: &6" + Pricing.fmt(avg) + " coins",
                    "",
                    "&7A &6Sell Offer &7would likely earn more.",
                    "&8You won't be warned again for " + Text.duration(BzDials.warnCooldownSeconds.get()) + "."),
                    () -> { PlayerData.of(p).warnSellUntil = System.currentTimeMillis() + BzDials.warnCooldownSeconds.get() * 1000L; execSell(p, id, qty, sacks); },
                    null);
            return;
        }
        execSell(p, id, qty, sacks);
    }

    private static void execSell(Player p, String id, int qty, boolean sacks) {
        Ui.result(p, Bz.market.instantSell(p, id, qty, sacks));
        BazaarMenus.product(p, id).open(p);
    }

    // ---- buy order wizard --------------------------------------------------------------------------------------

    static void buyOrderAmount(Player p, String id) {
        if (blocked(p, "buyorder", id)) return;
        Menu m = new Menu(BazaarMenus.clip("How many do you want?"), 4);
        m.chrome();
        // research s2.3 presets: 64 / 160 / 1,024 for stackables, 1 / 4 / 64 for unstackables (enchanted books...)
        int[] pre = Pricing.orderPresets(Inv.maxStack(id));
        boolean single = pre[0] == 1;
        int cap = Bz.market.maxOrder(id);
        amountPreset(m, 10, Material.PAPER, single ? "&aBuy one!" : "&aBuy a stack!", pre[0], cl -> buyOrderPrice(cl.player(), id, pre[0]));
        amountPreset(m, 12, Material.MAP, single ? "&aBuy a few!" : "&aBuy a big stack!", pre[1], cl -> buyOrderPrice(cl.player(), id, pre[1]));
        amountPreset(m, 14, Material.FILLED_MAP, single ? "&aBuy a lot!" : "&aBuy a thousand!", pre[2], cl -> buyOrderPrice(cl.player(), id, pre[2]));
        m.set(16, Icons.of(Material.OAK_SIGN, "&aCustom Amount", "&7Up to &a" + Text.commasInt(cap) + "&7 per order.", "", "&eClick to type an amount!"),
                cl -> Sky.menus().inputNumber(cl.player(), "How many to order?", n -> {
                    if (n == null) { buyOrderAmount(cl.player(), id); return; }
                    int q = (int) Math.floor(n);
                    if (q < 1 || q > cap) { Msg.error(cl.player(), "Pick between 1 and " + Text.commasInt(cap) + "."); return; }
                    buyOrderPrice(cl.player(), id, q);
                }));
        m.back(Catalogue.plainName(id), () -> BazaarMenus.product(p, id));
        m.open(p);
    }

    private static void amountPreset(Menu m, int slot, Material mat, String title, int amount, java.util.function.Consumer<com.skyclone.api.ui.MenuClick> click) {
        m.set(slot, Icons.amount(Icons.of(mat, title, "&7Amount: &a" + Text.commasInt(amount) + "&7x", "", "&eClick to proceed!"), Math.min(64, amount)), click);
    }

    static void buyOrderPrice(Player p, String id, int qty) {
        Menu m = new Menu(BazaarMenus.clip("How much do you want to pay?"), 4);
        m.chrome();
        long b = Bz.market.bidPrice(id), a = Bz.market.askPrice(id), avg = Bz.market.avg7d(id);
        if (b > 0) {
            pricePreset(m, p, 10, Material.GOLD_NUGGET, "&6Same as Top Order", b, qty, Order.Side.BUY, id, "&7Join the highest Buy Order.");
            long plus = b + 1;
            if (a <= 0 || plus < a) pricePreset(m, p, 12, Material.GOLD_INGOT, "&6Top Order +0.1", plus, qty, Order.Side.BUY, id, "&7Beat every other buyer by 0.1.");
            else m.set(12, Icons.of(Material.BARRIER, "&cTop Order +0.1", "&7That would meet the cheapest", "&7Sell Offer - use &aBuy Instantly&7!"));
            if (a > 0) {
                long fiveP = b + Math.max(1, (a - b) / 20);
                if (fiveP < a) pricePreset(m, p, 14, Material.GOLD_BLOCK, "&65% of Spread", fiveP, qty, Order.Side.BUY, id, "&7Top order plus 5% of the gap", "&7to the cheapest offer.");
            }
        } else {
            long sug = a > 0 ? Math.max(1, Math.min(a - 1, avg > 0 ? avg : a - 1)) : Math.max(1, avg);
            if (sug > 0) pricePreset(m, p, 12, Material.GOLD_INGOT, "&6Suggested Price", sug, qty, Order.Side.BUY, id, "&7No Buy Orders yet. This is", "&7the weekly average price.");
        }
        m.set(16, Icons.of(Material.OAK_SIGN, "&6Custom Price", "&7Any price, to one decimal.", "", "&eClick to type a price!"),
                cl -> Sky.menus().inputNumber(cl.player(), "Price per unit?", n -> {
                    if (n == null) { buyOrderPrice(cl.player(), id, qty); return; }
                    long t = Pricing.tenths(n);
                    if (t < 1) { Msg.error(cl.player(), "The price must be at least 0.1 coins."); return; }
                    confirm(cl.player(), Order.Side.BUY, id, qty, t, false);
                }));
        m.set(m.closeSlot() - 1, Icons.back("Pick an amount"), cl -> buyOrderAmount(cl.player(), id));
        m.open(p);
    }

    // ---- sell offer wizard -------------------------------------------------------------------------------------

    static void sellOfferAmount(Player p, String id) {
        if (blocked(p, "order", id)) return;
        Menu m = new Menu(BazaarMenus.clip("How many do you want to offer?"), 4);
        m.chrome();
        int inv = Inv.inInventory(p, id), sk = Inv.inSacks(p, id);
        m.set(11, Icons.amount(Icons.of(Material.CHEST, "&6Offer your inventory", "&7Amount: &a" + Text.commasInt(inv) + "&7x", "", inv > 0 ? "&eClick to proceed!" : "&cYou have none!"),
                Math.min(64, Math.max(1, inv))), inv > 0 ? cl -> sellOfferPrice(cl.player(), id, inv, false) : null);
        if (Inv.hasSacks()) {
            m.set(13, Icons.amount(Icons.of(Material.BUNDLE, "&6Offer inventory and sacks", "&7Amount: &a" + Text.commasInt(inv + sk) + "&7x", "", inv + sk > 0 ? "&eClick to proceed!" : "&cYou have none!"),
                    Math.min(64, Math.max(1, inv + sk))), inv + sk > 0 ? cl -> sellOfferPrice(cl.player(), id, inv + sk, true) : null);
        }
        m.set(15, Icons.of(Material.OAK_SIGN, "&6Custom Amount", "&7You have &a" + Text.commasInt(inv + sk) + "&7.", "", "&eClick to type an amount!"),
                cl -> Sky.menus().inputNumber(cl.player(), "How many to offer?", n -> {
                    if (n == null) { sellOfferAmount(cl.player(), id); return; }
                    int q = (int) Math.floor(n);
                    if (q < 1) { Msg.error(cl.player(), "Enter a whole number of at least 1."); return; }
                    sellOfferPrice(cl.player(), id, q, Inv.inInventory(cl.player(), id) < q);
                }));
        m.back(Catalogue.plainName(id), () -> BazaarMenus.product(p, id));
        m.open(p);
    }

    static void sellOfferPrice(Player p, String id, int qty, boolean sacks) {
        Menu m = new Menu(BazaarMenus.clip("At what price are you selling?"), 4);
        m.chrome();
        long b = Bz.market.bidPrice(id), a = Bz.market.askPrice(id), avg = Bz.market.avg7d(id);
        if (a > 0) {
            pricePreset(m, p, 10, Material.GOLD_NUGGET, "&6Same as Best Offer", a, qty, Order.Side.SELL, id, "&7Join the cheapest Sell Offer.");
            long minus = a - 1;
            if (minus > b && minus > 0) pricePreset(m, p, 12, Material.GOLD_INGOT, "&6Best Offer -0.1", minus, qty, Order.Side.SELL, id, "&7Undercut every other seller by 0.1.");
            else m.set(12, Icons.of(Material.BARRIER, "&cBest Offer -0.1", "&7That would meet the highest", "&7Buy Order - use &6Sell Instantly&7!"));
            if (b > 0) {
                long tenP = a - Math.max(1, (a - b) / 10);
                if (tenP > b) pricePreset(m, p, 14, Material.GOLD_BLOCK, "&610% of Spread", tenP, qty, Order.Side.SELL, id, "&7Best offer minus 10% of the gap", "&7to the highest buy order.");
            }
        } else {
            long sug = Math.max(b + 1, avg > 0 ? avg : b + 1);
            if (sug > 0) pricePreset(m, p, 12, Material.GOLD_INGOT, "&6Suggested Price", sug, qty, Order.Side.SELL, id, "&7No Sell Offers yet. This is", "&7the weekly average price.");
        }
        final boolean sk = sacks;
        m.set(16, Icons.of(Material.OAK_SIGN, "&6Custom Price", "&7Any price, to one decimal.", "", "&eClick to type a price!"),
                cl -> Sky.menus().inputNumber(cl.player(), "Price per unit?", n -> {
                    if (n == null) { sellOfferPrice(cl.player(), id, qty, sk); return; }
                    long t = Pricing.tenths(n);
                    if (t < 1) { Msg.error(cl.player(), "The price must be at least 0.1 coins."); return; }
                    confirm(cl.player(), Order.Side.SELL, id, qty, t, sk);
                }));
        m.set(m.closeSlot() - 1, Icons.back("Pick an amount"), cl -> sellOfferAmount(cl.player(), id));
        m.open(p);
    }

    private static void pricePreset(Menu m, Player p, int slot, Material mat, String title, long price, int qty, Order.Side side, String id, String... why) {
        List<String> l = new ArrayList<>();
        for (String w : why) l.add(w);
        l.add("");
        l.add("&7Price per unit: &6" + Pricing.fmt(price) + " coins");
        l.add("&7Amount: &a" + Text.commasInt(qty) + "&7x");
        long total = Pricing.total(price, qty);
        if (side == Order.Side.BUY) {
            l.add("&7Total: &6" + Pricing.fmtTotal(total) + " coins");
            if (Sky.economy().purse(p) * 10 < total) { l.add(""); l.add("&cYou can't afford this!"); }
        } else {
            double tax = Bz.market.taxRate(p);
            l.add("&7You'd receive: &6" + Pricing.fmtTotal(Pricing.afterTax(total, tax)) + " coins");
            l.add("&8(after " + Text.commas(tax) + "% tax)");
        }
        l.add("");
        l.add("&eClick to use this price!");
        final boolean sacks = side == Order.Side.SELL && Inv.inInventory(p, id) < qty;
        m.set(slot, Icons.of(mat, title, l), cl -> confirm(cl.player(), side, id, qty, price, sacks));
    }

    // ---- confirm -----------------------------------------------------------------------------------------------

    static void confirm(Player p, Order.Side side, String id, int qty, long price, boolean sacks) {
        String bad = Bz.market.validatePrice(id, side, price, qty);
        if (bad != null) { Msg.error(p, bad); return; }
        boolean buy = side == Order.Side.BUY;
        Menu m = new Menu(buy ? "Confirm Buy Order" : "Confirm Sell Offer", 3);
        m.chrome();
        long total = Pricing.total(price, qty);
        double tax = Bz.market.taxRate(p);
        List<String> l = new ArrayList<>();
        l.add(buy ? "&aBuy Order" : "&6Sell Offer");
        l.add("");
        l.add("&7Amount: &a" + Text.commasInt(qty) + "&7x");
        l.add("&7Price per unit: &6" + Pricing.fmt(price) + " coins");
        if (buy) l.add("&7Total cost: &6" + Pricing.fmtTotal(total) + " coins &8(paid now)");
        else {
            l.add("&7Total value: &6" + Pricing.fmtTotal(total) + " coins");
            l.add("&7You'd receive: &6" + Pricing.fmtTotal(Pricing.afterTax(total, tax)) + " coins &8(" + Text.commas(tax) + "% tax)");
            if (sacks) l.add("&8Takes from your sacks too.");
        }
        l.add("&7Expires in: &a" + Text.duration((long) (BzDials.expiryDays.get() * 86400)));
        OrderBook.Quote now = Bz.market.quoteLimit(p, id, buy ? Order.Side.BUY : Order.Side.SELL, qty, price);
        if (now.qty() > 0) {
            l.add("");
            boolean better = Pricing.improvement(buy, price, now.qty(), now.cost()) > 0;
            boolean all = now.qty() >= qty;
            l.add("&e" + Text.commasInt(now.qty()) + " &7of these fill &einstantly&7 at " + (better ? "a" : "your"));
            l.add(better ? "&7better price" + (all ? "." : "; the rest waits on the book.")
                         : "&7price" + (all ? "." : "; the rest waits on the book."));
        }
        long avg = Bz.market.avg7d(id);
        if (avg > 0 && ((buy && price > avg * 2) || (!buy && price * 2 < avg))) {
            l.add("");
            l.add("&c⚠ Far from the 7-day average (" + Pricing.fmt(avg) + ")!");
        }
        m.set(13, Ui.product(id, Catalogue.name(id), l));
        m.set(11, Icons.of(Material.LIME_TERRACOTTA, buy ? "&aSubmit Buy Order" : "&aSubmit Sell Offer", "&7" + Text.commasInt(qty) + "x @ " + Pricing.fmt(price), "", "&eClick to confirm!"),
                cl -> {
                    Market.Result r = buy ? Bz.market.placeBuy(cl.player(), id, qty, price) : Bz.market.placeSell(cl.player(), id, qty, price, sacks);
                    Ui.result(cl.player(), r);
                    if (r.ok()) OrdersMenu.open(cl.player(), null); else BazaarMenus.product(cl.player(), id).open(cl.player());
                });
        m.set(15, Icons.of(Material.RED_TERRACOTTA, "&cCancel", "&7Back to the product."), cl -> BazaarMenus.product(cl.player(), id).open(cl.player()));
        m.open(p);
    }
}
