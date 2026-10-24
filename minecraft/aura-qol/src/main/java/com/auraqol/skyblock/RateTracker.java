package com.auraqol.skyblock;

/**
 * Coins per hour / per minute over ACTIVE time only. Pure; clock is passed in.
 *
 * <p><b>Why idle time is trimmed retroactively.</b> The obvious rule - "stop
 * the clock after 60 s of nothing" - still bills you for those 60 s every time
 * you walk away, so a session with ten short AFKs is ten minutes slower than
 * the one you actually played. Here the clock runs in segments: a segment
 * ends at the moment of the LAST activity, not at the moment idleness was
 * noticed. So once you have been idle past the threshold, the whole gap since
 * your last action is removed, and coming back starts a fresh segment.
 *
 * <p>Income itself counts as activity (a sack summary arriving means you were
 * doing something), and so does anything the caller reports through
 * {@link #activity} - moving, breaking blocks, looking around.
 *
 * <p>A manual pause closes the segment and drops income until resumed: a
 * paused tracker that kept adding coins but not time would report an
 * infinite rate, and one that kept time but not coins would report zero.
 */
public final class RateTracker {

    public enum State { WAITING, ACTIVE, IDLE, PAUSED }

    private final long idleMs;

    private double total;
    private long committedMs;
    private long segStart = -1;
    private long lastActivity = -1;
    private boolean paused;

    public RateTracker(long idleMs) {
        this.idleMs = Math.max(1000, idleMs);
    }

    public long idleMs() {
        return idleMs;
    }

    /** Something happened that a player does on purpose. */
    public void activity(long now) {
        if (paused) {
            return;
        }
        if (segStart < 0) {
            segStart = now;
        } else if (now - lastActivity > idleMs) {
            // The gap is over: bank the segment up to the last real action,
            // then start again from now. The idle stretch is simply not in it.
            committedMs += Math.max(0, lastActivity - segStart);
            segStart = now;
        }
        lastActivity = Math.max(lastActivity, now);
    }

    /** Coins gained (positive) or lost (negative). Ignored while paused. */
    public void income(long now, double amount) {
        if (paused || amount == 0 || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return;
        }
        total += amount;
        activity(now);
    }

    public void pause(long now) {
        if (paused) {
            return;
        }
        committedMs += openSegmentMs(now);
        segStart = -1;
        paused = true;
    }

    public void resume(long now) {
        if (!paused) {
            return;
        }
        paused = false;
        segStart = now;
        lastActivity = now;
    }

    public void reset() {
        total = 0;
        committedMs = 0;
        segStart = -1;
        lastActivity = -1;
        paused = false;
    }

    public boolean paused() {
        return paused;
    }

    public State state(long now) {
        if (paused) {
            return State.PAUSED;
        }
        if (segStart < 0) {
            return State.WAITING;
        }
        return now - lastActivity > idleMs ? State.IDLE : State.ACTIVE;
    }

    public double total() {
        return total;
    }

    /** Time that counts: closed segments plus the open one, trimmed if idle. */
    public long activeMs(long now) {
        return committedMs + openSegmentMs(now);
    }

    private long openSegmentMs(long now) {
        if (segStart < 0) {
            return 0;
        }
        long end = now - lastActivity > idleMs ? lastActivity : now;
        return Math.max(0, end - segStart);
    }

    /**
     * Coins per hour. Zero until at least {@code minMs} of active time exists,
     * because two seconds in, one sack message extrapolates to billions.
     */
    public double perHour(long now, long minMs) {
        long t = activeMs(now);
        if (t < Math.max(1, minMs)) {
            return 0;
        }
        return total * 3_600_000.0 / t;
    }

    public double perMinute(long now, long minMs) {
        return perHour(now, minMs) / 60.0;
    }
}
