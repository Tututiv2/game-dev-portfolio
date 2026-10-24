package com.skyclone.gen.util;

import java.util.Arrays;

/** Open-addressing set of longs, no boxing. */
public final class LongSet {

    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys;
    private int size, mask, limit;

    public LongSet() { this(1024); }

    public LongSet(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
        alloc(cap);
    }

    private void alloc(int cap) {
        keys = new long[cap];
        Arrays.fill(keys, EMPTY);
        mask = cap - 1;
        limit = (int) (cap * 0.6);
        size = 0;
    }

    private static int mix(long k) {
        k ^= k >>> 33; k *= 0xc4ceb9fe1a85ec53L; k ^= k >>> 33;
        return (int) k;
    }

    public boolean contains(long k) {
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == k) return true;
            if (c == EMPTY) return false;
            i = (i + 1) & mask;
        }
    }

    /** @return true if newly added. */
    public boolean add(long k) {
        if (k == EMPTY) throw new IllegalArgumentException("reserved key");
        if (size >= limit) grow();
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == k) return false;
            if (c == EMPTY) { keys[i] = k; size++; return true; }
            i = (i + 1) & mask;
        }
    }

    private void grow() {
        long[] ok = keys;
        alloc(ok.length * 2);
        for (long k : ok) if (k != EMPTY) add(k);
    }

    public int size() { return size; }

    public interface Visitor { void visit(long key); }

    public void forEach(Visitor v) {
        for (long k : keys) if (k != EMPTY) v.visit(k);
    }
}
