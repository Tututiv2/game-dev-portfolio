package com.secondwatch.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The group that was here before you, generated per run.
 *
 * <p>Exactly one of them takes the seat and gets out, and the rest do not. That
 * asymmetry is not decoration - it is the ending being demonstrated to the
 * players before they reach it, by people who are not around to discuss it. By
 * the time the players find the seat and count how many it fits, they have
 * already read what happened the last time somebody had to choose.
 */
public final class PreviousGroup {

    /**
     * Usernames that read as real people rather than as content. Mixed casing,
     * numbers, abbreviations, a couple of names that are clearly somebody's
     * actual first name - the texture of a real friend group's server list.
     */
    private static final String[] NAME_POOL = {
            "hollis", "TAM_", "bexley", "milo_04", "SPOONKING", "arch",
            "rennie", "d0ve", "Kestrel_", "gormless", "PIP", "sable9",
            "wren", "OTTO", "quill_", "mardy", "Fennick", "bramble",
            "nix", "HALFPINT", "corvid", "sunny_j", "TALLBOY", "ives"
    };

    /** Exposed so the test suite can assert no entry names a stranger. */
    public static List<String> namePool() {
        return List.of(NAME_POOL);
    }

    private final List<Survivor> members;

    private PreviousGroup(List<Survivor> members) {
        this.members = List.copyOf(members);
    }

    public List<Survivor> members() {
        return members;
    }

    public Survivor whoGotOut() {
        for (Survivor s : members) {
            if (s.gotOut()) return s;
        }
        throw new IllegalStateException("every group has exactly one who got out");
    }

    public List<Survivor> whoDidNot() {
        List<Survivor> out = new ArrayList<>();
        for (Survivor s : members) {
            if (!s.gotOut()) out.add(s);
        }
        return out;
    }

    /**
     * The one who kept the journal - the last to be lost, so their entries run
     * furthest into the dark. Not the one who got out: the survivor took the
     * seat and their notes with them, which is why the book you find stops
     * mid-thought.
     */
    public Survivor diarist() {
        Survivor latest = null;
        for (Survivor s : members) {
            if (s.gotOut()) continue;
            if (latest == null || s.nightLost() > latest.nightLost()) latest = s;
        }
        if (latest == null) throw new IllegalStateException("somebody has to have stayed");
        return latest;
    }

    public static PreviousGroup generate(RunRules rules, Rng seedRng) {
        Rng rng = seedRng.fork("group");

        List<String> pool = new ArrayList<>(List.of(NAME_POOL));
        rng.shuffle(pool);

        int size = rules.groupSize();
        List<String> names = new ArrayList<>(pool.subList(0, size));

        // Exactly one gets out, and it is never the diarist.
        int escapee = rng.nextInt(size);

        Survivor.Fate[] fates = {
                Survivor.Fate.FOLLOWED_IT,
                Survivor.Fate.FOLLOWED_IT,   // weighted: this is the run's own lesson
                Survivor.Fate.WENT_BACK,
                Survivor.Fate.DARK,
                Survivor.Fate.LEFT_BEHIND,
                Survivor.Fate.UNKNOWN
        };

        List<Survivor> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (i == escapee) {
                members.add(new Survivor(names.get(i), Survivor.Fate.TOOK_THE_SEAT, 0));
            } else {
                Survivor.Fate fate = rng.pick(fates);
                int night = rng.range(1, rules.nights());
                members.add(new Survivor(names.get(i), fate, night));
            }
        }

        // Somebody has to have lasted to the final night, and it has to be the
        // same person every time: the diarist. Without this the group can roll
        // all-night-one deaths and the journal has nothing worth finding in it -
        // and worse, the last page can end up signed by somebody whose body the
        // players already found two nights earlier.
        int latest = -1;
        for (int i = 0; i < members.size(); i++) {
            Survivor s = members.get(i);
            if (s.gotOut()) continue;
            if (latest < 0 || s.nightLost() > members.get(latest).nightLost()) latest = i;
        }
        Survivor last = members.get(latest);
        members.set(latest, new Survivor(last.name(), last.fate(), rules.nights()));

        return new PreviousGroup(members);
    }
}
