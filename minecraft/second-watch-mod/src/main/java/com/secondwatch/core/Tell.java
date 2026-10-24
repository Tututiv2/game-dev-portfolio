package com.secondwatch.core;

/**
 * The giveaway. One of these is rolled per run, and learning which one you got
 * IS the gameplay - that is where replayability comes from, because you cannot
 * re-scare somebody with a scare they have already seen, but you can absolutely
 * re-scare them with a rule they do not know yet.
 *
 * <p>Note what is NOT in this list: "it can only repeat things you have already
 * said." That is not a tell, it is the law - it is true in every run without
 * exception, and it is the one thing players can always fall back on. The tells
 * below are the SECOND giveaway, the cheaper one, the one you can catch before
 * you have worked out the law.
 *
 * <p>Every tell here is detectable without speech-to-text. That is a hard
 * constraint: none of these require knowing what was said, only when, how loud,
 * from where, and how often.
 */
public enum Tell {

    /**
     * Its voice does not get quieter with distance. Walk away from it mid
     * sentence and it stays exactly as loud as it was.
     */
    FIXED_VOLUME(
            Kind.PRESENTATION,
            "it never gets quieter. walked 40 blocks off while it was talking and it was still right in my ear"),

    /**
     * It arrives in both ears equally. A real player is somewhere; this is
     * nowhere.
     */
    MONO(
            Kind.PRESENTATION,
            "close one ear. go on. its in both. everything real in this world is on one side or the other"),

    /**
     * No room tone, no breath, no shuffle. The silence between its words is
     * absolute in a way a live microphone never is.
     */
    NO_BREATH(
            Kind.PRESENTATION,
            "listen to the gaps not the words. when {other} stops talking you can still hear {other}. when IT stops there is nothing there at all"),

    /**
     * It cannot use anything said recently - only lines gone cold. So challenge
     * it with something you said thirty seconds ago and it has nothing.
     */
    STALE_ONLY(
            Kind.SELECTION,
            "it is always behind. it has never once said a thing from the same hour. ask about right now and it goes quiet"),

    /**
     * It never speaks first. It only ever answers. Say nothing for long enough
     * and the thing pretending to be your friend says nothing either, forever.
     */
    ONLY_REPLIES(
            Kind.PRESENTATION,
            "shut up and it shuts up. it has never once started a conversation. real people cannot help themselves"),

    /**
     * It will reuse a line it has already used this night. People almost never
     * repeat themselves word for word, tone for tone.
     */
    REPEATS_ITSELF(
            Kind.SELECTION,
            "it said the exact same sentence twice, same everything. nobody does that. nobody says a thing identically twice");

    public enum Kind {
        /** Constrains WHICH recorded lines the phantom may use. */
        SELECTION,
        /** Constrains HOW the phantom's voice is played back. */
        PRESENTATION
    }

    private final Kind kind;
    private final String journalHint;

    Tell(Kind kind, String journalHint) {
        this.kind = kind;
        this.journalHint = journalHint;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * How the previous group wrote it down. Deliberately lowercase and badly
     * punctuated - these were written by frightened people in the dark, not by
     * a tooltip.
     */
    public String journalHint() {
        return journalHint;
    }

    /** Lines younger than this are unavailable under {@link #STALE_ONLY}. */
    public static final long STALE_THRESHOLD_MS = 10 * 60 * 1000L;
}
