package org.aincrad.plugin.layer;

import java.util.BitSet;
import java.util.List;

/**
 * Computes which of the ten local floor layers sharing one dimension group are "active" for a
 * given chunk column, per docs/floor-01-layout.json's dimension_architecture.activation contract:
 * a player on one local floor must not keep mobs, NPCs, encounters, hoppers, or machines ticking
 * on the other nine. Floor 1 additionally owns its deep understructure (Y=-64..319) and its own
 * ceiling plate (Y=420..479); floors 2-10 each own a plain 160-block band starting at their
 * surface datum. This class is pure calculation - it does not itself listen for Bukkit events;
 * see org.aincrad.plugin.layer.LayerSpawnGuard for the (currently partial) enforcement listener.
 */
public final class LayerActivationController {

    public static final int FLOORS_PER_DIMENSION = 10;
    public static final int FLOOR_PITCH = 160;
    public static final int BASE_SURFACE_Y = 320;

    private final int activationRadiusBlocks;

    public LayerActivationController(int activationRadiusBlocks) {
        this.activationRadiusBlocks = activationRadiusBlocks;
    }

    public static int surfaceYForLocalFloor(int localFloor) {
        return BASE_SURFACE_Y + FLOOR_PITCH * (localFloor - 1);
    }

    /**
     * Returns the 1-based local floor (1..10) that owns the given Y coordinate, or -1 if Y falls
     * outside every floor's owned band (should not happen inside the permanent dimension's
     * min_y=-64/height=2032 range, but callers should not assume it can't).
     */
    public int localFloorForY(int y) {
        if (y >= -64 && y <= 479) {
            return 1;
        }
        for (int floor = 2; floor <= FLOORS_PER_DIMENSION; floor++) {
            int surfaceY = surfaceYForLocalFloor(floor);
            if (y >= surfaceY && y < surfaceY + FLOOR_PITCH) {
                return floor;
            }
        }
        return -1;
    }

    /**
     * A player at (playerX, playerY, playerZ) activates their own local floor for every chunk
     * column within activationRadiusBlocks horizontally, regardless of that column's own (chunkX,
     * chunkZ) - vertical floor identity is never inferred from chunk position alone.
     */
    public BitSet activeFloorMaskForColumn(int chunkX, int chunkZ, List<int[]> onlinePlayerPositions) {
        BitSet mask = new BitSet(FLOORS_PER_DIMENSION + 1);
        int columnCenterX = chunkX * 16 + 8;
        int columnCenterZ = chunkZ * 16 + 8;
        for (int[] pos : onlinePlayerPositions) {
            int px = pos[0], py = pos[1], pz = pos[2];
            double dx = px - columnCenterX;
            double dz = pz - columnCenterZ;
            double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDistance <= activationRadiusBlocks) {
                int floor = localFloorForY(py);
                if (floor > 0) {
                    mask.set(floor);
                }
            }
        }
        return mask;
    }

    public boolean isLayerActive(int chunkX, int chunkZ, int localFloor, List<int[]> onlinePlayerPositions) {
        return activeFloorMaskForColumn(chunkX, chunkZ, onlinePlayerPositions).get(localFloor);
    }
}
