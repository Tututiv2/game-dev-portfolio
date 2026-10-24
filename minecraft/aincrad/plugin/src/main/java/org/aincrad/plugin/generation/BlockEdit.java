package org.aincrad.plugin.generation;

import org.bukkit.Material;

/**
 * One block write. {@code blockData} is an optional vanilla block-state string (e.g.
 * {@code "minecraft:stone_brick_stairs[facing=north,half=top]"}); when null the material's default
 * state is used. Needed for stairs, slabs, doors, ladders and other oriented blocks - without it a
 * generator can only place full cubes, which is fine for terrain and walls but not for interior
 * detailing or pitched roofs.
 */
public record BlockEdit(int x, int y, int z, Material material, String blockData) {

    public BlockEdit(int x, int y, int z, Material material) {
        this(x, y, z, material, null);
    }
}
