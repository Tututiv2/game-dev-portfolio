package com.thestill.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * Money formatting and parsing. The only place that knows money is stored in
 * minor units.
 *
 * <p>Everything is a {@code long} count of cents. A double balance is how a
 * single {@code NaN} permanently poisoned an account on Shadow Prison — with an
 * integer type that failure is unstateable rather than merely guarded against —
 * and it also removes the slow drift where repeated float arithmetic leaves a
 * player on $999.9999999.
 */
public final class Money {

    /** Cents per dollar. Prices like $0.25 per filler block need the precision. */
    public static final long MINOR_PER_UNIT = 100L;

    private static final DecimalFormat GROUPED = new DecimalFormat("#,##0");
    private static final DecimalFormat GROUPED_CENTS = new DecimalFormat("#,##0.00");
    private static final DecimalFormat COMPACT = new DecimalFormat("0.##");

    private Money() {
    }

    public static long fromUnits(double units) {
        if (!Double.isFinite(units)) {
            throw new IllegalArgumentException("non-finite amount: " + units);
        }
        return BigDecimal.valueOf(units)
                .multiply(BigDecimal.valueOf(MINOR_PER_UNIT))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static double toUnits(long minor) {
        return (double) minor / MINOR_PER_UNIT;
    }

    /** {@code "$1,234"}, or {@code "$12.50"} when the cents actually matter. */
    public static String format(long minor) {
        boolean negative = minor < 0;
        long magnitude = Math.abs(minor);
        long whole = magnitude / MINOR_PER_UNIT;
        long cents = magnitude % MINOR_PER_UNIT;
        String body = cents == 0
                ? GROUPED.format(whole)
                : GROUPED_CENTS.format(magnitude / (double) MINOR_PER_UNIT);
        return (negative ? "-$" : "$") + body;
    }

    /**
     * {@code "$1.4M"} — for the sidebar, where a full number would push the
     * line off the edge of the board.
     */
    public static String compact(long minor) {
        boolean negative = minor < 0;
        double units = Math.abs(minor) / (double) MINOR_PER_UNIT;
        String sign = negative ? "-$" : "$";
        if (units < 1_000d) {
            return sign + COMPACT.format(units);
        }
        if (units < 1_000_000d) {
            return sign + COMPACT.format(units / 1_000d) + "K";
        }
        if (units < 1_000_000_000d) {
            return sign + COMPACT.format(units / 1_000_000d) + "M";
        }
        if (units < 1_000_000_000_000d) {
            return sign + COMPACT.format(units / 1_000_000_000d) + "B";
        }
        return sign + COMPACT.format(units / 1_000_000_000_000d) + "T";
    }

    /**
     * Parses an amount as typed by an admin: {@code 500}, {@code 12.50},
     * {@code 1.4m}, {@code 250k}.
     *
     * @return the amount in minor units, or null if it is not a usable amount
     */
    public static Long parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase().replace(",", "").replace("$", "");
        if (text.isEmpty()) {
            return null;
        }
        long multiplier = 1L;
        char suffix = text.charAt(text.length() - 1);
        switch (suffix) {
            case 'k' -> multiplier = 1_000L;
            case 'm' -> multiplier = 1_000_000L;
            case 'b' -> multiplier = 1_000_000_000L;
            case 't' -> multiplier = 1_000_000_000_000L;
            default -> {
            }
        }
        if (multiplier > 1L) {
            text = text.substring(0, text.length() - 1);
        }
        try {
            BigDecimal units = new BigDecimal(text).multiply(BigDecimal.valueOf(multiplier));
            BigDecimal minor = units.multiply(BigDecimal.valueOf(MINOR_PER_UNIT))
                    .setScale(0, RoundingMode.HALF_UP);
            // longValueExact throws rather than silently wrapping on overflow.
            return minor.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
