package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Garden's barn: a gambrel-roofed barn with a hay loft, the Desk, and a double-height front
 * hall. Pure Java. The SAME code runs in the island generator (default skin) and at runtime when a
 * player picks a barn skin (it is rebuilt into a MemoryCanvas and copied over the barn's box), so
 * every skin has exactly the same shape and the anchors inside it never move.
 *
 * <p>Everything it writes is inside {@link #BOX} at y &gt;= {@link #FLOOR_Y}; the floor row (y =
 * FLOOR_Y) is written only inside the footprint.
 */
public final class BarnBuilder {

    private BarnBuilder() {}

    // footprint (walls inclusive)
    public static final int X0 = -13, X1 = 13, Z0 = -34, Z1 = -16;
    public static final int FLOOR_Y = PlotGrid.GROUND_Y;           // 70
    public static final int WALL_TOP = FLOOR_Y + 8;                 // 78
    public static final int LOFT_Y = FLOOR_Y + 5;                   // 75 (loft floor block)
    public static final int LOFT_FRONT_Z = -24;                     // loft covers z -33 .. -24
    public static final Box BOX = new Box(X0 - 2, FLOOR_Y, Z0 - 2, X1 + 2, WALL_TOP + 18, Z1 + 2);

    /** The desk (a lectern) and where to stand to use it. */
    public static final int DESK_X = 8, DESK_Z = -21;
    public static final int[] DESK_STAND = {8, FLOOR_Y, -19};
    /** A free spot in the loft (fairy soul) and in the hall (spare NPC spot). */
    public static final int[] LOFT_SPOT = {9, LOFT_Y, -31};
    public static final int[] HALL_SPOT = {-6, FLOOR_Y, -21};

    /**
     * A skin: its SHAPE (the roof and what stands on it: gambrel, gable, hip, dome, mushroom, castle, pagoda,
     * spire) and its palette. Every shape keeps the same footprint, walls, doors, loft and Desk, so the anchors
     * inside never move; only what is above the wall line (and a porch / chimney / turrets in the box margin)
     * differs. Wave 5: skins used to be palette swaps of one gambrel barn.
     */
    public record Skin(String id, String shape, String wall, String trim, String roof, String roofFull, String floor, String accent, String lamp) {}

    public static final Map<String, Skin> SKINS = new LinkedHashMap<>();

    /** Every roof shape a skin can name. */
    public static final java.util.List<String> SHAPES = java.util.List.of("gambrel", "gable", "hip", "dome", "mushroom", "castle", "pagoda", "spire");

    private static void skin(String id, String shape, String wall, String trim, String roof, String roofFull, String floor, String accent, String lamp) {
        SKINS.put(id, new Skin(id, shape, wall, trim, roof, roofFull, floor, accent, lamp));
    }

    static {
        skin("default", "gambrel", "mangrove_planks:6,stripped_mangrove_log[axis=y]:1", "stripped_birch_log", "dark_oak", "dark_oak_planks", "spruce_planks", "white_terracotta", "lantern");
        skin("medieval", "gable", "cobblestone:3,stone_bricks:3,mossy_stone_bricks:1", "spruce_log", "spruce", "spruce_planks", "spruce_planks", "spruce_planks", "lantern");
        skin("red", "gambrel", "red_terracotta", "white_terracotta", "deepslate_tile", "deepslate_tiles", "dark_oak_planks", "white_terracotta", "lantern");
        skin("sunny", "hip", "red_sandstone:3,cut_red_sandstone:2", "smooth_sandstone", "red_sandstone", "red_sandstone", "birch_planks", "yellow_terracotta", "lantern");
        skin("cabin", "gable", "spruce_planks:3,stripped_spruce_wood:1", "spruce_log", "dark_oak", "dark_oak_planks", "spruce_planks", "stripped_spruce_log", "lantern");
        skin("mansion_heights", "hip", "quartz_block:4,quartz_bricks:1", "polished_blackstone", "blackstone", "blackstone", "dark_oak_planks", "polished_blackstone_bricks", "soul_lantern");
        skin("trading_post", "gambrel", "oak_planks:5,oak_log[axis=y]:1", "oak_log", "spruce", "spruce_planks", "oak_planks", "barrel[facing=up,open=false]", "lantern");
        skin("autumn_hut", "gable", "orange_terracotta:2,brown_terracotta:1", "dark_oak_log", "dark_oak", "dark_oak_planks", "spruce_planks", "brown_terracotta", "lantern");
        skin("cozy_cottage", "gable", "white_terracotta:2,birch_planks:1", "stripped_oak_log", "spruce", "spruce_planks", "oak_planks", "hay_block", "lantern");
        skin("tavern", "gambrel", "dark_oak_planks", "stripped_dark_oak_log", "deepslate_brick", "deepslate_bricks", "spruce_planks", "barrel[facing=up,open=false]", "lantern");
        skin("bamboo", "pagoda", "bamboo_planks:3,bamboo_mosaic:1", "bamboo_block", "bamboo", "bamboo_planks", "bamboo_planks", "bamboo_mosaic", "lantern");
        skin("melon", "dome", "lime_terracotta:3,green_terracotta:1", "melon", "warped", "melon", "birch_planks", "green_terracotta", "lantern");
        skin("hive", "dome", "honeycomb_block:3,yellow_terracotta:1", "stripped_birch_log", "sandstone", "honeycomb_block", "birch_planks", "yellow_terracotta", "lantern");
        skin("castle", "castle", "stone_bricks:5,cracked_stone_bricks:1,mossy_stone_bricks:1", "polished_andesite", "stone_brick", "stone_bricks", "spruce_planks", "chiseled_stone_bricks", "lantern");
        skin("mushroom", "mushroom", "mushroom_stem", "brown_mushroom_block", "red_nether_brick", "red_mushroom_block", "spruce_planks", "mushroom_stem", "lantern");
        skin("winter_barn", "gable", "spruce_planks:4,stripped_spruce_wood:1", "stripped_spruce_log", "quartz", "quartz_block", "spruce_planks", "snow_block", "lantern");
        skin("sand_castle", "castle", "sandstone:3,cut_sandstone:2,chiseled_sandstone:1", "smooth_sandstone", "sandstone", "sandstone", "birch_planks", "chiseled_sandstone", "lantern");
        skin("the_end", "spire", "end_stone_bricks", "purpur_pillar", "purpur", "purpur_block", "purpur_block", "purpur_block", "end_rod[facing=up]");
        skin("pestwarden_lair", "gable", "mossy_cobblestone:3,mud_bricks:2", "dark_oak_log", "mud_brick", "mud_bricks", "spruce_planks", "moss_block", "soul_lantern");
    }

    /** The building each shape makes, for the skin menu. */
    public static String style(String shape) {
        return switch (shape == null ? "" : shape) {
            case "gable" -> "Gabled Hall";
            case "hip" -> "Hipped Lodge";
            case "dome" -> "Domed Hall";
            case "mushroom" -> "Mushroom Cap";
            case "castle" -> "Keep with Turrets";
            case "pagoda" -> "Two-Tier Pagoda";
            case "spire" -> "Spired Hall";
            default -> "Gambrel Barn";
        };
    }

    public static Skin skin(String id) {
        Skin s = SKINS.get(id);
        return s != null ? s : SKINS.get("default");
    }

    private static boolean axial(String b) {
        return b.endsWith("_log") || b.endsWith("_wood") || b.endsWith("_stem") || b.endsWith("pillar") || b.equals("bamboo_block")
                || b.equals("hay_block") || b.endsWith("basalt");
    }

    static String axis(String b, char a) {
        if (b.equals("mushroom_stem")) return b;
        return axial(b) ? b + "[axis=" + a + "]" : b;
    }

    /** Build the barn with a skin. */
    public static void build(VoxelCanvas c, String skinId, long seed) {
        Skin s = skin(skinId);
        Fill wall = Palette.of(s.wall);
        Fill floor = Palette.of(s.floor);
        String trim = s.trim;
        int y = FLOOR_Y;

        // ---- floor ----
        for (int z = Z0; z <= Z1; z++)
            for (int x = X0; x <= X1; x++) {
                boolean perim = x == X0 || x == X1 || z == Z0 || z == Z1;
                c.set(x, y, z, perim ? "polished_andesite" : floor.at(x, y, z));
            }
        // ---- walls ----
        for (int yy = y + 1; yy <= WALL_TOP; yy++)
            for (int z = Z0; z <= Z1; z++)
                for (int x = X0; x <= X1; x++) {
                    boolean wx = x == X0 || x == X1, wz = z == Z0 || z == Z1;
                    if (!wx && !wz) { c.set(x, yy, z, "air"); continue; }
                    if (wx && wz) c.set(x, yy, z, axis(trim, 'y'));
                    else if (yy == LOFT_Y || yy == WALL_TOP) c.set(x, yy, z, axis(trim, wz ? 'x' : 'z'));
                    else if (wz && (x - X0) % 9 == 0) c.set(x, yy, z, axis(trim, 'y'));     // posts on the long walls
                    else if (wx && (z - Z0) % 6 == 0) c.set(x, yy, z, axis(trim, 'y'));
                    else c.set(x, yy, z, wall.at(x, yy, z));
                }
        // ---- big front door (south), trimmed ----
        for (int x = -2; x <= 2; x++)
            for (int yy = y + 1; yy <= y + 5; yy++) c.set(x, yy, Z1, "air");
        for (int yy = y + 1; yy <= y + 6; yy++) { c.set(-3, yy, Z1, axis(trim, 'y')); c.set(3, yy, Z1, axis(trim, 'y')); }
        for (int x = -3; x <= 3; x++) c.set(x, y + 6, Z1, axis(trim, 'x'));
        // the two door leaves, swung open against the wall (decoration, flush with the wall face)
        for (int yy = y + 1; yy <= y + 5; yy++) {
            c.set(-5, yy, Z1 + 1, Blocks.trapdoor("spruce", "south", yy % 2 == 0 ? "top" : "bottom", true));
            c.set(5, yy, Z1 + 1, Blocks.trapdoor("spruce", "south", yy % 2 == 0 ? "top" : "bottom", true));
        }
        // ---- back door (north) ----
        c.set(0, y + 1, Z0, "air");
        c.set(0, y + 2, Z0, "air");
        Blocks.door(c, 0, y + 1, Z0, "spruce", "north", "left", false);
        // ---- windows ----
        for (int z = Z0 + 3; z <= Z1 - 3; z += 4) {
            for (int xs : new int[]{X0, X1}) {
                if ((z - Z0) % 6 == 0) continue;
                c.set(xs, y + 3, z, "glass_pane");
                c.set(xs, y + 2, z, "glass_pane");
                c.set(xs, LOFT_Y + 2, z, "glass_pane");
            }
        }
        for (int x = X0 + 3; x <= X1 - 3; x += 4) {
            if ((x - X0) % 9 == 0 || Math.abs(x) <= 4) continue;
            c.set(x, y + 3, Z1, "glass_pane");
            c.set(x, y + 3, Z0, "glass_pane");
        }
        // round loft window above the door
        c.set(0, LOFT_Y + 2, Z1, "glass_pane");
        c.set(-1, LOFT_Y + 2, Z1, "glass_pane");
        c.set(1, LOFT_Y + 2, Z1, "glass_pane");

        // ---- loft (back half) ----
        for (int z = Z0 + 1; z <= LOFT_FRONT_Z; z++)
            for (int x = X0 + 1; x <= X1 - 1; x++) c.set(x, LOFT_Y, z, floor.at(x, LOFT_Y, z));
        for (int x = X0 + 2; x <= X1 - 1; x++) c.set(x, LOFT_Y + 1, LOFT_FRONT_Z, "spruce_fence");
        // stairs up the west wall, walking north
        for (int i = 0; i < 5; i++) {
            int sz = LOFT_FRONT_Z + 5 - i, sy = y + 1 + i;
            c.set(X0 + 1, sy, sz, Blocks.stairs("spruce", "north"));
            for (int k = sy - 1; k > y; k--) c.set(X0 + 1, k, sz, "spruce_planks");
        }
        // hay in the loft and below
        for (int[] hb : new int[][]{{-9, -32}, {-8, -32}, {-9, -31}, {-6, -32}, {11, -32}, {11, -30}, {5, -32}, {-2, -32}, {-2, -31}})
            c.set(hb[0], LOFT_Y + 1, hb[1], "hay_block[axis=" + ((hb[0] + hb[1]) % 2 == 0 ? 'x' : 'z') + "]");
        c.set(-9, LOFT_Y + 2, -32, "hay_block[axis=y]");
        c.set(11, LOFT_Y + 2, -32, "hay_block[axis=y]");
        // ground floor: stalls along the back, barrels, the desk corner
        for (int x = X0 + 3; x <= X1 - 3; x++) {
            if (Math.abs(x) <= 1) continue;
            c.set(x, y + 1, Z0 + 2, (x % 4 == 0) ? "spruce_fence" : "air");
        }
        for (int z = Z0 + 1; z <= Z0 + 2; z++) {
            c.set(X0 + 2, y + 1, z, "hay_block[axis=y]");
            c.set(X1 - 2, y + 1, z, "hay_block[axis=y]");
        }
        c.set(-12, y + 1, -26, "barrel[facing=up,open=false]");
        c.set(-12, y + 1, -27, "barrel[facing=up,open=false]");
        c.set(-12, y + 2, -27, "barrel[facing=up,open=false]");
        c.set(12, y + 1, -26, "composter[level=8]");
        // the Desk
        c.set(DESK_X, y + 1, DESK_Z, "lectern[facing=south,has_book=true,powered=false]");
        c.set(DESK_X - 1, y + 1, DESK_Z, "bookshelf");
        c.set(DESK_X + 1, y + 1, DESK_Z, "bookshelf");
        c.set(DESK_X + 1, y + 2, DESK_Z, "potted_red_tulip");
        c.set(DESK_X - 1, y + 2, DESK_Z, "lantern[hanging=false,waterlogged=false]");
        c.set(DESK_X + 3, y + 1, DESK_Z, "cartography_table");
        c.set(DESK_X - 3, y + 1, DESK_Z, "smoker[facing=south,lit=false]");
        for (int x = DESK_X - 2; x <= DESK_X + 2; x++) c.set(x, y + 1, DESK_Z + 1, "air");
        for (int x = DESK_X - 1; x <= DESK_X + 1; x++)
            for (int z = DESK_Z + 1; z <= DESK_Z + 3; z++) c.set(x, y + 1, z, "green_carpet");
        // accents: a stripe under the eaves on the front
        for (int x = X0 + 1; x <= X1 - 1; x++) if (Math.abs(x) > 3) c.set(x, WALL_TOP - 1, Z1, accentBlock(s));
        // lights (under a flat ceiling they hang from it; under a pitched roof from a chain)
        boolean flat = flatCeiling(s.shape);
        for (int x = -8; x <= 8; x += 8) {
            if (flat) c.set(x, WALL_TOP, -20, lamp(s, true));
            else {
                c.set(x, WALL_TOP + 3, -20, "iron_chain");
                c.set(x, WALL_TOP + 2, -20, lamp(s, true));
            }
            c.set(x, LOFT_Y + 3, -29, lamp(s, true));
        }
        c.set(-4, y + 6, Z1 + 1, lamp(s, false).startsWith("end_rod") ? lamp(s, false) : "wall_torch[facing=south]");
        c.set(4, y + 6, Z1 + 1, lamp(s, false).startsWith("end_rod") ? lamp(s, false) : "wall_torch[facing=south]");

        // ---- the roof: the shape is what makes a skin a different building ----
        int peak = switch (s.shape) {
            case "gable" -> BarnShapes.gable(c, s, wall);
            case "hip" -> BarnShapes.hip(c, s, wall);
            case "dome" -> BarnShapes.dome(c, s);
            case "mushroom" -> BarnShapes.mushroom(c, s);
            case "castle" -> BarnShapes.castle(c, s, wall);
            case "pagoda" -> BarnShapes.pagoda(c, s, wall);
            case "spire" -> BarnShapes.spire(c, s, wall);
            default -> gambrel(c, s, wall);
        };
        BarnShapes.extras(c, s, wall, peak);
        if (!flat && !s.shape.equals("pagoda")) {
            // weathervane
            c.set(0, peak + 1, (Z0 + Z1) / 2, "iron_chain");
            c.set(0, peak + 2, (Z0 + Z1) / 2, "lightning_rod[facing=up,powered=false,waterlogged=false]");
        }
    }

    /** Shapes that close the barn with a flat ceiling at WALL_TOP + 1 (the decoration sits on top of it). */
    static boolean flatCeiling(String shape) {
        return shape.equals("dome") || shape.equals("mushroom") || shape.equals("castle") || shape.equals("spire");
    }

    private static String accentBlock(Skin s) { return s.accent; }

    static String lamp(Skin s, boolean hanging) {
        if (s.lamp.startsWith("end_rod")) return "end_rod[facing=" + (hanging ? "down" : "up") + "]";
        return s.lamp + "[hanging=" + hanging + ",waterlogged=false]";
    }

    /**
     * Gambrel profile across z: a steep lower pitch (two blocks up per block in) for three steps,
     * then a gentle upper pitch (one up per one in) to the ridge. One block of overhang.
     */
    private static int gambrel(VoxelCanvas c, Skin s, Fill wall) {
        String st = s.roof, full = s.roofFull;
        int vMin = Z0 - 1, vMax = Z1 + 1;
        int uMin = X0 - 1, uMax = X1 + 1;
        int base = WALL_TOP;               // the first roof row sits on top of the wall line, overhanging
        int k = 0, yy = base, top = base;
        // per row: inset k from each side, row height yy
        java.util.List<int[]> rows = new java.util.ArrayList<>();
        int steep = 3;
        while (true) {
            int vl = vMin + k, vr = vMax - k;
            if (vl > vr) break;
            rows.add(new int[]{vl, vr, yy});
            top = yy;
            if (vl == vr || vl + 1 == vr) break;
            k++;
            yy += (k <= steep) ? 2 : 1;
        }
        for (int i = 0; i < rows.size(); i++) {
            int vl = rows.get(i)[0], vr = rows.get(i)[1], ry = rows.get(i)[2];
            boolean steepRow = i < steep;
            for (int u = uMin; u <= uMax; u++) {
                if (vl == vr) { c.set(u, ry, vl, full); continue; }
                c.set(u, ry, vl, Blocks.stairs(st, "south"));
                c.set(u, ry, vr, Blocks.stairs(st, "north"));
                if (steepRow) {
                    // the steep pitch: a full block under the next stair row, so the roof is closed
                    c.set(u, ry + 1, vl, full);
                    c.set(u, ry + 1, vr, full);
                }
                if (vl + 1 == vr) {
                    c.set(u, ry + 1, vl, Blocks.slab(st, "bottom"));
                    c.set(u, ry + 1, vr, Blocks.slab(st, "bottom"));
                    top = ry + 1;
                }
            }
        }
        // fill the gable triangle fully (rows above can leave gaps between steps)
        for (int gy = WALL_TOP + 1; gy <= top; gy++)
            for (int v = Z0; v <= Z1; v++)
                for (int gx : new int[]{X0, X1}) {
                    if (!roofAbove(c, gx, gy, v)) continue;
                    if (c.isAir(gx, gy, v)) c.set(gx, gy, v, wall.at(gx, gy, v));
                }
        // gable vents
        int mid = (Z0 + Z1) / 2;
        c.set(X0, WALL_TOP + 4, mid, "glass_pane");
        c.set(X1, WALL_TOP + 4, mid, "glass_pane");
        return top;
    }

    /** Is there roof somewhere above (x, y, z) in this column (so a gable fill stays under the roof line)? */
    static boolean roofAbove(VoxelCanvas c, int x, int y, int z) {
        for (int yy = y + 1; yy <= y + 16; yy++) if (!c.isAir(x, yy, z)) return true;
        return false;
    }
}
