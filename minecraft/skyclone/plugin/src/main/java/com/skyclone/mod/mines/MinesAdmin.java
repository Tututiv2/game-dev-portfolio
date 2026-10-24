package com.skyclone.mod.mines;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /sb mines ... - admin tools to exercise the module over RCON (every sub that changes a player takes a name). */
final class MinesAdmin {

    private MinesAdmin() {}

    private static final List<String> SUBS = List.of("info", "layer", "ride", "lift", "rides", "endride", "unlock", "pass", "hollowspass", "reset", "souls", "status");

    static void register(MinesModule m) {
        Sky.commands().admin("mines", "<info|status <p>|layer <p> <n>|ride <p> <station>|lift <p> <1-6|dwarven>|rides|endride <p> [arrive]|unlock <p>|pass <p> <on|off>|hollowspass <p> <on|off>|reset <p>|souls <island>>",
                "Mines islands: lift/minecart rides, layer + pass unlocks, layout info", (s, a) -> run(m, s, a),
                (s, a) -> {
                    if (a.length == 1) return SUBS;
                    if (a.length == 2 && a[0].equals("souls") || a.length == 2 && a[0].equals("info")) return Places.ISLANDS;
                    if (a.length == 3 && a[0].equals("ride")) return Places.stations();
                    if (a.length == 3 && (a[0].equals("pass") || a[0].equals("hollowspass"))) return List.of("on", "off");
                    if (a.length == 3 && a[0].equals("lift")) return List.of("1", "2", "3", "4", "5", "6", "dwarven");
                    if (a.length == 3 && a[0].equals("layer")) return List.of("1", "2", "3", "4", "5", "6");
                    return List.of();
                });
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static void run(MinesModule m, CommandSender s, String[] a) {
        if (a.length == 0) { say(s, "&e/sb mines " + String.join("|", SUBS)); return; }
        switch (a[0]) {
            case "info" -> {
                for (String island : a.length > 1 ? List.of(a[1]) : Places.ISLANDS) {
                    Layout l = Sky.worlds().layout(island);
                    if (l == null) { say(s, "&c" + island + ": no layout yet"); continue; }
                    long souls = l.anchors().keySet().stream().filter(k -> k.startsWith("fairy_soul.")).count();
                    List<String> missing = new ArrayList<>();
                    for (String an : Places.requiredAnchors(island)) if (l.anchor(an) == null) missing.add(an);
                    for (String p : Places.requiredPortals(island)) if (l.area(p) == null) missing.add(p);
                    say(s, "&6" + island + "&7: built=" + Sky.worlds().isBuilt(island) + " anchors=" + l.anchors().size()
                            + " areas=" + l.areas().size() + " zones=" + l.zones().size() + " souls=" + souls
                            + (missing.isEmpty() ? " &aall required anchors present" : " &cmissing " + missing));
                }
            }
            case "status" -> {
                if (a.length < 2) { say(s, "&c/sb mines status <player>"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                MinesData d = m.data(p);
                say(s, "&6" + p.getName() + "&7: layers " + d.layers + ", pass " + d.dwarvenPass + ", stations " + d.stations
                        + ", on layer " + m.currentLayer(p) + ", island " + Travel.islandOf(p) + ", riding " + Rides.riding(p)
                        + ", hollows pass " + d.hollowsPass(System.currentTimeMillis()));
            }
            case "layer" -> {
                if (a.length < 3) { say(s, "&c/sb mines layer <player> <1-6>"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                int n = MinesModule.parseInt(a[2]);
                if (n < 1 || n > Places.layers()) { say(s, "&cLayer 1-" + Places.layers()); return; }
                if (!Places.DEEP.equals(Travel.islandOf(p))) {
                    Location to = Sky.worlds().anchor(Places.DEEP, "lift." + n);
                    if (to == null || !Sky.worlds().isBuilt(Places.DEEP)) { say(s, "&cThe Deep Caverns are not built yet"); return; }
                    p.teleport(to);
                    m.data(p).visit(n);
                    say(s, "&aSent " + p.getName() + " to layer " + n);
                    return;
                }
                say(s, m.travel.rideLift(p, n, true) ? "&aSent " + p.getName() + " to layer " + n : "&cCould not ride");
            }
            case "ride" -> {
                if (a.length < 3) { say(s, "&c/sb mines ride <player> <station>"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                if (!Places.stations().contains(a[2])) { say(s, "&cStations: " + Places.stations()); return; }
                say(s, m.travel.rideCart(p, a[2], true) ? "&aSent " + p.getName() + " to " + a[2] : "&cCould not ride");
            }
            case "lift" -> {
                if (a.length < 3) { say(s, "&c/sb mines lift <player> <1-6|dwarven>"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                int n = MinesModule.liftStop(a[2]);
                say(s, m.travel.rideLift(p, n, true) ? "&aLift ride started for " + p.getName() : "&cCould not ride (stop 1-6 or dwarven)");
            }
            case "rides" -> {
                say(s, "&6" + m.rides.active() + " ride(s) moving");
                for (String line : m.rides.describe()) say(s, "&7 " + line);
            }
            case "endride" -> {
                if (a.length < 2) { say(s, "&c/sb mines endride <player> [arrive]"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                boolean arrive = a.length > 2 && a[2].equalsIgnoreCase("arrive");
                m.rides.stop(p, arrive);
                say(s, "&aEnded " + p.getName() + "'s ride" + (arrive ? " at its destination" : " where it was"));
            }
            case "hollowspass" -> {
                if (a.length < 3) { say(s, "&c/sb mines hollowspass <player> <on|off>"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                boolean on = a[2].equalsIgnoreCase("on") || a[2].equalsIgnoreCase("true");
                m.data(p).hollowsPassUntil = on ? System.currentTimeMillis() + (long) (m.dials.hollowsPassHours.get() * 3_600_000L) : 0;
                Sky.profiles().saveNow(p);
                say(s, "&aCrystal Caverns Pass for " + p.getName() + ": " + on);
            }
            case "unlock" -> {
                if (a.length < 2) { say(s, "&c/sb mines unlock <player>"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                MinesData d = m.data(p);
                for (int n = 1; n <= Places.layers(); n++) d.visit(n);
                d.dwarvenPass = true;
                Sky.profiles().saveNow(p);
                say(s, "&aUnlocked every layer and the Dwarven Mines pass for " + p.getName());
            }
            case "pass" -> {
                if (a.length < 3) { say(s, "&c/sb mines pass <player> <on|off>"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                m.data(p).dwarvenPass = a[2].equalsIgnoreCase("on") || a[2].equalsIgnoreCase("true");
                Sky.profiles().saveNow(p);
                say(s, "&aPass for " + p.getName() + ": " + m.data(p).dwarvenPass);
            }
            case "reset" -> {
                if (a.length < 2) { say(s, "&c/sb mines reset <player>"); return; }
                Player p = MinesModule.player(s, a[1]);
                if (p == null) return;
                MinesData d = m.data(p);
                d.layers.clear(); d.stations.clear(); d.dwarvenPass = false; d.lastRide = 0; d.hollowsPassUntil = 0;
                Sky.profiles().saveNow(p);
                say(s, "&aReset mines progress for " + p.getName());
            }
            case "souls" -> {
                String island = a.length > 1 ? a[1] : Places.DEEP;
                Map<String, Location> souls = MinesModule.anchorsOf(island, "fairy_soul.");
                say(s, "&6" + island + "&7: " + souls.size() + " fairy souls");
                for (Map.Entry<String, Location> e : souls.entrySet()) {
                    Location l = e.getValue();
                    if (l != null) say(s, "&7 " + e.getKey() + " &f" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
                }
            }
            default -> say(s, "&e/sb mines " + String.join("|", SUBS));
        }
    }
}
