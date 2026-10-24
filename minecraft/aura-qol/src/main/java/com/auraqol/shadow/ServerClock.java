package com.auraqol.shadow;

/**
 * The server's clock as seen from this PC. Pure.
 *
 * <p>Boost expiries are absolute SERVER epoch ms so the server never has to
 * send a per-second countdown. Reading them against this PC's own clock would
 * be off by however far the two clocks disagree (minutes, on a badly set PC),
 * so {@code WELCOME.serverTimeMs} is stamped against the local clock the moment
 * it arrives and every later expiry is shifted by that offset. Network delay
 * makes it late by a few tens of milliseconds, which is below what a
 * whole-second countdown can show.
 */
public final class ServerClock {

    private long offsetMs;
    private boolean synced;

    /** WELCOME arrived: {@code serverTimeMs} is "now" on the server at local wall time {@code localWallMs}. */
    public void sync(long serverTimeMs, long localWallMs) {
        if (serverTimeMs <= 0) {
            offsetMs = 0;
            synced = false;
            return;
        }
        offsetMs = serverTimeMs - localWallMs;
        synced = true;
    }

    public void reset() {
        offsetMs = 0;
        synced = false;
    }

    public boolean synced() {
        return synced;
    }

    public long offsetMs() {
        return offsetMs;
    }

    /** The server's time now, given this PC's wall clock. */
    public long serverNow(long localWallMs) {
        return localWallMs + offsetMs;
    }

    /**
     * Milliseconds until a server-time expiry, never below 0;
     * {@code Long.MAX_VALUE} for {@code expiresAtMs = 0} (no expiry).
     */
    public long remaining(long expiresAtMs, long localWallMs) {
        if (expiresAtMs == 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, expiresAtMs - serverNow(localWallMs));
    }
}
