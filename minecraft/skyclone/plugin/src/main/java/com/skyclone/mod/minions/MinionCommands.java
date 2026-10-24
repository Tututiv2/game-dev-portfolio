package com.skyclone.mod.minions;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.minions.MinionCatalog.FuelDef;
import com.skyclone.mod.minions.MinionCatalog.TierDef;
import com.skyclone.mod.minions.MinionCatalog.TypeDef;
import com.skyclone.mod.minions.MinionCatalog.UpgradeDef;
import com.skyclone.mod.minions.MinionStore.Placed;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /minions, /craftedminions and every /sb minions admin tool (all take a player name: RCON-testable). */
final class MinionCommands {

    private MinionCommands() {}

    static void register(MinionsModule mod) {
        Sky.commands().register(SkyCommand.of("minions", "Your minions: overview, slots, crafted minions, fuels and upgrades")
                .alias("minion").category("Island").usage("[crafted|slots|gear|crystals|beacon|collect]").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    String sub = a.length == 0 ? "" : a[0].toLowerCase();
                    switch (sub) {
                        case "crafted", "guide" -> mod.menus.openCrafted(p, "ALL", 0);
                        case "slots" -> mod.menus.openSlots(p);
                        case "gear", "upgrades", "fuels", "fuel" -> mod.menus.openGear(p, 0);
                        case "crystals" -> mod.menus.openCrystals(p);
                        case "beacon" -> {
                            MinionStore.Crystal b = mod.manager.beacon(mod.manager.store(p));
                            if (b == null) { Msg.send(p, "&7Your island has no Beacon. Place one to boost every minion."); mod.menus.openCrystals(p); }
                            else mod.menus.openBeacon(p, b.id);
                        }
                        case "collect" -> {
                            int total = 0;
                            for (Placed m : new ArrayList<>(mod.manager.store(p).placed.values()))
                                if (mod.manager.canManage(p, m)) total += mod.manager.collectAll(p, m, true);
                            Msg.send(p, total > 0 ? "&aCollected " + Text.commas(total) + " items from your minions." : "&7Nothing to collect here.");
                        }
                        default -> mod.menus.openOverview(p, 0);
                    }
                })
                .tab((s, a) -> a.length == 1 ? List.of("crafted", "slots", "gear", "crystals", "beacon", "collect") : List.of()));
        Sky.commands().register(SkyCommand.of("craftedminions", "Every minion type and which tiers you have crafted")
                .alias("minionguide").category("Island").playerOnly()
                .run((s, a) -> mod.menus.openCrafted((Player) s, "ALL", 0)));

        Sky.commands().admin("minions", "<sub> ...", "Minion tools: info|give|place|tick|fill|clear|collect|fuel|upgrade|pickup|crafted|beacon|seat|freewill|chest|present|rate|recipes|types|open",
                (s, a) -> admin(mod, s, a),
                (s, a) -> {
                    if (a.length == 1) return List.of("info", "give", "place", "tick", "fill", "clear", "collect", "fuel", "upgrade",
                            "pickup", "crafted", "beacon", "seat", "freewill", "chest", "present", "rate", "recipes", "types", "open", "respawn", "slots");
                    if (a.length == 2 && !List.of("rate", "recipes", "types", "respawn").contains(a[0]))
                        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                    if (a.length >= 2 && (a[0].equals("rate") && a.length == 2 || (a[0].equals("give") || a[0].equals("place")) && a.length == 3))
                        return new ArrayList<>(mod.cat.types.keySet());
                    return List.of();
                });
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player target(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) { say(s, "&cPlayer " + name + " is not online."); return null; }
        if (!Sky.profiles().isReady(p)) { say(s, "&cProfile of " + name + " is not ready."); return null; }
        return p;
    }

    private static List<Placed> select(MinionsModule mod, Player p, String which) {
        List<Placed> all = new ArrayList<>(mod.manager.store(p).placed.values());
        if (which == null || which.equals("all")) return all;
        try {
            int i = Integer.parseInt(which);
            return i >= 1 && i <= all.size() ? List.of(all.get(i - 1)) : List.of();
        } catch (NumberFormatException e) {
            List<Placed> out = new ArrayList<>();
            for (Placed m : all) if (m.type.equalsIgnoreCase(which) || m.id.startsWith(which)) out.add(m);
            return out;
        }
    }

    static void admin(MinionsModule mod, CommandSender s, String[] a) {
        if (a.length == 0) {
            say(s, "&e/sb minions info <player> &7- placed minions, slots, uniques");
            say(s, "&e/sb minions give <player> <TYPE> <tier> [n] &7- give minion items");
            say(s, "&e/sb minions place <player> <TYPE> <tier> &7- place one where the player stands");
            say(s, "&e/sb minions tick <player> <seconds> [index|all] &7- fast-forward production (offline catch-up test)");
            say(s, "&e/sb minions fill|clear|collect|pickup <player> [index|all]");
            say(s, "&e/sb minions fuel <player> <index|all> <FUEL_ID> [n] &7| upgrade <player> <index|all> <ITEM_ID>");
            say(s, "&e/sb minions crafted <player> add <TYPE> <tier> | all | clear | count");
            say(s, "&e/sb minions beacon <player> power <hours> [scorched] | clear | stat <STAT> | info &7- island Beacon power and profile stat");
            say(s, "&e/sb minions seat <player> <index|all> &7- toggle the Minion Chair (no item, ignores the cap)");
            say(s, "&e/sb minions freewill <player> <index> <loyal|leave|roll|clear> &7- Free Will outcomes without the item");
            say(s, "&e/sb minions chest <player> <index|all> [remove] &7- put the storage chest beside the minion as a block");
            say(s, "&e/sb minions rate <TYPE> <tier> [speed%] [mult] &7- items per hour");
            say(s, "&e/sb minions recipes &7| types &7| respawn &7| open <player> [crafted|slots|gear|index]");
            return;
        }
        String sub = a[0].toLowerCase();
        MinionCatalog cat = mod.cat;
        switch (sub) {
            case "types" -> {
                StringBuilder sb = new StringBuilder();
                for (TypeDef t : cat.types()) sb.append(t.id).append("(").append(t.tiers.size()).append(") ");
                say(s, "&e" + cat.types.size() + " types: &7" + sb);
            }
            case "recipes" -> {
                say(s, "&eMinion recipes registered: &a" + mod.recipesRegistered + "&e, skipped: &c" + mod.recipesSkipped.size());
                for (int i = 0; i < Math.min(30, mod.recipesSkipped.size()); i++) say(s, "&7 - " + mod.recipesSkipped.get(i));
            }
            case "respawn" -> { mod.manager.despawnAll(); mod.manager.ensureStands(); say(s, "&aMinion bodies respawned."); }
            case "rate" -> {
                if (a.length < 3) { say(s, "&cUsage: rate <TYPE> <tier> [speed%] [mult]"); return; }
                TypeDef t = cat.type(a[1].toUpperCase());
                if (t == null) { say(s, "&cUnknown type."); return; }
                int tier = parseInt(a[2], 1);
                TierDef td = t.tier(tier);
                if (td == null) { say(s, "&cNo tier " + tier); return; }
                double sp = a.length > 3 ? parseD(a[3], 0) : 0;
                double mult = a.length > 4 ? parseD(a[4], 1) : 1;
                say(s, "&e" + t.name + " Minion " + Text.roman(tier) + "&7: TBA " + td.tba + "s -> " + MinionText.seconds(MinionSim.effectiveTba(td.tba, sp))
                        + " &7at +" + sp + "%, harvest every " + MinionText.seconds(2 * MinionSim.effectiveTba(td.tba, sp)) + ", storage " + td.storage);
                for (Map.Entry<String, Double> e : MinionSim.perHour(t, tier, sp, mult).entrySet())
                    say(s, "&7  " + e.getKey() + ": &a" + Math.round(e.getValue() * 100) / 100.0 + "/h &8(" + Math.round(e.getValue() * 24) + "/day)");
            }
            case "info" -> {
                if (a.length < 2) { say(s, "&cUsage: info <player>"); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                MinionStore st = mod.manager.store(p);
                say(s, "&e" + p.getName() + "&7: placed &a" + st.working() + (st.placed.size() > st.working() ? "&7 (+" + (st.placed.size() - st.working()) + " seated)&a" : "") + "&7/&a" + mod.manager.slotsFor(p, st)
                        + "&7, uniques &e" + st.crafted.size() + "&7, crystals &d" + st.crystals.size()
                        + "&7, lifetime &b" + st.totalGenerated);
                int i = 1;
                MinionManager.Env env = mod.manager.env(p);
                for (Placed m : st.placed.values()) {
                    TypeDef t = cat.type(m.type);
                    boolean full = t != null && MinionSim.isFull(cat, m, t, MinionSim.capacity(cat, m), env);
                    say(s, "&7 " + (i++) + ". &f" + m.type + " " + Text.roman(m.tier) + " &8@" + MinionText.where(m)
                            + " &7slots " + m.storage.size() + "/" + MinionSim.capacity(cat, m) + " items " + m.stored()
                            + " fuel " + (m.fuel == null ? "-" : m.fuel + "x" + m.fuelCount) + " up " + m.upgrade1 + "," + m.upgrade2
                            + (full ? " &cFULL" : "") + (mod.manager.stand(m.id) != null ? " &a[body]" : " &8[no body]"));
                }
            }
            case "give" -> {
                if (a.length < 4) { say(s, "&cUsage: give <player> <TYPE> <tier> [n]"); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                TypeDef t = cat.type(a[2].toUpperCase());
                int tier = parseInt(a[3], 1);
                if (t == null || t.tier(tier) == null) { say(s, "&cUnknown type/tier."); return; }
                int n = Math.max(1, Math.min(64, a.length > 4 ? parseInt(a[4], 1) : 1));
                for (int i = 0; i < n; i++) Sky.loot().give(p, mod.manager.minionItem(t.id, tier, 0, false), LootSource.ADMIN);
                say(s, "&aGave " + n + "x " + t.id + " " + tier + " to " + p.getName());
            }
            case "place" -> {
                if (a.length < 4) { say(s, "&cUsage: place <player> <TYPE> <tier>"); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                TypeDef t = cat.type(a[2].toUpperCase());
                int tier = parseInt(a[3], 1);
                if (t == null || t.tier(tier) == null) { say(s, "&cUnknown type/tier."); return; }
                Block feet = p.getLocation().getBlock();
                Block below = feet.getRelative(BlockFace.DOWN);
                MinionStore st = mod.manager.store(p);
                Placed m = new Placed();
                m.id = UUID.randomUUID().toString();
                m.type = t.id;
                m.tier = tier;
                m.world = feet.getWorld().getName();
                m.x = feet.getX();
                m.y = feet.getY();
                m.z = feet.getZ();
                m.lastUpdate = System.currentTimeMillis();
                m.placedAt = m.lastUpdate;
                m.placedBy = "admin";
                st.placed.put(m.id, m);
                mod.manager.ensureStands();
                say(s, "&aPlaced " + t.id + " " + tier + " at " + MinionText.where(m) + (below.getType().isSolid() ? "" : " &e(no floor!)")
                        + " &7- ignores slots and island checks.");
            }
            case "tick" -> {
                if (a.length < 3) { say(s, "&cUsage: tick <player> <seconds> [index|all]"); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                long secs = (long) parseD(a[2], 0);
                List<Placed> list = select(mod, p, a.length > 3 ? a[3] : "all");
                MinionManager.Env env = mod.manager.env(p);
                long now = System.currentTimeMillis();
                long total = 0;
                double coins = 0;
                for (Placed m : list) {
                    m.lastUpdate = Math.min(m.lastUpdate, now) - secs * 1000L;
                    env.live = false;
                    MinionSim.Result r = mod.manager.advance(m, now, env, false);
                    total += r.total();
                    coins += r.coins;
                    say(s, "&7 " + m.type + " " + Text.roman(m.tier) + ": &a" + r.harvests + " harvests&7, made &a" + r.total()
                            + "&7 " + r.produced + (r.coins > 0 ? " &6" + Math.round(r.coins) + "c" : "") + (r.full ? " &cFULL" : "")
                            + (r.lost > 0 ? " &clost " + r.lost : ""));
                }
                mod.manager.store(p).totalGenerated += total;
                say(s, "&aFast-forwarded " + list.size() + " minions by " + Text.duration(secs) + ": " + total + " items"
                        + (coins > 0 ? ", " + Math.round(coins) + " coins" : "") + ".");
            }
            case "fill" -> {
                Player p = a.length > 1 ? target(s, a[1]) : null;
                if (p == null) return;
                MinionManager.Env env = mod.manager.env(p);
                for (Placed m : select(mod, p, a.length > 2 ? a[2] : "all")) {
                    TypeDef t = cat.type(m.type);
                    if (t == null || t.drops.isEmpty()) continue;
                    String id = t.drops.get(0).item;
                    MinionSim.addToStorage(m, id, 64 * MinionSim.capacity(cat, m), MinionSim.capacity(cat, m), env);
                }
                say(s, "&aFilled.");
            }
            case "clear" -> {
                Player p = a.length > 1 ? target(s, a[1]) : null;
                if (p == null) return;
                for (Placed m : select(mod, p, a.length > 2 ? a[2] : "all")) { m.storage.clear(); m.heldCoins = 0; m.frac.clear(); m.progress = 0; }
                say(s, "&aCleared storage.");
            }
            case "collect" -> {
                Player p = a.length > 1 ? target(s, a[1]) : null;
                if (p == null) return;
                int n = 0;
                for (Placed m : select(mod, p, a.length > 2 ? a[2] : "all")) n += mod.manager.collectAll(p, m, true);
                say(s, "&aCollected " + n + " items for " + p.getName() + ".");
            }
            case "pickup" -> {
                Player p = a.length > 1 ? target(s, a[1]) : null;
                if (p == null) return;
                int n = 0;
                for (Placed m : select(mod, p, a.length > 2 ? a[2] : "all")) if (mod.manager.pickup(p, m.id, true)) n++;
                say(s, "&aPicked up " + n + " minions.");
            }
            case "fuel" -> {
                if (a.length < 4) { say(s, "&cUsage: fuel <player> <index|all> <FUEL_ID> [n]"); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                FuelDef f = cat.fuel(a[3].toUpperCase());
                if (f == null) { say(s, "&cUnknown fuel. Fuels: " + String.join(", ", cat.fuels.keySet())); return; }
                int n = Math.max(1, Math.min(64, a.length > 4 ? parseInt(a[4], 1) : 1));
                int c = 0;
                for (Placed m : select(mod, p, a[2])) {
                    m.fuel = f.id;
                    m.fuelCount = f.infinite() ? 1 : n;
                    m.fuelLeftMs = f.infinite() ? 0 : f.seconds * 1000L;
                    c++;
                }
                say(s, "&aFuelled " + c + " minions with " + f.id + ".");
            }
            case "upgrade" -> {
                if (a.length < 4) { say(s, "&cUsage: upgrade <player> <index|all> <ITEM_ID>  (none = remove)"); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                String id = a[3].toUpperCase();
                UpgradeDef u = cat.upgrade(id);
                if (u == null && !id.equals("NONE")) { say(s, "&cUnknown upgrade."); return; }
                int c = 0;
                for (Placed m : select(mod, p, a[2])) {
                    if (id.equals("NONE")) {
                        m.upgrade1 = null; m.upgrade2 = null; m.shipping = null; m.chest = null; m.skin = null;
                        mod.manager.removeChestBlock(m);
                        mod.manager.refreshBody(m);
                        c++;
                        continue;
                    }
                    switch (u.slot) {
                        case "shipping" -> m.shipping = id;
                        case "chest" -> { m.chest = id; mod.manager.placeChestBlockNear(m, Sky.profiles().profileId(p)); }
                        case "freewill" -> m.freeWill = true;
                        case "skin" -> m.skin = id;
                        case "infusion" -> m.infused = true;
                        default -> { if (m.upgrade1 == null) m.upgrade1 = id; else m.upgrade2 = id; }
                    }
                    mod.manager.refreshBody(m);
                    c++;
                }
                say(s, "&aApplied " + id + " to " + c + " minions.");
            }
            case "crafted" -> {
                if (a.length < 3) { say(s, "&cUsage: crafted <player> add <TYPE> <tier> | all | clear | count"); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                MinionStore st = mod.manager.store(p);
                switch (a[2].toLowerCase()) {
                    case "clear" -> { st.crafted.clear(); say(s, "&aCleared uniques."); }
                    case "count" -> say(s, "&e" + st.crafted.size() + " uniques, " + mod.manager.slotsFor(p, st) + " slots.");
                    case "all" -> {
                        for (TypeDef t : cat.types()) for (TierDef td : t.tiers) st.crafted.add(MinionStore.uniqueKey(t.id, td.tier));
                        say(s, "&aMarked every tier crafted: " + st.crafted.size() + " uniques, " + mod.manager.slotsFor(p, st) + " slots.");
                    }
                    case "add" -> {
                        if (a.length < 5) { say(s, "&cUsage: crafted <player> add <TYPE> <tier>"); return; }
                        mod.manager.markCrafted(p, a[3].toUpperCase(), parseInt(a[4], 1), true);
                        say(s, "&a" + st.crafted.size() + " uniques.");
                    }
                    default -> say(s, "&cUnknown option.");
                }
            }
            case "beacon" -> {
                if (a.length < 3) { say(s, "&cUsage: beacon <player> power <hours> [scorched] | clear | stat <STAT> | info"); return; }
                Player p = target(s, a[1]);
                if (p == null) return;
                MinionStore st = mod.manager.store(p);
                MinionStore.Crystal b = mod.manager.beacon(st);
                long now = System.currentTimeMillis();
                switch (a[2].toLowerCase()) {
                    case "power" -> {
                        double h = a.length > 3 ? parseD(a[3], 48) : 48;
                        st.beaconPoweredUntil = now + (long) (h * 3_600_000L);
                        st.beaconScorched = a.length > 4 && a[4].equalsIgnoreCase("scorched");
                        say(s, "&aBeacon power set to " + h + " h" + (st.beaconScorched ? " (scorched)" : "") + ".");
                    }
                    case "clear" -> { st.beaconPoweredUntil = 0; st.beaconScorched = false; say(s, "&aBeacon power cleared."); }
                    case "stat" -> {
                        String want = a.length > 3 ? a[3].toUpperCase() : "";
                        com.skyclone.api.stat.Stat pick = null;
                        for (com.skyclone.api.stat.Stat x : MinionManager.BEACON_STATS) if (x.name().equals(want)) pick = x;
                        if (pick == null && !want.equals("NONE")) {
                            StringBuilder opts = new StringBuilder();
                            for (com.skyclone.api.stat.Stat x : MinionManager.BEACON_STATS) opts.append(x.name()).append(' ');
                            say(s, "&cUsage: beacon <player> stat <" + opts.toString().trim().replace(' ', '|') + "|NONE>");
                            return;
                        }
                        st.beaconStat = pick == null ? null : pick.name();
                        Sky.stats().invalidate(p);
                        say(s, "&aBeacon stat: " + (pick == null ? "none" : pick.name()) + " &7(now +"
                                + mod.manager.beaconStatValue(st, now) + ")");
                    }
                    default -> say(s, "&eBeacon: " + (b == null ? "&cnone placed" : "&a" + b.item + " @" + b.x + "," + b.y + "," + b.z)
                            + " &7power " + (st.beaconPoweredUntil > now ? Text.duration((st.beaconPoweredUntil - now) / 1000) : "none")
                            + (st.beaconScorched ? " &6scorched" : "")
                            + " &7stat " + (st.beaconStat == null ? "none" : st.beaconStat + " +" + mod.manager.beaconStatValue(st, now)));
                }
            }
            case "seat" -> {
                // wave 5: toggle the Minion Chair without spending a chair (ignores the cap when standing up)
                Player p = a.length > 2 ? target(s, a[1]) : null;
                if (p == null) { say(s, "&cUsage: seat <player> <index|all>"); return; }
                int n = 0;
                for (Placed m : select(mod, p, a[2])) {
                    m.seated = !m.seated;
                    m.chair = null;     // no item was spent, so standing up hands nothing back
                    m.lastUpdate = System.currentTimeMillis();
                    if (m.seated) mod.visuals.removeMobs(m.id);
                    mod.manager.refreshBody(m);
                    say(s, "&7 " + m.type + " " + Text.roman(m.tier) + ": " + (m.seated ? "&eseated" : "&astanding"));
                    n++;
                }
                MinionStore st = mod.manager.store(p);
                say(s, "&aToggled " + n + ". Working " + st.working() + "/" + mod.manager.slotsFor(p, st) + ", seated " + (st.placed.size() - st.working()) + ".");
            }
            case "freewill" -> {
                // wave 5: force an outcome (loyal | leave) or roll it like the item does (roll), without the item
                Player p = a.length > 3 ? target(s, a[1]) : null;
                if (p == null) { say(s, "&cUsage: freewill <player> <index> <loyal|leave|roll|clear>"); return; }
                List<Placed> l = select(mod, p, a[2]);
                if (l.isEmpty()) { say(s, "&cNo such minion."); return; }
                Placed m = l.get(0);
                switch (a[3].toLowerCase()) {
                    case "loyal" -> { m.freeWill = true; say(s, "&aLoyal: +10% speed."); }
                    case "clear" -> { m.freeWill = false; say(s, "&aFree Will cleared."); }
                    case "leave", "roll" -> {
                        boolean leave = a[3].equalsIgnoreCase("leave")
                                || java.util.concurrent.ThreadLocalRandom.current().nextDouble(100) >= mod.manager.freeWillChance(m);
                        if (!leave) { m.freeWill = true; say(s, "&aRolled LOYAL (" + MinionText.pct(mod.manager.freeWillChance(m)) + ")."); return; }
                        mod.manager.store(p).placed.remove(m.id);
                        mod.manager.dismantle(p, m, cat.type(m.type), false);
                        if (Sky.items().exists(MinionManager.POSTCARD)) Sky.loot().give(p, Sky.items().create(MinionManager.POSTCARD), LootSource.ADMIN);
                        say(s, "&cThe minion LEFT: its contents and a Postcard went to " + p.getName() + ".");
                    }
                    default -> say(s, "&cUsage: freewill <player> <index> <loyal|leave|roll|clear>");
                }
            }
            case "chest" -> {
                // wave 5: put the attached storage chest next to the minion as a real block (or report why not)
                Player p = a.length > 2 ? target(s, a[1]) : null;
                if (p == null) { say(s, "&cUsage: chest <player> <index|all> [remove]"); return; }
                boolean remove = a.length > 3 && a[3].equalsIgnoreCase("remove");
                for (Placed m : select(mod, p, a[2])) {
                    if (remove) { mod.manager.removeChestBlock(m); mod.manager.refreshBody(m); say(s, "&7 " + m.type + ": block removed (storage kept)."); continue; }
                    if (m.chest == null) { say(s, "&7 " + m.type + ": no storage chest attached."); continue; }
                    if (m.chestBlock != null) { say(s, "&7 " + m.type + ": already at " + MinionVisuals.unpackX(m.chestBlock) + "," + MinionVisuals.unpackY(m.chestBlock) + "," + MinionVisuals.unpackZ(m.chestBlock)); continue; }
                    boolean ok = mod.manager.placeChestBlockNear(m, Sky.profiles().profileId(p));
                    mod.manager.refreshBody(m);
                    say(s, "&7 " + m.type + ": " + (ok ? "&aplaced" : "&cno free touching cell (or chunk unloaded)"));
                }
            }
            case "present" -> {
                // wave 5: the Winter Crystal's yearly present (spawn = as if a new SkyBlock year began)
                Player p = a.length > 1 ? target(s, a[1]) : null;
                if (p == null) { say(s, "&cUsage: present <player> [spawn|clear|info]"); return; }
                MinionStore st = mod.manager.store(p);
                String pid = Sky.profiles().profileId(p);
                String what = a.length > 2 ? a[2].toLowerCase() : "info";
                switch (what) {
                    case "spawn" -> {
                        mod.manager.removePresent(pid);
                        st.presentWorld = null;
                        st.presentYear = Integer.MIN_VALUE;
                        MinionManager.Prof pr = mod.manager.prof(pid);
                        if (pr != null) mod.manager.ensurePresent(pr, st);
                        say(s, st.presentWorld == null ? "&cNo present: the island needs a placed Winter Crystal with a free spot near it (chunk loaded)."
                                : "&aPresent at " + MinionVisuals.unpackX(st.presentAt) + "," + MinionVisuals.unpackY(st.presentAt) + "," + MinionVisuals.unpackZ(st.presentAt));
                    }
                    case "clear" -> {
                        st.presentWorld = null;
                        st.presentAt = 0;
                        mod.manager.removePresent(pid);
                        say(s, "&aPresent cleared (this year's stays used: year " + st.presentYear + ").");
                    }
                    default -> say(s, "&ePresent: " + (st.presentWorld == null ? "&7none waiting" : "&a" + st.presentWorld + " "
                            + MinionVisuals.unpackX(st.presentAt) + "," + MinionVisuals.unpackY(st.presentAt) + "," + MinionVisuals.unpackZ(st.presentAt))
                            + " &7last year " + st.presentYear + ", now " + Sky.calendar().now().year()
                            + ", contents " + mod.dials.winterPresentAmount.get() + "x " + mod.dials.winterPresentItem.get());
                }
            }
            case "slots" -> {
                Player p = a.length > 1 ? target(s, a[1]) : null;
                if (p == null) return;
                MinionStore st = mod.manager.store(p);
                say(s, "&e" + p.getName() + ": " + mod.manager.slotsFor(p, st) + " slots (" + st.crafted.size() + " uniques, community +"
                        + mod.manager.communitySlots(p) + ")");
            }
            case "open" -> {
                Player p = a.length > 1 ? target(s, a[1]) : null;
                if (p == null) return;
                String what = a.length > 2 ? a[2] : "";
                switch (what) {
                    case "crafted" -> mod.menus.openCrafted(p, "ALL", 0);
                    case "slots" -> mod.menus.openSlots(p);
                    case "gear" -> mod.menus.openGear(p, 0);
                    case "crystals" -> mod.menus.openCrystals(p);
                    case "" -> mod.menus.openOverview(p, 0);
                    default -> {
                        List<Placed> l = select(mod, p, what);
                        if (l.isEmpty()) { say(s, "&cNo such minion."); return; }
                        mod.menus.openMinion(p, l.get(0).id);
                    }
                }
                say(s, "&aOpened.");
            }
            default -> say(s, "&cUnknown sub-command. /sb minions for help.");
        }
    }

    static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }

    static double parseD(String s, double def) { try { return Double.parseDouble(s); } catch (Exception e) { return def; } }
}
