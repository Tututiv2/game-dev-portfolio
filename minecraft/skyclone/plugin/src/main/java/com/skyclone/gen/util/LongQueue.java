package com.skyclone.gen.util;

/** Growable ring-buffer FIFO of longs. */
public final class LongQueue {

    private long[] buf = new long[1024];
    private int head, tail, size;

    public void add(long v) {
        if (size == buf.length) {
            long[] n = new long[buf.length * 2];
            for (int i = 0; i < size; i++) n[i] = buf[(head + i) % buf.length];
            buf = n; head = 0; tail = size;
        }
        buf[tail] = v;
        tail = (tail + 1) % buf.length;
        size++;
    }

    public long poll() {
        long v = buf[head];
        head = (head + 1) % buf.length;
        size--;
        return v;
    }

    public boolean isEmpty() { return size == 0; }

    public int size() { return size; }
}
