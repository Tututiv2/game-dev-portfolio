package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.gen.util.Pack;

/**
 * The walkable network of the crystal caverns: one tunnel from the Nucleus floor into each quadrant's
 * structure, a long descent into the Magma Fields to the Emberdeep hall, and branches to the Gang
 * Hideout, the Dragon's Lair and the three Fairy Grottos. Every point is a FLOOR block {x, y, z}; the
 * coordinates are planned against {@link Structures}' chambers. Pure Java.
 */
public final class Network {

    private Network() {}

    static final Palette DECK_JUNGLE = Palette.of("coarse_dirt:3,dirt_path:4,mossy_cobblestone:1");
    static final Palette DECK_GOBLIN = Palette.of("dirt_path:3,coarse_dirt:3,packed_mud:2");
    static final Palette DECK_CITY = Palette.of("polished_andesite:4,smooth_stone:2,cracked_stone_bricks:1,andesite:1");
    static final Palette DECK_MAGMA = Palette.of("polished_blackstone_bricks:3,cracked_polished_blackstone_bricks:1,polished_blackstone:2");
    static final Palette DECK_MINE = Palette.of("polished_andesite:3,andesite:2,tuff:1");

    // tunnel routes (floor points)
    static final int[][] TO_JUNGLE = {{-24, 100, -24}, {-31, 100, -31}, {-55, 95, -48}, {-75, 88, -78}, {-88, 82, -92}, {-96, 78, -98}};
    static final int[][] TO_VARNAS = {{24, 100, -24}, {31, 100, -31}, {48, 96, -55}, {70, 90, -85}, {100, 86, -92}, {106, 84, -96}, {106, 84, -97}};
    static final int[][] TO_CITY = {{24, 100, 24}, {31, 100, 31}, {50, 95, 48}, {72, 88, 70}, {88, 82, 84}, {96, 80, 92}};
    static final int[][] TO_GOBLIN = {{-24, 100, 24}, {-31, 100, 31}, {-50, 96, 50}, {-75, 90, 72}, {-90, 84, 88}, {-97, 82, 95}};
    static final int[][] TO_MAGMA = {{0, 100, 34}, {0, 100, 44}, {18, 94, 62}, {14, 85, 80}, {-6, 76, 92}, {-24, 66, 84},
            {-30, 56, 100}, {-18, 46, 116}, {-6, 38, 130}, {0, 36, 138}};
    static final int[][] TO_HIDEOUT = {{149, 84, -122}, {160, 90, -100}, {172, 100, -70}, {176, 106, -54}};
    static final int[][] JUNGLE_TO_LAIR = {{-163, 78, -112}, {-180, 84, -80}, {-188, 90, -40}, {-187, 94, -13}};
    static final int[][] GOBLIN_TO_LAIR = {{-140, 82, 86}, {-165, 86, 50}, {-182, 92, 18}, {-186, 94, 11}};
    static final int[][] TO_GROTTO_1 = {{-55, 95, -48}, {-61, 96, -37}, {-64, 96, -31}};
    static final int[][] TO_GROTTO_2 = {{72, 88, 70}, {79, 89, 58}, {82, 89, 52}};
    static final int[][] TO_GROTTO_3 = {{172, 100, -70}, {184, 100, -78}, {190, 100, -82}};
    /** Magma Fields loop: Emberdeep's side gates out to the east and west fields. */
    static final int[][] MAGMA_EAST = {{8, 36, 163}, {28, 36, 166}, {60, 37, 150}, {95, 37, 120}, {120, 38, 80}};
    static final int[][] MAGMA_WEST = {{-8, 36, 163}, {-28, 36, 166}, {-60, 37, 150}, {-95, 37, 120}, {-125, 38, 75}};

    public static void carve(Ctx x) {
        Carve.tunnel(x, TO_JUNGLE, 3.2, 5, null, true);
        Carve.tunnel(x, TO_VARNAS, 3.2, 5, DECK_MINE, true);
        Carve.tunnel(x, TO_CITY, 3.2, 5, null, true);
        Carve.tunnel(x, TO_GOBLIN, 3.2, 5, null, true);
        Carve.tunnel(x, TO_MAGMA, 3.6, 6, null, true);
        Carve.tunnel(x, TO_HIDEOUT, 3.0, 5, null, true);
        Carve.tunnel(x, JUNGLE_TO_LAIR, 2.8, 5, null, true);
        Carve.tunnel(x, GOBLIN_TO_LAIR, 2.8, 5, null, true);
        Carve.tunnel(x, TO_GROTTO_1, 1.8, 3, null, false);
        Carve.tunnel(x, TO_GROTTO_2, 1.8, 3, null, false);
        Carve.tunnel(x, TO_GROTTO_3, 1.8, 3, null, false);
        Carve.tunnel(x, MAGMA_EAST, 3.0, 5, null, false);
        Carve.tunnel(x, MAGMA_WEST, 3.0, 5, null, false);
    }

    /** After the grid is written: rails along bridges and the hanging lanterns. */
    public static void finish(Ctx x) {
        Grid g = x.g;
        VoxelCanvas c = x.c;
        int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
        x.bridge.forEach(k -> {
            int bx = Pack.x(k), by = Pack.y(k), bz = Pack.z(k);
            boolean magma = by <= Zones.MAGMA_TOP;
            for (int i = 0; i < 4; i++) {
                int nx = bx + dx[i], nz = bz + dz[i];
                if (g.solid(nx, by, nz)) continue;                      // the walkway continues there
                if (!g.air(nx, by + 1, nz) || g.locked(nx, by + 1, nz)) continue;
                if (x.inStruct(nx, by + 1, nz)) continue;
                // the rail stands on a widened deck block, so it is never left hanging in the air
                if (!g.air(nx, by, nz) || g.locked(nx, by, nz)) continue;
                c.set(nx, by, nz, magma ? "polished_blackstone_brick_slab[type=top,waterlogged=false]" : "spruce_slab[type=top,waterlogged=false]");
                c.set(nx, by + 1, nz, magma ? "polished_blackstone_wall" : "spruce_fence");
            }
            // support posts every few blocks so a long bridge reads as built, not floating
            if (((bx * 7 + bz * 13) & 7) == 0 && !magma) {
                for (int y = by - 1; y > by - 14 && g.in(bx, y, bz); y--) {
                    if (g.solid(bx, y, bz) || g.fluid(bx, y, bz)) {
                        for (int yy = y + 1; yy < by; yy++) c.set(bx, yy, bz, "spruce_log[axis=y]");
                        break;
                    }
                }
            }
        });
        for (int[] p : x.lanterns) {
            int lx = p[0], ly = p[1], lz = p[2];
            if (!g.in(lx, ly, lz)) continue;
            // hang from the first rock above the walkway head room
            int y = ly;
            while (y > ly - 3 && !g.solid(lx, y + 1, lz)) y--;
            if (g.solid(lx, y + 1, lz) && g.air(lx, y, lz) && !x.inStruct(lx, y, lz) && !g.solid(lx, y, lz))
                c.set(lx, y, lz, y <= Zones.MAGMA_TOP ? "soul_lantern[hanging=true,waterlogged=false]" : "lantern[hanging=true,waterlogged=false]");
        }
    }
}
