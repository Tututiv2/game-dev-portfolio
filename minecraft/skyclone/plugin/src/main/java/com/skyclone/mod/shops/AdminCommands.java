package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.shops.ShopData.Entry;
import com.skyclone.mod.shops.ShopData.ItemCost;
import com.skyclone.mod.shops.ShopData.Shop;
import com.skyclone.mod.shops.ShopData.Townsfolk;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** {@code /sb shops ...}: everything an admin (or the integrator over RCON) needs to exercise the shops. */
final class AdminCommands {

    private AdminCommands() {}

    private static final List<String> SUBS = List.of("list", "info", "open", "directory", "buy", "sell", "limits",
            "buyback", "missing", "station", "npcs", "external", "reprice", "discount", "minigame");

    static void register() {
        Sky.commands().admin("shops", "<" + String.join("|", SUBS) + "> ...",
                "Merchants: list/info/open/buy/sell/limits/buyback/missing/station/npcs/external/reprice/discount/minigame (RCON-testable)",
                AdminCommands::run, AdminCommands::complete);
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    private static Player player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) say(s, "&cNo online player " + name);
        return p;
    }

    private static Shop shop(CommandSender s, String id) {
        Shop sh = Shops.get(id);
        if (sh == null) say(s, "&cNo shop '" + id + "'. Try /sb shops list");
        return sh;
    }

    static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb shops " + String.join("|", SUBS)); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String[] r = Arrays.copyOfRange(a, 1, a.length);
        switch (sub) {
            case "list" -> list(s);
            case "info" -> { if (r.length < 1) say(s, "&e/sb shops info <shop>"); else info(s, r[0]); }
            case "open" -> {
                if (r.length < 2) { say(s, "&e/sb shops open <player> <shop>"); return; }
                Player p = player(s, r[0]); Shop sh = shop(s, r[1]);
                if (p == null || sh == null) return;
                ShopMenus.openShop(p, new ShopMenus.Session(sh, true, null, false));
                say(s, "&aOpened " + sh.name + " &afor " + p.getName());
            }
            case "directory" -> {
                if (r.length < 1) { say(s, "&e/sb shops directory <player>"); return; }
                Player p = player(s, r[0]);
                if (p != null) { ShopMenus.directory(p, 0).open(p); say(s, "&aOpened the directory for " + p.getName()); }
            }
            case "buy" -> buy(s, r);
            case "sell" -> sell(s, r);
            case "limits" -> limits(s, r);
            case "buyback" -> buyback(s, r);
            case "missing" -> missing(s, r.length > 0 ? r[0] : null);
            case "station" -> {
                int n = Stationing.stationAll();
                say(s, "&aStationing re-checked: &e" + n + " &aNPC(s) newly placed.");
            }
            case "npcs" -> npcs(s);
            case "external" -> external(s);
            case "reprice" -> say(s, "&aRe-priced &e" + ShopRecipes.refreshPrices() + " &aof &e" + ShopRecipes.lineCount() + " &aNPC shop recipe(s).");
            case "discount" -> discount(s, r);
            case "minigame" -> minigame(s, r);
            default -> say(s, "&cUnknown sub. &e/sb shops " + String.join("|", SUBS));
        }
    }

    private static void list(CommandSender s) {
        int total = 0, avail = 0;
        for (Shop sh : Shops.all()) {
            int n = Shops.availableCount(sh);
            total += sh.entries.size();
            avail += n;
            Shops.Status st = Shops.status(sh);
            String col = switch (st) { case STATIONED -> "&a"; case YIELDED -> "&7"; case PENDING -> "&e"; case DISABLED -> "&8"; };
            say(s, col + st.name().charAt(0) + " &f" + sh.id + " &8" + sh.island + "/" + sh.anchor + " &7stock &a" + n + "&7/" + sh.entries.size()
                    + (st == Shops.Status.YIELDED ? " &8(npc: " + Shops.YIELDED_TO.get(sh.id) + ")" : ""));
        }
        say(s, "&e" + Shops.BY_ID.size() + " merchants, " + avail + "/" + total + " stock lines resolve. &7S=stationed Y=yielded P=pending D=disabled");
    }

    private static void info(CommandSender s, String id) {
        Shop sh = shop(s, id);
        if (sh == null) return;
        say(s, "&e" + sh.name + " &7(" + sh.id + ", " + sh.mode + ", " + Shops.status(sh) + ") at &b" + sh.island + "/" + sh.anchor
                + " &7from " + sh.source);
        int i = 0;
        for (Entry e : sh.entries) {
            String rid = Shops.resolve(e);
            StringBuilder b = new StringBuilder("&8#" + (i++) + " ");
            b.append(rid == null ? "&c" + e.item + " (missing)" : "&f" + rid).append(" &8x").append(e.amount);
            if (e.coins > 0) {
                double eff = rid == null ? e.coins : Trade.bundleCoins(null, e);
                b.append(" &6").append(Text.commas(e.coins)).append("c");
                if (Math.abs(eff - e.coins) > 1e-9) b.append(" &c->").append(Text.commas(eff)).append("c");
            }
            if (e.bits > 0) b.append(" &b").append(e.bits).append(" bits");
            if (e.hasItemCosts()) for (ItemCost c : e.items) b.append(" &a+").append(c.item).append("x").append(c.amount)
                    .append(Shops.resolve(c) == null ? "&c(?)" : "");
            if (e.hasCurrency()) for (var c : e.currency) b.append(" &d+").append(c.id).append("x").append(c.amount)
                    .append(Shops.currencySupported(c.id) ? "&8(=" + Currencies.describe(c.id) + ")" : "&c(unspendable)");
            if (e.req != null) b.append(" &e[").append(e.req).append("]");
            if (e.tune) b.append(" &8TUNE");
            if (e.note != null) b.append(" &8(").append(e.note).append(")");
            say(s, b.toString());
        }
        if (sh.req != null) say(s, "&eEvery line requires: &f" + sh.req + " &7= " + Shops.describeReq(sh.req));
        if (sh.discountAttr != null) say(s, "&eExtra discount: &fHunting attribute value:" + sh.discountAttr
                + (ShopDials.HUNTING_DISCOUNTS.get() ? "" : " &c(dial shops.hunting-discounts is off)"));
        if (Shops.status(sh) == Shops.Status.YIELDED) say(s, "&7Spot held by &f" + Shops.YIELDED_TO.get(sh.id) + " &7(" + Shops.yieldedName(sh) + "&7)");
        say(s, "&7NPC_SHOP recipes registered (all shops): &a" + ShopRecipes.registered + " &7skipped &c" + ShopRecipes.skipped);
    }

    private static Entry findEntry(Shop sh, String key) {
        if (key.startsWith("#")) {
            try { int i = Integer.parseInt(key.substring(1)); return i >= 0 && i < sh.entries.size() ? sh.entries.get(i) : null; }
            catch (NumberFormatException e) { return null; }
        }
        for (Entry e : sh.entries) {
            if (key.equalsIgnoreCase(e.item) || key.equalsIgnoreCase(Shops.resolve(e))) return e;
        }
        return null;
    }

    private static void buy(CommandSender s, String[] r) {
        if (r.length < 3) { say(s, "&e/sb shops buy <player> <shop> <ITEM|#index> [bundles]"); return; }
        Player p = player(s, r[0]); Shop sh = shop(s, r[1]);
        if (p == null || sh == null) return;
        Entry e = findEntry(sh, r[2]);
        if (e == null) { say(s, "&cNo such stock line in " + sh.id); return; }
        int bundles = 1;
        if (r.length > 3) try { bundles = Math.max(1, Math.min(10_000, Integer.parseInt(r[3]))); } catch (NumberFormatException ignored) { }
        double before = Sky.economy().purse(p);
        int got = Trade.buy(p, sh, e, bundles, false);
        say(s, got > 0 ? "&aBought " + got + " " + Shops.resolve(e) + " for " + Sky.economy().format(before - Sky.economy().purse(p)) + " coins"
                : "&cRefused (see the player's chat)");
    }

    private static void sell(CommandSender s, String[] r) {
        if (r.length < 2) { say(s, "&e/sb shops sell <player> <ITEM_ID>  (sells the first stack of it)"); return; }
        Player p = player(s, r[0]);
        if (p == null) return;
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length; i++) {
            if (inv[i] != null && r[1].equalsIgnoreCase(Sky.items().idOf(inv[i]))) {
                double v = Trade.sellSlot(p, null, i, inv[i].clone());
                say(s, v > 0 ? "&aSold for " + Sky.economy().format(v) + " coins" : "&cRefused");
                return;
            }
        }
        say(s, "&c" + p.getName() + " has no " + r[1]);
    }

    private static void limits(CommandSender s, String[] r) {
        if (r.length < 1) { say(s, "&e/sb shops limits <player> [reset]"); return; }
        Player p = player(s, r[0]);
        if (p == null) return;
        if (!Sky.profiles().isReady(p)) { say(s, "&cProfile not ready"); return; }
        Pricing.Ledger l = Trade.ledger(p);
        if (r.length > 1 && r[1].equalsIgnoreCase("reset")) {
            l.bought.clear(); l.sold = 0;
            say(s, "&aDaily counters reset for " + p.getName());
            return;
        }
        say(s, "&eDay " + l.day + " &7limit &a" + Trade.dailyLimit() + " &7sold today &6" + Text.commas(l.sold)
                + " &7lifetime bought &a" + l.lifetimeItemsBought + " &7spent &6" + Text.shortNum(l.lifetimeCoinsSpent)
                + " &7sold &6" + Text.shortNum(l.lifetimeCoinsSold) + " &7met " + l.met.size());
        l.bought.forEach((k, v) -> say(s, " &7" + k + " &f" + v));
    }

    private static void buyback(CommandSender s, String[] r) {
        if (r.length < 1) { say(s, "&e/sb shops buyback <player> [index]"); return; }
        Player p = player(s, r[0]);
        if (p == null) return;
        if (r.length > 1) {
            try { say(s, Trade.buyBack(p, Integer.parseInt(r[1])) ? "&aBought back" : "&cRefused"); }
            catch (NumberFormatException e) { say(s, "&cindex must be a number"); }
            return;
        }
        var list = Trade.buybackList(p);
        for (int i = 0; i < list.size(); i++) say(s, "&8#" + i + " &f" + list.get(i).itemId + " x" + list.get(i).amount + " &6" + list.get(i).price);
        if (list.isEmpty()) say(s, "&7(empty)");
    }

    static List<String> missingIds(Shop only) {
        List<String> out = new ArrayList<>();
        for (Shop sh : Shops.all()) {
            if (only != null && only != sh) continue;
            for (Entry e : sh.entries) {
                if (Shops.resolve(e) == null) out.add(sh.id + ": " + e.item);
                if (e.hasItemCosts()) for (ItemCost c : e.items) if (Shops.resolve(c) == null) out.add(sh.id + ": cost " + c.item);
            }
        }
        return out;
    }

    private static void missing(CommandSender s, String shopId) {
        Shop only = shopId == null ? null : shop(s, shopId);
        if (shopId != null && only == null) return;
        List<String> m = missingIds(only);
        for (String x : m.subList(0, Math.min(80, m.size()))) say(s, "&c" + x);
        say(s, "&e" + m.size() + " unresolved id(s)" + (m.size() > 80 ? " (first 80 shown)" : ""));
    }

    private static void npcs(CommandSender s) {
        int[] c = new int[4];
        for (Shop sh : Shops.all()) c[Shops.status(sh).ordinal()]++;
        say(s, "&eMerchants: &a" + c[0] + " stationed &7" + c[1] + " yielded &e" + c[2] + " pending &8" + c[3] + " disabled");
        for (Shop sh : Shops.all()) if (Shops.status(sh) == Shops.Status.PENDING)
            say(s, " &e" + sh.island + "/" + sh.anchor + " &7(" + Text.strip(sh.name) + ")");
        int t = 0;
        for (Townsfolk tf : Shops.TOWNSFOLK) if (Shops.TOWN_STATUS.get(tf.id) == Shops.Status.STATIONED) t++;
        say(s, "&eTownsfolk: &a" + t + "&7/" + Shops.TOWNSFOLK.size() + " placed (anchors hub/npc.lore.1.." + Shops.TOWNSFOLK.size() + ")");
    }

    private static void external(CommandSender s) {
        for (ShopData.External x : Shops.EXTERNAL) {
            boolean live = Shops.externalLive(x);
            say(s, (live ? "&a" : "&8") + x.id + " &7" + Text.strip(x.name) + " &8" + x.island + " &7-> /" + x.command
                    + (x.args == null || x.args.isEmpty() ? "" : " " + String.join(" ", x.args)) + " &8(" + x.owner + ")"
                    + (live ? "" : " &c(command not registered: hidden)"));
        }
        say(s, "&e" + Shops.EXTERNAL.size() + " merchants run by other modules; dial shops.directory-external = " + ShopDials.DIRECTORY_EXTERNAL.get());
    }

    private static void discount(CommandSender s, String[] r) {
        if (r.length < 1) { say(s, "&e/sb shops discount <player> [shop]"); return; }
        Player p = player(s, r[0]);
        if (p == null) return;
        Shop sh = r.length > 1 ? shop(s, r[1]) : null;
        if (r.length > 1 && sh == null) return;
        double acc = Trade.accessoryDiscount(p), attr = Trade.attributeDiscount(p, sh);
        say(s, "&e" + p.getName() + (sh == null ? "" : " at " + sh.id) + ": &7accessory &a" + Text.commas(acc) + "% &7+ attribute &a"
                + Text.commas(attr) + "% &7= &a" + Text.commas(Pricing.combinedDiscount(acc, attr)) + "%");
    }

    private static void minigame(CommandSender s, String[] r) {
        if (r.length < 3) { say(s, "&e/sb shops minigame <player> <other> <TIC_TAC_TOE|CONNECT_FOUR>  (challenge as the player)"); return; }
        Player p = player(s, r[0]), o = player(s, r[1]);
        if (p == null || o == null) return;
        String g = r[2].toUpperCase(Locale.ROOT);
        if (!g.equals(Decorations.TTT) && !g.equals(Decorations.C4)) { say(s, "&cGame must be TIC_TAC_TOE or CONNECT_FOUR"); return; }
        Decorations.challenge(p, o, g);
        say(s, "&a" + p.getName() + " challenged " + o.getName() + " (" + g + ")");
    }

    static List<String> complete(CommandSender s, String[] a) {
        if (a.length <= 1) return SUBS;
        String sub = a[0].toLowerCase(Locale.ROOT);
        List<String> shops = new ArrayList<>(Shops.BY_ID.keySet());
        List<String> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
        return switch (sub) {
            case "info", "missing" -> a.length == 2 ? shops : List.of();
            case "open", "buy" -> a.length == 2 ? players : a.length == 3 ? shops : List.of();
            case "directory", "sell", "limits", "buyback" -> a.length == 2 ? players : List.of();
            case "discount" -> a.length == 2 ? players : a.length == 3 ? shops : List.of();
            case "minigame" -> a.length <= 3 ? players : List.of("TIC_TAC_TOE", "CONNECT_FOUR");
            default -> List.of();
        };
    }

    /** For self-tests: is this a sellable def at all. */
    static boolean sellable(ItemDef d) { return d != null && d.npcSell > 0; }
}
