package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Small nether-flavoured pieces the Crimson Isle builders share. Pure Java. */
final class CKit {

    private CKit() {}

    static final Palette BLACK_BRICK = Palette.of("polished_blackstone_bricks:8,cracked_polished_blackstone_bricks:2,chiseled_polished_blackstone:1");
    static final Palette RED_BRICK = Palette.of("red_nether_bricks:7,nether_bricks:2");
    static final Palette ROAD = Palette.of("blackstone:4,polished_blackstone:3,gravel:2,basalt[axis=y]:1");

    /** A huge nether fungus standing on ground block (x, y, z). */
    static void fungus(VoxelCanvas c, int x, int y, int z, long seed, double size, boolean warped) {
        Random r = new Random(Hash.hash(seed, x, y, z));
        String stem = warped ? "warped_stem" : "crimson_stem";
        String wart = warped ? "warped_wart_block" : "nether_wart_block";
        int h = (int) Math.round((5 + r.nextInt(4)) * size);
        for (int i = 1; i <= h; i++) c.set(x, y + i, z, stem + "[axis=y]");
        int capY = y + h;
        int rad = (int) Math.round((2.5 + r.nextDouble()) * size);
        for (int dy = -2; dy <= 1; dy++) {
            int rr = dy == 1 ? rad - 1 : rad;
            for (int dz = -rr; dz <= rr; dz++)
                for (int dx = -rr; dx <= rr; dx++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > rr + 0.3) continue;
                    boolean shell = d > rr - 1.2 || dy == 1;
                    if (!shell) continue;
                    if (dy < 0 && d < rr - 0.6) continue;           // only the rim hangs down
                    int bx = x + dx, by = capY + dy, bz = z + dz;
                    if (!c.isAir(bx, by, bz)) continue;
                    String b = Hash.unit(seed, bx, by, bz) < 0.12 ? "shroomlight" : wart;
                    c.set(bx, by, bz, b);
                }
        }
        // a few vines hanging from the rim
        String vine = warped ? "twisting_vines_plant" : "weeping_vines_plant";
        String vineEnd = warped ? "twisting_vines" : "weeping_vines";
        if (!warped) {
            for (int k = 0; k < 4; k++) {
                double a = r.nextDouble() * Math.PI * 2;
                int vx = x + (int) Math.round(Math.cos(a) * rad), vz = z + (int) Math.round(Math.sin(a) * rad);
                int vy = capY - 3;
                if (c.isAir(vx, vy + 1, vz)) continue;
                int len = 1 + r.nextInt(3);
                for (int i = 0; i < len; i++) {
                    if (!c.isAir(vx, vy - i, vz)) break;
                    c.set(vx, vy - i, vz, i == len - 1 ? vineEnd : vine);
                }
            }
        }
        c.set(x, capY + 1, z, wart);
    }

    /** A big red mushroom whose cap sides hang straight under the cap's rim (every block face-connected). */
    static void redMushroom(VoxelCanvas c, int x, int y, int z, long seed) {
        Random r = new Random(Hash.hash(seed, x, y, z));
        int h = 5 + r.nextInt(3);
        for (int i = 1; i <= h; i++) c.set(x, y + i, z, "mushroom_stem");
        int R = 3, top = y + h + 1;
        for (int dz = -R; dz <= R; dz++)
            for (int dx = -R; dx <= R; dx++) {
                if (Math.abs(dx) == R && Math.abs(dz) == R) continue;
                if (c.isAir(x + dx, top, z + dz)) c.set(x + dx, top, z + dz, "red_mushroom_block");
                boolean rim = Math.abs(dx) == R || Math.abs(dz) == R;
                if (rim) for (int k = 1; k <= 2; k++) if (c.isAir(x + dx, top - k, z + dz)) c.set(x + dx, top - k, z + dz, "red_mushroom_block");
            }
    }

    /**
     * A pool carved into filled terrain, like Terrain.pond but with any liquid and bed. Columns
     * become wet. Returns the liquid level or NONE if the spot is not fully on land.
     */
    static int pool(VoxelCanvas c, Terrain.Heightmap h, int cx, int cz, double radius, int depth, long seed, String liquid, Palette bed) {
        Noise n = new Noise(Hash.salt(seed, "pool" + cx + "," + cz));
        int r = (int) Math.ceil(radius * 1.3) + 1;
        List<int[]> inside = new ArrayList<>();
        for (int z = cz - r; z <= cz + r; z++)
            for (int x = cx - r; x <= cx + r; x++) {
                double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)) / (radius * (1 + 0.22 * n.noise2(x / 5.0, z / 5.0)));
                if (d <= 1) {
                    if (!h.inland(x, z, 2)) return Terrain.Heightmap.NONE;
                    inside.add(new int[]{x, z, (int) Math.round(depth * (1 - d * d))});
                }
            }
        java.util.Set<Long> in = new java.util.HashSet<>();
        for (int[] p : inside) in.add(((long) p[0] << 32) ^ (p[1] & 0xffffffffL));
        int rim = Integer.MAX_VALUE;
        for (int[] p : inside)
            for (int d = 0; d < 4; d++) {
                int nx = p[0] + Blocks.DX[d], nz = p[1] + Blocks.DZ[d];
                if (!in.contains(((long) nx << 32) ^ (nz & 0xffffffffL))) rim = Math.min(rim, h.top(nx, nz));
            }
        if (rim == Integer.MAX_VALUE || inside.isEmpty()) return Terrain.Heightmap.NONE;
        int level = rim - 1;
        for (int[] p : inside) {
            int x = p[0], z = p[1];
            int floorY = level - Math.max(1, p[2]);
            int t = h.top(x, z);
            for (int y = floorY + 1; y <= Math.max(t, level); y++) c.set(x, y, z, y <= level ? liquid : "air");
            c.set(x, floorY, z, bed.at(x, floorY, z));
            // seal the sides below the level so the liquid can never leak sideways
            for (int d = 0; d < 4; d++) {
                int nx = x + Blocks.DX[d], nz = z + Blocks.DZ[d];
                for (int y = floorY; y <= level; y++) if (c.isAir(nx, y, nz)) c.set(nx, y, nz, bed.at(nx, y, nz));
            }
            h.setTop(x, z, level);
            h.setWet(x, z, true);
        }
        return level;
    }

    /**
     * A framed portal standing on ground y at (x, z): opening {@code w} wide (along X when
     * {@code alongX}, else along Z) and 4 high. Returns the opening box (the portal area).
     */
    static Box portal(VoxelCanvas c, int x, int y, int z, boolean alongX, int w, Fill frame, String glow) {
        int half = w / 2;
        int x0 = alongX ? x - half : x, x1 = alongX ? x - half + w - 1 : x;
        int z0 = alongX ? z : z - half, z1 = alongX ? z : z - half + w - 1;
        // base slab across + one block each side
        for (int i = -1; i <= w; i++) {
            int bx = alongX ? x0 + i : x, bz = alongX ? z : z0 + i;
            for (int k = -1; k <= 1; k++) {
                int px = alongX ? bx : bx + k, pz = alongX ? bz + k : bz;
                c.set(px, y, pz, frame.at(px, y, pz));
                Builders.footing(c, px, y, pz, frame, 10);
            }
        }
        for (int h = 1; h <= 5; h++) {
            int ax = alongX ? x0 - 1 : x, az = alongX ? z : z0 - 1;
            int bx = alongX ? x1 + 1 : x, bz = alongX ? z : z1 + 1;
            c.set(ax, y + h, az, frame.at(ax, y + h, az));
            c.set(bx, y + h, bz, frame.at(bx, y + h, bz));
        }
        for (int i = -1; i <= w; i++) {
            int bx = alongX ? x0 + i : x, bz = alongX ? z : z0 + i;
            c.set(bx, y + 5, bz, frame.at(bx, y + 5, bz));
        }
        String axis = alongX ? "x" : "z";
        for (int i = 0; i < w; i++)
            for (int h = 1; h <= 4; h++) {
                int bx = alongX ? x0 + i : x, bz = alongX ? z : z0 + i;
                c.set(bx, y + h, bz, "nether_portal[axis=" + axis + "]");
            }
        // glowing caps on the frame corners
        int ax = alongX ? x0 - 1 : x, az = alongX ? z : z0 - 1;
        int bx = alongX ? x1 + 1 : x, bz = alongX ? z : z1 + 1;
        c.set(ax, y + 6, az, glow);
        c.set(bx, y + 6, bz, glow);
        return Box.of(x0, y + 1, z0, x1, y + 4, z1);
    }

    /** A blackstone brazier with a soul or normal campfire on top, on ground block (x,y,z). */
    static void brazier(VoxelCanvas c, int x, int y, int z, boolean soul) {
        c.set(x, y + 1, z, "polished_blackstone_wall");
        c.set(x, y + 2, z, "chiseled_polished_blackstone");
        c.set(x, y + 3, z, soul ? "soul_campfire[lit=true,facing=north,signal_fire=false,waterlogged=false]"
                : Blocks.campfire(true, "north"));
        Builders.footing(c, x, y + 1, z, Fill.of("blackstone"), 8);
    }

    /** A broken pillar of {@code height} max on ground (x,y,z); the top crumbles. */
    static void ruinPillar(VoxelCanvas c, int x, int y, int z, int height, Palette p, long seed) {
        int h = Math.max(2, height - (int) (Hash.unit(seed, x, 3, z) * height * 0.6));
        for (int i = 1; i <= h; i++) c.set(x, y + i, z, p.at(x, y + i, z));
        Builders.footing(c, x, y + 1, z, p, 8);
        if (Hash.unit(seed, x, 5, z) < 0.5) {
            String fam = Blocks.family(p.first());
            c.set(x, y + h + 1, z, Blocks.slab(fam, "bottom"));
        }
    }

    /** A bone "dragon skull" sculpture facing {@code facing}, on ground (x,y,z). About 7x7x9. */
    static void dragonSkull(VoxelCanvas c, int x, int y, int z, String facing) {
        Builders.Frame f = new Builders.Frame(x, y, z, facing);
        String bone = "bone_block[axis=y]";
        // plinth
        for (int u = -3; u <= 3; u++)
            for (int v = -4; v <= 5; v++) {
                f.set(c, u, 0, v, "polished_blackstone");
                Builders.footing(c, f.x(u, v), y, f.z(u, v), Fill.of("blackstone"), 8);
            }
        // cranium
        for (int u = -2; u <= 2; u++)
            for (int h = 1; h <= 4; h++)
                for (int v = -3; v <= 0; v++) {
                    if (Math.abs(u) == 2 && h == 4) continue;
                    f.set(c, u, h, v, bone);
                }
        // snout
        for (int u = -1; u <= 1; u++)
            for (int h = 1; h <= 2; h++)
                for (int v = 1; v <= 4; v++) f.set(c, u, h, v, bone);
        // eye sockets glowing
        f.set(c, -1, 3, 1, "air");
        f.set(c, 1, 3, 1, "air");
        f.set(c, -1, 3, 0, "shroomlight");
        f.set(c, 1, 3, 0, "shroomlight");
        // horns: a stair-stepped sweep back from the brow, every block face-connected
        int[][] horn = {{4, -2}, {5, -2}, {5, -3}, {6, -3}, {6, -4}, {7, -4}};
        for (int[] hv : horn) { f.set(c, -2, hv[0], hv[1], bone); f.set(c, 2, hv[0], hv[1], bone); }
        // teeth
        f.set(c, -1, 0, 4, "polished_blackstone");
        f.set(c, 1, 1, 5, "bone_block[axis=y]");
        f.set(c, -1, 1, 5, "bone_block[axis=y]");
    }

    /** A wooden signpost with two arms (a crossroads marker) on ground (x,y,z). */
    static void signpost(VoxelCanvas c, int x, int y, int z) {
        for (int h = 1; h <= 4; h++) c.set(x, y + h, z, "crimson_fence");
        c.set(x, y + 5, z, "soul_lantern[hanging=false,waterlogged=false]");
        c.set(x + 1, y + 4, z, "crimson_wall_sign[facing=east,waterlogged=false]");
        c.set(x - 1, y + 4, z, "crimson_wall_sign[facing=west,waterlogged=false]");
        c.set(x, y + 3, z + 1, "crimson_wall_sign[facing=south,waterlogged=false]");
        c.set(x, y + 3, z - 1, "crimson_wall_sign[facing=north,waterlogged=false]");
    }

    /** A banner pole: blackstone wall with a fence and a banner on top, on ground (x,y,z). */
    static void bannerPole(VoxelCanvas c, int x, int y, int z, String color, int rotation) {
        c.set(x, y + 1, z, "polished_blackstone_wall");
        c.set(x, y + 2, z, "nether_brick_fence");
        c.set(x, y + 3, z, Blocks.banner(color, rotation));
    }

    /**
     * A town wall along a closed polyline of (x,z) corners, standing on the ground, {@code height} tall.
     * Cells where {@code gap} is true are left open (gates for the roads); cells inside a reserved
     * building box are skipped. {@code cap} goes on top, {@code post} every 8 cells with a lantern.
     */
    static void townWall(CCtx x, int[][] corners, int height, Fill body, String cap, String post,
                         java.util.function.BiPredicate<Integer, Integer> gap) {
        VoxelCanvas c = x.c;
        int k = 0;
        for (int i = 0; i < corners.length; i++) {
            int[] a = corners[i], b = corners[(i + 1) % corners.length];
            int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            for (int s = 0; s < steps; s++, k++) {
                int wx = a[0] + Integer.signum(b[0] - a[0]) * s, wz = a[1] + Integer.signum(b[1] - a[1]) * s;
                if (!x.h.has(wx, wz) || x.h.wet(wx, wz) || gap.test(wx, wz) || x.res.covers(wx, wz, 1)) continue;
                int g = x.ground(wx, wz);
                if (!c.isAir(wx, g + 1, wz) && !Builders.natural(c.get(wx, g + 1, wz))) continue;
                boolean isPost = k % 8 == 0;
                int hh = isPost ? height + 1 : height;
                for (int h = 1; h <= hh; h++) c.set(wx, g + h, wz, isPost ? post : body.at(wx, g + h, wz));
                c.set(wx, g + hh + 1, wz, isPost ? Blocks.lantern(false) : cap);
            }
        }
    }

    /** Fill under-carved air beneath a deck so nothing floats: drop columns of {@code f} to the ground. */
    static void support(VoxelCanvas c, int x, int y, int z, Fill f) { Builders.footing(c, x, y, z, f, 30); }

    static boolean solid(VoxelCanvas c, int x, int y, int z) { return BlockInfo.of(c.get(x, y, z)).cube; }
}
