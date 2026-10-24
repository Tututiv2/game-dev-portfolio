package com.auraqol.skyblock;

/**
 * Events in the last N milliseconds, plus the best that number has ever been
 * this session. Pure: the caller supplies the clock, so the tests can too.
 *
 * <p>Same shape as the CPS counter's click window (a fixed ring, newest-first
 * scan that stops at the first stale entry) for the same reason: it never
 * allocates while you play, and "how many in the last 1000 ms" is the number
 * people mean - a per-wall-clock-second bucket drops to 0 and back every
 * second.
 *
 * <p>The peak is only ever raised by {@link #sample}, i.e. by the same
 * rolling-window number that is on screen. Raising it inside {@link #add}
 * would be identical in steady state, but sampling is what makes "peak" mean
 * "the highest the HUD ever showed", which is the claim the HUD makes.
 */
public final class RollingCounter {

    private final long windowMs;
    private final long[] times;
    private int head;
    private int size;
    private int peak;
    private long total;

    public RollingCounter(long windowMs, int capacity) {
        if (windowMs <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("window and capacity must be positive");
        }
        this.windowMs = windowMs;
        this.times = new long[capacity];
    }

    /** One event at {@code nowMs}. Events must arrive in non-decreasing time order. */
    public synchronized void add(long nowMs) {
        times[head] = nowMs;
        head = (head + 1) % times.length;
        if (size < times.length) {
            size++;
        }
        total++;
    }

    /** Events whose timestamp is within the window ending at {@code nowMs}. */
    public synchronized int count(long nowMs) {
        int n = 0;
        int cap = times.length;
        for (int i = 1; i <= size; i++) {
            long t = times[(head - i + cap) % cap];
            if (nowMs - t >= windowMs) {
                break; // newest-first, so everything older is out too
            }
            n++;
        }
        return n;
    }

    /** The current count, and raises the session peak if it beat it. */
    public synchronized int sample(long nowMs) {
        int c = count(nowMs);
        if (c > peak) {
            peak = c;
        }
        return c;
    }

    public synchronized int peak() {
        return peak;
    }

    /** Every event since construction or the last {@link #reset}. */
    public synchronized long total() {
        return total;
    }

    public synchronized void reset() {
        head = 0;
        size = 0;
        peak = 0;
        total = 0;
    }
}
