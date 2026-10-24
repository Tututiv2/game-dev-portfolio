package com.auraqol.shadow;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Amounts per key stamped on the ACTIVE clock ({@code RateTracker.activeMs}),
 * so "the last 10 minutes" means the last 10 minutes you were playing - an AFK
 * gap neither empties the window nor drags the rate down. The Shadow sibling
 * of {@code skyblock.IncomeLedger}, which is keyed by a fixed SkyBlock enum;
 * here the keys are whatever the server sends ({@code selling},
 * {@code enchant_greed}, ...), so they are strings. Pure.
 *
 * <p>Entries for the same key landing in the same second are merged, which
 * bounds memory whatever the server's payout rate: at most one entry per key per
 * second of active play, dropped once older than {@link #keepMs}.
 */
public final class ActiveLedger {

    private static final long BUCKET_MS = 1000;

    private static final class Entry {
        final long at;
        final String key;
        double value;

        Entry(long at, String key, double value) {
            this.at = at;
            this.key = key;
            this.value = value;
        }
    }

    private final long keepMs;
    private final int maxEntries;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    /** Everything since the last clear, per key, in arrival order. */
    private final Map<String, Double> session = new LinkedHashMap<>();
    private double sessionTotal;

    public ActiveLedger(long keepMs, int maxEntries) {
        this.keepMs = Math.max(60_000, keepMs);
        this.maxEntries = Math.max(64, maxEntries);
    }

    public void add(long activeAt, String key, double value) {
        if (value == 0 || Double.isNaN(value) || Double.isInfinite(value) || key == null) {
            return;
        }
        session.merge(key, value, Double::sum);
        sessionTotal += value;
        long bucket = activeAt - Math.floorMod(activeAt, BUCKET_MS);
        // Same key, same second: merge into the newest entry for that key in this bucket.
        Iterator<Entry> it = entries.descendingIterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.at != bucket) {
                break;
            }
            if (e.key.equals(key)) {
                e.value += value;
                return;
            }
        }
        entries.addLast(new Entry(bucket, key, value));
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
        prune(activeAt);
    }

    public void prune(long activeNow) {
        while (!entries.isEmpty() && entries.peekFirst().at < activeNow - keepMs) {
            entries.removeFirst();
        }
    }

    public void clear() {
        entries.clear();
        session.clear();
        sessionTotal = 0;
    }

    /** Sum per key over the last {@code windowMs} of active time, in first-seen order. */
    public Map<String, Double> byKey(long activeNow, long windowMs) {
        Map<String, Double> out = new LinkedHashMap<>();
        long from = activeNow - windowMs;
        for (Entry e : entries) {
            // Buckets are floored to the second, so an entry counts if any of its second is inside.
            if (e.at + BUCKET_MS <= from) {
                continue;
            }
            out.merge(e.key, e.value, Double::sum);
        }
        return out;
    }

    public double sum(long activeNow, long windowMs) {
        double t = 0;
        for (double v : byKey(activeNow, windowMs).values()) {
            t += v;
        }
        return t;
    }

    /** Per key since the last clear (the session). */
    public Map<String, Double> sessionByKey() {
        return new LinkedHashMap<>(session);
    }

    public double sessionTotal() {
        return sessionTotal;
    }

    /**
     * Per hour over the window: the window's sum divided by the window, or by
     * the active time so far if that is shorter. Zero until {@code minMs} of
     * active time exists (one payout two seconds in would otherwise extrapolate
     * to billions).
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
