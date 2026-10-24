package com.auraqol.skyblock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * How fast a progress number is climbing, and when it will reach its target.
 * Used for the slayer bar ("266 XP left, ~1m 20s"). Pure, no Minecraft.
 *
 * <p>Samples are kept only when the value changes. The rate is measured from
 * the oldest kept sample to NOW (not to the newest sample), so standing still
 * makes the estimate decay instead of freezing at the last good pace - an ETA
 * that keeps promising 30 s while you are AFK in a menu is a lie.
 *
 * <p>A different {@code key} (new boss / tier / target) or the value going
 * DOWN (quest claimed, new quest) starts over: one quest's pace says nothing
 * certain about the next one's first seconds.
 */
public final class ProgressRate {

    private record Sample(long t, double v) {
    }

    private final long windowMs;
    private final long minSpanMs;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private String key;
    private double target = Double.NaN;

    /**
     * @param windowMs  how much history the rate looks back over
     * @param minSpanMs below this much history there is no estimate
     */
    public ProgressRate(long windowMs, long minSpanMs) {
        this.windowMs = windowMs;
        this.minSpanMs = minSpanMs;
    }

    public synchronized void sample(long now, String key, double value, double target) {
        if (Double.isNaN(value)) {
            return;
        }
        Sample last = samples.peekLast();
        if (!Objects.equals(this.key, key) || (last != null && value < last.v())) {
            samples.clear();
            last = null;
        }
        this.key = key;
        this.target = target;
        if (last == null || value != last.v()) {
            samples.addLast(new Sample(now, value));
        }
        // Keep one sample at or before the window start as the base.
        while (samples.size() > 2) {
            Sample first = samples.peekFirst();
            Sample second = secondOf(samples);
            if (second != null && now - second.t() >= windowMs && first != null) {
                samples.pollFirst();
            } else {
                break;
            }
        }
    }

    private static Sample secondOf(Deque<Sample> d) {
        var it = d.iterator();
        it.next();
        return it.hasNext() ? it.next() : null;
    }

    public synchronized void clear() {
        samples.clear();
        key = null;
        target = Double.NaN;
    }

    /** Units per minute, or NaN with too little history or no progress yet. */
    public synchronized double perMinute(long now) {
        if (samples.size() < 2) {
            return Double.NaN;
        }
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        long span = now - first.t();
        if (span < minSpanMs || last.v() <= first.v()) {
            return Double.NaN;
        }
        return (last.v() - first.v()) * 60_000.0 / span;
    }

    /** Milliseconds until the target at the current pace, or -1 if unknown. */
    public synchronized long etaMs(long now) {
        double rate = perMinute(now);
        Sample last = samples.peekLast();
        if (Double.isNaN(rate) || rate <= 0 || last == null || Double.isNaN(target)) {
            return -1;
        }
        double left = Math.max(0, target - last.v());
        return Math.round(left / rate * 60_000.0);
    }
}
