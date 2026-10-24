package com.skyclone.mod.garden;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Garden's glass greenhouse (west of the barn). Pure Java. The SAME code runs in the island
 * generator (default skin) and at runtime when a player picks a greenhouse skin: it is rebuilt into
 * a MemoryCanvas and copied over {@link #PLACE_BOX} by the {@link Placer}. Wave 5: every skin has its
 * own SHAPE of building (pitched glasshouse, barrel vault, open pavilion, dome, atrium with a cupola,
 * curved eaves, pointed-arch, sawtooth, pyramid) on the same footprint, with the same door, planter beds
 * and botanist's corner, so nothing the island relies on moves.
 */
public final class GreenhouseBuilder {

    private GreenhouseBuilder() {}

    static final int Y = PlotGrid.GROUND_Y;
    /** The reserved area in the island layout (one block of margin round the building). */
    public static final Box BOX = Box.of(-44, Y, -42, -22, Y + 12, -20);
    static final int X0 = BOX.minX() + 1, X1 = BOX.maxX() - 1, Z0 = BOX.minZ() + 1, Z1 = BOX.maxZ() - 1;
    static final int WALL_TOP = Y + 5;
    /** What a skin change rewrites: the building's footprint, up to the roof ridge (no margin, so the path is never touched). */
    public static final Box PLACE_BOX = Box.of(X0, Y, Z0, X1, WALL_TOP + 2 + (Z1 - Z0) / 2, Z1);
    static final int CX = (X0 + X1) / 2, CZ = (Z0 + Z1) / 2;

    /** Every building shape a skin can name. */
    public static final List<String> SHAPES = List.of("pitched", "vault", "pavilion", "dome", "atrium", "curved", "gothic", "sawtooth", "pyramid");

    /**
     * A skin: its shape, the stair/slab material its roof uses where the shape has solid roofing, frame posts,
     * beams / sill, glass, the floor edge, the floor, door wood, the hanging lights, and the menu card (name,
     * icon item, how it unlocks - same source grammar as barn skins: free, level:N, unique:N, milestone:CROP:N,
     * pests:N, copper:N).
     */
    public record Skin(String id, String name, String icon, String source, String shape, String roof, String post, String beam, String glass,
                       String edge, String floor, String door, String light) {}

    public static final Map<String, Skin> SKINS = new LinkedHashMap<>();

    private static void skin(String id, String name, String icon, String source, String shape, String roof, String post, String beam,
                             String glass, String edge, String floor, String door, String light) {
        SKINS.put(id, new Skin(id, name, icon, source, shape, roof, post, beam, glass, edge, floor, door, light));
    }

    static {
        skin("default", "Greenhouse", "GLASS", "free", "pitched", "spruce", "stripped_spruce_log[axis=y]", "spruce_planks", "glass",
                "polished_andesite", "spruce_planks", "spruce", Blocks.lantern(true));
        skin("conservatory", "Conservatory", "BIRCH_PLANKS", "level:6", "vault", "birch", "stripped_birch_log[axis=y]", "birch_planks", "glass",
                "smooth_quartz", "birch_planks", "birch", Blocks.lantern(true));
        skin("bamboo_pavilion", "Bamboo Pavilion", "BAMBOO", "copper:2500", "pavilion", "bamboo", "bamboo_block[axis=y]", "bamboo_planks", "glass",
                "bamboo_mosaic", "bamboo_planks", "bamboo", Blocks.lantern(true));
        skin("tropical", "Tropical Glasshouse", "JUNGLE_LEAVES", "unique:15", "dome", "jungle", "stripped_jungle_log[axis=y]", "jungle_planks",
                "lime_stained_glass", "mossy_stone_bricks", "jungle_planks", "jungle", Blocks.lantern(true));
        skin("copper_atrium", "Copper Atrium", "COPPER_BLOCK", "copper:6000", "atrium", "waxed_cut_copper", "waxed_oxidized_copper", "waxed_cut_copper",
                "light_blue_stained_glass", "waxed_copper_block", "waxed_cut_copper", "spruce", Blocks.lantern(true));
        skin("blossom", "Blossom House", "CHERRY_LEAVES", "milestone:WILD_ROSE:10", "curved", "cherry", "stripped_cherry_log[axis=y]", "cherry_planks",
                "pink_stained_glass", "polished_andesite", "cherry_planks", "cherry", Blocks.lantern(true));
        skin("moonlit", "Moonlit Glasshouse", "BLUE_ORCHID", "milestone:MOONFLOWER:10", "gothic", "dark_oak", "stripped_dark_oak_log[axis=y]",
                "dark_oak_planks", "purple_stained_glass", "deepslate_tiles", "dark_oak_planks", "dark_oak", Blocks.soulLantern(true));
        skin("hothouse", "Ember Hothouse", "CRIMSON_STEM", "pests:250", "sawtooth", "crimson", "stripped_crimson_stem[axis=y]", "crimson_planks",
                "red_stained_glass", "polished_blackstone", "crimson_planks", "crimson", Blocks.soulLantern(true));
        skin("crystal", "Crystal Glasshouse", "AMETHYST_SHARD", "level:13", "pyramid", "quartz", "quartz_pillar[axis=y]", "quartz_bricks",
                "white_stained_glass", "polished_diorite", "smooth_quartz", "birch", "end_rod[facing=down]");
    }

    /** The building each shape makes, for the skin menu. */
    public static String style(String shape) {
        return switch (shape == null ? "" : shape) {
            case "vault" -> "Barrel Vault";
            case "pavilion" -> "Open Pavilion";
            case "dome" -> "Glass Dome";
            case "atrium" -> "Atrium with Cupola";
            case "curved" -> "Curved Eaves";
            case "gothic" -> "Pointed Arch";
            case "sawtooth" -> "Sawtooth Roof";
            case "pyramid" -> "Glass Pyramid";
            default -> "Pitched Glasshouse";
        };
    }

    public static Skin skin(String id) {
        Skin s = id == null ? null : SKINS.get(id);
        return s != null ? s : SKINS.get("default");
    }

    private static boolean isPost(int x, int z) {
        return (x - X0) % 4 == 0 && (z == Z0 || z == Z1) || (z - Z0) % 4 == 0 && (x == X0 || x == X1);
    }

    /** Build the greenhouse in a skin; returns where the botanist stands. */
    public static int[] build(VoxelCanvas c, String skinId) {
        Skin s = skin(skinId);
        boolean open = s.shape().equals("pavilion");
        // ---- floor and walls ----
        for (int z = Z0; z <= Z1; z++)
            for (int x = X0; x <= X1; x++) {
                boolean edge = x == X0 || x == X1 || z == Z0 || z == Z1;
                c.set(x, Y, z, edge ? s.edge() : s.floor());
                for (int yy = Y + 1; yy <= WALL_TOP; yy++) {
                    if (!edge) { c.set(x, yy, z, "air"); continue; }
                    boolean post = isPost(x, z);
                    String b;
                    if (post) b = s.post();
                    else if (open) b = yy == Y + 1 ? s.roof() + "_fence" : yy == WALL_TOP ? s.beam() : "air";   // open sides with a rail
                    else b = yy == Y + 1 ? s.beam() : s.glass();
                    c.set(x, yy, z, b);
                }
            }
        // ---- the roof: the shape is what makes a skin a different building ----
        switch (s.shape()) {
            case "vault" -> vault(c, s);
            case "pavilion" -> pyramidRoof(c, s, false);
            case "dome" -> dome(c, s);
            case "atrium" -> atrium(c, s);
            case "curved" -> curved(c, s);
            case "gothic" -> gothic(c, s);
            case "sawtooth" -> sawtooth(c, s);
            case "pyramid" -> pyramidRoof(c, s, true);
            default -> pitched(c, s);
        }
        // ---- door on the east side ----
        int dz = CZ;
        c.set(X1, Y + 1, dz, "air");
        c.set(X1, Y + 2, dz, "air");
        Blocks.door(c, X1, Y + 1, dz, s.door(), "east", "left", false);
        if (open) { c.set(X1, Y + 1, dz - 1, s.post()); c.set(X1, Y + 1, dz + 1, s.post()); }
        // ---- planter beds: rows of farmland with crops, water channel every fourth row ----
        String[] crops = {"wheat[age=7]", "carrots[age=7]", "potatoes[age=7]", "beetroots[age=3]", "torchflower", "pitcher_crop[age=4,half=lower]"};
        for (int z = Z0 + 2; z <= Z1 - 2; z++) {
            if (z == dz) continue;
            for (int x = X0 + 2; x <= X1 - 5; x++) {
                if ((z - Z0) % 4 == 0) { c.set(x, Y, z, "water"); continue; }
                c.set(x, Y, z, "farmland[moisture=7]");
                String crop = crops[Math.floorMod(z * 7 + x / 4, crops.length)];
                if (crop.startsWith("pitcher")) {
                    c.set(x, Y + 1, z, "pitcher_crop[age=4,half=lower]");
                    c.set(x, Y + 2, z, "pitcher_crop[age=4,half=upper]");
                } else c.set(x, Y + 1, z, crop);
            }
        }
        // hanging lights down the walk: from the roof where there is one above, else from the wall line
        for (int x = X0 + 3; x <= X1 - 3; x += 5) {
            int ceil = WALL_TOP + 1;
            for (int y = PLACE_BOX.maxY(); y > WALL_TOP; y--) if (!c.isAir(x, y, dz)) { ceil = y; break; }
            if (ceil > WALL_TOP + 2) c.set(x, WALL_TOP + 2, dz, "iron_chain");
            if (ceil > WALL_TOP + 1) c.set(x, WALL_TOP + 1, dz, s.light());
            else c.set(x, WALL_TOP, dz, s.light());
        }
        // the walk from the door, and the botanist's corner
        for (int x = X0 + 1; x <= X1 - 1; x++) c.set(x, Y, dz, s.floor());
        c.set(X1 - 2, Y + 1, Z0 + 1, "barrel[facing=up,open=false]");
        c.set(X1 - 1, Y + 1, Z0 + 1, "potted_fern");
        return new int[]{X1 - 2, Y, dz + 1};
    }

    // =====================================================================================
    // shapes
    // =====================================================================================

    /** The original: a glass ridge roof pitched 1:1 along x with glass gable ends. */
    private static void pitched(VoxelCanvas c, Skin s) {
        int half = (Z1 - Z0) / 2;
        for (int k = 0; k <= half; k++) {
            int yy = WALL_TOP + 1 + k;
            for (int x = X0; x <= X1; x++) {
                c.set(x, yy, Z0 + k, (x - X0) % 4 == 0 ? s.beam() : s.glass());
                c.set(x, yy, Z1 - k, (x - X0) % 4 == 0 ? s.beam() : s.glass());
                if (Z0 + k + 1 < Z1 - k) for (int zz = Z0 + k + 1; zz < Z1 - k; zz++) if (k < half) c.set(x, yy, zz, "air");
            }
            for (int zz = Z0 + k + 1; zz < Z1 - k; zz++) { c.set(X0, yy, zz, s.glass()); c.set(X1, yy, zz, s.glass()); }
        }
    }

    /** A cross-section profile across z (height above the wall line at each z) swept along x, with glass end walls. */
    private static void sweep(VoxelCanvas c, Skin s, int[] h) {
        for (int z = Z0; z <= Z1; z++) {
            int i = z - Z0;
            int low = h[i];
            if (i > 0) low = Math.min(low, h[i - 1] + 1);
            if (i < h.length - 1) low = Math.min(low, h[i + 1] + 1);
            low = Math.max(1, Math.min(low, h[i]));
            for (int x = X0; x <= X1; x++)
                for (int k = low; k <= h[i]; k++) c.set(x, WALL_TOP + k, z, (x - X0) % 4 == 0 ? s.beam() : s.glass());
            // end walls under the curve
            for (int k = 1; k < h[i]; k++) { c.set(X0, WALL_TOP + k, z, s.glass()); c.set(X1, WALL_TOP + k, z, s.glass()); }
        }
    }

    /** Conservatory: a round barrel vault. */
    private static void vault(VoxelCanvas c, Skin s) {
        int n = Z1 - Z0 + 1;
        int[] h = new int[n];
        double r = n / 2.0;
        for (int i = 0; i < n; i++) {
            double dz = Math.abs(i + 0.5 - r);
            h[i] = 1 + (int) Math.round(Math.sqrt(Math.max(0, r * r - dz * dz)) * 0.72);
        }
        sweep(c, s, h);
    }

    /** Moonlit: a pointed (gothic) arch - two arcs meeting at the ridge. */
    private static void gothic(VoxelCanvas c, Skin s) {
        int n = Z1 - Z0 + 1;
        int[] h = new int[n];
        double half = n / 2.0, rr = 14, off = rr - half;
        for (int i = 0; i < n; i++) {
            double dz = Math.abs(i + 0.5 - half);
            h[i] = 1 + (int) Math.round(Math.sqrt(Math.max(0, rr * rr - (dz + off) * (dz + off))) * 0.8);
        }
        sweep(c, s, h);
        // a tracery rose window in each end
        c.set(X0, WALL_TOP + h[n / 2] - 3, CZ, "amethyst_block");
        c.set(X1, WALL_TOP + h[n / 2] - 3, CZ, "amethyst_block");
    }

    /** Blossom House: a curved East-Asian roof - flat eaves that sweep up steeply to the ridge, cherry leaves along it. */
    private static void curved(VoxelCanvas c, Skin s) {
        int n = Z1 - Z0 + 1;
        int[] h = new int[n];
        double half = (n - 1) / 2.0;
        for (int i = 0; i < n; i++) {
            double d = half - Math.abs(i - half);                 // 0 at the eaves, half at the ridge
            h[i] = 1 + (int) Math.round(10 * Math.pow(d / half, 1.8));
        }
        sweep(c, s, h);
        int top = WALL_TOP + h[n / 2];
        for (int x = X0; x <= X1; x++) c.set(x, top + 1, CZ, Blocks.leaves("cherry"));
        for (int x = X0; x <= X1; x += 2) { c.set(x, WALL_TOP + 2, Z0, Blocks.leaves("cherry")); c.set(x, WALL_TOP + 2, Z1, Blocks.leaves("cherry")); }
    }

    /** Hothouse: a sawtooth roof - glass slopes rising across z with vertical faces, like a factory. */
    private static void sawtooth(VoxelCanvas c, Skin s) {
        int tooth = 5;
        for (int z = Z0; z <= Z1; z++) {
            int t = Math.floorMod(z - Z0, tooth);
            int hy = WALL_TOP + 1 + t;
            for (int x = X0; x <= X1; x++) {
                String b = (x - X0) % 4 == 0 ? s.beam() : s.glass();
                c.set(x, hy, z, b);
                if (t == tooth - 1 || z == Z1) for (int y = WALL_TOP + 1; y < hy; y++) c.set(x, y, z, s.glass());   // the vertical face
            }
            for (int y = WALL_TOP + 1; y < hy; y++) { c.set(X0, y, z, s.glass()); c.set(X1, y, z, s.glass()); }
        }
        // vents on the faces
        for (int x = X0 + 2; x <= X1 - 2; x += 4)
            for (int z = Z0 + tooth - 1; z < Z1; z += tooth) c.set(x, WALL_TOP + 2, z, Blocks.trapdoor(s.roof(), "south", "top", true));
    }

    /** A pyramid over the whole footprint: glass with beams on the diagonals (crystal), or a solid stair roof (pavilion). */
    private static void pyramidRoof(VoxelCanvas c, Skin s, boolean glass) {
        int top = WALL_TOP + 1;
        for (int k = 0; ; k++) {
            int ul = X0 + k, ur = X1 - k, vl = Z0 + k, vr = Z1 - k, yy = WALL_TOP + 1 + k;
            if (ul > ur || vl > vr) break;
            top = yy;
            for (int u = ul; u <= ur; u++)
                for (int v = vl; v <= vr; v++) {
                    boolean ring = u == ul || u == ur || v == vl || v == vr;
                    if (!ring) continue;
                    boolean diag = (u == ul || u == ur) && (v == vl || v == vr);
                    String b;
                    if (glass) b = diag ? s.beam() : s.glass();
                    else if (ul == ur || vl == vr) b = s.beam();
                    else if (v == vl) b = Blocks.stairs(s.roof(), "south");
                    else if (v == vr) b = Blocks.stairs(s.roof(), "north");
                    else if (u == ul) b = Blocks.stairs(s.roof(), "east");
                    else b = Blocks.stairs(s.roof(), "west");
                    c.set(u, yy, v, b);
                }
            // a course just inside the ring carries the next one, so the roof is one connected piece
            String inner = glass ? s.glass() : s.beam();
            for (int u = ul + 1; u <= ur - 1; u++) for (int v : new int[]{vl + 1, vr - 1}) if (v > vl && v < vr && c.isAir(u, yy, v)) c.set(u, yy, v, inner);
            for (int v = vl + 1; v <= vr - 1; v++) for (int u : new int[]{ul + 1, ur - 1}) if (u > ul && u < ur && c.isAir(u, yy, v)) c.set(u, yy, v, inner);
        }
        if (glass) c.set(CX, top + 1, CZ, "amethyst_cluster[facing=up,waterlogged=false]");
        else { c.set(CX, top + 1, CZ, "iron_chain"); }
    }

    /** Tropical: a glass dome on the walls, leaves spilling over the rim. */
    private static void dome(VoxelCanvas c, Skin s) {
        double r = (X1 - X0) / 2.0 + 0.5, ry = 10;
        for (int x = X0; x <= X1; x++)
            for (int z = Z0; z <= Z1; z++) {
                double q = ((x - CX) / r) * ((x - CX) / r) + ((z - CZ) / r) * ((z - CZ) / r);
                if (q > 1) q = 1;
                int hi = (int) Math.floor(ry * Math.sqrt(1 - q));
                int lo = hi;
                int[][] n = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] d : n) {
                    int nx = x + d[0], nz = z + d[1];
                    double nq = ((nx - CX) / r) * ((nx - CX) / r) + ((nz - CZ) / r) * ((nz - CZ) / r);
                    int nh = nq >= 1 || nx < X0 || nx > X1 || nz < Z0 || nz > Z1 ? 0 : (int) Math.floor(ry * Math.sqrt(1 - nq));
                    lo = Math.min(lo, nh);                      // overlap the lower neighbour so the shell is face-connected
                }
                lo = Math.max(1, lo);
                for (int k = lo; k <= Math.max(1, hi); k++)
                    c.set(x, WALL_TOP + k, z, ((x - X0) % 4 == 0 && (z - Z0) % 4 == 0) ? s.beam() : s.glass());
            }
        for (int x = X0; x <= X1; x += 3) { c.set(x, WALL_TOP + 1, Z0, Blocks.leaves("jungle")); c.set(x, WALL_TOP + 1, Z1, Blocks.leaves("jungle")); }
        c.set(CX, WALL_TOP + (int) ry + 1, CZ, "jungle_log[axis=y]");
    }

    /** Copper Atrium: a flat glass roof on a beam grid, with a raised cupola and a copper spire in the middle. */
    private static void atrium(VoxelCanvas c, Skin s) {
        int ry = WALL_TOP + 1;
        int k0 = 3;
        for (int x = X0; x <= X1; x++)
            for (int z = Z0; z <= Z1; z++) {
                boolean grid = (x - X0) % 4 == 0 || (z - Z0) % 4 == 0;
                boolean hole = Math.abs(x - CX) < k0 && Math.abs(z - CZ) < k0;
                if (!hole) c.set(x, ry, z, grid ? s.beam() : s.glass());
            }
        // cupola: glass walls with posts round the hole, three high
        for (int y = ry; y <= ry + 2; y++)
            for (int x = CX - k0; x <= CX + k0; x++)
                for (int z = CZ - k0; z <= CZ + k0; z++) {
                    boolean ring = Math.abs(x - CX) == k0 || Math.abs(z - CZ) == k0;
                    if (!ring) continue;
                    boolean corner = Math.abs(x - CX) == k0 && Math.abs(z - CZ) == k0;
                    c.set(x, y, z, corner ? s.post() : s.glass());
                }
        // its pyramid cap in copper
        for (int k = 0; k <= k0; k++) {
            int yy = ry + 3 + k;
            for (int x = CX - k0 + k; x <= CX + k0 - k; x++)
                for (int z = CZ - k0 + k; z <= CZ + k0 - k; z++) {
                    boolean ring = Math.abs(x - CX) == k0 - k || Math.abs(z - CZ) == k0 - k;
                    if (!ring) continue;
                    String b = k == k0 ? s.beam()
                            : z == CZ - k0 + k ? Blocks.stairs(s.roof(), "south") : z == CZ + k0 - k ? Blocks.stairs(s.roof(), "north")
                            : x == CX - k0 + k ? Blocks.stairs(s.roof(), "east") : Blocks.stairs(s.roof(), "west");
                    c.set(x, yy, z, b);
                }
            for (int x = CX - k0 + k + 1; x <= CX + k0 - k - 1; x++)
                for (int z = CZ - k0 + k + 1; z <= CZ + k0 - k - 1; z++)
                    if (Math.abs(x - CX) == k0 - k - 1 || Math.abs(z - CZ) == k0 - k - 1) c.set(x, yy, z, s.beam());
        }
        c.set(CX, ry + 4 + k0, CZ, "lightning_rod[facing=up,powered=false,waterlogged=false]");
    }
}
