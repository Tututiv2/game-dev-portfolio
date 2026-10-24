package com.secondwatch.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tests that hold the whole design up. If any of these go red the mod is
 * not scary any more, it is just broken.
 */
class PhantomDirectorTest {

    private static final String A = "tutut";
    private static final String B = "friend";

    /** A corpus with plenty of long, fully armed lines from both players. */
    private static VoiceCorpus stockedCorpus(long nowMs) {
        VoiceCorpus c = new VoiceCorpus();
        for (int i = 0; i < 12; i++) {
            long start = i * 20_000L;
            c.record(A, start, 2_000L, i % 2 == 0, "a" + i);
            c.record(B, start + 5_000L, 2_400L, i % 3 == 0, "b" + i);
        }
        return c;
    }

    private static Dread dreadAt(RunRules rules, double v) {
        Dread d = new Dread(rules);
        d.feed(v);
        return d;
    }

    private static List<PlayerState> apart(long lastSpokeMs) {
        return List.of(
                new PlayerState(A, 0, 0, 7, lastSpokeMs, false, true),
                new PlayerState(B, 300, 300, 7, lastSpokeMs, false, true));
    }

    // ---------------------------------------------------------------- the law

    /**
     * THE LAW. The phantom may only ever hand back audio that a real player
     * really produced. This is the rule players fall back on when everything
     * else has failed them, so nothing may ever weaken it.
     */
    @Test
    void phantomNeverSaysAnythingNew() {
        for (long seed = 0; seed < 200; seed++) {
            RunRules rules = RunRules.roll(seed);
            long now = 2_000_000L;
            VoiceCorpus corpus = stockedCorpus(now);
            PhantomDirector director =
                    new PhantomDirector(rules, corpus, new Rng(seed).fork("phantom"));

            long t = now;
            for (int step = 0; step < 40; step++) {
                t += PhantomDirector.COOLDOWN_MS;
                Optional<PhantomPlan> plan =
                        director.consider(apart(t - 1_000L), dreadAt(rules, 0.8), t);
                if (plan.isEmpty() || !plan.get().speaks()) continue;

                Utterance said = plan.get().utterance().orElseThrow();
                assertTrue(corpus.contains(said),
                        "seed " + seed + ": phantom produced audio that is not in the corpus");
                assertEquals(plan.get().wearingId(), said.speakerId(),
                        "seed " + seed + ": phantom spoke in the wrong person's voice");
            }
        }
    }

    /** It wears the other player. Never the person watching it. */
    @Test
    void phantomNeverWearsItsOwnVictim() {
        RunRules rules = RunRules.roll(7);
        long now = 2_000_000L;
        PhantomDirector d = new PhantomDirector(rules, stockedCorpus(now), new Rng(7));

        long t = now;
        for (int i = 0; i < 30; i++) {
            t += PhantomDirector.COOLDOWN_MS;
            d.consider(apart(t - 1_000L), dreadAt(rules, 0.8), t)
                    .ifPresent(p -> assertNotEquals(p.victimId(), p.wearingId()));
        }
    }

    // -------------------------------------------------------------- the thesis

    /**
     * The thesis, compiled. With one observer nothing can be established as
     * false, so this system has no meaning - and it must say so loudly rather
     * than quietly doing something reasonable.
     */
    @Test
    void isUndefinedBelowTwoPlayers() {
        RunRules rules = RunRules.roll(1);
        PhantomDirector d = new PhantomDirector(rules, stockedCorpus(2_000_000L), new Rng(1));

        List<PlayerState> alone = List.of(new PlayerState(A, 0, 0, 7, 0, false, true));
        assertThrows(IllegalStateException.class,
                () -> d.consider(alone, dreadAt(rules, 0.8), 2_000_000L));

        List<PlayerState> oneWrittenOut = List.of(
                new PlayerState(A, 0, 0, 7, 0, false, true),
                new PlayerState(B, 10, 10, 7, 0, false, false));
        assertThrows(IllegalStateException.class,
                () -> d.consider(oneWrittenOut, dreadAt(rules, 0.8), 2_000_000L),
                "a written-out player is not an observer");
    }

    // ---------------------------------------------------------------- gating

    @Test
    void noPhantomBeforeSocialTier() {
        RunRules rules = RunRules.roll(3);
        PhantomDirector d = new PhantomDirector(rules, stockedCorpus(2_000_000L), new Rng(3));
        assertTrue(d.consider(apart(1_999_000L), dreadAt(rules, 0.10), 2_000_000L).isEmpty());
        assertTrue(d.consider(apart(1_999_000L), dreadAt(rules, 0.30), 2_000_000L).isEmpty());
    }

    /** Showing you a copy of somebody you are looking straight at is a bug report. */
    @Test
    void neverTargetsAPlayerWhoCanSeeTheirPartner() {
        RunRules rules = RunRules.roll(11);
        PhantomDirector d = new PhantomDirector(rules, stockedCorpus(2_000_000L), new Rng(11));

        List<PlayerState> together = List.of(
                new PlayerState(A, 0, 0, 7, 1_999_000L, true, true),
                new PlayerState(B, 4, 0, 7, 1_999_000L, true, true));

        assertTrue(d.consider(together, dreadAt(rules, 0.8), 2_000_000L).isEmpty());
    }

    @Test
    void respectsCooldown() {
        RunRules rules = RunRules.roll(5);
        PhantomDirector d = new PhantomDirector(rules, stockedCorpus(2_000_000L), new Rng(5));
        long t = 2_000_000L;

        assertTrue(d.consider(apart(t - 1_000L), dreadAt(rules, 0.8), t).isPresent());
        assertTrue(d.consider(apart(t - 1_000L), dreadAt(rules, 0.8), t + 1_000L).isEmpty());
        assertTrue(d.consider(apart(t - 1_000L), dreadAt(rules, 0.8),
                t + PhantomDirector.COOLDOWN_MS).isPresent());
    }

    // ----------------------------------------------------------------- tells

    /**
     * On an ONLY_REPLIES run it genuinely cannot speak first, so a silent pair
     * can starve it - and then find out what silence costs them.
     */
    @Test
    void onlyRepliesRunCannotSpeakFirst() {
        RunRules base = RunRules.roll(0);
        RunRules rules = new RunRules(0, Tell.ONLY_REPLIES, base.trigger(),
                base.nights(), base.groupSize(), base.falseLeads());

        long now = 2_000_000L;
        PhantomDirector d = new PhantomDirector(rules, stockedCorpus(now), new Rng(42));

        // Nobody has spoken for an hour.
        List<PlayerState> silent = List.of(
                new PlayerState(A, 0, 0, 7, now - 3_600_000L, false, true),
                new PlayerState(B, 300, 300, 7, now - 3_600_000L, false, true));

        long t = now;
        for (int i = 0; i < 20; i++) {
            t += PhantomDirector.COOLDOWN_MS;
            Optional<PhantomPlan> p = d.consider(silent, dreadAt(rules, 0.8), t);
            assertTrue(p.isEmpty() || !p.get().speaks(),
                    "an ONLY_REPLIES phantom spoke into silence");
        }
    }

    /** Presentation flags must match the rolled tell, and only that tell. */
    @Test
    void presentationFlagsFollowTheRolledTell() {
        long now = 2_000_000L;
        for (Tell tell : Tell.values()) {
            RunRules base = RunRules.roll(9);
            RunRules rules = new RunRules(9, tell, base.trigger(),
                    base.nights(), base.groupSize(), base.falseLeads());
            PhantomDirector d = new PhantomDirector(rules, stockedCorpus(now), new Rng(9));

            Optional<PhantomPlan> p = d.consider(apart(now - 1_000L), dreadAt(rules, 0.8), now);
            assertTrue(p.isPresent(), "expected a plan for tell " + tell);

            assertEquals(tell == Tell.FIXED_VOLUME, p.get().fixedVolume());
            assertEquals(tell == Tell.MONO, p.get().mono());
            assertEquals(tell == Tell.NO_BREATH, p.get().stripRoomTone());
        }
    }

    // ----------------------------------------------------------------- luring

    /** A lure always widens the gap. Never narrows it. */
    @Test
    void luringAlwaysLeadsAwayFromThePartner() {
        RunRules rules = RunRules.roll(21);
        long now = 2_000_000L;
        PhantomDirector d = new PhantomDirector(rules, stockedCorpus(now), new Rng(21));

        long t = now;
        for (int i = 0; i < 25; i++) {
            t += PhantomDirector.COOLDOWN_MS;
            List<PlayerState> ps = apart(t - 1_000L);
            Optional<PhantomPlan> plan = d.consider(ps, dreadAt(rules, 0.99), t);
            if (plan.isEmpty() || !plan.get().luring()) continue;

            PlayerState victim = ps.stream()
                    .filter(p -> p.id().equals(plan.get().victimId())).findFirst().orElseThrow();
            PlayerState partner = ps.stream()
                    .filter(p -> !p.id().equals(plan.get().victimId())).findFirst().orElseThrow();

            double before = victim.distanceTo(partner);
            double dx = plan.get().headingX() - partner.x();
            double dz = plan.get().headingZ() - partner.z();
            double after = Math.sqrt(dx * dx + dz * dz);

            assertTrue(after > before,
                    "a lure moved the victim closer to their partner: " + before + " -> " + after);
        }
    }
}
