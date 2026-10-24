package com.auraqol.skyblock;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Income entries stamped on the ACTIVE clock ({@link RateTracker#activeMs}),
 * so "the last 10 minutes" means the last 10 minutes you were playing - an AFK
 * gap neither empties the window nor drags the rate down. Pure.
 *
 * <p>Entries older than {@link #keepMs} of active time are dropped; the
 * longest window the panel offers is 30 minutes.
 */
public final class IncomeLedger {

    private record Entry(long at, double value, IncomeSource source) {
    }

    private final long keepMs;
    private final int maxEntries;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    public IncomeLedger(long keepMs, int maxEntries) {
        this.keepMs = Math.max(60_000, keepMs);
        this.maxEntries = Math.max(64, maxEntries);
    }

    public void add(long activeAt, double value, IncomeSource source) {
        if (value == 0 || Double.isNaN(value) || Double.isInfinite(value) || source == null) {
            return;
        }
        entries.addLast(new Entry(activeAt, value, source));
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
        prune(activeAt);
    }

    public void prune(long activeNow) {
        while (!entries.isEmpty() && entries.peekFirst().at() < activeNow - keepMs) {
            entries.removeFirst();
        }
    }

    public void clear() {
        entries.clear();
    }

    /** Sum per source over the last {@code windowMs} of active time, indexed by ordinal. */
    public double[] bySource(long activeNow, long windowMs) {
        double[] out = new double[IncomeSource.values().length];
        long from = activeNow - windowMs;
        Iterator<Entry> it = entries.descendingIterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.at() <= from) {
                break;
            }
            out[e.source().ordinal()] += e.value();
        }
        return out;
    }

    public double sum(long activeNow, long windowMs) {
        double t = 0;
        for (double v : bySource(activeNow, windowMs)) {
            t += v;
        }
        return t;
    }

    /**
     * Coins per hour over the window: the window's income divided by the
     * window, or by the active time so far if that is shorter. Zero until
     * {@code minMs} of active time exists (one sack line would otherwise
     * extrapolate to billions).
     */
    public double perHour(long activeNow, long windowMs, long minMs) {
        if (activeNow < Math.max(1, minMs)) {
            return 0;
        }
        long span = Math.max(1, Math.min(windowMs, activeNow));
        return sum(activeNow, windowMs) * 3_600_000.0 / span;
    }

    /** The span {@link #perHour} divides by. */
    public static long span(long activeNow, long windowMs) {
        return Math.max(0, Math.min(windowMs, activeNow));
    }

    public int size() {
        return entries.size();
    }
}
