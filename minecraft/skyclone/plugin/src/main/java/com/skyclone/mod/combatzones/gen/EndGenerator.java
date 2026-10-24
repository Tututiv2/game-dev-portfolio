package com.skyclone.mod.combatzones.gen;

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
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The End: a floating archipelago of end stone. The main island holds the End Gate (spawn and
 * the trading post), obsidian pillars and purpur ruins, a hill hollowed into the Bruiser's
 * Hollow, the Protector's Field quarry, and the Dragon's Nest: a crater whose altar takes eight
 * Summoning Eyes, ringed by six crystal towers. Bridges lead to the Null Tomb (high-level void
 * spawn) and the Void Ledge. Pure: no Bukkit.
 */
public final class EndGenerator implements IslandGenerator {

    public static final String ID = "the_end";
    /** Nest centre (the dragon's podium). */
    public static final int NX = 52, NZ = -42;
    /** The crater floor the Zealots walk on. */
    public static final int FLOOR_Y = 60;

    /*
     * Warp arrivals (playtest 2026-10-03: /warp drag stood the player 2.8 blocks from a Lv55 Zealot,
     * which killed an 844 HP player in ~3 s). Each arrival is a small SAFE pad (core spawns nothing in it,
     * returns any mob that walks in, and lets none target or hit a player standing in it), and the mob
     * anchors round it keep ARRIVAL_CLEAR blocks off the anchor so nothing aggros (16) on arrival.
     * Same fix class as the hub's crypt_landing.
     */
    /** /warp drag: high on the west ramp into the crater, looking down at the dais. */
    public static final int DRAG_X = NX - 40, DRAG_Z = NZ;
    /** /warp void: the Null Tomb plaza, just inside the arch. */
    public static final int VOID_X = -118, VOID_Z = -114;
    /** Mob anchors stay this far (horizontal) from an arrival anchor. */
    public static final int ARRIVAL_CLEAR = 18;

    /** The SAFE pad round warp.drag: the upper ramp, x NX-48..NX-33 (the crater floor starts at NX-33). */
    public static Box dragLanding() { return new Box(NX - 48, FLOOR_Y - 3, NZ - 6, NX - 33, FLOOR_Y + 40, NZ + 6); }

    /** The SAFE pad round warp.void. */
    public static Box voidLanding() { return new Box(VOID_X - 5, 50, VOID_Z - 5, VOID_X + 5, 130, VOID_Z + 5); }

    /** True when a mob anchor at (x, z) is too close to either warp arrival. */
    public static boolean nearArrival(int x, int z) {
        return Math.hypot(x - DRAG_X, z - DRAG_Z) < ARRIVAL_CLEAR || Math.hypot(x - VOID_X, z - VOID_Z) < ARRIVAL_CLEAR;
    }

    /** Vanilla block interaction range (survival), from the eye. */
    public static final double REACH = 4.5;
    /** Standing eye height. */
    public static final double EYE = 1.62;

    /** "Is there a block here" for {@link #altarReachProblem}: the generator canvas in tests, the live world in game. */
    @FunctionalInterface
    public interface Solid { boolean at(int x, int y, int z); }

    /**
     * Pure check shared by the self-test and the unit test (playtest wave4-endgame: from the old centre
     * pillar 4 of the 8 frames were out of reach). Null when, from the {@code altar.dragon} anchor, every
     * frame's centre is within {@link #REACH} of the eye with a clear line to its top face, and from
     * every one of the pedestal's 9 blocks every frame's nearest point is within reach with a clear line.
     */
    public static String altarReachProblem(Layout l, Solid solid) {
        Layout.Anchor a = l.anchor("altar.dragon");
        if (a == null) return "altar.dragon missing";
        String fr = l.data().get("altar.dragon.frames");
        if (fr == null) return "altar.dragon.frames missing";
        List<int[]> frames = new ArrayList<>();
        for (String s : fr.split(";")) {
            String[] p = s.split(",");
            frames.add(new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])});
        }
        StringBuilder bad = new StringBuilder();
        if (solid.at((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()))
                || solid.at((int) Math.floor(a.x()), (int) Math.floor(a.y()) + 1, (int) Math.floor(a.z())))
            bad.append("altar.dragon stands inside a block; ");
        if (!solid.at((int) Math.floor(a.x()), (int) Math.floor(a.y()) - 1, (int) Math.floor(a.z())))
            bad.append("altar.dragon stands on air; ");
        double ex = a.x(), ey = a.y() + EYE, ez = a.z();
        for (int[] f : frames) {
            double d = Math.sqrt(sq(f[0] + 0.5 - ex) + sq(f[1] + 0.5 - ey) + sq(f[2] + 0.5 - ez));
            if (d > REACH) bad.append("frame ").append(f[0]).append(',').append(f[1]).append(',').append(f[2])
                    .append(" is ").append(String.format("%.2f", d)).append(" from the altar anchor; ");
            if (!clear(solid, ex, ey, ez, f)) bad.append("frame ").append(f[0]).append(',').append(f[1]).append(',').append(f[2])
                    .append(" hidden from the altar anchor; ");
        }
        int px = (int) Math.floor(a.x()), pz = (int) Math.floor(a.z());
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                double sx = px + dx + 0.5, sz = pz + dz + 0.5, sy = a.y() + EYE;
                for (int[] f : frames) {
                    double nx = Math.max(f[0], Math.min(f[0] + 1, sx)), nz = Math.max(f[2], Math.min(f[2] + 1, sz));
                    double ny = f[1] + 0.8125;                                            // the frame's top face
                    double d = Math.sqrt(sq(nx - sx) + sq(ny - sy) + sq(nz - sz));
                    if (d > REACH || !clear(solid, sx, sy, sz, f))
                        bad.append("from pedestal ").append(dx).append(',').append(dz).append(" frame ")
                                .append(f[0]).append(',').append(f[2]).append(d > REACH ? String.format(" %.2f away", d) : " hidden").append("; ");
                }
            }
        return bad.length() == 0 ? null : bad.toString();
    }

    private static double sq(double v) { return v * v; }

    /** A straight line from the eye to the centre of the frame's top face meets no block but the frame. */
    private static boolean clear(Solid solid, double ex, double ey, double ez, int[] f) {
        double tx = f[0] + 0.5, ty = f[1] + 0.8125, tz = f[2] + 0.5;
        double len = Math.sqrt(sq(tx - ex) + sq(ty - ey) + sq(tz - ez));
        int steps = (int) Math.ceil(len / 0.05);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int bx = (int) Math.floor(ex + (tx - ex) * t), by = (int) Math.floor(ey + (ty - ey) * t), bz = (int) Math.floor(ez + (tz - ez) * t);
            if (bx == f[0] && by == f[1] && bz == f[2]) return true;
            if (solid.at(bx, by, bz)) return false;
        }
        return true;
    }

    /**
     * Pure check shared by the self-test and the unit test: null when every warp arrival stands in a SAFE
     * zone that does not cover the fighting ground, and no mob anchor is within ARRIVAL_CLEAR of it.
     */
    public static String arrivalProblem(Layout l) {
        StringBuilder bad = new StringBuilder();
        for (String w : new String[]{"warp.drag", "warp.void"}) {
            Layout.Anchor a = l.anchor(w);
            if (a == null) { bad.append(w).append(" missing; "); continue; }
            Layout.Zone safe = null;
            long best = Long.MAX_VALUE;
            for (Layout.Zone z : l.zones())
                if (z.box().contains(a.x(), a.y() + 1, a.z()) && z.box().volume() < best) { best = z.box().volume(); safe = z; }
            if (safe == null || safe.flags() == null || !safe.flags().contains(Layout.Zone.SAFE))
                bad.append(w).append(" lands in ").append(safe == null ? "no zone" : safe.id()).append(" (not SAFE); ");
            else if (safe.box().volume() > 40_000) bad.append(w).append(" SAFE zone ").append(safe.id()).append(" is too big; ");
            for (Map.Entry<String, Layout.Anchor> e : l.anchors().entrySet()) {
                if (!e.getKey().startsWith("spawn.mob.")) continue;
                Layout.Anchor m = e.getValue();
                double d = Math.hypot(m.x() - a.x(), m.z() - a.z());
                if (d < ARRIVAL_CLEAR) bad.append(e.getKey()).append(" is ").append(Math.round(d)).append(" from ").append(w).append("; ");
            }
        }
        return bad.length() == 0 ? null : bad.toString();
    }

    private final String hollowReq, tombReq;

    public EndGenerator() { this("skill:combat:20", "skill:combat:25"); }

    /** Zone entry requirements (dials in game; blank = none). */
    public EndGenerator(String hollowRequirement, String tombRequirement) {
        this.hollowReq = hollowRequirement == null || hollowRequirement.isBlank() ? null : hollowRequirement;
        this.tombReq = tombRequirement == null || tombRequirement.isBlank() ? null : tombRequirement;
    }

    @Override public String islandId() { return ID; }

    // v2: no invalid cracked_end_stone_bricks (integrator)
    // v3: SAFE arrival pads for /warp drag (upper west ramp) and /warp void; mob anchors kept ARRIVAL_CLEAR off them
    // v4: flat altar pedestal (no centre post / dragon head), altar.dragon stands on it: every frame in reach
    // v5 (wave 5): the Void Ledge pillar is the Draconic Altar - end rods + crying obsidian, layout data "altar.draconic"
    @Override public int version() { return 5; }

    @Override public Box bounds() { return new Box(-215, 0, -215, 215, 200, 215); }

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        // ---------------------------------------------------------------- 1. islands
        Terrain.IslandShape shape = new Terrain.IslandShape(seed);
        shape.radiusX = 132; shape.radiusZ = 118; shape.baseY = 70;
        shape.hills = 10; shape.hillScale = 34; shape.mountains = 14; shape.mountainScale = 55; shape.cliff = 0.75; shape.coastNoise = 0.2;
        shape.undersideDepth = 46; shape.undersideNoise = 0.6; shape.coastDrop = 3;
        Terrain.Heightmap h = Terrain.island(shape);

        Terrain.IslandShape tombShape = new Terrain.IslandShape(seed ^ 0x70B);
        tombShape.centerX = -118; tombShape.centerZ = -122; tombShape.radiusX = 34; tombShape.radiusZ = 30; tombShape.baseY = 74;
        tombShape.hills = 3; tombShape.cliff = 0.8; tombShape.undersideDepth = 30; tombShape.coastNoise = 0.15;
        Terrain.Heightmap tomb = Terrain.island(tombShape);

        Terrain.IslandShape ledgeShape = new Terrain.IslandShape(seed ^ 0x51A);
        ledgeShape.centerX = -172; ledgeShape.centerZ = 22; ledgeShape.radiusX = 20; ledgeShape.radiusZ = 17; ledgeShape.baseY = 68;
        ledgeShape.hills = 2; ledgeShape.cliff = 0.8; ledgeShape.undersideDepth = 22; ledgeShape.coastNoise = 0.15;
        Terrain.Heightmap ledge = Terrain.island(ledgeShape);

        // ---------------------------------------------------------------- 2. the nest crater
        final int floorY = FLOOR_Y, rimY = 77;
        for (int z = NZ - 56; z <= NZ + 56; z++)
            for (int x = NX - 56; x <= NX + 56; x++) {
                if (!h.has(x, z)) continue;
                double d = Math.hypot(x - NX, z - NZ);
                int t = h.top(x, z);
                if (d <= 33) h.setTop(x, z, floorY);
                else if (d <= 43) h.setTop(x, z, (int) Math.round(floorY + (rimY - floorY) * Noise.smoothstep(33, 43, d)));
                else if (d <= 54) h.setTop(x, z, (int) Math.round(rimY + (t - rimY) * Noise.smoothstep(43, 54, d)));
            }
        // two ramps down into the crater: from the west (spawn side) and from the south
        ramp(h, NX - 58, NZ, NX - 30, NZ, floorY, 3);
        ramp(h, NX, NZ + 58, NX, NZ + 30, floorY, 3);

        // ---------------------------------------------------------------- 3. plots and hills
        Box gatePlot = Box.of(-100, 0, 46, -72, 0, 70);
        int gateY = h.average(gatePlot);
        h.flatten(gatePlot, gateY, 7);
        final int hx = 106, hz = 26;                       // bruiser hill
        Kit.bump(h, hx, hz, 36, 27);
        Box fieldPlot = Box.of(30, 0, 58, 76, 0, 92);
        int fieldY = h.average(fieldPlot);
        h.flatten(fieldPlot, fieldY, 8);
        Box tombPlaza = Box.of(-130, 0, -134, -106, 0, -110);
        int tombY = tomb.average(tombPlaza);
        tomb.flatten(tombPlaza, tombY, 6);
        Box ledgePlot = Box.of(-180, 0, 14, -164, 0, 30);
        int ledgeY = ledge.average(ledgePlot);
        ledge.flatten(ledgePlot, ledgeY, 4);

        // ---------------------------------------------------------------- 4. terrain
        Terrain.SurfaceRules rules = new Terrain.SurfaceRules();
        Palette endTop = Palette.of("end_stone:14,end_stone_bricks:1");
        rules.top = endTop;
        rules.soil = Fill.of("end_stone");
        rules.stone = Fill.of("end_stone");
        rules.cliff = Palette.of("end_stone:10,end_stone_bricks:1");
        rules.underside = Palette.of("end_stone:10,obsidian:1");
        Terrain.fill(c, h, rules);
        Terrain.SurfaceRules dark = new Terrain.SurfaceRules();
        dark.top = Palette.of("blackstone:4,end_stone:3,polished_blackstone:1,crying_obsidian:1");
        dark.soil = Palette.of("blackstone:3,end_stone:2");
        dark.stone = Palette.of("end_stone:3,blackstone:2,obsidian:1");
        dark.cliff = Palette.of("blackstone:3,end_stone:3,obsidian:1");
        dark.underside = Palette.of("end_stone:4,obsidian:2,blackstone:2");
        Terrain.fill(c, tomb, dark);
        Terrain.fill(c, ledge, rules);

        Reservations res = new Reservations();

        // ---------------------------------------------------------------- 5. carving: the hollow
        int hollowY = Kit.ground(c, hx - 38, hz, 160, 70);
        Palette hollowFloor = Palette.of("end_stone_bricks:3,end_stone:3,obsidian:1");
        Kit.tunnel(c, new double[][]{{hx - 38, hollowY, hz}, {hx - 26, hollowY, hz + 2}, {hx - 13, hollowY, hz}}, 3.2, 6, hollowFloor);
        Kit.cavern(c, hx, hollowY, hz, 15, 11, hollowFloor, seed);
        res.reserve("hollow", Box.of(hx - 22, hollowY - 2, hz - 22, hx + 22, hollowY + 16, hz + 22));
        for (int[] lp : new int[][]{{-8, -8}, {8, -8}, {-8, 8}, {8, 8}, {0, 0}})
            Kit.hangingLantern(c, hx + lp[0], DenGenerator.topAir(c, hx + lp[0], hollowY, hz + lp[1]) + 1, hz + lp[1], 2, true);
        for (int[] cr : new int[][]{{-12, 4}, {11, -6}, {4, 12}, {-5, -12}})       // obsidian rubble inside
            Kit.spike(c, hx + cr[0], hz + cr[1], 2.2, hollowY + 4, Palette.of("obsidian:3,crying_obsidian:1"));

        // ---------------------------------------------------------------- 6. the Dragon's Nest
        res.reserve("nest", Box.of(NX - 34, floorY - 2, NZ - 34, NX + 34, floorY + 40, NZ + 34));
        Palette nestFloor = Palette.of("end_stone:6,end_stone_bricks:2,obsidian:1");
        for (int z = NZ - 32; z <= NZ + 32; z++)
            for (int x = NX - 32; x <= NX + 32; x++) {
                double d = Math.hypot(x - NX, z - NZ);
                if (d > 32) continue;
                if (d > 9 && d < 11) c.set(x, floorY, z, "obsidian");             // a dark ring around the dais
                else if (Hash.chance(Hash.salt(seed, "nf"), x, z, 0.08)) c.set(x, floorY, z, nestFloor.at(x, floorY, z));
            }
        // the altar dais: obsidian with eight eye frames facing inward
        for (int z = NZ - 5; z <= NZ + 5; z++)
            for (int x = NX - 5; x <= NX + 5; x++) {
                boolean edge = Math.abs(x - NX) == 5 || Math.abs(z - NZ) == 5;
                c.set(x, floorY + 1, z, edge ? Blocks.slab("polished_blackstone_brick", "bottom") : "obsidian");
            }
        for (int[] f : FRAMES) {
            String facing = f[0] > 1 ? "west" : f[0] < -1 ? "east" : f[1] > 1 ? "north" : "south";
            c.set(NX + f[0], floorY + 2, NZ + f[1], "end_portal_frame[eye=false,facing=" + facing + "]");
        }
        // the pedestal: a flat 3x3 of bedrock one block up, standing room for whoever fills the altar.
        // wave4-endgame: it used to carry a bedrock post + dragon head in the middle, which blocked the view of
        // the far frames, so from the pedestal 4 of the 8 were out of reach. Flat, every frame is within reach
        // and in plain view from any of its 9 blocks (altarReachProblem).
        for (int z = NZ - 1; z <= NZ + 1; z++)
            for (int x = NX - 1; x <= NX + 1; x++) c.set(x, floorY + 2, z, "bedrock");
        for (int[] t : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) {
            c.set(NX + t[0], floorY + 2, NZ + t[1], "end_stone_brick_wall");
            c.set(NX + t[0], floorY + 3, NZ + t[1], "end_rod[facing=up]");
        }
        // six crystal towers on the crater floor
        int[] heights = {22, 26, 19, 28, 21, 25};
        for (int i = 0; i < 6; i++) {
            double a = i / 6.0 * Math.PI * 2 + 0.5;
            int x = NX + (int) Math.round(Math.sin(a) * 26), z = NZ + (int) Math.round(Math.cos(a) * 26);
            int top = floorY + heights[i];
            Shapes.cylinder(c, x, floorY - 3, z, 2.6, heights[i] + 4, Palette.of("obsidian:8,crying_obsidian:1"), 0);
            c.set(x, top + 1, z, "bedrock");
            if (i % 2 == 0) {                                                         // caged tops
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) != 2 && Math.abs(dz) != 2 || Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                        for (int k = 1; k <= 4; k++) c.set(x + dx, top + k, z + dz, "iron_bars");
                    }
            }
            layout.standAt("crystal." + (i + 1), x, top + 1, z, 0f);
        }

        // ---------------------------------------------------------------- 7. the End Gate (spawn)
        res.reserve("gate_portal", Box.of(-92, gateY - 3, 68, -80, gateY + 8, 78));
        Palette pave = Palette.of("end_stone_bricks:5,purpur_block:2,end_stone:1");
        for (int z = gatePlot.minZ(); z <= gatePlot.maxZ(); z++)
            for (int x = gatePlot.minX(); x <= gatePlot.maxX(); x++) c.set(x, gateY, z, pave.at(x, gateY, z));
        Palette purpur = Palette.of("purpur_block:5,purpur_pillar[axis=y]:1,end_stone_bricks:2");
        Kit.portal(c, layout, "portal.hub", -86, gateY, 73, true, 3, purpur, "nether_portal", pave);
        c.set(-86, gateY + 6, 73, "end_rod[facing=up]");
        // the trading post (pearl dealer) and a ruined purpur tower as the landmark
        Builders.House post = new Builders.House(-99, gateY, 47, 9, 7);
        post.door = "east"; post.roof = "purpur"; post.roofFull = "purpur_block"; post.roofStyle = "hip";
        post.wall = Palette.of("end_stone_bricks:5,purpur_block:1"); post.frame = "purpur_pillar"; post.floor = Fill.of("purpur_block");
        post.window = "magenta_stained_glass_pane"; post.doorWood = "dark_oak"; post.seed = seed;
        if (res.reserve("post", post.reserve())) post.build(c);
        Builders.Tower tower = new Builders.Tower(-77, gateY, 49);
        tower.radius = 4; tower.height = 22; tower.top = "battlements"; tower.door = "south";
        tower.wall = Palette.of("purpur_block:5,end_stone_bricks:3,purpur_pillar[axis=y]:1"); tower.floor = Fill.of("purpur_block");
        tower.window = "magenta_stained_glass_pane";
        if (res.reserve("tower", tower.reserve())) tower.build(c);
        for (int[] lp : new int[][]{{-99, 69}, {-73, 69}, {-73, 58}, {-100, 58}})
            endLamp(c, lp[0], gateY, lp[1]);
        Builders.bench(c, -90, gateY, 60, "east", 3, "dark_oak");
        Builders.crates(c, -80, gateY, 66, seed);

        // ---------------------------------------------------------------- 8. Protector's Field (quarry + arena)
        res.reserve("field", Box.of(fieldPlot.minX(), fieldY - 10, fieldPlot.minZ(), fieldPlot.maxX(), fieldY + 12, fieldPlot.maxZ()));
        // an end stone quarry pit on the east half, with a stepped way down on its west side
        for (int z = 66; z <= 84; z++)
            for (int x = 62; x <= 70; x++)
                for (int y = fieldY - 5; y <= fieldY + 3; y++) c.set(x, y, z, y == fieldY - 5 ? "end_stone" : "air");
        for (int i = 0; i < 5; i++)
            for (int z = 74; z <= 76; z++) {
                int x = 62 + i;
                for (int y = fieldY - 5; y <= fieldY - 1 - i; y++) c.set(x, y, z, "end_stone_bricks");
            }
        // the arena: a ring of end stone brick posts with end rods
        for (int a = 0; a < 360; a += 30) {
            int x = 42 + (int) Math.round(Math.sin(Math.toRadians(a)) * 10), z = 75 + (int) Math.round(Math.cos(Math.toRadians(a)) * 10);
            c.set(x, fieldY + 1, z, "end_stone_brick_wall");
            c.set(x, fieldY + 2, z, "end_stone_brick_wall");
            c.set(x, fieldY + 3, z, "end_rod[facing=up]");
        }

        // ---------------------------------------------------------------- 9. the Null Tomb
        res.reserve("tomb", Box.of(-152, tombY - 4, -152, -84, tombY + 20, -92));
        Palette tombPave = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:2,blackstone:1,gilded_blackstone:0");
        for (int z = tombPlaza.minZ(); z <= tombPlaza.maxZ(); z++)
            for (int x = tombPlaza.minX(); x <= tombPlaza.maxX(); x++) c.set(x, tombY, z, tombPave.at(x, tombY, z));
        java.util.Random tr = Hash.random(seed, "tomb");
        for (int i = 0; i < 16; i++) {                                    // gravestones in rows
            int x = -128 + (i % 4) * 6, z = -131 + (i / 4) * 6;
            c.set(x, tombY + 1, z, "polished_blackstone_wall");
            c.set(x, tombY + 2, z, tr.nextInt(3) == 0 ? "chiseled_polished_blackstone" : "polished_blackstone_wall");
        }
        for (int[] o : new int[][]{{-131, -137}, {-103, -137}, {-131, -107}, {-103, -107}}) {
            Kit.spike(c, o[0], o[1], 2.4, tombY + 12, Palette.of("obsidian:4,crying_obsidian:1"));
            c.set(o[0], tombY + 13, o[1], "soul_lantern[hanging=false,waterlogged=false]");
        }
        Shapes.arch(c, -122, -109, -112, -109, tombY + 1, 9, 2, 2, Palette.of("polished_blackstone_bricks:3,obsidian:1"));

        // ---------------------------------------------------------------- 10. the Void Ledge
        res.reserve("ledge", Box.of(ledgePlot.minX() - 3, ledgeY - 3, ledgePlot.minZ() - 3, ledgePlot.maxX() + 3, ledgeY + 8, ledgePlot.maxZ() + 3));
        for (int z = ledgePlot.minZ(); z <= ledgePlot.maxZ(); z++)
            for (int x = ledgePlot.minX(); x <= ledgePlot.maxX(); x++) c.set(x, ledgeY, z, Palette.of("purpur_block:2,end_stone_bricks:3").at(x, ledgeY, z));
        Shapes.cylinder(c, -172, ledgeY + 1, 22, 2.5, 2, Palette.of("purpur_pillar[axis=y]"), 0);
        c.set(-172, ledgeY + 3, 22, "dragon_head[rotation=4]");
        // the Draconic Altar (wave 5): a crying-obsidian offering ring around the head, four end rods as candles.
        // Right-clicking any block of it opens Keeper Ixo's altar (DragonEssence.onAltarClick reads "altar.draconic").
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) c.set(-172 + o[0], ledgeY + 2, 22 + o[1], "crying_obsidian");
        for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) c.set(-172 + o[0], ledgeY + 3, 22 + o[1], "end_rod[facing=up]");
        layout.data("altar.draconic", (-172) + "," + (ledgeY + 1) + "," + 22);
        for (int[] lp : new int[][]{{-179, 15}, {-165, 15}, {-179, 29}, {-165, 29}}) endLamp(c, lp[0], ledgeY, lp[1]);

        // ---------------------------------------------------------------- 11. bridges and roads
        Palette deck = Palette.of("end_stone_bricks:4,purpur_block:1");
        int b1y = Kit.ground(c, -62, -62, 160, 70);
        Builders.bridge(c, -62, b1y, -62, -107, tombY, -107, 3, deck, "end_stone_brick_wall", "purpur_pillar", "end_stone_brick_stairs");
        int b3y = Kit.ground(c, -122, 20, 160, 70);
        Builders.bridge(c, -122, b3y, 20, -165, ledgeY, 22, 3, deck, "end_stone_brick_wall", "purpur_pillar", "end_stone_brick_stairs");
        Palette road = Palette.of("end_stone_bricks:6,end_stone:3,purpur_block:1");
        Builders.PathResult paths = Builders.path(c, new int[][]{{-80, 46}, {-60, 30}, {-34, 14}, {-12, 4}, {NX - 58, NZ}, {NX - 40, NZ}, {NX - 30, NZ}},
                4, road, "end_stone_brick_stairs", null);
        Builders.path(c, new int[][]{{-34, 14}, {-30, 40}, {-6, 64}, {18, 72}, {29, 74}}, 4, road, "end_stone_brick_stairs", paths);
        Builders.path(c, new int[][]{{18, 72}, {30, 40}, {NX, NZ + 58}, {NX, NZ + 30}}, 4, road, "end_stone_brick_stairs", paths);
        Builders.path(c, new int[][]{{30, 40}, {56, 30}, {hx - 44, hz}, {hx - 38, hz}}, 4, road, "end_stone_brick_stairs", paths);
        Builders.path(c, new int[][]{{-34, 14}, {-52, -20}, {-58, -48}, {-61, -61}}, 4, road, "end_stone_brick_stairs", paths);
        Builders.path(c, new int[][]{{-60, 30}, {-96, 26}, {-121, 20}}, 4, road, "end_stone_brick_stairs", paths);
        DenGenerator.lampsAlongStyle(c, new int[][]{{-80, 46}, {-60, 30}, {-34, 14}, {-12, 4}, {NX - 58, NZ}}, 20, 4, paths, res, true);
        DenGenerator.lampsAlongStyle(c, new int[][]{{-34, 14}, {-30, 40}, {-6, 64}, {18, 72}, {30, 40}, {56, 30}, {hx - 44, hz}}, 20, 4, paths, res, true);

        // ---------------------------------------------------------------- 12. pillars, ruins, chorus
        Kit.Keepout keep = (x, z) -> res.covers(x, z, 5) || paths.near(x, z, 4);
        java.util.Random pr = Hash.random(seed, "pillars");
        int placed = 0;
        List<int[]> pillarFeet = new ArrayList<>();
        for (int i = 0; i < 60 && placed < 9; i++) {
            int x = -120 + pr.nextInt(240), z = -105 + pr.nextInt(210);
            if (!h.inland(x, z, 6) || keep.blocked(x, z) || Math.hypot(x - NX, z - NZ) < 58) continue;
            boolean close = false;
            for (int[] o : pillarFeet) if (Math.hypot(o[0] - x, o[1] - z) < 34) close = true;
            if (close) continue;
            int g = h.top(x, z);
            int hgt = 12 + pr.nextInt(22);
            Shapes.cylinder(c, x, g - 4, z, 2.2 + pr.nextDouble() * 1.4, hgt + 4, Palette.of("obsidian:9,crying_obsidian:1"), 0);
            if (pr.nextBoolean()) c.set(x, g + hgt, z, "end_rod[facing=up]");
            pillarFeet.add(new int[]{x, z});
            placed++;
        }
        for (int i = 0; i < 7; i++) {                                     // purpur ruins: broken walls and a fallen column
            int x = -110 + pr.nextInt(220), z = -100 + pr.nextInt(200);
            if (!h.inland(x, z, 6) || keep.blocked(x, z) || Math.hypot(x - NX, z - NZ) < 56) continue;
            ruin(c, x, h.top(x, z), z, pr);
        }
        h.forEach((x, z, top, bottom) -> {                                // chorus groves
            if (keep.blocked(x, z) || Math.hypot(x - NX, z - NZ) < 50) return;
            if (!Hash.chance(Hash.salt(seed, "chorus"), x, z, 0.006)) return;
            if (!c.isAir(x, top + 1, z)) return;
            chorus(c, x, top, z, Hash.random(seed, "ch" + x + "," + z));
        });
        Terrain.scatter(c, h, seed, 0.004, Fill.of("end_rod[facing=up]"), "end_stone");
        Terrain.scatter(c, tomb, seed, 0.03, Palette.of("wither_rose:1,dead_bush:2"), "blackstone");

        Kit.despeck(c, new Box(hx - 40, hollowY - 2, hz - 40, hx + 40, hollowY + 40, hz + 40));
        Kit.despeck(c, new Box(-130, 60, -110, 130, 120, 110));

        // ---------------------------------------------------------------- 13. anchors
        layout.standAt("spawn", -86, gateY, 64, 180f);
        if (post.inside != null) layout.standAt("npc.pearl_dealer", post.inside[0], post.inside[1], post.inside[2], 90f);
        else layout.standAt("npc.pearl_dealer", -94, gateY, 60, 90f);
        layout.standAt("npc.end_archer", -76, gateY, 58, 180f);
        layout.standAt("npc.end_guide", -92, gateY, 56, 90f);
        layout.standAt("npc.lone_adventurer", -63, Kit.ground(c, -63, 26, 160, 70), 26, 90f);
        layout.standAt("npc.void_keeper", -168, ledgeY, 18, 135f);
        layout.standAt("altar.dragon", NX, floorY + 2, NZ, 180f);                  // on the pedestal: all 8 frames in reach
        layout.data("altar.dragon.center", NX + "," + (floorY + 2) + "," + NZ);
        StringBuilder fr = new StringBuilder();
        for (int[] f : FRAMES) fr.append(fr.length() == 0 ? "" : ";").append(NX + f[0]).append(',').append(floorY + 2).append(',').append(NZ + f[1]);
        layout.data("altar.dragon.frames", fr.toString());
        layout.standAt("boss.dragon", NX, floorY + 22, NZ, 0f);
        layout.standAt("boss.end_stone_protector", 42, fieldY, 75, 0f);
        // arrivals stand on SAFE pads clear of every mob anchor (see dragLanding / voidLanding / arrivalProblem)
        int dragY = Kit.ground(c, DRAG_X, DRAG_Z, rimY + 20, floorY + 8);
        while (dragY < rimY + 20 && !(c.isAir(DRAG_X, dragY + 1, DRAG_Z) && c.isAir(DRAG_X, dragY + 2, DRAG_Z))) dragY++; // ramp stairs/slabs are not "ground"
        layout.standAt("warp.drag", DRAG_X, dragY, DRAG_Z, -90f);
        layout.standAt("warp.void", VOID_X, tombY, VOID_Z, 180f);
        layout.standAt("warp.ledge", -170, ledgeY, 26, 180f);
        layout.data("walk.ignore", "spawn.mob.,crystal.,boss.dragon");

        Box isl = h.box();
        Kit.Keepout mobKeep = (x, z) -> res.covers(x, z, 3) || paths.near(x, z, 2) || nearArrival(x, z);
        put(layout, "spawn.mob.main.", Kit.spots(c, h, isl, 60, 110, 44, 9, seed, (x, z) -> mobKeep.blocked(x, z) || Math.hypot(x - NX, z - NZ) < 50));
        put(layout, "spawn.mob.mite.", Kit.spots(c, h, isl, 60, 110, 16, 11, seed ^ 1, (x, z) -> mobKeep.blocked(x, z) || Math.hypot(x - NX, z - NZ) < 50 || z > 20));
        put(layout, "spawn.mob.seer.", Kit.spots(c, h, Box.of(isl.minX(), 0, isl.minZ(), isl.maxX(), 0, 0), 60, 110, 16, 10, seed ^ 2,
                (x, z) -> mobKeep.blocked(x, z) || Math.hypot(x - NX, z - NZ) < 50));
        List<int[]> defenders = new ArrayList<>();
        for (int[] pf : pillarFeet) {
            for (int[] o : new int[][]{{5, 0}, {-5, 0}}) {
                int x = pf[0] + o[0], z = pf[1] + o[1];
                int g = Kit.ground(c, x, z, 160, -1);
                if (g > 0 && !nearArrival(x, z) && c.isAir(x, g + 1, z) && c.isAir(x, g + 2, z)) defenders.add(new int[]{x, g, z});
            }
        }
        put(layout, "spawn.mob.defender.", defenders);
        put(layout, "spawn.mob.zealot.", Kit.spots(c, h, Box.of(NX - 32, 0, NZ - 32, NX + 32, 0, NZ + 32), floorY, floorY, 32, 4, seed ^ 3,
                (x, z) -> Math.hypot(x - NX, z - NZ) < 9 || Math.hypot(x - NX, z - NZ) > 31 || nearArrival(x, z)));
        put(layout, "spawn.mob.nestmite.", Kit.spots(c, h, Box.of(NX - 32, 0, NZ - 32, NX + 32, 0, NZ + 32), floorY, floorY, 10, 6, seed ^ 4,
                (x, z) -> Math.hypot(x - NX, z - NZ) < 12 || nearArrival(x, z)));
        List<int[]> bruisers = new ArrayList<>();
        for (int a = 0; a < 360; a += 36) {
            int x = hx + (int) Math.round(Math.sin(Math.toRadians(a)) * 9), z = hz + (int) Math.round(Math.cos(Math.toRadians(a)) * 9);
            if (!nearArrival(x, z) && c.isAir(x, hollowY + 1, z) && c.isAir(x, hollowY + 2, z)) bruisers.add(new int[]{x, hollowY, z});
        }
        put(layout, "spawn.mob.bruiser.", bruisers);
        put(layout, "spawn.mob.void.", Kit.spots(c, tomb, tomb.box(), 60, 110, 14, 6, seed ^ 5,
                (x, z) -> !tomb.inland(x, z, 2) || paths.near(x, z, 1) || nearArrival(x, z)));

        fairySouls(c, layout, h, tomb, ledge, gateY, floorY, fieldY, hollowY, tombY, ledgeY, hx, hz, tower, post);

        // ---------------------------------------------------------------- 14. zones and areas
        Box all = new Box(-215, 0, -215, 215, 255, 215);
        layout.zone("the_end", "The End", 'd', all, null);
        layout.zone("dragons_nest", "Dragon's Nest", '5', new Box(NX - 44, floorY - 4, NZ - 44, NX + 44, 160, NZ + 44), null);
        layout.zone("end_gate", "End Gate", 'a', new Box(gatePlot.minX() - 3, gateY - 4, gatePlot.minZ() - 3, gatePlot.maxX() + 3, gateY + 25, gatePlot.maxZ() + 6), null, Layout.Zone.SAFE);
        layout.zone("bruisers_hollow", "Bruiser's Hollow", 'c', new Box(hx - 38, hollowY - 3, hz - 21, hx + 21, hollowY + 15, hz + 21), hollowReq);
        layout.zone("null_tomb", "Null Tomb", '8', new Box(-160, 40, -160, -80, 160, -84), tombReq);
        // SAFE arrival pads (playtest 2026-10-03: /warp drag landed 2.8 blocks from a Lv55 Zealot)
        layout.zone("drag_landing", "Dragon's Nest", '5', dragLanding(), null, Layout.Zone.SAFE);
        layout.zone("tomb_landing", "Null Tomb", '8', voidLanding(), tombReq, Layout.Zone.SAFE);
        layout.zone("void_ledge", "Void Ledge", '9', new Box(-196, 40, 0, -150, 140, 44), null, Layout.Zone.SAFE);
        layout.zone("protectors_field", "Protector's Field", 'e', new Box(fieldPlot.minX() - 4, fieldY - 12, fieldPlot.minZ() - 4, fieldPlot.maxX() + 4, fieldY + 20, fieldPlot.maxZ() + 4), null);
        layout.area("nest_floor", new Box(NX - 30, floorY + 1, NZ - 30, NX + 30, floorY + 3, NZ + 30));
        layout.area("endstone.quarry", new Box(57, fieldY - 7, 61, 75, fieldY, 89));
        layout.area("protector_arena", new Box(30, fieldY + 1, 63, 54, fieldY + 3, 87));
        layout.area("hollow", new Box(hx - 15, hollowY + 1, hz - 15, hx + 15, hollowY + 3, hz + 15));
        layout.area("null_tomb", new Box(-150, 60, -150, -86, 110, -94));
        layout.data("podium", NX + "," + (floorY + 3) + "," + NZ);
        layout.data("reservations", res.summary());
    }

    /** The eight eye frames around the podium, offsets from the centre (radius 3 ring). */
    public static final int[][] FRAMES = {{-1, -3}, {1, -3}, {3, -1}, {3, 1}, {1, 3}, {-1, 3}, {-3, 1}, {-3, -1}};

    static void ramp(Terrain.Heightmap h, int x1, int z1, int x2, int z2, int toY, int half) {
        double len = Math.hypot(x2 - x1, z2 - z1);
        int startY = h.top(x1, z1);
        List<Kit.RoadPoint> pts = new ArrayList<>();
        for (int i = 0; i <= len * 2; i++) {
            double t = i / (len * 2);
            pts.add(new Kit.RoadPoint(x1 + (x2 - x1) * t, z1 + (z2 - z1) * t, startY + (toY - startY) * t));
        }
        Kit.gradeRoad(h, pts, half, 3);
    }

    static void put(Layout layout, String prefix, List<int[]> pts) { DenGenerator.put(layout, prefix, pts); }

    static void endLamp(VoxelCanvas c, int x, int y, int z) {
        Builders.footing(c, x, y + 1, z, Fill.of("end_stone_bricks"), 12);
        c.set(x, y + 1, z, "end_stone_brick_wall");
        c.set(x, y + 2, z, "purpur_pillar[axis=y]");
        c.set(x, y + 3, z, "end_rod[facing=up]");
    }

    static void ruin(VoxelCanvas c, int x, int y, int z, java.util.Random r) {
        Palette p = Palette.of("purpur_block:5,purpur_pillar[axis=y]:1,end_stone_bricks:2");
        int w = 4 + r.nextInt(4);
        for (int i = 0; i < w; i++) {
            int hgt = 1 + r.nextInt(5);
            for (int k = 1; k <= hgt; k++) c.set(x + i, y + k, z, p.at(x + i, y + k, z));
            c.set(x + i, y, z, "purpur_block");
        }
        for (int i = 0; i < 3; i++) {
            int hgt = 1 + r.nextInt(4);
            for (int k = 1; k <= hgt; k++) c.set(x, y + k, z + i, p.at(x, y + k, z + i));
        }
        for (int i = 0; i < 4; i++) {
            int g = Builders.ground(c, x + 2 + i, z + 3, y + 3, y - 3);
            if (g != Integer.MIN_VALUE) c.set(x + 2 + i, g + 1, z + 3, "purpur_pillar[axis=x]");
        }
    }

    static void chorus(VoxelCanvas c, int x, int y, int z, java.util.Random r) {
        int hgt = 3 + r.nextInt(4);
        for (int k = 1; k <= hgt; k++) c.set(x, y + k, z, "chorus_plant");
        c.set(x, y + hgt + 1, z, "chorus_flower[age=5]");
        int[] d = {1, 0, -1, 0, 0, 1, 0, -1};
        int arms = 1 + r.nextInt(2);
        for (int a = 0; a < arms; a++) {
            int i = r.nextInt(4);
            int ay = y + 2 + r.nextInt(Math.max(1, hgt - 2));
            int ax = x + d[i * 2], az = z + d[i * 2 + 1];
            c.set(ax, ay, az, "chorus_plant");
            c.set(ax, ay + 1, az, "chorus_flower[age=5]");
        }
    }

    static void fairySouls(VoxelCanvas c, Layout layout, Terrain.Heightmap h, Terrain.Heightmap tomb, Terrain.Heightmap ledge,
                           int gateY, int floorY, int fieldY, int hollowY, int tombY, int ledgeY, int hx, int hz,
                           Builders.Tower tower, Builders.House post) {
        List<int[]> s = new ArrayList<>();
        if (tower.inside != null) s.add(tower.inside);                           // inside the purpur tower
        s.add(new int[]{-101, gateY, 72});                                       // behind the gate's portal
        s.add(new int[]{NX + 30, floorY, NZ - 8});                               // crater wall, by a tower
        s.add(new int[]{NX - 3, floorY, NZ - 29});                               // far side of the crater
        s.add(new int[]{hx + 12, hollowY, hz + 7});                              // deep in the hollow
        s.add(new int[]{66, fieldY - 5, 82});                                    // bottom of the quarry pit
        s.add(new int[]{-129, tombY, -111});                                     // tomb plaza corner
        s.add(new int[]{-112, tombY, -132});                                     // between the graves
        s.add(new int[]{-178, ledgeY, 29});                                      // the ledge's far corner
        for (int[] w : new int[][]{{110, -60}, {-20, -100}, {-118, -20}}) {
            int g = Kit.ground(c, w[0], w[1], 160, -1);
            if (g > 0) s.add(new int[]{w[0], g, w[1]});
        }
        int i = 1;
        for (int[] p : s) {
            int g = p[1];
            for (int k = 1; k <= 2; k++) if (!c.isAir(p[0], g + k, p[2])) c.set(p[0], g + k, p[2], "air");
            layout.standAt("fairy_soul." + (i++), p[0], g, p[2], 0f);
        }
    }
}
