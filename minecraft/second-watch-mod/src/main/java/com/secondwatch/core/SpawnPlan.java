package com.secondwatch.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What the world is allowed to contain, by tier.
 *
 * <p>The escalation the players actually feel is not "harder monsters", it is
 * <b>a change in what kind of thing is happening</b>:
 *
 * <ol>
 *   <li>DENIABLE - nothing. Not one spawn. This is the control sample and the
 *       most important ten minutes in the mod.</li>
 *   <li>ARGUABLE - a Follower. One. It does nothing, and the players will spend
 *       twenty minutes arguing about whether it moved.</li>
 *   <li>SOCIAL - Watchers and the first Mimic. The world starts using their own
 *       voices, which is when the journal's law stops being trivia.</li>
 *   <li>AUTHORED - the Crawler arrives, and for the first time something can
 *       kill them. Light stops being comfort and becomes budget.</li>
 *   <li>HUNT - the Tall One, and it does not care about their torches.</li>
 * </ol>
 *
 * <p>Pure and capped, so the whole escalation curve can be inspected in a test
 * rather than discovered on night four of a recording.
 */
public final class SpawnPlan {

    /** The bestiary, named so the core need not know about Minecraft classes. */
    public enum Kind {
        FOLLOWER,
        WATCHER,
        MIMIC,
        SWARM,
        CRAWLER,
        TALL_ONE
    }

    /**
     * Population ceilings per tier.
     *
     * <p>Ceilings rather than rates, because the failure mode of a spawner is
     * always the same: it quietly keeps working while the players are busy, and
     * they turn around into forty of something. A cap makes the worst case
     * something a person chose.
     */
    public static Map<Kind, Integer> capsFor(Dread.Tier tier) {
        Map<Kind, Integer> caps = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) caps.put(k, 0);

        switch (tier) {
            case DENIABLE -> {
                // Nothing. Deliberately, completely nothing.
            }
            case ARGUABLE -> {
                caps.put(Kind.FOLLOWER, 1);
            }
            case SOCIAL -> {
                caps.put(Kind.FOLLOWER, 3);
                caps.put(Kind.WATCHER, 2);
                caps.put(Kind.MIMIC, 1);
                caps.put(Kind.SWARM, 4);
            }
            case AUTHORED -> {
                caps.put(Kind.FOLLOWER, 6);
                caps.put(Kind.WATCHER, 3);
                caps.put(Kind.MIMIC, 2);
                caps.put(Kind.SWARM, 8);
                caps.put(Kind.CRAWLER, 3);
            }
            case HUNT -> {
                caps.put(Kind.FOLLOWER, 11);
                caps.put(Kind.WATCHER, 5);
                caps.put(Kind.MIMIC, 3);
                caps.put(Kind.SWARM, 12);
                caps.put(Kind.CRAWLER, 6);
                caps.put(Kind.TALL_ONE, 1);
            }
        }
        return caps;
    }

    /** True once this kind can hurt somebody. Used to keep the ratio honest. */
    public static boolean lethal(Kind kind) {
        return kind == Kind.CRAWLER || kind == Kind.TALL_ONE;
    }

    /**
     * What to spawn right now, given what is already alive.
     *
     * <p>At most one thing per call. Trickling is the whole point: eleven
     * Followers that arrived one at a time over an hour is a slow horror, and
     * eleven that arrive together is a mob.
     */
    public static List<Kind> next(Dread.Tier tier, Map<Kind, Integer> alive, Rng rng) {
        Map<Kind, Integer> caps = capsFor(tier);

        List<Kind> room = new ArrayList<>();
        for (Kind k : Kind.values()) {
            int have = alive.getOrDefault(k, 0);
            if (have < caps.getOrDefault(k, 0)) room.add(k);
        }
        if (room.isEmpty()) return List.of();

        return List.of(rng.pick(room));
    }

    private SpawnPlan() {
    }
}
