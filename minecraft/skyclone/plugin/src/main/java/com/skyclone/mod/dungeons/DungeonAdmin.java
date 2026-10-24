package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.ext.Bosses;
import com.skyclone.mod.dungeons.gen.DefaultArena;
import com.skyclone.mod.dungeons.gen.DungeonPlan;
import com.skyclone.mod.dungeons.gen.PlanGenerator;
import com.skyclone.mod.dungeons.gen.RunBlueprint;
import com.skyclone.mod.dungeons.gen.RunBuilder;
import com.skyclone.mod.dungeons.model.CataMath;
import com.skyclone.mod.dungeons.model.DClass;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** {@code /sb dungeons ...}: everything needed to test the framework over RCON or in game. */
final class DungeonAdmin {

    private DungeonAdmin() {}

    static void register() {
        Sky.commands().admin("dungeons", "<start|end|skip|xp|classxp|level|class|runs|plan|info|reset|kit|keys|boss|chests|loot|rng|perk|gear|phase|balance|perks|secrets> ...",
                "Dungeons admin: start <p> <floor> [force] | end <p> [complete|fail] | skip <p> <keys|clear|blood|boss> | xp <p> <n> | "
                        + "classxp <p> <class> <n> | level <p> <lvl> | class <p> <class> | runs | plan <floor> [seed] | info <p> | reset <p> | kit <p> | chests <p> <floor> [score] | loot <floor> <chest> [n] | rng <p> <floor> <xp> | perk <p> <perk> <lvl> | gear <p> <set|scrolls|keys> | phase <p> | balance <floor> [hp def] | perks <p> | secrets <p> <n>",
                DungeonAdmin::run,
                (s, a) -> a.length <= 1 ? List.of("start", "end", "skip", "xp", "classxp", "level", "class", "runs", "plan", "info", "reset", "kit", "keys", "boss", "chests", "loot", "rng", "perk", "gear", "phase", "balance", "perks", "secrets")
                        : a.length == 2 && a[0].equals("balance") ? floorIds()
                        : a.length == 2 && !a[0].equals("plan") && !a[0].equals("runs") ? names()
                        : a.length == 3 && (a[0].equals("start")) ? floorIds()
                        : a.length == 3 && a[0].equals("skip") ? List.of("keys", "clear", "blood", "boss")
                        : a.length == 3 && (a[0].equals("class") || a[0].equals("classxp")) ? classKeys() : List.of());
    }

    private static List<String> names() { List<String> o = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) o.add(p.getName()); return o; }

    private static List<String> floorIds() { List<String> o = new ArrayList<>(); for (FloorDef f : Floors.all()) o.add(f.id); return o; }

    private static List<String> classKeys() { List<String> o = new ArrayList<>(); for (DClass c : DClass.values()) o.add(c.key()); return o; }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String[] a, int i) {
        Player p = a.length > i ? Bukkit.getPlayerExact(a[i]) : (s instanceof Player sp ? sp : null);
        if (p == null) say(s, "&cPlayer not found.");
        else if (!Sky.profiles().isReady(p)) { say(s, "&c" + p.getName() + "'s profile is not ready."); return null; }
        return p;
    }

    static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&eUsage: /sb dungeons <start|end|skip|xp|classxp|level|class|runs|plan|info|reset|kit>"); return; }
        switch (a[0].toLowerCase()) {
            case "start" -> {
                Player p = player(s, a, 1);
                FloorDef f = a.length > 2 ? Floors.get(a[2]) : Floors.get("E");
                if (p == null || f == null) { if (f == null) say(s, "&cUnknown floor."); return; }
                boolean force = a.length > 3 && a[3].equalsIgnoreCase("force");
                String err = RunManager.get().start(p, f, force);
                say(s, err == null ? "&aStarting " + f.display() + " for " + p.getName() + "'s party." : "&c" + err);
            }
            case "end" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                DungeonRun r = RunManager.get().runOf(p);
                if (r == null) { say(s, "&cNot in a run."); return; }
                String how = a.length > 2 ? a[2] : "fail";
                if (how.equals("complete")) r.complete(); else if (how.equals("close")) RunManager.get().close(r); else r.fail("Ended by an admin");
                say(s, "&aRun " + r.id + " -> " + how);
            }
            case "skip" -> skip(s, a);
            case "boss" -> { String[] b = {"skip", a.length > 1 ? a[1] : "", "boss"}; skip(s, b); }
            case "keys" -> { String[] b = {"skip", a.length > 1 ? a[1] : "", "keys"}; skip(s, b); }
            case "xp" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) return;
                Progress.addCataXp(p, parse(a[2]));
                say(s, "&a" + p.getName() + " is now Catacombs " + Progress.cataLevel(p) + " (" + Text.commasInt(Progress.data(p).cataXp) + " XP)");
            }
            case "classxp" -> {
                Player p = player(s, a, 1);
                DClass c = a.length > 2 ? DClass.parse(a[2]) : null;
                if (p == null || c == null || a.length < 4) { say(s, "&cUsage: classxp <p> <class> <xp>"); return; }
                Progress.addClassXp(p, c, parse(a[3]));
                say(s, "&a" + p.getName() + " " + c.display + " level " + Progress.classLevel(p, c));
            }
            case "level" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) return;
                int lvl = (int) parse(a[2]);
                DData d = Progress.data(p);
                d.cataXp = CataMath.cumulative(Math.max(0, lvl));
                for (DClass c : DClass.values()) d.classXp.put(c.key(), (double) CataMath.cumulative(Math.max(0, lvl)));
                say(s, "&aSet " + p.getName() + " to Catacombs and class level " + lvl);
            }
            case "class" -> {
                Player p = player(s, a, 1);
                DClass c = a.length > 2 ? DClass.parse(a[2]) : null;
                if (p == null || c == null) { say(s, "&cUsage: class <p> <healer|mage|berserk|archer|tank>"); return; }
                Progress.data(p).selectedClass = c.key();
                say(s, "&a" + p.getName() + " is now a " + c.display);
            }
            case "runs" -> {
                say(s, "&e" + RunManager.get().runs().size() + " run(s):");
                for (DungeonRun r : RunManager.get().runs()) {
                    StringBuilder who = new StringBuilder();
                    for (DungeonRun.Member m : r.members.values()) who.append(m.name).append(m.ghost ? "(ghost)" : "").append(' ');
                    say(s, " &7" + r.id + " &c" + r.floor.id + " &e" + r.state + " &7" + Text.duration(r.elapsedSeconds()) + " &b" + who
                            + (r.bp != null ? "&7 rooms " + r.clearedPercent() + "% secrets " + r.secretsFound + "/" + r.bp.totalSecrets() : ""));
                }
            }
            case "plan" -> {
                FloorDef f = a.length > 1 ? Floors.get(a[1]) : Floors.get("F1");
                if (f == null) { say(s, "&cUnknown floor."); return; }
                long seed = a.length > 2 ? (long) parse(a[2]) : System.nanoTime();
                long t0 = System.currentTimeMillis();
                DungeonPlan plan = PlanGenerator.generate(f, seed);
                RunBlueprint bp = RunBuilder.build(plan, f, f.isEntrance() ? null : Bosses.forFloor(f) == null ? DefaultArena.INSTANCE : Bosses.forFloor(f).arena());
                say(s, "&e" + f.display() + " seed " + seed + ": " + plan.rooms.size() + " rooms, " + plan.witherDoors + " wither doors, "
                        + bp.totalSecrets() + " secrets, " + bp.totalCrypts() + " crypts, " + Text.commasInt(bp.canvas.blockCount()) + " blocks, "
                        + (System.currentTimeMillis() - t0) + "ms (attempt " + plan.attempts + ")");
                for (String line : plan.ascii()) say(s, "&7" + line.replace(' ', '.'));
            }
            case "info" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                DData d = Progress.data(p);
                say(s, "&e" + p.getName() + ": Catacombs " + Progress.cataLevel(p) + " (" + Text.commasInt(d.cataXp) + " XP), class " + Progress.selected(p).display);
                for (DClass c : DClass.values()) say(s, "  &7" + c.display + " " + Progress.classLevel(p, c));
                say(s, "  &7completions " + d.completions + " best " + d.bestScore);
                DungeonRun r = RunManager.get().runOf(p);
                if (r != null) say(s, "  &7in run " + r.id + " (" + r.state + ")");
            }
            case "reset" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                DData d = Progress.data(p);
                d.cataXp = 0; d.classXp.clear(); d.completions.clear(); d.bestScore.clear(); d.fastest.clear();
                say(s, "&aReset dungeon progress of " + p.getName());
            }
            case "kit" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                for (String id : List.of("SPIRIT_LEAP", "SUPERBOOM_TNT", "DUNGEON_DECOY", "REVIVE_STONE", "POTION_DUNGEON_3"))
                    if (Sky.items().exists(id)) Sky.loot().give(p, id, id.equals("REVIVE_STONE") || id.startsWith("POTION") ? 1 : 8, LootSource.ADMIN);
                say(s, "&aGave the dungeon test kit to " + p.getName());
            }
            case "balance", "dmg" -> balance(s, a);
            case "perks", "w5" -> perks(s, a);
            case "secrets" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) { say(s, "&cUsage: secrets <p> <lifetime count>"); return; }
                Progress.data(p).totalSecrets = (int) Math.max(0, parse(a[2]));
                Sky.stats().invalidate(p);
                say(s, "&a" + p.getName() + " has " + Progress.data(p).totalSecrets + " lifetime secrets (General's Medallion +"
                        + com.skyclone.mod.dungeons.model.DungeonPerks.medallionPercent(Progress.data(p).totalSecrets) + "%).");
            }
            default -> { if (!PartB.admin(s, a)) say(s, "&cUnknown sub-command."); }
        }
    }

    /**
     * {@code /sb dungeons perks <p>} (wave 5): every dungeon accessory / pet / star effect as it applies to p right now -
     * Master Mode, General's Medallion, the Bone Necklace's Diversion reach, the held item's in-run boost, the pet perks
     * this module reads, and the starred weapons registered at boot.
     */
    private static void perks(CommandSender s, String[] a) {
        Player p = player(s, a, 1);
        if (p == null) return;
        DungeonRun r = RunManager.get().runOf(p);
        say(s, "&e" + p.getName() + " &7- run: " + (r == null ? "&8none" : "&a" + r.floor.display()) + " &7| Master Mode: "
                + (Progress.INSTANCE.masterMode(p) ? "&ayes" : "&cno") + " &7| lifetime secrets: &b" + Progress.data(p).totalSecrets);
        say(s, "&7General's Medallion: &a+" + com.skyclone.mod.dungeons.classes.ClassSystem.medallion(p) + "% &7| Diversion reach: &a"
                + com.skyclone.mod.dungeons.classes.ClassSystem.diversionRange(p) + " &7blocks");
        org.bukkit.inventory.ItemStack held = p.getInventory().getItemInMainHand();
        String hid = Sky.items().idOf(held);
        if (hid != null) say(s, "&7Held &f" + hid + "&7: stars " + Sky.items().data(held).stars + ", in-run boost &a+"
                + Text.commas(com.skyclone.mod.dungeons.classes.ClassSystem.boostFraction(p, held) * 100) + "% &8(0 outside a run's dungeon item)");
        say(s, "&7Pet perks: Strong Brew &a" + com.skyclone.mod.dungeons.classes.ClassSystem.petValue(p, "jellyfish_powerful_potions", "a")
                + "% &7| Stored Charge &a" + com.skyclone.mod.dungeons.classes.ClassSystem.petValue(p, "jellyfish_stored_energy", "a")
                + "s &7| Restless &a" + com.skyclone.mod.dungeons.classes.ClassSystem.petValue(p, "spirit_spirit_cooldowns", "a") + "%");
        if (r != null && r.member(p) != null) {
            DungeonRun.Member m = r.member(p);
            say(s, "&7Class " + m.cls.display + " " + m.classLevel + " &7| ghost " + m.ghost + " | healed since Wish &a"
                    + Text.commasInt(m.healedSinceWish) + " &7(trimmed " + Text.commas(m.wishTrimmed) + "s) | tether "
                    + (m.tether == null ? "-" : Bukkit.getOfflinePlayer(m.tether).getName()));
        }
        say(s, "&7Starred weapons: &a" + com.skyclone.mod.dungeons.gear.StarredWeapons.REGISTERED.size() + " &7registered"
                + (com.skyclone.mod.dungeons.gear.StarredWeapons.SKIPPED.isEmpty() ? "" : ", skipped " + com.skyclone.mod.dungeons.gear.StarredWeapons.SKIPPED));
    }

    /**
     * {@code /sb dungeons balance <floor> [hp def]}: what every damage source of a floor takes off a player at the
     * CURRENT dials (fixer, integrate-w2-1: the F1 balance complaint could only be argued in the client before). With
     * no hp/def a player sender is measured off their own stat sheet; the two reference players are always shown.
     */
    private static void balance(CommandSender s, String[] a) {
        FloorDef f = a.length > 1 ? Floors.get(a[1]) : null;
        if (f == null) { say(s, "&cUsage: /sb dungeons balance <floor> [hp def]"); return; }
        List<com.skyclone.mod.dungeons.model.Balance.Ref> refs = new ArrayList<>();
        if (a.length > 3) refs.add(new com.skyclone.mod.dungeons.model.Balance.Ref("given", parse(a[2]), parse(a[3])));
        else if (s instanceof Player p && Sky.profiles().isReady(p))
            refs.add(new com.skyclone.mod.dungeons.model.Balance.Ref(p.getName() + " (now)",
                    Sky.stats().get(p, com.skyclone.api.stat.Stat.HEALTH), Sky.stats().get(p, com.skyclone.api.stat.Stat.DEFENSE)));
        refs.add(com.skyclone.mod.dungeons.model.Balance.MID);
        refs.add(com.skyclone.mod.dungeons.model.Balance.ENTRY);
        double mobDial = DDials.MOB_DMG_SCALE.get(), bossDial = DDials.BOSS_FIGHT_DMG.get(), share = DDials.BOSS_MELEE_SHARE.get();
        say(s, "&6" + f.display() + " &7damage at the current dials &8(mob-damage-scale " + mobDial + ", boss-damage-scale " + bossDial
                + ", boss-melee-share " + share + ", floor dmgScale " + f.dmgScale + ")");
        List<String[]> rows = new ArrayList<>();
        rows.add(worst("room mob", f.mobPool, f.dmgScale * mobDial));
        rows.add(worst("miniboss", f.minibossPool, f.dmgScale * mobDial));
        rows.add(worst("blood mob", f.bloodPool, f.dmgScale * mobDial));
        if (!f.isEntrance()) {
            rows.add(new String[]{"boss body", String.valueOf(com.skyclone.mod.dungeons.model.Balance.bossBody(f.bossDamage, share, bossDial))});
            rows.add(new String[]{"boss attack x0.6", String.valueOf(com.skyclone.mod.dungeons.model.Balance.scripted(f.bossDamage, 0.6, bossDial))});
            rows.add(new String[]{"boss attack x1.0", String.valueOf(com.skyclone.mod.dungeons.model.Balance.scripted(f.bossDamage, 1.0, bossDial))});
            // wave-4 endgame: the boss floor scales with party size; the rows above are a full party's numbers
            StringBuilder ps = new StringBuilder("&7Boss floor by party size &8(solo-boss-hp-scale " + DDials.SOLO_BOSS_HP.get()
                    + ", solo-boss-damage-scale " + DDials.SOLO_BOSS_DMG.get() + ", add contact share " + DDials.ADD_MELEE_SHARE.get() + ")&7:");
            for (int n = 1; n <= com.skyclone.mod.dungeons.model.Balance.FULL_PARTY; n++)
                ps.append(" &8| &f").append(n).append("&7: HP x").append(String.format("%.2f", com.skyclone.mod.dungeons.model.Balance.partyScale(n, DDials.SOLO_BOSS_HP.get())))
                        .append(" dmg x").append(String.format("%.2f", com.skyclone.mod.dungeons.model.Balance.partyScale(n, DDials.SOLO_BOSS_DMG.get())));
            say(s, ps.toString());
        }
        for (String[] r : rows) {
            if (r == null) continue;
            double raw = Double.parseDouble(r[1]);
            StringBuilder b = new StringBuilder("&e" + r[0] + " &7raw &f" + Text.commasInt(raw) + "&7:");
            for (com.skyclone.mod.dungeons.model.Balance.Ref ref : refs) {
                int h = com.skyclone.mod.dungeons.model.Balance.hitsToKill(raw, ref);
                b.append(" &8| &7").append(ref.label()).append(" &c").append(Text.commasInt(com.skyclone.mod.dungeons.model.Balance.landed(raw, ref.defense())))
                        .append(" &7(").append(h <= 1 ? "&4" : h <= 2 ? "&c" : "&a").append(h == Integer.MAX_VALUE ? "-" : String.valueOf(h)).append(" hits&7)");
            }
            say(s, b.toString());
        }
        // playtest-endgame: hits-to-kill read "19 hits, safe" for a miniboss that won the duel. A duel is a race in seconds.
        for (String id : f.minibossPool) {
            com.skyclone.api.mob.MobDef d = Sky.mobs().def(id);
            if (d == null) continue;
            com.skyclone.mod.dungeons.model.Balance.Pattern pt =
                    com.skyclone.mod.dungeons.model.Balance.pattern(d.behaviours.isEmpty() ? null : d.behaviours.get(0));
            double raw = d.damage * f.dmgScale * mobDial, hp = d.health * f.hpScale * DDials.MOB_HP_SCALE.get();
            StringBuilder b = new StringBuilder("&e" + Text.strip(d.name) + " &7duel &8(" + Text.commasInt(hp) + " HP)&7:");
            for (com.skyclone.mod.dungeons.model.Balance.Ref ref : refs) {
                double die = com.skyclone.mod.dungeons.model.Balance.secondsToDie(raw, pt, ref);
                b.append(" &8| &7").append(ref.label()).append(" lasts &f").append(Math.round(die)).append("s");
                if (ref.dps() > 0) {
                    double kill = com.skyclone.mod.dungeons.model.Balance.secondsToKill(hp, pt, ref);
                    b.append("&7, kills it in ").append(die >= 2 * kill ? "&a" : die >= kill ? "&e" : "&c")
                            .append(Double.isInfinite(kill) ? "never" : Math.round(kill) + "s");
                }
            }
            say(s, b.toString());
        }
        // wave-5 regression: a room is a pack fight (all of a pack swinging while each dies in turn) and scales with party size
        {
            com.skyclone.mod.dungeons.model.Balance.Ref early = com.skyclone.mod.dungeons.model.Balance.EARLY;
            double raw = 0, hp = 0;
            int n = 0;
            for (String id : f.mobPool) {
                com.skyclone.api.mob.MobDef d = Sky.mobs().def(id);
                if (d == null) continue;
                raw += d.damage; hp += d.health; n++;
            }
            if (n > 0) {
                raw = raw / n * f.dmgScale * mobDial;
                hp = hp / n * f.hpScale * DDials.MOB_HP_SCALE.get();
                StringBuilder b = new StringBuilder("&eRoom pack &7(" + f.mobsPerCell + " average mobs at once, aggro range "
                        + DDials.ROOM_AGGRO.get() + ") vs " + early.label() + "&7, health lost by party size:");
                for (int m = 1; m <= com.skyclone.mod.dungeons.model.Balance.FULL_PARTY; m++) {
                    double sd = com.skyclone.mod.dungeons.model.Balance.partyScale(m, DDials.SOLO_ROOM_DMG.get());
                    double sh = com.skyclone.mod.dungeons.model.Balance.partyScale(m, DDials.SOLO_ROOM_HP.get());
                    double t = com.skyclone.mod.dungeons.model.Balance.packTaken(f.mobsPerCell, raw * sd, hp * sh, early);
                    b.append(" &8| &f").append(m).append("&7: ").append(t <= early.health() * 0.4 ? "&a" : t < early.health() ? "&e" : "&c")
                            .append(Math.round(t)).append(" &8(HP x").append(String.format("%.2f", sh)).append(" dmg x").append(String.format("%.2f", sd)).append(")");
                }
                say(s, b.toString());
            }
        }
        say(s, "&8Tune with /sb dial set dungeons.boss-melee-share|boss-damage-scale|mob-damage-scale|solo-boss-damage-scale|solo-room-hp-scale|solo-room-damage-scale|room-aggro-range <x>. Grinko: skull x0.6, barrage x0.96 of the headline.");
    }

    /** The hardest hitter of a pool: {label (id), raw}. */
    private static String[] worst(String label, List<String> pool, double scale) {
        String best = null;
        double raw = 0;
        for (String id : pool) {
            com.skyclone.api.mob.MobDef d = Sky.mobs().def(id);
            if (d != null && d.damage * scale > raw) { raw = d.damage * scale; best = id; }
        }
        return best == null ? null : new String[]{label + " (" + best + ")", String.valueOf(raw)};
    }

    private static void skip(CommandSender s, String[] a) {
        Player p = player(s, a, 1);
        if (p == null) return;
        DungeonRun r = RunManager.get().runOf(p);
        if (r == null || r.bp == null) { say(s, "&cNot in a live run."); return; }
        String what = a.length > 2 ? a[2] : "keys";
        String notLive = r.makeLive();
        if (notLive != null) { say(s, "&c" + notLive); return; }
        switch (what) {
            case "keys" -> { r.witherKeys += 5; r.bloodKey = true; r.mapVersion++; say(s, "&aGave the party 5 Wither Keys and the Blood Key."); }
            case "clear" -> { for (DungeonRun.RoomRt rr : r.rooms) { rr.entered = true; r.clearRoom(rr); } say(s, "&aCleared every room."); }
            case "blood" -> {
                r.witherKeys += 5; r.bloodKey = true;
                for (DungeonRun.RoomRt rr : r.rooms) rr.entered = true;
                r.mapVersion++;
                p.teleportAsync(r.at(r.bp.blood().points.get("spawn")));
                say(s, "&aKeys given; teleported to the Blood room. Open its door to start the Watcher.");
            }
            case "boss" -> {
                r.forcePortal();
                if (r.floor.isEntrance()) { say(s, "&aEntrance completed."); return; }
                for (DungeonRun.Member m : r.members.values()) { Player q = m.player(); if (q != null) q.teleportAsync(r.at(r.bp.blood().points.get("portal"))); }
                say(s, "&aSent the party to the boss portal.");
            }
            default -> say(s, "&cskip keys|clear|blood|boss");
        }
    }

    static double parse(String s) {
        try {
            String t = s.trim().toLowerCase();
            double mul = t.endsWith("k") ? 1e3 : t.endsWith("m") ? 1e6 : t.endsWith("b") ? 1e9 : 1;
            if (mul != 1) t = t.substring(0, t.length() - 1);
            return Double.parseDouble(t) * mul;
        } catch (RuntimeException e) { return 0; }
    }
}
