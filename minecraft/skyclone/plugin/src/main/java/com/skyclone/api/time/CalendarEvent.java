package com.skyclone.api.time;

/**
 * A recurring event on the calendar. Either CALENDAR-based (month/day/durationDays, every
 * {@code everyYears}) or REAL-TIME-based ({@code intervalMinutes} &gt; 0: starts every N real
 * minutes at {@code offsetMinutes} past, lasts {@code durationMinutes}).
 *
 * <p>Examples: Spooky Festival = month 8 (Autumn) day 29, 3 days. Dark auction = every 60 real
 * minutes at offset 55, duration 5. Core fires SkyEvents.CalendarStart / CalendarEnd.
 */
/*
 * Core behaviour worth knowing (gate, 2026-10-02): month 0 = EVERY month (day/duration apply each
 * month). On the first tick after a server boot, every event already in progress receives a
 * CalendarStart - so handle "started" idempotently. Duplicate ids: the first registration wins.
 */
public class CalendarEvent {
    public String id;
    public String name;
    public int month;
    public int day;
    public int durationDays = 1;
    public int everyYears = 1;
    public int yearOffset;
    public int intervalMinutes;
    public int offsetMinutes;
    public int durationMinutes;

    public static CalendarEvent onDate(String id, String name, int month, int day, int durationDays) {
        CalendarEvent e = new CalendarEvent();
        e.id = id; e.name = name; e.month = month; e.day = day; e.durationDays = durationDays;
        return e;
    }

    public static CalendarEvent realtime(String id, String name, int intervalMinutes, int offsetMinutes, int durationMinutes) {
        CalendarEvent e = new CalendarEvent();
        e.id = id; e.name = name; e.intervalMinutes = intervalMinutes;
        e.offsetMinutes = offsetMinutes; e.durationMinutes = durationMinutes;
        return e;
    }
}
