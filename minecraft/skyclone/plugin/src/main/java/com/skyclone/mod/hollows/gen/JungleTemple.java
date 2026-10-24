package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Trees;

/**
 * The Jungle (north-west): a domed cavern 100 blocks across with a mossy floor, jungle trees and glow
 * vines, a stepped temple in the middle whose shrine holds the Amethyst crystal (the altar spirit
 * wants a Jungle Key), Odala's camp by the east wall, and a pond.
 */
final class JungleTemple {

    private JungleTemple() {}

    static final int CX = -124, CZ = -124, FLOOR = 78, RAD = 50;
    /** Temple footprint: tiers shrink by 3 per side. */
    static final int T0 = -138, T1 = -110, TIER_H = 3, TIERS = 4;
    static final int CAMP_X = -102, CAMP_Z = -146;

    static int floorAt(Ctx x, int xx, int z) {
        if (Math.abs(xx - CX) <= 20 && Math.abs(z - CZ) <= 20) return FLOOR;          // temple plot
        if (xx >= CAMP_X - 6 && xx <= CAMP_X + 10 && z >= CAMP_Z - 4 && z <= CAMP_Z + 12) return FLOOR;   // camp plot
        return FLOOR + (int) Math.round(2.2 * x.noise.fbm2(xx / 26.0, z / 26.0, 2));
    }

    static void prepare(Ctx x) {
        Carve.blob(x, CX, FLOOR + 18, CZ, RAD, 28, RAD, 0.14);
        Carve.floor(x, CX, CZ, RAD - 1, RAD - 1, FLOOR + 10, 8, (xx, z) -> floorAt(x, xx, z));
        // a pond on the west side
        for (int z = -132; z <= -118; z++)
            for (int xx = -164; xx <= -152; xx++) {
                double d = Math.hypot((xx + 158) / 6.5, (z + 125) / 7.5);
                if (d > 1) continue;
                int f = floorAt(x, xx, z);
                x.g.put(xx, f, z, Grid.WATER, true);
                if (d < 0.6) x.g.put(xx, f - 1, z, Grid.WATER, true);
                x.g.put(xx, f - 2, z, Grid.ROCK, true);
            }
        x.structure(Box.of(T0 - 1, FLOOR + 1, T0 - 1, T1 + 1, FLOOR + TIER_H * TIERS + 9, T1 + 1), true);
        x.structure(Box.of(CAMP_X - 1, FLOOR + 1, CAMP_Z - 1, CAMP_X + 8, FLOOR + 9, CAMP_Z + 7), true);
        // paths across the floor: tunnel mouth -> temple stairs, -> camp, -> the lair tunnel
        Carve.tunnel(x, new int[][]{{-96, FLOOR, -98}, {-110, FLOOR, -103}, {-124, FLOOR, -106}}, 1.6, 4, Network.DECK_JUNGLE, false);
        Carve.tunnel(x, new int[][]{{-110, FLOOR, -103}, {-104, FLOOR, -120}, {CAMP_X + 3, FLOOR, CAMP_Z + 9}}, 1.6, 4, Network.DECK_JUNGLE, false);
        Carve.tunnel(x, new int[][]{{-124, FLOOR, -106}, {-142, FLOOR, -104}, {-163, FLOOR, -112}}, 1.6, 4, Network.DECK_JUNGLE, false);
    }

    static void build(Ctx x) {
        Layout l = x.l;
        Palette brick = Palette.of("mossy_stone_bricks:4,stone_bricks:3,cracked_stone_bricks:2,mossy_cobblestone:1");
        // ---- the stepped pyramid ----
        for (int t = 0; t < TIERS; t++) {
            int a = T0 + 3 * t, b = T1 - 3 * t;
            int y0 = FLOOR + 1 + TIER_H * t, y1 = y0 + TIER_H - 1;
            Structures.box(x, a, y0, a, b, y1, b, brick);
        }
        int top = FLOOR + TIER_H * TIERS;                // the top platform surface
        int pa = T0 + 3 * (TIERS - 1), pb = T1 - 3 * (TIERS - 1); // the last tier: -129 .. -119
        // ---- the staircase up the south face (the last step is cut into the platform) ----
        for (int i = 0; i <= TIER_H * TIERS - 1; i++) {
            int z = T1 - i, y = FLOOR + 1 + i;
            for (int xx = CX - 2; xx <= CX + 2; xx++) {
                for (int yy = y; yy <= top + 1; yy++) x.c.set(xx, yy, z, "air");
                x.c.set(xx, y, z, "mossy_stone_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
                for (int yy = FLOOR + 1; yy < y; yy++) x.c.set(xx, yy, z, brick.at(xx, yy, z));
            }
            x.c.set(CX - 3, y + 1, z, "mossy_stone_brick_wall");
            x.c.set(CX + 3, y + 1, z, "mossy_stone_brick_wall");
        }
        // ---- dart traps: dispensers set into the stair railings, alternating sides (fired by the module) ----
        StringBuilder traps = new StringBuilder();
        int[] trapSteps = {2, 5, 8, 10};
        for (int k = 0; k < trapSteps.length; k++) {
            int i = trapSteps[k], z = T1 - i, y = FLOOR + 1 + i;
            boolean west = k % 2 == 0;                       // a west-wall trap shoots east
            int tx = west ? CX - 3 : CX + 3;
            x.c.set(tx, y + 1, z, "dispenser[facing=" + (west ? "east" : "west") + ",triggered=false]");
            x.c.set(tx, y + 2, z, "mossy_stone_brick_wall");
            if (traps.length() > 0) traps.append(';');
            traps.append(tx).append(',').append(y + 1).append(',').append(z).append(',').append(west ? 1 : -1);
        }
        l.data("hollows.temple.traps", traps.toString());
        l.data("hollows.temple.stairs", (CX - 3) + "," + FLOOR + "," + (T1 - TIER_H * TIERS) + "," + (CX + 3) + "," + (top + 2) + "," + (T1 + 1));
        // ---- the shrine on top ----
        int sa = CX - 3, sb = CX + 3, sza = CZ - 3, szb = CZ + 3;
        for (int[] p : new int[][]{{sa, sza}, {sa, szb}, {sb, sza}, {sb, szb}})
            for (int y = top + 1; y <= top + 5; y++) x.c.set(p[0], y, p[1], y == top + 5 ? "chiseled_stone_bricks" : "mossy_stone_brick_wall");
        for (int xx = sa; xx <= sb; xx++)
            for (int z = sza; z <= szb; z++) x.c.set(xx, top + 6, z, (xx == sa || xx == sb || z == sza || z == szb) ? "mossy_stone_brick_slab[type=bottom,waterlogged=false]" : "mossy_stone_bricks");
        x.c.set(CX, top + 7, CZ, "mossy_stone_brick_slab[type=bottom,waterlogged=false]");
        x.c.set(CX, top + 1, CZ - 2, "chiseled_stone_bricks");
        x.c.set(CX, top + 2, CZ - 2, "amethyst_cluster[facing=up,waterlogged=false]");
        x.c.set(CX - 1, top + 1, CZ - 2, "candle[candles=3,lit=true,waterlogged=false]");
        x.c.set(CX + 1, top + 1, CZ - 2, "candle[candles=2,lit=true,waterlogged=false]");
        x.c.set(CX, top + 5, CZ, "lantern[hanging=true,waterlogged=false]");
        for (int[] p : new int[][]{{pa, pa}, {pa, pb}, {pb, pa}, {pb, pb}}) {
            x.c.set(p[0], top + 1, p[1], "mossy_stone_brick_wall");
            x.c.set(p[0], top + 2, p[1], "lantern[hanging=false,waterlogged=false]");
        }
        l.standAt("npc.temple_altar", CX, top, CZ, 180f);
        l.anchor("structure.jungle_temple", CX + 0.5, top + 1, CZ + 0.5, 0f);
        inner(x, brick);
        // ---- a hidden room inside the pyramid, reached from the east side ----
        int ry = FLOOR + 1;
        for (int xx = T1 - 8; xx <= T1; xx++) Structures.air(x, xx, ry, CZ, xx, ry + 1, CZ + 1);
        Structures.air(x, T1 - 13, ry, CZ - 2, T1 - 9, ry + 2, CZ + 3);
        x.c.set(T1 - 11, ry + 2, CZ - 2, "lantern[hanging=true,waterlogged=false]");
        x.c.set(T1 - 13, ry, CZ + 3, "chest[facing=east,type=single,waterlogged=false]");
        x.c.set(T1 - 13, ry, CZ - 2, "decorated_pot[facing=east,cracked=false,waterlogged=false]");
        x.soul(T1 - 10, FLOOR, CZ + 2);
        x.soul(CX, top, CZ - 5);
        // ---- vines and leaves on the temple flanks ----
        long vs = Hash.salt(x.seed, "jvine");
        for (int t = 0; t < TIERS; t++) {
            int a = T0 + 3 * t, b = T1 - 3 * t, y1 = FLOOR + TIER_H * (t + 1);
            for (int i = a; i <= b; i++) {
                if (Hash.unit(vs, i, t, 0) < 0.3) x.c.set(i, y1, a - 1, "vine[north=false,east=false,south=true,west=false,up=false]");
                if (Hash.unit(vs, i, t, 1) < 0.3) x.c.set(a - 1, y1, i, "vine[north=false,east=true,south=false,west=false,up=false]");
                if (Hash.unit(vs, i, t, 2) < 0.15 && !(i >= CX - 3 && i <= CX + 3)) x.c.set(i, y1 + 1, b - 1, "jungle_leaves[persistent=true,distance=1,waterlogged=false]");
            }
        }
        // ---- Odala's camp ----
        Builders.House hut = new Builders.House(CAMP_X, FLOOR, CAMP_Z, 7, 6);
        hut.door = "south";
        hut.wall = Palette.of("jungle_planks:4,stripped_jungle_log[axis=y]:1");
        hut.frame = "jungle_log";
        hut.roof = "jungle";
        hut.roofStyle = "gable";
        hut.foundation = Fill.of("mossy_cobblestone");
        hut.floor = Fill.of("jungle_planks");
        hut.seed = x.seed;
        hut.build(x.c);
        Builders.campfire(x.c, CAMP_X - 3, FLOOR, CAMP_Z + 10);
        Builders.crates(x.c, CAMP_X + 7, FLOOR, CAMP_Z + 8, x.seed);
        l.standAt("npc.odala", hut.doorStep[0] + 1, hut.doorStep[1], hut.doorStep[2] + 1, 180f);
        l.standAt("npc.tribal_trader", CAMP_X - 6, FLOOR, CAMP_Z + 10, -90f);
        x.soul(hut.inside[0], hut.inside[1], hut.inside[2]);
        // ---- jungle trees on the cavern floor ----
        long ts = Hash.salt(x.seed, "jtree");
        for (int gz = CZ - RAD; gz <= CZ + RAD; gz += 9)
            for (int gx = CX - RAD; gx <= CX + RAD; gx += 9) {
                int tx = gx + (int) (Hash.unit(ts, gx, 0, gz) * 6), tz = gz + (int) (Hash.unit(ts, gx, 1, gz) * 6);
                if (Math.hypot(tx - CX, tz - CZ) > RAD - 8) continue;
                if (Hash.unit(ts, gx, 2, gz) > 0.75) continue;
                if (x.inStruct(tx, FLOOR + 2, tz, 5)) continue;
                int f = x.g.floorBelow(tx, FLOOR + 6, tz);
                if (f < FLOOR - 4 || !x.g.air(tx, f + 1, tz) || x.g.fluid(tx, f, tz)) continue;
                if (nearWalk(x, tx, f, tz, 5)) continue;
                if (!x.g.air(tx, f + 18, tz)) continue;
                Trees.grow(x.c, Hash.unit(ts, gx, 3, gz) < 0.7 ? "jungle" : "big_oak", tx, f, tz, x.seed + gx * 31L + gz, 0.9 + Hash.unit(ts, gx, 4, gz) * 0.5);
            }
        // the zone, the mob grounds, a sign of the dragon's tunnel
        l.zone("jungle_temple", "Jungle Temple", 'a', Box.of(T0 - 2, FLOOR, T0 - 2, T1 + 2, FLOOR + 30, T1 + 2), null);
        l.zone("jungle_camp", "Odala's Camp", 'a', Box.of(CAMP_X - 2, FLOOR, CAMP_Z - 2, CAMP_X + 9, FLOOR + 10, CAMP_Z + 12), null, Layout.Zone.SAFE);
        l.area("mobs.jungle", Box.of(CX - 45, FLOOR - 2, CZ - 45, CX + 45, FLOOR + 5, CZ + 45));
        l.area("mobs.temple_court", Box.of(T0 - 8, FLOOR - 1, T1 + 2, T1 + 8, FLOOR + 4, T1 + 12));
        l.anchor("landmark.jungle", CX + 0.5, FLOOR + 20, CZ + 0.5, 0f);
    }

    // ---- the inner temple (wave 5) -------------------------------------------------------------
    // Reference: the Jungle Key opens the temple's entrance; inside is a parkour over a pit and the
    // Kalhuiki Door Guardian in front of the crystal. Ours is SEALED inside the pyramid's lower tiers:
    // the shrine on top (npc.temple_altar) takes the key and lets the player down to parkour.start,
    // a fall into the pit sends them back to the last checkpoint, and the sanctum's exit stone carries
    // them out to the court. Nothing outside connects to it, so it can only be entered through the key.

    /** Parkour hall (start pad + pit) and sanctum boxes. Interior air is y FLOOR+1 .. FLOOR+5 (tier 1's top is the ceiling). */
    static final int HALL_X0 = -134, HALL_X1 = -129, PAD_Z0 = -119, PAD_Z1 = -114, PIT_Z0 = -127, PIT_Z1 = -120;
    static final int SAN_X0 = -134, SAN_X1 = -114, SAN_Z0 = -134, SAN_Z1 = -128;
    /** Parkour platforms {x, top-above-FLOOR, z}: west-east zig-zag across the pit, the last one steps into the sanctum. */
    static final int[][] PLATFORMS = {{-131, 0, -121}, {-133, 1, -123}, {-130, 1, -124}, {-132, 2, -126}, {-129, 1, -127}};
    /** The platform that holds the checkpoint plate (index into PLATFORMS). */
    static final int CHECKPOINT = 2;

    static void inner(Ctx x, Palette brick) {
        Layout l = x.l;
        int iy0 = FLOOR + 1, iy1 = FLOOR + 5, bottom = FLOOR - 5;
        Palette floor = Palette.of("mossy_stone_bricks:3,stone_bricks:3,cracked_stone_bricks:1,chiseled_stone_bricks:1");
        // the pit: lined walls and a mossy bottom, open from bottom+1 up to the ceiling
        Structures.box(x, HALL_X0 - 1, bottom, PIT_Z0 - 1, HALL_X1 + 1, FLOOR, PIT_Z1 + 1, brick);
        Structures.box(x, HALL_X0, bottom, PIT_Z0, HALL_X1, bottom, PIT_Z1, "mossy_cobblestone");
        Structures.air(x, HALL_X0, bottom + 1, PIT_Z0, HALL_X1, iy1, PIT_Z1);
        // the start pad and the sanctum: stone floors, five blocks of air
        Structures.box(x, HALL_X0, FLOOR, PAD_Z0, HALL_X1, FLOOR, PAD_Z1, floor);
        Structures.air(x, HALL_X0, iy0, PAD_Z0, HALL_X1, iy1, PAD_Z1);
        Structures.box(x, SAN_X0, FLOOR, SAN_Z0, SAN_X1, FLOOR, SAN_Z1, Palette.of("polished_andesite:3,chiseled_stone_bricks:1,mossy_stone_bricks:2"));
        Structures.air(x, SAN_X0, iy0, SAN_Z0, SAN_X1, iy1, SAN_Z1);
        // platforms: pillars standing in the pit
        for (int[] pl : PLATFORMS) {
            int topY = FLOOR + pl[1];
            for (int y = bottom + 1; y <= topY; y++) x.c.set(pl[0], y, pl[2], y == topY ? "chiseled_stone_bricks" : "mossy_stone_bricks");
        }
        int[] cp = PLATFORMS[CHECKPOINT];
        x.c.set(cp[0], FLOOR + cp[1] + 1, cp[2], "light_weighted_pressure_plate[power=0]");
        // an escape ladder out of the pit onto the start pad (the module sends fallers back anyway)
        for (int y = bottom + 1; y <= FLOOR; y++) x.c.set(HALL_X0, y, PIT_Z1, "ladder[facing=north,waterlogged=false]");
        // light: glow blocks set into the walls (never hanging over a jump)
        for (int z : new int[]{-125, -117}) x.c.set(HALL_X0 - 1, FLOOR + 3, z, "shroomlight");
        for (int z : new int[]{-123, -116}) x.c.set(HALL_X1 + 1, FLOOR + 3, z, "shroomlight");
        for (int xx = SAN_X0 + 2; xx <= SAN_X1 - 1; xx += 5) {
            if (xx > HALL_X1 + 1) x.c.set(xx, FLOOR + 3, SAN_Z1 + 1, "shroomlight");   // west of that the south side is the open pit
            x.c.set(xx, FLOOR + 3, SAN_Z0 - 1, "shroomlight");
        }
        x.c.set(HALL_X0, iy0, PAD_Z1, "candle[candles=4,lit=true,waterlogged=false]");
        x.c.set(HALL_X1, iy0, PAD_Z1, "candle[candles=3,lit=true,waterlogged=false]");
        // the Tidecarver door on the sanctum's north wall, the crystal on a pedestal in front of it
        int dx0 = -122, dx1 = -118, dz = SAN_Z0 - 1;
        for (int y = iy0; y <= iy1; y++)
            for (int xx = dx0; xx <= dx1; xx++) {
                boolean frame = xx == dx0 || xx == dx1 || y == iy1;
                x.c.set(xx, y, dz, frame ? "chiseled_stone_bricks" : (xx == -120 && y == iy0 + 1 ? "amethyst_block" : "crying_obsidian"));
            }
        x.c.set(-120, iy0, SAN_Z0 + 1, "chiseled_stone_bricks");
        x.c.set(-120, iy0 + 1, SAN_Z0 + 1, "amethyst_cluster[facing=up,waterlogged=false]");
        x.c.set(-122, iy0, SAN_Z0, "candle[candles=2,lit=true,waterlogged=false]");
        x.c.set(-118, iy0, SAN_Z0, "candle[candles=2,lit=true,waterlogged=false]");
        // the exit stone at the sanctum's east end
        x.c.set(SAN_X1 - 1, FLOOR, -131, "lodestone");
        x.c.set(SAN_X1, iy0, -133, "lantern[hanging=false,waterlogged=false]");
        x.c.set(SAN_X1, iy0, -129, "lantern[hanging=false,waterlogged=false]");
        // what the module reads
        l.standAt("parkour.start", -131, FLOOR, -116, 180f);
        l.standAt("parkour.cp.1", cp[0], FLOOR + cp[1], cp[2], 180f);
        l.standAt("temple.guardian", -120, FLOOR, -131, 90f);
        l.standAt("temple.exit_to", CX, FLOOR, T1 + 4, 0f);
        l.area("temple.hall", Box.of(HALL_X0, bottom, PIT_Z0, HALL_X1, iy1, PAD_Z1));
        l.area("temple.pit", Box.of(HALL_X0, bottom, PIT_Z0, HALL_X1, FLOOR, PIT_Z1));
        l.area("temple.sanctum", Box.of(SAN_X0, iy0, SAN_Z0, SAN_X1, iy1, SAN_Z1));
        l.area("temple.exit", Box.of(SAN_X1 - 2, iy0, -132, SAN_X1, iy0 + 1, -130));
        l.zone("jungle_temple_inner", "Inner Temple", '5', Box.of(SAN_X0, bottom, SAN_Z0, HALL_X1 + 1, iy1, PAD_Z1), null);
        l.zone("jungle_temple_sanctum", "Tidecarver Sanctum", '5', Box.of(SAN_X0, iy0, SAN_Z0, SAN_X1, iy1, SAN_Z1), null);
        l.data("hollows.temple.inner", "1");
    }

    static boolean nearWalk(Ctx x, int px, int fy, int pz, int r) {
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                for (int y = fy - 2; y <= fy + 3; y++)
                    if (x.g.locked(px + dx, y, pz + dz) && x.g.air(px + dx, y, pz + dz)) return true;
        return false;
    }
}
