package org.aincrad.plugin.terrain;

/**
 * Southern Grasslands base height formula, FLOOR-01-RECONSTRUCTION-SPEC.md 5.1:
 * base = 320; macro = simplex2D(x/520, z/520) * 7; micro = simplex2D(x/68, z/68) * 2.
 * The entire authorized 7,912-chunk mask lies inside the south_grasslands region (verified
 * against docs/floor-01-layout.json's terrain_regions polygons before writing this), so no other
 * region's formula is needed for this review slice.
 */
public final class TerrainProfile {

    private static final int BASE_Y = 320;
    private static final double MACRO_WAVELENGTH = 520.0;
    private static final double MACRO_AMPLITUDE = 7.0;
    private static final double MICRO_WAVELENGTH = 68.0;
    private static final double MICRO_AMPLITUDE = 2.0;

    private final GradientNoise2D macroNoise;
    private final GradientNoise2D microNoise;

    public TerrainProfile(long worldSeed) {
        this.macroNoise = new GradientNoise2D(worldSeed);
        this.microNoise = new GradientNoise2D(worldSeed + 1);
    }

    public int surfaceY(int blockX, int blockZ) {
        double macro = macroNoise.noise(blockX / MACRO_WAVELENGTH, blockZ / MACRO_WAVELENGTH) * MACRO_AMPLITUDE;
        double micro = microNoise.noise(blockX / MICRO_WAVELENGTH, blockZ / MICRO_WAVELENGTH) * MICRO_AMPLITUDE;
        return (int) Math.round(BASE_Y + macro + micro);
    }
}
