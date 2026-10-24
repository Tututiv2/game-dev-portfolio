package com.skyclone.mod.hub.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Trees;

import java.util.ArrayList;
import java.util.List;

/**
 * The Village: spawn plaza and fountain at the centre, four paved streets and four lanes, and
 * every town building with a furnished interior and its NPC spots.
 *
 * <pre>
 *   lots (x/z):  inner  |coord| 4..47      outer |coord| 53..98      streets x,z in -3..3, lanes at +-50
 *   NW Bank        NE Auction House       SE Museum         SW Bazaar (+ alley stalls)
 *   N  Community Center / phone shop      N-E Election Hall / decorator
 *   E  Library (+ sewer) / Tavern         S  Pet Care / Alchemist + Flower House
 *   W  Builder's House / Weapon + Armour smiths + Archery range
 *   corners: Adventurer + Tailor + Owl keeper (NW), Thaumaturge tower (NE), Greenhouse + pig pen (SE), Fairy garden (SW)
 * </pre>
 */
final class HubVillage {

    private HubVillage() {}

    static final Palette AVENUE = Palette.of("polished_andesite:3,stone_bricks:4,andesite:2,cracked_stone_bricks:1,cobblestone:1");
    static final Palette LANE = Palette.of("dirt_path:6,coarse_dirt:1,gravel:1,cobblestone:1");
    static final Palette PLAZA = Palette.of("stone_bricks:5,polished_andesite:3,cracked_stone_bricks:1,mossy_stone_bricks:1");
    static final Palette WALK = Palette.of("dirt_path:5,gravel:1,cobblestone:1");

    static void build(HubCtx g) {
        int V = g.V;
        streets(g, V);
        plaza(g, V);

        bank(g, V);
        auctionHouse(g, V);
        museum(g, V);
        bazaar(g, V);
        communityCenter(g, V);
        electionHall(g, V);
        library(g, V);
        tavern(g, V);
        petCare(g, V);
        alchemist(g, V);
        builder(g, V);
        smiths(g, V);
        nwCorner(g, V);
        neCorner(g, V);
        seCorner(g, V);
        fairyGarden(g, V);
        streetLife(g, V);
        festivalSpots(g, V);
        infill(g, V);

        Box vb = HubPlan.VILLAGE;
        g.zone("village", "Village", 'b', Box.of(vb.minX(), V - 12, vb.minZ(), vb.maxX(), V + 60, vb.maxZ()), Layout.Zone.SAFE);
        g.area("village", Box.of(vb.minX(), V + 1, vb.minZ(), vb.maxX(), V + 3, vb.maxZ()));
    }

    // =======================================================================================
    // streets & plaza
    // =======================================================================================

    static void streets(HubCtx g, int V) {
        // main streets (7 wide) from the plaza to the village edge
        g.pave(-3, -98, 3, -20, V, AVENUE);
        g.pave(-3, 20, 3, 98, V, AVENUE);
        g.pave(-98, -3, -20, 3, V, AVENUE);
        g.pave(20, -3, 98, 3, V, AVENUE);
        // curb lines of polished stone along the avenues
        for (int t = 20; t <= 98; t++)
            for (int s : new int[]{-4, 4}) {
                g.paveCell(s, V, -t, Fill.of("smooth_stone"));
                g.paveCell(s, V, t, Fill.of("smooth_stone"));
                g.paveCell(-t, V, s, Fill.of("smooth_stone"));
                g.paveCell(t, V, s, Fill.of("smooth_stone"));
            }
        // lanes (5 wide) at +-50
        for (int k : new int[]{-50, 50}) {
            g.pave(k - 2, -98, k + 2, 98, V, LANE);
            g.pave(-98, k - 2, 98, k + 2, V, LANE);
        }
        g.keepOut(Box.of(-4, 0, -98, 4, 0, 98));
        g.keepOut(Box.of(-98, 0, -4, 98, 0, 4));
        for (int k : new int[]{-50, 50}) {
            g.keepOut(Box.of(k - 3, 0, -98, k + 3, 0, 98));
            g.keepOut(Box.of(-98, 0, k - 3, 98, 0, k + 3));
        }
        // street lamps every 14 blocks, alternating sides
        for (int t = 26; t <= 96; t += 14) {
            int s = (t / 14) % 2 == 0 ? 5 : -5;
            g.lamp(s, -t, "street", s > 0 ? "west" : "east");
            g.lamp(-s, t, "street", s > 0 ? "east" : "west");
            g.lamp(-t, s, "street", s > 0 ? "north" : "south");
            g.lamp(t, -s, "street", s > 0 ? "south" : "north");
        }
        for (int k : new int[]{-50, 50})
            for (int t = -90; t <= 90; t += 20) {
                if (Math.abs(t) < 8 || Math.abs(Math.abs(t) - 50) < 6) continue;
                g.lamp(k + 3, t, "post", "north");
                g.lamp(t, k + 3, "post", "north");
            }
    }

    static void plaza(HubCtx g, int V) {
        // rings of paving
        for (int dz = -23; dz <= 23; dz++)
            for (int dx = -23; dx <= 23; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 22.6) continue;
                String b;
                if (d > 21.4) b = "polished_andesite";
                else if (Math.abs(d - 14) < 0.6) b = "chiseled_stone_bricks";
                else if (d < 9 && ((dx + dz) & 1) == 0) b = "polished_diorite";
                else b = PLAZA.at(dx, V, dz);
                g.paveCell(dx, V, dz, Fill.of(b));
            }
        g.keepOut(Box.of(-23, 0, -23, 23, 0, 23));
        g.reserve("plaza.fountain", Box.of(-7, V - 6, -7, 7, V + 9, 7));
        grandFountain(g, 0, V, 0);
        // planters with cherry trees on the four diagonals, benches facing the fountain between them
        for (int[] q : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            int px = q[0] * 13, pz = q[1] * 13;
            planter(g, px, V, pz);
            Trees.grow(g.c, "cherry", px, V, pz, g.seed ^ (px * 31L + pz), 0.85);
        }
        Builders.bench(g.c, 9, V, 16, "south", 3, "spruce");
        Builders.bench(g.c, -11, V, 16, "south", 3, "spruce");
        Builders.bench(g.c, 9, V, -16, "north", 3, "spruce");
        Builders.bench(g.c, -11, V, -16, "north", 3, "spruce");
        // lamps on the plaza ring
        for (int a = 0; a < 8; a++) {
            double ang = Math.PI / 8 + a * Math.PI / 4;
            int lx = (int) Math.round(Math.cos(ang) * 19), lz = (int) Math.round(Math.sin(ang) * 19);
            g.clearAbove(lx, V, lz, 5);
            Builders.lampPost(g.c, lx, V, lz, "street", Blocks.dirOf(-lx, -lz));
        }
        // banners on tall posts at the four street mouths
        for (int[] q : new int[][]{{-5, -21, 8}, {5, -21, 8}, {-5, 21, 0}, {5, 21, 0}, {-21, -5, 4}, {-21, 5, 4}, {21, -5, 12}, {21, 5, 12}}) {
            for (int y = V + 1; y <= V + 4; y++) g.c.set(q[0], y, q[1], "stripped_dark_oak_log[axis=y]");
            g.c.set(q[0], V + 5, q[1], Blocks.banner(q[2] % 8 == 0 ? "yellow" : "blue", q[2]));
        }
        g.layout.standAt("spawn", 0, V, 12, 180f);
        g.area("plaza", Box.of(-22, V + 1, -22, 22, V + 3, 22));
        g.zone("plaza", "Village Square", 'b', Box.of(-23, V - 4, -23, 23, V + 30, 23), Layout.Zone.SAFE);
    }

    /** Three-tier fountain: a wide basin, a raised bowl and a lit spire. */
    static void grandFountain(HubCtx g, int cx, int y, int cz) {
        Palette rim = Palette.of("stone_bricks:4,mossy_stone_bricks:1,chiseled_stone_bricks:1");
        for (int dz = -6; dz <= 6; dz++)
            for (int dx = -6; dx <= 6; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 6.4) continue;
                int x = cx + dx, z = cz + dz;
                g.c.set(x, y, z, rim.at(x, y, z));
                g.c.set(x, y - 1, z, "stone_bricks");
                if (d > 5.5) g.c.set(x, y + 1, z, Blocks.slab("stone_brick", "bottom"));
                else g.c.set(x, y + 1, z, "water");
            }
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 2.4) continue;
                int x = cx + dx, z = cz + dz;
                g.c.set(x, y + 1, z, "chiseled_stone_bricks");
                g.c.set(x, y + 2, z, "polished_andesite");
                g.c.set(x, y + 3, z, d > 1.5 ? Blocks.slab("smooth_stone", "bottom") : "water");
            }
        g.c.set(cx, y + 3, cz, "chiseled_quartz_block");
        g.c.set(cx, y + 4, cz, "quartz_pillar[axis=y]");
        g.c.set(cx, y + 5, cz, "quartz_pillar[axis=y]");
        g.c.set(cx, y + 6, cz, "sea_lantern");
        for (int d = 0; d < 4; d++) g.c.set(cx + Blocks.DX[d], y + 6, cz + Blocks.DZ[d], Blocks.stairs("quartz", Blocks.DIRS[(d + 2) & 3], "top", "straight"));
        g.c.set(cx, y + 7, cz, "gold_block");
    }

    /** A 3x3 raised planter: stone rim, grass centre (for a tree), flowers around. */
    static void planter(HubCtx g, int x, int y, int z) {
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                g.c.set(x + dx, y, z + dz, dx == 0 && dz == 0 ? "grass_block" : "moss_block");
                if (dx != 0 || dz != 0) g.c.set(x + dx, y + 1, z + dz, (dx + dz & 1) == 0 ? "flowering_azalea" : "azalea");
            }
        for (int d = 0; d < 4; d++) {
            int bx = x + Blocks.DX[d] * 2, bz = z + Blocks.DZ[d] * 2;
            for (int s = -1; s <= 1; s++) {
                int ax = bx + Blocks.DX[(d + 1) & 3] * s, az = bz + Blocks.DZ[(d + 1) & 3] * s;
                g.c.set(ax, y + 1, az, Blocks.trapdoor("spruce", Blocks.DIRS[d], "bottom", true));
            }
        }
    }

    // =======================================================================================
    // building helpers
    // =======================================================================================

    /** A walkway from a building's door step to (tx, tz), paved with {@code f}. */
    static void walk(HubCtx g, Room r, int tx, int tz, Palette f) {
        int[] s = r.house.doorStep;
        int ex = s[0] + Blocks.dx(r.house.door), ez = s[2] + Blocks.dz(r.house.door);
        // start one block clear of the wall: a gable's ridge overhangs the door step, and a path that
        // samples its first height under a roof climbs the gable
        g.road(new int[][]{{ex, ez}, {ex + Blocks.dx(r.house.door), ez + Blocks.dz(r.house.door)}, {tx, tz}}, 3, f);
        g.paveCell(s[0], s[1], s[2], f);
    }

    /** A pillar of {@code block} from floor h1 to h2 at (u, v). */
    static void pillar(Room r, int u, int v, int h1, int h2, String block) {
        for (int h = h1; h <= h2; h++) r.set(u, h, v, block);
    }

    /** Two flower pots/barrels flanking a door outside, on the ground. */
    static void doorDecor(HubCtx g, Room r, String a, String b) {
        int[] s = r.house.doorStep;
        String side = Blocks.cw(r.house.door);
        int ax = s[0] + Blocks.dx(side) * 2, az = s[2] + Blocks.dz(side) * 2;
        int bx = s[0] - Blocks.dx(side) * 2, bz = s[2] - Blocks.dz(side) * 2;
        if (g.c.isAir(ax, s[1] + 1, az)) g.c.set(ax, s[1] + 1, az, a);
        if (g.c.isAir(bx, s[1] + 1, bz)) g.c.set(bx, s[1] + 1, bz, b);
    }

    /**
     * Anchor {@code door.<id>} on the step outside a building's main door, looking in. The Village
     * Directory (Hub Map) points a player's compass at it.
     */
    static void doorAnchor(HubCtx g, Room r, String id) {
        int[] s = r.house.doorStep;
        g.npc("door." + id, s[0], s[1], s[2], HubCtx.yaw(r.in));
    }

    /**
     * A second entrance in the wall that faces the square: a double door, a solid lintel, the two
     * rows inside cleared, a torch each side and a paved walk out to {@code (tx, tz)}. Anchor
     * {@code door.<id>} goes on its step.
     *
     * <p>Why (2026-10-03 playtest): the Bank, Auction House, Bazaar and Builder's House each had one
     * door, on the side facing the street their walk leads to, and a wall of windows facing the
     * square. A player walking over from the square met glass at all four and had to find the door
     * round the side. The toolkit's house has one door, so the extra one is cut here.
     *
     * @param face the wall the door is in (north/east/south/west)
     * @param at   the first of the two door columns along that wall (x for north/south, z for east/west)
     */
    static void squareDoor(HubCtx g, Room r, String id, String face, int at, int tx, int tz, Palette path) {
        Builders.House b = r.house;
        int x0 = b.x, x1 = b.x + b.width - 1, z0 = b.z, z1 = b.z + b.depth - 1, y = b.y;
        boolean alongX = face.equals("north") || face.equals("south");
        int wall = switch (face) { case "north" -> z0; case "south" -> z1; case "west" -> x0; default -> x1; };
        int lo = alongX ? x0 : z0, hi = alongX ? x1 : z1;
        if (at <= lo || at + 1 >= hi) throw new IllegalStateException("square door of " + id + " is off its wall: " + at);
        int ox = Blocks.dx(face), oz = Blocks.dz(face);
        String hingeSide = Blocks.ccw(face);
        for (int k = 0; k < 2; k++) {
            int a = at + k, other = at + 1 - k;
            int wx = alongX ? a : wall, wz = alongX ? wall : a;
            int ax = alongX ? other : wall, az = alongX ? wall : other;
            // the door further toward the hinge side hinges on its outer edge, so the pair opens apart
            boolean outer = (wx - ax) * Blocks.dx(hingeSide) + (wz - az) * Blocks.dz(hingeSide) > 0;
            Blocks.door(g.c, wx, y + 1, wz, b.doorWood, face, outer ? "left" : "right", false);
            // a window may sit in this column: the block over a door is wall
            g.c.set(wx, y + 3, wz, b.wall.at(wx, y + 3, wz));
            // inside: furniture lined along the wall must not block the way in
            for (int d = 1; d <= 2; d++) {
                int ix = wx - ox * d, iz = wz - oz * d;
                for (int h = 1; h <= 2; h++) {
                    BlockInfo bi = BlockInfo.of(g.c.get(ix, y + h, iz));
                    if (!g.c.isAir(ix, y + h, iz) && bi.top16 > 1) g.c.set(ix, y + h, iz, "air");
                }
            }
            // outside: the step, plants cleared
            g.paveCell(wx + ox, y, wz + oz, path);
            for (int h = 1; h <= 3; h++) if (g.natural(wx + ox, y + h, wz + oz)) g.c.set(wx + ox, y + h, wz + oz, "air");
        }
        // a torch on the wall either side of the pair
        for (int a : new int[]{at - 1, at + 2}) {
            int wx = alongX ? a : wall, wz = alongX ? wall : a;
            if (BlockInfo.of(g.c.get(wx, y + 3, wz)).cube && g.c.isAir(wx + ox, y + 3, wz + oz))
                g.c.set(wx + ox, y + 3, wz + oz, Blocks.wallTorch(face));
        }
        // the walk starts one block clear of the wall (an eave overhangs the step) and is 3 wide, so
        // centred on the first column it covers both
        int sx = (alongX ? at : wall) + ox, sz = (alongX ? wall : at) + oz;
        g.road(new int[][]{{sx + ox, sz + oz}, {sx + 2 * ox, sz + 2 * oz}, {tx, tz}}, 3, path);
        g.npc("door." + id, sx, y, sz, HubCtx.yaw(Blocks.opposite(face)));
    }

    // =======================================================================================
    // the big four around the plaza
    // =======================================================================================

    static void bank(HubCtx g, int V) {
        Room r = Bld.build(g, "bank", -44, V, -44, 23, 17, "east", Bld.CIVIC.floors(1, 7));
        // portico: quartz columns and a slab roof in front of the door
        g.reserve("bank.portico", Box.of(-19, V - 4, -42, -16, V + 8, -30));
        for (int z = -42; z <= -30; z++)
            for (int x = -19; x <= -16; x++) {
                g.paveCell(x, V, z, Fill.of("polished_andesite"));
                g.c.set(x, V + 7, z, Blocks.slab("smooth_quartz", "bottom"));
            }
        for (int z = -42; z <= -30; z += 4)
            for (int y = V + 1; y <= V + 6; y++) g.c.set(-17, y, z, "quartz_pillar[axis=y]");
        for (int z = -42; z <= -30; z++) g.c.set(-17, V + 6, z, "chiseled_quartz_block");
        r.floor(Palette.of("polished_andesite:3,smooth_stone:2"));
        r.runner(1, 10, "red_carpet");
        for (int u = -5; u <= 5; u++) {
            r.set(u, 1, 12, (u & 1) == 0 ? "polished_blackstone_bricks" : "chiseled_polished_blackstone");
            // lanterns at the ends and the middle, never in front of a Banker (u -3 / 3): one there
            // took the clicks aimed at his body (integration round 2)
            if (Math.abs(u) == 5 || u == 0) r.set(u, 2, 12, Blocks.lantern(false));
        }
        for (int v : new int[]{3, 8}) for (int u : new int[]{-5, 5}) pillar(r, u, v, 1, r.height, "quartz_pillar[axis=y]");
        // the vault at the back
        for (int u = -3; u <= 3; u++) for (int h = 1; h <= 5; h++) r.set(u, h, r.vMax, Math.abs(u) <= 1 && h >= 2 && h <= 4 ? "gold_block" : "iron_block");
        r.set(0, 3, r.vMax, "chiseled_quartz_block");
        r.set(-6, 1, 4, "lectern[facing=" + r.right() + ",has_book=true,powered=false]");
        r.set(r.uMin, 1, 15, "chest[facing=" + r.right() + ",type=single,waterlogged=false]");
        r.set(r.uMax, 1, 15, "chest[facing=" + r.left() + ",type=single,waterlogged=false]");
        r.chandeliers(5);
        r.backBanner(-5, 4, "yellow");
        r.backBanner(5, 4, "yellow");
        r.npc("npc.banker", -3, 14);
        r.npc("npc.banker_2", 3, 14);
        r.npc("npc.broker", -6, 5);
        g.zone("bank", "Bank", 'b', r.box().grow(1), Layout.Zone.SAFE);
        walk(g, r, -3, -36, AVENUE);
        squareDoor(g, r, "bank", "south", -34, -17, -14, AVENUE);
    }

    static void auctionHouse(HubCtx g, int V) {
        Room r = Bld.build(g, "auction_house", 22, V, -44, 23, 17, "west", Bld.SLATE.floors(1, 7));
        g.reserve("ah.portico", Box.of(16, V - 4, -42, 19, V + 8, -30));
        for (int z = -42; z <= -30; z++)
            for (int x = 16; x <= 19; x++) {
                g.paveCell(x, V, z, Fill.of("polished_deepslate"));
                g.c.set(x, V + 7, z, Blocks.slab("deepslate_tile", "bottom"));
            }
        for (int z = -42; z <= -30; z += 4)
            for (int y = V + 1; y <= V + 6; y++) g.c.set(17, y, z, "polished_basalt[axis=y]");
        for (int z = -42; z <= -30; z++) g.c.set(17, V + 6, z, "gilded_blackstone");
        r.floor(Palette.of("dark_oak_planks:3,polished_deepslate:1"));
        r.runner(1, 11, "red_carpet");
        // the auctioneer's podium and the two agents' desks
        r.set(0, 1, 12, "lectern[facing=" + r.back() + ",has_book=true,powered=false]");
        for (int u = -2; u <= 2; u++) if (u != 0) r.set(u, 1, 13, "polished_blackstone_bricks");
        r.set(-2, 2, 13, "gold_block");
        r.set(2, 2, 13, "gold_block");
        for (int s : new int[]{-1, 1}) {
            r.set(s * 6, 1, 7, "cartography_table");
            r.set(s * 6, 1, 9, "barrel[facing=up,open=false]");
        }
        // display cases down both walls: a plinth with a treasure block under glass
        String[] treasures = {"diamond_block", "emerald_block", "gold_block", "amethyst_block", "lapis_block", "redstone_block"};
        int k = 0;
        for (int v = 2; v <= 14; v += 4)
            for (int u : new int[]{r.uMin, r.uMax}) {
                r.set(u, 1, v, "polished_deepslate");
                r.set(u, 2, v, treasures[k++ % treasures.length]);
                r.set(u, 3, v, "glass");
            }
        r.chandeliers(5);
        r.backBanner(-4, 4, "red");
        r.backBanner(0, 5, "black");
        r.backBanner(4, 4, "red");
        r.npc("npc.auction_master", 0, 14);
        r.npc("npc.auction_agent_1", -6, 8);
        r.npc("npc.auction_agent_2", 6, 8);
        // the vault keeper's corner: a strongbox behind the keeper (auction module, vault auctions)
        // (u -8 was the side wall itself: the keeper stood in a window hole facing brick. Inside now.)
        r.set(r.uMin, 1, r.vMax, "vault[facing=" + r.back() + ",ominous=false,vault_state=inactive]");
        r.npc("npc.auction_vault", r.uMin + 1, r.vMax - 2);
        g.zone("auction_house", "Auction House", 'b', r.box().grow(1), Layout.Zone.SAFE);
        walk(g, r, 3, -36, AVENUE);
        // the pair sits east of the middle: the middle column of the south wall is a display case
        squareDoor(g, r, "auction_house", "south", 34, 17, -14, AVENUE);
    }

    static void museum(HubCtx g, int V) {
        Room r = Bld.build(g, "museum", 20, V, 22, 27, 21, "west", Bld.SAND.floors(1, 8));
        // a stepped entrance with columns
        g.reserve("museum.steps", Box.of(14, V - 4, 27, 17, V + 9, 37));
        for (int z = 27; z <= 37; z++) for (int x = 14; x <= 17; x++) g.paveCell(x, V, z, Fill.of("smooth_sandstone"));
        for (int z = 27; z <= 37; z += 5)
            for (int y = V + 1; y <= V + 7; y++) g.c.set(15, y, z, "cut_sandstone");
        for (int z = 27; z <= 37; z++) { g.c.set(15, V + 8, z, "chiseled_sandstone"); g.c.set(14, V + 8, z, Blocks.slab("smooth_sandstone", "bottom")); g.c.set(16, V + 8, z, Blocks.slab("smooth_sandstone", "bottom")); g.c.set(17, V + 8, z, Blocks.slab("smooth_sandstone", "bottom")); }
        r.floor(Palette.of("polished_andesite:3,polished_diorite:2,smooth_stone:1"));
        r.runner(1, 4, "brown_carpet");
        // curator's desk by the door
        for (int u = 3; u <= 6; u++) r.set(u, 1, 3, "spruce_planks");
        r.set(6, 2, 3, Blocks.lantern(false));
        // exhibits on plinths down both sides
        String[] ex = {"skeleton_skull[rotation=0]", "decorated_pot[facing=north,cracked=false,waterlogged=false]", "diamond_block",
                "amethyst_cluster[facing=up,waterlogged=false]", "emerald_block", "wither_skeleton_skull[rotation=0]", "lodestone",
                "sea_lantern", "creeper_head[rotation=0]", "gold_block", "zombie_head[rotation=0]", "beacon", "conduit[waterlogged=false]"};
        int k = 0;
        for (int v = 7; v <= r.vMax - 2; v += 4)
            for (int u : new int[]{-7, 7}) {
                r.set(u, 1, v, "chiseled_stone_bricks");
                r.set(u, 2, v, ex[k++ % ex.length]);
            }
        // a fossil skeleton down the middle: spine, ribs and skull of bone blocks
        String spine = "bone_block[axis=" + (r.fwd().equals("east") || r.fwd().equals("west") ? 'x' : 'z') + "]";
        for (int v = 9; v <= 17; v++) {
            r.set(0, 1, v, "polished_andesite");
            r.set(0, 2, v, "smooth_stone_slab[type=bottom,waterlogged=false]");
            r.set(0, 4, v, spine);
        }
        for (int v = 9; v <= 17; v += 4) { r.set(0, 3, v, "bone_block[axis=y]"); }
        for (int v = 11; v <= 15; v += 2) for (int u : new int[]{-1, 1}) r.set(u, 4, v, "bone_block[axis=y]");
        r.set(0, 5, 18, "bone_block[axis=y]");
        r.set(0, 4, 18, "bone_block[axis=y]");
        r.set(0, 5, 19, "skeleton_skull[rotation=0]");
        r.chandeliers(6);
        r.backBanner(-3, 5, "orange");
        r.backBanner(3, 5, "orange");
        r.npc("npc.museum", 4, 4);
        Box in = r.box();
        g.area("museum", in);
        g.zone("museum", "Museum", 'b', in.grow(1), Layout.Zone.SAFE);
        walk(g, r, 3, 32, AVENUE);
        doorAnchor(g, r, "museum");
    }

    static void bazaar(HubCtx g, int V) {
        Room r = Bld.build(g, "bazaar", -44, V, 22, 21, 17, "east", Bld.TIMBER.floors(1, 6).roof("spruce", "gable"));
        r.floor(Palette.of("spruce_planks:3,oak_planks:1"));
        // the central counter ring with produce, NPC inside
        for (int u = -3; u <= 3; u++) {
            r.set(u, 1, 8, "barrel[facing=up,open=false]");
        }
        r.set(-3, 2, 8, "melon"); r.set(3, 2, 8, "carved_pumpkin[facing=" + r.back() + "]");
        r.set(-1, 2, 8, "hay_block[axis=y]");
        r.set(1, 2, 8, Blocks.lantern(false));
        // produce shelves along the walls
        String[] goods = {"hay_block[axis=y]", "melon", "pumpkin", "barrel[facing=up,open=false]", "composter[level=0]", "beehive[facing=north,honey_level=0]",
                "dried_kelp_block", "bone_block[axis=y]", "slime_block", "brown_mushroom_block[down=true,east=true,north=true,south=true,up=true,west=true]"};
        int k = 0;
        for (int v = 3; v <= r.vMax - 1; v += 2) {
            r.set(r.uMin, 1, v, goods[k++ % goods.length]);
            r.set(r.uMax, 1, v, goods[k++ % goods.length]);
        }
        r.chandeliers(5);
        r.backBanner(-3, 4, "orange");
        r.backBanner(3, 4, "lime");
        r.npc("npc.bazaar", 0, 10);
        g.zone("bazaar", "Bazaar Alley", 'b', Box.of(-46, V - 2, 20, -10, V + 14, 44), Layout.Zone.SAFE);
        walk(g, r, -3, 30, AVENUE);
        // the alley: three stalls between the hall and the street
        String[][] colours = {{"red", "white"}, {"yellow", "orange"}, {"lime", "green"}};
        int i = 0;
        for (int z : new int[]{25, 33, 41}) {
            g.reserve("bazaar.stall." + i, Box.of(-17, V - 6, z - 2, -12, V + 5, z + 2));
            Builders.marketStall(g.c, -14, V, z, "east", colours[i][0], colours[i][1], g.seed + i);
            i++;
        }
        squareDoor(g, r, "bazaar", "north", -35, -18, 11, AVENUE);
    }

    // =======================================================================================
    // north side
    // =======================================================================================

    static void communityCenter(HubCtx g, int V) {
        Room r = Bld.build(g, "community_center", -42, V, -90, 21, 15, "east", Bld.BRICK.floors(1, 6).roof("dark_oak", "hip"));
        r.floor(Palette.of("oak_planks:3,birch_planks:1"));
        r.runner(1, 6, "light_blue_carpet");
        for (int u = -3; u <= 3; u++) r.set(u, 1, 8, u == 0 ? "bell[attachment=floor,facing=" + r.back() + ",powered=false]" : "oak_planks");
        r.set(-3, 2, 8, "cake[bites=0]");
        r.set(3, 2, 8, "potted_blue_orchid");
        for (int v = 2; v <= r.vMax; v += 3) {
            r.set(r.uMin, 1, v, "bookshelf");
            r.set(r.uMax, 1, v, "chest[facing=" + r.left() + ",type=single,waterlogged=false]");
        }
        r.chandeliers(5);
        r.backBanner(-4, 4, "light_blue");
        r.backBanner(0, 4, "white");
        r.backBanner(4, 4, "light_blue");
        r.npc("npc.community_shop", 0, 10);
        g.zone("community_center", "Community Center", 'b', r.box().grow(1), Layout.Zone.SAFE);
        walk(g, r, -3, -83, AVENUE);
        doorAnchor(g, r, "community_center");
        // the phone shop next door
        Room p = Bld.build(g, "phone_shop", -42, V, -70, 11, 11, "east", Bld.TIMBER.roof("spruce", "gable"));
        p.set(0, 1, 6, "spruce_planks"); p.set(-1, 1, 6, "spruce_planks"); p.set(1, 1, 6, "spruce_planks");
        p.set(1, 2, 6, "lightning_rod[facing=up,powered=false,waterlogged=false]");
        p.set(p.uMin, 1, 3, "note_block[instrument=bell,note=12,powered=false]");
        p.npc("npc.phone_vendor", 0, 7);
        walk(g, p, -3, -65, AVENUE);
    }

    static void electionHall(HubCtx g, int V) {
        Room r = Bld.build(g, "election_hall", 22, V, -92, 23, 17, "west", Bld.CIVIC.floors(1, 7).roof("deepslate_tile", "hip"));
        r.floor(Palette.of("polished_andesite:2,dark_oak_planks:3"));
        r.runner(1, 12, "purple_carpet");
        // rows of seats facing the podium, aisle in the middle
        for (int v = 3; v <= 10; v += 2)
            for (int u = -7; u <= 7; u++) {
                if (Math.abs(u) <= 1) continue;
                r.set(u, 1, v, Blocks.stairs("dark_oak", r.back()));
            }
        // the podium
        r.rect(-4, 13, 4, r.vMax, 0, "polished_blackstone_bricks");
        r.set(-2, 1, 13, "lectern[facing=" + r.back() + ",has_book=true,powered=false]");
        r.set(2, 1, 13, "lectern[facing=" + r.back() + ",has_book=true,powered=false]");
        r.backBanner(-6, 4, "red");
        r.backBanner(-2, 5, "blue");
        r.backBanner(2, 5, "green");
        r.backBanner(6, 4, "yellow");
        r.chandeliers(5);
        r.npc("npc.election", 0, 15);
        Box room = r.box();
        g.area("election_room", room);
        g.zone("election_room", "Election Room", 'b', room.grow(1), Layout.Zone.SAFE);
        walk(g, r, 3, -84, AVENUE);
        doorAnchor(g, r, "election_hall");
        Room d = Bld.build(g, "decorator", 30, V, -70, 11, 11, "west", Bld.BRICK);
        d.set(-2, 1, 6, "loom[facing=" + d.back() + "]");
        d.set(2, 1, 6, "white_wool");
        d.set(2, 2, 6, "pink_wool");
        d.set(0, 1, d.vMax, "flower_pot");
        d.npc("npc.decorator", 0, 5);
        walk(g, d, 3, -65, AVENUE);
    }

    // =======================================================================================
    // east side
    // =======================================================================================

    static void library(HubCtx g, int V) {
        Room r = Bld.build(g, "library", 58, V, -36, 23, 17, "south", Bld.SLATE.floors(1, 6).roof("dark_oak", "gable").wall("stone_bricks:4,mossy_stone_bricks:1,cracked_stone_bricks:1"));
        r.floor(Palette.of("dark_oak_planks:3,spruce_planks:1"));
        // bookshelves along the side and back walls, 3 high
        r.lineWalls(1, 3, Fill.of("bookshelf"), true, true, 2);
        // the enchanting corner: table, an air ring, and a ring of 15 shelves two high with a gap toward the door
        int tu = -5, tv = 8;
        for (int du = -2; du <= 2; du++)
            for (int dv = -2; dv <= 2; dv++) {
                boolean ring = Math.max(Math.abs(du), Math.abs(dv)) == 2;
                for (int h = 1; h <= 2; h++) r.set(tu + du, h, tv + dv, ring ? "bookshelf" : "air");
            }
        r.set(tu, 1, tv - 2, "air"); r.set(tu, 2, tv - 2, "air");
        r.set(tu, 1, tv, "enchanting_table");
        // reading desks and the librarian's lectern
        r.set(4, 1, 8, "lectern[facing=" + r.back() + ",has_book=true,powered=false]");
        for (int u = 3; u <= 7; u += 4) {
            r.set(u, 1, 4, Blocks.slab("dark_oak", "top"));
            r.set(u + 1, 1, 4, Blocks.slab("dark_oak", "top"));
            r.set(u, 2, 4, "candle[candles=2,lit=true,waterlogged=false]");
        }
        r.chandeliers(5);
        r.npc("npc.librarian", 4, 10);
        r.npc("npc.enchanting_table", tu, tv - 1, r.fwd());
        g.zone("library", "Library", 'b', r.box().grow(1), Layout.Zone.SAFE);
        walk(g, r, 69, -3, AVENUE);
        doorAnchor(g, r, "library");
        // the sewer entrance east of the library (the tunnel itself is carved later)
        sewerEntrance(g, V);
    }

    static void sewerEntrance(HubCtx g, int V) {
        g.reserve("sewer", Box.of(84, V - 10, -44, 93, V + 4, -9));
        // a walled stairwell going down northward from z=-12
        for (int z = -19; z <= -10; z++)
            for (int x = 85; x <= 91; x++) g.paveCell(x, V, z, Fill.of(x == 85 || x == 91 ? "mossy_stone_bricks" : "stone_bricks"));
        g.c.set(85, V + 1, -10, "stone_brick_wall"); g.c.set(85, V + 2, -10, Blocks.lantern(false));
        g.c.set(91, V + 1, -10, "stone_brick_wall"); g.c.set(91, V + 2, -10, Blocks.lantern(false));
        g.npc("npc.plumber", 88, V, -9, 0f);
    }

    static void tavern(HubCtx g, int V) {
        Room r = Bld.build(g, "tavern", 58, V, 16, 21, 17, "north", Bld.CABIN.floors(2, 5).chimney().wall("spruce_planks:4,stripped_spruce_log:1,cobblestone:1"));
        r.floor(Palette.of("spruce_planks:3,dark_oak_planks:1"));
        // the bar along the back with barrels behind it
        for (int u = -6; u <= 6; u++) { r.set(u, 1, 10, "stripped_spruce_wood[axis=y]"); }
        r.set(-4, 2, 10, Blocks.lantern(false)); r.set(4, 2, 10, Blocks.lantern(false));
        r.set(-2, 2, 10, "brewing_stand[has_bottle_0=true,has_bottle_1=true,has_bottle_2=false]");   // not in front of the bartender
        for (int u = -5; u <= 5; u++) { r.set(u, 1, r.vMax, "barrel[facing=" + r.back() + ",open=false]"); if ((u & 1) == 0) r.set(u, 2, r.vMax, "barrel[facing=up,open=false]"); }
        // tables with stools
        for (int[] t : new int[][]{{-5, 4}, {4, 4}, {4, 7}, {-5, 7}}) {
            r.set(t[0], 1, t[1], "spruce_fence");
            r.set(t[0], 2, t[1], "spruce_pressure_plate[powered=false]");
            r.set(t[0] + 1, 1, t[1], Blocks.stairs("spruce", r.left()));
            r.set(t[0] - 1, 1, t[1], Blocks.stairs("spruce", r.right()));
        }
        // the slayer master's notice wall: trophies over a board
        for (int v = 2; v <= 5; v++) { r.set(r.uMin, 2, v, "dark_oak_planks"); r.set(r.uMin, 3, v, (v & 1) == 0 ? "target[power=0]" : "dark_oak_planks"); }
        r.set(r.uMin, 1, 3, "chest[facing=" + r.right() + ",type=single,waterlogged=false]");
        r.set(r.uMin + 1, 1, 7, "smoker[facing=" + r.right() + ",lit=true]");
        r.npc("npc.slayer_master", r.uMin + 1, 4, r.right());
        r.npc("npc.bartender", 0, 12);
        r.chandeliers(6);
        Upstairs.furnish(g, r, "inn");   // the guest rooms over the bar
        g.zone("tavern", "Tavern", 'b', Box.of(57, V - 2, 15, 79, V + 14, 33), Layout.Zone.SAFE);
        walk(g, r, 68, 3, AVENUE);
        // a second door in the west wall, the side that faces the square and the spawn. The only door
        // was round the north side, so walking straight over to the Slayer Master met a wall of windows
        // (playtest "loops" 2026-10-03, at 58,21). This one opens on a clear row (z 19-20) that runs
        // the length of the room to him at the east wall; its walk joins the east lane (x 48..52).
        squareDoor(g, r, "tavern", "west", 19, 50, 19, AVENUE);
        // the alley behind: a sly vendor among crates
        Builders.crates(g.c, 84, V, 22, g.seed);
        Builders.crates(g.c, 84, V, 28, g.seed + 1);
        g.npc("npc.sly_vendor", 85, V, 25, HubCtx.yaw("west"));
        g.keepOut(Box.of(82, 0, 18, 88, 0, 32));
    }

    // =======================================================================================
    // south side
    // =======================================================================================

    static void petCare(HubCtx g, int V) {
        Room r = Bld.build(g, "pet_care", 20, V, 62, 19, 15, "west", Bld.BIRCH.floors(1, 5));
        r.floor(Palette.of("birch_planks:3,stripped_birch_wood[axis=y]:1"));
        String[] beds = {"red_carpet", "yellow_carpet", "light_blue_carpet", "lime_carpet", "pink_carpet"};
        int k = 0;
        for (int v = 3; v <= r.vMax - 1; v += 3) {
            r.set(r.uMin, 1, v, beds[k++ % beds.length]);
            r.set(r.uMax, 1, v, beds[k++ % beds.length]);
        }
        r.set(-2, 1, 9, "hay_block[axis=y]"); r.set(2, 1, 9, "water_cauldron[level=3]");
        r.set(-2, 2, 9, "potted_azure_bluet");
        r.npc("npc.pet_sitter", 0, 10);
        r.chandeliers(5);
        g.zone("pet_care", "Pet Care", 'b', r.box().grow(1), Layout.Zone.SAFE);
        walk(g, r, 3, 69, AVENUE);
        doorAnchor(g, r, "pet_care");
        // the pet yard behind: a fenced lawn with a little pond and the pet supplies stall
        g.reserve("pet_yard", Box.of(16, V - 6, 80, 44, V + 5, 95));
        Builders.fenceLine(g.c, new int[][]{{17, 81}, {43, 81}, {43, 94}, {17, 94}}, "birch_fence", true);
        Builders.gate(g.c, 17, V + 1, 87, "birch", "east");
        for (int z = 82; z <= 93; z++) for (int x = 18; x <= 42; x++) g.clearAbove(x, V, z, 3);
        Terrain.pond(g.c, g.h, 36, 89, 2.5, 1, g.seed);
        Builders.Stall st = Builders.marketStall(g.c, 24, V, 88, "east", "pink", "white", g.seed);
        g.npc("npc.pet_supplies", st.vendor()[0], V, st.vendor()[2], HubCtx.yaw("east"));
        g.keepOut(Box.of(16, 0, 80, 44, 0, 95));
        g.road(new int[][]{{4, 87}, {16, 87}}, 3, LANE);
    }

    static void alchemist(HubCtx g, int V) {
        Room r = Bld.build(g, "alchemist", -36, V, 60, 17, 13, "east", Bld.MUD.floors(1, 6).roof("deepslate_tile", "gable").chimney());
        r.floor(Palette.of("mangrove_planks:3,packed_mud:1"));
        for (int u = -3; u <= 3; u++) if (u != 0 && Math.abs(u) != 1) r.set(u, 1, 8, "polished_deepslate");
        r.set(2, 1, 10, "brewing_stand[has_bottle_0=true,has_bottle_1=false,has_bottle_2=true]");
        r.set(-2, 1, 10, "water_cauldron[level=3]");
        for (int u = r.uMin; u <= r.uMax; u++) {
            r.set(u, 1, r.vMax, (u & 1) == 0 ? "bookshelf" : "barrel[facing=" + r.back() + ",open=false]");
            r.set(u, 2, r.vMax, (u & 1) == 0 ? "potted_crimson_fungus" : "amethyst_cluster[facing=up,waterlogged=false]");
        }
        r.set(r.uMin, 1, 4, "cauldron"); r.set(r.uMax, 1, 4, "lava_cauldron");
        r.chandeliers(4);
        r.npc("npc.alchemist", 0, 10);
        r.npc("npc.brewing_stand", 1, 9, r.fwd());
        g.zone("alchemist", "Alchemist", 'b', r.box().grow(1), Layout.Zone.SAFE);
        walk(g, r, -3, 66, AVENUE);
        doorAnchor(g, r, "alchemist");
        // the flower house: glass walls, beehives and beds of flowers
        Room f = Bld.build(g, "flower_house", -38, V, 80, 15, 13, "east",
                Bld.BIRCH.wall("glass:3,birch_planks:1").floors(1, 5).roof("birch", "gable"));
        f.floor(Palette.of("moss_block:3,rooted_dirt:1"));
        String[] fl = {"allium", "poppy", "cornflower", "oxeye_daisy", "azure_bluet", "lily_of_the_valley", "pink_tulip", "orange_tulip", "blue_orchid", "dandelion"};
        int k = 0;
        for (int v = 2; v <= f.vMax; v += 1)
            for (int u : new int[]{f.uMin, f.uMin + 1, f.uMax - 1, f.uMax}) f.set(u, 1, v, fl[k++ % fl.length]);
        f.set(0, 1, f.vMax, "beehive[facing=" + f.back() + ",honey_level=3]");
        f.set(-2, 1, f.vMax, "bee_nest[facing=" + f.back() + ",honey_level=2]");
        f.npc("npc.beekeeper", 0, 6);
        walk(g, f, -3, 86, AVENUE);
    }

    // =======================================================================================
    // west side
    // =======================================================================================

    static void builder(HubCtx g, int V) {
        Room r = Bld.build(g, "builders_house", -90, V, -38, 21, 17, "south", Bld.TIMBER.floors(1, 6).roof("oak", "gable").wall("oak_planks:4,stripped_oak_log:1"));
        r.floor(Palette.of("oak_planks:3,spruce_planks:1"));
        String[] blocks = {"white_concrete", "red_wool", "bricks", "terracotta", "blue_concrete", "yellow_wool", "quartz_block", "green_terracotta",
                "glass", "mossy_stone_bricks", "orange_concrete", "prismarine_bricks", "cyan_wool", "polished_granite"};
        int k = 0;
        for (int v = 2; v <= r.vMax; v++)
            for (int h = 1; h <= 2; h++) {
                r.set(r.uMin, h, v, blocks[k++ % blocks.length]);
                r.set(r.uMax, h, v, blocks[k++ % blocks.length]);
            }
        r.set(-3, 1, 8, "crafting_table"); r.set(3, 1, 8, "stonecutter[facing=" + r.back() + "]");
        r.set(-5, 1, 4, "loom[facing=" + r.right() + "]");
        r.set(5, 1, 4, "smithing_table");
        r.npc("npc.builder", 0, 9);
        r.npc("npc.wool_weaver", -4, 4, r.right());
        r.npc("npc.tinkerer", 4, 4, r.left());
        r.chandeliers(5);
        g.zone("builders_house", "Builder's House", 'b', r.box().grow(1), Layout.Zone.SAFE);
        walk(g, r, -79, -3, AVENUE);
        // the yard: stacks of building materials
        g.reserve("builder_yard", Box.of(-66, V - 4, -42, -55, V + 5, -24));
        Palette stack = Palette.of("oak_planks:2,cobblestone:2,stone_bricks:2,bricks:1,white_wool:1,sandstone:1");
        for (int z = -40; z <= -26; z += 4)
            for (int x = -64; x <= -58; x += 3) {
                int hgt = 1 + (int) (g.rnd("stack", x, z) * 3);
                for (int y = 1; y <= hgt; y++) { g.c.set(x, V + y, z, stack.at(x, V + y, z)); g.c.set(x + 1, V + y, z, stack.at(x + 1, V + y, z)); }
            }
        Builders.crates(g.c, -57, V, -27, g.seed);
        // the square-side door: its walk runs straight down to the west avenue, just west of the
        // yard, and meets the Armorsmith's walk on the other side of the street
        squareDoor(g, r, "builders_house", "east", -31, -68, -5, AVENUE);
    }

    static void smiths(HubCtx g, int V) {
        Room w = Bld.build(g, "weaponsmith", -92, V, 12, 13, 11, "north", Bld.STONE.chimney());
        w.set(-3, 1, 6, "grindstone[face=floor,facing=" + w.fwd() + "]");
        w.set(3, 1, 6, "anvil[facing=" + w.right() + "]");
        w.set(0, 1, w.vMax, "smithing_table");
        w.set(w.uMin, 1, 3, "blast_furnace[facing=" + w.right() + ",lit=true]");
        w.npc("npc.weaponsmith", 0, 6);
        walk(g, w, -86, 3, AVENUE);
        doorAnchor(g, w, "weaponsmith");
        Room a = Bld.build(g, "armorsmith", -74, V, 12, 13, 11, "north", Bld.STONE.chimney().wall("stone_bricks:4,cobblestone:2,mossy_stone_bricks:1"));
        a.set(-3, 1, 6, "anvil[facing=" + a.right() + "]");
        a.set(3, 1, 6, "smithing_table");
        for (int u = -3; u <= 3; u += 2) { a.set(u, 1, a.vMax, "iron_block"); a.set(u, 2, a.vMax, "iron_bars"); }
        a.set(a.uMax, 1, 3, "furnace[facing=" + a.left() + ",lit=true]");
        a.npc("npc.armorsmith", 0, 6);
        walk(g, a, -68, 3, AVENUE);
        doorAnchor(g, a, "armorsmith");
        archery(g, V);
    }

    static void archery(HubCtx g, int V) {
        g.reserve("archery", Box.of(-96, V - 4, 28, -56, V + 6, 46));
        Builders.fenceLine(g.c, new int[][]{{-95, 29}, {-57, 29}, {-57, 45}, {-95, 45}}, "spruce_fence", true);
        Builders.gate(g.c, -57, V + 1, 37, "spruce", "east");
        for (int z = 30; z <= 44; z++) for (int x = -94; x <= -58; x++) { g.clearAbove(x, V, z, 3); g.c.set(x, V, z, ((x + z) & 3) == 0 ? "coarse_dirt" : "grass_block"); }
        // targets at the west end on hay bales
        int t = 0;
        for (int z = 31; z <= 43; z += 4) {
            g.c.set(-93, V + 1, z, "hay_block[axis=y]");
            g.c.set(-93, V + 2, z, "target[power=0]");
            g.c.set(-93, V + 3, z, Blocks.slab("spruce", "bottom"));
            // the hub's Target Practice reads these: the target block itself (walk check ignores "archery.")
            g.layout.anchor("archery.target." + (++t), -93, V + 2, z, 90f);
        }
        // the shooting line with a booth
        for (int z = 31; z <= 43; z++) g.c.set(-66, V, z, "stripped_spruce_wood[axis=y]");
        Builders.Stall st = Builders.marketStall(g.c, -61, V, 37, "west", "brown", "white", g.seed + 9);
        g.npc("npc.fletcher", st.vendor()[0], V, st.vendor()[2], HubCtx.yaw("west"));
        // Target Practice (research 03 section 2.2, Jax): the range master by the shooting line, the
        // standing ground a shot must come from (behind the line), and the whole range
        g.npc("door.archery_range", -56, V, 37, HubCtx.yaw("west"));   // outside the gate (Village Directory)
        g.npc("npc.range_master", -60, V, 30, HubCtx.yaw("east"));   // behind the shooters, off every line of fire
        g.area("archery.line", Box.of(-66, V + 1, 30, -58, V + 3, 44));
        g.area("archery", Box.of(-95, V - 1, 29, -57, V + 12, 45));
        g.zoneXZ("archery_range", "Archery Range", 'b', -96, 28, -56, 46, V - 3, V + 20, Layout.Zone.SAFE);
        g.keepOut(Box.of(-96, 0, 28, -56, 0, 46));
    }

    // =======================================================================================
    // corners
    // =======================================================================================

    static void nwCorner(HubCtx g, int V) {
        Room a = Bld.build(g, "adventurer", -94, V, -94, 17, 13, "south", Bld.TIMBER.chimney().roof("spruce", "gable"));
        String[] trophies = {"skeleton_skull[rotation=0]", "zombie_head[rotation=0]", "creeper_head[rotation=0]", "wither_skeleton_skull[rotation=0]"};
        for (int u = a.uMin; u <= a.uMax; u += 2) {
            a.set(u, 1, a.vMax, "chest[facing=" + a.back() + ",type=single,waterlogged=false]");
            a.set(u, 2, a.vMax, trophies[Math.floorMod(u, 3)]);
        }
        a.set(-2, 1, 6, "spruce_planks"); a.set(2, 1, 6, "spruce_planks"); a.set(2, 2, 6, Blocks.lantern(false));
        a.npc("npc.adventurer", 0, 7);
        walk(g, a, -86, -50, LANE);
        doorAnchor(g, a, "adventurer");
        Room t = Bld.build(g, "tailor", -72, V, -92, 13, 11, "south", Bld.BRICK.roof("dark_oak", "gable"));
        t.set(-3, 1, 5, "loom[facing=" + t.back() + "]");
        t.set(3, 1, 5, "cyan_wool"); t.set(3, 2, 5, "magenta_wool");
        for (int u = t.uMin; u <= t.uMax; u++) t.set(u, 1, t.vMax, (u & 1) == 0 ? "white_wool" : "light_gray_wool");
        t.npc("npc.tailor", 0, 6);
        walk(g, t, -66, -50, LANE);
        doorAnchor(g, t, "tailor");
        Room o = Bld.build(g, "owl_keeper", -94, V, -74, 11, 11, "east", Bld.CABIN);
        o.set(0, 1, o.vMax, "dark_oak_fence"); o.set(0, 2, o.vMax, "dark_oak_fence");
        o.set(-2, 1, 6, "hay_block[axis=y]");
        o.npc("npc.owl_keeper", 0, 5);
        walk(g, o, -50, -69, LANE);
    }

    static void neCorner(HubCtx g, int V) {
        Builders.Tower t = new Builders.Tower(76, V, -76);
        t.radius = 5; t.height = 21; t.top = "cone"; t.door = "south";
        t.wall = Palette.of("purpur_block:3,purpur_pillar[axis=y]:1,end_stone_bricks:2");
        t.roofFill = Palette.of("purple_concrete:2,magenta_terracotta:1");
        t.window = "purple_stained_glass_pane";
        g.reserve("thaumaturge_tower", t.reserve());
        t.build(g.c);
        g.npc("npc.thaumaturgist", t.inside[0], t.inside[1], t.inside[2], 0f);
        g.road(new int[][]{{t.doorStep[0], t.doorStep[2]}, {t.doorStep[0], t.doorStep[2] + 3}, {76, -52}}, 3, LANE);
        g.zoneXZ("thaumaturge", "Thaumaturge's Spire", 'd', 69, -83, 83, -69, V - 2, V + 40, Layout.Zone.SAFE);
    }

    static void seCorner(HubCtx g, int V) {
        Room r = Bld.build(g, "greenhouse", 58, V, 58, 19, 13, "west", Bld.BIRCH.wall("glass:4,birch_planks:1").floors(1, 6).roof("birch", "gable"));
        r.floor(Palette.of("moss_block:2,rooted_dirt:1,coarse_dirt:1"));
        String[] plants = {"potted_fern", "potted_azalea_bush", "potted_cactus", "sweet_berry_bush[age=3]", "potted_flowering_azalea_bush", "potted_bamboo"};
        int k = 0;
        for (int v = 2; v <= r.vMax; v += 2) {
            r.set(r.uMin, 1, v, "composter[level=7]");
            r.set(r.uMax, 1, v, "moss_block");
            r.set(r.uMax, 2, v, plants[k++ % plants.length]);
        }
        r.set(-2, 1, 8, "flowering_azalea"); r.set(2, 1, 8, "azalea");
        r.npc("npc.gardener", 0, 9);
        g.zone("greenhouse", "Greenhouse", 'b', r.box().grow(1), Layout.Zone.SAFE);
        walk(g, r, 50, 64, LANE);
        // the pig pen and its keeper
        g.reserve("pig_pen", Box.of(80, V - 4, 58, 95, V + 4, 74));
        Builders.fenceLine(g.c, new int[][]{{81, 59}, {94, 59}, {94, 73}, {81, 73}}, "oak_fence", true);
        Builders.gate(g.c, 81, V + 1, 66, "oak", "west");
        for (int z = 60; z <= 72; z++) for (int x = 82; x <= 93; x++) { g.clearAbove(x, V, z, 3); g.c.set(x, V, z, ((x * 7 + z) % 5 == 0) ? "mud" : "coarse_dirt"); }
        g.c.set(90, V + 1, 70, "hay_block[axis=y]");
        g.c.set(91, V + 1, 70, "composter[level=3]");
        g.npc("npc.pig_shop", 79, V, 66, HubCtx.yaw("east"));
        g.keepOut(Box.of(78, 0, 56, 96, 0, 76));
        g.area("village.pigpen", Box.of(82, V + 1, 60, 93, V + 2, 72));
    }

    static void fairyGarden(HubCtx g, int V) {
        int px = HubPlan.FAIRY_POND_X, pz = HubPlan.FAIRY_POND_Z;
        g.reserve("fairy_garden", Box.of(px - 16, V - 8, pz - 16, px + 16, V + 8, pz + 16));
        int level = Terrain.pond(g.c, g.h, px, pz, 8, 3, g.seed + 77);
        if (level != Terrain.Heightmap.NONE) wadeable(g, px, pz, 14, level);
        // lily pads and a ring of flowers and stepping stones
        for (int dz = -12; dz <= 12; dz++)
            for (int dx = -12; dx <= 12; dx++) {
                int x = px + dx, z = pz + dz;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (g.h.wet(x, z)) { if (g.rnd("lily", x, z) < 0.12 && g.c.isAir(x, g.h.top(x, z) + 1, z)) g.c.set(x, g.h.top(x, z) + 1, z, "lily_pad"); continue; }
                int y = g.ground(x, z);
                if (d > 9.5 && d < 11) { g.clearAbove(x, y, z, 2); g.c.set(x, y, z, (dx + dz & 1) == 0 ? "mossy_cobblestone" : "moss_block"); }
                else if (d >= 11 && d < 13 && g.c.isAir(x, y + 1, z) && g.rnd("ffl", x, z) < 0.5)
                    g.c.set(x, y + 1, z, g.rnd("ffk", x, z) < 0.5 ? "pink_tulip" : "allium");
            }
        // the fairy waits on the north-east bank, the side you arrive at from the square, so nobody
        // has to cross (or walk round) the pond to reach her; she looks back toward the town
        int fx = px + 8, fz = pz - 8, fy = g.ground(fx, fz);
        g.clearAbove(fx, fy, fz, 2);
        g.npc("npc.fairy", fx, fy, fz, -135f);
        Builders.bench(g.c, px + 6, g.ground(px + 6, pz - 11), pz - 11, "north", 3, "birch");
        g.zoneXZ("fairy_pond", "Fairy Pond", 'd', px - 14, pz - 14, px + 14, pz + 14, V - 6, V + 20, Layout.Zone.SAFE);
        g.keepOut(Box.of(px - 14, 0, pz - 14, px + 14, 0, pz + 14));
    }

    /**
     * Make a sunken pond something you can walk out of from anywhere (2026-10-03 playtest: a new
     * player walking in from the Village went into the Fairy Pond and drowned from 105 to 28 HP; the
     * bed dropped away under him and the bank stood two blocks over his feet, so pressing forward
     * walked him along the bottom, head under, into a wall).
     *
     * <p>Rule, applied until nothing changes: a water column's bed is at most one block below the
     * floor of each neighbour, where a dry neighbour's floor is its ground. So the bed is a staircase
     * up from anywhere; the ring touching the bank becomes a dry lip level with the water (one
     * step under the bank); the next ring is one deep, so a player standing in it keeps his head
     * above water. The middle keeps its depth.
     *
     * @return how many columns were made shallower (for the unit test)
     */
    static int wadeable(HubCtx g, int cx, int cz, int r, int level) {
        Palette bed = Palette.of("sand:4,clay:2,gravel:1");
        Palette lip = Palette.of("moss_block:3,mossy_cobblestone:1,coarse_dirt:1");
        int n = 2 * r + 1;
        int[][] floor = new int[n][n], was = new int[n][n];
        boolean[][] wet = new boolean[n][n];
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++) {
                int x = cx + dx, z = cz + dz, i = dx + r, k = dz + r;
                wet[i][k] = g.h.wet(x, z) && BlockState.name(g.c.get(x, level, z)).equals("water");
                if (!wet[i][k]) { floor[i][k] = g.h.top(x, z); continue; }
                int y = level;
                while (y > level - 16 && BlockState.name(g.c.get(x, y, z)).equals("water")) y--;
                floor[i][k] = was[i][k] = y;
            }
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 1; i < n - 1; i++)
                for (int k = 1; k < n - 1; k++) {
                    if (!wet[i][k]) continue;
                    int need = Math.max(Math.max(floor[i - 1][k], floor[i + 1][k]), Math.max(floor[i][k - 1], floor[i][k + 1])) - 1;
                    need = Math.min(need, level);
                    if (floor[i][k] < need) { floor[i][k] = need; changed = true; }
                }
        }
        int raised = 0;
        for (int i = 0; i < n; i++)
            for (int k = 0; k < n; k++) {
                if (!wet[i][k] || floor[i][k] == was[i][k]) continue;
                int x = cx + i - r, z = cz + k - r;
                raised++;
                for (int y = was[i][k] + 1; y <= floor[i][k]; y++) g.c.set(x, y, z, bed.at(x, y, z));
                if (floor[i][k] >= level) {
                    g.c.set(x, level, z, lip.at(x, level, z));
                    g.h.setWet(x, z, false);
                }
            }
        return raised;
    }

    // =======================================================================================
    // townhouses filling the empty lots
    // =======================================================================================

    static void infill(HubCtx g, int V) {
        Bld.Style[] styles = {Bld.TIMBER, Bld.BRICK, Bld.TIMBER.roof("spruce", "gable"), Bld.STONE.roof("dark_oak", "gable"),
                Bld.BRICK.roof("deepslate_tile", "hip"), Bld.TIMBER.floors(2, 4), Bld.CABIN, Bld.BIRCH.roof("dark_oak", "gable")};
        List<Room> homes = new ArrayList<>();
        outer:
        for (int gz = -94; gz <= 90; gz += 6)
            for (int gx = -94; gx <= 90; gx += 6) {
                double r = g.rnd("infill", gx, gz);
                int w = 9 + (int) (r * 5), d = 8 + (int) (g.rnd("infill-d", gx, gz) * 4);
                Bld.Style st = styles[(int) (g.rnd("infill-s", gx, gz) * styles.length)];
                Room h = home(g, V, gx, gz, w, d, st, 2, homes.size() + 1);
                if (h != null) homes.add(h);
                if (homes.size() >= 40) break outer;
            }
        // Wave 5: the civic buildings of waves 2-4 took most of the big lots (3 townhouses were left for 8
        // residents), so cottages fill the gaps that are left, a block closer to the lanes.
        Bld.Style[] cottages = {Bld.TIMBER, Bld.BRICK, Bld.CABIN, Bld.BIRCH.roof("dark_oak", "gable"), Bld.STONE.roof("spruce", "gable")};
        int want = HubAnchors.RESIDENTS.size() + 4;
        outer2:
        for (int gz = -95; gz <= 92; gz += 3)
            for (int gx = -95; gx <= 92; gx += 3) {
                if (homes.size() >= want) break outer2;
                int w = 7 + (int) (g.rnd("cottage", gx, gz) * 3), d = 7 + (int) (g.rnd("cottage-d", gx, gz) * 2);
                Bld.Style st = cottages[(int) (g.rnd("cottage-s", gx, gz) * cottages.length)];
                Room h = home(g, V, gx, gz, w, d, st, 1, homes.size() + 1);
                if (h != null) homes.add(h);
            }
        residents(g, homes);
    }

    /**
     * One furnished townhouse on the lot at (gx, gz) if it fits: inside the village, off the square, at least
     * {@code margin} blocks clear of every street, lamp and other building. Null when it does not fit.
     */
    static Room home(HubCtx g, int V, int gx, int gz, int w, int d, Bld.Style st, int margin, int n) {
        if (gx + w > 96 || gz + d > 96) return null;
        if (Math.abs(gx + w / 2) < 30 && Math.abs(gz + d / 2) < 30) return null;
        String door = nearestStreet(gx + w / 2, gz + d / 2);
        Builders.House probe = new Builders.House(gx, V, gz, w, d);
        probe.door = door; probe.roofStyle = st.roofStyle; probe.floors = st.floors; probe.floorHeight = st.floorHeight;
        Box rb = probe.reserve();
        if (!clearOfStreets(g, rb, margin) || g.res.overlap(rb.grow(margin)) != null) return null;
        Room h = Bld.build(g, "house." + n, gx, V, gz, w, d, door, st, true);
        furnishHome(g, h);
        Upstairs.furnish(g, h, "home");
        // a short walk out to the nearest paving
        int[] s0 = h.house.doorStep;
        int tx = s0[0], tz = s0[2];
        for (int k = 0; k < 40; k++) {
            tx += Blocks.dx(door); tz += Blocks.dz(door);
            if (g.roads.covers(tx, tz)) break;
        }
        int fx = s0[0] + Blocks.dx(door), fz = s0[2] + Blocks.dz(door);
        if (fx != tx || fz != tz) g.road(new int[][]{{fx, fz}, {tx, tz}}, 2, WALK);
        return h;
    }

    /**
     * Wave 5: townhouses with someone living in them. Each name in {@link HubAnchors#RESIDENTS} gets a
     * townhouse of its own, spread over the village (at least {@link #RESIDENT_SPACING} blocks from the
     * last pick when possible), standing at the back of the ground floor facing the door with a clear
     * line from the doorway, never beside a ladder.
     */
    static final int RESIDENT_SPACING = 30;

    static void residents(HubCtx g, List<Room> homes) {
        List<Room> left = new ArrayList<>(homes);
        List<int[]> taken = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : HubAnchors.RESIDENTS) {
            Room pick = null;
            int[] spot = null;
            for (int pass = 0; pass < 2 && pick == null; pass++)
                for (Room r : left) {
                    int cx = r.house.x + r.house.width / 2, cz = r.house.z + r.house.depth / 2;
                    boolean far = true;
                    for (int[] t : taken) if (Math.hypot(t[0] - cx, t[1] - cz) < RESIDENT_SPACING) far = false;
                    if (pass == 0 && !far) continue;
                    int[] s = residentSpot(r);
                    if (s == null) continue;
                    pick = r; spot = s;
                    break;
                }
            if (pick == null) { missing.add(name); continue; }   // the unit test fails on a missing resident; the module only logs it
            left.remove(pick);
            taken.add(new int[]{pick.house.x + pick.house.width / 2, pick.house.z + pick.house.depth / 2});
            pick.npc(name, spot[0], spot[1]);
            // a doormat inside the door, so the house reads as somebody's
            if (pick.free(0, 1, 0)) pick.set(0, 1, 0, "brown_carpet");
        }
        // diagnostics for the preview report and /sb hub info: "8/8 in 31 townhouses"
        g.layout.data("hub.residents", (HubAnchors.RESIDENTS.size() - missing.size()) + "/" + HubAnchors.RESIDENTS.size()
                + " in " + homes.size() + " townhouses" + (missing.isEmpty() ? "" : "; no room for " + String.join(", ", missing)));
    }

    /**
     * Back-most (u, v) on the door's axis (or next to it) with floor, head room and a clear walk from the doorway.
     * Carpet counts as open floor: the toolkit lays rugs and runners in most homes (wave 5: with plain air only
     * 3 of the 8 residents found a house).
     */
    static int[] residentSpot(Room r) {
        for (int v = r.vMax - 1; v >= 2; v--)
            for (int u : new int[]{0, 1, -1, 2, -2}) {
                if (u < r.uMin + 1 || u > r.uMax - 1) continue;
                if (r.free(u, 0, v) || !open(r, u, 1, v) || !r.free(u, 2, v)) continue;
                boolean clear = true;
                for (int w = 0; w < v && clear; w++)
                    if (!open(r, u, 1, w) || !r.free(u, 2, w) || r.free(u, 0, w)) clear = false;
                // the doorway itself is on u = 0: a resident off the axis needs the cells between too
                for (int k = 1; k <= Math.abs(u) && clear; k++) {
                    int uu = Integer.signum(u) * k;
                    if (!open(r, uu, 1, 0) || !r.free(uu, 2, 0)) clear = false;
                }
                if (!clear) continue;
                if (nearLadder(r, u, v)) continue;
                return new int[]{u, v};
            }
        return null;
    }

    /** Air or a carpet (a rug is walked over, not around). */
    static boolean open(Room r, int u, int h, int v) {
        if (r.free(u, h, v)) return true;
        return BlockState.name(r.g.c.get(r.x(u, v), r.y + h, r.z(u, v))).endsWith("_carpet");
    }

    static boolean nearLadder(Room r, int u, int v) {
        for (int du = -1; du <= 1; du++)
            for (int dv = -1; dv <= 1; dv++)
                for (int h = 1; h <= 2; h++)
                    if (BlockState.name(r.g.c.get(r.x(u + du, v + dv), r.y + h, r.z(u + du, v + dv))).equals("ladder")) return true;
        return false;
    }

    /** Door side that faces the nearest street or lane line. */
    static String nearestStreet(int cx, int cz) {
        int[] lines = {0, -50, 50};
        int bestX = Integer.MAX_VALUE, bestZ = Integer.MAX_VALUE, lx = 0, lz = 0;
        for (int l : lines) {
            if (Math.abs(cx - l) < bestX) { bestX = Math.abs(cx - l); lx = l; }
            if (Math.abs(cz - l) < bestZ) { bestZ = Math.abs(cz - l); lz = l; }
        }
        if (bestX <= bestZ) return lx < cx ? "west" : "east";
        return lz < cz ? "north" : "south";
    }

    static boolean clearOfStreets(HubCtx g, Box b, int margin) {
        for (int x = b.minX() - margin; x <= b.maxX() + margin; x++)
            for (int z = b.minZ() - margin; z <= b.maxZ() + margin; z++) {
                if (g.roads.covers(x, z) || g.lampNear(x, z, 0)) return false;
                if (x < -97 || x > 97 || z < -97 || z > 97) return false;
                for (Box k : g.keepOut) if (k.containsXZ(x, z)) return false;
            }
        return true;
    }

    /** A few lived-in touches inside a townhouse (the toolkit already furnished its corners). */
    static void furnishHome(HubCtx g, Room r) {
        int mid = r.vMax / 2;
        String[] colours = {"red", "blue", "green", "yellow", "light_blue", "brown"};
        String bed = colours[(int) (g.rnd("bed", r.house.x, r.house.z) * colours.length)] + "_bed[facing=" + r.fwd() + ",occupied=false,part=";
        if (r.vMax > 4 && r.free(r.uMin, 1, mid) && r.free(r.uMin, 1, mid + 1)) {
            r.set(r.uMin, 1, mid, bed + "foot]");
            r.set(r.uMin, 1, mid + 1, bed + "head]");
        }
        if (r.free(r.uMax, 1, mid)) r.set(r.uMax, 1, mid, "furnace[facing=" + r.left() + ",lit=false]");
    }

    // =======================================================================================
    /**
     * Where the seasonal vendors stand while their festival runs (the mayors module places them;
     * nothing stands here otherwise): four spots on the plaza ring between the lamps and the
     * planters, and the north side of the fountain for the share broker.
     */
    static void festivalSpots(HubCtx g, int V) {
        String[][] spots = {{"npc.baker", "15", "8"}, {"npc.fear_mongerer", "-15", "8"}, {"npc.zoo", "-15", "-8"},
                {"npc.mythology", "15", "-8"}, {"npc.stonks", "0", "-10"}};
        for (String[] sp : spots) {
            int x = Integer.parseInt(sp[1]), z = Integer.parseInt(sp[2]);
            g.npc(sp[0], x, g.ground(x, z), z, HubCtx.yaw(Blocks.dirOf(-x, -z)));
        }
    }

    // people and small things on the streets
    // =======================================================================================

    static void streetLife(HubCtx g, int V) {
        int[][] lore = {{2, -19}, {-2, 19}, {19, -2}, {-19, 2}, {-2, -62}, {2, 62}, {62, -2}, {-62, 2},
                {48, -30}, {-50, 30}, {30, 52}, {-30, -52}};
        for (int i = 0; i < lore.length; i++) {
            int x = lore[i][0], z = lore[i][1];
            g.npc("npc.lore." + (i + 1), x, g.ground(x, z), z, HubCtx.yaw(Blocks.dirOf(-x, -z)));
        }
        // the hub guide stands by the spawn, facing new arrivals
        g.npc("npc.hub_guide", 4, V, 15, 180f);
        // wells and haystacks in the lanes' corners
        g.reserve("well.sw", Box.of(-55, V - 4, 50 + 5, -51, V + 5, 59));
        Builders.well(g.c, -53, V, 57);
        Builders.crates(g.c, 54, V, 47, g.seed + 3);
        Builders.crates(g.c, -56, V, -47, g.seed + 4);
        // small trees on the lawns
        Trees.forest(g.c, g.h, HubPlan.VILLAGE, g.seed + 5, 0.010, 9, "oak:3,birch:2,cherry:1", 3,
                (x, z) -> g.treeBlocked(x, z, 4) || Math.abs(x) < 26 && Math.abs(z) < 26);
    }

}
