package com.skyclone.api.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text helpers. The whole game writes text with legacy '&amp;' codes ("&amp;6&amp;lLEGENDARY") because
 * that is the genre's visual language; this turns them into components with italics OFF (item
 * lore is italic by default in vanilla, and the genre never is). "&amp;#RRGGBB" hex is supported.
 */
public final class Text {

    private Text() {}

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final LegacyComponentSerializer SECTION =
            LegacyComponentSerializer.builder().character('§').hexColors().build();

    /** '&amp;' codes to a non-italic component. */
    public static Component c(String legacy) {
        if (legacy == null) return Component.empty();
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> lines(List<String> legacy) {
        List<Component> out = new ArrayList<>(legacy.size());
        for (String s : legacy) out.add(c(s));
        return out;
    }

    /** '&amp;' codes to '§' codes (for places that still take raw strings, e.g. scoreboard entries). */
    public static String section(String legacy) { return legacy == null ? "" : legacy.replace('&', '§'); }

    public static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }

    /** Remove all colour codes. */
    public static String strip(String legacy) {
        if (legacy == null) return "";
        return legacy.replaceAll("(?i)[&§]#[0-9a-f]{6}", "").replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }

    public static String toLegacy(Component c) { return LEGACY.serialize(c); }

    // ---- numbers --------------------------------------------------------------------------

    private static final ThreadLocal<DecimalFormat> COMMAS =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US)));
    private static final ThreadLocal<DecimalFormat> COMMAS_INT =
            ThreadLocal.withInitial(() -> new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)));

    /** 1234567.5 -> "1,234,567.5"; 12.0 -> "12" */
    public static String commas(double v) { return COMMAS.get().format(v); }

    public static String commasInt(double v) { return COMMAS_INT.get().format(Math.floor(v)); }

    /** 1234 -> "1.2k", 2500000 -> "2.5M", 3e9 -> "3B" */
    public static String shortNum(double v) {
        double a = Math.abs(v);
        String[] units = {"", "k", "M", "B", "T"};
        int i = 0;
        while (a >= 1000 && i < units.length - 1) { a /= 1000; i++; }
        String n = (a >= 100 || i == 0) ? String.valueOf((long) Math.floor(a)) : new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US)).format(Math.floor(a * 10) / 10);
        return (v < 0 ? "-" : "") + n + units[i];
    }

    private static final int[] ROMAN_V = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_S = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    public static String roman(int n) {
        if (n <= 0) return String.valueOf(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ROMAN_V.length; i++) while (n >= ROMAN_V[i]) { n -= ROMAN_V[i]; sb.append(ROMAN_S[i]); }
        return sb.toString();
    }

    /** Genre progress bar: "&amp;2&amp;l&amp;m          &amp;f&amp;l&amp;m          " — {@code len} dashes, filled part green. */
    public static String bar(double fraction, int len) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * len);
        return "&2&l&m" + " ".repeat(filled) + "&f&l&m" + " ".repeat(len - filled) + "&r";
    }

    /** Simple "-----" style bar for places strikethrough looks wrong (action bar): green '-' then white '-'. */
    public static String dashBar(double fraction, int len) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, fraction)) * len);
        return "&a" + "-".repeat(filled) + "&f" + "-".repeat(len - filled);
    }

    /** 3725 -> "1h 2m 5s"; 59 -> "59s" */
    public static String duration(long seconds) {
        if (seconds < 0) seconds = 0;
        long d = seconds / 86400, h = seconds % 86400 / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 && d == 0) sb.append(m).append("m ");
        if (s > 0 && d == 0 && h == 0) sb.append(s).append("s");
        String r = sb.toString().trim();
        return r.isEmpty() ? "0s" : r;
    }

    /** "ENCHANTED_DIAMOND" -> "Enchanted Diamond" */
    public static String titleCase(String id) {
        StringBuilder sb = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("[_ ]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Wrap a long description to ~{@code width} visible chars per line, carrying the last colour code. */
    public static List<String> wrap(String legacy, int width) {
        List<String> out = new ArrayList<>();
        String carry = "&7";
        StringBuilder line = new StringBuilder();
        int visible = 0;
        for (String word : legacy.split(" ")) {
            int wv = strip(word).length();
            if (visible > 0 && visible + 1 + wv > width) {
                out.add(line.toString());
                line = new StringBuilder(carry);
                visible = 0;
            }
            if (visible > 0) { line.append(' '); visible++; }
            line.append(word);
            visible += wv;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("&[0-9a-fk-or]").matcher(word);
            while (m.find()) carry = m.group().equals("&r") ? "&7" : m.group();
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
