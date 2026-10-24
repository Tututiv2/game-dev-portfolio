package com.secondwatch.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TheSeatTest {

    private static final String A = "tutut";
    private static final String B = "friend";

    /** The sentence the whole run has been pointing at. */
    @Test
    void itFitsOne() {
        TheSeat seat = new TheSeat();

        assertEquals(TheSeat.Result.BEGUN, seat.attempt(A, Dread.Tier.HUNT, 0));
        assertEquals(A, seat.tick(TheSeat.DEPARTURE_MS).orElseThrow());
        assertTrue(seat.spent());

        assertEquals(TheSeat.Result.SPENT, seat.attempt(B, Dread.Tier.HUNT, 999_999),
                "a second person got out, which is the one thing that must never happen");
    }

    /**
     * Finding it early has to be frustrating rather than useful. A group that
     * knows the exit exists and cannot reach it yet plays a much better night
     * three.
     */
    @Test
    void itDoesNothingBeforeTheLastNight() {
        for (Dread.Tier tier : Dread.Tier.values()) {
            TheSeat seat = new TheSeat();
            TheSeat.Result result = seat.attempt(A, tier, 0);
            if (tier == Dread.Tier.HUNT) {
                assertEquals(TheSeat.Result.BEGUN, result);
            } else {
                assertEquals(TheSeat.Result.NOT_READY, result, "it worked at " + tier);
                assertFalse(seat.inProgress());
            }
        }
    }

    /** Both of them cannot be in it at once, however hard they click. */
    @Test
    void onlyOnePersonCanBeInItAtATime() {
        TheSeat seat = new TheSeat();
        assertEquals(TheSeat.Result.BEGUN, seat.attempt(A, Dread.Tier.HUNT, 0));
        assertEquals(TheSeat.Result.IN_PROGRESS, seat.attempt(B, Dread.Tier.HUNT, 100));
        assertEquals(A, seat.occupant().orElseThrow());
    }

    /** The departure takes the full time, so nobody leaves on an impulse. */
    @Test
    void leavingIsNotInstant() {
        TheSeat seat = new TheSeat();
        seat.attempt(A, Dread.Tier.HUNT, 0);

        assertTrue(seat.tick(TheSeat.DEPARTURE_MS - 1).isEmpty());
        assertFalse(seat.spent());
        assertEquals(0.5, seat.progress(TheSeat.DEPARTURE_MS / 2), 0.01);

        assertTrue(seat.tick(TheSeat.DEPARTURE_MS).isPresent());
    }

    /**
     * An interrupted attempt must not spend it. A run made unwinnable by bad
     * luck is a run the players stop taking seriously.
     */
    @Test
    void anInterruptedDepartureDoesNotSpendIt() {
        TheSeat seat = new TheSeat();
        seat.attempt(A, Dread.Tier.HUNT, 0);
        seat.interrupt();

        assertFalse(seat.spent());
        assertFalse(seat.inProgress());
        assertEquals(TheSeat.Result.BEGUN, seat.attempt(B, Dread.Tier.HUNT, 1_000));
    }

    @Test
    void nobodyHasLeftUntilSomebodyHas() {
        TheSeat seat = new TheSeat();
        assertTrue(seat.departed().isEmpty());
        assertTrue(seat.occupant().isEmpty());
        assertEquals(0.0, seat.progress(50_000));
        assertTrue(seat.tick(50_000).isEmpty());
    }
}
