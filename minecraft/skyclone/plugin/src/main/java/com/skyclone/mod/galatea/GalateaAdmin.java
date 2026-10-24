package com.skyclone.mod.galatea;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** {@code /sb galatea ...} - every sub takes a player name, so it all works over RCON. */
final class GalateaAdmin {

    private GalateaAdmin() {}

    static final String USAGE = "info | frog <p> <SPECIES> <bronze|silver|gold|diamond> [n] | title <p> <0-4> | bless <p> [id] | wake <p> | "
            + "rain on|off|clear | journal <p> reset|complete | scroll <p> <id> [remove] | simulate <p> <casts> | where <p> | reset <p> confirm";

    static void register() {
        Sky.commands().admin("galatea", USAGE, "Lotus Atoll / Torrent Canyon admin tools", GalateaAdmin::run, (s, a) -> a.length <= 1
                ? List.of("info", "frog", "title", "bless", "wake", "rain", "journal", "scroll", "simulate", "where", "reset") : List.of());
    }

    static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    static Player target(CommandSender s, String[] a, int i) {
        Player p = a.length > i ? Bukkit.getPlayerExact(a[i]) : null;
        if (p == null || !GalateaModule.ready(p)) { say(s, "&cNo such online player (or profile not ready)."); return null; }
        return p;
    }

    static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb galatea " + USAGE); return; }
        switch (a[0].toLowerCase()) {
            case "info" -> {
                say(s, "&6Galatea: &7atoll built &e" + Sky.worlds().isBuilt(GalateaModule.ATOLL) + "&7, canyon built &e" + Sky.worlds().isBuilt(GalateaModule.CANYON)
                        + "&7, rain &e" + Sky.calendar().active(GalateaModule.RAIN) + "&7, journal chapters &e" + Journal.chapters().size()
                        + "&7, npcs &e" + GalateaModule.NPCS.size());
            }
            case "frog" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 4) { if (p != null) say(s, "&cfrog <p> <SPECIES> <tier> [n]"); return; }
                String id = a[2].toUpperCase() + "_" + a[3].toUpperCase();
                if (FrogMath.parse(id) == null) { say(s, "&cUnknown frog " + id); return; }
                int n = a.length > 4 ? Math.max(1, Math.min(64, GalateaModule.parse(a[4]))) : 1;
                for (int i = 0; i < n; i++) Frogs.award(p, id, "admin");
                say(s, "&aGave " + n + "x " + id + " to " + p.getName());
            }
            case "title" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) return;
                int t = Math.max(0, Math.min(4, GalateaModule.parse(a[2])));
                GalateaData d = GalateaModule.data(p);
                d.frogs.clear();
                if (t > 0) for (FrogMath.Species sp : FrogMath.SPECIES) d.frogs.put(sp.item(FrogMath.Tier.values()[t - 1]), 1);
                d.title = FrogMath.title(d.frogs);
                say(s, "&aLedger set: " + p.getName() + " is now title " + d.title + " (" + FrogMath.TITLES[d.title] + ")");
            }
            case "bless" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                Atoll.Blessing b = a.length > 2 ? Atoll.blessing(a[2]) : Atoll.BLESSINGS.get(0);
                if (b == null) { say(s, "&cBlessings: speed, creatures, treasure, trophy, wisdom"); return; }
                GalateaData d = GalateaModule.data(p);
                d.blessing = b.id();
                d.blessingUntil = System.currentTimeMillis() + GalateaModule.D.blessingMinutes.get() * 60_000L;
                Sky.stats().invalidate(p);
                say(s, "&a" + p.getName() + " has " + b.label());
            }
            case "wake" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                GalateaModule.data(p).eaterAwakeUntil = System.currentTimeMillis() + GalateaModule.D.eaterMinutes.get() * 60_000L;
                say(s, "&aThe Lotus Eater is awake for " + p.getName());
            }
            case "rain" -> {
                String mode = a.length > 1 ? a[1].toLowerCase() : "on";
                if (mode.equals("clear")) Sky.calendar().force(GalateaModule.RAIN, false);
                else Sky.calendar().force(GalateaModule.RAIN, mode.equals("on"));
                Atoll.tick();
                say(s, "&aAtoll rain: " + mode + " (active now: " + Sky.calendar().active(GalateaModule.RAIN) + ")");
            }
            case "journal" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) return;
                GalateaData d = GalateaModule.data(p);
                if (a[2].equalsIgnoreCase("reset")) { d.journal.clear(); d.journalDone.clear(); d.chaptersPaid.clear(); say(s, "&aJournal reset."); return; }
                for (Journal.Chapter c : Journal.chapters()) for (Journal.Task t : c.tasks) Journal.credit(p, t.kind, t.need);
                say(s, "&aJournal completed for " + p.getName() + " (" + d.chaptersPaid.size() + " chapters paid)");
            }
            case "scroll" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) return;
                GalateaData d = GalateaModule.data(p);
                if (a.length > 3 && a[3].equalsIgnoreCase("remove")) d.scrolls.remove(a[2]); else d.scrolls.add(a[2]);
                say(s, "&aScrolls of " + p.getName() + ": " + d.scrolls);
            }
            case "simulate" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                int casts = a.length > 2 ? Math.max(1, Math.min(200_000, GalateaModule.parse(a[2]))) : 10_000;
                FrogMath.Context c = Frogs.context(p);
                c.onAtoll = true;
                Map<String, Integer> out = new LinkedHashMap<>();
                Random r = new Random(1);
                int g = 0, dd = 0;
                for (int i = 0; i < casts; i++) {
                    String id = FrogMath.roll(c, r, GalateaModule.D.frogBase.get(), Sky.stats().get(p, Stat.TROPHY_FISH_CHANCE), g, dd,
                            GalateaModule.D.goldPity.get(), GalateaModule.D.diamondPity.get());
                    if (id == null) continue;
                    out.merge(id, 1, Integer::sum);
                    FrogMath.Tier t = (FrogMath.Tier) FrogMath.parse(id)[1];
                    g = t.ordinal() >= 2 ? 0 : g + 1;
                    dd = t == FrogMath.Tier.DIAMOND ? 0 : dd + 1;
                }
                say(s, "&6" + casts + " casts at " + p.getName() + "'s spot (" + FrogMath.eligible(c).size() + " species eligible):");
                List<String> lines = new ArrayList<>();
                out.forEach((k, v) -> lines.add(k + " " + v));
                say(s, "&7" + String.join(", ", lines));
            }
            case "where" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                FrogMath.Context c = Frogs.context(p);
                List<String> eligible = new ArrayList<>();
                for (FrogMath.Species sp : FrogMath.eligible(c)) eligible.add(sp.id());
                say(s, "&7zone &e" + Atoll.zoneId(p) + "&7, frog species here: &a" + eligible);
            }
            case "reset" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3 || !a[2].equals("confirm")) { say(s, "&creset <p> confirm"); return; }
                GalateaData d = GalateaModule.data(p);
                d.frogs.clear(); d.title = 0; d.sinceGold = 0; d.sinceDiamond = 0; d.blessing = null; d.blessingUntil = 0;
                d.coinsTossed = 0; d.eaterAwakeUntil = 0; d.scrolls.clear(); d.journal.clear(); d.journalDone.clear(); d.chaptersPaid.clear(); d.talked.clear();
                say(s, "&aGalatea data wiped for " + p.getName());
            }
            default -> say(s, "&e/sb galatea " + USAGE);
        }
    }
}
