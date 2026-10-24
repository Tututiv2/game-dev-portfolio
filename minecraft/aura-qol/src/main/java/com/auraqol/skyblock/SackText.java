package com.auraqol.skyblock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the sack GUIs: which menus are sacks, and what each item's lore says
 * is stored. Every pattern is the colour-less form of SkyHanni's
 * ({@code constants/regexesModern.json}, key cited per pattern). Pure.
 */
public final class SackText {

    private SackText() {
    }

    /**
     * {@code data.sacks.sack}: "^(?:.* Sack|Enchanted .* Sack)$" - the per-sack menus ("Agronomy Sack",
     * "Large Enchanted Mining Sack", "Gemstones Sack"), plus the /sacks overview ("Sacks", "Sack of Sacks")
     * and a page prefix like "(1/2) ".
     */
    private static final Pattern SACK_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?(?:.* Sack|Sacks|Sack of Sacks)$");

    public static boolean isSackMenu(String title) {
        if (title == null) {
            return false;
        }
        return SACK_TITLE.matcher(Fmt.plainName(title)).matches();
    }

    /**
     * {@code data.sacks.number}: "(?:(?:§[0-9a-f](?<level>I{1,3})§7:)?|(?:§7Stored:)?) (?<color>§[0-9a-f])(?<stored>[0-9.,kKmMbB]+)§7/(?<total>\d+(?:[0-9.,]+)?[kKmMbB]?)"
     * - colour-less, the "Stored:" form only ("Stored: 12,345/60.4k"); the "I:/II:/III:" level rows are the
     * rune sack and have no single count.
     */
    private static final Pattern STORED = Pattern.compile(
            "^\\s*Stored:\\s*(?<stored>[0-9.,kKmMbB]+)\\s*/\\s*(?<total>[0-9][0-9.,]*[kKmMbB]?)\\s*$");

    /** The "Stored: X/Y" count in a sack item's lore, or -1 if there is none. */
    public static long stored(List<String> lore) {
        if (lore == null) {
            return -1;
        }
        for (String raw : lore) {
            Matcher m = STORED.matcher(MoneyChat.clean(raw));
            if (m.matches()) {
                return amount(m.group("stored"));
            }
        }
        return -1;
    }

    /**
     * {@code data.sacks.gemstone.name}: "(?:(?:§.)?. |§.)(?:(?:Rough|Flawed|Fine) )?(?<gem>[^ ]+) Gemstones?"
     * - the icon prefix is dropped by {@link Fmt#plainName} first. Returns the gem upper-cased ("RUBY"), or null.
     */
    private static final Pattern GEM_NAME = Pattern.compile(
            "^(?:(?:Rough|Flawed|Fine) )?(?<gem>[A-Za-z]+) Gemstones?$");

    public static String gemType(String itemName) {
        if (itemName == null) {
            return null;
        }
        Matcher m = GEM_NAME.matcher(Fmt.plainName(itemName));
        return m.matches() ? m.group("gem").toUpperCase(Locale.ROOT) : null;
    }

    /**
     * {@code data.sacks.gemstone.count}: " (?:§.)?(?<quality>[A-z]*): §[0-9a-f](?<stored>\d+(?:\.\d+)?(?:(?:,\d+)?)+[kKmM]?)(?: §[0-9a-f]\(\d+...\))?"
     * - " Rough: 1,234 (15.2k)". Returns ROUGH/FLAWED/FINE -> count.
     */
    private static final Pattern GEM_COUNT = Pattern.compile(
            "^\\s*(?<quality>[A-Za-z]+): (?<stored>[0-9][0-9.,]*[kKmM]?)(?: \\([0-9][0-9.,]*[kKmM]?\\))?\\s*$");

    public static Map<String, Long> gemCounts(List<String> lore) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (lore == null) {
            return out;
        }
        for (String raw : lore) {
            Matcher m = GEM_COUNT.matcher(MoneyChat.clean(raw));
            if (!m.matches()) {
                continue;
            }
            String q = m.group("quality").toUpperCase(Locale.ROOT);
            if (q.equals("ROUGH") || q.equals("FLAWED") || q.equals("FINE")) {
                out.put(q, amount(m.group("stored")));
            }
        }
        return out;
    }

    /** "12,345" -> 12345, "60.4k" -> 60400, "1.2M" -> 1200000; -1 if unreadable. */
    public static long amount(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        String t = s.trim().replace(",", "");
        double mult = 1;
        char last = Character.toLowerCase(t.charAt(t.length() - 1));
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            t = t.substring(0, t.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(t) * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
