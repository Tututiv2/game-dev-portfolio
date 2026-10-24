package com.skyclone.api.time;

import java.util.Collection;

/** The in-game calendar (see SkyDate) and its recurring events. */
public interface CalendarService {

    SkyDate now();

    /** Real millis of one in-game day (default 20 min; a dial). */
    long millisPerDay();

    void register(CalendarEvent event);

    Collection<CalendarEvent> events();

    boolean active(String eventId);

    /** Real millis until the event next starts (0 if active now). */
    long millisUntil(String eventId);

    /**
     * Real millis until the event's CURRENT occurrence ends (0 when it is not active), forced runs
     * included, so a menu can say "ends in 2h". (Added by integration round 2, menus Request 4.)
     */
    default long millisRemaining(String eventId) { return 0; }

    /** Admin: force an event on/off right now (for testing). */
    void force(String eventId, boolean on);
}
