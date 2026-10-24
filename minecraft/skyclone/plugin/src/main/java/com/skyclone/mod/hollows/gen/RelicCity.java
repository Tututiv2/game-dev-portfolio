package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;

import java.util.ArrayList;
import java.util.List;

/**
 * The Lost Relic City (reference: Lost Precursor City), in the Relic Remnants: a ruined city in a
 * cavern 116 blocks across. Streets cross at a plaza where a fallen automaton the size of a house
 * still stands; the professor's workshop is the one building with a roof. Automatons patrol the streets.
 */
final class RelicCity {

    private RelicCity() {}

    static final int CX = 126, CZ = 126, FLOOR = 80, RAD = 58;
    static final int CITY = 48;                      // half-size of the street grid
    static final int[] LAB = {134, 106, 144, 114};   // x0, z0, x1, z1 (north-east of the plaza)

    static void prepare(Ctx x) {
        Carve.blob(x, CX, FLOOR + 20, CZ, RAD, 34, RAD, 0.12);
        Carve.floor(x, CX, CZ, RAD - 1, RAD - 1, FLOOR + 6, 8, (xx, z) -> FLOOR);
        // lava pools near the walls (molten seams the city was built over)
        for (int[] p : new int[][]{{168, 150}, {150, 172}, {172, 112}}) {
            for (int z = p[1] - 5; z <= p[1] + 5; z++)
                for (int xx = p[0] - 5; xx <= p[0] + 5; xx++) {
                    if (Math.hypot(xx - p[0], z - p[1]) > 4.6) continue;
                    if (!x.g.locked(xx, FLOOR, z)) continue;
                    x.g.put(xx, FLOOR, z, Grid.LAVA, true);
                    x.g.put(xx, FLOOR - 1, z, Grid.LAVA, true);
                }
        }
        x.structure(Box.of(CX - CITY, FLOOR + 1, CZ - CITY, CX + CITY, FLOOR + 30, CZ + CITY), true);
    }

    static void build(Ctx x) {
        Layout l = x.l;
        Palette street = Palette.of("polished_andesite:4,smooth_stone:2,cracked_stone_bricks:1,andesite:2,stone_bricks:1");
        Palette wall = Palette.of("stone_bricks:3,polished_andesite:3,smooth_stone:2,cracked_stone_bricks:2,andesite:1");
        Fill trim = Fill.of("polished_diorite");
        // ---- streets: the cross, the ring road, the plaza ----
        for (int z = CZ - CITY; z <= CZ + CITY; z++)
            for (int xx = CX - CITY; xx <= CX + CITY; xx++) {
                if (!x.g.locked(xx, FLOOR, z) || x.g.fluid(xx, FLOOR, z)) continue;
                double d = Math.hypot(xx - CX, z - CZ);
                boolean road = Math.abs(xx - CX) <= 2 || Math.abs(z - CZ) <= 2 || (d >= 32 && d <= 36.5) || d <= 11;
                if (road) x.c.set(xx, FLOOR, z, d <= 11 && ((int) d) % 4 == 0 ? "chiseled_stone_bricks" : street.at(xx, FLOOR, z));
                else x.c.set(xx, FLOOR, z, Palette.of("stone:3,gravel:1,andesite:2,cobblestone:1").at(xx, FLOOR, z));
            }
        // ---- the colossus on the plaza ----
        colossus(x, CX, FLOOR, CZ);
        // ---- ruined buildings in the blocks between the streets ----
        Reservations res = new Reservations();
        res.reserve("plaza", Box.of(CX - 13, 0, CZ - 13, CX + 13, 0, CZ + 13));
        res.reserve("lab", Box.of(LAB[0] - 2, 0, LAB[1] - 2, LAB[2] + 2, 0, LAB[3] + 2));
        long s = Hash.salt(x.seed, "city");
        List<int[]> ruins = new ArrayList<>();
        for (int i = 0; i < 3000 && ruins.size() < 40; i++) {
            int w = 7 + (int) (Hash.unit(s, i, 0, 0) * 7), d = 7 + (int) (Hash.unit(s, i, 1, 0) * 7);
            int bx = CX - CITY + 2 + (int) (Hash.unit(s, i, 2, 0) * (2 * CITY - w - 4));
            int bz = CZ - CITY + 2 + (int) (Hash.unit(s, i, 3, 0) * (2 * CITY - d - 4));
            if (!plot(x, bx, bz, w, d)) continue;
            if (!res.reserve("ruin" + i, Box.of(bx - 1, 0, bz - 1, bx + w, 0, bz + d))) continue;
            int h = 6 + (int) (Math.pow(Hash.unit(s, i, 4, 0), 1.6) * 18);
            ruin(x, bx, bz, w, d, h, wall, trim, s + i);
            ruins.add(new int[]{bx, bz, w, d, h});
        }
        // ---- the professor's workshop (intact, lit, with a door) ----
        int lx0 = LAB[0], lz0 = LAB[1], lx1 = LAB[2], lz1 = LAB[3];
        for (int y = FLOOR + 1; y <= FLOOR + 6; y++)
            for (int xx = lx0; xx <= lx1; xx++)
                for (int z = lz0; z <= lz1; z++) {
                    boolean edge = xx == lx0 || xx == lx1 || z == lz0 || z == lz1;
                    x.c.set(xx, y, z, edge ? (y == FLOOR + 3 && (xx + z) % 3 == 0 ? "cyan_stained_glass" : wall.at(xx, y, z)) : "air");
                }
        Structures.box(x, lx0 - 1, FLOOR + 7, lz0 - 1, lx1 + 1, FLOOR + 7, lz1 + 1, "smooth_stone_slab[type=bottom,waterlogged=false]");
        Structures.box(x, lx0, FLOOR + 7, lz0, lx1, FLOOR + 7, lz1, "polished_andesite");
        int dxm = (lx0 + lx1) / 2;
        Structures.air(x, dxm - 1, FLOOR + 1, lz1, dxm + 1, FLOOR + 3, lz1);
        for (int xx = lx0 + 1; xx < lx1; xx += 3) x.c.set(xx, FLOOR + 6, lz0 + 1, "sea_lantern");
        x.c.set(lx0 + 1, FLOOR + 1, lz0 + 1, "crafter[crafting=false,orientation=north_up,triggered=false]");
        x.c.set(lx0 + 2, FLOOR + 1, lz0 + 1, "smithing_table");
        x.c.set(lx1 - 1, FLOOR + 1, lz0 + 1, "lectern[facing=south,has_book=true,powered=false]");
        x.c.set(lx1 - 2, FLOOR + 1, lz0 + 1, "redstone_lamp[lit=true]");
        x.c.set(lx0 + 1, FLOOR + 1, lz1 - 1, "lightning_rod[facing=up,powered=false,waterlogged=false]");
        x.c.set(lx1, FLOOR + 8, lz1, "lightning_rod[facing=up,powered=false,waterlogged=false]");
        l.standAt("npc.professor", dxm, FLOOR, lz1 + 2, 0f);
        x.soul(lx1 - 1, FLOOR, lz1 - 1);
        // ---- street lamps along the cross ----
        for (int k = -CITY + 6; k <= CITY - 6; k += 12) {
            if (Math.abs(k) < 14) continue;
            Builders.lampPost(x.c, CX + 3, FLOOR, CZ + k, "street", "west");
            Builders.lampPost(x.c, CX + k, FLOOR, CZ - 3, "street", "south");
        }
        // a soul inside the first ruin that has a doorway on a street
        if (!ruins.isEmpty()) {
            int[] r = ruins.get(ruins.size() / 2);
            x.soul(r[0] + 1, FLOOR, r[1] + 1);
        }
        l.anchor("structure.lost_relic_city", CX + 0.5, FLOOR + 1, CZ + 15.5, 0f);
        l.zone("precursor_city", "Lost Relic City", 'b', Box.of(CX - CITY - 2, FLOOR, CZ - CITY - 2, CX + CITY + 2, FLOOR + 40, CZ + CITY + 2), null);
        l.area("mobs.relic_city", Box.of(CX - CITY + 1, FLOOR + 1, CZ - CITY + 1, CX + CITY - 1, FLOOR + 3, CZ + CITY - 1));
    }

    /** Is the whole footprint city floor (not lava, not a road)? */
    private static boolean plot(Ctx x, int bx, int bz, int w, int d) {
        for (int z = bz - 1; z <= bz + d; z++)
            for (int xx = bx - 1; xx <= bx + w; xx++) {
                if (!x.g.locked(xx, FLOOR, z) || x.g.fluid(xx, FLOOR, z)) return false;
                double dd = Math.hypot(xx - CX, z - CZ);
                if (Math.abs(xx - CX) <= 3 || Math.abs(z - CZ) <= 3 || (dd >= 31 && dd <= 37.5) || dd <= 13) return false;
                if (Math.hypot(xx - CX, z - CZ) > RAD - 6) return false;
            }
        return true;
    }

    /** A hollow, broken building: walls eroded from the top, gaps punched in, a doorway on each side. */
    private static void ruin(Ctx x, int bx, int bz, int w, int d, int h, Fill wall, Fill trim, long s) {
        for (int xx = bx; xx < bx + w; xx++)
            for (int z = bz; z < bz + d; z++) {
                boolean edge = xx == bx || xx == bx + w - 1 || z == bz || z == bz + d - 1;
                if (!edge) continue;
                int top = FLOOR + h - (int) (Hash.unit(s, xx, 9, z) * Hash.unit(s, xx / 3, 8, z / 3) * h * 0.8);
                for (int y = FLOOR + 1; y <= top; y++) {
                    boolean band = (y - FLOOR) % 5 == 0;
                    boolean window = !band && (y - FLOOR) % 5 == 3 && ((xx + z) & 1) == 0;
                    x.c.set(xx, y, z, band ? trim.at(xx, y, z) : window ? "cyan_stained_glass_pane" : wall.at(xx, y, z));
                }
            }
        // doorways in the middle of each wall
        int mx = bx + w / 2, mz = bz + d / 2;
        Structures.air(x, mx, FLOOR + 1, bz, mx, FLOOR + 2, bz);
        Structures.air(x, mx, FLOOR + 1, bz + d - 1, mx, FLOOR + 2, bz + d - 1);
        Structures.air(x, bx, FLOOR + 1, mz, bx, FLOOR + 2, mz);
        Structures.air(x, bx + w - 1, FLOOR + 1, mz, bx + w - 1, FLOOR + 2, mz);
        // rubble inside and a light
        for (int i = 0; i < 4; i++) {
            int rx = bx + 1 + (int) (Hash.unit(s, i, 1, 2) * (w - 2)), rz = bz + 1 + (int) (Hash.unit(s, i, 2, 2) * (d - 2));
            if (rx == mx || rz == mz) continue;
            x.c.set(rx, FLOOR + 1, rz, Hash.unit(s, i, 3, 2) < 0.5 ? "cobblestone_slab[type=bottom,waterlogged=false]" : "andesite_slab[type=bottom,waterlogged=false]");
        }
        x.c.set(bx + 1, FLOOR + 1, bz + 1, "air");
        x.c.set(bx + w - 2, FLOOR + 1, bz + d - 2, "sea_lantern");
    }

    /** A toppled-over giant automaton still standing in the plaza: legs, a torso, a head with lit eyes. */
    private static void colossus(Ctx x, int cx, int fy, int cz) {
        Fill body = Palette.of("polished_andesite:3,smooth_stone:2,polished_diorite:1");
        Fill dark = Fill.of("polished_deepslate");
        // pedestal
        for (int z = cz - 5; z <= cz + 5; z++)
            for (int xx = cx - 5; xx <= cx + 5; xx++) if (Math.hypot(xx - cx, z - cz) <= 5.3) x.c.set(xx, fy + 1, z, "polished_blackstone_bricks");
        // legs
        Structures.box(x, cx - 3, fy + 2, cz - 1, cx - 2, fy + 9, cz, body);
        Structures.box(x, cx + 2, fy + 2, cz - 1, cx + 3, fy + 9, cz, body);
        Structures.box(x, cx - 3, fy + 2, cz - 1, cx - 2, fy + 2, cz + 1, dark);
        Structures.box(x, cx + 2, fy + 2, cz - 1, cx + 3, fy + 2, cz + 1, dark);
        // torso
        Structures.box(x, cx - 4, fy + 10, cz - 2, cx + 4, fy + 17, cz + 1, body);
        Structures.box(x, cx - 1, fy + 12, cz + 2, cx + 1, fy + 14, cz + 2, "sea_lantern");
        // arms
        Structures.box(x, cx - 6, fy + 11, cz - 1, cx - 5, fy + 17, cz, body);
        Structures.box(x, cx + 5, fy + 11, cz - 1, cx + 6, fy + 17, cz, body);
        Structures.box(x, cx - 6, fy + 9, cz - 1, cx - 5, fy + 10, cz, dark);
        Structures.box(x, cx + 5, fy + 9, cz - 1, cx + 6, fy + 10, cz, dark);
        // head
        Structures.box(x, cx - 2, fy + 18, cz - 2, cx + 2, fy + 22, cz + 1, body);
        x.c.set(cx - 1, fy + 20, cz + 2, "sea_lantern");
        x.c.set(cx + 1, fy + 20, cz + 2, "sea_lantern");
        x.c.set(cx, fy + 23, cz, "lightning_rod[facing=up,powered=false,waterlogged=false]");
        // a fairy soul between its feet
        x.soul(cx, fy + 1, cz + 3);
    }
}
