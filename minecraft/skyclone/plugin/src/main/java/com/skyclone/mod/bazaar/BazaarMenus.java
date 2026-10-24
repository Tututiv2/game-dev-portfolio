package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Browsing: the category pages (the Bazaar's front door), product groups, the product page, the full order book,
 * search, and Sell-Inventory/Sacks. Layout of a category page (6 rows):
 * <pre>
 *  col 0  : six category tabs (Farming, Mining, Combat, Woods &amp; Fishes, Oddities, Enchantments)
 *  col 1  : the category's coloured glass
 *  cols 2-8, rows 0-4 : product groups (35 per page)
 *  bottom : 46 prev | 47 Sell Inventory | 48 Sell Sacks | 49 Close | 50 Manage Orders | 51 Search | 52 More | 53 next
 * </pre>
 */
final class BazaarMenus {

    private BazaarMenus() {}

    static final int[] TAB_SLOTS = {0, 9, 18, 27, 36, 45};
    static final int[] GROUP_SLOTS;
    static {
        List<Integer> s = new ArrayList<>();
        for (int r = 0; r < 5; r++) for (int c = 2; c < 9; c++) s.add(r * 9 + c);
        GROUP_SLOTS = s.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- front door -------------------------------------------------------------------------------------------

    static void open(Player p, boolean atNpc) {
        if (!Bz.access(p, atNpc, true)) return;
        if (Bz.special(p)) special(p).open(p);
        else category(p, Bz.lastCategory.getOrDefault(p.getUniqueId(), "FARMING"), 0).open(p);
        int claim = Bz.market.claimableCount(p.getUniqueId());
        if (claim > 0) Market.sendClickable(p, "&6[Bazaar] &eYou have &a" + claim + " &eorder" + (claim == 1 ? "" : "s") + " ready to claim! &7(click)");
    }

    static Menu category(Player p, String catId, int page) {
        Catalogue cat = Bz.cat;
        Catalogue.Category c = cat.category(catId);
        if (c == null) { catId = "FARMING"; c = cat.category(catId); }
        final String cid = catId;
        Bz.lastCategory.put(p.getUniqueId(), cid);
        List<Catalogue.Group> groups = cat.liveGroups(cid);
        int pages = Math.max(1, (groups.size() + GROUP_SLOTS.length - 1) / GROUP_SLOTS.length);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu("Bazaar" + Ui.ARROW + (c == null ? "?" : c.name) + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : ""), 6);
        m.chrome();
        // tabs
        int i = 0;
        for (Catalogue.Category t : cat.categories.values()) {
            if (i >= TAB_SLOTS.length) break;
            boolean sel = t.id.equals(cid);
            int liveG = cat.liveGroups(t.id).size();
            int liveP = 0;
            for (Catalogue.Group g : cat.liveGroups(t.id)) liveP += g.live.size();
            List<String> lore = new ArrayList<>();
            lore.add("&8Category");
            lore.add("");
            lore.add("&7Products: &a" + Text.commasInt(liveP));
            lore.add("&7Groups: &a" + liveG);
            lore.add("");
            lore.add(sel ? "&aCurrently viewing!" : "&eClick to view!");
            ItemStack icon = Icons.of(Ui.mat(t.icon, Material.CHEST), (sel ? "&a" : "&e") + t.name, lore);
            if (sel) Icons.glow(icon);
            final String tid = t.id;
            m.set(TAB_SLOTS[i++], icon, sel ? null : cl -> category(cl.player(), tid, 0).open(cl.player()));
        }
        Material glass = Ui.glass(c == null ? "GRAY" : c.glass);
        for (int s : new int[]{1, 10, 19, 28, 37}) m.set(s, Icons.of(glass, " "));
        // groups
        int from = pg * GROUP_SLOTS.length;
        for (int k = 0; k < GROUP_SLOTS.length && from + k < groups.size(); k++) {
            Catalogue.Group g = groups.get(from + k);
            m.set(GROUP_SLOTS[k], groupIcon(p, g), cl -> openGroup(cl.player(), g.key));
        }
        if (groups.isEmpty()) {
            m.set(22, Icons.of(Material.BARRIER, "&cNothing here yet", "&7No items of this category are", "&7traded on this server yet."));
        }
        if (pg > 0) m.set(46, Icons.prevPage(pg + 1), cl -> category(cl.player(), cid, pg - 1).open(cl.player()));
        if (pg < pages - 1) m.set(53, Icons.nextPage(pg + 1), cl -> category(cl.player(), cid, pg + 1).open(cl.player()));
        footer(m, p);
        return m;
    }

    /** The shared bottom row: sell inventory, sell sacks, manage orders, search, more. */
    static void footer(Menu m, Player p) {
        m.set(47, Icons.of(Material.CHEST, "&aSell Inventory Now",
                "&7Instantly sell everything in your", "&7inventory that has a Buy Order.", "", "&7You'll see the full list and the", "&7total before anything is sold.", "",
                "&eClick to review!"), cl -> sellAll(cl.player(), true, PlayerData.of(cl.player()).sellSacksInSellAll && Inv.hasSacks()));
        boolean sacks = Inv.hasSacks();
        m.set(48, Icons.of(Material.BUNDLE, sacks ? "&aSell Sacks Now" : "&8Sell Sacks Now",
                sacks ? Ui.lore("&7Instantly sell everything in your", "&7sacks that has a Buy Order.", "", "&eClick to review!")
                        : Ui.lore("&7You have no sacks yet.")), sacks ? cl -> sellAll(cl.player(), false, true) : null);
        int n = Bz.market.activeCount(p.getUniqueId());
        int claim = Bz.market.claimableCount(p.getUniqueId());
        List<String> ml = new ArrayList<>();
        ml.add("&7View and claim your Buy Orders");
        ml.add("&7and Sell Offers.");
        ml.add("");
        ml.add("&7Orders: &a" + n + "&7/" + Bz.market.maxOrders(p));
        if (claim > 0) ml.add("&7Ready to claim: &e" + claim);
        ml.add("");
        ml.add("&eClick to manage!");
        ItemStack mi = Icons.of(Material.BOOK, "&aManage Orders", ml);
        if (claim > 0) Icons.glow(mi);
        m.set(50, mi, cl -> OrdersMenu.open(cl.player(), null));
        m.set(51, Icons.of(Material.OAK_SIGN, "&aSearch",
                "&7Find a product by name.", "", "&8Also: /bz <name>", "", "&eClick to search!"), cl -> search(cl.player()));
        m.set(52, Icons.of(Material.COMPARATOR, "&aMore",
                "&7Favourites, recently viewed,", "&7trade history, your stats, market", "&7movers, settings and help.", "", "&eClick to open!"),
                cl -> InfoMenus.hub(cl.player()));
    }

    static ItemStack groupIcon(Player p, Catalogue.Group g) {
        Catalogue cat = Bz.cat;
        Market mk = Bz.market;
        List<String> lore = new ArrayList<>();
        lore.add("&8" + Ui.plural(g.live.size(), "product"));
        lore.add("");
        int shown = 0;
        for (String id : g.live) {
            if (shown >= 7) { lore.add("&8...and " + (g.live.size() - shown) + " more"); break; }
            lore.add(Catalogue.name(id));
            lore.add(" &8▸ &7Buy: " + Ui.unit(mk.askPrice(id)) + " &8| &7Sell: " + Ui.unit(mk.bidPrice(id)));
            shown++;
        }
        lore.add("");
        lore.add(g.live.size() == 1 ? "&eClick to view details!" : "&eClick to view products!");
        String icon = cat.iconId(g);
        return Ui.product(icon, "&e" + cat.groupName(g), lore);
    }

    // ---- product group -----------------------------------------------------------------------------------------

    static void openGroup(Player p, String key) {
        Catalogue.Group g = Bz.cat.groups.get(key);
        if (g == null || g.live.isEmpty()) { Msg.error(p, "That group is empty."); return; }
        if (g.live.size() == 1) { openProduct(p, g.live.get(0)); return; }
        group(p, g, 0).open(p);
    }

    /** Page of a product group: 28 per page (Shards and Miscellaneous hold hundreds), prev/next at 45/53. */
    static Menu group(Player p, Catalogue.Group g, int page) {
        Catalogue cat = Bz.cat;
        Catalogue.Category c = cat.category(g.category);
        List<String> ids = g.live;
        int per = Ui.INNER.length;
        int pages = Math.max(1, (ids.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        String title = (c == null ? "Bazaar" : c.name) + Ui.ARROW + cat.groupName(g);
        String suffix = pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : "";
        Menu m = new Menu(clip(title, 40 - suffix.length()) + suffix, 6);
        m.chrome();
        int from = pg * per;
        int n = Math.min(per, ids.size() - from);
        int[] slots = ids.size() <= 7 ? Ui.centred(2, ids.size()) : Ui.INNER;
        for (int i = 0; i < n && i < slots.length; i++) {
            String id = ids.get(from + i);
            m.set(slots[i], productIcon(p, id, true), cl -> {
                if (cl.right()) { toggleFav(cl.player(), id); group(cl.player(), g, pg).open(cl.player()); }
                else openProduct(cl.player(), id);
            });
        }
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), cl -> group(cl.player(), g, pg - 1).open(cl.player()));
        if (pg < pages - 1) m.set(53, Icons.nextPage(pg + 1), cl -> group(cl.player(), g, pg + 1).open(cl.player()));
        int catPage = Math.max(0, cat.liveGroups(g.category).indexOf(g)) / GROUP_SLOTS.length;
        m.set(48, Icons.back("Bazaar" + Ui.ARROW + (c == null ? "" : c.name)), cl -> category(cl.player(), g.category, catPage).open(cl.player()));
        m.set(50, Icons.of(Material.BOOK, "&aManage Orders", "&7Your orders: &a" + Bz.market.activeCount(p.getUniqueId()), "", "&eClick to manage!"),
                cl -> OrdersMenu.open(cl.player(), null));
        return m;
    }

    static String clip(String t) { return clip(t, 40); }

    static String clip(String t, int max) {
        max = Math.max(8, max);
        return t.length() > max ? t.substring(0, max - 1) + "…" : t;
    }

    /** A product's icon with live prices, book depth, 24h change and what the viewer holds. */
    static ItemStack productIcon(Player p, String id, boolean clickHint) {
        Market mk = Bz.market;
        OrderBook b = mk.book(id);
        List<String> lore = new ArrayList<>();
        Catalogue.Group g = Bz.cat.groupOf(id);
        Catalogue.Category c = g == null ? null : Bz.cat.category(g.category);
        lore.add("&8" + (c == null ? "Bazaar" : c.name) + " commodity");
        lore.add("");
        lore.add("&7Buy price: " + Ui.unit(mk.askPrice(id)));
        lore.add("&7Sell price: " + Ui.unit(mk.bidPrice(id)));
        lore.add("");
        lore.add("&7Sell offers: &a" + b.count(Order.Side.SELL) + " &8(" + Text.commasInt(b.volume(Order.Side.SELL)) + " items)");
        lore.add("&7Buy orders: &a" + b.count(Order.Side.BUY) + " &8(" + Text.commasInt(b.volume(Order.Side.BUY)) + " items)");
        lore.add("&724h change: " + Ui.change(mk.history(id).change24h(System.currentTimeMillis())));
        if (p != null) {
            int inv = Inv.inInventory(p, id), sk = Inv.inSacks(p, id);
            if (inv + sk > 0) lore.add("&7You have: &a" + Text.commasInt(inv) + (sk > 0 ? " &8(+" + Text.commasInt(sk) + " in sacks)" : ""));
            if (PlayerData.of(p).favourites.contains(id)) lore.add("&6★ Favourite");
        }
        if (clickHint) {
            lore.add("");
            lore.add("&eClick to view details!");
            lore.add("&bRight-click to toggle favourite!");
        }
        return Ui.product(id, Catalogue.name(id), lore);
    }

    static void toggleFav(Player p, String id) {
        boolean on = PlayerData.of(p).toggleFavourite(id);
        Msg.send(p, on ? "&6[Bazaar] &aAdded &f" + Catalogue.plainName(id) + " &ato your favourites." : "&6[Bazaar] &7Removed &f" + Catalogue.plainName(id) + " &7from your favourites.");
    }

    // ---- product page ------------------------------------------------------------------------------------------

    static void openProduct(Player p, String id) {
        if (!Bz.cat.isLive(id)) { Msg.error(p, "That item is not traded on the Bazaar."); return; }
        if (!Bz.access(p, true, true)) return;
        if (Bz.special(p)) {
            // the Special Bazaar has no product pages: an allowed product goes straight to Instant Buy
            if (Bz.specialProduct(id)) TradeMenus.instantBuy(p, id);
            else { Msg.error(p, Bz.market.modeBlock(p, "buy", id)); special(p).open(p); }
            return;
        }
        PlayerData.of(p).viewed(id);
        product(p, id).open(p);
    }

    // ---- Special Bazaar (Ironman / Stranded, research s9.3-9.4) ------------------------------------------------

    /**
     * The restricted front door for Special-Bazaar profile modes: just the products they may Instant Buy (Booster
     * Cookies by default) plus Manage Orders, so claims and cancels from before a mode switch stay reachable.
     */
    static Menu special(Player p) {
        Menu m = new Menu("Special Bazaar", 4);
        m.chrome();
        m.drawer(mm -> drawSpecial(mm, p)).refreshEvery(40);
        return m;
    }

    private static void drawSpecial(Menu m, Player p) {
        List<String> ids = new ArrayList<>();
        for (String s : BzDials.ironmanProducts.get().split(",")) {
            String id = s.trim().toUpperCase(java.util.Locale.ROOT);
            if (!id.isEmpty() && Bz.cat.isLive(id) && !ids.contains(id)) ids.add(id);
        }
        String mode = Text.titleCase(Bz.mode(p));
        m.set(4, Icons.of(Material.EMERALD, "&aSpecial Bazaar",
                "&7" + mode + " profiles can't trade", "&7with other players here, but they", "&7can Instant Buy a few essentials.", "",
                "&7Selling and orders: &cunavailable"));
        int[] slots = Ui.centred(1, Math.min(7, ids.size()));
        for (int k = 0; k < slots.length; k++) {
            String id = ids.get(k);
            long ask = Bz.market.askPrice(id);
            List<String> lore = new ArrayList<>();
            lore.add("&8Special Bazaar");
            lore.add("");
            lore.add("&7Buy price: " + Ui.unit(ask));
            lore.add("");
            lore.add(ask > 0 ? "&eClick to buy!" : "&cNobody is selling right now.");
            ItemStack icon = Ui.product(id, Catalogue.name(id), lore);
            m.set(slots[k], icon, ask > 0 ? cl -> TradeMenus.instantBuy(cl.player(), id) : null);
        }
        if (ids.isEmpty()) m.set(13, Icons.of(Material.BARRIER, "&cNothing for sale", "&7The Special Bazaar has nothing", "&7to offer on this server."));
        int n = Bz.market.activeCount(p.getUniqueId());
        int claim = Bz.market.claimableCount(p.getUniqueId());
        if (n > 0 || claim > 0) {
            ItemStack mi = Icons.of(Material.BOOK, "&aManage Orders", "&7Orders from before this profile", "&7mode can still be claimed or", "&7cancelled here.", "",
                    "&7Orders: &a" + n + (claim > 0 ? " &8| &7Ready: &e" + claim : ""), "", "&eClick to manage!");
            if (claim > 0) Icons.glow(mi);
            m.set(32, mi, cl -> OrdersMenu.open(cl.player(), null));
        }
    }

    static Menu product(Player p, String id) {
        if (Bz.special(p)) return special(p);
        Market mk = Bz.market;
        Catalogue.Group g = Bz.cat.groupOf(id);
        String title = (g == null ? "Bazaar" : Bz.cat.groupName(g)) + Ui.ARROW + Catalogue.plainName(id);
        Menu m = new Menu(clip(title), 6);
        m.chrome();
        m.drawer(mm -> drawProduct(mm, p, id)).refreshEvery(40);
        return m;
    }

    /**
     * Merchant Guild supply (research s2.9 seeding trick) on a seeded material: what is left of this drop, its price,
     * when it restocks, and what it unlocks, so a new player knows the material will keep coming.
     */
    static void guildLines(List<String> info, Catalogue.Product pr) {
        Seeder g = Bz.seeder;
        if (g == null || pr == null || !BzDials.seedEnabled.get() || !g.list().containsKey(pr.id)) return;
        info.add("");
        long price = g.restingPrice(pr.id);
        info.add(price > 0 ? "&6Merchant Guild: &a" + Text.commasInt(g.restingQty(pr.id)) + " &7left at &6" + Pricing.fmt(price) + " coins"
                : "&6Merchant Guild: &7sold out");
        info.add("&8Restocks every " + Text.duration((long) (BzDials.seedIntervalMinutes.get() * 60)) + ", next in " + Text.duration(g.untilNext() / 1000));
        if (pr.seedFor != null) {
            List<String> names = new ArrayList<>();
            for (String out : pr.seedFor) if (Sky.items().exists(out)) names.add(Catalogue.plainName(out));
            if (!names.isEmpty()) info.add("&8Needed for " + String.join(", ", names));
        }
    }

    private static void drawProduct(Menu m, Player p, String id) {
        Market mk = Bz.market;
        OrderBook b = mk.book(id);
        long now = System.currentTimeMillis();
        PriceHistory h = mk.history(id);
        long ask = mk.askPrice(id), bid = mk.bidPrice(id);
        int inv = Inv.inInventory(p, id), sk = Inv.inSacks(p, id);
        int stack = Inv.maxStack(id);
        double tax = mk.taxRate(p);
        Catalogue.Product pr = Bz.cat.product(id);
        ItemDef def = Sky.items().def(id);

        // 4: the product
        List<String> info = new ArrayList<>();
        info.add("&8" + (def == null || def.rarity == null ? "" : def.rarity.display() + " ") + "commodity");
        info.add("");
        info.add("&7Buy price: " + Ui.unit(ask));
        info.add("&7Sell price: " + Ui.unit(bid));
        long avg = mk.avg7d(id);
        info.add("&77-day average: " + Ui.unit(avg));
        if (def != null && def.npcSell > 0) info.add("&7NPC sell price: &6" + Text.commas(def.npcSell) + " coins");
        guildLines(info, pr);
        info.add("");
        info.add("&724h volume: &a" + Text.commasInt(h.volume(now, 24)) + " &7traded");
        info.add("&7You have: &a" + Text.commasInt(inv) + (sk > 0 ? " &8(+" + Text.commasInt(sk) + " in sacks)" : ""));
        boolean fav = PlayerData.of(p).favourites.contains(id);
        info.add("");
        info.add(fav ? "&6★ Favourite &8(click to remove)" : "&eClick to add to favourites!");
        m.set(4, Ui.product(id, Catalogue.name(id), info), cl -> { toggleFav(cl.player(), id); m.redraw(); });

        // 19: buy instantly
        List<String> bl = new ArrayList<>();
        bl.add("&8" + Catalogue.plainName(id));
        bl.add("");
        if (ask > 0) {
            bl.add("&7Price per unit: &6" + Pricing.fmt(ask) + " coins");
            OrderBook.Quote q = mk.quoteBuy(p, id, stack);
            if (q.qty() > 0) bl.add("&7Stack price: &6" + Pricing.fmtTotal(q.cost()) + " coins" + (q.qty() < stack ? " &8(" + q.qty() + " available)" : ""));
            bl.add("");
            bl.add("&eClick to pick amount!");
        } else {
            bl.add("&cNo one is selling this right now!");
            bl.add("&7Place a &aBuy Order &7instead.");
        }
        m.set(19, Icons.of(Material.GOLDEN_HORSE_ARMOR, "&aBuy Instantly", bl), ask > 0 ? cl -> TradeMenus.instantBuy(cl.player(), id) : null);

        // 20: sell instantly
        List<String> sl = new ArrayList<>();
        sl.add("&8" + Catalogue.plainName(id));
        sl.add("");
        sl.add("&7Inventory: &a" + Text.commasInt(inv) + " items");
        if (Inv.hasSacks()) sl.add("&7Sacks: &a" + Text.commasInt(sk) + " items");
        sl.add("");
        if (bid > 0) {
            sl.add("&7Price per unit: &6" + Pricing.fmt(bid) + " coins");
            if (inv > 0) {
                OrderBook.Quote q = mk.quoteSell(p, id, inv);
                long net = Pricing.afterTax(q.cost(), tax);
                sl.add("&7Selling your inventory: &6" + Pricing.fmtTotal(net) + " coins");
                sl.add("&8(after " + Text.commas(tax) + "% tax)");
            }
            sl.add("");
            sl.add(inv + sk > 0 ? "&eClick to pick amount!" : "&cYou don't have any!");
        } else {
            sl.add("&cNo one is buying this right now!");
            sl.add("&7Create a &6Sell Offer &7instead.");
        }
        m.set(20, Icons.of(Material.HOPPER, "&6Sell Instantly", sl), bid > 0 && inv + sk > 0 ? cl -> TradeMenus.instantSell(cl.player(), id) : null);

        // 24: create buy order
        List<String> ol = new ArrayList<>();
        ol.add("&8" + Catalogue.plainName(id));
        ol.add("");
        List<OrderBook.Level> bids = b.levels(Order.Side.BUY, 7);
        if (bids.isEmpty()) ol.add("&7No buy orders yet - be the first!");
        else {
            ol.add("&7Top orders:");
            for (OrderBook.Level l : bids) ol.add(levelLine(l, "&a"));
        }
        ol.add("");
        ol.add("&eClick to setup Buy Order!");
        m.set(24, Icons.of(Material.FILLED_MAP, "&aCreate Buy Order", ol), cl -> TradeMenus.buyOrderAmount(cl.player(), id));

        // 25: create sell offer
        List<String> fl = new ArrayList<>();
        fl.add("&8" + Catalogue.plainName(id));
        fl.add("");
        List<OrderBook.Level> asks = b.levels(Order.Side.SELL, 7);
        if (asks.isEmpty()) fl.add("&7No sell offers yet - be the first!");
        else {
            fl.add("&7Top offers:");
            for (OrderBook.Level l : asks) fl.add(levelLine(l, "&6"));
        }
        fl.add("");
        fl.add("&7Inventory: &a" + Text.commasInt(inv) + (sk > 0 ? " &8(+" + Text.commasInt(sk) + " in sacks)" : ""));
        fl.add("");
        fl.add(inv + sk > 0 ? "&eClick to setup Sell Offer!" : "&cYou don't have any to offer!");
        m.set(25, Icons.of(Material.MAP, "&6Create Sell Offer", fl), inv + sk > 0 ? cl -> TradeMenus.sellOfferAmount(cl.player(), id) : null);

        // 22: price history
        List<String> hl = new ArrayList<>();
        hl.add("&8Last 24 hours, one bar per hour");
        hl.add("");
        hl.add(Ui.sparkline(h.series(now, 24)));
        hl.add("");
        long hi = 0, lo = Long.MAX_VALUE;
        for (long v : h.series(now, 24)) if (v > 0) { hi = Math.max(hi, v); lo = Math.min(lo, v); }
        hl.add("&7High: " + Ui.unit(hi) + "  &7Low: " + Ui.unit(lo == Long.MAX_VALUE ? 0 : lo));
        hl.add("&724h change: " + Ui.change(h.change24h(now)));
        hl.add("&77-day average: " + Ui.unit(avg));
        hl.add("&77-day volume: &a" + Text.commasInt(h.volume(now, PriceHistory.KEEP_HOURS)));
        m.set(22, Icons.of(Material.CLOCK, "&bPrice History", hl));

        // 31: order book
        m.set(31, Icons.of(Material.BOOKSHELF, "&bView Order Book",
                "&7Every price level on both sides,", "&7deeper than the top seven.", "",
                "&7Buy orders: &a" + b.count(Order.Side.BUY) + " &8(" + Text.commasInt(b.volume(Order.Side.BUY)) + " items)",
                "&7Sell offers: &a" + b.count(Order.Side.SELL) + " &8(" + Text.commasInt(b.volume(Order.Side.SELL)) + " items)", "",
                "&eClick to view!"), cl -> book(cl.player(), id).open(cl.player()));

        // 30: margin
        List<String> ml = new ArrayList<>();
        ml.add("&8What the spread is worth");
        ml.add("");
        if (ask > 0 && bid > 0) {
            long spread = ask - bid;
            long sellNet = Pricing.afterTax(ask, tax);
            long margin = sellNet - bid;
            ml.add("&7Spread: &6" + Pricing.fmt(spread) + " coins &8(" + String.format(java.util.Locale.US, "%.1f", 100.0 * spread / Math.max(1, ask)) + "%)");
            ml.add("&7Buy at top order +0.1: &6" + Pricing.fmt(bid + 1));
            ml.add("&7Sell at best offer -0.1: &6" + Pricing.fmt(Math.max(1, ask - 1)));
            ml.add("&7Margin per item after tax: " + (margin > 0 ? "&a+" : "&c") + Pricing.fmt(Math.abs(margin)).replace("-", "") + " coins");
            ml.add("");
            ml.add("&8Patient orders on both sides earn the");
            ml.add("&8spread; instant trades pay it.");
        } else {
            ml.add("&7Needs orders on both sides.");
        }
        m.set(30, Icons.of(Material.EMERALD, "&aFlip Margin", ml));

        // 32: your orders for this product
        int mine = 0;
        for (Order o : Bz.market.ordersOf(p.getUniqueId())) if (o.product.equals(id)) mine++;
        final int fm = mine;
        m.set(32, Icons.of(Material.WRITABLE_BOOK, "&aYour Orders Here",
                "&7You have &a" + mine + " &7order" + (mine == 1 ? "" : "s") + " for this item.", "", mine > 0 ? "&eClick to manage!" : "&8Nothing to manage."),
                fm > 0 ? cl -> OrdersMenu.open(cl.player(), id) : null);

        // 45: recipe cross-link (crafting module's command, if registered)
        m.set(45, Icons.of(Material.CRAFTING_TABLE, "&aView Recipe", "&7Open this item in the Recipe Book.", "", "&eClick to view!"),
                cl -> { if (!Sky.commands().run(cl.player(), "recipe", id)) Msg.error(cl.player(), "The Recipe Book isn't available."); });

        Catalogue.Group g = Bz.cat.groupOf(id);
        m.set(48, Icons.back(g == null ? "Bazaar" : Bz.cat.groupName(g)), cl -> {
            if (g != null && g.live.size() > 1) group(cl.player(), g, Math.max(0, g.live.indexOf(id)) / Ui.INNER.length).open(cl.player());
            else category(cl.player(), g == null ? "FARMING" : g.category, 0).open(cl.player());
        });
        m.set(50, Icons.of(Material.BOOK, "&aManage Orders", "&eClick to manage!"), cl -> OrdersMenu.open(cl.player(), null));
        if (pr != null && pr.extra) m.set(53, Icons.of(Material.PAPER, "&8Server item", "&7Listed because its module marks it", "&7as a Bazaar commodity."));
    }

    static String levelLine(OrderBook.Level l, String col) {
        return "&8- &6" + Pricing.fmt(l.price()) + " coins &7each &8| " + col + Text.commasInt(l.amount()) + "&7x &8from &f"
                + l.orders() + " &8order" + (l.orders() == 1 ? "" : "s") + (l.hasBot() ? " &8(market)" : "");
    }

    // ---- full order book ---------------------------------------------------------------------------------------

    static Menu book(Player p, String id) {
        Menu m = new Menu(clip("Order Book" + Ui.ARROW + Catalogue.plainName(id)), 6);
        m.chrome();
        m.drawer(mm -> {
            OrderBook b = Bz.market.book(id);
            List<OrderBook.Level> bids = b.levels(Order.Side.BUY, 15);
            List<OrderBook.Level> asks = b.levels(Order.Side.SELL, 15);
            m.set(4, Ui.product(id, Catalogue.name(id), Ui.lore("&7Left: &aBuy Orders &7(best first)", "&7Right: &6Sell Offers &7(best first)",
                    "", "&8Refreshes every two seconds.")));
            for (int s = 9; s < 45; s++) if (s % 9 != 4) m.set(s, Icons.filler());
            for (int r = 1; r < 5; r++) m.set(r * 9 + 4, Icons.of(Material.GRAY_STAINED_GLASS_PANE, " "));
            int k = 0;
            for (int r = 1; r < 5 && k < bids.size(); r++) for (int c = 0; c < 4 && k < bids.size(); c++) {
                OrderBook.Level l = bids.get(k++);
                m.set(r * 9 + c, Icons.amount(Icons.of(Material.LIME_STAINED_GLASS_PANE, "&a" + Pricing.fmt(l.price()) + " coins",
                        "&7Amount: &a" + Text.commasInt(l.amount()), "&7Orders: &f" + l.orders(), l.hasBot() ? "&8Includes the market maker" : "&8Players only"),
                        (int) Math.min(64, Math.max(1, l.orders()))));
            }
            k = 0;
            for (int r = 1; r < 5 && k < asks.size(); r++) for (int c = 5; c < 9 && k < asks.size(); c++) {
                OrderBook.Level l = asks.get(k++);
                m.set(r * 9 + c, Icons.amount(Icons.of(Material.ORANGE_STAINED_GLASS_PANE, "&6" + Pricing.fmt(l.price()) + " coins",
                        "&7Amount: &a" + Text.commasInt(l.amount()), "&7Orders: &f" + l.orders(), l.hasBot() ? "&8Includes the market maker" : "&8Players only"),
                        (int) Math.min(64, Math.max(1, l.orders()))));
            }
            m.set(48, Icons.back(Catalogue.plainName(id)), cl -> product(cl.player(), id).open(cl.player()));
            m.closeButton();
        }).refreshEvery(40);
        return m;
    }

    // ---- search ------------------------------------------------------------------------------------------------

    static void search(Player p) {
        Sky.menus().input(p, "Search the Bazaar", q -> {
            if (q == null || q.isBlank()) { open(p, true); return; }
            results(p, q.trim(), 0);
        });
    }

    static void results(Player p, String query, int page) {
        List<String> hits = Bz.cat.search(query, 280);
        if (hits.isEmpty()) {
            Msg.error(p, "No Bazaar items match \"" + Text.strip(query) + "\".");
            return;
        }
        if (hits.size() == 1 && page == 0) { openProduct(p, hits.get(0)); return; }
        list(p, "Search" + Ui.ARROW + "\"" + clip(Text.strip(query)) + "\"", hits, page, pg -> results(p, query, pg));
    }

    /** A paged list of products (search results, favourites, recent). */
    static void list(Player p, String title, List<String> ids, int page, java.util.function.IntConsumer reopen) {
        Sky.menus().paged(clip(title), ids, id -> productIcon(p, id, true), pc -> {
            if (pc.click().right()) { toggleFav(pc.click().player(), pc.entry()); reopen.accept(pc.page()); }
            else openProduct(pc.click().player(), pc.entry());
        }, mm -> mm.set(48, Icons.back("Bazaar"), cl -> open(cl.player(), true)), page).open(p);
    }

    // ---- sell everything ---------------------------------------------------------------------------------------

    static void sellAll(Player p, boolean inventory, boolean sacks) {
        String block = Bz.market.modeBlock(p, "sell", null);
        if (block != null) { Msg.error(p, block); return; }
        Map<String, Integer> found = Bz.market.sellable(p, inventory, sacks);
        if (found.isEmpty()) {
            Msg.error(p, inventory ? "You don't have anything in your inventory the Bazaar is buying!" : "Your sacks have nothing the Bazaar is buying!");
            return;
        }
        Menu m = new Menu(inventory ? "Sell Inventory Now" : "Sell Sacks Now", 6);
        m.chrome();
        long total = 0;
        int k = 0, items = 0;
        double tax = Bz.market.taxRate(p);
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            OrderBook.Quote q = Bz.market.quoteSell(p, e.getKey(), e.getValue());
            long net = Pricing.afterTax(q.cost(), tax);
            total += net;
            items += q.qty();
            if (k < Ui.INNER.length - 1 || (k == Ui.INNER.length - 1 && found.size() == Ui.INNER.length)) {
                m.set(Ui.INNER[k], Icons.amount(Ui.product(e.getKey(), Catalogue.name(e.getKey()), Ui.lore(
                        "&7Amount: &a" + Text.commasInt(e.getValue()),
                        q.qty() < e.getValue() ? "&7Sellable now: &e" + Text.commasInt(q.qty()) : "&7Sellable now: &a" + Text.commasInt(q.qty()),
                        "&7You get: &6" + Pricing.fmtTotal(net) + " coins")), Math.min(64, e.getValue())));
            } else if (k == Ui.INNER.length - 1) {
                m.set(Ui.INNER[k], Icons.of(Material.PAPER, "&7...and " + (found.size() - k) + " more kinds"));
            }
            k++;
        }
        final boolean inv = inventory, sk = sacks;
        m.set(50, Icons.glow(Icons.of(Material.LIME_TERRACOTTA, "&aSell Everything!",
                "&7Kinds: &a" + found.size(), "&7Items: &a" + Text.commasInt(items), "&7Total: &6" + Pricing.fmtTotal(total) + " coins",
                "&8(after " + Text.commas(tax) + "% tax, at today's Buy Orders)", "", "&eClick to sell!")), cl -> {
            cl.player().closeInventory();
            Market.Result r = Bz.market.sellAll(cl.player(), inv, sk);
            Ui.result(cl.player(), r);
        });
        m.set(48, Icons.back("Bazaar"), cl -> open(cl.player(), true));
        if (inventory && Inv.hasSacks()) {
            boolean incl = PlayerData.of(p).sellSacksInSellAll;
            m.set(46, Icons.of(incl ? Material.LIME_DYE : Material.GRAY_DYE, "&aInclude Sacks: " + (incl ? "&aON" : "&cOFF"),
                    "&7Also sell matching items from", "&7your sacks.", "", "&eClick to toggle!"), cl -> {
                PlayerData d = PlayerData.of(cl.player());
                d.sellSacksInSellAll = !d.sellSacksInSellAll;
                sellAll(cl.player(), true, d.sellSacksInSellAll);
            });
        }
        if (PlayerData.of(p).sounds) Msg.sound(p, Sound.UI_BUTTON_CLICK, 1f);
        m.open(p);
    }

    static boolean sacksFeature() { return Sky.has(Features.Sacks.class); }
}
