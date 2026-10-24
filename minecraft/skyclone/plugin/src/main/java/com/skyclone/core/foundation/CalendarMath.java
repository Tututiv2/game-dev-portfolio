package com.skyclone.core.foundation;

import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.time.SkyDate;

/**
 * Pure calendar arithmetic (no Bukkit), so it is unit-testable and the self-test can check it
 * against the research doc's numbers.
 *
 * <p>Time is DERIVED from the wall clock and an epoch, never stored as a ticking counter, so a
 * crash or restart can never make the calendar drift. Year 1, Early Spring 1st, 00:00 is the
 * epoch. A day is {@code millisPerDay} real ms (20 min by default), a month 31 days, a year
 * 12 months = 372 days.
 *
 * <p>Calendar-date events: {@code month} 1..12 (or 0 = EVERY month, an extension so monthly
 * events need no twelve copies), {@code day} 1..31, lasting {@code durationDays} days (may run
 * past the end of the month/year), occurring in years where
 * {@code (year - yearOffset) mod everyYears == 0}.
 *
 * <p>Real-time events: start every {@code intervalMinutes} real minutes at {@code offsetMinutes}
 * past the interval boundary, measured from the Unix epoch (so interval 60 + offset 55 = every
 * real hour at :55 UTC, which is :55 in every whole-hour timezone), lasting {@code durationMinutes}.
 */
public final class CalendarMath {

    private CalendarMath() {}

    public static final int DAYS_PER_MONTH = 31;
    public static final int MONTHS_PER_YEAR = 12;
    public static final int DAYS_PER_YEAR = DAYS_PER_MONTH * MONTHS_PER_YEAR; // 372

    /** Elapsed calendar millis since the epoch, never negative. */
    public static long elapsed(long epochMillis, long nowMillis) {
        return Math.max(0, nowMillis - epochMillis);
    }

    public static SkyDate date(long epochMillis, long millisPerDay, long nowMillis) {
        long mpd = Math.max(24, millisPerDay);
        long t = elapsed(epochMillis, nowMillis);
        long dayIndex = t / mpd;                              // days since epoch
        long year = dayIndex / DAYS_PER_YEAR + 1;
        long dayOfYear = dayIndex % DAYS_PER_YEAR;            // 0..371
        int month = (int) (dayOfYear / DAYS_PER_MONTH) + 1;   // 1..12
        int day = (int) (dayOfYear % DAYS_PER_MONTH) + 1;     // 1..31
        long inDay = t % mpd;
        // hour/minute from fractional day: exact even when mpd is not divisible by 24
        long minuteOfDay = inDay * 1440L / mpd;               // 0..1439
        int hour = (int) (minuteOfDay / 60);
        int minute = (int) (minuteOfDay % 60);
        return new SkyDate((int) Math.min(Integer.MAX_VALUE, year), month, day, hour, minute);
    }

    /** Absolute day index (0-based since epoch) at {@code nowMillis}. */
    public static long dayIndex(long epochMillis, long millisPerDay, long nowMillis) {
        return elapsed(epochMillis, nowMillis) / Math.max(24, millisPerDay);
    }

    public static boolean isRealtime(CalendarEvent e) { return e.intervalMinutes > 0; }

    /** Validation message for a malformed event, or null when it is fine. */
    public static String validate(CalendarEvent e) {
        if (e == null) return "null event";
        if (e.id == null || e.id.isBlank()) return "event without id";
        if (isRealtime(e)) {
            if (e.durationMinutes <= 0) return e.id + ": durationMinutes must be > 0";
            if (e.durationMinutes > e.intervalMinutes) return e.id + ": durationMinutes > intervalMinutes (always active)";
            if (e.offsetMinutes < 0) return e.id + ": negative offsetMinutes";
            return null;
        }
        if (e.month < 0 || e.month > 12) return e.id + ": month must be 1..12 (or 0 = every month)";
        if (e.day < 1 || e.day > DAYS_PER_MONTH) return e.id + ": day must be 1..31";
        if (e.durationDays < 1) return e.id + ": durationDays must be >= 1";
        if (e.everyYears < 0) return e.id + ": negative everyYears";
        return null;
    }

    // ---- real-time events -------------------------------------------------------------------

    public static boolean realtimeActive(CalendarEvent e, long nowMillis) {
        long interval = e.intervalMinutes * 60_000L;
        long phase = Math.floorMod(nowMillis - e.offsetMinutes * 60_000L, interval);
        return phase < e.durationMinutes * 60_000L;
    }

    /** Millis until the next start; 0 when active. */
    public static long realtimeUntil(CalendarEvent e, long nowMillis) {
        if (realtimeActive(e, nowMillis)) return 0;
        long interval = e.intervalMinutes * 60_000L;
        long phase = Math.floorMod(nowMillis - e.offsetMinutes * 60_000L, interval);
        return interval - phase;
    }

    /** Millis until the current occurrence ends; 0 when not active. */
    public static long realtimeRemaining(CalendarEvent e, long nowMillis) {
        if (!realtimeActive(e, nowMillis)) return 0;
        long phase = Math.floorMod(nowMillis - e.offsetMinutes * 60_000L, e.intervalMinutes * 60_000L);
        return e.durationMinutes * 60_000L - phase;
    }

    // ---- calendar-date events ---------------------------------------------------------------

    static boolean yearQualifies(CalendarEvent e, long year) {
        int every = Math.max(1, e.everyYears);
        return Math.floorMod(year - e.yearOffset, every) == 0;
    }

    /** Absolute start day index of the occurrence in {@code year} / {@code month} (month 1..12). */
    static long startDay(CalendarEvent e, long year, int month) {
        return (year - 1) * DAYS_PER_YEAR + (long) (month - 1) * DAYS_PER_MONTH + (e.day - 1);
    }

    /**
     * Start day index of the occurrence covering {@code dayIndex}, or -1. Looks back far enough
     * to catch an occurrence that started last month/year and is still running.
     */
    static long coveringStart(CalendarEvent e, long dayIndex) {
        long year = dayIndex / DAYS_PER_YEAR + 1;
        int dur = Math.max(1, e.durationDays);
        // how many years back can an occurrence still be running? dur / 372 + 1
        long yearsBack = dur / DAYS_PER_YEAR + 1;
        long best = -1;
        for (long y = year - yearsBack; y <= year; y++) {
            if (y < 1 || !yearQualifies(e, y)) continue;
            if (e.month == 0) {
                for (int m = 1; m <= MONTHS_PER_YEAR; m++) {
                    long s = startDay(e, y, m);
                    if (s <= dayIndex && dayIndex < s + dur) best = Math.max(best, s);
                }
            } else {
                long s = startDay(e, y, e.month);
                if (s <= dayIndex && dayIndex < s + dur) best = Math.max(best, s);
            }
        }
        return best;
    }

    public static boolean dateActive(CalendarEvent e, long epochMillis, long millisPerDay, long nowMillis) {
        if (nowMillis < epochMillis) return false;
        return coveringStart(e, dayIndex(epochMillis, millisPerDay, nowMillis)) >= 0;
    }

    /** Millis until the next start strictly after now; 0 when active. -1 if it never occurs. */
    public static long dateUntil(CalendarEvent e, long epochMillis, long millisPerDay, long nowMillis) {
        long mpd = Math.max(24, millisPerDay);
        if (dateActive(e, epochMillis, mpd, nowMillis)) return 0;
        long t = elapsed(epochMillis, nowMillis);
        long year = t / mpd / DAYS_PER_YEAR + 1;
        int every = Math.max(1, e.everyYears);
        long bestStartMs = Long.MAX_VALUE;
        // Jump straight to the qualifying years (y = yearOffset mod every) instead of testing each one: the
        // mayors' forced-only events use everyYears = 1,000,000, which made this a million-step loop per call.
        long first = year + Math.floorMod(e.yearOffset - year, (long) every);
        for (long y = first; y <= year + every + 1; y += every) {
            int mFrom = e.month == 0 ? 1 : e.month, mTo = e.month == 0 ? MONTHS_PER_YEAR : e.month;
            for (int m = mFrom; m <= mTo; m++) {
                long startMs = startDay(e, y, m) * mpd;
                if (startMs > t && startMs < bestStartMs) bestStartMs = startMs;
            }
            if (bestStartMs != Long.MAX_VALUE) break;
        }
        if (bestStartMs == Long.MAX_VALUE) return -1;
        long until = bestStartMs - t;
        if (nowMillis < epochMillis) until += epochMillis - nowMillis;
        return until;
    }

    /** Millis until the current occurrence ends; 0 when not active. */
    public static long dateRemaining(CalendarEvent e, long epochMillis, long millisPerDay, long nowMillis) {
        long mpd = Math.max(24, millisPerDay);
        long day = dayIndex(epochMillis, mpd, nowMillis);
        long start = coveringStart(e, day);
        if (start < 0) return 0;
        long endMs = (start + Math.max(1, e.durationDays)) * mpd;
        return endMs - elapsed(epochMillis, nowMillis);
    }

    /** Natural (un-forced) state. */
    public static boolean active(CalendarEvent e, long epochMillis, long millisPerDay, long nowMillis) {
        return isRealtime(e) ? realtimeActive(e, nowMillis) : dateActive(e, epochMillis, millisPerDay, nowMillis);
    }

    public static long until(CalendarEvent e, long epochMillis, long millisPerDay, long nowMillis) {
        return isRealtime(e) ? realtimeUntil(e, nowMillis) : dateUntil(e, epochMillis, millisPerDay, nowMillis);
    }

    public static long remaining(CalendarEvent e, long epochMillis, long millisPerDay, long nowMillis) {
        return isRealtime(e) ? realtimeRemaining(e, nowMillis) : dateRemaining(e, epochMillis, millisPerDay, nowMillis);
    }

    /** Natural length of one occurrence in real millis. */
    public static long length(CalendarEvent e, long millisPerDay) {
        return isRealtime(e) ? e.durationMinutes * 60_000L : Math.max(1, e.durationDays) * Math.max(24, millisPerDay);
    }
}
