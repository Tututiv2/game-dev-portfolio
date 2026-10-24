package org.aincrad.plugin.district;

import java.util.List;

/**
 * Everything a building plot must keep clear of: the city wall and its service band, the three
 * gate approaches, the plaza and its arcade, the processional avenue, the Palace precinct, and the
 * outbound roads. Checked per-parcel before a plot is ever accepted, so no building can be planned
 * on top of an earlier committed feature.
 */
public final class ExclusionZones {

    private static final double WALL_CX = 0, WALL_CZ = 4500, WALL_R = 500;
    private static final int NORTH_WALL_Z = 4000;
    private static final int SIDE_WALL_X = 500;
    /** Wall thickness plus wall-walk service band buildings must not encroach on. */
    private static final int WALL_CLEARANCE = 10;

    private static final int PLAZA_CX = 0, PLAZA_CZ = 4416;
    private static final int PLAZA_CLEAR_R = 142;      // arcade 136 + margin

    private static final int AVENUE_HALF = 12, AVENUE_Z0 = 4220, AVENUE_Z1 = 4312;

    private static final int PALACE_X0 = -170, PALACE_X1 = 170;
    private static final int PALACE_Z0 = 4045, PALACE_Z1 = 4235;

    /** Outbound roads: {x1,z1,x2,z2,halfWidthPlusVerge}. */
    private static final double[][] ROADS = {
            {0, 4000, -80, 3160, 9},
            {-380, 4000, -840, 3520, 8},
            {380, 4000, 960, 3380, 8},
    };

    private ExclusionZones() {
    }

    public static boolean isBlocked(int x, int z) {
        // Outside the city polygon entirely.
        if (!insideCity(x, z)) {
            return true;
        }
        // Wall service band, measured against whichever wall element is nearest.
        if (z - NORTH_WALL_Z < WALL_CLEARANCE) {
            return true;
        }
        if (SIDE_WALL_X - Math.abs(x) < WALL_CLEARANCE && z <= WALL_CZ) {
            return true;
        }
        if (z > WALL_CZ && WALL_R - Math.hypot(x - WALL_CX, z - WALL_CZ) < WALL_CLEARANCE) {
            return true;
        }
        if (Math.hypot(x - PLAZA_CX, z - PLAZA_CZ) <= PLAZA_CLEAR_R) {
            return true;
        }
        if (Math.abs(x) <= AVENUE_HALF && z >= AVENUE_Z0 && z <= AVENUE_Z1) {
            return true;
        }
        if (x >= PALACE_X0 && x <= PALACE_X1 && z >= PALACE_Z0 && z <= PALACE_Z1) {
            return true;
        }
        for (double[] r : ROADS) {
            if (distanceToSegment(x, z, r[0], r[1], r[2], r[3]) <= r[4]) {
                return true;
            }
        }
        return false;
    }

    /** The adopted D-shape city polygon (see layout JSON city_envelope). */
    public static boolean insideCity(int x, int z) {
        if (x < -SIDE_WALL_X || x > SIDE_WALL_X || z < NORTH_WALL_Z || z > 5000) {
            return false;
        }
        if (z <= WALL_CZ) {
            return true;
        }
        return Math.hypot(x - WALL_CX, z - WALL_CZ) <= WALL_R;
    }

    /** True when any part of the given rectangle is blocked. */
    public static boolean rectBlocked(int minX, int minZ, int maxX, int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (isBlocked(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double distanceToSegment(double px, double pz, double ax, double az,
                                             double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (pz - az) * dz) / len2));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }

    public static List<int[]> districtBounds() {
        return List.of(
                new int[]{110, 4320, 410, 4680},     // central market
                new int[]{-430, 4280, -110, 4700},   // inn and crafting quarter
                new int[]{-380, 4680, -70, 4910},    // church and orphan quarter
                new int[]{50, 4660, 410, 4920},      // residential quarter
                new int[]{-420, 4000, 420, 4320}     // north gate services
        );
    }
}
