package org.aincrad.plugin.layer;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Partial enforcement of layer activation: cancels natural/spawner-driven mob spawns on an
 * inactive local floor. This does NOT yet cover hopper clocks, redstone, or scheduled block
 * entities on inactive layers - those need broader event/tick coverage and are an open follow-up
 * (see docs/ARCHITECTURE-DECISION.md), not something to claim as done here.
 */
public final class LayerSpawnGuard implements Listener {

    private final LayerActivationController controller;

    public LayerSpawnGuard(LayerActivationController controller) {
        this.controller = controller;
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) {
            return;
        }
        Location location = event.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        int localFloor = controller.localFloorForY(location.getBlockY());
        if (localFloor <= 0) {
            return;
        }
        List<int[]> playerPositions = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            Location loc = player.getLocation();
            playerPositions.add(new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()});
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!controller.isLayerActive(chunkX, chunkZ, localFloor, playerPositions)) {
            event.setCancelled(true);
        }
    }
}
