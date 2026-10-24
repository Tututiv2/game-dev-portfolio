package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Find a Place": the caverns are a 448-block maze of rock, so the guide can point a player at any
 * landmark. Picking one starts a tracker: the action bar names the place, the distance, the compass
 * direction and how far up or down it is, and a short trail of sparks leaves the player's chest in
 * that direction. Arriving (within 6 blocks), leaving the island, or 10 minutes, clears it.
 * Fairy Grottos are deliberately not listed: finding them is the point (they hold the only Jasper).
 */
final class Waypoints {

    record Place(String label, String anchor, Material icon, String hint) {}

    static final List<Place> CAVERNS = List.of(
            new Place("&eCaverns Entrance", "spawn", Material.LANTERN, "Portals, the merchant and the guide."),
            new Place("&dCrystal Nucleus", "landmark.crystal_nucleus", Material.BEACON, "Set all five crystals here."),
            new Place("&5Jungle Temple", "structure.jungle_temple", Material.MOSSY_COBBLESTONE, "Amethyst crystal. Needs a Jungle Key."),
            new Place("&aOdala's Camp", "npc.odala", Material.TRIPWIRE_HOOK, "Trades Jungle Keys for Sludge Juice."),
            new Place("&aMines of Varnas", "structure.mines_of_varnas", Material.IRON_HOE, "Jade crystal. Bring a Metal Detector."),
            new Place("&bLost Relic City", "structure.lost_relic_city", Material.IRON_BLOCK, "Sapphire crystal. Automaton parts."),
            new Place("&bProfessor Gearhart", "npc.professor", Material.COMPARATOR, "Takes the six automaton parts."),
            new Place("&6Goblin King's Burrow", "structure.goblin_burrow", Material.GOLD_BLOCK, "Amber crystal. Trade him a Goblin Egg."),
            new Place("&6Goblin Queen's Den", "structure.goblin_queen_den", Material.TURTLE_EGG, "Amber crystal + Goblin Eggs. A boss."),
            new Place("&cEmberdeep", "structure.emberdeep", Material.MAGMA_BLOCK, "Topaz crystal. Magmaw rises here."),
            new Place("&7Gang Hideout", "structure.gang_hideout", Material.LEATHER_HELMET, "The treasure gang and their boss."),
            new Place("&6Dragon's Lair", "landmark.dragons_lair", Material.DRAGON_EGG, "A nest at the end of a long tunnel."));

    static final List<Place> TUNNELS = List.of(
            new Place("&eBase Camp", "spawn", Material.CAMPFIRE, "Warmth, portals, commissions, supplies."),
            new Place("&bThe Junction", "landmark.junction", Material.RAIL, "Where the timbered tunnels meet."),
            new Place("&7Old Lupo's Cave", "npc.old_lupo", Material.BONE, "Frozen corpses and the shafts below."),
            new Place("&bThe Frozen Depths", "structure.frozen_depths", Material.BLUE_ICE, "Rimestone guardians roam here."),
            new Place("&3Great Rimestone Lake", "landmark.lake", Material.PACKED_ICE, "Fishing on the ice."));

    private record Track(String island, String anchor, String label, long since) {}

    private final Map<UUID, Track> tracks = new HashMap<>();

    Waypoints() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 10L);
    }

    void menu(Player p) {
        String isl = Quests.island(p);
        boolean tunnels = Quests.GT.equals(isl);
        List<Place> list = tunnels ? TUNNELS : CAVERNS;
        String island = tunnels ? Quests.GT : Quests.CH;
        Menu menu = new Menu(tunnels ? "Find a Place (Tunnels)" : "Find a Place (Caverns)", 4).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        boolean here = island.equals(isl);
        for (int i = 0; i < list.size() && i < slots.length; i++) {
            Place pl = list.get(i);
            Location at = Sky.worlds().anchor(island, pl.anchor());
            String dist = !here || at == null || at.getWorld() != p.getWorld() ? "&8(go there first)"
                    : "&7" + (int) at.distance(p.getLocation()) + " blocks " + compass(p.getLocation(), at);
            Track t = tracks.get(p.getUniqueId());
            boolean on = t != null && t.anchor.equals(pl.anchor()) && t.island.equals(island);
            var icon = Icons.of(pl.icon(), pl.label(), "&7" + pl.hint(), "", dist, "",
                    on ? "&aTracking! &eClick to stop." : at == null ? "&cNot built yet." : here ? "&eClick to track!" : "&cYou are not on this island.");
            menu.set(slots[i], on ? Icons.glow(icon) : icon, c -> {
                Player pp = c.player();
                if (on) { stop(pp, "&7Tracker cleared."); menu(pp); return; }
                if (!here || at == null) { Msg.error(pp, "You need to be in the " + (tunnels ? "Frozen Tunnels" : "Crystal Caverns") + " to track that."); return; }
                tracks.put(pp.getUniqueId(), new Track(island, pl.anchor(), pl.label(), System.currentTimeMillis()));
                pp.closeInventory();
                Msg.send(pp, "&aTracking " + pl.label() + "&a. &7Follow the sparks; the action bar shows the way.");
                Msg.sound(pp, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.4f);
            });
        }
        menu.set(31, Icons.back(tunnels ? "Frozen Tunnels" : "Crystal Caverns"), c -> {
            if (tunnels) Sky.commands().run(c.player(), "tunnels"); else Sky.commands().run(c.player(), "hollows");
        });
        menu.open(p);
    }

    /** Start tracking an anchor on the player's current island (used by the boss board). */
    boolean track(Player p, String island, String anchor, String label) {
        if (!island.equals(Quests.island(p)) || Sky.worlds().anchor(island, anchor) == null) return false;
        tracks.put(p.getUniqueId(), new Track(island, anchor, label, System.currentTimeMillis()));
        Msg.send(p, "&aTracking " + label + "&a. &7Follow the sparks; the action bar shows the way.");
        return true;
    }

    void stop(Player p, String why) {
        if (tracks.remove(p.getUniqueId()) != null && why != null) Msg.send(p, why);
    }

    private void tick() {
        if (tracks.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var it = tracks.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Track t = e.getValue();
            if (p == null) { it.remove(); continue; }
            if (!t.island.equals(Quests.island(p)) || now - t.since > 600_000) { it.remove(); Msg.send(p, "&7Tracker for " + t.label + " &7cleared."); continue; }
            Location to = Sky.worlds().anchor(t.island, t.anchor);
            if (to == null || to.getWorld() != p.getWorld()) { it.remove(); continue; }
            Location from = p.getLocation();
            double d = to.distance(from);
            if (d < 6) {
                it.remove();
                Msg.send(p, "&aYou have reached " + t.label + "&a!");
                Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
                continue;
            }
            int dy = to.getBlockY() - from.getBlockY();
            String vert = Math.abs(dy) < 4 ? "" : dy > 0 ? " &8(" + dy + " up)" : " &8(" + (-dy) + " down)";
            Sky.vitals().flash(p, "&e➜ " + t.label + " &f" + (int) d + "m &7" + compass(from, to) + vert, 14);
            Vector dir = to.toVector().subtract(from.toVector());
            if (dir.lengthSquared() < 1e-6) continue;
            dir.normalize();
            Location eye = from.clone().add(0, 1.1, 0);
            for (double s = 1.5; s <= 5.0; s += 0.7)
                p.spawnParticle(Particle.END_ROD, eye.clone().add(dir.clone().multiply(s)), 1, 0, 0, 0, 0);
        }
    }

    /** 8-point compass from a to b (north = -z). */
    static String compass(Location a, Location b) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        return dx * dx + dz * dz < 4 ? "here" : compass(dx, dz);
    }

    /** Pure: for tests. */
    static String compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0) deg += 360;
        return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[(int) Math.round(deg / 45.0) % 8];
    }
}
