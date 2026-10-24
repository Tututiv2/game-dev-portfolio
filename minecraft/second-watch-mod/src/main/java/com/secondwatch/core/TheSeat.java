package com.secondwatch.core;

import java.util.Optional;

/**
 * The way out. It fits one.
 *
 * <p>The whole run has been pointing at this sentence since the first journal
 * entry, and the reason it works is that it is not a puzzle. There is nothing to
 * solve, no way to widen it, no second seat hidden somewhere for players who
 * search hard enough. Two people who have spent five nights keeping each other
 * alive have to say out loud which of them is leaving - while something that can
 * imitate both of their voices is listening to that conversation.
 *
 * <p>Kept pure so the rules can be stated once, tested, and never quietly
 * softened by a special case in the block code.
 */
public final class TheSeat {

    /**
     * How long the departure takes.
     *
     * <p>Long enough that it cannot be a panic decision, and long enough that
     * whoever is staying has to stand there through all of it. A departure that
     * resolved instantly would let a player leave before either of them had
     * really agreed to it.
     */
    public static final long DEPARTURE_MS = 20_000L;

    public enum Result {
        /** Not the last night. It is built, it is real, and it will not work yet. */
        NOT_READY,
        /** Somebody is already in it. */
        IN_PROGRESS,
        /** It has been used. There was only ever one. */
        SPENT,
        /** The departure has begun. */
        BEGUN
    }

    private String occupant;
    private long startedAtMs = -1;
    private String departed;

    /**
     * Try to use it.
     *
     * <p>Only at {@link Dread.Tier#HUNT}. Earlier and it does nothing - which is
     * deliberately frustrating, because a group that finds it on night two and
     * cannot leave has learned that the exit exists and that they have to
     * survive to reach it. That is a much better night three.
     */
    public Result attempt(String playerId, Dread.Tier tier, long nowMs) {
        if (departed != null) return Result.SPENT;
        if (occupant != null) return Result.IN_PROGRESS;
        if (tier != Dread.Tier.HUNT) return Result.NOT_READY;

        occupant = playerId;
        startedAtMs = nowMs;
        return Result.BEGUN;
    }

    /** Whoever is currently in it, if anybody. */
    public Optional<String> occupant() {
        return Optional.ofNullable(occupant);
    }

    /** Whoever got out, if anybody has. */
    public Optional<String> departed() {
        return Optional.ofNullable(departed);
    }

    public boolean spent() {
        return departed != null;
    }

    public boolean inProgress() {
        return occupant != null && departed == null;
    }

    /** How far through the departure, 0..1. */
    public double progress(long nowMs) {
        if (!inProgress()) return 0.0;
        return Math.max(0.0, Math.min(1.0, (nowMs - startedAtMs) / (double) DEPARTURE_MS));
    }

    /**
     * Advance the departure.
     *
     * @return the id of whoever just left, if this call completed it
     */
    public Optional<String> tick(long nowMs) {
        if (!inProgress()) return Optional.empty();
        if (nowMs - startedAtMs < DEPARTURE_MS) return Optional.empty();

        departed = occupant;
        occupant = null;
        return Optional.of(departed);
    }

    /**
     * Interrupt a departure - the occupant died, disconnected, or stepped out.
     *
     * <p>The seat is NOT spent by an interrupted attempt. It has to remain
     * usable, because the alternative is a run that becomes unwinnable through
     * bad luck, and an unwinnable run is one the players stop taking seriously.
     */
    public void interrupt() {
        occupant = null;
        startedAtMs = -1;
    }
}
