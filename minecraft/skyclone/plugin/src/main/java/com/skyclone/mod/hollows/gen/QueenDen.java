package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

/**
 * The Goblin Queen's Den (reference: Goblin Queen's Den, Goblin Holdout): a mud-walled brood chamber
 * dug south of the Holdout, reached by one tunnel from the fire pit. A ring of hay holds the egg
 * clutch in the middle; the Goblin Queen stands guard in front of it with her Brood Guards around
 * the hall. Goblin Eggs come from here far more often than from the goblins outside.
 */
final class QueenDen {

    private QueenDen() {}

    /** Hall bounds (inside air) and floor. */
    static final int X0 = -126, X1 = -100, Z0 = 183, Z1 = 203, FLOOR = GoblinDen.FLOOR, H = 9;
    static final int NX = -113, NZ = 195;

    static void prepare(Ctx x) {
        Structures.carveRoom(x, X0, FLOOR + 1, Z0, X1, FLOOR + H, Z1);
        Carve.blob(x, (X0 + X1) / 2.0, FLOOR + H, (Z0 + Z1) / 2.0, 14, 5, 11, 0.12);
        x.structure(Box.of(X0 - 1, FLOOR + 1, Z0 - 1, X1 + 1, FLOOR + 15, Z1 + 1), true);
        // the one way in: from the Holdout's fire pit south through the door in the hall's north wall
        Carve.tunnel(x, new int[][]{{GoblinDen.CX + 6, FLOOR, GoblinDen.CZ + 10}, {-115, FLOOR, 160}, {-113, FLOOR, 176}, {NX, FLOOR, Z0 + 2}},
                1.6, 4, Network.DECK_GOBLIN, true);
    }

    static void build(Ctx x) {
        Layout l = x.l;
        long s = Hash.salt(x.seed, "queen");
        Palette floor = Palette.of("packed_mud:5,mud:2,coarse_dirt:2,rooted_dirt:1");
        for (int z = Z0; z <= Z1; z++)
            for (int xx = X0; xx <= X1; xx++) x.c.set(xx, FLOOR, z, floor.at(xx, FLOOR, z));
        // mud-brick walls, timber posts every few blocks
        Fill wall = Palette.of("mud_bricks:5,packed_mud:2,cracked_stone_bricks:1");
        for (int y = FLOOR + 1; y <= FLOOR + H; y++) {
            for (int xx = X0 - 1; xx <= X1 + 1; xx++) { lining(x, xx, y, Z0 - 1, wall); lining(x, xx, y, Z1 + 1, wall); }
            for (int z = Z0; z <= Z1; z++) { lining(x, X0 - 1, y, z, wall); lining(x, X1 + 1, y, z, wall); }
        }
        for (int xx = X0 - 1; xx <= X1 + 1; xx += 6) {
            post(x, xx, Z0 - 1);
            post(x, xx, Z1 + 1);
        }
        // the door: an opening in the north wall with a timber lintel
        for (int xx = NX - 1; xx <= NX + 1; xx++)
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) x.c.set(xx, y, Z0 - 1, "air");
        for (int xx = NX - 2; xx <= NX + 2; xx++) x.c.set(xx, FLOOR + 4, Z0 - 1, "stripped_dark_oak_log[axis=x]");
        x.c.set(NX - 2, FLOOR + 5, Z0 - 2, "orange_wall_banner[facing=north]");
        x.c.set(NX + 2, FLOOR + 5, Z0 - 2, "orange_wall_banner[facing=north]");
        // ---- the clutch: a hay ring with eggs on a mud mound ----
        for (int dz = -5; dz <= 5; dz++)
            for (int dx = -5; dx <= 5; dx++) {
                double d = Math.hypot(dx, dz);
                int px = NX + dx, pz = NZ + dz;
                if (d <= 5.4 && d > 3.6) x.c.set(px, FLOOR + 1, pz, "hay_block[axis=y]");
                else if (d <= 3.6) {
                    x.c.set(px, FLOOR + 1, pz, "mud");
                    double r = Hash.unit(s, dx, 0, dz);
                    if (r < 0.45) x.c.set(px, FLOOR + 2, pz, "turtle_egg[eggs=" + (1 + (int) (Hash.unit(s, dx, 1, dz) * 4)) + ",hatch=" + (int) (Hash.unit(s, dx, 2, dz) * 3) + "]");
                    else if (r < 0.6) x.c.set(px, FLOOR + 2, pz, "sniffer_egg[hatch=" + (int) (Hash.unit(s, dx, 3, dz) * 3) + "]");
                }
            }
        x.c.set(NX, FLOOR + 2, NZ, "sniffer_egg[hatch=2]");
        x.c.set(NX, FLOOR + 3, NZ, "orange_banner[rotation=8]");
        // ---- dressing: barrels of stolen goods, lanterns, chandeliers ----
        for (int z = Z0 + 2; z <= Z1 - 2; z += 5) {
            Builders.crates(x.c, X0 + 1, FLOOR, z, s + z);
            Builders.crates(x.c, X1 - 2, FLOOR, z, s + z * 7L);
        }
        for (int xx = X0 + 4; xx <= X1 - 4; xx += 7) {
            x.c.set(xx, FLOOR + 1, Z1, "barrel[facing=up,open=false]");
            x.c.set(xx, FLOOR + 2, Z1, "lantern[hanging=false,waterlogged=false]");
        }
        Structures.chandelier(x, NX - 7, FLOOR + 5, NZ, 3);
        Structures.chandelier(x, NX + 7, FLOOR + 5, NZ, 3);
        Structures.chandelier(x, NX, FLOOR + 5, Z0 + 3, 3);
        // the Queen holds the floor in front of her clutch, facing the door
        l.standAt("boss.goblin_queen", NX, FLOOR, NZ - 8, 180f);
        l.area("boss.goblin_queen", Box.of(X0 + 2, FLOOR + 1, Z0 + 2, X1 - 2, FLOOR + 3, Z1 - 2));
        l.area("mobs.goblin_queen_den", Box.of(X0 + 2, FLOOR + 1, Z0 + 1, X1 - 2, FLOOR + 3, Z1 - 1));
        l.anchor("structure.goblin_queen_den", NX + 0.5, FLOOR + 1, Z0 + 2.5, 0f);
        l.zone("goblin_queen_den", "Goblin Queen's Den", '6', Box.of(X0 - 1, FLOOR, Z0 - 1, X1 + 1, FLOOR + 14, Z1 + 1), null);
        x.soul(X1 - 1, FLOOR, Z1 - 1);
    }

    private static void post(Ctx x, int px, int pz) {
        for (int y = FLOOR + 1; y <= FLOOR + H; y++)
            if (x.g.solid(px, y, pz) && !(x.g.locked(px, y, pz) && x.g.air(px, y, pz))) x.c.set(px, y, pz, "dark_oak_log[axis=y]");
    }

    private static void lining(Ctx x, int xx, int y, int z, Fill f) {
        if (x.g.locked(xx, y, z) && x.g.air(xx, y, z)) return;
        if (!x.g.solid(xx, y, z)) return;
        x.c.set(xx, y, z, f.at(xx, y, z));
    }
}
