package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RateTrackerTest {

    private static final long S = 1000;

    @Test
    void ratesAreOverActiveTime() {
        RateTracker r = new RateTracker(60 * S);
        r.activity(0);
        r.income(10 * S, 1000);
        r.activity(20 * S);
        assertEquals(20 * S, r.activeMs(20 * S));
        assertEquals(180_000, r.perHour(20 * S, 0), 1e-6);
        assertEquals(3_000, r.perMinute(20 * S, 0), 1e-6);
    }

    @Test
    void idleGapIsTrimmedBackToTheLastActivityNotTheMomentItWasNoticed() {
        RateTracker r = new RateTracker(60 * S);
        r.activity(0);
        r.income(20 * S, 600);
        // Walk away: at 100 s you have been idle 80 s. None of it counts -
        // not even the first 60 s before the threshold was crossed.
        assertEquals(RateTracker.State.IDLE, r.state(100 * S));
        assertEquals(20 * S, r.activeMs(100 * S));
        // Come back at 200 s; the new segment starts there.
        r.activity(200 * S);
        assertEquals(RateTracker.State.ACTIVE, r.state(205 * S));
        assertEquals(30 * S, r.activeMs(210 * S));
        assertEquals(600 * 3600.0 / 30, r.perHour(210 * S, 0), 1e-6);
    }

    @Test
    void justUnderTheThresholdIsStillActive() {
        RateTracker r = new RateTracker(60 * S);
        r.activity(0);
        assertEquals(RateTracker.State.ACTIVE, r.state(60 * S));
        assertEquals(60 * S, r.activeMs(60 * S));
        assertEquals(RateTracker.State.IDLE, r.state(60 * S + 1));
    }

    @Test
    void pauseStopsTimeAndDropsIncome() {
        RateTracker r = new RateTracker(60 * S);
        r.activity(0);
        r.income(10 * S, 100);
        r.pause(10 * S);
        r.income(20 * S, 5_000_000); // ignored
        r.activity(30 * S);          // ignored
        assertEquals(RateTracker.State.PAUSED, r.state(500 * S));
        assertEquals(10 * S, r.activeMs(500 * S));
        assertEquals(100, r.total(), 1e-9);
        r.resume(500 * S);
        assertEquals(20 * S, r.activeMs(510 * S));
    }

    @Test
    void noRateUntilEnoughActiveTime() {
        RateTracker r = new RateTracker(60 * S);
        r.activity(0);
        r.income(2 * S, 50_000);
        assertEquals(0, r.perHour(2 * S, 15 * S), 1e-9);
        assertEquals(RateTracker.State.WAITING, new RateTracker(60 * S).state(0));
    }

    @Test
    void lossesCountToo() {
        RateTracker r = new RateTracker(60 * S);
        r.activity(0);
        r.income(1 * S, 1000);
        r.income(2 * S, -400); // died, lost coins
        assertEquals(600, r.total(), 1e-9);
    }

    @Test
    void resetClearsTheSession() {
        RateTracker r = new RateTracker(60 * S);
        r.activity(0);
        r.income(5 * S, 99);
        r.pause(6 * S);
        r.reset();
        assertEquals(0, r.total(), 1e-9);
        assertEquals(0, r.activeMs(100 * S));
        assertEquals(RateTracker.State.WAITING, r.state(100 * S));
    }

    @Test
    void nonFiniteIncomeIsRefused() {
        RateTracker r = new RateTracker(60 * S);
        r.income(0, Double.NaN);
        r.income(0, Double.POSITIVE_INFINITY);
        assertEquals(0, r.total(), 1e-9);
    }
}
