package com.skyclone.gen;

/**
 * Seeded gradient noise (Ken Perlin's improved noise, 2D and 3D) plus the usual stacks: fractal
 * Brownian motion, ridged, billow and domain warping. All outputs are deterministic for a seed.
 *
 * <pre>
 *   Noise n = new Noise(seed);
 *   double h = n.fbm2(x / 64.0, z / 64.0, 5);          // [-1, 1]
 *   double r = n.ridged2(x / 90.0, z / 90.0, 4);        // [0, 1], sharp crests
 *   double w = n.warped2(x / 80.0, z / 80.0, 1.5, 4);   // fbm of a warped domain: organic shapes
 * </pre>
 * Scale inputs yourself: one unit of input is one "feature" (divide block coords by the size you want).
 */
public final class Noise {

    private final int[] p = new int[512];
    private final double ox, oy, oz;

    public Noise(long seed) {
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        java.util.SplittableRandom r = new java.util.SplittableRandom(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = perm[i]; perm[i] = perm[j]; perm[j] = t;
        }
        for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
        ox = r.nextDouble() * 256; oy = r.nextDouble() * 256; oz = r.nextDouble() * 256;
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }

    public static double lerp(double t, double a, double b) { return a + t * (b - a); }

    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Hermite smoothstep of x between edges a and b, clamped to [0,1]. */
    public static double smoothstep(double a, double b, double x) {
        double t = clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double grad3(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private static double grad2(int hash, double x, double y) {
        switch (hash & 7) {
            case 0: return x + y;
            case 1: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x;
            case 5: return -x;
            case 6: return y;
            default: return -y;
        }
    }

    /** 2D noise in roughly [-1, 1]. */
    public double noise2(double x, double y) {
        x += ox; y += oy;
        int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255;
        x -= Math.floor(x); y -= Math.floor(y);
        double u = fade(x), v = fade(y);
        int a = p[X] + Y, b = p[X + 1] + Y;
        double r = lerp(v, lerp(u, grad2(p[a], x, y), grad2(p[b], x - 1, y)),
                lerp(u, grad2(p[a + 1], x, y - 1), grad2(p[b + 1], x - 1, y - 1)));
        return clamp(r * 0.98, -1, 1);
    }

    /** 3D noise in roughly [-1, 1]. */
    public double noise3(double x, double y, double z) {
        x += ox; y += oy; z += oz;
        int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255, Z = (int) Math.floor(z) & 255;
        x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
        double u = fade(x), v = fade(y), w = fade(z);
        int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z, B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
        double r = lerp(w, lerp(v, lerp(u, grad3(p[AA], x, y, z), grad3(p[BA], x - 1, y, z)),
                        lerp(u, grad3(p[AB], x, y - 1, z), grad3(p[BB], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad3(p[AA + 1], x, y, z - 1), grad3(p[BA + 1], x - 1, y, z - 1)),
                        lerp(u, grad3(p[AB + 1], x, y - 1, z - 1), grad3(p[BB + 1], x - 1, y - 1, z - 1))));
        return clamp(r, -1, 1);
    }

    /** Fractal sum, normalised to [-1, 1]. lacunarity 2, gain 0.5 by default. */
    public double fbm2(double x, double y, int octaves) { return fbm2(x, y, octaves, 2.0, 0.5); }

    public double fbm2(double x, double y, int octaves, double lacunarity, double gain) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise2(x * f + i * 17.13, y * f - i * 31.7);
            norm += amp; amp *= gain; f *= lacunarity;
        }
        return sum / norm;
    }

    public double fbm3(double x, double y, double z, int octaves) { return fbm3(x, y, z, octaves, 2.0, 0.5); }

    public double fbm3(double x, double y, double z, int octaves, double lacunarity, double gain) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise3(x * f + i * 17.13, y * f + i * 5.3, z * f - i * 31.7);
            norm += amp; amp *= gain; f *= lacunarity;
        }
        return sum / norm;
    }

    /** Ridged multifractal in [0, 1]: sharp crests where the noise crosses zero (mountain ridges, canyons). */
    public double ridged2(double x, double y, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1, weight = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(noise2(x * f + i * 11.1, y * f + i * 7.7));
            n *= n;
            n *= weight;
            weight = clamp(n * 2, 0, 1);
            sum += n * amp; norm += amp; amp *= 0.5; f *= 2;
        }
        return clamp(sum / norm, 0, 1);
    }

    public double ridged3(double x, double y, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(noise3(x * f, y * f, z * f));
            sum += n * n * amp; norm += amp; amp *= 0.5; f *= 2;
        }
        return clamp(sum / norm, 0, 1);
    }

    /** Billow: abs-noise fractal, puffy shapes (clouds, bushes), in [0, 1]. */
    public double billow2(double x, double y, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += Math.abs(noise2(x * f + i * 3.3, y * f + i * 9.1)) * amp;
            norm += amp; amp *= 0.5; f *= 2;
        }
        return clamp(sum / norm, 0, 1);
    }

    /** fbm2 of a domain warped by two other fbm fields; {@code strength} ~1-2 gives organic coastlines. */
    public double warped2(double x, double y, double strength, int octaves) {
        double wx = fbm2(x + 5.2, y + 1.3, 3);
        double wy = fbm2(x - 8.3, y + 2.8, 3);
        return fbm2(x + strength * wx, y + strength * wy, octaves);
    }

    /** Returns the warped coordinates instead of the value: {x', y'}. */
    public double[] warp2(double x, double y, double strength) {
        return new double[]{x + strength * fbm2(x + 5.2, y + 1.3, 3), y + strength * fbm2(x - 8.3, y + 2.8, 3)};
    }
}
