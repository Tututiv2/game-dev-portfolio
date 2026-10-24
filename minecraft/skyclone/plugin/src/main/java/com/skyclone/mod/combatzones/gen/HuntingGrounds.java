package com.skyclone.mod.combatzones.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.api.world.gen.VoxelCanvas;
import com.skyclone.gen.Builders;
import com.skyclone.gen.MemoryCanvas;
import com.skyclone.gen.Terrain;
import com.skyclone.gen.Walkability;
import com.skyclone.gen.util.LongSet;
import com.skyclone.gen.util.Pack;

import java.util.ArrayList;
import java.util.List;

/**
 * Hunting grounds: compact, open, gentle patches of a mountain band where one pack of mobs lives.
 *
 * <p>Why this exists (playtest-loops 2026-10-03): the Spider's Den used to scatter each band's anchors
 * over the WHOLE mountain (46 low anchors on a ring ~560 blocks round) and core's spawner cycles a
 * rule's anchors round-robin. Kill nine spiders in front of you and their respawns land anywhere on
 * that ring, so nothing came back within 40 blocks for four minutes, and the spiders that did exist
 * stood on cliffs, spires and in trees. A ground is one rule-set's whole anchor list, inside
 * {@link #RADIUS} blocks, so a respawn lands where the kill happened and the leash (24 blocks from the
 * nearest anchor) keeps the pack there. Grounds are scored on the built canvas: columns a player can
 * walk on (one-block slope, head room, no road/structure) count for, cliff columns count against.
 * Pure: no Bukkit.
 */
final class HuntingGrounds {

    /** Anchor radius of one ground. Trees, boulders, webs and spires are kept out of {@link #CLEAR}. */
    static final int RADIUS = 13;
    static final int CLEAR = RADIUS + 6;

    record Ground(String id, int x, int z, int score, int cliffs) {
        boolean covers(int px, int pz, int margin) { return Math.hypot(px - x, pz - z) <= RADIUS + margin; }
    }

    private HuntingGrounds() {}

    /** Column surface grid over a box, computed once and shared by every band. */
    static final class Surface {
        final int minX, minZ, w, d;
        final int[] g;          // ground y or MIN
        final byte[] slope;     // max |dy| to the 4 neighbours (capped 99)

        Surface(VoxelCanvas c, Terrain.Heightmap h, Box b) {
            minX = b.minX(); minZ = b.minZ(); w = b.maxX() - b.minX() + 1; d = b.maxZ() - b.minZ() + 1;
            g = new int[w * d];
            slope = new byte[w * d];
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) {
                    int wx = minX + x, wz = minZ + z;
                    int t = h.has(wx, wz) ? h.top(wx, wz) : Integer.MIN_VALUE;
                    g[z * w + x] = t == Integer.MIN_VALUE ? Integer.MIN_VALUE : Builders.ground(c, wx, wz, t + 30, t - 16);
                }
            for (int z = 0; z < d; z++)
                for (int x = 0; x < w; x++) {
                    int y = g[z * w + x];
                    if (y == Integer.MIN_VALUE) { slope[z * w + x] = 99; continue; }
                    int s = 0;
                    for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + o[0], nz = z + o[1];
                        int ny = nx < 0 || nz < 0 || nx >= w || nz >= d ? Integer.MIN_VALUE : g[nz * w + nx];
                        s = Math.max(s, ny == Integer.MIN_VALUE ? 99 : Math.min(99, Math.abs(ny - y)));
                    }
                    slope[z * w + x] = (byte) s;
                }
        }

        int ground(int x, int z) {
            int i = idx(x, z);
            return i < 0 ? Integer.MIN_VALUE : g[i];
        }

        int slope(int x, int z) {
            int i = idx(x, z);
            return i < 0 ? 99 : slope[i];
        }

        private int idx(int x, int z) {
            int ix = x - minX, iz = z - minZ;
            return ix < 0 || iz < 0 || ix >= w || iz >= d ? -1 : iz * w + ix;
        }
    }

    /**
     * Choose {@code count} grounds in the band {@code minY..maxY}. The first lies within
     * {@code firstRange} of {@code (nearX, nearZ)} (the way in), the rest at least {@code separation}
     * apart. Each centre is within {@code roadRange} of a road so a player finds it by walking.
     * Never returns fewer than {@code count}: the thresholds relax until the band yields them
     * (the spawn rules name grounds a, b, c... and must always find them).
     */
    static List<Ground> choose(VoxelCanvas c, Surface s, Box area, int minY, int maxY, int count, int separation,
                               int nearX, int nearZ, int firstRange, int roadRange, Kit.Keepout keep,
                               Builders.PathResult paths, String idPrefix) {
        // candidate centres on a 3-block lattice, scored once
        List<int[]> cand = new ArrayList<>();          // x, z, score, cliffs
        for (int z = area.minZ(); z <= area.maxZ(); z += 3)
            for (int x = area.minX(); x <= area.maxX(); x += 3) {
                if (!good(c, s, x, z, minY, maxY, keep)) continue;
                if (!paths.near(x, z, roadRange)) continue;
                int score = 0, cliffs = 0;
                for (int dz = -RADIUS; dz <= RADIUS; dz++)
                    for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                        if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                        int px = x + dx, pz = z + dz;
                        if (good(c, s, px, pz, minY, maxY, keep)) score++;
                        else if (s.slope(px, pz) >= 3 && s.slope(px, pz) < 99) cliffs++;
                    }
                cand.add(new int[]{x, z, score - 3 * cliffs, cliffs});
            }
        cand.sort((a, b) -> b[2] != a[2] ? Integer.compare(b[2], a[2]) : a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        int area0 = (int) Math.round(Math.PI * RADIUS * RADIUS);
        List<Ground> out = new ArrayList<>();
        // relax: demand a mostly-open patch first, accept rougher ones only if the band has nothing better
        for (double need : new double[]{0.55, 0.40, 0.25, 0.0}) {
            for (int sep = separation; sep >= RADIUS * 2 && out.size() < count; sep -= 8) {
                for (int[] k : cand) {
                    if (out.size() >= count) break;
                    if (k[2] < need * area0) break;   // sorted: nothing further qualifies
                    if (out.isEmpty() && Math.hypot(k[0] - nearX, k[1] - nearZ) > firstRange) continue;
                    boolean far = true;
                    for (Ground o : out) if (Math.hypot(o.x() - k[0], o.z() - k[1]) < sep) { far = false; break; }
                    if (!far) continue;
                    out.add(new Ground(idPrefix + (char) ('a' + out.size()), k[0], k[1], k[2], k[3]));
                }
                if (out.isEmpty()) break;   // no first ground near the way in at this threshold: relax it
            }
            if (out.size() >= count) break;
        }
        if (out.size() < count) {       // last resort (never hit on the shipping seed): drop the near-entrance demand
            for (int[] k : cand) {
                if (out.size() >= count) break;
                boolean far = true;
                for (Ground o : out) if (Math.hypot(o.x() - k[0], o.z() - k[1]) < RADIUS * 2) { far = false; break; }
                if (far) out.add(new Ground(idPrefix + (char) ('a' + out.size()), k[0], k[1], k[2], k[3]));
            }
        }
        return out;
    }

    /** A column a mob can stand on and a player can walk across. */
    static boolean good(VoxelCanvas c, Surface s, int x, int z, int minY, int maxY, Kit.Keepout keep) {
        int y = s.ground(x, z);
        if (y == Integer.MIN_VALUE || y < minY || y > maxY || s.slope(x, z) > 1) return false;
        if (keep != null && keep.blocked(x, z)) return false;
        return c.isAir(x, y + 1, z) && c.isAir(x, y + 2, z);
    }

    /**
     * Up to {@code count} anchors inside a ground, at least {@code spacing} apart, every one on a
     * cell the player can reach from spawn ({@code reach} null = no filter).
     */
    static List<int[]> spots(VoxelCanvas c, Surface s, Ground gr, int minY, int maxY, int count, int spacing,
                             Kit.Keepout keep, LongSet reach) {
        List<int[]> cells = new ArrayList<>();
        for (int dz = -RADIUS; dz <= RADIUS; dz++)
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                int x = gr.x() + dx, z = gr.z() + dz;
                if (!good(c, s, x, z, minY - 2, maxY + 2, keep)) continue;
                int y = s.ground(x, z);
                if (reach != null && !reach.contains(Pack.key(x, y + 1, z))) continue;
                cells.add(new int[]{x, y, z, dx * dx + dz * dz});
            }
        // spread from the centre outwards so a short list still covers the middle of the patch
        cells.sort((a, b) -> a[3] != b[3] ? Integer.compare(a[3], b[3]) : a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[2], b[2]));
        List<int[]> out = new ArrayList<>();
        for (int sp = spacing; sp >= 2 && out.size() < count; sp--)
            for (int[] k : cells) {
                if (out.size() >= count) break;
                boolean close = false;
                for (int[] o : out) if (Math.abs(o[0] - k[0]) < sp && Math.abs(o[2] - k[2]) < sp) { close = true; break; }
                if (!close) out.add(new int[]{k[0], k[1], k[2]});
            }
        return out;
    }

    /**
     * Cells reachable on foot from the layout's {@code spawn}, or null when the canvas is not a
     * {@link MemoryCanvas} (then nothing is filtered; preview and the game both use one).
     */
    static LongSet reachable(VoxelCanvas c, Layout layout) {
        if (!(c instanceof MemoryCanvas mc)) return null;
        Walkability.Options o = new Walkability.Options();
        o.skipFloating = true;
        Walkability.Report r = Walkability.analyse(mc, layout, o);
        return r.spawnProblem != null ? null : r.reachable;
    }

    /** Keep only points whose feet cell is reachable ({@code reach} null = keep all). */
    static List<int[]> reachableOnly(List<int[]> pts, LongSet reach) {
        if (reach == null) return pts;
        List<int[]> out = new ArrayList<>();
        for (int[] p : pts) if (reach.contains(Pack.key(p[0], p[1] + 1, p[2]))) out.add(p);
        return out;
    }
}
