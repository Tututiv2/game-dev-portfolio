package com.skyclone.mod.mayors;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which calendar announcements a player hears - pure, no server, so the unit test plays every case.
 *
 * <p>Playtest (wave 3): every player, AI or human, got "[Calendar] X starts in 4m 43s!" and "X has started!" for
 * EVERY calendar event - Spider's Den rain every 40 minutes, a Harvest Fair and a Dark Auction every hour - so the
 * chat a player actually needed to read scrolled away. The rule now:
 * <ul>
 *   <li><b>important</b> (the default): festivals with rewards, the mayor election, and the Dark Auction only for
 *       a player who carries enough coins to get in (or is within reach of it, {@code daNear} of the entry purse).
 *       A player far below the entry purse is never told the coin requirement - it is an invitation they cannot
 *       act on.</li>
 *   <li><b>all</b>: every event, as before - except that the Dark Auction's coin requirement is still only shown
 *       to players within reach of it.</li>
 *   <li><b>off</b>: no calendar chat at all.</li>
 * </ul>
 * The menus module's own per-player toggles ({@code notify.event_reminders}, {@code notify.event_start}) still
 * apply on top. A START line is skipped for events whose owner already sends its own, richer announcement (the
 * Spooky Festival, the Dark Auction invitation, ...), so nobody reads the same news twice.
 */
final class AlertPolicy {

    private AlertPolicy() {}

    enum Scope {
        IMPORTANT("important", "&aImportant only", "festivals with rewards, the mayor election, and the Dark Auction once you can afford it"),
        ALL("all", "&eEverything", "every calendar event, including hourly ones"),
        OFF("off", "&cOff", "no calendar announcements in chat");

        final String id, label, meaning;
        Scope(String id, String label, String meaning) { this.id = id; this.label = label; this.meaning = meaning; }

        /** Unknown or missing -> the default (IMPORTANT). */
        static Scope parse(String s) {
            if (s != null) for (Scope v : values()) if (v.id.equalsIgnoreCase(s.trim())) return v;
            return IMPORTANT;
        }

        static Scope parseStrict(String s) {
            if (s != null) for (Scope v : values()) if (v.id.equalsIgnoreCase(s.trim())) return v;
            return null;
        }

        Scope next() { return values()[(ordinal() + 1) % values().length]; }
    }

    enum Kind { REMIND, START }

    /** NONE = say nothing. DA_OPEN = "you can enter". DA_SHORT = "bring X more" (only for players within reach). */
    enum Line { NONE, PLAIN, DA_OPEN, DA_SHORT }

    /** What we know about one player. */
    record Prefs(Scope scope, boolean reminders, boolean starts, double purse) {}

    /** Server-wide rules (dials). */
    record Rules(Set<String> important, Set<String> ownerAnnounced, Map<String, String> perkGates, double daNeed, double daNear) {}

    static final String DARK_AUCTION = "dark_auction";

    /** Festivals with rewards + the election. Other modules' ids included: fishing, progression's cakes. */
    static final String DEFAULT_IMPORTANT = String.join(",",
            "spooky_festival", "spooky_festival_extra", "new_year_celebration", "traveling_zoo", "traveling_zoo_winter",
            "season_of_jerry", "mythological_ritual", "carnival", "election_booth", "mayor_term_start", DARK_AUCTION,
            "fishing_festival", "fishing_festival_extra", "mining_fiesta", "progression_century_celebration", "progression_new_year");

    /** Events whose owner sends its own start announcement, so the generic "has started!" line would repeat it. */
    static final String DEFAULT_OWNER_ANNOUNCED = String.join(",",
            "spooky_festival", "spooky_festival_extra", "new_year_celebration", "traveling_zoo", "traveling_zoo_winter",
            "season_of_jerry", "mythological_ritual", "carnival", "election_booth", "mayor_term_start", DARK_AUCTION,
            "progression_century_celebration", "progression_new_year", "crimson_volcano_eruption");

    /**
     * Calendar events that only really happen while a perk is on. Fishing registers its festival on day 1 of EVERY
     * month and runs it only under the Fishing Festival perk, so without the perk the calendar would announce a
     * festival that never comes.
     */
    static final String DEFAULT_PERK_GATES = "fishing_festival=marina.fishing_festival";

    /** The line a player gets for one event. {@code gateOk}: the event's perk gate (if any) holds. */
    static Line decide(String eventId, Kind kind, boolean gateOk, Prefs p, Rules r) {
        if (p == null || p.scope() == Scope.OFF) return Line.NONE;
        if (kind == Kind.REMIND && !p.reminders()) return Line.NONE;
        if (kind == Kind.START && !p.starts()) return Line.NONE;
        if (!gateOk) return Line.NONE;
        String id = norm(eventId);
        if (DARK_AUCTION.equals(id)) {
            if (kind == Kind.START) return Line.NONE;            // the auction's own invitation goes to players who can enter
            if (canEnter(p.purse(), r.daNeed())) return Line.DA_OPEN;
            if (withinReach(p.purse(), r.daNeed(), r.daNear())) return Line.DA_SHORT;
            return p.scope() == Scope.ALL ? Line.PLAIN : Line.NONE;   // never the coin requirement to someone far below it
        }
        if (kind == Kind.START && r.ownerAnnounced().contains(id)) return Line.NONE;
        if (p.scope() == Scope.ALL) return Line.PLAIN;
        return r.important().contains(id) ? Line.PLAIN : Line.NONE;
    }

    /**
     * May an event owner's own announcement (the Spooky Festival's "the festival has begun!", the election
     * opening, ...) reach this player? Same scope rule as the calendar line, without the owner-announced skip.
     */
    static boolean ownLine(String eventId, boolean gateOk, Prefs p, Rules r) {
        if (p == null || p.scope() == Scope.OFF || !p.starts() || !gateOk) return false;
        return p.scope() == Scope.ALL || r.important().contains(norm(eventId));
    }

    /** The Dark Auction's door rule (DarkAuction.canEnter delegates here, so the door and the alerts cannot drift). */
    static boolean canEnter(double purse, double need) { return purse >= need; }

    /** At least {@code near} (0..1) of the entry purse, but not yet all of it. */
    static boolean withinReach(double purse, double need, double near) {
        if (canEnter(purse, need)) return false;
        double f = Math.max(0, Math.min(1, near));
        return f > 0 && purse >= need * f;
    }

    static String norm(String id) { return id == null ? "" : id.trim().toLowerCase(Locale.ROOT); }

    /** "a, b,c" -> {a, b, c} (lower case, blanks dropped). */
    static Set<String> ids(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null) for (String s : csv.split(",")) if (!s.isBlank()) out.add(norm(s));
        return out;
    }

    /** "event=perk,event=perk" -> map (malformed pairs dropped). */
    static Map<String, String> gates(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        if (csv != null) for (String s : csv.split(",")) {
            int eq = s.indexOf('=');
            if (eq <= 0 || eq == s.length() - 1) continue;
            out.put(norm(s.substring(0, eq)), s.substring(eq + 1).trim());
        }
        return out;
    }
}
