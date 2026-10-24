package com.skyclone.mod.mines;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.mines.gen.DeepCavernsGenerator;
import com.skyclone.mod.mines.gen.RailRoute;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Getting around the mines: the Mining Islands overview (/mines), the lift (/lift, the Lift Operators: the Deep
 * Caverns shaft and on down to The Lift in the Dwarven Mines) and the Dwarven Mines minecart network (/minecart,
 * the Ticket Masters). Since wave 5 both are real rides ({@link Rides}); a missing path or a switched-off dial
 * falls back to the old instant teleport.
 */
final class Travel {

    /** The lift's seventh stop: The Lift, at the bottom of the shaft in the Dwarven Mines. */
    static final int DWARVEN_STOP = 7;

    private final MinesModule m;

    Travel(MinesModule m) { this.m = m; }

    private MinesDials dials() { return m.dials; }

    static String islandOf(Player p) {
        try { return Sky.worlds().islandOf(p.getLocation()); } catch (Throwable t) { return null; }
    }

    // ---- overview ---------------------------------------------------------------------------------

    void openOverview(Player p) { buildOverview(p, m.data(p)).open(p); }

    Menu buildOverview(Player p, MinesData d) {
        Menu menu = new Menu("The Mines", 6).chrome();
        menu.set(4, Icons.of(Material.DIAMOND_PICKAXE, "&aThe Mines",
                "&7Three islands, each deeper and richer",
                "&7than the last: an open pit of gold,",
                "&7six cave layers down a shaft, and a",
                "&7buried dwarf city of mithril."));
        menu.set(20, island(p, Material.GOLD_ORE, "&6Gold Mine", "gold_mine",
                List.of("&7A sunlit open pit cut in five benches.", "&7Gold, iron and coal; lapis in the", "&7west tunnel. Smiths in the camp.")),
                c -> warp(c.player(), "gold_mine"));
        menu.set(22, island(p, Material.LAPIS_ORE, "&9Deep Caverns", "deep_caverns",
                List.of("&7Six cave layers, darker and richer", "&7the deeper you go. A lift runs down", "&7the middle; a haul road spirals round.")),
                c -> warp(c.player(), "deep_caverns"));
        menu.set(24, island(p, Material.PRISMARINE, "&2Dwarven Mines", "dwarven_mines",
                List.of("&7A buried city under a stone sky:", "&7mithril, titanium, commissions,", "&7the Forge and the King.")),
                c -> warp(c.player(), "dwarven_mines"));
        int visited = d == null ? 0 : d.layers.size();
        List<String> lift = new ArrayList<>(List.of("&7Ride the cage between the Deep", "&7Caverns layers you have walked", "&7into, and on down to the Dwarven", "&7Mines.", "",
                "&7Layers visited: &a" + visited + "&7/&a" + Places.layers()));
        lift.add("");
        lift.add("&eClick to view!");
        menu.set(30, Icons.of(Material.IRON_CHAIN, "&aThe Lift", lift), c -> openLift(c.player()));
        menu.set(32, Icons.of(Material.MINECART, "&aMinecart Network",
                "&7" + Places.stations().size() + " stations across the Dwarven", "&7Mines, joined by real track.", "&7Ride from any Ticket Master.", "",
                "&eClick to view!"), c -> openMinecart(c.player()));
        boolean pass = d != null && d.dwarvenPass;
        menu.set(40, Icons.of(pass ? Material.ENCHANTED_BOOK : Material.BOOK, pass ? "&aDwarven Mines Pass" : "&cDwarven Mines Pass",
                pass ? List.of("&7The delver at the bottom of the", "&7Deep Caverns has let you through.", "", "&aUnlocked")
                        : List.of("&7Talk to the delver at the bottom", "&7of the Deep Caverns (Obsidian", "&7Sanctuary) once you reach",
                        "&7Mining " + Text.roman(dials().passMiningLevel.get()) + ".", "", "&cLocked")));
        menu.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        return menu;
    }

    private org.bukkit.inventory.ItemStack island(Player p, Material mat, String name, String warp, List<String> about) {
        List<String> lore = new ArrayList<>(about);
        lore.add("");
        String req = requirementOf(warp);
        if (req != null && p != null && !Sky.requirements().check(p, req)) {
            lore.add("&cRequires " + Sky.requirements().describe(req));
            lore.add("");
            lore.add("&cLocked");
        } else lore.add("&eClick to warp!");
        return Icons.of(mat, name, lore);
    }

    private String requirementOf(String warp) {
        return switch (warp) {
            case "gold_mine" -> MinesDials.blankToNull(dials().goldRequirement.get());
            case "deep_caverns" -> MinesDials.blankToNull(dials().deepRequirement.get());
            case "dwarven_mines" -> MinesDials.blankToNull(dials().dwarvenRequirement.get());
            default -> null;
        };
    }

    private void warp(Player p, String warp) {
        p.closeInventory();
        Sky.worlds().warp(p, warp);
    }

    // ---- the lift -------------------------------------------------------------------------------------

    void openLift(Player p) { buildLift(p, m.data(p), islandOf(p)).open(p); }

    private static boolean liftIsland(String island) { return Places.DEEP.equals(island) || Places.DWARVEN.equals(island); }

    Menu buildLift(Player p, MinesData d, String island) {
        Menu menu = new Menu("The Lift", 6).chrome();
        int here = p == null ? 0 : Places.DWARVEN.equals(island) ? DWARVEN_STOP : m.currentLayer(p);
        menu.set(4, Icons.of(Material.IRON_CHAIN, "&aThe Lift",
                "&7The cage runs the whole Deep Caverns", "&7shaft and on down to the Dwarven", "&7Mines. It only stops at layers you", "&7have set foot on before."));
        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int n = 1; n <= Places.layers(); n++) {
            final int layer = n;
            boolean visited = d != null && d.visited(n);
            boolean open = visited || !dials().liftVisitedOnly.get();
            List<String> lore = new ArrayList<>();
            lore.add("&7Ores: &f" + Places.layerOres(n));
            lore.add("&7Mobs: &c" + Places.layerMobs(n));
            lore.add("");
            if (here == n) lore.add("&aYou are here!");
            else if (!liftIsland(island)) lore.add("&cOnly works in the Deep Caverns or at The Lift.");
            else if (open) lore.add("&eClick to ride!");
            else lore.add("&cYou haven't been to this layer yet!");
            Material mat = open ? Material.matchMaterial(Places.layerIcon(n)) : Material.GRAY_DYE;
            menu.set(slots[n - 1], Icons.of(mat == null ? Material.STONE : mat,
                    "&" + Places.layerColor(n) + "Layer " + n + ": " + Places.layerName(n), lore), c -> rideLift(c.player(), layer, false));
        }
        List<String> dw = new ArrayList<>(List.of("&7The shaft runs on below the Obsidian", "&7Sanctuary, down through the dark to", "&7The Lift in the Dwarven Mines.", ""));
        String req = MinesDials.blankToNull(dials().dwarvenRequirement.get());
        boolean ok = req == null || p == null || Sky.requirements().check(p, req);
        if (here == DWARVEN_STOP) dw.add("&aYou are here!");
        else if (!liftIsland(island)) dw.add("&cOnly works in the Deep Caverns or at The Lift.");
        else if (!ok) { dw.add("&cRequires " + Sky.requirements().describe(req)); dw.add(""); dw.add("&cLocked"); }
        else dw.add("&eClick to ride!");
        menu.set(31, Icons.of(ok ? Material.PRISMARINE : Material.GRAY_DYE, "&2The Lift: Dwarven Mines", dw), c -> rideLift(c.player(), DWARVEN_STOP, false));
        menu.set(22, Icons.of(Material.LADDER, "&aThe Shaft", "&7Prefer to walk? The haul road", "&7spirals around the outside of", "&7every layer, all the way down.", "",
                "&7Riding? &eSneak &7to hop out at the", "&7stop you asked for."));
        menu.set(48, Icons.back("The Mines"), c -> openOverview(c.player()));
        return menu;
    }

    /**
     * Ride the lift to layer n (1..6) or to the Dwarven Mines ({@link #DWARVEN_STOP}). {@code admin} skips the
     * island, visit, requirement and cooldown checks.
     */
    boolean rideLift(Player p, int n, boolean admin) {
        boolean toDwarven = n == DWARVEN_STOP;
        if (!toDwarven && (n < 1 || n > Places.layers())) return false;
        MinesData d = m.data(p);
        String island = islandOf(p);
        boolean fromDwarven = Places.DWARVEN.equals(island);
        if (!admin) {
            if (!liftIsland(island)) { Msg.error(p, "The lift only runs in the Deep Caverns and down to the Dwarven Mines."); return false; }
            if (fromDwarven && toDwarven) { Msg.error(p, "You are already in the Dwarven Mines!"); return false; }
            if (!fromDwarven && !toDwarven && m.currentLayer(p) == n) { Msg.error(p, "You are already on this layer!"); return false; }
            if (!toDwarven && dials().liftVisitedOnly.get() && !d.visited(n)) { Msg.error(p, "You haven't been to the " + Places.layerName(n) + " yet!"); return false; }
            String req = MinesDials.blankToNull(dials().dwarvenRequirement.get());
            if (toDwarven && req != null && !Sky.requirements().checkOrTell(p, req)) return false;
            if (!cooldownOk(p, d)) return false;
        }
        Location to = toDwarven ? Sky.worlds().anchor(Places.DWARVEN, "lift.dwarven") : Sky.worlds().anchor(Places.DEEP, "lift." + n);
        if (to == null) { Msg.error(p, "The lift is out of order (no " + (toDwarven ? "lift.dwarven" : "lift." + n) + " anchor)."); return false; }
        if (!Sky.worlds().isBuilt(Places.DEEP) || (toDwarven || fromDwarven) && !Sky.worlds().isBuilt(Places.DWARVEN)) {
            Msg.error(p, "The mines are still being built."); return false;
        }
        p.closeInventory();
        d.lastRide = System.currentTimeMillis();
        Runnable arrive = () -> {
            p.teleport(to);
            String name = toDwarven ? "&2Dwarven Mines" : "&" + Places.layerColor(n) + Places.layerName(n);
            if (!toDwarven && Sky.profiles().isReady(p)) m.data(p).visit(n);
            Msg.sound(p, Sound.BLOCK_CHAIN_PLACE, 0.6f);
            Msg.send(p, "&7The lift rattles to a stop at the " + name + "&7.");
        };
        List<Rides.Leg> legs = (admin && !liftIsland(island)) || !dials().liftRides.get() ? null : liftLegs(p, n, fromDwarven);
        if (legs == null || !m.rides.start(p, legs, arrive)) { arrive.run(); return true; }
        Msg.sound(p, Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f);
        Msg.send(p, "&7The cage lurches into motion... &8(sneak to hop out at the stop)");
        return true;
    }

    /** The shaft legs of a lift ride, or null when a layout lacks its shaft data (rebuild pending). */
    List<Rides.Leg> liftLegs(Player p, int n, boolean fromDwarven) {
        double[] dc = shaft(Places.DEEP, "lift.shaft");
        double[] dw = shaft(Places.DWARVEN, "lift.dwarven.shaft");
        boolean toDwarven = n == DWARVEN_STOP;
        if (dc == null || (toDwarven || fromDwarven) && dw == null) return null;
        int from = fromDwarven ? DWARVEN_STOP : Math.max(1, m.currentLayer(p));
        List<Rides.Leg> legs = new ArrayList<>();
        if (from != DWARVEN_STOP && !toDwarven) {
            legs.add(new Rides.Leg(Places.DEEP, Rides.Kind.LIFT, RailRoute.vertical(dc[0], stop(from), stop(n), dc[1])));
        } else if (toDwarven) {
            legs.add(new Rides.Leg(Places.DEEP, Rides.Kind.LIFT, RailRoute.vertical(dc[0], stop(from), dc[2], dc[1])));
            legs.add(new Rides.Leg(Places.DWARVEN, Rides.Kind.LIFT, RailRoute.vertical(dw[0], dw[3], dw[2], dw[1])));
        } else {
            legs.add(new Rides.Leg(Places.DWARVEN, Rides.Kind.LIFT, RailRoute.vertical(dw[0], dw[2], dw[3], dw[1])));
            legs.add(new Rides.Leg(Places.DEEP, Rides.Kind.LIFT, RailRoute.vertical(dc[0], dc[2], stop(n), dc[1])));
        }
        for (Rides.Leg l : legs) if (Math.abs(l.pts().get(0)[1] - l.pts().get(1)[1]) < 0.5) return null;
        return legs;
    }

    /** Cage height (the floor the rider sits on) at Deep Caverns layer n. */
    static double stop(int n) { return DeepCavernsGenerator.FLOOR[n - 1] + 1; }

    /** Shaft data of an island's layout: {centre x, centre z, bottom y[, top y]} in world coordinates, or null. */
    static double[] shaft(String island, String key) {
        Layout l = Sky.worlds().layout(island);
        String v = l == null ? null : l.data().get(key);
        return parseShaft(v);
    }

    static double[] parseShaft(String v) {
        if (v == null) return null;
        String[] s = v.split(",");
        if (s.length < 3) return null;
        try {
            double[] out = new double[s.length];
            for (int i = 0; i < s.length; i++) out[i] = Double.parseDouble(s[i].trim());
            out[0] += 0.5;
            out[1] += 0.5;
            return out;
        } catch (NumberFormatException e) { return null; }
    }

    private boolean cooldownOk(Player p, MinesData d) {
        long wait = (long) (dials().rideCooldown.get() * 1000) - (System.currentTimeMillis() - d.lastRide);
        if (wait > 0) { Msg.error(p, "Wait " + Math.max(1, (wait + 999) / 1000) + "s before riding again."); return false; }
        return true;
    }

    // ---- the Dwarven Mines minecarts ------------------------------------------------------------------

    void openMinecart(Player p) { buildMinecart(p, islandOf(p)).open(p); }

    Menu buildMinecart(Player p, String island) {
        Menu menu = new Menu("Minecart Network", 6).chrome();
        menu.set(4, Icons.of(Material.MINECART, "&aMinecart Network",
                "&7The Cart & Cable Co. runs real track", "&7from its depot in the village to", "&7every district of the Dwarven Mines.",
                "&7Ride from any Ticket Master or with", "&7/minecart. &eSneak &7to hop out at", "&7your stop."));
        String near = p != null && "dwarven_mines".equals(island) ? nearestStation(p) : null;
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        List<String> st = Places.stations();
        for (int i = 0; i < st.size() && i < slots.length; i++) {
            String id = st.get(i);
            Material mat = Material.matchMaterial(Places.stationIcon(id));
            List<String> lore = new ArrayList<>();
            double dist = distanceTo(p, id);
            long fare = dist < 0 ? Places.fare(dials().minecartBase.get(), 0, 0) : Places.fare(dials().minecartBase.get(), dials().minecartPerBlock.get(), dist);
            if (dist >= 0) lore.add("&7Distance: &a" + Text.commasInt(dist) + " blocks");
            lore.add("&7Fare: " + (fare <= 0 ? "&aFree" : "&6" + Text.commasInt(fare) + " Coins"));
            lore.add("");
            if (id.equals(near)) lore.add("&aYou are here!");
            else if (!"dwarven_mines".equals(island)) lore.add("&cOnly runs inside the Dwarven Mines.");
            else lore.add("&eClick to ride!");
            menu.set(slots[i], Icons.of(mat == null ? Material.RAIL : mat, "&a" + Places.stationName(id), lore), c -> rideCart(c.player(), id, false));
        }
        menu.set(48, Icons.back("The Mines"), c -> openOverview(c.player()));
        return menu;
    }

    private static double distanceTo(Player p, String station) {
        if (p == null) return -1;
        Location to;
        try { to = Sky.worlds().anchor("dwarven_mines", "station." + station); } catch (Throwable t) { return -1; }
        if (to == null || to.getWorld() == null || !to.getWorld().equals(p.getWorld())) return -1;
        return to.distance(p.getLocation());
    }

    String nearestStation(Player p) { return nearestStation(p, 40); }

    /** The closest station within {@code max} blocks, or null. */
    String nearestStation(Player p, double max) {
        String best = null;
        double bd = max;
        for (String id : Places.stations()) {
            double dd = distanceTo(p, id);
            if (dd >= 0 && dd < bd) { bd = dd; best = id; }
        }
        return best;
    }

    /**
     * Ride a cart to a station. The cart leaves from the nearest station (any distance: /minecart from out in a
     * district walks you to the closest platform first). {@code admin} skips the island, cooldown and fare checks.
     */
    boolean rideCart(Player p, String station, boolean admin) {
        if (!Places.stations().contains(station)) return false;
        MinesData d = m.data(p);
        boolean inMines = "dwarven_mines".equals(islandOf(p));
        if (!admin) {
            if (!inMines) { Msg.error(p, "The minecarts only run inside the Dwarven Mines."); return false; }
            if (station.equals(nearestStation(p))) { Msg.error(p, "You are already at this station!"); return false; }
            if (!cooldownOk(p, d)) return false;
        }
        Location to = Sky.worlds().anchor("dwarven_mines", "station." + station);
        if (to == null) { Msg.error(p, "That line is closed (no station." + station + " anchor)."); return false; }
        if (!Sky.worlds().isBuilt("dwarven_mines")) { Msg.error(p, "The Dwarven Mines are still being built."); return false; }
        if (!admin) {
            double dist = distanceTo(p, station);
            long fare = Places.fare(dials().minecartBase.get(), dials().minecartPerBlock.get(), Math.max(0, dist));
            if (fare > 0 && !Sky.economy().withdraw(p, fare, "mines: minecart fare to " + station)) {
                Msg.error(p, "You need " + Text.commasInt(fare) + " coins for that ride.");
                return false;
            }
        }
        p.closeInventory();
        d.lastRide = System.currentTimeMillis();
        Runnable arrive = () -> {
            p.teleport(to);
            if (Sky.profiles().isReady(p)) { MinesData dd = m.data(p); if (!dd.stations.contains(station)) dd.stations.add(station); }
            Msg.sound(p, Sound.ENTITY_MINECART_RIDING, 1.2f);
            Msg.send(p, "&7The cart clatters into &a" + Places.stationName(station) + "&7.");
        };
        String from = inMines ? nearestStation(p, 1e9) : null;
        List<double[]> pts = from == null || from.equals(station) || !dials().cartRides.get() ? null : cartPath(from, station);
        if (pts == null || !m.rides.start(p, List.of(new Rides.Leg(Places.DWARVEN, Rides.Kind.CART, pts)), arrive)) { arrive.run(); return true; }
        Msg.send(p, "&7All aboard from &a" + Places.stationName(from) + "&7! &8(sneak to hop out at your stop)");
        return true;
    }

    /** The cart's wheel path from one station's buffer to another's along the laid track, or null. */
    static List<double[]> cartPath(String from, String to) {
        Layout l = Sky.worlds().layout(Places.DWARVEN);
        if (l == null) return null;
        List<int[]> cells = RailRoute.join(RailRoute.decode(l.data().get("rail." + from)), RailRoute.decode(l.data().get("rail." + to)));
        if (cells == null || cells.size() < 2 || !RailRoute.contiguous(cells)) return null;
        return RailRoute.waypoints(cells);
    }
}
