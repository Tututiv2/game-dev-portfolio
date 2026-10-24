package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RollingCounterTest {

    @Test
    void countsOnlyTheLastSecond() {
        RollingCounter c = new RollingCounter(1000, 64);
        for (int i = 0; i < 20; i++) {
            c.add(i * 50L); // 20 breaks/s, the client ceiling
        }
        assertEquals(20, c.count(999));
        // At t=1000 the event at t=0 is exactly one window old and falls out.
        assertEquals(19, c.count(1000));
        assertEquals(0, c.count(5000));
    }

    @Test
    void peakIsTheHighestSampledValueAndSurvivesQuietTime() {
        RollingCounter c = new RollingCounter(1000, 64);
        for (int i = 0; i < 12; i++) {
            c.add(i * 10L);
            c.sample(i * 10L);
        }
        assertEquals(12, c.peak());
        assertEquals(0, c.sample(10_000));
        assertEquals(12, c.peak());
        assertEquals(12, c.total());
    }

    @Test
    void ringNeverOvercountsPastCapacity() {
        RollingCounter c = new RollingCounter(1000, 8);
        for (int i = 0; i < 30; i++) {
            c.add(100);
        }
        assertEquals(8, c.count(100));
        assertEquals(30, c.total());
    }

    @Test
    void resetClearsEverything() {
        RollingCounter c = new RollingCounter(1000, 8);
        c.add(1);
        c.sample(1);
        c.reset();
        assertEquals(0, c.count(1));
        assertEquals(0, c.peak());
        assertEquals(0, c.total());
    }

    @Test
    void rejectsNonsenseConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new RollingCounter(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new RollingCounter(1000, 0));
    }
}
