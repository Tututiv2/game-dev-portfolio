package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Player commands and the /sb mining admin tools (every admin action takes a player name: RCON-testable). */
final class MiningCommands {

    private MiningCommands() {}

    /**
     * Every admin sub with its argument line and the fewest arguments it can run with. One table feeds the
     * top-level usage, tab completion, the "too few arguments" check and the reply to a malformed number,
     * so a sub can never again answer a missing argument with a raw "Index 0 out of bounds".
     */
    private record Sub(String args, int min) {}
    private static final Map<String, Sub> SUBS = new LinkedHashMap<>();
    static {
        SUBS.put("powder", new Sub("<player> add|set|take <mithril|gemstone|glacite> <n>", 4));
        SUBS.put("hotm", new Sub("<player> xp <n>|tier <n>|reset|maxperks", 2));
        SUBS.put("perk", new Sub("<player> <perkId> <level>", 3));
        SUBS.put("commission", new Sub("<player> complete|reroll|list [island]", 2));
        SUBS.put("regen", new Sub("[count|flush]", 0));
        SUBS.put("event", new Sub("[list] | start <island> <type> [minutes] | stop <island> | star <player> | ghast <player>", 0));
        SUBS.put("forge", new Sub("<player> [list|finish]  (no player: recipe registration report)", 0));
        SUBS.put("drill", new Sub("<player> fuel <n>", 1));
        SUBS.put("skymall", new Sub("<buff|clear>", 0));
        SUBS.put("mineshaft", new Sub("<player> [variant]", 1));
        SUBS.put("find", new Sub("<player> goblin|diamond|chest|worm|scatha|crystal [gem]", 1));
        SUBS.put("temp", new Sub("<player> heat|cold <0-100>", 3));
        SUBS.put("daily", new Sub("<player>", 1));
        SUBS.put("wipe", new Sub("<player> confirm", 2));
        SUBS.put("info", new Sub("<player>", 1));
        SUBS.put("block", new Sub("[player]", 0));
        SUBS.put("kit", new Sub("<player>", 1));
    }

    private static String usage(String sub) {
        Sub u = SUBS.get(sub);
        return u == null ? "&cUsage: /sb mining <" + String.join("|", SUBS.keySet()) + "> ..." : "&cUsage: /sb mining " + sub + " " + u.args();
    }

    static void register(MiningModule m) {
        reg(SkyCommand.of("mining", "Open the Mining menu").alias("miningmenu").category("Skills"), (p, a) -> m.menus.openMain(p));
        reg(SkyCommand.of("hotm", "Open the Heart of the Mountain").alias("heartofthemountain").category("Skills"), (p, a) -> m.hotmMenu.open(p, 0));
        reg(SkyCommand.of("commissions", "Open your mining commissions").alias("comms", "commission").category("Skills"), (p, a) -> m.comms.open(p, a.length > 0 ? a[0] : null));
        reg(SkyCommand.of("forge", "Open the Forge").category("Skills"), (p, a) -> m.forge.open(p));
        reg(SkyCommand.of("drill", "Open the Drill Mechanic for the drill in your hand").alias("drillmechanic").category("Skills"), (p, a) -> m.drills.openMechanic(p));
        reg(SkyCommand.of("nucleus", "Open the Crystal Nucleus").category("Skills"), (p, a) -> m.hollows.openNucleus(p));
        reg(SkyCommand.of("excavator", "Open the Fossil Excavator").alias("fossils").category("Skills"), (p, a) -> m.excavator.open(p));
        reg(SkyCommand.of("powder", "Show your powder").category("Skills"), (p, a) -> {
            MiningData d = MiningUtil.data(p);
            for (String t : MiningUtil.POWDERS)
                p.sendMessage(Text.c(MiningUtil.powderLabel(t) + "&7: " + MiningUtil.powderColor(t) + Text.commas(d.powder(t))
                        + " &8(earned " + Text.commas(d.powderEarned.getOrDefault(t, 0L)) + ")"));
        });
        reg(SkyCommand.of("mineshaft", "Enter a mineshaft you found, or leave one").usage("[enter|leave]").category("Skills"), (p, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("leave")) m.tunnels.leave(p, false); else m.tunnels.enter(p);
        });
        reg(SkyCommand.of("deepmarket", "Show today's Deep Market buff").alias("skymall").category("Skills"),
                (p, a) -> p.sendMessage(Text.c("&b&lDEEP MARKET &7Today: " + m.skyMall.today().text + (m.hotm.has(p, "sky_mall") ? "" : " &8(unlock the perk in /hotm)"))));
        reg(SkyCommand.of("kings", "Visit today's King of the Mines").category("Skills"), (p, a) -> m.menus.openKing(p));
        reg(SkyCommand.of("miningguide", "Every mineable block and where to find it").category("Skills"), (p, a) -> m.menus.openGuide(p, 0));
        reg(SkyCommand.of("basecamp", "Open the frozen tunnels' Base Camp menu").alias("camp").category("Skills"), (p, a) -> m.menus.openCamp(p));
        reg(SkyCommand.of("fossilresearch", "Donate fossils for rewards").category("Skills"), (p, a) -> m.excavator.openResearch(p));
        reg(SkyCommand.of("fossilmuncher", "Feed the Fossil Muncher one fossil a day").alias("muncher").category("Skills"), (p, a) -> m.excavator.openMuncher(p));
        reg(SkyCommand.of("hollowsmerchant", "Open the Hollows Merchant").category("Skills"), (p, a) -> m.menus.openMerchant(p));
        reg(SkyCommand.of("fetcher", "See what the Fetcher wants today").alias("fetchur").category("Skills"), (p, a) -> m.menus.openFetcher(p));
        reg(SkyCommand.of("riddlewright", "Solve today's arrow puzzle").alias("puzzler").category("Skills"), (p, a) -> m.menus.openPuzzle(p));
        reg(SkyCommand.of("pickaxesmith", "Buy and upgrade pickaxes").category("Skills"), (p, a) -> m.menus.openShop(p, "Pickaxe Smith"));
        reg(SkyCommand.of("miningevents", "Mining events and when the next one starts").category("Skills"), (p, a) -> m.menus.openEvents(p));
        reg(SkyCommand.of("miningstats", "Your mining stats and where they come from").category("Skills"), (p, a) -> m.menus.openStats(p));

        Sky.commands().admin("mining", "<sub> ...", "Mining admin: " + String.join("|", SUBS.keySet()),
                (s, a) -> admin(m, s, a), (s, a) -> a.length <= 1 ? List.copyOf(SUBS.keySet()) : List.of());
    }

    private interface PlayerRun { void run(Player p, String[] a); }

    private static void reg(SkyCommand c, PlayerRun r) {
        Sky.commands().register(c.playerOnly().run((s, a) -> {
            Player p = (Player) s;
            if (!MiningUtil.ready(p)) { s.sendMessage(Text.c("&cYour profile is still loading.")); return; }
            r.run(p, a);
        }));
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static String perkIds(MiningModule m) {
        List<String> ids = new ArrayList<>();
        for (HotmTree.Perk p : m.hotm.tree.perks) ids.add(p.id);
        return String.join(", ", ids);
    }

    private static Player target(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null || !MiningUtil.ready(p)) { say(s, "&cNo ready player " + name); return null; }
        return p;
    }

    static void admin(MiningModule m, CommandSender s, String[] a) {
        if (a.length == 0) { say(s, usage(null)); return; }
        String sub = a[0].toLowerCase();
        String[] r = Arrays.copyOfRange(a, 1, a.length);
        Sub spec = SUBS.get(sub);
        if (spec == null) { say(s, "&cUnknown sub " + sub + ". " + usage(null)); return; }
        if (r.length < spec.min()) { say(s, usage(sub)); return; }
        try {
            switch (sub) {
                case "powder" -> {   // powder <player> add|set|take <type> <n>
                    Player p = target(s, r[0]); if (p == null) return;
                    String type = MiningUtil.parsePowder(r[2]);
                    long n = (long) Double.parseDouble(r[3]);
                    if (type == null) { say(s, "&cUnknown powder " + r[2]); return; }
                    MiningData d = MiningUtil.data(p);
                    switch (r[1].toLowerCase()) {
                        case "add" -> m.hotm.grant(p, type, n, "admin", false);
                        case "set" -> d.powder.put(type, Math.max(0, n));
                        case "take" -> d.powder.merge(type, -Math.min(n, d.powder(type)), Long::sum);
                        default -> { say(s, "&cadd|set|take"); return; }
                    }
                    say(s, "&a" + p.getName() + " now has " + Text.commas(d.powder(type)) + " " + MiningUtil.powderName(type));
                }
                case "hotm" -> {   // hotm <player> xp <n> | tier <n> | reset | maxperks
                    Player p = target(s, r[0]); if (p == null) return;
                    MiningData d = MiningUtil.data(p);
                    switch (r[1].toLowerCase()) {
                        case "xp" -> m.hotm.addXp(p, Double.parseDouble(r[2]), "admin");
                        case "tier" -> {
                            int t = Math.max(1, Math.min(m.hotm.tree.maxTier(), Integer.parseInt(r[2])));
                            d.hotmXp = m.hotm.tree.xpForTier(t);
                            m.hotm.awaken(p);
                            m.hotm.payTierRewards(p);
                        }
                        case "reset" -> m.hotm.reset(p);
                        case "maxperks" -> {
                            d.hotmXp = m.hotm.tree.xpForTier(m.hotm.tree.maxTier());
                            for (HotmTree.Perk perk : m.hotm.tree.perks) d.perks.put(perk.id, perk.maxLevel);
                            if (d.selectedAbility == null) d.selectedAbility = "mining_speed_boost";
                            Sky.stats().invalidate(p);
                        }
                        default -> { say(s, "&cxp|tier|reset|maxperks"); return; }
                    }
                    say(s, "&a" + p.getName() + ": HOTM tier " + m.hotm.tier(p) + ", " + Text.commas(d.hotmXp) + " XP, tokens " + m.hotm.tokensFree(p) + "/" + m.hotm.tokensTotal(p));
                }
                case "commission" -> {   // commission <player> complete|reroll|list [island]
                    Player p = target(s, r[0]); if (p == null) return;
                    String island = r.length > 2 ? r[2] : "dwarven_mines";
                    List<MiningData.Commission> list = m.comms.active(p, island);
                    switch (r[1].toLowerCase()) {
                        case "complete" -> list.forEach(c -> c.progress = c.target);
                        case "reroll" -> { list.clear(); m.comms.active(p, island); }
                        case "list" -> { }
                        default -> { say(s, "&ccomplete|reroll|list"); return; }
                    }
                    for (MiningData.Commission c : m.comms.active(p, island)) say(s, "&7- " + c.name + " &e" + c.progress + "/" + c.target);
                    say(s, "&7done total: " + MiningUtil.data(p).commissionsDone + ", milestones " + MiningUtil.data(p).milestonesClaimed);
                }
                case "regen" -> {   // regen count|flush
                    if (r.length > 0 && r[0].equalsIgnoreCase("flush")) say(s, "&aRestored " + m.regen.restoreEverything() + " blocks.");
                    else say(s, "&7Pending regenerations: &a" + m.regen.size() + " &7active digs: &a" + m.engine.activeDigs());
                }
                case "event" -> {   // event list | start <island> <type> [minutes] | stop <island> | star <player> | ghast <player>
                    String op = r.length > 0 ? r[0].toLowerCase() : "list";
                    switch (op) {
                        case "start" -> {
                            MiningEvents.Type t = MiningEvents.Type.valueOf(r[2].toUpperCase());
                            m.events.start(r[1], t, r.length > 3 ? Integer.parseInt(r[3]) : m.dials.eventDuration.get());
                            say(s, "&aStarted " + t.display + " on " + r[1]);
                        }
                        case "stop" -> { m.events.stop(r[1]); say(s, "&aStopped the event on " + r[1]); }
                        case "star" -> { Player p = target(s, r[1]); if (p != null) say(s, m.events.fallenStar(p) ? "&aA star fell." : "&cNo spot / one already active."); }
                        case "ghast" -> { Player p = target(s, r[1]); if (p != null) say(s, m.events.powderGhast(p) ? "&aGhast spawned." : "&cCould not spawn."); }
                        default -> {
                            for (String line : m.events.status()) say(s, "&7" + line);
                            say(s, "&7types: " + Arrays.toString(MiningEvents.Type.values()));
                        }
                    }
                }
                case "forge" -> {   // forge [<player> finish|list]
                    if (r.length == 0) {
                        for (String line : m.forge.report()) say(s, "&7" + line);
                        say(s, usage(sub));
                        return;
                    }
                    Player p = target(s, r[0]); if (p == null) return;
                    if (r.length > 1 && r[1].equalsIgnoreCase("finish")) say(s, "&aFinished " + m.forge.finishAll(p) + " forge slot(s).");
                    for (MiningData.ForgeSlot fs : MiningUtil.data(p).forge)
                        say(s, "&7slot " + (fs.slot + 1) + ": " + fs.result + " ready in " + Text.duration(Math.max(0, fs.end - System.currentTimeMillis()) / 1000));
                    say(s, "&7" + m.forge.summary());
                }
                case "drill" -> {   // drill <player> fuel <n>
                    Player p = target(s, r[0]); if (p == null) return;
                    ItemStack h = m.drills.heldDrill(p);
                    if (h == null) { say(s, "&c" + p.getName() + " is not holding a drill."); return; }
                    int n = r.length > 2 ? Integer.parseInt(r[2]) : 3000;
                    Sky.items().update(h, x -> x.extra.addProperty(Drills.FUEL, n));
                    say(s, "&aSet drill fuel to " + n);
                }
                case "skymall" -> {   // skymall <buff|clear>
                    if (r.length == 0 || r[0].equalsIgnoreCase("clear")) m.skyMall.forced = null;
                    else m.skyMall.forced = SkyMall.Buff.valueOf(r[0].toUpperCase());
                    say(s, "&aDeep Market today: " + m.skyMall.today());
                }
                case "mineshaft" -> {   // mineshaft <player> [variant]
                    Player p = target(s, r[0]); if (p == null) return;
                    m.tunnels.forceFind(p, r.length > 1 ? r[1].toUpperCase() : null);
                    say(s, "&aOpened a mineshaft for " + p.getName() + " (they run /mineshaft enter).");
                }
                case "find" -> {   // find <player> goblin|diamond|chest|worm|scatha|crystal <gem>
                    Player p = target(s, r[0]); if (p == null) return;
                    String what = r.length > 1 ? r[1].toLowerCase() : "goblin";
                    switch (what) {
                        case "goblin" -> m.finds.goblin(p, false);
                        case "diamond" -> m.finds.goblin(p, true);
                        case "chest" -> {
                            Block b = p.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.NORTH, 2);
                            m.finds.chest(p, b);
                        }
                        case "worm" -> m.finds.worm(p, p.getLocation().getBlock(), false);
                        case "scatha" -> m.finds.worm(p, p.getLocation().getBlock(), true);
                        case "crystal" -> m.finds.crystal(p, r.length > 2 ? r[2].toUpperCase() : "AMETHYST");
                        default -> { say(s, "&cgoblin|diamond|chest|worm|scatha|crystal <gem>"); return; }
                    }
                    say(s, "&aDone.");
                }
                case "info" -> {
                    Player p = target(s, r[0]); if (p == null) return;
                    MiningData d = MiningUtil.data(p);
                    say(s, "&6" + p.getName() + " &7island " + MiningUtil.island(p) + ", tier " + m.hotm.tier(p) + " (" + Text.commas(d.hotmXp) + " xp), tokens " + m.hotm.tokensFree(p));
                    for (String t : MiningUtil.POWDERS) say(s, "&7 " + t + ": " + Text.commas(d.powder(t)));
                    say(s, "&7 speed " + Text.commas(Sky.stats().get(p, Stat.MINING_SPEED)) + " fortune " + Text.commas(Sky.stats().get(p, Stat.MINING_FORTUNE))
                            + " BP " + m.engine.toolPower(p) + " pristine " + Sky.stats().get(p, Stat.PRISTINE) + " spread " + Sky.stats().get(p, Stat.MINING_SPREAD));
                    say(s, "&7 blocks " + d.blocksMined + ", commissions " + d.commissionsDone + ", ability " + d.selectedAbility + " lvl " + m.hotm.abilityLevel(p)
                            + ", heat " + m.hollows.heat(p) + ", cold " + m.tunnels.cold(p) + ", pity " + d.mineshaftPity);
                    String isl = MiningUtil.island(p);
                    double rare = m.finds.rareMult(p, isl);
                    say(s, String.format(java.util.Locale.US, "&7 goblin %.4f%%/block (Ambered +%.0f%%), worm %.4f%%/block (Blazing +%.2f%%/Heat)%s",
                            m.finds.goblinChance(p, rare) * 100, ReforgeFinds.amberedPct(p), m.finds.wormChance(p, rare) * 100,
                            ReforgeFinds.blazingPctPerHeat(p), m.dials.reforgesExtraRoll.get() ? " &8(mining's roll only; reforges adds its share)" : ""));
                    say(s, m.islandSpeed.describe(p) + "&7, crystals still needed: " + Consumables.neededCrystals(p));
                }
                case "block" -> {   // block [player]: what the player looks at
                    Player p = r.length > 0 ? target(s, r[0]) : (s instanceof Player pl ? pl : null);
                    if (p == null) return;
                    Block b = p.getTargetBlockExact(6);
                    if (b == null) { say(s, "&cNot looking at a block."); return; }
                    BlockTable.Def def = m.engine.mineable(p, b, false);
                    String island = MiningUtil.island(b.getLocation());
                    BlockTable.Def raw = m.blocks.lookup(island, b.getType().name());
                    say(s, "&7" + b.getType() + " on " + island + ": table=" + (raw == null ? "none" : raw.id) + ", mineable here=" + (def == null ? "no" : def.id));
                    if (def != null) {
                        double speed = Sky.stats().get(p, Stat.MINING_SPEED) + m.breaker.contextSpeed(p, def, b);
                        say(s, "&7 strength " + def.strength + " BP " + def.breakingPower + " speed " + Text.commas(speed) + " -> "
                                + MiningMath.ticksToBreak(def.strength, speed, def.kind.sixtyRule()) + " ticks, fortune " + Text.commas(m.breaker.fortune(p, def)));
                    }
                }
                case "perk" -> {   // perk <player> <perkId> <level>: set one HOTM perk (0 removes it)
                    Player p = target(s, r[0]); if (p == null) return;
                    HotmTree.Perk perk = m.hotm.tree.perk(r[1].toLowerCase());
                    if (perk == null) { say(s, "&cUnknown perk " + r[1] + ". Perks: " + perkIds(m)); return; }
                    int lvl = Math.max(0, Math.min(perk.maxLevel, Integer.parseInt(r[2])));
                    MiningData d = MiningUtil.data(p);
                    if (lvl == 0) d.perks.remove(perk.id); else d.perks.put(perk.id, lvl);
                    if (perk.ability && lvl > 0 && d.selectedAbility == null) d.selectedAbility = perk.id;
                    Sky.stats().invalidate(p);
                    say(s, "&a" + p.getName() + ": " + perk.name + " is now level " + lvl + " (value " + perk.value(lvl) + ")");
                }
                case "temp" -> {   // temp <player> heat|cold <0-100>
                    Player p = target(s, r[0]); if (p == null) return;
                    int n = Integer.parseInt(r[2]);
                    if (r[1].equalsIgnoreCase("heat")) m.hollows.setHeat(p, n); else m.tunnels.setCold(p, n);
                    say(s, "&a" + p.getName() + ": heat " + m.hollows.heat(p) + ", cold " + m.tunnels.cold(p));
                }
                case "daily" -> {   // daily <player>: reset every once-a-day counter (King, Fetcher, Riddlewright, daily bonuses)
                    Player p = target(s, r[0]); if (p == null) return;
                    MiningData d = MiningUtil.data(p);
                    d.kingDate = ""; d.fetchurDate = ""; d.puzzlerDate = ""; d.dailyDate = ""; d.muncherDate = "";
                    MiningUtil.rollDay(d);
                    say(s, "&aReset the daily counters of " + p.getName() + ".");
                }
                case "wipe" -> {   // wipe <player> confirm: every mining field back to a new profile's (testing)
                    if (!r[1].equalsIgnoreCase("confirm")) { say(s, usage(sub) + "  &7(forge slots and powder are lost)"); return; }
                    Player p = target(s, r[0]); if (p == null) return;
                    MiningData d = MiningUtil.data(p), fresh = new MiningData();
                    for (java.lang.reflect.Field f : MiningData.class.getFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || java.lang.reflect.Modifier.isFinal(f.getModifiers())) continue;
                        try { f.set(d, f.get(fresh)); } catch (IllegalAccessException ignored) { }
                    }
                    Sky.stats().invalidate(p);
                    say(s, "&aWiped the mining data of " + p.getName() + ".");
                }
                case "kit" -> {   // kit <player>: a drill with parts and fuel, some powder
                    Player p = target(s, r[0]); if (p == null) return;
                    ItemStack drill = Sky.items().create("TITANIUM_DRILL_4");
                    Sky.items().update(drill, x -> {
                        x.extra.addProperty(Drills.TANK, "GEMSTONE_FUEL_TANK");
                        x.extra.addProperty(Drills.ENGINE, "TITANIUM_DRILL_ENGINE");
                        x.extra.addProperty(Drills.MODULE, "GOBLIN_OMELETTE");
                        x.extra.addProperty(Drills.FUEL, 50000);
                    });
                    Sky.loot().give(p, drill, LootSource.ADMIN);
                    for (String id : List.of("MITHRIL_PICKAXE", "CHISEL", "MITHRIL_LANTERN"))
                        if (MiningUtil.exists(id)) Sky.loot().give(p, id, 1, LootSource.ADMIN);
                    for (String id : List.of("SUSPICIOUS_SCRAP", "VOLTA")) if (MiningUtil.exists(id)) Sky.loot().give(p, id, 3, LootSource.ADMIN);
                    for (String g : Hollows.NUCLEUS_GEMS) Sky.loot().give(p, g + "_CRYSTAL", 1, LootSource.ADMIN);
                    say(s, "&aGave the mining test kit to " + p.getName());
                }
                default -> say(s, usage(null));
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            say(s, usage(sub));   // an optional-argument branch that needed one more (event start, drill fuel...)
        } catch (IllegalArgumentException e) {   // NumberFormatException, an unknown enum constant
            say(s, "&cBad value: " + e.getMessage());
            say(s, usage(sub));
        }
    }
}
