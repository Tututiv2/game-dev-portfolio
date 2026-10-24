package com.skyclone.mod.menus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Slot map of the SkyBlock Menu (nether star / {@code /sbmenu}). The research-confirmed layout
 * (research 08 section 11.1, profile button renamed "Stats &amp; Equipment") is {@link #RESEARCH};
 * {@link #QUICK_ROW} is our addition in the otherwise empty row 4 (toggle: setting
 * {@code menus.sbmenu_quick_row} and dial {@code menus.quick-row}). {@link #DESTINATIONS} is our
 * addition too: the two combat destinations that have their own front door (The Rift, the Leviathan
 * hunt) flank the navigation row beside Fast Travel, in the two bottom-row slots research leaves as
 * glass, so the research slots never move. Values are FeatureIndex ids. Pure, unit-tested.
 */
public final class SkyMenuLayout {

    private SkyMenuLayout() {}

    public static final Map<Integer, String> RESEARCH;
    public static final Map<Integer, String> QUICK_ROW;
    public static final Map<Integer, String> DESTINATIONS;

    static {
        Map<Integer, String> r = new LinkedHashMap<>();
        r.put(13, "stats");
        r.put(19, "skills");
        r.put(20, "collections");
        r.put(21, "recipes");
        r.put(22, "sblevel");
        r.put(23, "quests");
        r.put(24, "calendar");
        r.put(25, "storage");
        r.put(29, "bags");
        r.put(30, "pets");
        r.put(31, "craft");
        r.put(32, "wardrobe");
        r.put(33, "bank");
        r.put(47, "fasttravel");
        r.put(48, "profiles");
        r.put(50, "settings");
        r.put(51, "cookie");
        RESEARCH = Collections.unmodifiableMap(r);

        Map<Integer, String> q = new LinkedHashMap<>();
        // wave 5 (reforges Request 2): the two gear-upgrade stations close the row at both ends
        q.put(36, "reforges");
        q.put(44, "enchant");
        q.put(37, "museum");
        q.put(38, "bestiary");
        q.put(39, "effects");
        q.put(40, "guide");
        q.put(41, "island");
        q.put(42, "bazaar");
        q.put(43, "auction");
        QUICK_ROW = Collections.unmodifiableMap(q);

        // rift Request 3 + kuudra Request 3: a SkyBlock Menu entry for /rift and /kuudra. Bottom row,
        // beside Fast Travel (47) and the cookie (51); always shown, a missing module reads "Coming soon!".
        Map<Integer, String> d = new LinkedHashMap<>();
        d.put(46, "rift");
        d.put(52, "kuudra");
        DESTINATIONS = Collections.unmodifiableMap(d);
    }

    /** Close button slot (bottom middle). */
    public static final int CLOSE = 49;

    // ---- the two market icons (playtest wave4-endgame #12) ------------------------------------------------

    /** Requirement values the bazaar module reads as "no requirement" (its Gate.OPEN). */
    private static final java.util.Set<String> OPEN = java.util.Set.of("", "none", "off", "open", "everyone", "anyone",
            "all", "no", "false", "-", "0", "null");

    /**
     * {@code bazaar.access-requirement} as the bazaar module reads it: "" = everyone, a bare number N = SkyBlock
     * Level N, anything else a requirement key. Lore only - the click still goes through the bazaar's own gate.
     */
    public static String accessKey(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        while (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))))
            t = t.substring(1, t.length() - 1).trim();
        if (OPEN.contains(t.toLowerCase(java.util.Locale.ROOT))) return "";
        if (!t.isEmpty() && t.chars().allMatch(Character::isDigit)) {
            try { long n = Long.parseLong(t); return n <= 0 ? "" : "sblevel:" + n; }
            catch (NumberFormatException e) { return t; }
        }
        return t;
    }

    /**
     * Where the Bazaar / Auction House can be used from, ONE sentence for both icons so they can never state
     * different rules. {@code anywhere}: no requirement away from it; else {@code buff} names what lets you use it
     * remotely (null = nothing does); {@code buffActive} null = unknown.
     */
    public static String reachText(String place, String buff, Boolean buffActive, boolean anywhere) {
        if (anywhere) return "You can use it from anywhere.";
        if (buff == null || buff.isBlank()) return "Use it at " + place + "&7.";
        if (Boolean.TRUE.equals(buffActive)) return "Your &d" + buff + " &7lets you use it from anywhere.";
        return "Use it at " + place + "&7, or from anywhere with the &d" + buff + "&7.";
    }
}
