package com.secondwatch.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QuietTest {

    private static final String A = "tutut";
    private static final String B = "friend";

    private static VoiceCorpus stockedCorpus() {
        VoiceCorpus c = new VoiceCorpus();
        for (int i = 0; i < 12; i++) {
            c.record(A, i * 20_000L, 2_000L, false, "a" + i);
            c.record(B, i * 20_000L + 5_000L, 2_400L, false, "b" + i);
        }
        return c;
    }

    private static Dread dreadAt(RunRules rules, double v) {
        Dread d = new Dread(rules);
        d.feed(v);
        return d;
    }

    // ============================================================ the field

    @Nested
    class SuppressionField {

        /**
         * The field eats voice chat too. A boss that politely lets you
         * coordinate is not the boss this run has been building toward.
         */
        @Test
        void insideTheRadiusEverythingIsTotallySilent() {
            Quiet q = new Quiet(0, 0);
            assertEquals(1.0, q.suppressionAt(0, 0));
            assertEquals(1.0, q.suppressionAt(q.silenceRadius() - 0.1, 0));
            assertTrue(q.silences(0, 0));
        }

        @Test
        void suppressionFallsOffSmoothlyAndReachesZero() {
            Quiet q = new Quiet(0, 0);
            double r = q.silenceRadius();

            assertEquals(0.0, q.suppressionAt(r + Quiet.FALLOFF + 1, 0));

            double previous = 1.0;
            for (double d = r; d <= r + Quiet.FALLOFF; d += 0.5) {
                double s = q.suppressionAt(d, 0);
                assertTrue(s <= previous + 1e-9, "suppression rose with distance at " + d);
                assertTrue(s >= 0.0 && s <= 1.0, "suppression out of range: " + s);
                previous = s;
            }
        }

        /** Every stage of winning makes the situation worse. */
        @Test
        void thePocketThatWasSafeInPhaseOneIsNotSafeLater() {
            Quiet q = new Quiet(0, 0);
            double edgeOfPhaseOne = q.silenceRadius() + 1;
            assertFalse(q.silences(edgeOfPhaseOne, 0));

            q.expose(Quiet.EXPOSURE_TO_END);
            assertEquals(Quiet.MAX_PHASE, q.phase());
            assertTrue(q.silences(edgeOfPhaseOne, 0),
                    "the silence must grow, or beating it is a victory lap");
        }

        @Test
        void exposureIsMonotonicAndRejectsNegatives() {
            Quiet q = new Quiet(0, 0);
            assertThrows(IllegalArgumentException.class, () -> q.expose(-0.1));

            double last = q.exposure();
            for (int i = 0; i < 40; i++) {
                q.expose(0.1);
                assertTrue(q.exposure() >= last);
                last = q.exposure();
            }
            assertTrue(q.ended());
            assertEquals(Quiet.EXPOSURE_TO_END, q.exposure(), 1e-9, "exposure must not overshoot");
        }

        @Test
        void itWalksTowardYouAndNeverBackwards() {
            Quiet q = new Quiet(0, 0);
            assertThrows(IllegalArgumentException.class, () -> q.stepToward(10, 0, -1));

            double before = q.distanceTo(100, 0);
            q.stepToward(100, 0, 5);
            assertEquals(before - 5, q.distanceTo(100, 0), 1e-6);

            // It never overshoots its target and starts walking away again.
            q.stepToward(100, 0, 10_000);
            assertEquals(0.0, q.distanceTo(100, 0), 1e-6);
        }
    }

    // ============================================================ resonator

    @Nested
    class TheResonator {

        @Test
        void onlyDeliversExposureWhileActiveAndInRange() {
            Quiet q = new Quiet(0, 0);
            Resonator r = new Resonator(5, 0);

            assertEquals(0.0, r.exposureThisTick(q, 0, 1000), "must do nothing before firing");

            assertTrue(r.fire(1000));
            assertTrue(r.isActive(1000));
            assertTrue(r.exposureThisTick(q, 1500, 500) > 0);

            long after = 1000 + Resonator.ACTIVE_MS + 1;
            assertFalse(r.isActive(after));
            assertEquals(0.0, r.exposureThisTick(q, after, 500));
        }

        @Test
        void outOfRangeDoesNothing() {
            Quiet q = new Quiet(0, 0);
            Resonator far = new Resonator(Resonator.RANGE + 5, 0);
            far.fire(0);
            assertEquals(0.0, far.exposureThisTick(q, 100, 100));
        }

        @Test
        void cannotBeSpammed() {
            Resonator r = new Resonator(0, 0);
            assertTrue(r.fire(0));
            assertFalse(r.fire(1000), "a second firing inside the cooldown must be refused");
            assertFalse(r.canFire(Resonator.COOLDOWN_MS - 1));
            assertTrue(r.canFire(Resonator.COOLDOWN_MS));
        }

        @Test
        void rejectsNegativeTime() {
            Quiet q = new Quiet(0, 0);
            Resonator r = new Resonator(0, 0);
            r.fire(0);
            assertThrows(IllegalArgumentException.class, () -> r.exposureThisTick(q, 100, -1));
        }

        /**
         * Keeps the fight from silently turning into a slog or a formality when
         * somebody tunes a constant. Two decisions is a fight; one is a cutscene
         * and six is homework.
         */
        @Test
        void theFightIsTwoOrThreeDecisionsLong() {
            double firings = Resonator.firingsToEnd();
            assertTrue(firings >= 2.0 && firings <= 3.0,
                    "the fight is " + firings + " firings long, which is the wrong shape");
        }
    }

    // ============================================================== herding

    @Nested
    class Herding {

        private static List<PlayerState> apartNear(double qx, double qz, long lastSpoke) {
            return List.of(
                    new PlayerState(A, qx - 120, qz, 7, lastSpoke, false, true),
                    new PlayerState(B, qx - 400, qz - 300, 7, lastSpoke, false, true));
        }

        /**
         * The whole foreshadowing budget: for five nights the phantom is
         * everywhere except one region, and the players notice only in hindsight.
         */
        @Test
        void theUnderstudyNeverAppearsInsideTheSilence() {
            RunRules rules = RunRules.roll(4);
            Quiet q = new Quiet(0, 0);
            PhantomDirector d = new PhantomDirector(rules, stockedCorpus(), new Rng(4));
            d.setQuiet(q);

            long t = 2_000_000L;
            for (double dist = 0; dist < q.avoidRadius(); dist += 2.0) {
                List<PlayerState> ps = List.of(
                        new PlayerState(A, dist, 0, 7, t - 1_000L, false, true),
                        new PlayerState(B, 500, 500, 7, t - 1_000L, false, true));
                t += PhantomDirector.COOLDOWN_MS;

                Optional<PhantomPlan> plan = d.consider(ps, dreadAt(rules, 0.9), t);
                assertTrue(plan.isEmpty() || !plan.get().victimId().equals(A),
                        "the Understudy produced a phantom for a player standing "
                                + dist + " from the Quiet");
            }
        }

        /** A lure must never stop somewhere the Understudy would not go. */
        @Test
        void luresStopAtTheEdgeOfTheSilence() {
            RunRules rules = RunRules.roll(8);
            Quiet q = new Quiet(0, 0);
            PhantomDirector d = new PhantomDirector(rules, stockedCorpus(), new Rng(8));
            d.setQuiet(q);

            long t = 2_000_000L;
            for (int i = 0; i < 60; i++) {
                t += PhantomDirector.COOLDOWN_MS;
                double approach = q.avoidRadius() + 2 + (i % 30);
                List<PlayerState> ps = List.of(
                        new PlayerState(A, approach, 0, 7, t - 1_000L, false, true),
                        new PlayerState(B, approach + 300, 200, 7, t - 1_000L, false, true));

                Optional<PhantomPlan> plan = d.consider(ps, dreadAt(rules, 0.99), t);
                if (plan.isEmpty() || !plan.get().luring()) continue;

                assertTrue(q.distanceTo(plan.get().headingX(), plan.get().headingZ())
                                >= q.avoidRadius() - 1e-6,
                        "a lure led into the silence the Understudy will not enter");
            }
        }

        /**
         * Over a night the lures trend one way.
         *
         * <p>Measured across randomised geometry, because the claim is
         * statistical and not per-lure. It genuinely fails for one specific
         * arrangement and that is correct: when both players are on the same
         * side of the Quiet, the one further out can only be walked away from
         * their partner by also being walked away from the Quiet. The split
         * invariant outranks the herd, always. What the Understudy gets is a
         * bias, not a steering wheel - roughly two lures in three close the
         * distance, which over a five-night run is a landslide and inside any
         * single night is invisible.
         */
        @Test
        void luresTrendTowardTheQuietInAggregate() {
            RunRules rules = RunRules.roll(12);
            Quiet q = new Quiet(0, 0);
            Rng place = new Rng(9001);

            // Controlled A/B: identical seed, identical corpus, identical victim
            // and line choices - the ONLY difference is whether the director
            // knows where the Quiet is. Measuring an absolute "how often does a
            // lure close the distance" rate does not work, because where the
            // players happen to be standing dominates the answer.
            int herdedCloser = 0;
            int total = 0;
            double totalGain = 0;

            for (int trial = 0; trial < 400; trial++) {
                PhantomDirector herded = new PhantomDirector(rules, stockedCorpus(), new Rng(trial));
                herded.setQuiet(q);
                PhantomDirector blind = new PhantomDirector(rules, stockedCorpus(), new Rng(trial));

                double ax = place.range(-600, 600);
                double az = place.range(-600, 600);
                double bx = place.range(-600, 600);
                double bz = place.range(-600, 600);
                if (q.distanceTo(ax, az) < q.avoidRadius() + 40) continue;
                if (q.distanceTo(bx, bz) < q.avoidRadius() + 40) continue;

                long t = 2_000_000L;
                List<PlayerState> ps = List.of(
                        new PlayerState(A, ax, az, 7, t - 1_000L, false, true),
                        new PlayerState(B, bx, bz, 7, t - 1_000L, false, true));

                Optional<PhantomPlan> hp = herded.consider(ps, dreadAt(rules, 0.99), t);
                Optional<PhantomPlan> bp = blind.consider(ps, dreadAt(rules, 0.99), t);
                if (hp.isEmpty() || bp.isEmpty()) continue;
                if (!hp.get().luring() || !bp.get().luring()) continue;
                assertEquals(bp.get().victimId(), hp.get().victimId(),
                        "the A/B is not controlled - the two directors picked different victims");

                double herdedDist = q.distanceTo(hp.get().headingX(), hp.get().headingZ());
                double blindDist = q.distanceTo(bp.get().headingX(), bp.get().headingZ());

                total++;
                totalGain += blindDist - herdedDist;
                if (herdedDist < blindDist) herdedCloser++;
            }

            assertTrue(total > 100, "not enough lures sampled: " + total);
            double rate = herdedCloser / (double) total;
            double meanGain = totalGain / total;

            assertTrue(rate > 0.90,
                    "knowing where the Quiet is barely changed where lures point: "
                            + herdedCloser + " of " + total);
            assertTrue(meanGain > 3.0,
                    "the herd is too weak to matter over a run: " + meanGain + " blocks per lure");
            assertTrue(meanGain < PhantomDirector.LURE_DISTANCE * 0.75,
                    "the herd is so strong every lure points at the boss and players will see it "
                            + "in ten minutes: " + meanGain + " blocks per lure");
        }

        /**
         * The older invariant outranks the boss. A "lure" that walks you back
         * toward your friend is not a lure, however much the Quiet wants it.
         */
        @Test
        void herdingNeverBreaksTheAwayFromPartnerRule() {
            for (long seed = 0; seed < 40; seed++) {
                RunRules rules = RunRules.roll(seed);
                // Put the Quiet directly behind the partner, so the herd bias and
                // the split pull in opposite directions and one has to lose.
                Quiet q = new Quiet(-600, -400);
                PhantomDirector d = new PhantomDirector(rules, stockedCorpus(), new Rng(seed));
                d.setQuiet(q);

                long t = 2_000_000L;
                for (int i = 0; i < 20; i++) {
                    t += PhantomDirector.COOLDOWN_MS;
                    List<PlayerState> ps = List.of(
                            new PlayerState(A, 0, 0, 7, t - 1_000L, false, true),
                            new PlayerState(B, -300, -200, 7, t - 1_000L, false, true));

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
                            "seed " + seed + ": herding pulled a victim back toward their partner");
                }
            }
        }
    }
}
