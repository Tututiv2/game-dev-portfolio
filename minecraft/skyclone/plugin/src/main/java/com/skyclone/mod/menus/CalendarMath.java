package com.skyclone.mod.menus;

import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.time.SkyDate;

import java.util.ArrayList;
import java.util.List;

/**
 * Calendar arithmetic for the Calendar and Events menu. Pure, so the numbers can be unit-tested
 * against research 08 section 8: 12 months of 31 days, 1 SkyBlock day = 20 real minutes, so one
 * SkyBlock hour = 50 real seconds and one year = 372 days = 124 real hours.
 */
public final class CalendarMath {

    private CalendarMath() {}

    public static final int MONTHS = 12;
    public static final int DAYS_PER_MONTH = 31;
    public static final int DAYS_PER_YEAR = MONTHS * DAYS_PER_MONTH;   // 372

    /** 0-based day of the year: Early Spring 1st = 0, Late Winter 31st = 371. */
    public static int dayIndex(int month, int day) { return (month - 1) * DAYS_PER_MONTH + (day - 1); }

    /** Real-time event (every N real minutes) rather than a calendar date. */
    public static boolean isRealtime(CalendarEvent e) { return e.intervalMinutes > 0; }

    /** Does the event run in this SkyBlock year? ({@code everyYears}/{@code yearOffset}.) */
    public static boolean runsInYear(CalendarEvent e, int year) {
        int every = Math.max(1, e.everyYears);
        if (every == 1) return true;
        return Math.floorMod(year - e.yearOffset, every) == 0;
    }

    /**
     * Is a CALENDAR event in progress on this date? Handles month 0 (= every month), events that
     * spill over the end of a month into the next, and events that wrap from Late Winter into the
     * new year. Real-time events answer false.
     */
    public static boolean occursOn(CalendarEvent e, int year, int month, int day) {
        return dayOfEvent(e, year, month, day) > 0;
    }

    /**
     * Which day of the event this date is (1 = first day), or 0 when the event is not running.
     */
    public static int dayOfEvent(CalendarEvent e, int year, int month, int day) {
        if (isRealtime(e) || e.day <= 0) return 0;
        int dur = Math.max(1, e.durationDays);
        int idx = dayIndex(month, day);
        if (e.month == 0) {
            // starts on day e.day of EVERY month; check this month's start and the previous month's spill-over
            for (int back = 0; back <= (dur - 1) / DAYS_PER_MONTH + 1; back++) {
                int startMonth = month - back;
                int y = year;
                while (startMonth < 1) { startMonth += MONTHS; y--; }
                int start = dayIndex(startMonth, Math.min(e.day, DAYS_PER_MONTH));
                int rel = (idx - start) + (y < year ? DAYS_PER_YEAR * (year - y) : 0);
                if (rel >= 0 && rel < dur && runsInYear(e, y)) return rel + 1;
            }
            return 0;
        }
        if (e.month < 1 || e.month > MONTHS) return 0;
        int start = dayIndex(e.month, Math.min(e.day, DAYS_PER_MONTH));
        int rel = idx - start;
        if (rel >= 0 && rel < dur && runsInYear(e, year)) return rel + 1;
        // started last year and wrapped past Late Winter 31st
        int relWrapped = idx + DAYS_PER_YEAR - start;
        if (relWrapped >= 0 && relWrapped < dur && runsInYear(e, year - 1)) return relWrapped + 1;
        return 0;
    }

    /**
     * How far ahead the Upcoming list looks: 200 SkyBlock years. Far enough for the century
     * celebration (every 100 years), short of the "forced-only" events the mayors module parks a
     * million years away so they only ever run when a perk forces them on.
     */
    public static final int HORIZON_YEARS = 200;

    public static long horizonMillis(long millisPerDay) { return HORIZON_YEARS * (long) DAYS_PER_YEAR * Math.max(1, millisPerDay); }

    /**
     * Should an event appear in the Upcoming list? Running events always do; otherwise core's
     * {@code millisUntil} must be a real countdown (it is -1 for "never" and -1 must not read as "in 0s")
     * inside the horizon.
     */
    public static boolean listable(boolean active, long millisUntil, long horizonMs) {
        return active || (millisUntil >= 0 && millisUntil <= horizonMs);
    }

    /**
     * Key two registrations of the SAME occurrence share (mayors and progression both register a
     * "New Year Celebration" for Late Winter 29th): same name, same start minute.
     */
    public static String dedupeKey(String name, boolean active, long millisUntil) {
        String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        return n + "|" + (active ? "active" : String.valueOf(Math.round(millisUntil / 60_000.0)));
    }

    /** Fraction of the current day already gone, from the date's hour and minute. */
    public static double dayFraction(SkyDate d) { return (d.hour() * 60 + d.minute()) / 1440.0; }

    /**
     * Real milliseconds from {@code now} until the START of the given day (0 if that day is today or
     * in the past within this year; wraps into next year when {@code allowNextYear}).
     */
    public static long millisUntilDay(SkyDate now, int month, int day, long millisPerDay, boolean allowNextYear) {
        int from = dayIndex(now.month(), now.day());
        int to = dayIndex(month, day);
        int diff = to - from;
        if (diff <= 0) {
            if (!allowNextYear || diff == 0) return 0;
            diff += DAYS_PER_YEAR;
        }
        double ms = (diff - dayFraction(now)) * millisPerDay;
        return Math.max(0, Math.round(ms));
    }

    /** Real hours in one SkyBlock year (research: 124 at the default 20-minute day). */
    public static double realHoursPerYear(long millisPerDay) { return DAYS_PER_YEAR * (double) millisPerDay / 3_600_000.0; }

    /** Real seconds in one SkyBlock hour (research: 50 at the default 20-minute day). */
    public static double realSecondsPerHour(long millisPerDay) { return millisPerDay / 24.0 / 1000.0; }

    /** Month offset helper: (month, year) shifted by {@code delta} months. Returns {month, year}. */
    public static int[] shiftMonth(int month, int year, int delta) {
        int m0 = (month - 1) + delta;
        int y = year + Math.floorDiv(m0, MONTHS);
        int m = Math.floorMod(m0, MONTHS) + 1;
        return new int[]{m, y};
    }

    /** Season name of a month (1-12). */
    public static String season(int month) {
        return switch ((month - 1) / 3) { case 0 -> "Spring"; case 1 -> "Summer"; case 2 -> "Autumn"; default -> "Winter"; };
    }

    /** Season colour code char for a month: spring green, summer yellow, autumn gold, winter aqua. */
    public static char seasonColor(int month) {
        return switch ((month - 1) / 3) { case 0 -> 'a'; case 1 -> 'e'; case 2 -> '6'; default -> 'b'; };
    }

    /**
     * Human schedule of an event: "Every year: Autumn 29th to 31st (3 days)", "Every month on the 1st",
     * "Every 60 minutes, lasts 5 minutes".
     */
    public static String schedule(CalendarEvent e) {
        if (isRealtime(e)) {
            String every = e.intervalMinutes % 60 == 0 && e.intervalMinutes >= 60
                    ? (e.intervalMinutes == 60 ? "Every hour" : "Every " + e.intervalMinutes / 60 + " hours")
                    : "Every " + e.intervalMinutes + " minutes";
            return every + (e.durationMinutes > 0 ? ", lasts " + e.durationMinutes + " min" : "");
        }
        int dur = Math.max(1, e.durationDays);
        String years = Math.max(1, e.everyYears) == 1 ? "Every year" : "Every " + e.everyYears + " years";
        if (e.month == 0) {
            return "Every month: " + SkyDate.ordinal(e.day) + (dur > 1 ? " (" + dur + " days)" : "");
        }
        if (e.month < 1 || e.month > MONTHS) return "Unscheduled";
        String start = SkyDate.MONTHS[e.month - 1] + " " + SkyDate.ordinal(e.day);
        if (dur == 1) return years + ": " + start;
        int endIdx = Math.floorMod(dayIndex(e.month, e.day) + dur - 1, DAYS_PER_YEAR);
        int endMonth = endIdx / DAYS_PER_MONTH + 1, endDay = endIdx % DAYS_PER_MONTH + 1;
        String end = endMonth == e.month ? SkyDate.ordinal(endDay) : SkyDate.MONTHS[endMonth - 1] + " " + SkyDate.ordinal(endDay);
        return years + ": " + start + " to " + end + " (" + dur + " days)";
    }

    /** The SkyBlock date {@code ms} real milliseconds after {@code now} (minute precision). */
    public static SkyDate dateAfter(SkyDate now, long ms, long millisPerDay) {
        double days = dayFraction(now) + (double) ms / Math.max(1, millisPerDay);
        int whole = (int) Math.floor(days);
        int idx = dayIndex(now.month(), now.day()) + whole;
        int year = now.year() + Math.floorDiv(idx, DAYS_PER_YEAR);
        idx = Math.floorMod(idx, DAYS_PER_YEAR);
        int minutes = (int) Math.min(1439, Math.round((days - whole) * 1440));
        return new SkyDate(year, idx / DAYS_PER_MONTH + 1, idx % DAYS_PER_MONTH + 1, minutes / 60, minutes % 60);
    }

    /** Every event (calendar-based) running on a date, in the given order. */
    public static List<CalendarEvent> on(Iterable<CalendarEvent> events, int year, int month, int day) {
        List<CalendarEvent> out = new ArrayList<>();
        for (CalendarEvent e : events) if (occursOn(e, year, month, day)) out.add(e);
        return out;
    }
}
