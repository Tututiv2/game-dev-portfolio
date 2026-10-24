package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;

/**
 * The Belly of the Beast: a dead leviathan lying half-sunk in the Mystic Marsh. Its open jaw faces south
 * onto the marsh lane; inside, a stomach shaft 27 wide drops 12 below the marsh and rises 12 above it, with a
 * fleshy ledge spiralling round the wall from the floor to a landing under the roof. The Matriarch, a huge
 * pearl-making sac, hangs from the roof on a stalk. Heavy Pearls float on {@code crimson.pearl.<n>} spots
 * spread over the floor, the spiral and the top landing (the crimson module shows each player their own).
 *
 * <p>Anchors: {@code crimson.matriarch} (her underside), {@code crimson.belly} (the jaw), {@code crimson.pearl.1..9}.
 * Zone {@code crimson_belly} "Belly of the Beast". Pure Java.
 */
final class CBelly {

    private CBelly() {}

    static final int PEARL_SPOTS = 9;
    /** Inner radius of the shaft (air inside), the wall is 2 thick outside it. */
    private static final double R_IN = 13.5;
    private static final double R_OUT = 15.6;
    private static final int PITCH = 12;

    private static final Palette FLESH = Palette.of("nether_wart_block:6,crimson_hyphae[axis=y]:2,red_terracotta:1,red_nether_bricks:1");
    private static final Palette LINING = Palette.of("nether_wart_block:5,crimson_hyphae[axis=y]:2,stripped_crimson_hyphae[axis=y]:1");
    private static final Palette LEDGE = Palette.of("crimson_nylium:3,nether_wart_block:3,stripped_crimson_hyphae[axis=y]:1");
    private static final Palette FLOOR = Palette.of("crimson_nylium:4,nether_wart_block:3,red_terracotta:1");
    private static final Palette BONE = Palette.of("bone_block[axis=y]:8,calcite:1");

    static void build(CCtx x) {
        VoxelCanvas c = x.c;
        int g = x.y(CPlan.BELLY);                 // marsh level at the jaw
        int cx = CPlan.BELLY_X, cz = CPlan.BELLY_Z;
        int floor = g - PITCH;                    // stomach floor
        int top = g + PITCH;                      // the top landing
        int wallTop = top + 5;                    // where the dome starts
        int domeH = 7;
        x.res.reserve("belly", Box.of(cx - 24, floor - 3, cz - 24, cx + 24, wallTop + domeH + 6, cz + 28));

        // ---- the shaft: wall, floor bowl, dome ----
        int ro = (int) Math.ceil(R_OUT) + 1;
        for (int dz = -ro; dz <= ro; dz++)
            for (int dx = -ro; dx <= ro; dx++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > R_OUT + 0.4) continue;
                int bx = cx + dx, bz = cz + dz;
                // floor and the rock under it
                for (int y = floor - 3; y <= floor; y++) c.set(bx, y, bz, y == floor ? FLOOR.at(bx, y, bz) : FLESH.at(bx, y, bz));
                // wall + the hollow
                for (int y = floor + 1; y <= wallTop; y++) {
                    if (r > R_IN) c.set(bx, y, bz, r > R_IN + 0.9 ? FLESH.at(bx, y, bz) : LINING.at(bx, y, bz));
                    else c.set(bx, y, bz, "air");
                }
                // dome: an oblate cap from wallTop up
                double rr = r / (R_OUT + 0.4);
                int capOuter = wallTop + (int) Math.round(domeH * Math.sqrt(Math.max(0, 1 - rr * rr)));
                double ri = r / R_IN;
                int capInner = r <= R_IN ? wallTop + (int) Math.round((domeH - 2) * Math.sqrt(Math.max(0, 1 - ri * ri))) : wallTop - 1;
                for (int y = wallTop + 1; y <= capOuter; y++) c.set(bx, y, bz, y > capInner ? FLESH.at(bx, y, bz) : "air");
                Builders.footing(c, bx, floor - 3, bz, Fill.of("blackstone"), 12);
            }
        // glands: shroomlights in the lining, so the shaft is lit all the way up
        for (int i = 0; i < 64; i++) {
            double a = Hash.unit(x.seed, 801, i, 3) * Math.PI * 2;
            int y = floor + 2 + (int) (Hash.unit(x.seed, 802, i, 5) * (wallTop - floor - 2));
            int bx = cx + (int) Math.round(Math.cos(a) * (R_IN + 0.6)), bz = cz + (int) Math.round(Math.sin(a) * (R_IN + 0.6));
            if (!c.isAir(bx, y, bz)) c.set(bx, y, bz, "shroomlight");
        }

        // ---- the spiral ledge: floor (south) -> marsh level (south, the jaw) -> top landing (south) ----
        double a0 = Math.PI / 2;                  // +z = south
        double turns = 2.0;
        for (double a = a0; a <= a0 + turns * 2 * Math.PI + 0.001; a += 0.003) {
            int y = floor + (int) Math.floor((a - a0) / (2 * Math.PI) * PITCH + 1e-9);
            for (double rr = R_IN - 3.9; rr <= R_IN + 0.3; rr += 0.45) {
                int bx = cx + (int) Math.round(Math.cos(a) * rr), bz = cz + (int) Math.round(Math.sin(a) * rr);
                if (Math.sqrt((bx - cx) * (bx - cx) + (bz - cz) * (bz - cz)) > R_IN + 0.5) continue;
                c.set(bx, y, bz, rr > R_IN - 0.9 ? LINING.at(bx, y, bz) : LEDGE.at(bx, y, bz));
                for (int h = 1; h <= 4; h++) if (y + h <= wallTop) c.set(bx, y + h, bz, "air");
            }
        }
        // the top landing: a shelf from the spiral's end (south) round to the north-west, under the dome. It
        // must not cover the spiral's last climb, which comes in from the EAST side of south.
        for (int dz = -(int) R_IN; dz <= (int) R_IN; dz++)
            for (int dx = -(int) R_IN; dx <= 0; dx++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r < 6.5 || r > R_IN + 0.4 || !onLanding(dx, dz)) continue;
                c.set(cx + dx, top, cz + dz, LEDGE.at(cx + dx, top, cz + dz));
                for (int h = 1; h <= 4; h++) c.set(cx + dx, top + h, cz + dz, "air");
            }
        // a fringe of bone along the landing's inner lip
        for (int i = 0; i <= 8; i++) {
            double a = Math.PI / 2 + 0.2 + i * 0.1;
            int dx = (int) Math.round(Math.cos(a) * 7.2), dz = (int) Math.round(Math.sin(a) * 7.2);
            if (i % 2 == 0 && !c.isAir(cx + dx, top, cz + dz)) c.set(cx + dx, top + 1, cz + dz, "bone_block[axis=y]");
        }

        // ---- the jaw: a throat through the south wall at marsh level, teeth at the lip ----
        int jawZ0 = cz + 14, jawZ1 = cz + (int) R_OUT + 8;
        for (int z = jawZ0; z <= jawZ1; z++)
            for (int dx = -4; dx <= 4; dx++) {
                int bx = cx + dx;
                boolean side = Math.abs(dx) == 4;
                for (int y = g - 2; y <= g + 7; y++) {
                    if (y < g) { c.set(bx, y, z, FLESH.at(bx, y, z)); continue; }
                    if (y == g) { c.set(bx, y, z, side ? FLESH.at(bx, y, z) : (Math.abs(dx) <= 1 ? "crimson_nylium" : FLOOR.at(bx, y, z))); Builders.footing(c, bx, g - 2, z, Fill.of("blackstone"), 10); continue; }
                    if (side || y >= g + 6) c.set(bx, y, z, z >= jawZ1 - 1 && y >= g + 6 ? BONE.at(bx, y, z) : FLESH.at(bx, y, z));
                    else c.set(bx, y, z, "air");
                }
            }
        // teeth hanging from the upper jaw and rising from the lower one
        for (int dx = -3; dx <= 3; dx += 2) {
            c.set(cx + dx, g + 5, jawZ1, "bone_block[axis=y]");
            c.set(cx + dx, g + 4, jawZ1, "pointed_dripstone[thickness=tip,vertical_direction=down,waterlogged=false]");
        }
        for (int dx : new int[]{-3, 3}) {
            c.set(cx + dx, g + 1, jawZ1, "bone_block[axis=y]");
            c.set(cx + dx, g + 2, jawZ1, "pointed_dripstone[thickness=tip,vertical_direction=up,waterlogged=false]");
        }
        // the flanks bulge like a gorged belly between the marsh and the roof
        for (int y = g + 1; y < wallTop; y++) {
            double bulge = bulge(y, g, wallTop);
            int reach = (int) Math.ceil(R_OUT + bulge) + 1;
            for (int dz = -reach; dz <= reach; dz++)
                for (int dx = -reach; dx <= reach; dx++) {
                    double r = Math.sqrt(dx * dx + dz * dz);
                    if (r <= R_OUT + 0.4 || r > R_OUT + 0.4 + bulge) continue;
                    if (Math.abs(dx) <= 4 && dz >= 12 && y <= g + 7) continue;     // the jaw keeps its own walls
                    c.set(cx + dx, y, cz + dz, FLESH.at(cx + dx, y, cz + dz));
                }
        }
        // ribs: bone hoops round the belly, running east-west - up each flank (following the bulge) and
        // one block over the dome
        for (int dz = -10; dz <= 10; dz += 5) {
            for (int y = g - 1; y <= wallTop + 1; y++) {
                double rr = R_OUT + 1.2 + (y > g && y < wallTop ? bulge(y, g, wallTop) : 0);
                double half = Math.sqrt(Math.max(0, rr * rr - dz * dz));
                for (int side : new int[]{-1, 1})
                    for (int k = 0; k <= 1; k++) {
                        int bx = cx + side * ((int) Math.round(half) - k), bz = cz + dz;
                        c.set(bx, y, bz, BONE.at(bx, y, bz));
                        if (y == g - 1) Builders.footing(c, bx, y, bz, Fill.of("blackstone"), 8);
                    }
            }
            double half = Math.sqrt(Math.max(0, (R_OUT + 0.4) * (R_OUT + 0.4) - dz * dz));
            for (int dx = -(int) half; dx <= half; dx++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > R_OUT + 0.4) continue;
                double q = r / (R_OUT + 0.4);
                int y = wallTop + (int) Math.round(domeH * Math.sqrt(Math.max(0, 1 - q * q)));
                c.set(cx + dx, y + 1, cz + dz, "bone_block[axis=x]");
            }
        }

        // ---- the Matriarch: a pearl sac hung from the roof on a stalk ----
        int my = top + 4;                         // her underside (clear of the landing's headroom)
        int bodyH = 5;
        double brx = 4.6;
        for (int dy = 0; dy <= bodyH; dy++)
            for (int dz = -5; dz <= 5; dz++)
                for (int dx = -5; dx <= 5; dx++) {
                    double ny = (dy - bodyH / 2.0) / (bodyH / 2.0 + 0.4);
                    double d = (dx * dx + dz * dz) / (brx * brx) + ny * ny;
                    if (d > 1) continue;
                    int bx = cx + dx, by = my + dy, bz = cz + dz;
                    String b = "red_mushroom_block";
                    if (dy == 2 && d > 0.6 && ((dx + dz) & 1) == 0) b = "crying_obsidian";         // a ring of eyes
                    else if (d < 0.5) b = "shroomlight";
                    else if (Hash.unit(x.seed, bx, by, bz) < 0.12) b = "shroomlight";
                    c.set(bx, by, bz, b);
                }
        for (int y = my + bodyH; y <= wallTop + domeH; y++) {
            c.set(cx, y, cz, "crimson_stem[axis=y]");
            if (y > my + bodyH + 1) { c.set(cx + 1, y, cz, "crimson_hyphae[axis=y]"); c.set(cx, y, cz + 1, "crimson_hyphae[axis=y]"); }
        }
        // tendrils
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI * 2 / 10 + 0.3;
            int bx = cx + (int) Math.round(Math.cos(a) * 3), bz = cz + (int) Math.round(Math.sin(a) * 3);
            int len = 3 + (int) (Hash.unit(x.seed, 803, i, 1) * 6);
            int start = my - 1;
            while (start < my + bodyH && c.isAir(bx, start + 1, bz)) start++;
            for (int k = 0; k < len; k++) {
                int y = start - k;
                if (!c.isAir(bx, y, bz)) break;
                c.set(bx, y, bz, k == len - 1 ? "weeping_vines[age=25]" : "weeping_vines_plant");
            }
        }
        // the floor under her: a nest of bone around a glowing pool of pearl-milk
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > 3.3) continue;
                c.set(cx + dx, floor, cz + dz, r < 1.6 ? "sea_lantern" : (r < 2.6 ? "white_concrete_powder" : "bone_block[axis=y]"));
                if (r >= 2.6 && ((dx + dz) & 1) == 0) c.set(cx + dx, floor + 1, cz + dz, "bone_block[axis=y]");
            }

        // ---- anchors ----
        x.layout.standAt("crimson.belly", cx, g, jawZ1 - 2, 0f);
        x.layout.anchor("crimson.matriarch", cx + 0.5, my - 2.5, cz + 0.5, 0f);
        int n = 0;
        // floor (3)
        for (int[] o : new int[][]{{-8, 0}, {8, 2}, {1, -8}}) x.layout.standAt("crimson.pearl." + (++n), cx + o[0], floor, cz + o[1], 0f);
        // the spiral (4): west, north, east of the first turn, north of the second
        for (double k : new double[]{0.5 * Math.PI, Math.PI, 1.5 * Math.PI, 3 * Math.PI}) {   // distance along the spiral
            double aa = a0 + k;
            int y = floor + (int) Math.floor(k / (2 * Math.PI) * PITCH + 1e-9);
            double rr = R_IN - 2.3;
            int bx = cx + (int) Math.round(Math.cos(aa) * rr), bz = cz + (int) Math.round(Math.sin(aa) * rr);
            x.layout.standAt("crimson.pearl." + (++n), bx, ground(c, bx, bz, y + 1, y - 1, y), bz, 0f);
        }
        // the top landing (2)
        x.layout.standAt("crimson.pearl." + (++n), cx - 8, top, cz + 7, 0f);
        x.layout.standAt("crimson.pearl." + (++n), cx - 11, top, cz - 3, 0f);
        x.layout.data("crimson.pearl.count", String.valueOf(n));

        // NO_MOBS: the lair sits inside the marsh bulls' spawn box, and its spiral ledges are valid spawn spots -
        // a Lv80 bull on a 3-wide ledge over the stomach shaft would knock pearl hunters off it.
        x.layout.zone("crimson_belly", "Belly of the Beast", 'd', Box.of(cx - 17, floor - 4, cz - 17, cx + 17, wallTop + domeH + 3, jawZ1 + 1), null,
                Layout.Zone.NO_MOBS);
        x.layout.area("crimson.belly_inside", Box.of(cx - 14, floor, cz - 14, cx + 14, wallTop + domeH, cz + 14));
        x.stops.put("belly", new int[]{cx, g, jawZ1 + 2});
        x.soul(cx - 10, top, cz + 4);
    }

    /** How far the flank swells past the wall at height y (0 at the marsh and at the roof). */
    private static double bulge(int y, int g, int wallTop) {
        return 2.8 * Math.sin(Math.PI * (y - g) / (double) (wallTop - g));
    }

    /** The landing's sector: from due south (where the spiral ends) clockwise round to the north-west. */
    private static boolean onLanding(int dx, int dz) {
        double a = Math.atan2(dz, dx);
        if (a < 0) a += 2 * Math.PI;
        return a >= Math.PI / 2 - 0.02 && a <= 1.3 * Math.PI;
    }

    /** The highest solid block in [lo, hi] at (x, z), or {@code fallback}. */
    private static int ground(VoxelCanvas c, int x, int z, int hi, int lo, int fallback) {
        for (int y = hi; y >= lo; y--) if (!c.isAir(x, y, z) && c.isAir(x, y + 1, z)) return y;
        return fallback;
    }
}
