package com.skyclone.mod.farming;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sb farming ...}: everything the integrator needs to exercise farming over RCON. Every sub that acts on
 * a player takes the player's name, so it works without a client. Output is synchronous (RCON only shows that).
 */
final class FarmingAdmin {

    private FarmingAdmin() {}

    private static final String USAGE = "info|harvest|contest|medals|pelts|trapper|treasure|shepherd|tool|upgrades|golds|pb|host|regrow|perks|crest|orb|shiny|truffle|reset|open ...";

    static void register(FarmingModule m) {
        Sky.commands().admin("farming", USAGE, "Farming: contests, medals, pelts, tools, harvest simulation, regrowth",
                (s, a) -> run(m, s, a), (s, a) -> complete(a));
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) { say(s, "&cNo online player " + name); return null; }
        if (!Sky.profiles().isReady(p)) { say(s, "&c" + name + "'s profile is not ready yet"); return null; }
        return p;
    }

    private static void help(CommandSender s) {
        say(s, "&e/sb farming info <player> &7- fortune per crop, medals, pelts, contest state");
        say(s, "&e/sb farming harvest <player> <crop> [blocks] &7- simulate breaking grown crops (drops, XP, contest)");
        say(s, "&e/sb farming contest status|start [crop crop crop]|stop|score <player> <n>|claim <player>|ghosts <crop>");
        say(s, "&e/sb farming medals <player> <bronze|silver|gold> <add|set> <n>");
        say(s, "&e/sb farming pelts <player> <add|set> <n>");
        say(s, "&e/sb farming trapper <player> [animal] [rarity] | abandon <player> | areas");
        say(s, "&e/sb farming tool <player> counter|xp|almanac <n> &7- edit the held tool");
        say(s, "&e/sb farming upgrades <player> fortune|cap <n> | golds <player> <crop...>");
        say(s, "&e/sb farming pb <player> unlock|lock &7- Harvesta's Personal Bests perk");
        say(s, "&e/sb farming host <player> met|reset &7- mark the Harvest Fair host as talked to (contest sign-up)");
        say(s, "&e/sb farming treasure <player> give|clear|cooldown &7- Digger Nim's buried treasure");
        say(s, "&e/sb farming shepherd <player> start|reset &7- Shepherd Maela's shearing task");
        say(s, "&e/sb farming regrow [island|all] &7- finish every pending regrowth now");
        say(s, "&e/sb farming open <player> main|crops|tool|contest|medals|trapper|farmhand|treasure|shepherd|talbot|tony");
        say(s, "&e/sb farming perks <player> [crop] &7- what other modules' gear/pets add (crop fortune, Overbloom, pelts, garden tool)");
        say(s, "&e/sb farming crest <player> <n>|upgrade &7- Trapper Crest pelts / upgrade the crest in the inventory");
        say(s, "&e/sb farming orb [player] &7- Farmer Orb: finish crops near a player now (or show counts)");
        say(s, "&e/sb farming shiny <player> spawn|deliver|escape|cooldown|info &7- Shiny Pigs (spawn one at the player, finish it)");
        say(s, "&e/sb farming truffle <player> eat|eaten <n>|clear &7- Refined Dark Cacao Truffle (Cocoa Beans Fortune)");
        say(s, "&e/sb farming reset <player> &7- wipe that player's farming data");
    }

    /** What other modules' accessories, equipment, reforges and pets currently add for this player (wave 5). */
    private static void perks(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cUsage: /sb farming perks <player> [crop]"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        say(s, "&6Farming perks of " + p.getName() + " &7(indexed: " + m.perks.indexedAccessories() + " accessories, "
                + m.perks.indexedReforges() + " reforges, " + m.perks.indexedPets() + " pets; garden-gated tools: " + m.gardenGated + ")");
        List<Crop> crops = new ArrayList<>();
        if (a.length > 2) { Crop c = Crop.parse(a[2]); if (c != null) crops.add(c); }
        if (crops.isEmpty()) crops.addAll(Arrays.asList(Crop.values()));
        for (Crop c : crops) {
            List<Harvest.Part> parts = m.perks.cropParts(p, c);
            double pb = ToolTracker.playerBonus(m, p, c);
            if (parts.isEmpty() && pb == 0) continue;
            StringBuilder sb = new StringBuilder("&e" + c.display + "&7: ");
            for (Harvest.Part pt : parts) sb.append(pt.source()).append(" +").append(pt.value()).append("  ");
            sb.append("&8(garden tool carries +").append(Math.round(pb * 100) / 100.0).append(")");
            say(s, sb.toString());
        }
        for (Harvest.Part pt : m.perks.overbloomParts(p)) say(s, "&dOverbloom &7" + pt.source() + " +" + pt.value());
        say(s, "&7Overbloom total &d" + m.perks.overbloom(p) + "&7 -> rare crops x"
                + Math.round(PerkMath.overbloomMultiplier(m.perks.overbloom(p), m.dials.overbloomPercent.get()) * 1000) / 1000.0);
        say(s, "&7Pelts: plushie " + Perks.has(p, "JAKE_PLUSHIE") + ", belt " + Perks.wears(p, "PELT_BELT") + ", crest " + Perks.has(p, "TRAPPER_CREST")
                + " (" + m.data(p).crestPelts + "/" + m.dials.crestUpgradePelts.get() + "); cape " + Perks.wears(p, "ZORROS_CAPE")
                + "; orb " + Perks.has(p, "FARMER_ORB") + "; trapper call " + m.trapper.canCall(p));
        FarmData fd = m.data(p);
        say(s, "&7Pets: tool XP +" + Perks.toolXpPercent(p) + "% (Bright Mind), Shiny Pig coins +" + Perks.petAbility(p, "pig_hamfisted", "a")
                + "% (Ham Fist), Potato/tier " + Perks.petAbility(p, "pig_shining_stampede", "a") + " (Shine Rush); truffles eaten "
                + fd.trufflesEaten + ", buff " + (fd.truffleUntil > System.currentTimeMillis() ? (fd.truffleUntil - System.currentTimeMillis()) / 60000 + "m left" : "off"));
    }

    private static void run(FarmingModule m, CommandSender s, String[] a) {
        if (a.length == 0) { help(s); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "info" -> info(m, s, a);
                case "harvest" -> harvest(m, s, a);
                case "contest" -> contest(m, s, a);
                case "medals" -> medals(m, s, a);
                case "pelts" -> pelts(m, s, a);
                case "trapper" -> trapper(m, s, a);
                case "abandon" -> { Player p = a.length > 1 ? player(s, a[1]) : null; if (p != null) { m.trapper.abandon(p); say(s, "&aAbandoned."); } }
                case "tool" -> tool(m, s, a);
                case "upgrades" -> upgrades(m, s, a);
                case "golds" -> golds(m, s, a);
                case "regrow" -> regrow(m, s, a);
                case "perks" -> perks(m, s, a);
                case "crest" -> {
                    if (a.length < 3) { say(s, "&cUsage: /sb farming crest <player> <n>|upgrade"); return; }
                    Player p = player(s, a[1]);
                    if (p == null) return;
                    FarmData d = m.data(p);
                    if (a[2].equalsIgnoreCase("upgrade")) {
                        ItemStack crest = Trapper.upgradableCrest(p);
                        say(s, crest == null ? "&cNo un-upgraded Trapper Crest in " + p.getName() + "'s inventory"
                                : m.trapper.upgradeCrest(p, d, crest) ? "&aUpgraded." : "&cNeeds " + m.dials.crestUpgradePelts.get() + " crest pelts (has " + d.crestPelts + ")");
                    } else {
                        d.crestPelts = Math.max(0, Long.parseLong(a[2]));
                        d.crestReadyTold = false;
                        say(s, "&a" + p.getName() + "'s crest pelts = " + d.crestPelts);
                    }
                }
                case "shiny" -> shiny(m, s, a);
                case "truffle" -> truffle(m, s, a);
                case "orb" -> {
                    if (a.length > 1) {
                        Player p = player(s, a[1]);
                        if (p == null) return;
                        int n = m.regrowth.hasten(p.getLocation(), m.dials.orbRadius.get(), Math.max(1, m.dials.orbCrops.get()));
                        say(s, "&aFinished " + n + " crop(s) near " + p.getName() + " (has orb: " + Perks.has(p, "FARMER_ORB") + ")");
                    } else say(s, "&7Pending regrowth: &e" + m.regrowth.pendingCount() + "&7, finished early by Farmer Orbs: &e" + m.regrowth.hastenedCount());
                }
                case "pb" -> {
                    if (a.length < 3) { say(s, "&cUsage: /sb farming pb <player> unlock|lock"); return; }
                    Player p = player(s, a[1]);
                    if (p == null) return;
                    m.data(p).pbUnlocked = a[2].equalsIgnoreCase("unlock");
                    say(s, "&aPersonal Bests " + (m.data(p).pbUnlocked ? "unlocked" : "locked") + " for " + p.getName());
                }
                case "host" -> {
                    if (a.length < 3) { say(s, "&cUsage: /sb farming host <player> met|reset"); return; }
                    Player p = player(s, a[1]);
                    if (p == null) return;
                    m.data(p).metHost = a[2].equalsIgnoreCase("met");
                    say(s, "&a" + p.getName() + (m.data(p).metHost ? " has met the host." : " has not met the host."));
                }
                case "treasure" -> {
                    if (a.length < 3) { say(s, "&cUsage: /sb farming treasure <player> give|clear|cooldown"); return; }
                    Player p = player(s, a[1]);
                    if (p == null) return;
                    switch (a[2].toLowerCase(Locale.ROOT)) {
                        case "give" -> {
                            int[] spot = m.treasure.pickSpot();
                            if (spot == null) { say(s, "&cNo diggable spot found (is the desert built?)"); return; }
                            m.treasure.give(p, spot);
                            say(s, "&aTreasure for " + p.getName() + " at " + spot[0] + " " + spot[1] + " " + spot[2]);
                        }
                        case "clear" -> { m.treasure.clear(p); say(s, "&aCleared."); }
                        case "cooldown" -> { m.data(p).treasureCooldownUntil = 0; say(s, "&aCooldown reset."); }
                        default -> say(s, "&cgive|clear|cooldown");
                    }
                }
                case "shepherd" -> {
                    if (a.length < 3) { say(s, "&cUsage: /sb farming shepherd <player> start|reset"); return; }
                    Player p = player(s, a[1]);
                    if (p == null) return;
                    switch (a[2].toLowerCase(Locale.ROOT)) {
                        case "start" -> { m.shepherd.reset(p); m.shepherd.begin(p); say(s, "&aShearing task started for " + p.getName()); }
                        case "reset" -> { m.shepherd.reset(p); say(s, "&aShearing task and cooldown cleared."); }
                        default -> say(s, "&cstart|reset");
                    }
                }
                case "open" -> open(m, s, a);
                case "reset" -> {
                    Player p = a.length > 1 ? player(s, a[1]) : null;
                    if (p == null) return;
                    FarmData d = m.data(p);
                    FarmData fresh = new FarmData();
                    d.cropsBroken = fresh.cropsBroken; d.cropsCollected = fresh.cropsCollected; d.bronze = 0; d.silver = 0; d.gold = 0;
                    d.medalsEarned = fresh.medalsEarned; d.personalBest = fresh.personalBest; d.bestBracket = fresh.bestBracket;
                    d.uniqueGolds = fresh.uniqueGolds; d.contestsJoined = 0; d.contestIndex = Long.MIN_VALUE; d.contestCrop = null; d.contestScore = 0;
                    d.history = fresh.history; d.anitaFortune = 0; d.capSteps = 0; d.pelts = 0; d.peltsEarned = 0; d.quest = null;
                    d.trapperCooldownUntil = 0; d.trapsByRarity = fresh.trapsByRarity; d.metHost = false; d.pbUnlocked = false;
                    m.treasure.clear(p); d.treasureCooldownUntil = 0; d.treasuresFound = 0;
                    m.shepherd.reset(p); d.shearingTasksDone = 0; d.sheepSheared = 0;
                    Sky.stats().invalidate(p);
                    say(s, "&aReset farming data of " + p.getName() + " (the Farming cap is not lowered).");
                }
                default -> help(s);
            }
        } catch (NumberFormatException e) {
            say(s, "&cNot a number: " + e.getMessage());
        }
    }

    private static void info(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cUsage: /sb farming info <player>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        FarmData d = m.data(p);
        ItemStack held = p.getInventory().getItemInMainHand();
        say(s, "&e" + p.getName() + " &7- Farming Fortune stat &6" + Text.commas(Sky.stats().get(p, Stat.FARMING_FORTUNE))
                + "&7, held &f" + (Sky.items().idOf(held) == null ? held.getType() : Sky.items().idOf(held)));
        StringBuilder sb = new StringBuilder("&7Fortune: ");
        for (Crop c : Crop.values()) sb.append(c.name().toLowerCase(Locale.ROOT)).append('=').append(Text.commasInt(m.harvest.fortune(p, c, held, "red").total())).append(' ');
        say(s, sb.toString());
        say(s, "&7Medals &6" + d.gold + "g &f" + d.silver + "s &c" + d.bronze + "b&7, unique golds " + d.uniqueGolds + ", extra fortune tiers "
                + d.anitaFortune + ", cap steps " + d.capSteps + ", pelts " + d.pelts);
        say(s, "&7Contest: index " + (d.contestIndex == Long.MIN_VALUE ? "-" : d.contestIndex) + " crop " + d.contestCrop + " score " + d.contestScore
                + ", history " + d.history.size() + ", joined " + d.contestsJoined);
        say(s, "&7Broken: " + d.cropsBroken + "  Collected: " + d.cropsCollected);
        if (d.quest != null) say(s, "&7Quest: " + d.quest.rarity + " " + d.quest.animal + " @ " + d.quest.area + " (mob " + d.quest.mobUuid + ")");
        say(s, "&7Met host " + d.metHost + ", personal bests " + (d.pbUnlocked ? "unlocked" : "locked") + ", treasures " + d.treasuresFound
                + (d.treasure == null ? "" : ", hint at " + d.treasure.x + " " + d.treasure.y + " " + d.treasure.z));
    }

    private static void harvest(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&cUsage: /sb farming harvest <player> <crop> [blocks]"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        Crop c = Crop.parse(a[2]);
        if (c == null) { say(s, "&cUnknown crop " + a[2] + ". Crops: " + Arrays.toString(Crop.values())); return; }
        int blocks = a.length > 3 ? Math.max(1, Math.min(10_000, Integer.parseInt(a[3]))) : 1;
        double before = Sky.stats().get(p, Stat.FARMING_FORTUNE);
        int drops = m.harvest.harvest(p, c, blocks, p.getInventory().getItemInMainHand(), "red", p.getLocation());
        say(s, "&aSimulated " + blocks + " " + c.display + " block(s) for " + p.getName() + ": &e" + drops + " &adrops at "
                + Text.commasInt(m.harvest.fortune(p, c, p.getInventory().getItemInMainHand(), "red").total()) + " fortune (stat " + Text.commasInt(before) + ")"
                + (m.contests.active ? ", contest score now " + m.data(p).contestScore : ""));
    }

    private static void contest(FarmingModule m, CommandSender s, String[] a) {
        Contests ct = m.contests;
        String op = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
        switch (op) {
            case "status" -> {
                if (ct.active) say(s, "&aHarvest Fair #" + ct.index + " running" + (ct.forced ? " (forced)" : "") + ": " + ct.crops + ", "
                        + Text.duration(ct.millisLeft() / 1000) + " left");
                else say(s, "&7No fair running; next in " + Text.duration(ct.millisUntilNext() / 1000)
                        + (m.dials.contestEnabled.get() ? "" : " &c(schedule disabled by farming.contest.enabled)"));
                for (Crop c : ct.crops) say(s, "&7 " + c.display + ": real participants " + ct.participants(c));
            }
            case "start" -> {
                List<Crop> chosen = new ArrayList<>();
                for (int i = 2; i < a.length; i++) { Crop c = Crop.parse(a[i]); if (c != null && !chosen.contains(c)) chosen.add(c); }
                ct.forceStart(chosen.isEmpty() ? null : chosen);
                say(s, "&aStarted Harvest Fair #" + ct.index + " with " + ct.crops + " for " + m.dials.contestDuration.get() + " minutes.");
            }
            case "stop", "end" -> { boolean was = ct.active; ct.forceEnd(); say(s, was ? "&aEnded the fair and paid out." : "&7No fair was running."); }
            case "score" -> {
                if (a.length < 4) { say(s, "&cUsage: /sb farming contest score <player> <n>"); return; }
                Player p = player(s, a[2]);
                if (p == null) return;
                if (!ct.active) { say(s, "&cNo fair running."); return; }
                FarmData d = m.data(p);
                if (d.contestIndex != ct.index || d.contestCrop == null) {
                    d.contestIndex = ct.index; d.contestCrop = ct.crops.get(0).name(); d.contestsJoined++;
                }
                d.contestScore = Long.parseLong(a[3]);
                ct.onHarvest(p, Crop.parse(d.contestCrop), 0);
                int[] st = ct.standing(p);
                say(s, "&a" + p.getName() + " now has " + d.contestScore + " " + d.contestCrop
                        + (st == null ? "" : " (#" + st[0] + "/" + st[1] + ", " + ContestMath.bracket(st[0], st[1], ct.goated()) + ")"));
            }
            case "claim" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb farming contest claim <player>"); return; }
                Player p = player(s, a[2]);
                if (p != null) say(s, "&aPaid " + ct.claimAll(p) + " record(s).");
            }
            case "ghosts" -> {
                Crop c = a.length > 2 ? Crop.parse(a[2]) : Crop.WHEAT;
                if (c == null) { say(s, "&cUnknown crop"); return; }
                double[] g = ContestMath.ghostFinals(ct.active ? ct.index : 1, c, m.dials.contestGhosts.get(), m.dials.contestGhostMedian.get(), m.dials.contestGhostSigma.get());
                if (g.length == 0) { say(s, "&7No ghosts (dial farming.contest.ghosts = 0)"); return; }
                say(s, "&7" + c.display + " ghost finals: min " + Text.commasInt(g[0]) + ", median " + Text.commasInt(g[g.length / 2]) + ", max " + Text.commasInt(g[g.length - 1]));
                for (ContestMath.Bracket b : ContestMath.Bracket.values()) if (b != ContestMath.Bracket.NONE)
                    say(s, "&7  " + b.name() + " needs about " + Text.commasInt(ContestMath.scoreFor(b, g, false)));
            }
            default -> say(s, "&cUsage: /sb farming contest status|start [crops]|stop|score <p> <n>|claim <p>|ghosts <crop>");
        }
    }

    private static void medals(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 5) { say(s, "&cUsage: /sb farming medals <player> <bronze|silver|gold> <add|set> <n>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        FarmData d = m.data(p);
        int n = Integer.parseInt(a[4]);
        boolean set = a[3].equalsIgnoreCase("set");
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "bronze" -> d.bronze = Math.max(0, set ? n : d.bronze + n);
            case "silver" -> d.silver = Math.max(0, set ? n : d.silver + n);
            case "gold" -> d.gold = Math.max(0, set ? n : d.gold + n);
            default -> { say(s, "&cMedal must be bronze, silver or gold"); return; }
        }
        say(s, "&a" + p.getName() + " medals: " + d.gold + " gold, " + d.silver + " silver, " + d.bronze + " bronze");
    }

    private static void pelts(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 4) { say(s, "&cUsage: /sb farming pelts <player> <add|set> <n>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        FarmData d = m.data(p);
        long n = Long.parseLong(a[3]);
        d.pelts = Math.max(0, a[2].equalsIgnoreCase("set") ? n : d.pelts + n);
        say(s, "&a" + p.getName() + " has " + d.pelts + " pelts");
    }

    private static void trapper(FarmingModule m, CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("areas")) {
            for (var e : m.trapper.areas().entrySet()) say(s, "&7" + e.getKey() + " (" + e.getValue() + "): " + m.trapper.spots(e.getKey()).size() + " spots");
            return;
        }
        if (a.length < 2) { say(s, "&cUsage: /sb farming trapper <player> [animal] [rarity] | areas"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        String animal = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : null;
        String rarity = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : null;
        if (animal != null && !Arrays.asList(Trapper.ANIMALS).contains(animal)) { say(s, "&cAnimals: " + Arrays.toString(Trapper.ANIMALS)); return; }
        if (rarity != null && !Arrays.asList(Trapper.RARITIES).contains(rarity)) { say(s, "&cRarities: " + Arrays.toString(Trapper.RARITIES)); return; }
        FarmData d = m.data(p);
        if (d.quest != null) m.trapper.abandon(p);
        d.trapperCooldownUntil = 0;
        boolean ok = m.trapper.assign(p, animal, rarity);
        say(s, ok ? "&aGave " + p.getName() + " a quest: " + d.quest.rarity + " " + d.quest.animal + " near " + d.quest.areaName
                + (d.quest.mobUuid == null ? " &c(quarry not spawned: is the desert loaded?)" : "") : "&cCould not assign (desert not built?)");
    }

    private static void tool(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 4) { say(s, "&cUsage: /sb farming tool <player> counter|xp|almanac|overclock <n>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        if (m.tools.view(p, held) == null) { say(s, "&c" + p.getName() + " is not holding a farming tool"); return; }
        m.tools.flush(p);
        String key = switch (a[2].toLowerCase(Locale.ROOT)) { case "counter" -> ToolSpec.K_COUNTER; case "xp" -> ToolSpec.K_XP; case "almanac" -> ToolSpec.K_ALMANAC; case "overclock" -> ToolSpec.K_OVERCLOCK; default -> null; };
        if (key == null) { say(s, "&cField must be counter, xp, almanac or overclock"); return; }
        double v = Double.parseDouble(a[3]);
        Sky.items().update(held, d -> {
            if (key.equals(ToolSpec.K_XP)) d.extra.add(key, new JsonPrimitive(v));
            else d.extra.add(key, new JsonPrimitive((long) v));
        });
        Sky.stats().invalidate(p);
        ToolTracker.View view = m.tools.view(p, held);
        say(s, "&aTool updated: level " + view.level() + "/" + view.cap() + ", overclockers " + view.overclocks() + ", counter " + view.counter() + ", almanacs " + view.almanac());
    }

    private static void upgrades(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 4) { say(s, "&cUsage: /sb farming upgrades <player> fortune|cap <n>"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        FarmData d = m.data(p);
        int n = Integer.parseInt(a[3]);
        if (a[2].equalsIgnoreCase("fortune")) d.anitaFortune = Math.max(0, Math.min(m.tables.anitaFortune.size(), n));
        else if (a[2].equalsIgnoreCase("cap")) { d.capSteps = Math.max(0, Math.min(m.tables.farmingCap.size(), n)); m.applyCap(p); }
        else { say(s, "&cfortune or cap"); return; }
        Sky.stats().invalidate(p);
        say(s, "&aExtra fortune tiers " + d.anitaFortune + ", cap steps " + d.capSteps);
    }

    private static void golds(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&cUsage: /sb farming golds <player> <crop...>|clear"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        FarmData d = m.data(p);
        if (a[2].equalsIgnoreCase("clear")) d.uniqueGolds.clear();
        else for (int i = 2; i < a.length; i++) { Crop c = Crop.parse(a[i]); if (c != null) d.uniqueGolds.add(c.name()); }
        say(s, "&aUnique golds: " + d.uniqueGolds);
    }

    private static void regrow(FarmingModule m, CommandSender s, String[] a) {
        String which = a.length > 1 ? a[1] : "all";
        int n;
        if (which.equalsIgnoreCase("all")) n = m.regrowth.finishAll();
        else {
            World w = Sky.worlds().world(which);
            if (w == null) { say(s, "&cUnknown island " + which); return; }
            n = m.regrowth.finishWorld(w.getName());
        }
        say(s, "&aRegrew " + n + " block(s); " + m.regrowth.pendingCount() + " still pending, " + m.regrowth.appliedCount() + " restored since boot.");
    }

    private static void open(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&cUsage: /sb farming open <player> main|crops|tool|contest|medals|trapper|farmhand"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "main" -> m.menus.openMain(p);
            case "crops" -> m.menus.openCrops(p);
            case "tool" -> m.menus.openTool(p);
            case "contest" -> m.menus.openContest(p);
            case "medals" -> m.shops.openAnita(p);
            case "trapper" -> m.trapper.openQuest(p);
            case "farmhand" -> m.shops.openFarmhand(p);
            case "talbot" -> m.shops.openPeltShop(p, "talbot", "Surveyor Pruitt");
            case "tony" -> m.shops.openPeltShop(p, "tony", "Fur-trader Alder");
            case "treasure" -> m.treasure.open(p);
            case "shepherd" -> m.shepherd.open(p);
            case "shiny" -> m.shiny.open(p);
            default -> { say(s, "&cUnknown menu"); return; }
        }
        say(s, "&aOpened.");
    }

    /** Shiny Pigs (wave 5): spawn one next to a player, finish it either way, reset the cooldown, show the numbers. */
    private static void shiny(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&cUsage: /sb farming shiny <player> spawn|deliver|escape|cooldown|info"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                org.bukkit.Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
                String err = m.shiny.spawn(p, at);
                say(s, err == null ? "&aA Shiny Pig appeared next to " + p.getName() + "." : "&c" + err);
            }
            case "deliver" -> { String err = m.shiny.finish(p, true); say(s, err == null ? "&aDelivered (paid as if brought to the orb)." : "&c" + err); }
            case "escape" -> { String err = m.shiny.finish(p, false); say(s, err == null ? "&aThe pig got away." : "&c" + err); }
            case "cooldown" -> { m.shiny.clearCooldown(p); say(s, "&aShiny Pig cooldown cleared for " + p.getName()); }
            case "info" -> {
                FarmData d = m.data(p);
                long kills = Perks.shinyKills(p);
                int tier = ShinyMath.tier(kills, m.tables.shinyPigTiers);
                Shiny.Hunt h = m.shiny.hunt(p);
                say(s, "&dShiny Pigs of " + p.getName() + "&7: delivered &e" + d.shinyPigsCaught + "&7, escaped &e" + d.shinyPigsEscaped
                        + "&7, coins &6" + Text.commasInt(d.shinyCoins) + "&7, bestiary kills &e" + kills + " &7(tier " + tier + "/" + m.tables.shinyPigTiers.length + ")");
                say(s, "&7Ham Fist &e" + Perks.petAbility(p, "pig_hamfisted", "a") + "%&7, Shine Rush &e" + Perks.petAbility(p, "pig_shining_stampede", "a")
                        + "&7/tier, cooldown &e" + m.shiny.cooldownLeft(p) / 1000 + "s&7, active hunt: " + (h == null ? "&8none" : h.caught ? "&acaught" : "&eloose")
                        + "&7, live hunts on server: &e" + m.shiny.active());
            }
            default -> say(s, "&cUsage: /sb farming shiny <player> spawn|deliver|escape|cooldown|info");
        }
    }

    /** Refined Dark Cacao Truffle (materials request 11): eat one now, set the permanent count, or clear both. */
    private static void truffle(FarmingModule m, CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&cUsage: /sb farming truffle <player> eat|eaten <n>|clear"); return; }
        Player p = player(s, a[1]);
        if (p == null) return;
        FarmData d = m.data(p);
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "eat" -> {
                ItemStack one = Sky.items().exists(FarmListener.TRUFFLE) ? Sky.items().create(FarmListener.TRUFFLE) : null;
                if (one == null) { say(s, "&c" + FarmListener.TRUFFLE + " is not registered (materials)"); return; }
                m.listener.eatTruffle(p, one);
                say(s, "&aAte one: buff until " + new java.util.Date(d.truffleUntil) + ", eaten " + d.trufflesEaten);
            }
            case "eaten" -> {
                if (a.length < 4) { say(s, "&cUsage: /sb farming truffle <player> eaten <n>"); return; }
                d.trufflesEaten = Math.max(0, Integer.parseInt(a[3]));
                say(s, "&a" + p.getName() + " has eaten " + d.trufflesEaten + " truffles.");
            }
            case "clear" -> { d.truffleUntil = 0; d.trufflesEaten = 0; say(s, "&aTruffle buff and count cleared."); }
            default -> say(s, "&cUsage: /sb farming truffle <player> eat|eaten <n>|clear");
        }
        List<Harvest.Part> parts = m.perks.cropParts(p, Crop.COCOA);
        StringBuilder sb = new StringBuilder("&7Cocoa crop fortune now: ");
        for (Harvest.Part pt : parts) sb.append(pt.source()).append(" +").append(pt.value()).append("  ");
        say(s, sb.toString());
    }

    private static List<String> complete(String[] a) {
        if (a.length == 1) return List.of("info", "harvest", "contest", "medals", "pelts", "trapper", "abandon", "treasure", "shepherd", "tool", "upgrades", "golds",
                "pb", "host", "regrow", "perks", "crest", "orb", "shiny", "truffle", "open", "reset");
        if (a.length == 2 && a[0].equalsIgnoreCase("contest")) return List.of("status", "start", "stop", "score", "claim", "ghosts");
        if (a.length == 2 && !a[0].equalsIgnoreCase("regrow")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("shiny")) return List.of("spawn", "deliver", "escape", "cooldown", "info");
        if (a.length == 3 && a[0].equalsIgnoreCase("truffle")) return List.of("eat", "eaten", "clear");
        if (a.length == 3 && a[0].equalsIgnoreCase("harvest")) return Arrays.stream(Crop.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList();
        return List.of();
    }
}
