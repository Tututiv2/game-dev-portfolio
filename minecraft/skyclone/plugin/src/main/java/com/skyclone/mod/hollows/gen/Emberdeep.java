package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Shapes;

/**
 * Emberdeep (reference: Khazad-dum), in the Magma Fields: a ruined dwarven hall of black pillars with
 * two lava channels running its length. The arena in the middle is where Magmaw rises; it guards the
 * Topaz crystal. Side gates lead out to the east and west fields.
 */
final class Emberdeep {

    private Emberdeep() {}

    static final int CX = 0, CZ = 170, FLOOR = 36;
    static final int ARENA_Z = 176, ARENA_R = 13;

    static void prepare(Ctx x) {
        Carve.blob(x, CX, FLOOR + 12, CZ, 42, 15, 36, 0.10);
        Carve.floor(x, CX, CZ, 41, 35, FLOOR + 6, 6, (xx, z) -> FLOOR);
        // two lava channels along the hall
        for (int z = 142; z <= 198; z++)
            for (int side = -1; side <= 1; side += 2)
                for (int dx = 0; dx <= 1; dx++) {
                    int xx = side * (20 + dx);
                    if (!x.g.locked(xx, FLOOR, z)) continue;
                    x.g.put(xx, FLOOR, z, Grid.LAVA, true);
                    x.g.put(xx, FLOOR - 1, z, Grid.LAVA, true);
                }
        x.structure(Box.of(-18, FLOOR + 1, 140, 18, FLOOR + 22, 202), true);
    }

    static void build(Ctx x) {
        Layout l = x.l;
        Palette floor = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:2,polished_blackstone:2,blackstone:1");
        Fill pillar = Palette.of("polished_blackstone_bricks:5,cracked_polished_blackstone_bricks:1,chiseled_polished_blackstone:1");
        for (int z = 140; z <= 202; z++)
            for (int xx = -18; xx <= 18; xx++)
                if (x.g.locked(xx, FLOOR, z) && x.g.solid(xx, FLOOR, z)) x.c.set(xx, FLOOR, z, floor.at(xx, FLOOR, z));
        // pillar rows
        for (int pz = 146; pz <= 190; pz += 11)
            for (int px : new int[]{-12, 12}) {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int top = Structures.column(x, px + dx, FLOOR + 1, pz + dz, pillar, 30);
                        if (dx == 0 && dz == 0 && top > FLOOR + 8) x.c.set(px, FLOOR + 6, pz + 2, "soul_lantern[hanging=false,waterlogged=false]");
                    }
                x.c.set(px, FLOOR + 5, pz + 2, "polished_blackstone_slab[type=top,waterlogged=false]");
            }
        // the north gate arch
        Shapes.arch(x.c, -7, 140, 7, 140, FLOOR + 1, 10, 2, 2, pillar);
        // the arena
        for (int z = ARENA_Z - ARENA_R - 1; z <= ARENA_Z + ARENA_R + 1; z++)
            for (int xx = -ARENA_R - 1; xx <= ARENA_R + 1; xx++) {
                double d = Math.hypot(xx, z - ARENA_Z);
                if (d > ARENA_R + 0.5) continue;
                String b = d > ARENA_R - 0.8 ? "chiseled_polished_blackstone" : d < 3 ? "gilded_blackstone" : ((int) d) % 4 == 0 ? "polished_basalt[axis=y]" : "polished_blackstone";
                x.c.set(xx, FLOOR, z, b);
            }
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int px = (int) Math.round(Math.cos(a) * (ARENA_R + 2)), pz = ARENA_Z + (int) Math.round(Math.sin(a) * (ARENA_R + 2));
            if (Math.abs(px) >= 10 && Math.abs(px) <= 14) continue;
            x.c.set(px, FLOOR + 1, pz, "polished_blackstone_wall");
            x.c.set(px, FLOOR + 2, pz, "soul_campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]");
        }
        // the old forge at the south end: an anvil, a throne of gilded stone
        x.c.set(0, FLOOR + 1, 194, "gilded_blackstone");
        x.c.set(0, FLOOR + 2, 194, "gilded_blackstone");
        x.c.set(-1, FLOOR + 1, 194, "polished_blackstone_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        x.c.set(1, FLOOR + 1, 194, "polished_blackstone_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
        x.c.set(-3, FLOOR + 1, 192, "anvil[facing=north]");
        x.c.set(3, FLOOR + 1, 192, "blast_furnace[facing=north,lit=true]");
        x.c.set(4, FLOOR + 1, 192, "lava_cauldron");
        l.anchor("boss.magmaw", 0.5, FLOOR + 1, ARENA_Z + 0.5, 180f);
        l.area("boss.magmaw", Box.of(-ARENA_R + 2, FLOOR + 1, ARENA_Z - ARENA_R + 2, ARENA_R - 2, FLOOR + 3, ARENA_Z + ARENA_R - 2));
        l.anchor("structure.emberdeep", 0.5, FLOOR + 1, 150.5, 0f);
        l.zone("magma_emberdeep", "Emberdeep", 'c', Box.of(-24, FLOOR - 2, 136, 24, FLOOR + 24, 204), null);
        l.area("mobs.magma.emberdeep", Box.of(-17, FLOOR + 1, 141, 17, FLOOR + 2, 158));
        // souls: behind the south-west pillar, and beside the forge
        x.soul(-14, FLOOR, 192);
        x.soul(6, FLOOR, 194);
    }
}
