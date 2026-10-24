package com.skyclone.mod.collections;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sb collection ...}: everything the integrator needs to exercise the system over RCON.
 * <pre>
 *   /sb collection list [category]
 *   /sb collection &lt;player&gt; info [item]
 *   /sb collection &lt;player&gt; &lt;item&gt; add|set|remove &lt;n&gt;
 *   /sb collection &lt;player&gt; &lt;item&gt; tier &lt;tier&gt;        (set the total to that tier's threshold)
 *   /sb collection &lt;player&gt; &lt;item&gt; reset
 *   /sb collection &lt;player&gt; maxall | resetall | sync
 *   /sb collection &lt;player&gt; open [category|item]
 *   /sb collection &lt;player&gt; check &lt;requirement key&gt;   (e.g. collection:WHEAT:4)
 * </pre>
 * Output is synchronous (RCON only sees what is printed while the command runs).
 */
final class CollectionsAdmin {

    private CollectionsAdmin() {}

    static void register(CollectionsModule mod) {
        Sky.commands().admin("collection",
                "list [cat] | lb [info|flush|prune|top <item> [mode]] | <player> info [item] | <player> <item> add|set|remove|tier <n> | <player> <item> reset | <player> maxall|resetall|sync | <player> open [x] | <player> check <key>",
                "Inspect and edit a player's collections",
                (s, a) -> run(mod, s, a),
                (s, a) -> complete(mod, a));
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    /** {@code /sb collection lb [info|flush|prune|top <item> [mode]]}: leaderboard state (synchronous output for RCON). */
    private static void leaderboard(CollectionsModule mod, CommandSender s, String[] a) {
        CollectionsLeaderboard lb = mod.leaderboard();
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        switch (sub) {
            case "flush" -> {
                for (Player p : Bukkit.getOnlinePlayers()) lb.noteAll(p);
                lb.flush();
                say(s, "&aLeaderboard flush queued &7(the board re-reads when the write lands)");
            }
            case "prune" -> {
                lb.pruneNow();
                say(s, "&aPrune queued &7(last run removed &e" + lb.lastPruned() + "&7; -1 = never ran)");
            }
            case "top" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb collection lb top <item> [classic|ironman|stranded|bingo]"); return; }
                CollectionCatalog.Coll c = mod.catalog().resolve(a[2]);
                if (c == null) { say(s, "&cUnknown collection: " + a[2]); return; }
                String board = a.length > 3 ? CollectionsLeaderboard.parseBoard(a[3]) : "normal";
                if (board == null) { say(s, "&cUnknown board: " + a[3] + " &7(classic, ironman, stranded, bingo)"); return; }
                StringBuilder per = new StringBuilder();
                lb.sizes(c).forEach((b, n) -> per.append(" &7").append(CollectionsLeaderboard.label(b)).append(" &e").append(n));
                say(s, "&e" + c.id + " &7" + CollectionsLeaderboard.label(board) + " board: " + lb.size(c, board)
                        + " profiles &8|" + (per.length() == 0 ? " &7none" : per.toString()));
                int i = 1;
                for (CollectionsLeaderboard.Row r : lb.top(c, board, 10)) {
                    say(s, "  &7#" + (i++) + " &f" + r.names() + " &8(" + r.profileId() + ") &e" + Text.commasInt(r.total()));
                }
            }
            default -> {
                long age = lb.loadedAt() == 0 ? -1 : (System.currentTimeMillis() - lb.loadedAt()) / 1000;
                say(s, "&7Leaderboards: " + (lb.available() ? "&atable ok" : "&ctable unavailable")
                        + " &7| dial " + (mod.leaderboardOn() ? "&aon" : "&coff") + " &7| min &e" + Text.commasInt(mod.leaderboardMin())
                        + " &7| pending &e" + lb.pending() + " &7| snapshot age &e" + (age < 0 ? "never" : age + "s"));
                say(s, "&7Modes: " + (mod.leaderboardSplitModes() ? "&asplit" : "&eone board")
                        + " &7| live modes from core: " + (lb.joinOk() ? "&ayes" : "&cno (stored mode)")
                        + " &7| prune " + (mod.leaderboardPrune() ? "&aon" : "&coff") + " &7(last &e" + lb.lastPruned() + "&7)");
            }
        }
    }

    private static void run(CollectionsModule mod, CommandSender s, String[] a) {
        CollectionCatalog cat = mod.catalog();
        if (a.length == 0) { say(s, "&cUsage: /sb collection list | <player> ..."); return; }
        if (a[0].equalsIgnoreCase("list")) {
            for (CollectionCatalog.Category c : cat.categories()) {
                if (a.length > 1 && !c.id.equalsIgnoreCase(a[1]) && !c.name.equalsIgnoreCase(a[1])) continue;
                List<String> ids = new ArrayList<>();
                for (CollectionCatalog.Coll co : cat.inCategory(c.id)) ids.add(co.id + "(" + co.maxTier() + ")");
                say(s, "&e" + c.name + " &7(" + ids.size() + "): &f" + String.join(", ", ids));
            }
            say(s, "&7Total: &e" + cat.resourceCount() + " &7collections, &e" + cat.resourceTiers() + " &7tiers (+ boss)");
            return;
        }
        if (a[0].equalsIgnoreCase("lb")) { leaderboard(mod, s, a); return; }
        Player p = Bukkit.getPlayerExact(a[0]);
        if (p == null) { say(s, "&cPlayer not online: " + a[0]); return; }
        if (!Sky.profiles().isReady(p)) { say(s, "&cProfile not ready yet for " + p.getName()); return; }
        CollectionsService svc = mod.service();
        if (a.length == 1 || a[1].equalsIgnoreCase("info")) {
            if (a.length > 2) {
                CollectionCatalog.Coll c = cat.resolve(a[2]);
                if (c == null) { say(s, "&cUnknown collection: " + a[2]); return; }
                info(mod, s, p, c);
                return;
            }
            int disc = 0, tiers = 0;
            List<String> parts = new ArrayList<>();
            for (CollectionCatalog.Coll c : cat.all()) {
                long n = svc.total(p, c);
                if (n <= 0) continue;
                disc++;
                int t = CollectionMath.tierFor(c, n);
                tiers += t;
                parts.add(c.id + "=" + n + "(" + t + ")");
            }
            say(s, "&e" + p.getName() + "&7: " + disc + " discovered, " + tiers + " tiers");
            if (!parts.isEmpty()) say(s, "&f" + String.join(" ", parts));
            return;
        }
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "maxall" -> {
                for (CollectionCatalog.Coll c : cat.all()) svc.set(p, c, c.tiers.get(c.tiers.size() - 1).amount);
                say(s, "&aMaxed every collection for " + p.getName());
                return;
            }
            case "resetall" -> {
                for (CollectionCatalog.Coll c : cat.all()) svc.remove(p, c, CollectionMath.MAX);
                say(s, "&aReset every collection for " + p.getName() + " &7(rewards already paid are kept)");
                return;
            }
            case "sync" -> {
                svc.syncAll(p);
                say(s, "&aRe-synced collection rewards for " + p.getName());
                return;
            }
            case "open" -> {
                if (a.length > 2) {
                    CollectionCatalog.Category c = cat.category(a[2]);
                    if (c != null) mod.menus().openCategory(p, c.id);
                    else {
                        CollectionCatalog.Coll co = cat.resolve(a[2]);
                        if (co != null) mod.menus().openCollection(p, co.id); else mod.menus().openMain(p);
                    }
                } else mod.menus().openMain(p);
                say(s, "&aOpened collections for " + p.getName());
                return;
            }
            case "check" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb collection <player> check <key>"); return; }
                String key = a[2];
                boolean ok = Sky.requirements().check(p, key);
                say(s, "&7" + key + " -> " + (ok ? "&atrue" : "&cfalse") + " &7(" + Sky.requirements().describe(key) + "&7)");
                return;
            }
            default -> { }
        }
        CollectionCatalog.Coll c = cat.resolve(a[1]);
        if (c == null) { say(s, "&cUnknown collection: " + a[1]); return; }
        if (a.length < 3) { info(mod, s, p, c); return; }
        String op = a[2].toLowerCase(Locale.ROOT);
        if (op.equals("reset")) {
            svc.remove(p, c, CollectionMath.MAX);
            say(s, "&aReset " + c.id + " for " + p.getName());
            return;
        }
        if (a.length < 4) { say(s, "&cUsage: /sb collection <player> <item> " + op + " <n>"); return; }
        long n;
        try { n = parse(a[3]); } catch (NumberFormatException e) { say(s, "&cNot a number: " + a[3]); return; }
        if (n < 0) { say(s, "&cNumber must be positive"); return; }
        long result;
        switch (op) {
            case "add" -> result = svc.add(p, c, Math.min(n, CollectionMath.MAX));
            case "set" -> result = svc.set(p, c, n);
            case "remove" -> result = svc.remove(p, c, n);
            case "tier" -> {
                if (n == 0) result = svc.set(p, c, 0);
                else {
                    CollectionCatalog.Tier t = c.tier((int) Math.min(n, c.maxTier()));
                    result = svc.set(p, c, t.amount);
                }
            }
            default -> { say(s, "&cUnknown op " + op + " (add|set|remove|tier|reset)"); return; }
        }
        say(s, "&a" + c.id + " for " + p.getName() + " is now &e" + Text.commasInt(result)
                + " &7(tier " + CollectionMath.tierFor(c, Math.max(0, result)) + "/" + c.maxTier() + ")");
    }

    private static void info(CollectionsModule mod, CommandSender s, Player p, CollectionCatalog.Coll c) {
        CollectionsService svc = mod.service();
        long n = svc.total(p, c);
        int t = CollectionMath.tierFor(c, n);
        say(s, "&e" + c.id + " &7(" + mod.rewards().collName(c) + ", " + c.category + "): &f" + Text.commasInt(n)
                + " &7tier &f" + t + "/" + c.maxTier() + " &7next " + CollectionMath.nextThreshold(c, t));
        for (var e : svc.contributions(p, c)) say(s, "  &b" + e.getKey() + "&7: " + Text.commasInt(e.getValue()));
        CollectionCatalog.Tier next = c.tier(t + 1);
        if (next != null) for (String l : mod.rewards().lines(c, next, 0)) say(s, "  &7next: " + l);
    }

    /** "64", "1.5k", "2m". */
    static long parse(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        double mult = 1;
        if (v.endsWith("k")) { mult = 1e3; v = v.substring(0, v.length() - 1); }
        else if (v.endsWith("m")) { mult = 1e6; v = v.substring(0, v.length() - 1); }
        else if (v.endsWith("b")) { mult = 1e9; v = v.substring(0, v.length() - 1); }
        double d = Double.parseDouble(v) * mult;
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException(raw);
        return (long) Math.min(CollectionMath.MAX, Math.floor(d));
    }

    private static List<String> complete(CollectionsModule mod, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("list");
            out.add("lb");
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("lb")) {
            out.addAll(List.of("info", "flush", "prune", "top"));
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("list")) {
            out.addAll(List.of("info", "maxall", "resetall", "sync", "open", "check"));
            String pre = a[1].toUpperCase(Locale.ROOT);
            for (CollectionCatalog.Coll c : mod.catalog().all()) if (c.id.startsWith(pre)) out.add(c.id);
        } else if (a.length == 3) {
            out.addAll(List.of("add", "set", "remove", "tier", "reset"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
