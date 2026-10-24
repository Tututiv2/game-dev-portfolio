package com.skyclone.mod.bazaar;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Bazaar: a per-product order book exchange (research 05 s2) with instant trades, Buy Orders and Sell Offers,
 * escrow, tax, claims, expiry, price history and server market makers so it works with two players online.
 * Entry point; see {@link Market} for the money rules and {@link MarketMaker} for the liquidity.
 */
public final class BazaarModule implements GameModule, Listener {

    @Override public String id() { return "bazaar"; }

    @Override
    public void enable() {
        BzDials.register();
        Bz.cat = new Catalogue();
        Bz.cat.load();
        Bz.store = new Store();
        Bz.store.migrate();
        Bz.market = new Market(Bz.cat, Bz.store);
        Bz.market.load();
        Bz.mm = new MarketMaker(Bz.market, Bz.cat, Bz.store);
        Bz.market.mm = Bz.mm;
        Bz.mm.load();
        Bz.seeder = new Seeder(Bz.market, Bz.cat, Bz.mm);
        Bz.market.seeder = Bz.seeder;
        Bz.mm.seeder = Bz.seeder;

        Sky.provide(Features.Bazaar.class, new Api());

        NpcDef npc = NpcDef.of("bazaar_agent", "&6Bazaar Agent", "hub", "npc.bazaar").title("&e&lCLICK").entity("VILLAGER");
        npc.lines.add("&7Buy and sell everything");
        Sky.npcs().register(npc, p -> BazaarMenus.open(p, true));

        Sky.commands().register(SkyCommand.of("bz", "Open the Bazaar; /bz <item> searches, /bz orders|history|settings|sellinv|sellsacks|movers|favourites")
                .alias("bazaar").category("Economy").playerOnly()
                .usage("[item | orders | history | settings | sellinv | sellsacks | movers | favourites | recent]")
                .run((s, a) -> command((Player) s, a))
                .tab((s, a) -> a.length == 1 ? filter(List.of("orders", "history", "settings", "sellinv", "sellsacks", "movers", "favourites", "recent", "help"), a[0]) : List.of()));
        Sky.commands().admin("bazaar", Admin.USAGE, "Bazaar admin: inspect, trade for a player, drive the market makers", Admin::run, Admin::tab);
        SelfTests.register();
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
    }

    @Override
    public void postEnable() {
        Bz.cat.resolveLive();
        Sky.log().info("[bazaar] " + Bz.cat.liveProducts + " of " + Bz.cat.dataProducts + " catalogue products are live (+"
                + Bz.cat.extraProducts + " tagged by other modules)");
        long now = System.currentTimeMillis();
        Bz.store.pruneBars(PriceHistory.hourOf(now) - PriceHistory.KEEP_HOURS - 2);
        Bz.store.pruneLog(now - BzDials.logKeepDays.get() * 86_400_000L);
        try { Bz.mm.start(); } catch (RuntimeException e) { Sky.log().severe("[bazaar] market makers failed to start: " + e); }
        Bz.market.expire(now);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { Bz.mm.tick(); } catch (RuntimeException e) { Sky.log().warning("[bazaar] tick failed: " + e); }
        }, 40L, 1L);
        // Merchant Guild supply drops (research s2.9 seeding trick): first one shortly after boot, then on its interval
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { Bz.seeder.tick(); } catch (RuntimeException e) { Sky.log().warning("[bazaar] merchant guild failed: " + e); }
        }, 200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            Bz.market.expire(System.currentTimeMillis());
            flushBars();
        }, 1200L, 1200L);
    }

    @Override
    public void disable() {
        if (Bz.market == null) return;
        flushBars();
        Bz.mm.flush();
        Bz.store.flush();
    }

    static void flushBars() {
        List<Store.BarRow> rows = new ArrayList<>();
        for (var e : Bz.market.history.entrySet()) {
            for (PriceHistory.Bar b : e.getValue().dirtyBars()) {
                b.dirty = false;
                PriceHistory.Bar copy = new PriceHistory.Bar();
                copy.hour = b.hour; copy.open = b.open; copy.high = b.high; copy.low = b.low; copy.close = b.close;
                copy.volume = b.volume; copy.notional = b.notional; copy.midSum = b.midSum; copy.midCount = b.midCount;
                rows.add(new Store.BarRow(e.getKey(), copy));
            }
        }
        Bz.store.saveBars(rows);
    }

    /** Within 6 blocks of the hub's Bazaar Agent ({@code npc.bazaar}). */
    static boolean nearAgent(Player p) {
        try {
            org.bukkit.Location at = Sky.worlds().anchor("hub", "npc.bazaar");
            return at != null && at.getWorld() != null && at.getWorld().equals(p.getWorld())
                    && at.distanceSquared(p.getLocation()) <= 36.0;
        } catch (RuntimeException e) { return false; }
    }

    private static void command(Player p, String[] a) {
        // Standing at the Bazaar Agent, /bz is the same as clicking it (wave-4 loops playtest: /bz one block from the
        // NPC answered "Requires the Boon Buff! Visit the Bazaar in the Hub to trade there instead.").
        boolean atNpc = nearAgent(p);
        if (a.length == 0) { BazaarMenus.open(p, atNpc); return; }
        if (!Bz.access(p, atNpc, true)) return;
        if (Bz.special(p)) {
            // Special Bazaar: only its own page and Manage Orders (claims/cancels from before the mode)
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("orders") || sub.equals("manage")) OrdersMenu.open(p, null);
            else {
                String id = String.join("_", a).toUpperCase(Locale.ROOT);
                if (Bz.specialProduct(id) && Bz.cat.isLive(id)) BazaarMenus.openProduct(p, id);
                else BazaarMenus.special(p).open(p);
            }
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "orders", "manage" -> OrdersMenu.open(p, null);
            case "history" -> InfoMenus.history(p);
            case "settings" -> InfoMenus.settings(p);
            case "sellinv", "sellinventory" -> BazaarMenus.sellAll(p, true, PlayerData.of(p).sellSacksInSellAll && Inv.hasSacks());
            case "sellsacks" -> BazaarMenus.sellAll(p, false, true);
            case "movers" -> InfoMenus.movers(p);
            case "favourites", "favorites", "fav" -> InfoMenus.favourites(p, 0);
            case "recent" -> InfoMenus.recent(p, 0);
            case "help", "more" -> InfoMenus.hub(p);
            default -> {
                String q = String.join(" ", a);
                String id = q.toUpperCase(Locale.ROOT).replace(' ', '_');
                if (Bz.cat.isLive(id)) BazaarMenus.openProduct(p, id);
                else BazaarMenus.results(p, q, 0);
            }
        }
    }

    private static List<String> filter(List<String> all, String pre) {
        List<String> out = new ArrayList<>();
        for (String s : all) if (s.startsWith(pre.toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline() || Bz.market == null) return;
            String pid = Sky.profiles().profileId(p);
            int n = 0;
            for (Order o : Bz.market.ordersOf(p.getUniqueId())) if (o.owes() && pid != null && pid.equals(o.profileId)) n++;
            if (n > 0) Market.sendClickable(p, "&6[Bazaar] &eWhile you were away, &a" + n + " &eof your orders got something to claim! &7(click)");
        }, 80L);
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        Bz.lastCategory.remove(e.player.getUniqueId());
        if (Bz.market != null) Bz.market.forget(e.player.getUniqueId());
    }

    /** Features.Bazaar: price lookups other modules use for value displays and NPC fallbacks. */
    static final class Api implements Features.Bazaar {
        @Override public boolean isBazaarItem(String itemId) { return Bz.cat != null && Bz.cat.isLive(itemId); }

        @Override public double instantBuy(String itemId) {
            if (Bz.market == null || !isBazaarItem(itemId)) return 0;
            return Pricing.coins(Bz.market.askPrice(itemId));
        }

        @Override public double instantSell(String itemId) {
            if (Bz.market == null || !isBazaarItem(itemId)) return 0;
            return Pricing.coins(Bz.market.bidPrice(itemId));
        }

        @Override public void open(Player p, String itemId) {
            if (p == null) return;
            // another module's "View on Bazaar" button is remote access: the same rules as /bz apply
            if (itemId != null && isBazaarItem(itemId)) { if (Bz.access(p, false, true)) BazaarMenus.openProduct(p, itemId); }
            else if (itemId != null) { Msg.error(p, "That item isn't traded on the Bazaar."); }
            else BazaarMenus.open(p, false);
        }
    }
}
