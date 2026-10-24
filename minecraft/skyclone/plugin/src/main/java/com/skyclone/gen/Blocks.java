package com.skyclone.gen;

import com.skyclone.api.world.gen.VoxelCanvas;

/**
 * Correct block-state strings for the blocks that need them. Minecraft facts encoded here so no
 * builder has to remember them:
 * <ul>
 *   <li><b>Stairs</b> {@code facing} is the direction you walk to go UP them (the tall back side).
 *       A roof's south slope therefore faces NORTH. {@code half=top} is upside-down.</li>
 *   <li><b>Slabs</b> {@code type=bottom|top|double}.</li>
 *   <li><b>Logs</b> {@code axis=x|y|z}: a beam running east-west is axis=x.</li>
 *   <li><b>Doors</b> are two blocks: {@code half=lower} then {@code half=upper}, same facing/hinge.</li>
 *   <li><b>Ladders / wall torches / wall signs</b> {@code facing} points AWAY from the wall they hang on.</li>
 *   <li>Fences, panes, walls and bars are connected automatically by {@link Connect} — write the plain id.</li>
 * </ul>
 * Directions: north = -Z, south = +Z, east = +X, west = -X.
 */
public final class Blocks {

    private Blocks() {}

    public static final String[] DIRS = {"north", "east", "south", "west"};
    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DZ = {-1, 0, 1, 0};

    public static int index(String dir) {
        switch (dir) {
            case "north": return 0;
            case "east": return 1;
            case "south": return 2;
            case "west": return 3;
            default: throw new IllegalArgumentException("not a horizontal direction: " + dir);
        }
    }

    public static int dx(String dir) { return DX[index(dir)]; }

    public static int dz(String dir) { return DZ[index(dir)]; }

    public static String opposite(String dir) { return DIRS[(index(dir) + 2) & 3]; }

    /** Clockwise seen from above: north -> east -> south -> west. */
    public static String cw(String dir) { return DIRS[(index(dir) + 1) & 3]; }

    public static String ccw(String dir) { return DIRS[(index(dir) + 3) & 3]; }

    /** Horizontal direction of a vector (dominant axis). */
    public static String dirOf(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? "east" : "west";
        return dz >= 0 ? "south" : "north";
    }

    // ---- state builders -------------------------------------------------------------------

    /** {@code stairs("oak_stairs", "north")} = bottom, straight. Accepts "oak" too (adds _stairs). */
    public static String stairs(String id, String facing) { return stairs(id, facing, "bottom", "straight"); }

    public static String stairs(String id, String facing, String half, String shape) {
        return suffix(id, "_stairs") + "[facing=" + facing + ",half=" + half + ",shape=" + shape + ",waterlogged=false]";
    }

    public static String slab(String id, String type) {
        return suffix(id, "_slab") + "[type=" + type + ",waterlogged=false]";
    }

    public static String slab(String id) { return slab(id, "bottom"); }

    public static String log(String id, char axis) { return id + "[axis=" + axis + "]"; }

    /** Log axis for a direction vector. */
    public static char axisOf(int dx, int dy, int dz) {
        int ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return 'y';
        return ax >= az ? 'x' : 'z';
    }

    public static String facing(String id, String facing) { return id + "[facing=" + facing + "]"; }

    public static String ladder(String facing) { return "ladder[facing=" + facing + ",waterlogged=false]"; }

    public static String wallTorch(String facing) { return "wall_torch[facing=" + facing + "]"; }

    public static String lantern(boolean hanging) { return "lantern[hanging=" + hanging + ",waterlogged=false]"; }

    public static String soulLantern(boolean hanging) { return "soul_lantern[hanging=" + hanging + ",waterlogged=false]"; }

    public static String leaves(String id) { return suffix(id, "_leaves") + "[persistent=true]"; }

    public static String trapdoor(String id, String facing, String half, boolean open) {
        return suffix(id, "_trapdoor") + "[facing=" + facing + ",half=" + half + ",open=" + open + ",powered=false,waterlogged=false]";
    }

    public static String barrel(String facing) { return "barrel[facing=" + facing + ",open=false]"; }

    public static String fenceGate(String id, String facing, boolean open) {
        return suffix(id, "_fence_gate") + "[facing=" + facing + ",in_wall=false,open=" + open + ",powered=false]";
    }

    public static String campfire(boolean lit, String facing) {
        return "campfire[facing=" + facing + ",lit=" + lit + ",signal_fire=false,waterlogged=false]";
    }

    /** Standing banner, rotation 0-15 (0 = facing south). */
    public static String banner(String color, int rotation) { return color + "_banner[rotation=" + (rotation & 15) + "]"; }

    public static String wallBanner(String color, String facing) { return color + "_wall_banner[facing=" + facing + "]"; }

    /**
     * Writes a door (both halves) at (x, y, z) = lower half. {@code facing} = the direction a person
     * walking OUT of the building faces; hinge "left" or "right".
     */
    public static void door(VoxelCanvas c, int x, int y, int z, String id, String facing, String hinge, boolean open) {
        String d = suffix(id, "_door");
        String common = "facing=" + facing + ",hinge=" + hinge + ",open=" + open + ",powered=false";
        c.set(x, y, z, d + "[" + common + ",half=lower]");
        c.set(x, y + 1, z, d + "[" + common + ",half=upper]");
    }

    /** "oak" + part: planks, log, wood, stripped_log, stairs, slab, fence, fence_gate, door, trapdoor, leaves. */
    public static String wood(String type, String part) {
        boolean nether = type.equals("crimson") || type.equals("warped");
        boolean bamboo = type.equals("bamboo");
        switch (part) {
            case "log": return nether ? type + "_stem" : bamboo ? "bamboo_block" : type + "_log";
            case "wood": return nether ? type + "_hyphae" : bamboo ? "bamboo_block" : type + "_wood";
            case "stripped_log": return nether ? "stripped_" + type + "_stem" : bamboo ? "stripped_bamboo_block" : "stripped_" + type + "_log";
            case "leaves": return nether ? (type.equals("crimson") ? "nether_wart_block" : "warped_wart_block") : type + "_leaves";
            default: return type + "_" + part;
        }
    }

    static String suffix(String id, String suffix) {
        id = BlockState.name(id);
        return id.endsWith(suffix) ? id : id + suffix;
    }

    /** Material family for derived blocks: "stone_bricks" -> "stone_brick" (for stairs/slab/wall ids). */
    public static String family(String fullBlock) {
        String n = BlockState.name(fullBlock);
        if (n.endsWith("_planks")) return n.substring(0, n.length() - 7);
        if (n.endsWith("bricks")) return n.substring(0, n.length() - 1);
        if (n.endsWith("_tiles")) return n.substring(0, n.length() - 1);
        return n;
    }
}
