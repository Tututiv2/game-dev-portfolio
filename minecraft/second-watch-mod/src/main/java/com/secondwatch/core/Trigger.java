package com.secondwatch.core;

/**
 * What draws it. Rolled per run alongside the {@link Tell}.
 *
 * <p>The design rule every entry here obeys: the trigger must be something the
 * players cannot simply stop doing. A trigger you can abstain from is a puzzle
 * with a solution, and a solved horror game is a walking simulator. Each of
 * these is either how you survive the night, how you make progress, or how you
 * stay together - so obeying it costs you something you cannot afford.
 */
public enum Trigger {

    /**
     * Voice. The nastiest roll, because talking is also the only thing keeping
     * you together, AND every word feeds its vocabulary. On a TALKING run the
     * players work out that silence is safety, then discover silence is how you
     * get separated.
     */
    TALKING(
            "it comes to the noise. we stopped talking. then we lost each other, which i suppose was the point"),

    /**
     * Light. Torches keep the mobs off and mark your way home, and every one of
     * them is a flare.
     */
    LIGHT(
            "do not light it. i know. i KNOW. but it walks to the light every single time"),

    /**
     * Darkness. The inverse roll, and the kind one - until the coal runs out.
     */
    DARKNESS(
            "it will not come near a lit square. we have enough coal for maybe two more nights"),

    /**
     * Standing still. Punishes exactly the thing frightened people do, which is
     * stop and listen.
     */
    STILLNESS(
            "keep moving. the second you stop to work out where it is, it has already worked out where you are"),

    /**
     * Distance from your partner. The one that turns the split into a spiral -
     * being separated is what summons the thing that separates you.
     */
    SEPARATION(
            "it only ever came when we were apart. so we stayed together. so we got nothing done. so we starved"),

    /**
     * Breaking blocks. Mining is how you get everything, and it rings like a
     * bell.
     */
    DIGGING(
            "every time. every single time we broke stone. {other} thought it was the sound. i think it is the hole");

    private final String journalHint;

    Trigger(String journalHint) {
        this.journalHint = journalHint;
    }

    public String journalHint() {
        return journalHint;
    }
}
