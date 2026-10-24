package com.skyclone.mod.chocolate;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sb chocolate <sub> ...}: every sub that changes a player takes the player name first, so the integrator can
 * drive it over RCON. The Hunt itself is forced with core's {@code /sb calendar force hoppitys_hunt on}.
 */
final class ChocAdmin {

    private ChocAdmin() {}

    static final List<String> SUBS = List.of("info", "give", "set", "prestige", "rabbit", "egg", "eggs", "respawn-eggs",
            "stray", "tower", "bounty", "upgrade", "reset", "leaderboard");

    static void register() {
        Sky.commands().admin("chocolate",
                "info|give|set|prestige|rabbit|egg|eggs|respawn-eggs|stray|tower|bounty|upgrade|reset|leaderboard ...",
                "Chocolate Factory / Hunt admin (try /sb chocolate help)", ChocAdmin::run,
                (s, a) -> {
                    List<String> out = new ArrayList<>();
                    if (a.length <= 1) {
                        String pre = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
                        for (String sub : SUBS) if (sub.startsWith(pre)) out.add(sub);
                    } else if (a.length == 2) {
                        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
                    }
                    return out;
                });
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player target(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing player name."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null) { say(s, "&cNo online player " + a[i]); return null; }
        if (!Choc.ready(p)) { say(s, "&c" + p.getName() + " is not ready yet."); return null; }
        return p;
    }

    private static double number(CommandSender s, String v) {
        try {
            String t = v.toLowerCase(Locale.ROOT).trim();
            double mul = 1;
            if (t.endsWith("k")) { mul = 1e3; t = t.substring(0, t.length() - 1); }
            else if (t.endsWith("m")) { mul = 1e6; t = t.substring(0, t.length() - 1); }
            else if (t.endsWith("b")) { mul = 1e9; t = t.substring(0, t.length() - 1); }
            double d = Double.parseDouble(t) * mul;
            if (!Double.isFinite(d) || d < 0 || d > 1e15) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            say(s, "&cNot a number: " + v);
            return -1;
        }
    }

    static void run(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                ChocData d = Choc.data(p);
                ChocMath.Production prod = Factory.production(p, d, System.currentTimeMillis());
                say(s, "&6" + p.getName() + "&7: CF " + Text.roman(d.prestige) + ", chocolate &6" + Choc.num(d.chocolate)
                        + "&7, this prestige &6" + Choc.num(d.thisPrestige) + "&7, all-time &6" + Choc.num(d.allTime)
                        + "&7, spent &6" + Choc.num(d.spent));
                say(s, "&7CpS &6" + Choc.num(prod.cps()) + " &7= base " + Choc.num(prod.baseCps()) + " (emp " + Choc.num(prod.employeeCps())
                        + ", rabbits " + Choc.num(prod.rabbitCps()) + ", talisman " + Choc.num(prod.talismanCps()) + ", shard " + Choc.num(prod.shardCps())
                        + ") x " + Factory.fmtMult(prod.multiplier()) + " x speed " + String.format(Locale.ROOT, "%.4f", 1 + prod.speedBonus()));
                say(s, "&7rabbits " + d.unique() + "/" + Choc.catalog.rabbits.size() + " (barn " + Factory.barnCapacity(d) + "), hand-baked "
                        + d.handBaked + ", tower " + d.timeTower + " (charges " + d.towerCharges + "), shrine " + d.shrine + ", coach " + d.coach
                        + ", bounty " + d.hitmanEggs + "/" + d.hitmanSlots + ", eggs found " + d.eggsFound);
                say(s, "&7employees " + d.employees);
            }
            case "give", "set" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) { if (p != null) say(s, "&cUsage: /sb chocolate " + sub + " <player> <amount>"); return; }
                double v = number(s, a[2]);
                if (v < 0) return;
                ChocData d = Choc.data(p);
                if (sub.equals("give")) Factory.earn(p, d, v);
                else d.chocolate = v;
                say(s, "&a" + p.getName() + " now has &6" + Choc.num(d.chocolate) + " Chocolate");
            }
            case "prestige" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                ChocData d = Choc.data(p);
                if (a.length >= 3) {
                    int lvl;
                    try { lvl = Integer.parseInt(a[2]); } catch (NumberFormatException e) { say(s, "&cBad level"); return; }
                    d.prestige = Math.max(1, Math.min(Choc.catalog.maxPrestige(), lvl));
                    Factory.awardPrestigeXp(p, d);
                    say(s, "&a" + p.getName() + " is now Chocolate Factory " + Text.roman(d.prestige));
                } else {
                    double req = ChocMath.prestigeRequirement(Choc.catalog, d.prestige + 1);
                    if (req > d.thisPrestige) Factory.earn(p, d, req - d.thisPrestige);
                    say(s, Factory.prestige(p) ? "&aPrestiged " + p.getName() : "&cCould not prestige");
                }
            }
            case "rabbit" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) { if (p != null) say(s, "&cUsage: /sb chocolate rabbit <player> <id|all|random> [n]"); return; }
                String what = a[2].toLowerCase(Locale.ROOT);
                if (what.equals("all")) {
                    ChocData d = Choc.data(p);
                    int n = 0;
                    for (ChocCatalog.Rabbit r : Choc.catalog.rabbits) if (!d.has(r.id)) { Factory.grant(p, r, "admin", true, true); n++; }
                    say(s, "&aGave " + n + " rabbits");
                } else if (what.equals("random")) {
                    int n = a.length >= 4 ? parseInt(a[3], 1) : 1;
                    for (int i = 0; i < Math.min(500, n); i++) Factory.grant(p, Hunt.roll(p, Choc.data(p), null), "admin", false, false);
                } else {
                    ChocCatalog.Rabbit r = Choc.catalog.rabbit(what);
                    if (r == null) { say(s, "&cUnknown rabbit " + what); return; }
                    say(s, "&a" + Factory.grant(p, r, "admin", true, false));
                }
            }
            case "egg" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                String island = a.length >= 3 ? a[2] : Sky.worlds().islandOf(p.getLocation());
                Hunt.openEgg(p, Choc.data(p), island, "admin");
            }
            case "eggs" -> {
                if (!Hunt.active()) say(s, "&eThe Hunt is not running (force it: /sb calendar force " + Choc.HUNT + " on)");
                int n = 0;
                for (Hunt.Egg e : Hunt.eggs()) {
                    n++;
                    say(s, "&7" + e.island + " &" + e.meal.color + e.meal.id + " &7#" + e.instance + " "
                            + (e.loc == null ? "&cunresolved (chunk not loaded or no floor)" : "&a" + e.loc.getBlockX() + " " + e.loc.getBlockY() + " " + e.loc.getBlockZ())
                            + (e.display == null ? " &8(no entity)" : ""));
                }
                for (String isl : Hunt.islands()) say(s, "&8" + isl + ": " + Hunt.spots(isl).size() + " hiding places");
                say(s, "&7" + n + " eggs");
            }
            case "respawn-eggs" -> {
                Hunt.forgetSpots();
                Hunt.tick();
                say(s, "&aEgg spots recomputed and eggs respawned.");
            }
            case "stray" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                FactoryMenus.Session ss = FactoryMenus.session(p);
                if (ss == null) { say(s, "&c" + p.getName() + " does not have the factory open."); return; }
                FactoryMenus.spawnStray(p, ss, a.length >= 3 && a[2].equalsIgnoreCase("golden"));
                say(s, "&aStray spawned.");
            }
            case "tower" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                ChocData d = Choc.data(p);
                if (d.timeTower == 0) d.timeTower = 1;
                d.towerCharges = Factory.towerMaxCharges(d);
                d.towerChargeAt = 0;
                say(s, "&aTime Tower refilled (" + d.towerCharges + " charges).");
            }
            case "bounty" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 3) { if (p != null) say(s, "&cUsage: /sb chocolate bounty <player> <eggs>"); return; }
                ChocData d = Choc.data(p);
                int n = parseInt(a[2], 0);
                d.hitmanSlots = Math.max(d.hitmanSlots, Math.min(Choc.catalog.factory.hitmanCosts.size(), n));
                d.hitmanEggs = Math.min(d.hitmanSlots, Math.max(0, n));
                say(s, "&a" + p.getName() + " has " + d.hitmanEggs + " bounty eggs.");
            }
            case "upgrade" -> {
                Player p = target(s, a, 1);
                if (p == null || a.length < 4) { if (p != null) say(s, "&cUsage: /sb chocolate upgrade <player> <employee|hand|barn|tower|shrine|coach> <level>"); return; }
                ChocData d = Choc.data(p);
                int lvl = Math.max(0, parseInt(a[3], 0));
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "hand" -> d.handBaked = Math.max(1, Math.min(Choc.catalog.factory.handBaked.max, lvl));
                    case "barn" -> d.barn = Math.max(1, Math.min(Factory.barnMax(), lvl));
                    case "tower" -> { d.timeTower = Math.min(Choc.catalog.factory.timeTower.max, lvl); if (d.towerCharges < 0) d.towerCharges = Factory.towerMaxCharges(d); }
                    case "shrine" -> d.shrine = Math.min(Choc.catalog.factory.shrine.max, lvl);
                    case "coach" -> d.coach = Math.min(Choc.catalog.factory.coach.max, lvl);
                    default -> {
                        ChocCatalog.Employee e = Choc.catalog.employee(a[2].toLowerCase(Locale.ROOT));
                        if (e == null) { say(s, "&cUnknown upgrade " + a[2]); return; }
                        d.employees.put(e.id, Math.min(lvl, ChocMath.employeeCap(Choc.catalog.factory, e, d.prestige, Factory.youth(p))));
                    }
                }
                say(s, "&aDone.");
            }
            case "reset" -> {
                Player p = target(s, a, 1);
                if (p == null) return;
                ChocData d = Choc.data(p);
                ChocData fresh = new ChocData();
                d.chocolate = 0; d.thisPrestige = 0; d.allTime = 0; d.spent = 0; d.prestige = 1; d.employees.clear();
                d.handBaked = 1; d.barn = 1; d.timeTower = 0; d.shrine = 0; d.coach = 0; d.towerCharges = -1; d.towerChargeAt = 0;
                d.towerUntil = 0; d.rabbits.clear(); d.foundWhere.clear(); d.eggClaims.clear(); d.eggClaimsPrev.clear();
                d.hitmanSlots = 0; d.hitmanEggs = 0; d.eggsFound = 0; d.duplicates = 0; d.strays = 0; d.goldenStrays = 0;
                d.doradoCaught = 0; d.clicks = 0; d.talismans.clear(); d.seekerClaimed = false; d.lastTick = fresh.lastTick;
                say(s, "&aReset " + p.getName() + "'s factory (SkyBlock XP already paid is not taken back).");
            }
            case "leaderboard" -> {
                Leaderboard.pushAll();
                say(s, "&aPushed every online player to the leaderboard.");
            }
            default -> {
                say(s, "&6/sb chocolate &7info|give|set|prestige|rabbit|egg|eggs|respawn-eggs|stray|tower|bounty|upgrade|reset|leaderboard");
                say(s, "&7  give/set <p> <amount> · prestige <p> [level] · rabbit <p> <id|all|random [n]> · egg <p> [island]");
                say(s, "&7  stray <p> [golden] (factory must be open) · tower <p> · bounty <p> <eggs> · upgrade <p> <what> <lvl>");
                say(s, "&7  Hunt on/off: /sb calendar force " + Choc.HUNT + " on|off");
            }
        }
    }

    private static int parseInt(String v, int def) {
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }
}
