package com.auraqol.shadow;

import com.auraqol.skyblock.Fmt;

/**
 * Formatting for the Shadow panels. Pure. Money arrives in integer cents and
 * is only ever divided for DISPLAY here; nothing is computed from a formatted
 * string.
 */
public final class ShadowFmt {

    private ShadowFmt() {
    }

    /** "$1.24M", "-$5.00", "$950" - compact dollars from cents, sign before the $. */
    public static String money(double cents) {
        if (Double.isNaN(cents) || Double.isInfinite(cents)) {
            return "?";
        }
        double dollars = cents / 100.0;
        String sign = dollars < 0 ? "-" : "";
        double a = Math.abs(dollars);
        if (a < 10 && a != Math.rint(a)) {
            return sign + "$" + String.format(java.util.Locale.ROOT, "%.2f", a);
        }
        return sign + "$" + Fmt.compact(a);
    }

    /** "+$1.24M" for gains, "-$500" for losses, "$0" for nothing. */
    public static String signedMoney(double cents) {
        return cents > 0 ? "+" + money(cents) : money(cents);
    }

    /** Count, compact: 950, 12.3k, 1.24M. */
    public static String count(double n) {
        return Fmt.compact(n);
    }

    /** Contract tiers are Roman numerals in the server's own menus (SlayerService.roman). */
    public static String roman(long n) {
        if (n <= 0 || n > 3999) {
            return Long.toString(n);
        }
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        long left = n;
        for (int i = 0; i < v.length; i++) {
            while (left >= v[i]) {
                sb.append(s[i]);
                left -= v[i];
            }
        }
        return sb.toString();
    }

    /** "42.5%" from a 0-1000 permille, exactly as the bar shows it. */
    public static String permille(long p) {
        long c = Math.max(0, Math.min(1000, p));
        return c % 10 == 0 ? (c / 10) + "%" : (c / 10) + "." + (c % 10) + "%";
    }
}
