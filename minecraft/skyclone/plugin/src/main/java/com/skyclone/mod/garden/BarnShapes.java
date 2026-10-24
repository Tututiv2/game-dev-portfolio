package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Fill;

import static com.skyclone.mod.garden.BarnBuilder.WALL_TOP;
import static com.skyclone.mod.garden.BarnBuilder.X0;
import static com.skyclone.mod.garden.BarnBuilder.X1;
import static com.skyclone.mod.garden.BarnBuilder.Z0;
import static com.skyclone.mod.garden.BarnBuilder.Z1;

/**
 * The barn's roofs other than the gambrel (wave 5): each barn skin names a SHAPE, and the shape is what
 * makes it a different building. Pure Java, everything inside {@link BarnBuilder#BOX}; nothing here
 * touches the walls below {@link BarnBuilder#WALL_TOP} except a porch / chimney / turret in the margin, so
 * the Desk, the loft and every anchor stay where they are.
 */
final class BarnShapes {

    private BarnShapes() {}

    private static final int CZ = (Z0 + Z1) / 2;   // -25, the ridge line

    /** Stable 0..n-1 per block (moss patches, spots), so a skin always builds the same. */
    private static int hash(int x, int y, int z, int n) {
        int h = x * 73_856_093 ^ y * 19_349_663 ^ z * 83_492_791;
        h ^= h >>> 13;
        return Math.floorMod(h, n);
    }

    private static void setIfAir(VoxelCanvas c, int x, int y, int z, String b) { if (c.isAir(x, y, z)) c.set(x, y, z, b); }

    // =====================================================================================
    // gable: a plain 1:1 pitched roof, ridge along x, gable ends on the east and west walls
    // =====================================================================================

    static int gable(VoxelCanvas c, BarnBuilder.Skin s, Fill wall) {
        String st = s.roof(), full = s.roofFull();
        int vMin = Z0 - 1, vMax = Z1 + 1, uMin = X0 - 1, uMax = X1 + 1;
        int top = WALL_TOP;
        for (int k = 0; ; k++) {
            int vl = vMin + k, vr = vMax - k, yy = WALL_TOP + k;
            if (vl > vr) break;
            top = yy;
            for (int u = uMin; u <= uMax; u++) {
                if (vl == vr) { c.set(u, yy, vl, full); continue; }
                c.set(u, yy, vl, Blocks.stairs(st, "south"));
                c.set(u, yy, vr, Blocks.stairs(st, "north"));
                // a solid course under the next row, so the roof reads closed from inside the barn
                if (k > 0 && vl + 1 < vr) { setIfAir(c, u, yy, vl + 1, full); setIfAir(c, u, yy, vr - 1, full); }
                if (vl + 1 == vr) {
                    c.set(u, yy + 1, vl, Blocks.slab(st, "bottom"));
                    c.set(u, yy + 1, vr, Blocks.slab(st, "bottom"));
                    top = yy + 1;
                }
            }
            if (vl == vr || vl + 1 == vr) break;
        }
        gableEnds(c, wall, top);
        // a round window high in each gable
        c.set(X0, WALL_TOP + 4, CZ, "glass_pane");
        c.set(X1, WALL_TOP + 4, CZ, "glass_pane");
        return top;
    }

    /** Close the triangles on the east / west walls under whatever roof is above them. */
    private static void gableEnds(VoxelCanvas c, Fill wall, int top) {
        for (int gy = WALL_TOP + 1; gy <= top; gy++)
            for (int v = Z0; v <= Z1; v++)
                for (int gx : new int[]{X0, X1})
                    if (BarnBuilder.roofAbove(c, gx, gy, v) && c.isAir(gx, gy, v)) c.set(gx, gy, v, wall.at(gx, gy, v));
    }

    // =====================================================================================
    // hip: slopes on all four sides (shared by the pagoda's two roofs)
    // =====================================================================================

    static int hip(VoxelCanvas c, BarnBuilder.Skin s, Fill wall) {
        return hipRoof(c, s, X0 - 1, X1 + 1, Z0 - 1, Z1 + 1, WALL_TOP);
    }

    /** A hip roof over u0..u1 x v0..v1 starting at y0; returns its top y. */
    static int hipRoof(VoxelCanvas c, BarnBuilder.Skin s, int u0, int u1, int v0, int v1, int y0) {
        String st = s.roof(), full = s.roofFull();
        int top = y0;
        for (int k = 0; ; k++) {
            int ul = u0 + k, ur = u1 - k, vl = v0 + k, vr = v1 - k, yy = y0 + k;
            if (ul > ur || vl > vr) break;
            top = yy;
            if (vl == vr || ul == ur) {                       // the ridge
                for (int u = ul; u <= ur; u++) for (int v = vl; v <= vr; v++) c.set(u, yy, v, full);
                break;
            }
            if (vl + 1 == vr || ul + 1 == ur) {               // a two-wide ridge: slabs on a full course
                for (int u = ul; u <= ur; u++) for (int v = vl; v <= vr; v++) { c.set(u, yy, v, full); c.set(u, yy + 1, v, Blocks.slab(st, "bottom")); }
                top = yy + 1;
                break;
            }
            for (int u = ul; u <= ur; u++) {
                c.set(u, yy, vl, Blocks.stairs(st, "south"));
                c.set(u, yy, vr, Blocks.stairs(st, "north"));
            }
            for (int v = vl + 1; v <= vr - 1; v++) {
                c.set(ul, yy, v, Blocks.stairs(st, "east"));
                c.set(ur, yy, v, Blocks.stairs(st, "west"));
            }
            // a solid course just inside each ring: it carries the next ring, so the roof is one connected piece
            for (int u = ul + 1; u <= ur - 1; u++) { setIfAir(c, u, yy, vl + 1, full); setIfAir(c, u, yy, vr - 1, full); }
            for (int v = vl + 1; v <= vr - 1; v++) { setIfAir(c, ul + 1, yy, v, full); setIfAir(c, ur - 1, yy, v, full); }
        }
        return top;
    }

    // =====================================================================================
    // flat-ceilinged shapes: dome, mushroom, castle, spire
    // =====================================================================================

    private static void ceiling(VoxelCanvas c, String block) {
        for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) c.set(x, WALL_TOP + 1, z, block);
    }

    /** Height of an ellipsoid cap at (x, z), or -1 outside it. */
    private static double cap(int x, int z, double rx, double rz, double ry) {
        double q = (x / rx) * (x / rx) + ((z - CZ) / rz) * ((z - CZ) / rz);
        return q > 1 ? -1 : ry * Math.sqrt(1 - q);
    }

    /** Fill an ellipsoid shell from y0: each column from its lowest neighbour's height to its own, so there are no gaps. */
    private static int shell(VoxelCanvas c, int y0, double rx, double rz, double ry, BlockAt block) {
        int top = y0;
        int ix = (int) Math.ceil(rx), iz = (int) Math.ceil(rz);
        for (int x = -ix; x <= ix; x++)
            for (int z = CZ - iz; z <= CZ + iz; z++) {
                double h = cap(x, z, rx, rz, ry);
                if (h < 0) continue;
                double low = h;
                int[][] n = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : n) low = Math.min(low, Math.max(0, cap(x + d[0], z + d[1], rx, rz, ry)));
                int hi = (int) Math.floor(h), lo = Math.min(hi, (int) Math.floor(low));
                for (int y = y0 + lo; y <= y0 + hi; y++) c.set(x, y, z, block.at(x, y, z, y - y0, hi));
                top = Math.max(top, y0 + hi);
            }
        return top;
    }

    @FunctionalInterface
    interface BlockAt { String at(int x, int y, int z, int height, int columnTop); }

    /** A dome on a flat ceiling: hive (honeycomb with banded rings, a nest on top) or melon (striped, a stem on top). */
    static int dome(VoxelCanvas c, BarnBuilder.Skin s) {
        ceiling(c, s.roofFull());
        int y0 = WALL_TOP + 1;
        boolean hive = s.id().equals("hive");
        int top = shell(c, y0, 14, 10, 9, (x, y, z, h, colTop) -> {
            if (hive) return h % 3 == 2 ? s.accent() : s.roofFull();
            double ang = Math.atan2(z - CZ, x * 10.0 / 14.0);
            int stripe = (int) Math.floor((ang + Math.PI) / (2 * Math.PI) * 14);
            return stripe % 2 == 0 ? s.roofFull() : s.accent();
        });
        if (hive) {
            c.set(0, top + 1, CZ, "bee_nest[facing=south,honey_level=5]");
            c.set(0, top + 2, CZ, "honey_block");
        } else {
            c.set(0, top + 1, CZ, "jungle_log[axis=y]");
            c.set(0, top + 2, CZ, "jungle_log[axis=y]");
            c.set(1, top + 2, CZ, Blocks.leaves("jungle"));
            c.set(-1, top + 3, CZ, Blocks.leaves("jungle"));
            c.set(0, top + 3, CZ, Blocks.leaves("jungle"));
        }
        // a ring of round windows around the drum
        for (int x = X0 + 4; x <= X1 - 4; x += 6) { c.set(x, WALL_TOP - 1, Z0, "glass_pane"); c.set(x, WALL_TOP - 1, Z1, "glass_pane"); }
        return top;
    }

    /** A giant mushroom cap that overhangs the walls, red with pale spots. */
    static int mushroom(VoxelCanvas c, BarnBuilder.Skin s) {
        ceiling(c, "mushroom_stem");
        int y0 = WALL_TOP - 1;
        int top = shell(c, y0, 15, 11, 8, (x, y, z, h, colTop) ->
                y == y0 + colTop && hash(x / 2, y, z / 2, 7) == 0 ? "mushroom_stem" : s.roofFull());
        // gills under the rim, outside the walls
        for (int x = -15; x <= 15; x++)
            for (int z = Z0 - 2; z <= Z1 + 2; z++) {
                boolean outside = x < X0 || x > X1 || z < Z0 || z > Z1;
                if (!outside || cap(x, z, 15, 11, 8) < 0) continue;
                setIfAir(c, x, y0 - 1, z, s.trim());
            }
        return top;
    }

    /** Battlements and four corner turrets on a flat roof; the castle's turrets get pointed caps, the sand castle flags. */
    static int castle(VoxelCanvas c, BarnBuilder.Skin s, Fill wall) {
        ceiling(c, s.roofFull());
        int py = WALL_TOP + 2;
        for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++) {
            boolean edge = x == X0 || x == X1 || z == Z0 || z == Z1;
            if (edge && Math.floorMod(x + z, 2) == 0) c.set(x, py, z, wall.at(x, py, z));
        }
        boolean sand = s.id().equals("sand_castle");
        int top = py;
        for (int cx : new int[]{X0, X1})
            for (int cz : new int[]{Z0, Z1}) {
                for (int y = WALL_TOP + 1; y <= WALL_TOP + 5; y++)
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(cx + dx, y, cz + dz, wall.at(cx + dx, y, cz + dz));
                int ty = WALL_TOP + 6;
                if (sand) {
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                        if ((dx + dz) % 2 != 0) c.set(cx + dx, ty, cz + dz, s.accent());
                    c.set(cx, ty, cz, "birch_fence");
                    c.set(cx, ty + 1, cz, "birch_fence");
                    c.set(cx + (cx < 0 ? 1 : -1), ty + 1, cz, "red_wool");
                    top = Math.max(top, ty + 1);
                } else {
                    c.set(cx - 1, ty, cz, Blocks.stairs(s.roof(), "east"));
                    c.set(cx + 1, ty, cz, Blocks.stairs(s.roof(), "west"));
                    c.set(cx, ty, cz - 1, Blocks.stairs(s.roof(), "south"));
                    c.set(cx, ty, cz + 1, Blocks.stairs(s.roof(), "north"));
                    for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) c.set(cx + dx, ty, cz + dz, Blocks.slab(s.roof(), "bottom"));
                    c.set(cx, ty, cz, s.roofFull());
                    c.set(cx, ty + 1, cz, Blocks.slab(s.roof(), "bottom"));
                    top = Math.max(top, ty + 1);
                }
            }
        // a gatehouse over the big door, with a banner
        for (int x = -4; x <= 4; x++) for (int y = WALL_TOP + 2; y <= WALL_TOP + 3; y++) c.set(x, y, Z1, wall.at(x, y, Z1));
        for (int x = -4; x <= 4; x += 2) c.set(x, WALL_TOP + 4, Z1, wall.at(x, WALL_TOP + 4, Z1));
        c.set(0, WALL_TOP + 2, Z1 + 1, Blocks.wallBanner(sand ? "yellow" : "blue", "south"));
        return Math.max(top, WALL_TOP + 4);
    }

    /** A flat purpur roof with corner spires and a stepped central tower crowned by an end rod. */
    static int spire(VoxelCanvas c, BarnBuilder.Skin s, Fill wall) {
        ceiling(c, s.roofFull());
        int py = WALL_TOP + 2;
        for (int x = X0; x <= X1; x++) for (int z = Z0; z <= Z1; z++)
            if (x == X0 || x == X1 || z == Z0 || z == Z1) c.set(x, py, z, Blocks.slab("purpur", "bottom"));
        for (int cx : new int[]{X0 + 1, X1 - 1})
            for (int cz : new int[]{Z0 + 1, Z1 - 1}) {
                for (int y = py; y <= py + 5; y++) c.set(cx, y, cz, "purpur_pillar[axis=y]");
                c.set(cx, py + 6, cz, "end_rod[facing=up]");
            }
        int y = py;
        int[][] steps = {{5, 4}, {3, 2}, {1, 1}};
        for (int[] st : steps) {
            for (int x = -st[0]; x <= st[0]; x++) for (int z = CZ - st[1]; z <= CZ + st[1]; z++) c.set(x, y, z, wall.at(x, y, z));
            y++;
        }
        for (int k = 0; k < 4; k++) c.set(0, y + k, CZ, "purpur_pillar[axis=y]");
        c.set(0, y + 4, CZ, "end_rod[facing=up]");
        c.set(1, y, CZ, "end_rod[facing=east]");
        c.set(-1, y, CZ, "end_rod[facing=west]");
        return y + 4;
    }

    // =====================================================================================
    // pagoda: two tiers, flared eaves, an upper storey with its own roof
    // =====================================================================================

    static int pagoda(VoxelCanvas c, BarnBuilder.Skin s, Fill wall) {
        String full = s.roofFull();
        // lower eaves: a wide hip skirt three courses deep, then a deck
        // (starts two below the wall top so its third course lands ON the wall line and the skirt hangs from it)
        int u0 = X0 - 2, u1 = X1 + 2, v0 = Z0 - 2, v1 = Z1 + 2, y0 = WALL_TOP - 2;
        for (int k = 0; k < 4; k++) {
            int ul = u0 + k, ur = u1 - k, vl = v0 + k, vr = v1 - k, yy = y0 + k;
            for (int u = ul; u <= ur; u++) { c.set(u, yy, vl, Blocks.stairs(s.roof(), "south")); c.set(u, yy, vr, Blocks.stairs(s.roof(), "north")); }
            for (int v = vl + 1; v <= vr - 1; v++) { c.set(ul, yy, v, Blocks.stairs(s.roof(), "east")); c.set(ur, yy, v, Blocks.stairs(s.roof(), "west")); }
            for (int u = ul + 1; u <= ur - 1; u++) { setIfAir(c, u, yy, vl + 1, full); setIfAir(c, u, yy, vr - 1, full); }
            for (int v = vl + 1; v <= vr - 1; v++) { setIfAir(c, ul + 1, yy, v, full); setIfAir(c, ur - 1, yy, v, full); }
        }
        int deck = y0 + 3;
        for (int u = u0 + 4; u <= u1 - 4; u++) for (int v = v0 + 4; v <= v1 - 4; v++) c.set(u, deck, v, full);
        // upturned eave tips
        for (int u : new int[]{u0, u1}) for (int v : new int[]{v0, v1}) c.set(u, y0 + 1, v, Blocks.slab(s.roof(), "bottom"));
        for (int u : new int[]{u0, u1}) for (int v : new int[]{v0, v1}) c.set(u, y0, v, full);
        // the upper storey
        int ux0 = -7, ux1 = 7, uz0 = CZ - 4, uz1 = CZ + 4, wy0 = deck + 1, wy1 = deck + 4;
        for (int y = wy0; y <= wy1; y++)
            for (int x = ux0; x <= ux1; x++)
                for (int z = uz0; z <= uz1; z++) {
                    boolean ex = x == ux0 || x == ux1, ez = z == uz0 || z == uz1;
                    if (!ex && !ez) continue;
                    if (ex && ez) c.set(x, y, z, BarnBuilder.axis(s.trim(), 'y'));
                    else if (y == wy0 + 1 && ((ez && Math.floorMod(x, 3) == 0) || (ex && Math.floorMod(z - uz0, 3) == 0))) c.set(x, y, z, "glass_pane");
                    else c.set(x, y, z, wall.at(x, y, z));
                }
        // red-lacquer posts on the deck and lanterns on the eaves
        for (int x : new int[]{ux0 - 2, ux1 + 2}) for (int z : new int[]{uz0 - 2, uz1 + 2}) c.set(x, deck + 1, z, BarnBuilder.lamp(s, false));
        int top = hipRoof(c, s, ux0 - 2, ux1 + 2, uz0 - 2, uz1 + 2, wy1 - 1);
        for (int u : new int[]{ux0 - 2, ux1 + 2}) for (int v : new int[]{uz0 - 2, uz1 + 2}) c.set(u, wy1, v, Blocks.slab(s.roof(), "bottom"));
        c.set(0, top + 1, CZ, "iron_chain");
        c.set(0, top + 2, CZ, BarnBuilder.lamp(s, false));
        return top + 2;
    }

    // =====================================================================================
    // per-skin features: chimneys, a porch, pumpkins, moss, icicles
    // =====================================================================================

    static void extras(VoxelCanvas c, BarnBuilder.Skin s, Fill wall, int peak) {
        switch (s.id()) {
            case "medieval" -> chimney(c, "cobblestone", 9, Z0 + 3, 5);
            case "cabin" -> { chimney(c, "bricks", -9, Z0 + 3, 5); porch(c, "spruce_log[axis=y]", "spruce"); }
            case "cozy_cottage" -> { chimney(c, "bricks", 9, Z0 + 3, 5); flowerBoxes(c); }
            case "winter_barn" -> { chimney(c, "stone_bricks", 9, Z0 + 3, 5); icicles(c); }
            case "tavern" -> chimney(c, "deepslate_bricks", -9, Z0 + 3, 6);
            case "autumn_hut" -> {
                for (int x : new int[]{-8, 8}) { c.set(x, BarnBuilder.FLOOR_Y + 1, Z1 + 1, "carved_pumpkin[facing=south]"); c.set(x + (x < 0 ? 1 : -1), BarnBuilder.FLOOR_Y + 1, Z1 + 1, "hay_block[axis=y]"); }
                c.set(-8, BarnBuilder.FLOOR_Y + 2, Z1 + 1, "jack_o_lantern[facing=south]");
            }
            case "pestwarden_lair" -> {
                chimney(c, "mud_bricks", -9, Z0 + 3, 4);
                for (int x = X0 - 1; x <= X1 + 1; x++)
                    for (int z = Z0 - 1; z <= Z1 + 1; z++)
                        for (int y = WALL_TOP; y <= peak; y++)
                            if (!c.isAir(x, y, z) && c.get(x, y, z).startsWith("mud_brick_stairs") && hash(x, y, z, 6) == 0) c.set(x, y, z, "moss_block");
                for (int x : new int[]{X0 + 1, X1 - 1}) c.set(x, WALL_TOP, Z1 - 1, "cobweb");
            }
            case "mansion_heights" -> porch(c, "quartz_pillar[axis=y]", "quartz");
            default -> { }
        }
    }

    /** A chimney through the roof at (x, z), its top {@code above} blocks over the roof there, smoking. */
    private static void chimney(VoxelCanvas c, String block, int x, int z, int above) {
        int roofY = WALL_TOP;
        for (int y = WALL_TOP + 20; y > WALL_TOP; y--) if (!c.isAir(x, y, z)) { roofY = y; break; }
        int topY = roofY + above;
        for (int y = WALL_TOP + 1; y <= topY; y++) c.set(x, y, z, block);
        c.set(x, topY + 1, z, Blocks.campfire(true, "south"));
    }

    /** Posts and a slab roof over the front door, in the box margin. */
    private static void porch(VoxelCanvas c, String post, String slab) {
        int y = BarnBuilder.FLOOR_Y;
        for (int x : new int[]{-9, -6, 6, 9})
            for (int yy = y + 1; yy <= y + 5; yy++) c.set(x, yy, Z1 + 2, post);
        for (int x = -10; x <= 10; x++) for (int z = Z1 + 1; z <= Z1 + 2; z++) c.set(x, y + 6, z, Blocks.slab(slab, "bottom"));
    }

    /** Potted flowers on the outside sills of the long walls' lower windows. */
    private static void flowerBoxes(VoxelCanvas c) {
        String[] pots = {"potted_red_tulip", "potted_poppy", "potted_cornflower", "potted_allium"};
        int i = 0;
        for (int z = Z0 + 3; z <= Z1 - 3; z += 4) {
            if ((z - Z0) % 6 == 0) continue;
            for (int xs : new int[]{X0 - 1, X1 + 1}) {
                c.set(xs, BarnBuilder.FLOOR_Y + 1, z, "spruce_planks");
                c.set(xs, BarnBuilder.FLOOR_Y + 2, z, pots[i++ % pots.length]);
            }
        }
    }

    /** Icicles under the long eaves. */
    private static void icicles(VoxelCanvas c) {
        for (int x = X0; x <= X1; x += 3)
            for (int z : new int[]{Z0 - 1, Z1 + 1})
                if (!c.isAir(x, WALL_TOP, z) && c.isAir(x, WALL_TOP - 1, z))
                    c.set(x, WALL_TOP - 1, z, "pointed_dripstone[thickness=tip,vertical_direction=down,waterlogged=false]");
    }
}
