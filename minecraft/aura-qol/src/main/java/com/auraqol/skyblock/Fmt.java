package com.auraqol.skyblock;

import java.util.Locale;

/**
 * Number and time formatting for the SkyBlock features. Pure: no Minecraft.
 *
 * <p>Two coin formats on purpose. A tooltip is read once, closely, by someone
 * deciding whether to sell - so it gets the exact figure with grouping
 * ({@code 442,000,000}). A HUD is read at a glance mid-fight - so it gets
 * three significant figures and a suffix ({@code 1.24M}). Using one format
 * for both makes one of them wrong.
 */
public final class Fmt {

    private Fmt() {
    }

    /**
     * Exact coins with grouping. Whole numbers above 100 lose the decimal
     * (nobody cares about 0.4 of a coin on a 1,368-coin item); below that one
     * decimal is kept, because 3.6 vs 4 is the whole price of a cheap item.
     */
    public static String coins(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "?";
        }
        double abs = Math.abs(v);
        if (abs >= 100 || abs == Math.rint(abs)) {
            return String.format(Locale.ROOT, "%,d", Math.round(v));
        }
        return String.format(Locale.ROOT, "%,.1f", v);
    }

    /**
     * Compact coins: 950, 20.6k, 1.24M, 3.10B. Always three significant
     * figures above a thousand so the width barely changes as it ticks, which
     * is what stops a HUD number from jittering its box every second.
     */
    public static String compact(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "?";
        }
        String sign = v < 0 ? "-" : "";
        double a = Math.abs(v);
        if (a < 1_000) {
            return sign + Math.round(a);
        }
        String[] suffix = {"k", "M", "B", "T"};
        double scaled = a;
        int i = -1;
        while (scaled >= 999.5 && i < suffix.length - 1) {
            scaled /= 1000.0;
            i++;
        }
        String num;
        if (scaled >= 99.95) {
            num = String.format(Locale.ROOT, "%.0f", scaled);
        } else if (scaled >= 9.995) {
            num = String.format(Locale.ROOT, "%.1f", scaled);
        } else {
            num = String.format(Locale.ROOT, "%.2f", scaled);
        }
        return sign + num + suffix[i];
    }

    /** "+1.24M" / "-300" - compact with an explicit sign, for deltas. */
    public static String signedCompact(double v) {
        if (v > 0) {
            return "+" + compact(v);
        }
        return compact(v);
    }

    /** "42s", "5m 07s", "1h 02m". Seconds disappear once hours appear. */
    public static String duration(long millis) {
        long s = Math.max(0, millis / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", h, m);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", m, sec);
        }
        return sec + "s";
    }

    /** "3h ago" style, for how stale a price is. */
    public static String age(long millis) {
        long s = Math.max(0, millis / 1000);
        if (s < 90) {
            return s + "s";
        }
        long m = s / 60;
        if (m < 90) {
            return m + "m";
        }
        long h = m / 60;
        if (h < 48) {
            return h + "h";
        }
        return (h / 24) + "d";
    }

    /**
     * An item name as the price data spells it: colour codes gone, Hypixel's
     * private-use font glyphs (U+E000..U+F8FF - the modern client's stat and
     * gemstone icons, e.g. the Magic Find star) and emoji gone, NBSP made a
     * space, runs of spaces collapsed, and a leading symbol token ("❂ Fine
     * Jade Gemstone") dropped. Null in, "" out.
     */
    public static String plainName(String s) {
        if (s == null) {
            return "";
        }
        String t = strip(s);
        StringBuilder sb = new StringBuilder(t.length());
        boolean space = false;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isSupplementaryCodePoint(cp) || (cp >= 0xE000 && cp <= 0xF8FF)
                    || cp == 0xFE0F || cp == 0x200B) {
                continue;
            }
            if (cp == 0xA0 || Character.isWhitespace(cp)) {
                space = true;
                continue;
            }
            if (space && sb.length() > 0) {
                sb.append(' ');
            }
            space = false;
            sb.appendCodePoint(cp);
        }
        String out = sb.toString();
        // A leading token with no letter or digit in it is an icon, not a word.
        int sp = out.indexOf(' ');
        if (sp > 0) {
            String first = out.substring(0, sp);
            boolean word = false;
            for (int i = 0; i < first.length(); i++) {
                if (Character.isLetterOrDigit(first.charAt(i))) {
                    word = true;
                    break;
                }
            }
            if (!word) {
                out = out.substring(sp + 1);
            }
        }
        return out;
    }

    /** Strip Minecraft legacy formatting codes ({@code §a}, {@code §l}, ...). */
    public static String strip(String s) {
        if (s == null || s.indexOf('§') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++; // skip the code character too
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
