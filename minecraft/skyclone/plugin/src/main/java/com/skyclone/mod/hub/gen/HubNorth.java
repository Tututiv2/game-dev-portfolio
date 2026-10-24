package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Noise;
import com.skyclone.gen.Palette;

import java.util.ArrayList;
import java.util.List;

import static com.skyclone.mod.hub.gen.HubPlan.*;

/**
 * The Portal Plaza (west of the village), the Mining District, the Coal Mine dug into the
 * Mountain, the switchback road to the summit and the weather station on top.
 */
final class HubNorth {

    private HubNorth() {}

    // =======================================================================================
    // Portal Plaza
    // =======================================================================================

    /** One island portal: warp id, frame palette, accent block, banner colour. */
    record Portal(String warp, String frame, String accent, String banner) {}

    static final Portal[] PORTALS_ALL = {
            new Portal("private_island", "mossy_stone_bricks:3,moss_block:1,stone_bricks:1", "flowering_azalea", "lime"),
            new Portal("barn", "hay_block[axis=y]:2,stripped_oak_wood[axis=y]:2", "carved_pumpkin[facing=south]", "yellow"),
            new Portal("park", "spruce_wood[axis=y]:2,birch_wood[axis=y]:2,dark_oak_wood[axis=y]:1", "azalea", "green"),
            new Portal("gold_mine", "gold_block:1,raw_gold_block:2,stone:2", "raw_gold_block", "orange"),
            new Portal("spiders_den", "polished_blackstone_bricks:3,cracked_polished_blackstone_bricks:1", "cobweb", "black"),
            new Portal("the_end", "end_stone_bricks:3,purpur_block:2", "end_rod[facing=up]", "purple"),
            new Portal("crimson_isle", "nether_bricks:3,red_nether_bricks:2", "shroomlight", "red"),
            new Portal("dungeon_hub", "deepslate_bricks:2,cracked_deepslate_bricks:1,polished_deepslate:1", "skeleton_skull[rotation=0]", "gray"),
            new Portal("garden", "mossy_cobblestone:2,moss_block:1,flowering_azalea_leaves[persistent=true]:1", "potted_flowering_azalea_bush", "pink"),
            new Portal("backwater_bayou", "mud_bricks:3,mangrove_wood[axis=y]:2", "mangrove_roots[waterlogged=false]", "cyan"),
    };

    /** The eleventh gate: the seasonal one (mayors registers warp "jerrys_workshop"). */
    static final Portal SEASONAL = new Portal("jerrys_workshop", "packed_ice:3,snow_block:2,blue_ice:1", "snow_block", "light_blue");

    static void portalPlaza(HubCtx g) {
        int P = g.portalY;
        Box b = PORTALS;
        g.reserve("portal_plaza", Box.of(b.minX(), P - 6, b.minZ(), b.maxX(), P + 12, b.maxZ()));
        Palette pave = Palette.of("polished_andesite:4,stone_bricks:3,smooth_stone:2,chiseled_stone_bricks:1");
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++) {
                boolean edge = x == b.minX() || x == b.maxX() || z == b.minZ() || z == b.maxZ();
                g.paveCell(x, P, z, Fill.of(edge ? "polished_blackstone_bricks" : pave.at(x, P, z)));
            }
        // a low wall around the plaza with openings east (village) and west (forest)
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int z : new int[]{b.minZ(), b.maxZ()}) g.c.set(x, P + 1, z, "polished_blackstone_brick_wall");
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x : new int[]{b.minX(), b.maxX()}) if (Math.abs(z) > 4) g.c.set(x, P + 1, z, "polished_blackstone_brick_wall");
        // compass rose inlay and the waystone obelisk in the middle
        int cx = (b.minX() + b.maxX()) / 2, cz = 0;
        for (int dz = -9; dz <= 9; dz++)
            for (int dx = -9; dx <= 9; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 9.4) continue;
                String blk = d > 8.4 ? "polished_blackstone_bricks" : (dx == 0 || dz == 0) && d < 8 ? "gold_block"
                        : Math.abs(Math.abs(dx) - Math.abs(dz)) == 0 && d < 6 ? "chiseled_quartz_block" : "polished_diorite";
                g.c.set(cx + dx, P, cz + dz, blk);
            }
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) g.c.set(cx + dx, P + 1, cz + dz, "polished_blackstone_bricks");
        for (int y = P + 2; y <= P + 8; y++) g.c.set(cx, y, cz, y == P + 5 ? "crying_obsidian" : "chiseled_polished_blackstone");
        g.c.set(cx, P + 9, cz, "beacon");
        g.c.set(cx, P + 10, cz, Blocks.slab("polished_blackstone", "bottom"));
        for (int d = 0; d < 4; d++) g.c.set(cx + Blocks.DX[d], P + 2, cz + Blocks.DZ[d], Blocks.stairs("polished_blackstone", Blocks.DIRS[d]));
        // the ten portals: four on the north edge, four on the south edge, two at the west end
        int[] xs = {-162, -148, -126, -112};
        int i = 0;
        for (int x : xs) portal(g, PORTALS_ALL[i++], x, b.minZ() + 2, P, "south");
        for (int x : xs) portal(g, PORTALS_ALL[i++], x, b.maxZ() - 2, P, "north");
        portal(g, PORTALS_ALL[i++], b.minX() + 2, -14, P, "east");
        portal(g, PORTALS_ALL[i++], b.minX() + 2, 14, P, "east");
        // the seasonal gate in the middle of the north row (the winter workshop opens in late winter)
        portal(g, SEASONAL, cx, b.minZ() + 2, P, "south");
        // planters with trees and flower beds to break up the paving
        for (int[] q : new int[][]{{-158, -26}, {-118, -26}, {-158, 26}, {-118, 26}}) {
            HubVillage.planter(g, q[0], P, q[1]);
            com.skyclone.gen.Trees.grow(g.c, (q[0] & 1) == 0 ? "cherry" : "birch", q[0], P, q[1], g.seed + q[0] * 7L + q[1], 0.8);
        }
        for (int z : new int[]{-33, 33})
            for (int x = -165; x <= -109; x++) {
                if (Math.abs(x + 137) < 4) continue;
                g.c.set(x, P, z, "grass_block");
                g.c.set(x, P + 1, z, (x & 1) == 0 ? "allium" : "azure_bluet");
            }
        // benches and lamps
        for (int x = -156; x <= -116; x += 20) {
            Builders.bench(g.c, x, P, -14, "south", 3, "dark_oak");
            Builders.bench(g.c, x, P, 14, "north", 3, "dark_oak");
        }
        for (int x = b.minX() + 8; x <= b.maxX() - 6; x += 12)
            for (int z : new int[]{-24, 24}) { g.clearAbove(x, P, z, 5); Builders.lampPost(g.c, x, P, z, "soul", "north"); }
        g.zone("portal_plaza", "Portal Plaza", 'd', Box.of(b.minX(), P - 4, b.minZ(), b.maxX(), P + 30, b.maxZ()), Layout.Zone.SAFE);
        g.area("portal_plaza", Box.of(b.minX() + 1, P + 1, b.minZ() + 1, b.maxX() - 1, P + 3, b.maxZ() - 1));
        g.npc("npc.portal_keeper", cx + 3, P, cz + 3, HubCtx.yaw("east"));
    }

    /**
     * A framed portal standing on the plaza at ground {@code y}: opening 3 wide, 4 tall at (x, z),
     * facing {@code out} (toward the plaza). Writes the portal area and a label anchor above it.
     */
    static void portal(HubCtx g, Portal p, int x, int z, int y, String out) { portal(g, p, x, z, y, out, "portal.", true); }

    /**
     * The same gateway with its area named {@code prefix + warp}: "portal." areas are core's
     * walk-in portals; the hub module itself runs "gate." areas (a second way to the same warp).
     */
    static void portal(HubCtx g, Portal p, int x, int z, int y, String out, String prefix, boolean banner) {
        boolean alongX = out.equals("north") || out.equals("south");
        Palette frame = Palette.of(p.frame());
        String axis = alongX ? "x" : "z";
        int ux = alongX ? 1 : 0, uz = alongX ? 0 : 1;
        // dais and frame
        for (int s = -3; s <= 3; s++) {
            int fx = x + ux * s, fz = z + uz * s;
            g.c.set(fx, y, fz, "polished_blackstone_bricks");
            g.c.set(fx + Blocks.dx(out), y, fz + Blocks.dz(out), "polished_blackstone");
            g.c.set(fx - Blocks.dx(out), y, fz - Blocks.dz(out), frame.at(fx, y, fz));
            for (int h = 1; h <= 6; h++) {
                boolean frameCell = Math.abs(s) >= 2 || h >= 5;
                if (Math.abs(s) == 3 && h > 5) continue;
                if (Math.abs(s) == 3) { g.c.set(fx, y + h, fz, h == 5 ? Blocks.lantern(false) : (h <= 4 ? frame.at(fx, y + h, fz) : "air")); continue; }
                g.c.set(fx, y + h, fz, frameCell ? frame.at(fx, y + h, fz) : "nether_portal[axis=" + axis + "]");
                // back wall so the portal reads as a gateway, not a sheet
                int bx = fx - Blocks.dx(out), bz = fz - Blocks.dz(out);
                if (h <= 6 && Math.abs(s) <= 2) g.c.set(bx, y + h, bz, frame.at(bx, y + h, bz));
            }
        }
        g.c.set(x, y + 7, z, frame.at(x, y + 7, z));
        if (banner) g.c.set(x, y + 8, z, Blocks.banner(p.banner(), bannerRot(out)));
        // accents on the outer posts
        for (int s : new int[]{-3, 3}) {
            int fx = x + ux * s + Blocks.dx(out), fz = z + uz * s + Blocks.dz(out);
            g.c.set(fx, y + 1, fz, p.accent());
        }
        g.area(prefix + p.warp(), Box.of(x - ux, y + 1, z - uz, x + ux, y + 4, z + uz));
        if (banner) g.layout.anchor("label." + prefix + p.warp(), x + 0.5 + Blocks.dx(out) * 0.6, y + 6.4, z + 0.5 + Blocks.dz(out) * 0.6, HubCtx.yaw(out));
    }

    static int bannerRot(String out) {
        return switch (out) { case "south" -> 0; case "west" -> 4; case "north" -> 8; default -> 12; };
    }

    // =======================================================================================
    // Mining District
    // =======================================================================================

    static void miningDistrict(HubCtx g) {
        int M = g.miningY;
        Box b = MINING;
        // gravel yard paving with cart tracks
        Palette yard = Palette.of("gravel:3,cobblestone:2,andesite:2,coarse_dirt:2,stone:1");
        for (int z = b.minZ() + 1; z <= b.maxZ() - 1; z++)
            for (int x = b.minX() + 1; x <= b.maxX() - 1; x++) if (g.rnd("myard", x, z) < 0.55) g.paveCell(x, M, z, yard);
        // the forge: an open-fronted smithy
        Room f = Bld.build(g, "blacksmith", 98, M, -126, 17, 13, "south", Bld.STONE.floors(1, 6).chimney().roof("deepslate_tile", "gable"));
        f.floor(Palette.of("stone_bricks:2,polished_andesite:1,cobblestone:1"));
        for (int u = f.uMin; u <= f.uMax; u++) {
            f.set(u, 1, f.vMax, (u & 1) == 0 ? "blast_furnace[facing=" + f.back() + ",lit=true]" : "furnace[facing=" + f.back() + ",lit=true]");
            f.set(u, 2, f.vMax, "chiseled_stone_bricks");
        }
        f.set(-4, 1, 6, "lava_cauldron");
        f.set(-3, 1, 6, "water_cauldron[level=3]");
        f.set(3, 1, 6, "anvil[facing=" + f.right() + "]");
        f.set(5, 1, 6, "grindstone[face=floor,facing=" + f.fwd() + "]");
        f.set(f.uMin, 1, 3, "smithing_table");
        f.chandeliers(4);
        f.npc("npc.blacksmith", 0, 8);
        // wave 5 (skills request 13): the Runic Pedestal in the forge's west bay, a rune-lit plinth beside it
        f.set(-6, 1, 9, "chiseled_polished_blackstone");
        f.set(-6, 2, 9, "amethyst_cluster[facing=up,waterlogged=false]");
        f.npc("npc.runic_pedestal", -5, 9);
        // the reforge anvil under an awning out front, with the anvil-keeper beside it
        int[] ds = f.house.doorStep;
        int ax = ds[0] + 6, az = ds[2] + 5;
        g.reserve("reforge_awning", Box.of(ax - 3, M - 4, az - 2, ax + 3, M + 5, az + 2));
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                g.paveCell(ax + dx, M, az + dz, Fill.of("polished_andesite"));
                g.c.set(ax + dx, M + 4, az + dz, (dx & 1) == 0 ? "purple_wool" : "black_wool");
            }
        for (int[] q : new int[][]{{-3, -2}, {3, -2}, {-3, 2}, {3, 2}}) for (int y = M + 1; y <= M + 3; y++) g.c.set(ax + q[0], y, az + q[1], "dark_oak_fence");
        g.c.set(ax, M + 1, az, "anvil[facing=east]");
        g.c.set(ax - 2, M + 1, az + 1, "chipped_anvil[facing=north]");
        g.c.set(ax + 2, M + 1, az - 1, "smithing_table");
        g.c.set(ax, M + 3, az, Blocks.lantern(true));
        g.npc("npc.reforge_anvil", ax, M, az + 1, 180f);
        // the stone merchant beside the forge
        Builders.Stall st = Builders.marketStall(g.c, 88, M, -112, "south", "gray", "white", g.seed + 21);
        g.reserve("stone_stall", st.box());
        g.npc("npc.stone_merchant", st.vendor()[0], M, st.vendor()[2], 0f);
        // the mine merchant's shop
        Room m = Bld.build(g, "mine_merchant", 70, M, -112, 11, 9, "east", Bld.CABIN.chimney());
        m.set(-2, 1, 4, "barrel[facing=up,open=false]"); m.set(2, 1, 4, "barrel[facing=up,open=false]");
        m.set(2, 2, 4, "coal_block");
        m.set(0, 1, m.vMax, "chest[facing=" + m.back() + ",type=single,waterlogged=false]");
        m.npc("npc.mine_merchant", 0, 5);
        HubVillage.walk(g, m, 92, -106, HubVillage.LANE);
        // coal heaps and carts
        for (int[] q : new int[][]{{68, -98}, {74, -96}, {120, -98}}) {
            g.c.set(q[0], M + 1, q[1], "coal_block");
            g.c.set(q[0] + 1, M + 1, q[1], "coal_block");
            g.c.set(q[0], M + 2, q[1], "coal_block");
        }
        for (int x = 70; x <= 122; x += 13) for (int z : new int[]{-130, -96}) g.lamp(x, z, "street", "south");
        g.zone("mining_district", "Mining District", '6', Box.of(b.minX(), M - 8, b.minZ(), b.maxX(), M + 30, b.maxZ()));
        g.keepOut(b.grow(2));
    }

    // =======================================================================================
    // Coal Mine
    // =======================================================================================

    /** Carving pass: run after every additive pass. */
    static void coalMine(HubCtx g) {
        int F = g.miningY;
        int ex0 = 74, ex1 = 80;                        // tunnel width (x)
        int zMouth = MINING.minZ() + 1, zEnd = -163;
        int ccx = 77, ccz = -172;                      // chamber centre
        Palette rock = Palette.of("stone:6,andesite:3,cobblestone:2,tuff:1");
        // 1. give the tunnel a roof where the mountainside is too low (an outcrop around the mouth)
        Noise lump = new Noise(Hash.salt(g.seed, "coalmine-outcrop"));
        int mid = (ex0 + ex1) / 2;
        for (int z = zMouth - 1; z >= zEnd - 2; z--)
            for (int x = mid - 12; x <= mid + 12; x++) {
                int dx = Math.abs(x - mid);
                double want = F + 10 - Math.max(0, dx - 4) * 1.1 + 2.2 * lump.fbm2(x / 5.0, z / 5.0, 2)
                        - Math.max(0, (z - (zMouth - 4))) * 1.5;
                int top = g.ground(x, z);
                if (dx <= 6) want = Math.max(want, F + 8);
                for (int y = top + 1; y <= (int) Math.round(want); y++) g.c.set(x, y, z, rock.at(x, y, z));
                if (top + 1 <= (int) Math.round(want) && g.rnd("outcrop-grass", x, z) < 0.55 && dx > 3)
                    g.c.set(x, (int) Math.round(want), z, "grass_block");
            }
        // 2. carve the tunnel and the chamber with ragged walls
        Noise n = new Noise(Hash.salt(g.seed, "coalmine"));
        List<int[]> carved = new ArrayList<>();
        for (int z = zMouth; z >= zEnd; z--)
            for (int x = ex0 + 1; x <= ex1 - 1; x++)
                for (int y = F + 1; y <= F + 5; y++) { g.c.set(x, y, z, "air"); carved.add(new int[]{x, y, z}); }
        for (int dz = -14; dz <= 14; dz++)
            for (int dx = -16; dx <= 16; dx++) {
                double e = (dx * dx) / (15.0 * 15) + (dz * dz) / (12.0 * 12);
                double wob = 0.18 * n.noise2((ccx + dx) / 6.0, (ccz + dz) / 6.0);
                if (e > 1 + wob) continue;
                int hgt = 6 + (int) Math.round(2 * (1 - e) + 1.5 * n.noise2(dx / 5.0, dz / 5.0));
                for (int y = F + 1; y <= F + hgt; y++) { g.c.set(ccx + dx, y, ccz + dz, "air"); carved.add(new int[]{ccx + dx, y, ccz + dz}); }
            }
        // a side gallery to the west
        for (int x = ccx - 15; x >= ccx - 30; x--)
            for (int z = ccz - 2; z <= ccz + 2; z++)
                for (int y = F + 1; y <= F + 4; y++) { g.c.set(x, y, z, "air"); carved.add(new int[]{x, y, z}); }
        for (int dz = -5; dz <= 5; dz++)
            for (int dx = -5; dx <= 5; dx++) {
                if (dx * dx + dz * dz > 26) continue;
                for (int y = F + 1; y <= F + 5; y++) { g.c.set(ccx - 34 + dx, y, ccz + dz, "air"); carved.add(new int[]{ccx - 34 + dx, y, ccz + dz}); }
            }
        // 3. floors, then ore in every rock face that touches the open space
        Palette floor = Palette.of("stone:3,gravel:2,cobblestone:2,andesite:1");
        Box area = Box.of(ccx - 42, F, ccz - 16, ex1 + 18, F + 10, zMouth);
        for (int[] q : carved) {
            if (q[1] == F + 1) g.c.set(q[0], F, q[2], floor.at(q[0], F, q[2]));
        }
        Palette ore = Palette.of("coal_ore:14,stone:40,cobblestone:10,andesite:10,iron_ore:2,coal_block:1");
        for (int[] q : carved)
            for (int d = 0; d < 6; d++) {
                int x = q[0] + (d == 0 ? 1 : d == 1 ? -1 : 0), y = q[1] + (d == 2 ? 1 : d == 3 ? -1 : 0), z = q[2] + (d == 4 ? 1 : d == 5 ? -1 : 0);
                if (y <= F || !area.contains(x, y, z)) continue;
                String cur = BlockState.name(g.c.get(x, y, z));
                if (cur.equals("stone") || cur.equals("andesite") || cur.equals("cobblestone") || cur.equals("tuff") || cur.equals("dirt") || cur.equals("gravel"))
                    g.c.set(x, y, z, ore.at(x, y, z));
            }
        // 4. timber supports and lanterns down the tunnel, rails on the floor
        for (int z = zMouth - 2; z >= zEnd; z -= 5) {
            for (int y = F + 1; y <= F + 4; y++) { g.c.set(ex0 + 1, y, z, "oak_log[axis=y]"); g.c.set(ex1 - 1, y, z, "oak_log[axis=y]"); }
            for (int x = ex0 + 1; x <= ex1 - 1; x++) g.c.set(x, F + 5, z, "stripped_oak_log[axis=x]");
            g.c.set((ex0 + ex1) / 2, F + 4, z, Blocks.lantern(true));
        }
        for (int z = zMouth + 6; z >= zEnd - 6; z--) {
            int x = (ex0 + ex1) / 2;
            if (g.c.isAir(x, F + 1, z) && BlockInfo.of(g.c.get(x, F, z)).top16 == 16) g.c.set(x, F + 1, z, "rail[shape=north_south,waterlogged=false]");
        }
        // pillars and lanterns in the chamber, a cart and crates
        for (int[] p : new int[][]{{-7, -4}, {7, -4}, {-7, 5}, {7, 5}}) {
            int x = ccx + p[0], z = ccz + p[1];
            for (int y = F + 1; y <= F + 9; y++) if (g.c.isAir(x, y, z)) g.c.set(x, y, z, "oak_log[axis=y]"); else break;
            g.c.set(x + 1, F + 3, z, Blocks.wallTorch("east"));
        }
        g.c.set(ccx, F + 6, ccz, Blocks.lantern(true));
        g.c.set(ccx - 34, F + 4, ccz, Blocks.lantern(true));
        Builders.crates(g.c, ccx + 9, F, ccz + 6, g.seed + 31);
        g.c.set(ccx - 6, F + 1, ccz + 8, "chest[facing=north,type=single,waterlogged=false]");
        // the mouth: a timber gate frame and the dozing miner
        for (int y = F + 1; y <= F + 5; y++) { g.c.set(ex0, y, zMouth + 1, "oak_log[axis=y]"); g.c.set(ex1, y, zMouth + 1, "oak_log[axis=y]"); }
        for (int x = ex0; x <= ex1; x++) g.c.set(x, F + 6, zMouth + 1, "oak_log[axis=x]");
        g.c.set(ex0 - 1, F + 3, zMouth + 1, Blocks.wallTorch("west"));
        g.c.set(ex1 + 1, F + 3, zMouth + 1, Blocks.wallTorch("east"));
        g.npc("npc.lazy_miner", ex1 + 2, F, zMouth + 3, 180f);
        g.area("coal_mine", area);
        g.area("ores.coal_mine", area);
        g.zone("coal_mine", "Coal Mine", '7', Box.of(area.minX(), F - 2, area.minZ(), area.maxX(), F + 12, zMouth));
        // fairy souls: the end of the side gallery and behind a chamber pillar
        g.soul(ccx - 37, F, ccz + 2);
        g.soul(ccx + 12, F, ccz - 7);
    }

    // =======================================================================================
    // Mountain road and summit
    // =======================================================================================

    static void mountain(HubCtx g) {
        int S = g.summitY;
        // weather station on the flattened summit
        Room w = Bld.build(g, "weather_station", MX - 4, S, MZ - 2, 9, 7, "south", Bld.STONE.roof("spruce", "gable").wall("stone_bricks:3,calcite:2"));
        w.set(-2, 1, 3, "barrel[facing=up,open=false]"); w.set(2, 1, 3, "lectern[facing=" + w.back() + ",has_book=true,powered=false]");
        w.set(0, 1, w.vMax, "daylight_detector[inverted=false,power=0]");
        int mx = MX + 5, mz = MZ - 5;
        g.reserve("weather_mast", Box.of(mx - 1, S - 4, mz - 1, mx + 1, S + 16, mz + 1));
        for (int y = S + 1; y <= S + 12; y++) g.c.set(mx, y, mz, y % 3 == 0 ? "waxed_copper_block" : "iron_bars");
        g.c.set(mx, S + 13, mz, "lightning_rod[facing=up,powered=false,waterlogged=false]");
        g.c.set(mx + 1, S + 9, mz, "waxed_cut_copper_slab[type=bottom,waterlogged=false]");
        g.npc("npc.snow_shoveler", MX + 2, S, MZ + 5, 0f);   // faces the arrivals from the south, not the station wall
        g.c.set(MX - 6, S + 1, MZ + 6, "snow_block");
        g.c.set(MX - 6, S + 2, MZ + 6, "carved_pumpkin[facing=south]");
        g.soul(MX - 6, S, MZ - 6);
        g.zone("summit", "Weather Station", 'f', Box.of(SUMMIT.minX() - 4, S - 6, SUMMIT.minZ() - 4, SUMMIT.maxX() + 4, S + 30, SUMMIT.maxZ() + 4));
        // the switchback road: a spiral from the Mining District up to the summit
        List<int[]> pts = new ArrayList<>();
        int sx = MINING.maxX(), sz = -112;
        double th0 = Math.atan2(MINING.minZ() - 6 - MZ, sx - MX);
        double turns = 1.75, rStart = MR - 10, rEnd = 12;
        pts.add(new int[]{sx, sz});
        pts.add(new int[]{sx, MINING.minZ() - 6});
        int steps = (int) (turns * 30);
        for (int i = 0; i <= steps; i++) {
            double f = (double) i / steps;
            double th = th0 - f * turns * 2 * Math.PI;
            double r = rStart + (rEnd - rStart) * Math.pow(f, 0.85);
            pts.add(new int[]{(int) Math.round(MX + Math.cos(th) * r), (int) Math.round(MZ + Math.sin(th) * r)});
        }
        pts.add(new int[]{MX, MZ + 6});
        Builders.PathResult pr = Builders.path(g.c, pts.toArray(new int[0][]), 3,
                Palette.of("gravel:3,coarse_dirt:2,cobblestone:2,andesite:1"), "cobblestone_stairs", null);
        // a clean cut into the slope (no overhangs on the road) and posts on the drop side
        pr.cells.forEach((k, y) -> {
            int x = (int) (k >> 32), z = (int) k;
            for (int a = 4; a <= 30; a++) {
                String bl = g.c.get(x, y + a, z);
                if (g.c.isAir(x, y + a, z)) break;
                if (Builders.natural(bl)) g.c.set(x, y + a, z, "air");
            }
            g.roads.cells.put(k, y);
        });
        int i = 0;
        for (int[] p : pts) {
            if (i++ % 6 != 3) continue;
            int lx = p[0] + 2, lz = p[1];
            if (pr.covers(lx, lz)) lx += 1;
            if (!pr.covers(lx, lz)) g.lamp(lx, lz, "torch", "north");
        }
        g.area("mountain", Box.of(MX - MR + 10, g.miningY + 4, MZ - MR + 10, MX + MR - 10, S + 3, MZ + MR - 10));
        g.zone("mountain", "Mountain", 'f', Box.of(MX - MR, g.V + 4, MZ - MR, MX + MR, S + 40, MZ + MR - 25));
        // souls on the way up
        int[] a = pts.get(pts.size() / 3), b = pts.get(2 * pts.size() / 3);
        g.soulOnGround(a[0] + (pr.covers(a[0] + 3, a[1]) ? -3 : 3), a[1]);
        g.soulOnGround(b[0], b[1] + (pr.covers(b[0], b[1] + 3) ? -3 : 3));
    }
}
