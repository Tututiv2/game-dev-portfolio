package com.skyclone.core.foundation;

/**
 * Pure coin arithmetic. Coins are stored as a long count of TENTHS (12.5 coins = 125) so no
 * amount is ever a binary fraction and nothing drifts over millions of transactions.
 *
 * <p>Rounding rules are chosen so the server can never mint money by rounding:
 * credits round DOWN to the tenth, debits round UP. A value that is within 1e-6 of a tenth
 * (0.3 * 10 = 2.9999999999999996) counts as that tenth exactly.
 */
public final class Coins {

    private Coins() {}

    /** Hard ceiling for any single amount and any balance: one quadrillion coins. */
    public static final long MAX_TENTHS = 10_000_000_000_000_000L;

    /** True for a finite, non-negative amount small enough to store. */
    public static boolean valid(double amount) {
        return !Double.isNaN(amount) && !Double.isInfinite(amount) && amount >= 0 && amount * 10.0 <= MAX_TENTHS;
    }

    /** Amount being GIVEN to someone: rounds down. Throws on an invalid amount. */
    public static long creditTenths(double amount) {
        if (!valid(amount)) throw new IllegalArgumentException("invalid coin amount " + amount);
        double x = amount * 10.0;
        long r = Math.round(x);
        if (Math.abs(x - r) < 1e-6) return r;
        return (long) Math.floor(x);
    }

    /** Amount being TAKEN from someone: rounds up. Throws on an invalid amount. */
    public static long debitTenths(double amount) {
        if (!valid(amount)) throw new IllegalArgumentException("invalid coin amount " + amount);
        double x = amount * 10.0;
        long r = Math.round(x);
        if (Math.abs(x - r) < 1e-6) return r;
        return (long) Math.ceil(x);
    }

    public static double toCoins(long tenths) { return tenths / 10.0; }

    /** a + b, refusing to pass MAX_TENTHS (returns -1 when it would). */
    public static long addCapped(long a, long b) {
        if (b < 0 || a < 0) throw new IllegalArgumentException("negative operand");
        if (a > MAX_TENTHS - b) return -1;
        return a + b;
    }

    /** "1,234,567.5" — drops ".0". Works on any double (rounded to the nearest tenth). */
    public static String format(double coins) {
        if (Double.isNaN(coins)) return "0";
        if (Double.isInfinite(coins)) return coins > 0 ? "∞" : "-∞";
        long t = Math.round(coins * 10.0);
        return formatTenths(t);
    }

    public static String formatTenths(long tenths) {
        boolean neg = tenths < 0;
        long abs = neg ? -tenths : tenths;
        long whole = abs / 10;
        long frac = abs % 10;
        String w = groupThousands(whole);
        return (neg ? "-" : "") + w + (frac != 0 ? "." + frac : "");
    }

    static String groupThousands(long n) {
        String s = Long.toString(n);
        StringBuilder sb = new StringBuilder(s.length() + s.length() / 3);
        int first = s.length() % 3;
        if (first == 0) first = 3;
        sb.append(s, 0, first);
        for (int i = first; i < s.length(); i += 3) sb.append(',').append(s, i, i + 3);
        return sb.toString();
    }

    /**
     * Parse a player/admin typed amount: "1500", "1,500", "2.5k", "3m", "1.2b". Returns NaN on
     * garbage, never throws.
     */
    public static double parse(String s) {
        if (s == null) return Double.NaN;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT).replace(",", "").replace("_", "");
        if (t.isEmpty()) return Double.NaN;
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        switch (last) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            default -> { }
        }
        if (mult != 1) t = t.substring(0, t.length() - 1);
        try {
            double v = Double.parseDouble(t) * mult;
            return Double.isFinite(v) ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
