package com.skyclone.mod.hollows.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Palette;

import java.util.ArrayList;
import java.util.List;

/**
 * The Crystal Nucleus: a round hall 80 blocks across in the middle of the caverns. Players arrive on
 * the Entrance balcony high on its north wall (spawn, the two portals, the merchant, the guide),
 * walk a ledge that spirals down the wall to the floor, and find the five crystal sockets on the
 * dais in the middle. The four quadrant tunnels and the Magma descent leave from the floor.
 */
final class Nucleus {

    private Nucleus() {}

    static final int FLOOR = 100, R = 40, WALL_TOP = 134, DOME = 20;
    static final int BAL_Y = 136, BAL_X0 = -12, BAL_X1 = 12, BAL_Z0 = -53, BAL_Z1 = -36;
    static final double RAMP_R = 37.5, RAMP_START = Math.toRadians(-71), RAMP_SLOPE = 0.6;
    /** Crystal colours in socket order (the mining module's NUCLEUS_GEMS order). */
    static final String[][] SOCKETS = {{"AMETHYST", "purple"}, {"JADE", "lime"}, {"SAPPHIRE", "light_blue"}, {"AMBER", "orange"}, {"TOPAZ", "yellow"}};

    /** Ramp samples: {x, floorY, z, angle(deg*100)} for the build phase. */
    private static final List<double[]> ramp = new ArrayList<>();

    static void prepare(Ctx x) {
        Grid g = x.g;
        ramp.clear();
        // the hall: a rough cylinder with a dome
        for (int y = FLOOR + 1; y <= WALL_TOP + DOME; y++) {
            double r = y <= WALL_TOP ? R : R * Math.sqrt(Math.max(0, 1 - Math.pow((y - WALL_TOP) / (double) DOME, 2)));
            int ri = (int) Math.ceil(r + 3);
            for (int z = -ri; z <= ri; z++)
                for (int xx = -ri; xx <= ri; xx++) {
                    double d = Math.sqrt(xx * xx + z * z);
                    double n = x.noise.noise3(xx / 9.0, y / 7.0, z / 9.0);
                    if (d < r + 1.8 * n) g.carve(xx, y, z);
                }
        }
        // the floor (and a solid bowl under it)
        for (int z = -R - 2; z <= R + 2; z++)
            for (int xx = -R - 2; xx <= R + 2; xx++) {
                if (xx * xx + z * z > (R + 1.5) * (R + 1.5)) continue;
                for (int y = FLOOR - 8; y < FLOOR; y++) g.set(xx, y, z, Grid.ROCK);
                g.put(xx, FLOOR, z, Grid.ROCK, true);
            }
        // the Entrance balcony, cut into the north wall
        Structures.carveRoom(x, BAL_X0, BAL_Y + 1, BAL_Z0, BAL_X1, BAL_Y + 10, BAL_Z1);
        for (int z = BAL_Z0; z <= BAL_Z1; z++)
            for (int xx = BAL_X0; xx <= BAL_X1; xx++)
                for (int y = BAL_Y - 2; y <= BAL_Y; y++) g.put(xx, y, z, Grid.ROCK, true);
        x.structure(Box.of(BAL_X0, BAL_Y + 1, BAL_Z0, BAL_X1, BAL_Y + 10, BAL_Z1), true);
        // the spiral ledge: from the balcony's east end down the wall to the floor
        double s = 0;
        while (true) {
            double fy = BAL_Y - s * RAMP_SLOPE;
            int iy = (int) Math.round(fy);
            if (iy <= FLOOR) break;
            double a = RAMP_START + s / RAMP_R;
            for (double r = RAMP_R - 2.3; r <= R + 1.5; r += 0.4) {
                int cx = (int) Math.floor(Math.cos(a) * r), cz = (int) Math.floor(Math.sin(a) * r);
                boolean walkway = r <= RAMP_R + 2.1;
                g.put(cx, iy, cz, Grid.ROCK, true);
                g.put(cx, iy - 1, cz, Grid.ROCK, true);
                if (walkway) {
                    x.deck.put(Ctx.key(cx, iy, cz), (cx + cz & 3) == 0 ? "deepslate_tiles" : "polished_deepslate");
                    for (int y = iy + 1; y <= iy + 3; y++) if (!(g.locked(cx, y, cz) && g.solid(cx, y, cz))) g.put(cx, y, cz, Grid.AIR, true);
                }
            }
            ramp.add(new double[]{Math.cos(a) * (RAMP_R - 2.6), iy, Math.sin(a) * (RAMP_R - 2.6), Math.toDegrees(a)});
            s += 0.35;
        }
        // a niche halfway down the ledge (a fairy soul's hiding place)
        double mid = (BAL_Y - FLOOR) / RAMP_SLOPE * 0.55;
        double am = RAMP_START + mid / RAMP_R;
        int ny = (int) Math.round(BAL_Y - mid * RAMP_SLOPE);
        int nx = (int) Math.floor(Math.cos(am) * (R + 1)), nz = (int) Math.floor(Math.sin(am) * (R + 1));
        int dx = (int) Math.signum(Math.round(Math.cos(am))), dz = (int) Math.signum(Math.round(Math.sin(am)));
        if (dx == 0 && dz == 0) dz = -1;
        if (dx != 0 && dz != 0) dz = 0;
        int[] niche = Carve.alcove(x, nx, ny, nz, dx, dz, 2);
        x.l.data("hollows.nucleus.niche", niche[0] + "," + niche[1] + "," + niche[2]);
    }

    static void build(Ctx x) {
        Layout l = x.l;
        Palette floorPal = Palette.of("polished_deepslate:3,deepslate_tiles:3,cracked_deepslate_tiles:1,deepslate_bricks:2");
        // floor rings
        for (int z = -R - 1; z <= R + 1; z++)
            for (int xx = -R - 1; xx <= R + 1; xx++) {
                double d = Math.sqrt(xx * xx + z * z);
                if (d > R + 0.5 || !x.g.locked(xx, FLOOR, z)) continue;
                String b = floorPal.at(xx, FLOOR, z);
                if ((d >= 14 && d < 15.2) || (d >= 26 && d < 27.2)) b = "polished_blackstone_bricks";
                double ang = Math.toDegrees(Math.atan2(z, xx));
                if (d >= 20 && d < 21 && ((int) Math.round(ang / 15.0)) % 2 == 0) b = "sea_lantern";
                if (d >= 33 && d < 34 && ((int) Math.round(ang / 30.0)) % 3 == 0) b = "sea_lantern";
                x.c.set(xx, FLOOR, z, b);
            }
        // the dais: two rings up, the five sockets, the crystal spire
        for (int z = -10; z <= 10; z++)
            for (int xx = -10; xx <= 10; xx++) {
                double d = Math.sqrt(xx * xx + z * z);
                if (d <= 9.4) x.c.set(xx, FLOOR + 1, z, d > 8.4 ? "polished_blackstone_bricks" : "polished_blackstone");
                if (d <= 6.4) x.c.set(xx, FLOOR + 2, z, d > 5.4 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
            }
        for (int i = 0; i < SOCKETS.length; i++) {
            double a = Math.toRadians(-90 + 72 * i);
            int px = (int) Math.round(Math.cos(a) * 7.6), pz = (int) Math.round(Math.sin(a) * 7.6);
            x.c.set(px, FLOOR + 2, pz, "polished_blackstone");
            x.c.set(px, FLOOR + 3, pz, SOCKETS[i][1] + "_concrete");
            x.c.set(px, FLOOR + 4, pz, "end_rod[facing=up]");
            l.standAt("socket." + SOCKETS[i][0].toLowerCase(), px, FLOOR + 1, pz, 0f);
        }
        Structures.box(x, -1, FLOOR + 3, -1, 1, FLOOR + 3, 1, "sea_lantern");
        for (int y = FLOOR + 4; y <= FLOOR + 8; y++) {
            Structures.box(x, -1, y, -1, 1, y, 1, y % 2 == 0 ? "amethyst_block" : "crying_obsidian");
        }
        for (int y = FLOOR + 9; y <= FLOOR + 13; y++) x.c.set(0, y, 0, y == FLOOR + 13 ? "end_rod[facing=up]" : "amethyst_block");
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) x.c.set(o[0] * 2, FLOOR + 3, o[1] * 2, "amethyst_cluster[facing=up,waterlogged=false]");
        // soul lamps around the floor
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(22.5 + 45 * i);
            int px = (int) Math.round(Math.cos(a) * 30), pz = (int) Math.round(Math.sin(a) * 30);
            if (x.g.locked(px, FLOOR + 1, pz)) continue;               // a tunnel mouth's walkway
            Builders.lampPost(x.c, px, FLOOR, pz, "soul", "north");
        }
        // the ledge: rails on the open side, pillars under it
        int k = 0;
        for (double[] p : ramp) {
            int py = (int) p[1];
            double a = Math.toRadians(p[3]);
            int rx = (int) Math.floor(Math.cos(a) * (RAMP_R - 2.1)), rz = (int) Math.floor(Math.sin(a) * (RAMP_R - 2.1));
            if (x.g.locked(rx, py, rz) && x.g.solid(rx, py, rz) && !x.g.solid(rx, py + 1, rz)) x.c.set(rx, py + 1, rz, "deepslate_tile_wall");
            if (k++ % 30 == 0) {
                int cx = (int) Math.floor(Math.cos(a) * (RAMP_R + 0.5)), cz = (int) Math.floor(Math.sin(a) * (RAMP_R + 0.5));
                boolean clear = true;
                for (int y = FLOOR + 1; y < py - 1; y++) if (x.g.locked(cx, y, cz) || x.g.solid(cx, y, cz)) { clear = false; break; }
                if (clear) for (int y = FLOOR + 1; y < py - 1; y++) x.c.set(cx, y, cz, y % 6 == 0 ? "chiseled_deepslate" : "deepslate_bricks");
            }
        }
        balcony(x);
        // anchors and areas
        l.standAt("nucleus", 0, FLOOR + 2, -4, 180f);
        l.area("nucleus", Box.of(-9, FLOOR + 1, -9, 9, FLOOR + 10, 9));
        l.anchor("landmark.crystal_nucleus", 0.5, FLOOR + 3, 0.5, 0f);
        l.zone(Zones.NUCLEUS.id(), Zones.NUCLEUS.name(), Zones.NUCLEUS.color(),
                Box.of(-Zones.NUCLEUS_R, Zones.MAGMA_TOP + 1, -Zones.NUCLEUS_R, Zones.NUCLEUS_R, WALL_TOP + DOME + 2, Zones.NUCLEUS_R), null, Layout.Zone.SAFE);
        // fairy souls: under the ledge's start, in the niche, and behind the balcony crates
        String niche = l.data().get("hollows.nucleus.niche");
        if (niche != null) {
            String[] p = niche.split(",");
            x.soul(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        x.soul(-35, FLOOR, 12);
    }

    private static void balcony(Ctx x) {
        Layout l = x.l;
        Palette deck = Palette.of("polished_andesite:4,andesite:1,stone_bricks:2");
        Fill frame = Palette.of("polished_blackstone_bricks:4,cracked_polished_blackstone_bricks:1");
        Fill clad = Palette.of("stone_bricks:5,mossy_stone_bricks:1,cracked_stone_bricks:1");
        for (int z = BAL_Z0; z <= BAL_Z1; z++)
            for (int xx = BAL_X0; xx <= BAL_X1; xx++) x.c.set(xx, BAL_Y, z, deck.at(xx, BAL_Y, z));
        // back wall cladding
        for (int y = BAL_Y + 1; y <= BAL_Y + 9; y++)
            for (int xx = BAL_X0; xx <= BAL_X1; xx++) x.c.set(xx, y, BAL_Z0 - 1, clad.at(xx, y, BAL_Z0 - 1));
        // two portals on the back wall
        portal(x, -9, "dwarven_mines", frame, "lime");
        portal(x, 5, "hub", frame, "light_blue");
        // railing along the open front, open where the ledge begins
        for (int xx = BAL_X0; xx <= 8; xx++) x.c.set(xx, BAL_Y + 1, BAL_Z1, "stone_brick_wall");
        for (int xx : new int[]{BAL_X0, -4, 4}) x.c.set(xx, BAL_Y + 2, BAL_Z1, "lantern[hanging=false,waterlogged=false]");
        // camp dressing
        Builders.crates(x.c, BAL_X0 + 1, BAL_Y, BAL_Z0 + 2, x.seed);
        Builders.crates(x.c, BAL_X1 - 2, BAL_Y, BAL_Z0 + 2, x.seed + 1);
        Builders.campfire(x.c, -3, BAL_Y, -42);
        Builders.bench(x.c, -6, BAL_Y, -40, "south", 3, "spruce");
        x.c.set(BAL_X1 - 1, BAL_Y + 1, -44, "barrel[facing=up,open=false]");
        x.c.set(BAL_X1 - 1, BAL_Y + 1, -45, "barrel[facing=up,open=false]");
        Structures.chandelier(x, 0, BAL_Y + 5, -46, 3);
        Structures.chandelier(x, -8, BAL_Y + 5, -48, 2);
        Structures.chandelier(x, 8, BAL_Y + 5, -48, 2);
        l.standAt("spawn", 0, BAL_Y, -43, 0f);
        l.standAt("npc.hollows_merchant", -9, BAL_Y, -44, 90f);
        l.standAt("npc.hollows_guide", 9, BAL_Y, -44, -90f);
        l.standAt("npc.gemcutter", -11, BAL_Y, -39, -90f);
        l.zone("caverns_entrance", "Caverns Entrance", 'e', Box.of(BAL_X0, BAL_Y - 1, BAL_Z0, BAL_X1, BAL_Y + 10, BAL_Z1), null, Layout.Zone.SAFE);
        x.soul(BAL_X0 + 1, BAL_Y, BAL_Z0 + 1);
    }

    /** A 3-wide, 4-tall portal framed on the balcony's back wall; {@code x0} is the frame's west post. */
    private static void portal(Ctx x, int x0, String warp, Fill frame, String banner) {
        int z = BAL_Z0;
        for (int y = BAL_Y + 1; y <= BAL_Y + 5; y++) {
            x.c.set(x0, y, z, frame.at(x0, y, z));
            x.c.set(x0 + 4, y, z, frame.at(x0 + 4, y, z));
        }
        for (int xx = x0; xx <= x0 + 4; xx++) x.c.set(xx, BAL_Y + 5, z, frame.at(xx, BAL_Y + 5, z));
        for (int xx = x0 + 1; xx <= x0 + 3; xx++)
            for (int y = BAL_Y + 1; y <= BAL_Y + 4; y++) x.c.set(xx, y, z, "nether_portal[axis=x]");
        x.c.set(x0 + 2, BAL_Y + 6, z, "chiseled_polished_blackstone");
        x.c.set(x0, BAL_Y + 6, z + 1, banner + "_wall_banner[facing=south]");
        x.c.set(x0 + 4, BAL_Y + 6, z + 1, banner + "_wall_banner[facing=south]");
        x.l.area("portal." + warp, Box.of(x0 + 1, BAL_Y + 1, z, x0 + 3, BAL_Y + 4, z));
    }
}
