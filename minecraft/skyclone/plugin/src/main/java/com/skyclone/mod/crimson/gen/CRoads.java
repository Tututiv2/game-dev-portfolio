package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Palette;

import java.util.ArrayList;
import java.util.List;

/** Every road on the isle, then lamps along the long ones. Runs after all the builders. */
final class CRoads {

    private CRoads() {}

    private static final Palette MAIN = Palette.of("blackstone:4,polished_blackstone:3,gravel:2,basalt[axis=y]:1");
    private static final Palette LANE = Palette.of("gravel:3,blackstone:3,coarse_dirt:2,soul_soil:1");
    private static final String STEP = "blackstone";

    static void build(CCtx x) {
        List<int[][]> lit = new ArrayList<>();

        // ---- trunk roads from the Stronghold ----
        lit.add(road(x, 4, MAIN, p(x, "stronghold.north"), xz(0, 20), p(x, "crossroads")));
        lit.add(road(x, 4, MAIN, xz(-8, -12), xz(-50, -4), xz(-92, 14), p(x, "drakehold_gate")));
        lit.add(road(x, 4, MAIN, xz(8, -12), xz(50, -4), xz(92, 14), p(x, "carmine_gate")));
        lit.add(road(x, 4, MAIN, p(x, "stronghold.south"), xz(6, 124), p(x, "tomb")));
        // the climb: crossroads -> volcano foot -> switchbacks -> crater lookout
        lit.add(road(x, 3, MAIN, xz(0, -18), xz(2, -50), xz(6, -64), xz(34, -82), xz(40, -102), xz(18, -114),
                xz(-16, -112), xz(-24, -124), xz(-8, -125), p(x, "crater")));
        road(x, 3, LANE, xz(2, -52), xz(-10, -56), p(x, "tunnel"));
        // west: marsh and the lava-fishing hut
        lit.add(road(x, 3, MAIN, xz(-6, -16), xz(-55, -40), xz(-90, -58), p(x, "hut")));
        road(x, 3, LANE, xz(-112, -86), xz(-140, -104), xz(-165, -125), xz(-176, -140));
        road(x, 3, LANE, xz(-140, -106), p(x, "belly"));
        // east: dojo and the skull, then the dock
        lit.add(road(x, 3, MAIN, xz(6, -16), xz(60, -32), xz(92, -64), p(x, "dojo")));
        lit.add(road(x, 3, MAIN, xz(106, -80), xz(134, -96), xz(150, -108), p(x, "skull")));
        road(x, 3, LANE, xz(156, -150), p(x, "dock"));
        // south: tomb -> ruins -> courtyard; carmine -> courtyard
        lit.add(road(x, 3, MAIN, xz(28, 136), xz(70, 142), p(x, "ashfang.w")));
        lit.add(road(x, 3, MAIN, p(x, "ashfang.e"), xz(146, 148), xz(152, 128), p(x, "courtyard.w")));
        lit.add(road(x, 3, MAIN, p(x, "carmine_square"), xz(163, 42), xz(163, 62), xz(178, 80), p(x, "courtyard.n")));
        // south-west: drakehold -> desert -> dukedom, and the desert lava pool
        lit.add(road(x, 3, MAIN, p(x, "drakehold_square"), xz(-168, 42), xz(-168, 84), xz(-140, 110), p(x, "dukedom")));
        road(x, 3, LANE, xz(-140, 112), xz(-112, 140), xz(-100, 160));
        road(x, 3, LANE, xz(-40, 40), xz(-70, 80), xz(-100, 112), xz(-112, 138));
        // stronghold surroundings
        road(x, 3, LANE, p(x, "stronghold.north"), xz(40, 38), xz(46, 50), xz(48, 66), p(x, "blade_yard"));
        road(x, 3, LANE, p(x, "stronghold.north"), xz(-28, 40), p(x, "stronghold.tower0"));
        road(x, 3, LANE, p(x, "stronghold.north"), xz(28, 40), p(x, "stronghold.tower1"));
        road(x, 3, LANE, p(x, "stronghold.south"), xz(-28, 112), p(x, "stronghold.tower2"));
        road(x, 3, LANE, p(x, "stronghold.south"), xz(28, 112), p(x, "stronghold.tower3"));

        // ---- Drakehold streets ----
        road(x, 3, LANE, p(x, "drakehold_gate"), xz(-150, 22));
        road(x, 3, LANE, p(x, "drakehold_hall"), xz(-164, 9));
        road(x, 3, LANE, p(x, "drakehold_tavern"), xz(-182, 7), xz(-176, 14));
        road(x, 3, LANE, p(x, "drakehold_forge"), xz(-182, 29), xz(-176, 28));
        road(x, 3, LANE, p(x, "drakehold_bank"), xz(-150, 3), xz(-152, 12));
        road(x, 3, LANE, p(x, "drakehold_shop"), xz(-152, 29));
        for (int i = 0; i < 5; i++) road(x, 3, LANE, p(x, "drakehold_home" + i), xz(hx(x, "drakehold_home" + i), 44), xz(-168, 44));
        // ---- Carmine Spire streets ----
        road(x, 3, LANE, p(x, "carmine_gate"), xz(150, 22));
        road(x, 3, LANE, p(x, "carmine_palace"), xz(163, 9));
        road(x, 3, LANE, p(x, "carmine_tavern"), xz(178, 5), xz(176, 14));
        road(x, 3, LANE, p(x, "carmine_forge"), xz(178, 29), xz(176, 28));
        road(x, 3, LANE, p(x, "carmine_lamps"), xz(146, 3), xz(150, 12));
        road(x, 3, LANE, p(x, "carmine_phones"), xz(146, 29), xz(150, 28));
        road(x, 3, LANE, p(x, "carmine_bank"), xz(hx(x, "carmine_bank"), 38), xz(160, 36));
        road(x, 3, LANE, p(x, "carmine_shop"), xz(hx(x, "carmine_shop"), 38), xz(166, 36));
        road(x, 3, LANE, p(x, "carmine_home"), xz(hx(x, "carmine_home"), 40), xz(170, 40));
        road(x, 3, LANE, p(x, "carmine_home2"), xz(134, 38), xz(146, 36), xz(152, 34));
        road(x, 3, LANE, p(x, "carmine_home3"), xz(198, -9), xz(180, -9), xz(176, 6));
        road(x, 3, LANE, p(x, "carmine_home4"), xz(198, 40), xz(180, 40));
        road(x, 3, LANE, p(x, "carmine_home5"), xz(134, -14), xz(146, -6));
        road(x, 3, LANE, p(x, "carmine_spire142"), xz(146, -6), xz(150, 8));
        road(x, 3, LANE, p(x, "carmine_spire184"), xz(180, -6), xz(176, 8));

        // ---- lamps every ~18 blocks beside the long roads ----
        VoxelCanvas c = x.c;
        for (int[][] r : lit) {
            double acc = 9;
            for (int i = 0; i + 1 < r.length; i++) {
                double dx = r[i + 1][0] - r[i][0], dz = r[i + 1][1] - r[i][1];
                double len = Math.hypot(dx, dz);
                if (len < 1) continue;
                for (double t = 0; t < len; t += 1) {
                    acc += 1;
                    if (acc < 18) continue;
                    int px = (int) Math.round(r[i][0] + dx * t / len - dz / len * 3);
                    int pz = (int) Math.round(r[i][1] + dz * t / len + dx / len * 3);
                    if (x.paths.covers(px, pz) || x.res.covers(px, pz, 1)) continue;
                    int g = x.ground(px, pz);
                    if (!c.isAir(px, g + 1, pz) || x.h.wet(px, pz)) continue;
                    Builders.lampPost(c, px, g, pz, "soul", dz >= 0 ? "east" : "west");
                    acc = 0;
                }
            }
        }
    }

    private static int[] xz(int x, int z) { return new int[]{x, z}; }

    private static int hx(CCtx x, String stop) {
        int[] s = x.stops.get(stop);
        return s == null ? 0 : s[0];
    }

    private static int[] p(CCtx x, String stop) {
        int[] s = x.stops.get(stop);
        if (s == null) {
            String prev = x.layout.data().get("crimson.missing_stops");
            x.layout.data("crimson.missing_stops", prev == null ? stop : prev + "," + stop);
            return null;
        }
        return new int[]{s[0], s[2]};
    }

    private static int[][] road(CCtx x, int width, Palette surface, int[]... pts) {
        for (int[] q : pts) if (q == null) return new int[0][];
        Builders.path(x.c, pts, width, surface, STEP, x.paths);
        return pts;
    }
}
