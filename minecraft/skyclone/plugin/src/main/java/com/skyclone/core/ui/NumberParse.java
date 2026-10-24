package com.skyclone.core.ui;

import java.util.Locale;

/**
 * Pure parser for number input ("64", "1.5k", "2m", "3b", "1,234"). No Bukkit.
 * Rejects negatives, NaN, infinities, empty strings and garbage by returning null.
 */
public final class NumberParse {

    private NumberParse() {}

    /** Largest value accepted (one quadrillion); anything bigger is treated as garbage. */
    public static final double MAX = 1e15;

    public static Double parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (s.isEmpty() || s.length() > 32) return null;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            default -> { }
        }
        if (mult != 1) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty()) return null;
        // Only digits and a single dot: refuses "1e5", "0x10", "+5", "-5", "nan", "infinity".
        int dots = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') { if (++dots > 1) return null; }
            else if (c < '0' || c > '9') return null;
        }
        if (s.equals(".")) return null;
        double v;
        try { v = Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        v *= mult;
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > MAX) return null;
        return v;
    }
}
