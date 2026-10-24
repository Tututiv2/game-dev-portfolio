package com.skyclone.gen.util;

import java.util.Arrays;

/** Open-addressing long -> int map with no boxing. {@link #MISSING} is returned for absent keys. */
public final class LongIntMap {

    public static final int MISSING = Integer.MIN_VALUE;
    private static final long EMPTY = Long.MIN_VALUE;

    private long[] keys;
    private int[] vals;
    private int size, mask, limit;

    public LongIntMap() { this(1024); }

    public LongIntMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
        alloc(cap);
    }

    private void alloc(int cap) {
        keys = new long[cap];
        Arrays.fill(keys, EMPTY);
        vals = new int[cap];
        mask = cap - 1;
        limit = (int) (cap * 0.6);
        size = 0;
    }

    private static int mix(long k) {
        k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33;
        return (int) k;
    }

    public int get(long k) {
        if (k == EMPTY) throw new IllegalArgumentException("reserved key");
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == k) return vals[i];
            if (c == EMPTY) return MISSING;
            i = (i + 1) & mask;
        }
    }

    public boolean containsKey(long k) { return get(k) != MISSING; }

    public void put(long k, int v) {
        if (k == EMPTY) throw new IllegalArgumentException("reserved key");
        if (size >= limit) grow();
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == k) { vals[i] = v; return; }
            if (c == EMPTY) { keys[i] = k; vals[i] = v; size++; return; }
            i = (i + 1) & mask;
        }
    }

    private void grow() {
        long[] ok = keys; int[] ov = vals;
        alloc(ok.length * 2);
        for (int i = 0; i < ok.length; i++) if (ok[i] != EMPTY) put(ok[i], ov[i]);
    }

    public int size() { return size; }

    public interface Visitor { void visit(long key, int value); }

    public void forEach(Visitor v) {
        for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY) v.visit(keys[i], vals[i]);
    }
}
