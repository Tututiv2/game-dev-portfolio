package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * The Crystal Caverns (reference: Crystal Hollows): a 448 x 448 x 160 block of solid, mineable
 * rock with a cave world inside it. Five zones — Jungle (NW), Mithril Deposits (NE), Relic Remnants
 * (SE), Goblin Holdout (SW) and the Magma Fields (everything below y 64) — around the Crystal
 * Nucleus, a cylindrical hall in the middle that players enter from a balcony high on its north wall.
 *
 * <p>Passes, in order (additive before carving, and the grid before the canvas):
 * <ol>
 *   <li>Fill the grid with rock inside a bedrock shell.</li>
 *   <li>Lobby noise caves (cheese + spaghetti) in the upper zones; the magma layer with pillars and lava lakes.</li>
 *   <li>Structure chambers: carved, given flat locked floors, their air locked.</li>
 *   <li>The tunnel network: walkways from the Nucleus to every structure (they become bridges over chasms).</li>
 *   <li>Write the grid: zone palettes on exposed faces, gem veins by zone colour, ore pockets, ceiling lights.</li>
 *   <li>Structures, bridges' rails, lanterns, plants and vines; then anchors, areas and zones.</li>
 * </ol>
 * Structure placement never changes; caves, veins and ores follow the LOBBY seed, so an admin
 * "new lobby" rebuild gives a new cave layout around the same landmarks. Pure Java.
 */
public final class HollowsGenerator implements IslandGenerator {

    /** Lobby number, set by the module from its dial before a build. Mixed into the cave seed and the version. */
    public static volatile int lobby = 0;

    public static final int R = 224, MIN_Y = 30, MAX_Y = 190;
    public static final int BASE_VERSION = 3;   // wave 5: inner temple + buried Varnas chests

    @Override public String islandId() { return "crystal_hollows"; }

    @Override public int version() { return BASE_VERSION + Math.max(0, lobby); }

    @Override public Box bounds() { return new Box(-R, MIN_Y, -R, R - 1, MAX_Y, R - 1); }

    @Override
    public void generate(VoxelCanvas canvas, Layout layout, long seed) {
        long var = Hash.mix(seed ^ (0x9E3779B97F4A7C15L * (lobby + 1)));
        Ctx x = new Ctx(canvas, layout, bounds(), seed, var);
        Grid g = x.g;

        // ---- 1. solid rock in a bedrock shell ----
        g.fillRock(1);

        // ---- 2. caves ----
        noiseCaves(x);
        magmaLayer(x);

        // ---- 3. structure chambers (carve + floors + locks) ----
        Structures.prepare(x);

        // ---- 4. tunnel network ----
        Network.carve(x);
        g.dropLooseRock();

        // ---- 5. write the grid ----
        List<int[]> plants = new ArrayList<>();
        List<int[]> ceilings = new ArrayList<>();
        writeGrid(x, plants, ceilings);
        Network.finish(x);

        // ---- 6. structures, then decoration ----
        Structures.build(x);
        decorate(x, plants, ceilings);

        // ---- 7. what the rest of the game reads ----
        zones(x);
        layout.data("lobby", Integer.toString(lobby));
        // landmark./structure. anchors are compass targets (points in space), not places to stand
        layout.data("walk.ignore", "cam.,landmark.,structure.,socket.,parkour.,temple.");
        // parkour./temple. sit inside the SEALED inner temple: entered only through the Jungle Key (a teleport), checked
        // by HollowsPureTest.innerTempleIsAParkour with jumps allowed instead
    }

    // =============================================================================================
    // caves
    // =============================================================================================

    /** Cheese caverns and spaghetti tunnels in the upper zones, sampled on a 4-block lattice and interpolated. */
    static void noiseCaves(Ctx x) {
        Grid g = x.g;
        Noise n = x.varNoise;
        int step = 4;
        int x0 = -R + 6, x1 = R - 7, z0 = -R + 6, z1 = R - 7, y0 = Zones.MAGMA_TOP + 4, y1 = MAX_Y - 6;
        int nx = (x1 - x0) / step + 2, ny = (y1 - y0) / step + 2, nz = (z1 - z0) / step + 2;
        float[] cheese = new float[nx * ny * nz], spagA = new float[nx * ny * nz], spagB = new float[nx * ny * nz];
        for (int iy = 0; iy < ny; iy++)
            for (int iz = 0; iz < nz; iz++)
                for (int ix = 0; ix < nx; ix++) {
                    double wx = x0 + ix * step, wy = y0 + iy * step, wz = z0 + iz * step;
                    int i = (iy * nz + iz) * nx + ix;
                    cheese[i] = (float) n.fbm3(wx / 44.0, wy / 26.0, wz / 44.0, 3);
                    spagA[i] = (float) n.noise3(wx / 34.0 + 40, wy / 22.0, wz / 34.0);
                    spagB[i] = (float) n.noise3(wx / 34.0 - 70, wy / 22.0 + 9, wz / 34.0 + 13);
                }
        for (int y = y0; y <= y1; y++) {
            double fy = (double) (y - y0) / step;
            int iy = (int) fy; double ty = fy - iy;
            // fade caves out toward the ceiling and the magma roof so neither is holed through
            double edge = Math.min(1, Math.min((y - y0) / 8.0, (y1 - y) / 8.0));
            for (int z = z0; z <= z1; z++) {
                double fz = (double) (z - z0) / step;
                int iz = (int) fz; double tz = fz - iz;
                for (int xx = x0; xx <= x1; xx++) {
                    if ((long) xx * xx + (long) z * z < 56L * 56L) continue;   // keep the Nucleus shell whole
                    double fx = (double) (xx - x0) / step;
                    int ix = (int) fx; double tx = fx - ix;
                    double ch = tri(cheese, nx, nz, ix, iy, iz, tx, ty, tz);
                    double a = tri(spagA, nx, nz, ix, iy, iz, tx, ty, tz);
                    double b = tri(spagB, nx, nz, ix, iy, iz, tx, ty, tz);
                    boolean open = ch * edge > 0.34 || (Math.abs(a) < 0.048 * edge && Math.abs(b) < 0.048 * edge);
                    if (open) g.carve(xx, y, z);
                }
            }
        }
    }

    private static double tri(float[] v, int nx, int nz, int ix, int iy, int iz, double tx, double ty, double tz) {
        int i000 = (iy * nz + iz) * nx + ix;
        int i100 = i000 + 1, i010 = i000 + nx * nz, i110 = i010 + 1;
        int i001 = i000 + nx, i101 = i001 + 1, i011 = i010 + nx, i111 = i011 + 1;
        double c00 = v[i000] + (v[i100] - v[i000]) * tx, c01 = v[i001] + (v[i101] - v[i001]) * tx;
        double c10 = v[i010] + (v[i110] - v[i010]) * tx, c11 = v[i011] + (v[i111] - v[i011]) * tx;
        double c0 = c00 + (c01 - c00) * tz, c1 = c10 + (c11 - c10) * tz;
        return c0 + (c1 - c0) * ty;
    }

    /** Floor height of the Magma Fields at a column. */
    static int magmaFloor(Ctx x, int xx, int z) {
        double mound = Math.max(0, x.noise.fbm2(xx / 30.0 + 77, z / 30.0, 2) - 0.12);
        return 36 + (int) Math.round(2.5 * x.noise.fbm2(xx / 45.0, z / 45.0, 3) + 9 * mound);
    }

    /** The Magma Fields: one open layer under everything, pillars holding up the roof, lava lakes on the floor. */
    static void magmaLayer(Ctx x) {
        Grid g = x.g;
        Noise n = x.noise, v = x.varNoise;
        int lim = R - 9;
        for (int z = -lim; z <= lim; z++)
            for (int xx = -lim; xx <= lim; xx++) {
                double rim = Math.max(Math.abs(xx), Math.abs(z));
                int f = magmaFloor(x, xx, z);
                int top = 52 + (int) Math.round(9 * n.fbm2(xx / 60.0 + 50, z / 60.0, 3));
                if (rim > lim - 12) top -= (int) ((rim - (lim - 12)) * 1.2);     // the roof slopes down at the walls
                boolean pillar = v.noise2(xx / 22.0, z / 22.0) > 0.55 || v.noise2(xx / 9.0 + 30, z / 9.0) > 0.80
                        || n.fbm2(xx / 70.0 + 200, z / 70.0, 3) < -0.33;     // rock masses split the fields into halls
                if (pillar || top <= f + 3) continue;
                top = Math.min(top, Zones.MAGMA_TOP - 1);
                for (int y = f + 1; y <= top; y++) g.carve(xx, y, z);
                double lake = n.fbm2(xx / 80.0 + 9, z / 80.0 - 4, 2);
                if (lake < -0.27) {
                    g.set(xx, f, z, Grid.LAVA);
                    g.set(xx, f - 1, z, Grid.LAVA);
                }
            }
    }

    // =============================================================================================
    // writing the grid
    // =============================================================================================

    static final Palette WALL_JUNGLE = Palette.of("stone:8,cyan_terracotta:2,light_gray_terracotta:1");
    static final Palette FLOOR_JUNGLE = Palette.of("moss_block:6,grass_block:3,rooted_dirt:1,coarse_dirt:1");
    static final Palette WALL_MITHRIL = Palette.of("stone:6,gray_wool:2,light_gray_wool:1,cyan_terracotta:1");
    static final Palette FLOOR_MITHRIL = Palette.of("stone:4,andesite:2,tuff:2,polished_andesite:1");
    static final Palette VEIN_MITHRIL = Palette.of("prismarine:3,dark_prismarine:2,prismarine_bricks:1,light_blue_wool:3");
    static final Palette WALL_PRECURSOR = Palette.of("stone:5,light_gray_terracotta:3,cyan_terracotta:1,gray_terracotta:1");
    static final Palette FLOOR_PRECURSOR = Palette.of("stone:3,light_gray_terracotta:2,smooth_stone:1,andesite:1");
    static final Palette WALL_GOBLIN = Palette.of("stone:5,brown_terracotta:3,black_terracotta:1");
    static final Palette FLOOR_GOBLIN = Palette.of("coarse_dirt:3,packed_mud:2,brown_terracotta:2,rooted_dirt:1");
    static final Palette WALL_MAGMA = Palette.of("stone:4,black_terracotta:3,gray_terracotta:2,magma_block:1");
    static final Palette FLOOR_MAGMA = Palette.of("blackstone:3,basalt[axis=y]:2,black_terracotta:2,smooth_basalt:1");
    static final Palette WALL_NUCLEUS = Palette.of("stone:6,light_gray_wool:1,gray_terracotta:1");
    static final Palette ORES = Palette.of("coal_ore:30,iron_ore:25,gold_ore:15,redstone_ore:12,lapis_ore:10,emerald_ore:4,diamond_ore:4");

    static Palette wall(Zones.Zone z) {
        if (z == Zones.JUNGLE) return WALL_JUNGLE;
        if (z == Zones.MITHRIL) return WALL_MITHRIL;
        if (z == Zones.PRECURSOR) return WALL_PRECURSOR;
        if (z == Zones.GOBLIN) return WALL_GOBLIN;
        if (z == Zones.MAGMA) return WALL_MAGMA;
        return WALL_NUCLEUS;
    }

    static Palette floor(Zones.Zone z) {
        if (z == Zones.JUNGLE) return FLOOR_JUNGLE;
        if (z == Zones.MITHRIL) return FLOOR_MITHRIL;
        if (z == Zones.PRECURSOR) return FLOOR_PRECURSOR;
        if (z == Zones.GOBLIN) return FLOOR_GOBLIN;
        if (z == Zones.MAGMA) return FLOOR_MAGMA;
        return WALL_NUCLEUS;
    }

    static String light(Zones.Zone z) {
        if (z == Zones.JUNGLE) return "shroomlight";
        if (z == Zones.MITHRIL || z == Zones.PRECURSOR) return "sea_lantern";
        if (z == Zones.MAGMA) return "magma_block";
        return "glowstone";
    }

    static void writeGrid(Ctx x, List<int[]> plants, List<int[]> ceilings) {
        Grid g = x.g;
        VoxelCanvas c = x.c;
        Noise v = x.varNoise;
        long lightSalt = Hash.salt(x.var, "light"), plantSalt = Hash.salt(x.var, "plant"), paneSalt = Hash.salt(x.var, "pane");
        for (int y = g.minY; y <= g.maxY(); y++)
            for (int z = g.minZ; z <= g.maxZ(); z++)
                for (int xx = g.minX; xx <= g.maxX(); xx++) {
                    byte k = g.kind(xx, y, z);
                    if (k == Grid.AIR) continue;
                    if (k == Grid.BEDROCK) { c.set(xx, y, z, "bedrock"); continue; }
                    if (k == Grid.LAVA) { c.set(xx, y, z, "lava"); continue; }
                    if (k == Grid.WATER) { c.set(xx, y, z, "water"); continue; }
                    long key = Ctx.key(xx, y, z);
                    boolean locked = g.locked(xx, y, z);
                    if (locked) {
                        String d = x.deck.get(key);
                        if (d != null) { c.set(xx, y, z, d); continue; }
                        if (x.bridge.contains(key)) { c.set(xx, y, z, y <= Zones.MAGMA_TOP ? "polished_blackstone_bricks" : "spruce_planks"); continue; }
                    }
                    boolean exposed = g.exposed(xx, y, z);
                    Zones.Zone zone = Zones.at(xx, y, z);
                    // gemstones: the zone's colour in veins, ruby everywhere, both deeper in the rock too
                    double gn = v.noise3(xx / 6.5 + 100, y / 6.5, z / 6.5);
                    double rn = v.noise3(xx / 5.0 + 300, y / 5.0, z / 5.0);
                    String gem = null;
                    if (!locked && gn > (exposed ? 0.56 : 0.64) && zone != Zones.NUCLEUS) gem = zone.glass();
                    else if (!locked && rn > (exposed ? 0.68 : 0.74)) gem = "red";
                    if (gem != null) {
                        c.set(xx, y, z, gem + "_stained_glass");
                        if (exposed && g.air(xx, y + 1, z) && !g.locked(xx, y + 1, z) && Hash.unit(paneSalt, xx, y, z) < 0.35)
                            c.set(xx, y + 1, z, gem + "_stained_glass_pane");
                        continue;
                    }
                    if (!exposed) {
                        if (!locked && v.noise3(xx / 3.5 + 500, y / 3.5, z / 3.5) > 0.76) c.set(xx, y, z, ORES.at(xx >> 2, y >> 2, z >> 2));
                        else if (zone == Zones.MITHRIL && v.noise3(xx / 9.0, y / 9.0, z / 9.0) > 0.42) c.set(xx, y, z, VEIN_MITHRIL.at(xx, y, z));
                        else c.set(xx, y, z, "stone");
                        continue;
                    }
                    boolean top = g.air(xx, y + 1, z), bottom = g.air(xx, y - 1, z);
                    if (zone == Zones.MITHRIL && !locked && v.noise3(xx / 9.0, y / 9.0, z / 9.0) > 0.36) { c.set(xx, y, z, VEIN_MITHRIL.at(xx, y, z)); continue; }
                    if (!locked && v.noise3(xx / 3.5 + 500, y / 3.5, z / 3.5) > 0.74) { c.set(xx, y, z, ORES.at(xx >> 2, y >> 2, z >> 2)); continue; }
                    if (bottom && !top && !locked && Hash.unit(lightSalt, xx, y, z) < (zone == Zones.MAGMA ? 0.002 : 0.0045)) {
                        c.set(xx, y, z, light(zone));
                        continue;
                    }
                    c.set(xx, y, z, top ? floor(zone).at(xx, y, z) : wall(zone).at(xx, y, z));
                    if (top && !g.locked(xx, y + 1, z) && Hash.unit(plantSalt, xx, y, z) < 0.25) plants.add(new int[]{xx, y, z});
                    if (bottom && !top && Hash.unit(plantSalt, xx, y + 7, z) < 0.03) ceilings.add(new int[]{xx, y, z});
                }
    }

    // =============================================================================================
    // decoration
    // =============================================================================================

    static void decorate(Ctx x, List<int[]> plants, List<int[]> ceilings) {
        Grid g = x.g;
        VoxelCanvas c = x.c;
        long salt = Hash.salt(x.var, "deco");
        for (int[] p : plants) {
            int px = p[0], py = p[1] + 1, pz = p[2];
            if (!x.freeAir(px, py, pz) || !x.c.isAir(px, py, pz)) continue;
            Zones.Zone zone = Zones.at(px, py, pz);
            double r = Hash.unit(salt, px, py, pz);
            String b = null;
            if (zone == Zones.JUNGLE) {
                b = r < 0.45 ? "short_grass" : r < 0.70 ? "fern" : r < 0.82 ? "moss_carpet" : r < 0.90 ? "azalea" : r < 0.95 ? "flowering_azalea" : "brown_mushroom";
            } else if (zone == Zones.GOBLIN) {
                if (r < 0.25) b = "brown_mushroom"; else if (r < 0.35) b = "red_mushroom"; else if (r < 0.45) b = "dead_bush";
                else if (r < 0.50) b = "cobweb";
            } else if (zone == Zones.MAGMA) {
                if (r < 0.06) b = "pointed_dripstone[thickness=tip,vertical_direction=up]"; else if (r < 0.10) b = "crimson_roots";
            } else if (zone == Zones.PRECURSOR) {
                if (r < 0.05) b = "lightning_rod[facing=up]"; else if (r < 0.15) b = "dead_bush";
            } else if (zone == Zones.MITHRIL) {
                if (r < 0.10) b = "pointed_dripstone[thickness=tip,vertical_direction=up]"; else if (r < 0.14) b = "brown_mushroom";
            }
            if (b != null && g.solid(px, py - 1, pz)) c.set(px, py, pz, b);
        }
        for (int[] p : ceilings) {
            int px = p[0], py = p[1] - 1, pz = p[2];
            if (!x.freeAir(px, py, pz) || !c.isAir(px, py, pz)) continue;
            Zones.Zone zone = Zones.at(px, py, pz);
            int len = 1 + (int) (Hash.unit(salt, px, py + 3, pz) * 6);
            if (zone == Zones.JUNGLE || zone == Zones.GOBLIN) {
                // hanging glow vines: light the jungle without lamps
                int n = 0;
                while (n < len && x.freeAir(px, py - n, pz) && c.isAir(px, py - n, pz) && x.freeAir(px, py - n - 2, pz)) n++;
                for (int i = 0; i < n; i++)
                    c.set(px, py - i, pz, i == n - 1 ? "cave_vines[age=25,berries=true]" : "cave_vines_plant[berries=true]");
            } else {
                if (x.freeAir(px, py - 2, pz)) c.set(px, py, pz, "pointed_dripstone[thickness=tip,vertical_direction=down]");
            }
        }
    }

    // =============================================================================================
    // zones
    // =============================================================================================

    static void zones(Ctx x) {
        Layout l = x.l;
        l.zone(Zones.JUNGLE.id(), Zones.JUNGLE.name(), Zones.JUNGLE.color(), new Box(-R, Zones.MAGMA_TOP + 1, -R, -1, MAX_Y, -1), null);
        l.zone(Zones.MITHRIL.id(), Zones.MITHRIL.name(), Zones.MITHRIL.color(), new Box(0, Zones.MAGMA_TOP + 1, -R, R - 1, MAX_Y, -1), null);
        l.zone(Zones.PRECURSOR.id(), Zones.PRECURSOR.name(), Zones.PRECURSOR.color(), new Box(0, Zones.MAGMA_TOP + 1, 0, R - 1, MAX_Y, R - 1), null);
        l.zone(Zones.GOBLIN.id(), Zones.GOBLIN.name(), Zones.GOBLIN.color(), new Box(-R, Zones.MAGMA_TOP + 1, 0, -1, MAX_Y, R - 1), null);
        l.zone(Zones.MAGMA.id(), Zones.MAGMA.name(), Zones.MAGMA.color(), new Box(-R, MIN_Y, -R, R - 1, Zones.MAGMA_TOP, R - 1), null);
        l.area("mobs.magma.east", Box.of(36, 36, 70, 130, 46, 168));
        l.area("mobs.magma.west", Box.of(-130, 36, 70, -36, 46, 168));
        l.area("ores.caverns", new Box(-R + 1, MIN_Y + 1, -R + 1, R - 2, MAX_Y - 1, R - 2));
    }
}
