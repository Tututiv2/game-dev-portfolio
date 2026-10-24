package com.skyclone.gen;

/**
 * Deterministic per-coordinate randomness. Use instead of java.util.Random wherever the answer
 * for a position must not depend on the ORDER blocks were visited — e.g. "is there a flower at
 * (x, z)" stays the same when you add a building somewhere else.
 */
public final class Hash {

    private Hash() {}

    /** SplitMix64 finaliser. */
    public static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long hash(long seed, int x, int y, int z) {
        long h = mix(seed ^ 0x632BE59BD9B4E019L);
        h = mix(h ^ (x * 0x9E3779B1L));
        h = mix(h ^ (y * 0x85EBCA77L));
        h = mix(h ^ (z * 0xC2B2AE3DL));
        return h;
    }

    public static long hash(long seed, int x, int z) { return hash(seed, x, 0x5bd1e995, z); }

    /** Uniform in [0, 1). */
    public static double unit(long seed, int x, int y, int z) {
        return (hash(seed, x, y, z) >>> 11) * 0x1.0p-53;
    }

    public static double unit(long seed, int x, int z) { return unit(seed, x, 0x5bd1e995, z); }

    /** Uniform integer in [lo, hi] inclusive. */
    public static int range(long seed, int x, int y, int z, int lo, int hi) {
        if (hi <= lo) return lo;
        return lo + (int) Math.floor(unit(seed, x, y, z) * (hi - lo + 1));
    }

    public static boolean chance(long seed, int x, int y, int z, double p) { return unit(seed, x, y, z) < p; }

    public static boolean chance(long seed, int x, int z, double p) { return unit(seed, x, z) < p; }

    /** Stable 64-bit hash of a string (FNV-1a then mixed). */
    public static long of(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return mix(h);
    }

    /** Derive a sub-seed: {@code Hash.salt(seed, "trees")}. */
    public static long salt(long seed, String salt) { return mix(seed ^ of(salt)); }

    /** A java.util.Random seeded deterministically from a seed and a salt. */
    public static java.util.Random random(long seed, String salt) { return new java.util.Random(salt(seed, salt)); }
}
