package com.skyclone.mod.dungeons.gen.arena;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Palette;
import com.skyclone.mod.dungeons.ext.BossArena;
import com.skyclone.mod.dungeons.model.FloorDef;

/**
 * Floor VII: the withered vault, five sealed chambers the party is moved between (research 04 sec 3.13):
 * <pre>
 *   z   0..63   P1 Gravax's foundry (x 0..63)        | P2 Tempestra's crusher deck (x 64..159)
 *   z  64..135  P3 Aurik's gauntlet: four east-west corridors (S1..S4) joined by gates, and the core room (x 0..29)
 *   z 136..199  P4 Morvane's lava ring (x 0..79)      | P5 the Hollow King's throne room (x 80..159, Master Mode)
 * </pre>
 * Every device wall faces SOUTH, so runtime blocks (buttons, levers, frames, lamps) all attach the same way.
 * Points (template coordinates; see the fight for what each does): spawn, boss, chests, p1.*, p2.*, p3.*, p4.*, p5.*.
 */
public final class WitherArena implements BossArena {

    public static final WitherArena INSTANCE = new WitherArena();
    /** Corridor k (0..3) of P3: wall z at {@code CZ[k]} (north) and {@code CZ[k] + 14} (south). */
    public static final int[] CZ = {66, 83, 100, 117};
    public static final int CX0 = 30, CX1 = 149;
    /** Terminal x positions per section (S2 has five, research). */
    public static final int[][] TERMS = {{50, 72, 110, 132}, {44, 62, 108, 122, 138}, {50, 72, 110, 132}, {50, 72, 110, 132}};
    public static final int[] LEVERS = {60, 120};
    public static final int DEVICE_X = 88;
    public static final String[] RELICS = {"apex", "flame", "ice", "power", "soul"};

    @Override public String id() { return "f7_withered_vault"; }

    @Override public int sizeX() { return 160; }

    @Override public int sizeZ() { return 200; }

    @Override
    public void build(VoxelCanvas c, int ox, int fy, int oz, long seed, FloorDef floor, Points out) {
        Palette wall = Palette.of("polished_blackstone_bricks:5,cracked_polished_blackstone_bricks:2,blackstone:2,gilded_blackstone:1");
        Palette trim = Palette.of("polished_blackstone:3,chiseled_polished_blackstone:1");
        Palette ceil = Palette.of("blackstone:3,polished_blackstone_bricks:2");
        p1(c, ox, fy, oz, wall, trim, ceil, out);
        p2(c, ox, fy, oz, wall, trim, ceil, out);
        p3(c, ox, fy, oz, wall, trim, ceil, out);
        p4(c, ox, fy, oz, wall, trim, out);
        p5(c, ox, fy, oz, wall, trim, ceil, out);
        int[] s = out.get("p1.spawn");
        out.put("spawn", s[0], s[1], s[2]);
        int[] b = out.get("p1.boss");
        out.put("boss", b[0], b[1], b[2]);
        int[] ch = out.get(floor != null && floor.master ? "p5.chests" : "p4.chests");
        out.put("chests", ch[0], ch[1], ch[2]);
    }

    // ---- P1: the foundry. Two ledges hold the energy crystals, two podiums on the floor take them --------------
    private void p1(VoxelCanvas c, int ox, int fy, int oz, Palette wall, Palette trim, Palette ceil, Points out) {
        int x0 = ox, x1 = ox + 63, z0 = oz, z1 = oz + 63, cx = ox + 32, cz = oz + 32;
        ArenaKit.hall(c, x0, z0, x1, z1, fy, 24, Palette.of("polished_blackstone:3,iron_block:1,polished_basalt[axis=y]:1"), wall, trim, ceil, "shroomlight", 6);
        // hazard stripes across the floor
        for (int x = x0 + 1; x < x1; x++) { c.set(x, fy, cz - 10, ((x / 2) & 1) == 0 ? "yellow_concrete" : "black_concrete"); c.set(x, fy, cz + 10, ((x / 2) & 1) == 0 ? "yellow_concrete" : "black_concrete"); }
        for (int side = 0; side < 2; side++) {
            int lx0 = side == 0 ? x0 + 1 : x1 - 9, lx1 = side == 0 ? x0 + 9 : x1 - 1;
            ArenaKit.fill(c, lx0, fy + 1, cz - 12, lx1, fy + 8, cz + 8, "polished_blackstone_bricks");
            ArenaKit.fill(c, lx0, fy + 8, cz - 12, lx1, fy + 8, cz + 8, Palette.of("polished_blackstone:3,iron_block:1"));
            int sx0 = side == 0 ? x0 + 3 : x1 - 7, sx1 = sx0 + 4;
            ArenaKit.stairs(c, sx0, sx1, cz + 16, fy + 1, 8, "north", "polished_blackstone_brick_stairs", "polished_blackstone_bricks");
            int crx = side == 0 ? x0 + 5 : x1 - 5;
            c.set(crx + (side == 0 ? 1 : -1), fy + 9, cz - 8, "iron_bars");
            c.set(crx, fy + 8, cz - 6, "crying_obsidian");
            out.put("p1.crystal." + (side == 0 ? "a" : "b"), crx, fy + 9, cz - 6);
            int px = side == 0 ? cx - 14 : cx + 14;
            ArenaKit.fill(c, px - 1, fy, cz - 1, px + 1, fy, cz + 1, "quartz_block");
            c.set(px, fy + 1, cz, "lodestone");
            out.put("p1.podium." + (side == 0 ? "a" : "b"), px, fy + 1, cz);
        }
        // the laser focus on the floor's centre
        ArenaKit.disc(c, cx, cz, 2.5, fy, Palette.of("magenta_glazed_terracotta"));
        c.set(cx, fy, cz, "beacon");
        c.set(cx, fy - 1, cz, "iron_block");
        out.put("p1.laser", cx, fy + 1, cz);
        out.put("p1.spawn", cx, fy + 1, z1 - 5);
        out.put("p1.boss", cx, fy + 1, cz - 14);
        out.put("p1.exit", cx, fy + 1, z1 - 5);
    }

    // ---- P2: the crusher deck. Three crushers hang over marked squares; a pad in a corner drops each -------------
    private void p2(VoxelCanvas c, int ox, int fy, int oz, Palette wall, Palette trim, Palette ceil, Points out) {
        int x0 = ox + 64, x1 = ox + 159, z0 = oz, z1 = oz + 63, cx = ox + 112, cz = oz + 32;
        ArenaKit.hall(c, x0, z0, x1, z1, fy, 18, Palette.of("polished_andesite:3,andesite:2,polished_diorite:1"), wall, trim, ceil, "sea_lantern", 6);
        // a lava gutter along the long walls (does not touch the pads or the crush squares)
        for (int x = x0 + 1; x < x1; x++) { c.set(x, fy, z0 + 1, "magma_block"); c.set(x, fy, z1 - 1, "magma_block"); }
        int[][] crush = {{x0 + 20, cz - 14}, {x1 - 20, cz - 14}, {x0 + 20, cz + 14}, {x1 - 20, cz + 14}};
        int[][] pads = {{x0 + 4, z0 + 4}, {x1 - 4, z0 + 4}, {x0 + 4, z1 - 4}, {x1 - 4, z1 - 4}};
        for (int i = 0; i < 4; i++) {
            int qx = crush[i][0], qz = crush[i][1];
            boolean broken = i == 3;
            ArenaKit.fill(c, qx - 3, fy, qz - 3, qx + 3, fy, qz + 3, broken ? "red_concrete" : "polished_diorite");
            ArenaKit.fill(c, qx - 2, fy + 11, qz - 2, qx + 2, fy + 17, qz + 2, broken ? "red_terracotta" : "diorite");
            ArenaKit.fill(c, qx - 2, fy + 10, qz - 2, qx + 2, fy + 10, qz + 2, broken ? "air" : "polished_diorite");
            if (broken) { c.set(qx - 1, fy + 1, qz, "red_terracotta"); c.set(qx + 2, fy + 1, qz + 1, "red_terracotta"); out.put("p2.broken", qx, fy + 1, qz); }
            else out.put("p2.crusher." + i, qx, fy + 1, qz);
            int px = pads[i][0], pz = pads[i][1];
            ArenaKit.fill(c, px - 1, fy, pz - 1, px + 1, fy, pz + 1, broken ? "gray_concrete" : "lime_concrete");
            if (!broken) out.put("p2.pad." + i, px, fy + 1, pz);
        }
        out.put("p2.spawn", cx, fy + 1, cz + 4);
        out.put("p2.boss", cx, fy + 1, cz - 4);
    }

    // ---- P3: four corridors with terminals, levers and a device; gates between them; the core room ----------------
    private void p3(VoxelCanvas c, int ox, int fy, int oz, Palette wall, Palette trim, Palette ceil, Points out) {
        // the whole block solid first, then carve
        ArenaKit.fill(c, ox, fy - 2, oz + 64, ox + 159, fy - 1, oz + 135, "stone");
        ArenaKit.fill(c, ox, fy, oz + 64, ox + 159, fy + 8, oz + 135, wall);
        Palette fl = Palette.of("gold_block:1,polished_blackstone:4,smooth_basalt:2");
        for (int k = 0; k < 4; k++) {
            int zA = oz + CZ[k], zB = zA + 14;
            ArenaKit.fill(c, ox + CX0 + 1, fy + 1, zA + 1, ox + CX1 - 1, fy + 7, zB - 1, "air");
            ArenaKit.fill(c, ox + CX0 + 1, fy, zA + 1, ox + CX1 - 1, fy, zB - 1, fl);
            for (int x = ox + CX0 + 4; x < ox + CX1; x += 6) c.set(x, fy + 8, zA + 7, "glowstone");
            // TNT racks above (decoration: research "TNT traps overhead")
            for (int x = ox + CX0 + 9; x < ox + CX1; x += 18) c.set(x, fy + 7, zB - 1, "tnt");
            int s = k + 1;
            int[] terms = TERMS[k];
            for (int t = 0; t < terms.length; t++) {
                int tx = ox + terms[t];
                c.set(tx, fy + 1, zA, "chiseled_quartz_block");
                c.set(tx, fy + 2, zA, "chiseled_quartz_block");
                out.put("p3.s" + s + ".term." + t, tx, fy + 1, zA + 1);
            }
            for (int l = 0; l < LEVERS.length; l++) {
                int lx = ox + LEVERS[l];
                c.set(lx, fy + 2, zA, "gold_block");
                out.put("p3.s" + s + ".lever." + l, lx, fy + 2, zA + 1);
            }
            // the device wall: a 7 wide, 6 high panel of smooth stone in the north wall
            int dx = ox + DEVICE_X;
            ArenaKit.fill(c, dx - 1, fy + 1, zA, dx + 5, fy + 7, zA, "smooth_stone");
            out.put("p3.s" + s + ".device", dx, fy + 2, zA);
            out.put("p3.s" + s + ".stand", dx + 2, fy + 1, zA + (k == 3 ? 12 : 4));
            // gates: S1 east -> S2, S2 west -> S3, S3 east -> S4, S4 west -> core
            if (k < 3) {
                boolean east = k % 2 == 0;
                int gx0 = east ? ox + CX1 - 8 : ox + CX0 + 3, gx1 = gx0 + 4;
                int gz0 = zB, gz1 = oz + CZ[k + 1];
                ArenaKit.fill(c, gx0, fy + 1, gz0, gx1, fy + 5, gz1, "air");
                ArenaKit.fill(c, gx0, fy, gz0, gx1, fy, gz1, fl);
                ArenaKit.fill(c, gx0, fy + 1, gz0 + 1, gx1, fy + 5, gz0 + 1, "iron_bars");
                out.put("p3.s" + s + ".gate.a", gx0, fy + 1, gz0 + 1);
                out.put("p3.s" + s + ".gate.b", gx1, fy + 5, gz0 + 1);
                out.put("p3.s" + (s + 1) + ".entry", gx0 + 2, fy + 1, gz1 + 2);
            }
        }
        // the core room, west of the corridors, reached from S4's west end
        int cx0 = ox + 3, cx1 = ox + 27, cz0 = oz + 67, cz1 = oz + 130;
        ArenaKit.fill(c, cx0, fy + 1, cz0, cx1, fy + 7, cz1, "air");
        ArenaKit.fill(c, cx0, fy, cz0, cx1, fy, cz1, Palette.of("gold_block:2,polished_blackstone:3"));
        for (int z = cz0 + 3; z < cz1; z += 6) c.set(ox + 15, fy + 8, z, "glowstone");
        int sz = oz + CZ[3] + 5;
        ArenaKit.fill(c, ox + 28, fy + 1, sz, ox + CX0 + 1, fy + 5, sz + 4, "air");
        ArenaKit.fill(c, ox + 28, fy, sz, ox + CX0 + 1, fy, sz + 4, fl);
        ArenaKit.fill(c, ox + 29, fy + 1, sz, ox + 29, fy + 5, sz + 4, "iron_bars");
        out.put("p3.s4.gate.a", ox + 29, fy + 1, sz);
        out.put("p3.s4.gate.b", ox + 29, fy + 5, sz + 4);
        out.put("p3.core", ox + 15, fy + 1, oz + 98);
        out.put("p3.spawn", ox + CX0 + 10, fy + 1, oz + CZ[0] + 7);
        out.put("p3.boss", ox + CX0 + 3, fy + 1, oz + CZ[0] + 7);
    }

    // ---- P4: Morvane's platform, ringed by lava ------------------------------------------------------------
    private void p4(VoxelCanvas c, int ox, int fy, int oz, Palette wall, Palette trim, Points out) {
        int cx = ox + 40, cz = oz + 168;
        // one foundation slab under the chamber ties it to the rest of the vault (no free-floating pieces)
        ArenaKit.fill(c, ox, fy - 3, oz + 135, ox + 80, fy - 3, oz + 199, "deepslate");
        ArenaKit.round(c, cx, cz, 30, fy, 22, Palette.of("polished_blackstone:3,obsidian:1,crying_obsidian:1"), wall, trim, Palette.of("blackstone"));
        for (int x = cx - 30; x <= cx + 30; x++)
            for (int z = cz - 30; z <= cz + 30; z++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > 25 && d <= 29) { c.set(x, fy, z, "lava"); c.set(x, fy - 1, z, "obsidian"); }
            }
        for (int k = 0; k < 16; k++) {
            double a = Math.PI * 2 * k / 16;
            c.set(cx + (int) Math.round(Math.cos(a) * 30), fy + 6, cz + (int) Math.round(Math.sin(a) * 30), "shroomlight");
            c.set(cx + (int) Math.round(Math.cos(a) * 12), fy + 22, cz + (int) Math.round(Math.sin(a) * 12), "glowstone");
        }
        out.put("p4.spawn", cx, fy + 1, cz + 20);
        out.put("p4.boss", cx, fy + 1, cz);
        out.put("p4.chests", cx, fy + 1, cz - 12);
    }

    // ---- P5: the throne room. Five coloured statues, five cauldrons, five relics -------------------------------
    private void p5(VoxelCanvas c, int ox, int fy, int oz, Palette wall, Palette trim, Palette ceil, Points out) {
        int x0 = ox + 80, x1 = ox + 159, z0 = oz + 136, z1 = oz + 199, cx = ox + 120, cz = oz + 170;
        ArenaKit.hall(c, x0, z0, x1, z1, fy, 26, Palette.of("blackstone:3,polished_blackstone:2,gilded_blackstone:1"), wall, trim, ceil, "shroomlight", 6);
        String[] concrete = {"lime_concrete", "orange_concrete", "light_blue_concrete", "red_concrete", "purple_concrete"};
        for (int i = 0; i < 5; i++) {
            double a = Math.PI + Math.PI * i / 4;           // a half circle along the north and the sides
            int sx = cx + (int) Math.round(Math.cos(a) * 30), sz = cz + (int) Math.round(Math.sin(a) * 24);
            ArenaKit.fill(c, sx - 1, fy + 1, sz - 1, sx + 1, fy + 9, sz + 1, concrete[i]);
            c.set(sx, fy + 10, sz, "wither_skeleton_skull[rotation=0,powered=false]");
            out.put("p5.statue." + RELICS[i], sx, fy + 1, sz);
            int kx = cx + (int) Math.round(Math.cos(a) * 7), kz = cz + (int) Math.round(Math.sin(a) * 7);
            c.set(kx, fy, kz, concrete[i]);
            c.set(kx, fy + 1, kz, "cauldron");
            out.put("p5.cauldron." + RELICS[i], kx, fy + 1, kz);
            double b = a + Math.PI;
            int rx = cx + (int) Math.round(Math.cos(b) * 16), rz = cz + 6 + (int) Math.round(Math.sin(b) * 10);
            out.put("p5.relic." + RELICS[i], rx, fy + 1, rz);
        }
        ArenaKit.fill(c, cx - 4, fy + 1, z0 + 1, cx + 4, fy + 3, z0 + 5, "polished_blackstone_bricks");
        ArenaKit.fill(c, cx - 1, fy + 4, z0 + 1, cx + 1, fy + 12, z0 + 2, "gilded_blackstone");
        out.put("p5.king", cx, fy + 4, z0 + 4);
        out.put("p5.spawn", cx, fy + 1, z1 - 5);
        out.put("p5.chests", cx, fy + 1, z1 - 14);
    }
}
