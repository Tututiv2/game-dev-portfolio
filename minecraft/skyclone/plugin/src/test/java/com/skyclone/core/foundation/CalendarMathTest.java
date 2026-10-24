package com.skyclone.core.foundation;

import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.time.SkyDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarMathTest {

    static final long MPD = 1_200_000L; // 20 minutes

    @Test
    void epochIsYearOneEarlySpringFirstMidnight() {
        SkyDate d = CalendarMath.date(1000, MPD, 1000);
        assertEquals(new SkyDate(1, 1, 1, 0, 0), d);
        assertEquals("Early Spring 1st", d.dayLabel());
        assertEquals("12:00am", d.timeLabel());
    }

    @Test
    void researchReferenceYear() {
        // research/05 §7.3: epoch 1560275700 unix s; 2026-10-02 00:00 UTC is year 517
        SkyDate d = CalendarMath.date(1_560_275_700_000L, MPD, 1_790_899_200_000L);
        assertEquals(517, d.year());
    }

    @Test
    void unitsMatchTheResearchTable() {
        // SB hour = 50 s
        assertEquals(1, CalendarMath.date(0, MPD, 50_000).hour());
        // day = 20 min, month = 31 days, year = 372 days
        assertEquals(2, CalendarMath.date(0, MPD, MPD).day());
        assertEquals(2, CalendarMath.date(0, MPD, 31 * MPD).month());
        assertEquals(2, CalendarMath.date(0, MPD, 372 * MPD).year());
        SkyDate d = CalendarMath.date(0, MPD, (372L + 31 + 1) * MPD + 625_000L);
        assertEquals(new SkyDate(2, 2, 2, 12, 30), d);
        assertEquals("12:30pm", d.timeLabel());
    }

    @Test
    void beforeEpochClampsToEpoch() {
        assertEquals(new SkyDate(1, 1, 1, 0, 0), CalendarMath.date(10_000, MPD, 0));
    }

    @Test
    void realtimeEvent() {
        CalendarEvent da = CalendarEvent.realtime("dark_auction", "Dark Auction", 60, 55, 5);
        long hour = 3_600_000L * 480_000;
        assertFalse(CalendarMath.realtimeActive(da, hour + 54 * 60_000L));
        assertTrue(CalendarMath.realtimeActive(da, hour + 55 * 60_000L));
        assertTrue(CalendarMath.realtimeActive(da, hour + 59 * 60_000L + 59_999));
        assertFalse(CalendarMath.realtimeActive(da, hour + 60 * 60_000L));
        assertEquals(60_000L, CalendarMath.realtimeUntil(da, hour + 54 * 60_000L));
        assertEquals(0, CalendarMath.realtimeUntil(da, hour + 56 * 60_000L));
        assertEquals(4 * 60_000L, CalendarMath.realtimeRemaining(da, hour + 56 * 60_000L));
    }

    @Test
    void dateEventSpansDaysAndYears() {
        CalendarEvent spooky = CalendarEvent.onDate("spooky", "Spooky Festival", 8, 29, 3);
        long start = (7L * 31 + 28) * MPD;
        assertFalse(CalendarMath.dateActive(spooky, 0, MPD, start - 1));
        assertTrue(CalendarMath.dateActive(spooky, 0, MPD, start));
        assertTrue(CalendarMath.dateActive(spooky, 0, MPD, start + 3 * MPD - 1));
        assertFalse(CalendarMath.dateActive(spooky, 0, MPD, start + 3 * MPD));
        assertEquals(1000, CalendarMath.dateUntil(spooky, 0, MPD, start - 1000));
        assertEquals(MPD, CalendarMath.dateRemaining(spooky, 0, MPD, start + 2 * MPD));
        // next year's occurrence
        long nextYear = start + 372 * MPD;
        assertEquals(nextYear - (start + 3 * MPD), CalendarMath.dateUntil(spooky, 0, MPD, start + 3 * MPD));

        // New Year: Late Winter 29, 5 days -> wraps into the next year
        CalendarEvent ny = CalendarEvent.onDate("ny", "New Year", 12, 29, 5);
        long nyStart = (11L * 31 + 28) * MPD;
        assertTrue(CalendarMath.dateActive(ny, 0, MPD, 372 * MPD + MPD)); // year 2, day 2
        assertFalse(CalendarMath.dateActive(ny, 0, MPD, 372 * MPD + 2 * MPD));
        assertTrue(CalendarMath.dateActive(ny, 0, MPD, nyStart));
    }

    @Test
    void everyYearsAndYearOffset() {
        CalendarEvent e = CalendarEvent.onDate("e", "E", 1, 1, 1);
        e.everyYears = 3;
        e.yearOffset = 2;            // years 2, 5, 8 ...
        assertFalse(CalendarMath.dateActive(e, 0, MPD, 0));
        assertTrue(CalendarMath.dateActive(e, 0, MPD, 372 * MPD));
        assertFalse(CalendarMath.dateActive(e, 0, MPD, 2 * 372 * MPD));
        assertTrue(CalendarMath.dateActive(e, 0, MPD, 4 * 372 * MPD));
        assertEquals(372 * MPD, CalendarMath.dateUntil(e, 0, MPD, 0));
    }

    @Test
    void everyMonthExtension() {
        CalendarEvent cult = CalendarEvent.onDate("cult", "Cult", 0, 7, 1);
        assertTrue(CalendarMath.dateActive(cult, 0, MPD, 6 * MPD));
        assertTrue(CalendarMath.dateActive(cult, 0, MPD, (31 + 6) * MPD));
        assertFalse(CalendarMath.dateActive(cult, 0, MPD, 7 * MPD));
        assertEquals(31 * MPD - MPD, CalendarMath.dateUntil(cult, 0, MPD, 7 * MPD));
    }

    @Test
    void validation() {
        assertNull(CalendarMath.validate(CalendarEvent.onDate("a", "A", 8, 29, 3)));
        assertNull(CalendarMath.validate(CalendarEvent.realtime("b", "B", 60, 55, 5)));
        assertNotNull(CalendarMath.validate(CalendarEvent.onDate("c", "C", 13, 1, 1)));
        assertNotNull(CalendarMath.validate(CalendarEvent.onDate("d", "D", 1, 32, 1)));
        assertNotNull(CalendarMath.validate(CalendarEvent.realtime("e", "E", 60, 0, 61)));
        assertNotNull(CalendarMath.validate(CalendarEvent.onDate(null, "F", 1, 1, 1)));
    }
}
