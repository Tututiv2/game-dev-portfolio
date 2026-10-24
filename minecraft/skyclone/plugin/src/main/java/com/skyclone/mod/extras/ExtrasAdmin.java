package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /sb extras ...}: every action takes a player NAME so it can be driven over RCON; output is synchronous. */
final class ExtrasAdmin {

    private ExtrasAdmin() {}

    static final String USAGE = "info <p> | phone <p> [PHONE_ID] | contact <p> <id|all|clear> | contacts | call <p> <contact> | "
            + "harp <p> <SONG_ID|all|reset> | play <p> <SONG_ID> | city [<p> fund <project> <component> | progress <project> <n>] | "
            + "firesale [list|next|reset] | skin <p> <SKIN_ID> <slot> | trios <p> <n>";

    static void register() {
        Sky.commands().admin("extras", USAGE, "Harp, phone, City Projects, Fire Sales, skins (extras module)", ExtrasAdmin::run,
                (s, a) -> a.length == 1 ? List.of("info", "phone", "contact", "contacts", "call", "harp", "play", "city", "firesale", "skin", "trios") : List.of());
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(com.skyclone.api.ui.Text.c(msg)); }

    private static Player player(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing player."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null || !X.ready(p)) { say(s, "&cPlayer not online / not ready: " + a[i]); return null; }
        return p;
    }

    static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb extras " + USAGE); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "info" -> {
                Player p = player(s, a, 1); if (p == null) return;
                ExtrasData d = X.data(p);
                Catalog.Phone ph = Phone.bestPhone(p);
                say(s, "&ePhone: &f" + (ph == null ? "none" : ph.id) + " &7cap " + Phone.capacity(p) + " &7trios " + d.trios);
                say(s, "&eContacts (" + d.contacts.size() + "): &f" + String.join(", ", d.contacts));
                say(s, "&eHarp: &f" + d.songsDone.size() + " done, " + d.songsPerfect.size() + " perfect, Int +" + Harp.intelligence(d));
                say(s, "&eCity: &f" + String.join(", ", d.cityDone));
                say(s, "&eFire sale buys: &f" + d.fireBought);
            }
            case "phone" -> {
                Player p = player(s, a, 1); if (p == null) return;
                String id = a.length > 2 ? a[2].toUpperCase(Locale.ROOT) : "ABIPHONE_BASIC";
                if (!X.catalog.phones.containsKey(id)) { say(s, "&cUnknown phone " + id); return; }
                Sky.loot().give(p, id, 1, LootSource.ADMIN);
                say(s, "&aGave " + id + " to " + p.getName());
            }
            case "trios" -> {
                Player p = player(s, a, 1); if (p == null) return;
                X.data(p).trios = Math.max(0, Math.min(25, a.length > 2 ? ExtrasModule.parseInt(a[2]) : 0));
                say(s, "&aTrios = " + X.data(p).trios);
            }
            case "contact" -> {
                Player p = player(s, a, 1); if (p == null) return;
                if (a.length < 3) { say(s, "&cUsage: contact <p> <id|all|clear>"); return; }
                ExtrasData d = X.data(p);
                if (a[2].equalsIgnoreCase("clear")) { d.contacts.clear(); d.speedDial = ""; say(s, "&aCleared."); return; }
                List<Catalog.Contact> list = new ArrayList<>();
                if (a[2].equalsIgnoreCase("all")) { for (Catalog.Contact c : X.catalog.contacts.values()) if (Phone.available(c)) list.add(c); }
                else { Catalog.Contact c = X.catalog.contact(a[2].toLowerCase(Locale.ROOT)); if (c != null) list.add(c); }
                if (list.isEmpty()) { say(s, "&cUnknown contact " + a[2]); return; }
                for (Catalog.Contact c : list) if (!d.contacts.contains(c.id)) d.contacts.add(c.id);
                say(s, "&aAdded " + list.size() + " contact(s); now " + d.contacts.size());
            }
            case "contacts" -> {
                int ok = 0;
                List<String> missing = new ArrayList<>();
                for (Catalog.Contact c : X.catalog.contacts.values()) {
                    if (Phone.available(c)) ok++; else missing.add(c.id + "(" + String.join("/", c.npcs) + ")");
                    if (c.command != null && !Phone.COMMUNITY.equals(c.command) && !Sky.commands().exists(c.command))
                        say(s, "&e" + c.id + ": command /" + c.command + " is not registered");
                }
                say(s, "&aContacts in service: " + ok + "/" + X.catalog.contacts.size());
                if (!missing.isEmpty()) say(s, "&7No NPC: " + String.join(", ", missing));
            }
            case "call" -> {
                Player p = player(s, a, 1); if (p == null) return;
                Catalog.Contact c = a.length > 2 ? X.catalog.contact(a[2].toLowerCase(Locale.ROOT)) : null;
                if (c == null) { say(s, "&cUnknown contact."); return; }
                ExtrasModule.phone.ring(p, c);
                say(s, "&aRinging " + p.getName() + " from " + c.id);
            }
            case "harp" -> {
                Player p = player(s, a, 1); if (p == null) return;
                if (a.length < 3) { say(s, "&cUsage: harp <p> <SONG_ID|all|reset>"); return; }
                ExtrasData d = X.data(p);
                String w = a[2].toUpperCase(Locale.ROOT);
                if (w.equals("RESET")) { d.songsDone.clear(); d.songsPerfect.clear(); d.songBest.clear(); d.harpReward = false; }
                else if (w.equals("ALL")) { for (String id : X.catalog.songs.keySet()) if (!d.songsDone.contains(id)) d.songsDone.add(id); }
                else if (X.catalog.songs.containsKey(w)) { if (!d.songsDone.contains(w)) d.songsDone.add(w); }
                else { say(s, "&cUnknown song " + w); return; }
                Sky.stats().invalidate(p);
                say(s, "&aHarp: " + d.songsDone.size() + " songs, Int +" + Harp.intelligence(d));
            }
            case "play" -> {
                Player p = player(s, a, 1); if (p == null) return;
                Catalog.Song song = a.length > 2 ? X.catalog.songs.get(a[2].toUpperCase(Locale.ROOT)) : null;
                if (song == null) { say(s, "&cUnknown song."); return; }
                Harp.start(p, song, true);
                say(s, "&aStarted " + song.id + " for " + p.getName());
            }
            case "city" -> city(s, a);
            case "firesale" -> firesale(s, a);
            case "skin" -> {
                Player p = player(s, a, 1); if (p == null) return;
                if (a.length < 4) { say(s, "&cUsage: skin <p> <SKIN_ID> <slot 0-40>"); return; }
                Skins.apply(p, ExtrasModule.parseInt(a[3]), a[2].toUpperCase(Locale.ROOT));
                say(s, "&aTried to apply " + a[2] + " on slot " + a[3]);
            }
            default -> say(s, "&e/sb extras " + USAGE);
        }
    }

    private static void city(CommandSender s, String[] a) {
        if (a.length == 1) {
            say(s, "&eCity cycle " + City.cycle() + ", ends in " + (City.cycleMillisLeft() / 60000) + " min");
            for (Catalog.Project pr : City.active())
                say(s, "&f" + pr.id + " &7" + City.progress(pr) + "/" + City.goal(pr) + (City.complete(pr) ? " &aCOMPLETE" : ""));
            return;
        }
        if (a[1].equalsIgnoreCase("progress") && a.length > 3) {
            Catalog.Project pr = X.catalog.projects.get(a[2]);
            if (pr == null) { say(s, "&cUnknown project"); return; }
            City.PROGRESS.put(City.cycle() + ":" + pr.id, Math.max(0, ExtrasModule.parseInt(a[3])));
            say(s, "&aProgress set (memory only; resets on restart)");
            return;
        }
        Player p = player(s, a, 1); if (p == null) return;
        if (a.length > 4 && a[2].equalsIgnoreCase("fund")) {
            Catalog.Project pr = X.catalog.projects.get(a[3]);
            Catalog.Component co = pr == null ? null : pr.component(a[4]);
            if (co == null) { say(s, "&cUnknown project/component"); return; }
            City.fund(p, pr, co);
            say(s, "&aFund attempted; done = " + X.data(p).cityDone.contains(pr.id + ":" + co.id));
            return;
        }
        say(s, "&cUsage: city [<p> fund <project> <component> | progress <project> <n>]");
    }

    private static void firesale(CommandSender s, String[] a) {
        String w = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        long c = FireSales.cycle();
        switch (w) {
            case "next" -> c = c + 1;
            case "reset" -> { FireSales.SOLD.clear(); say(s, "&aSold counters cleared (memory only)."); return; }
            default -> { }
        }
        say(s, "&eFire sale cycle " + c + " open=" + FireSales.isOpen() + " catalogue=" + FireSales.catalogue().size());
        for (Catalog.Skin sk : FireSales.sale(c))
            say(s, "&f" + sk.id + " &7" + FireSales.price(sk) + " bits, sold " + FireSales.SOLD.getOrDefault(FireSales.key(sk, c), 0) + "/" + FireSales.stock(sk));
    }
}
