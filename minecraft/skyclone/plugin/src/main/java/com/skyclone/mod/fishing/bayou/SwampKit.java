package com.skyclone.mod.fishing.bayou;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Shapes;

import java.util.Random;

/**
 * Swamp set pieces for the Backwater Bayou. Pure Java (gen toolkit only), so the preview renders
 * exactly what the game builds.
 */
final class SwampKit {

    private SwampKit() {}

    static boolean airish(VoxelCanvas c, int x, int y, int z) {
        String b = c.get(x, y, z);
        BlockInfo i = BlockInfo.of(b);
        return i.isAir() || i.top16 == 0 && !i.liquid();
    }

    static boolean water(VoxelCanvas c, int x, int y, int z) {
        return BlockState.name(c.get(x, y, z)).equals("water");
    }

    /**
     * A mangrove: an arched root cage standing in mud or shallow water, a leaning trunk and a broad
     * leafy crown with hanging moss. (x, groundY, z) is the ground (or bed) block under the trunk.
     */
    static void mangrove(VoxelCanvas c, int x, int groundY, int z, long seed, double size) {
        Random r = new Random(Hash.hash(seed, x, groundY, z));
        int lift = 2 + r.nextInt(2);
        int baseY = groundY + lift;                 // the trunk starts above the roots
        int trunk = (int) Math.round((5 + r.nextInt(4)) * size);
        // roots: arcs from the trunk base down to the ground around it, face-connected step by step
        int roots = 4 + r.nextInt(3);
        for (int i = 0; i < roots; i++) {
            double a = i * Math.PI * 2 / roots + r.nextDouble() * 0.6;
            int dist = 2 + r.nextInt(2);
            int ex = x + (int) Math.round(Math.cos(a) * dist), ez = z + (int) Math.round(Math.sin(a) * dist);
            int steps = dist + lift + 1;
            int px = x, py = baseY, pz = z;
            for (int s = 1; s <= steps; s++) {
                double t = (double) s / steps;
                int rx = (int) Math.round(x + (ex - x) * t);
                int rz = (int) Math.round(z + (ez - z) * t);
                int ry = (int) Math.round(baseY - (baseY - groundY) * (t * t));
                while (px != rx) { px += Integer.signum(rx - px); rootAt(c, px, py, pz); }
                while (pz != rz) { pz += Integer.signum(rz - pz); rootAt(c, px, py, pz); }
                while (py != ry) { py += Integer.signum(ry - py); rootAt(c, px, py, pz); }
            }
            // anchor the root foot down into the ground
            for (int yy = py - 1; yy >= groundY - 4; yy--) {
                if (!airish(c, px, yy, pz) && !water(c, px, yy, pz)) break;
                rootAt(c, px, yy, pz);
            }
        }
        for (int yy = groundY + 1; yy < baseY; yy++) rootAt(c, x, yy, z);
        // trunk
        int tx = x, tz = z;
        int leanX = r.nextInt(3) - 1, leanZ = r.nextInt(3) - 1;
        for (int h = 0; h < trunk; h++) {
            if (h == trunk / 2 && (leanX != 0 || leanZ != 0)) {
                if (leanX != 0) { tx += leanX; c.set(tx, baseY + h - 1, tz, "mangrove_log[axis=x]"); }
                if (leanZ != 0) { tz += leanZ; c.set(tx, baseY + h - 1, tz, "mangrove_log[axis=z]"); }
            }
            c.set(tx, baseY + h, tz, "mangrove_log[axis=y]");
        }
        int topY = baseY + trunk;
        // crown: a flattened blob with a few side lobes
        double rad = 3.2 * size + r.nextDouble();
        crown(c, tx, topY, tz, rad, 2.2, r);
        for (int i = 0; i < 3; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            int bx = tx + (int) Math.round(Math.cos(a) * rad * 0.9), bz = tz + (int) Math.round(Math.sin(a) * rad * 0.9);
            Shapes.line(c, tx, topY - 2, tz, bx, topY - 1, bz, 0.5, Fill.of("mangrove_log[axis=y]"));
            crown(c, bx, topY - 1, bz, rad * 0.6, 1.6, r);
        }
        c.set(tx, topY, tz, "mangrove_log[axis=y]");
        hangMoss(c, new Box((int) (tx - rad - 3), topY - 4, (int) (tz - rad - 3), (int) (tx + rad + 3), topY + 3, (int) (tz + rad + 3)), seed, 0.18);
    }

    private static void rootAt(VoxelCanvas c, int x, int y, int z) {
        if (water(c, x, y, z)) c.set(x, y, z, "mangrove_roots[waterlogged=true]");
        else if (airish(c, x, y, z)) c.set(x, y, z, "mangrove_roots[waterlogged=false]");
    }

    private static void crown(VoxelCanvas c, int cx, int cy, int cz, double r, double ry, Random rnd) {
        int R = (int) Math.ceil(r);
        int RY = (int) Math.ceil(ry);
        for (int dy = -RY; dy <= RY; dy++)
            for (int dz = -R; dz <= R; dz++)
                for (int dx = -R; dx <= R; dx++) {
                    double d = (dx * dx + dz * dz) / (r * r) + (dy * dy) / (ry * ry);
                    if (d > 1 || d > 0.75 && rnd.nextDouble() < 0.35) continue;
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (airish(c, x, y, z)) c.set(x, y, z, "mangrove_leaves[persistent=true]");
                }
    }

    /** Pale hanging moss / vines under leaves in a box. */
    static void hangMoss(VoxelCanvas c, Box b, long seed, double chance) {
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int y = b.maxY(); y >= b.minY(); y--) {
                    String n = BlockState.name(c.get(x, y, z));
                    if (!n.endsWith("_leaves") || !c.isAir(x, y - 1, z)) continue;
                    if (!Hash.chance(seed ^ 0x77L, x, y, z, chance)) continue;
                    int len = 1 + (int) (Hash.unit(seed, x, y + 3, z) * 3);
                    for (int i = 1; i <= len; i++) {
                        if (!c.isAir(x, y - i, z)) break;
                        c.set(x, y - i, z, i == len ? "pale_hanging_moss[tip=true]" : "pale_hanging_moss[tip=false]");
                    }
                }
    }

    /** Vines hanging from the underside of leaves. */
    static void vines(VoxelCanvas c, Box b, long seed, double chance) {
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z = b.minZ(); z <= b.maxZ(); z++)
                for (int y = b.maxY(); y >= b.minY(); y--) {
                    String n = BlockState.name(c.get(x, y, z));
                    if (!n.endsWith("_leaves") || !c.isAir(x, y - 1, z)) continue;
                    if (!Hash.chance(seed ^ 0x3131L, x, y, z, chance)) continue;
                    int len = 2 + (int) (Hash.unit(seed, x, y + 9, z) * 4);
                    for (int i = 1; i <= len; i++) {
                        if (!c.isAir(x, y - i, z)) break;
                        c.set(x, y - i, z, "vine[up=true]");
                    }
                }
    }

    /** A half-sunk rowing boat in the water, open hull, leaning. (x, bedY, z) is the lake bed under its middle. */
    static int[] wreck(VoxelCanvas c, int x, int bedY, int z, String facing) {
        Builders.Frame f = new Builders.Frame(x, bedY + 1, z, facing);
        Palette hull = Palette.of("oak_planks:4,dark_oak_planks:2,stripped_oak_log[axis=y]:1");
        for (int v = -5; v <= 5; v++) {
            int half = Math.abs(v) >= 4 ? 1 : 2;
            for (int u = -half; u <= half; u++) {
                f.set(c, u, 0, v, hull.at(f.x(u, v), bedY + 1, f.z(u, v)));
                if (Math.abs(u) == half || Math.abs(v) == 5) {
                    f.set(c, u, 1, v, hull.at(f.x(u, v), bedY + 2, f.z(u, v)));
                    if (v > -2) f.set(c, u, 2, v, Blocks.slab("oak", "bottom"));
                } else {
                    f.set(c, u, 1, v, "water");
                    f.set(c, u, 2, v, "water");
                }
            }
        }
        f.set(c, 0, 1, 2, "barrel[facing=up,open=false]");
        f.set(c, 0, 1, -2, "spruce_fence");
        f.set(c, 0, 2, -2, "spruce_fence");
        f.set(c, 0, 3, -2, "spruce_fence");
        return new int[]{f.x(0, 0), bedY + 1, f.z(0, 0)};
    }

    /** A fallen hollow log lying along x with a 1x2 tunnel through it. Returns the floor block inside. */
    static int[] hollowLog(VoxelCanvas c, int x, int groundY, int z, int len) {
        for (int i = 0; i < len; i++) {
            int lx = x + i;
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = 1; dy <= 3; dy++) {
                    boolean tunnel = dz == 0 && dy <= 2;
                    c.set(lx, groundY + dy, z + dz, tunnel ? "air" : "oak_log[axis=x]");
                }
            if (i % 3 == 1) c.set(lx, groundY + 4, z, "moss_carpet");
        }
        return new int[]{x + len / 2, groundY, z};
    }

    /** A crumbling stone hut (three walls standing, no roof). Returns a floor block inside. */
    static int[] ruin(VoxelCanvas c, int x, int groundY, int z, long seed) {
        Palette wall = Palette.of("mossy_cobblestone:5,cobblestone:3,mossy_stone_bricks:2,cracked_stone_bricks:1");
        int w = 7, d = 6;
        for (int dx = 0; dx < w; dx++)
            for (int dz = 0; dz < d; dz++) {
                int xx = x + dx, zz = z + dz;
                c.set(xx, groundY, zz, Palette.of("mossy_cobblestone:2,mud_bricks:2,packed_mud:1").at(xx, groundY, zz));
                Builders.footing(c, xx, groundY, zz, Fill.of("mud"), 6);
                boolean edge = dx == 0 || dz == 0 || dx == w - 1 || dz == d - 1;
                for (int h = 1; h <= 4; h++) {
                    if (edge) {
                        int keep = 4 - (int) (Hash.unit(seed, xx, 0, zz) * 3);   // ragged tops
                        boolean door = dz == 0 && dx == 3;
                        c.set(xx, groundY + h, zz, h <= keep && !door ? wall.at(xx, groundY + h, zz) : "air");
                    } else c.set(xx, groundY + h, zz, "air");
                }
            }
        c.set(x + 1, groundY + 1, z + d - 2, "cobweb");
        c.set(x + w - 2, groundY + 1, z + 1, "barrel[facing=up,open=false]");
        c.set(x + w - 2, groundY + 1, z + d - 2, Blocks.soulLantern(false));
        return new int[]{x + 3, groundY, z + 3};
    }

    /** A little open gazebo: four posts and a slab roof. (x,y,z) ground centre. */
    static void gazebo(VoxelCanvas c, int x, int y, int z, String wood) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                c.set(x + dx, y, z + dz, wood + "_planks");
                Builders.footing(c, x + dx, y, z + dz, Fill.of(wood + "_log[axis=y]"), 10);
                for (int h = 1; h <= 3; h++) c.set(x + dx, y + h, z + dz, "air");
                c.set(x + dx, y + 4, z + dz, Blocks.slab(wood, "bottom"));
            }
        for (int sx = -2; sx <= 2; sx += 4)
            for (int sz = -2; sz <= 2; sz += 4)
                for (int h = 1; h <= 3; h++) c.set(x + sx, y + h, z + sz, wood + "_fence");
        c.set(x, y + 3, z, Blocks.lantern(true));
        c.set(x, y + 4, z, wood + "_planks");
    }

    /** Log posts from a deck block down into the water/bed below. */
    static void stilt(VoxelCanvas c, int x, int deckY, int z, String log) {
        for (int y = deckY - 1; y > deckY - 14; y--) {
            String b = c.get(x, y, z);
            BlockInfo i = BlockInfo.of(b);
            if (!i.isAir() && !i.liquid() && i.top16 > 0) return;
            c.set(x, y, z, log + "[axis=y]");
        }
    }

    /** A drying rack of fish: two posts with a fence bar and hanging items look (carpets/trapdoors). */
    static void dryingRack(VoxelCanvas c, int x, int y, int z) {
        c.set(x, y + 1, z, "spruce_fence");
        c.set(x, y + 2, z, "spruce_fence");
        c.set(x + 3, y + 1, z, "spruce_fence");
        c.set(x + 3, y + 2, z, "spruce_fence");
        for (int i = 0; i <= 3; i++) c.set(x + i, y + 3, z, Blocks.slab("spruce", "bottom"));
        c.set(x + 1, y + 2, z, "dried_kelp_block");
        c.set(x + 2, y + 2, z, "oak_trapdoor[facing=north,half=top,open=true]");
    }

    /**
     * An open-sided market hall: plank floor, log posts every 4 blocks, a pitched slab roof with
     * hanging lanterns, and two rows of fish counters. Walkable all the way through.
     */
    static void marketHall(VoxelCanvas c, int x0, int z0, int x1, int z1, int y) {
        Palette floor = Palette.of("spruce_planks:3,stripped_spruce_wood[axis=y]:1,oak_planks:1");
        int mid = (z0 + z1) / 2;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                c.set(x, y, z, floor.at(x, y, z));
                Builders.footing(c, x, y, z, Fill.of("cobblestone"), 8);
                for (int hh = 1; hh <= 6; hh++) c.set(x, y + hh, z, "air");
                boolean postX = (x - x0) % 4 == 0 || x == x1;
                boolean edgeZ = z == z0 || z == z1;
                if (postX && edgeZ) for (int hh = 1; hh <= 3; hh++) c.set(x, y + hh, z, "spruce_log[axis=y]");
                int rise = Math.min(z - z0, z1 - z) / 2;
                c.set(x, y + 4 + rise, z, z == mid ? "spruce_planks" : Blocks.slab("dark_oak", "bottom"));
                for (int k = 0; k < rise; k++) c.set(x, y + 4 + k, z, "dark_oak_planks");   // roof body, no gaps
                if (edgeZ) c.set(x, y + 4, z, "spruce_planks");
            }
        for (int x = x0 + 2; x <= x1 - 2; x += 4) c.set(x, y + 3 + Math.min(mid - z0, z1 - mid) / 2, mid, Blocks.lantern(true));
        for (int x = x0 + 2; x <= x1 - 2; x++) {
            if ((x - x0) % 6 == 5) continue;     // gaps to walk through
            c.set(x, y + 1, z0 + 2, Blocks.slab("spruce", "top"));
            c.set(x, y + 1, z1 - 2, Blocks.slab("spruce", "top"));
            if ((x - x0) % 3 == 0) {
                c.set(x, y + 2, z0 + 2, (x % 2 == 0) ? "barrel[facing=up,open=false]" : "dried_kelp_block");
                c.set(x, y + 2, z1 - 2, (x % 2 == 0) ? "water_cauldron[level=3]" : "barrel[facing=up,open=false]");
            }
        }
    }

    /** A lighthouse: a striped round tower with a lit lantern room. Returns the gallery block you can stand on. */
    static int[] lighthouse(VoxelCanvas c, int cx, int y, int cz, int height) {
        Builders.Tower t = new Builders.Tower(cx, y, cz);
        t.radius = 3; t.height = height; t.top = "battlements"; t.door = "south";
        t.wall = (x, yy, z) -> ((yy - y) / 4) % 2 == 0 ? "white_terracotta" : "red_terracotta";
        t.floor = Fill.of("spruce_planks");
        t.build(c);
        int top = y + height;
        c.set(cx, top + 1, cz, "sea_lantern");
        c.set(cx, top + 2, cz, "glowstone");
        c.set(cx, top + 3, cz, "sea_lantern");
        for (int d = 0; d < 4; d++) c.set(cx + Blocks.DX[d], top + 3, cz + Blocks.DZ[d], Blocks.slab("dark_oak", "bottom"));
        c.set(cx, top + 4, cz, Blocks.slab("dark_oak", "bottom"));
        return t.platform;
    }

    /** A small rowing boat moored on the water surface. (x, waterY, z) = water surface block at its middle. */
    static void boat(VoxelCanvas c, int x, int waterY, int z, String facing) {
        Builders.Frame f = new Builders.Frame(x, waterY, z, facing);
        for (int v = -2; v <= 2; v++) {
            int half = Math.abs(v) == 2 ? 0 : 1;
            for (int u = -half; u <= half; u++) {
                boolean side = Math.abs(u) == half && half > 0 || Math.abs(v) == 2;
                f.set(c, u, 0, v, "oak_planks");
                if (side) f.set(c, u, 1, v, Blocks.slab("oak", "bottom"));
            }
        }
        f.set(c, 0, 1, 0, "spruce_trapdoor[facing=north,half=bottom,open=false]");
    }

    /** A lantern on a fence post standing on (x, y, z). */
    static void postLantern(VoxelCanvas c, int x, int y, int z, boolean soul) {
        c.set(x, y + 1, z, "spruce_fence");
        c.set(x, y + 2, z, soul ? Blocks.soulLantern(false) : Blocks.lantern(false));
    }
}
