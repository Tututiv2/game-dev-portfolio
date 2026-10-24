package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.util.LongIntMap;
import com.skyclone.gen.util.Pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parametric structures. Each builder writes into a canvas and reports where the useful spots
 * are (door step, an interior standing spot, a vendor spot) so the generator can put anchors there.
 *
 * <p>Conventions: {@code y} is always the FLOOR/GROUND block level — a person stands at y + 1.
 * Builders never float: foundations and posts are extended down to the first solid block below
 * (up to 12 blocks). Reserve each building's {@code reserve()} box with {@link Reservations}
 * before building, so nothing overlaps.
 */
public final class Builders {

    private Builders() {}

    // =======================================================================================
    // ground helpers
    // =======================================================================================

    /**
     * Highest "ground" y at (x, z) between {@code fromY} (top, inclusive) and {@code toY}: a
     * full-top block that is not leaves or a log. Plants, leaves and air are looked through.
     * Integer.MIN_VALUE if none.
     */
    public static int ground(VoxelCanvas c, int x, int z, int fromY, int toY) {
        for (int y = fromY; y >= toY; y--) {
            String b = c.get(x, y, z);
            BlockInfo i = BlockInfo.of(b);
            if (i.isAir() || i.top16 <= 0 || i.liquid()) continue;
            String n = BlockState.name(b);
            if (n.endsWith("_leaves") || n.endsWith("_log") || n.endsWith("_wood") || n.equals("mushroom_stem")
                    || n.endsWith("mushroom_block")) continue;
            if (i.top16 >= 15) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Fill downward from y-1 with {@code f} until something non-air is hit (max {@code max} blocks). */
    public static void footing(VoxelCanvas c, int x, int y, int z, Fill f, int max) {
        for (int yy = y - 1; yy >= y - max; yy--) {
            BlockInfo i = BlockInfo.of(c.get(x, yy, z));
            if (!i.isAir() && i.top16 != 0) return;
            c.set(x, yy, z, f.at(x, yy, z));
        }
    }

    private static final Set<String> NATURAL = Set.of("grass_block", "dirt", "coarse_dirt", "podzol", "stone", "andesite",
            "diorite", "granite", "cobblestone", "mossy_cobblestone", "gravel", "sand", "red_sand", "sandstone", "tuff",
            "clay", "mud", "moss_block", "snow_block", "snow", "mycelium", "rooted_dirt", "deepslate", "calcite", "dirt_path",
            "terracotta", "netherrack", "end_stone", "packed_ice", "ice", "blackstone", "basalt");

    /** Is this block terrain or vegetation (safe for a path to dig through)? */
    public static boolean natural(String block) {
        String n = BlockState.name(block);
        BlockInfo i = BlockInfo.of(block);
        return i.isAir() || i.top16 == 0 && !n.endsWith("_door") && !n.endsWith("_fence_gate") && !n.endsWith("torch")
                || NATURAL.contains(n) || n.endsWith("_leaves") || n.endsWith("_log") || n.endsWith("terracotta");
    }

    // =======================================================================================
    // local frames
    // =======================================================================================

    /**
     * Local coordinates for a structure facing a direction: u = to the right (as seen when you
     * look along {@code facing}), v = forward along {@code facing}, y = up.
     */
    public static final class Frame {
        public final int ox, oy, oz;
        public final String facing;
        private final int fx, fz, rx, rz;

        public Frame(int ox, int oy, int oz, String facing) {
            this.ox = ox; this.oy = oy; this.oz = oz; this.facing = facing;
            fx = Blocks.dx(facing); fz = Blocks.dz(facing);
            String right = Blocks.cw(facing);
            rx = Blocks.dx(right); rz = Blocks.dz(right);
        }

        public int x(int u, int v) { return ox + u * rx + v * fx; }
        public int z(int u, int v) { return oz + u * rz + v * fz; }
        public int y(int h) { return oy + h; }

        public void set(VoxelCanvas c, int u, int h, int v, String b) { c.set(x(u, v), y(h), z(u, v), b); }

        /** Direction names relative to the frame. */
        public String forward() { return facing; }
        public String back() { return Blocks.opposite(facing); }
        public String right() { return Blocks.cw(facing); }
        public String left() { return Blocks.ccw(facing); }
    }

    // =======================================================================================
    // House
    // =======================================================================================

    /**
     * A house. Set the fields, then {@link #build}. After building, {@link #doorStep} is the
     * block outside the door (stand on it at y+1) and {@link #inside} a free floor block inside.
     *
     * <pre>
     *   Builders.House h = new Builders.House(10, 70, -4, 9, 7);   // x, y(floor), z, width(x), depth(z)
     *   h.floors = 2; h.roofStyle = "hip"; h.door = "south";
     *   h.wall = Palette.of("spruce_planks:5,stripped_spruce_log:1"); h.roof = "deepslate_tile";
     *   reservations.reserve("bank", h.reserve());
     *   h.build(canvas);
     *   layout.standAt("npc.banker", h.inside[0], h.inside[1], h.inside[2], 180);
     * </pre>
     */
    public static final class House {
        public int x, y, z, width, depth;
        public int floors = 1, floorHeight = 4;
        /** Wall the door is in: north/east/south/west. */
        public String door = "south";
        /** Door position along its wall counted from the wall's west/north end; -1 = centre. */
        public int doorOffset = -1;
        public Fill wall = Fill.of("oak_planks");
        /** Corner posts and floor beams (a log id), or null for none. */
        public String frame = "oak_log";
        public Fill foundation = Palette.of("cobblestone:3,stone_bricks:2,mossy_cobblestone:1");
        public Fill floor = Fill.of("spruce_planks");
        /** Roof material family: uses {roof}_stairs and {roof}_slab ("dark_oak", "deepslate_tile", "stone_brick"). */
        public String roof = "dark_oak";
        /** Full block for the ridge and parapets; null = {roof}_planks or the family's full block. */
        public String roofFull = null;
        /** "gable", "hip" or "flat". */
        public String roofStyle = "gable";
        public int overhang = 1;
        public String window = "glass_pane";
        public String doorWood = "oak";
        public boolean furnish = true;
        public boolean chimney = false;
        public String chimneyBlock = "bricks";
        public long seed = 0;

        /** Outside the door: the ground block you stand on (x, y, z). */
        public int[] doorStep;
        /** A free floor block inside on the ground floor. */
        public int[] inside;
        /** Highest y the roof reaches. */
        public int peakY;

        public House(int x, int y, int z, int width, int depth) {
            this.x = x; this.y = y; this.z = z; this.width = Math.max(4, width); this.depth = Math.max(4, depth);
        }

        private String full() {
            if (roofFull != null) return roofFull;
            String r = roof;
            if (Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped", "pale_oak").contains(r))
                return r + "_planks";
            if (r.endsWith("_tile") || r.endsWith("_brick")) return r + "s";
            return r;
        }

        private int top() { return y + floors * floorHeight; }

        /** The full box this house will occupy (overhang, foundation and roof included). */
        public Box reserve() {
            int span = roofStyle.equals("flat") ? 2 : (Math.min(width, depth) + 2 * overhang) / 2 + 2;
            int oh = roofStyle.equals("flat") ? 0 : overhang;
            int extra = doorStepExtra();
            return new Box(x - oh - (door.equals("west") ? extra : 0), y - 8, z - oh - (door.equals("north") ? extra : 0),
                    x + width - 1 + oh + (door.equals("east") ? extra : 0), top() + span + (chimney ? 2 : 0),
                    z + depth - 1 + oh + (door.equals("south") ? extra : 0));
        }

        private int doorStepExtra() { return Math.max(0, 2 - overhang); }

        public House build(VoxelCanvas c) {
            int x0 = x, x1 = x + width - 1, z0 = z, z1 = z + depth - 1;
            int top = top();
            // ---- foundation + ground floor ----
            for (int fz = z0; fz <= z1; fz++)
                for (int fx = x0; fx <= x1; fx++) {
                    boolean perim = fx == x0 || fx == x1 || fz == z0 || fz == z1;
                    c.set(fx, y, fz, perim ? foundation.at(fx, y, fz) : floor.at(fx, y, fz));
                    footing(c, fx, y, fz, foundation, 12);
                }
            // ---- walls, floors, interior air ----
            for (int yy = y + 1; yy <= top; yy++) {
                boolean boundary = (yy - y) % floorHeight == 0;
                for (int fz = z0; fz <= z1; fz++)
                    for (int fx = x0; fx <= x1; fx++) {
                        boolean wx = fx == x0 || fx == x1, wz = fz == z0 || fz == z1;
                        if (wx && wz) c.set(fx, yy, fz, frame != null ? frame + "[axis=y]" : wall.at(fx, yy, fz));
                        else if (wx || wz) {
                            if (boundary && frame != null) c.set(fx, yy, fz, frame + "[axis=" + (wz ? 'x' : 'z') + "]");
                            else c.set(fx, yy, fz, wall.at(fx, yy, fz));
                        } else c.set(fx, yy, fz, boundary ? floor.at(fx, yy, fz) : "air");
                    }
            }
            // ---- door ----
            int len = door.equals("north") || door.equals("south") ? width : depth;
            int off = doorOffset < 0 ? len / 2 : Math.max(1, Math.min(len - 2, doorOffset));
            int dxw, dzw;
            switch (door) {
                case "north": dxw = x0 + off; dzw = z0; break;
                case "south": dxw = x0 + off; dzw = z1; break;
                case "west": dxw = x0; dzw = z0 + off; break;
                default: dxw = x1; dzw = z0 + off; break;
            }
            Blocks.door(c, dxw, y + 1, dzw, doorWood, door, "left", false);
            int ox = dxw + Blocks.dx(door), oz = dzw + Blocks.dz(door);
            c.set(ox, y, oz, foundation.at(ox, y, oz));
            footing(c, ox, y, oz, foundation, 12);
            for (int h = 1; h <= 3; h++) if (!c.isAir(ox, y + h, oz) && natural(c.get(ox, y + h, oz))) c.set(ox, y + h, oz, "air");
            doorStep = new int[]{ox, y, oz};
            // a step down if the ground beyond is one lower
            int sx = ox + Blocks.dx(door), sz = oz + Blocks.dz(door);
            int g = ground(c, sx, sz, y + 1, y - 12);
            if (g != Integer.MIN_VALUE && g < y) {
                if (g == y - 1) c.set(sx, y, sz, Blocks.stairs(Blocks.family(foundationBlock()) + "_stairs", Blocks.opposite(door)));
                else {
                    c.set(sx, y, sz, Blocks.stairs(Blocks.family(foundationBlock()) + "_stairs", Blocks.opposite(door)));
                    footing(c, sx, y, sz, foundation, 12);
                }
            }
            // ---- windows ----
            int[] rows = floorHeight >= 5 ? new int[]{2, 3} : new int[]{2};
            for (int f = 0; f < floors; f++)
                for (int rIdx : rows) {
                    int wy = y + f * floorHeight + rIdx;
                    if (wy >= y + (f + 1) * floorHeight) continue;
                    windowsAlong(c, x0, z0, x1, z0, wy, f == 0 && door.equals("north") ? dxw : Integer.MIN_VALUE, true);
                    windowsAlong(c, x0, z1, x1, z1, wy, f == 0 && door.equals("south") ? dxw : Integer.MIN_VALUE, true);
                    windowsAlong(c, x0, z0, x0, z1, wy, f == 0 && door.equals("west") ? dzw : Integer.MIN_VALUE, false);
                    windowsAlong(c, x1, z0, x1, z1, wy, f == 0 && door.equals("east") ? dzw : Integer.MIN_VALUE, false);
                }
            // ---- ladder between floors (NW inner corner, on the west wall) ----
            int lx = x0 + 1, lz = z0 + 1;
            if (door.equals("west") && dzw == lz || door.equals("north") && dxw == lx) { lx = x1 - 1; lz = z0 + 1; }
            if (floors > 1) {
                String ladderFacing = lx == x0 + 1 ? "east" : "west";
                for (int yy = y + 1; yy <= y + (floors - 1) * floorHeight; yy++) c.set(lx, yy, lz, Blocks.ladder(ladderFacing));
                // ladder needs a solid wall behind it, even where a window would be
                int wallX = lx == x0 + 1 ? x0 : x1;
                for (int yy = y + 1; yy <= y + (floors - 1) * floorHeight; yy++)
                    if (!BlockInfo.of(c.get(wallX, yy, lz)).cube) c.set(wallX, yy, lz, wall.at(wallX, yy, lz));
            }
            // ---- lights ----
            int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
            for (int f = 0; f < floors; f++) {
                int ly = y + (f + 1) * floorHeight - 1;
                if (cx == lx && cz == lz) cx++;
                c.set(cx, ly, cz, Blocks.lantern(true));
            }
            // ---- furniture ----
            int ix = cx, iz = cz;
            if (furnish) {
                long s = Hash.salt(seed, "house" + x + "," + z);
                int[][] corners = {{x1 - 1, z0 + 1}, {x1 - 1, z1 - 1}, {x0 + 1, z1 - 1}, {x0 + 1, z0 + 1}};
                String[] items = {"barrel[facing=up,open=false]", "crafting_table", "potted_red_tulip", "bookshelf", "composter", "potted_fern"};
                int k = 0;
                for (int[] cn : corners) {
                    if (cn[0] == lx && cn[1] == lz && floors > 1) continue;
                    if (adjacentToDoor(cn[0], cn[1], dxw, dzw)) continue;
                    if (width <= 5 && depth <= 5 && k >= 1) break;
                    c.set(cn[0], y + 1, cn[1], items[(int) ((Hash.hash(s, cn[0], 0, cn[1]) >>> 1) % items.length)]);
                    k++;
                }
                // a rug in the middle
                String rug = new String[]{"red_carpet", "brown_carpet", "green_carpet", "blue_carpet", "light_gray_carpet"}[(int) ((Hash.hash(s, 1, 2, 3) >>> 1) % 5)];
                for (int fz = cz - 1; fz <= cz + 1; fz++)
                    for (int fx = cx - 1; fx <= cx + 1; fx++)
                        if (fx > x0 + 1 && fx < x1 - 1 && fz > z0 + 1 && fz < z1 - 1 && c.isAir(fx, y + 1, fz)) c.set(fx, y + 1, fz, rug);
            }
            inside = new int[]{ix, y, iz};
            if (floors > 1 && ix == lx && iz == lz) inside = new int[]{ix + 1, y, iz};
            // ---- roof ----
            switch (roofStyle) {
                case "hip": peakY = hipRoof(c, x0 - overhang, z0 - overhang, x1 + overhang, z1 + overhang, top + 1 - overhang); break;
                case "flat": peakY = flatRoof(c, x0, z0, x1, z1, top); break;
                default: peakY = gableRoof(c, x0, z0, x1, z1, top); break;
            }
            if (chimney) {
                int chx = x1 - 1, chz = z0 + 1;
                if (chx == lx && chz == lz) chx = x0 + 1;
                for (int yy = top + 1; yy <= peakY + 2; yy++) c.set(chx, yy, chz, chimneyBlock);
                c.set(chx, peakY + 3, chz, Blocks.slab(Blocks.family(chimneyBlock), "bottom"));
                peakY += 3;
            }
            return this;
        }

        private String foundationBlock() {
            String b = BlockState.name(foundation.at(x, y, z));
            if (b.equals("mossy_cobblestone")) return "cobblestone";
            if (b.startsWith("cracked_")) b = b.substring(8);
            if (b.startsWith("chiseled_")) b = b.substring(9);
            return b;
        }

        private static boolean adjacentToDoor(int fx, int fz, int dxw, int dzw) {
            return Math.abs(fx - dxw) <= 1 && Math.abs(fz - dzw) <= 1;
        }

        private void windowsAlong(VoxelCanvas c, int ax, int az, int bx, int bz, int wy, int doorAt, boolean alongX) {
            int len = alongX ? bx - ax + 1 : bz - az + 1;
            for (int i = 1; i < len - 1; i++) {
                boolean put = len <= 6 ? i == len / 2 || (len == 6 && i == len / 2 - 1) : (i - 1) % 3 == 1;
                if (!put) continue;
                int wx = alongX ? ax + i : ax, wz = alongX ? az : az + i;
                int along = alongX ? wx : wz;
                if (doorAt != Integer.MIN_VALUE && Math.abs(along - doorAt) <= 1) continue;
                c.set(wx, wy, wz, window);
            }
        }

        private int gableRoof(VoxelCanvas c, int x0, int z0, int x1, int z1, int top) {
            boolean alongX = (x1 - x0) >= (z1 - z0);
            int oh = overhang;
            String st = Blocks.suffix(roof, "_stairs"), full = full(), sl = Blocks.suffix(roof, "_slab");
            int base = top + 1 - oh;
            int uMin = (alongX ? x0 : z0) - oh, uMax = (alongX ? x1 : z1) + oh;
            int vMin = (alongX ? z0 : x0) - oh, vMax = (alongX ? z1 : x1) + oh;
            int wallU0 = alongX ? x0 : z0, wallU1 = alongX ? x1 : z1;
            // "low" side faces: for alongX the slopes face north (vMin side) and south (vMax side)
            String upFromLow = alongX ? "south" : "east";   // walking up from the vMin side goes +v
            String upFromHigh = alongX ? "north" : "west";
            int peak = base;
            for (int k = 0; ; k++) {
                int vl = vMin + k, vr = vMax - k, yy = base + k;
                if (vl > vr) break;
                peak = yy;
                for (int u = uMin; u <= uMax; u++) {
                    if (vl == vr) { put(c, alongX, u, yy, vl, full); continue; }
                    put(c, alongX, u, yy, vl, Blocks.stairs(st, upFromLow));
                    put(c, alongX, u, yy, vr, Blocks.stairs(st, upFromHigh));
                    if (vl + 1 == vr) {           // even span: cap the ridge
                        put(c, alongX, u, yy + 1, vl, Blocks.slab(sl, "bottom"));
                        put(c, alongX, u, yy + 1, vr, Blocks.slab(sl, "bottom"));
                        peak = yy + 1;
                    }
                }
                // gable triangles on the end walls, plus the top-of-wall rows on the long sides
                if (yy > top - 1) {
                    for (int v = vl + 1; v <= vr - 1; v++) {
                        if (yy <= top) continue;
                        put(c, alongX, wallU0, yy, v, wall.at(alongX ? wallU0 : v, yy, alongX ? v : wallU0));
                        put(c, alongX, wallU1, yy, v, wall.at(alongX ? wallU1 : v, yy, alongX ? v : wallU1));
                    }
                }
            }
            // a small window in each gable
            int mid = (vMin + vMax) / 2, wy = top + 2;
            if (wy < peak) {
                put(c, alongX, wallU0, wy, mid, window);
                put(c, alongX, wallU1, wy, mid, window);
            }
            return peak;
        }

        private static void put(VoxelCanvas c, boolean alongX, int u, int y, int v, String b) {
            if (alongX) c.set(u, y, v, b); else c.set(v, y, u, b);
        }

        private int hipRoof(VoxelCanvas c, int ux0, int uz0, int ux1, int uz1, int base) {
            String st = Blocks.suffix(roof, "_stairs"), full = full();
            int peak = base;
            for (int k = 0; ; k++) {
                int ax = ux0 + k, az = uz0 + k, bx = ux1 - k, bz = uz1 - k, yy = base + k;
                if (ax > bx || az > bz) break;
                peak = yy;
                if (k > 0) {
                    // a solid underside below this ring: it shares a face with the ring below, so the
                    // roof is one connected piece (stairs a step apart only touch at an edge)
                    for (int xx = ax; xx <= bx; xx++) { underside(c, xx, yy - 1, az, full); underside(c, xx, yy - 1, bz, full); }
                    for (int zz = az; zz <= bz; zz++) { underside(c, ax, yy - 1, zz, full); underside(c, bx, yy - 1, zz, full); }
                }
                if (bx - ax <= 1 || bz - az <= 1) {
                    if (bx - ax <= 0 || bz - az <= 0) {
                        for (int zz = az; zz <= bz; zz++) for (int xx = ax; xx <= bx; xx++) c.set(xx, yy, zz, full);
                        break;
                    }
                }
                for (int xx = ax + 1; xx <= bx - 1; xx++) {
                    c.set(xx, yy, az, Blocks.stairs(st, "south"));
                    c.set(xx, yy, bz, Blocks.stairs(st, "north"));
                }
                for (int zz = az + 1; zz <= bz - 1; zz++) {
                    c.set(ax, yy, zz, Blocks.stairs(st, "east"));
                    c.set(bx, yy, zz, Blocks.stairs(st, "west"));
                }
                c.set(ax, yy, az, Blocks.stairs(st, "south", "bottom", "outer_left"));
                c.set(bx, yy, az, Blocks.stairs(st, "south", "bottom", "outer_right"));
                c.set(bx, yy, bz, Blocks.stairs(st, "north", "bottom", "outer_left"));
                c.set(ax, yy, bz, Blocks.stairs(st, "north", "bottom", "outer_right"));
                if (bx - ax == 1 || bz - az == 1) {
                    // two-wide: the next layer has nothing left; cap with slabs
                    String sl = Blocks.suffix(roof, "_slab");
                    for (int zz = az; zz <= bz; zz++) for (int xx = ax; xx <= bx; xx++) c.set(xx, yy + 1, zz, Blocks.slab(sl, "bottom"));
                    peak = yy + 1;
                    break;
                }
            }
            return peak;
        }

        private static void underside(VoxelCanvas c, int x, int y, int z, String block) {
            if (c.isAir(x, y, z)) c.set(x, y, z, block);
        }

        private int flatRoof(VoxelCanvas c, int x0, int z0, int x1, int z1, int top) {
            String full = full();
            for (int fz = z0; fz <= z1; fz++)
                for (int fx = x0; fx <= x1; fx++) {
                    boolean perim = fx == x0 || fx == x1 || fz == z0 || fz == z1;
                    if (perim) c.set(fx, top + 1, fz, full);
                }
            return top + 1;
        }
    }

    // =======================================================================================
    // Tower
    // =======================================================================================

    /** A round or square tower with floors, ladder, windows and a cone or battlement top. */
    public static final class Tower {
        public int cx, y, cz;
        public int radius = 3;
        public int height = 16;
        public boolean round = true;
        public Fill wall = Palette.of("stone_bricks:6,mossy_stone_bricks:2,cracked_stone_bricks:1");
        public Fill floor = Fill.of("spruce_planks");
        /** "cone" or "battlements". */
        public String top = "cone";
        public Fill roofFill = Palette.of("dark_oak_planks");
        public String door = "south";
        public int floorHeight = 5;
        public String window = "glass_pane";
        public String doorWood = "spruce";

        public int[] doorStep, inside;
        /** With battlements: a block on the roof platform you can stand on. */
        public int[] platform;
        public int peakY;

        public Tower(int cx, int y, int cz) { this.cx = cx; this.y = y; this.cz = cz; }

        private boolean in(int dx, int dz, double r) {
            if (!round) return Math.abs(dx) <= r && Math.abs(dz) <= r;
            return dx * dx + dz * dz <= (r + 0.4) * (r + 0.4);
        }

        public Box reserve() {
            int extra = top.equals("cone") ? radius + 4 : 2;
            return new Box(cx - radius - 2, y - 12, cz - radius - 2, cx + radius + 2, y + height + extra, cz + radius + 2);
        }

        public Tower build(VoxelCanvas c) {
            int r = radius;
            int topY = y + height;
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (!in(dx, dz, r)) continue;
                    boolean shell = !in(dx, dz, r - 1);
                    int x = cx + dx, z = cz + dz;
                    c.set(x, y, z, shell ? wall.at(x, y, z) : floor.at(x, y, z));
                    footing(c, x, y, z, wall, 12);
                    for (int yy = y + 1; yy <= topY; yy++) {
                        boolean fl = (yy - y) % floorHeight == 0;
                        c.set(x, yy, z, shell ? wall.at(x, yy, z) : (fl ? floor.at(x, yy, z) : "air"));
                    }
                }
            // ladder on the inner face of the north wall
            int lx = cx, lz = cz - (r - 1);
            String lf = "south";
            if (door.equals("north")) { lz = cz + (r - 1); lf = "north"; }
            int ladderTop = top.equals("battlements") ? topY : y + ((height - 1) / floorHeight) * floorHeight;
            for (int yy = y + 1; yy <= ladderTop; yy++) c.set(lx, yy, lz, Blocks.ladder(lf));
            // windows: four sides, mid-floor
            for (int f = 0; (f + 1) * floorHeight <= height + 1; f++) {
                int wy = y + f * floorHeight + 2;
                if (wy >= topY) break;
                for (int d = 0; d < 4; d++) {
                    String dir = Blocks.DIRS[d];
                    if (f == 0 && dir.equals(door)) continue;
                    int wx = cx + Blocks.DX[d] * r, wz = cz + Blocks.DZ[d] * r;
                    int behindZ = lf.equals("south") ? lz - 1 : lz + 1;      // the wall the ladder hangs on
                    if (wx == lx && wz == behindZ) continue;
                    c.set(wx, wy, wz, window);
                }
                c.set(cx + (lx == cx && lz == cz ? 1 : 0), y + (f + 1) * floorHeight - 1, cz, Blocks.lantern(true));
            }
            // door
            int ddx = cx + Blocks.dx(door) * r, ddz = cz + Blocks.dz(door) * r;
            Blocks.door(c, ddx, y + 1, ddz, doorWood, door, "left", false);
            int ox = ddx + Blocks.dx(door), oz = ddz + Blocks.dz(door);
            c.set(ox, y, oz, wall.at(ox, y, oz));
            footing(c, ox, y, oz, wall, 12);
            for (int h = 1; h <= 3; h++) if (natural(c.get(ox, y + h, oz))) c.set(ox, y + h, oz, "air");
            doorStep = new int[]{ox, y, oz};
            inside = new int[]{cx + (Blocks.dx(door) == 0 ? 1 : 0), y, cz + (Blocks.dz(door) == 0 ? 1 : 0)};
            if (inside[0] == lx && inside[2] == lz) inside = new int[]{cx - 1, y, cz};
            // top
            if (top.equals("battlements")) {
                for (int dz = -r; dz <= r; dz++)
                    for (int dx = -r; dx <= r; dx++) {
                        if (!in(dx, dz, r)) continue;
                        int x = cx + dx, z = cz + dz;
                        boolean shell = !in(dx, dz, r - 1);
                        if (!shell) { if (!(x == lx && z == lz)) c.set(x, topY, z, floor.at(x, topY, z)); continue; }
                        c.set(x, topY + 1, z, wall.at(x, topY + 1, z));
                        if (((dx + dz) & 1) == 0) c.set(x, topY + 2, z, wall.at(x, topY + 2, z));
                    }
                platform = new int[]{cx + (lx == cx ? 1 : 0), topY, cz};
                peakY = topY + 2;
            } else {
                int h = r + 3;
                for (int i = 0; i <= h; i++) {
                    double rr = (r + 1) * (1 - (double) i / (h + 1));
                    for (int dz = -r - 1; dz <= r + 1; dz++)
                        for (int dx = -r - 1; dx <= r + 1; dx++) {
                            double d = round ? Math.sqrt(dx * dx + dz * dz) : Math.max(Math.abs(dx), Math.abs(dz));
                            if (d > rr + 0.4) continue;
                            if (d < rr - 1.2) continue;
                            c.set(cx + dx, topY + 1 + i, cz + dz, roofFill.at(cx + dx, topY + 1 + i, cz + dz));
                        }
                }
                c.set(cx, topY + h + 2, cz, "lightning_rod[facing=up,powered=false,waterlogged=false]");
                peakY = topY + h + 2;
            }
            return this;
        }
    }

    // =======================================================================================
    // Market stall
    // =======================================================================================

    /** Result of {@link #marketStall}: where the vendor stands and where a customer stands. */
    public record Stall(int[] vendor, int[] customer, Box box) {}

    /**
     * A 5-wide, 3-deep market stall with a striped awning, counter and lantern, open toward
     * {@code facing}. (x, y, z) is the ground block at the centre of the counter row.
     */
    public static Stall marketStall(VoxelCanvas c, int x, int y, int z, String facing, String colorA, String colorB, long seed) {
        Frame f = new Frame(x, y, z, facing);
        Fill post = Fill.of("dark_oak_fence");
        for (int v = -2; v <= 0; v++)
            for (int u = -2; u <= 2; u++) {
                f.set(c, u, 0, v, "spruce_planks");
                footing(c, f.x(u, v), y, f.z(u, v), Fill.of("cobblestone"), 12);
                for (int h = 1; h <= 3; h++) if (natural(c.get(f.x(u, v), y + h, f.z(u, v)))) f.set(c, u, h, v, "air");
            }
        for (int u = -2; u <= 2; u += 4)
            for (int v = -2; v <= 0; v += 2)
                for (int h = 1; h <= 3; h++) f.set(c, u, h, v, post.at(0, 0, 0));
        for (int v = -3; v <= 1; v++)
            for (int u = -2; u <= 2; u++) {
                String col = ((u + 2) & 1) == 0 ? colorA : colorB;
                if (v == 1) f.set(c, u, 4, v, col + "_carpet");
                else f.set(c, u, 4, v, col + "_wool");
            }
        // counter: front row, with goods on top
        String counter = Blocks.slab("spruce", "top");
        for (int u = -1; u <= 1; u++) f.set(c, u, 1, 0, counter);
        f.set(c, -1, 2, 0, Blocks.lantern(false));
        f.set(c, 1, 2, 0, "potted_red_tulip");
        // crates at the back
        f.set(c, -1, 1, -2, "barrel[facing=up,open=false]");
        f.set(c, 1, 1, -2, "barrel[facing=up,open=false]");
        f.set(c, 1, 2, -2, "hay_block[axis=y]");
        f.set(c, 0, 3, -1, Blocks.lantern(true));
        int[] vendor = {f.x(0, -1), y, f.z(0, -1)};
        int[] customer = {f.x(0, 2), y, f.z(0, 2)};
        Box box = Box.of(f.x(-2, -3), y - 12, f.z(-2, -3), f.x(2, 2), y + 4, f.z(2, 2));
        return new Stall(vendor, customer, box);
    }

    // =======================================================================================
    // Fountain, well, lamp posts
    // =======================================================================================

    /** Round fountain on the ground at (cx, y, cz): stone basin, water, a lit centre column. */
    public static Box fountain(VoxelCanvas c, int cx, int y, int cz, int radius, Fill rim, long seed) {
        int R = Math.max(2, radius);
        for (int dz = -R; dz <= R; dz++)
            for (int dx = -R; dx <= R; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > R + 0.4) continue;
                int x = cx + dx, z = cz + dz;
                c.set(x, y, z, rim.at(x, y, z));
                footing(c, x, y, z, rim, 12);
                for (int h = 1; h <= 4; h++) if (natural(c.get(x, y + h, z))) c.set(x, y + h, z, "air");
                if (d > R - 0.6) c.set(x, y + 1, z, rim.at(x, y + 1, z));
                else c.set(x, y + 1, z, "water");
            }
        c.set(cx, y + 1, cz, "chiseled_stone_bricks");
        c.set(cx, y + 2, cz, "stone_brick_wall");
        c.set(cx, y + 3, cz, "stone_brick_wall");
        c.set(cx, y + 4, cz, "sea_lantern");
        for (int d = 0; d < 4; d++) c.set(cx + Blocks.DX[d], y + 4, cz + Blocks.DZ[d], Blocks.slab("stone_brick", "bottom"));
        return Box.of(cx - R, y - 12, cz - R, cx + R, y + 4, cz + R);
    }

    /** Stone well with a roof: 3x3 shaft of water, posts, and a slab roof. Ground at (cx, y, cz). */
    public static Box well(VoxelCanvas c, int cx, int y, int cz) {
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx, z = cz + dz;
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                c.set(x, y, z, edge ? "cobblestone" : "water");
                // the shaft is two deep: footing() from y would turn the lower water into cobblestone
                // (water is not solid), leaving one block of water under a rim two blocks above the
                // player's feet - a trap nobody can jump out of. Two deep, you swim up and climb out.
                if (!edge) { c.set(x, y - 1, z, "water"); c.set(x, y - 2, z, "cobblestone"); }
                footing(c, x, edge ? y : y - 2, z, Fill.of("cobblestone"), 12);
                if (edge) c.set(x, y + 1, z, Math.abs(dx) == 2 && Math.abs(dz) == 2 ? "cobblestone_wall" : "mossy_cobblestone");
                else c.set(x, y + 1, z, "air");
            }
        // and a ladder up the inside of the north rim, so a player who falls in can always climb out
        c.set(cx, y - 1, cz - 1, "ladder[facing=south,waterlogged=true]");
        c.set(cx, y, cz - 1, "ladder[facing=south,waterlogged=true]");
        c.set(cx, y + 1, cz - 1, "ladder[facing=south]");
        for (int sx = -2; sx <= 2; sx += 4)
            for (int sz = -2; sz <= 2; sz += 4) {
                c.set(cx + sx, y + 2, cz + sz, "oak_fence");
                c.set(cx + sx, y + 3, cz + sz, "oak_fence");
            }
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) c.set(cx + dx, y + 4, cz + dz, Blocks.slab("spruce", "bottom"));
        c.set(cx, y + 3, cz, Blocks.lantern(true));
        return Box.of(cx - 2, y - 12, cz - 2, cx + 2, y + 4, cz + 2);
    }

    /**
     * A lamp post standing on ground block (x, y, z). Styles: "post" (wall base, fences, lantern on
     * top), "street" (tall post with an arm and a hanging lantern, arm pointing {@code facing}),
     * "torch" (fence + torch), "soul" (like post with a soul lantern).
     */
    public static void lampPost(VoxelCanvas c, int x, int y, int z, String style, String facing) {
        footing(c, x, y + 1, z, Fill.of("cobblestone"), 12);
        switch (style) {
            case "street": {
                c.set(x, y + 1, z, "stone_brick_wall");
                for (int h = 2; h <= 4; h++) c.set(x, y + h, z, "dark_oak_fence");
                int ax = x + Blocks.dx(facing), az = z + Blocks.dz(facing);
                c.set(ax, y + 4, az, "dark_oak_fence");
                c.set(ax, y + 3, az, Blocks.lantern(true));
                c.set(x, y + 5, z, Blocks.slab("dark_oak", "bottom"));
                break;
            }
            case "torch":
                c.set(x, y + 1, z, "oak_fence");
                c.set(x, y + 2, z, "oak_fence");
                c.set(x, y + 3, z, "torch");
                break;
            case "soul":
                c.set(x, y + 1, z, "polished_blackstone_wall");
                c.set(x, y + 2, z, "dark_oak_fence");
                c.set(x, y + 3, z, Blocks.soulLantern(false));
                break;
            default:
                c.set(x, y + 1, z, "cobblestone_wall");
                c.set(x, y + 2, z, "spruce_fence");
                c.set(x, y + 3, z, Blocks.lantern(false));
        }
    }

    // =======================================================================================
    // Paths
    // =======================================================================================

    /** Result of a path: every column it paved and the height it paved at (Pack.column -> y). */
    public static final class PathResult {
        public final LongIntMap cells = new LongIntMap(1024);
        public boolean covers(int x, int z) { return cells.containsKey(Pack.column(x, z)); }
        public int height(int x, int z) { return cells.get(Pack.column(x, z)); }
        /** Grown by {@code margin}: is (x,z) within margin blocks of the path? */
        public boolean near(int x, int z, int margin) {
            for (int dz = -margin; dz <= margin; dz++) for (int dx = -margin; dx <= margin; dx++) if (covers(x + dx, z + dz)) return true;
            return false;
        }
    }

    /**
     * A walkable path along a polyline of (x, z) points, {@code width} wide, following the
     * terrain. The height profile is smoothed so it never climbs more than one block per block
     * walked; every one-block climb gets a step block ({@code step}: a stairs id like
     * "stone_brick_stairs" for stairs, or a slab id like "spruce_slab" for half-steps). Terrain
     * and plants above the path are cleared (3 high) so it is walkable; buildings are not touched.
     * Pass {@code into} to accumulate several paths into one result.
     */
    public static PathResult path(VoxelCanvas c, int[][] pointsXZ, int width, Fill surface, String step, PathResult into) {
        PathResult res = into != null ? into : new PathResult();
        if (pointsXZ.length < 2) return res;
        // 1. sample the polyline every half block
        List<double[]> samples = new ArrayList<>();
        for (int i = 0; i + 1 < pointsXZ.length; i++) {
            double ax = pointsXZ[i][0], az = pointsXZ[i][1], bx = pointsXZ[i + 1][0], bz = pointsXZ[i + 1][1];
            double len = Math.hypot(bx - ax, bz - az);
            int n = Math.max(1, (int) Math.ceil(len * 2));
            for (int s = (i == 0 ? 0 : 1); s <= n; s++) {
                double t = (double) s / n;
                samples.add(new double[]{ax + (bx - ax) * t, az + (bz - az) * t, bx - ax, bz - az});
            }
        }
        // 2. raw ground height under each sample
        int[] raw = new int[samples.size()];
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < raw.length; i++) {
            int sx = (int) Math.floor(samples.get(i)[0] + 0.5), sz = (int) Math.floor(samples.get(i)[1] + 0.5);
            int from = prev == Integer.MIN_VALUE ? 320 : prev + 12, to = prev == Integer.MIN_VALUE ? -64 : prev - 16;
            int g = ground(c, sx, sz, from, to);
            if (g == Integer.MIN_VALUE) g = prev == Integer.MIN_VALUE ? ground(c, sx, sz, 320, -64) : prev;
            raw[i] = g;
            if (g != Integer.MIN_VALUE) prev = g;
        }
        for (int i = 0; i < raw.length; i++) if (raw[i] == Integer.MIN_VALUE) raw[i] = prev == Integer.MIN_VALUE ? 64 : prev;
        // 3. smooth (moving average over +-3 samples) then limit the climb to 1 block per 2 samples
        int[] h = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double s = 0; int n = 0;
            for (int j = Math.max(0, i - 3); j <= Math.min(raw.length - 1, i + 3); j++) { s += raw[j]; n++; }
            h[i] = (int) Math.round(s / n);
        }
        h[0] = raw[0];
        h[h.length - 1] = raw[raw.length - 1];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 2; i < h.length; i++) h[i] = Math.max(h[i - 2] - 1, Math.min(h[i - 2] + 1, h[i]));
            for (int i = h.length - 3; i >= 0; i--) h[i] = Math.max(h[i + 2] - 1, Math.min(h[i + 2] + 1, h[i]));
        }
        // 4. paint cells
        double rad = width / 2.0;
        LongIntMap dirOf = new LongIntMap(1024);
        for (int i = 0; i < samples.size(); i++) {
            double[] s = samples.get(i);
            int r = (int) Math.ceil(rad);
            for (int dz = -r; dz <= r; dz++)
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz > rad * rad + 0.01) continue;
                    int x = (int) Math.floor(s[0] + 0.5) + dx, z = (int) Math.floor(s[1] + 0.5) + dz;
                    long k = Pack.column(x, z);
                    if (res.cells.containsKey(k)) continue;
                    res.cells.put(k, h[i]);
                    dirOf.put(k, Blocks.index(Blocks.dirOf((int) Math.round(s[2] * 10), (int) Math.round(s[3] * 10))));
                }
        }
        res.cells.forEach((k, y) -> {
            if (!dirOf.containsKey(k)) return;
            int x = (int) (k >> 32), z = (int) k;
            if (!natural(c.get(x, y, z)) && !c.isAir(x, y, z)) return;     // never pave over a building
            c.set(x, y, z, surface.at(x, y, z));
            footing(c, x, y, z, Fill.of("dirt"), 12);
            for (int a = 1; a <= 3; a++) {
                String b = c.get(x, y + a, z);
                if (!c.isAir(x, y + a, z) && natural(b)) c.set(x, y + a, z, "air");
            }
        });
        // 5. steps where the next cell along the path is one higher
        if (step != null) {
            boolean slab = step.endsWith("_slab");
            res.cells.forEach((k, y) -> {
                if (!dirOf.containsKey(k)) return;
                int x = (int) (k >> 32), z = (int) k;
                int d = dirOf.get(k);
                for (int sgn : new int[]{0, 2}) {
                    int dd = (d + sgn) & 3;
                    int nx = x + Blocks.DX[dd], nz = z + Blocks.DZ[dd];
                    int nh = res.cells.get(Pack.column(nx, nz));
                    if (nh == y + 1 && c.isAir(x, y + 1, z)) {
                        c.set(x, y + 1, z, slab ? Blocks.slab(step, "bottom") : Blocks.stairs(step, Blocks.DIRS[dd]));
                        return;
                    }
                }
                for (int dd = 0; dd < 4; dd++) {
                    int nh = res.cells.get(Pack.column(x + Blocks.DX[dd], z + Blocks.DZ[dd]));
                    if (nh == y + 1 && c.isAir(x, y + 1, z)) { c.set(x, y + 1, z, slab ? Blocks.slab(step, "bottom") : Blocks.stairs(step, Blocks.DIRS[dd])); return; }
                }
            });
        }
        return res;
    }

    // =======================================================================================
    // Bridges & fences
    // =======================================================================================

    /**
     * A bridge deck from ground block (x1,y1,z1) to (x2,y2,z2): {@code width} wide (odd), fence
     * railings, log beams underneath, lantern posts every 8 blocks. Height changes become stairs.
     */
    public static void bridge(VoxelCanvas c, int x1, int y1, int z1, int x2, int y2, int z2, int width, Fill deck,
                              String railFence, String beamLog, String stairs) {
        double len = Math.hypot(x2 - x1, z2 - z1);
        int n = Math.max(1, (int) Math.ceil(len));
        String dirName = Blocks.dirOf(x2 - x1, z2 - z1);
        int di = Blocks.index(dirName);
        int px = Blocks.DX[(di + 1) & 3], pz = Blocks.DZ[(di + 1) & 3];
        int half = width / 2;
        int lastY = y1;
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            int bx = (int) Math.round(x1 + (x2 - x1) * t), bz = (int) Math.round(z1 + (z2 - z1) * t);
            int by = (int) Math.round(y1 + (y2 - y1) * t);
            if (by > lastY + 1) by = lastY + 1;
            if (by < lastY - 1) by = lastY - 1;
            for (int w = -half; w <= half; w++) {
                int x = bx + px * w, z = bz + pz * w;
                c.set(x, by, z, deck.at(x, by, z));
                for (int a = 1; a <= 3; a++) if (natural(c.get(x, by + a, z))) c.set(x, by + a, z, "air");
                if (by == lastY + 1 && i > 0) {
                    // previous cell gets a stair up toward this one
                    int qx = (int) Math.round(x1 + (x2 - x1) * ((double) (i - 1) / n)) + px * w, qz = (int) Math.round(z1 + (z2 - z1) * ((double) (i - 1) / n)) + pz * w;
                    if (stairs != null && c.isAir(qx, lastY + 1, qz)) c.set(qx, lastY + 1, qz, Blocks.stairs(stairs, dirName));
                } else if (by == lastY - 1 && i > 0 && stairs != null && c.isAir(x, by + 1, z)) {
                    c.set(x, by + 1, z, Blocks.stairs(stairs, Blocks.opposite(dirName)));
                }
            }
            if (i > 0 && i < n) {
                c.set(bx + px * (half + 1), by, bz + pz * (half + 1), deck.at(bx, by, bz));
                c.set(bx - px * (half + 1), by, bz - pz * (half + 1), deck.at(bx, by, bz));
                c.set(bx + px * (half + 1), by + 1, bz + pz * (half + 1), railFence);
                c.set(bx - px * (half + 1), by + 1, bz - pz * (half + 1), railFence);
                if (beamLog != null) {
                    char ax = Blocks.axisOf(x2 - x1, 0, z2 - z1);
                    c.set(bx + px * half, by - 1, bz + pz * half, beamLog + "[axis=" + ax + "]");
                    c.set(bx - px * half, by - 1, bz - pz * half, beamLog + "[axis=" + ax + "]");
                }
                if (i % 8 == 4) {
                    c.set(bx + px * (half + 1), by + 2, bz + pz * (half + 1), Blocks.lantern(false));
                    c.set(bx - px * (half + 1), by + 2, bz - pz * (half + 1), Blocks.lantern(false));
                }
            }
            lastY = by;
        }
    }

    /** Fence along a polyline of (x,z) points, standing on the ground. Returns the cells used. */
    public static List<int[]> fenceLine(VoxelCanvas c, int[][] pointsXZ, String fence, boolean closed) {
        List<int[]> out = new ArrayList<>();
        int segs = closed ? pointsXZ.length : pointsXZ.length - 1;
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < segs; i++) {
            int[] a = pointsXZ[i], b = pointsXZ[(i + 1) % pointsXZ.length];
            int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            for (int s = 0; s < Math.max(1, steps); s++) {
                double t = steps == 0 ? 0 : (double) s / steps;
                int x = (int) Math.round(a[0] + (b[0] - a[0]) * t), z = (int) Math.round(a[1] + (b[1] - a[1]) * t);
                int g = ground(c, x, z, prev == Integer.MIN_VALUE ? 320 : prev + 6, prev == Integer.MIN_VALUE ? -64 : prev - 10);
                if (g == Integer.MIN_VALUE) continue;
                prev = g;
                c.set(x, g + 1, z, fence);
                out.add(new int[]{x, g + 1, z});
            }
        }
        if (!closed && pointsXZ.length > 0) {
            int[] e = pointsXZ[pointsXZ.length - 1];
            int g = ground(c, e[0], e[1], 320, -64);
            if (g != Integer.MIN_VALUE) { c.set(e[0], g + 1, e[1], fence); out.add(new int[]{e[0], g + 1, e[1]}); }
        }
        return out;
    }

    /** Replace one fence post with a gate (closed) whose opening runs along {@code facing}. */
    public static void gate(VoxelCanvas c, int x, int y, int z, String wood, String facing) {
        c.set(x, y, z, Blocks.fenceGate(wood, facing, false));
    }

    // =======================================================================================
    // Decoration
    // =======================================================================================

    /** A small heap of barrels and hay with a trapdoor-lidded crate, on ground block (x, y, z). */
    public static void crates(VoxelCanvas c, int x, int y, int z, long seed) {
        java.util.Random r = new java.util.Random(Hash.hash(seed, x, y, z));
        int n = 2 + r.nextInt(3);
        int[][] spots = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
        for (int i = 0; i < n; i++) {
            int sx = x + spots[i][0], sz = z + spots[i][1];
            int g = ground(c, sx, sz, y + 2, y - 3);
            if (g == Integer.MIN_VALUE) continue;
            String b;
            switch (r.nextInt(4)) {
                case 0: b = "barrel[facing=up,open=false]"; break;
                case 1: b = "hay_block[axis=y]"; break;
                case 2: b = "spruce_planks"; break;
                default: b = "barrel[facing=" + Blocks.DIRS[r.nextInt(4)] + ",open=false]";
            }
            c.set(sx, g + 1, sz, b);
            if (b.equals("spruce_planks")) c.set(sx, g + 2, sz, Blocks.trapdoor("spruce", "north", "bottom", false));
            else if (i == 0 && r.nextBoolean()) c.set(sx, g + 2, sz, "barrel[facing=up,open=false]");
        }
    }

    /** A potted plant ("red_tulip", "fern", "cactus"...) on top of block (x, y, z). */
    public static void flowerPot(VoxelCanvas c, int x, int y, int z, String plant) {
        c.set(x, y + 1, z, plant == null ? "flower_pot" : "potted_" + plant);
    }

    /** A standing banner on ground block (x, y, z). rotation 0 = facing south, 4 = west, 8 = north, 12 = east. */
    public static void banner(VoxelCanvas c, int x, int y, int z, String color, int rotation) {
        c.set(x, y + 1, z, Blocks.banner(color, rotation));
    }

    /** A wall banner hanging on the face of a wall: (x,y,z) is the AIR cell in front of the wall, facing away from it. */
    public static void wallBanner(VoxelCanvas c, int x, int y, int z, String color, String facing) {
        c.set(x, y, z, Blocks.wallBanner(color, facing));
    }

    /** A bench on ground block (x, y, z), {@code length} long along the direction perpendicular to {@code facing} (the way a sitter looks). */
    public static void bench(VoxelCanvas c, int x, int y, int z, String facing, int length, String wood) {
        String back = Blocks.opposite(facing);
        String along = Blocks.cw(facing);
        for (int i = 0; i < length; i++) {
            int bx = x + Blocks.dx(along) * i, bz = z + Blocks.dz(along) * i;
            c.set(bx, y + 1, bz, Blocks.stairs(wood + "_stairs", back));
        }
        int ax = x - Blocks.dx(along), az = z - Blocks.dz(along);
        int bx = x + Blocks.dx(along) * length, bz = z + Blocks.dz(along) * length;
        c.set(ax, y + 1, az, Blocks.trapdoor(wood, Blocks.ccw(facing), "bottom", true));
        c.set(bx, y + 1, bz, Blocks.trapdoor(wood, Blocks.cw(facing), "bottom", true));
    }

    /** A campfire ring: campfire on the ground with a stone ring and two log seats. */
    public static void campfire(VoxelCanvas c, int x, int y, int z) {
        c.set(x, y + 1, z, Blocks.campfire(true, "north"));
        for (int d = 0; d < 4; d++) {
            int sx = x + Blocks.DX[d] * 2, sz = z + Blocks.DZ[d] * 2;
            if (d % 2 == 0) c.set(sx, y + 1, sz, "stripped_spruce_log[axis=" + (Blocks.DX[d] == 0 ? 'x' : 'z') + "]");
        }
    }
}
