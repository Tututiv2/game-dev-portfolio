package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

/**
 * The Mines of Varnas (reference: Mines of Divan), in the Mithril Deposits: a pillared dwarven hall
 * cut into the rock. Four Keepers stand in niches on the north wall, each missing the tool their order
 * lost; the tools are buried under the hall floor at the "varnas.treasure.N" spots and found with a
 * Metal Detector. The Jade crystal sits on its pedestal between the Keepers.
 */
final class Varnas {

    private Varnas() {}

    static final int X0 = 95, X1 = 147, Z0 = -147, Z1 = -99, FLOOR = 84, H = 16;
    /** Keeper order: lapis, emerald, diamond, gold (west to east). */
    static final String[][] KEEPERS = {{"lapis", "blue"}, {"emerald", "green"}, {"diamond", "light_blue"}, {"gold", "yellow"}};
    static final int[] PILLAR_X = {103, 113, 129, 139};
    static final int[] PILLAR_Z = {-110, -123, -136};

    static void prepare(Ctx x) {
        Structures.carveRoom(x, X0, FLOOR + 1, Z0, X1, FLOOR + H, Z1);
        Carve.blob(x, (X0 + X1) / 2.0, FLOOR + H, (Z0 + Z1) / 2.0, 26, 6, 24, 0.12);
        // the hall's walls are a lining, so keep one ring of rock outside the air
        x.structure(Box.of(X0 - 1, FLOOR + 1, Z0 - 1, X1 + 1, FLOOR + H + 7, Z1 + 1), true);
        // the Mithril Deposits around it: open the cave a little east and west of the hall
        Carve.blob(x, X0 - 14, FLOOR + 8, -128, 10, 7, 14, 0.25);
        Carve.blob(x, X1 + 16, FLOOR + 10, -140, 12, 8, 10, 0.25);
    }

    static void build(Ctx x) {
        Layout l = x.l;
        Palette floor = Palette.of("polished_deepslate:3,deepslate_tiles:3,cracked_deepslate_tiles:1,deepslate_bricks:1");
        Palette wall = Palette.of("deepslate_bricks:4,cracked_deepslate_bricks:1,deepslate_tiles:2,polished_deepslate:1");
        Fill gold = Fill.of("raw_gold_block");
        // floor and wall lining
        for (int z = Z0; z <= Z1; z++)
            for (int xx = X0; xx <= X1; xx++) x.c.set(xx, FLOOR, z, floor.at(xx, FLOOR, z));
        for (int y = FLOOR + 1; y <= FLOOR + H; y++) {
            Fill f = (y == FLOOR + 4 || y == FLOOR + 11) ? gold : wall;
            for (int xx = X0 - 1; xx <= X1 + 1; xx++) {
                lining(x, xx, y, Z0 - 1, f);
                lining(x, xx, y, Z1 + 1, f);
            }
            for (int z = Z0; z <= Z1; z++) {
                lining(x, X0 - 1, y, z, f);
                lining(x, X1 + 1, y, z, f);
            }
        }
        // a gold-trimmed aisle with a minecart line down the middle
        int mid = (X0 + X1) / 2;
        for (int z = Z0 + 4; z <= Z1; z++) {
            x.c.set(mid - 2, FLOOR, z, "polished_blackstone_bricks");
            x.c.set(mid + 2, FLOOR, z, "polished_blackstone_bricks");
            x.c.set(mid, FLOOR + 1, z, "rail[shape=north_south,waterlogged=false]");
        }
        // pillars with gold capitals, meeting the vault
        for (int px : PILLAR_X)
            for (int pz : PILLAR_Z) {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int cx = px + dx, cz = pz + dz;
                        int y = FLOOR + 1;
                        while (y <= FLOOR + H + 6 && !x.g.solid(cx, y, cz)) {
                            boolean cap = y == FLOOR + H - 2 || y == FLOOR + 1;
                            x.c.set(cx, y, cz, cap ? "raw_gold_block" : (dx == 0 && dz == 0 ? "polished_deepslate" : "deepslate_tiles"));
                            y++;
                        }
                    }
                x.c.set(px, FLOOR + 5, pz + 2, "lantern[hanging=false,waterlogged=false]");
                x.c.set(px, FLOOR + 4, pz + 2, "deepslate_tile_slab[type=top,waterlogged=false]");
            }
        for (int px = X0 + 6; px <= X1 - 6; px += 12)
            for (int pz = Z0 + 6; pz <= Z1 - 4; pz += 14) Structures.chandelier(x, px, FLOOR + 9, pz, 4);
        // the Keepers' niches on the north wall
        for (int i = 0; i < KEEPERS.length; i++) {
            int kx = PILLAR_X[i];
            for (int dx = -2; dx <= 2; dx++)
                for (int y = FLOOR + 1; y <= FLOOR + 5; y++) x.c.set(kx + dx, y, Z0 - 1, y == FLOOR + 5 ? "chiseled_deepslate" : "air");
            for (int dx = -2; dx <= 2; dx++) for (int y = FLOOR + 1; y <= FLOOR + 5; y++) x.c.set(kx + dx, y, Z0 - 2, wall.at(kx + dx, y, Z0 - 2));
            x.c.set(kx, FLOOR, Z0 - 1, "raw_gold_block");
            x.c.set(kx - 2, FLOOR + 1, Z0 - 1, "deepslate_brick_wall");
            x.c.set(kx + 2, FLOOR + 1, Z0 - 1, "deepslate_brick_wall");
            x.c.set(kx - 1, FLOOR + 4, Z0, KEEPERS[i][1] + "_wall_banner[facing=south]");
            x.c.set(kx + 1, FLOOR + 4, Z0, KEEPERS[i][1] + "_wall_banner[facing=south]");
            l.standAt("npc.keeper_" + KEEPERS[i][0], kx, FLOOR, Z0 + 1, 0f);
        }
        // the Jade crystal's pedestal between the Keepers
        x.c.set(mid, FLOOR + 1, Z0 + 2, "polished_blackstone_bricks");
        x.c.set(mid, FLOOR + 2, Z0 + 2, "lime_concrete");
        x.c.set(mid, FLOOR + 3, Z0 + 2, "end_rod[facing=up]");
        for (int dx = -1; dx <= 1; dx += 2) x.c.set(mid + dx, FLOOR + 1, Z0 + 2, "deepslate_tile_stairs[facing=" + (dx < 0 ? "east" : "west") + ",half=bottom,shape=straight,waterlogged=false]");
        l.anchor("structure.mines_of_varnas", mid + 0.5, FLOOR + 1, Z0 + 4.5, 0f);
        // crates, carts and tools left behind
        long s = Hash.salt(x.seed, "varnas");
        for (int i = 0; i < 10; i++) {
            int cx = X0 + 2 + (int) (Hash.unit(s, i, 0, 0) * (X1 - X0 - 4));
            int cz = Z0 + 4 + (int) (Hash.unit(s, i, 1, 0) * (Z1 - Z0 - 6));
            if (Math.abs(cx - mid) < 4 || nearPillar(cx, cz, 3) || cz < Z0 + 5) continue;
            Builders.crates(x.c, cx, FLOOR, cz, s + i);
        }
        // treasure spots: open floor cells away from pillars, the aisle and the keepers. Each one is a REAL
        // chest buried one block under the hall floor (wave 5); the player who is hunting that spot digs the
        // floor tile above it open and opens the chest. Spots whose buried chest would touch a cave are skipped.
        int n = 0;
        String[] faces = {"north", "east", "south", "west"};
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        for (int i = 0; i < 400 && n < TREASURES; i++) {
            int tx = X0 + 2 + (int) (Hash.unit(s, i, 7, 3) * (X1 - X0 - 4));
            int tz = Z0 + 6 + (int) (Hash.unit(s, i, 8, 3) * (Z1 - Z0 - 8));
            if (Math.abs(tx - mid) < 4 || nearPillar(tx, tz, 3)) continue;
            if (!x.c.isAir(tx, FLOOR + 1, tz) || !x.c.isAir(tx, FLOOR + 2, tz)) continue;
            if (!buriedOk(x, tx, tz)) continue;
            boolean near = false;                       // never two chests side by side
            for (int[] q : placed) if (Math.abs(q[0] - tx) <= 1 && Math.abs(q[1] - tz) <= 1) { near = true; break; }
            if (near) continue;
            placed.add(new int[]{tx, tz});
            x.c.set(tx, FLOOR - 1, tz, "chest[facing=" + faces[(int) (Hash.unit(s, i, 9, 3) * 4)] + ",type=single,waterlogged=false]");
            x.c.set(tx, FLOOR, tz, COVER);
            l.standAt("varnas.treasure." + (++n), tx, FLOOR, tz, 0f);
        }
        l.data("hollows.varnas.buried", Integer.toString(FLOOR - 1));
        // souls: behind the east-most keeper, and in the hall's south-west corner behind crates
        x.soul(X1 - 1, FLOOR, Z0);
        x.soul(X0, FLOOR, Z1);
        Builders.crates(x.c, X0 + 1, FLOOR, Z1 - 2, s + 99);
        l.zone("mithril_varnas", "Mines of Varnas", '2', Box.of(X0 - 1, FLOOR, Z0 - 2, X1 + 1, FLOOR + H + 6, Z1 + 1), null);
        l.area("mobs.varnas", Box.of(X0 + 1, FLOOR + 1, Z0 + 6, X1 - 1, FLOOR + 3, Z1 - 1));
        l.area("varnas.hall", Box.of(X0, FLOOR + 1, Z0, X1, FLOOR + H, Z1));
    }

    /** How many buried treasure chests the hall holds. */
    static final int TREASURES = 24;
    /** The floor tile over a buried chest: one of the hall's own floor blocks, so only the detector gives it away. */
    static final String COVER = "cracked_deepslate_tiles";

    /** A chest one block under (tx, FLOOR, tz) is sealed in rock on every side but the floor tile above. */
    static boolean buriedOk(Ctx x, int tx, int tz) {
        int y = FLOOR - 1;
        return x.g.solid(tx, y - 1, tz) && x.g.solid(tx + 1, y, tz) && x.g.solid(tx - 1, y, tz)
                && x.g.solid(tx, y, tz + 1) && x.g.solid(tx, y, tz - 1) && x.g.solid(tx, y, tz);
    }

    private static void lining(Ctx x, int xx, int y, int z, Fill f) {
        if (x.g.locked(xx, y, z) && x.g.air(xx, y, z)) return;
        if (!x.g.solid(xx, y, z)) return;            // a door the tunnels cut stays open
        x.c.set(xx, y, z, f.at(xx, y, z));
    }

    static boolean nearPillar(int px, int pz, int r) {
        for (int x : PILLAR_X) for (int z : PILLAR_Z) if (Math.abs(px - x) <= r && Math.abs(pz - z) <= r) return true;
        return false;
    }
}
