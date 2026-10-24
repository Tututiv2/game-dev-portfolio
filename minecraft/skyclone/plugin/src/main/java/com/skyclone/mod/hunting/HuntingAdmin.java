package com.skyclone.mod.hunting;

import com.skyclone.api.Sky;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /sb hunting ... - every admin action takes a player name so it is testable over RCON. */
final class HuntingAdmin {

    private HuntingAdmin() {}

    private static final List<String> SUBS = List.of("info", "give", "capture", "level", "syphon", "reset", "spawn", "critters",
            "fuse", "safari", "charm", "salt");

    static void register() {
        Sky.commands().admin("hunting",
                "info <p> | give <p> <shard> [n] | capture <p> <shard> | level <p> <shard> <0-10> | syphon <p> <shard> | reset <p> | "
                        + "spawn <p> <shard|name|critter> | critters | fuse <shardA> <shardB> | safari <p> start|end [ticket] | charm <p> | salt <p> <salt>",
                "Hunting: shards, attributes, critters, fusion, the Safari", HuntingAdmin::run,
                (s, a) -> a.length <= 1 ? SUBS
                        : takesShard(a[0].toLowerCase(java.util.Locale.ROOT), a.length - 1) ? shardCompletions(a[a.length - 1]) : List.of());
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing player."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null || !Hunt.ready(p)) { say(s, "&cPlayer " + a[i] + " is not online (or not ready)."); return null; }
        return p;
    }

    private static ShardDef shard(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing shard."); return null; }
        ShardDef d = Hunt.cat.find(a[i]);
        if (d == null) say(s, "&cUnknown shard " + a[i] + " (id, name, attribute or code).");
        return d;
    }

    /** Tab completion for a shard argument: the names players see (lower snake), plus the ids. */
    static List<String> shardCompletions(String prefix) {
        String pre = prefix == null ? "" : prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (ShardDef d : Hunt.cat.all()) {
            String name = d.name == null ? "" : d.name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
            if (!name.isEmpty() && name.startsWith(pre)) out.add(name);
            else if (d.id.toLowerCase(java.util.Locale.ROOT).startsWith(pre)) out.add(d.id);
            if (out.size() >= 60) break;
        }
        return out;
    }

    private static boolean takesShard(String sub, int argIndex) {
        return switch (sub) {
            case "give", "capture", "level", "syphon", "spawn" -> argIndex == 2;
            case "fuse" -> argIndex == 1 || argIndex == 2;
            default -> false;
        };
    }

    private static void run(CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb hunting " + String.join("|", SUBS)); return; }
        switch (a[0].toLowerCase()) {
            case "info" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                HuntingData d = Hunt.data(p);
                AttrSnapshot snap = Hunt.snapshot(p);
                int boxed = 0;
                for (int v : d.box.values()) boxed += v;
                say(s, "&dHunting &f" + p.getName() + "&7: level " + Sky.service(SkillService.class).level(p, SkillType.HUNTING)
                        + ", box " + boxed + " shards (" + d.box.size() + " kinds), attributes " + snap.levels.size()
                        + ", caught " + d.totalCaught + ", syphoned " + d.totalSyphoned + ", fusions " + d.totalFusions
                        + ", traps " + d.traps.size() + ", charm " + HuntMath.fmt(Hunt.charm(p, Sky.worlds().islandOf(p.getWorld()), false)) + "%");
                List<String> top = new ArrayList<>();
                for (Map.Entry<String, Integer> e : snap.levels.entrySet()) top.add(e.getKey() + " " + e.getValue());
                if (!top.isEmpty()) say(s, "&7" + String.join(", ", top.subList(0, Math.min(20, top.size()))));
                if (Safari.active(p)) say(s, "&2Safari hunt: " + d.safariTicket + ", " + Safari.left(p) + "s left, caught " + d.safariCaught);
            }
            case "give" -> {
                Player p = player(s, a, 1);
                ShardDef sd = shard(s, a, 2);
                if (p == null || sd == null) return;
                int n = a.length > 3 ? parse(a[3], 1) : 1;
                Hunt.data(p).box.merge(sd.id, Math.max(1, n), Integer::sum);
                say(s, "&aGave " + n + "x " + sd.id + " to " + p.getName() + "'s Hunting Box.");
            }
            case "capture" -> {
                Player p = player(s, a, 1);
                ShardDef sd = shard(s, a, 2);
                if (p == null || sd == null) return;
                int n = Hunt.capture(p, sd, "admin", 0, 1);
                say(s, "&aCaptured " + n + "x " + sd.id + ".");
            }
            case "level" -> {
                Player p = player(s, a, 1);
                ShardDef sd = shard(s, a, 2);
                if (p == null || sd == null) return;
                int lvl = Math.max(0, Math.min(HuntMath.MAX_LEVEL, a.length > 3 ? parse(a[3], 1) : 1));
                if (lvl == 0) Hunt.data(p).syphoned.remove(sd.id);
                else Hunt.data(p).syphoned.put(sd.id, HuntMath.shardsFor(sd.rarity, lvl));
                Hunt.invalidate(p);
                say(s, "&a" + sd.attrName + " (" + sd.attr + ") set to " + lvl + " for " + p.getName() + ": "
                        + Text.strip(sd.effectText(Math.max(1, lvl), Hunt.snapshot(p).echoMult.getOrDefault(sd.attr, 1.0))));
            }
            case "syphon" -> {
                Player p = player(s, a, 1);
                ShardDef sd = shard(s, a, 2);
                if (p == null || sd == null) return;
                say(s, "&aSyphoned " + Hunt.syphon(p, sd, true) + " shards.");
            }
            case "reset" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                HuntingData d = Hunt.data(p);
                d.box.clear(); d.syphoned.clear(); d.disabled.clear(); d.caught.clear(); d.salts.clear(); d.traps.clear();
                d.totalCaught = 0; d.totalSyphoned = 0; d.totalFusions = 0;
                Hunt.invalidate(p);
                say(s, "&aReset " + p.getName() + "'s hunting data.");
            }
            case "spawn" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                // the rest of the line, so a two-word display name works too ("King Minos"); a critter mob id
                // (critter_sepialot), a shard id (SHARD_SEPIALOT) or what players see (Inklotl / SHARD_INKLOTL)
                String mob = a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)).trim() : null;
                ShardDef sd = mob == null ? null : Hunt.cat.find(mob);
                if (sd != null && sd.critterMob() != null) mob = sd.critterMob();
                else if (sd != null && Sky.mobs().def(mob) == null) {
                    say(s, "&c" + sd.name + " (" + sd.id + ") has no critter: it comes from "
                            + (sd.hasSource("mob") ? "mob kills (Charm Chance / Black Hole)"
                               : sd.hasSource("slayer") ? "T4/T5 slayer bosses (the slayer drop pool)"
                               : sd.hasSource("kuudra") ? "paid Kuudra chests" : "fusion") + ".");
                    return;
                }
                if (mob == null || Sky.mobs().def(mob) == null) {
                    say(s, "&cUnknown critter " + (mob == null ? "(none)" : mob) + " (shard id, name, code or critter mob id).");
                    return;
                }
                LivingEntity le = Critters.spawn(mob, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)), null);
                say(s, le == null ? "&cSpawn refused." : "&aSpawned " + mob + ".");
            }
            case "critters" -> {
                say(s, "&dLive critters: &f" + Critters.liveTotal() + " &7(black holes: " + Tools.activeHoles() + ")");
                for (Map.Entry<String, Integer> e : Critters.counts().entrySet()) say(s, "&7 " + e.getKey() + ": &a" + e.getValue());
            }
            case "fuse" -> {
                ShardDef x = shard(s, a, 1), y = shard(s, a, 2);
                if (x == null || y == null) return;
                List<Fusion.Output> outs = Fusion.outputs(Hunt.cat, x, y);
                say(s, "&5" + x.id + " + " + y.id + " -> " + (outs.isEmpty() ? "nothing" : ""));
                for (Fusion.Output o : outs) say(s, "&7 " + o.amount() + "x " + o.shardId() + " (" + o.kind() + ")");
            }
            case "safari" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                String op = a.length > 2 ? a[2] : "start";
                if (op.equalsIgnoreCase("end")) { Safari.end(p, "An admin ended the hunt."); say(s, "&aEnded."); return; }
                String t = a.length > 3 ? a[3].toUpperCase() : "SAFARI_TICKET_BASIC";
                var it = Sky.items().create(t);
                if (!Safari.start(p, it)) say(s, "&cCould not start (is " + p.getName() + " on the Safari island with Hunting XV?).");
                else say(s, "&aStarted a " + t + " hunt.");
            }
            case "charm" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                say(s, "&dCharm Chance: " + HuntMath.fmt(Hunt.charm(p, Sky.worlds().islandOf(p.getWorld()), false)) + "% &7(skill "
                        + HuntMath.fmt(HuntMath.skillCharm(Sky.service(SkillService.class).level(p, SkillType.HUNTING))) + ")");
            }
            case "salt" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) return;
                Salts.Salt st = Salts.get(a[2].toUpperCase());
                if (st == null) { say(s, "&cUnknown salt."); return; }
                Hunt.data(p).salts.put(st.id, System.currentTimeMillis() + (long) (st.hours * 3_600_000));
                say(s, "&aApplied " + st.id + ".");
            }
            default -> say(s, "&e/sb hunting " + String.join("|", SUBS));
        }
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
