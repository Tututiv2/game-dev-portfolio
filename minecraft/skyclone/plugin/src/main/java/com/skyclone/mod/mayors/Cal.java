package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Text;

/**
 * Calendar events this module owns (research/05 §7.4). Ids are public contract: pets, armor and the
 * calendar menu read them. Perk-driven events (the Mythological Ritual, the Encore festival) are
 * registered with a date that never comes naturally and are FORCED on while their perk says so (see ExtraEvents),
 * so {@code Sky.calendar().active("mythological_ritual")} is true exactly while the ritual runs.
 */
final class Cal {

    private Cal() {}

    static final String SPOOKY = "spooky_festival";
    static final String SPOOKY_EXTRA = "spooky_festival_extra";
    static final String NEW_YEAR = "new_year_celebration";
    static final String ZOO_SUMMER = "traveling_zoo";
    static final String ZOO_WINTER = "traveling_zoo_winter";
    static final String SEASON_OF_JERRY = "season_of_jerry";
    static final String WORKSHOP = "jerrys_workshop_open";
    static final String DARK_AUCTION = "dark_auction";
    static final String ELECTION = "election_booth";
    static final String TERM_START = "mayor_term_start";
    static final String MYTH = "mythological_ritual";
    static final String CARNIVAL = "carnival";
    /** Foxy's Extra Event, fishing variant. Fishing ORs it into its festival check (Fishing.festival). */
    static final String FISHING_EXTRA = "fishing_festival_extra";
    /** Mining Fiesta: on all term under Cole's perk (mayor or minister) and for Foxy's mining Extra Event.
     *  Mining reads it through its dials mining.fiesta-event / mining.fiesta-perk (both "mining_fiesta"). */
    static final String FIESTA = "mining_fiesta";

    /** A date that never arrives: forced-only events. */
    private static CalendarEvent forcedOnly(String id, String name, int durationDays) {
        CalendarEvent e = CalendarEvent.onDate(id, name, 1, 1, durationDays);
        e.everyYears = 1_000_000;
        e.yearOffset = 999_999;
        return e;
    }

    static void register() {
        var cal = Sky.calendar();
        cal.register(CalendarEvent.onDate(SPOOKY, "Spooky Festival", 8, 29, 3));                     // Autumn 29-31
        cal.register(forcedOnly(SPOOKY_EXTRA, "Encore Spooky Festival", 3));                          // Foxy: Extra Event
        cal.register(CalendarEvent.onDate(NEW_YEAR, "New Year Celebration", 12, 29, 3));             // Late Winter 29-31
        cal.register(CalendarEvent.onDate(ZOO_SUMMER, "Traveling Zoo", 4, 1, 3));                    // Early Summer 1-3
        cal.register(CalendarEvent.onDate(ZOO_WINTER, "Traveling Zoo", 10, 1, 3));                   // Early Winter 1-3
        cal.register(CalendarEvent.onDate(SEASON_OF_JERRY, "Season of Gerald", 12, 24, 3));          // Late Winter 24-26
        cal.register(CalendarEvent.onDate(WORKSHOP, "Winter Workshop Open", 12, 1, 31));             // all of Late Winter
        cal.register(CalendarEvent.realtime(DARK_AUCTION, "Dark Auction", 60, 55, 5));              // every real hour at :55
        cal.register(CalendarEvent.onDate(ELECTION, "Election Booth Open", 6, 27, ElectionMath.votingDays()));
        cal.register(CalendarEvent.onDate(TERM_START, "New Mayor Takes Office", 3, 27, 1));
        cal.register(forcedOnly(MYTH, "Mythological Ritual", 372));
        cal.register(forcedOnly(CARNIVAL, "The Carnival", 372));                                       // Fox mayor: Fairground
        cal.register(forcedOnly(FISHING_EXTRA, "Fishing Festival (Extra Event)", 3));                  // Foxy: Extra Event, fishing
        cal.register(forcedOnly(FIESTA, "Mining Fiesta", 372));                                          // Cole: whole term; Foxy: Extra Event, mining
    }

    static boolean active(String id) {
        try { return Sky.calendar().active(id); } catch (Throwable t) { return false; }
    }

    /** "&aACTIVE" or "&ein 2h 13m". */
    static String when(String id) {
        try {
            if (Sky.calendar().active(id)) return "&aACTIVE NOW";
            long ms = Sky.calendar().millisUntil(id);
            if (ms < 0 || ms > 1000L * 3600 * 24 * 365) return "&8not scheduled";
            return "&ein " + Text.duration(ms / 1000);
        } catch (Throwable t) {
            return "&8unknown";
        }
    }

    /** Drive a forced-only event to match {@code want} (idempotent; re-forces before a force lapses). */
    static void drive(String id, boolean want) {
        try {
            boolean now = Sky.calendar().active(id);
            if (want && !now) Sky.calendar().force(id, true);
            else if (!want && now) Sky.calendar().force(id, false);
        } catch (Throwable t) {
            Sky.log().warning("[mayors] could not drive calendar event " + id + ": " + t);
        }
    }
}
