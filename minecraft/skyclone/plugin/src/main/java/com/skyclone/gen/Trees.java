package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic trees. Every method takes the GROUND block position (x, y, z) — the trunk starts
 * at y + 1 — a seed (same seed + position = same tree), and a size multiplier (1.0 = vanilla-ish).
 * Leaves never overwrite anything solid; logs overwrite leaves and plants. Leaves are written
 * persistent so they never decay in game.
 *
 * <p>Types for {@link #grow} and {@link #forest}: oak, big_oak, birch, spruce, mega_spruce, pine,
 * dark_oak, jungle, acacia, cherry, red_mushroom, brown_mushroom, dead, palm, azalea.
 */
public final class Trees {

    private Trees() {}

    public static final String[] TYPES = {"oak", "big_oak", "birch", "spruce", "mega_spruce", "pine", "dark_oak",
            "jungle", "acacia", "cherry", "red_mushroom", "brown_mushroom", "dead", "palm", "azalea"};

    /** Grow a tree by type name. Returns false for an unknown type. */
    public static boolean grow(VoxelCanvas c, String type, int x, int y, int z, long seed, double size) {
        switch (type) {
            case "oak": oak(c, x, y, z, seed, size); return true;
            case "big_oak": bigOak(c, x, y, z, seed, size); return true;
            case "birch": birch(c, x, y, z, seed, size); return true;
            case "spruce": spruce(c, x, y, z, seed, size); return true;
            case "mega_spruce": megaSpruce(c, x, y, z, seed, size); return true;
            case "pine": pine(c, x, y, z, seed, size); return true;
            case "dark_oak": darkOak(c, x, y, z, seed, size); return true;
            case "jungle": jungle(c, x, y, z, seed, size); return true;
            case "acacia": acacia(c, x, y, z, seed, size); return true;
            case "cherry": cherry(c, x, y, z, seed, size); return true;
            case "red_mushroom": redMushroom(c, x, y, z, seed, size); return true;
            case "brown_mushroom": brownMushroom(c, x, y, z, seed, size); return true;
            case "dead": dead(c, x, y, z, seed, size); return true;
            case "palm": palm(c, x, y, z, seed, size); return true;
            case "azalea": azalea(c, x, y, z, seed, size); return true;
            default: return false;
        }
    }

    // ---- primitives -----------------------------------------------------------------------

    private static Random rng(long seed, int x, int y, int z) { return new Random(Hash.hash(seed, x, y, z)); }

    static void log(VoxelCanvas c, int x, int y, int z, String id, char axis) {
        String cur = c.get(x, y, z);
        BlockInfo i = BlockInfo.of(cur);
        if (i.isAir() || i.top16 == 0 || BlockState.name(cur).endsWith("_leaves")) c.set(x, y, z, id + "[axis=" + axis + "]");
    }

    static void block(VoxelCanvas c, int x, int y, int z, String id) {
        String cur = c.get(x, y, z);
        BlockInfo i = BlockInfo.of(cur);
        if (i.isAir() || i.top16 == 0 || BlockState.name(cur).endsWith("_leaves")) c.set(x, y, z, id);
    }

    static void leaf(VoxelCanvas c, int x, int y, int z, String leaves) {
        if (c.isAir(x, y, z)) c.set(x, y, z, leaves);
    }

    /** A leafy blob: ellipsoid with ragged edges. */
    static void blob(VoxelCanvas c, double cx, double cy, double cz, double rx, double ry, double rz, String leaves, long seed) {
        int x0 = (int) Math.floor(cx - rx), x1 = (int) Math.ceil(cx + rx);
        int y0 = (int) Math.floor(cy - ry), y1 = (int) Math.ceil(cy + ry);
        int z0 = (int) Math.floor(cz - rz), z1 = (int) Math.ceil(cz + rz);
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > 1) continue;
                    if (d > 0.62 && Hash.unit(seed, x, y, z) < (d - 0.62) * 1.6) continue;
                    leaf(c, x, y, z, leaves);
                }
    }

    /** Diagonal branch of logs from (x,y,z) by (dx,dy,dz); returns the end point. */
    static int[] branch(VoxelCanvas c, int x, int y, int z, double dx, double dy, double dz, int len, String logId) {
        double fx = x, fy = y, fz = z;
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= l; dy /= l; dz /= l;
        char axis = Blocks.axisOf((int) Math.round(dx * 10), (int) Math.round(dy * 10), (int) Math.round(dz * 10));
        int bx = x, by = y, bz = z;
        for (int i = 0; i < len; i++) {
            fx += dx; fy += dy; fz += dz;
            int nx = (int) Math.round(fx), ny = (int) Math.round(fy), nz = (int) Math.round(fz);
            connect(c, bx, by, bz, nx, ny, nz, logId, axis);
            bx = nx; by = ny; bz = nz;
        }
        return new int[]{bx, by, bz};
    }

    /**
     * Logs from (ax,ay,az) to (bx,by,bz) moving ONE axis at a time (vertical first), so every log
     * shares a face with the previous one. Diagonal steps only touch at an edge, which reads as a
     * floating limb in game and in the preview's floating-block check.
     */
    static void connect(VoxelCanvas c, int ax, int ay, int az, int bx, int by, int bz, String logId, char axis) {
        int x = ax, y = ay, z = az;
        while (y != by) { y += Integer.signum(by - y); log(c, x, y, z, logId, 'y'); }
        while (x != bx) { x += Integer.signum(bx - x); log(c, x, y, z, logId, axis == 'y' ? 'x' : axis); }
        while (z != bz) { z += Integer.signum(bz - z); log(c, x, y, z, logId, axis == 'y' ? 'z' : axis); }
        log(c, bx, by, bz, logId, axis);
    }

    // ---- oak family -----------------------------------------------------------------------

    public static void oak(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((4 + r.nextInt(3)) * size);
        String leaves = Blocks.leaves("oak");
        for (int i = 1; i <= h; i++) log(c, x, y + i, z, "oak_log", 'y');
        int top = y + h;
        int rad = Math.max(2, (int) Math.round(2 * size));
        for (int ly = top - 2; ly <= top + 1; ly++) {
            int rr = ly >= top ? Math.max(1, rad - 1) : rad;
            for (int dz = -rr; dz <= rr; dz++)
                for (int dx = -rr; dx <= rr; dx++) {
                    boolean corner = Math.abs(dx) == rr && Math.abs(dz) == rr;
                    if (corner && (ly >= top || Hash.unit(seed, x + dx, ly, z + dz) < 0.5)) continue;
                    leaf(c, x + dx, ly, z + dz, leaves);
                }
        }
    }

    /** Fancy oak: tall trunk, 3-5 limbs each ending in a leaf cluster. */
    public static void bigOak(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((8 + r.nextInt(5)) * size);
        String leaves = Blocks.leaves("oak");
        for (int i = 1; i <= h; i++) log(c, x, y + i, z, "oak_log", 'y');
        int limbs = 3 + r.nextInt(3);
        for (int i = 0; i < limbs; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            int startY = y + h / 2 + r.nextInt(Math.max(1, h / 2));
            int len = (int) Math.round((3 + r.nextInt(3)) * size);
            int[] e = branch(c, x, startY, z, Math.cos(a), 0.6, Math.sin(a), len, "oak_log");
            blob(c, e[0], e[1] + 1, e[2], 2.8 * size, 2.0 * size, 2.8 * size, leaves, seed + i);
        }
        blob(c, x, y + h + 1, z, 3.2 * size, 2.4 * size, 3.2 * size, leaves, seed ^ 99);
    }

    public static void birch(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((5 + r.nextInt(3)) * size);
        String leaves = Blocks.leaves("birch");
        for (int i = 1; i <= h; i++) log(c, x, y + i, z, "birch_log", 'y');
        int top = y + h;
        for (int ly = top - 3; ly <= top + 1; ly++) {
            int rr = ly >= top ? 1 : 2;
            if (ly == top - 3) rr = 1;
            for (int dz = -rr; dz <= rr; dz++)
                for (int dx = -rr; dx <= rr; dx++) {
                    if (Math.abs(dx) == rr && Math.abs(dz) == rr && (rr == 1 || Hash.unit(seed, x + dx, ly, z + dz) < 0.6)) continue;
                    leaf(c, x + dx, ly, z + dz, leaves);
                }
        }
    }

    public static void azalea(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((3 + r.nextInt(2)) * size);
        for (int i = 1; i <= h; i++) log(c, x, y + i, z, "oak_log", 'y');
        Palette lv = Palette.of("azalea_leaves[persistent=true]:3,flowering_azalea_leaves[persistent=true]:1");
        int top = y + h;
        for (int ly = top - 1; ly <= top + 1; ly++) {
            int rr = ly == top + 1 ? 1 : 2;
            for (int dz = -rr; dz <= rr; dz++)
                for (int dx = -rr; dx <= rr; dx++) {
                    if (Math.abs(dx) == rr && Math.abs(dz) == rr) continue;
                    leaf(c, x + dx, ly, z + dz, lv.at(x + dx, ly, z + dz));
                }
        }
    }

    // ---- conifers -------------------------------------------------------------------------

    public static void spruce(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((7 + r.nextInt(4)) * size);
        String leaves = Blocks.leaves("spruce");
        for (int i = 1; i <= h - 1; i++) log(c, x, y + i, z, "spruce_log", 'y');
        int top = y + h;
        int start = y + 2 + r.nextInt(2);
        int maxR = Math.max(2, (int) Math.round(3 * size));
        leaf(c, x, top, z, leaves);
        leaf(c, x, top + 1, z, leaves);
        int ring = 0;
        for (int ly = top - 1; ly >= start; ly--) {
            double t = (double) (top - ly) / Math.max(1, top - start);
            int rr = (int) Math.round(maxR * t);
            if (ring++ % 2 == 1) rr = Math.max(1, rr - 1);
            if (rr < 1) rr = 1;
            for (int dz = -rr; dz <= rr; dz++)
                for (int dx = -rr; dx <= rr; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) > rr + (rr > 1 ? 1 : 0)) continue;
                    leaf(c, x + dx, ly, z + dz, leaves);
                }
        }
    }

    /** 2x2 giant spruce (the trunk occupies x..x+1, z..z+1). */
    public static void megaSpruce(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((18 + r.nextInt(8)) * size);
        String leaves = Blocks.leaves("spruce");
        for (int i = 1; i <= h; i++)
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) log(c, x + dx, y + i, z + dz, "spruce_log", 'y');
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++)
            if (Hash.unit(seed, x + dx, y, z + dz) < 0.6 && BlockState.name(c.get(x + dx, y, z + dz)).equals("grass_block")) c.set(x + dx, y, z + dz, "podzol");
        int top = y + h;
        int start = y + h / 3;
        double cx = x + 0.5, cz = z + 0.5;
        int ring = 0;
        for (int ly = top + 1; ly >= start; ly--) {
            double t = (double) (top + 1 - ly) / Math.max(1, top + 1 - start);
            double rr = 1 + 4.5 * size * t;
            if (ring++ % 3 == 2) rr -= 1.2;
            for (int dz = -6; dz <= 7; dz++)
                for (int dx = -6; dx <= 7; dx++) {
                    double ddx = x + dx - cx, ddz = z + dz - cz;
                    if (ddx * ddx + ddz * ddz > rr * rr) continue;
                    leaf(c, x + dx, ly, z + dz, leaves);
                }
        }
    }

    /** Tall, sparse pine: bare lower trunk, small tiers near the top. */
    public static void pine(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((11 + r.nextInt(5)) * size);
        String leaves = Blocks.leaves("spruce");
        for (int i = 1; i <= h; i++) log(c, x, y + i, z, "spruce_log", 'y');
        int top = y + h;
        leaf(c, x, top + 1, z, leaves);
        for (int ly = top; ly >= top - h / 3; ly--) {
            int rr = ((top - ly) % 3 == 1) ? 2 : 1;
            for (int dz = -rr; dz <= rr; dz++)
                for (int dx = -rr; dx <= rr; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) > rr) continue;
                    leaf(c, x + dx, ly, z + dz, leaves);
                }
        }
    }

    // ---- broadleaf giants -----------------------------------------------------------------

    /** 2x2 trunk, wide flat canopy. */
    public static void darkOak(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((6 + r.nextInt(3)) * size);
        String leaves = Blocks.leaves("dark_oak");
        for (int i = 1; i <= h; i++)
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) log(c, x + dx, y + i, z + dz, "dark_oak_log", 'y');
        int top = y + h;
        double cx = x + 0.5, cz = z + 0.5;
        double R = 4.2 * size;
        for (int ly = top - 1; ly <= top + 1; ly++) {
            double rr = ly == top + 1 ? R - 1.6 : (ly == top - 1 ? R - 0.6 : R);
            for (int dz = -7; dz <= 8; dz++)
                for (int dx = -7; dx <= 8; dx++) {
                    double ddx = x + dx - cx, ddz = z + dz - cz;
                    double d = Math.sqrt(ddx * ddx + ddz * ddz);
                    if (d > rr) continue;
                    if (d > rr - 1 && Hash.unit(seed, x + dx, ly, z + dz) < 0.35) continue;
                    leaf(c, x + dx, ly, z + dz, leaves);
                }
        }
        // a couple of short side limbs under the canopy
        for (int i = 0; i < 2; i++) {
            String dir = Blocks.DIRS[r.nextInt(4)];
            int bx = x + (Blocks.dx(dir) > 0 ? 1 : 0) + Blocks.dx(dir), bz = z + (Blocks.dz(dir) > 0 ? 1 : 0) + Blocks.dz(dir);
            log(c, bx, top - 2, bz, "dark_oak_log", Blocks.dx(dir) != 0 ? 'x' : 'z');
        }
    }

    /** Big jungle tree: 2x2 trunk, side branches with leaf pads, crown on top, vines on the trunk. */
    public static void jungle(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((16 + r.nextInt(10)) * size);
        String leaves = Blocks.leaves("jungle");
        for (int i = 1; i <= h; i++)
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) log(c, x + dx, y + i, z + dz, "jungle_log", 'y');
        int top = y + h;
        blob(c, x + 0.5, top + 1, z + 0.5, 4.8 * size, 2.2, 4.8 * size, leaves, seed);
        for (int by = y + 6; by < top - 3; by += 3 + r.nextInt(3)) {
            double a = r.nextDouble() * Math.PI * 2;
            int len = 2 + r.nextInt(3);
            int[] e = branch(c, x, by, z, Math.cos(a), 0.4, Math.sin(a), len, "jungle_log");
            blob(c, e[0], e[1] + 1, e[2], 2.6, 1.4, 2.6, leaves, seed + by);
        }
        // vines: on each trunk face, hanging in runs
        for (int i = 2; i < h - 1; i++) {
            int ly = y + i;
            if (Hash.unit(seed, x - 1, ly, z) < 0.45) c.set(x - 1, ly, z, "vine[east=true,north=false,south=false,up=false,west=false]");
            if (Hash.unit(seed, x + 2, ly, z + 1) < 0.45) c.set(x + 2, ly, z + 1, "vine[west=true,north=false,south=false,up=false,east=false]");
            if (Hash.unit(seed, x, ly, z - 1) < 0.35) c.set(x, ly, z - 1, "vine[south=true,north=false,east=false,up=false,west=false]");
            if (Hash.unit(seed, x + 1, ly, z + 2) < 0.35) c.set(x + 1, ly, z + 2, "vine[north=true,south=false,east=false,up=false,west=false]");
        }
    }

    /** Acacia: trunk that bends sideways, flat umbrella canopy (sometimes two). */
    public static void acacia(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        String leaves = Blocks.leaves("acacia");
        int straight = (int) Math.round((2 + r.nextInt(2)) * size);
        for (int i = 1; i <= straight; i++) log(c, x, y + i, z, "acacia_log", 'y');
        int crowns = r.nextDouble() < 0.4 ? 2 : 1;
        for (int k = 0; k < crowns; k++) {
            String dir = Blocks.DIRS[(r.nextInt(4) + k * 2) & 3];
            int bx = x, by = y + straight, bz = z;
            int bend = (int) Math.round((2 + r.nextInt(2)) * size);
            for (int i = 0; i < bend; i++) {
                log(c, bx + Blocks.dx(dir), by, bz + Blocks.dz(dir), "acacia_log", Blocks.dx(dir) != 0 ? 'x' : 'z');
                bx += Blocks.dx(dir); bz += Blocks.dz(dir); by += 1;
                log(c, bx, by, bz, "acacia_log", 'y');
            }
            int up = 1 + r.nextInt(2);
            for (int i = 0; i < up; i++) log(c, bx, ++by, bz, "acacia_log", 'y');
            int rr = (int) Math.round(3 * size);
            for (int dz = -rr; dz <= rr; dz++)
                for (int dx = -rr; dx <= rr; dx++) {
                    if (Math.abs(dx) == rr && Math.abs(dz) == rr) continue;
                    if (Math.abs(dx) + Math.abs(dz) > rr + 1) continue;
                    leaf(c, bx + dx, by + 1, bz + dz, leaves);
                }
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) leaf(c, bx + dx, by + 2, bz + dz, leaves);
        }
    }

    /** Cherry: short trunk splitting into curved limbs, each under a broad pink dome with a hanging fringe. */
    public static void cherry(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        String leaves = Blocks.leaves("cherry");
        int h = (int) Math.round((3 + r.nextInt(2)) * size);
        for (int i = 1; i <= h; i++) log(c, x, y + i, z, "cherry_log", 'y');
        int limbs = 2 + r.nextInt(2);
        double a0 = r.nextDouble() * Math.PI * 2;
        for (int k = 0; k < limbs; k++) {
            double a = a0 + k * Math.PI * 2 / limbs + r.nextDouble() * 0.5;
            int len = (int) Math.round((3 + r.nextInt(2)) * size);
            int[] e = branch(c, x, y + h, z, Math.cos(a), 0.9, Math.sin(a), len, "cherry_log");
            double R = 3.3 * size;
            blob(c, e[0], e[1] + 1, e[2], R, 1.7 * size, R, leaves, seed + k);
            // hanging fringe
            for (int dz = -4; dz <= 4; dz++)
                for (int dx = -4; dx <= 4; dx++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d < R - 1.2 || d > R) continue;
                    if (Hash.unit(seed + 7, e[0] + dx, e[1], e[2] + dz) < 0.45) leaf(c, e[0] + dx, e[1] - 1, e[2] + dz, leaves);
                }
        }
    }

    // ---- fungi & oddities -----------------------------------------------------------------

    public static void redMushroom(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((4 + r.nextInt(3)) * size);
        for (int i = 1; i <= h; i++) block(c, x, y + i, z, "mushroom_stem");
        int R = Math.max(2, (int) Math.round(2.5 * size));
        int top = y + h;
        for (int ly = top - R + 1; ly <= top + 1; ly++) {
            int rr = ly == top + 1 ? R - 1 : R;
            for (int dz = -rr; dz <= rr; dz++)
                for (int dx = -rr; dx <= rr; dx++) {
                    boolean edge = Math.abs(dx) == rr || Math.abs(dz) == rr;
                    if (Math.abs(dx) == rr && Math.abs(dz) == rr) continue;
                    if (ly <= top && !edge) continue;          // hollow sides, closed top
                    block(c, x + dx, ly, z + dz, "red_mushroom_block");
                }
        }
    }

    public static void brownMushroom(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((5 + r.nextInt(3)) * size);
        for (int i = 1; i <= h; i++) block(c, x, y + i, z, "mushroom_stem");
        int R = Math.max(3, (int) Math.round(3.5 * size));
        int top = y + h + 1;
        for (int dz = -R; dz <= R; dz++)
            for (int dx = -R; dx <= R; dx++) {
                if (Math.abs(dx) == R && Math.abs(dz) == R) continue;
                if (Math.abs(dx) + Math.abs(dz) > R * 2 - 2) continue;
                block(c, x + dx, top, z + dz, "brown_mushroom_block");
            }
    }

    /** Bare, crooked dead tree. */
    public static void dead(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        String wood = r.nextBoolean() ? "dark_oak_log" : "stripped_oak_log";
        int h = (int) Math.round((4 + r.nextInt(4)) * size);
        for (int i = 1; i <= h; i++) log(c, x, y + i, z, wood, 'y');
        int limbs = 2 + r.nextInt(3);
        for (int k = 0; k < limbs; k++) {
            double a = r.nextDouble() * Math.PI * 2;
            int sy = y + h / 2 + r.nextInt(Math.max(1, h / 2 + 1));
            int[] e = branch(c, x, sy, z, Math.cos(a), 0.5 + r.nextDouble(), Math.sin(a), 2 + r.nextInt(3), wood);
            if (r.nextBoolean()) branch(c, e[0], e[1], e[2], Math.cos(a + 0.8), 1, Math.sin(a + 0.8), 1 + r.nextInt(2), wood);
        }
    }

    /** Palm: leaning curved trunk with drooping fronds. */
    public static void palm(VoxelCanvas c, int x, int y, int z, long seed, double size) {
        Random r = rng(seed, x, y, z);
        int h = (int) Math.round((7 + r.nextInt(4)) * size);
        double a = r.nextDouble() * Math.PI * 2;
        double lean = (2 + r.nextInt(2)) * size;
        int tx = x, tz = z;
        for (int i = 1; i <= h; i++) {
            double t = (double) i / h;
            int nx = x + (int) Math.round(Math.cos(a) * lean * t * t);
            int nz = z + (int) Math.round(Math.sin(a) * lean * t * t);
            if (i > 1) connect(c, tx, y + i - 1, tz, nx, y + i - 1, nz, "jungle_log", 'y');   // sidestep at the lower level
            log(c, nx, y + i, nz, "jungle_log", 'y');
            tx = nx; tz = nz;
        }
        int top = y + h;
        String leaves = Blocks.leaves("jungle");
        leaf(c, tx, top + 1, tz, leaves);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int frond = (int) Math.round(4 * size);
        for (int[] d : dirs) {
            int len = (d[0] != 0 && d[1] != 0) ? frond - 1 : frond;
            for (int k = 1; k <= len; k++) {
                int dy = k <= 1 ? 1 : (k <= 2 ? 0 : -(k - 2) * (k - 2) / 2);
                leaf(c, tx + d[0] * k, top + dy, tz + d[1] * k, leaves);
            }
        }
        if (r.nextBoolean()) leaf(c, tx + 1, top, tz, "cocoa[age=2,facing=west]");
    }

    // ---- forests --------------------------------------------------------------------------

    /**
     * Scatter trees over the land in {@code area} (x/z only), on grass/dirt/podzol/sand, avoiding
     * wet and steep columns, the coast ({@code coastMargin}), and keeping {@code spacing} blocks
     * between trunks. {@code typesSpec} is a palette-style spec: "oak:4,birch:2,big_oak:1".
     * Columns for which {@code keepOut} returns true are skipped (roads, plots). Returns the
     * number of trees planted.
     */
    public static int forest(VoxelCanvas c, Terrain.Heightmap h, Box area, long seed, double density, int spacing,
                             String typesSpec, int coastMargin, java.util.function.BiPredicate<Integer, Integer> keepOut) {
        Palette types = Palette.of(typesSpec);
        long s = Hash.salt(seed, "forest" + typesSpec);
        List<int[]> planted = new ArrayList<>();
        int n = 0;
        for (int z = area.minZ(); z <= area.maxZ(); z++)
            for (int x = area.minX(); x <= area.maxX(); x++) {
                if (!Hash.chance(s, x, z, density)) continue;
                int t = h.top(x, z);
                if (t == Terrain.Heightmap.NONE || h.wet(x, z) || h.slope(x, z) > 2) continue;
                if (coastMargin > 0 && !h.inland(x, z, coastMargin)) continue;
                if (keepOut != null && keepOut.test(x, z)) continue;
                String ground = BlockState.name(c.get(x, t, z));
                if (!(ground.equals("grass_block") || ground.equals("dirt") || ground.equals("podzol") || ground.equals("sand")
                        || ground.equals("mycelium") || ground.equals("coarse_dirt") || ground.equals("moss_block") || ground.equals("snow_block"))) continue;
                boolean near = false;
                for (int[] p : planted) if (Math.abs(p[0] - x) < spacing && Math.abs(p[1] - z) < spacing) { near = true; break; }
                if (near) continue;
                String type = BlockState.name(types.pick(s, x, 0, z));
                double size = 0.85 + Hash.unit(s, x, 3, z) * 0.35;
                if (BlockInfo.of(c.get(x, t + 1, z)).top16 == 0) c.set(x, t + 1, z, "air");
                grow(c, type, x, t, z, Hash.hash(s, x, t, z), size);
                planted.add(new int[]{x, z});
                n++;
            }
        return n;
    }
}
