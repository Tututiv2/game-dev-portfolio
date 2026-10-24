package com.skyclone.mod.mines.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.IslandGenerator;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * The Deep Caverns: six cave levels stacked down a single shaft, each its own colour and ore, from
 * the grey Gunpowder Mines at the top to the black Obsidian Sanctuary at the bottom. A haul tunnel
 * spirals around the outside from each level to the next (you can walk all the way down), and a
 * caged lift shaft runs through the middle of every level, worked by a lift operator on each floor.
 *
 * <p>Research 03 §9 (layers, ores, mobs, Lift Operator, the delver at the bottom), §28 (21 souls),
 * §31.8 (grey → blue → red → green → cyan → black per layer, lift shaft landmark).
 */
public final class DeepCavernsGenerator implements IslandGenerator {

    /** Layer ids, in order from the top. Index 0 = layer 1. Shared with the module (lift, zones, spawns). */
    public static final String[] LAYER_IDS = {"gunpowder_mines", "lapis_quarry", "pigmens_den", "slimehill", "diamond_reserve", "obsidian_sanctuary"};
    public static final String[] LAYER_NAMES = {"Gunpowder Mines", "Lapis Quarry", "Pigmen's Den", "Slime Hollow", "Diamond Reserve", "Obsidian Sanctuary"};
    public static final char[] LAYER_COLORS = {'7', '9', 'c', 'a', 'b', '5'};
    public static final int[] FLOOR = {150, 124, 98, 72, 46, 18};
    static final int[] HEIGHT = {13, 15, 14, 15, 14, 22};
    static final double CAVE_R = 44, RING_R = 59;

    @Override public String islandId() { return "deep_caverns"; }
    // 2 (wave 5): the shaft runs on down to SHAFT_BOTTOM (the lift to the Dwarven Mines), the reference's 21 souls
    @Override public int version() { return 2; }
    /** The floor block at the very bottom of the lift shaft, below the Obsidian Sanctuary: the cage leaves for the Dwarven Mines from here. */
    public static final int SHAFT_BOTTOM = 4;
    @Override public Box bounds() { return new Box(-74, 0, -74, 74, 190, 74); }

    private final Palette[] rock = {
            Palette.of("stone:20,andesite:6,tuff:4,cobblestone:2,gravel:1"),
            Palette.of("stone:14,deepslate:4,blue_terracotta:3,light_blue_terracotta:1,andesite:3,calcite:1"),
            Palette.of("stone:10,granite:6,netherrack:5,red_terracotta:3,blackstone:2"),
            Palette.of("stone:12,moss_block:3,green_terracotta:3,andesite:3,mossy_cobblestone:3"),
            Palette.of("stone:8,deepslate:7,calcite:3,packed_ice:2,andesite:2,tuff:1"),
            Palette.of("obsidian:8,blackstone:4,deepslate:4,crying_obsidian:1")};
    private final Palette[] face = {
            Palette.of("stone:20,andesite:6,tuff:4,cobblestone:2,coal_ore:5,iron_ore:4,gold_ore:2"),
            Palette.of("stone:14,deepslate:3,blue_terracotta:3,andesite:3,lapis_ore:6,deepslate_lapis_ore:1"),
            Palette.of("stone:9,granite:6,netherrack:5,red_terracotta:2,redstone_ore:6,gold_ore:2"),
            Palette.of("stone:12,moss_block:3,green_terracotta:2,mossy_cobblestone:3,emerald_ore:5"),
            Palette.of("stone:7,deepslate:7,calcite:3,packed_ice:2,diamond_ore:4,deepslate_diamond_ore:1"),
            Palette.of("obsidian:10,blackstone:4,deepslate:3,crying_obsidian:1,diamond_ore:3,diamond_block:1")};
    private final Palette[] floorP = {
            Palette.of("gravel:3,stone:3,cobblestone:2,andesite:2,coarse_dirt:1"),
            Palette.of("stone:3,gravel:2,blue_terracotta:1,andesite:2,deepslate:1"),
            Palette.of("netherrack:3,granite:3,stone:2,red_terracotta:1,coarse_dirt:1"),
            Palette.of("moss_block:5,mossy_cobblestone:2,stone:2,clay:1"),
            Palette.of("deepslate:3,calcite:2,stone:2,packed_ice:1"),
            Palette.of("obsidian:3,blackstone:3,polished_deepslate:1")};

    private static final Palette ROAD = Palette.of("gravel:3,cobblestone:2,stone:2,coarse_dirt:2");

    @Override
    public void generate(VoxelCanvas c, Layout layout, long seed) {
        Noise wob = new Noise(Hash.salt(seed, "wobble"));
        Noise fl = new Noise(Hash.salt(seed, "floor"));
        Noise ce = new Noise(Hash.salt(seed, "ceil"));

        // ---- 1. plan the six levels ----
        List<CaveGrid> grids = new ArrayList<>();
        CaveGrid[] layers = new CaveGrid[6];
        for (int L = 0; L < 6; L++) {
            CaveGrid g = new CaveGrid(-74, -74, 74, 74);
            layers[L] = g;
            grids.add(g);
            int base = FLOOR[L];
            for (int z = -60; z <= 60; z++)
                for (int x = -60; x <= 60; x++) {
                    double a = Math.atan2(z, x);
                    double r = Math.hypot(x, z);
                    double edge = CAVE_R * (1 + 0.13 * wob.noise3(Math.cos(a) * 1.8, Math.sin(a) * 1.8, L * 3.1));
                    if (r > edge) continue;
                    double t = r / edge;
                    int f = base;
                    if (r > 10) f += (int) Math.round(1.6 * fl.fbm2(x / 22.0 + L * 7, z / 22.0, 3));
                    int hh = (int) Math.round(HEIGHT[L] * (0.62 + 0.38 * Math.sqrt(Math.max(0, 1 - t * t))) + 2.5 * ce.fbm2(x / 15.0, z / 15.0 + L * 5, 3));
                    if (L == 1) {                            // the quarry bowl
                        double d = Math.hypot(x + 20, z - 10) / 15.0;
                        if (d < 1) f -= (int) Math.round(6 * Noise.smoothstep(1, 0.25, d));
                    }
                    if (L == 3) {                            // Slime Hollow's mound
                        double d = Math.hypot(x - 18, z + 14) / 15.0;
                        if (d < 1) { int up = (int) Math.round(7 * Noise.smoothstep(1, 0.1, d)); f += up; hh = Math.max(hh, 8); }
                    }
                    if (L == 5 && r < 26) hh += 4;           // the sanctuary hall vaults higher
                    g.set(x, z, f, f + hh + 1, L + 1);
                }
            // rock pillars standing in the cave
            java.util.Random rnd = Hash.random(seed, "pillars" + L);
            for (int i = 0; i < 5; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = 16 + rnd.nextDouble() * 20;
                int px = (int) Math.round(Math.cos(a) * r), pz = (int) Math.round(Math.sin(a) * r);
                int pr = 2 + rnd.nextInt(2);
                for (int dz = -pr; dz <= pr; dz++) for (int dx = -pr; dx <= pr; dx++)
                    if (dx * dx + dz * dz <= pr * pr + 1 && Math.hypot(px + dx, pz + dz) > 12) g.close(px + dx, pz + dz);
            }
            // rubble mounds and outcrops so no floor is a flat plain
            for (int i = 0; i < 7; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, r = 18 + rnd.nextDouble() * 18;
                int mx = (int) Math.round(Math.cos(a) * r), mz = (int) Math.round(Math.sin(a) * r);
                int mr = 4 + rnd.nextInt(4), up = 2 + rnd.nextInt(3);
                for (int dz = -mr; dz <= mr; dz++) for (int dx = -mr; dx <= mr; dx++) {
                    int x = mx + dx, z = mz + dz;
                    double d = Math.hypot(dx, dz) / mr;
                    if (d >= 1 || !g.open(x, z) || Math.abs(x) < 16 && Math.abs(z) < 16) continue;
                    int f = g.floor(x, z) + (int) Math.round(up * Noise.smoothstep(1, 0.2, d));
                    g.set(x, z, f, Math.max(g.ceil(x, z), f + 6), L + 1);
                }
            }
            // a flat, level apron around the lift shaft
            for (int z = -15; z <= 15; z++) for (int x = -15; x <= 15; x++) if (g.open(x, z)) g.set(x, z, base, Math.max(g.ceil(x, z), base + 8), L + 1);
        }
        // the arrival hall on the top level (south) and the sanctuary gate (north)
        for (int z = 30; z <= 46; z++) for (int x = -9; x <= 9; x++) layers[0].set(x, z, FLOOR[0], FLOOR[0] + 9, 1);
        for (int z = -50; z <= -32; z++) for (int x = -9; x <= 9; x++) layers[5].set(x, z, FLOOR[5], FLOOR[5] + 15, 6);

        // ---- 2. plan the spiral haul tunnels between levels ----
        CaveGrid[] tunnels = new CaveGrid[5];
        double[] startA = new double[5], endA = new double[5];
        for (int k = 0; k < 5; k++) {
            CaveGrid t = new CaveGrid(-74, -74, 74, 74);
            tunnels[k] = t;
            grids.add(t);
            double a0 = k * Math.PI / 2 + Math.PI / 4, a1 = a0 + Math.PI / 2 * 0.78;
            startA[k] = a0; endA[k] = a1;
            int y0 = FLOOR[k], y1 = FLOOR[k + 1];
            int top = layers[k].floor((int) Math.round(Math.cos(a0) * 36), (int) Math.round(Math.sin(a0) * 36));
            if (top != CaveGrid.NONE) y0 = top;
            int bot = layers[k + 1].floor((int) Math.round(Math.cos(a1) * 36), (int) Math.round(Math.sin(a1) * 36));
            if (bot != CaveGrid.NONE) y1 = bot;
            for (int z = -66; z <= 66; z++)
                for (int x = -66; x <= 66; x++) {
                    double r = Math.hypot(x, z);
                    double a = Math.atan2(z, x);
                    double d = a - a0;
                    while (d < 0) d += Math.PI * 2;
                    double span = a1 - a0;
                    // the ring part
                    if (r >= RING_R - 2.2 && r <= RING_R + 2.2 && d <= span) {
                        double tt = d / span;
                        double eased = Noise.smoothstep(0.04, 0.96, tt);
                        int f = (int) Math.round(y0 + (y1 - y0) * eased);
                        t.set(x, z, f, f + 6, 10 + k);
                    }
                    // the two radial connectors (into the level above, out into the level below)
                    for (int e = 0; e < 2; e++) {
                        double ea = e == 0 ? a0 : a1;
                        double ux = Math.cos(ea), uz = Math.sin(ea);
                        double along = x * ux + z * uz, across = Math.abs(-x * uz + z * ux);
                        if (across <= 2.2 && along >= 34 && along <= RING_R + 1) {
                            int f = e == 0 ? y0 : y1;
                            CaveGrid lay = layers[e == 0 ? k : k + 1];
                            if (lay.open(x, z) && Math.abs(lay.floor(x, z) - f) <= 1) f = lay.floor(x, z);
                            if (!t.open(x, z) || e == 1) t.set(x, z, f, f + 6, 10 + k);
                        }
                    }
                }
        }

        // ---- 3. write rock, bottom level first ----
        CaveGrid.Painter painter = new CaveGrid.Painter() {
            @Override public String rock(int x, int y, int z, int region, boolean exposed) {
                int L = layerOf(region, y);
                return exposed ? face[L].at(x, y, z) : rock[L].at(x, y, z);
            }
            @Override public String floorTop(int x, int y, int z, int region) {
                int L = layerOf(region, y);
                return region >= 10 ? ROAD.at(x, y, z) : floorP[L].at(x, y, z);
            }
        };
        for (int L = 5; L >= 0; L--) layers[L].write(c, 5, 5, 5, painter, grids);
        for (int k = 4; k >= 0; k--) tunnels[k].write(c, 3, 3, 3, painter, grids);

        // ---- 4. the lift shaft through the middle ----
        int shaftTop = FLOOR[0] + 12;
        for (int y = FLOOR[5]; y <= shaftTop; y++)
            for (int z = -3; z <= 3; z++)
                for (int x = -3; x <= 3; x++) {
                    boolean ring = Math.abs(x) == 3 || Math.abs(z) == 3;
                    boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
                    if (!ring) { c.set(x, y, z, "air"); continue; }   // wave 5: open below the sanctuary too
                    boolean inLevel = false;
                    for (int L = 0; L < 6; L++) if (y > FLOOR[L] && y <= FLOOR[L] + 7) inLevel = true;
                    if (corner) c.set(x, y, z, "stone_bricks");
                    else if (!inLevel) c.set(x, y, z, "stone_bricks");
                    else c.set(x, y, z, (y % 4 == 0) ? "stone_brick_slab[type=bottom]" : "iron_bars");
                }
        // wave 5: the shaft goes on down past the sanctuary floor into the dark, where the cage leaves for the Dwarven Mines
        for (int y = SHAFT_BOTTOM; y < FLOOR[5]; y++)
            for (int z = -3; z <= 3; z++)
                for (int x = -3; x <= 3; x++) {
                    boolean ring = Math.abs(x) == 3 || Math.abs(z) == 3;
                    c.set(x, y, z, y == SHAFT_BOTTOM ? (ring ? "stone_bricks" : "polished_andesite") : ring ? (y % 6 == 0 ? "chiseled_stone_bricks" : "stone_bricks") : "air");
                }
        layout.data("lift.shaft", "0,0," + (SHAFT_BOTTOM + 1));
        for (int L = 0; L < 6; L++) {
            int y = FLOOR[L];
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
                if (Math.abs(x) == 3 || Math.abs(z) == 3) { c.set(x, y + 8, z, "chiseled_stone_bricks"); c.set(x, y + 1, z, Math.abs(x) == 3 && Math.abs(z) == 3 ? "stone_bricks" : "polished_andesite"); }
            Blocks.door(c, 0, y + 1, 3, "iron", "south", "left", false);
            c.set(0, y + 3, 3, "stone_bricks");
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 6; z++) if (Math.abs(x) > 3 || Math.abs(z) > 3) c.set(x, y, z, Palette.of("polished_andesite:3,andesite:1,stone_bricks:2").at(x, y, z));
            MineKit.hangLantern(c, -4, y + 9, 4, 3, false);
            MineKit.hangLantern(c, 4, y + 9, 4, 3, false);
            layout.standAt("npc.lift." + (L + 1), -2, y, 5, 180f);
            layout.standAt("lift." + (L + 1), 1, y, 6, 180f);
            layout.zone("lift_" + (L + 1), "Lift Station", LAYER_COLORS[L], Box.of(-8, y - 1, -8, 8, y + 8, 9), null, Layout.Zone.SAFE, Layout.Zone.NO_MOBS);
        }
        // the cage itself waits at the top level, hung from a winch
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) c.set(x, FLOOR[0], z, "spruce_planks");
        for (int y = FLOOR[0] + 1; y <= shaftTop; y++) { c.set(-2, y, -2, "iron_chain[axis=y]"); c.set(2, y, 2, "iron_chain[axis=y]"); }
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) c.set(x, shaftTop + 1, z, "stone_bricks");

        // ---- 5. per-level dressing ----
        MineKit.Souls souls = new MineKit.Souls();
        java.util.function.BiPredicate<Integer, Integer> nearShaft = (x, z) -> Math.abs(x) < 11 && Math.abs(z) < 11;
        for (int L = 0; L < 6; L++) {
            CaveGrid g = layers[L];
            Box ob = g.openBox();
            int y = FLOOR[L];
            final int lv = L;
            String tooth = switch (L) { case 2 -> "netherrack"; case 3 -> "mossy_cobblestone"; case 4 -> "packed_ice"; case 5 -> "obsidian"; default -> null; };
            MineKit.stalactites(c, g, ob, seed + L, 0.045, tooth);
            MineKit.stalagmites(c, g, ob, seed + L, L == 4 ? 0.02 : 0.012, L == 4 ? "packed_ice" : L == 5 ? "obsidian" : null,
                    (x, z) -> nearShaft.test(x, z) || nearTunnelMouth(x, z, lv, startA, endA) || lv == 0 && Math.abs(x) < 12 && z > 26);
            lights(c, g, ob, L, seed);
            decorate(c, g, L, seed, souls, nearShaft);
            layout.zone(LAYER_IDS[L], LAYER_NAMES[L], LAYER_COLORS[L], new Box(-66, y - 6, -66, 66, y + HEIGHT[L] + 8, 66), null);
            layout.area("ores." + LAYER_IDS[L], new Box(-66, y - 7, -66, 66, y + HEIGHT[L] + 9, 66));
            layout.area(LAYER_IDS[L], new Box(-50, y - 6, -50, 50, y + 4, 50));
        }
        // tunnels: supports, lanterns, rails on the flat stretches, stairs on the rises
        for (int k = 0; k < 5; k++) {
            CaveGrid t = tunnels[k];
            final CaveGrid tg = t;
            MineKit.stepify(c, new Box(-66, 0, -66, 66, 0, 66), tg::floor, tg::open, k < 2 ? "cobblestone_stairs" : k < 4 ? "stone_brick_stairs" : "polished_blackstone_brick_stairs");
            double span = endA[k] - startA[k];
            for (int i = 1; i < 12; i++) {
                double a = startA[k] + span * i / 12.0;
                int x = (int) Math.round(Math.cos(a) * RING_R), z = (int) Math.round(Math.sin(a) * RING_R);
                if (!t.open(x, z)) continue;
                int f = t.floor(x, z);
                if (i % 2 == 0) MineKit.hangLantern(c, x, t.ceil(x, z), z, 2, k >= 3);
                else {
                    int ix = (int) Math.round(Math.cos(a) * (RING_R - 2)), iz = (int) Math.round(Math.sin(a) * (RING_R - 2));
                    int ox = (int) Math.round(Math.cos(a) * (RING_R + 2)), oz = (int) Math.round(Math.sin(a) * (RING_R + 2));
                    post(c, ix, t.floor(ix, iz), iz, t.ceil(ix, iz), k >= 3 ? "dark_oak_log" : "spruce_log");
                    post(c, ox, t.floor(ox, oz), oz, t.ceil(ox, oz), k >= 3 ? "dark_oak_log" : "spruce_log");
                }
                if (i == 6) souls.add(x + (int) Math.signum(Math.cos(a)) * 2, t.floor(x + (int) Math.signum(Math.cos(a)) * 2, z), z);
            }
            layout.zone("descent_" + (k + 1), "Haul Road", LAYER_COLORS[k], new Box(-66, FLOOR[k + 1] - 2, -66, 66, FLOOR[k] + 8, 66), null);
        }
        layout.data("walk.ignore", "lift.");

        // ---- 6. arrival hall on the Gunpowder Mines: portals home and to the Gold Mine ----
        int ay = FLOOR[0];
        Palette paving = Palette.of("stone_bricks:4,polished_andesite:2,cracked_stone_bricks:1");
        for (int z = 30; z <= 45; z++) for (int x = -8; x <= 8; x++) c.set(x, ay, z, paving.at(x, ay, z));
        MineKit.portal(c, layout, "gold_mine", -4, ay, 44, 'x', 3, 4, Palette.of("stripped_spruce_log[axis=y]:3,gold_block:1"), null);
        MineKit.portal(c, layout, "hub", 4, ay, 44, 'x', 3, 4, Palette.of("stone_bricks:3,chiseled_stone_bricks:1"), null);
        for (int z = 31; z <= 41; z += 5) { MineKit.supportFrame(c, 0, ay, z, 'z', 8, 6, "spruce_log"); }
        MineKit.hangLantern(c, -5, ay + 10, 36, 3, false);
        MineKit.hangLantern(c, 5, ay + 10, 36, 3, false);
        layout.standAt("spawn", 0, ay, 38, 180f);
        layout.standAt("npc.blaster", 6, ay, 32, 180f);
        MineKit.tntPile(c, 7, ay, 34, seed);
        MineKit.crates(c, -7, ay, 33, seed);
        layout.zone("arrival", "Gunpowder Mines", '7', Box.of(-9, ay - 1, 30, 9, ay + 10, 46), null, Layout.Zone.SAFE);

        // ---- 7. the Obsidian Sanctuary: the gate to the Dwarven Mines ----
        int sy = FLOOR[5];
        for (int z = -50; z <= -30; z++) for (int x = -8; x <= 8; x++)
            c.set(x, sy, z, Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:1,obsidian:1").at(x, sy, z));
        MineKit.portal(c, layout, "dwarven_mines", 0, sy, -48, 'x', 5, 7, Palette.of("crying_obsidian:1,obsidian:3,gilded_blackstone:1"), null);
        for (int z = -46; z <= -32; z += 7) {
            for (int s : new int[]{-7, 7}) {
                for (int yy = sy + 1; yy <= sy + 11; yy++) c.set(s, yy, z, yy == sy + 11 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
                c.set(s + (s < 0 ? 1 : -1), sy + 9, z, Blocks.soulLantern(false).replace("hanging=false", "hanging=false"));
            }
        }
        layout.standAt("npc.delver", 3, sy, -42, 180f);
        Box gate = Box.of(-9, sy - 1, -51, 9, sy + 16, -30);
        layout.zone("sanctuary_gate", "Sanctuary Gate", '5', gate, null, Layout.Zone.SAFE);
        // a ring of obsidian pillars around a crying-obsidian dais in the middle of the hall
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + Math.PI / 8;
            int px = (int) Math.round(Math.cos(a) * 19), pz = (int) Math.round(Math.sin(a) * 19);
            if (!layers[5].open(px, pz)) continue;
            int top = layers[5].ceil(px, pz);
            for (int yy = layers[5].floor(px, pz) + 1; yy < top; yy++) c.set(px, yy, pz, yy % 5 == 0 ? "crying_obsidian" : "obsidian");
        }

        // nothing decorative may hang inside the lift shaft
        for (int y = SHAFT_BOTTOM + 1; y <= shaftTop; y++)
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                if (y == FLOOR[0] && true) continue;                 // the cage floor
                if ((x == -2 && z == -2 || x == 2 && z == 2) && y > FLOOR[0]) continue;   // its chains
                c.set(x, y, z, "air");
            }

        souls.write(c, layout, 21);   // wave 5: NEU fairy_souls.json mining_2 = 21

        // ---- 8. the island as a whole ----
        layout.zone("deep_caverns", "Deep Caverns", '9', new Box(-74, 0, -74, 74, 190, 74), null);
    }

    private static int layerOf(int region, int y) {
        if (region >= 1 && region <= 6) return region - 1;
        if (region >= 10) return Math.min(5, region - 10);
        for (int L = 5; L >= 0; L--) if (y < FLOOR[L] + 20) return L;
        return 0;
    }

    private static boolean nearTunnelMouth(int x, int z, int L, double[] startA, double[] endA) {
        for (int k = 0; k < 5; k++)
            for (int e = 0; e < 2; e++) {
                if ((e == 0 ? k : k + 1) != L) continue;
                double a = e == 0 ? startA[k] : endA[k];
                double ux = Math.cos(a), uz = Math.sin(a);
                double along = x * ux + z * uz, across = Math.abs(-x * uz + z * ux);
                if (across < 5 && along > 26) return true;
            }
        return false;
    }

    private static void post(VoxelCanvas c, int x, int f, int z, int ceil, String log) {
        if (f == CaveGrid.NONE || ceil == CaveGrid.NONE) return;
        for (int y = f + 1; y < ceil; y++) if (c.isAir(x, y, z)) c.set(x, y, z, log + "[axis=y]");
    }

    /** Light every level so it can be seen: hanging lanterns plus each level's own glow. */
    private void lights(VoxelCanvas c, CaveGrid g, Box ob, int L, long seed) {
        long s = Hash.salt(seed, "lights" + L);
        String glow = switch (L) { case 2 -> "shroomlight"; case 3 -> "verdant_froglight"; case 4 -> "sea_lantern"; case 5 -> "crying_obsidian"; default -> "glowstone"; };
        for (int z = ob.minZ(); z <= ob.maxZ(); z += 3)
            for (int x = ob.minX(); x <= ob.maxX(); x += 3) {
                if (!g.open(x, z)) continue;
                double u = Hash.unit(s, x, z);
                int cy = g.ceil(x, z);
                if (u < 0.05) MineKit.hangLantern(c, x, cy, z, 2 + (int) (Hash.unit(s, x, 1, z) * 3), L >= 4);
                else if (u < 0.11) { c.set(x, cy, z, glow); if (Hash.unit(s, x, 2, z) < 0.5) c.set(x + 1, cy, z, glow); }
            }
    }

    private void decorate(VoxelCanvas c, CaveGrid g, int L, long seed, MineKit.Souls souls, java.util.function.BiPredicate<Integer, Integer> nearShaft) {
        java.util.Random rnd = Hash.random(seed, "decor" + L);
        Box ob = g.openBox();
        int placed = 0, tries = 0, soulCount = 0;
        int wantSouls = L == 5 ? 4 : 3;
        while (tries++ < 600 && (placed < 22 || soulCount < wantSouls)) {
            int x = ob.minX() + rnd.nextInt(ob.maxX() - ob.minX() + 1), z = ob.minZ() + rnd.nextInt(ob.maxZ() - ob.minZ() + 1);
            if (!g.open(x, z) || nearShaft.test(x, z)) continue;
            int f = g.floor(x, z);
            if (!MineKit.solid(c, x, f, z) || !c.isAir(x, f + 1, z) || !c.isAir(x, f + 2, z)) continue;
            double r = Math.hypot(x, z);
            if (soulCount < wantSouls && r > 30 && rnd.nextDouble() < 0.25) {
                souls.add(x, f, z);
                soulCount++;
                continue;
            }
            if (placed >= 22) continue;
            placed++;
            switch (L) {
                case 0 -> {
                    if (rnd.nextBoolean()) MineKit.tntPile(c, x, f, z, seed + x);
                    else MineKit.crates(c, x, f, z, seed + z);
                }
                case 1 -> {
                    if (rnd.nextInt(3) == 0) c.set(x, f + 1, z, "blue_glazed_terracotta");
                    else MineKit.crates(c, x, f, z, seed + z);
                }
                case 2 -> {
                    for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                        if (dx * dx + dz * dz > 5 || !g.open(x + dx, z + dz)) continue;
                        int ff = g.floor(x + dx, z + dz);
                        if (!MineKit.solid(c, x + dx, ff, z + dz)) continue;
                        c.set(x + dx, ff, z + dz, "crimson_nylium");
                        if (c.isAir(x + dx, ff + 1, z + dz) && rnd.nextInt(3) == 0) c.set(x + dx, ff + 1, z + dz, rnd.nextBoolean() ? "crimson_roots" : "crimson_fungus");
                    }
                }
                case 3 -> {
                    for (int dz = -1; dz <= 1; dz++) for (int dx = -2; dx <= 1; dx++) {
                        if (!g.open(x + dx, z + dz) || g.floor(x + dx, z + dz) != f) continue;
                        c.set(x + dx, f, z + dz, "slime_block");
                    }
                    if (c.isAir(x + 3, f + 1, z) && g.open(x + 3, z) && g.floor(x + 3, z) == f) c.set(x + 3, f + 1, z, "moss_carpet");
                }
                case 4 -> {
                    int hgt = 2 + rnd.nextInt(3);
                    for (int i = 1; i <= hgt && c.isAir(x, f + i, z); i++) c.set(x, f + i, z, "blue_ice");
                    if (c.isAir(x + 1, f + 1, z)) c.set(x + 1, f + 1, z, "snow[layers=1]");
                }
                default -> {
                    if (rnd.nextBoolean()) c.set(x, f + 1, z, "amethyst_cluster[facing=up,waterlogged=false]");
                    else c.set(x, f + 1, z, Blocks.soulLantern(false));
                }
            }
        }
        // a cart loop around the lift apron, and timber work platforms against the walls
        if (L != 5) MineKit.railLoop(c, 0, FLOOR[L], 0, 12);
        String wood = L == 5 || L == 2 ? "dark_oak" : "spruce";
        int made = 0;
        for (int i = 0; i < 40 && made < 3; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            for (double r = 46; r > 24; r -= 1) {
                int x = (int) Math.round(Math.cos(a) * r), z = (int) Math.round(Math.sin(a) * r);
                boolean ok = true;
                for (int dx = -2; dx <= 1 && ok; dx++) for (int dz = -1; dz <= 1 && ok; dz++)
                    ok = g.open(x + dx, z + dz) && g.floor(x + dx, z + dz) == g.floor(x, z) && g.ceil(x + dx, z + dz) - g.floor(x, z) >= 9;
                if (!ok) continue;
                int f = g.floor(x, z);
                if (!c.isAir(x, f + 1, z) || !c.isAir(x - 2, f + 1, z + 1)) break;
                int[] top = MineKit.platform(c, x, f, z, 4, wood);
                if (made == 0) souls.add(top[0], top[1], top[2]);
                made++;
                break;
            }
        }
    }
}
