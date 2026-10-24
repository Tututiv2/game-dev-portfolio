package com.skyclone.mod.kuudra;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** {@code /kuudra ...} for players and {@code /sb kuudra ...} for admins (every admin action takes a player name). */
final class KCommands {

    private KCommands() {}

    static void register(KuudraModule mod) {
        Sky.commands().register(SkyCommand.of("kuudra", "The Magma Leviathan hunt: tiers, crew, perks, chests")
                .alias("leviathan", "hunt").category("Combat").playerOnly()
                .usage("[start <tier>|again|leave|perks|chests|crew <invite|accept|leave|kick|disband|list> [player]|stats|loot [tier]|intro]")
                .tab((s, a) -> tab(mod, a))
                .run((s, a) -> player(mod, (Player) s, a)));

        Sky.commands().admin("kuudra", "<start|phase|tokens|fuel|hp|end|list|complete|chest|reset|loot|open|arenas|intro|scene> ...",
                "Leviathan (Kuudra) hunt tools", (s, a) -> admin(mod, s, a),
                (s, a) -> a.length <= 1 ? List.of("start", "phase", "tokens", "fuel", "hp", "end", "list", "complete", "chest", "reset", "loot", "open", "arenas", "intro", "scene")
                        : a.length == 3 && (a[0].equals("start") || a[0].equals("complete") || a[0].equals("chest")) ? tierIds(mod)
                        : a.length == 3 && a[0].equals("phase") ? List.of("supplies", "build", "ballista", "stun", "dps", "lair", "win")
                        : a.length == 3 && a[0].equals("intro") ? List.of("show", "complete", "reset", "step", "kills")
                        : a.length == 3 && a[0].equals("scene") ? List.of("skip", "forget", "opening", "victory")
                        : List.of());
    }

    private static List<String> tierIds(KuudraModule mod) {
        List<String> out = new ArrayList<>();
        for (KCatalog.Tier t : mod.cat.tiers) out.add(t.id);
        return out;
    }

    private static List<String> tab(KuudraModule mod, String[] a) {
        if (a.length <= 1) return List.of("start", "again", "leave", "perks", "chests", "crew", "stats", "loot", "intro", "salvage");
        if (a.length == 2 && (a[0].equals("start") || a[0].equals("loot"))) return tierIds(mod);
        if (a.length == 2 && a[0].equals("crew")) return List.of("invite", "accept", "leave", "kick", "disband", "list");
        if (a.length == 3 && a[0].equals("crew")) { List<String> n = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName()); return n; }
        return List.of();
    }

    private static void player(KuudraModule mod, Player p, String[] a) {
        if (!Sky.profiles().isReady(p)) return;
        if (a.length == 0) { mod.menus.main(p); return; }
        switch (a[0].toLowerCase()) {
            case "start" -> {
                KCatalog.Tier t = a.length > 1 ? mod.cat.tier(a[1]) : null;
                if (t == null) { p.sendMessage(Text.c("&cUsage: /kuudra start <basic|hot|burning|fiery|infernal>")); return; }
                String err = mod.runs.start(p, t, false);
                if (err != null) p.sendMessage(Text.c("&c" + err));
            }
            case "again", "requeue" -> {
                String err = mod.runs.again(p);
                if (err != null) p.sendMessage(Text.c("&c" + err));
            }
            case "leave" -> { Run r = mod.runs.of(p); if (r == null) p.sendMessage(Text.c("&cYou are not on a hunt.")); else r.leave(p, true); }
            case "perks" -> mod.menus.perks(p);
            case "chests" -> mod.menus.chests(p, 0);
            case "stats" -> mod.menus.stats(p);
            case "intro", "quest" -> mod.menus.intro(p);
            case "salvage" -> mod.salvageMandibles(p, a.length > 1 && a[1].equalsIgnoreCase("all"));
            case "loot" -> { KCatalog.Tier t = a.length > 1 ? mod.cat.tier(a[1]) : null; if (t == null) mod.menus.lootTiers(p); else mod.menus.loot(p, t, 0); }
            case "crew" -> {
                String sub = a.length > 1 ? a[1].toLowerCase() : "list";
                String name = a.length > 2 ? a[2] : null;
                switch (sub) {
                    case "invite" -> { Player t = name == null ? null : Bukkit.getPlayerExact(name); if (t == null) p.sendMessage(Text.c("&cNo such player online.")); else mod.crew.invite(p, t); }
                    case "accept" -> mod.crew.accept(p, name);
                    case "leave" -> mod.crew.leave(p);
                    case "kick" -> { if (name != null) mod.crew.kick(p, name); }
                    case "disband" -> mod.crew.disband(p);
                    default -> mod.menus.crew(p);
                }
            }
            default -> mod.menus.main(p);
        }
    }

    private static void admin(KuudraModule mod, CommandSender s, String[] a) {
        if (a.length == 0) { help(s); return; }
        String sub = a[0].toLowerCase();
        switch (sub) {
            case "list" -> {
                say(s, "&c" + mod.runs.runs.size() + " hunt(s) running &8(arenas busy: " + mod.runs.busy + ")");
                for (Run r : mod.runs.runs.values())
                    say(s, "&e" + r.id + " &7" + r.tier.name + " &8| &7" + r.phaseName() + " &8| &7" + String.join(", ", r.names())
                            + " &8| &7beast " + (int) r.pct() + "% &8| &7wave " + r.wave + " &8| " + r.arena.key);
            }
            case "arenas" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("rebuild")) say(s, "&aReleased " + mod.runs.rebuildIdle() + " idle arena(s); the next hunt in each rebuilds it.");
                else say(s, "&7Arena versions: " + mod.runs.versions + " &8| busy " + mod.runs.busy);
            }
            case "loot" -> {
                KCatalog.Tier t = a.length > 1 ? mod.cat.tier(a[1]) : null;
                if (t == null) { say(s, "&cUsage: /sb kuudra loot <tier> [rolls]"); return; }
                int n = a.length > 2 ? Math.max(1, Math.min(100000, KuudraModule.parseInt(a[2], 1000))) : 1000;
                Map<String, Integer> seen = new TreeMap<>();
                Random rng = new Random(42);
                for (int i = 0; i < n; i++) {
                    KLoot.Rolled r = KLoot.roll(mod.cat.loot.get(t.id), rng, Sky.items()::exists, new KLoot.Mods(0, 0, KDials.PAID_ESSENCE_SCALE.get(), 0));
                    for (String id : r.paid().keySet()) seen.merge(id, 1, Integer::sum);
                }
                say(s, "&6" + n + " paid chests at " + t.name + ":");
                seen.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(40)
                        .forEach(e -> say(s, " &7" + e.getKey() + " &e" + String.format(java.util.Locale.ROOT, "%.2f%%", 100.0 * e.getValue() / n)));
            }
            default -> {
                if (a.length < 2) { help(s); return; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { say(s, "&cNo online player " + a[1]); return; }
                adminPlayer(mod, s, sub, p, a);
            }
        }
    }

    private static void adminPlayer(KuudraModule mod, CommandSender s, String sub, Player p, String[] a) {
        Run r = mod.runs.of(p);
        switch (sub) {
            case "start" -> {
                KCatalog.Tier t = a.length > 2 ? mod.cat.tier(a[2]) : mod.cat.tier(0);
                String err = mod.runs.start(p, t, true);
                say(s, err == null ? "&aStarting a " + t.name + " hunt for " + p.getName() + "'s crew." : "&c" + err);
            }
            case "phase" -> {
                if (r == null || a.length < 3) { say(s, "&cNot on a hunt / usage: phase <p> <supplies|build|ballista|stun|dps|lair|win>"); return; }
                r.skipTo(a[2].toLowerCase());
                say(s, "&aHunt " + r.id + " is now in " + r.phaseName() + ".");
            }
            case "tokens" -> {
                if (r == null) { say(s, "&cNot on a hunt."); return; }
                Run.PS ps = r.ps.get(p.getUniqueId());
                ps.tokens += a.length > 2 ? KuudraModule.parseInt(a[2], 100) : 100;
                say(s, "&a" + p.getName() + " now has " + ps.tokens + " tokens.");
            }
            case "fuel" -> {
                if (r == null) { say(s, "&cNot on a hunt."); return; }
                r.fuel = Math.max(0, a.length > 2 ? KuudraModule.parseInt(a[2], 4) : KDials.FUEL_PER_VOLLEY.get());
                say(s, "&aFuel set to " + r.fuel + ".");
            }
            case "hp" -> {
                if (r == null || r.boss == null) { say(s, "&cNo beast."); return; }
                double pct = a.length > 2 ? Double.parseDouble(a[2]) : 50;
                Sky.mobs().setHealth(r.boss, Math.max(1, r.bossMax * pct / 100.0));
                say(s, "&aBeast health set to " + pct + "%.");
            }
            case "end" -> {
                if (r == null) { say(s, "&cNot on a hunt."); return; }
                if (a.length > 2 && a[2].equalsIgnoreCase("fail")) r.fail("Ended by an admin."); else r.close();
                say(s, "&aEnded.");
            }
            case "complete" -> {
                KCatalog.Tier t = a.length > 2 ? mod.cat.tier(a[2]) : null;
                if (t == null) { say(s, "&cUsage: complete <p> <tier> [n]"); return; }
                int n = a.length > 3 ? KuudraModule.parseInt(a[3], 1) : 1;
                mod.data(p).completions.merge(t.id, n, Integer::sum);
                say(s, "&a" + p.getName() + " now has " + mod.data(p).completions(t.id) + " " + t.name + " clears.");
            }
            case "chest" -> {
                KCatalog.Tier t = a.length > 2 ? mod.cat.tier(a[2]) : null;
                if (t == null) { say(s, "&cUsage: chest <p> <tier>"); return; }
                KData.Chest c = mod.rollChest(p, t, "admin" + System.currentTimeMillis(), new Random().nextLong());
                mod.data(p).chests.add(c);
                say(s, "&aGave " + p.getName() + " a " + t.name + " chest: free " + c.free + " paid " + c.paid);
            }
            case "reset" -> {
                KData d = mod.data(p);
                d.completions.clear(); d.bestMillis.clear(); d.chests.clear(); d.tokensEarned = 0; d.huntsStarted = 0; d.deaths = 0; d.brood = 0;
                say(s, "&aReset " + p.getName() + "'s hunt record.");
            }
            case "intro" -> {
                KData d = mod.data(p);
                String what = a.length > 2 ? a[2].toLowerCase() : "show";
                switch (what) {
                    case "complete" -> { d.introStep = KIntro.DONE; d.introduced = true; }
                    case "reset" -> { d.introStep = KIntro.MEET; d.introKills = 0; d.introduced = false; d.introKeyGiven = false; }
                    case "step" -> d.introStep = Math.max(0, Math.min(KIntro.DONE, a.length > 3 ? KuudraModule.parseInt(a[3], 0) : 0));
                    case "kills" -> d.introKills = Math.max(0, a.length > 3 ? KuudraModule.parseInt(a[3], 0) : 0);
                    default -> { }
                }
                int now = what.equals("show") ? mod.intro(p) : mod.introRefresh(p);
                say(s, "&e" + p.getName() + " &7- " + KIntro.NAME + ": step &e" + now + "&7/" + KIntro.DONE
                        + " &8(stored " + d.introStep + ", kills " + d.introKills + ", key " + (d.introKeyGiven ? "given" : "not given") + ")"
                        + " &7" + (now >= KIntro.DONE ? "&acomplete" : KIntro.objective(now, d.introKills, KDials.INTRO_KILLS.get())));
            }
            case "scene" -> {
                String what = a.length > 2 ? a[2].toLowerCase() : "skip";
                switch (what) {
                    case "forget" -> { mod.data(p).scenesSeen.clear(); say(s, "&a" + p.getName() + " will see the full opening again."); }
                    case "skip" -> {
                        if (r == null || r.scene == null) { say(s, "&cNo cutscene playing."); return; }
                        r.endScene(true);
                        say(s, "&aSkipped.");
                    }
                    default -> {
                        KScript.Scene sc = KScript.byId(what);
                        if (sc == null || r == null) { say(s, "&cUsage: scene <p> <skip|forget|opening|victory> (opening/victory need a hunt)"); return; }
                        r.playScene(sc);
                        say(s, "&aPlaying " + what + " for " + p.getName() + "'s hunt.");
                    }
                }
            }
            case "open" -> {
                String what = a.length > 2 ? a[2].toLowerCase() : "main";
                switch (what) {
                    case "intro" -> mod.menus.intro(p);
                    case "perks" -> mod.menus.perks(p);
                    case "chests" -> mod.menus.chests(p, 0);
                    case "crew" -> mod.menus.crew(p);
                    case "stats" -> mod.menus.stats(p);
                    case "loot" -> mod.menus.lootTiers(p);
                    case "run" -> mod.menus.run(p);
                    default -> mod.menus.main(p);
                }
                say(s, "&aOpened " + what + " for " + p.getName() + ".");
            }
            default -> help(s);
        }
    }

    private static void help(CommandSender s) {
        say(s, "&c/sb kuudra &7start <p> [tier] &8| &7phase <p> <supplies|build|ballista|stun|dps|lair|win> &8| &7tokens <p> [n]");
        say(s, "&7 fuel <p> [n] &8| &7hp <p> <percent> &8| &7end <p> [fail] &8| &7list &8| &7complete <p> <tier> [n] &8| &7chest <p> <tier>");
        say(s, "&7 reset <p> &8| &7loot <tier> [rolls] &8| &7open <p> <main|perks|chests|crew|stats|loot|run|intro> &8| &7arenas [rebuild]");
        say(s, "&7 intro <p> [show|complete|reset|step <n>|kills <n>] &8| &7scene <p> <skip|forget|opening|victory>");
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }
}
