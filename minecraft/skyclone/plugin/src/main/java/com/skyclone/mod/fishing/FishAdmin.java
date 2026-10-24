package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** {@code /sb fishing ...} - everything the integrator needs to exercise fishing over RCON. */
final class FishAdmin {

    private FishAdmin() {}

    static final String USAGE = "seacreature <p> <mob> [n] | catch <p> [n] | bite <p> | odds <p> | simulate <p> <casts> | stats <p> | "
            + "trophy <p> <fish> <tier> [n] | trophytitle <p> <0-4> | golden <p> | clear [p] | hotspot list|spawn <island>|clear | "
            + "festival on|off|auto | creatures [filter] | bag <p> | reset <p> confirm | gusher | "
            + "geysers [rescan] | jumper <p> [ride] | thunder <p> <charge> | husks <p> [n] | effects <p> | chumbuckets [clear]";

    static void register() {
        Sky.commands().admin("fishing", USAGE, "Fishing admin: spawn sea creatures, simulate catches, trophies, hotspots, festival",
                FishAdmin::run, (s, a) -> complete(a));
    }

    private static List<String> complete(String[] a) {
        if (a.length <= 1) return List.of("seacreature", "catch", "bite", "odds", "simulate", "stats", "trophy", "trophytitle", "golden",
                "clear", "hotspot", "festival", "creatures", "bag", "reset", "gusher", "geysers", "jumper", "thunder", "husks", "effects", "chumbuckets");
        if (a.length == 2 && a[0].equals("geysers")) return List.of("rescan");
        if (a.length == 2 && a[0].equals("chumbuckets")) return List.of("clear");
        if (a.length == 2 && a[0].equals("festival")) return List.of("on", "off", "auto");
        if (a.length == 2 && a[0].equals("hotspot")) return List.of("list", "spawn", "clear");
        if (a.length == 2) { List<String> n = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName()); return n; }
        if (a.length == 3 && a[0].equals("seacreature")) return FishMenus.creatureOrder();
        if (a.length == 3 && a[0].equals("trophy")) return new ArrayList<>(Fishing.CAT.trophy.fish.keySet());
        if (a.length == 4 && a[0].equals("trophy")) return List.of(FishingCatalog.TIERS);
        return List.of();
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String name) {
        Player p = name == null ? null : Bukkit.getPlayerExact(name);
        if (p == null) say(s, "&cNo online player '" + name + "'.");
        else if (!Fishing.ready(p)) { say(s, "&cThat player's profile is not ready."); return null; }
        return p;
    }

    static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&7/sb fishing " + USAGE); return; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "seacreature" -> {
                if (a.length < 3) { say(s, "&7/sb fishing seacreature <player> <mobId> [n]"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                String mob = a[2].startsWith("sc_") ? a[2] : "sc_" + a[2];
                if (Sky.mobs().def(mob) == null) { say(s, "&cUnknown sea creature '" + mob + "'. Try /sb fishing creatures"); return; }
                int n = Math.max(1, Math.min(20, a.length > 3 ? parse(a[3], 1) : 1));
                int ok = 0;
                for (int i = 0; i < n; i++) {
                    LivingEntity le = SeaCreatures.spawn(p, mob, p.getLocation().add(p.getLocation().getDirection().multiply(3)), 0, 0, 0, false);
                    if (le != null) ok++;
                }
                say(s, "&aSpawned " + ok + "x " + Fishing.mobName(mob) + " &afor " + p.getName() + ".");
            }
            case "catch" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                int n = a.length > 2 ? Math.min(200, parse(a[2], 1)) : 1;
                for (int i = 0; i < n; i++) {
                    Casting.Cast c = fakeCast(p);
                    Casting.resolve(p, c, p.getLocation());
                }
                say(s, "&aRolled " + n + " real catch(es) for " + p.getName() + " at their location.");
            }
            case "odds" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                PoolResolver.Spot spot = FishMenus.spotFor(p);
                var base = Fishing.RES.basePool(spot);
                say(s, "&bPool at " + p.getName() + ": " + (base == null ? "none" : base.id) + " &7(" + spot.liquid + ", island " + spot.island
                        + ", lvl " + spot.fishingLevel + ", night " + spot.night + ", zone '" + spot.zoneText.trim() + "')");
                Casting.Cast fake = fakeCast(p);
                Map<String, Double> odds = PoolResolver.odds(Fishing.RES.options(spot), Casting.mods(p, fake, spot));
                for (Map.Entry<String, Double> e : odds.entrySet())
                    say(s, " &7" + e.getKey() + ": &a" + Text.commas(Math.round(e.getValue() * 10000) / 100.0) + "%");
                for (PoolResolver.Replacement r : Fishing.RES.replacements(spot))
                    say(s, " &dreplace " + r.pool().id + " @ " + Text.commas(Math.round(Casting.replaceChance(p, fake, r.pool()) * 1000) / 10.0) + "%");
            }
            case "simulate" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                int n = a.length > 2 ? Math.min(100000, parse(a[2], 1000)) : 1000;
                Casting.Cast fake = fakeCast(p);
                Map<String, Integer> counts = new TreeMap<>();
                PoolResolver.Spot spot = Casting.spot(p, fake, p.getLocation(), Fishing.island(p.getLocation()));
                var opts = Fishing.RES.options(spot);
                var mods = Casting.mods(p, fake, spot);
                var reps = Fishing.RES.replacements(spot);
                for (int i = 0; i < n; i++) {
                    String mob = Casting.pickFrom(p, fake, opts, mods, reps);
                    counts.merge(mob == null ? "(none)" : mob, 1, Integer::sum);
                }
                say(s, "&bSimulated " + n + " sea creature bites for " + p.getName() + ":");
                for (Map.Entry<String, Integer> e : counts.entrySet())
                    say(s, " &7" + e.getKey() + ": &a" + e.getValue() + " &8(" + Text.commas(Math.round(e.getValue() * 10000.0 / n) / 100.0) + "%)");
            }
            case "stats" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                for (String l : FishMenus.statLines(p, p.getLocation(), null)) say(s, l);
                FishData d = Fishing.data(p);
                say(s, "&7Fishing level " + Fishing.level(p) + ", casts " + d.casts + ", items " + d.itemsCaught + ", trophies " + d.trophyTotal
                        + ", title " + Trophies.title(d) + ", live creatures " + SeaCreatures.count(p.getUniqueId()));
            }
            case "trophy" -> {
                if (a.length < 4) { say(s, "&7/sb fishing trophy <player> <FISH> <tier> [n]"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                String fish = a[2].toUpperCase(Locale.ROOT);
                String tier = a[3].toUpperCase(Locale.ROOT);
                if (!Fishing.CAT.trophy.fish.containsKey(fish) || FishingCatalog.tierIndex(tier) < 0) { say(s, "&cUnknown fish or tier."); return; }
                int n = Math.max(1, Math.min(1000, a.length > 4 ? parse(a[4], 1) : 1));
                for (int i = 0; i < n; i++) Trophies.give(p, fish, tier);
                say(s, "&aGave " + n + "x " + fish + " " + tier + " to " + p.getName() + ".");
            }
            case "trophytitle" -> {
                if (a.length < 3) { say(s, "&7/sb fishing trophytitle <player> <0-4>"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                int t = Math.max(0, Math.min(4, parse(a[2], 0)));
                FishData d = Fishing.data(p);
                d.trophies.clear();
                if (t > 0) for (String stem : Fishing.CAT.trophy.fish.keySet()) {
                    Map<String, Integer> m = new HashMap<>();
                    for (int i = 0; i < t; i++) m.put(FishingCatalog.TIERS[i], 1);
                    d.trophies.put(stem, m);
                }
                say(s, "&aTrophy progress set: " + p.getName() + " now holds title " + Trophies.title(d) + ".");
            }
            case "gusher" -> {
                Trophies.EruptionClock c = Trophies.eruptionClock();
                say(s, "&7Eruption clock: &e" + (c.own() ? "fishing's own cycle" : FishDials.ERUPTION_EVENT.get())
                        + "&7, last eruption &e" + Trophies.clock(c.since() / 1000L) + " &7ago, next in &e"
                        + Trophies.clock(c.untilNext() / 1000L) + "&7, cycle &e" + (c.cycle() / 60_000L) + "m");
                say(s, Trophies.gusherWindow() ? "&6Gusher biting now, &e" + Trophies.clock(Trophies.gusherSeconds()) + " &6left"
                        : "&7Gusher window opens in &e" + Trophies.clock(Trophies.gusherSeconds()));
            }
            case "geysers" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("rescan")) { Geysers.reset(); say(s, "&aGeysers forgotten; the next tick searches again (players must be near the volcano)."); return; }
                say(s, "&7Crimson geysers: &e" + Geysers.describe(Trophies.TROPHY_ISLAND) + " &7(radius &e" + FishDials.GEYSER_RADIUS.get() + "&7)");
                for (var g : Geysers.sites(Trophies.TROPHY_ISLAND)) say(s, " &8- &f" + g.getBlockX() + ", " + g.getBlockY() + ", " + g.getBlockZ());
            }
            case "jumper" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                LivingEntity le = SeaCreatures.spawn(p, PuddleJumper.MOB, p.getLocation().add(p.getLocation().getDirection().multiply(3)), 0, 0, 0, false);
                if (le == null) { say(s, "&cCould not spawn the Puddle Jumper."); return; }
                if (a.length > 2 && a[2].equalsIgnoreCase("ride")) {
                    PuddleJumper.Jumper j = PuddleJumper.LIVE.get(le.getUniqueId());
                    if (j != null) PuddleJumper.startRide(p, le, j, Sky.mobs().defOf(le));
                }
                say(s, "&aPuddle Jumper for " + p.getName() + ": " + PuddleJumper.describe(le.getUniqueId()) + " &7(hold a rod, don't sneak)");
            }
            case "thunder" -> {
                if (a.length < 3) { say(s, "&7/sb fishing thunder <player> <charge> &8- charges their first Empty Thunder Bottle as if a creature with that Fishing XP died"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                long add = FishItems.chargeBottle(p, parse(a[2], 0));
                say(s, add > 0 ? "&aAdded " + add + " charge to " + p.getName() + "'s Thunder Bottle." : "&c" + p.getName() + " carries no single Empty Thunder Bottle.");
            }
            case "husks" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                FishData d = Fishing.data(p);
                if (a.length > 2) { d.isopodHusks = Math.max(0, parse(a[2], 0)); FishItems.syncRespiration(p); }
                say(s, "&7" + p.getName() + " has eaten &e" + d.isopodHusks + "&7/" + FishDials.HUSK_MAX.get() + " Isopod Husks.");
            }
            case "effects" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                double[] tq = FishEffects.treasureQuality(p, false), jq = FishEffects.treasureQuality(p, true);
                say(s, "&7Fishing hooks for &f" + p.getName() + "&7: bait keep &a" + Math.round(Casting.baitAccessoryKeep(p) * 1000) / 10.0
                        + "%&7, treasure coins &a+" + Math.round(FishEffects.treasureCoins(p) * 100) + "%&7, great &a+" + Math.round(tq[0] * 100)
                        + "%&7, outstanding &a+" + Math.round(tq[1] * 100) + "%&7, junk great/out &a+" + Math.round(jq[0] * 100) + "/" + Math.round(jq[1] * 100)
                        + "%&7, wet book &a" + Math.round(FishEffects.wetBook(p) * 1000) / 10.0 + "%&7, infection &a+" + Math.round(FishEffects.infection(p) * 100)
                        + "%&7, golden sooner &a" + Math.round(FishEffects.goldenSooner(p) * 60) + "s");
                say(s, "&7Slugfish wait &e" + Trophies.slugfishWaitMs(p) / 1000.0 + "s&7, Flash x&e" + FishEffects.flashMult(p)
                        + "&7, shark teeth &a+" + Math.round(FishEffects.sharkToothBonus(p) * 100) + "%&7, Tera Shell " + (FishEffects.teraShell(p) ? "&cON" : "&8off")
                        + "&7, Pulse Ring " + (FishEffects.pulseRing(p) ? "&aON" : "&8off"));
                String isl = Fishing.island(p.getLocation());
                var pool = isl == null ? null : FishBowls.pools().get(isl);
                say(s, "&7Fish Bowl &a+" + Math.round(FishBowls.bonus(p) * 100) + "% &7(extra roll " + Math.round(FishBowls.baseChance() * FishBowls.bonus(p) * 10000) / 100.0
                        + "%, " + (pool == null ? 0 : pool.size()) + " rare water shards here), Luminous Bracelet "
                        + (FishEffects.has(p, "LUMINOUS_BRACELET") ? "&aON" : "&8off") + "&7, Chumcap FS &b+" + ChumBuckets.fishingSpeed(p.getLocation())
                        + "&7, buckets in reach &a" + ChumBuckets.inReach(p.getLocation(), false));
            }
            case "chumbuckets" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("clear")) {
                    int n = ChumBuckets.LIVE.size();
                    ChumBuckets.stop();
                    ChumBuckets.start();
                    say(s, "&aRemoved " + n + " placed bucket(s); each goes back to its owner on the next sweep.");
                    return;
                }
                if (ChumBuckets.LIVE.isEmpty()) { say(s, "&7No chum buckets placed."); return; }
                for (ChumBuckets.Bucket b : ChumBuckets.LIVE.values())
                    say(s, "&7" + b.ownerName + " &f" + b.kind + " &7at " + b.at.getWorld().getName() + " " + b.at.getBlockX() + "," + b.at.getBlockY()
                            + "," + b.at.getBlockZ() + " &7deposits &a" + b.deposits + " &7age " + (System.currentTimeMillis() - b.placedAt) / 1000 + "s");
            }
            case "golden" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                Trophies.forceGolden(p);
                say(s, "&6A Golden Fish now circles " + p.getName() + "'s next lava catch.");
            }
            case "clear" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                int n = SeaCreatures.removeAll(p == null ? null : p.getUniqueId());
                say(s, "&aRemoved " + n + " sea creature(s).");
            }
            case "hotspot" -> {
                String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
                if (act.equals("spawn")) {
                    String island = a.length > 2 ? a[2] : "hub";
                    World w = Bukkit.getWorld("sb_" + island);
                    if (w == null) { say(s, "&cWorld sb_" + island + " is not loaded."); return; }
                    Hotspots.Hotspot h = Hotspots.spawn(island, w);
                    say(s, h == null ? "&cNo water/lava surface found for a hotspot on " + island + "."
                            : "&aHotspot on " + island + " at " + h.center.getBlockX() + "," + h.center.getBlockY() + "," + h.center.getBlockZ() + " (" + h.buff + ")");
                } else if (act.equals("clear")) {
                    say(s, "&aCleared " + Hotspots.clear(a.length > 2 ? a[2] : null) + " hotspot(s).");
                } else {
                    say(s, "&d" + Hotspots.live().size() + " hotspot(s):");
                    for (Hotspots.Hotspot h : Hotspots.live())
                        say(s, " &7" + h.island + " " + h.center.getBlockX() + "," + h.center.getBlockY() + "," + h.center.getBlockZ()
                                + " " + h.buff + (h.lava ? " (lava)" : "") + " " + h.secondsLeft() + "s");
                }
            }
            case "festival" -> {
                String mode = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                if (!mode.equals("on") && !mode.equals("off") && !mode.equals("auto")) {
                    say(s, "&7Festival is " + (Fishing.festival() ? "&aactive" : "&cinactive") + " &7(mode " + FishDials.FESTIVAL_MODE.get() + "). Use on|off|auto.");
                    return;
                }
                FishDials.FESTIVAL_MODE.set(mode);
                say(s, "&aFishing Festival mode: " + mode + " -> " + (Fishing.festival() ? "active" : "inactive"));
            }
            case "creatures" -> {
                String f = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                int n = 0;
                for (String id : FishMenus.creatureOrder()) {
                    if (!id.contains(f)) continue;
                    MobDef d = Sky.mobs().def(id);
                    say(s, " &7" + id + " &f" + (d == null ? "MISSING" : Text.strip(d.name) + " hp " + Text.shortNum(d.health) + " lvl " + FishMenus.levelOf(id)));
                    n++;
                }
                say(s, "&b" + n + " sea creature(s).");
            }
            case "bite" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                int n = Casting.forceBite(p);
                say(s, n == 0 ? "&c" + p.getName() + " has no bobber in fishable liquid." : "&aThe next bite on " + p.getName() + "'s bobber comes now.");
            }
            case "bag" -> {
                Player p = a.length > 1 ? player(s, a[1]) : null;
                if (p == null) return;
                FishData d = Fishing.data(p);
                say(s, "&bFishing Bag of " + p.getName() + " &7(" + Baits.capacity(p) + " slots, use " + (d.bagEnabled ? "on" : "off")
                        + ", preferred " + d.preferredBait + "):");
                int shown = 0;
                for (int i = 0; i < d.bag.size(); i++) if (!d.bag.get(i).isEmpty()) { say(s, " &7" + i + ": &f" + d.bag.get(i)); shown++; }
                if (shown == 0) say(s, " &8(empty)");
            }
            case "reset" -> {
                if (a.length < 3 || !a[2].equalsIgnoreCase("confirm")) { say(s, "&7/sb fishing reset <player> confirm &8- wipes their fishing records, trophies and bag"); return; }
                Player p = player(s, a[1]);
                if (p == null) return;
                FishData d = Fishing.data(p);
                d.caught.clear(); d.kills.clear(); d.trophies.clear(); d.poolsSeen.clear(); d.bag.clear();
                d.sinceGold = 0; d.sinceDiamond = 0; d.trophyTotal = 0; d.itemsCaught = 0; d.casts = 0;
                d.treasureGood = 0; d.treasureGreat = 0; d.treasureOutstanding = 0; d.doubleHooks = 0; d.magmafishFilleted = 0;
                d.preferredBait = null; d.starterRod = false; d.partsApplied = 0; d.bagEnabled = true;
                d.isopodHusks = 0; d.puddleRides = 0; d.puddleFalls = 0;
                FishItems.syncRespiration(p);
                SeaCreatures.removeAll(p.getUniqueId());
                Sky.profiles().saveNow(p);
                say(s, "&aReset all fishing data of " + p.getName() + ".");
            }
            default -> say(s, "&7/sb fishing " + USAGE);
        }
    }

    /** A cast as if the player just landed a bobber where they stand, with their current stats. */
    static Casting.Cast fakeCast(Player p) {
        var rod = p.getInventory().getItemInMainHand();
        String rodId = Fishing.isRod(rod) ? Sky.items().idOf(rod) : "FISHING_ROD";
        Casting.Cast c = new Casting.Cast(p.getUniqueId(), java.util.UUID.randomUUID(), rodId,
                Fishing.isRod(rod) ? Sky.items().data(rod) : null, Fishing.isLavaRod(rodId), false);
        c.liquid = c.lavaRod ? "lava" : "water";
        c.bait = Fishing.data(p).preferredBait;
        c.landedAt = System.currentTimeMillis() - 25_000;
        c.fs = Sky.stats().get(p, com.skyclone.api.stat.Stat.FISHING_SPEED);
        c.scc = Sky.stats().get(p, com.skyclone.api.stat.Stat.SEA_CREATURE_CHANCE);
        c.treasure = Sky.stats().get(p, com.skyclone.api.stat.Stat.TREASURE_CHANCE);
        c.dh = Sky.stats().get(p, com.skyclone.api.stat.Stat.DOUBLE_HOOK_CHANCE);
        c.trophy = Sky.stats().get(p, com.skyclone.api.stat.Stat.TROPHY_FISH_CHANCE);
        c.hotspot = Hotspots.at(p.getLocation());
        c.phase = Casting.Phase.BITE;
        return c;
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
