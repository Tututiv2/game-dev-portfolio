package com.skyclone.gen.util;

/**
 * Packs a block position into one long: x and z in 22 bits each (+/- 2,097,151), y in 20 bits.
 * Used as a hash key by the walkability flood and the component search, which touch millions of
 * cells and must not box a Long per lookup.
 */
public final class Pack {

    private Pack() {}

    private static final int XZ_BITS = 22, Y_BITS = 20;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1, Y_MASK = (1L << Y_BITS) - 1;
    private static final int Z_SHIFT = Y_BITS, X_SHIFT = Y_BITS + XZ_BITS;

    public static long key(int x, int y, int z) {
        return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    public static int x(long k) { return (int) (k << 0 >> X_SHIFT); }

    public static int z(long k) { return (int) ((k << (64 - X_SHIFT)) >> (64 - XZ_BITS)); }

    public static int y(long k) { return (int) ((k << (64 - Y_BITS)) >> (64 - Y_BITS)); }

    /** Column key (x,z) only. */
    public static long column(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }
}
