package com.thestill.din;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DinTest {

    @Test
    @DisplayName("a silent player is QUIET and earns no bonus")
    void startsQuiet() {
        Din din = new Din();
        assertEquals(0.0, din.value());
        assertSame(DinBand.QUIET, din.band());
        assertEquals(1.0, din.multiplier());
    }

    @Test
    @DisplayName("bands begin at the spec's thresholds")
    void thresholds() {
        assertEquals(0, DinBand.QUIET.threshold());
        assertEquals(120, DinBand.STIRRING.threshold());
        assertEquals(400, DinBand.DRAWN.threshold());
        assertEquals(900, DinBand.SWARM.threshold());
        assertEquals(1800, DinBand.CHOIR.threshold());
        // x1.0 quiet to x4.0 choir. The ceiling is load-bearing: a number almost
        // nobody reaches does real work simply by existing.
        assertEquals(1.0, DinBand.QUIET.multiplier());
        assertEquals(4.0, DinBand.CHOIR.multiplier());
    }

    @Test
    @DisplayName("multipliers only ever increase with the band")
    void multipliersRise() {
        DinBand[] bands = DinBand.values();
        for (int i = 1; i < bands.length; i++) {
            assertTrue(bands[i].multiplier() > bands[i - 1].multiplier(),
                    bands[i] + " does not pay more than " + bands[i - 1]);
            assertTrue(bands[i].threshold() > bands[i - 1].threshold());
        }
    }

    @Test
    @DisplayName("breaking blocks raises the band, stopping lets it fall")
    void risesAndFalls() {
        Din din = new Din();
        din.add(150);
        assertSame(DinBand.STIRRING, din.band());
        din.add(300);   // 450
        assertSame(DinBand.DRAWN, din.band());
        din.add(500);   // 950
        assertSame(DinBand.SWARM, din.band());

        // 4/sec is the spec's decay. From 950, four minutes of silence is silence.
        din.decay(4, 240);
        assertSame(DinBand.QUIET, din.band());
        assertEquals(0.0, din.value());
    }

    @Test
    @DisplayName("going loud is instant, going quiet has to be meant")
    void hysteresis() {
        Din din = new Din();
        din.add(900);
        assertSame(DinBand.SWARM, din.band(), "crosses up exactly at the threshold");

        // Drop just under 900. Without a deadband this flips band every tick,
        // which strobes the bossbar and stutters the audio layer.
        din.decay(4, 5); // 880
        assertSame(DinBand.SWARM, din.band(), "880 is inside the 5% deadband, still SWARM");

        din.decay(4, 7); // 852, below 900 * 0.95 = 855
        assertSame(DinBand.DRAWN, din.band(), "clear of the deadband, drops properly");
    }

    @Test
    @DisplayName("hovering exactly on a boundary never oscillates")
    void noFlicker() {
        Din din = new Din();
        din.add(400);
        assertSame(DinBand.DRAWN, din.band());
        DinBand settled = din.band();
        // Jitter around the threshold the way real accrual and decay would.
        for (int i = 0; i < 200; i++) {
            din.decay(4, 0.25);
            din.add(1.0);
            assertSame(settled, din.band(), "band changed while hovering at the boundary");
        }
    }

    @Test
    @DisplayName("Din is clamped and can never be poisoned")
    void clamped() {
        Din din = new Din();
        din.add(Double.NaN);
        din.add(Double.POSITIVE_INFINITY);
        din.add(-500);
        assertEquals(0.0, din.value(), "bad input changed the meter");

        din.add(Din.MAX * 10);
        assertEquals(Din.MAX, din.value(), "no ceiling");
        assertSame(DinBand.CHOIR, din.band());

        din.decay(4, 100_000);
        assertEquals(0.0, din.value(), "decayed below zero");
        assertSame(DinBand.QUIET, din.band());
    }

    @Test
    @DisplayName("the bar refills from empty at every threshold")
    void progressIsPerBand() {
        Din din = new Din();
        din.add(400);
        assertEquals(0.0, din.progressThroughBand(), 1e-9, "a new band starts empty");
        din.add(250); // 650, halfway from 400 to 900
        assertEquals(0.5, din.progressThroughBand(), 1e-9);
        din.add(249); // 899, nearly full
        assertTrue(din.progressThroughBand() > 0.99);
    }

    @Test
    @DisplayName("progress stays sane in CHOIR, where there is no next threshold")
    void progressInTopBand() {
        Din din = new Din();
        din.add(1800);
        double atFloor = din.progressThroughBand();
        din.add(500);
        assertTrue(din.progressThroughBand() > atFloor, "the bar stopped moving at the top");
        din.add(Din.MAX);
        assertTrue(din.progressThroughBand() <= 1.0, "progress exceeded 1");
    }

    @Test
    @DisplayName("clear silences the meter outright")
    void clears() {
        Din din = new Din();
        din.add(2000);
        assertSame(DinBand.CHOIR, din.band());
        din.clear();
        assertEquals(0.0, din.value());
        assertSame(DinBand.QUIET, din.band());
    }
}
