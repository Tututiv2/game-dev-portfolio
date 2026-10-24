package org.aincrad.plugin.safety;

/**
 * The Town of Beginnings safe polygon: the D-shape city envelope plus a 16-block exterior apron,
 * and crucially <em>Y-bound</em>.
 *
 * <p>The spec is explicit that "a player in a cave directly beneath a safe town is not safe unless
 * that cave is explicitly included", so this is not a simple X/Z test. The safe band runs from
 * {@link #MIN_SAFE_Y} (below the deepest house cellar, which sits about 5 blocks under a surface of
 * roughly 311-329) up to {@link #MAX_SAFE_Y} at Floor Two's structural plate. The whole Floor One
 * underbelly below that - the shell, the water seam, and any future hidden dungeon - is
 * deliberately outside the safe zone.</p>
 *
 * <p>The southern apron is clipped rather than extended: 16 blocks beyond the southern arc would
 * fall past the floor edge at r=5000 into the void, so out there the wall face itself is the
 * boundary.</p>
 */
public final class SafeZone {

    private static final double ARC_CX = 0, ARC_CZ = 4500, ARC_R = 500;
    private static final int NORTH_WALL_Z = 4000;
    private static final int SIDE_WALL_X = 500;
    private static final int APRON = 16;
    private static final int FLOOR_RADIUS = 5000;

    public static final int MIN_SAFE_Y = 300;
    public static final int MAX_SAFE_Y = 420;

    private SafeZone() {
    }

    /** Inside the city envelope itself (no apron). */
    public static boolean insideCity(int x, int z) {
        if (x < -SIDE_WALL_X || x > SIDE_WALL_X || z < NORTH_WALL_Z || z > 5000) {
            return false;
        }
        if (z <= ARC_CZ) {
            return true;
        }
        return Math.hypot(x - ARC_CX, z - ARC_CZ) <= ARC_R;
    }

    /** Inside the city envelope expanded by the 16-block apron, ignoring height. */
    public static boolean insideSafePolygon(int x, int z) {
        if (insideCity(x, z)) {
            return true;
        }
        if (x < -SIDE_WALL_X - APRON || x > SIDE_WALL_X + APRON
                || z < NORTH_WALL_Z - APRON || z > 5000 + APRON) {
            return false;
        }
        if (z <= ARC_CZ) {
            return Math.abs(x) <= SIDE_WALL_X + APRON && z >= NORTH_WALL_Z - APRON;
        }
        double d = Math.hypot(x - ARC_CX, z - ARC_CZ);
        if (d > ARC_R + APRON) {
            return false;
        }
        // Never claim ground that does not exist: the floor ends at r=5000.
        return Math.hypot(x, z) <= FLOOR_RADIUS;
    }

    /** The full, Y-aware safe test. */
    public static boolean isSafe(int x, int y, int z) {
        return y >= MIN_SAFE_Y && y <= MAX_SAFE_Y && insideSafePolygon(x, z);
    }
}
