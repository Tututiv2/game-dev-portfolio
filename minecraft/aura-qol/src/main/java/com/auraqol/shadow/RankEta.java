package com.auraqol.shadow;

/**
 * "Rank D in ~6m 20s": {@code (nextRankCost - balance) / earning rate}. Pure.
 *
 * <p>It HIDES rather than lie: no next rank (max rank), no balance yet, or an
 * earning rate of about zero (or negative, or so slow the answer is weeks)
 * are all {@link Kind#HIDDEN} - never "infinity", never a countdown that is
 * really a guess. A balance already at or above the cost is
 * {@link Kind#AFFORDABLE} whatever the rate.
 */
public final class RankEta {

    public enum Kind { HIDDEN, AFFORDABLE, ETA }

    public record Result(Kind kind, long etaMs) {
        static final Result HIDDEN = new Result(Kind.HIDDEN, -1);
        static final Result AFFORDABLE = new Result(Kind.AFFORDABLE, 0);
    }

    /** Below this many cents an hour the rate is "about zero". */
    public static final double MIN_CENTS_PER_HOUR = 100;

    /** An ETA longer than this is not a countdown, it is a shrug: hidden. */
    public static final long MAX_ETA_MS = 7L * 24 * 3_600_000;

    private RankEta() {
    }

    /**
     * @param balanceCents    current balance, or {@code Long.MIN_VALUE} if unknown
     * @param nextCostCents   full cost of the next rank-up, {@code < 0} = max rank
     * @param centsPerHour    money actually earned per hour (INCOME only)
     */
    public static Result of(long balanceCents, long nextCostCents, double centsPerHour) {
        if (nextCostCents < 0 || balanceCents == Long.MIN_VALUE) {
            return Result.HIDDEN;
        }
        if (balanceCents >= nextCostCents) {
            return Result.AFFORDABLE;
        }
        if (Double.isNaN(centsPerHour) || Double.isInfinite(centsPerHour) || centsPerHour < MIN_CENTS_PER_HOUR) {
            return Result.HIDDEN;
        }
        double left = (double) nextCostCents - (double) balanceCents;
        double ms = left / centsPerHour * 3_600_000.0;
        if (ms > MAX_ETA_MS) {
            return Result.HIDDEN;
        }
        return new Result(Kind.ETA, Math.max(1000, Math.round(ms)));
    }
}
