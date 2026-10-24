package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /sb crimson ...}: every admin action takes a player name, so all of it works over RCON. */
final class CrimsonAdmin {

    private CrimsonAdmin() {}

    private static final String USAGE = "<info|rep|faction|quests|boss|spawn|vanquisher|dojo|armor|eruption|tp|open|pearls|kuudrareq|bossloot|ledger|synth> ...";

    static void register() {
        Sky.commands().admin("crimson", USAGE, "Crimson Isle: factions, quests, bosses, Dojo, armor", CrimsonAdmin::run, CrimsonAdmin::complete);
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) { say(s, "&cNo online player " + name); return null; }
        if (!CrimsonModule.ready(p)) { say(s, "&c" + name + "'s profile is not ready"); return null; }
        return p;
    }

    private static void run(CommandSender s, String[] a) {
        if (a.length == 0) { help(s); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "info" -> info(s, a);
                case "rep" -> rep(s, a);
                case "faction" -> faction(s, a);
                case "quests" -> quests(s, a);
                case "boss" -> boss(s, a);
                case "spawn" -> spawn(s, a);
                case "vanquisher" -> { if (a.length < 2) { say(s, "&c/sb crimson vanquisher <player>"); return; } Player p = player(s, a[1]); if (p != null) spawnAt(s, "crimson_vanquisher", p.getLocation(), p); }
                case "dojo" -> dojo(s, a);
                case "armor" -> armor(s, a);
                case "eruption" -> eruption(s, a);
                case "tp" -> tp(s, a);
                case "open" -> open(s, a);
                case "pearls" -> pearls(s, a);
                case "bossloot" -> bossLoot(s, a);
                case "ledger" -> ledger(s, a);
                case "synth" -> synth(s, a);
                case "kuudrareq" -> say(s, "&7Leviathan clear on the armor: " + (CrimsonModule.kuudraResolverPresent() ? "&aresolver present" : "&cno kuudra resolver")
                        + "&7, dial " + CrimsonModule.D.armorNeedsKuudra.get() + ", CRIMSON_CHESTPLATE requires " + (Sky.items().def("CRIMSON_CHESTPLATE") == null ? "?" : Sky.items().def("CRIMSON_CHESTPLATE").requirements));
                default -> help(s);
            }
        } catch (NumberFormatException e) {
            say(s, "&cNot a number: " + e.getMessage());
        }
    }

    private static void help(CommandSender s) {
        say(s, "&6/sb crimson &7- Crimson Isle admin");
        say(s, "&e info <p> &7standing, quests, Dojo, kills");
        say(s, "&e rep <p> <mage|barbarian> <set|add> <n>");
        say(s, "&e faction <p> <mage|barbarian|none> &7(free, no cooldown)");
        say(s, "&e quests <p> [show|reroll|complete <1-5>|progress <1-5> <n>]");
        say(s, "&e boss <list|spawn|kill> [bladesoul|mage_outlaw|barbarian_duke|ashfang|magma_boss]");
        say(s, "&e spawn <mobId> <p> &7spawn a crimson mob at a player (owned by them)");
        say(s, "&e vanquisher <p>");
        say(s, "&e dojo <p> <trial> <points> | dojo start <p> <trial> | dojo stop | dojo reset <p>");
        say(s, "&e armor <p> <crimson|aurora|terror|fervor|hollow> <0-4>");
        say(s, "&e eruption <on|off|clear>");
        say(s, "&e tp <p> <anchor> &7(spawn, boss.ashfang, dojo.center, npc.mage_leader...)");
        say(s, "&e open <p> <main|faction|quests|shop|bosses|dojo|prestige>");
        say(s, "&e pearls <p> [show|reset|collect|perfume] &7Heavy Pearls in the Belly of the Beast");
        say(s, "&e kuudrareq &7is the Leviathan clear on the armor active?");
        say(s, "&e bossloot <p> <boss> [damage%] &7pay <p> one mini-boss share (loot, rep, spoils, quests) as a non-killer");
        say(s, "&e ledger [boss] &7damage booked on live mini-boss fights");
        say(s, "&e synth <p> <x|y|z> <n> &7set what the Synthesizer in <p>'s hand has eaten");
    }

    private static void pearls(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&c/sb crimson pearls <player> [show|reset|collect|perfume]"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        String op = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "show";
        CrimsonData d = Pearls.today(p);
        switch (op) {
            case "reset" -> { d.pearlsTaken.clear(); d.perfumeUses = 0; d.perfumePearls = 0; say(s, "&aReset today's pearls for " + p.getName()); }
            case "collect" -> {
                java.util.Map<Integer, Integer> show = Pearls.showing(p);
                if (show.isEmpty()) { say(s, "&cNo pearl left for " + p.getName() + " today"); return; }
                Pearls.collect(p, show.values().iterator().next());
                say(s, "&aCollected one pearl for " + p.getName());
            }
            case "perfume" -> {
                Sky.loot().give(p, Pearls.PERFUME, 1, LootSource.ADMIN);
                say(s, Pearls.usePerfume(p) ? "&aUsed a perfume for " + p.getName() : "&cPerfume refused (daily cap?) - the item stays with " + p.getName());
            }
            default -> { }
        }
        java.util.List<org.bukkit.Location> spots = Pearls.spots();
        say(s, "&6Pearls &7" + p.getName() + ": day " + d.pearlDay + ", taken " + d.pearlsTaken + " of " + Pearls.allowance(d)
                + ", perfume " + d.perfumeUses + ", ever " + d.pearlsTotal + ", spots " + spots.size());
        for (java.util.Map.Entry<Integer, Integer> e : Pearls.showing(p).entrySet()) {
            org.bukkit.Location l = spots.get(e.getKey());
            say(s, "&7  pearl #" + e.getValue() + " at spot " + (e.getKey() + 1) + " (" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + ")");
        }
    }

    private static void info(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&c/sb crimson info <player>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        CrimsonData d = CrimsonModule.data(p);
        Factions.ensureBoard(p, d);
        say(s, "&6" + p.getName() + " &7faction: &f" + (d.faction == null ? "none" : d.faction)
                + " &7rep mage &f" + d.rep("mage") + " &7barbarian &f" + d.rep("barbarian"));
        say(s, "&7board " + d.questDay + " &7(" + d.quests.size() + " quests, bonus " + (d.dailyBonusClaimed ? "claimed" : "open") + ", total done " + d.questsDone + ")");
        int i = 1;
        for (CrimsonData.QuestState q : d.quests) {
            CrimsonTables.Quest t = CrimsonModule.tables.quest(q.id);
            say(s, "&8 " + i++ + ". &f" + q.id + " &7" + q.progress + "/" + (t == null ? "?" : t.amount) + (q.done ? " &aDONE" : ""));
        }
        say(s, "&7dojo total &f" + Dojo.total(d) + " &7best " + d.dojoBest + " &7belts " + d.beltsGiven);
        say(s, "&7boss kills " + d.bossKills);
        say(s, "&7leviathan clears seen " + d.kuudraSeen + " &7rep paid " + d.kuudraRepDay + " &7| rollim " + d.rollimClaims + "/"
                + CrimsonModule.D.rollimDays.get() + (d.rollimLast > 0 ? " &7last " + Text.duration((System.currentTimeMillis() - d.rollimLast) / 1000) + " ago" : ""));
    }

    private static void rep(CommandSender s, String[] a) {
        if (a.length < 5) { say(s, "&c/sb crimson rep <player> <mage|barbarian> <set|add> <n>"); return; }
        Player p = player(s, a[1]);
        String f = CrimsonRules.faction(a[2]);
        if (p == null || f == null) { if (f == null) say(s, "&cUnknown faction " + a[2]); return; }
        int n = Integer.parseInt(a[4]);
        CrimsonData d = CrimsonModule.data(p);
        if (a[3].equalsIgnoreCase("set")) { int cur = d.rep(f); Factions.addRep(p, f, n - cur, "admin"); }
        else Factions.addRep(p, f, n, "admin");
        say(s, "&a" + p.getName() + " " + f + " reputation is now " + d.rep(f));
    }

    private static void faction(CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&c/sb crimson faction <player> <mage|barbarian|none>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        CrimsonData d = CrimsonModule.data(p);
        if (a[2].equalsIgnoreCase("none")) { d.faction = null; d.quests.clear(); d.questDay = null; say(s, "&a" + p.getName() + " left their faction"); return; }
        String f = CrimsonRules.faction(a[2]);
        if (f == null) { say(s, "&cUnknown faction " + a[2]); return; }
        d.faction = null;                     // admin pledge skips fee and cooldown
        d.factionSince = 0;
        Factions.pledge(p, f);
        say(s, "&a" + p.getName() + " now serves the " + f + "s");
    }

    private static void quests(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&c/sb crimson quests <player> [show|reroll|complete <i>|progress <i> <n>]"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        CrimsonData d = CrimsonModule.data(p);
        String act = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "show";
        switch (act) {
            case "reroll" -> { d.questDay = null; d.quests.clear(); Factions.ensureBoard(p, d); say(s, "&aRerolled (" + d.quests.size() + " quests)"); }
            case "complete", "progress" -> {
                int i = Integer.parseInt(a.length > 3 ? a[3] : "1") - 1;
                if (i < 0 || i >= d.quests.size()) { say(s, "&cNo quest #" + (i + 1)); return; }
                CrimsonData.QuestState q = d.quests.get(i);
                CrimsonTables.Quest t = CrimsonModule.tables.quest(q.id);
                if (t == null) { say(s, "&cUnknown quest " + q.id); return; }
                if (act.equals("complete")) Factions.complete(p, d, q, t);
                else { q.progress = Math.min(t.amount, Integer.parseInt(a.length > 4 ? a[4] : "1")); if (q.progress >= t.amount) Factions.complete(p, d, q, t); }
                say(s, "&a" + q.id + " " + q.progress + "/" + t.amount + (q.done ? " DONE" : ""));
            }
            default -> info(s, new String[]{"info", a[1]});
        }
    }

    private static void boss(CommandSender s, String[] a) {
        String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        if (act.equals("list")) {
            for (String[] b : CrimsonMenus.BOSSES) {
                LivingEntity e = Bosses.alive(b[0]);
                say(s, "&e" + b[0] + " &7" + (e != null ? "&aALIVE " + Text.shortNum(Sky.mobs().health(e)) + "/" + Text.shortNum(Sky.mobs().maxHealth(e))
                        : "&cdown, back in " + Bosses.respawnLeft(b[0]) + "s"));
            }
            return;
        }
        if (a.length < 3) { say(s, "&c/sb crimson boss <spawn|kill> <name>"); return; }
        String id = a[2].startsWith("crimson_") ? a[2] : "crimson_" + a[2];
        String anchor = null;
        for (String[] b : CrimsonMenus.BOSSES) if (b[0].equals(id)) anchor = b[1];
        if (anchor == null) { say(s, "&cUnknown boss " + a[2]); return; }
        if (act.equals("kill")) {
            int n = 0;
            for (LivingEntity e : new ArrayList<>(Sky.mobs().live(id))) { Sky.mobs().remove(e); n++; }
            say(s, "&aRemoved " + n + " " + id + " &7(no loot, no timer: its spawn rule puts it back on its anchor"
                    + " within a second while a player is near - do not 'boss spawn' it as well)");
            return;
        }
        LivingEntity up = Bosses.alive(id);
        if (up != null) {
            // One at a time: the rule does not count a boss spawned here, so a second copy would be a second
            // kill, a second announcement and double reputation (playtest-endgame). Bosses.keepSingle merges
            // one anyway if it slips through, but say so instead of pretending to spawn.
            Location at = up.getLocation();
            say(s, "&e" + id + " is already alive &7at " + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ() + " ("
                    + Text.shortNum(Sky.mobs().health(up)) + "/" + Text.shortNum(Sky.mobs().maxHealth(up)) + "). Only one exists at a time.");
            return;
        }
        Location l = Sky.worlds().anchor(CrimsonModule.ISLAND, anchor);
        if (l == null) { say(s, "&cAnchor " + anchor + " missing (island not built?)"); return; }
        spawnAt(s, id, l, null);
    }

    private static void spawn(CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&c/sb crimson spawn <mobId> <player>"); return; }
        String id = a[1].startsWith("crimson_") ? a[1] : "crimson_" + a[1];
        Player p = player(s, a[2]);
        if (p != null) spawnAt(s, id, p.getLocation(), p);
    }

    private static void spawnAt(CommandSender s, String id, Location l, Player owner) {
        MobDef d = Sky.mobs().def(id);
        if (d == null) { say(s, "&cUnknown mob " + id); return; }
        LivingEntity e = Sky.mobs().spawn(id, l, owner);
        say(s, e == null ? "&cSpawn refused" : "&aSpawned " + d.name + " &7at " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
    }

    private static void dojo(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&c/sb crimson dojo <player> <trial> <points> | start <player> <trial> | stop | reset <player>"); return; }
        if (a[1].equalsIgnoreCase("stop")) { Dojo.stopAll("an admin stopped it"); say(s, "&aStopped"); return; }
        if (a[1].equalsIgnoreCase("start") && a.length >= 4) {
            Player p = player(s, a[2]);
            if (p == null) return;
            Location c = Sky.worlds().anchor(CrimsonModule.ISLAND, "dojo.center");
            if (c != null) p.teleport(c);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> Dojo.start(p, a[3].toLowerCase(Locale.ROOT)), 15L);
            say(s, "&aStarting " + a[3] + " for " + p.getName());
            return;
        }
        if (a[1].equalsIgnoreCase("reset") && a.length >= 3) {
            Player p = player(s, a[2]);
            if (p == null) return;
            CrimsonData d = CrimsonModule.data(p);
            d.dojoBest.clear();
            d.beltsGiven.clear();
            say(s, "&aReset " + p.getName() + "'s Dojo scores");
            return;
        }
        if (a.length < 4) { say(s, "&c/sb crimson dojo <player> <trial> <points>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        String trial = a[2].toLowerCase(Locale.ROOT);
        if (CrimsonModule.tables.trial(trial) == null) { say(s, "&cUnknown trial " + trial); return; }
        int pts = Math.max(0, Math.min(CrimsonModule.tables.dojo.max_points, Integer.parseInt(a[3])));
        CrimsonData d = CrimsonModule.data(p);
        d.dojoBest.put(trial, pts);
        Factions.dojoScored(p, trial, pts);
        Dojo.checkBelts(p, d);
        say(s, "&a" + p.getName() + " " + trial + " = " + pts + " (total " + Dojo.total(d) + ")");
    }

    private static void armor(CommandSender s, String[] a) {
        if (a.length < 4) { say(s, "&c/sb crimson armor <player> <set> <0-4>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        String set = a[2].toUpperCase(Locale.ROOT);
        int tier = Math.max(0, Math.min(4, Integer.parseInt(a[3])));
        int n = 0;
        for (String piece : new String[]{"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) {
            String id = CrimsonRules.TIER_PREFIX[tier] + set + "_" + piece;
            if (!Sky.items().exists(id)) { say(s, "&cUnknown item " + id); continue; }
            Sky.loot().give(p, id, 1, LootSource.ADMIN);
            n++;
        }
        say(s, "&aGave " + n + " pieces of " + CrimsonRules.TIER_NAME[tier] + " " + set);
    }

    private static void eruption(CommandSender s, String[] a) {
        String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "on";
        switch (act) {
            case "on" -> Sky.calendar().force(Bosses.ERUPTION, true);
            case "off", "clear" -> Sky.calendar().force(Bosses.ERUPTION, false);
            default -> { say(s, "&c/sb crimson eruption <on|off>"); return; }
        }
        say(s, "&aEruption " + act + " (active now: " + Sky.calendar().active(Bosses.ERUPTION) + ")");
    }

    private static void tp(CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&c/sb crimson tp <player> <anchor>"); return; }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null) { say(s, "&cNo online player " + a[1]); return; }
        if (!Sky.worlds().isBuilt(CrimsonModule.ISLAND)) { say(s, "&cThe Crimson Isle is not built yet"); return; }
        Location l = Sky.worlds().anchor(CrimsonModule.ISLAND, a[2]);
        if (l == null) { say(s, "&cNo anchor " + a[2]); return; }
        p.teleport(l);
        say(s, "&aTeleported " + p.getName() + " to " + a[2]);
    }

    private static void open(CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&c/sb crimson open <player> <main|faction|quests|shop|bosses|dojo|prestige>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "main" -> CrimsonMenus.main(p);
            case "faction" -> CrimsonMenus.faction(p);
            case "quests" -> CrimsonMenus.quests(p);
            case "shop" -> CrimsonMenus.shop(p, CrimsonModule.data(p).faction == null ? CrimsonRules.MAGE : CrimsonModule.data(p).faction, 0);
            case "bosses" -> CrimsonMenus.bosses(p);
            case "dojo" -> Dojo.menu(p);
            case "prestige" -> Prestige.menu(p, 0);
            default -> { say(s, "&cUnknown menu " + a[2]); return; }
        }
        say(s, "&aOpened " + a[2] + " for " + p.getName());
    }

    // ---- wave 5 ---------------------------------------------------------------------------------------------

    private static String bossId(String raw) { return raw.startsWith("crimson_") ? raw : "crimson_" + raw; }

    /** Pay one share of a mini-boss kill to a player as if they fought it without the last hit. */
    private static void bossLoot(CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&c/sb crimson bossloot <player> <boss> [damage%]"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        MobDef def = Sky.mobs().def(bossId(a[2]));
        if (def == null || !def.has("boss")) { say(s, "&cNot a crimson mini-boss: " + a[2]); return; }
        double pct = a.length > 3 ? Double.parseDouble(a[3]) : 10;
        double max = def.health * Math.max(0.0001, CrimsonModule.D.bossHpScale.get());
        double dmg = max * pct / 100.0;
        if (!CrimsonRules.sharesKill(dmg, max, CrimsonModule.D.bossLootMinPercent.get(), false)) {
            say(s, "&e" + pct + "% is under the share threshold (" + CrimsonModule.D.bossLootMinPercent.get() + "%): " + p.getName() + " would get nothing");
            return;
        }
        BossLoot.pay(p, def, false, dmg, 1, 1, dmg);
        say(s, "&aPaid " + p.getName() + " one share of " + def.id + " (" + Text.shortNum(dmg) + " damage)");
    }

    private static void ledger(CommandSender s, String[] a) {
        int n = 0;
        for (String[] b : CrimsonMenus.BOSSES) {
            if (a.length > 1 && !b[0].equals(bossId(a[1]))) continue;
            for (LivingEntity e : Sky.mobs().live(b[0])) {
                java.util.Map<java.util.UUID, Double> l = BossLoot.ledger(e.getUniqueId());
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<java.util.UUID, Double> en : CrimsonRules.ranking(l)) {
                    Player o = Bukkit.getPlayer(en.getKey());
                    sb.append(o == null ? en.getKey().toString().substring(0, 8) : o.getName()).append('=').append(Text.shortNum(en.getValue())).append(' ');
                }
                say(s, "&e" + b[0] + " &7" + Text.shortNum(Sky.mobs().health(e)) + "/" + Text.shortNum(Sky.mobs().maxHealth(e)) + " &f" + (sb.length() == 0 ? "(no hits)" : sb.toString()));
                n++;
            }
        }
        say(s, "&7" + n + " live fight(s), " + BossLoot.fights() + " ledger(s) held");
    }

    private static void synth(CommandSender s, String[] a) {
        if (a.length < 4) { say(s, "&c/sb crimson synth <player> <x|y|z> <n>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(hand);
        if (!Synthesizer.is(id)) { say(s, "&c" + p.getName() + " is not holding a Synthesizer"); return; }
        String item = a[2].toUpperCase(Locale.ROOT);
        CrimsonTables.SynthSlot slot = null;
        for (CrimsonTables.SynthSlot x : CrimsonRules.synthSlots(id)) if (x.item.equals(item)) slot = x;
        if (slot == null) { say(s, "&c" + id + " does not take " + item); return; }
        int n = Math.max(0, Math.min(slot.cap, Integer.parseInt(a[3])));
        Sky.items().update(hand, d -> d.extra.addProperty(CrimsonRules.synthKey(item), n));
        p.getInventory().setItemInMainHand(hand);
        say(s, "&a" + id + " in " + p.getName() + "'s hand: " + item + " = " + n + "/" + slot.cap);
    }

    private static List<String> complete(CommandSender s, String[] a) {
        if (a.length <= 1) return List.of("info", "rep", "faction", "quests", "boss", "spawn", "vanquisher", "dojo", "armor", "eruption", "tp", "open", "pearls", "kuudrareq", "bossloot", "ledger", "synth");
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return switch (a[0].toLowerCase(Locale.ROOT)) {
            case "boss" -> a.length == 2 ? List.of("list", "spawn", "kill") : List.of("bladesoul", "mage_outlaw", "barbarian_duke", "ashfang", "magma_boss");
            case "eruption" -> List.of("on", "off");
            case "bossloot" -> a.length == 2 ? names : a.length == 3 ? List.of("bladesoul", "mage_outlaw", "barbarian_duke", "ashfang", "magma_boss") : List.of("1", "10", "50");
            case "ledger" -> List.of("bladesoul", "mage_outlaw", "barbarian_duke", "ashfang", "magma_boss");
            case "synth" -> a.length == 2 ? names : a.length == 3 ? List.of("x", "y", "z") : List.of("0", "30", "60");
            case "pearls" -> a.length == 2 ? names : List.of("show", "reset", "collect", "perfume");
            case "rep", "faction" -> a.length == 2 ? names : List.of("mage", "barbarian", "none");
            case "armor" -> a.length == 2 ? names : a.length == 3 ? List.of("crimson", "aurora", "terror", "fervor", "hollow") : List.of("0", "1", "2", "3", "4");
            default -> names;
        };
    }
}
