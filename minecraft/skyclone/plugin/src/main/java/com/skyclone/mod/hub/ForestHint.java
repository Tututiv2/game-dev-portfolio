package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Says where the choppable trees are when a player swings at a decorative one.
 *
 * <p>Wave-3 playtest: the trees round the Portal Plaza look exactly like the Forest's, but only trees in
 * the hub's {@code forest} area belong to foraging. Core now says "You can't break blocks here!", which
 * explains the refusal and not the way forward, and the objective "Chop 20 logs in the Hub's Forest"
 * gives no direction either. So a refused log OUTSIDE the forest area (checked a tick later: the block
 * must still be there, which is how a refusal differs from a chop when foraging handles more than the
 * Forest) gets one line naming the Forest's distance and direction, and the compass points at it.
 */
final class ForestHint implements Listener {

    static final long EVERY_MS = 30_000L;
    private static final Map<UUID, Long> told = new HashMap<>();

    static boolean isLog(Material m) {
        String n = m.name();
        return n.endsWith("_LOG") || n.endsWith("_WOOD");
    }

    /** The point of {@code b} nearest to (x, z), on the ground plane. Pure. */
    static double[] nearestXZ(Box b, double x, double z) {
        double nx = Math.max(b.minX(), Math.min(b.maxX() + 1, x));
        double nz = Math.max(b.minZ(), Math.min(b.maxZ() + 1, z));
        return new double[]{nx, nz};
    }

    /** The hint for a player at (x, z), given the forest area. Pure. */
    static String hint(Box forest, double x, double z) {
        double[] n = nearestXZ(forest, x, z);
        double dx = n[0] - x, dz = n[1] - z;
        int dist = (int) Math.round(Math.hypot(dx, dz));
        return "&7These trees are the Hub's decoration. Only the &2Forest &7can be chopped: it is &e" + dist
                + " blocks &7to the &e" + HubMenus.compass(dx, dz) + "&7, west of the Portal Plaza. &8(Your compass points there now.)";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent e) {
        if (!e.isCancelled() || HubModule.forestHint == null || !HubModule.forestHint.get()) return;
        Block b = e.getBlock();
        Material was = b.getType();
        if (!isLog(was) || !HubModule.ISLAND.equals(Sky.worlds().islandOf(b.getWorld()))) return;
        Box forest = TargetPractice.area("forest");
        if (forest == null || forest.containsXZ(b.getX() + 0.5, b.getZ() + 0.5)) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = told.get(p.getUniqueId());
        if (last != null && now - last < EVERY_MS) return;
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline() || b.getType() != was) return;   // it was chopped after all (foraging owns more than the Forest)
            told.put(p.getUniqueId(), System.currentTimeMillis());
            Location me = p.getLocation();
            Msg.send(p, hint(forest, me.getX(), me.getZ()));
            Location at = Sky.worlds().anchor(HubModule.ISLAND, "warp.forest");
            if (at == null) {
                double[] n = nearestXZ(forest, me.getX(), me.getZ());
                at = new Location(me.getWorld(), n[0], me.getY(), n[1]);
            }
            p.setCompassTarget(at);
            Msg.sound(p, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.2f);
        });
    }

    static void forget(UUID id) { told.remove(id); }
}
