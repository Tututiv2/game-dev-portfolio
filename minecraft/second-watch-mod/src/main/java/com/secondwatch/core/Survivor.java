package com.secondwatch.core;

/**
 * One of the people who were here before you.
 *
 * <p>They are not lore. They are the tutorial - everything the players learn
 * about this run's rules, they learn from someone who worked it out too late,
 * and the emotional weight of a hint is completely different when it is signed.
 * "It comes to the light" is a tooltip. "do not light it. i know. i KNOW. but it
 * walks to the light every single time" is a person.
 *
 * @param name      their username
 * @param fate      how they ended
 * @param nightLost which night they were lost on, or 0 if they got out
 */
public record Survivor(String name, Fate fate, int nightLost) {

    public enum Fate {
        /** Followed a voice. The most common, and the one the players will repeat. */
        FOLLOWED_IT("followed it"),
        /** Went back for somebody who was already gone. */
        WENT_BACK("went back"),
        /** Ran out of light with too far to go. */
        DARK("ran out of light"),
        /** The others left. Sometimes that is the honest word for it. */
        LEFT_BEHIND("left behind"),
        /** Took the seat. There is only ever one. */
        TOOK_THE_SEAT("got out"),
        /** Nobody wrote it down. */
        UNKNOWN("we do not know");

        private final String phrase;

        Fate(String phrase) {
            this.phrase = phrase;
        }

        public String phrase() {
            return phrase;
        }
    }

    public boolean gotOut() {
        return fate == Fate.TOOK_THE_SEAT;
    }
}
