package com.skyclone.mod.bazaar;

import java.util.Locale;
import java.util.Set;

/**
 * Pure: turns what an admin TYPED into {@code bazaar.access-requirement} into a requirement key ("" = everyone).
 *
 * <p>Why it exists (wave-3 playtest, AI show): the dial said "Empty = everyone", but empty cannot be typed.
 * {@code /sb dial set <key>} needs a value, so an empty YAML value in a ruleset is refused as "Usage", and the
 * obvious words — {@code none}, {@code off}, {@code ""} — were stored literally and handed to the requirement
 * registry, which fails CLOSED on an unknown prefix: the Bazaar shut for EVERYONE, the opposite of what was asked.
 * So the open state is spelled with words here, and a bare number is a SkyBlock Level, which is what a show (or
 * a server that wants the Bazaar sooner than research Level 7) actually wants to say: {@code /sb dial set
 * bazaar.access-requirement 3}.
 */
final class Gate {

    private Gate() {}

    /** Values that mean "no requirement" (compared lower case, after stripping quotes). */
    static final Set<String> OPEN = Set.of("", "none", "off", "open", "everyone", "anyone", "all", "no", "false", "-", "0", "null");

    /** The requirement key for a typed dial value: "" = everyone, a bare number N = {@code sblevel:N}. */
    static String key(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        // strip one layer of matching quotes: a ruleset value of "" arrives as the two characters ""
        while (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))))
            t = t.substring(1, t.length() - 1).trim();
        if (OPEN.contains(t.toLowerCase(Locale.ROOT))) return "";
        if (t.chars().allMatch(Character::isDigit)) {
            long n;
            try { n = Long.parseLong(t); } catch (NumberFormatException e) { return t; }
            return n <= 0 ? "" : "sblevel:" + n;
        }
        return t;
    }

    /** Is buying this product behind the essence gate (an {@code ESSENCE_*} id not in the exempt list)? */
    static boolean essenceGated(String pid, String exemptCsv) {
        if (pid == null || !pid.startsWith("ESSENCE_")) return false;
        if (exemptCsv != null) for (String s : exemptCsv.split(",")) if (s.trim().equalsIgnoreCase(pid)) return false;
        return true;
    }

    /** Short human text for admin output: "everyone" or the key. */
    static String label(String raw) {
        String k = key(raw);
        return k.isEmpty() ? "everyone" : k;
    }
}
