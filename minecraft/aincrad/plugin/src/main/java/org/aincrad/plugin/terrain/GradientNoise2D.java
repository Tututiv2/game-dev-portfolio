package org.aincrad.plugin.terrain;

import java.util.Random;

/**
 * Deterministic, seeded 2D gradient (Perlin-style) noise. This is not a bit-for-bit port of any
 * particular reference implementation - the spec's "simplex2D(x/scale, z/scale)" formulas
 * (FLOOR-01-RECONSTRUCTION-SPEC.md 5.1) describe a smooth, continuous, seeded noise shape with a
 * given wavelength/amplitude, not a requirement to match Minecraft's own internal noise
 * algorithm. This produces exactly that: smooth, continuous, and fully deterministic for a given
 * seed and (x, z).
 */
public final class GradientNoise2D {

    private final int[] permutation = new int[512];

    public GradientNoise2D(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        Random random = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int swapIndex = random.nextInt(i + 1);
            int temp = p[i];
            p[i] = p[swapIndex];
            p[swapIndex] = temp;
        }
        for (int i = 0; i < 512; i++) {
            permutation[i] = p[i & 255];
        }
    }

    /** Returns a value in approximately [-1, 1]. */
    public double noise(double x, double z) {
        int xi = (int) Math.floor(x) & 255;
        int zi = (int) Math.floor(z) & 255;
        double xf = x - Math.floor(x);
        double zf = z - Math.floor(z);

        double u = fade(xf);
        double v = fade(zf);

        int aa = permutation[permutation[xi] + zi];
        int ab = permutation[permutation[xi] + zi + 1];
        int ba = permutation[permutation[xi + 1] + zi];
        int bb = permutation[permutation[xi + 1] + zi + 1];

        double x1 = lerp(grad(aa, xf, zf), grad(ba, xf - 1, zf), u);
        double x2 = lerp(grad(ab, xf, zf - 1), grad(bb, xf - 1, zf - 1), u);
        return lerp(x1, x2, v);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double z) {
        int h = hash & 7;
        double u = h < 4 ? x : z;
        double v = h < 4 ? z : x;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}
