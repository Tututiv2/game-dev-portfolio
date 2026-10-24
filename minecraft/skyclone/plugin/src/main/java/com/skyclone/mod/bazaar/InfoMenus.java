package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The "More" hub: favourites, recently viewed, trade history, lifetime stats, market movers, settings, help. */
final class InfoMenus {

    private InfoMenus() {}

    static void hub(Player p) {
        Menu m = new Menu("Bazaar" + Ui.ARROW + "More", 4);
        m.chrome();
        PlayerData d = PlayerData.of(p);
        m.set(10, Icons.of(Material.NETHER_STAR, "&6Favourites", "&7Products you starred.", "&7Right-click any product to star it.", "",
                "&7Starred: &a" + d.favourites.size(), "", "&eClick to view!"), cl -> favourites(cl.player(), 0));
        m.set(11, Icons.of(Material.CLOCK, "&aRecently Viewed", "&7The last products you opened.", "", "&eClick to view!"),
                cl -> recent(cl.player(), 0));
        m.set(12, Icons.of(Material.WRITABLE_BOOK, "&aTrade History", "&7Your latest Bazaar trades,", "&7orders and cancellations.", "", "&eClick to view!"),
                cl -> history(cl.player()));
        m.set(13, statsIcon(p), null);
        m.set(14, Icons.of(Material.SPYGLASS, "&bMarket Movers", "&7Biggest risers, fallers and the", "&7most traded products today.", "", "&eClick to view!"),
                cl -> movers(cl.player()));
        m.set(15, Icons.of(Material.REDSTONE_TORCH, "&aBazaar Settings", "&7Notifications, price warnings,", "&7confirmations and sacks.", "", "&eClick to change!"),
                cl -> settings(cl.player()));
        m.set(16, help(), null);
        m.back("Bazaar", () -> BazaarMenus.category(p, Bz.lastCategory.getOrDefault(p.getUniqueId(), "FARMING"), 0));
        m.open(p);
    }

    static ItemStack statsIcon(Player p) {
        PlayerData d = PlayerData.of(p);
        long profit = d.coinsEarned - d.coinsSpent;
        return Icons.of(Material.EMERALD, "&aYour Bazaar Stats",
                "&7Trades made: &a" + Text.commasInt(d.trades),
                "&7Orders placed: &a" + Text.commasInt(d.ordersPlaced),
                "&7Orders filled: &a" + Text.commasInt(d.ordersFilled),
                "",
                "&7Items bought: &a" + Text.commasInt(d.itemsBought),
                "&7Items sold: &a" + Text.commasInt(d.itemsSold),
                "",
                "&7Coins spent: &6" + Pricing.fmtTotal(d.coinsSpent),
                "&7Coins earned: &6" + Pricing.fmtTotal(d.coinsEarned),
                "&7Tax paid: &c" + Pricing.fmtTotal(d.taxPaid),
                "&7Net: " + (profit >= 0 ? "&a+" : "&c-") + Pricing.fmtTotal(Math.abs(profit)) + " coins",
                "",
                "&8Earned counts claimed proceeds.");
    }

    static ItemStack help() {
        return Icons.of(Material.BOOK, "&eHow the Bazaar works",
                "&aBuy Instantly &7pays the cheapest", "&7Sell Offers right now.",
                "&6Sell Instantly &7takes the highest", "&7Buy Orders right now, minus tax.",
                "",
                "&aBuy Orders &7wait for a seller at your", "&7price; your coins are held meanwhile.",
                "&6Sell Offers &7wait for a buyer; your", "&7items are held meanwhile.",
                "",
                "&7Filled orders wait in &aManage Orders", "&7until you claim them. Cancel any time",
                "&7for a full refund of the unfilled part.",
                "",
                "&7Tax: &c" + Text.commas(BzDials.taxPercent.get()) + "% &7of what sellers receive.",
                "&7Orders: &a" + BzDials.maxOrders.get() + " &7at once, expire after &a" + Text.commas(BzDials.expiryDays.get()) + " &7days.",
                "&8Bazaar Flipper (Community Shop): -" + Text.commas(BzDials.flipperTaxStep.get()) + "% tax",
                "&8and +" + BzDials.flipperSlots.get() + " order slots per tier.",
                remoteLine(),
                "",
                "&8Market makers keep every product", "&8stocked; beat their price and other", "&8traders fill your order over time.",
                "&8The &6Merchant Guild &8restocks slayer,", "&8dragon, mining and fishing drops that", "&8early gear needs every "
                        + Text.duration((long) (BzDials.seedIntervalMinutes.get() * 60)) + ".");
    }

    private static String remoteLine() {
        String r = BzDials.remoteRequirement.get();
        if (r == null || r.isBlank()) return "&7Use &e/bz &7from anywhere.";
        String what;
        try { what = Sky.requirements().describe(r.trim()); } catch (RuntimeException e) { what = r; }
        return "&7Away from the Hub, &e/bz &7needs " + what + "&7.";
    }

    static void favourites(Player p, int page) {
        List<String> ids = new ArrayList<>();
        for (String id : PlayerData.of(p).favourites) if (Bz.cat.isLive(id)) ids.add(id);
        if (ids.isEmpty()) { Msg.error(p, "You have no favourites yet. Right-click a product to star it!"); return; }
        BazaarMenus.list(p, "Bazaar" + Ui.ARROW + "Favourites", ids, page, pg -> favourites(p, pg));
    }

    static void recent(Player p, int page) {
        List<String> ids = new ArrayList<>();
        for (String id : PlayerData.of(p).recent) if (Bz.cat.isLive(id)) ids.add(id);
        if (ids.isEmpty()) { Msg.error(p, "You haven't looked at anything yet."); return; }
        BazaarMenus.list(p, "Bazaar" + Ui.ARROW + "Recently Viewed", ids, page, pg -> recent(p, pg));
    }

    static void history(Player p) {
        Bz.store.recent(p.getUniqueId(), 28).whenComplete((rows, ex) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            if (ex != null) { Msg.error(p, "Couldn't load your history right now."); return; }
            Menu m = new Menu("Bazaar" + Ui.ARROW + "Trade History", 6);
            m.chrome();
            long now = System.currentTimeMillis();
            int k = 0;
            for (Store.LogRow r : rows) {
                if (k >= Ui.INNER.length) break;
                m.set(Ui.INNER[k++], logIcon(r, now), Bz.cat.isLive(r.product()) ? cl -> BazaarMenus.openProduct(cl.player(), r.product()) : null);
            }
            if (rows.isEmpty()) m.set(22, Icons.of(Material.MAP, "&7Nothing yet", "&7Your Bazaar trades show up here."));
            m.set(48, Icons.back("Bazaar"), cl -> BazaarMenus.open(cl.player(), true));
            m.set(50, Icons.of(Material.BOOK, "&aManage Orders", "&eClick to manage!"), cl -> OrdersMenu.open(cl.player(), null));
            m.open(p);
        }));
    }

    private static ItemStack logIcon(Store.LogRow r, long now) {
        String what = switch (r.kind()) {
            case "BUY" -> "&aBought";
            case "SELL" -> "&6Sold";
            case "BUY_ORDER" -> "&aBuy Order";
            case "SELL_OFFER" -> "&6Sell Offer";
            case "CANCEL" -> "&cCancelled";
            default -> "&7" + Text.titleCase(r.kind());
        };
        List<String> l = new ArrayList<>();
        l.add("&8" + Text.duration((now - r.time()) / 1000) + " ago");
        l.add("");
        l.add("&7Amount: &a" + Text.commasInt(r.qty()) + "&7x");
        if (r.price() > 0) l.add("&7Unit price: &6" + Pricing.fmt(r.price()) + " coins");
        if (r.total() > 0) l.add("&7" + (r.kind().equals("SELL") ? "Received" : r.kind().equals("CANCEL") ? "Refunded" : "Total") + ": &6" + Pricing.fmtTotal(r.total()) + " coins");
        return Ui.product(r.product(), what + " &f" + Catalogue.plainName(r.product()), l);
    }

    // ---- market movers -----------------------------------------------------------------------------------------

    static void movers(Player p) {
        long now = System.currentTimeMillis();
        record Row(String id, double change, long vol) {}
        List<Row> rows = new ArrayList<>();
        for (Catalogue.Product pr : Bz.cat.products.values()) {
            if (!pr.live) continue;
            PriceHistory h = Bz.market.history.get(pr.id);
            if (h == null) continue;
            rows.add(new Row(pr.id, h.change24h(now), h.volume(now, 24)));
        }
        Menu m = new Menu("Bazaar" + Ui.ARROW + "Market Movers", 6);
        m.chrome();
        List<Row> up = new ArrayList<>(rows.stream().filter(r -> !Double.isNaN(r.change) && r.change > 0).toList());
        up.sort(Comparator.comparingDouble((Row r) -> -r.change));
        List<Row> down = new ArrayList<>(rows.stream().filter(r -> !Double.isNaN(r.change) && r.change < 0).toList());
        down.sort(Comparator.comparingDouble((Row r) -> r.change));
        List<Row> busy = new ArrayList<>(rows.stream().filter(r -> r.vol > 0).toList());
        busy.sort(Comparator.comparingLong((Row r) -> -r.vol));
        m.set(9, Icons.of(Material.LIME_STAINED_GLASS_PANE, "&aRising", "&7Biggest gains over 24 hours."));
        m.set(18, Icons.of(Material.RED_STAINED_GLASS_PANE, "&cFalling", "&7Biggest drops over 24 hours."));
        m.set(27, Icons.of(Material.YELLOW_STAINED_GLASS_PANE, "&eMost Traded", "&7Most items changing hands today."));
        fillRow(m, p, 10, up.stream().map(Row::id).toList());
        fillRow(m, p, 19, down.stream().map(Row::id).toList());
        fillRow(m, p, 28, busy.stream().map(Row::id).toList());
        m.set(48, Icons.back("More"), cl -> hub(cl.player()));
        m.open(p);
    }

    private static void fillRow(Menu m, Player p, int start, List<String> rows) {
        for (int i = 0; i < 7 && i < rows.size(); i++) {
            String id = rows.get(i);
            m.set(start + i, BazaarMenus.productIcon(p, id, true), cl -> {
                if (cl.right()) BazaarMenus.toggleFav(cl.player(), id);
                else BazaarMenus.openProduct(cl.player(), id);
            });
        }
        if (rows.isEmpty()) m.set(start + 3, Icons.of(Material.GRAY_DYE, "&7Nothing yet", "&7Check back after some trading."));
    }

    // ---- settings ----------------------------------------------------------------------------------------------

    static void settings(Player p) {
        Menu m = new Menu("Bazaar" + Ui.ARROW + "Settings", 4);
        m.chrome();
        PlayerData d = PlayerData.of(p);
        toggle(m, 10, "Fill Notifications", "Tell me when one of my orders fills.", d.notifyFills, cl -> { d(cl).notifyFills = !d(cl).notifyFills; settings(cl.player()); });
        toggle(m, 11, "Partial Fill Updates", "Also tell me as orders fill partly.", d.notifyPartial, cl -> { d(cl).notifyPartial = !d(cl).notifyPartial; settings(cl.player()); });
        toggle(m, 12, "Price Warnings", "Ask before buying far above or selling far below the weekly average.", d.priceWarnings, cl -> { d(cl).priceWarnings = !d(cl).priceWarnings; settings(cl.player()); });
        toggle(m, 13, "Claim Into Sacks", "When my inventory is full, claimed items go into my sacks.", d.claimToSacks, cl -> { d(cl).claimToSacks = !d(cl).claimToSacks; settings(cl.player()); });
        toggle(m, 14, "Sell Sacks With Inventory", "Sell Inventory Now also sells from my sacks.", d.sellSacksInSellAll, cl -> { d(cl).sellSacksInSellAll = !d(cl).sellSacksInSellAll; settings(cl.player()); });
        toggle(m, 15, "Sounds", "Play sounds on trades and fills.", d.sounds, cl -> { d(cl).sounds = !d(cl).sounds; settings(cl.player()); });
        double[] steps = {0, 100_000, 1_000_000, 10_000_000, 100_000_000};
        int cur = 0;
        for (int i = 0; i < steps.length; i++) if (Math.abs(steps[i] - d.confirmAbove) < 1) cur = i;
        final int ci = cur;
        m.set(16, Icons.of(Material.GOLD_INGOT, "&aConfirm Big Purchases",
                "&7Ask before an Instant Buy costing", "&7more than:", "",
                "&7Now: " + (d.confirmAbove <= 0 ? "&cnever ask" : "&6" + Text.shortNum(d.confirmAbove) + " coins"), "",
                "&eClick to cycle!"), cl -> { d(cl).confirmAbove = steps[(ci + 1) % steps.length]; settings(cl.player()); });
        m.set(m.closeSlot() - 1, Icons.back("More"), cl -> hub(cl.player()));
        m.open(p);
    }

    private static PlayerData d(com.skyclone.api.ui.MenuClick c) { return PlayerData.of(c.player()); }

    private static void toggle(Menu m, int slot, String name, String desc, boolean on, java.util.function.Consumer<com.skyclone.api.ui.MenuClick> click) {
        List<String> l = new ArrayList<>(Text.wrap("&7" + desc, 32));
        l.add("");
        l.add(on ? "&aEnabled" : "&cDisabled");
        l.add("");
        l.add("&eClick to toggle!");
        m.set(slot, Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "&a" : "&c") + name, l), click);
    }
}
