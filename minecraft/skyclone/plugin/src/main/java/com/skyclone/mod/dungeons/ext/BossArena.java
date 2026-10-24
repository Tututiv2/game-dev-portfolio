package com.skyclone.mod.dungeons.ext;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.mod.dungeons.model.FloorDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EXTENSION POINT (agent B): the BLOCKS of a boss floor's arena. PURE - no Bukkit - because it runs off-thread with the
 * rest of a run's generation (and in tests/preview). The runtime half of a boss is {@link BossFloor}.
 *
 * <p>{@link #build} writes the arena with its min corner at (ox, floorY, oz) in TEMPLATE coordinates: x in
 * [ox, ox + sizeX), z in [oz, oz + sizeZ), y between floorY - 30 and floorY + 60. Size is capped at
 * {@code Geo.ARENA_MAX_X} x {@code Geo.ARENA_MAX_Z}. Record named points in {@code out}:
 * <ul>
 *   <li>{@code spawn} (required) - where players arrive (standing position: the block above the floor);</li>
 *   <li>{@code boss} - where the boss appears;</li>
 *   <li>{@code chests} - where reward chests go after the kill;</li>
 *   <li>anything else your fight needs ("pillar.1", "crystal.a", "terminal.3", "pad.storm"...).</li>
 * </ul>
 */
public interface BossArena {

    String id();

    int sizeX();

    int sizeZ();

    void build(VoxelCanvas c, int ox, int floorY, int oz, long seed, FloorDef floor, Points out);

    /** Named points an arena records (template coordinates, standing positions). */
    final class Points {
        private final Map<String, int[]> map = new LinkedHashMap<>();

        public Points put(String name, int x, int y, int z) { map.put(name, new int[]{x, y, z}); return this; }

        public int[] get(String name) { return map.get(name); }

        public Map<String, int[]> map() { return map; }
    }
}
