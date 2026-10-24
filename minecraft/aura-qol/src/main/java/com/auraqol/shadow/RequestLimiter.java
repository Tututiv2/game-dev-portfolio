package com.auraqol.shadow;

/**
 * The client's own brake on profile lookups, so the mod never spams the
 * server even if a key is held or a command is repeated. Pure; clock passed in.
 *
 * <p>Three rules, all of which must pass:
 * <ol>
 *   <li>a token bucket sized from {@code WELCOME.lookupsPerMinute} (the server's
 *       own limit, so the client normally never even hits RATE_LIMITED), burst
 *       capped at {@link #MAX_BURST};</li>
 *   <li>at least {@link #MIN_GAP_MS} between two requests;</li>
 *   <li>after a {@code RATE_LIMITED} answer, nothing until its retry-after has
 *       passed.</li>
 * </ol>
 */
public final class RequestLimiter {

    public static final long MIN_GAP_MS = 1000;
    public static final int MAX_BURST = 3;
    /** Used when the server did not say (lookupsPerMinute = 0). */
    public static final int DEFAULT_PER_MINUTE = 10;

    private double perMs;
    private double capacity;
    private double tokens;
    private long lastRefill = Long.MIN_VALUE;
    private long lastSent = Long.MIN_VALUE;
    private long blockedUntil = Long.MIN_VALUE;

    public RequestLimiter(long perMinute) {
        configure(perMinute);
    }

    public void configure(long perMinute) {
        long pm = perMinute <= 0 ? DEFAULT_PER_MINUTE : perMinute;
        perMs = pm / 60_000.0;
        capacity = Math.max(1, Math.min(MAX_BURST, pm));
        tokens = capacity;
        lastRefill = Long.MIN_VALUE;
    }

    private void refill(long now) {
        if (lastRefill != Long.MIN_VALUE && now > lastRefill) {
            tokens = Math.min(capacity, tokens + (now - lastRefill) * perMs);
        }
        lastRefill = now;
    }

    /** 0 if a request may go now, else how many ms to wait. Does not take a token. */
    public long waitMs(long now) {
        refill(now);
        long wait = 0;
        if (blockedUntil != Long.MIN_VALUE && now < blockedUntil) {
            wait = blockedUntil - now;
        }
        if (lastSent != Long.MIN_VALUE && now - lastSent < MIN_GAP_MS) {
            wait = Math.max(wait, MIN_GAP_MS - (now - lastSent));
        }
        if (tokens < 1 - 1e-9) {
            wait = Math.max(wait, (long) Math.ceil((1 - tokens) / perMs));
        }
        return wait;
    }

    /** Take a token if allowed. Returns 0 on success, else the wait in ms (nothing taken). */
    public long tryAcquire(long now) {
        long w = waitMs(now);
        if (w > 0) {
            return w;
        }
        tokens = Math.max(0, tokens - 1);
        lastSent = now;
        return 0;
    }

    /** The server said RATE_LIMITED: honour its retry-after. */
    public void rateLimited(long now, long retryAfterMs) {
        long until = now + Math.max(MIN_GAP_MS, retryAfterMs);
        blockedUntil = Math.max(blockedUntil, until);
    }
}
