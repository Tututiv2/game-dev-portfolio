package com.skyclone.mod.bazaar;

import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sb bazaar ...}: everything the integrator needs to drive the market over RCON. Every action that needs a
 * player takes their name, so it works from the console. Output is synchronous (RCON only shows what prints while
 * the command runs).
 */
final class Admin {

    private Admin() {}

    static final String USAGE = "info | open <p> [item] | price <item> | book <item> | buy <p> <item> <n> | sell <p> <item> <n>"
            + " | order <p> buy|sell <item> <n> <price> | orders <p> | cancel <p> <id> | claim <p> [id] | sellall <p> [inv|sacks|both]"
            + " | mm refresh|reset|set ... | flow <item> <sell> <buy> | expire [all] | audit | search <words> | live [filter] | shocks | seed [list|now|off <item>]";

    static final List<String> SUBS = List.of("info", "open", "price", "book", "buy", "sell", "order", "orders", "cancel", "claim",
            "sellall", "mm", "flow", "expire", "audit", "search", "live", "shocks", "seed");

    static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb bazaar " + USAGE); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        Market mk = Bz.market;
        switch (sub) {
            case "info" -> {
                say(s, "&6Bazaar: &7catalogue &a" + Bz.cat.dataProducts + "&7, live &a" + Bz.cat.liveProducts + "&7 (" + Bz.cat.extraProducts
                        + " from item tags), groups &a" + Bz.cat.groups.size());
                int open = 0;
                for (Order o : mk.orders.values()) if (o.open()) open++;
                say(s, "&7Player orders: &a" + mk.orders.size() + " &7(" + open + " on the book), escrow &6" + Pricing.fmtTotal(mk.escrowCoins())
                        + " coins &7+ &a" + Text.commasInt(mk.escrowItems()) + " &7items");
                say(s, "&7Market makers: &a" + Bz.mm.states.size() + " &7products, " + Bz.mm.refreshes + " re-quotes, P&L &6"
                        + Pricing.fmtTotal(Bz.mm.totalPnl()) + "&7 (positive = coins removed from players), simulated flow "
                        + Text.commasInt(Bz.mm.flowUnits) + " units");
                say(s, "&7Tax &c" + Text.commas(mk.taxRate()) + "%&7, trading " + (BzDials.enabled.get() ? "&aopen" : "&cHALTED")
                        + "&7, market makers " + (BzDials.mmEnabled.get() ? "&aon" : "&coff") + "&7, flow " + (BzDials.flowEnabled.get() ? "&aon" : "&coff"));
                say(s, "&7Merchant Guild: " + (BzDials.seedEnabled.get() ? "&aon" : "&coff") + "&7, " + Bz.seeder.resting.size()
                        + " supply offers, sold " + Text.commasInt(Bz.seeder.unitsSold) + " units &8(/sb bazaar seed)");
                say(s, "&7Access: &a" + Gate.label(BzDials.accessRequirement.get()) + " &8(bazaar.access-requirement = '"
                        + BzDials.accessRequirement.get() + "')&7, away from the NPC also &a" + Gate.label(BzDials.remoteRequirement.get()));
            }
            case "open" -> {
                Player p = player(s, a, 1); if (p == null) return;
                if (a.length > 2) { String id = item(s, a[2]); if (id != null) BazaarMenus.product(p, id).open(p); }
                else BazaarMenus.open(p, true);
                say(s, "&aOpened for " + p.getName());
            }
            case "price" -> {
                String id = a.length > 1 ? item(s, a[1]) : null; if (id == null) return;
                Catalogue.Product pr = Bz.cat.product(id);
                say(s, "&6" + Catalogue.plainName(id) + " &8(" + id + ")");
                say(s, "&7Instant buy &6" + Pricing.fmt(mk.askPrice(id)) + " &7| instant sell &6" + Pricing.fmt(mk.bidPrice(id))
                        + " &7| 7d avg &6" + Pricing.fmt(mk.avg7d(id)));
                say(s, "&7Reference &6" + Text.commas(pr.ref) + " &7(snapshot bid " + Text.commas(pr.refBid) + " / ask " + Text.commas(pr.refAsk)
                        + "), npc sell " + Text.commas(pr.npcSell) + ", shop " + Text.commas(MarketMaker.shopPrice(pr)) + ", weekly " + Text.commasInt(pr.weekly));
                MarketMaker.State st = Bz.mm.states.get(id);
                if (st != null) say(s, "&7MM fair &6" + Text.commas(Math.round(st.fair * 10) / 10.0) + " &7stock " + Text.commasInt(st.inv) + "/" + Text.commasInt(st.target)
                        + " depth " + st.depth + " spread " + Text.commas(Math.round(st.spread * 1000) / 10.0) + "% cap " + Text.commas(Math.round(st.bidCap * 10) / 10.0)
                        + " P&L " + Pricing.fmtTotal(st.pnl)
                        + (st.shock != 1.0 ? " &devent shock x" + Text.commas(Math.round(st.shock * 1000) / 1000.0) : ""));
                else say(s, "&7No market maker" + (pr.mm ? "" : " (unpriced in the snapshot)"));
            }
            case "shocks" -> {
                EventShocks ev = EventShocks.current();
                if (ev.all().isEmpty()) { say(s, "&7No event shocks configured (bazaar.event-shocks)."); return; }
                for (EventShocks.Shock sh : ev.all()) {
                    boolean on = EventShocks.active(sh.event());
                    StringBuilder b = new StringBuilder();
                    for (String id : sh.products()) {
                        MarketMaker.State st = Bz.mm.states.get(id);
                        b.append(' ').append(id).append(st == null ? "&8(none)&7" : "&8(x" + Math.round(st.shock * 100) / 100.0 + ")&7");
                    }
                    say(s, (on ? "&aACTIVE " : "&8idle ") + "&e" + sh.event() + " &7x" + sh.factor() + ":" + b);
                }
                say(s, "&7Glide half-life " + BzDials.eventShockGlideMinutes.get() + " min. Force an event with the calendar admin to test.");
            }
            case "book" -> {
                String id = a.length > 1 ? item(s, a[1]) : null; if (id == null) return;
                OrderBook b = mk.book(id);
                say(s, "&6Order book " + id + " &7(" + b.count(Order.Side.BUY) + " bids / " + b.count(Order.Side.SELL) + " asks)");
                for (OrderBook.Level l : b.levels(Order.Side.SELL, 8)) say(s, "  &6ASK " + Pricing.fmt(l.price()) + " x" + l.amount() + " (" + l.orders() + (l.hasBot() ? ", mm" : "") + ")");
                for (OrderBook.Level l : b.levels(Order.Side.BUY, 8)) say(s, "  &aBID " + Pricing.fmt(l.price()) + " x" + l.amount() + " (" + l.orders() + (l.hasBot() ? ", mm" : "") + ")");
            }
            case "buy", "sell" -> {
                if (a.length < 4) { say(s, "&cUsage: /sb bazaar " + sub + " <player> <item> <amount>"); return; }
                Player p = player(s, a, 1); String id = item(s, a[2]); Integer n = num(s, a[3]);
                if (p == null || id == null || n == null) return;
                Market.Result r = sub.equals("buy") ? mk.instantBuy(p, id, n) : mk.instantSell(p, id, n, true);
                say(s, (r.ok() ? "&a" : "&c") + Text.strip(r.message()));
            }
            case "order" -> {
                if (a.length < 6) { say(s, "&cUsage: /sb bazaar order <player> buy|sell <item> <amount> <price>"); return; }
                Player p = player(s, a, 1); String id = item(s, a[3]); Integer n = num(s, a[4]);
                long price = Pricing.parse(a[5]);
                if (p == null || id == null || n == null) return;
                if (price <= 0) { say(s, "&cBad price " + a[5]); return; }
                boolean buy = a[2].equalsIgnoreCase("buy");
                Market.Result r = buy ? mk.placeBuy(p, id, n, price) : mk.placeSell(p, id, n, price, true);
                say(s, (r.ok() ? "&a" : "&c") + Text.strip(r.message()));
            }
            case "orders" -> {
                Player p = player(s, a, 1); if (p == null) return;
                List<Order> l = mk.ordersOf(p.getUniqueId());
                String mode = mk.modeBlock(p, "order", null);
                say(s, "&6" + p.getName() + " has " + l.size() + "/" + mk.maxOrders(p) + " orders &7(Flipper tier " + mk.flipperTier(p)
                        + ", tax " + Text.commas(mk.taxRate(p)) + "%, bot allowance left on this hour is per product"
                        + (mode == null ? "" : ", &c" + mode) + "&7)");
                for (Order o : l) say(s, "  &7" + o + " pending items " + o.itemsPending + " coins " + Pricing.fmtTotal(o.coinsPending));
            }
            case "cancel" -> {
                Player p = player(s, a, 1); if (p == null || a.length < 3) { say(s, "&cUsage: cancel <player> <id>"); return; }
                Order o = mk.order(parseLong(a[2]));
                Market.Result r = mk.cancel(p, o);
                say(s, (r.ok() ? "&a" : "&c") + Text.strip(r.message()));
            }
            case "claim" -> {
                Player p = player(s, a, 1); if (p == null) return;
                Market.Result r = a.length > 2 ? mk.claim(p, mk.order(parseLong(a[2])), true) : mk.claimAll(p);
                say(s, (r.ok() ? "&a" : "&c") + Text.strip(r.message()));
            }
            case "sellall" -> {
                Player p = player(s, a, 1); if (p == null) return;
                String w = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "inv";
                Market.Result r = mk.sellAll(p, !w.equals("sacks"), !w.equals("inv"));
                say(s, (r.ok() ? "&a" : "&c") + Text.strip(r.message()));
            }
            case "mm" -> mm(s, a);
            case "seed", "guild" -> seed(s, a);
            case "flow" -> {
                if (a.length < 4) { say(s, "&cUsage: flow <item> <sell units> <buy units>"); return; }
                String id = item(s, a[1]); Integer sell = num(s, a[2]); Integer buy = num(s, a[3]);
                if (id == null || sell == null || buy == null) return;
                int moved = Bz.mm.flowNow(id, sell, buy, System.currentTimeMillis());
                Bz.store.flush();
                say(s, "&aSimulated traders moved " + moved + " units of " + id);
            }
            case "expire" -> {
                boolean all = a.length > 1 && a[1].equalsIgnoreCase("all");   // "all" = force every open order to expire now
                say(s, "&aExpired " + mk.expire(all ? Long.MAX_VALUE : System.currentTimeMillis()) + " orders");
            }
            case "audit" -> {
                String r = mk.audit();
                say(s, r == null ? "&aBooks and order index agree. Escrow " + Pricing.fmtTotal(mk.escrowCoins()) + " coins, "
                        + Text.commasInt(mk.escrowItems()) + " items." : "&cAUDIT: " + r);
            }
            case "search" -> {
                String q = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                List<String> hits = Bz.cat.search(q, 20);
                say(s, "&6" + hits.size() + " hits for '" + q + "'");
                for (String id : hits) say(s, "  &7" + id + " &8- &f" + Catalogue.plainName(id) + " &7buy " + Pricing.fmt(mk.askPrice(id)) + " sell " + Pricing.fmt(mk.bidPrice(id)));
            }
            case "live" -> {
                String f = a.length > 1 ? a[1].toUpperCase(Locale.ROOT) : "";
                int n = 0;
                StringBuilder sb = new StringBuilder();
                for (Catalogue.Product p : Bz.cat.products.values()) {
                    if (!p.live || (!f.isEmpty() && !p.id.contains(f) && !p.category.equals(f))) continue;
                    n++;
                    if (sb.length() < 900) sb.append(p.id).append(' ');
                }
                say(s, "&6" + n + " live products" + (f.isEmpty() ? "" : " matching " + f) + ": &7" + sb);
            }
            default -> say(s, "&cUnknown sub. /sb bazaar " + USAGE);
        }
    }

    /** Merchant Guild supply drops: status, the list with each offer, force a drop. */
    private static void seed(CommandSender s, String[] a) {
        Seeder g = Bz.seeder;
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        switch (op) {
            case "now", "drop" -> {
                int n = g.drop(System.currentTimeMillis());
                Bz.store.flush();
                say(s, "&aMerchant Guild dropped: " + n + " offers on the book, " + g.skipped.size() + " skipped (see /sb bazaar seed list).");
            }
            case "list" -> {
                for (var e : g.list().entrySet()) {
                    String id = e.getKey();
                    String why = g.skipped.get(id);
                    long price = g.restingPrice(id);
                    say(s, "  &e" + id + " &7batch " + e.getValue() + (price > 0 ? " &a" + g.restingQty(id) + " left @ " + Pricing.fmt(price) : "")
                            + " &8sold " + g.soldBy.getOrDefault(id, 0L) + (why == null ? "" : " &c" + why));
                }
            }
            case "off" -> {
                String id = a.length > 2 ? item(s, a[2]) : null; if (id == null) return;
                g.withdraw(id);
                say(s, "&aWithdrew the guild offer on " + id + " until the next drop (remove it for good with bazaar.seed-products -" + id + ").");
            }
            default -> {
                long left = g.untilNext();
                say(s, "&6Merchant Guild &7(" + (BzDials.seedEnabled.get() ? "&aon" : "&coff") + "&7): " + g.list().size() + " materials, "
                        + g.resting.size() + " offers on the book, " + g.drops + " drops, next in " + Text.duration(left / 1000)
                        + ", sold &a" + Text.commasInt(g.unitsSold) + "&7/" + Text.commasInt(g.unitsPosted) + " units for &6" + Pricing.fmtTotal(g.coinsIn)
                        + " coins &7(removed from players)");
                say(s, "&7seed list | seed now | seed off <item>   &8dials bazaar.seed-*");
            }
        }
    }

    private static void mm(CommandSender s, String[] a) {
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        switch (op) {
            case "refresh" -> {
                long now = System.currentTimeMillis();
                if (a.length > 2) { String id = item(s, a[2]); if (id == null) return; Bz.mm.refresh(id, now); }
                else {
                    Bz.mm.computeCaps();
                    for (Catalogue.Product p : Bz.cat.products.values()) if (p.live) Bz.mm.refresh(p.id, now);
                    Bz.mm.drainReclamp(now, Integer.MAX_VALUE);
                }
                Bz.mm.flush();
                Bz.store.flush();
                say(s, "&aRe-quoted.");
            }
            case "reset" -> { String id = a.length > 2 ? item(s, a[2]) : null; if (id == null) return; Bz.mm.reset(id); Bz.store.flush(); say(s, "&aReset " + id + " to its reference."); }
            case "set" -> {
                if (a.length < 4) { say(s, "&cUsage: mm set <item> <fair coins>"); return; }
                String id = item(s, a[2]); long t = Pricing.parse(a[3]);
                if (id == null || t <= 0) return;
                Bz.mm.setFair(id, t / 10.0);
                Bz.store.flush();
                say(s, "&aFair value of " + id + " set to " + Pricing.fmt(t));
            }
            default -> say(s, "&7mm refresh [item] | mm reset <item> | mm set <item> <fair>  &8(see /sb bazaar price <item>)");
        }
    }

    static List<String> tab(CommandSender s, String[] a) {
        if (a.length <= 1) return filter(SUBS, a.length == 0 ? "" : a[0]);
        String sub = a[0].toLowerCase(Locale.ROOT);
        boolean playerArg = List.of("open", "buy", "sell", "order", "orders", "cancel", "claim", "sellall").contains(sub);
        if (a.length == 2 && playerArg) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, a[1]);
        }
        if (sub.equals("mm") && a.length == 2) return filter(List.of("refresh", "reset", "set"), a[1]);
        if (sub.equals("seed") && a.length == 2) return filter(List.of("list", "now", "off"), a[1]);
        int itemIdx = switch (sub) { case "price", "book", "flow" -> 1; case "open", "buy", "sell" -> 2; case "order" -> 3; case "mm", "seed" -> 2; default -> -1; };
        if (sub.equals("order") && a.length == 3) return filter(List.of("buy", "sell"), a[2]);
        if (itemIdx >= 0 && a.length == itemIdx + 1) {
            String pre = a[itemIdx].toUpperCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Catalogue.Product p : Bz.cat.products.values()) if (p.live && p.id.startsWith(pre)) { out.add(p.id); if (out.size() > 60) break; }
            return out;
        }
        return List.of();
    }

    private static List<String> filter(List<String> all, String pre) {
        List<String> out = new ArrayList<>();
        for (String x : all) if (x.toLowerCase(Locale.ROOT).startsWith(pre.toLowerCase(Locale.ROOT))) out.add(x);
        return out;
    }

    private static Player player(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cName a player."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null) say(s, "&cPlayer " + a[i] + " is not online.");
        return p;
    }

    private static String item(CommandSender s, String raw) {
        String id = raw.toUpperCase(Locale.ROOT);
        if (Bz.cat.product(id) != null) {
            if (!Bz.cat.isLive(id)) say(s, "&e" + id + " is in the catalogue but not live (no item registered).");
            return id;
        }
        List<String> hits = Bz.cat.search(raw.replace('_', ' '), 2);
        if (hits.size() == 1) return hits.get(0);
        say(s, "&cUnknown bazaar product " + raw);
        return null;
    }

    private static Integer num(CommandSender s, String raw) {
        try {
            double v = Double.parseDouble(raw.toLowerCase(Locale.ROOT).replace("k", "e3"));
            if (v < 1 || v > 10_000_000) throw new NumberFormatException();
            return (int) v;
        } catch (NumberFormatException e) {
            say(s, "&cBad amount " + raw);
            return null;
        }
    }

    private static long parseLong(String s) { try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; } }

    static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }
}
