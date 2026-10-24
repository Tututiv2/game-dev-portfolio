package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;

/**
 * Small PURE building helpers shared by the seven boss arenas. Coordinates are inclusive. Every hall has a solid
 * floor slab under it (so nothing floats), walls with a trim course, a ceiling, and lights at least every 6 blocks:
 * the catacombs world sits at midnight, so an arena without its own light is a black box.
 */
final class ArenaKit {

    private ArenaKit() {}

    static void fill(VoxelCanvas c, int x0, int y0, int z0, int x1, int y1, int z1, String b) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) c.set(x, y, z, b);
    }

    static void fill(VoxelCanvas c, int x0, int y0, int z0, int x1, int y1, int z1, Palette p) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) c.set(x, y, z, p.at(x, y, z));
    }

    static void air(VoxelCanvas c, int x0, int y0, int z0, int x1, int y1, int z1) { fill(c, x0, y0, z0, x1, y1, z1, "air"); }

    /**
     * A closed rectangular hall: footprint x0..x1, z0..z1 (walls on the edges), floor block layer at fy (with a
     * stone layer under it), interior air fy+1 .. fy+h-1, ceiling at fy+h. Lights hang from the ceiling on a grid.
     */
    static void hall(VoxelCanvas c, int x0, int z0, int x1, int z1, int fy, int h, Palette floor, Palette wall, Palette trim,
                     Palette ceiling, String light, int lightStep) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                c.set(x, fy - 1, z, "stone");
                c.set(x, fy - 2, z, "stone");
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                c.set(x, fy, z, edge ? trim.at(x, fy, z) : floor.at(x, fy, z));
                c.set(x, fy + h, z, ceiling.at(x, fy + h, z));
                for (int y = fy + 1; y < fy + h; y++) {
                    if (edge) c.set(x, y, z, y == fy + 1 || y == fy + h - 1 ? trim.at(x, y, z) : wall.at(x, y, z));
                    else c.set(x, y, z, "air");
                }
            }
        if (light != null && lightStep > 0)
            for (int x = x0 + 3; x <= x1 - 3; x += lightStep)
                for (int z = z0 + 3; z <= z1 - 3; z += lightStep) c.set(x, fy + h, z, light);
    }

    /** A filled cylinder of {@code b} from y0 for h blocks. */
    static void column(VoxelCanvas c, int cx, int cz, double r, int y0, int h, Palette p) {
        int ri = (int) Math.ceil(r);
        for (int x = cx - ri; x <= cx + ri; x++)
            for (int z = cz - ri; z <= cz + ri; z++)
                if (Math.hypot(x - cx, z - cz) <= r + 0.01)
                    for (int y = y0; y < y0 + h; y++) c.set(x, y, z, p.at(x, y, z));
    }

    /** A disc of blocks at one y. */
    static void disc(VoxelCanvas c, int cx, int cz, double r, int y, Palette p) {
        int ri = (int) Math.ceil(r);
        for (int x = cx - ri; x <= cx + ri; x++)
            for (int z = cz - ri; z <= cz + ri; z++)
                if (Math.hypot(x - cx, z - cz) <= r + 0.01) c.set(x, y, z, p.at(x, y, z));
    }

    /** A ring (annulus) of blocks at one y. */
    static void ring(VoxelCanvas c, int cx, int cz, double r0, double r1, int y, String b) {
        int ri = (int) Math.ceil(r1);
        for (int x = cx - ri; x <= cx + ri; x++)
            for (int z = cz - ri; z <= cz + ri; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d >= r0 - 0.01 && d <= r1 + 0.01) c.set(x, y, z, b);
            }
    }

    /** A round, walled, roofed arena of radius r; everything inside is air from fy+1 to fy+h-1. */
    static void round(VoxelCanvas c, int cx, int cz, int r, int fy, int h, Palette floor, Palette wall, Palette trim, Palette roof) {
        for (int x = cx - r - 2; x <= cx + r + 2; x++)
            for (int z = cz - r - 2; z <= cz + r + 2; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > r + 1.6) continue;
                c.set(x, fy - 1, z, "stone");
                c.set(x, fy - 2, z, "stone");
                c.set(x, fy, z, d > r ? trim.at(x, fy, z) : floor.at(x, fy, z));
                if (roof != null) c.set(x, fy + h, z, roof.at(x, fy + h, z));
                for (int y = fy + 1; y < fy + h; y++) {
                    if (d > r) c.set(x, y, z, y == fy + 1 || y == fy + h - 1 ? trim.at(x, y, z) : wall.at(x, y, z));
                    else c.set(x, y, z, "air");
                }
            }
    }

    /** A straight flight of stairs rising toward {@code facing} ("north" = going up as z decreases). */
    static void stairs(VoxelCanvas c, int x0, int x1, int zStart, int yStart, int steps, String facing, String stairBlock, String fillBlock) {
        int dz = facing.equals("north") ? -1 : 1;
        for (int i = 0; i < steps; i++) {
            int z = zStart + dz * i, y = yStart + i;
            for (int x = x0; x <= x1; x++) {
                for (int yy = yStart - 1; yy < y; yy++) c.set(x, yy, z, fillBlock);
                c.set(x, y, z, stairBlock + "[facing=" + facing + ",half=bottom,shape=straight,waterlogged=false]");
                for (int yy = y + 1; yy <= y + 3; yy++) c.set(x, yy, z, "air");
            }
        }
    }

    /** Braziers / campfires and hanging lanterns along a wall line, every {@code step} blocks. */
    static void wallLights(VoxelCanvas c, int x0, int x1, int z, int y, int step, String light) {
        for (int x = x0; x <= x1; x += step) c.set(x, y, z, light);
    }
}
