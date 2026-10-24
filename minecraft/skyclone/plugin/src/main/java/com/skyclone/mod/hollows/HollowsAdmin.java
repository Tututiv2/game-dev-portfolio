package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.hollows.gen.HollowsGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /sb hollows ...}: every sub takes a player NAME where a player is involved, so the integrator
 * can drive it over RCON.
 */
final class HollowsAdmin {

    private HollowsAdmin() {}

    static final String USAGE = "newlobby | rebuild <crystal_hollows|glacite_tunnels> | lobby [day <n>|close] | temple <p> | treasure <p> | pass <p> [hours] | crystal <p> <AMETHYST|JADE|SAPPHIRE|AMBER|TOPAZ|all>"
            + " | spawn <p> <mobId> | magmaw | queen | detector <p> | dig <p> | keepers <p> | info <p> | reset <p> | tp <p> <anchor> | souls";

    static void register(HollowsModule m) {
        Sky.commands().admin("hollows", USAGE, "Crystal Caverns / Frozen Tunnels admin tools", (s, a) -> run(m, s, a), (s, a) -> {
            if (a.length <= 1) return List.of("newlobby", "rebuild", "lobby", "temple", "treasure", "pass", "crystal", "spawn", "magmaw", "queen", "detector", "dig", "keepers", "info", "reset", "tp", "souls");
            if (a.length == 3 && a[0].equals("crystal")) return List.of("AMETHYST", "JADE", "SAPPHIRE", "AMBER", "TOPAZ", "all");
            if (a.length == 2 && a[0].equals("rebuild")) return List.of("crystal_hollows", "glacite_tunnels");
            if (a.length == 2 && a[0].equals("lobby")) return List.of("day", "close");
            return List.of();
        });
    }

    private static void say(CommandSender s, String t) { s.sendMessage(com.skyclone.api.ui.Text.c(t)); }

    private static Player player(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing player name."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null) say(s, "&cNo online player " + a[i]);
        else if (!Sky.profiles().isReady(p)) { say(s, "&c" + p.getName() + "'s profile is not ready."); return null; }
        return p;
    }

    private static void run(HollowsModule m, CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb hollows " + USAGE); return; }
        switch (a[0].toLowerCase()) {
            case "newlobby" -> {
                say(s, "&aClosing lobby #" + m.lobbies.lobby() + ": rebuilding the Crystal Caverns as lobby #" + (m.lobbies.lobby() + 1) + " (new caves, veins and ores)...");
                m.lobbies.rotate("admin", s);
            }
            case "temple" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                if (!m.temple.available()) { say(s, "&cNo inner temple on this build (generator v3+) or dial hollows.temple-parkour is off."); return; }
                if (!"crystal_hollows".equals(Sky.worlds().islandOf(p.getWorld()))) { say(s, "&c" + p.getName() + " is not in the caverns."); return; }
                m.quests.data(p).templePaidUntil = System.currentTimeMillis() + m.dials.templeRetryMinutes.get() * 60_000L;
                m.temple.enter(p);
                say(s, "&aSent " + p.getName() + " into the inner temple (key paid).");
            }
            case "pass" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                int h = 6;
                try { if (a.length > 2) h = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) { }
                HollowsData d = m.quests.data(p);
                d.passUntil = h <= 0 ? 0 : System.currentTimeMillis() + h * 3_600_000L;
                d.railPass = h > 0;
                say(s, "&a" + p.getName() + ": Caverns Pass " + (h <= 0 ? "removed" : h + "h") + ", rail pass " + d.railPass);
            }
            case "treasure" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                String t = m.quests.data(p).detectorTarget;
                if (t == null) t = m.quests.newTarget(p);
                Location l = t == null ? null : Sky.worlds().anchor("crystal_hollows", t);
                if (l == null) { say(s, "&cNo treasure target."); return; }
                var chest = l.getBlock().getRelative(0, -2, 0);
                say(s, "&7" + p.getName() + " hunts &e" + t + "&7: floor tile " + l.getBlockX() + "," + (l.getBlockY() - 1) + "," + l.getBlockZ()
                        + ", buried " + chest.getType() + (m.varnas.buried() ? "" : " &8(old build: no buried chests)") + "&7, open tiles " + m.varnas.openTiles()
                        + ", chests opened " + m.quests.data(p).chestsOpened);
            }
            case "rebuild" -> {
                String id = a.length > 1 ? a[1] : "crystal_hollows";
                if (!id.equals("crystal_hollows") && !id.equals("glacite_tunnels")) { say(s, "&cUnknown island " + id); return; }
                Sky.worlds().build(id, true, s);
                say(s, "&aRebuilding " + id + ".");
            }
            case "lobby" -> {
                if (a.length >= 3 && a[1].equalsIgnoreCase("day")) {
                    int d;
                    try { d = Integer.parseInt(a[2]); } catch (NumberFormatException e) { say(s, "&c/sb hollows lobby day <n>"); return; }
                    m.lobbies.setDay(d);
                    say(s, "&aThe lobby now counts as Day " + m.lobbies.day() + " (" + m.lobbies.stage() + ").");
                    return;
                }
                if (a.length >= 2 && a[1].equalsIgnoreCase("close")) { m.lobbies.rotate("admin", s); say(s, "&aLobby closing now."); return; }
                say(s, "&7Generator version " + new HollowsGenerator().version() + ", built: " + Sky.worlds().isBuilt("crystal_hollows")
                        + ", tunnels built: " + Sky.worlds().isBuilt("glacite_tunnels") + ", stage " + m.lobbies.stage());
                for (String line : m.lobbies.status()) say(s, line);
            }
            case "crystal" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                String which = a.length > 2 ? a[2].toUpperCase() : "all";
                int n = 0;
                for (Lore.Quest q : Lore.QUESTS)
                    if (which.equals("ALL") || q.gem().equals(which)) { m.quests.reward(p, q.gem(), "&7(admin)"); n++; }
                say(s, n == 0 ? "&cUnknown crystal " + which : "&aGave " + n + " crystal(s) to " + p.getName());
            }
            case "spawn" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) { say(s, "&c/sb hollows spawn <p> <mobId>"); return; }
                if (Sky.mobs().def(a[2]) == null) { say(s, "&cUnknown mob " + a[2]); return; }
                LivingEntity e = Sky.mobs().spawn(a[2], p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)));
                say(s, e == null ? "&cSpawn refused." : "&aSpawned " + a[2] + " near " + p.getName());
            }
            case "magmaw" -> {
                Location at = Sky.worlds().anchor("crystal_hollows", "boss.magmaw");
                if (at == null) { say(s, "&cNo boss.magmaw anchor (is the island built?)"); return; }
                if (!Sky.mobs().live("ch_magmaw").isEmpty()) { say(s, "&eMagmaw is already up."); return; }
                LivingEntity e = Sky.mobs().spawn("ch_magmaw", at);
                say(s, e == null ? "&cSpawn refused." : "&aMagmaw spawned (" + m.magmaw.hitsToKill() + " hits to kill).");
            }
            case "queen" -> {
                Location at = Sky.worlds().anchor("crystal_hollows", "boss.goblin_queen");
                if (at == null) { say(s, "&cNo boss.goblin_queen anchor (is the island built on generator v2+?)"); return; }
                if (!Sky.mobs().live(GoblinQueen.MOB).isEmpty()) { say(s, "&eThe Goblin Queen is already up."); return; }
                LivingEntity e = Sky.mobs().spawn(GoblinQueen.MOB, at);
                say(s, e == null ? "&cSpawn refused." : "&aGoblin Queen spawned in her den.");
            }
            case "detector" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                Sky.loot().give(p, Lore.METAL_DETECTOR, 1, LootSource.ADMIN);
                String t = m.quests.newTarget(p);
                Location l = t == null ? null : Sky.worlds().anchor("crystal_hollows", t);
                say(s, "&aDetector given. Target: " + t + (l == null ? "" : " @ " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ()));
            }
            case "dig" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                m.quests.dig(p);
                say(s, "&aDug a treasure for " + p.getName() + ".");
            }
            case "keepers" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                say(s, "&7Keepers helped: &a" + m.quests.data(p).keepersGiven);
            }
            case "info" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                HollowsData d = m.quests.data(p);
                say(s, "&7" + p.getName() + ": runs " + d.runs + ", keepers " + d.keepersGiven + ", target " + d.detectorTarget
                        + ", dug " + d.treasuresDug + ", chests " + d.chestsOpened + ", temple runs " + d.templeRuns + ", lobbySeen " + d.lobbySeen
                        + ", checkpoint " + m.temple.checkpointOf(p) + ", odala " + d.odalaTrades + ", templePaid " + (d.templePaidUntil > System.currentTimeMillis()));
            }
            case "reset" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                HollowsData d = m.quests.data(p);
                d.keepersGiven.clear(); d.runs.clear(); d.detectorTarget = null; d.templePaidUntil = 0; d.treasuresDug = 0; d.odalaTrades = 0; d.metGuide = false; d.templeRuns = 0; d.chestsOpened = 0;
                say(s, "&aReset hollows data of " + p.getName());
            }
            case "tp" -> {
                Player p = player(s, a, 1);
                if (p == null || a.length < 3) { say(s, "&c/sb hollows tp <p> <anchor> (anchor of the island the player is on, or of the caverns)"); return; }
                String isl = Sky.worlds().islandOf(p.getWorld());
                if (!"glacite_tunnels".equals(isl)) isl = "crystal_hollows";
                Location l = Sky.worlds().anchor(isl, a[2]);
                if (l == null) { say(s, "&cNo anchor " + a[2] + " on " + isl); return; }
                p.teleport(l);
                say(s, "&aTeleported " + p.getName() + " to " + a[2]);
            }
            case "souls" -> {
                for (String isl : List.of("crystal_hollows", "glacite_tunnels")) {
                    Layout lay = Sky.worlds().layout(isl);
                    List<String> souls = new ArrayList<>();
                    if (lay != null) for (String n : lay.anchors().keySet()) if (n.startsWith("fairy_soul")) souls.add(n);
                    say(s, "&7" + isl + ": &d" + souls.size() + " &7fairy souls");
                }
            }
            default -> say(s, "&e/sb hollows " + USAGE);
        }
    }
}
