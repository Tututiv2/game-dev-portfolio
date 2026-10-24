package com.skyclone.mod.crimson.gen;

import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.Noise;

/**
 * The Crimson Isle's district map: every plot, centre and radius in one place, so the terrain pass
 * (which flattens plots BEFORE any block is written) and the builders agree on where things go.
 * Pure Java. North is -Z.
 *
 * <pre>
 *            Mystic Marsh        BLAZING VOLCANO         Dojo   Forgotten Skull
 *                                 (Magma Chamber below)   lava hut
 *   DRAKEHOLD (barbarians)     CRIMSON FIELDS          CARMINE SPIRE (mages)
 *                                  STRONGHOLD (spawn)  Edgewraith yard
 *   The Dukedom   Burning Desert   Smoldering Tomb   Ruins of the Twin Pyres   Courtyard
 * </pre>
 */
public final class CPlan {

    private CPlan() {}

    public static final int BASE_Y = 72;
    public static final double RADIUS_X = 240, RADIUS_Z = 222;
    public static final Box BOUNDS = new Box(-268, 0, -250, 268, 200, 250);

    // ---- volcano + chamber ----
    public static final int VOLCANO_X = 0, VOLCANO_Z = -142;
    public static final double VOLCANO_R = 76;
    public static final double VOLCANO_H = 62;
    public static final double CRATER_R = 14;
    public static final int CHAMBER_FLOOR = 50;
    public static final double CHAMBER_R = 26;
    public static final int TUNNEL_X0 = -16, TUNNEL_X1 = -12;          // tunnel walls run north/south
    public static final int TUNNEL_Z_START = -66, TUNNEL_Z_END = -122;

    // ---- plots (y is ignored; the terrain pass picks a height) ----
    public static final Box STRONGHOLD = Box.of(-42, 0, 38, 42, 0, 116);
    public static final Box BLADE_YARD = Box.of(50, 0, 56, 82, 0, 92);
    public static final Box DRAKEHOLD = Box.of(-206, 0, -30, -120, 0, 76);
    public static final Box CARMINE = Box.of(120, 0, -34, 206, 0, 76);
    public static final Box DOJO = Box.of(84, 0, -124, 120, 0, -82);
    public static final Box SKULL = Box.of(136, 0, -152, 176, 0, -104);
    public static final Box HUT = Box.of(-118, 0, -104, -78, 0, -66);
    public static final Box TOMB = Box.of(-18, 0, 132, 48, 0, 196);
    public static final Box ASHFANG = Box.of(88, 0, 138, 134, 0, 184);
    public static final Box COURTYARD = Box.of(156, 0, 92, 214, 0, 150);
    public static final Box DUKEDOM = Box.of(-214, 0, 106, -156, 0, 166);
    public static final Box CROSSROADS = Box.of(-10, 0, -22, 10, 0, -2);
    public static final Box TUNNEL_MOUTH = Box.of(-22, 0, -70, -6, 0, -56);
    /** The Belly of the Beast (the Matriarch's lair) in the Mystic Marsh; its mouth faces south. */
    public static final int BELLY_X = -140, BELLY_Z = -152;
    public static final Box BELLY = Box.of(-158, 0, -170, -122, 0, -128);

    public static final Box[] PLOTS = {STRONGHOLD, BLADE_YARD, DRAKEHOLD, CARMINE, DOJO, SKULL, HUT, TOMB, ASHFANG,
            COURTYARD, DUKEDOM, CROSSROADS, TUNNEL_MOUTH, BELLY};

    // ---- districts (surface palettes, flora, zones) ----
    public enum District {
        FIELDS, VOLCANO, MARSH, SKULL, DRAKEHOLD, CARMINE, STRONGHOLD, DESERT, TOMB, DUKEDOM, COURTYARD, ASHFANG, DOJO
    }

    private static final Object[][] CENTRES = {
            {District.FIELDS, 0, -30},
            {District.MARSH, -150, -120},
            {District.SKULL, 160, -130},
            {District.DRAKEHOLD, -165, 22},
            {District.CARMINE, 165, 22},
            {District.STRONGHOLD, 0, 78},
            {District.DESERT, -95, 150},
            {District.TOMB, 15, 165},
            {District.DUKEDOM, -190, 140},
            {District.COURTYARD, 185, 122},
            {District.ASHFANG, 110, 160},
            {District.DOJO, 102, -103},
            {District.FIELDS, -60, -60},
            {District.FIELDS, 70, -40},
            {District.FIELDS, -80, 60},
            {District.FIELDS, 85, 75},
    };

    /** Which district a column belongs to (nearest centre on a noise-warped plane; the volcano wins its cone). */
    public static District district(Noise n, int x, int z) {
        double vx = x - VOLCANO_X, vz = z - VOLCANO_Z;
        if (Math.sqrt(vx * vx + vz * vz) < VOLCANO_R - 4) return District.VOLCANO;
        double wx = x + 26 * n.fbm2(x / 70.0, z / 70.0, 3), wz = z + 26 * n.fbm2((x + 500) / 70.0, (z - 300) / 70.0, 3);
        District best = District.FIELDS;
        double bd = Double.MAX_VALUE;
        for (Object[] c : CENTRES) {
            double dx = wx - (int) c[1], dz = wz - (int) c[2];
            double d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = (District) c[0]; }
        }
        return best;
    }

    public static double volcanoDist(double x, double z) {
        double dx = x - VOLCANO_X, dz = z - VOLCANO_Z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
