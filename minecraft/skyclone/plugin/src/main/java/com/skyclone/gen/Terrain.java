package com.skyclone.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.util.LongQueue;
import com.skyclone.gen.util.LongSet;
import com.skyclone.gen.util.Pack;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating-island terrain. The usual flow:
 * <pre>
 *   Terrain.IslandShape s = new Terrain.IslandShape(seed);
 *   s.radiusX = 90; s.radiusZ = 70; s.baseY = 70; s.hills = 8; s.cliff = 0.6;
 *   Terrain.Heightmap h = Terrain.island(s);          // pure numbers, nothing written yet
 *   h.flatten(Box.of(-10, 0, -10, 10, 0, 10), 71, 6); // a building plot, blended over 6 blocks
 *   Terrain.fill(canvas, h, new Terrain.SurfaceRules()); // grass/dirt/stone + a rocky underside
 *   Terrain.pond(canvas, h, 30, -20, 7, 3, seed);       // carve + fill a pond (updates h)
 *   Terrain.grassAndFlowers(canvas, h, seed, 0.25, 0.04);
 * </pre>
 * The Heightmap is the island's ground truth for every later pass: plant trees with {@code h.top(x,z)},
 * place buildings on flattened plots, route paths across it.
 */
public final class Terrain {

    private Terrain() {}

    // =======================================================================================
    // Heightmap
    // =======================================================================================

    public static final class Heightmap {
        public static final int NONE = Integer.MIN_VALUE;
        public final int minX, minZ, width, depth;
        private final int[] top, bottom;
        private final boolean[] wet;

        public Heightmap(int minX, int minZ, int width, int depth) {
            this.minX = minX; this.minZ = minZ; this.width = width; this.depth = depth;
            top = new int[width * depth];
            bottom = new int[width * depth];
            wet = new boolean[width * depth];
            java.util.Arrays.fill(top, NONE);
            java.util.Arrays.fill(bottom, NONE);
        }

        private int idx(int x, int z) {
            int ix = x - minX, iz = z - minZ;
            if (ix < 0 || iz < 0 || ix >= width || iz >= depth) return -1;
            return iz * width + ix;
        }

        public boolean has(int x, int z) { int i = idx(x, z); return i >= 0 && top[i] != NONE; }

        /** Surface block y (the grass block), or NONE. For a pond column this is the water surface. */
        public int top(int x, int z) { int i = idx(x, z); return i < 0 ? NONE : top[i]; }

        public int bottom(int x, int z) { int i = idx(x, z); return i < 0 ? NONE : bottom[i]; }

        /** True where the column is water (a pond) — do not plant or build there. */
        public boolean wet(int x, int z) { int i = idx(x, z); return i >= 0 && wet[i]; }

        public void setWet(int x, int z, boolean w) { int i = idx(x, z); if (i >= 0) wet[i] = w; }

        public void set(int x, int z, int top, int bottom) {
            int i = idx(x, z);
            if (i < 0) return;
            this.top[i] = top;
            this.bottom[i] = Math.min(bottom, top);
        }

        public void setTop(int x, int z, int y) {
            int i = idx(x, z);
            if (i < 0 || top[i] == NONE) return;
            top[i] = y;
            if (bottom[i] > y - 1) bottom[i] = y - 1;
        }

        public void clear(int x, int z) { int i = idx(x, z); if (i >= 0) { top[i] = NONE; bottom[i] = NONE; } }

        /** Largest height difference to a 4-neighbour; 99 at the island's edge. */
        public int slope(int x, int z) {
            int t = top(x, z);
            if (t == NONE) return 0;
            int m = 0;
            for (int d = 0; d < 4; d++) {
                int n = top(x + Blocks.DX[d], z + Blocks.DZ[d]);
                if (n == NONE) return 99;
                m = Math.max(m, Math.abs(n - t));
            }
            return m;
        }

        /** Is this column land at least {@code margin} blocks from the coast? */
        public boolean inland(int x, int z, int margin) {
            for (int dz = -margin; dz <= margin; dz++)
                for (int dx = -margin; dx <= margin; dx++)
                    if (!has(x + dx, z + dz)) return false;
            return true;
        }

        public Box box() { return new Box(minX, minTop(), minZ, minX + width - 1, maxTop(), minZ + depth - 1); }

        public int maxTop() { int m = NONE; for (int t : top) if (t != NONE && t > m) m = t; return m; }

        public int minTop() { int m = Integer.MAX_VALUE; for (int t : top) if (t != NONE && t < m) m = t; return m == Integer.MAX_VALUE ? NONE : m; }

        public int minBottom() { int m = Integer.MAX_VALUE; for (int b : bottom) if (b != NONE && b < m) m = b; return m == Integer.MAX_VALUE ? NONE : m; }

        public interface ColumnVisitor { void visit(int x, int z, int top, int bottom); }

        public void forEach(ColumnVisitor v) {
            for (int iz = 0; iz < depth; iz++)
                for (int ix = 0; ix < width; ix++) {
                    int i = iz * width + ix;
                    if (top[i] != NONE) v.visit(minX + ix, minZ + iz, top[i], bottom[i]);
                }
        }

        /**
         * Make a flat plot at height {@code y} over {@code area} (only x/z of the box are used) and
         * blend the surrounding {@code blend} blocks smoothly back to the natural terrain. Columns
         * that were not land inside the area BECOME land (with a shallow underside) so a plot can
         * reach past the coast.
         */
        public void flatten(Box area, int y, int blend) {
            for (int z = area.minZ() - blend; z <= area.maxZ() + blend; z++)
                for (int x = area.minX() - blend; x <= area.maxX() + blend; x++) {
                    int i = idx(x, z);
                    if (i < 0) continue;
                    int dx = Math.max(0, Math.max(area.minX() - x, x - area.maxX()));
                    int dz = Math.max(0, Math.max(area.minZ() - z, z - area.maxZ()));
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d == 0) {
                        if (top[i] == NONE) { top[i] = y; bottom[i] = y - 4; }
                        else { top[i] = y; if (bottom[i] > y - 3) bottom[i] = y - 3; }
                        continue;
                    }
                    if (top[i] == NONE || d > blend) continue;
                    double t = Noise.smoothstep(0, blend, d);
                    int ny = (int) Math.round(y + (top[i] - y) * t);
                    top[i] = ny;
                    if (bottom[i] > ny - 3) bottom[i] = ny - 3;
                }
        }

        /**
         * Remove every land column not 4-connected to the largest landmass, so warped coastlines
         * cannot leave floating islets beside the island. Returns the number of columns removed.
         */
        public int keepLargestLand() {
            int n = width * depth;
            int[] comp = new int[n];
            int best = 0, bestSize = 0, id = 0;
            int[] stack = new int[n];
            for (int i = 0; i < n; i++) {
                if (top[i] == NONE || comp[i] != 0) continue;
                id++;
                int sp = 0, size = 0;
                stack[sp++] = i; comp[i] = id;
                while (sp > 0) {
                    int c = stack[--sp];
                    size++;
                    int cx = c % width, cz = c / width;
                    int[] nb = {cx > 0 ? c - 1 : -1, cx < width - 1 ? c + 1 : -1, cz > 0 ? c - width : -1, cz < depth - 1 ? c + width : -1};
                    for (int q : nb) if (q >= 0 && top[q] != NONE && comp[q] == 0) { comp[q] = id; stack[sp++] = q; }
                }
                if (size > bestSize) { bestSize = size; best = id; }
            }
            int removed = 0;
            for (int i = 0; i < n; i++) if (top[i] != NONE && comp[i] != best) { top[i] = NONE; bottom[i] = NONE; wet[i] = false; removed++; }
            return removed;
        }

        /** Mean surface height over an area (for picking a plot height). */
        public int average(Box area) {
            long s = 0; int n = 0;
            for (int z = area.minZ(); z <= area.maxZ(); z++)
                for (int x = area.minX(); x <= area.maxX(); x++) { int t = top(x, z); if (t != NONE) { s += t; n++; } }
            return n == 0 ? NONE : (int) Math.round((double) s / n);
        }
    }

    // =======================================================================================
    // Island shape
    // =======================================================================================

    /** Parameters for {@link #island}. Every field has a sensible default. */
    public static final class IslandShape {
        public final long seed;
        public int centerX = 0, centerZ = 0;
        /** Half-size of the island in blocks. */
        public double radiusX = 60, radiusZ = 60;
        /** Typical ground level at the island's centre (the grass block y). */
        public int baseY = 70;
        /** Amplitude of rolling hills on top. */
        public double hills = 6;
        /** Size of one hill, in blocks. */
        public double hillScale = 48;
        /** Coastline irregularity, as a fraction of the radius (0 = perfect ellipse). */
        public double coastNoise = 0.22;
        public double coastScale = 36;
        /** 0 = land slopes gently down to the coast; 1 = flat-topped island with sheer cliffs. */
        public double cliff = 0.5;
        /** How far the coast drops below baseY on a gentle island. */
        public int coastDrop = 4;
        /** Terrace step in blocks (0 = none). */
        public int terraceStep = 0;
        /** Width of a low sandy band along the coast in blocks (0 = none). */
        public int beachWidth = 0;
        /** Depth of the hanging rock underside at the centre. */
        public double undersideDepth = 28;
        /** How jagged the underside is (0..1). */
        public double undersideNoise = 0.45;
        /** Extra ridged mountains: amplitude (0 = none). */
        public double mountains = 0;
        public double mountainScale = 70;
        /** Keep land patches the coast noise detaches from the main island (true for deliberate archipelagos). */
        public boolean keepIslets = false;

        public IslandShape(long seed) { this.seed = seed; }
    }

    /** Compute an island heightmap (nothing is written). */
    public static Heightmap island(IslandShape s) {
        int rx = (int) Math.ceil(s.radiusX * (1 + s.coastNoise)) + 2;
        int rz = (int) Math.ceil(s.radiusZ * (1 + s.coastNoise)) + 2;
        Heightmap h = new Heightmap(s.centerX - rx, s.centerZ - rz, rx * 2 + 1, rz * 2 + 1);
        Noise coast = new Noise(Hash.salt(s.seed, "coast"));
        Noise hill = new Noise(Hash.salt(s.seed, "hills"));
        Noise mount = new Noise(Hash.salt(s.seed, "mountains"));
        Noise under = new Noise(Hash.salt(s.seed, "underside"));
        double rim = Noise.lerp(Noise.clamp(s.cliff, 0, 1), 0.65, 0.05);
        double beachT = s.beachWidth <= 0 ? 0 : s.beachWidth / Math.max(s.radiusX, s.radiusZ);
        for (int z = s.centerZ - rz; z <= s.centerZ + rz; z++)
            for (int x = s.centerX - rx; x <= s.centerX + rx; x++) {
                double nx = (x - s.centerX) / s.radiusX, nz = (z - s.centerZ) / s.radiusZ;
                double d = Math.sqrt(nx * nx + nz * nz);
                double wobble = 1 + s.coastNoise * coast.warped2(x / s.coastScale, z / s.coastScale, 1.2, 4);
                double dd = d / wobble;
                if (dd > 1) continue;
                double t = 1 - dd;                        // 0 at coast, 1 at centre
                double shape = Noise.smoothstep(0, rim, t);
                double hillV = (hill.fbm2(x / s.hillScale, z / s.hillScale, 4) * 0.5 + 0.5) * s.hills;
                double mountV = s.mountains <= 0 ? 0 : Math.pow(mount.ridged2(x / s.mountainScale, z / s.mountainScale, 5), 2.2) * s.mountains * Noise.smoothstep(0.1, 0.5, t);
                double y = s.baseY - s.coastDrop * (1 - shape) + (hillV + mountV) * shape;
                if (s.terraceStep > 1) {
                    double rel = (y - s.baseY) / s.terraceStep;
                    double fl = Math.floor(rel), fr = rel - fl;
                    y = s.baseY + (fl + Noise.smoothstep(0.7, 1.0, fr)) * s.terraceStep;
                }
                if (beachT > 0 && t < beachT) {
                    double bt = Noise.smoothstep(0, beachT, t);
                    y = Noise.lerp(bt, s.baseY - 1, Math.min(y, s.baseY + 1));
                }
                int top = (int) Math.round(y);
                double depthHere = s.undersideDepth * Math.pow(t, 0.55) * (1 - s.undersideNoise + s.undersideNoise * (under.fbm2(x / 14.0, z / 14.0, 3) * 0.5 + 0.5));
                if (Hash.chance(s.seed ^ 0x51A1L, x, z, 0.015 * t)) depthHere += 2 + Hash.range(s.seed, x, 1, z, 0, 4);   // stalactites
                int bottom = top - 3 - (int) Math.round(depthHere);
                h.set(x, z, top, bottom);
            }
        if (!s.keepIslets) h.keepLargestLand();
        return h;
    }

    // =======================================================================================
    // Surface painting
    // =======================================================================================

    /** How a column is painted from the top down. Every field has a sensible default. */
    public static final class SurfaceRules {
        public Fill top = Fill.of("grass_block");
        public Fill soil = Fill.of("dirt");
        public int soilDepth = 3;
        public Fill stone = Palette.of("stone:12,andesite:3,cobblestone:1,gravel:1");
        /** Slope (max neighbour height difference) at which the surface becomes cliff rock. */
        public int steepSlope = 3;
        public Fill cliff = Palette.of("stone:8,andesite:4,cobblestone:2,mossy_cobblestone:1");
        /** Columns whose top is at or above this get {@link #snow}. */
        public int snowAbove = Integer.MAX_VALUE;
        public Fill snow = Fill.of("snow_block");
        /** Columns whose top is at or below this get {@link #sand} (beaches, pond shores). */
        public int sandBelow = Integer.MIN_VALUE;
        public Fill sand = Fill.of("sand");
        public Fill sandSoil = Fill.of("sandstone");
        /** The hanging underside (below the stone core). */
        public Fill underside = Palette.of("stone:6,andesite:3,cobblestone:2,tuff:2,dirt:1");
        /** Blocks of underside material at the very bottom of each column. */
        public int undersideDepth = 6;
    }

    /** Write every column of the heightmap: surface, soil, stone core and the underside. */
    public static void fill(VoxelCanvas c, Heightmap h, SurfaceRules r) {
        h.forEach((x, z, top, bottom) -> {
            int slope = h.slope(x, z);
            boolean steep = slope >= r.steepSlope;
            boolean sandy = top <= r.sandBelow;
            boolean snowy = top >= r.snowAbove;
            int exposed = steep ? Math.min(slope, 12) : 0;
            for (int y = bottom; y <= top; y++) {
                int depth = top - y;
                String b;
                if (y - bottom < r.undersideDepth && depth > r.soilDepth) b = r.underside.at(x, y, z);
                else if (depth == 0) b = steep ? r.cliff.at(x, y, z) : snowy ? r.snow.at(x, y, z) : sandy ? r.sand.at(x, y, z) : r.top.at(x, y, z);
                else if (depth < exposed) b = r.cliff.at(x, y, z);
                else if (depth <= r.soilDepth) b = sandy ? (depth == 1 ? r.sand.at(x, y, z) : r.sandSoil.at(x, y, z)) : r.soil.at(x, y, z);
                else b = r.stone.at(x, y, z);
                c.set(x, y, z, b);
            }
        });
    }

    // =======================================================================================
    // Decoration
    // =======================================================================================

    /** Short grass, ferns and flowers on grass blocks. Skips wet and steep columns. */
    public static void grassAndFlowers(VoxelCanvas c, Heightmap h, long seed, double grassChance, double flowerChance) {
        Palette flowers = Palette.of("dandelion:3,poppy:3,cornflower:2,oxeye_daisy:2,azure_bluet:2,allium:1,red_tulip:1,white_tulip:1,lily_of_the_valley:1");
        long s = Hash.salt(seed, "flora");
        h.forEach((x, z, top, bottom) -> {
            if (h.wet(x, z)) return;
            if (!BlockState.name(c.get(x, top, z)).equals("grass_block")) return;
            if (!c.isAir(x, top + 1, z)) return;
            double u = Hash.unit(s, x, z);
            if (u < flowerChance) c.set(x, top + 1, z, flowers.pick(s, x, top, z));
            else if (u < flowerChance + grassChance) c.set(x, top + 1, z, Hash.unit(s, x, 7, z) < 0.15 ? "fern" : "short_grass");
        });
    }

    /** Place {@code fill} on top of every land column where {@code chance} rolls true (e.g. pebbles, bushes). */
    public static void scatter(VoxelCanvas c, Heightmap h, long seed, double chance, Fill fill, String onlyOnName) {
        long s = Hash.salt(seed, "scatter" + onlyOnName);
        h.forEach((x, z, top, bottom) -> {
            if (h.wet(x, z) || !Hash.chance(s, x, z, chance)) return;
            if (onlyOnName != null && !BlockState.name(c.get(x, top, z)).equals(onlyOnName)) return;
            if (!c.isAir(x, top + 1, z)) return;
            c.set(x, top + 1, z, fill.at(x, top + 1, z));
        });
    }

    // =======================================================================================
    // Caves
    // =======================================================================================

    /**
     * Spaghetti caves: carve air where two 3D noise fields are both near zero. Stays at least
     * {@code roof} blocks below the surface and {@code floor} blocks above the underside, so
     * nothing breaks through. Returns cells carved.
     */
    public static int carveCaves(VoxelCanvas c, Heightmap h, long seed, double scale, double width, int roof, int floor) {
        Noise a = new Noise(Hash.salt(seed, "caveA")), b = new Noise(Hash.salt(seed, "caveB"));
        int[] n = {0};
        h.forEach((x, z, top, bottom) -> {
            for (int y = bottom + floor; y <= top - roof; y++) {
                double va = a.noise3(x / scale, y / (scale * 0.7), z / scale);
                if (Math.abs(va) > width) continue;
                double vb = b.noise3(x / scale, y / (scale * 0.7), z / scale);
                if (Math.abs(vb) > width) continue;
                c.set(x, y, z, "air");
                n[0]++;
            }
        });
        return n[0];
    }

    /** A worm tunnel through a list of points {x,y,z}; radius in blocks. Floors are left rough. */
    public static void carveTunnel(VoxelCanvas c, double[][] points, double radius) {
        for (int i = 0; i + 1 < points.length; i++) {
            double[] p = points[i], q = points[i + 1];
            Shapes.line(c, p[0], p[1], p[2], q[0], q[1], q[2], radius, Fill.of("air"));
        }
    }

    // =======================================================================================
    // Liquids
    // =======================================================================================

    /**
     * Flood-fill a basin with a liquid from a seed cell, never above the seed's y, never past
     * {@code limit}. If the liquid would leak (touch the limit's edge or exceed {@code maxCells})
     * NOTHING is written and -1 is returned — so a leaking pond is caught here instead of being
     * discovered when water pours off the island in game.
     */
    public static int fillBasin(VoxelCanvas c, int sx, int sy, int sz, String liquid, Box limit, int maxCells) {
        if (!c.isAir(sx, sy, sz)) return 0;
        LongSet seen = new LongSet(4096);
        LongQueue q = new LongQueue();
        List<long[]> cells = new ArrayList<>();
        long k0 = Pack.key(sx, sy, sz);
        seen.add(k0); q.add(k0);
        int[] ddx = {1, -1, 0, 0, 0}, ddy = {0, 0, 0, 0, -1}, ddz = {0, 0, 1, -1, 0};
        while (!q.isEmpty()) {
            long k = q.poll();
            int x = Pack.x(k), y = Pack.y(k), z = Pack.z(k);
            if (x <= limit.minX() || x >= limit.maxX() || z <= limit.minZ() || z >= limit.maxZ() || y <= limit.minY()) return -1;
            cells.add(new long[]{k});
            if (cells.size() > maxCells) return -1;
            for (int d = 0; d < 5; d++) {
                int nx = x + ddx[d], ny = y + ddy[d], nz = z + ddz[d];
                long nk = Pack.key(nx, ny, nz);
                if (seen.contains(nk)) continue;
                seen.add(nk);
                if (c.isAir(nx, ny, nz)) q.add(nk);
            }
            // sideways at the same level only via the 4 horizontal directions above; never upward
        }
        for (long[] k : cells) c.set(Pack.x(k[0]), Pack.y(k[0]), Pack.z(k[0]), liquid);
        return cells.size();
    }

    /** For every column in {@code area} whose surface is below {@code level}, fill up to it. Caller guarantees enclosure. */
    public static int fillLevel(VoxelCanvas c, Heightmap h, Box area, int level, String liquid) {
        int n = 0;
        for (int z = area.minZ(); z <= area.maxZ(); z++)
            for (int x = area.minX(); x <= area.maxX(); x++) {
                int t = h.top(x, z);
                if (t == Heightmap.NONE || t >= level) continue;
                for (int y = t + 1; y <= level; y++) { c.set(x, y, z, liquid); n++; }
                h.setWet(x, z, true);
            }
        return n;
    }

    /**
     * Carve a natural pond into already-filled terrain and fill it: irregular outline of
     * {@code radius}, deepest {@code depth} in the middle, sand/clay floor, water up to one below
     * the lowest rim. Updates the heightmap (columns become wet). Returns the water level, or
     * NONE if the spot is not fully on land.
     */
    public static int pond(VoxelCanvas c, Heightmap h, int cx, int cz, double radius, int depth, long seed) {
        Noise n = new Noise(Hash.salt(seed, "pond" + cx + "," + cz));
        int r = (int) Math.ceil(radius * 1.3) + 1;
        int rim = Integer.MAX_VALUE;
        List<int[]> inside = new ArrayList<>();
        for (int z = cz - r; z <= cz + r; z++)
            for (int x = cx - r; x <= cx + r; x++) {
                double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)) / (radius * (1 + 0.25 * n.noise2(x / 6.0, z / 6.0)));
                if (d <= 1) {
                    if (!h.inland(x, z, 2)) return Heightmap.NONE;
                    inside.add(new int[]{x, z, (int) Math.round(depth * (1 - d * d)) });
                }
            }
        LongSet in = new LongSet(inside.size() * 2 + 16);
        for (int[] p : inside) in.add(Pack.column(p[0], p[1]));
        for (int[] p : inside)
            for (int d = 0; d < 4; d++) {
                int nx = p[0] + Blocks.DX[d], nz = p[1] + Blocks.DZ[d];
                if (!in.contains(Pack.column(nx, nz))) rim = Math.min(rim, h.top(nx, nz));
            }
        if (rim == Integer.MAX_VALUE) return Heightmap.NONE;
        int level = rim - 1;
        Palette bed = Palette.of("sand:4,clay:2,gravel:1");
        for (int[] p : inside) {
            int x = p[0], z = p[1];
            int floorY = level - Math.max(1, p[2]);
            int t = h.top(x, z);
            for (int y = floorY + 1; y <= Math.max(t, level); y++) c.set(x, y, z, y <= level ? "water" : "air");
            c.set(x, floorY, z, bed.at(x, floorY, z));
            h.setTop(x, z, level);
            h.setWet(x, z, true);
        }
        return level;
    }
}
