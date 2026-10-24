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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * "Your Bazaar Orders": every order with fill %, what is waiting to be claimed, and expiry. Left-click claims,
 * right-click cancels (refund straight back), shift-click on a Buy Order with items waiting FLIPS it: claims them
 * and opens a Sell Offer for exactly that many. The menu redraws every second, so fills show up while it is open.
 */
final class OrdersMenu {

    private OrdersMenu() {}

    static void open(Player p, String onlyProduct) {
        if (!Bz.access(p, true, true)) return;
        Menu m = new Menu(onlyProduct == null ? "Your Bazaar Orders" : BazaarMenus.clip("Orders" + Ui.ARROW + Catalogue.plainName(onlyProduct)), 6);
        m.chrome();
        m.drawer(mm -> draw(mm, p, onlyProduct)).refreshEvery(20);
        m.open(p);
    }

    private static void draw(Menu m, Player p, String only) {
        for (int s : Ui.INNER) m.set(s, null);
        List<Order> list = new ArrayList<>();
        for (Order o : Bz.market.ordersOf(p.getUniqueId())) if (only == null || o.product.equals(only)) list.add(o);
        list.sort(Comparator.comparing((Order o) -> !o.owes()).thenComparingLong(o -> -o.created));
        String profile = Sky.profiles().profileId(p);
        long now = System.currentTimeMillis();
        int k = 0;
        for (Order o : list) {
            if (k >= Ui.INNER.length) break;
            m.set(Ui.INNER[k++], icon(o, profile, now), cl -> click(cl.player(), o, cl.right(), cl.shift(), only));
        }
        if (list.isEmpty()) {
            m.set(22, Icons.of(Material.MAP, "&7No orders", "&7You don't have any Buy Orders", "&7or Sell Offers" + (only == null ? "." : " for this item."),
                    "", "&8Create one from any product page."));
        }
        int claimable = 0;
        for (Order o : list) if (o.owes() && Objects.equals(o.profileId, profile)) claimable++;
        ItemStack all = Icons.of(Material.HOPPER, claimable > 0 ? "&aClaim All" : "&7Claim All",
                "&7Claim coins and items from every", "&7order at once.", "", claimable > 0 ? "&7Ready: &e" + claimable : "&8Nothing to claim.",
                "", claimable > 0 ? "&eClick to claim!" : "");
        if (claimable > 0) Icons.glow(all);
        m.set(47, all, claimable > 0 ? cl -> { Ui.result(cl.player(), Bz.market.claimAll(cl.player())); m.redraw(); } : null);
        m.set(48, Icons.back(only == null ? "Bazaar" : Catalogue.plainName(only)), cl -> {
            if (only == null) BazaarMenus.open(cl.player(), true); else BazaarMenus.product(cl.player(), only).open(cl.player());
        });
        m.set(50, Icons.of(Material.WRITABLE_BOOK, "&aTrade History", "&7Your recent Bazaar activity.", "", "&eClick to view!"),
                cl -> InfoMenus.history(cl.player()));
        m.set(51, Icons.of(Material.PAPER, "&7Order slots",
                "&7Used: &a" + Bz.market.activeCount(p.getUniqueId()) + "&7/" + Bz.market.maxOrders(p),
                "&8Filled orders keep their slot", "&8until you claim them."));
        m.closeButton();
    }

    static ItemStack icon(Order o, String profile, long now) {
        boolean buy = o.side == Order.Side.BUY;
        List<String> l = new ArrayList<>();
        String status;
        if (o.open()) status = "&8Expires in " + Text.duration(Math.max(0, (o.expires - now) / 1000));
        else if (o.remaining() <= 0) status = "&aFilled!";
        else if (o.expires <= now) status = "&cExpired";
        else status = "&cCancelled";
        l.add(status);
        l.add("");
        l.add("&7Order amount: &a" + Text.commasInt(o.amount) + "&7x");
        int pct = (int) Math.floor(o.fillFraction() * 100);
        l.add("&7Filled: " + (pct >= 100 ? "&a" : "&e") + Text.commasInt(o.filled) + "&7/" + Text.commasInt(o.amount) + " " + (pct >= 100 ? "&a&l100%!" : "&e" + pct + "%"));
        l.add("&7" + Text.bar(o.fillFraction(), 20));
        l.add("");
        l.add("&7Price per unit: &6" + Pricing.fmt(o.price) + " coins");
        l.add("&7" + (buy ? "Total paid" : "Total value") + ": &6" + Pricing.fmtTotal(Pricing.total(o.price, o.amount)) + " coins");
        l.add("");
        if (o.itemsPending > 0) l.add("&7You have &a" + Text.commasInt(o.itemsPending) + "&7 items to claim!");
        if (o.coinsPending > 0) l.add("&7You have &6" + Pricing.fmtTotal(o.coinsPending) + " coins &7to claim!");
        if (!o.owes()) l.add("&8Nothing to claim yet.");
        boolean other = !Objects.equals(o.profileId, profile);
        l.add("");
        if (other) l.add("&cPlaced on another of your profiles.");
        else {
            if (o.owes()) l.add("&eClick to claim!");
            if (o.open()) l.add("&bRight-click to cancel order!");
            if (buy && o.itemsPending > 0) l.add("&dShift-click to flip into a Sell Offer!");
        }
        String title = (buy ? "&a&lBUY " : "&6&lSELL ") + Catalogue.name(o.product);
        ItemStack it = Ui.product(o.product, title, l);
        if (o.owes()) Icons.glow(it);
        return Icons.amount(it, Math.max(1, Math.min(64, o.owes() ? (buy ? o.itemsPending : 1) : 1)));
    }

    private static void click(Player p, Order o, boolean right, boolean shift, String only) {
        if (!Objects.equals(o.profileId, Sky.profiles().profileId(p))) { Msg.error(p, "This order belongs to another of your profiles!"); return; }
        if (shift && o.side == Order.Side.BUY && o.itemsPending > 0) {
            int before = Inv.inInventory(p, o.product);
            Market.Result r = Bz.market.claim(p, o, false);
            if (!r.ok()) { Msg.error(p, r.message()); return; }
            int got = Inv.inInventory(p, o.product) - before;
            if (got <= 0) { open(p, only); return; }
            TradeMenus.sellOfferPrice(p, o.product, got, false);
            return;
        }
        if (right && o.open()) {
            Sky.menus().confirm(p, "Cancel Order?", List.of(
                    (o.side == Order.Side.BUY ? "&aBuy Order" : "&6Sell Offer") + " &7for &f" + Catalogue.plainName(o.product),
                    "&7Unfilled: &a" + Text.commasInt(o.remaining()) + "&7x",
                    "",
                    o.side == Order.Side.BUY ? "&7Refund: &6" + Pricing.fmtTotal(Pricing.total(o.price, o.remaining())) + " coins" : "&7Items come back to you.",
                    "&8Anything already filled is claimed too."),
                    () -> { Ui.result(p, Bz.market.cancel(p, o)); open(p, only); },
                    () -> open(p, only));
            return;
        }
        if (o.owes()) {
            Market.Result r = Bz.market.claim(p, o, false);
            if (!r.ok()) Msg.error(p, r.message());
            return;
        }
        Msg.send(p, "&7Nothing to claim yet. &8Right-click to cancel the order.");
    }
}
