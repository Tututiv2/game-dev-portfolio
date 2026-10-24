package org.aincrad.plugin.terrain;

/**
 * Whole-floor terrain for Floor One: grasslands, the Nepenthes forest rise, eastern lake country,
 * the Dallhari canyon with its two raised crossings, the northern mountain belt, the Tolbana valley
 * notch, and the Labyrinth shelf.
 *
 * <p>The macro shape is a direct port of this project's own reference function in
 * {@code scripts/render_floor01_plan.py:terrain_height()}, deliberately rather than a fresh
 * invention: that function is what produced the published plan/elevation render the owner has been
 * reviewing, so porting it means the generated world matches the picture instead of quietly
 * diverging from it. Seeded gradient noise adds the micro detail the plan renderer did not need.</p>
 *
 * <p>{@link TerrainProfile} is retained unchanged for the already-built Town review slice - the
 * town's 7,912 chunks were shaped with it and are committed, so re-shaping them would break
 * finished work. The full-floor driver skips committed chunks, which keeps the two consistent.</p>
 */
public final class FloorOneTerrain {

    public static final int WATER_Y = 316;
    // Still the 1:1 figures: the world currently on disk was built at 1:1, and pointing these at
    // the compact profile while the town sits at r=4500 would put the entire town "outside the
    // floor" and break every shell/terrain check. WorldScale exists and is ready, but the rescale
    // must happen as one atomic pass with a fresh world, not half-applied.
    public static final int FLOOR_RADIUS = 5000;
    public static final int PLAYABLE_RADIUS = 4976;

    private final GradientNoise2D macro;
    private final GradientNoise2D micro;

    public FloorOneTerrain(long worldSeed) {
        this.macro = new GradientNoise2D(worldSeed + 101);
        this.micro = new GradientNoise2D(worldSeed + 202);
    }

    /** Regions, used for surface material and decoration decisions. */
    public enum Region {GRASSLAND, FOREST, LAKE, MARSH, CANYON, RUINS, MOUNTAIN, TOLBANA_VALLEY, LABYRINTH_SHELF, RIM}

    /** Reference macro shape, ported from scripts/render_floor01_plan.py. */
    private static double referenceHeight(double x, double z) {
        double y = 320.0;
        y += Math.sin(x / 480.0) * 3.0 + Math.cos(z / 620.0) * 3.0;
        if (z < -1300) {
            y += Math.min(58.0, (-z - 1300.0) * 0.0175);          // northern mountain belt
        }
        if (z < -2600) {
            y += 10.0;
        }
        if (x > 1400 && z > -2000 && z < 2600) {
            y = Math.min(y, 322.0);                                // eastern lake country basin
        }
        if (z > -2360 && z < -1120) {                              // Dallhari divide
            double canyon = Math.max(0.0, 1.0 - Math.abs(z + 1700.0) / 640.0);
            double crossingRelief = Math.max(
                    Math.max(0.0, 1.0 - Math.abs(x + 2560.0) / 520.0),   // west cave crossing
                    Math.max(0.0, 1.0 - Math.abs(x - 2420.0) / 600.0));  // east aqueduct crossing
            y -= canyon * (28.0 - crossingRelief * 21.0);
        }
        double tolbanaNotch = Math.max(0.0,
                1.0 - Math.hypot((x - 700.0) / 1300.0, (z + 3480.0) / 1040.0));
        y -= tolbanaNotch * 20.0;
        return y;
    }

    /** Elliptical lake basins from the layout JSON's water_system. */
    private static double lakeDepression(double x, double z) {
        double[][] lakes = {
                {3320, 1080, 620, 420, 20},   // Mirror Lake
                {3680, -500, 500, 340, 8},    // North Marsh
                {2360, -80, 340, 260, 14},    // Medai Lake
        };
        double deepest = 0;
        for (double[] l : lakes) {
            double d = Math.hypot((x - l[0]) / l[2], (z - l[1]) / l[3]);
            if (d < 1.0) {
                double depth = l[4] * (1.0 - d * d);
                deepest = Math.max(deepest, depth);
            }
        }
        return deepest;
    }

    /** The Labyrinth sits on a carved shelf at roughly Y=376 at the far north rim. */
    private static double labyrinthShelf(double x, double z) {
        double d = Math.hypot((x + 500) / 460.0, (z + 4700) / 420.0);
        return d < 1.0 ? (1.0 - d) : 0.0;
    }

    public int surfaceY(int bx, int bz) {
        double x = bx, z = bz;
        double y = referenceHeight(x, z);
        y += macro.noise(x / 520.0, z / 520.0) * 5.0;
        y += micro.noise(x / 68.0, z / 68.0) * 2.0;

        double lake = lakeDepression(x, z);
        if (lake > 0) {
            y = Math.min(y, WATER_Y - 1) - lake * 0.6;
        }

        double shelf = labyrinthShelf(x, z);
        if (shelf > 0) {
            y = y * (1 - shelf) + 376.0 * shelf;
        }

        // Rim: the outer band flattens into the shell crown so the edge reads as built structure.
        double r = Math.hypot(x, z);
        if (r > 4936) {
            double t = Math.min(1.0, (r - 4936) / 40.0);
            y = y * (1 - t) + 322.0 * t;
        }
        // Nothing may sit above the ordinary terrain ceiling: Floor Two's plate starts at Y=420 and
        // the spec reserves 11 blocks of emergency clearance below it.
        return (int) Math.round(Math.max(290, Math.min(408, y)));
    }

    public Region regionAt(int bx, int bz) {
        double x = bx, z = bz;
        double r = Math.hypot(x, z);
        if (r > 4936) {
            return Region.RIM;
        }
        if (labyrinthShelf(x, z) > 0.15) {
            return Region.LABYRINTH_SHELF;
        }
        if (lakeDepression(x, z) > 0) {
            return (Math.hypot((x - 3680) / 500.0, (z + 500) / 340.0) < 1.0) ? Region.MARSH : Region.LAKE;
        }
        if (z > -2360 && z < -1120 && Math.abs(z + 1700) < 400) {
            return Region.CANYON;
        }
        if (Math.hypot((x - 700) / 1300.0, (z + 3480) / 1040.0) < 0.6) {
            return Region.TOLBANA_VALLEY;
        }
        if (z < -1300) {
            return Region.MOUNTAIN;
        }
        if (x > 1040 && z < -1320 && z > -3260) {
            return Region.RUINS;
        }
        if (x < -1640 && z > -1640 && z < 2240) {
            return Region.FOREST;
        }
        return Region.GRASSLAND;
    }

    /** True when this column lies inside the floor's playable disc. */
    public static boolean inPlayableDisc(int bx, int bz) {
        return Math.hypot(bx, bz) <= PLAYABLE_RADIUS;
    }

    /** Underside of the floor shell, per the layout JSON's underside_formula. */
    public static int undersideY(int bx, int bz) {
        double r = Math.hypot(bx, bz);
        int v = (int) Math.round(-48 + 0.0000136 * r * r);
        return Math.max(-64 + 6, Math.min(v, 320 - 28));
    }
}
