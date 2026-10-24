package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** {@code /sb slayer ...}: everything the integrator needs to exercise the system over RCON. */
final class SlayerAdmin {

    private SlayerAdmin() {}

    static final String USAGE = "<help|info|spawn|start|progress|xp|level|kills|rng|kill|fail|cancel|mini|loot|reset|menu|cocoon|mixin> ...";

    static void register(SlayerModule mod) {
        Sky.commands().admin("slayer", USAGE, "Slayer admin: spawn bosses, set XP/levels, RNG meter, loot simulation",
                (s, a) -> run(mod, s, a),
                (s, a) -> {
                    if (a.length <= 1) return List.of("help", "info", "spawn", "start", "progress", "xp", "level", "kills", "rng", "kill",
                            "fail", "cancel", "mini", "loot", "reset", "menu", "cocoon", "mixin");
                    if (a.length == 2 && !a[0].equalsIgnoreCase("loot")) {
                        List<String> names = new ArrayList<>();
                        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                        return names;
                    }
                    return mod.tabTypes(List.of());
                });
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player target(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) { say(s, "&cNo online player " + name); return null; }
        if (!Sky.profiles().isReady(p)) { say(s, "&c" + name + " is not ready yet"); return null; }
        return p;
    }

    private static int num(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    private static double dnum(String s, double def) {
        try { return Double.parseDouble(s.trim().replace(",", "")); } catch (RuntimeException e) { return def; }
    }

    static void run(SlayerModule mod, CommandSender s, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            say(s, "&5/sb slayer &7subcommands:");
            say(s, " &einfo [player] &7- catalog summary or a player's slayer data");
            say(s, " &espawn <player> <type> <tier> &7- free quest + boss right now");
            say(s, " &estart <player> <type> <tier> [paid] &7- start a quest (free unless 'paid')");
            say(s, " &eprogress <player> <combatXp> &7- add quest bar progress");
            say(s, " &exp <player> <type> <amount> &7- add slayer XP (negative removes)");
            say(s, " &elevel <player> <type> <level> &7- set slayer level");
            say(s, " &ekills <player> <type> <tier> <n> &7- set boss kills (unlocks)");
            say(s, " &erng <player> <type> [item|clear|fill|<xp>] &7- RNG meter");
            say(s, " &ekill <player> &7- kill the player's boss (loot + XP)");
            say(s, " &efail|cancel <player> &7- fail / cancel the quest");
            say(s, " &emini <player> <type> [mobId] &7- spawn a miniboss");
            say(s, " &eloot <type> <tier> <level> [rolls] [magicFind] &7- simulate the loot table");
            say(s, " &ereset <player> [type] &7- wipe slayer data");
            say(s, " &emenu <player> [type] &7- open a menu for a player");
            say(s, " &ecocoon <player> <type> <tier|mini> &7- force a Primordial cocoon next to the player");
            say(s, " &emixin <player> <MIXIN_ID> [hours] &7- set a mixin active (0 removes)");
            say(s, "&7Types: zombie spider wolf enderman blaze vampire");
            return;
        }
        String sub = a[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "info" -> info(mod, s, a);
            case "loot" -> loot(mod, s, a);
            default -> {
                if (a.length < 2) { say(s, "&cUsage: /sb slayer " + sub + " <player> ..."); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                playerSub(mod, s, sub, p, a);
            }
        }
    }

    private static void playerSub(SlayerModule mod, CommandSender s, String sub, Player p, String[] a) {
        SlayerData d = mod.quests.data(p);
        SlayerCatalog.Type t = a.length > 2 ? mod.catalog.type(SlayerModule.alias(a[2])) : null;
        switch (sub) {
            case "spawn" -> {
                if (t == null || a.length < 4) { say(s, "&cUsage: spawn <player> <type> <tier>"); return; }
                int tier = num(a[3], 1);
                if (t.tier(tier) == null) { say(s, "&cNo tier " + tier + " for " + t.id); return; }
                if (d.hasQuest()) mod.quests.cancel(p);
                mod.quests.start(p, t.id, tier, true, false);
                d.questProgress = mod.quests.spawnTarget(t.tier(tier));
                mod.quests.spawnBoss(p, p.getLocation(), true);
                say(s, "&aSpawning " + t.bossName(tier) + " " + SlayerMath.roman(tier) + " for " + p.getName());
            }
            case "start" -> {
                if (t == null || a.length < 4) { say(s, "&cUsage: start <player> <type> <tier> [paid]"); return; }
                boolean paid = a.length > 4 && a[4].equalsIgnoreCase("paid");
                boolean ok = mod.quests.start(p, t.id, num(a[3], 1), !paid, false);
                say(s, ok ? "&aQuest started" : "&cCould not start (see the player's chat)");
            }
            case "progress" -> {
                if (!d.hasQuest()) { say(s, "&c" + p.getName() + " has no quest"); return; }
                double add = a.length > 2 ? dnum(a[2], 0) : 0;
                d.questProgress += add;
                SlayerCatalog.Type qt = mod.catalog.type(d.questType);
                SlayerCatalog.Tier tier = qt == null ? null : qt.tier(d.questTier);
                if (tier != null && Quests.COLLECTING.equals(d.questState) && d.questProgress >= mod.quests.spawnTarget(tier)) {
                    d.questProgress = mod.quests.spawnTarget(tier);
                    mod.quests.spawnBoss(p, p.getLocation());
                }
                say(s, "&aProgress now " + Text.commasInt(d.questProgress));
            }
            case "xp" -> {
                if (t == null || a.length < 4) { say(s, "&cUsage: xp <player> <type> <amount>"); return; }
                mod.addXp(p, t, dnum(a[3], 0), "admin");
                say(s, "&a" + p.getName() + " " + t.id + " XP = " + Text.commasInt(d.xp(t.id)) + " (level " + mod.level(p, t) + ")");
            }
            case "level" -> {
                if (t == null || a.length < 4) { say(s, "&cUsage: level <player> <type> <level>"); return; }
                int lvl = Math.max(0, Math.min(t.xp.length, num(a[3], 0)));
                double xp = lvl == 0 ? 0 : t.xp[lvl - 1];
                d.xp.put(t.id, xp);
                if (lvl < d.announced.getOrDefault(t.id, 0)) d.announced.put(t.id, lvl);
                mod.checkLevelAnnouncements(p);
                Sky.stats().invalidate(p);
                say(s, "&a" + p.getName() + " is now " + t.slayerName() + " " + lvl);
            }
            case "kills" -> {
                if (t == null || a.length < 5) { say(s, "&cUsage: kills <player> <type> <tier> <n>"); return; }
                d.kills.put(t.id + ":" + num(a[3], 1), Math.max(0, num(a[4], 0)));
                say(s, "&aSet " + t.id + " T" + a[3] + " kills to " + a[4]);
            }
            case "rng" -> {
                if (t == null) { say(s, "&cUsage: rng <player> <type> [item|clear|fill|<xp>]"); return; }
                if (a.length < 4) {
                    say(s, "&7Selected: &f" + d.rngSelected.get(t.id) + " &7meter XP: &d" + Text.commasInt(d.rngXp.getOrDefault(t.id, 0.0)));
                    for (SlayerCatalog.LootEntry e : t.loot) if (e.rng > 0) say(s, " &8- &f" + e.item + " &7" + Text.commasInt(e.rng) + " XP, lvl " + e.minLevel);
                    return;
                }
                String arg = a[3];
                if (arg.equalsIgnoreCase("clear")) { d.rngSelected.remove(t.id); d.rngXp.put(t.id, 0.0); say(s, "&aCleared"); return; }
                if (arg.equalsIgnoreCase("fill")) {
                    SlayerCatalog.LootEntry e = t.lootEntry(d.rngSelected.getOrDefault(t.id, ""));
                    if (e == null) { say(s, "&cNothing selected"); return; }
                    d.rngXp.put(t.id, (double) e.rng);
                    say(s, "&aMeter full: the next eligible kill drops " + e.item);
                    return;
                }
                SlayerCatalog.LootEntry e = t.lootEntry(arg.toUpperCase(java.util.Locale.ROOT));
                if (e != null && e.rng > 0) { d.rngSelected.put(t.id, e.item); say(s, "&aSelected " + e.item); return; }
                double xp = dnum(arg, -1);
                if (xp >= 0) { d.rngXp.put(t.id, xp); say(s, "&aMeter XP = " + Text.commasInt(xp)); return; }
                say(s, "&cUnknown item or number: " + arg);
            }
            case "kill" -> {
                BossFight f = mod.quests.fights.get(p.getUniqueId());
                if (f == null || f.boss == null) { say(s, "&c" + p.getName() + " has no live boss"); return; }
                f.immune = null;
                Sky.mobs().setHealth(f.boss, 0);
                say(s, "&aKilled the boss");
            }
            case "fail" -> { mod.quests.fail(p, "An admin failed your quest."); say(s, "&aFailed"); }
            case "cancel" -> { mod.quests.cancel(p); say(s, "&aCancelled"); }
            case "mini" -> {
                if (t == null || t.minibosses.isEmpty()) { say(s, "&cUsage: mini <player> <type> [mobId] (type with minibosses)"); return; }
                String id = a.length > 3 ? a[3] : t.minibosses.get(ThreadLocalRandom.current().nextInt(t.minibosses.size())).mob;
                var m = mod.quests.spawnMiniboss(p, id, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)));
                say(s, m == null ? "&cCould not spawn " + id : "&aSpawned " + id);
            }
            case "reset" -> {
                if (t == null) {
                    BossFight f = mod.quests.fights.get(p.getUniqueId());
                    if (f != null) mod.quests.endFight(f);
                    d.xp.clear(); d.kills.clear(); d.announced.clear(); d.rngSelected.clear(); d.rngXp.clear();
                    d.questType = null; d.questState = null; d.questProgress = 0; d.questTier = 0;
                    d.autoSlayer.clear(); d.mixins.clear(); d.hotlineClaimed = false;
                    d.questsStarted = d.questsCompleted = d.questsFailed = 0; d.coinsSpent = 0; d.motesSpent = 0; d.cocoonKills = 0;
                    d.questCurrency = null; d.questPaid = 0;
                    d.miniKills.clear(); d.dropLog.clear(); d.bestTime.clear();
                } else {
                    d.xp.remove(t.id); d.announced.remove(t.id); d.rngSelected.remove(t.id); d.rngXp.remove(t.id);
                    d.kills.keySet().removeIf(k -> k.startsWith(t.id + ":"));
                }
                Sky.stats().invalidate(p);
                say(s, "&aReset " + (t == null ? "all slayer data" : t.id) + " for " + p.getName());
            }
            case "menu" -> {
                if (t != null) mod.menus.openType(p, t); else mod.menus.openMain(p);
                say(s, "&aOpened");
            }
            case "cocoon" -> {
                // cocoon <player> <type> <tier>  (boss)  |  cocoon <player> <type> mini  - The Primordial, forced
                if (t == null || a.length < 4) { say(s, "&cUsage: cocoon <player> <type> <tier|mini>"); return; }
                org.bukkit.Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
                if (a[3].equalsIgnoreCase("mini")) {
                    if (t.minibosses.isEmpty()) { say(s, "&c" + t.id + " has no minibosses"); return; }
                    String id = t.minibosses.get(0).mob;
                    var def = Sky.mobs().def(id);
                    mod.cocoon.start(p, id, at, t, 0, false, def == null ? 0 : def.health);
                    say(s, "&aCocooned " + id + " (hatches in " + SlayerDials.COCOON_HATCH_SECONDS.get() + "s)");
                } else {
                    SlayerCatalog.Tier tier = t.tier(num(a[3], 1));
                    if (tier == null) { say(s, "&cNo tier " + a[3]); return; }
                    mod.cocoon.start(p, tier.mob, at, t, tier.tier, true, Quests.bossHealth(t, tier));
                    say(s, "&aCocooned " + tier.mob + "; kill it for a loot-only re-roll");
                }
                say(s, "&7wearing The Primordial: " + Cocoon.wearing(p) + ", chance now "
                        + Text.commas(100 * SlayerMath.cocoonChance(Cocoon.wearing(p), SlayerDials.PRIMORDIAL_CHANCE.get(), Cocoon.attributeChance(p), false)) + "%");
            }
            case "mixin" -> {
                // mixin <player> <MIXIN_ID> [hours]  (0 hours removes) - drink without the item/cookie checks
                if (a.length < 3) { say(s, "&cUsage: mixin <player> <MIXIN_ID> [hours]"); return; }
                String id = a[2].toUpperCase(java.util.Locale.ROOT);
                if (!SlayerItems.MIXINS.containsKey(id)) { say(s, "&cUnknown mixin " + id + " " + SlayerItems.MIXINS.keySet()); return; }
                double h = a.length > 3 ? dnum(a[3], 36) : 36;
                if (h <= 0) d.mixins.remove(id); else d.mixins.put(id, System.currentTimeMillis() + (long) (h * 3_600_000L));
                Sky.stats().invalidate(p);
                say(s, "&a" + id + (h <= 0 ? " removed" : " active for " + h + "h") + "; requirement slayer:mixin:" + id + " = "
                        + Sky.requirements().check(p, "slayer:mixin:" + id));
            }
            default -> say(s, "&cUnknown sub. /sb slayer help");
        }
    }

    private static void info(SlayerModule mod, CommandSender s, String[] a) {
        if (a.length > 1) {
            Player p = target(s, a[1]);
            if (p == null) return;
            SlayerData d = mod.quests.data(p);
            say(s, "&5Slayer data of " + p.getName() + ":");
            for (SlayerCatalog.Type t : mod.catalog.types) {
                StringBuilder k = new StringBuilder();
                for (SlayerCatalog.Tier tier : t.tiers) k.append(" T").append(tier.tier).append("=").append(d.kills(t.id, tier.tier));
                say(s, " &" + t.color + t.id + "&7: lvl &e" + mod.level(p, t) + " &7xp &d" + Text.commasInt(d.xp(t.id)) + " &7kills" + k
                        + " &7rng=" + d.rngSelected.get(t.id) + "/" + Text.commasInt(d.rngXp.getOrDefault(t.id, 0.0)));
            }
            say(s, " &7quest: &f" + d.questType + " T" + d.questTier + " " + d.questState + " " + Text.commasInt(d.questProgress)
                    + " &7fight: " + (mod.quests.fights.containsKey(p.getUniqueId()) ? "&aLIVE" : "&8none"));
            say(s, " &7started/completed/failed: " + d.questsStarted + "/" + d.questsCompleted + "/" + d.questsFailed
                    + " &7paid: " + Text.commasInt(d.questPaid) + " " + (d.questCurrency == null ? "" : d.questCurrency)
                    + " &7spent coins/motes: " + Text.commasInt(d.coinsSpent) + "/" + Text.commasInt(d.motesSpent));
            say(s, " &7Primordial: " + Cocoon.wearing(p) + " (+" + Text.commas(Cocoon.attributeChance(p)) + "% attribute), cocoon kills "
                    + d.cocoonKills + " &7| Hunting: discount " + Text.commas(Quests.attribute(p, "value:slayer_discount"))
                    + "%, progress " + Text.commas(Quests.attribute(p, "value:slayer_progress")) + "% &7| mixins " + d.mixins.keySet()
                    + " &7| Vivid Darkness " + (mod.gear.vivid(p) ? "&aon" : "&8off"));
            say(s, " &7Wisp out: " + BlazeFight.wispOut(p) + " &7| Coldfused pillar bonus "
                    + (mod.gear.coldfusedActive(p, System.currentTimeMillis()) ? "&aon" : "&8off")
                    + " &7| Webbed Cells " + Text.commas(SpiderFight.webbedCells(p)) + "% (T5 venom Vitality -"
                    + Text.commas(SlayerMath.venomVitality(SlayerMath.venom(5)[0], SpiderFight.webbedCells(p))) + "%)");
            return;
        }
        say(s, "&5Slayer module: &7" + mod.catalog.types.size() + " types, items " + mod.itemsLoaded + " (+" + mod.fallbackLoaded
                + " fallback, " + mod.fallbackSkipped + " owned elsewhere), mobs " + mod.mobsLoaded + ", recipes " + mod.recipesLoaded
                + ", live fights " + mod.quests.fights.size() + ", cocoons " + mod.cocoon.cocoons + " (" + mod.cocoon.live() + " hatched alive)"
                + ", fee currency for the Bloodfiend: " + (Quests.paysMotes(mod.catalog.type("vampire")) ? "&dMotes" : "&6coins")
                + "&7, hunting " + (Sky.has(com.skyclone.api.feature.Features.Hunting.class) ? "&aloaded" : "&8absent (shards off)"));
        if (!mod.lateSkipped.isEmpty()) say(s, " &7recipes waiting for another module: &e" + mod.lateSkipped);
        for (SlayerCatalog.Type t : mod.catalog.types) {
            int missing = 0;
            for (SlayerCatalog.LootEntry e : t.loot) if (!SlayerMenus.shown(e)) missing++;
            say(s, " &" + t.color + t.id + "&7: " + t.tiers.size() + " tiers, " + t.loot.size() + " drops (" + missing + " not registered yet), "
                    + t.minibosses.size() + " minibosses, tagged mobs: " + (mod.quests.anyTagged(t.id) ? "&ayes" : "&eno (entity fallback)"));
        }
    }

    /** Monte-Carlo the weighted roll and print observed vs expected. */
    private static void loot(SlayerModule mod, CommandSender s, String[] a) {
        if (a.length < 4) { say(s, "&cUsage: loot <type> <tier> <level> [rolls] [magicFind]"); return; }
        SlayerCatalog.Type t = mod.catalog.type(SlayerModule.alias(a[1]));
        if (t == null) { say(s, "&cUnknown type"); return; }
        int tier = num(a[2], 1), lvl = num(a[3], 9), rolls = Math.max(1, Math.min(2_000_000, a.length > 4 ? num(a[4], 100000) : 100000));
        double mf = a.length > 5 ? dnum(a[5], 0) : 0;
        Map<String, Double> ch = SlayerMath.chances(t, tier, lvl, mf, 0, null, 1);
        Map<String, Integer> seen = new LinkedHashMap<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < rolls; i++) seen.merge(SlayerMath.pick(ch, r.nextDouble()), 1, Integer::sum);
        say(s, "&5" + t.name + " T" + tier + " at level " + lvl + ", MF " + mf + ", " + rolls + " rolls:");
        for (Map.Entry<String, Double> e : ch.entrySet()) {
            String name = e.getKey().isEmpty() ? "(token only)" : e.getKey();
            double obs = seen.getOrDefault(e.getKey(), 0) * 100.0 / rolls;
            say(s, String.format(java.util.Locale.US, " &f%-34s &7expected &a%.4f%% &7observed &e%.4f%%", name, e.getValue() * 100, obs));
        }
        // own-pool drops (dyes, Paragon) and the lootshare-only ones are not in the pick above
        for (SlayerCatalog.LootEntry e : t.loot) {
            double c = SlayerMath.independentChance(e, tier, lvl, mf);
            if (c > 0) say(s, String.format(java.util.Locale.US, " &f%-34s &7own roll &a%.5f%%", e.item, c * 100));
            if (e.lootshareOnly && e.dropsAt(tier)) say(s, " &f" + e.item + " &6lootshare only");
        }
    }
}
