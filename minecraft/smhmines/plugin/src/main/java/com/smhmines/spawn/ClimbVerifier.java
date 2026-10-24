package com.smhmines.spawn;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Proves you can actually walk from one place to another.
 *
 * <p>This exists because three spawn builds in a row were rejected, and the common thread was not
 * taste — it was that <b>the climb did not connect</b>. The last one had a helical ramp starting
 * eight blocks above the camp floor with no stairs onto it, and no door at the top. It looked
 * like a route and was a statue.
 *
 * <p>So the builder no longer gets to claim a climb exists. It runs this, and reports what this
 * says. Modelled on the prison project's {@code assertNpcReach} flood-fill, which proves every
 * NPC is walkable-to before a build is called finished.
 *
 * <p>Deliberately conservative: it only walks where a player provably could, and any doubt
 * resolves to "not reachable". A false failure costs a look; a false success costs another
 * rejected build.
 */
public final class ClimbVerifier {

    /** Safety cap. A spawn climb is tens of thousands of nodes; a runaway means a bug. */
    private static final int MAX_VISITED = 400_000;

    public record Result(boolean reachable, int visited, int highestY, double walkDistance,
                         String detail) {}

    private ClimbVerifier() {}

    /**
     * Flood-fills standable space from {@code from} and reports whether {@code to} is reached.
     *
     * <p>A block position is standable when the block beneath it supports a player and the two
     * blocks at head and foot height are passable. Moves are the four horizontal neighbours with
     * a height change of −1, 0 or +1 — i.e. exactly what a player can do by walking and stepping,
     * with no jumping across gaps and no falling assumed to be survivable.
     */
    public static Result canWalk(World world, Location from, Location to, int radius) {
        int fx = from.getBlockX(), fy = from.getBlockY(), fz = from.getBlockZ();
        int tx = to.getBlockX(), ty = to.getBlockY(), tz = to.getBlockZ();

        // Drop the start onto the first solid ground beneath it.
        fy = settle(world, fx, fy, fz);
        if (fy == Integer.MIN_VALUE) {
            return new Result(false, 0, 0, 0, "start position has no ground under it");
        }

        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{fx, fy, fz});
        seen.add(key(fx, fy, fz));

        int visited = 0;
        int highestY = fy;
        boolean found = false;

        while (!queue.isEmpty() && visited < MAX_VISITED) {
            int[] cur = queue.poll();
            visited++;
            if (cur[1] > highestY) highestY = cur[1];

            // Reaching the target column at roughly the target height is success — the summit is
            // a wide platform, not a single block.
            if (Math.abs(cur[0] - tx) <= 6 && Math.abs(cur[2] - tz) <= 6
                    && Math.abs(cur[1] - ty) <= 4) {
                found = true;
                break;
            }

            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                for (int dy = 1; dy >= -1; dy--) {
                    int nx = cur[0] + d[0], ny = cur[1] + dy, nz = cur[2] + d[1];
                    if (Math.abs(nx - fx) > radius || Math.abs(nz - fz) > radius) continue;
                    if (ny < world.getMinHeight() + 1 || ny > world.getMaxHeight() - 3) continue;
                    long k = key(nx, ny, nz);
                    if (seen.contains(k)) continue;
                    if (!standable(world, nx, ny, nz)) continue;
                    seen.add(k);
                    queue.add(new int[]{nx, ny, nz});
                    break;   // one height per horizontal direction — prefer stepping up
                }
            }
        }

        String detail;
        if (found) {
            detail = "reachable on foot";
        } else if (visited >= MAX_VISITED) {
            detail = "gave up after " + MAX_VISITED + " nodes — treat as NOT proven";
        } else {
            detail = "walked every reachable block and never got there; highest standable point "
                    + "reached was y" + highestY;
        }
        // Straight-line lower bound on how far the walk is; the real path is longer.
        double dist = Math.sqrt(Math.pow(tx - fx, 2) + Math.pow(tz - fz, 2)) + Math.abs(ty - fy);
        return new Result(found, visited, highestY, dist, detail);
    }

    /** First standable Y at or below the given one, or MIN_VALUE if the column is empty. */
    private static int settle(World world, int x, int y, int z) {
        for (int cy = Math.min(y + 2, world.getMaxHeight() - 3); cy > world.getMinHeight(); cy--) {
            if (standable(world, x, cy, z)) return cy;
        }
        return Integer.MIN_VALUE;
    }

    /** Feet at (x,y,z): solid support beneath, and room for a body. */
    private static boolean standable(World world, int x, int y, int z) {
        Block below = world.getBlockAt(x, y - 1, z);
        if (!supports(below.getType())) return false;
        return passable(world.getBlockAt(x, y, z).getType())
                && passable(world.getBlockAt(x, y + 1, z).getType());
    }

    private static boolean supports(Material m) {
        if (m.isAir() || !m.isSolid()) return false;
        // Things you cannot stand on safely, or that a player would not choose to.
        return m != Material.MAGMA_BLOCK && m != Material.CACTUS && m != Material.LAVA;
    }

    private static boolean passable(Material m) {
        if (m.isAir()) return true;
        // Walk-through decoration the builder uses on paths.
        return m == Material.LANTERN || m == Material.SOUL_LANTERN || m == Material.TORCH
                || m == Material.END_ROD || m == Material.IRON_CHAIN
                || m == Material.SHORT_GRASS || m == Material.TALL_GRASS;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFF);
    }
}
