package com.skyclone.api.time;

/**
 * A moment on the in-game calendar. One in-game DAY is 20 real minutes; a month is 31 days;
 * a year is 12 months (372 days = 124 real hours). Months are named by season:
 * Early Spring, Spring, Late Spring, Early Summer, Summer, Late Summer,
 * Early Autumn, Autumn, Late Autumn, Early Winter, Winter, Late Winter.
 */
public record SkyDate(int year, int month, int day, int hour, int minute) {

    public static final String[] MONTHS = {
            "Early Spring", "Spring", "Late Spring", "Early Summer", "Summer", "Late Summer",
            "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"
    };

    public String monthName() { return MONTHS[month - 1]; }

    /** "Early Spring 5th" */
    public String dayLabel() { return monthName() + " " + ordinal(day); }

    /** "2:30pm" — minutes rounded down to 10 like the genre's sidebar. */
    public String timeLabel() {
        int h12 = hour % 12 == 0 ? 12 : hour % 12;
        int m = (minute / 10) * 10;
        return h12 + ":" + (m < 10 ? "0" + m : m) + (hour < 12 ? "am" : "pm");
    }

    /** Night is 7pm to 6am. */
    public boolean isNight() { return hour >= 19 || hour < 6; }

    /** 0 = spring, 1 = summer, 2 = autumn, 3 = winter */
    public int season() { return (month - 1) / 3; }

    public static String ordinal(int n) {
        int m100 = n % 100;
        if (m100 >= 11 && m100 <= 13) return n + "th";
        return switch (n % 10) { case 1 -> n + "st"; case 2 -> n + "nd"; case 3 -> n + "rd"; default -> n + "th"; };
    }
}
