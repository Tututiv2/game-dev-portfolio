package com.secondwatch.core;

/**
 * One page of the previous group's journal.
 *
 * <p>{@code truthful} is server-side bookkeeping and must never reach the
 * player. There is no icon, no colour, no tooltip and no sorting that separates
 * a good entry from a false lead - the whole device only works because the
 * players have to weigh a dead stranger's confidence against what they are
 * seeing themselves. {@code JournalTest} asserts that the rendered text of a
 * false entry is indistinguishable from a true one.
 *
 * @param author   which of the previous group wrote it
 * @param night    the night they wrote it
 * @param text     what the players read
 * @param truthful whether it is actually correct about this run
 * @param kind     what it is about
 */
public record JournalEntry(
        String author,
        int night,
        String text,
        boolean truthful,
        Kind kind) {

    public enum Kind {
        /** Ordinary. Establishes that these were people having a normal week. */
        MUNDANE,
        /** The law: it can only repeat things you have already said. */
        LAW,
        /** This run's tell - or, if false, somebody else's. */
        TELL,
        /** This run's trigger - or, if false, somebody else's. */
        TRIGGER,
        /** It used a name that had not arrived yet. */
        PRESCIENT,
        /** It fits one. */
        SEAT
    }

    /** What the player sees. Carries no signal about {@link #truthful}. */
    public String rendered() {
        return "[night " + night + " - " + author + "]\n" + text;
    }
}
