package org.aincrad.plugin.geometry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared rasterization helpers for hand-authored linear/curved features (roads, walls). Used by
 * every Phase 5+ generation class so the "sample every route at 4-block intervals" rule
 * (FLOOR-01-RECONSTRUCTION-SPEC.md 6.3) and polyline/arc thickening logic is implemented once,
 * not copy-pasted per feature.
 */
public final class GeometryUtils {

    private GeometryUtils() {
    }

    /** Every integer (x, z) column within halfWidth of the polyline defined by points ([x, z] pairs, at least 2). */
    public static Set<BlockColumn> thickPolylineColumns(List<double[]> points, double halfWidth) {
        Set<BlockColumn> result = new LinkedHashSet<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            double ax = points.get(i)[0], az = points.get(i)[1];
            double bx = points.get(i + 1)[0], bz = points.get(i + 1)[1];
            double segLength = Math.hypot(bx - ax, bz - az);
            double minX = Math.min(ax, bx) - halfWidth, maxX = Math.max(ax, bx) + halfWidth;
            double minZ = Math.min(az, bz) - halfWidth, maxZ = Math.max(az, bz) + halfWidth;
            int loX = (int) Math.floor(minX), hiX = (int) Math.ceil(maxX);
            int loZ = (int) Math.floor(minZ), hiZ = (int) Math.ceil(maxZ);
            for (int x = loX; x <= hiX; x++) {
                for (int z = loZ; z <= hiZ; z++) {
                    if (segLength == 0) {
                        if (Math.hypot(x - ax, z - az) <= halfWidth) {
                            result.add(new BlockColumn(x, z));
                        }
                        continue;
                    }
                    double t = ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / (segLength * segLength);
                    t = Math.max(0.0, Math.min(1.0, t));
                    double px = ax + t * (bx - ax);
                    double pz = az + t * (bz - az);
                    if (Math.hypot(x - px, z - pz) <= halfWidth) {
                        result.add(new BlockColumn(x, z));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Every integer (x, z) column at radial distance within halfWidth of the given radius,
     * centered at (centerX, centerZ), restricted to the angular span [startAngleDeg, endAngleDeg]
     * (0 = +X axis, sweeping toward +Z; pass 0..360 for a full ring).
     */
    public static Set<BlockColumn> thickArcColumns(double centerX, double centerZ, double radius, double halfWidth,
                                                     double startAngleDeg, double endAngleDeg) {
        Set<BlockColumn> result = new LinkedHashSet<>();
        // A literal 0..360 span must mean "every angle". Normalizing 360 % 360 == 0 would
        // otherwise collapse the whole span down to angle == 0 exactly - caught for real via a
        // boot test where only the east side of a ring (angle ~0) got built and the west side
        // didn't (server/boot-test9.log, /aincrad verify world sample asymmetry).
        boolean fullCircle = (endAngleDeg - startAngleDeg) >= 360.0;
        double start = ((startAngleDeg % 360) + 360) % 360;
        double end = ((endAngleDeg % 360) + 360) % 360;
        int loX = (int) Math.floor(centerX - radius - halfWidth);
        int hiX = (int) Math.ceil(centerX + radius + halfWidth);
        int loZ = (int) Math.floor(centerZ - radius - halfWidth);
        int hiZ = (int) Math.ceil(centerZ + radius + halfWidth);
        for (int x = loX; x <= hiX; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                double dx = x - centerX, dz = z - centerZ;
                double dist = Math.hypot(dx, dz);
                if (Math.abs(dist - radius) > halfWidth) {
                    continue;
                }
                if (!fullCircle) {
                    double angle = Math.toDegrees(Math.atan2(dz, dx));
                    if (angle < 0) {
                        angle += 360;
                    }
                    boolean inSpan = start <= end ? (angle >= start && angle <= end) : (angle >= start || angle <= end);
                    if (!inSpan) {
                        continue;
                    }
                }
                result.add(new BlockColumn(x, z));
            }
        }
        return result;
    }
}
