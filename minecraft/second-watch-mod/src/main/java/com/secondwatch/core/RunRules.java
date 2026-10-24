package com.secondwatch.core;

import java.util.List;

/**
 * Everything that varies between runs, rolled once from the world seed.
 *
 * <p>This is where replayability actually lives. The scares do not survive
 * repetition - nothing does - so the thing that changes between runs is the
 * <em>rule set</em>, and the gameplay is working out which rules you got before
 * they work out where you are. Phasmophobia and Lethal Company both run on this
 * and it is the only mechanism known to make horror replayable.
 *
 * @param seed        the run seed; everything else is derived from it
 * @param tell        the giveaway that reveals a phantom this run
 * @param trigger     what draws it this run
 * @param nights      how many nights before it stops pretending
 * @param groupSize   how many were here before you
 * @param falseLeads  how many journal entries are confidently wrong
 */
public record RunRules(
        long seed,
        Tell tell,
        Trigger trigger,
        int nights,
        int groupSize,
        int falseLeads) {

    public RunRules {
        if (nights < 4) throw new IllegalArgumentException("a run needs at least four nights");
        if (groupSize < 1) throw new IllegalArgumentException("somebody was here before you");
        if (falseLeads < 0) throw new IllegalArgumentException("falseLeads cannot be negative");
        if (falseLeads >= groupSize + 2) {
            // The journal must contain more truth than error or it is noise
            // rather than a trap, and a trap the player cannot ever trust is
            // just a document they stop reading.
            throw new IllegalArgumentException("too many false leads to leave the journal usable");
        }
    }

    /** Roll a run from a world seed. Deterministic: same seed, same run. */
    public static RunRules roll(long seed) {
        Rng rng = new Rng(seed).fork("rules");

        Tell tell = rng.pick(Tell.values());
        Trigger trigger = rng.pick(Trigger.values());
        // Minimum four: a three-night run cannot fit an ordinary opening, a
        // night that teaches the law, a night with teeth AND an ending.
        int nights = rng.range(4, 6);
        int groupSize = rng.range(3, 6);

        // Usually two wrong entries, sometimes only one. Never zero: the whole
        // point of the journal is that it was written by people who were
        // guessing, and a document that is never wrong is a quest marker.
        int falseLeads = rng.chance(0.65) ? 2 : 1;

        return new RunRules(seed, tell, trigger, nights, groupSize, falseLeads);
    }

    /**
     * The night the entity stops imitating and starts hunting. Always the last
     * one - the escalation has to land somewhere the players can see coming,
     * because dread you cannot anticipate is just surprise.
     */
    public int lastNight() {
        return nights;
    }

    /** A tell that is not this run's tell, for generating a plausible false lead. */
    public Tell decoyTell(Rng rng) {
        List<Tell> others = new java.util.ArrayList<>(List.of(Tell.values()));
        others.remove(tell);
        return rng.pick(others);
    }

    /** A trigger that is not this run's trigger. */
    public Trigger decoyTrigger(Rng rng) {
        List<Trigger> others = new java.util.ArrayList<>(List.of(Trigger.values()));
        others.remove(trigger);
        return rng.pick(others);
    }
}
