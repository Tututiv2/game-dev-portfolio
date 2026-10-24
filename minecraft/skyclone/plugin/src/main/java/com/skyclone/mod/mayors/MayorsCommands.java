package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Player commands and {@code /sb mayors ...} admin tools (every one takes a player name, so RCON can drive it). */
final class MayorsCommands {

    private MayorsCommands() {}

    static void register(MayorsModule mod) {
        Sky.commands().register(SkyCommand.of("election", "Open the Election Booth: vote for next year's mayor")
                .alias("vote", "electionbooth").category("Events").playerOnly().run((s, a) -> ElectionMenus.open((Player) s)));
        Sky.commands().register(SkyCommand.of("mayor", "See the current mayor, minister and every active perk")
                .alias("mayors", "perks").category("Events").playerOnly().run((s, a) -> ElectionMenus.openMayor((Player) s)));
        Sky.commands().register(SkyCommand.of("festivals", "Festivals & Events: every event the mayors run")
                .alias("eventboard", "mayorevents").category("Events").playerOnly().run((s, a) -> EventBoard.open((Player) s)));
        Sky.commands().register(SkyCommand.of("spooky", "The Spooky Festival: candy shop and leaderboard")
                .alias("spookyfestival", "candyshop").category("Events").playerOnly().run((s, a) -> mod.spooky().open((Player) s)));
        Sky.commands().register(SkyCommand.of("newyear", "The New Year Celebration: claim your cake")
                .alias("newyearcake", "baker").category("Events").playerOnly().run((s, a) -> mod.newYear().open((Player) s)));
        Sky.commands().register(SkyCommand.of("zoo", "The Traveling Zoo: this visit's pets")
                .alias("travelingzoo").category("Events").playerOnly().run((s, a) -> mod.zoo().open((Player) s)));
        Sky.commands().register(SkyCommand.of("darkauction", "Join the Dark Auction (every hour at :55)")
                .alias("da").category("Events").playerOnly().run((s, a) -> mod.darkAuction().join((Player) s, false)));
        Sky.commands().register(SkyCommand.of("mythology", "The Mythological Ritual: griffins, spades and burrows")
                .alias("ritual", "burrows").category("Events").playerOnly().run((s, a) -> mod.myth().open((Player) s)));
        Sky.commands().register(SkyCommand.of("winter", "The Winter Workshop and the Season of Gerald")
                .alias("workshop", "savethejerrys", "jerrysworkshop").category("Events").playerOnly().run((s, a) -> mod.winter().open((Player) s)));
        Sky.commands().register(SkyCommand.of("stonks", "The Stonks Auction (while the Economist governs)")
                .alias("stockexchange").category("Events").playerOnly().run((s, a) -> mod.stonks().open((Player) s)));

        Sky.commands().register(SkyCommand.of("carnival", "The Carnival: daily tickets, the three booths, goals, perks and the souvenir shop (while the Fox governs)")
                .alias("fruitdigging", "carnivaltickets", "zombieshootout", "catchafish").usage("[goals|perks|shop|stop]").category("Events").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    String op = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
                    switch (op) {
                        case "goals" -> mod.carnival().openGoals(p);
                        case "perks" -> mod.carnival().perks().open(p);
                        case "shop" -> mod.carnival().openShop(p);
                        case "stop", "leave" -> mod.carnival().games().stop(p, "You stopped playing.");
                        default -> mod.carnival().openLeader(p);
                    }
                })
                .tab((s, a) -> a.length <= 1 ? List.of("goals", "perks", "shop", "stop") : List.of()));

        Sky.commands().register(SkyCommand.of("eventalerts", "Calendar chat: important events only, everything, or off")
                .alias("calendaralerts", "calalerts", "eventnotify").usage("[important|all|off]").category("Events").playerOnly()
                .run((s, a) -> mod.alerts().command((Player) s, a))
                .tab((s, a) -> a.length <= 1 ? List.of("important", "all", "off") : List.of()));

        Sky.commands().admin("mayors", "<status|perks|set|perk|election|vote|event|da|spooky|cake|zoo|myth|siege|gift|gerald|stonks|carnival|claims|npcs|alerts> ...",
                "Mayors, elections and festivals: test tools", (s, a) -> admin(mod, s, a), (s, a) -> complete(mod, a));
    }

    private static final List<String> SUBS = List.of("status", "perks", "set", "perk", "election", "vote", "event", "da", "spooky", "cake",
            "zoo", "myth", "siege", "gift", "gerald", "stonks", "carnival", "claims", "npcs", "alerts");

    private static List<String> complete(MayorsModule mod, String[] a) {
        if (a.length <= 1) return SUBS;
        List<String> ids = new ArrayList<>();
        switch (a[0]) {
            case "set" -> mod.roster().mayors.forEach(m -> ids.add(m.id));
            case "perk" -> { if (a.length == 2) ids.addAll(mod.roster().perks().keySet()); else ids.addAll(List.of("on", "off", "clear")); }
            case "election" -> ids.addAll(List.of("open", "reroll", "resolve"));
            case "event" -> { if (a.length == 2) ids.addAll(List.of(Cal.SPOOKY, Cal.NEW_YEAR, Cal.ZOO_SUMMER, Cal.ZOO_WINTER, Cal.SEASON_OF_JERRY, Cal.WORKSHOP, Cal.DARK_AUCTION, Cal.ELECTION)); else ids.addAll(List.of("on", "off")); }
            case "da" -> ids.addAll(List.of("start", "skip"));
            case "siege" -> ids.addAll(List.of("start", "skip", "stop"));
            case "myth" -> ids.addAll(List.of("dig", "spawn", "tier"));
            case "spooky" -> ids.addAll(List.of("points", "end", "start"));
            case "carnival" -> {
                if (a.length == 2) ids.addAll(List.of("status", "tokens", "tickets", "reset", "play", "stop", "perk", "goals"));
                else if (a.length == 4 && "play".equalsIgnoreCase(a[1])) ids.addAll(List.of("fruit", "shoot", "fish"));
                else if (a.length == 4 && "perk".equalsIgnoreCase(a[1])) mod.data().carnival.perks.forEach(pk -> ids.add(pk.id));
                else Bukkit.getOnlinePlayers().forEach(p -> ids.add(p.getName()));
            }
            case "alerts" -> { if (a.length == 2) Bukkit.getOnlinePlayers().forEach(p -> ids.add(p.getName())); else ids.addAll(List.of("important", "all", "off")); }
            default -> Bukkit.getOnlinePlayers().forEach(p -> ids.add(p.getName()));
        }
        return ids;
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String name) {
        Player p = name == null ? null : Bukkit.getPlayerExact(name);
        if (p == null) say(s, "&cNo online player '" + name + "'.");
        return p;
    }

    private static void admin(MayorsModule mod, CommandSender s, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        ElectionService es = mod.election();
        switch (sub) {
            case "status" -> es.status().forEach(l -> say(s, l));
            case "perks" -> {
                for (MayorRoster.Mayor m : mod.roster().mayors) {
                    StringBuilder sb = new StringBuilder(m.display() + " &8(" + m.id + ")&7: ");
                    for (MayorRoster.Perk p : m.perks) sb.append(es.perkActive(p.id) ? "&a" : "&7").append(p.id).append("=").append(Text.commas(es.baseValue(p.id))).append("[").append(p.owner).append("] ");
                    say(s, sb.toString());
                }
            }
            case "set" -> {
                if (a.length < 2) { say(s, "&cUsage: /sb mayors set <mayor> [minister]"); return; }
                String err = es.adminSet(a[1], a.length > 2 ? a[2] : null);
                say(s, err == null ? "&aMayor set. Active perks: " + es.activePerks() : "&c" + err);
            }
            case "perk" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb mayors perk <id> on|off|clear"); return; }
                if (mod.roster().canonical(a[1]) == null) { say(s, "&cUnknown perk " + a[1]); return; }
                es.adminForce(a[1], a[2].equalsIgnoreCase("clear") ? null : a[2].equalsIgnoreCase("on"));
                say(s, "&a" + mod.roster().canonical(a[1]) + " is now " + (es.perkActive(a[1]) ? "ON" : "OFF"));
            }
            case "election" -> {
                String op = a.length > 1 ? a[1] : "";
                String err = switch (op) {
                    case "open" -> es.adminReroll(true);
                    case "reroll" -> es.adminReroll(false);
                    case "resolve" -> es.adminResolve();
                    default -> "usage: election open|reroll|resolve";
                };
                say(s, err == null ? "&aDone." : "&c" + err);
                es.status().forEach(l -> say(s, l));
            }
            case "vote" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb mayors vote <player> <mayor>"); return; }
                Player p = player(s, a[1]);
                if (p != null) say(s, es.vote(p, a[2].toLowerCase(Locale.ROOT)) ? "&aVote cast." : "&cVote refused (see the player's chat).");
            }
            case "event" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb mayors event <calendarId> on|off"); return; }
                try { Sky.calendar().force(a[1], a[2].equalsIgnoreCase("on")); say(s, "&a" + a[1] + " forced " + a[2]); }
                catch (Exception e) { say(s, "&c" + e.getMessage()); }
            }
            case "alerts" -> {
                // /sb mayors alerts <player> [important|all|off]: set the scope, then show what that player would hear
                if (a.length < 2) { say(s, "&cUsage: /sb mayors alerts <player> [important|all|off]"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                if (!Sky.profiles().isReady(p)) { say(s, "&c" + p.getName() + "'s profile is not ready."); return; }
                if (a.length > 2) {
                    AlertPolicy.Scope sc = AlertPolicy.Scope.parseStrict(a[2]);
                    if (sc == null) { say(s, "&cScope must be important, all or off."); return; }
                    EventAlerts.setScope(p, sc);
                }
                EventAlerts al = mod.alerts();
                say(s, "&e" + p.getName() + ": " + EventAlerts.scope(p).label + " &7(announcer: " + (al.running() ? "&amayors" : "&emenus") + "&7, purse "
                        + Text.commasInt(Sky.economy().purse(p)) + ", lead " + EventAlerts.leadMillis(p) / 60_000 + "m)");
                AlertPolicy.Rules r = al.rules();
                AlertPolicy.Prefs pr = al.prefs(p);
                for (var ev : Sky.calendar().events()) {
                    if (ev == null || ev.id == null) continue;
                    boolean gate = al.gateOk(ev.id, r);
                    say(s, " &7" + ev.id + (EventAlerts.forcedOnly(ev) ? " &8(forced-only)" : "") + ": remind="
                            + AlertPolicy.decide(ev.id, AlertPolicy.Kind.REMIND, gate, pr, r) + " start="
                            + AlertPolicy.decide(ev.id, AlertPolicy.Kind.START, gate, pr, r) + (gate ? "" : " &8(perk gate closed)"));
                }
            }
            case "da" -> {
                String op = a.length > 1 ? a[1] : "start";
                String err = op.equals("skip") ? mod.darkAuction().adminSkip() : mod.darkAuction().start();
                say(s, err == null ? "&aDark Auction: " + op : "&c" + err);
            }
            case "spooky" -> {
                String op = a.length > 1 ? a[1] : "";
                if (op.equals("start")) { Sky.calendar().force(Cal.SPOOKY, true); say(s, "&aSpooky Festival forced on."); }
                else if (op.equals("end")) { Sky.calendar().force(Cal.SPOOKY, false); Sky.calendar().force(Cal.SPOOKY_EXTRA, false); mod.spooky().tick(); say(s, "&aSpooky Festival ended and paid out."); }
                else if (op.equals("points") && a.length > 3) {
                    Player p = player(s, a[2]);
                    if (p == null) return;
                    if (mod.spooky().running() == null) { say(s, "&cNo festival running (/sb mayors spooky start)."); return; }
                    mod.spooky().adminPoints(p, Long.parseLong(a[3]));
                    say(s, "&aAdded points. " + p.getName() + " is #" + mod.spooky().rankOf(p.getUniqueId()));
                } else say(s, "&cUsage: /sb mayors spooky start|end|points <player> <n>");
            }
            case "cake" -> {
                Player p = player(s, a.length > 1 ? a[1] : null);
                if (p == null) return;
                int year = a.length > 2 ? Integer.parseInt(a[2]) : Sky.calendar().now().year();
                NewYear.giveCake(p, year);
                say(s, "&aGave a Year " + year + " cake to " + p.getName());
            }
            case "carnival" -> {
                // carnival status | tokens <player> <n> | tickets <player> [n] | reset <player> | play <player> fruit|shoot|fish
                // | stop <player> | perk <player> <perkId> <tier> | goals <player> [clear]   (open it with: perk foxy.chivalrous_carnival on)
                String op = a.length > 1 ? a[1] : "";
                if ("status".equals(op) || a.length < 3) {
                    say(s, "&eCarnival active: " + mod.carnival().active() + ", grounds built: " + Sky.worlds().isBuilt(CarnivalGroundsGenerator.ID)
                            + ", games in progress: " + mod.carnival().games().playing());
                    mod.carnival().games().describe().forEach(l -> say(s, "&7 " + l));
                    if (a.length < 3 && !"status".equals(op)) say(s, "&7Usage: carnival status|tokens|tickets|reset|play|stop|perk|goals <player> ...");
                    return;
                }
                Player p = player(s, a.length > 2 ? a[2] : null);
                PlayerData pd = p == null ? null : mod.data(p);
                if (pd == null) { say(s, "&eCarnival active: " + mod.carnival().active() + ". Usage: carnival tokens|tickets|reset <player> [n]"); return; }
                switch (op) {
                    case "tokens" -> { pd.carnivalTokens = Math.max(0, a.length > 3 ? Long.parseLong(a[3]) : 1000); say(s, "&a" + p.getName() + " now has " + pd.carnivalTokens + " Carnival Tokens"); }
                    case "tickets" -> { int n = a.length > 3 ? Integer.parseInt(a[3]) : 25; Goods.give(p, "CARNIVAL_TICKET", n, com.skyclone.api.loot.LootSource.EVENT); say(s, "&aGave " + n + " Carnival Tickets to " + p.getName()); }
                    case "reset" -> { pd.carnivalTicketDay = -1; say(s, "&a" + p.getName() + " can claim today's tickets again"); }
                    case "play" -> {
                        CarnivalRules.Game g = CarnivalRules.Game.of(a.length > 3 ? a[3] : "");
                        if (g == null) { say(s, "&cUsage: carnival play <player> fruit|shoot|fish"); return; }
                        if (!CarnivalGroundsGenerator.ID.equals(Sky.worlds().islandOf(p.getWorld()))) Sky.worlds().warp(p, "carnival");
                        say(s, mod.carnival().games().start(p, g) ? "&aStarted " + g.title + " for " + p.getName() : "&cCould not start (see their chat; they may need to be on the Grounds first)");
                    }
                    case "stop" -> { mod.carnival().games().stop(p, "An admin ended the game."); say(s, "&aStopped " + p.getName() + "'s game (if any)"); }
                    case "perk" -> {
                        EventData.Perk pk = a.length > 3 ? mod.data().carnival.perk(a[3]) : null;
                        if (pk == null) { say(s, "&cUnknown perk. Ids: " + mod.data().carnival.perks.stream().map(x -> x.id).toList()); return; }
                        int t = Math.max(0, Math.min(pk.costs.size(), a.length > 4 ? Integer.parseInt(a[4]) : pk.costs.size()));
                        pd.carnivalPerks.put(pk.id, t);
                        Sky.stats().invalidate(p);
                        say(s, "&a" + p.getName() + " now has " + pk.name + " tier " + t);
                    }
                    case "goals" -> {
                        if (a.length > 3 && "clear".equalsIgnoreCase(a[3])) { pd.carnivalGoalsDone.clear(); pd.carnivalPlays.clear(); say(s, "&aCleared " + p.getName() + "'s Carnival goals"); }
                        else say(s, "&e" + p.getName() + ": goals done " + pd.carnivalGoalsDone + ", plays " + pd.carnivalPlays + ", best " + pd.carnivalBestScores + ", perks " + pd.carnivalPerks);
                    }
                    default -> say(s, "&cUsage: carnival status|tokens|tickets|reset|play|stop|perk|goals <player> ...");
                }
            }
            case "zoo" -> {
                int v = TravelingZoo.visitIndex(Sky.calendar().now());
                say(s, "&eZoo visit " + v + " (active " + mod.zoo().active() + "): &f" + mod.zoo().stock(v));
            }
            case "myth" -> {
                String op = a.length > 1 ? a[1] : "";
                Player p = player(s, a.length > 2 ? a[2] : null);
                if (p == null) return;
                switch (op) {
                    case "tier" -> say(s, "&eGriffin variant of " + p.getName() + ": " + mod.myth().tier(p) + " (0 = no Griffin), Tracking "
                            + mod.myth().tracking(p) + ", griffin out " + Mythology.griffinOut(p));
                    case "dig" -> say(s, mod.myth().echo(p) ? "&aEcho used." : "&7Echo did not start a cooldown (dug, or refused: see their chat).");
                    case "spawn" -> {
                        String kind = a.length > 3 ? a[3] : "hunter";
                        int tier = a.length > 4 ? Integer.parseInt(a[4]) : 1;
                        mod.myth().spawnCreature(p, kind, tier, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4)));
                        say(s, "&aSpawned " + Mythology.mobId(kind, tier));
                    }
                    default -> say(s, "&cUsage: /sb mayors myth tier|dig|spawn <player> [kind] [variant 1-6]  (kinds: hunter lynx nymph bull harpy gaia minotaur champion sphinx inquisitor manticore minos)");
                }
            }
            case "siege" -> {
                String op = a.length > 1 ? a[1] : "start";
                switch (op) {
                    case "start" -> { String err = mod.winter().startSiege(); say(s, err == null ? "&aSiege started (waits for a player on the island)." : "&c" + err); }
                    case "skip" -> { mod.winter().skip(); say(s, "&aSkipped to the next phase."); }
                    case "stop" -> { mod.winter().stopSiege("An admin called off the siege."); say(s, "&aSiege stopped."); }
                    default -> say(s, "&cUsage: /sb mayors siege start|skip|stop");
                }
                say(s, "&7Phase: " + mod.winter().phase() + ", round " + mod.winter().round());
            }
            case "gift" -> {
                Player p = player(s, a.length > 1 ? a[1] : null);
                if (p == null) return;
                String color = a.length > 2 ? a[2].toUpperCase(Locale.ROOT) : "WHITE";
                int n = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                Sky.loot().give(p, color + "_GIFT", n, LootSource.ADMIN);
                say(s, "&aGave " + n + " " + color + "_GIFT");
            }
            case "gerald" -> {
                Player p = player(s, a.length > 1 ? a[1] : null);
                if (p == null) return;
                new HiddenGeralds(mod).spawn(p, p.getLocation());
                say(s, "&aRevealed a hidden Gerald next to " + p.getName());
            }
            case "stonks" -> { String err = mod.stonks().settle(); say(s, err == null ? "&aStonks round settled." : "&c" + err); }
            case "claims" -> {
                Player p = player(s, a.length > 1 ? a[1] : null);
                if (p != null) say(s, "&e" + p.getName() + " has " + Claims.pending(p.getUniqueId()) + " queued reward(s).");
            }
            case "npcs" -> {
                say(s, "&eSeasonal vendors in the hub now: &f" + mod.npcs().inHub());
                for (Map.Entry<String, org.bukkit.Location> e : mod.npcs().hubSpots().entrySet())
                    say(s, " &7" + e.getKey() + " -> " + e.getValue().getBlockX() + "," + e.getValue().getBlockY() + "," + e.getValue().getBlockZ());
            }
            default -> say(s, "&cUnknown sub. " + SUBS);
        }
    }

    static void tell(Player p, String msg) { Msg.send(p, msg); }
}
