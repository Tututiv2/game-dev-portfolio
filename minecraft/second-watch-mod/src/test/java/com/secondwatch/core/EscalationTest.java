package com.secondwatch.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The escalation curve, checked here rather than discovered on night four of a
 * recording.
 */
class EscalationTest {

    // =============================================================== triggers

    @Nested
    class Triggers {

        private static TriggerModel.Conditions idle() {
            return new TriggerModel.Conditions(false, 0, 1.0, 5.0, 0);
        }

        /** Doing the right thing must earn nothing - dread never goes down. */
        @Test
        void obeyingTheTriggerCostsNothing() {
            for (Trigger t : Trigger.values()) {
                TriggerModel.Conditions safe = switch (t) {
                    case TALKING -> new TriggerModel.Conditions(false, 0, 1.0, 5.0, 0);
                    case LIGHT -> new TriggerModel.Conditions(false, 0, 1.0, 5.0, 0);
                    case DARKNESS -> new TriggerModel.Conditions(false, 15, 1.0, 5.0, 0);
                    case STILLNESS -> new TriggerModel.Conditions(false, 0, 1.0, 5.0, 0);
                    case SEPARATION -> new TriggerModel.Conditions(false, 0, 1.0, 5.0, 0);
                    case DIGGING -> new TriggerModel.Conditions(false, 0, 1.0, 5.0, 0);
                };
                assertEquals(0.0, TriggerModel.feed(t, safe, 10), 1e-12,
                        t + " charged a player who was obeying it");
            }
        }

        /** Every trigger must actually be trippable, or the run has no lever. */
        @Test
        void everyTriggerCanBeTripped() {
            for (Trigger t : Trigger.values()) {
                TriggerModel.Conditions bad = switch (t) {
                    case TALKING -> new TriggerModel.Conditions(true, 0, 1.0, 5.0, 0);
                    case LIGHT -> new TriggerModel.Conditions(false, 14, 1.0, 5.0, 0);
                    case DARKNESS -> new TriggerModel.Conditions(false, 0, 1.0, 5.0, 0);
                    case STILLNESS -> new TriggerModel.Conditions(false, 0, 0.0, 5.0, 0);
                    case SEPARATION -> new TriggerModel.Conditions(false, 0, 1.0, 200.0, 0);
                    case DIGGING -> new TriggerModel.Conditions(false, 0, 1.0, 5.0, 30);
                };
                assertTrue(TriggerModel.feed(t, bad, 10) > 0,
                        t + " cannot be tripped at all");
            }
        }

        @Test
        void neverNegativeAndRejectsNegativeTime() {
            for (Trigger t : Trigger.values()) {
                assertTrue(TriggerModel.feed(t, idle(), 10) >= 0);
                assertThrows(IllegalArgumentException.class,
                        () -> TriggerModel.feed(t, idle(), -1));
            }
        }

        /**
         * Carelessness must be worth something, and not worth everything. If a
         * whole chapter of it could skip an act, the ending would arrive before
         * the story did.
         */
        @Test
        void aRecklessChapterIsWorthAboutHalfATier() {
            double chapter = TriggerModel.FULL_CHAPTER_CONTRIBUTION;
            assertTrue(chapter > 0.15, "too small to be worth reading the journal for: " + chapter);
            assertTrue(chapter < 0.60, "big enough to skip an act: " + chapter);
        }

        /** Digging is per block, so it must scale with how hard you dig. */
        @Test
        void diggingScalesWithBlocks() {
            TriggerModel.Conditions few = new TriggerModel.Conditions(false, 0, 1, 5, 5);
            TriggerModel.Conditions many = new TriggerModel.Conditions(false, 0, 1, 5, 50);
            assertTrue(TriggerModel.feed(Trigger.DIGGING, many, 1)
                    > TriggerModel.feed(Trigger.DIGGING, few, 1));
        }
    }

    // ============================================================== schedule

    @Nested
    class Schedule {

        /**
         * The bug this test exists for: a linear dread ramp skipped AUTHORED on
         * four-night runs, so the Crawler never spawned, so light was never
         * worth carrying. A whole mechanic went missing and nothing failed.
         */
        @Test
        void everyRunVisitsTheTiersThatCarryMechanics() {
            for (long seed = 0; seed < 400; seed++) {
                RunRules rules = RunRules.roll(seed);
                Dread dread = new Dread(rules);

                java.util.Set<Dread.Tier> seen = new java.util.HashSet<>();
                seen.add(dread.tier());
                for (int n = 1; n < rules.nights(); n++) {
                    dread.advanceNight();
                    seen.add(dread.tier());
                }

                assertTrue(seen.contains(Dread.Tier.AUTHORED),
                        "seed " + seed + " (" + rules.nights() + " nights) never reached AUTHORED, "
                                + "so the Crawler never spawns and light never matters");
                assertTrue(seen.contains(Dread.Tier.SOCIAL),
                        "seed " + seed + " never reached SOCIAL, so the Mimic never teaches the law");
                assertTrue(seen.contains(Dread.Tier.HUNT), "seed " + seed + " never ended");
            }
        }

        /** Night one is ordinary Minecraft, always, in every run. */
        @Test
        void nightOneIsAlwaysDeniable() {
            for (long seed = 0; seed < 400; seed++) {
                RunRules rules = RunRules.roll(seed);
                assertEquals(Dread.Tier.DENIABLE, new Dread(rules).tier(), "seed " + seed);
                assertTrue(SpawnPlan.capsFor(new Dread(rules).tier())
                        .values().stream().allMatch(v -> v == 0));
            }
        }

        /** The schedule never goes backwards. */
        @Test
        void scheduledTiersOnlyEverRise() {
            for (int nights = 4; nights <= 6; nights++) {
                Dread.Tier previous = null;
                for (int n = 1; n <= nights; n++) {
                    Dread.Tier tier = Dread.scheduledTier(n, nights);
                    if (previous != null) {
                        assertTrue(tier.ordinal() >= previous.ordinal(),
                                nights + "-night run went backwards at night " + n);
                    }
                    previous = tier;
                }
                assertEquals(Dread.Tier.HUNT, Dread.scheduledTier(nights, nights));
                assertEquals(Dread.Tier.AUTHORED, Dread.scheduledTier(nights - 1, nights));
            }
        }
    }

    // ================================================================= spawns

    @Nested
    class Spawns {

        private static Map<SpawnPlan.Kind, Integer> none() {
            return new EnumMap<>(SpawnPlan.Kind.class);
        }

        /**
         * The control sample. If anything at all spawns on night one, the whole
         * opening stops being ordinary Minecraft and the mod loses its best
         * ten minutes.
         */
        @Test
        void nothingSpawnsWhileItIsStillDeniable() {
            for (int v : SpawnPlan.capsFor(Dread.Tier.DENIABLE).values()) {
                assertEquals(0, v);
            }
            assertTrue(SpawnPlan.next(Dread.Tier.DENIABLE, none(), new Rng(1)).isEmpty());
        }

        /** Nothing that can kill you exists until AUTHORED. */
        @Test
        void nothingLethalBeforeAuthored() {
            for (Dread.Tier tier : List.of(Dread.Tier.DENIABLE, Dread.Tier.ARGUABLE, Dread.Tier.SOCIAL)) {
                SpawnPlan.capsFor(tier).forEach((kind, cap) -> {
                    if (SpawnPlan.lethal(kind)) {
                        assertEquals(0, cap, tier + " allows " + kind + ", which can kill");
                    }
                });
            }
            assertTrue(SpawnPlan.capsFor(Dread.Tier.AUTHORED).get(SpawnPlan.Kind.CRAWLER) > 0);
        }

        /** Caps must never shrink as things get worse. */
        @Test
        void capsOnlyEverGrow() {
            Dread.Tier[] order = Dread.Tier.values();
            for (int i = 1; i < order.length; i++) {
                Map<SpawnPlan.Kind, Integer> before = SpawnPlan.capsFor(order[i - 1]);
                Map<SpawnPlan.Kind, Integer> after = SpawnPlan.capsFor(order[i]);
                for (SpawnPlan.Kind k : SpawnPlan.Kind.values()) {
                    assertTrue(after.get(k) >= before.get(k),
                            k + " shrank from " + order[i - 1] + " to " + order[i]);
                }
            }
        }

        /** One at a time. Eleven arriving together is a mob, not a haunting. */
        @Test
        void spawnsTrickle() {
            for (Dread.Tier tier : Dread.Tier.values()) {
                assertTrue(SpawnPlan.next(tier, none(), new Rng(7)).size() <= 1);
            }
        }

        /** A full world asks for nothing more. */
        @Test
        void nothingSpawnsOnceTheCapsAreFull() {
            Map<SpawnPlan.Kind, Integer> full = new EnumMap<>(SpawnPlan.Kind.class);
            SpawnPlan.capsFor(Dread.Tier.HUNT).forEach(full::put);
            assertTrue(SpawnPlan.next(Dread.Tier.HUNT, full, new Rng(3)).isEmpty());
        }

        /** The Tall One is the last thing to arrive, and there is only ever one. */
        @Test
        void exactlyOneTallOneAndOnlyAtTheEnd() {
            assertEquals(0, SpawnPlan.capsFor(Dread.Tier.AUTHORED).get(SpawnPlan.Kind.TALL_ONE));
            assertEquals(1, SpawnPlan.capsFor(Dread.Tier.HUNT).get(SpawnPlan.Kind.TALL_ONE));
        }
    }
}
