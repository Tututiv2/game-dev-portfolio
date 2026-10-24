package com.secondwatch.core;

/**
 * Deterministic seeded RNG (xorshift64*).
 *
 * <p>Hand-rolled rather than {@link java.util.Random} on purpose: a run must be
 * byte-identical from its seed on any JDK, forever. That is what makes "the
 * journal describes this run's real rules" testable, and it is what lets a
 * recorded session be reproduced when something goes wrong on camera.
 *
 * <p>Nothing in the core may call Math.random() or System.currentTimeMillis().
 * If it does, runs stop being reproducible and the test suite becomes fiction.
 */
public final class Rng {

    private long state;

    public Rng(long seed) {
        // A zero state is a fixed point for xorshift, so fold it away.
        this.state = (seed == 0L) ? 0x9E3779B97F4A7C15L : seed;
    }

    /** Derive an independent stream from this one, labelled by purpose. */
    public Rng fork(String label) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < label.length(); i++) {
            h ^= label.charAt(i);
            h *= 0x100000001B3L;
        }
        return new Rng(state ^ h);
    }

    public long nextLong() {
        long x = state;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        state = x;
        return x * 0x2545F4914F6CDD1DL;
    }

    /** Uniform in [0, bound). */
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive: " + bound);
        return (int) Math.floorMod(nextLong(), (long) bound);
    }

    /** Uniform in [min, max] inclusive. */
    public int range(int min, int max) {
        if (max < min) throw new IllegalArgumentException("max < min");
        return min + nextInt(max - min + 1);
    }

    /** Uniform in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    public boolean chance(double p) {
        return nextDouble() < p;
    }

    public <T> T pick(java.util.List<T> from) {
        if (from.isEmpty()) throw new IllegalArgumentException("cannot pick from an empty list");
        return from.get(nextInt(from.size()));
    }

    public <T> T pick(T[] from) {
        if (from.length == 0) throw new IllegalArgumentException("cannot pick from an empty array");
        return from[nextInt(from.length)];
    }

    /** Fisher-Yates, in place, deterministic. */
    public <T> void shuffle(java.util.List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}
