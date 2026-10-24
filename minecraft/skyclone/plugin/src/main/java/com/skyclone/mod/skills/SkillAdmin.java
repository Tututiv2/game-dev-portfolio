package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /sb skill ...} (also {@code /sb skills ...}). Every sub takes a player NAME so it works
 * over RCON. Output is synchronous (RCON only sees what is printed while the command runs).
 */
final class SkillAdmin {

    static final String USAGE = "<player> <skill|all> addxp|rawxp|setxp|setlevel|setcap <n> | <player> info|reset [skill]|maxall | <player> trainer [info|finish|clear] | <player> runebook [n] | table <skill> [level]";

    private final SkillServiceImpl svc;
    private final SkillTable table;
    /** Set by the module (wave 5). */
    PetTrainer trainer;

    SkillAdmin(SkillServiceImpl svc, SkillTable table) {
        this.svc = svc;
        this.table = table;
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    void run(CommandSender s, String[] a) {
        if (a.length == 0) { help(s); return; }
        if (a[0].equalsIgnoreCase("table")) { table(s, a); return; }
        Player p = Bukkit.getPlayerExact(a[0]);
        if (p == null) { say(s, "&cNo online player named " + a[0] + "."); return; }
        if (!svc.ready(p)) { say(s, "&c" + p.getName() + "'s profile is not ready yet."); return; }
        if (a.length < 2) { info(s, p); return; }
        String sub = a[1].toLowerCase();
        switch (sub) {
            case "info" -> { info(s, p); return; }
            case "trainer" -> {
                if (trainer == null) { say(s, "&cNo Pet Trainer."); return; }
                say(s, trainer.admin(p, a.length >= 3 ? a[2] : "info"));
                return;
            }
            case "runebook" -> {
                SkillData d = svc.data(p);
                if (a.length >= 3) {
                    try { d.runebookKills = Math.max(0, Long.parseLong(a[2])); } catch (NumberFormatException e) { say(s, "&cNot a number: " + a[2]); return; }
                    Sky.stats().invalidate(p);
                }
                say(s, "&7" + p.getName() + ": Runebook &d" + d.runebookKills + " &7Runic kills, carried: "
                        + (svc.hasRunebook(p) ? "&ayes &7(+" + TrainerTiers.digits(d.runebookKills) + " Runecrafting Wisdom)" : "&cno"));
                return;
            }
            case "maxall" -> {
                for (SkillType t : SkillType.values()) svc.setLevel(p, t, table.maxLevel(t));
                say(s, "&aMaxed every skill for " + p.getName() + ".");
                return;
            }
            case "reset" -> {
                List<SkillType> which = a.length >= 3 ? skills(a[2]) : List.of(SkillType.values());
                if (which == null) { say(s, "&cUnknown skill " + a[2]); return; }
                for (SkillType t : which) svc.reset(p, t);
                say(s, "&aReset " + (which.size() == 1 ? which.get(0).display : "all skills") + " for " + p.getName()
                        + " &7(rewards already paid are not paid again)");
                return;
            }
            default -> { }
        }
        if (a.length < 4) { help(s); return; }
        List<SkillType> which = skills(a[1]);
        if (which == null) { say(s, "&cUnknown skill " + a[1]); return; }
        double n;
        try { n = parse(a[3]); } catch (NumberFormatException e) { say(s, "&cNot a number: " + a[3]); return; }
        if (!(n >= 0) || Double.isInfinite(n)) { say(s, "&cThe number must be 0 or more."); return; }
        String op = a[2].toLowerCase();
        for (SkillType t : which) {
            switch (op) {
                case "addxp" -> svc.addXp(p, t, n, "admin");
                case "rawxp" -> svc.grant(p, t, n, true);
                case "setxp" -> svc.setXp(p, t, n);
                case "setlevel" -> svc.setLevel(p, t, (int) n);
                case "setcap" -> svc.setCap(p, t, (int) n);
                default -> { say(s, "&cUnknown action " + a[2] + " (addxp|rawxp|setxp|setlevel|setcap)"); return; }
            }
        }
        for (SkillType t : which) say(s, line(p, t));
    }

    private static double parse(String v) {
        String x = v.trim().toLowerCase();
        double mul = 1;
        if (x.endsWith("k")) { mul = 1e3; x = x.substring(0, x.length() - 1); }
        else if (x.endsWith("m")) { mul = 1e6; x = x.substring(0, x.length() - 1); }
        else if (x.endsWith("b")) { mul = 1e9; x = x.substring(0, x.length() - 1); }
        return Double.parseDouble(x.replace(",", "")) * mul;
    }

    private static List<SkillType> skills(String arg) {
        if (arg.equalsIgnoreCase("all")) return List.of(SkillType.values());
        SkillType t = SkillType.parse(arg);
        return t == null ? null : List.of(t);
    }

    private String line(Player p, SkillType t) {
        int lvl = svc.level(p, t);
        return "&7" + t.display + ": &aLevel " + lvl + " &7(cap " + svc.cap(p, t) + "/" + table.maxLevel(t) + ") &e"
                + Text.commas(Math.floor(svc.xp(p, t) * 10) / 10) + " XP &7(" + Text.commasInt(svc.progress(p, t)) + "/"
                + Text.commasInt(svc.needed(p, t)) + ")";
    }

    private void info(CommandSender s, Player p) {
        say(s, "&6Skills of " + p.getName() + " &7- average &e" + SkillText.fmt(svc.average(p)));
        for (SkillType t : SkillType.values()) say(s, line(p, t));
    }

    private void table(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cUsage: /sb skill table <skill> [level]"); return; }
        SkillType t = SkillType.parse(a[1]);
        if (t == null) { say(s, "&cUnknown skill " + a[1]); return; }
        if (a.length < 3) {
            say(s, "&6" + t.display + "&7: max " + table.maxLevel(t) + ", default cap " + svc.defaultCap(t)
                    + ", XP to max &e" + Text.commasInt(table.total(t, table.maxLevel(t)))
                    + "&7, coins &6" + Text.commasInt(table.coinsThrough(t, table.maxLevel(t)))
                    + "&7, SkyBlock XP &b" + table.sbxpThrough(t, table.maxLevel(t)));
            StringBuilder sb = new StringBuilder("&7Stats at max:");
            for (Map.Entry<Stat, Double> e : table.statsAt(t, table.maxLevel(t)).entrySet())
                sb.append(" ").append(SkillText.statLine(e.getKey(), e.getValue()));
            if (table.perkStat(t) != null) sb.append(" ").append(SkillText.statLine(table.perkStat(t), table.perk(t, table.maxLevel(t))));
            else if (table.perk(t, table.maxLevel(t)) > 0) sb.append(" &7perk ").append(SkillText.fmt(table.perk(t, table.maxLevel(t))));
            say(s, sb.toString());
            return;
        }
        int L;
        try { L = Integer.parseInt(a[2]); } catch (NumberFormatException e) { say(s, "&cNot a level: " + a[2]); return; }
        SkillTable.Level lv = table.level(t, L);
        if (lv == null) { say(s, "&cLevel out of range 1-" + table.maxLevel(t)); return; }
        say(s, "&6" + SkillText.name(t, L) + "&7: total XP &e" + Text.commasInt(lv.total) + "&7 (step "
                + Text.commasInt(table.step(t, L)) + "), coins &6" + Text.commasInt(lv.coins) + "&7, SkyBlock XP &b" + lv.sbxp
                + "&7, perk &a" + SkillText.fmt(lv.perk));
        for (String l : SkillText.levelRewards(table, t, L, 1, 1, "  ")) say(s, l);
    }

    private void help(CommandSender s) {
        say(s, "&6/sb skill &7" + USAGE);
        say(s, "&7e.g. &e/sb skill Steve combat addxp 5000&7, &e/sb skill Steve farming setcap 60&7, &e/sb skill table mining 12");
    }

    List<String> complete(CommandSender s, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("table");
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 2) {
            if (a[0].equalsIgnoreCase("table")) return SkillsModule.skillNames(a[1], false);
            out.addAll(SkillsModule.skillNames(a[1], true));
            out.addAll(List.of("info", "reset", "maxall", "trainer", "runebook"));
        } else if (a.length == 3 && !a[0].equalsIgnoreCase("table")) {
            if (a[1].equalsIgnoreCase("reset")) return SkillsModule.skillNames(a[2], true);
            if (a[1].equalsIgnoreCase("trainer")) return List.of("info", "finish", "clear");
            if (a[1].equalsIgnoreCase("runebook")) return List.of("0", "9", "99", "999");
            out.addAll(List.of("addxp", "rawxp", "setxp", "setlevel", "setcap"));
        }
        return out;
    }
}
