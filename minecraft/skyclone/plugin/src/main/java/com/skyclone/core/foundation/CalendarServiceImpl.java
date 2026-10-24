package com.skyclone.core.foundation;

import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.time.CalendarService;
import com.skyclone.api.time.SkyDate;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The calendar: SkyDate derived from wall clock + epoch dial, plus recurring events.
 *
 * <p>A 1-second main-thread task compares every event's state with last second's and fires
 * {@link SkyEvents.CalendarStart} / {@link SkyEvents.CalendarEnd} on each transition. On the first
 * tick after boot every event ALREADY in progress gets a CalendarStart, so a module that listens
 * learns about a festival that was running when the server restarted.
 *
 * <p>Forcing (admin, for testing): force on = active for one natural occurrence length from now;
 * force off = inactive until the current natural occurrence ends; {@link #clearForce} returns to
 * natural state.
 */
public final class CalendarServiceImpl implements CalendarService {

    private final JavaPlugin plugin;
    private final Logger log;
    private final Dials.Dial<Double> epoch;
    private final Dials.Dial<Double> dayMinutes;
    private final Map<String, CalendarEvent> events = new LinkedHashMap<>();
    private final Map<String, Boolean> lastState = new HashMap<>();
    /** id -> {forcedValue (1/0), untilMillis} */
    private final Map<String, long[]> forced = new HashMap<>();
    private BukkitTask task;

    public CalendarServiceImpl(JavaPlugin plugin, Dials dials) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.epoch = dials.number("calendar.epoch-millis", 0,
                "Real time (unix ms) of Year 1 Early Spring 1st 00:00. 0 = set to first boot. /sb calendar skip moves it");
        this.dayMinutes = dials.number("calendar.day-minutes", 20,
                "Real minutes per in-game day (genre: 20). Changing it moves the current date");
        if (epoch.get() <= 0) {
            long now = System.currentTimeMillis();
            epoch.set((double) now);
            log.info("[calendar] first boot: epoch set to " + now);
        }
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    public long epochMillis() { return epoch.get().longValue(); }

    @Override public long millisPerDay() { return Math.max(1000L, (long) (dayMinutes.get() * 60_000L)); }

    @Override public SkyDate now() { return CalendarMath.date(epochMillis(), millisPerDay(), System.currentTimeMillis()); }

    @Override
    public synchronized void register(CalendarEvent event) {
        String err = CalendarMath.validate(event);
        if (err != null) {
            log.warning("[calendar] refusing event: " + err);
            return;
        }
        if (events.containsKey(event.id)) {
            log.warning("[calendar] event " + event.id + " registered twice; keeping the first");
            return;
        }
        events.put(event.id, event);
    }

    @Override public synchronized Collection<CalendarEvent> events() { return new ArrayList<>(events.values()); }

    public synchronized CalendarEvent event(String id) { return events.get(id); }

    @Override
    public synchronized boolean active(String eventId) {
        CalendarEvent e = events.get(eventId);
        if (e == null) return false;
        long now = System.currentTimeMillis();
        long[] f = forced.get(eventId);
        if (f != null) {
            if (now < f[1]) return f[0] == 1;
            forced.remove(eventId);
        }
        return CalendarMath.active(e, epochMillis(), millisPerDay(), now);
    }

    @Override
    public synchronized long millisUntil(String eventId) {
        CalendarEvent e = events.get(eventId);
        if (e == null) return -1;
        if (active(eventId)) return 0;
        long now = System.currentTimeMillis();
        long u = CalendarMath.until(e, epochMillis(), millisPerDay(), now);
        long[] f = forced.get(eventId);
        if (f != null && f[0] == 0 && u >= 0 && now + u < f[1]) {
            // forced off over the next natural start: report the start after the force expires
            u = CalendarMath.until(e, epochMillis(), millisPerDay(), f[1]) + (f[1] - now);
        }
        return u;
    }

    /** Millis until the current occurrence ends (forced or natural); 0 if not active. */
    @Override
    public synchronized long millisRemaining(String eventId) {
        CalendarEvent e = events.get(eventId);
        if (e == null || !active(eventId)) return 0;
        long now = System.currentTimeMillis();
        long[] f = forced.get(eventId);
        if (f != null && f[0] == 1) return Math.max(0, f[1] - now);
        return CalendarMath.remaining(e, epochMillis(), millisPerDay(), now);
    }

    @Override
    public synchronized void force(String eventId, boolean on) {
        CalendarEvent e = events.get(eventId);
        if (e == null) throw new IllegalArgumentException("no calendar event '" + eventId + "'");
        long now = System.currentTimeMillis();
        if (on) {
            forced.put(eventId, new long[]{1, now + CalendarMath.length(e, millisPerDay())});
        } else {
            long rem = CalendarMath.remaining(e, epochMillis(), millisPerDay(), now);
            forced.put(eventId, new long[]{0, now + Math.max(1000, rem)});
        }
        if (Bukkit.isPrimaryThread()) tick();
    }

    public synchronized void clearForce(String eventId) {
        forced.remove(eventId);
        if (Bukkit.isPrimaryThread()) tick();
    }

    public synchronized boolean isForced(String eventId) {
        long[] f = forced.get(eventId);
        return f != null && System.currentTimeMillis() < f[1];
    }

    /** Move the calendar forward by {@code days} in-game days (negative = back). Persists. */
    public void skipDays(double days) {
        long shift = (long) (days * millisPerDay());
        epoch.set((double) (epochMillis() - shift));
        if (Bukkit.isPrimaryThread()) tick();
    }

    /** Main thread. Fires transitions. */
    void tick() {
        List<String> starts = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        synchronized (this) {
            for (CalendarEvent e : events.values()) {
                boolean now;
                try {
                    now = active(e.id);
                } catch (Throwable t) {
                    log.log(Level.WARNING, "[calendar] state of " + e.id + " threw", t);
                    continue;
                }
                Boolean was = lastState.put(e.id, now);
                if (was == null) { if (now) starts.add(e.id); }
                else if (was != now) { if (now) starts.add(e.id); else ends.add(e.id); }
            }
        }
        for (String id : ends) fire(new SkyEvents.CalendarEnd(id), id, "end");
        for (String id : starts) fire(new SkyEvents.CalendarStart(id), id, "start");
    }

    private void fire(org.bukkit.event.Event ev, String id, String what) {
        try {
            Bukkit.getPluginManager().callEvent(ev);
            log.info("[calendar] " + id + " " + what);
        } catch (Throwable t) {
            log.log(Level.WARNING, "[calendar] listener for " + id + " " + what + " threw", t);
        }
    }

    /** Next {@code n} events by time until start (active ones first). */
    public synchronized List<CalendarEvent> upcoming(int n) {
        List<CalendarEvent> list = new ArrayList<>(events.values());
        Map<String, Long> until = new HashMap<>();
        for (CalendarEvent e : list) {
            long u = millisUntil(e.id);
            until.put(e.id, u < 0 ? Long.MAX_VALUE : u);
        }
        list.sort((a, b) -> Long.compare(until.get(a.id), until.get(b.id)));
        return list.size() > n ? new ArrayList<>(list.subList(0, n)) : list;
    }
}
