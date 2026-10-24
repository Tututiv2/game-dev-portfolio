package com.shadowprison.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * Money is a {@code long} count of CENTS. There is no floating-point balance anywhere in this
 * plugin, which makes Shadow Prison 1's whole NaN/Infinity family (golden rule 27 — a NaN typed
 * into /gang bank made every later "bank &lt; amount" check pass: infinite money) unstateable.
 *
 * <p>Players may type shorthand like Hypixel: {@code 1.5k}, {@code 2m}, {@code 3b}, {@code 1,250}.
 */
public final class Money {

    /** 1 quadrillion coins. Far above any economy; far below long overflow even after multiplying. */
    public static final long MAX_CENTS = 100_000_000_000_000_000L;

    private Money() {
    }

    public static long ofWhole(long coins) {
        return Math.multiplyExact(coins, 100L);
    }

    /**
     * Strictly parses player input. Empty for anything that is not a finite, non-negative amount
     * with at most two decimals after expansion — "NaN", "Infinity", "-5", "1e999", "1.234" all fail.
     */
    public static Optional<Long> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").replace("_", "");
        if (s.isEmpty()) {
            return Optional.empty();
        }
        long multiplier = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> multiplier = 1_000L;
            case 'm' -> multiplier = 1_000_000L;
            case 'b' -> multiplier = 1_000_000_000L;
            case 't' -> multiplier = 1_000_000_000_000L;
            default -> {
            }
        }
        if (multiplier != 1) {
            s = s.substring(0, s.length() - 1);
        }
        // Only digits and one dot. Rejects NaN/Infinity/exponents/signs before BigDecimal sees them.
        if (!s.matches("[0-9]+(\\.[0-9]+)?|\\.[0-9]+")) {
            return Optional.empty();
        }
        try {
            BigDecimal coins = new BigDecimal(s).multiply(BigDecimal.valueOf(multiplier));
            BigDecimal cents = coins.movePointRight(2);
            if (cents.stripTrailingZeros().scale() > 0) {
                return Optional.empty();   // finer than a cent
            }
            if (cents.compareTo(BigDecimal.valueOf(MAX_CENTS)) > 0) {
                return Optional.empty();
            }
            return Optional.of(cents.longValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** {@code $1,234,567} — cents only when there are some: {@code $12.50}. */
    public static String format(long cents) {
        long whole = cents / 100;
        long frac = Math.abs(cents % 100);
        String body = String.format(Locale.ROOT, "%,d", whole);
        return "$" + (frac == 0 ? body : body + "." + String.format(Locale.ROOT, "%02d", frac));
    }

    /** {@code $1.25M} — for sidebars and tight lore. */
    public static String compact(long cents) {
        double coins = cents / 100.0;
        String[] units = {"", "K", "M", "B", "T", "Q"};
        int u = 0;
        while (Math.abs(coins) >= 1000 && u < units.length - 1) {
            coins /= 1000;
            u++;
        }
        if (u == 0) {
            return format(cents);
        }
        BigDecimal shown = BigDecimal.valueOf(coins).setScale(coins >= 100 ? 0 : coins >= 10 ? 1 : 2, RoundingMode.DOWN);
        return "$" + shown.stripTrailingZeros().toPlainString() + units[u];
    }

    /** cents x a multiplier in basis points (10000 = 1.00x), rounded down. Overflow-safe. */
    public static long scaleBp(long cents, long basisPoints) {
        return BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(MAX_CENTS)).longValueExact();
    }
}
