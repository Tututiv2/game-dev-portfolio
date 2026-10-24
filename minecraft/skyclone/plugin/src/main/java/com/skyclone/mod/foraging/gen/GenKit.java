package com.skyclone.mod.foraging.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.BlockInfo;
import com.skyclone.gen.BlockState;
import com.skyclone.gen.Blocks;
import com.skyclone.gen.Builders;
import com.skyclone.gen.Fill;
import com.skyclone.gen.Hash;
import com.skyclone.gen.Palette;
import com.skyclone.gen.Reservations;
import com.skyclone.gen.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Small pure building helpers shared by the foraging islands (no Bukkit). Everything takes the
 * canvas and returns what a caller needs to anchor things on (ground y, a standing block...).
 */
public final class GenKit {

    private GenKit() {}

    /** Highest ground block at (x, z) (looks through plants, leaves and logs), or MIN_VALUE. */
    public static int ground(VoxelCanvas c, int x, int z) {
        return Builders.ground(c, x, z, 250, 0);
    }

    /** Ground under (x, z) searching down from {@code fromY}. */
    public static int groundBelow(VoxelCanvas c, int x, int z, int fromY) {
        return Builders.ground(c, x, z, fromY, 0);
    }

    /** True when the two cells above (x, y, z) are free (air or a non-colliding plant) — somewhere to stand. */
    public static boolean headroom(VoxelCanvas c, int x, int y, int z) {
        for (int a = 1; a <= 2; a++) {
            BlockInfo i = BlockInfo.of(c.get(x, y + a, z));
            if (!i.isAir() && i.top16 != 0) return false;
        }
        return true;
    }

    /** Clear plants in the two cells above a standing block. */
    public static void clearAbove(VoxelCanvas c, int x, int y, int z) {
        for (int a = 1; a <= 2; a++) {
            String b = c.get(x, y + a, z);
            if (!c.isAir(x, y + a, z) && BlockInfo.of(b).top16 == 0) c.set(x, y + a, z, "air");
        }
    }

    /**
     * A point {@code frac} of the way from island centre (ax, az) toward (bx, bz), measured against
     * the heightmap's real extent: walks outward until land ends, then backs off to frac of that.
     */
    public static int[] edgePoint(Terrain.Heightmap h, int ax, int az, int bx, int bz, double frac) {
        double dx = bx - ax, dz = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        dx /= len; dz /= len;
        int last = 0;
        for (int i = 0; i < 400; i++) {
            int x = (int) Math.round(ax + dx * i), z = (int) Math.round(az + dz * i);
            if (h.has(x, z)) last = i; else if (i > last + 6) break;
        }
        int d = (int) Math.round(last * frac);
        return new int[]{(int) Math.round(ax + dx * d), (int) Math.round(az + dz * d)};
    }

    /** A framed portal opening 3 wide x 4 high; {@code axis} 'x' = the opening spans x (walk through along z). */
    public static Box portal(VoxelCanvas c, Layout layout, String areaName, int cx, int y, int cz, char axis,
                             Fill frame, Fill floor, String lampStyle) {
        int ux = axis == 'x' ? 1 : 0, uz = axis == 'x' ? 0 : 1;   // along the opening
        int px = 1 - ux, pz = 1 - uz;                              // through it
        for (int u = -3; u <= 3; u++)
            for (int v = -2; v <= 2; v++) {
                int x = cx + ux * u + px * v, z = cz + uz * u + pz * v;
                c.set(x, y, z, floor.at(x, y, z));
                Builders.footing(c, x, y, z, floor, 14);
                for (int a = 1; a <= 6; a++) if (Builders.natural(c.get(x, y + a, z))) c.set(x, y + a, z, "air");
            }
        for (int a = 1; a <= 5; a++) {
            c.set(cx - ux * 2, y + a, cz - uz * 2, frame.at(cx - ux * 2, y + a, cz - uz * 2));
            c.set(cx + ux * 2, y + a, cz + uz * 2, frame.at(cx + ux * 2, y + a, cz + uz * 2));
        }
        for (int u = -2; u <= 2; u++) c.set(cx + ux * u, y + 6, cz + uz * u, frame.at(cx + ux * u, y + 6, cz + uz * u));
        for (int u = -1; u <= 1; u++) c.set(cx + ux * u, y + 5, cz + uz * u, frame.at(cx + ux * u, y + 5, cz + uz * u));
        String portalBlock = "nether_portal[axis=" + axis + "]";
        for (int u = -1; u <= 1; u++)
            for (int a = 1; a <= 4; a++) c.set(cx + ux * u, y + a, cz + uz * u, portalBlock);
        if (lampStyle != null) {
            Builders.lampPost(c, cx - ux * 3 + px * 2, y, cz - uz * 3 + pz * 2, lampStyle, "north");
            Builders.lampPost(c, cx + ux * 3 + px * 2, y, cz + uz * 3 + pz * 2, lampStyle, "north");
            Builders.lampPost(c, cx - ux * 3 - px * 2, y, cz - uz * 3 - pz * 2, lampStyle, "north");
            Builders.lampPost(c, cx + ux * 3 - px * 2, y, cz + uz * 3 - pz * 2, lampStyle, "north");
        }
        Box area = Box.of(cx - ux, y + 1, cz - uz, cx + ux, y + 4, cz + uz);
        layout.area(areaName, area);
        return Box.of(cx - 3, y - 14, cz - 3, cx + 3, y + 6, cz + 3);
    }

    /**
     * A mossy boulder at (x, z) you can climb: a 3x3 core two high with a crown block, and a
     * one-high step on one side. Returns the crown's standing block (x, y, z).
     */
    public static int[] boulder(VoxelCanvas c, int x, int z, Fill rock, String stepSide) {
        int g = ground(c, x, z);
        if (g == Integer.MIN_VALUE) return null;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                int gx = x + dx, gz = z + dz;
                int gg = ground(c, gx, gz);
                if (gg == Integer.MIN_VALUE) gg = g;
                for (int y = Math.min(gg, g) + 1; y <= g + 2; y++) c.set(gx, y, gz, rock.at(gx, y, gz));
            }
        c.set(x, g + 3, z, rock.at(x, g + 3, z));
        int sx = x + Blocks.dx(stepSide) * 2, sz = z + Blocks.dz(stepSide) * 2;
        int sg = ground(c, sx, sz);
        if (sg != Integer.MIN_VALUE) {
            for (int y = sg + 1; y <= g + 1; y++) c.set(sx, y, sz, rock.at(sx, y, sz));
            clearAbove(c, sx, g + 1, sz);
        }
        for (int a = 4; a <= 6; a++) c.set(x, g + a, z, "air");
        return new int[]{x, g + 3, z};
    }

    /**
     * Carve a walkable tunnel along (x, z) points whose FLOOR goes linearly from y0 to y1: air from
     * floor+1 to floor+{@code height} in a rounded section, the floor itself re-paved with {@code floor}.
     */
    public static void tunnel(VoxelCanvas c, Terrain.Heightmap h, int[][] pts, int y0, int y1, double radius, int height, Fill floor, Fill wall) {
        tunnel(c, h, pts, y0, y1, radius, height, floor, wall, List.of());
    }

    /** As above; no wall is ever added inside {@code protect} (chambers the tunnel opens into). */
    public static void tunnel(VoxelCanvas c, Terrain.Heightmap h, int[][] pts, int y0, int y1, double radius, int height, Fill floor, Fill wall,
                              List<Box> protect) {
        double total = 0;
        for (int i = 0; i + 1 < pts.length; i++) total += Math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]);
        // every cell takes the floor height of the NEAREST point on the centre line, so overlapping
        // samples can never stamp a shallower floor across a deeper stretch of the same tunnel
        java.util.Map<Long, double[]> cells = new java.util.HashMap<>();   // key -> {dist, floorY}
        double walked = 0;
        int r = (int) Math.ceil(radius) + 2;
        for (int i = 0; i + 1 < pts.length; i++) {
            double ax = pts[i][0], az = pts[i][1], bx = pts[i + 1][0], bz = pts[i + 1][1];
            double len = Math.hypot(bx - ax, bz - az);
            int n = Math.max(1, (int) Math.ceil(len * 3));
            for (int k = 0; k <= n; k++) {
                double t = (double) k / n;
                double sx = ax + (bx - ax) * t, sz = az + (bz - az) * t;
                double fy = y0 + (y1 - y0) * ((walked + len * t) / Math.max(1, total));
                for (int dz = -r; dz <= r; dz++)
                    for (int dx = -r; dx <= r; dx++) {
                        int x = (int) Math.round(sx) + dx, z = (int) Math.round(sz) + dz;
                        double d = Math.hypot(x - sx, z - sz);
                        if (d > radius + 1.3) continue;
                        long key = ((long) x << 32) ^ (z & 0xffffffffL);
                        double[] cur = cells.get(key);
                        if (cur == null || d < cur[0] - 1e-9) cells.put(key, new double[]{d, Math.round(fy), x, z});
                    }
            }
            walked += len;
        }
        // walls (only below the ground surface, so nothing pokes out of the hillside)
        for (double[] v : cells.values()) {
            int x = (int) v[2], z = (int) v[3], fy = (int) v[1];
            int lim = h == null || !h.has(x, z) ? fy + height + 1 : Math.min(fy + height + 1, h.top(x, z) - 1);
            for (int y = fy - 1; y <= lim; y++) {
                if (!c.isAir(x, y, z)) continue;
                boolean inside = false;
                for (Box b : protect) if (b.contains(x, y, z)) { inside = true; break; }
                if (!inside) c.set(x, y, z, wall.at(x, y, z));
            }
        }
        // air, then the floor
        for (double[] v : cells.values()) {
            if (v[0] > radius + 0.3) continue;
            int x = (int) v[2], z = (int) v[3], fy = (int) v[1];
            int top = v[0] > radius - 0.8 ? height - 1 : height;
            for (int y = fy + 1; y <= fy + top; y++) c.set(x, y, z, "air");
            if (h != null && h.has(x, z) && fy > h.top(x, z)) continue;   // never pave above the ground
            boolean inside = false;
            for (Box bx : protect) if (bx.contains(x, fy, z)) { inside = true; break; }
            c.set(x, fy, z, floor.at(x, fy, z));
            if (inside)   // a rubble ramp down into the chamber, never a floating lip
                for (int y = fy - 1; y > fy - 10 && c.isAir(x, y, z); y--) c.set(x, y, z, wall.at(x, y, z));
        }
    }

    /**
     * Carve a cave chamber: an elliptical footprint with an uneven floor near {@code floorY} and a
     * domed ceiling up to {@code height} above it. Floor and a shell of {@code wall} are guaranteed.
     */
    public static Box chamber(VoxelCanvas c, int cx, int floorY, int cz, double rx, double rz, int height, Fill floor, Fill wall, long seed) {
        int x0 = (int) Math.floor(cx - rx - 2), x1 = (int) Math.ceil(cx + rx + 2);
        int z0 = (int) Math.floor(cz - rz - 2), z1 = (int) Math.ceil(cz + rz + 2);
        // shell
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                double nx = (x - cx) / (rx + 1.5), nz = (z - cz) / (rz + 1.5);
                double d = nx * nx + nz * nz;
                if (d > 1) continue;
                double t = Math.sqrt(Math.max(0, 1 - d));
                int top = floorY + (int) Math.round(height * t) + 2;
                for (int y = floorY - 2; y <= top; y++) if (c.isAir(x, y, z)) c.set(x, y, z, wall.at(x, y, z));
            }
        // air + floor
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                double nx = (x - cx) / rx, nz = (z - cz) / rz;
                double d = nx * nx + nz * nz;
                if (d > 1) continue;
                double t = Math.sqrt(Math.max(0, 1 - d));
                int fy = floorY + (Hash.unit(seed, x, z) < 0.12 && d > 0.25 ? 1 : 0);
                int top = floorY + Math.max(3, (int) Math.round(height * t + (Hash.unit(seed, x, 7, z) - 0.5) * 2));
                c.set(x, fy, z, floor.at(x, fy, z));
                for (int y = fy + 1; y <= top; y++) c.set(x, y, z, "air");
            }
        return Box.of(x0, floorY, z0, x1, floorY + height, z1);
    }

    /** Lamp posts every {@code spacing} blocks beside a polyline, offset {@code side} blocks, on natural ground. */
    public static int lampsAlong(VoxelCanvas c, int[][] pts, int spacing, int side, String style, BiPredicate<Integer, Integer> keepOut) {
        int n = 0;
        double carry = spacing / 2.0;
        int flip = 1;
        for (int i = 0; i + 1 < pts.length; i++) {
            double ax = pts[i][0], az = pts[i][1], bx = pts[i + 1][0], bz = pts[i + 1][1];
            double len = Math.hypot(bx - ax, bz - az);
            if (len < 1) continue;
            double ux = (bx - ax) / len, uz = (bz - az) / len;
            for (double d = carry; d < len; d += spacing) {
                int x = (int) Math.round(ax + ux * d - uz * side * flip), z = (int) Math.round(az + uz * d + ux * side * flip);
                flip = -flip;
                if (keepOut != null && keepOut.test(x, z)) continue;
                int g = ground(c, x, z);
                if (g == Integer.MIN_VALUE || !Builders.natural(c.get(x, g, z)) || !headroom(c, x, g, z)) continue;
                if (BlockState.name(c.get(x, g, z)).equals("dirt_path")) continue;
                clearAbove(c, x, g, z);
                Builders.lampPost(c, x, g, z, style, Blocks.dirOf((int) Math.round(-uz * 10), (int) Math.round(ux * 10)));
                n++;
            }
            carry = (carry - len) % spacing;
            if (carry < 0) carry += spacing;
        }
        return n;
    }

    /** A canvas A-frame tent opening toward {@code facing}; (x, y, z) = ground block at the door. Returns its box. */
    public static Box tent(VoxelCanvas c, int x, int y, int z, String facing, String woolA, String woolB, int depth) {
        Builders.Frame f = new Builders.Frame(x, y, z, facing);
        for (int v = -depth; v <= 0; v++)
            for (int u = -3; u <= 3; u++) {
                f.set(c, u, 0, v, (u + v) % 2 == 0 ? "spruce_planks" : "stripped_spruce_wood[axis=y]");
                Builders.footing(c, f.x(u, v), y, f.z(u, v), Fill.of("dirt"), 10);
                for (int h = 1; h <= 5; h++) f.set(c, u, h, v, "air");
            }
        for (int v = -depth; v <= 0; v++) {
            String wool = (v & 1) == 0 ? woolA : woolB;
            for (int h = 1; h <= 4; h++) {
                int u = 4 - h;
                f.set(c, -u, h, v, wool + "_wool");
                f.set(c, u, h, v, wool + "_wool");
            }
        }
        for (int u = -2; u <= 2; u++)
            for (int h = 1; h <= 3 - Math.abs(u); h++) f.set(c, u, h, -depth, woolB + "_wool");
        f.set(c, 0, 4, 0, "spruce_fence");
        f.set(c, 0, 5, 0, Blocks.lantern(false));
        int x0 = Math.min(f.x(-4, -depth), f.x(4, 1)), x1 = Math.max(f.x(-4, -depth), f.x(4, 1));
        int z0 = Math.min(f.z(-4, -depth), f.z(4, 1)), z1 = Math.max(f.z(-4, -depth), f.z(4, 1));
        return Box.of(x0, y - 10, z0, x1, y + 5, z1);
    }

    /** Scatter one block type on top of the given grounds in an area (flowers, ferns, mushrooms). */
    public static int sprinkle(VoxelCanvas c, Terrain.Heightmap h, Box area, long seed, double chance, Palette what,
                               String grounds, BiPredicate<Integer, Integer> keepOut) {
        int n = 0;
        java.util.Set<String> ok = java.util.Set.of(grounds.split(","));
        for (int z = area.minZ(); z <= area.maxZ(); z++)
            for (int x = area.minX(); x <= area.maxX(); x++) {
                if (!Hash.chance(seed, x, z, chance)) continue;
                if (keepOut != null && keepOut.test(x, z)) continue;
                int t = h.top(x, z);
                if (t == Terrain.Heightmap.NONE) continue;
                int g = groundBelow(c, x, z, t + 2);
                if (g == Integer.MIN_VALUE || !ok.contains(BlockState.name(c.get(x, g, z)))) continue;
                if (!c.isAir(x, g + 1, z)) continue;
                String b = what.at(x, g + 1, z);
                c.set(x, g + 1, z, b);
                if (b.contains("_tall") || b.startsWith("tall_grass") || b.startsWith("large_fern") || b.startsWith("lilac")
                        || b.startsWith("rose_bush") || b.startsWith("peony") || b.startsWith("sunflower")) {
                    if (c.isAir(x, g + 2, z)) {
                        String base = BlockState.name(b);
                        c.set(x, g + 1, z, base + "[half=lower]");
                        c.set(x, g + 2, z, base + "[half=upper]");
                    } else c.set(x, g + 1, z, "air");
                }
                n++;
            }
        return n;
    }

    /** A stand-on block for an NPC: makes (x, y, z) a full block and clears the two above it. */
    public static void npcSpot(VoxelCanvas c, int x, int y, int z, String block) {
        String cur = c.get(x, y, z);
        if (c.isAir(x, y, z) || BlockInfo.of(cur).top16 < 16) c.set(x, y, z, block);
        for (int a = 1; a <= 2; a++) c.set(x, y + a, z, "air");
    }

    /** Find a standable spot at or near (x, z) (spiralling out up to r), returning {x, groundY, z} or null. */
    public static int[] standNear(VoxelCanvas c, int x, int z, int r, Reservations res) {
        return standNear(c, x, z, r, res, 250);
    }

    /** Like {@link #standNear(VoxelCanvas, int, int, int, Reservations)} but searching down from {@code fromY} (caves). */
    public static int[] standNear(VoxelCanvas c, int x, int z, int r, Reservations res, int fromY) {
        for (int d = 0; d <= r; d++)
            for (int dz = -d; dz <= d; dz++)
                for (int dx = -d; dx <= d; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    int gx = x + dx, gz = z + dz;
                    if (res != null && res.covers(gx, gz, 0)) continue;
                    int g = groundBelow(c, gx, gz, fromY);
                    if (g == Integer.MIN_VALUE) continue;
                    String n = BlockState.name(c.get(gx, g, gz));
                    if (n.equals("water") || BlockInfo.of(c.get(gx, g, gz)).top16 < 15) continue;
                    if (!headroom(c, gx, g, gz)) continue;
                    return new int[]{gx, g, gz};
                }
        return null;
    }
}
