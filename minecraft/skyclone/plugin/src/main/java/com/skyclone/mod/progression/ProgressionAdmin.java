package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /sb progression ...} - every admin action, all taking a player NAME so they work over RCON.
 * All output is synchronous (CORE_GUIDE trap 11).
 */
final class ProgressionAdmin {

    private ProgressionAdmin() {}

    static final String USAGE = "info|xp|award|objective|guide|fairy|bestiary|museum|cake|celebrate|perks|open ... (/sb progression help)";

    static void register() {
        Sky.commands().admin("progression", USAGE, "Progression admin: SkyBlock XP, objectives, fairy souls, bestiary, museum, cakes",
                ProgressionAdmin::run, ProgressionAdmin::complete);
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static void help(CommandSender s) {
        say(s, "&6/sb progression &7subcommands:");
        say(s, "&e info <player> &7- level, xp by category, objective, souls, bestiary, museum");
        say(s, "&e xp <player> add <n> | revoke <key> | awards &7- SkyBlock XP");
        say(s, "&e award <player> <key> <xp> &7- a keyed one-time award (as other modules do)");
        say(s, "&e xpguide <player> &7- the nearest SkyBlock XP sources the guide shows them (kind, share left, XP)");
        say(s, "&e objective <player> list | complete <id> | reset | skipto <id>");
        say(s, "&e fairy list &7| &efairy <player> add <n> | all [island] | reset | exchange | nearest [tp|click]");
        say(s, "&e guide list &7| &eguide <player> status | step <guideId> | start <guideId> | reset [guideId|all]");
        say(s, "&e bestiary families [filter] &7| &ebestiary <player> kill <familyKey> <n> | vs <mobId>");
        say(s, "&e museum list [filter] &7| &emuseum <player> donate <entryId> | reset | milestone | bound");
        say(s, "&e perks <player> &7- SkyBlock Level perks (tome, Stacked Healing, Strongarm, Infliction)");
        say(s, "&e cake <player> <cakeId|all> &7- give century cakes");
        say(s, "&e celebrate century|newyear on|off|clear &7- force a cake celebration");
        say(s, "&e open <player> sblevel|questlog|fairy|bestiary|museum|cakes &7- open a menu for them");
    }

    private static Player player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) { say(s, "&cNo online player '" + name + "'."); return null; }
        if (!Sky.profiles().isReady(p)) { say(s, "&c" + p.getName() + "'s profile is not ready yet."); return null; }
        return p;
    }

    private static int num(String s, int def) {
        try { return (int) Math.round(Double.parseDouble(s)); } catch (Exception e) { return def; }
    }

    static void run(CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(s); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(s, a);
            case "xp" -> xp(s, a);
            case "award" -> {
                if (a.length < 4) { say(s, "&cUsage: award <player> <key> <xp>"); return; }
                Player p = player(s, a[1]); if (p == null) return;
                int before = (int) Prog.levels.xp(p);
                Prog.levels.award(p, a[2], num(a[3], 0), "Admin");
                say(s, "&aAward '" + a[2] + "': " + before + " -> " + (int) Prog.levels.xp(p) + " SB XP (a key pays once).");
            }
            case "objective" -> objective(s, a);
            case "guide" -> guide(s, a);
            case "perks" -> {
                if (a.length < 2) { say(s, "&cUsage: perks <player>"); return; }
                Player p = player(s, a[1]); if (p == null) return;
                int lvl = Prog.levels.level(p);
                say(s, "&6" + p.getName() + " &7SkyBlock Level &b" + lvl + " &7tome stage " + PMath.tomeStage(lvl)
                        + " &7claimed " + Prog.perks(p).claimed);
                say(s, "&7Stacked Healing (" + Perks.HEALING_LEVEL + "): " + (lvl >= Perks.HEALING_LEVEL ? "&a+" + Prog.perkHealingVitality.get() + " Vitality" : "&8locked")
                        + " &7Strongarm (" + Perks.STRONGARM_LEVEL + "): " + (lvl >= Perks.STRONGARM_LEVEL ? "&a+" + Prog.perkStrongarmDefense.get() + " Defense" : "&8locked")
                        + " &7Infliction (" + Perks.INFLICTION_LEVEL + "): " + (lvl >= Perks.INFLICTION_LEVEL ? "&a+" + Prog.perkInflictionPct.get() + "%" : "&8locked"));
                Prog.perksSys.checkTome(p);
            }
            case "xpguide" -> {
                if (a.length < 2) { say(s, "&cUsage: xpguide <player>"); return; }
                Player p = player(s, a[1]); if (p == null) return;
                List<PMath.XpTip> tips = Prog.xpGuide == null ? List.of() : Prog.xpGuide.tips(p, 10);
                say(s, "&6" + p.getName() + " &7SkyBlock XP &b" + (int) Prog.levels.xp(p) + " &7(Level " + Prog.levels.level(p) + "), " + tips.size() + " tips:");
                for (PMath.XpTip t : tips) say(s, " &8" + t.kind() + " " + String.format(Locale.US, "%.2f", t.remaining()) + " " + XpGuide.line(t));
            }
            case "fairy" -> fairy(s, a);
            case "bestiary" -> bestiary(s, a);
            case "museum" -> museum(s, a);
            case "cake" -> {
                if (a.length < 3) { say(s, "&cUsage: cake <player> <cakeId|all>"); return; }
                Player p = player(s, a[1]); if (p == null) return;
                int n = 0;
                for (String id : Prog.cakes.all().keySet()) {
                    if (a[2].equalsIgnoreCase("all") || a[2].equalsIgnoreCase(id)) { Sky.loot().give(p, id, 1, LootSource.ADMIN); n++; }
                }
                say(s, n == 0 ? "&cUnknown cake. Ids: " + String.join(", ", Prog.cakes.all().keySet()) : "&aGave " + n + " cake(s).");
            }
            case "celebrate" -> {
                if (a.length < 3) { say(s, "&cUsage: celebrate century|newyear on|off|clear"); return; }
                String id = a[1].equalsIgnoreCase("century") ? Cakes.CENTURY_EVENT : Cakes.NEW_YEAR_EVENT;
                if (a[2].equalsIgnoreCase("clear")) { say(s, "&7Use /sb calendar force " + id + " clear"); return; }
                Sky.calendar().force(id, a[2].equalsIgnoreCase("on"));
                say(s, "&aForced " + id + " " + a[2] + ".");
            }
            case "open" -> {
                if (a.length < 3) { say(s, "&cUsage: open <player> <menu>"); return; }
                Player p = player(s, a[1]); if (p == null) return;
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "sblevel" -> Prog.levels.openMenu(p);
                    case "questlog" -> Prog.objectives.openMenu(p);
                    case "fairy" -> Prog.fairy.openMenu(p, true);
                    case "bestiary" -> Prog.bestiary.openMenu(p);
                    case "museum" -> Prog.museum.openMenu(p);
                    case "cakes" -> Prog.cakes.openMenu(p);
                    default -> { say(s, "&cUnknown menu."); return; }
                }
                say(s, "&aOpened " + a[2] + " for " + p.getName() + ".");
            }
            default -> help(s);
        }
    }

    private static void info(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cUsage: info <player>"); return; }
        Player p = player(s, a[1]); if (p == null) return;
        PData.Level d = Prog.level(p);
        int lvl = PMath.level(d.xp, Prog.levelsFile.xpPerLevel);
        say(s, "&6" + p.getName() + " &7SkyBlock Level " + PMath.levelBracket(lvl) + " &7(" + d.xp + " XP, " + d.awards.size() + " awards)");
        StringBuilder sb = new StringBuilder("&7 by category: ");
        for (Map.Entry<String, Integer> e : Prog.levels.awardsByCategory(d).entrySet()) sb.append("&f").append(e.getKey()).append(" &b").append(e.getValue()).append("&7, ");
        say(s, sb.toString());
        String cur = Prog.objectives.current(p);
        PData.Objectives o = Prog.obj(p);
        say(s, "&7 objective: &e" + (cur.isEmpty() ? "(chain finished)" : cur) + " &7done " + o.done.size() + "/" + Prog.objectives.chain().size()
                + " skipped " + o.skipped.size() + " early " + o.early.size());
        PData.Fairy f = Prog.fairy(p);
        say(s, "&7 fairy souls: &d" + f.found.size() + "/" + Prog.fairy.total() + " &7exchanged " + f.exchanged);
        say(s, "&7 bestiary: &3" + Prog.best(p).kills.size() + " families killed, " + Prog.bestiary.totalTiers(Prog.best(p))
                + " tiers, milestone " + Prog.bestiary.milestone(p));
        say(s, "&7 museum: &6" + Prog.museum(p).exhibits.size() + " exhibits, " + Prog.museum.museumXp(Prog.museum(p)) + " XP");
        say(s, "&7 cakes active: &d" + Prog.cakes(p).active.size());
    }

    private static void xp(CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&cUsage: xp <player> add <n> | revoke <key> | awards"); return; }
        Player p = player(s, a[1]); if (p == null) return;
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                int n = a.length > 3 ? num(a[3], 0) : 0;
                if (n <= 0) { say(s, "&cGive a positive amount."); return; }
                Prog.levels.award(p, "admin:" + System.currentTimeMillis() + ":" + n, n, "Admin");
                say(s, "&aNow " + (int) Prog.levels.xp(p) + " SB XP (level " + Prog.levels.level(p) + ").");
            }
            case "revoke" -> {
                if (a.length < 4) { say(s, "&cUsage: xp <player> revoke <key>"); return; }
                Prog.levels.revoke(p, a[3]);
                say(s, "&aRevoked. Now " + (int) Prog.levels.xp(p) + " SB XP.");
            }
            case "awards" -> {
                PData.Level d = Prog.level(p);
                say(s, "&6" + d.awards.size() + " awards:");
                int n = 0;
                for (Map.Entry<String, Integer> e : d.awards.entrySet()) {
                    if (n++ >= 60) { say(s, "&7... (" + (d.awards.size() - 60) + " more)"); break; }
                    say(s, "&7 " + e.getKey() + " &b+" + e.getValue());
                }
            }
            default -> say(s, "&cUsage: xp <player> add <n> | revoke <key> | awards");
        }
    }

    private static void objective(CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&cUsage: objective <player> list | complete <id> | reset | skipto <id>"); return; }
        Player p = player(s, a[1]); if (p == null) return;
        PData.Objectives d = Prog.obj(p);
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                String cur = Prog.objectives.current(p);
                for (Catalogs.ObjectiveDef o : Prog.objectives.chain()) {
                    String st = d.done.contains(o.id) ? "&a done" : d.skipped.contains(o.id) ? "&7 skipped"
                            : o.id.equals(cur) ? "&e CURRENT" : d.early.contains(o.id) ? "&b early" : "&8 -";
                    say(s, st + " &f" + o.id + " &7" + o.trigger.type + " " + d.progress.getOrDefault(o.id, 0) + "/" + o.trigger.amount);
                }
            }
            case "complete" -> {
                if (a.length < 4) { say(s, "&cUsage: objective <player> complete <id>"); return; }
                Prog.objectives.complete(p, a[3]);
                boolean early = d.early.contains(a[3]);
                say(s, "&a" + (early ? "Recorded " + a[3] + " early (credited when the chain reaches it)" : "Completed " + a[3])
                        + ". Current: " + Prog.objectives.current(p));
            }
            case "reset" -> {
                d.done.clear(); d.skipped.clear(); d.early.clear(); d.progress.clear(); d.finished = false;
                say(s, "&aObjectives reset (SkyBlock XP already paid is kept). Current: " + Prog.objectives.current(p));
            }
            case "skipto" -> {
                if (a.length < 4 || Prog.objectives.def(a[3]) == null) { say(s, "&cUnknown objective id."); return; }
                for (Catalogs.ObjectiveDef o : Prog.objectives.chain()) {
                    if (o.id.equals(a[3])) break;
                    if (!d.done.contains(o.id)) d.done.add(o.id);
                    d.early.remove(o.id);
                }
                say(s, "&aCurrent: " + Prog.objectives.current(p) + " (earlier ones marked done without rewards)");
            }
            default -> say(s, "&cUsage: objective <player> list | complete <id> | reset | skipto <id>");
        }
    }

    private static void fairy(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
            Prog.fairy.rescan();
            say(s, "&d" + Prog.fairy.summary());
            say(s, "&7 heads on display: " + Prog.fairy.displayCount());
            return;
        }
        if (a.length < 3) { say(s, "&cUsage: fairy list | fairy <player> add <n> | all [island] | reset | exchange | nearest [tp|click]"); return; }
        Player p = player(s, a[1]); if (p == null) return;
        PData.Fairy d = Prog.fairy(p);
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                int n = a.length > 3 ? num(a[3], 1) : 1;
                int added = 0;
                for (int i = 0; i < n; i++) {
                    String id = "admin:" + (d.found.size() + 1);
                    while (d.found.contains(id)) id = id + "x";
                    d.found.add(id);
                    added++;
                }
                Prog.fairy.onReady(p);
                say(s, "&aAdded " + added + " placeholder souls. Found: " + d.found.size());
            }
            case "all" -> {
                int n = Prog.fairy.grantAll(p, a.length > 3 ? a[3] : null);
                say(s, "&aMarked " + n + " souls found. Found: " + d.found.size() + "/" + Prog.fairy.total());
            }
            case "reset" -> { Prog.fairy.reset(p); say(s, "&aFairy souls reset."); }
            case "exchange" -> {
                Prog.fairy.exchange(p, true);
                say(s, "&aExchanges: " + d.exchanged);
            }
            case "nearest" -> {
                // nearest unfound soul on their island; "tp" puts them 2 blocks from it, "click" collects it as a click would
                var near = Prog.fairy.nearestUnfound(p);
                if (near == null) { say(s, "&cNo unfound Fairy Soul on " + p.getName() + "'s island."); return; }
                org.bukkit.Location at = near.getValue();
                say(s, "&d" + near.getKey().id() + " &7at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()
                        + " &8(" + Math.round(at.distance(p.getLocation())) + " blocks)");
                String mode = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "";
                if (mode.equals("tp")) {
                    org.bukkit.Location to = at.clone().add(2, 0, 0);
                    to.setDirection(at.toVector().add(new org.bukkit.util.Vector(0, FairySouls.HOVER, 0)).subtract(to.toVector().add(new org.bukkit.util.Vector(0, 1.62, 0))));
                    p.teleport(to);
                    say(s, "&aTeleported " + p.getName() + " next to it.");
                } else if (mode.equals("click")) {
                    Prog.fairy.claim(p, near.getKey(), at.clone().add(0, FairySouls.HOVER, 0));
                    say(s, "&aCollected for " + p.getName() + ". Found: " + d.found.size() + "/" + Prog.fairy.total());
                }
            }
            default -> say(s, "&cUsage: fairy <player> add <n> | all [island] | reset | exchange | nearest [tp|click]");
        }
    }

    private static void guide(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
            for (Catalogs.IslandGuide g : Prog.islandGuides.all()) {
                say(s, "&f" + g.id + " &7'" + g.name + "' on " + g.island + ", " + g.steps.size() + " steps, " + g.coins + " coins");
            }
            List<String> bad = Prog.islandGuides.unwired();
            say(s, bad.isEmpty() ? "&aEvery guide step is wired." : "&c" + bad.size() + " unwired: " + bad);
            return;
        }
        if (a.length < 3) { say(s, "&cUsage: guide list | guide <player> status | step <id> | start <id> | reset [id|all]"); return; }
        Player p = player(s, a[1]); if (p == null) return;
        PData.Guides d = Prog.guides(p);
        String id = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : null;
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "status" -> {
                for (Catalogs.IslandGuide g : Prog.islandGuides.all()) {
                    int st = IslandGuides.step(d, g);
                    say(s, "&f" + g.id + " &7" + (d.finished.contains(g.id) ? "&adone" : d.skipped.contains(g.id) ? "&8skipped"
                            : st < 0 ? "&8not started" : "&estep " + (st + 1) + "/" + g.steps.size() + " progress " + d.progress.getOrDefault(g.id, 0)));
                }
            }
            case "step" -> say(s, id != null && Prog.islandGuides.adminStep(p, id) ? "&aCompleted the current step of " + id + "." : "&cUnknown guide.");
            case "start" -> {
                Catalogs.IslandGuide g = Prog.islandGuides.guide(id);
                if (g == null) { say(s, "&cUnknown guide."); return; }
                Prog.islandGuides.adminReset(p, g.id);
                Prog.islandGuides.maybeStart(p, g.island);
                say(s, "&aStarted " + g.id + " for " + p.getName() + ".");
            }
            case "reset" -> say(s, Prog.islandGuides.adminReset(p, id == null ? "all" : id) ? "&aGuide state reset." : "&cUnknown guide.");
            default -> say(s, "&cUsage: guide <player> status | step <id> | start <id> | reset [id|all]");
        }
    }

    private static void bestiary(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("families")) {
            Prog.bestiary.index();
            String filter = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
            int n = 0;
            for (Bestiary.Cat c : Prog.bestiary.visible()) {
                for (Bestiary.Fam f : c.families) {
                    if (!filter.isEmpty() && !f.key.contains(filter) && !f.name.toLowerCase(Locale.ROOT).contains(filter)) continue;
                    if (n++ < 80) say(s, "&7" + c.id + " &f" + f.key + " &7'" + f.name + "' max " + f.maxTier + " mobs " + f.mobIds);
                }
            }
            say(s, "&a" + n + " visible families (" + Prog.bestiary.curatedCount() + " curated in data).");
            return;
        }
        if (a.length >= 4 && a[2].equalsIgnoreCase("vs")) {
            Player p = player(s, a[1]); if (p == null) return;
            com.skyclone.api.mob.MobDef def = Sky.mobs().def(a[3]);
            if (def == null) { say(s, "&cUnknown mob id '" + a[3] + "'."); return; }
            String key = Prog.bestiary.familyKey(def, null);
            Bestiary.Fam f = key == null ? null : Prog.bestiary.family(key);
            if (f == null) { say(s, "&e" + def.id + " &7is not a bestiary family (slayer boss / summon / no_bestiary)."); return; }
            int t = Prog.bestiary.tier(Prog.best(p), f);
            say(s, "&e" + def.id + " &7-> family &f" + f.key + " &7(" + f.category + ") tier &b" + t + "/" + f.maxTier
                    + (f.combat() ? " &7| +" + (t * Prog.bestiaryDamage.get()) + "% dmg, +" + Prog.bestiary.magicFindVs(p, def)
                            + " MF (here), +" + PMath.bestiaryCoinPct(t, Prog.bestiaryCoinPerTier.get()) + "% coins"
                            : " &7| +" + (t * Prog.bestiaryFortunePerTier.get()) + " " + f.fortune.name())
                    + " &7| core MF for this kill: &b" + Sky.loot().magicFind(p, def));
            return;
        }
        if (a.length < 5 || !a[2].equalsIgnoreCase("kill")) { say(s, "&cUsage: bestiary families [filter] | bestiary <player> kill <familyKey> <n> | vs <mobId>"); return; }
        Player p = player(s, a[1]); if (p == null) return;
        String key = a[3];
        if (Prog.bestiary.family(key) == null && Prog.bestiary.family("mob:" + key) != null) key = "mob:" + key;
        if (Prog.bestiary.family(key) == null) { say(s, "&cUnknown family '" + a[3] + "' (see /sb progression bestiary families)."); return; }
        Prog.bestiary.addKills(p, key, Math.max(1, num(a[4], 1)));
        say(s, "&a" + key + " kills now " + Prog.best(p).kills.get(key) + ", tier " + Prog.bestiary.tier(Prog.best(p), Prog.bestiary.family(key)));
    }

    private static void museum(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("list")) {
            String filter = a.length > 2 ? a[2].toUpperCase(Locale.ROOT) : "";
            int n = 0, shown = 0;
            for (Catalogs.MCategory c : Prog.museumFile.categories) {
                for (Catalogs.MEntry e : c.entries) {
                    if (!Prog.museum.available(e)) continue;
                    n++;
                    if (!filter.isEmpty() && !e.id.contains(filter)) continue;
                    if (shown++ < 60) say(s, "&7" + c.id + " &f" + e.id + " &b+" + e.xp + (e.isSet() ? " &8(set of " + e.pieces.size() + ")" : ""));
                }
            }
            say(s, "&a" + n + "/" + Prog.museum.entryCount() + " museum entries exist in this world.");
            return;
        }
        if (a.length < 3) { say(s, "&cUsage: museum list [filter] | museum <player> donate <entryId> | reset"); return; }
        Player p = player(s, a[1]); if (p == null) return;
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "donate" -> {
                if (a.length < 4) { say(s, "&cUsage: museum <player> donate <entryId>"); return; }
                boolean ok = Prog.museum.adminDonate(p, a[3].toUpperCase(Locale.ROOT));
                say(s, ok ? "&aDonated " + a[3] + " (fresh items stored)." : "&cUnknown or unavailable entry.");
            }
            case "reset" -> {
                Prog.museum(p).exhibits.clear();
                Prog.museum(p).milestonesPaid.clear();
                say(s, "&aMuseum cleared for " + p.getName() + "'s profile (stored items deleted; XP kept).");
            }
            case "milestone" -> say(s, "&a" + p.getName() + ": museum XP " + Prog.museum.museumXp(Prog.museum(p))
                    + ", milestone " + Prog.museum.milestone(p) + ", special items " + Prog.museum.specialCount(Prog.museum(p))
                    + "/" + Prog.museumSpecialMax.get());
            case "bound" -> {
                org.bukkit.inventory.ItemStack held = p.getInventory().getItemInMainHand();
                String pid = Museum.boundProfile(held);
                say(s, pid == null ? "&7The held item is not co-op soulbound."
                        : "&aHeld item is co-op soulbound to profile " + pid + (Museum.foreignBound(p, held) ? " &c(NOT this player's co-op)" : " &a(this player's co-op)"));
            }
            default -> say(s, "&cUsage: museum <player> donate <entryId> | reset | milestone | bound");
        }
    }

    static List<String> complete(CommandSender s, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("help", "info", "xp", "award", "objective", "guide", "fairy", "bestiary", "museum", "cake", "celebrate", "perks", "open"))
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length == 2 && !List.of("celebrate").contains(a[0].toLowerCase(Locale.ROOT))) {
            if (a[0].equalsIgnoreCase("fairy")) out.add("list");
            if (a[0].equalsIgnoreCase("bestiary")) out.add("families");
            if (a[0].equalsIgnoreCase("museum")) out.add("list");
            if (a[0].equalsIgnoreCase("guide")) out.add("list");
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
        } else if (a.length == 2) {
            out.addAll(List.of("century", "newyear"));
        } else if (a.length == 3) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "xp" -> out.addAll(List.of("add", "revoke", "awards"));
                case "objective" -> out.addAll(List.of("list", "complete", "reset", "skipto"));
                case "fairy" -> out.addAll(List.of("add", "all", "reset", "exchange", "nearest"));
                case "bestiary" -> out.addAll(List.of("kill", "vs"));
                case "museum" -> out.addAll(List.of("donate", "reset", "milestone", "bound"));
                case "guide" -> out.addAll(List.of("status", "step", "start", "reset"));
                case "cake" -> { out.add("all"); out.addAll(Prog.cakes.all().keySet()); }
                case "celebrate" -> out.addAll(List.of("on", "off"));
                case "open" -> out.addAll(List.of("sblevel", "questlog", "fairy", "bestiary", "museum", "cakes"));
                default -> { }
            }
        } else if (a.length == 4 && a[0].equalsIgnoreCase("objective")) {
            for (Catalogs.ObjectiveDef o : Prog.objectives.chain()) if (o.id.startsWith(a[3])) out.add(o.id);
        }
        return out;
    }
}
