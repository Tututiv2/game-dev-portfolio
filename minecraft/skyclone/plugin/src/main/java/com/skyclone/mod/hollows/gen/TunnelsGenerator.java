package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * The Frozen Tunnels (reference: Glacite Tunnels): a 300 x 300 block of frozen rock under the Dwarven
 * Mines. Players come down into the Base Camp (warm: a camp, its NPCs and the two portals) and walk out
 * along wide timbered tunnels to a central junction, Old Lupo's cave in the west, the frozen Depths in
 * the east (where the big Rimestone guardians prowl) and the Great Rimestone Lake in the south.
 *
 * <p>Everything outside the camp is mineable for the mining module: packed ice is Rimestone, terracotta
 * and red sandstone Umber, clay and cobblestone Tungsten, and black / blue / brown / green stained glass
 * the four gems found only here (Onyx, Aquamarine, Citrine, Peridot). Pure Java.
 */
public final class TunnelsGenerator implements IslandGenerator {

    public static final int R = 150, MIN_Y = 66, MAX_Y = 160;

    @Override public String islandId() { return "glacite_tunnels"; }

    @Override public int version() { return 2; }

    @Override public Box bounds() { return new Box(-R, MIN_Y, -R, R - 1, MAX_Y, R - 1); }

    // ---- places ----
    static final int CAMP_FLOOR = 130, CX0 = -22, CX1 = 22, CZ0 = -124, CZ1 = -80;
    static final int[] JUNCTION = {0, 108, 0};
    static final int[] LAKE = {0, 92, 92};
    static final int[] WOLF = {-106, 110, 22};
    static final int[] DEPTHS = {100, 86, 62};

    static final int[][] CAMP_TO_JUNCTION = {{0, 130, -84}, {0, 130, -78}, {10, 124, -60}, {-5, 116, -35}, {0, 108, -12}, {0, 108, -4}};
    static final int[][] JUNCTION_TO_LAKE = {{0, 108, 4}, {0, 108, 12}, {12, 102, 40}, {5, 96, 60}, {0, 92, 66}};
    static final int[][] JUNCTION_TO_WOLF = {{-4, 108, 0}, {-12, 108, 0}, {-45, 112, 10}, {-75, 110, 18}, {-94, 110, 21}};
    static final int[][] JUNCTION_TO_DEPTHS = {{4, 108, 0}, {12, 108, 0}, {45, 102, 15}, {75, 94, 35}, {88, 86, 50}};
    static final int[][] WOLF_TO_LAKE = {{-104, 110, 34}, {-90, 104, 60}, {-60, 98, 80}, {-30, 92, 90}};
    static final int[][] DEPTHS_TO_LAKE = {{98, 86, 76}, {80, 88, 92}, {30, 92, 96}};
    static final int[][] CAMP_WEST = {{-20, 130, -98}, {-50, 126, -110}, {-90, 122, -92}, {-122, 120, -60}};
    static final int[][] CAMP_EAST = {{20, 130, -98}, {55, 128, -115}, {95, 124, -96}, {125, 120, -60}};

    @Override
    public void generate(VoxelCanvas canvas, Layout layout, long seed) {
        Ctx x = new Ctx(canvas, layout, bounds(), seed, Hash.mix(seed ^ 0x51C0FFEEL));
        Grid g = x.g;
        g.fillRock(1);
        caves(x);
        // ---- chambers ----
        Structures.carveRoom(x, CX0, CAMP_FLOOR + 1, CZ0, CX1, CAMP_FLOOR + 9, CZ1);
        Carve.blob(x, 0, CAMP_FLOOR + 9, (CZ0 + CZ1) / 2.0, 24, 6, 24, 0.12);
        x.structure(Box.of(CX0, CAMP_FLOOR + 1, CZ0, CX1, CAMP_FLOOR + 12, CZ1), true);
        chamber(x, JUNCTION, 17, 9);
        chamber(x, LAKE, 40, 16);
        chamber(x, WOLF, 15, 8);
        chamber(x, DEPTHS, 26, 13);
        // the lake: a basin of water in the middle of its cavern
        for (int z = LAKE[2] - 26; z <= LAKE[2] + 26; z++)
            for (int xx = LAKE[0] - 30; xx <= LAKE[0] + 30; xx++) {
                double d = Math.hypot((xx - LAKE[0]) / 28.0, (z - LAKE[2]) / 22.0);
                if (d > 1) continue;
                int depth = 1 + (int) ((1 - d) * 5);
                for (int y = LAKE[1] - depth; y <= LAKE[1]; y++) x.g.put(xx, y, z, Grid.WATER, true);
                x.g.put(xx, LAKE[1] - depth - 1, z, Grid.ROCK, true);
            }
        // ---- tunnels ----
        Palette deck = Palette.of("spruce_planks:3,stripped_spruce_log[axis=x]:1,packed_mud:1");
        for (int[][] t : new int[][][]{CAMP_TO_JUNCTION, JUNCTION_TO_LAKE, JUNCTION_TO_WOLF, JUNCTION_TO_DEPTHS, WOLF_TO_LAKE, DEPTHS_TO_LAKE})
            Carve.tunnel(x, t, 3.4, 5, deck, true);
        for (int[][] t : new int[][][]{CAMP_WEST, CAMP_EAST}) Carve.tunnel(x, t, 3.0, 5, null, true);
        int[] niche1 = Carve.alcove(x, -60, 125, -107, 0, -1, 4);
        int[] niche2 = Carve.alcove(x, 75, 94, 34, 0, -1, 4);
        g.dropLooseRock();
        // ---- write ----
        List<int[]> ceilings = new ArrayList<>();
        write(x, ceilings);
        Network.finish(x);
        timbers(x);
        camp(x);
        places(x);
        for (int[] p : ceilings) {
            int px = p[0], py = p[1] - 1, pz = p[2];
            if (!x.freeAir(px, py, pz) || !canvas.isAir(px, py, pz) || !x.freeAir(px, py - 2, pz)) continue;
            canvas.set(px, py, pz, Hash.unit(x.var, px, py, pz) < 0.5 ? "pointed_dripstone[thickness=tip,vertical_direction=down,waterlogged=false]" : "blue_ice");
        }
        x.soul(niche1[0], niche1[1], niche1[2]);
        x.soul(CAMP_EAST[CAMP_EAST.length - 1][0], CAMP_EAST[CAMP_EAST.length - 1][1], CAMP_EAST[CAMP_EAST.length - 1][2]);
        x.soul(CAMP_WEST[CAMP_WEST.length - 1][0], CAMP_WEST[CAMP_WEST.length - 1][1], CAMP_WEST[CAMP_WEST.length - 1][2]);
        x.soul(niche2[0], niche2[1], niche2[2]);
        layout.zone("frozen_tunnels", "Frozen Tunnels", 'b', bounds(), null);
        layout.area("ores.tunnels", new Box(-R + 1, MIN_Y + 1, -R + 1, R - 2, MAX_Y - 1, R - 2));
        layout.area("mobs.tunnels.west", Box.of(-125, 118, -115, -45, 128, -55));
        layout.area("mobs.tunnels.east", Box.of(45, 118, -118, 128, 130, -55));
        layout.area("mobs.tunnels.mid", Box.of(-20, 100, -70, 20, 126, -10));
        layout.data("walk.ignore", "landmark.,structure.");
    }

    // =============================================================================================

    private static void caves(Ctx x) {
        Noise n = x.varNoise;
        for (int y = MIN_Y + 6; y <= MAX_Y - 8; y++)
            for (int z = -R + 6; z <= R - 7; z++)
                for (int xx = -R + 6; xx <= R - 7; xx++) {
                    double a = n.noise3(xx / 30.0 + 40, y / 18.0, z / 30.0), b = n.noise3(xx / 30.0 - 70, y / 18.0 + 9, z / 30.0 + 13);
                    if (Math.abs(a) < 0.038 && Math.abs(b) < 0.038) x.g.carve(xx, y, z);
                }
    }

    private static void chamber(Ctx x, int[] c, double r, double h) {
        Carve.blob(x, c[0], c[1] + h * 0.6, c[2], r, h, r, 0.15);
        Carve.floor(x, c[0], c[2], r - 1, r - 1, c[1] + 3, 6, (xx, z) -> c[1]);
    }

    static final Palette WALL = Palette.of("packed_ice:5,stone:3,light_gray_terracotta:1,snow_block:2,blue_ice:1");
    static final Palette FLOOR = Palette.of("snow_block:4,packed_ice:2,stone:2,light_gray_terracotta:1");
    static final Palette UMBER = Palette.of("brown_terracotta:3,terracotta:3,red_sandstone:1,smooth_red_sandstone:1");
    static final Palette TUNGSTEN = Palette.of("clay:3,cobblestone:2");
    static final String[] GEMS = {"black", "blue", "brown", "green"};

    private static void write(Ctx x, List<int[]> ceilings) {
        Grid g = x.g;
        VoxelCanvas c = x.c;
        Noise v = x.varNoise;
        long s = Hash.salt(x.var, "tl");
        for (int y = g.minY; y <= g.maxY(); y++)
            for (int z = g.minZ; z <= g.maxZ(); z++)
                for (int xx = g.minX; xx <= g.maxX(); xx++) {
                    byte k = g.kind(xx, y, z);
                    if (k == Grid.AIR) continue;
                    if (k == Grid.BEDROCK) { c.set(xx, y, z, "bedrock"); continue; }
                    if (k == Grid.WATER) { c.set(xx, y, z, "water"); continue; }
                    if (k == Grid.LAVA) { c.set(xx, y, z, "lava"); continue; }
                    long key = Ctx.key(xx, y, z);
                    boolean locked = g.locked(xx, y, z);
                    if (locked) {
                        String d = x.deck.get(key);
                        if (d != null) { c.set(xx, y, z, d); continue; }
                        if (x.bridge.contains(key)) { c.set(xx, y, z, "spruce_planks"); continue; }
                    }
                    boolean exposed = g.exposed(xx, y, z);
                    double gn = v.noise3(xx / 6.0 + 100, y / 6.0, z / 6.0);
                    if (!locked && gn > (exposed ? 0.58 : 0.66)) {
                        int region = (int) ((v.noise2(xx / 70.0, z / 70.0) + 1) * 2) & 3;
                        String gem = GEMS[region];
                        c.set(xx, y, z, gem + "_stained_glass");
                        if (exposed && g.air(xx, y + 1, z) && !g.locked(xx, y + 1, z) && Hash.unit(s, xx, y, z) < 0.3)
                            c.set(xx, y + 1, z, gem + "_stained_glass_pane");
                        continue;
                    }
                    double vein = v.noise3(xx / 8.0 + 300, y / 8.0, z / 8.0);
                    if (!locked && vein > 0.40) { c.set(xx, y, z, "packed_ice"); continue; }
                    if (!locked && vein < -0.50) { c.set(xx, y, z, UMBER.at(xx, y, z)); continue; }
                    double t = v.noise3(xx / 7.0 - 200, y / 7.0, z / 7.0);
                    if (!locked && t > 0.55) { c.set(xx, y, z, TUNGSTEN.at(xx, y, z)); continue; }
                    if (!locked && t < -0.68) { c.set(xx, y, z, Hash.unit(s, xx, y + 1, z) < 0.5 ? "polished_diorite" : "gray_wool"); continue; }
                    if (!exposed) { c.set(xx, y, z, "stone"); continue; }
                    boolean top = g.air(xx, y + 1, z), bottom = g.air(xx, y - 1, z);
                    if (bottom && !top && Hash.unit(s, xx, y, z) < 0.004) { c.set(xx, y, z, "sea_lantern"); continue; }
                    c.set(xx, y, z, top ? FLOOR.at(xx, y, z) : WALL.at(xx, y, z));
                    if (bottom && !top && Hash.unit(s, xx, y + 5, z) < 0.03) ceilings.add(new int[]{xx, y, z});
                }
    }

    /** Timber frames every few blocks along the main tunnels: posts on both walls and a beam overhead. */
    private static void timbers(Ctx x) {
        for (int[][] t : new int[][][]{CAMP_TO_JUNCTION, JUNCTION_TO_LAKE, JUNCTION_TO_WOLF, JUNCTION_TO_DEPTHS, CAMP_WEST, CAMP_EAST}) {
            double acc = 0;
            for (int sIdx = 0; sIdx + 1 < t.length; sIdx++) {
                int[] a = t[sIdx], b = t[sIdx + 1];
                double dx = b[0] - a[0], dz = b[2] - a[2], len = Math.hypot(dx, dz);
                int n = Math.max(1, (int) Math.ceil(len));
                for (int i = 0; i <= n; i++) {
                    acc += len / n;
                    if (acc < 9) continue;
                    acc = 0;
                    double tt = (double) i / n;
                    int px = (int) Math.floor(a[0] + dx * tt), pz = (int) Math.floor(a[2] + dz * tt);
                    int fy = (int) Math.round(a[1] + (b[1] - a[1]) * tt);
                    // perpendicular direction (axis-aligned)
                    boolean alongX = Math.abs(dx) > Math.abs(dz);
                    int ox = alongX ? 0 : 1, oz = alongX ? 1 : 0;
                    int l = -3, r = 3;
                    int[] left = {px + ox * l, pz + oz * l}, right = {px + ox * r, pz + oz * r};
                    if (!post(x, left[0], fy, left[1]) || !post(x, right[0], fy, right[1])) continue;
                    for (int k = l; k <= r; k++) {
                        int bx = px + ox * k, bz = pz + oz * k;
                        if (x.g.air(bx, fy + 4, bz) && !x.inStruct(bx, fy + 4, bz)) x.c.set(bx, fy + 4, bz, alongX ? "stripped_spruce_log[axis=z]" : "stripped_spruce_log[axis=x]");
                    }
                }
            }
        }
    }

    private static boolean post(Ctx x, int px, int fy, int pz) {
        for (int y = fy + 1; y <= fy + 4; y++) if (!x.g.air(px, y, pz) || (x.g.locked(px, y, pz) && y <= fy + 3) || x.inStruct(px, y, pz)) return false;
        if (!x.g.solid(px, fy, pz)) return false;
        for (int y = fy + 1; y <= fy + 3; y++) x.c.set(px, y, pz, "spruce_log[axis=y]");
        return true;
    }

    private static void camp(Ctx x) {
        Layout l = x.l;
        int f = CAMP_FLOOR;
        Palette deck = Palette.of("spruce_planks:5,stripped_spruce_log[axis=x]:1,snow_block:1");
        for (int z = CZ0; z <= CZ1; z++)
            for (int xx = CX0; xx <= CX1; xx++) x.c.set(xx, f, z, deck.at(xx, f, z));
        // walls lined with planks so the camp reads as built, not mined
        Fill wall = Palette.of("spruce_planks:3,stripped_spruce_log[axis=y]:1,snow_block:1");
        for (int y = f + 1; y <= f + 8; y++) {
            for (int xx = CX0 - 1; xx <= CX1 + 1; xx++) { line(x, xx, y, CZ0 - 1, wall); line(x, xx, y, CZ1 + 1, wall); }
            for (int z = CZ0; z <= CZ1; z++) { line(x, CX0 - 1, y, z, wall); line(x, CX1 + 1, y, z, wall); }
        }
        // the central fire: the warmth that keeps the camp safe from the cold
        Builders.campfire(x.c, 0, f, -102);
        for (int[] p : new int[][]{{-3, -105}, {3, -105}, {-3, -99}, {3, -99}}) Builders.lampPost(x.c, p[0], f, p[1], "street", "north");
        // tents along the side walls
        String[] colors = {"red", "brown", "white"};
        int[][] tents = {{-17, -118}, {-17, -96}, {17, -118}};
        for (int t = 0; t < tents.length; t++) {
            int tx = tents[t][0], tz = tents[t][1];
            for (int z = tz; z <= tz + 4; z++)
                for (int dx = -2; dx <= 2; dx++) {
                    x.c.set(tx + dx, f + 1 + (2 - Math.abs(dx)), z, colors[t] + "_wool");
                    if (Math.abs(dx) > 0) x.c.set(tx + dx, f + 2 + (2 - Math.abs(dx)), z, colors[t] + "_wool");   // overlap: faces touch
                }
            x.c.set(tx, f + 1, tz + 1, t == 0 ? "red_bed[facing=north,occupied=false,part=foot]" : "barrel[facing=up,open=false]");
        }
        Builders.crates(x.c, 15, f, -88, x.seed);
        Builders.crates(x.c, -16, f, -86, x.seed + 1);
        x.c.set(18, f + 1, -100, "smithing_table");
        x.c.set(18, f + 1, -102, "blast_furnace[facing=west,lit=true]");
        x.c.set(18, f + 1, -104, "anvil[facing=north]");
        for (int px = -14; px <= 14; px += 14) Structures.chandelier(x, px, f + 5, -110, 2);
        // portals on the north wall
        Fill frame = Palette.of("polished_deepslate:3,deepslate_tiles:1");
        portal(x, -10, "dwarven_mines", frame);
        portal(x, 6, "hub", frame);
        l.standAt("spawn", 0, f, -110, 0f);
        l.standAt("npc.camp_excavator", 15, f, -100, -90f);
        l.standAt("npc.camp_quartermaster", -12, f, -104, 90f);
        l.standAt("npc.camp_commissions", 8, f, -116, 0f);
        l.standAt("npc.camp_supplies", -12, f, -97, -90f);
        l.standAt("npc.fossil_researcher", 12, f, -92, 90f);
        l.area("base_camp", Box.of(CX0, f + 1, CZ0, CX1, f + 8, CZ1));
        l.zone("glacite_base_camp", "Base Camp", 'e', Box.of(CX0 - 1, f - 1, CZ0 - 1, CX1 + 1, f + 12, CZ1 + 3), null, Layout.Zone.SAFE);
        x.soul(-20, f, -122);
        x.soul(20, f, -82);
    }

    private static void line(Ctx x, int xx, int y, int z, Fill f) {
        if (!x.g.solid(xx, y, z) || x.g.kind(xx, y, z) == Grid.BEDROCK) return;
        x.c.set(xx, y, z, f.at(xx, y, z));
    }

    private static void portal(Ctx x, int x0, String warp, Fill frame) {
        int z = CZ0, f = CAMP_FLOOR;
        for (int y = f + 1; y <= f + 5; y++) { x.c.set(x0, y, z, frame.at(x0, y, z)); x.c.set(x0 + 4, y, z, frame.at(x0 + 4, y, z)); }
        for (int xx = x0; xx <= x0 + 4; xx++) x.c.set(xx, f + 5, z, frame.at(xx, f + 5, z));
        for (int xx = x0 + 1; xx <= x0 + 3; xx++) for (int y = f + 1; y <= f + 4; y++) x.c.set(xx, y, z, "nether_portal[axis=x]");
        x.l.area("portal." + warp, Box.of(x0 + 1, f + 1, z, x0 + 3, f + 4, z));
    }

    private static void places(Ctx x) {
        Layout l = x.l;
        // junction: a ring of lamps and a signpost
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6 + 0.4;
            int px = JUNCTION[0] + (int) Math.round(Math.cos(a) * 11), pz = JUNCTION[2] + (int) Math.round(Math.sin(a) * 11);
            if (x.g.locked(px, JUNCTION[1] + 1, pz) || !x.g.solid(px, JUNCTION[1], pz)) continue;
            Builders.lampPost(x.c, px, JUNCTION[1], pz, "post", "north");
        }
        l.anchor("landmark.junction", JUNCTION[0] + 0.5, JUNCTION[1] + 1, JUNCTION[2] + 0.5, 0f);
        x.soul(JUNCTION[0] - 10, JUNCTION[1], JUNCTION[2] - 7);
        // Old Lupo's cave: a hermit's corner with a fire and a bedroll
        int wx = WOLF[0], wf = WOLF[1], wz = WOLF[2];
        Builders.campfire(x.c, wx, wf, wz);
        x.c.set(wx - 4, wf + 1, wz + 3, "brown_carpet");
        x.c.set(wx - 5, wf + 1, wz + 3, "brown_carpet");
        x.c.set(wx + 4, wf + 1, wz - 4, "barrel[facing=up,open=false]");
        x.c.set(wx + 5, wf + 1, wz - 4, "bone_block[axis=y]");
        l.standAt("npc.old_lupo", wx + 3, wf, wz + 1, -90f);
        l.zone("tunnels_wolf_cave", "Old Lupo's Cave", 'e', Box.of(wx - 15, wf - 1, wz - 15, wx + 15, wf + 12, wz + 15), null, Layout.Zone.SAFE);
        x.soul(wx - 8, wf, wz - 6);
        // the lake shore
        l.zone("tunnels_lake", "Great Rimestone Lake", 'b', Box.of(LAKE[0] - 41, LAKE[1] - 8, LAKE[2] - 41, LAKE[0] + 41, LAKE[1] + 22, LAKE[2] + 41), null);
        l.anchor("landmark.lake", LAKE[0] + 0.5, LAKE[1] + 1, LAKE[2] + 0.5, 0f);
        l.area("fishing.lake", Box.of(LAKE[0] - 26, LAKE[1] - 6, LAKE[2] - 20, LAKE[0] + 26, LAKE[1], LAKE[2] + 20));
        l.area("mobs.lake", Box.of(LAKE[0] - 38, LAKE[1] + 1, LAKE[2] - 38, LAKE[0] + 38, LAKE[1] + 3, LAKE[2] + 38));
        x.soul(LAKE[0] + 30, LAKE[1], LAKE[2] + 18);
        x.soul(LAKE[0] - 31, LAKE[1], LAKE[2] - 12);
        // the Depths
        l.zone("tunnels_depths", "Frozen Depths", '3', Box.of(DEPTHS[0] - 27, DEPTHS[1] - 2, DEPTHS[2] - 27, DEPTHS[0] + 27, DEPTHS[1] + 25, DEPTHS[2] + 27), null);
        l.area("mobs.depths", Box.of(DEPTHS[0] - 22, DEPTHS[1] + 1, DEPTHS[2] - 22, DEPTHS[0] + 22, DEPTHS[1] + 3, DEPTHS[2] + 22));
        l.anchor("structure.frozen_depths", DEPTHS[0] + 0.5, DEPTHS[1] + 1, DEPTHS[2] + 0.5, 0f);
        x.soul(DEPTHS[0] + 20, DEPTHS[1], DEPTHS[2] + 12);
        // frozen remains in the depths: old campsite of an earlier expedition
        for (int i = 0; i < 6; i++) {
            int px = DEPTHS[0] - 12 + (int) (Hash.unit(x.seed, i, 1, 9) * 24), pz = DEPTHS[2] - 12 + (int) (Hash.unit(x.seed, i, 2, 9) * 24);
            if (!x.g.solid(px, DEPTHS[1], pz) || x.g.locked(px, DEPTHS[1] + 1, pz)) continue;
            x.c.set(px, DEPTHS[1] + 1, pz, i % 3 == 0 ? "skeleton_skull[rotation=3]" : i % 3 == 1 ? "blue_ice" : "packed_ice");
        }
    }
}
