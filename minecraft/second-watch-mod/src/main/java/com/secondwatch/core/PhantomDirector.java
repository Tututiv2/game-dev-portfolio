package com.secondwatch.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides when the world produces a second version of somebody, and what it is
 * allowed to say.
 *
 * <p>The rule this class exists to enforce, above everything else: <b>the
 * phantom is boring.</b> It walks, it waits at doors, it mines, and it says
 * things your friend genuinely said. A phantom that behaves strangely gets
 * caught in four seconds and the run is over; a phantom that behaves normally
 * gets followed for two minutes, and the horror is not the moment it appears -
 * it is the ordinary conversation that happens afterwards, and the moment
 * somebody says "wait, say that again, where are you?"
 *
 * <p>Note that {@link #consider} throws when handed fewer than two players. That
 * is not defensive programming, it is the thesis compiled: with one observer
 * nothing can ever be established as false, so this system has no meaning alone.
 * It does not degrade gracefully to single player because it must not.
 */
public final class PhantomDirector {

    /** Minimum gap between fabrications. Scarcity is most of what sells it. */
    public static final long COOLDOWN_MS = 45_000L;

    /** How recently the victim must have spoken for an ONLY_REPLIES run to answer. */
    public static final long REPLY_WINDOW_MS = 4_000L;

    /** How far a lure walks a victim per leg. */
    public static final double LURE_DISTANCE = 24.0;

    /**
     * How hard lures bend toward the Quiet.
     *
     * <p>Deliberately gentle. The Understudy is herding, not marching: any single
     * lure has to look like your friend wandering off, and only the shape of a
     * whole night should reveal that everything has been trending one way. Turn
     * this up and players notice the pattern in ten minutes.
     */
    public static final double HERD_BIAS = 0.55;

    private final RunRules rules;
    private final VoiceCorpus corpus;
    private final Rng rng;

    /**
     * Time of the last fabrication, or {@link #NEVER}.
     *
     * <p>Do not "simplify" this to a Long.MIN_VALUE sentinel compared with
     * {@code nowMs - lastEventMs}: that subtraction overflows, the difference
     * comes out negative, the cooldown reads as permanently active and the
     * phantom never fires for the whole session. It presents in game as the mod
     * silently doing nothing, which is close to undiagnosable live. Caught by
     * {@code PhantomDirectorTest#respectsCooldown}.
     */
    private static final long NEVER = Long.MIN_VALUE;

    private long lastEventMs = NEVER;
    private int eventCount;

    /**
     * The thing the Understudy is walking everybody toward and will not itself
     * approach. Null until the Quiet exists in this run.
     */
    private Quiet quiet;

    public void setQuiet(Quiet quiet) {
        this.quiet = quiet;
    }

    public PhantomDirector(RunRules rules, VoiceCorpus corpus, Rng rng) {
        this.rules = rules;
        this.corpus = corpus;
        this.rng = rng;
    }

    public int eventCount() {
        return eventCount;
    }

    /**
     * Consider producing a fabrication right now.
     *
     * @throws IllegalStateException if handed fewer than two live players
     */
    public Optional<PhantomPlan> consider(List<PlayerState> players, Dread dread, long nowMs) {
        List<PlayerState> live = new ArrayList<>();
        for (PlayerState p : players) {
            if (p.alive()) live.add(p);
        }
        if (live.size() < 2) {
            throw new IllegalStateException(
                    "the phantom system is undefined below two players - there is nobody to be "
                            + "wrong about, and nobody to disagree with. got " + live.size());
        }

        if (!dread.tier().allowsPhantomBody()) return Optional.empty();
        if (lastEventMs != NEVER && nowMs - lastEventMs < COOLDOWN_MS) return Optional.empty();

        // The illusion only holds against somebody who cannot currently see the
        // real person. Showing you a second copy of a friend you are looking
        // straight at is not a scare, it is a bug report.
        List<PlayerState> candidates = new ArrayList<>();
        for (PlayerState p : live) {
            if (!p.seesPartner()) candidates.add(p);
        }
        if (candidates.isEmpty()) return Optional.empty();

        PlayerState victim = rng.pick(candidates);
        PlayerState partner = otherThan(live, victim);
        if (partner == null) return Optional.empty();

        // The Understudy will not come near the Quiet. This is the entire
        // foreshadowing budget for the boss and it is spent here: for five
        // nights the phantom is everywhere except one region, the previous
        // group wrote down that it never comes down there, and that entry is
        // TRUE - which is exactly why they went, and why the players will.
        //
        // It also produces the inversion the fight is built on. Inside the
        // field there is no phantom and no voice, and players have spent the
        // whole run learning that a voice cutting out means the phantom is
        // doing something. It means the opposite.
        if (quiet != null && quiet.distanceTo(victim.x(), victim.z()) < quiet.avoidRadius()) {
            return Optional.empty();
        }

        Optional<Utterance> line = chooseLine(victim, partner, nowMs);

        // A silent phantom is still worth producing - a figure that walks off
        // ahead of you and says nothing is how most of these start. But at
        // SOCIAL tier with an empty corpus there is nothing to sell it with, so
        // do not burn the cooldown on it.
        if (line.isEmpty() && !dread.tier().allowsLuring()) return Optional.empty();

        boolean luring = dread.tier().allowsLuring();
        double hx = victim.x();
        double hz = victim.z();
        if (luring) {
            // Lead them along the vector pointing away from their partner, so
            // every step widens the gap that the partner is also, right now,
            // being widened along.
            double dx = victim.x() - partner.x();
            double dz = victim.z() - partner.z();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) {
                // Standing on top of each other: pick a deterministic direction.
                double a = rng.nextDouble() * Math.PI * 2;
                dx = Math.cos(a);
                dz = Math.sin(a);
                len = 1.0;
            }

            // Baseline: straight away from the partner. Every lure must widen
            // the gap - that invariant is older than the boss and outranks it.
            double ax = dx / len;
            double az = dz / len;
            double cx = ax;
            double cz = az;

            if (quiet != null) {
                double qx = quiet.x() - victim.x();
                double qz = quiet.z() - victim.z();
                double qlen = Math.sqrt(qx * qx + qz * qz);
                if (qlen > 1e-6) {
                    double bx = ax + (qx / qlen) * HERD_BIAS;
                    double bz = az + (qz / qlen) * HERD_BIAS;
                    double blen = Math.sqrt(bx * bx + bz * bz);
                    if (blen > 1e-9) {
                        double nx = bx / blen;
                        double nz = bz / blen;
                        // Only accept the bend if it still points away from the
                        // partner. When the Quiet lies back past the partner the
                        // herd loses and the split wins, because a "lure" that
                        // walks you back to your friend is not a lure.
                        if (nx * ax + nz * az > 0.05) {
                            cx = nx;
                            cz = nz;
                        }
                    }
                }
            }

            double reach = LURE_DISTANCE;
            if (quiet != null) {
                // Walk them to the edge of the silence and no further. It stops
                // at the line every time, which is what makes the line visible
                // in hindsight.
                while (reach > 0
                        && quiet.distanceTo(victim.x() + cx * reach, victim.z() + cz * reach)
                                < quiet.avoidRadius()) {
                    reach -= 0.5;
                }
            }

            if (reach <= 0.5) {
                // Nowhere left to lead them that is not inside the silence, so
                // it does not lead them anywhere. It just stands there.
                luring = false;
            } else {
                hx = victim.x() + cx * reach;
                hz = victim.z() + cz * reach;
            }
        }

        PhantomPlan plan = new PhantomPlan(
                victim.id(),
                partner.id(),
                line,
                rules.tell() == Tell.FIXED_VOLUME,
                rules.tell() == Tell.MONO,
                rules.tell() == Tell.NO_BREATH,
                luring,
                hx,
                hz);

        line.ifPresent(corpus::markUsed);
        lastEventMs = nowMs;
        eventCount++;
        return Optional.of(plan);
    }

    /**
     * Pick a line for the phantom to speak, or nothing.
     *
     * <p>Everything returned here came out of {@link VoiceCorpus#eligible}, which
     * is the only door. There is no branch in this method that can produce audio
     * from anywhere else, and there must never be one.
     */
    private Optional<Utterance> chooseLine(PlayerState victim, PlayerState partner, long nowMs) {
        if (rules.tell() == Tell.ONLY_REPLIES && !victim.spokeWithin(nowMs, REPLY_WINDOW_MS)) {
            // On an ONLY_REPLIES run it genuinely cannot speak first. Players who
            // work this out can starve it by shutting up, and then discover what
            // shutting up costs them.
            return Optional.empty();
        }

        List<Utterance> pool = corpus.eligible(partner.id(), rules, nowMs);
        if (pool.isEmpty()) return Optional.empty();

        if (rules.tell() == Tell.REPEATS_ITSELF) {
            // Bias hard toward a line it has already used, because the reuse IS
            // the giveaway and a giveaway nobody ever observes is not a tell.
            List<Utterance> repeats = new ArrayList<>();
            for (Utterance u : pool) {
                if (corpus.wasUsedByPhantom(u)) repeats.add(u);
            }
            if (!repeats.isEmpty() && rng.chance(0.6)) {
                return Optional.of(rng.pick(repeats));
            }
        }

        return Optional.of(rng.pick(pool));
    }

    private static PlayerState otherThan(List<PlayerState> live, PlayerState p) {
        for (PlayerState q : live) {
            if (!q.id().equals(p.id())) return q;
        }
        return null;
    }
}
