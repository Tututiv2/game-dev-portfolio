package com.smhmines.spawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps players out of the void.
 *
 * <p>Exists because of a real first-run failure: on a fresh void world with nothing built yet,
 * joining drops you straight through the world — the server log read {@code fell out of the
 * world} four times in twenty seconds before anything could be looked at.
 *
 * <p>Two guards:
 * <ul>
 *   <li><b>On join</b>, if there is nothing solid under you, move you to the hub.</li>
 *   <li><b>On falling below the world</b>, catch you and put you back rather than killing you.</li>
 * </ul>
 *
 * <p>If the hub itself is empty (nothing has been built yet), a one-time bootstrap pad is placed
 * so there is somewhere to stand while you run {@code /smhspawn build}. That write is guarded by
 * a persisted config flag — per the project law, nothing places blocks unconditionally on enable,
 * and this only ever fires once.
 */
public final class SpawnSafetyListener implements Listener {

    private static final int PAD_RADIUS = 4;

    private final JavaPlugin plugin;

    public SpawnSafetyListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        if (hasGroundBelow(player.getLocation())) return;

        Location hub = ensureStandableHub(player.getWorld());
        player.teleport(hub);
        player.sendMessage(Component.text(
                "Nothing built here yet — dropped you on the bootstrap pad. Run ",
                NamedTextColor.GRAY)
                .append(Component.text("/smhspawn build", NamedTextColor.GOLD)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        var to = event.getTo();
        World world = to.getWorld();
        if (world == null || to.getY() > world.getMinHeight() - 8) return;
        if (event.getPlayer().isFlying()) return;

        Location hub = ensureStandableHub(world);
        event.getPlayer().teleport(hub);
        event.getPlayer().setFallDistance(0);
    }

    /** True if any non-air block sits between the location and the bottom of the world. */
    private boolean hasGroundBelow(Location loc) {
        World world = loc.getWorld();
        if (world == null) return true;
        int x = loc.getBlockX(), z = loc.getBlockZ();
        int from = Math.min(loc.getBlockY(), world.getMaxHeight() - 1);
        for (int y = from; y >= world.getMinHeight(); y--) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) return true;
        }
        return false;
    }

    /** The configured hub, with a one-time pad under it if nothing has ever been built. */
    private Location ensureStandableHub(World world) {
        var cfg = plugin.getConfig();
        Location hub = new Location(world,
                cfg.getDouble("hub.x", 0.5), cfg.getDouble("hub.y", 101.0), cfg.getDouble("hub.z", 0.5),
                (float) cfg.getDouble("hub.yaw", 0.0), (float) cfg.getDouble("hub.pitch", 0.0));

        if (hasGroundBelow(hub)) return hub;

        if (!cfg.getBoolean("bootstrap-pad-placed", false)) {
            int px = hub.getBlockX(), py = hub.getBlockY() - 1, pz = hub.getBlockZ();
            for (int dx = -PAD_RADIUS; dx <= PAD_RADIUS; dx++) {
                for (int dz = -PAD_RADIUS; dz <= PAD_RADIUS; dz++) {
                    boolean edge = Math.abs(dx) == PAD_RADIUS || Math.abs(dz) == PAD_RADIUS;
                    world.getBlockAt(px + dx, py, pz + dz)
                         .setType(edge ? Material.CHISELED_TUFF_BRICKS : Material.TUFF_BRICKS, false);
                }
            }
            cfg.set("bootstrap-pad-placed", true);
            plugin.saveConfig();
            plugin.getLogger().info("Placed the one-time bootstrap pad at the hub.");
        }
        return hub;
    }
}
