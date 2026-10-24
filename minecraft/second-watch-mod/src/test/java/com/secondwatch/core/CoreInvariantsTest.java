package com.secondwatch.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoreInvariantsTest {

    private static final String A = "tutut";
    private static final String B = "friend";

    // ==================================================================== corpus

    @Nested
    class Corpus {

        /**
         * The passphrase fuse. You invent a challenge word, you say it out loud,
         * and for exactly ARMING_DELAY_MS it is still yours. Then it belongs to
         * the thing as much as to you.
         */
        @Test
        void aFreshLineIsNotYetAvailableToThePhantom() {
            RunRules rules = RunRules.roll(1);
            VoiceCorpus c = new VoiceCorpus();
            Utterance u = c.record(B, 0, 2_000L, false, "ref");

            long justAfter = u.endMs() + 1_000L;
            assertTrue(c.eligible(B, rules, justAfter).isEmpty(),
                    "a line said one second ago must not be replayable");

            long armed = u.endMs() + VoiceCorpus.ARMING_DELAY_MS + 1;
            if (rules.tell() != Tell.STALE_ONLY) {
                assertEquals(List.of(u), c.eligible(B, rules, armed));
            }
        }

        /** Grunts and half-words read as a glitch, and a glitch breaks the spell. */
        @Test
        void tooShortToUse() {
            RunRules rules = RunRules.roll(2);
            VoiceCorpus c = new VoiceCorpus();
            c.record(B, 0, Utterance.MIN_USABLE_MS - 1, false, "ref");
            assertTrue(c.eligible(B, rules, 10_000_000L).isEmpty());
        }

        /**
         * On a STALE_ONLY run the journal's advice actually works: something you
         * said in the last ten minutes is a safe challenge.
         */
        @Test
        void staleOnlyRunsCannotUseRecentLines() {
            RunRules base = RunRules.roll(3);
            RunRules rules = new RunRules(3, Tell.STALE_ONLY, base.trigger(),
                    base.nights(), base.groupSize(), base.falseLeads());

            VoiceCorpus c = new VoiceCorpus();
            Utterance recent = c.record(B, 0, 2_000L, false, "recent");
            long now = recent.endMs() + Tell.STALE_THRESHOLD_MS - 1;
            assertTrue(c.eligible(B, rules, now).isEmpty());

            long later = recent.endMs() + Tell.STALE_THRESHOLD_MS + 1;
            assertEquals(List.of(recent), c.eligible(B, rules, later));
        }

        @Test
        void spentLinesAreNotReusedUnlessTheRunIsRepeatsItself() {
            RunRules base = RunRules.roll(4);
            long now = 10_000_000L;

            RunRules normal = new RunRules(4, Tell.MONO, base.trigger(),
                    base.nights(), base.groupSize(), base.falseLeads());
            VoiceCorpus c1 = new VoiceCorpus();
            Utterance u1 = c1.record(B, 0, 2_000L, false, "x");
            c1.markUsed(u1);
            assertTrue(c1.eligible(B, normal, now).isEmpty());

            RunRules repeats = new RunRules(4, Tell.REPEATS_ITSELF, base.trigger(),
                    base.nights(), base.groupSize(), base.falseLeads());
            VoiceCorpus c2 = new VoiceCorpus();
            Utterance u2 = c2.record(B, 0, 2_000L, false, "x");
            c2.markUsed(u2);
            assertEquals(List.of(u2), c2.eligible(B, repeats, now),
                    "REPEATS_ITSELF must be able to repeat, or the tell can never be observed");
        }

        @Test
        void neverLeaksAnotherPlayersVoice() {
            RunRules rules = RunRules.roll(5);
            VoiceCorpus c = new VoiceCorpus();
            c.record(A, 0, 2_000L, false, "mine");
            assertTrue(c.eligible(B, rules, 10_000_000L).isEmpty());
        }
    }

    // ===================================================================== dread

    @Nested
    class DreadClock {

        /** Doing well buys time, never ground. */
        @Test
        void neverDecreases() {
            RunRules rules = RunRules.roll(6);
            Dread d = new Dread(rules);
            double last = d.value();

            for (int night = 1; night <= rules.nights(); night++) {
                for (int i = 0; i < 20; i++) {
                    d.feed(0.01);
                    assertTrue(d.value() >= last, "dread went down");
                    last = d.value();
                }
                d.advanceNight();
                assertTrue(d.value() >= last, "dread went down across a night boundary");
                last = d.value();
            }
        }

        /** A negative feed is the "relief" this class exists to forbid. Loudly. */
        @Test
        void rejectsNegativeFeed() {
            Dread d = new Dread(RunRules.roll(7));
            assertThrows(IllegalArgumentException.class, () -> d.feed(-0.01));
        }

        /** A group that plays perfectly still arrives at the ending. */
        @Test
        void theLastNightForcesTheEndgameEvenIfNothingWasFed() {
            RunRules rules = RunRules.roll(8);
            Dread d = new Dread(rules);
            for (int i = 1; i < rules.nights(); i++) d.advanceNight();

            assertTrue(d.isLastNight());
            assertTrue(d.tier().ordinal() >= Dread.Tier.AUTHORED.ordinal(),
                    "surviving well must not opt out of the arc, got " + d.tier());
        }

        @Test
        void tierOrderingGatesCapabilities() {
            assertFalse(Dread.Tier.DENIABLE.allowsPhantomBody());
            assertFalse(Dread.Tier.ARGUABLE.allowsPhantomBody());
            assertTrue(Dread.Tier.SOCIAL.allowsPhantomBody());
            assertFalse(Dread.Tier.SOCIAL.allowsLuring());
            assertTrue(Dread.Tier.AUTHORED.allowsLuring());
            assertTrue(Dread.Tier.HUNT.allowsLuring());
        }
    }

    // =============================================================== determinism

    @Nested
    class Determinism {

        /** Same seed, same run - so a session that went wrong can be replayed. */
        @Test
        void sameSeedProducesTheSameRun() {
            for (long seed = 0; seed < 50; seed++) {
                RunRules a = RunRules.roll(seed);
                RunRules b = RunRules.roll(seed);
                assertEquals(a, b);

                PreviousGroup ga = PreviousGroup.generate(a, new Rng(seed));
                PreviousGroup gb = PreviousGroup.generate(b, new Rng(seed));
                assertEquals(ga.members(), gb.members());

                List<String> players = List.of(A, B);
                String ja = Journal.generate(a, ga, players, new Rng(seed)).rendered();
                String jb = Journal.generate(b, gb, players, new Rng(seed)).rendered();
                assertEquals(ja, jb);
            }
        }

        @Test
        void differentSeedsActuallyDiverge() {
            long distinctTells = 0;
            java.util.Set<String> journals = new java.util.HashSet<>();
            for (long seed = 0; seed < 60; seed++) {
                RunRules r = RunRules.roll(seed);
                PreviousGroup g = PreviousGroup.generate(r, new Rng(seed));
                journals.add(Journal.generate(r, g, List.of(A, B), new Rng(seed)).rendered());
                if (r.tell() == Tell.STALE_ONLY) distinctTells++;
            }
            assertTrue(journals.size() > 50, "journals are not varying enough: " + journals.size());
            assertTrue(distinctTells > 0, "STALE_ONLY never rolled in 60 runs");
        }

        @Test
        void everyRunIsPlayable() {
            for (long seed = 0; seed < 500; seed++) {
                RunRules r = RunRules.roll(seed);
                assertTrue(r.nights() >= 3 && r.nights() <= 6);
                assertTrue(r.falseLeads() >= 1, "a journal that is never wrong is a quest marker");

                PreviousGroup g = PreviousGroup.generate(r, new Rng(seed));
                assertNotNull(g.whoGotOut(), "exactly one gets out");
                assertNotNull(g.diarist());
                assertFalse(g.diarist().gotOut(), "the one who got out took their notes with them");
            }
        }
    }
}
