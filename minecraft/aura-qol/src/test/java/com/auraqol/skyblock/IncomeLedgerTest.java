package com.auraqol.skyblock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IncomeLedgerTest {

    private static final long M = 60_000;

    @Test
    void windowSumsAndPerSource() {
        IncomeLedger l = new IncomeLedger(31 * M, 1000);
        l.add(1 * M, 100, IncomeSource.SACKS);
        l.add(8 * M, 50, IncomeSource.PEST);
        l.add(9 * M, 25, IncomeSource.SACKS);
        assertEquals(175, l.sum(10 * M, 10 * M), 1e-9);
        assertEquals(75, l.sum(10 * M, 5 * M), 1e-9);
        double[] by = l.bySource(10 * M, 5 * M);
        assertEquals(25, by[IncomeSource.SACKS.ordinal()], 1e-9);
        assertEquals(50, by[IncomeSource.PEST.ordinal()], 1e-9);
    }

    @Test
    void perHourDividesByTheWindowOrTheTimeSoFar() {
        IncomeLedger l = new IncomeLedger(31 * M, 1000);
        l.add(1 * M, 1000, IncomeSource.PURSE);
        // 2 minutes in, a 10-minute window: divide by 2 minutes, not 10.
        assertEquals(30_000, l.perHour(2 * M, 10 * M, 15_000), 1e-6);
        // 20 minutes in: the entry at minute 1 has left the window.
        assertEquals(0, l.perHour(20 * M, 10 * M, 15_000), 1e-6);
        // Under the minimum active time: no rate.
        assertEquals(0, l.perHour(10_000, 10 * M, 15_000), 1e-6);
    }

    @Test
    void oldEntriesArePruned() {
        IncomeLedger l = new IncomeLedger(31 * M, 1000);
        l.add(0, 1, IncomeSource.OTHER);
        l.add(40 * M, 1, IncomeSource.OTHER);
        assertEquals(1, l.size());
        l.add(41 * M, 0, IncomeSource.OTHER);   // zero is not an entry
        assertEquals(1, l.size());
    }
}
