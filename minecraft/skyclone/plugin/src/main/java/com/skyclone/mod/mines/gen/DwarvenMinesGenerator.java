package com.skyclone.mod.mines.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Dwarven Mines: one vast cavern under a stone sky, ~440 blocks across, holding a buried dwarf
 * city and the mines around it. Districts are chambers at their own heights joined by graded haul
 * passages; the Royal Palace stands on a plateau across a lava chasm, reached over the Palace Bridge.
 * Mining districts are coated in mithril (grey wool / cyan terracotta, prismarine, light blue wool)
 * with rare titanium (polished diorite), each registered as an {@code ores.<district>} area for the
 * mining module's regen and commissions.
 *
 * <p>Research 03 §10 (sub-areas, mobs, NPCs), §28 (souls), §31.8 (mithril cyan/grey, gold trim, lava,
 * Royal Palace + bridge); research 07 §2.9 (mithril grades, titanium).
 */
public final class DwarvenMinesGenerator implements IslandGenerator {

    /** kind: 0 = town (SAFE, no mining), 1 = mining district, 2 = hazard/special. */
    public record District(String id, String name, char color, int x, int z, int r, int floor, int height, int kind) {}

    public static final List<District> DISTRICTS = List.of(
            new District("dwarven_village", "Dwarven Village", 'b', 0, 70, 48, 140, 40, 0),
            new District("royal_palace", "Royal Palace", '6', -115, -40, 42, 152, 44, 0),
            new District("the_forge", "The Forge", '6', 105, 55, 32, 138, 30, 0),
            new District("ramparts_quarry", "Ramparts Quarry", 'b', -150, 70, 40, 128, 34, 1),
            new District("goblin_burrows", "Goblin Burrows", '2', -60, 170, 40, 122, 26, 1),
            new District("lava_springs", "Lava Springs", 'c', 100, 160, 42, 112, 40, 1),
            new District("upper_mines", "Upper Mines", 'b', 130, -80, 48, 170, 32, 1),
            new District("royal_mines", "Royal Mines", 'b', -10, -95, 40, 150, 30, 1),
            new District("cliffside_veins", "Cliffside Veins", 'b', -165, -130, 35, 168, 30, 1),
            new District("far_reserve", "Far Reserve", 'b', 185, 40, 30, 140, 28, 1),
            new District("great_ice_wall", "Great Ice Wall", 'f', -60, -170, 36, 160, 32, 1),
            new District("the_mist", "The Mist", '8', 55, -10, 40, 136, 46, 2),
            new District("deepkings_gateway", "Deepking's Gateway", 'd', 90, -180, 28, 175, 26, 0),
            new District("base_camp", "Dwarven Base Camp", 'b', -160, 175, 28, 110, 24, 0),
            new District("palace_chasm", "Palace Chasm", 'c', -60, 13, 34, 80, 120, 2));

    record Passage(String a, String b, int w) {}

    static final List<Passage> PASSAGES = List.of(
            new Passage("dwarven_village", "the_forge", 12), new Passage("dwarven_village", "the_mist", 10),
            new Passage("the_forge", "the_mist", 10), new Passage("dwarven_village", "royal_mines", 10),
            new Passage("dwarven_village", "ramparts_quarry", 10), new Passage("dwarven_village", "goblin_burrows", 10),
            new Passage("the_forge", "lava_springs", 10), new Passage("the_forge", "far_reserve", 8),
            new Passage("goblin_burrows", "lava_springs", 8), new Passage("royal_mines", "royal_palace", 8),
            new Passage("royal_mines", "great_ice_wall", 10), new Passage("royal_mines", "upper_mines", 10),
            new Passage("upper_mines", "deepkings_gateway", 8), new Passage("upper_mines", "far_reserve", 8),
            new Passage("royal_palace", "cliffside_veins", 8), new Passage("royal_palace", "ramparts_quarry", 8),
            new Passage("ramparts_quarry", "base_camp", 8), new Passage("goblin_burrows", "base_camp", 8),
            new Passage("great_ice_wall", "cliffside_veins", 8));

    public static final int MIN = -224, MAX = 224;
    /** Fairy souls in the reference's Dwarven Mines (NEU fairy_souls.json, mining_3). */
    public static final int RESEARCH_SOULS = 15;
    /** The minecart depot: every cart line starts at this station's platform. */
    public static final String DEPOT = "village";

    @Override public String islandId() { return "dwarven_mines"; }
    // 2: SAFE station zones; 3: plaza regulars (fetcher, riddlewright, drill mechanic) + well ladder;
    // 4 (wave 5): railed cart lines, The Lift, Forge Basin, Gates to the Mines, the cart depot, Miner's Guild,
    //   the Crystal Hollows warden, 15 souls
    @Override public int version() { return 4; }
    @Override public Box bounds() { return new Box(MIN, 60, MIN, MAX, 236, MAX); }

    static District d(String id) { for (District x : DISTRICTS) if (x.id.equals(id)) return x; throw new IllegalArgumentException(id); }
    static int idx(String id) { for (int i = 0; i < DISTRICTS.size(); i++) if (DISTRICTS.get(i).id.equals(id)) return i; return -1; }

    // ---- themes -------------------------------------------------------------------------------------

    private static final Palette STONE = Palette.of("stone:14,andesite:5,tuff:3,cobblestone:1");
    private static final Palette TOWN_FACE = Palette.of("stone:10,stone_bricks:3,andesite:4,cracked_stone_bricks:1,tuff:2");
    private static final Palette MINE_FACE = Palette.of("stone:12,andesite:5,tuff:3,cobblestone:1,coal_ore:1,iron_ore:1");
    private static final Palette GOBLIN_FACE = Palette.of("stone:8,coarse_dirt:4,rooted_dirt:2,mud:2,andesite:3,gravel:1");
    private static final Palette LAVA_FACE = Palette.of("stone:6,basalt[axis=y]:5,blackstone:5,magma_block:1,tuff:2");
    private static final Palette ICE_FACE = Palette.of("packed_ice:8,stone:5,snow_block:3,blue_ice:1,ice:2,calcite:2");
    private static final Palette MIST_FACE = Palette.of("deepslate:8,tuff:5,smooth_basalt:3,stone:3,dark_prismarine:1");
    private static final Palette CHASM_FACE = Palette.of("blackstone:6,basalt[axis=y]:4,stone:5,magma_block:2,deepslate:3");
    private static final Palette MITHRIL_1 = Palette.of("gray_wool:5,cyan_terracotta:4");
    private static final Palette MITHRIL_2 = Palette.of("prismarine:4,prismarine_bricks:2,dark_prismarine:3");
    private static final Palette TOWN_FLOOR = Palette.of("stone_bricks:4,polished_andesite:2,cobblestone:2,cracked_stone_bricks:1,andesite:1");
    private static final Palette MINE_FLOOR = Palette.of("stone:5,gravel:3,cobblestone:2,andesite:2,coarse_dirt:1");
    private static final Palette GOBLIN_FLOOR = Palette.of("coarse_dirt:5,rooted_dirt:2,mud:2,gravel:2,podzol:1");
    private static final Palette LAVA_FLOOR = Palette.of("basalt[axis=y]:3,blackstone:3,stone:2,gravel:1");
    private static final Palette ICE_FLOOR = Palette.of("snow_block:4,packed_ice:3,stone:1,calcite:1");
    private static final Palette MIST_FLOOR = Palette.of("deepslate:4,tuff:3,smooth_basalt:2,gravel:1");
    private static final Palette PATH = Palette.of("gravel:4,cobblestone:2,stone_bricks:2,andesite:2");

    private Noise mith;

    private String face(District dd, int x, int y, int z) {
        switch (dd.id) {
            case "goblin_burrows": if (mithril(dd, x, y, z, 0.52)) return mithrilBlock(x, y, z); return GOBLIN_FACE.at(x, y, z);
            case "lava_springs": if (mithril(dd, x, y, z, 0.30)) return mithrilBlock(x, y, z); return LAVA_FACE.at(x, y, z);
            case "great_ice_wall": if (mithril(dd, x, y, z, 0.45)) return mithrilBlock(x, y, z); return ICE_FACE.at(x, y, z);
            case "the_mist": if (mithril(dd, x, y, z, 0.42)) return mithrilBlock(x, y, z); return MIST_FACE.at(x, y, z);
            case "palace_chasm": return CHASM_FACE.at(x, y, z);
            default:
                if (dd.kind == 0) return TOWN_FACE.at(x, y, z);
                if (mithril(dd, x, y, z, 0.18)) return mithrilBlock(x, y, z);
                return MINE_FACE.at(x, y, z);
        }
    }

    private boolean mithril(District dd, int x, int y, int z, double threshold) {
        return mith.fbm3(x / 7.0, y / 6.0, z / 7.0, 2) > threshold;
    }

    private String mithrilBlock(int x, int y, int z) {
        double u = Hash.unit(0x77A1L, x, y, z);
        if (u < 0.012) return "polished_diorite";                     // titanium
        double g = mith.noise3(x / 13.0 + 40, y / 13.0, z / 13.0);
        if (g > 0.45 && u < 0.5) return "light_blue_wool";
        if (g > 0.05) return MITHRIL_2.at(x, y, z);
        return MITHRIL_1.at(x, y, z);
    }

    private String floorFor(District dd, int x, int y, int z) {
        switch (dd.id) {
            case "goblin_burrows": return GOBLIN_FLOOR.at(x, y, z);
            case "lava_springs": return LAVA_FLOOR.at(x, y, z);
            case "great_ice_wall": case "base_camp": return ICE_FLOOR.at(x, y, z);
            case "the_mist": return MIST_FLOOR.at(x, y, z);
            case "palace_chasm": return "blackstone";
            default: return dd.kind == 0 ? TOWN_FLOOR.at(x, y, z) : MINE_FLOOR.at(x, y, z);
        }
    }

    // ---- generate -----------------------------------------------------------------------------------

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        stationGeo.clear();
        mith = new Noise(Hash.salt(seed, "mithril"));
        Noise wob = new Noise(Hash.salt(seed, "wobble"));
        Noise fn = new Noise(Hash.salt(seed, "floor"));
        Noise cn = new Noise(Hash.salt(seed, "ceiling"));
        CaveGrid g = new CaveGrid(MIN, MIN, MAX, MAX);

        // ---- 1. plan every column: district chambers, then passages ----
        int n = DISTRICTS.size();
        for (int z = MIN + 6; z <= MAX - 6; z++)
            for (int x = MIN + 6; x <= MAX - 6; x++) {
                int best = -1; double bestD = 9;
                for (int i = 0; i < n; i++) {
                    District dd = DISTRICTS.get(i);
                    double dx = x - dd.x, dz = z - dd.z;
                    double a = Math.atan2(dz, dx);
                    double rr = dd.r * (1 + 0.12 * wob.noise3(Math.cos(a) * 1.7, Math.sin(a) * 1.7, i * 2.3));
                    double nd = Math.hypot(dx, dz) / rr;
                    if (nd < 1 && nd < bestD) { bestD = nd; best = i; }
                }
                if (best >= 0) {
                    District dd = DISTRICTS.get(best);
                    double fade = Noise.smoothstep(1.0, 0.75, bestD);
                    int f = dd.floor + (int) Math.round(1.6 * fade * fn.fbm2(x / 20.0, z / 20.0, 3));
                    double prof = 0.5 + 0.5 * Math.sqrt(Math.max(0, 1 - bestD * bestD));
                    int h = (int) Math.round(dd.height * prof + 4 * cn.fbm2(x / 17.0, z / 17.0, 3));
                    if (dd.id.equals("the_mist")) {
                        f = dd.floor - (int) Math.round(24 * (1 - Noise.smoothstep(0.3, 1.0, bestD))) + (int) Math.round(fn.fbm2(x / 12.0, z / 12.0, 2) * (1 - bestD));
                        h = Math.max(h, 140 + 10 - f);
                    }
                    if (dd.id.equals("palace_chasm")) { f = dd.floor; h = 200 - f; }
                    g.set(x, z, f, f + Math.max(h, 9), best + 1);
                    continue;
                }
                // passages
                for (Passage p : PASSAGES) {
                    District A = d(p.a), B = d(p.b);
                    double vx = B.x - A.x, vz = B.z - A.z, len = Math.hypot(vx, vz);
                    double t = ((x - A.x) * vx + (z - A.z) * vz) / (len * len);
                    if (t < 0 || t > 1) continue;
                    double px = A.x + vx * t, pz = A.z + vz * t;
                    double off = Math.hypot(x - px, z - pz);
                    double half = p.w / 2.0 * (1 + 0.18 * wob.noise2(x / 11.0, z / 11.0));
                    if (off > half) continue;
                    double tA = (double) A.r / len, tB = 1 - (double) B.r / len;
                    double s = Noise.clamp((t - tA) / Math.max(0.01, tB - tA), 0, 1);
                    int f = (int) Math.round(A.floor + (B.floor - A.floor) * s);
                    int region = (t < 0.5 ? idx(p.a) : idx(p.b)) + 1;
                    int ceil = f + p.w + 5 + (int) Math.round(2 * cn.noise2(x / 9.0, z / 9.0));
                    if (!g.open(x, z) || g.floor(x, z) < f) g.set(x, z, f, ceil, region);
                    break;
                }
            }
        // rubble mounds and terraces in the mining districts, pillars everywhere
        Random rnd = Hash.random(seed, "relief");
        for (int i = 0; i < n; i++) {
            District dd = DISTRICTS.get(i);
            if (dd.kind != 1) continue;
            for (int k = 0; k < 6; k++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = dd.r * (0.25 + rnd.nextDouble() * 0.5);
                int mx = dd.x + (int) Math.round(Math.cos(a) * r), mz = dd.z + (int) Math.round(Math.sin(a) * r);
                int mr = 5 + rnd.nextInt(5), up = 2 + rnd.nextInt(4);
                for (int dz = -mr; dz <= mr; dz++) for (int dx = -mr; dx <= mr; dx++) {
                    int x = mx + dx, z = mz + dz;
                    double q = Math.hypot(dx, dz) / mr;
                    if (q >= 1 || !g.open(x, z) || g.region(x, z) != i + 1) continue;
                    int f = g.floor(x, z) + (int) Math.round(up * Noise.smoothstep(1, 0.25, q));
                    g.set(x, z, f, Math.max(g.ceil(x, z), f + 7), i + 1);
                }
            }
            for (int k = 0; k < 4; k++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = dd.r * (0.3 + rnd.nextDouble() * 0.45);
                int px = dd.x + (int) Math.round(Math.cos(a) * r), pz = dd.z + (int) Math.round(Math.sin(a) * r);
                int pr = 2 + rnd.nextInt(3);
                for (int dz = -pr; dz <= pr; dz++) for (int dx = -pr; dx <= pr; dx++)
                    if (dx * dx + dz * dz <= pr * pr + 1 && g.region(px + dx, pz + dz) == i + 1) g.close(px + dx, pz + dz);
            }
        }
        // the Great Ice Wall itself: a ridge of ice across its district, broken by two gaps
        District ice = d("great_ice_wall");
        for (int x = ice.x - ice.r; x <= ice.x + ice.r; x++) {
            int wz = ice.z + (int) Math.round(6 * Math.sin((x - ice.x) / 9.0));
            boolean gap = Math.abs(x - (ice.x - 14)) < 4 || Math.abs(x - (ice.x + 15)) < 4;
            if (gap) continue;
            for (int dz = -2; dz <= 2; dz++) if (g.region(x, wz + dz) == idx("great_ice_wall") + 1) g.close(x, wz + dz);
        }

        // towns are level ground
        District vd = d("dwarven_village"), pd = d("royal_palace"), fd = d("the_forge"), gd = d("deepkings_gateway"), bd = d("base_camp");
        for (int z = vd.z - vd.r - 8; z <= vd.z + vd.r + 8; z++)
            for (int x = vd.x - vd.r - 8; x <= vd.x + vd.r + 8; x++)
                if (g.open(x, z) && g.region(x, z) == idx(vd.id) + 1 && Math.hypot(x - vd.x, z - vd.z) < vd.r * 0.97)
                    g.set(x, z, vd.floor, Math.max(g.ceil(x, z), vd.floor + 12), g.region(x, z));
        level(g, Box.of(pd.x - 20, 0, pd.z - 17, pd.x + 20, 0, pd.z + 30), pd.floor);
        level(g, Box.of(fd.x - 17, 0, fd.z - 14, fd.x + 17, 0, fd.z + 14), fd.floor);
        level(g, Box.of(gd.x - 12, 0, gd.z - 14, gd.x + 12, 0, gd.z + 8), gd.floor);
        level(g, Box.of(bd.x - 16, 0, bd.z - 12, bd.x + 16, 0, bd.z + 16), bd.floor);
        forgeBasinPlan(g);

        // ---- 2. write the rock ----
        g.write(c, 6, 6, 6, new CaveGrid.Painter() {
            @Override public String rock(int x, int y, int z, int region, boolean exposed) {
                District dd = DISTRICTS.get(Math.max(0, region - 1));
                if (!exposed) return dd.id.equals("great_ice_wall") ? (y % 3 == 0 ? "packed_ice" : "stone") : STONE.at(x, y, z);
                return face(dd, x, y, z);
            }
            @Override public String floorTop(int x, int y, int z, int region) {
                return floorFor(DISTRICTS.get(Math.max(0, region - 1)), x, y, z);
            }
        });
        // passages get a gravel haul road down the middle and stairs on the rises
        for (Passage p : PASSAGES) {
            District A = d(p.a), B = d(p.b);
            double len = Math.hypot(B.x - A.x, B.z - A.z);
            for (double s = A.r - 2; s <= len - B.r + 2; s += 0.5) {
                int x = (int) Math.round(A.x + (B.x - A.x) * s / len), z = (int) Math.round(A.z + (B.z - A.z) * s / len);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    int qx = x + dx, qz = z + dz;
                    if (!g.open(qx, qz)) continue;
                    if (DISTRICTS.get(g.region(qx, qz) - 1).kind == 0 && inside(qx, qz, d(DISTRICTS.get(g.region(qx, qz) - 1).id))) continue;
                    c.set(qx, g.floor(qx, qz), qz, PATH.at(qx, g.floor(qx, qz), qz));
                }
            }
        }
        // (wave 5: the stair-cut on passage rises moved after the cart lines, so a rail can climb a rise the stairs
        // would otherwise have blocked; the stairs then fill every rise the track does not use)

        // ---- 3. the chasm: a lava lake under the Palace Bridge ----
        District ch = d("palace_chasm");
        for (int z = ch.z - ch.r - 6; z <= ch.z + ch.r + 6; z++)
            for (int x = ch.x - ch.r - 6; x <= ch.x + ch.r + 6; x++)
                if (g.open(x, z) && g.region(x, z) == idx("palace_chasm") + 1) {
                    int f = g.floor(x, z);
                    c.set(x, f, z, "lava");
                    c.set(x, f + 1, z, "lava");
                    c.set(x, f - 1, z, "blackstone");
                }

        Reservations res = new Reservations();
        MineKit.Souls souls = new MineKit.Souls();
        List<String> stations = new ArrayList<>();

        // ---- 4. districts ----
        village(c, g, layout, res, souls, stations, seed);
        palace(c, g, layout, res, souls, stations, seed);
        forge(c, g, layout, res, souls, stations, seed);
        gateway(c, g, layout, res, souls, stations, seed);
        baseCamp(c, g, layout, res, souls, stations, seed);
        bridge(c, g, layout);
        for (District dd : DISTRICTS) if (dd.kind == 1 || dd.id.equals("the_mist")) miningDistrict(c, g, layout, dd, souls, stations, seed);

        // ---- 5. lights and dripstone across the whole cavern ----
        long ls = Hash.salt(seed, "lights");
        for (int z = MIN; z <= MAX; z += 4)
            for (int x = MIN; x <= MAX; x += 4) {
                if (!g.open(x, z)) continue;
                District dd = DISTRICTS.get(g.region(x, z) - 1);
                if (dd.id.equals("palace_chasm") || dd.id.equals("the_mist")) continue;
                double u = Hash.unit(ls, x, z);
                int cy = g.ceil(x, z), fy = g.floor(x, z);
                if (cy - fy > 12 && u < 0.07) MineKit.hangLantern(c, x, cy, z, 3 + (int) (Hash.unit(ls, x, 1, z) * 5), dd.id.equals("great_ice_wall"));
                else if (u < 0.16) { c.set(x, cy, z, dd.id.equals("lava_springs") ? "shroomlight" : "glowstone"); c.set(x + 1, cy, z, "glowstone"); }
            }
        Box all = new Box(MIN, 0, MIN, MAX, 0, MAX);
        MineKit.stalactites(c, g, all, seed, 0.03, null);

        // ---- 5b. wave 5: the cart lines (a real railed track from the depot to every station), then the stairs ----
        cartLines(c, g, layout, res);
        MineKit.stepify(c, all, g::floor, (x, z) -> g.open(x, z) && !inAnyDistrict(x, z), "stone_brick_stairs");
        souls.write(c, layout, RESEARCH_SOULS);

        // ---- 6. zones and areas ----
        for (District dd : DISTRICTS) {
            Box b = new Box(dd.x - dd.r - 4, dd.floor - 12, dd.z - dd.r - 4, dd.x + dd.r + 4, dd.floor + dd.height + 10, dd.z + dd.r + 4);
            if (dd.id.equals("palace_chasm")) { layout.zone(dd.id, dd.name, dd.color, new Box(b.minX(), 70, b.minZ(), b.maxX(), 150, b.maxZ()), null); continue; }
            if (dd.kind == 0) layout.zone(dd.id, dd.name, dd.color, b, null, Layout.Zone.SAFE);
            else layout.zone(dd.id, dd.name, dd.color, b, null);
            if (dd.kind == 1 || dd.id.equals("the_mist")) layout.area("ores." + dd.id, b);
            int[] fr = g.floorRange(b, idx(dd.id) + 1);
            if (fr[0] != CaveGrid.NONE) layout.area(dd.id, new Box(dd.x - dd.r + 3, fr[0], dd.z - dd.r + 3, dd.x + dd.r - 3, fr[1] + 3, dd.z + dd.r - 3));
        }
        District v = d("dwarven_village"), rm = d("royal_mines");
        layout.zone("aristocrat_passage", "Aristocrat Passage", 'e', Box.of(-8, 130, rm.z + rm.r - 2, 8, 170, v.z - v.r + 2), null);
        stationZones(layout, stations);
        layout.zone("dwarven_mines", "Dwarven Mines", '2', new Box(MIN, 0, MIN, MAX, 255, MAX), null);
        layout.data("stations", String.join(",", stations));
        layout.data("reservations", res.summary());
        layout.data("walk.ignore", "station.");
    }

    /** Half-width of a station's SAFE box around its Ticket Master (the platform reaches 3 out). */
    public static final int STATION_SAFE_RADIUS = 5;

    /**
     * Every minecart station is a SAFE stop. The town stations already sit in SAFE districts; the
     * ones out in the mining districts got their own small SAFE zone, because a cart that drops you
     * into a district's mob area (Goblin Burrows: ~30 goblins and a Lv200 Cutthroat) kills you before
     * you can look around. In a SAFE zone mobs never spawn, never target or hurt you, and are leashed
     * back out if they wander in. Added after the district zones so anything that walks
     * {@code layout.zones()} in order (commission names) still finds the district first.
     */
    private static void stationZones(Layout layout, List<String> stations) {
        for (String id : stations) {
            Layout.Anchor t = layout.anchor("npc.ticket." + id);
            if (t == null) continue;
            if (safeAt(layout, t.x(), t.y(), t.z())) continue;
            District dd = DISTRICTS.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
            String name = (dd == null ? id : dd.name) + " Station";
            char color = dd == null ? 'a' : dd.color;
            int x = (int) Math.floor(t.x()), y = (int) Math.floor(t.y()), z = (int) Math.floor(t.z());
            int r = STATION_SAFE_RADIUS;
            layout.zone("station_" + id, name, color, new Box(x - r, y - 2, z - r, x + r, y + 5, z + r), null, Layout.Zone.SAFE);
        }
    }

    /** True when the smallest zone containing the point is SAFE (the same rule core applies). */
    public static boolean safeAt(Layout layout, double x, double y, double z) {
        Layout.Zone best = null;
        long bestVol = Long.MAX_VALUE;
        for (Layout.Zone zn : layout.zones()) {
            Box b = zn.box();
            if (!b.contains(x, y, z)) continue;
            long vol = (long) (b.maxX() - b.minX() + 1) * (b.maxY() - b.minY() + 1) * (b.maxZ() - b.minZ() + 1);
            if (vol < bestVol) { bestVol = vol; best = zn; }
        }
        return best != null && best.flags() != null && best.flags().contains(Layout.Zone.SAFE);
    }

    private static boolean inside(int x, int z, District dd) { return Math.hypot(x - dd.x, z - dd.z) < dd.r * 0.85; }

    private static boolean inAnyDistrict(int x, int z) {
        for (District dd : DISTRICTS) if (Math.hypot(x - dd.x, z - dd.z) < dd.r * 0.88) return true;
        return false;
    }

    // ---- shared bits -------------------------------------------------------------------------------

    /** A minecart station: a short platform with a buffer, a ticket master and a teleport target. */
    private void station(VoxelCanvas c, CaveGrid g, Layout layout, List<String> stations, String id, int x, int z, String facing) {
        if (!g.open(x, z)) return;
        int y = g.floor(x, z);
        int fx = Blocks.dx(facing), fz = Blocks.dz(facing);
        int px = Blocks.dx(Blocks.cw(facing)), pz = Blocks.dz(Blocks.cw(facing));
        for (int i = -3; i <= 3; i++)
            for (int s = -1; s <= 2; s++) {
                int bx = x + fx * i + px * s, bz = z + fz * i + pz * s;
                if (!g.open(bx, bz) || Math.abs(g.floor(bx, bz) - y) > 1) continue;
                c.set(bx, y, bz, s == 2 ? "polished_andesite" : "stone_bricks");
                for (int yy = y + 1; yy <= y + 3; yy++) if (!c.isAir(bx, yy, bz)) c.set(bx, yy, bz, "air");
            }
        for (int i = -3; i <= 2; i++) c.set(x + fx * i + px * 2, y + 1, z + fz * i + pz * 2, "rail[shape=" + (fx != 0 ? "east_west" : "north_south") + "]");
        // wave 5: the throat (where the cart line leaves the platform) is kept clear of the district's props
        int tx = x - fx * 4 + px * 2, tz = z - fz * 4 + pz * 2;
        if (g.open(tx, tz) && Math.abs(g.floor(tx, tz) - y) <= 1)
            for (int yy = g.floor(tx, tz) + 1; yy <= g.floor(tx, tz) + 4; yy++) if (!c.isAir(tx, yy, tz) && yy < g.ceil(tx, tz)) c.set(tx, yy, tz, "air");
        c.set(x + fx * 3 + px * 2, y + 1, z + fz * 3 + pz * 2, "polished_andesite_slab[type=bottom]");
        c.set(x - fx * 3 + px * 3, y + 1, z - fz * 3 + pz * 3, "lantern[hanging=false,waterlogged=false]");
        Builders.lampPost(c, x + fx * 3 - px, y, z + fz * 3 - pz, "post", facing);
        layout.standAt("npc.ticket." + id, x, y, z, yawOf(Blocks.opposite(facing)));
        layout.standAt("station." + id, x - fx * 2, y, z - fz * 2, yawOf(facing));
        stations.add(id);
        stationGeo.put(id, new int[]{x, y, z, java.util.Arrays.asList(RailRoute.NAMES).indexOf(facing)});
    }

    /** Wave 5: each station's Ticket Master spot {x, y, z, facing index}, for the cart lines. */
    private final java.util.Map<String, int[]> stationGeo = new java.util.LinkedHashMap<>();

    /**
     * A station's rail lane (the platform's s=2 lane), from the buffer end (i=2) out to the throat (i=-4), the
     * first cell beyond the platform where its line leaves. Cells {x, y, z}.
     */
    static List<int[]> lane(int[] geo) {
        int fx = RailRoute.DX[geo[3]], fz = RailRoute.DZ[geo[3]];
        int px = RailRoute.DX[(geo[3] + 1) % 4], pz = RailRoute.DZ[(geo[3] + 1) % 4];
        List<int[]> out = new ArrayList<>();
        for (int i = 2; i >= -4; i--) out.add(new int[]{geo[0] + fx * i + px * 2, geo[1], geo[2] + fz * i + pz * 2});
        return out;
    }

    static float yawOf(String facing) {
        return switch (facing) { case "south" -> 0f; case "west" -> 90f; case "north" -> 180f; default -> -90f; };
    }

    private int ground(CaveGrid g, int x, int z) { return g.open(x, z) ? g.floor(x, z) : Integer.MIN_VALUE; }

    /** Flatten a box of open columns to one height (towns, plazas); returns that height. */
    private int level(CaveGrid g, Box b, int y) {
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++)
                if (g.open(x, z) && g.region(x, z) != idx("palace_chasm") + 1) g.set(x, z, y, Math.max(g.ceil(x, z), y + 12), g.region(x, z));
        return y;
    }

    // ---- the village -------------------------------------------------------------------------------

    private void village(VoxelCanvas c, CaveGrid g, Layout layout, Reservations res, MineKit.Souls souls, List<String> stations, long seed) {
        District v = d("dwarven_village");
        int y = v.floor;
        Box plaza = Box.of(v.x - 12, y, v.z - 10, v.x + 12, y, v.z + 10);
        res.reserve("plaza", plaza.grow(1));
        Palette pav = Palette.of("polished_andesite:3,stone_bricks:3,chiseled_stone_bricks:1,polished_diorite:1");
        for (int z = plaza.minZ(); z <= plaza.maxZ(); z++) for (int x = plaza.minX(); x <= plaza.maxX(); x++) c.set(x, y, z, pav.at(x, y, z));
        // the Heart of the Mountain monument: a cut crystal on a stepped plinth
        int mx = v.x, mz = v.z - 2;
        for (int r = 4; r >= 2; r--) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) c.set(mx + dx, y + 5 - r, mz + dz, r == 4 ? "polished_deepslate" : r == 3 ? "deepslate_bricks" : "gold_block");
        for (int h = 0; h < 9; h++) {
            int w = h < 3 ? 1 : h < 6 ? 1 : 0;
            for (int dx = -w; dx <= w; dx++) for (int dz = -w; dz <= w; dz++) c.set(mx + dx, y + 4 + h, mz + dz, (dx + dz + h) % 2 == 0 ? "light_blue_stained_glass" : "prismarine_bricks");
        }
        c.set(mx, y + 13, mz, "sea_lantern");
        for (int[] q : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) { c.set(mx + q[0], y + 2, mz + q[1], Blocks.lantern(false)); }
        layout.standAt("npc.hotm", mx, y, mz + 6, 180f);
        layout.standAt("spawn", v.x, y, v.z + 8, 180f);
        for (int[] q : new int[][]{{-12, -10}, {12, -10}, {-12, 10}, {12, 10}}) Builders.lampPost(c, v.x + q[0], y, v.z + q[1], "street", q[0] < 0 ? "east" : "west");

        // the commission board, north side of the plaza
        int bz = v.z - 11;
        for (int dx = -3; dx <= 3; dx++) for (int h = 1; h <= 4; h++)
            c.set(v.x + 6 + dx, y + h, bz, Math.abs(dx) == 3 || h == 4 ? "dark_oak_log[axis=y]" : h == 1 ? "barrel[facing=south,open=false]" : "spruce_planks");
        for (int dx = -3; dx <= 3; dx++) c.set(v.x + 6 + dx, y + 5, bz, Blocks.slab("dark_oak", "bottom"));
        c.set(v.x + 6, y + 3, bz + 1, "lantern[hanging=false,waterlogged=false]".replace("lantern", "lantern"));
        layout.standAt("npc.commissions", v.x + 6, y, bz + 2, 0f);

        // houses around the plaza: shops, the tavern, the library
        Object[][] houses = {
                {"tavern", -34, 54, 13, 9, "east", "Dwarven Tavern"},
                {"library", -34, 72, 11, 9, "east", "Grand Library"},
                {"pick_shop", 20, 52, 9, 7, "west", null},
                {"cook_house", 20, 66, 9, 7, "west", null},
                {"lamp_shop", 20, 80, 9, 7, "west", null},
                {"house_a", -30, 90, 8, 7, "north", "Miner's Guild"},   // wave 5: the reference's Miner's Guild
                {"house_b", 14, 96, 8, 7, "north", null},
                {"house_c", -30, 36, 8, 7, "south", null},
                {"house_d", 18, 36, 8, 7, "south", null},
                {"house_e", -20, 32, 8, 7, "south", null},
                {"house_f", 8, 40, 7, 6, "west", null},
                {"house_g", -8, 98, 9, 7, "north", null}};
        Builders.House[] built = new Builders.House[houses.length];
        for (int i = 0; i < houses.length; i++) {
            Object[] hd = houses[i];
            Builders.House b = new Builders.House((int) hd[1], y, (int) hd[2], (int) hd[3], (int) hd[4]);
            b.door = (String) hd[5];
            b.floors = hd[0].equals("tavern") || hd[0].equals("library") || hd[0].equals("house_a") ? 2 : 1;
            b.wall = Palette.of("stone_bricks:5,cracked_stone_bricks:1,polished_andesite:2");
            b.frame = "dark_oak_log"; b.roof = "deepslate_tile"; b.roofStyle = i % 2 == 0 ? "gable" : "hip";
            b.floor = Fill.of("spruce_planks"); b.doorWood = "dark_oak"; b.chimney = i % 3 == 0; b.chimneyBlock = "deepslate_bricks";
            b.foundation = Palette.of("deepslate_bricks:3,polished_deepslate:1"); b.seed = seed + i;
            if (!res.reserve((String) hd[0], b.reserve())) continue;
            b.build(c);
            built[i] = b;
            if (hd[6] != null) {
                Box rb = b.reserve();
                layout.zone((String) hd[0], (String) hd[6], 'b', new Box(rb.minX(), y - 1, rb.minZ(), rb.maxX(), b.peakY, rb.maxZ()), null, Layout.Zone.SAFE);
            }
        }
        npcBeside(layout, built[2], "npc.pickwright");
        npcBeside(layout, built[3], "npc.mine_cook");
        npcBeside(layout, built[4], "npc.lamplighter");
        if (built[0] != null) souls.add(built[0].inside[0], built[0].inside[1], built[0].inside[2]);
        if (built[1] != null) souls.add(built[1].inside[0], built[1].inside[1], built[1].inside[2]);
        // wave 3: a barkeep in the Tavern and an archivist in the Grand Library (two steps from the rug, away from the door)
        if (built[0] != null) layout.standAt("npc.barkeep", built[0].inside[0] - 2, y, built[0].inside[2], -90f);
        if (built[1] != null) layout.standAt("npc.archivist", built[1].inside[0] - 2, y, built[1].inside[2], -90f);
        // wave 5: the guild's warden stands just inside the Miner's Guild door
        if (built[5] != null) layout.standAt("npc.guild", built[5].inside[0], y, built[5].inside[2], 0f);
        // streets from the plaza to the houses
        Palette street = Palette.of("cobblestone:3,stone_bricks:3,gravel:1,polished_andesite:1");
        for (Builders.House b : built) {
            if (b == null) continue;
            int sx = b.doorStep[0], sz = b.doorStep[2];
            int tx = Math.max(plaza.minX(), Math.min(plaza.maxX(), sx)), tz = Math.max(plaza.minZ(), Math.min(plaza.maxZ(), sz));
            paveLine(c, g, sx, sz, tx, sz, street);
            paveLine(c, g, tx, sz, tx, tz, street);
        }
        // market stalls and benches along the plaza
        String[][] cols = {{"red", "white"}, {"cyan", "light_gray"}, {"orange", "white"}, {"blue", "yellow"}};
        int[][] stallAt = {{v.x - 17, v.z - 4, 0}, {v.x - 17, v.z + 6, 0}, {v.x + 16, v.z - 1, 1}, {v.x + 16, v.z + 9, 1}};
        for (int i = 0; i < stallAt.length; i++) {
            int[] s = stallAt[i];
            Box sb = Box.of(s[0] - 3, y, s[1] - 3, s[0] + 3, y + 5, s[1] + 3);
            if (res.reserve("stall" + i, sb)) Builders.marketStall(c, s[0], y, s[1], s[2] == 0 ? "east" : "west", cols[i][0], cols[i][1], seed + i);
        }
        Builders.bench(c, v.x - 8, y, v.z + 11, "north", 3, "dark_oak");
        Builders.bench(c, v.x + 6, y, v.z + 11, "north", 3, "dark_oak");
        Builders.well(c, v.x - 22, y, v.z + 22);
        layout.standAt("npc.old_miner", v.x - 18, y, v.z + 22, 90f);
        // the foreman waits at the north road out toward the Royal Mines
        layout.standAt("npc.foreman", v.x + 3, y, v.z - v.r + 8, 180f);
        villageRegulars(c, layout, v.x, y, v.z);
        // the gate home: portals to the Hub and back up to the Deep Caverns at the south edge
        int gz = v.z + 38;
        for (int dz = -2; dz <= 4; dz++) for (int dx = -14; dx <= 14; dx++) if (g.open(v.x + dx, gz + dz)) c.set(v.x + dx, y, gz + dz, pav.at(v.x + dx, y, gz + dz));
        res.reserve("gate", Box.of(v.x - 14, y - 2, gz - 2, v.x + 14, y + 12, gz + 4));
        MineKit.portal(c, layout, "hub", v.x - 6, y, gz + 2, 'x', 3, 4, Palette.of("polished_deepslate:3,gold_block:1"), null);
        MineKit.portal(c, layout, "deep_caverns", v.x + 6, y, gz + 2, 'x', 3, 4, Palette.of("obsidian:3,crying_obsidian:1"), null);
        for (int dx : new int[]{-10, -2, 2, 10}) Builders.lampPost(c, v.x + dx, y, gz, "post", "north");
        gatesToTheMines(c, layout, v.x, y, gz);
        station(c, g, layout, stations, "village", v.x + 36, v.z - 6, "north");
        cartDepot(c, layout, v.x + 36, y, v.z - 6);
        theLift(c, g, layout, res, v);
        souls.add(v.x - 40, y, v.z + 20);
        souls.add(v.x + 38, y, v.z - 24);
    }

    /**
     * The plaza regulars the mining module asked for (its request 13), each with a prop that says what they do:
     * the Fetcher (the reference's Fetchur, a daily fetch quest) by his barrels on the west side, the Drill
     * Mechanic (refuels and fits drills) at an anvil and smithing table, and the Riddlewright (the reference's
     * Puzzler, a daily arrow puzzle) on the east side behind a 3x3 board of arrow tiles set into the paving.
     * Everything sits on the plaza (flat, SAFE, reachable from spawn), never on a house that might not get built.
     */
    private void villageRegulars(VoxelCanvas c, Layout layout, int vx, int y, int vz) {
        // Fetcher: west side, facing the plaza, a stack of barrels and a chest behind him
        layout.standAt("npc.fetcher", vx - 10, y, vz - 6, -90f);
        c.set(vx - 11, y + 1, vz - 7, "barrel[facing=up,open=false]");
        c.set(vx - 11, y + 2, vz - 7, "barrel[facing=up,open=false]");
        c.set(vx - 11, y + 1, vz - 5, "chest[facing=east,type=single,waterlogged=false]");
        c.set(vx - 12, y + 1, vz - 6, "barrel[facing=east,open=false]");
        // Drill Mechanic: west side, south of the Fetcher, between an anvil and a smithing table
        layout.standAt("npc.drill_mechanic", vx - 10, y, vz + 5, -90f);
        c.set(vx - 11, y + 1, vz + 4, "anvil[facing=north]");
        c.set(vx - 11, y + 1, vz + 6, "smithing_table");
        c.set(vx - 12, y + 1, vz + 5, "blast_furnace[facing=east,lit=true]");
        // Riddlewright: east side, a lectern at his elbow and the arrow board in the paving in front of him
        layout.standAt("npc.riddlewright", vx + 10, y, vz + 4, 90f);
        c.set(vx + 11, y + 1, vz + 6, "lectern[facing=west,has_book=true,powered=false]");
        String[] facing = {"north", "east", "south", "west"};
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++)
                c.set(vx + 7 + dx, y, vz + 4 + dz, "magenta_glazed_terracotta[facing=" + facing[Math.floorMod(dx * 3 + dz * 5, 4)] + "]");
    }

    private void npcBeside(Layout layout, Builders.House b, String anchor) {
        if (b == null) return;
        int dx = b.door.equals("east") || b.door.equals("west") ? 0 : 2, dz = dx == 0 ? 2 : 0;
        float yaw = yawOf(b.door);
        layout.standAt(anchor, b.doorStep[0] + dx, b.doorStep[1], b.doorStep[2] + dz, yaw);
    }

    private void paveLine(VoxelCanvas c, CaveGrid g, int x1, int z1, int x2, int z2, Palette p) {
        int n = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= n; i++) {
            int x = x1 + Integer.signum(x2 - x1) * i, z = z1 + Integer.signum(z2 - z1) * i;
            for (int s = -1; s <= 1; s++) {
                int qx = x + (z1 == z2 ? 0 : s), qz = z + (z1 == z2 ? s : 0);
                if (!g.open(qx, qz)) continue;
                int f = g.floor(qx, qz);
                if (MineKit.solid(c, qx, f, qz) && c.isAir(qx, f + 1, qz)) c.set(qx, f, qz, p.at(qx, f, qz));
            }
        }
    }

    // ---- the palace --------------------------------------------------------------------------------

    private void palace(VoxelCanvas c, CaveGrid g, Layout layout, Reservations res, MineKit.Souls souls, List<String> stations, long seed) {
        District p = d("royal_palace");
        int y = p.floor;
        int x0 = p.x - 15, x1 = p.x + 15, z0 = p.z - 12, z1 = p.z + 12;
        Box keep = Box.of(x0, y, z0, x1, y + 16, z1);
        res.reserve("palace", keep.grow(2));
        level(g, keep.grow(5), y);
        Palette wall = Palette.of("stone_bricks:6,polished_blackstone_bricks:2,cracked_stone_bricks:1");
        Palette floor = Palette.of("polished_blackstone_bricks:3,polished_andesite:2");
        for (int x = x0 - 5; x <= x1 + 5; x++) for (int z = z0 - 5; z <= z1 + 5; z++) if (g.open(x, z)) c.set(x, y, z, TOWN_FLOOR.at(x, y, z));
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int yy = y; yy <= y + 16; yy++) {
                    boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                    if (yy == y) { c.set(x, yy, z, (x + z) % 7 == 0 ? "gold_block" : "polished_blackstone_bricks"); continue; }
                    if (yy == y + 16) { c.set(x, yy, z, "polished_blackstone_bricks"); continue; }
                    if (!edge) { c.set(x, yy, z, "air"); continue; }
                    if (yy == y + 1 || yy == y + 8) c.set(x, yy, z, "gold_block");
                    else if (yy % 4 == 3 && (x + z) % 4 == 0) c.set(x, yy, z, "yellow_stained_glass_pane");
                    else c.set(x, yy, z, wall.at(x, yy, z));
                }
        for (int x = x0; x <= x1; x += 2) { c.set(x, y + 17, z0, "polished_blackstone_brick_wall"); c.set(x, y + 17, z1, "polished_blackstone_brick_wall"); }
        for (int z = z0; z <= z1; z += 2) { c.set(x0, y + 17, z, "polished_blackstone_brick_wall"); c.set(x1, y + 17, z, "polished_blackstone_brick_wall"); }
        // great door on the east wall, toward the bridge
        for (int dz = -2; dz <= 2; dz++) for (int h = 1; h <= 6; h++) c.set(x1, y + h, p.z + dz, "air");
        for (int dz = -3; dz <= 3; dz++) c.set(x1, y + 7, p.z + dz, "gold_block");
        // pillars and a carpet to the throne
        for (int x = x0 + 4; x <= x1 - 4; x += 6)
            for (int s : new int[]{-6, 6}) for (int yy = y + 1; yy <= y + 15; yy++) c.set(x, yy, p.z + s, yy % 5 == 0 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
        for (int x = x0 + 4; x <= x1 - 1; x++) for (int dz = -1; dz <= 1; dz++) c.set(x, y + 1, p.z + dz, "red_carpet");
        for (int dz = -3; dz <= 3; dz++) for (int dx = 1; dx <= 3; dx++) c.set(x0 + dx, y + 1, p.z + dz, "gold_block");
        for (int dz = -2; dz <= 2; dz++) c.set(x0 + 4, y + 1, p.z + dz, Blocks.stairs("polished_blackstone_brick", "west"));
        c.set(x0 + 1, y + 2, p.z, Blocks.stairs("polished_blackstone_brick", "east"));
        c.set(x0 + 1, y + 3, p.z, "gold_block");
        for (int dz : new int[]{-1, 1}) c.set(x0 + 1, y + 2, p.z + dz, "gold_block");
        layout.standAt("npc.mine_king", x0 + 2, y + 1, p.z, -90f);
        for (int x = x0 + 3; x <= x1 - 3; x += 6) { MineKit.hangLantern(c, x, y + 16, p.z - 3, 4, false); MineKit.hangLantern(c, x, y + 16, p.z + 3, 4, false); }
        layout.zone("throne_room", "Royal Quarters", '6', new Box(x0, y - 1, z0, x1, y + 16, z1), null, Layout.Zone.SAFE);
        souls.add(x1 - 2, y, z0 + 2);
        // corner towers
        for (int[] q : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
            for (int yy = y + 1; yy <= y + 22; yy++)
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    if (shell) c.set(q[0] + dx, yy, q[1] + dz, yy % 6 == 0 ? "gold_block" : "stone_bricks");
                }
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (!(Math.abs(dx) == 2 && Math.abs(dz) == 2)) c.set(q[0] + dx, y + 23, q[1] + dz, "deepslate_tiles");
            c.set(q[0], y + 24, q[1], "gold_block");
            c.set(q[0], y + 25, q[1], Blocks.lantern(false));
        }
        // the barracks beside the keep
        Builders.House barracks = new Builders.House(p.x - 12, y, p.z + 18, 13, 8);
        barracks.door = "north"; barracks.roof = "deepslate_tile"; barracks.wall = Palette.of("stone_bricks:5,polished_blackstone_bricks:1");
        barracks.frame = "dark_oak_log"; barracks.doorWood = "dark_oak"; barracks.seed = seed + 50;
        barracks.foundation = Palette.of("deepslate_bricks:2,polished_deepslate:1");
        if (res.reserve("barracks", barracks.reserve())) {
            barracks.build(c);
            Box rb = barracks.reserve();
            layout.zone("barracks", "Barracks of Heroes", '6', new Box(rb.minX(), y - 1, rb.minZ(), rb.maxX(), barracks.peakY, rb.maxZ()), null, Layout.Zone.SAFE);
            souls.add(barracks.inside[0], barracks.inside[1], barracks.inside[2]);
        }
        // wave 3: two Royal Guards flank the great door
        layout.standAt("npc.guard.1", x1 + 2, y, p.z - 3, -90f);
        layout.standAt("npc.guard.2", x1 + 2, y, p.z + 3, -90f);
        hangingCourt(c, layout, x1, y, z0, z1, p.z);
        Builders.lampPost(c, x1 + 3, y, p.z - 5, "street", "west");
        Builders.lampPost(c, x1 + 3, y, p.z + 5, "street", "west");
        station(c, g, layout, stations, "palace", p.x + 4, p.z - 22, "east");
    }

    /**
     * Wave 3: the Hanging Court, the reference's forecourt of the Royal Palace (one of the sub-areas the first
     * pass folded away). The paved strip between the keep's great door and the bridge gets royal banners hung
     * down the east facade and lanterns on long chains from the cave roof, and its own SAFE zone so the sidebar
     * names it. Added after the district zone, and smaller, so the smallest-box rule picks it inside its box.
     */
    private void hangingCourt(VoxelCanvas c, Layout layout, int x1, int y, int z0, int z1, int pz) {
        // banners on the facade, skipping the great door (pz-2..pz+2) and the guards beside it
        for (int z = z0 + 1; z <= z1 - 1; z += 2) {
            if (Math.abs(z - pz) <= 3) continue;
            for (int h : new int[]{6, 5}) {
                if (!c.isAir(x1 + 1, y + h, z)) continue;
                c.set(x1 + 1, y + h, z, (z / 2) % 2 == 0 ? "red_wall_banner[facing=east]" : "yellow_wall_banner[facing=east]");
                break;
            }
        }
        // lanterns hanging from the cave roof down the middle of the court
        for (int z = z0; z <= z1; z += 6) {
            int x = x1 + 4, top = -1;
            for (int yy = y + 2; yy <= y + 40; yy++) if (!c.isAir(x, yy, z)) { top = yy; break; }
            if (top > y + 6) MineKit.hangLantern(c, x, top, z, Math.min(top - y - 5, 10), false);
        }
        layout.zone("hanging_court", "Hanging Court", '6', new Box(x1 + 1, y - 1, z0 - 3, x1 + 6, y + 14, z1 + 3), null, Layout.Zone.SAFE);
    }

    // ---- the forge ---------------------------------------------------------------------------------

    private void forge(VoxelCanvas c, CaveGrid g, Layout layout, Reservations res, MineKit.Souls souls, List<String> stations, long seed) {
        District f = d("the_forge");
        int y = f.floor;
        int x0 = f.x - 11, x1 = f.x + 11, z0 = f.z - 8, z1 = f.z + 8;
        Box hall = Box.of(x0, y, z0, x1, y + 12, z1);
        res.reserve("forge", hall.grow(2));
        level(g, hall.grow(6), y);
        for (int x = x0 - 6; x <= x1 + 6; x++) for (int z = z0 - 6; z <= z1 + 6; z++) if (g.open(x, z)) c.set(x, y, z, TOWN_FLOOR.at(x, y, z));
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) c.set(x, y, z, (x + z) % 2 == 0 ? "polished_deepslate" : "deepslate_tiles");
        // open-sided hall: deepslate pillars, a stepped roof, a great chimney
        for (int x = x0; x <= x1; x += 4)
            for (int z : new int[]{z0, z1}) for (int yy = y + 1; yy <= y + 9; yy++) c.set(x, yy, z, yy % 4 == 0 ? "chiseled_deepslate" : "deepslate_bricks");
        for (int x = x0 - 1; x <= x1 + 1; x++)
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                int edge = Math.min(Math.min(x - (x0 - 1), (x1 + 1) - x), Math.min(z - (z0 - 1), (z1 + 1) - z));
                int ry = y + 10 + Math.min(edge, 3);
                c.set(x, ry, z, edge >= 3 ? "deepslate_tiles" : "deepslate_tile_slab[type=bottom]".replace("slab[type=bottom]", "slab[type=bottom]"));
                if (edge < 3) c.set(x, ry - 1, z, "deepslate_tiles");
            }
        for (int yy = y + 1; yy <= Math.min(g.ceil(f.x, f.z) - 1, y + 40); yy++)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                c.set(f.x + dx, yy, f.z + dz, dx == 0 && dz == 0 ? (yy <= y + 2 ? "lava" : "air") : yy <= y + 3 ? "polished_deepslate" : "bricks");
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(f.x + dx, y, f.z + dz, "polished_deepslate");
        c.set(f.x, y + 1, f.z, "magma_block");
        c.set(f.x, y + 2, f.z, "lava");
        // anvils, furnaces and quench troughs along the sides
        for (int x = x0 + 2; x <= x1 - 2; x += 4) {
            c.set(x, y + 1, z0 + 2, "blast_furnace[facing=south,lit=true]");
            c.set(x + 1, y + 1, z0 + 2, "anvil[facing=east]");
            c.set(x, y + 1, z1 - 2, "smithing_table");
            c.set(x + 1, y + 1, z1 - 2, "water_cauldron[level=3]");
        }
        layout.standAt("npc.forge", f.x - 4, y, f.z + 3, 90f);
        souls.add(x1 - 1, y, z1 - 1);
        forgeBasin(c, g, layout);
        Builders.lampPost(c, x0 - 3, y, z0 - 3, "street", "east");
        Builders.lampPost(c, x1 + 3, y, z1 + 3, "street", "west");
        station(c, g, layout, stations, "forge", f.x - 20, f.z, "south");
    }

    // ---- the gateway to the Crystal Hollows, and the base camp for the Glacite Tunnels ----------------

    private void gateway(VoxelCanvas c, CaveGrid g, Layout layout, Reservations res, MineKit.Souls souls, List<String> stations, long seed) {
        District gw = d("deepkings_gateway");
        int y = gw.floor;
        level(g, Box.of(gw.x - 12, 0, gw.z - 14, gw.x + 12, 0, gw.z + 6), y);
        for (int x = gw.x - 12; x <= gw.x + 12; x++) for (int z = gw.z - 14; z <= gw.z + 6; z++) if (g.open(x, z)) c.set(x, y, z, Palette.of("polished_deepslate:3,deepslate_tiles:2,amethyst_block:1").at(x, y, z));
        Box arch = Box.of(gw.x - 9, y, gw.z - 12, gw.x + 9, y + 16, gw.z - 8);
        res.reserve("gateway", arch);
        for (int x = arch.minX(); x <= arch.maxX(); x++)
            for (int z = arch.minZ(); z <= arch.maxZ(); z++)
                for (int yy = y + 1; yy <= y + 16; yy++) c.set(x, yy, z, (yy % 5 == 0) ? "chiseled_deepslate" : "deepslate_bricks");
        for (int x = gw.x - 4; x <= gw.x + 4; x++) for (int yy = y + 1; yy <= y + 10; yy++) for (int z = arch.minZ(); z <= arch.maxZ(); z++) c.set(x, yy, z, "air");
        MineKit.portal(c, layout, "crystal_hollows", gw.x, y, gw.z - 9, 'x', 5, 7, Palette.of("amethyst_block:3,purpur_block:1"), null);
        for (int x : new int[]{gw.x - 6, gw.x + 6}) for (int yy = y + 1; yy <= y + 3; yy++) c.set(x, yy, gw.z - 7, "amethyst_block");
        c.set(gw.x - 6, y + 4, gw.z - 7, "amethyst_cluster[facing=up,waterlogged=false]");
        c.set(gw.x + 6, y + 4, gw.z - 7, "amethyst_cluster[facing=up,waterlogged=false]");
        Builders.lampPost(c, gw.x - 8, y, gw.z - 3, "soul", "north");
        Builders.lampPost(c, gw.x + 8, y, gw.z - 3, "soul", "north");
        souls.add(gw.x + 11, y, gw.z + 4);
        // wave 5: the warden of the Crystal Hollows entrance (the reference's Gwendolyn) beside the amethyst portal
        layout.standAt("npc.gwendolyn", gw.x + 3, y, gw.z - 5, 180f);
        station(c, g, layout, stations, "gateway", gw.x, gw.z + 6, "east");
    }

    private void baseCamp(VoxelCanvas c, CaveGrid g, Layout layout, Reservations res, MineKit.Souls souls, List<String> stations, long seed) {
        District b = d("base_camp");
        int y = b.floor;
        level(g, Box.of(b.x - 16, 0, b.z - 12, b.x + 16, 0, b.z + 16), y);
        for (int x = b.x - 16; x <= b.x + 16; x++) for (int z = b.z - 12; z <= b.z + 16; z++) if (g.open(x, z)) c.set(x, y, z, ICE_FLOOR.at(x, y, z));
        MineKit.portal(c, layout, "glacite_tunnels", b.x, y, b.z + 14, 'x', 3, 5, Palette.of("packed_ice:3,blue_ice:2"), null);
        // tents around a campfire
        for (int i = 0; i < 3; i++) {
            int tx = b.x - 12 + i * 9, tz = b.z - 6;
            tent(c, tx, y, tz, i == 1 ? "light_blue_wool" : "white_wool");
        }
        c.set(b.x, y + 1, b.z + 3, Blocks.campfire(true, "north"));
        Builders.bench(c, b.x - 2, y, b.z + 3, "east", 1, "spruce");
        MineKit.crates(c, b.x + 8, y, b.z + 6, seed);
        Builders.lampPost(c, b.x - 5, y, b.z + 12, "post", "north");
        Builders.lampPost(c, b.x + 5, y, b.z + 12, "post", "north");
        souls.add(b.x - 14, y, b.z + 12);
        station(c, g, layout, stations, "base_camp", b.x + 10, b.z - 2, "south");
    }

    private void tent(VoxelCanvas c, int x, int y, int z, String wool) {
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                int h = 3 - Math.abs(dx);
                c.set(x + dx, y + h, z + dz, wool);
                if (Math.abs(dz) == 2 && Math.abs(dx) < 2) for (int yy = y + 1; yy < y + h; yy++) c.set(x + dx, yy, z + dz, dz == 2 && dx == 0 ? "air" : wool);
            }
        c.set(x, y + 4, z, "spruce_fence");
    }

    // ---- the Palace Bridge -------------------------------------------------------------------------------

    private void bridge(VoxelCanvas c, CaveGrid g, Layout layout) {
        District v = d("dwarven_village"), p = d("royal_palace");
        double ux = p.x - v.x, uz = p.z - v.z, len = Math.hypot(ux, uz);
        ux /= len; uz /= len;
        int sx = (int) Math.round(v.x + ux * (v.r * 0.7)), sz = (int) Math.round(v.z + uz * (v.r * 0.7));
        int ex = (int) Math.round(p.x - ux * (p.r * 0.62)), ez = (int) Math.round(p.z - uz * (p.r * 0.62));
        int sy = v.floor, ey = p.floor;
        Palette deck = Palette.of("stone_bricks:4,polished_andesite:2,cracked_stone_bricks:1");
        double blen = Math.hypot(ex - sx, ez - sz);
        double px = -uz, pz = ux;                           // perpendicular
        // deck first (rising one block at a time), then railings where no deck is
        java.util.Map<Long, Integer> deckY = new java.util.HashMap<>();
        for (double s = 0; s <= blen; s += 0.4) {
            double t = s / blen;
            int y = (int) Math.round(sy + (ey - sy) * Noise.smoothstep(0.1, 0.9, t));
            for (double w = -2.6; w <= 2.6; w += 0.4) {
                int x = (int) Math.round(sx + (ex - sx) * t + px * w), z = (int) Math.round(sz + (ez - sz) * t + pz * w);
                long k = ((long) x << 32) ^ (z & 0xffffffffL);
                Integer prev = deckY.get(k);
                if (prev != null && prev >= y) continue;
                deckY.put(k, y);
                c.set(x, y, z, deck.at(x, y, z));
                c.set(x, y - 1, z, Math.abs(w) > 2.2 ? "dark_oak_log[axis=y]" : "stone_bricks");
                for (int a = 1; a <= 4; a++) c.set(x, y + a, z, "air");
            }
        }
        for (double s = 0; s <= blen; s += 0.4) {
            double t = s / blen;
            for (int side : new int[]{-1, 1}) {
                double w = side * 3.4;
                int x = (int) Math.round(sx + (ex - sx) * t + px * w), z = (int) Math.round(sz + (ez - sz) * t + pz * w);
                long k = ((long) x << 32) ^ (z & 0xffffffffL);
                if (deckY.containsKey(k) || !c.isAir(x, sy + 1, z) && g.open(x, z) && g.region(x, z) != idx("palace_chasm") + 1) continue;
                int y = (int) Math.round(sy + (ey - sy) * Noise.smoothstep(0.1, 0.9, t));
                if (g.open(x, z) && g.region(x, z) != idx("palace_chasm") + 1) continue;   // on land: no railing needed
                c.set(x, y, z, "stone_bricks");
                c.set(x, y + 1, z, "stone_brick_wall");
                if (Math.round(s * 2.5) % 24 == 0) { c.set(x, y + 2, z, "stone_brick_wall"); c.set(x, y + 3, z, Blocks.lantern(false)); }
            }
        }
        int mx = (sx + ex) / 2, mz = (sz + ez) / 2, my = (sy + ey) / 2;
        for (int yy = d("palace_chasm").floor - 1; yy < my - 1; yy++)
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2 || yy > my - 4) c.set(mx + dx, yy, mz + dz, yy % 6 == 0 ? "chiseled_stone_bricks" : "stone_bricks");
        layout.zone("palace_bridge", "Palace Bridge", 'e', Box.of(Math.min(sx, ex) - 3, Math.min(sy, ey) - 2, Math.min(sz, ez) - 3, Math.max(sx, ex) + 3, Math.max(sy, ey) + 8, Math.max(sz, ez) + 3), null, Layout.Zone.SAFE);
    }

    // ---- mining districts --------------------------------------------------------------------------

    private void miningDistrict(VoxelCanvas c, CaveGrid g, Layout layout, District dd, MineKit.Souls souls, List<String> stations, long seed) {
        Random rnd = Hash.random(seed, "district" + dd.id);
        int reg = idx(dd.id) + 1;
        String wood = switch (dd.id) { case "lava_springs" -> "dark_oak"; case "great_ice_wall" -> "spruce"; case "goblin_burrows" -> "oak"; default -> "spruce"; };
        int platforms = 0, soulsHere = 0, props = 0;
        int wantSouls = dd.id.equals("the_mist") ? 1 : 1 + (dd.r > 38 ? 1 : 0);
        for (int i = 0; i < 400 && (platforms < 3 || soulsHere < wantSouls || props < 26); i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = dd.r * (0.15 + rnd.nextDouble() * 0.75);
            int x = dd.x + (int) Math.round(Math.cos(a) * r), z = dd.z + (int) Math.round(Math.sin(a) * r);
            if (!g.open(x, z) || g.region(x, z) != reg) continue;
            int f = g.floor(x, z);
            if (!MineKit.solid(c, x, f, z) || !c.isAir(x, f + 1, z) || !c.isAir(x, f + 2, z)) continue;
            if (soulsHere < wantSouls && r > dd.r * 0.5 && rnd.nextDouble() < 0.2) { souls.add(x, f, z); soulsHere++; continue; }
            if (platforms < 3 && !dd.id.equals("the_mist")) {
                boolean ok = true;
                for (int dx = -2; dx <= 1 && ok; dx++) for (int dz = -1; dz <= 1 && ok; dz++)
                    ok = g.open(x + dx, z + dz) && g.floor(x + dx, z + dz) == f && g.ceil(x + dx, z + dz) - f >= 10;
                if (ok) { MineKit.platform(c, x, f, z, 4 + rnd.nextInt(3), wood); platforms++; continue; }
            }
            if (props >= 26) continue;
            props++;
            switch (dd.id) {
                case "goblin_burrows" -> {
                    if (rnd.nextInt(3) == 0) tent(c, x, f, z, rnd.nextBoolean() ? "brown_wool" : "green_wool");
                    else if (rnd.nextBoolean()) c.set(x, f + 1, z, Blocks.campfire(true, "north"));
                    else MineKit.crates(c, x, f, z, seed + x);
                }
                case "lava_springs" -> {
                    // a lava pool sunk into the floor, rimmed in basalt
                    boolean ok = true;
                    for (int dx = -2; dx <= 2 && ok; dx++) for (int dz = -2; dz <= 2 && ok; dz++) ok = g.open(x + dx, z + dz) && g.floor(x + dx, z + dz) == f;
                    if (ok) {
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { c.set(x + dx, f, z + dz, "lava"); c.set(x + dx, f - 1, z + dz, "magma_block"); }
                        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (Math.abs(dx) == 2 || Math.abs(dz) == 2) c.set(x + dx, f, z + dz, "polished_basalt[axis=y]");
                    } else c.set(x, f + 1, z, "magma_block");
                }
                case "great_ice_wall" -> {
                    int h = 2 + rnd.nextInt(4);
                    for (int k = 1; k <= h && c.isAir(x, f + k, z); k++) c.set(x, f + k, z, k == h ? "blue_ice" : "packed_ice");
                    if (c.isAir(x + 1, f + 1, z) && g.open(x + 1, z) && g.floor(x + 1, z) == f) c.set(x + 1, f + 1, z, "snow[layers=2]");
                }
                case "the_mist" -> {
                    if (rnd.nextInt(3) == 0) c.set(x, f + 1, z, "cobweb");
                    else if (rnd.nextInt(4) == 0) c.set(x, f + 1, z, Blocks.soulLantern(false));
                    else c.set(x, f + 1, z, "dead_bush");
                }
                default -> {
                    if (rnd.nextInt(3) == 0) MineKit.crates(c, x, f, z, seed + z);
                    else if (rnd.nextBoolean()) Builders.lampPost(c, x, f, z, "torch", "north");
                    else c.set(x, f + 1, z, "cobblestone_wall");
                }
            }
        }
        if (STATIONS.contains(dd.id)) {
            boolean placed = false;
            for (int tries = 0; tries < 200 && !placed; tries++) {
                double a = rnd.nextDouble() * Math.PI * 2, rr = dd.r * (0.1 + 0.5 * Math.min(1, tries / 100.0));
                int x = dd.x + (int) Math.round(Math.cos(a) * rr), z = dd.z + (int) Math.round(Math.sin(a) * rr);
                String facing = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? "north" : "east";
                if (!stationFits(g, x, z, facing)) continue;
                station(c, g, layout, stations, dd.id, x, z, facing);
                placed = true;
            }
            if (!placed) station(c, g, layout, stations, dd.id, dd.x, dd.z, "north");
        }
    }

    /** Every minecart station, in menu order. Each has anchors npc.ticket.&lt;id&gt; and station.&lt;id&gt;. */
    public static final List<String> STATIONS = List.of("village", "forge", "palace", "royal_mines", "upper_mines", "gateway",
            "far_reserve", "lava_springs", "goblin_burrows", "ramparts_quarry", "cliffside_veins", "great_ice_wall", "base_camp");

    private boolean stationFits(CaveGrid g, int x, int z, String facing) {
        int fx = Blocks.dx(facing), fz = Blocks.dz(facing), px = Blocks.dx(Blocks.cw(facing)), pz = Blocks.dz(Blocks.cw(facing));
        if (!g.open(x, z)) return false;
        int y = g.floor(x, z);
        for (int i = -3; i <= 3; i++) for (int s = -1; s <= 3; s++) {
            int bx = x + fx * i + px * s, bz = z + fz * i + pz * s;
            if (!g.open(bx, bz) || g.floor(bx, bz) != y || g.ceil(bx, bz) - y < 6) return false;
        }
        return true;
    }

    // ---- wave 5: the skipped sub-areas ---------------------------------------------------------------

    /** The Lift's cage rests on this floor block and rises this many blocks up its shaft (the Deep Caverns side). */
    public static final int LIFT_RISE = 54;

    /**
     * The Lift (reference: "The Lift", beside the village spawn): the bottom of the lift shaft that comes down from
     * the Deep Caverns. A 7x7 stone tube with a cage floor and two chains climbs out through the cave roof; its
     * Lift Operator takes you back up. The ride itself is the module's: it reads {@code lift.dwarven.shaft}
     * (centre x, centre z, cage bottom y, cage top y) and lands you at {@code lift.dwarven}.
     */
    private void theLift(VoxelCanvas c, CaveGrid g, Layout layout, Reservations res, District v) {
        int y = v.floor;
        int[][] spots = {{30, 26}, {30, 30}, {31, 22}, {-24, 30}};   // offsets from the village centre, tried in order
        for (int[] s : spots) {
            int cx = v.x + s[0], cz = v.z + s[1];
            boolean ok = true;
            for (int dz = -4; dz <= 4 && ok; dz++)
                for (int dx = -7; dx <= 3 && ok; dx++)
                    ok = g.open(cx + dx, cz + dz) && g.floor(cx + dx, cz + dz) == y;
            if (!ok || !res.reserve("the_lift", Box.of(cx - 7, y - 1, cz - 4, cx + 3, y + LIFT_RISE + 4, cz + 4))) continue;
            int top = y + LIFT_RISE + 3;
            for (int yy = y + 1; yy <= top; yy++)
                for (int dz = -3; dz <= 3; dz++)
                    for (int dx = -3; dx <= 3; dx++) {
                        boolean ring = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                        boolean corner = Math.abs(dx) == 3 && Math.abs(dz) == 3;
                        if (yy == top) { c.set(cx + dx, yy, cz + dz, "stone_bricks"); continue; }
                        if (!ring) { c.set(cx + dx, yy, cz + dz, (dx == -2 && dz == -2 || dx == 2 && dz == 2) ? "iron_chain[axis=y]" : "air"); continue; }
                        if (dx == -3 && Math.abs(dz) <= 1 && yy <= y + 3) { c.set(cx + dx, yy, cz + dz, "air"); continue; }   // the door
                        String b;
                        if (corner) b = yy % 6 == 0 ? "chiseled_stone_bricks" : "stone_bricks";
                        else if (yy <= y + 7) b = yy == y + 4 || yy == y + 7 ? "polished_andesite" : "iron_bars";
                        else b = yy % 6 == 0 ? "polished_andesite" : "stone_bricks";
                        c.set(cx + dx, yy, cz + dz, b);
                    }
            for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) c.set(cx + dx, y, cz + dz, "spruce_planks");
            for (int dz = -1; dz <= 1; dz++) c.set(cx - 3, y + 4, cz + dz, "chiseled_stone_bricks");   // the lintel
            // the apron in front of the door
            for (int dz = -3; dz <= 3; dz++) for (int dx = -7; dx <= -4; dx++) c.set(cx + dx, y, cz + dz, Palette.of("polished_andesite:3,stone_bricks:2").at(cx + dx, y, cz + dz));
            Builders.lampPost(c, cx - 7, y, cz + 3, "street", "east");
            c.set(cx - 4, y + 1, cz - 3, "barrel[facing=up,open=false]");
            layout.standAt("npc.lift.dwarven", cx - 5, y, cz - 2, -90f);
            layout.standAt("lift.dwarven", cx - 5, y, cz, 90f);
            layout.data("lift.dwarven.shaft", cx + "," + cz + "," + (y + 1) + "," + (y + LIFT_RISE));
            layout.zone("the_lift", "The Lift", 'b', new Box(cx - 7, y - 1, cz - 4, cx + 3, y + 12, cz + 4), null, Layout.Zone.SAFE);
            return;
        }
    }

    /**
     * Gates to the Mines (reference sub-area): the village's south gate, where the portals home and up to the
     * Deep Caverns stand, under a deepslate gatehouse arch hung with banners. Its own SAFE zone names it.
     */
    private void gatesToTheMines(VoxelCanvas c, Layout layout, int vx, int y, int gz) {
        Palette stone = Palette.of("deepslate_bricks:4,polished_deepslate:1,cracked_deepslate_bricks:1");
        for (int s : new int[]{-1, 1})
            for (int k = 12; k <= 13; k++)
                for (int yy = y + 1; yy <= y + 9; yy++)
                    c.set(vx + s * k, yy, gz + 1, yy == y + 1 || yy == y + 9 ? "chiseled_deepslate" : stone.at(vx + s * k, yy, gz + 1));
        for (int x = -13; x <= 13; x++) {
            c.set(vx + x, y + 10, gz + 1, x == 0 ? "gold_block" : Math.abs(x) % 4 == 0 ? "chiseled_deepslate" : "deepslate_tiles");
            c.set(vx + x, y + 11, gz + 1, "deepslate_tile_slab[type=bottom]");
        }
        for (int x : new int[]{-10, -6, -2, 2, 6, 10}) c.set(vx + x, y + 10, gz, (x / 2) % 2 == 0 ? "red_wall_banner[facing=north]" : "yellow_wall_banner[facing=north]");
        MineKit.hangLantern(c, vx - 3, y + 10, gz + 2, 2, false);
        MineKit.hangLantern(c, vx + 3, y + 10, gz + 2, 2, false);
        layout.zone("gates_to_the_mines", "Gates to the Mines", 'b', Box.of(vx - 15, y - 1, gz - 3, vx + 15, y + 12, gz + 5), null, Layout.Zone.SAFE);
    }

    /**
     * The minecart company's depot over the village station (reference: "C&C Minecarts Co.", renamed): a timber
     * shed roof on four posts over the platform, every cart line starts here. Its own SAFE zone names it.
     */
    private void cartDepot(VoxelCanvas c, Layout layout, int x, int y, int z) {
        int x0 = x - 2, x1 = x + 4, z0 = z - 4, z1 = z + 4;
        for (int[] q : new int[][]{{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}})
            for (int yy = y + 1; yy <= y + 5; yy++) if (c.isAir(q[0], yy, q[1])) c.set(q[0], yy, q[1], "dark_oak_log[axis=y]");
        for (int xx = x0 - 1; xx <= x1 + 1; xx++)
            for (int zz = z0 - 1; zz <= z1 + 1; zz++) {
                boolean rim = xx == x0 - 1 || xx == x1 + 1 || zz == z0 - 1 || zz == z1 + 1;
                if (c.isAir(xx, y + 6, zz)) c.set(xx, y + 6, zz, rim ? "dark_oak_slab[type=bottom]" : "spruce_planks");
            }
        for (int xx = x0; xx <= x1; xx++) { c.set(xx, y + 5, z0, "dark_oak_log[axis=x]"); c.set(xx, y + 5, z1, "dark_oak_log[axis=x]"); }
        c.set(x, y + 5, z, "lantern[hanging=true,waterlogged=false]");
        c.set(x + 2, y + 5, z - 2, "lantern[hanging=true,waterlogged=false]");
        layout.zone("cart_depot", "Cart & Cable Co.", 'e', Box.of(x0 - 1, y - 1, z0 - 1, x1 + 1, y + 7, z1 + 1), null, Layout.Zone.SAFE);
    }

    /** Forge Basin (reference sub-area): a sunken working floor south of the Forge hall, three terraces deep. */
    private static int[] basinBox() {
        District f = d("the_forge");
        return new int[]{f.x - 10, f.z + 16, f.x + 10, f.z + 26};
    }

    private void forgeBasinPlan(CaveGrid g) {
        District f = d("the_forge");
        int reg = idx(f.id) + 1;
        int[] b = basinBox();
        for (int z = b[1]; z <= b[3]; z++)
            for (int x = b[0]; x <= b[2]; x++) {
                if (!g.open(x, z) || g.region(x, z) != reg) continue;
                int e = Math.min(Math.min(x - b[0], b[2] - x), Math.min(z - b[1], b[3] - z));
                int fl = f.floor - Math.min(3, e + 1);
                g.set(x, z, fl, Math.max(g.ceil(x, z), fl + 12), reg);
            }
    }

    private void forgeBasin(VoxelCanvas c, CaveGrid g, Layout layout) {
        District f = d("the_forge");
        int[] b = basinBox();
        int y0 = f.floor - 3, zc = (b[1] + b[3]) / 2;
        for (int z = b[1] + 2; z <= b[3] - 2; z++)
            for (int x = b[0] + 2; x <= b[2] - 2; x++)
                if (g.open(x, z) && g.floor(x, z) == y0) c.set(x, y0, z, (x + z) % 3 == 0 ? "polished_blackstone_bricks" : "polished_deepslate");
        int k = 0;
        for (int x = b[0] + 4; x <= b[2] - 4; x += 4, k++) {
            if (!g.open(x, zc) || g.floor(x, zc) != y0) continue;
            c.set(x, y0 + 1, zc, "lava_cauldron");                                   // crucibles
            MineKit.hangLantern(c, x, Math.min(g.ceil(x, zc), y0 + 9), zc, 3, false);
            if (g.open(x, zc - 3) && g.floor(x, zc - 3) == y0) c.set(x, y0 + 1, zc - 3, k % 2 == 0 ? "water_cauldron[level=3]" : "grindstone[face=floor,facing=north]");
            if (g.open(x, zc + 3) && g.floor(x, zc + 3) == y0) c.set(x, y0 + 1, zc + 3, k % 2 == 0 ? "anvil[facing=east]" : "blast_furnace[facing=north,lit=true]");
        }
        layout.zone("forge_basin", "Forge Basin", '6', new Box(b[0], y0 - 1, b[1], b[2], f.floor + 10, b[3]), null, Layout.Zone.SAFE);
    }

    // ---- wave 5: the cart lines ------------------------------------------------------------------------

    private static final java.util.Set<String> TRACK_BEDS = java.util.Set.of("stone_bricks", "polished_andesite", "cracked_stone_bricks",
            "chiseled_stone_bricks", "polished_deepslate", "deepslate_tiles", "deepslate_bricks", "polished_blackstone_bricks");
    private static final Palette TRACK_BED = Palette.of("stone_bricks:3,cracked_stone_bricks:1,polished_andesite:2");

    /**
     * Real track: a rail line from the depot (the village station) to every other station, planned by
     * {@link RailPlanner} over the finished cavern (it rolls only over solid floor with four blocks of air above,
     * never through a portal, a building, a reserved plaza or onto an NPC), then laid as vanilla rails on a
     * stone-brick bed (no mining block, so nobody can dig a rail loose). Each station's line is stored as
     * {@code rail.<station>} for the module's carts; a station the planner cannot reach keeps the old teleport.
     */
    private void cartLines(VoxelCanvas c, CaveGrid g, Layout layout, Reservations res) {
        int[] depot = stationGeo.get(DEPOT);
        if (depot == null) return;
        int chasm = idx("palace_chasm") + 1;
        java.util.Set<Long> blocked = new java.util.HashSet<>();
        for (Layout.Anchor a : layout.anchors().values()) {
            if (a.name().startsWith("fairy_soul.")) continue;
            int ax = (int) Math.floor(a.x()), az = (int) Math.floor(a.z());
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) blocked.add(key(ax + dx, az + dz));
        }
        for (java.util.Map.Entry<String, Box> e : layout.areas().entrySet()) {
            if (!e.getKey().startsWith("portal.")) continue;
            Box b = e.getValue();
            for (int z = b.minZ() - 2; z <= b.maxZ() + 2; z++) for (int x = b.minX() - 2; x <= b.maxX() + 2; x++) blocked.add(key(x, z));
        }
        // the station lanes themselves are laid by station(); keep other lines off them
        for (int[] geo : stationGeo.values()) for (int[] q : lane(geo).subList(0, 6)) blocked.add(key(q[0], q[2]));
        java.util.function.IntBinaryOperator floorAt = (x, z) -> {
            if (!g.open(x, z) || g.region(x, z) == chasm || blocked.contains(key(x, z)) || res.covers(x, z, 0)) return CaveGrid.NONE;
            int f = g.floor(x, z);
            if (!MineKit.solid(c, x, f, z)) return CaveGrid.NONE;
            for (int yy = f + 1; yy <= f + 4; yy++) if (!c.isAir(x, yy, z)) return CaveGrid.NONE;
            return f;
        };
        int[] open = new int[(MAX - MIN + 1) * (MAX - MIN + 1)];
        for (int z = MIN; z <= MAX; z++) for (int x = MIN; x <= MAX; x++) open[(z - MIN) * (MAX - MIN + 1) + (x - MIN)] = floorAt.applyAsInt(x, z);
        java.util.function.IntBinaryOperator cached = (x, z) -> x < MIN || z < MIN || x > MAX || z > MAX ? CaveGrid.NONE : open[(z - MIN) * (MAX - MIN + 1) + (x - MIN)];
        java.util.function.IntBinaryOperator hug = (x, z) -> {
            int n = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) if ((dx != 0 || dz != 0) && cached.applyAsInt(x + dx, z + dz) == CaveGrid.NONE) n++;
            return n * 4;
        };
        RailPlanner planner = new RailPlanner(MIN, MIN, MAX, MAX, cached, hug);

        List<int[]> depotLane = lane(depot);
        int[] dThroat = depotLane.get(6);
        List<String> ids = new ArrayList<>();
        List<RailPlanner.Target> targets = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : stationGeo.entrySet()) {
            if (e.getKey().equals(DEPOT)) continue;
            int[] t = lane(e.getValue()).get(6);
            ids.add(e.getKey());
            targets.add(new RailPlanner.Target(e.getKey(), t[0], t[2], e.getValue()[3], e.getValue()[1]));
        }
        // the depot throat must be open for any line to leave; its own lane is blocked, so open it explicitly
        List<List<int[]>> lines = planner.plan(dThroat[0], dThroat[2], (depot[3] + 2) % 4, targets);
        List<String> missing = new ArrayList<>();
        java.util.Set<Long> laid = new java.util.HashSet<>();
        List<int[]> depotHead = new ArrayList<>(depotLane.subList(0, 6));
        layout.data("rail." + DEPOT, RailRoute.encode(List.of(depotLane.get(0))));
        for (int i = 0; i < ids.size(); i++) {
            List<int[]> mid = lines.get(i);
            if (mid == null) { missing.add(ids.get(i)); continue; }
            List<int[]> full = new ArrayList<>(depotHead);
            full.addAll(mid);
            List<int[]> tl = lane(stationGeo.get(ids.get(i)));
            for (int j = 5; j >= 0; j--) full.add(tl.get(j));
            String enc = RailRoute.encode(full);
            if (enc == null) { missing.add(ids.get(i)); continue; }
            layout.data("rail." + ids.get(i), enc);
            for (int j = 0; j < full.size(); j++) {
                int[] q = full.get(j);
                if (!laid.add(key(q[0], q[2]))) continue;
                String bed = c.get(q[0], q[1], q[2]);
                String bedId = bed == null ? "" : bed.contains("[") ? bed.substring(0, bed.indexOf('[')) : bed;
                if (!TRACK_BEDS.contains(bedId.replace("minecraft:", ""))) c.set(q[0], q[1], q[2], TRACK_BED.at(q[0], q[1], q[2]));
                c.set(q[0], q[1] + 1, q[2], "rail[shape=" + RailRoute.shape(full, j) + ",waterlogged=false]");
            }
        }
        layout.data("rail.depot", DEPOT);
        layout.data("rail.missing", String.join(",", missing));
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
