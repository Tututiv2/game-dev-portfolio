package com.skyclone.mod.rift.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Shapes;
import com.skyclone.gen.Terrain;
import com.skyclone.mod.rift.RiftCatalog;

import static com.skyclone.mod.rift.gen.RiftGenerator.*;

/**
 * The districts of the Rift. Every structure is reserved before it is built, every anchor comes
 * from what a builder reports, and every plot was flattened in {@link #flattenPlots} BEFORE the
 * terrain was written (so buildings sit on ground instead of on stilts).
 */
final class RiftBuild {

    private RiftBuild() {}

    // ---- plots (x1, z1, x2, z2) -------------------------------------------------------------------
    static final Box P_CRADLE = Box.of(-39, 0, 11, -11, 0, 39);
    static final Box P_TOWER = Box.of(-70, 0, -40, -50, 0, -20);
    static final Box P_TREE = Box.of(-65, 0, 55, -45, 0, 75);
    static final Box P_GALLERY = Box.of(-8, 0, 41, 12, 0, 55);
    static final Box P_STALL = Box.of(0, 0, -7, 10, 0, 3);
    static final Box P_CIRCLE = Box.of(18, 0, 22, 34, 0, 38);
    static final Box P_HUT = Box.of(-132, 0, 61, -118, 0, 75);
    static final Box P_SQUARE = Box.of(-152, 0, -97, -128, 0, -73);
    static final Box P_MIRROR = Box.of(-118, 0, -112, -102, 0, -96);
    static final Box P_DREAD = Box.of(-45, 0, -165, 38, 0, -100);
    static final Box P_PLAZA = Box.of(70, 0, -50, 132, 0, 8);
    static final Box P_COLOSSEUM = Box.of(22, 0, 112, 88, 0, 178);
    static final Box P_CHATEAU = Box.of(97, 0, 92, 153, 0, 148);

    // houses of the West Village: x, z, width, depth, door
    static final Object[][] WEST_HOUSES = {
            {-158, -118, 9, 7, "south", "west_house_1"},
            {-142, -120, 13, 9, "south", "cake_house"},
            {-160, -66, 11, 9, "north", "infested_house"},
            {-138, -64, 9, 7, "north", "west_house_2"},
            {-178, -90, 9, 9, "east", "west_house_3"},
    };

    static void flattenPlots(RiftCtx g) {
        Terrain.Heightmap h = g.h;
        g.cradleY = flat(h, P_CRADLE, 6);
        flat(h, P_TOWER, 5);
        flat(h, P_TREE, 5);
        flat(h, P_GALLERY, 5);
        flat(h, P_STALL, 4);
        flat(h, P_CIRCLE, 4);
        h.flatten(P_HUT, RiftCtx.WATER + 1, 3);
        g.squareY = flat(h, P_SQUARE, 6);
        h.flatten(P_MIRROR, g.squareY, 5);
        for (Object[] w : WEST_HOUSES) {
            int x = (int) w[0], z = (int) w[1], wd = (int) w[2], dp = (int) w[3];
            h.flatten(Box.of(x - 2, 0, z - 2, x + wd + 1, 0, z + dp + 1), g.squareY, 5);
        }
        g.dreadY = flat(h, P_DREAD, 8);
        g.plazaY = flat(h, P_PLAZA, 8);
        g.colY = flat(h, P_COLOSSEUM, 8);
        g.chateauY = flat(h, P_CHATEAU, 8);
        // the summit: a small plateau at the mountain's top
        Box summit = Box.of(MOUNTAIN[0] - 8, 0, MOUNTAIN[1] - 8, MOUNTAIN[0] + 8, 0, MOUNTAIN[1] + 8);
        int peak = h.maxTop();
        g.summitY = Math.min(peak, h.average(summit));
        h.flatten(summit, g.summitY, 6);
    }

    private static int flat(Terrain.Heightmap h, Box b, int blend) {
        int y = h.average(b);
        if (y == Terrain.Heightmap.NONE) y = RiftCtx.BASE;
        h.flatten(b, y, blend);
        return y;
    }

    private static void pave(RiftCtx g, Box b, int y, Fill f) {
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++) g.c.set(x, y, z, f.at(x, y, z));
    }

    private static void clearAbove(RiftCtx g, Box b, int y, int height) {
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int k = 1; k <= height; k++) if (Builders.natural(g.c.get(x, y + k, z))) g.c.set(x, y + k, z, "air");
    }

    private static Builders.House house(RiftCtx g, String name, int x, int y, int z, int w, int d, String door, int floors,
                                        String wall, String frame, String roof, String style) {
        Builders.House b = new Builders.House(x, y, z, w, d);
        b.floors = floors; b.door = door; b.wall = Palette.of(wall); b.frame = frame; b.roof = roof; b.roofStyle = style;
        b.seed = g.seed + x * 31L + z;
        b.foundation = Palette.of("cobbled_deepslate:3,tuff:2,mossy_cobblestone:1");
        if (!g.res.reserve(name, b.reserve())) return null;
        b.build(g.c);
        return b;
    }

    private static void lamp(RiftCtx g, int x, int z, String style) {
        int y = g.ground(x, z);
        if (!g.c.isAir(x, y + 1, z)) return;
        Builders.lampPost(g.c, x, y, z, style, "north");
    }

    // =========================================================================================
    // The Cradle (spawn)
    // =========================================================================================

    static void cradle(RiftCtx g) {
        int y = g.cradleY, cx = CRADLE[0], cz = CRADLE[1];
        g.res.reserve("cradle", P_CRADLE);
        Palette floor = Palette.of("polished_tuff:4,tuff_bricks:3,chiseled_tuff:1,mossy_stone_bricks:1");
        for (int z = cz - 13; z <= cz + 13; z++)
            for (int x = cx - 13; x <= cx + 13; x++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > 13.4) continue;
                g.c.set(x, y, z, d > 12.4 ? "polished_deepslate" : floor.at(x, y, z));
                Builders.footing(g.c, x, y, z, Fill.of("tuff"), 10);
                for (int k = 1; k <= 6; k++) if (Builders.natural(g.c.get(x, y + k, z))) g.c.set(x, y + k, z, "air");
            }
        // a ring of pale pillars with amethyst tips, gaps where the roads leave
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI * 2 / 16;
            int px = cx + (int) Math.round(Math.cos(a) * 12), pz = cz + (int) Math.round(Math.sin(a) * 12);
            if (i % 2 == 1) continue;
            if (pz <= cz - 10 && Math.abs(px - cx) <= 4) continue;           // the portal stands here
            for (int k = 1; k <= 4; k++) g.c.set(px, y + k, pz, k == 4 ? "chiseled_tuff" : "pale_oak_log[axis=y]");
            g.c.set(px, y + 5, pz, "amethyst_cluster[facing=up,waterlogged=false]");
        }
        // the centre: a sunken basin with a floating-crystal look
        Shapes.disk(g.c, cx, y, cz, 2.5, Fill.of("crying_obsidian"));
        g.c.set(cx, y, cz, "respawn_anchor[charges=4]");
        // the portal home, on the north edge
        int pz = cz - 12;
        Palette frame = Palette.of("crying_obsidian:3,obsidian:2,purpur_block:1");
        for (int x = cx - 3; x <= cx + 3; x++) { g.c.set(x, y + 6, pz, frame.at(x, y + 6, pz)); g.c.set(x, y, pz, "obsidian"); }
        for (int k = 1; k <= 5; k++) { g.c.set(cx - 3, y + k, pz, frame.at(cx - 3, y + k, pz)); g.c.set(cx + 3, y + k, pz, frame.at(cx + 3, y + k, pz)); }
        for (int x = cx - 2; x <= cx + 2; x++) for (int k = 1; k <= 5; k++) g.c.set(x, y + k, pz, "nether_portal[axis=x]");
        g.l.area("portal.hub", Box.of(cx - 2, y + 1, pz, cx + 2, y + 5, pz));
        Builders.lampPost(g.c, cx - 5, y, pz + 1, "soul", "south");
        Builders.lampPost(g.c, cx + 5, y, pz + 1, "soul", "south");
        // benches and lights
        Builders.bench(g.c, cx - 8, y, cz + 9, "north", 3, "pale_oak");
        Builders.bench(g.c, cx + 6, y, cz + 9, "north", 3, "pale_oak");
        Builders.lampPost(g.c, cx - 9, y, cz - 6, "street", "east");
        Builders.lampPost(g.c, cx + 9, y, cz - 6, "street", "west");
        // who stands here
        g.stand("spawn", cx, y, cz + 4, 180f);
        g.stand("npc.rift_wizard", cx + 4, y, cz - 8, 0f);
        g.stand("npc.mote_monger", cx + 9, y, cz + 2, 90f);
        g.stand("npc.riddler", cx - 9, y, cz + 2, -90f);
        g.soul("wyld_woods", "rift_enigma_original", cx - 10, y, cz + 5, "A practice soul, right next to the Riddler in the Cradle.");
        g.keepOut.add(Box.of(cx - 16, 0, cz - 16, cx + 16, 0, cz + 16));
    }

    // =========================================================================================
    // Wildwood Thicket
    // =========================================================================================

    static void wyld(RiftCtx g) {
        // ---- the Rift's Wizard Tower ----
        int ty = g.ground(TOWER[0], TOWER[1]);
        Builders.Tower t = new Builders.Tower(TOWER[0], ty, TOWER[1]);
        t.radius = 6; t.height = 30; t.floorHeight = 6; t.top = "cone"; t.door = "east";
        t.wall = Palette.of("deepslate_bricks:3,polished_deepslate:1,purpur_block:1,deepslate_tiles:1");
        t.roofFill = Palette.of("purple_concrete:2,magenta_terracotta:1,purple_terracotta:1");
        t.window = "magenta_stained_glass_pane";
        if (g.res.reserve("wizard_tower", t.reserve())) {
            t.build(g.c);
            g.stand("npc.tower_sage", t.inside[0], t.inside[1], t.inside[2], -90f);
            int topFloor = ty + ((t.height - 1) / t.floorHeight) * t.floorHeight;
            g.soul("wyld_woods", "rift_enigma_jump_pad_1", TOWER[0] + 2, topFloor, TOWER[1] + 2, "On the highest floor of the Rift's own Wizard Tower.");
            g.l.data("tower.y", String.valueOf(ty));
        }

        // ---- the Great Wyld Tree: a spiral of steps to the crown (Crown Timecharm) ----
        greatTree(g);

        // ---- the Rift Gallery ----
        int gy = g.ground(GALLERY[0], GALLERY[1]);
        Builders.House gal = house(g, "rift_gallery", GALLERY[0] - 7, gy, GALLERY[1] - 4, 15, 9, "west", 1,
                "calcite:4,polished_diorite:2,quartz_bricks:1", "stripped_pale_oak_log", "deepslate_tile", "gable");
        if (gal != null) {
            // exhibit pedestals: one per Timecharm. The space above each stays empty: the module floats the
            // Timecharm there (an item display only the players who secured it can see) - wave 3, gallery.exhibit.N
            for (int i = 0; i < 8; i++) {
                int px = GALLERY[0] - 5 + (i % 4) * 3, pz = GALLERY[1] + (i < 4 ? -2 : 2);
                if (!g.c.isAir(px, gy + 1, pz)) continue;
                g.c.set(px, gy + 1, pz, "chiseled_tuff");
                g.c.set(px, gy + 2, pz, "air");
                g.c.set(px, gy, pz + (i < 4 ? -1 : 1), i % 2 == 0 ? "amethyst_block" : "purpur_block");   // floor inlay
                g.l.data("gallery.exhibit." + (i + 1), px + "," + (gy + 2) + "," + pz);
            }
            // the curator, at the far (east) end facing the door
            g.stand("npc.curator", GALLERY[0] + 6, gy, GALLERY[1], 90f);
            g.l.data("gallery.box", boxStr(Box.of(GALLERY[0] - 7, gy, GALLERY[1] - 4, GALLERY[0] + 7, gal.peakY, GALLERY[1] + 4)));
            g.soul("wyld_woods", "rift_enigma_two_plates", GALLERY[0] + 9, g.ground(GALLERY[0] + 9, GALLERY[1] - 2), GALLERY[1] - 2, "Behind the Rift Gallery, against its east wall.");
            g.l.standAt("fairy_soul.rift", GALLERY[0] + 9, g.ground(GALLERY[0] + 9, GALLERY[1] + 6), GALLERY[1] + 6, 0f);
        }

        // ---- the Glade Peddlers' stall ----
        int sy = g.ground(5, -2);
        Builders.Stall st = Builders.marketStall(g.c, 5, sy, -2, "north", "purple", "lime", g.seed + 5);
        g.res.reserve("peddler_stall", st.box());
        g.stand("npc.glade_peddler", st.vendor()[0], st.vendor()[1], st.vendor()[2], 180f);
        g.soul("wyld_woods", "rift_enigma_phantom", 8, g.ground(8, -7), -7, "Tucked behind the Glade Peddlers' stall.");

        // ---- the old stone circle ----
        int cy = g.ground(CIRCLE[0], CIRCLE[1]);
        g.res.reserve("stone_circle", P_CIRCLE);
        for (int i = 0; i < 9; i++) {
            double a = i * Math.PI * 2 / 9;
            int px = CIRCLE[0] + (int) Math.round(Math.cos(a) * 6), pz = CIRCLE[1] + (int) Math.round(Math.sin(a) * 6);
            int hgt = 3 + (i % 3);
            for (int k = 1; k <= hgt; k++) g.c.set(px, cy + k, pz, Palette.of("mossy_stone_bricks:2,cracked_stone_bricks:1,tuff:1").at(px, cy + k, pz));
        }
        g.c.set(CIRCLE[0], cy, CIRCLE[1], "lodestone");
        g.soul("wyld_woods", "rift_enigma_flower_pot_1", CIRCLE[0] + 1, cy, CIRCLE[1], "In the middle of the old stone circle east of the Cradle.");
        // wave 3: the Split or Steal host sits inside the circle, two coloured stones either side of him
        int hy = g.ground(CIRCLE[0] - 2, CIRCLE[1]);
        g.stand("npc.split_steal", CIRCLE[0] - 2, hy, CIRCLE[1], -90f);
        g.c.set(CIRCLE[0] - 2, g.ground(CIRCLE[0] - 2, CIRCLE[1] - 2), CIRCLE[1] - 2, "lime_glazed_terracotta");
        g.c.set(CIRCLE[0] - 2, g.ground(CIRCLE[0] - 2, CIRCLE[1] + 2), CIRCLE[1] + 2, "red_glazed_terracotta");

        // ---- the pond (already carved): a soul on its bed ----
        int bed = pondBed(g, POND[0], POND[1]);
        g.soul("wyld_woods", "rift_enigma_pressure_water", POND[0], bed, POND[1], "At the bottom of the thicket pond, under the water.");
        // caducous stems around the pond
        for (int i = 0; i < 26; i++) {
            double a = i * 0.71;
            int px = POND[0] + (int) Math.round(Math.cos(a) * (11 + i % 3)), pz = POND[1] + (int) Math.round(Math.sin(a) * (11 + i % 3));
            int py = g.ground(px, pz);
            if (g.h.wet(px, pz) || !g.c.isAir(px, py + 1, pz) || g.blocked(px, pz, 1)) continue;
            g.c.set(px, py, pz, "moss_block");
            g.c.set(px, py + 1, pz, "big_dripleaf_stem[facing=north,waterlogged=false]");
            g.c.set(px, py + 2, pz, "big_dripleaf[facing=north,tilt=none,waterlogged=false]");
        }

        // ---- the south woods and the edge ----
        int ey = g.ground(-30, 150);
        g.soul("wyld_woods", "rift_enigma_bark", -30, ey, 150, "Deep in the south woods, beneath the pale oaks.");
        int[] edge = edgeSpot(g, -40, 200, 0, -1);
        if (edge != null) g.soul("wyld_woods", edge[0], edge[1], edge[2], "Where the south woods end at the void.");

        // mobs
        g.mobArea("mobs.wyld_shy", -70, 92, 15, 200, 66, 84);
        g.mobArea("mobs.wyld_larva", -75, -60, -10, -5, 66, 84);
        for (int[] p : new int[][]{{-40, -5}, {-30, 55}, {0, 25}, {-60, 30}, {20, 0}, {-10, 75}})
            lamp(g, p[0], p[1], "street");
    }

    private static void greatTree(RiftCtx g) {
        int cx = TREE[0], cz = TREE[1];
        int y = g.ground(cx, cz);
        int height = 44;
        g.res.reserve("great_tree", Box.of(cx - 12, y - 5, cz - 12, cx + 12, y + height + 14, cz + 12));
        // trunk: a thick round pale-and-dark bole
        for (int dz = -4; dz <= 4; dz++)
            for (int dx = -4; dx <= 4; dx++) {
                double d = Math.hypot(dx, dz);
                if (d > 4.3) continue;
                for (int k = 0; k <= height; k++) g.c.set(cx + dx, y + k, cz + dz, d > 3.2 ? "pale_oak_wood[axis=y]" : "dark_oak_log[axis=y]");
                Builders.footing(g.c, cx + dx, y, cz + dz, Fill.of("dirt"), 6);
            }
        // roots
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + 0.3;
            for (int r = 4; r <= 8; r++) {
                int rx = cx + (int) Math.round(Math.cos(a) * r), rz = cz + (int) Math.round(Math.sin(a) * r);
                int ry = g.ground(rx, rz);
                if (r < 7) g.c.set(rx, ry + 1, rz, "pale_oak_wood[axis=y]");
            }
        }
        // canopy: a flattened leaf dome, hollow under the crown deck
        int top = y + height;
        for (int dy = -6; dy <= 8; dy++)
            for (int dz = -13; dz <= 13; dz++)
                for (int dx = -13; dx <= 13; dx++) {
                    double rr = 13 - Math.abs(dy) * 0.9;
                    double d = Math.hypot(dx, dz);
                    if (d > rr || Hash.unit(g.seed, cx + dx, top + dy, cz + dz) < 0.08) continue;
                    if (d < 4.5 && dy <= 0) continue;
                    int bx = cx + dx, by = top + dy, bz = cz + dz;
                    if (g.c.isAir(bx, by, bz)) g.c.set(bx, by, bz, Hash.unit(g.seed, bx, by, bz) < 0.7 ? "pale_oak_leaves[persistent=true]" : "dark_oak_leaves[persistent=true]");
                }
        // the crown deck
        for (int dz = -6; dz <= 6; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                if (Math.hypot(dx, dz) > 6.3) continue;
                g.c.set(cx + dx, top + 1, cz + dz, "stripped_pale_oak_wood[axis=y]");
                for (int k = 2; k <= 4; k++) g.c.set(cx + dx, top + k, cz + dz, "air");
            }
        g.c.set(cx, top + 2, cz, "amethyst_cluster[facing=up,waterlogged=false]");
        g.c.set(cx + 5, top + 2, cz, Blocks.lantern(false));
        g.c.set(cx - 5, top + 2, cz, Blocks.lantern(false));
        // the spiral of steps: 2x2 pads, one block higher each, one block apart
        double a = 0;
        double r = 7.0;
        int steps = height;
        int mid = steps / 2;
        for (int i = 0; i < steps; i++) {
            int px = cx + (int) Math.round(Math.cos(a) * r), pz = cz + (int) Math.round(Math.sin(a) * r);
            int py = y + 1 + i;
            for (int dz = 0; dz <= 1; dz++)
                for (int dx = 0; dx <= 1; dx++) {
                    g.c.set(px + dx, py, pz + dz, i % 5 == 4 ? "moss_block" : "stripped_dark_oak_wood[axis=y]");
                    for (int k = 1; k <= 3; k++) g.c.set(px + dx, py + k, pz + dz, "air");
                }
            // a branch from the trunk carries every step, so nothing floats (4-connected, one block under the pad)
            branch(g, cx + (int) Math.round(Math.cos(a) * 3), cz + (int) Math.round(Math.sin(a) * 3), px, pz, py - 1);
            if (i == mid) g.soul("wyld_woods", "rift_enigma_silk_1", px, py, pz, "Halfway up the Great Wyld Tree's spiral of steps.");
            a += 3.1 / r;
        }
        int sx = cx + 9, sz = cz;
        g.stand("charm.supreme.start", sx, g.ground(sx, sz), sz, 90f);
        g.l.standAt("charm.supreme.top", cx + 3, top + 1, cz + 3, 0f);
        g.l.data("tree.y", String.valueOf(y));
        g.l.data("tree.top", String.valueOf(top + 1));
    }

    /** A 4-connected line of logs at height y from (x0, z0) to (x1, z1). */
    private static void branch(RiftCtx g, int x0, int z0, int x1, int z1, int y) {
        int x = x0, z = z0;
        for (int guard = 0; guard < 40; guard++) {
            if (g.c.isAir(x, y, z)) g.c.set(x, y, z, Blocks.log("dark_oak_log", Math.abs(x1 - x0) >= Math.abs(z1 - z0) ? 'x' : 'z'));
            if (x == x1 && z == z1) break;
            if (Math.abs(x1 - x) >= Math.abs(z1 - z) && x != x1) x += Integer.signum(x1 - x); else z += Integer.signum(z1 - z);
        }
    }

    /** The deepest bed block of a pond around (x, z). */
    private static int pondBed(RiftCtx g, int x, int z) {
        for (int y = RiftCtx.BASE + 5; y > RiftCtx.BASE - 15; y--) {
            String b = BlockState.name(g.c.get(x, y, z));
            if (!b.equals("water") && !g.c.isAir(x, y, z)) return y;
        }
        return RiftCtx.BASE - 4;
    }

    /** Walk from (x, z) in direction (dx, dz) until the land ends; the last land column. */
    private static int[] edgeSpot(RiftCtx g, int x, int z, int dx, int dz) {
        int lx = x, lz = z;
        for (int i = 0; i < 120; i++) {
            int nx = x + dx * i, nz = z + dz * i;
            if (g.h.has(nx, nz)) { lx = nx; lz = nz; }
        }
        lx -= dx * 3; lz -= dz * 3;
        if (!g.h.has(lx, lz)) return null;
        return new int[]{lx, g.ground(lx, lz), lz};
    }

    static String boxStr(Box b) {
        return b.minX() + "," + b.minY() + "," + b.minZ() + "," + b.maxX() + "," + b.maxY() + "," + b.maxZ();
    }

    // =========================================================================================
    // Black Lagoon
    // =========================================================================================

    static void lagoon(RiftCtx g) {
        int y = RiftCtx.WATER + 1;
        // the stilt hut
        Builders.House hut = house(g, "lagoon_hut", HUT[0] - 4, y, HUT[1] - 3, 9, 7, "east", 2,
                "mangrove_planks:4,stripped_mangrove_log:1", "mangrove_log", "mangrove", "gable");
        if (hut != null) {
            g.stand("npc.vel_ka", hut.doorStep[0], hut.doorStep[1], hut.doorStep[2], -90f);
            g.soul("black_lagoon", "rift_tel_kar", hut.inside[0], hut.inside[1], hut.inside[2], "Inside Old Vel'ka's hut on the lagoon.");
        }
        // the Leeches Lair: a hollow mound with a pool floor
        int cx = LAIR[0], cz = LAIR[1];
        int floor = RiftCtx.BASE + 1;
        g.res.reserve("leeches_lair", Box.of(cx - LAIR_R, 0, cz - LAIR_R, cx + LAIR_R, 0, cz + LAIR_R));
        Shapes.ellipsoid(g.c, cx, floor + 3, cz, 14, 8, 12, Fill.of("air"), 0);
        for (int z = cz - 13; z <= cz + 13; z++)
            for (int x = cx - 15; x <= cx + 15; x++) {
                double d = Math.hypot((x - cx) / 14.0, (z - cz) / 12.0);
                if (d > 1.0) continue;
                for (int yy = floor - 6; yy < floor; yy++) g.c.set(x, yy, z, "mud");
                g.c.set(x, floor, z, d < 0.45 ? "water" : Palette.of("mud:3,muddy_mangrove_roots:1,moss_block:2").at(x, floor, z));
                if (d < 0.45) g.c.set(x, floor - 1, z, "water");
            }
        // glow in the walls
        for (int i = 0; i < 30; i++) {
            double a = i * 0.9;
            int lx = cx + (int) Math.round(Math.cos(a) * 13), lz = cz + (int) Math.round(Math.sin(a) * 11);
            int ly = floor + 2 + (i % 4);
            if (!g.c.isAir(lx, ly, lz)) g.c.set(lx, ly, lz, i % 3 == 0 ? "ochre_froglight[axis=y]" : "shroomlight");
        }
        // the mouth: a tunnel from the north-east road end into the chamber
        int[] mouth = {cx + 14, cz - 16};
        int my = g.ground(mouth[0], mouth[1]);
        Terrain.carveTunnel(g.c, new double[][]{{mouth[0], my + 2.5, mouth[1]}, {cx + 7, floor + 2.5, cz - 7}}, 2.2);
        for (int i = 0; i <= 12; i++) {
            double t = i / 12.0;
            int tx = (int) Math.round(mouth[0] + (cx + 7 - mouth[0]) * t), tz = (int) Math.round(mouth[1] + (cz - 7 - mouth[1]) * t);
            int ty = (int) Math.round(my + (floor - my) * t);
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                g.c.set(tx + ox, ty, tz + oz, "mud_bricks");
                for (int k = 1; k <= 3; k++) g.c.set(tx + ox, ty + k, tz + oz, "air");
            }
        }
        g.stand("npc.lagoon_scientist", mouth[0] + 2, g.ground(mouth[0] + 2, mouth[1] - 2), mouth[1] - 2, 135f);
        g.stand("boss.leech", cx - 4, floor, cz + 4, 0f);
        g.soul("black_lagoon", "rift_lagoon_parkour", cx - 9, floor, cz - 2, "At the back of the Leeches Lair, past the pool.");
        g.l.data("lair.box", boxStr(Box.of(cx - 15, floor - 1, cz - 13, cx + 15, floor + 11, cz + 13)));
        // the deepest pool and a mangrove giant
        int[] deep = deepestPool(g);
        if (deep != null) g.soul("black_lagoon", deep[0], deep[1], deep[2], "Sunk in the deepest pool of the Black Lagoon.");
        int mx = -175, mz = 50;
        int mgy = g.ground(mx, mz);
        mangrove(g, mx, mgy, mz);
        g.soul("black_lagoon", "rift_rocks_enigma", mx + 4, g.ground(mx + 4, mz + 1), mz + 1, "Among the roots of the giant mangrove on the lagoon's west shore.");
        int[] edge = edgeSpot(g, -170, 140, -1, 0);
        if (edge != null) g.soul("black_lagoon", edge[0], edge[1], edge[2], "On the reed bank where the lagoon spills into the void.");
        // lily pads on the pools
        g.h.forEach((x, z, top, bottom) -> {
            if (!g.h.wet(x, z) || Math.hypot(x - LAGOON[0], z - LAGOON[1]) > 64) return;
            if (Hash.chance(Hash.salt(g.seed, "lily"), x, z, 0.06) && g.c.isAir(x, top + 1, z)) g.c.set(x, top + 1, z, "lily_pad");
        });
        g.mobArea("mobs.lagoon_leech", -195, 80, -110, 150, 64, 76);
        g.mobArea("mobs.lagoon_deadgehog", -205, 10, -120, 75, 66, 80);
        for (int[] p : new int[][]{{-100, 64}, {-135, 92}, {-150, 64}})
            lamp(g, p[0], p[1], "soul");
    }

    private static int[] deepestPool(RiftCtx g) {
        int[] best = null;
        int bestY = Integer.MAX_VALUE;
        for (int z = LAGOON[1] - 50; z <= LAGOON[1] + 50; z += 2)
            for (int x = LAGOON[0] - 50; x <= LAGOON[0] + 50; x += 2) {
                if (!g.h.wet(x, z)) continue;
                int bed = pondBed(g, x, z);
                if (bed < bestY) { bestY = bed; best = new int[]{x, bed, z}; }
            }
        return best;
    }

    private static void mangrove(RiftCtx g, int x, int y, int z) {
        for (int k = 1; k <= 12; k++) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) g.c.set(x + dx, y + k, z + dz, "mangrove_log[axis=y]");
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            for (int r = 1; r <= 2; r++) {
                int rx = x + (int) Math.round(Math.cos(a) * (r + 0.5)), rz = z + (int) Math.round(Math.sin(a) * (r + 0.5));
                int ry = y + 3 - r;
                if (ry > y && g.c.isAir(rx, ry, rz)) g.c.set(rx, ry, rz, "mangrove_roots[waterlogged=false]");
            }
        }
        for (int dy = 10; dy <= 15; dy++)
            for (int dz = -6; dz <= 7; dz++)
                for (int dx = -6; dx <= 7; dx++) {
                    double rr = 7 - Math.abs(dy - 12) * 1.2;
                    if (Math.hypot(dx - 0.5, dz - 0.5) > rr) continue;
                    if (g.c.isAir(x + dx, y + dy, z + dz)) g.c.set(x + dx, y + dy, z + dz, "mangrove_leaves[persistent=true,waterlogged=false]");
                }
        g.res.reserve("mangrove", Box.of(x - 7, y - 4, z - 7, x + 8, y + 16, z + 8));
    }

    // =========================================================================================
    // West Village
    // =========================================================================================

    static void westVillage(RiftCtx g) {
        int y = g.squareY;
        g.res.reserve("west_square", P_SQUARE);
        pave(g, P_SQUARE, y, Palette.of("tuff_bricks:4,mossy_cobblestone:2,cobbled_deepslate:2,polished_tuff:1"));
        clearAbove(g, P_SQUARE, y, 5);
        Builders.well(g.c, WEST[0], y, WEST[1]);
        g.soul("west_village", "rift_hot_dog", WEST[0] + 4, y, WEST[1] + 4, "On the cobbles beside the West Village well.");
        String[][] looks = {
                {"warped_planks:4,stripped_warped_stem:1", "warped_stem", "warped", "gable"},
                {"pink_terracotta:3,white_terracotta:2,pink_concrete:1", "stripped_cherry_log", "cherry", "gable"},
                {"crimson_planks:4,stripped_crimson_stem:1", "crimson_stem", "crimson", "hip"},
                {"mangrove_planks:4,mud_bricks:2", "mangrove_log", "dark_oak", "gable"},
                {"pale_oak_planks:4,stripped_pale_oak_log:1", "pale_oak_log", "deepslate_tile", "hip"},
        };
        int i = 0;
        for (Object[] w : WEST_HOUSES) {
            int x = (int) w[0], z = (int) w[1], wd = (int) w[2], dp = (int) w[3];
            String[] lk = looks[i++ % looks.length];
            Builders.House b = house(g, (String) w[5], x, y, z, wd, dp, (String) w[4], w[5].equals("cake_house") ? 2 : 1, lk[0], lk[1], lk[2], lk[3]);
            if (b == null) continue;
            switch ((String) w[5]) {
                case "cake_house" -> {
                    g.soul("west_village", "rift_cake_house_1", b.inside[0], b.inside[1], b.inside[2], "On the ground floor of the Cake House.");
                    g.soul("west_village", "rift_cake_house_2", b.inside[0] + 1, y + b.floorHeight, b.inside[2], "Upstairs in the Cake House.");
                }
                case "infested_house" -> {
                    g.mobArea("mobs.west_vermin", x + 1, z + 1, x + wd - 2, z + dp - 2, y + 1, y + 3);
                    g.l.data("infested.box", boxStr(Box.of(x, y, z, x + wd - 1, b.peakY, z + dp - 1)));
                }
                case "west_house_1" -> g.soul("west_village", "rift_kat_soul", b.inside[0], b.inside[1], b.inside[2], "Inside the north-west house of the West Village.");
                default -> { }
            }
        }
        // the Mirror House: the stairs down to the Looking-Glass Halls are inside
        Builders.House mh = house(g, "mirror_house", P_MIRROR.minX() + 2, y, P_MIRROR.minZ() + 2, 13, 13, "west", 1,
                "quartz_bricks:3,calcite:2,light_blue_stained_glass:1", "stripped_birch_log", "deepslate_tile", "flat");
        if (mh != null) {
            g.stand("npc.quillon", mh.doorStep[0], mh.doorStep[1], mh.doorStep[2] - 2, -90f);
            g.l.data("mirror_house.floor", String.valueOf(y));
        }
        g.stand("npc.racer", WEST[0] + 9, y, WEST[1] + 9, 135f);
        g.stand("race.start", WEST[0] + 6, y, WEST[1] + 11, 180f);
        g.stand("race.finish", WEST[0] + 6, y, WEST[1] + 11, 180f);
        int[][] cps = {{-172, -52}, {-185, -95}, {-163, -126}, {-128, -134}, {-96, -78}, {-120, -45}};
        for (int[] cp : cps) {
            int cyy = g.ground(cp[0], cp[1]);
            g.raceCp(cp[0], cyy, cp[1]);
            g.c.set(cp[0], cyy + 1, cp[1], "air");
            Builders.banner(g.c, cp[0] + 2, cyy, cp[1], "lime", 0);
        }
        g.mobArea("mobs.west_shadow", -215, -200, -80, -25, 66, 86);
        for (int[] p : new int[][]{{-150, -100}, {-130, -100}, {-150, -70}, {-128, -70}, {-165, -85}})
            lamp(g, p[0], p[1], "street");
    }

    // =========================================================================================
    // The Looking-Glass Halls (underground, under the West Village)
    // =========================================================================================

    static final int MV_FLOOR = 53;

    static void mirrorverse(RiftCtx g) {
        int f = MV_FLOOR;
        String hx = g.l.data().get("mirror_house.floor");
        int houseFloor = hx == null ? g.squareY : Integer.parseInt(hx);
        Box shell = Box.of(-136, f - 8, -162, -62, f + 13, -122);
        Shapes.box(g.c, shell, Palette.of("polished_deepslate:4,deepslate_tiles:2,calcite:1"));
        // the stairs: from inside the Mirror House north, down to the corridor
        int sx0 = P_MIRROR.minX() + 6, sx1 = sx0 + 2;
        int startZ = P_MIRROR.maxZ() - 4;
        int z = startZ, yy = houseFloor;
        while (yy > f) {
            for (int x = sx0; x <= sx1; x++) {
                g.c.set(x, yy, z, "quartz_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
                g.c.set(x, yy - 1, z, "quartz_block");
                for (int k = 1; k <= 4; k++) g.c.set(x, yy + k, z, "air");
            }
            if (yy < houseFloor - 3) g.c.set(sx0 - 1, yy + 2, z, "sea_lantern");
            z--; yy--;
        }
        // flat run to the corridor
        for (; z >= -126; z--)
            for (int x = sx0; x <= sx1; x++) {
                g.c.set(x, f, z, "quartz_block");
                for (int k = 1; k <= 4; k++) g.c.set(x, f + k, z, "air");
            }
        // the tunnel walls where the stairs cut through rock
        // corridor along x
        Palette mirrorWall = Palette.of("quartz_bricks:3,calcite:2,light_blue_stained_glass:1");
        for (int x = -134; x <= -64; x++)
            for (int cz = -130; cz <= -126; cz++) {
                g.c.set(x, f, cz, (x + cz) % 2 == 0 ? "black_concrete" : "white_concrete");
                for (int k = 1; k <= 5; k++) g.c.set(x, f + k, cz, "air");
                if (x % 8 == 0 && cz == -126) g.c.set(x, f + 5, cz, "sea_lantern");
            }
        // ---- room A: the upside-down parkour ----
        Box ra = Box.of(-134, f - 6, -160, -112, f + 11, -132);
        carve(g, ra.minX(), ra.minY() + 1, ra.minZ(), ra.maxX(), ra.maxY() - 1, ra.maxZ());
        for (int x = ra.minX(); x <= ra.maxX(); x++)
            for (int cz = ra.minZ(); cz <= ra.maxZ(); cz++) {
                g.c.set(x, ra.minY(), cz, Palette.of("crying_obsidian:2,obsidian:3,magma_block:1").at(x, ra.minY(), cz));
                g.c.set(x, ra.maxY(), cz, "grass_block");
                if (Hash.chance(g.seed, x, cz, 0.04) && g.c.isAir(x, ra.maxY() - 1, cz)) g.c.set(x, ra.maxY() - 1, cz, "spore_blossom");
            }
        // upside-down trees hanging from the grass ceiling
        for (int[] p : new int[][]{{-129, -150}, {-118, -142}, {-125, -138}}) {
            for (int k = 1; k <= 5; k++) g.c.set(p[0], ra.maxY() - k, p[1], "oak_log[axis=y]");
            Shapes.sphere(g.c, p[0], ra.maxY() - 6, p[1], 2.2, "oak_leaves[persistent=true]");
        }
        // ledges
        for (int x = ra.minX(); x <= ra.maxX(); x++) {
            for (int cz = -135; cz <= -132; cz++) { g.c.set(x, f, cz, "quartz_block"); clearCol(g, x, f, cz, 4); }
            for (int cz = -159; cz <= -156; cz++) { g.c.set(x, f, cz, "quartz_block"); clearCol(g, x, f, cz, 4); }
        }
        // the door from the corridor
        for (int x = -124; x <= -122; x++) for (int k = 1; k <= 3; k++) g.c.set(x, f + k, -131, "air");
        // the jumps: a zigzag of single blocks over the pit
        int[][] jumps = {{-123, -137, 0}, {-122, -139, 1}, {-123, -141, 1}, {-124, -143, 2}, {-123, -145, 1}, {-122, -147, 2},
                {-123, -149, 2}, {-124, -151, 1}, {-123, -153, 1}};
        for (int[] j : jumps) {
            g.c.set(j[0], f + j[2], j[1], "white_concrete");
            for (int py = ra.minY() + 1; py < f + j[2]; py++) g.c.set(j[0], py, j[1], "calcite");
            for (int k = 1; k <= 3; k++) g.c.set(j[0], f + j[2] + k, j[1], "air");
        }
        g.stand("mirror.parkour.start", -123, f, -133, 180f);
        g.l.standAt("mirror.parkour.end", -123, f, -158, 0f);
        g.soul("mirrorverse", "rift_dolphin_soul", -116, f, -158, "At the far end of the upside-down room in the Looking-Glass Halls.");
        g.l.area("mirror.parkour.room", Box.of(-133, f - 5, -155, -113, f + 9, -136));
        // ---- room B: the hidden-path maze ----
        Box rb = Box.of(-108, f - 2, -160, -88, f + 8, -132);
        carve(g, rb.minX() + 1, f + 1, rb.minZ() + 1, rb.maxX() - 1, rb.maxY() - 1, rb.maxZ() - 1);
        for (int x = rb.minX() + 1; x <= rb.maxX() - 1; x++)
            for (int cz = rb.minZ() + 1; cz <= rb.maxZ() - 1; cz++) {
                boolean tile = x >= -103 && x <= -93 && cz >= -154 && cz <= -138;
                g.c.set(x, f, cz, tile ? "purpur_block" : "quartz_block");
                g.c.set(x, f - 1, cz, tile ? "lava" : "quartz_block");
                g.c.set(x, f - 2, cz, "obsidian");
                g.c.set(x, rb.maxY(), cz, (x + cz) % 3 == 0 ? "sea_lantern" : "quartz_bricks");
            }
        for (int x = -99; x <= -97; x++) for (int dz = -133; dz <= -131; dz++) { g.c.set(x, f, dz, "quartz_block"); for (int k = 1; k <= 3; k++) g.c.set(x, f + k, dz, "air"); }
        g.stand("mirror.maze.start", -98, f, -136, 180f);
        g.stand("mirror.maze.end", -98, f, -157, 0f);
        g.l.area("mirror.maze.tiles", Box.of(-103, f, -154, -93, f, -138));
        // ---- room C: the dance floor ----
        Box rc = Box.of(-86, f - 2, -156, -64, f + 8, -132);
        carve(g, rc.minX() + 1, f + 1, rc.minZ() + 1, rc.maxX() - 1, rc.maxY() - 1, rc.maxZ() - 1);
        String[] wool = {"red_wool", "orange_wool", "yellow_wool", "lime_wool", "cyan_wool", "blue_wool", "purple_wool", "magenta_wool", "white_wool"};
        for (int x = rc.minX() + 1; x <= rc.maxX() - 1; x++)
            for (int cz = rc.minZ() + 1; cz <= rc.maxZ() - 1; cz++) {
                g.c.set(x, f, cz, "black_concrete");
                g.c.set(x, rc.maxY(), cz, (x + cz) % 4 == 0 ? "sea_lantern" : "black_concrete");
            }
        int dx0 = -79, dz0 = -150;
        for (int p = 0; p < 9; p++) {
            int px = dx0 + (p % 3) * 3, pz = dz0 + (p / 3) * 3;
            for (int ox = 0; ox < 3; ox++) for (int oz = 0; oz < 3; oz++) g.c.set(px + ox, f, pz + oz, wool[p]);
        }
        for (int x = -76; x <= -74; x++) for (int dz = -133; dz <= -131; dz++) { g.c.set(x, f, dz, "black_concrete"); for (int k = 1; k <= 3; k++) g.c.set(x, f + k, dz, "air"); }
        g.stand("mirror.dance.center", -75, f, -146, 180f);
        g.l.area("mirror.dance.floor", Box.of(dx0, f, dz0, dx0 + 8, f, dz0 + 8));
        g.l.data("mirror.box", boxStr(Box.of(-136, f - 8, -162, -62, f + 13, -122)));
    }

    private static void carve(RiftCtx g, int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) g.c.set(x, y, z, "air");
    }

    private static void clearCol(RiftCtx g, int x, int y, int z, int h) {
        for (int k = 1; k <= h; k++) g.c.set(x, y + k, z, "air");
    }

    // =========================================================================================
    // Dread Acres
    // =========================================================================================

    static void dreadfarm(RiftCtx g) {
        int y = g.dreadY;
        // the berberis rows (dead bushes on coarse dirt) and the agaricus beds (mushrooms on podzol)
        Box berberis = Box.of(-42, 0, -158, -14, 0, -122);
        Box agaricus = Box.of(-6, 0, -162, 20, 0, -140);
        g.res.reserve("berberis_field", berberis);
        g.res.reserve("agaricus_beds", agaricus);
        for (int z = berberis.minZ(); z <= berberis.maxZ(); z++)
            for (int x = berberis.minX(); x <= berberis.maxX(); x++) {
                boolean row = (x - berberis.minX()) % 3 != 2;
                g.c.set(x, y, z, row ? "coarse_dirt" : "dirt_path");
                clearCol(g, x, y, z, 3);
                if (row && (z % 2 == 0)) g.c.set(x, y + 1, z, "dead_bush");
            }
        for (int z = agaricus.minZ(); z <= agaricus.maxZ(); z++)
            for (int x = agaricus.minX(); x <= agaricus.maxX(); x++) {
                boolean bed = (z - agaricus.minZ()) % 4 != 3;
                g.c.set(x, y, z, bed ? (Hash.chance(g.seed, x, z, 0.3) ? "mycelium" : "podzol") : "dirt_path");
                clearCol(g, x, y, z, 3);
                if (bed && Hash.chance(g.seed + 3, x, z, 0.45)) g.c.set(x, y + 1, z, "brown_mushroom");
            }
        Builders.fenceLine(g.c, new int[][]{{berberis.minX() - 1, berberis.minZ() - 1}, {berberis.maxX() + 1, berberis.minZ() - 1},
                {berberis.maxX() + 1, berberis.maxZ() + 1}}, "dark_oak_fence", false);
        // the barn (Farmer Wilma)
        Builders.House barn = house(g, "barn", 10, y, -124, 15, 11, "west", 2,
                "red_terracotta:3,stripped_dark_oak_log:1,brown_terracotta:1", "dark_oak_log", "dark_oak", "gable");
        if (barn != null) {
            g.stand("npc.wilma", barn.doorStep[0] - 1, barn.doorStep[1], barn.doorStep[2], -90f);
            g.soul("dreadfarm", "rift_buttons_soul", barn.inside[0], barn.inside[1] + barn.floorHeight, barn.inside[2], "In the hayloft of the Dread Acres barn.");
            g.egg(24 + 2, g.ground(26, -111), -111);
            for (int k = 0; k < 4; k++) g.c.set(12 + k, y + 1, -126, "hay_block[axis=x]");
        }
        // the silo
        Builders.Tower silo = new Builders.Tower(32, y, -140);
        silo.radius = 3; silo.height = 18; silo.top = "cone"; silo.door = "west";
        silo.wall = Palette.of("terracotta:3,light_gray_terracotta:1,iron_block:1");
        silo.roofFill = Palette.of("iron_block:2,light_gray_concrete:1");
        if (g.res.reserve("silo", silo.reserve())) {
            silo.build(g.c);
            g.soul("dreadfarm", "rift_dreadfarm_flowerpot", silo.inside[0], silo.inside[1], silo.inside[2], "Inside the silo of Dread Acres.");
            g.egg(silo.doorStep[0], silo.doorStep[1], silo.doorStep[2] + 3);
        }
        // the beanstalk: a ladder to the sky
        int bx = -26, bz = -170;
        int by = g.ground(bx, bz);
        g.res.reserve("beanstalk", Box.of(bx - 3, by - 2, bz - 3, bx + 3, by + 36, bz + 3));
        for (int k = 1; k <= 32; k++)
            for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++)
                g.c.set(bx + ox, by + k, bz + oz, (k + ox + oz) % 4 == 0 ? "moss_block" : "green_terracotta");
        for (int k = 1; k <= 33; k++) g.c.set(bx, by + k, bz + 2, Blocks.ladder("south"));
        for (int ox = -2; ox <= 2; ox++) for (int oz = -2; oz <= 2; oz++) g.c.set(bx + ox, by + 33, bz + oz, "azalea_leaves[persistent=true]");
        g.c.set(bx, by + 33, bz + 2, Blocks.ladder("south"));
        g.c.set(bx, by + 33, bz, "moss_block");
        for (int k = 34; k <= 36; k++) g.c.set(bx, by + k, bz, "air");
        g.soul("dreadfarm", "rift_beanstalk", bx, by + 33, bz, "At the very top of the giant beanstalk.");
        g.egg(bx + 3, by, bz + 4);
        // a scarecrow and some hay
        int sx = -28, sz = -116;
        int sy = g.ground(sx, sz);
        g.c.set(sx, sy + 1, sz, "dark_oak_fence");
        g.c.set(sx, sy + 2, sz, "hay_block[axis=y]");
        g.c.set(sx, sy + 3, sz, "carved_pumpkin[facing=south]");
        g.c.set(sx - 1, sy + 2, sz, "dark_oak_fence");
        g.c.set(sx + 1, sy + 2, sz, "dark_oak_fence");
        g.egg(sx + 1, sy, sz + 2);
        g.soul("dreadfarm", "rift_dreadfarm_balloons", sx, sy, sz - 2, "At the feet of the Dread Acres scarecrow.");
        Builders.crates(g.c, 4, y, -133, g.seed);
        g.egg(6, y, -136);
        g.mobArea("mobs.dread_volt", -60, -195, 60, -100, 66, 84);
        for (int[] p : new int[][]{{-10, -110}, {-10, -140}, {28, -105}, {-35, -108}})
            lamp(g, p[0], p[1], "post");
        g.l.data("res.berberis", boxStr(Box.of(berberis.minX(), y + 1, berberis.minZ(), berberis.maxX(), y + 1, berberis.maxZ())));
        g.l.data("res.agaricus", boxStr(Box.of(agaricus.minX(), y + 1, agaricus.minZ(), agaricus.maxX(), y + 1, agaricus.maxZ())));
    }

    // =========================================================================================
    // Village Plaza
    // =========================================================================================

    static void plaza(RiftCtx g) {
        int y = g.plazaY;
        Box sq = Box.of(76, 0, -36, 116, 0, -4);
        g.res.reserve("plaza_square", sq);
        pave(g, sq, y, Palette.of("stone_bricks:4,polished_andesite:3,tuff_bricks:2,mossy_stone_bricks:1"));
        clearAbove(g, sq, y, 6);
        Builders.fountain(g.c, PLAZA[0] - 4, y, PLAZA[1], 4, Palette.of("stone_bricks:3,polished_tuff:1"), g.seed);
        g.soul("village_plaza", "rift_lonely_dave", PLAZA[0] - 4, y + 1, PLAZA[1] + 4, "On the rim of the Village Plaza fountain.");
        // the town hall (Mayor Barnaby)
        Builders.House hall = house(g, "town_hall", 88, y, -49, 17, 11, "south", 2,
                "polished_andesite:3,stone_bricks:2,chiseled_stone_bricks:1", "spruce_log", "deepslate_tile", "hip");
        if (hall != null) {
            g.stand("npc.barnaby", hall.doorStep[0] + 2, hall.doorStep[1], hall.doorStep[2] + 1, 0f);
            g.soul("village_plaza", "rift_scammer", hall.inside[0] + 3, hall.inside[1], hall.inside[2], "Inside the town hall, by the Mayor's desk.");
            g.soul("village_plaza", "rift_back2basics", hall.inside[0] - 2, hall.inside[1] + hall.floorHeight, hall.inside[2], "Upstairs in the town hall.");
        }
        // four clerk offices on the east side, in order from north to south
        int[] cz = {-46, -36, -26, -16};
        for (int i = 0; i < 4; i++) {
            Builders.House off = house(g, "clerk_office_" + (i + 1), 121, y, cz[i], 7, 7, "west", 1,
                    "white_terracotta:3,light_gray_terracotta:1", "birch_log", "spruce", "gable");
            if (off == null) continue;
            g.stand("npc.clerk_" + (i + 1), off.doorStep[0] - 1, off.doorStep[1], off.doorStep[2], -90f);
            g.c.set(off.inside[0], y + 1, off.inside[2] + 1, "lectern[facing=west,has_book=true,powered=false]");
            if (i == 3) g.soul("village_plaza", "rift_horsing_around", off.inside[0], off.inside[1], off.inside[2] - 1, "In the last clerk's office, where the forms go to be filed.");
        }
        // the tailor
        Builders.House tailor = house(g, "tailor", 90, y, -1, 11, 9, "north", 1,
                "white_wool:2,pink_terracotta:2,birch_planks:2", "stripped_birch_log", "cherry", "gable");
        if (tailor != null) {
            g.stand("npc.tailor", tailor.doorStep[0], tailor.doorStep[1], tailor.doorStep[2] - 1, 180f);
            g.soul("village_plaza", "rift_village_phantom", tailor.inside[0], tailor.inside[1], tailor.inside[2], "Among the bolts of cloth inside the tailor's shop.");
        }
        // the plaza's mote stall
        Builders.Stall st = Builders.marketStall(g.c, 80, y, -22, "east", "magenta", "white", g.seed + 9);   // on the paved square: no own reservation
        g.stand("npc.plaza_monger", st.vendor()[0], st.vendor()[1], st.vendor()[2], -90f);
        // the clock tower
        Builders.Tower clock = new Builders.Tower(74, y, -44);
        clock.round = false; clock.radius = 3; clock.height = 24; clock.top = "battlements"; clock.door = "east";
        clock.wall = Palette.of("stone_bricks:3,polished_andesite:1");
        if (g.res.reserve("clock_tower", clock.reserve())) {
            clock.build(g.c);
            g.soul("village_plaza", clock.platform[0], clock.platform[1], clock.platform[2], "On top of the plaza clock tower.");
            g.c.set(74, y + 20, -48, "white_concrete");
        }
        // the rabbit pen and carrot patch (south-west)
        Box carrots = Box.of(72, 0, -1, 84, 0, 7);
        g.res.reserve("carrot_patch", carrots);
        for (int z = carrots.minZ(); z <= carrots.maxZ(); z++)
            for (int x = carrots.minX(); x <= carrots.maxX(); x++) {
                boolean water = x == 78 && z == 3;
                g.c.set(x, y, z, water ? "water" : "farmland[moisture=7]");
                clearCol(g, x, y, z, 3);
                if (!water) g.c.set(x, y + 1, z, "carrots[age=7]");
            }
        Builders.fenceLine(g.c, new int[][]{{71, -2}, {85, -2}, {85, 8}, {71, 8}}, "spruce_fence", true);
        Builders.gate(g.c, 78, y + 1, -2, "spruce", "north");
        g.soul("village_plaza", "rift_rabbit_under_hat", 70, g.ground(70, 10), 10, "By the rabbit pen's south-west corner.");
        g.l.data("res.carrots", boxStr(Box.of(carrots.minX(), y + 1, carrots.minZ(), carrots.maxX(), y + 1, carrots.maxZ())));
        g.mobArea("mobs.plaza_scribe", 135, -60, 175, 15, 66, 92);
        for (int[] p : new int[][]{{80, -34}, {112, -34}, {80, -6}, {112, -6}, {96, -2}})
            Builders.lampPost(g.c, p[0], y, p[1], "street", "south");
    }

    // =========================================================================================
    // The Living Cave
    // =========================================================================================

    static void livingCave(RiftCtx g) {
        int cx = LIVING[0], cz = LIVING[1];
        int floor = RiftCtx.BASE + 1;
        g.res.reserve("living_cave", Box.of(cx - LIVING_R, 0, cz - LIVING_R, cx + LIVING_R, 0, cz + LIVING_R));
        Shapes.ellipsoid(g.c, cx + 3, floor + 5, cz, 22, 11, 18, Fill.of("air"), 0);
        Palette wall = Palette.of("deepslate:4,tuff:2,blue_stained_glass:1,calcite:1");
        for (int z = cz - 19; z <= cz + 19; z++)
            for (int x = cx - 20; x <= cx + 26; x++) {
                double d = Math.hypot((x - cx - 3) / 22.0, (z - cz) / 18.0);
                if (d > 1.02) continue;
                boolean still = x > cx + 6;               // the Living Stillness: the frozen east half
                for (int yy = floor - 3; yy < floor; yy++) g.c.set(x, yy, z, "deepslate");
                g.c.set(x, floor, z, still ? Palette.of("snow_block:4,packed_ice:2,blue_ice:1").at(x, floor, z)
                        : Palette.of("deepslate_tiles:3,tuff:2,moss_block:1").at(x, floor, z));
                for (int yy = floor + 1; yy <= floor + 16; yy++) {
                    if (g.c.isAir(x, yy, z)) continue;
                    // the living metal veins in the walls
                    if (Hash.chance(g.seed + 77, x, yy, z, 0.07) && touchesAir(g, x, yy, z)) g.c.set(x, yy, z, "blue_stained_glass");
                    else if (still && touchesAir(g, x, yy, z) && Hash.chance(g.seed, x, yy, z, 0.5)) g.c.set(x, yy, z, "packed_ice");
                }
            }
        // entrance tunnel from the west
        int ex = cx - LIVING_R - 2;
        for (int x = ex; x <= cx - 14; x++)
            for (int z = cz - 2; z <= cz + 2; z++) {
                g.c.set(x, floor, z, "deepslate_tiles");
                for (int k = 1; k <= 4; k++) g.c.set(x, floor + k, z, "air");
                if (Math.abs(z - cz) == 2 && x % 6 == 0) g.c.set(x, floor + 3, z, "verdant_froglight[axis=y]");
            }
        int ey = g.ground(ex - 2, cz + 4);
        g.stand("npc.cave_warden", ex - 2, ey, cz + 4, -90f);
        g.stand("living.heart.home", cx, floor, cz, 0f);
        int[][] spots = {{-10, -8}, {-12, 7}, {-2, -13}, {0, 12}, {9, -10}, {12, 9}, {18, -3}, {4, 2}};
        for (int[] s : spots) {
            int hx = cx + s[0], hz = cz + s[1];
            int hy = g.groundBelow(hx, floor + 3, hz);
            g.heart(hx, hy, hz);
        }
        for (int i = 0; i < 10; i++) {
            int lx = cx - 14 + i * 4, lz = cz + (i % 2 == 0 ? -12 : 12);
            int ly = g.groundBelow(lx, floor + 3, lz);
            if (g.c.isAir(lx, ly + 1, lz)) g.c.set(lx, ly + 1, lz, i % 2 == 0 ? "pearlescent_froglight[axis=y]" : "sea_lantern");
        }
        g.soul("living_cave", "rift_cave_wall", cx - 15, g.groundBelow(cx - 15, floor + 3, cz - 9), cz - 9, "In a dark corner of the Living Cave, near the entrance.");
        g.soul("living_cave", "rift_cave_flowers", cx + 20, g.groundBelow(cx + 20, floor + 3, cz + 4), cz + 4, "At the frozen back wall of the Living Stillness.");
        g.mobArea("mobs.cave_autonull", cx - 16, cz - 14, cx + 4, cz + 14, floor + 1, floor + 4);
        g.mobArea("mobs.cave_frozille", cx + 8, cz - 14, cx + 22, cz + 14, floor + 1, floor + 4);
        g.l.data("living.box", boxStr(Box.of(cx - 20, floor - 1, cz - 19, cx + 26, floor + 16, cz + 19)));
    }

    private static boolean touchesAir(RiftCtx g, int x, int y, int z) {
        return g.c.isAir(x + 1, y, z) || g.c.isAir(x - 1, y, z) || g.c.isAir(x, y + 1, z) || g.c.isAir(x, y - 1, z)
                || g.c.isAir(x, y, z + 1) || g.c.isAir(x, y, z - 1);
    }

    // =========================================================================================
    // The Colosseum
    // =========================================================================================

    static void colosseum(RiftCtx g) {
        int y = g.colY, cx = COLOSSEUM[0], cz = COLOSSEUM[1];
        g.res.reserve("colosseum", P_COLOSSEUM);
        Palette stone = Palette.of("stone_bricks:4,cracked_stone_bricks:2,mossy_stone_bricks:1,tuff_bricks:1");
        for (int z = cz - 31; z <= cz + 31; z++)
            for (int x = cx - 31; x <= cx + 31; x++) {
                double d = Math.hypot(x - cx, z - cz);
                if (d > 30.5) continue;
                boolean gate = z < cz && Math.abs(x - cx) <= 2;
                if (d <= 16.5) {                                             // the pit
                    g.c.set(x, y, z, Palette.of("sand:3,smooth_sandstone:2,gravel:1").at(x, y, z));
                    clearCol(g, x, y, z, 14);
                    // steps up to the stands at the east, south and west
                    boolean step = d > 15.5 && (Math.abs(z - cz) <= 1 || (z > cz && Math.abs(x - cx) <= 1));
                    if (step) g.c.set(x, y + 1, z, "stone_bricks");
                } else if (d <= 17.5) {                                      // pit wall
                    g.c.set(x, y, z, "stone_bricks");
                    for (int k = 1; k <= 2; k++) g.c.set(x, y + k, z, gate ? "air" : stone.at(x, y + k, z));
                    for (int k = 3; k <= 14; k++) g.c.set(x, y + k, z, "air");
                } else if (d <= 28.5) {                                      // the stands: one tier per block
                    int tier = (int) Math.floor(d - 17.5) + 2;
                    if (gate) {
                        g.c.set(x, y, z, "smooth_stone");
                        for (int k = 1; k <= 4; k++) g.c.set(x, y + k, z, "air");
                        for (int k = 5; k <= tier; k++) g.c.set(x, y + k, z, stone.at(x, y + k, z));
                        continue;
                    }
                    for (int k = 0; k < tier; k++) g.c.set(x, y + k, z, stone.at(x, y + k, z));
                    String face = Blocks.dirOf((int) Math.signum(cx - x) * (Math.abs(cx - x) >= Math.abs(cz - z) ? 1 : 0),
                            (int) Math.signum(cz - z) * (Math.abs(cz - z) > Math.abs(cx - x) ? 1 : 0));
                    g.c.set(x, y + tier, z, Blocks.stairs("stone_brick_stairs", Blocks.opposite(face)));
                    for (int k = tier + 1; k <= tier + 3; k++) g.c.set(x, y + k, z, "air");
                } else {                                                     // the outer wall
                    for (int k = 0; k <= 14; k++) g.c.set(x, y + k, z, gate && k <= 4 ? "air" : stone.at(x, y + k, z));
                    if (gate) g.c.set(x, y, z, "smooth_stone");
                    if (!gate && ((x + z) & 1) == 0) g.c.set(x, y + 15, z, stone.at(x, y + 15, z));
                }
                Builders.footing(g.c, x, y, z, stone, 10);
            }
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            int lx = cx + (int) Math.round(Math.cos(a) * 18), lz = cz + (int) Math.round(Math.sin(a) * 18);
            if (lz < cz && Math.abs(lx - cx) <= 3) continue;
            g.c.set(lx, y + 3, lz, Blocks.lantern(false));
        }
        g.stand("npc.arena_keeper", cx + 4, g.ground(cx + 4, cz - 34), cz - 34, 0f);
        g.stand("boss.bacte", cx, y, cz + 3, 180f);
        g.l.area("colosseum.pit", Box.of(cx - 16, y + 1, cz - 16, cx + 16, y + 6, cz + 16));
        // the soul sits on the top tier opposite the gate: walk the full circle of the stands to reach it
        int topTier = (int) Math.floor(28.0 - 17.5) + 2;
        g.soul("colosseum", "rift_mole", cx, y + topTier, cz + 28, "On the top row of the Colosseum stands, opposite the gate.");
    }

    // =========================================================================================
    // Grimhollow Chateau
    // =========================================================================================

    static void chateau(RiftCtx g) {
        int y = g.chateauY, cx = CHATEAU[0], cz = CHATEAU[1];
        int half = 24;
        g.res.reserve("chateau_walls", P_CHATEAU);
        Palette wall = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:2,blackstone:1,red_nether_bricks:1");
        Palette court = Palette.of("polished_blackstone:3,blackstone:2,soul_soil:1,coarse_dirt:1");
        for (int z = cz - half; z <= cz + half; z++)
            for (int x = cx - half; x <= cx + half; x++) {
                boolean edge = x <= cx - half + 1 || x >= cx + half - 1 || z <= cz - half + 1 || z >= cz + half - 1;
                boolean gate = x <= cx - half + 1 && Math.abs(z - cz) <= 2;
                if (edge && !gate) {
                    for (int k = 0; k <= 9; k++) g.c.set(x, y + k, z, wall.at(x, y + k, z));
                    if (((x + z) & 1) == 0 && (x == cx - half || x == cx + half || z == cz - half || z == cz + half)) g.c.set(x, y + 10, z, wall.at(x, y + 10, z));
                    Builders.footing(g.c, x, y, z, wall, 10);
                } else {
                    g.c.set(x, y, z, court.at(x, y, z));
                    clearCol(g, x, y, z, 8);
                    if (gate) for (int k = 6; k <= 9; k++) g.c.set(x, y + k, z, wall.at(x, y + k, z));
                }
            }
        for (int z = cz - 2; z <= cz + 2; z++) g.c.set(cx - half, y + 5, z, "iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]");
        // corner towers
        int ti = 0;
        for (int[] c : new int[][]{{cx - half + 2, cz - half + 2}, {cx + half - 2, cz - half + 2}, {cx - half + 2, cz + half - 2}, {cx + half - 2, cz + half - 2}}) {
            Builders.Tower t = new Builders.Tower(c[0], y, c[1]);
            t.radius = 4; t.height = 17; t.top = "battlements"; t.floorHeight = 6;
            t.door = c[0] < cx ? "east" : "west";
            t.wall = wall; t.window = "red_stained_glass_pane";
            t.build(g.c);
            if (ti == 1 || ti == 2) g.soul("chateau", t.platform[0], t.platform[1], t.platform[2], ti == 1
                    ? "On top of the Chateau's north-east tower." : "On top of the Chateau's south-west tower.");
            ti++;
        }
        // the keep
        Builders.House keep = new Builders.House(cx - 6, y, cz - 7, 17, 15);
        keep.floors = 3; keep.door = "west"; keep.wall = wall; keep.frame = "dark_oak_log"; keep.roof = "deepslate_tile"; keep.roofStyle = "hip";
        keep.window = "red_stained_glass_pane"; keep.seed = g.seed + 3; keep.foundation = wall;
        keep.build(g.c);
        g.soul("chateau", "rift_castle_standing", keep.inside[0], keep.inside[1], keep.inside[2], "In the great hall of the Chateau's keep.");
        g.soul("chateau", "rift_castle_phantom", keep.inside[0] + 2, keep.inside[1] + keep.floorHeight * 2, keep.inside[2], "On the top floor of the Chateau's keep.");
        // the oubliette: a sunken pit in the courtyard where the Chateau Lord holds court
        int ox = cx - 16, oz = cz + 10;
        int depth = 6;
        for (int z = oz - 7; z <= oz + 7; z++)
            for (int x = ox - 5; x <= ox + 7; x++) {
                boolean rim = x == ox - 5 || x == ox + 7 || z == oz - 7 || z == oz + 7;
                for (int k = 0; k > -depth; k--) g.c.set(x, y + k, z, rim ? "polished_blackstone_bricks" : "air");
                g.c.set(x, y - depth, z, rim ? "polished_blackstone_bricks" : Palette.of("soul_soil:2,blackstone:2,crying_obsidian:1").at(x, y - depth, z));
                if (rim) g.c.set(x, y + 1, z, "polished_blackstone_wall");
            }
        // stairs down on the north side
        for (int k = 1; k < depth; k++)
            for (int x = ox - 1; x <= ox + 1; x++) {
                int sz = oz - 7 + k;
                g.c.set(x, y - k + 1, oz - 7, "air");
                g.c.set(x, y - k, sz, Blocks.stairs("polished_blackstone_brick_stairs", "north"));
                g.c.set(x, y - k - 1, sz, "polished_blackstone_bricks");
                for (int h = 1; h <= 3; h++) g.c.set(x, y - k + h, sz, "air");
            }
        for (int x = ox - 1; x <= ox + 1; x++) { g.c.set(x, y + 1, oz - 7, "air"); g.c.set(x, y, oz - 7, "polished_blackstone_bricks"); }
        g.stand("boss.chateau", ox + 1, y - depth, oz + 2, 180f);
        g.soul("chateau", "rift_castle_balloons", ox + 6, y - depth, oz + 6, "In the far corner of the oubliette, the sunken pit in the courtyard.");
        g.stand("npc.chateau_hunter", cx - half + 4, y, cz - 4, -90f);
        // blood crystals (hemovibe) grow around the walls
        int n = 0;
        for (int i = 0; i < 400 && n < 26; i++) {
            int hx = cx + (int) Math.round((Hash.unit(g.seed, i, 1, 9) - 0.5) * 80);
            int hz = cz + (int) Math.round((Hash.unit(g.seed, i, 2, 9) - 0.5) * 80);
            if (Math.abs(hx - cx) <= half + 2 && Math.abs(hz - cz) <= half + 2) continue;
            if (!g.h.has(hx, hz) || g.blocked(hx, hz, 1)) continue;
            int hy = g.ground(hx, hz);
            if (!g.c.isAir(hx, hy + 1, hz)) continue;
            g.c.set(hx, hy + 1, hz, "red_stained_glass");
            if (Hash.chance(g.seed, hx, hz, 0.5)) g.c.set(hx, hy + 2, hz, "red_stained_glass");
            n++;
        }
        int[] edge = edgeSpot(g, cx + 30, cz + 30, 1, 1);
        if (edge != null) g.soul("chateau", edge[0], edge[1], edge[2], "Outside the Chateau's walls, where the land ends to the south-east.");
        g.mobArea("mobs.chateau_splatter", cx - half + 3, cz - half + 3, cx + half - 3, cz + half - 3, y + 1, y + 4);
        g.mobArea("mobs.chateau_thrall", cx - half - 18, cz - half - 10, cx - half - 2, cz + half + 10, 66, 84);
        g.l.data("chateau.box", boxStr(Box.of(cx - half, y - 8, cz - half, cx + half, y + 30, cz + half)));
    }

    // =========================================================================================
    // The Mountaintop
    // =========================================================================================

    static void mountain(RiftCtx g) {
        int cx = MOUNTAIN[0], cz = MOUNTAIN[1];
        int y = g.summitY;
        g.res.reserve("summit", Box.of(cx - 8, 0, cz - 8, cx + 8, 0, cz + 8));
        Palette top = Palette.of("calcite:3,snow_block:3,polished_diorite:1");
        for (int z = cz - 7; z <= cz + 7; z++)
            for (int x = cx - 7; x <= cx + 7; x++) {
                if (Math.hypot(x - cx, z - cz) > 7.3) continue;
                g.c.set(x, y, z, top.at(x, y, z));
                clearCol(g, x, y, z, 6);
            }
        // the altar
        g.c.set(cx, y + 1, cz, "lodestone");
        g.c.set(cx, y + 2, cz, "end_rod[facing=up]");
        for (int d = 0; d < 4; d++) g.c.set(cx + Blocks.DX[d] * 2, y + 1, cz + Blocks.DZ[d] * 2, "amethyst_block");
        for (int d = 0; d < 4; d++) g.c.set(cx + Blocks.DX[d] * 2, y + 2, cz + Blocks.DZ[d] * 2, "amethyst_cluster[facing=up,waterlogged=false]");
        g.stand("altar.celestial", cx + 1, y, cz + 1, 0f);
        g.stand("npc.starkeeper", cx - 3, y, cz - 3, 45f);
        g.soul("mountaintop", "rift_mountain_ubik_averages", cx + 4, y, cz - 4, "On the summit of the Mountaintop.");
        g.mobArea("mobs.mountain_puff", cx - 48, cz - 48, cx + 48, cz + 48, 72, 128);
        g.l.data("summit.y", String.valueOf(y));
    }

    /** Things that need the roads to exist: road-side souls, timite outcrops on the mountain. */
    static void afterRoads(RiftCtx g) {
        // mountain road ledges: souls at a few points along the climb
        int cx = MOUNTAIN[0], cz = MOUNTAIN[1];
        int placed = 0;
        double[] radii = {40, 30, 20};
        for (double r : radii) {
            double a = 0.6 + placed * 2.1;
            int x = cx + (int) Math.round(Math.cos(a) * r), z = cz + (int) Math.round(Math.sin(a) * r);
            int[] s = nearestRoad(g, x, z, 12);
            if (s == null) continue;
            g.soul("mountaintop", placed == 0 ? "rift_mountain_ikrus" : placed == 1 ? "rift_mountain_sungecko_perks" : "rift_mountain_ubik_boxes",
                    s[0], s[1], s[2], "Beside the mountain road, about " + (placed == 0 ? "a third" : placed == 1 ? "half" : "two thirds") + " of the way up.");
            placed++;
        }
        // timite outcrops: purpur nodes on the slopes
        int n = 0;
        for (int i = 0; i < 900 && n < 40; i++) {
            int x = cx + (int) Math.round((Hash.unit(g.seed, i, 3, 5) - 0.5) * 92);
            int z = cz + (int) Math.round((Hash.unit(g.seed, i, 4, 5) - 0.5) * 92);
            if (!g.h.has(x, z) || g.blocked(x, z, 1)) continue;
            int y = g.ground(x, z);
            if (y < 82 || !g.c.isAir(x, y + 1, z)) continue;
            g.c.set(x, y + 1, z, "purple_stained_glass");
            if (Hash.chance(g.seed, x, z, 0.4) && g.c.isAir(x, y + 2, z)) g.c.set(x, y + 2, z, "amethyst_cluster[facing=up,waterlogged=false]");
            n++;
        }
        // a hollow in the mountain's flank
        int hx = cx + 30, hz = cz + 18;
        int hy = g.ground(hx, hz);
        Shapes.ellipsoid(g.c, hx, hy + 2, hz, 4, 3, 4, Fill.of("air"), 0);
        for (int x = hx - 3; x <= hx + 3; x++) for (int z = hz - 3; z <= hz + 3; z++) if (Math.hypot(x - hx, z - hz) <= 3.3) g.c.set(x, hy, z, "calcite");
        g.soul("mountaintop", "rift_mountain_walk_of_fame_wall", hx, hy, hz, "In a hollow on the mountain's south-east flank.");
        g.c.set(hx + 2, hy + 1, hz + 1, Blocks.lantern(false));
    }

    private static int[] nearestRoad(RiftCtx g, int x, int z, int radius) {
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int dz = -radius; dz <= radius; dz++)
            for (int dx = -radius; dx <= radius; dx++) {
                if (!g.paths.covers(x + dx, z + dz)) continue;
                double d = Math.hypot(dx, dz);
                if (d < bd) { bd = d; best = new int[]{x + dx, g.paths.height(x + dx, z + dz), z + dz}; }
            }
        return best;
    }

    /** Any area that still has souls to place gets them on open ground inside its own zone. */
    static void looseSouls(RiftCtx g) {
        int[][] centres = {
                {0, 0}, {LAGOON[0], LAGOON[1]}, {WEST[0], WEST[1]}, {0, 0}, {DREAD[0], DREAD[1]}, {PLAZA[0], PLAZA[1]},
                {LIVING[0], LIVING[1]}, {COLOSSEUM[0], COLOSSEUM[1]}, {CHATEAU[0], CHATEAU[1]}, {MOUNTAIN[0], MOUNTAIN[1]}};
        String[] areaNames = RiftCatalog.SOULS_PER_AREA.keySet().toArray(new String[0]);
        String[] where = {"in the thicket", "on the lagoon's banks", "around the West Village", "", "among the wilted fields",
                "around the Plaza", "", "", "around the Chateau grounds", "on the mountain's lower slopes"};
        for (int ai = 0; ai < areaNames.length; ai++) {
            String area = areaNames[ai];
            int tries = 0;
            while (g.soulsLeft(area) > 0 && tries++ < 4000) {
                int[] c = ai < centres.length ? centres[ai] : new int[]{0, 0};
                int r = area.equals("mountaintop") ? 52 : 40;
                int x = c[0] + (int) Math.round((Hash.unit(g.seed, tries, ai, 31) - 0.5) * 2 * r);
                int z = c[1] + (int) Math.round((Hash.unit(g.seed, tries, ai, 37) - 0.5) * 2 * r);
                if (!g.h.has(x, z) || g.h.wet(x, z) || !g.h.inland(x, z, 4) || g.blocked(x, z, 2)) continue;
                if (area.equals("mountaintop") && Math.hypot(x - c[0], z - c[1]) < 30) continue;
                int y = g.ground(x, z);
                if (!g.c.isAir(x, y + 1, z) || !g.c.isAir(x, y + 2, z)) continue;
                String w = ai < where.length && !where[ai].isEmpty() ? where[ai] : "somewhere nearby";
                g.soul(area, x, y, z, "Out in the open " + w + ". Keep your eyes peeled.");
            }
        }
    }

    // =========================================================================================
    // small zones inside the districts
    // =========================================================================================

    static void subZones(RiftCtx g) {
        Layout l = g.l;
        int cy = g.cradleY;
        l.zone("cradle", "The Cradle", 'd', Box.of(CRADLE[0] - 13, cy - 3, CRADLE[1] - 13, CRADLE[0] + 13, cy + 20, CRADLE[1] + 13), null,
                Layout.Zone.SAFE, Layout.Zone.NO_FALL);
        String ty = l.data().get("tower.y");
        if (ty != null) {
            int t = Integer.parseInt(ty);
            l.zone("wizard_tower_upper", "Wizard Tower", '5', Box.of(TOWER[0] - 6, t + 5, TOWER[1] - 6, TOWER[0] + 6, t + 45, TOWER[1] + 6), null,
                    Layout.Zone.SAFE, Layout.Zone.NO_FALL);
        }
        String gb = l.data().get("gallery.box");
        if (gb != null) l.zone("rift_gallery", "Rift Gallery", 'f', parse(gb), null, Layout.Zone.SAFE, Layout.Zone.NO_FALL);
        String lb = l.data().get("lair.box");
        if (lb != null) l.zone("leeches_lair", "Leeches Lair", '2', parse(lb), RiftCatalog.area("black_lagoon").requirementKey(), Layout.Zone.NO_FALL);
        String mb = l.data().get("mirror.box");
        if (mb != null) l.zone("mirrorverse", RiftCatalog.area("mirrorverse").name(), 'b', parse(mb),
                RiftCatalog.area("mirrorverse").requirementKey(), Layout.Zone.NO_MOBS, Layout.Zone.NO_FALL);
        String lv = l.data().get("living.box");
        if (lv != null) {
            Box b = parse(lv);
            l.zone("living_stillness", "Living Stillness", 'b', Box.of(LIVING[0] + 7, b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()),
                    RiftCatalog.area("living_cave").requirementKey(), Layout.Zone.NO_FALL);
        }
        String ib = l.data().get("infested.box");
        if (ib != null) l.zone("infested_house", "Infested House", 'e', parse(ib), RiftCatalog.area("west_village").requirementKey(), Layout.Zone.NO_FALL);
        String sy = l.data().get("summit.y");
        if (sy != null) {
            int s = Integer.parseInt(sy);
            l.zone("summit", "Summit", 'f', Box.of(MOUNTAIN[0] - 8, s - 2, MOUNTAIN[1] - 8, MOUNTAIN[0] + 8, s + 20, MOUNTAIN[1] + 8),
                    RiftCatalog.area("mountaintop").requirementKey(), Layout.Zone.NO_FALL);
        }
    }

    static Box parse(String s) {
        String[] p = s.split(",");
        return Box.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
    }
}
