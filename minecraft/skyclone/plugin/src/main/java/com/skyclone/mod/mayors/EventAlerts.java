package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calendar chat, filtered per player ({@link AlertPolicy}). Playtest wave 3: the menus module told every ready
 * player about every calendar event ("[Calendar] X starts in 4m 43s!", "X has started!"), which on this server is
 * several lines an hour per player. This class sends those lines instead, through the policy, and - because the
 * generic announcer lives in the menus module and cannot be edited from here - switches it off through its own
 * dial ({@code menus.event-reminders}) while {@code mayors.alerts.takeover} is on. Exactly one announcer ever runs:
 * <ul>
 *   <li>takeover on (default): menus' dial is turned off once, at boot or when takeover is switched on;</li>
 *   <li>takeover off: menus' dial is turned back on and this class is silent;</li>
 *   <li>someone turns menus' dial back on by hand: that is read as "menus should do it", takeover goes off.</li>
 * </ul>
 * The player's menus settings keep working: {@code notify.event_reminders}, {@code notify.event_reminder_minutes}
 * and {@code notify.event_start} are read from the documented PDC mirror. The alert SCOPE (important / all / off)
 * is ours: the PDC key {@code skyclone:setting.notify.event_scope}, written here the way the island module writes
 * {@code island.visitors}, so a menus Settings entry for it (Request 15) picks it up with no migration.
 */
final class EventAlerts implements Listener {

    static final String SCOPE_KEY = "notify.event_scope";
    static final String MENUS_DIAL = "menus.event-reminders";
    private static final String MENUS_DIAL_DESC = "Chat reminders before calendar events and a line when they start";

    /** A reminder fires once, the first scan inside this window before the player's lead time. */
    private static final long WINDOW_MS = 30_000L;
    private static final long BOOT_QUIET_MS = 60_000L;      // core replays in-progress events as CalendarStart at boot

    private final MayorsModule mod;
    private final long enabledAt = System.currentTimeMillis();
    private Dials.Dial<Boolean> menusDial;                 // null until postEnable (menus has registered it by then)
    private boolean writingMenusDial;

    /** "name|startMinute|uuid" -> start minute; one reminder per player per occurrence. */
    private final Map<String, Long> reminded = new HashMap<>();
    /** event name (lower case) -> when its start line went out; two registrations of one festival say it once. */
    private final Map<String, Long> started = new HashMap<>();
    /** Players who have seen the "change with /eventalerts" hint this server run. */
    private final Set<UUID> hinted = new HashSet<>();

    EventAlerts(MayorsModule mod) { this.mod = mod; }

    // ---- who runs the announcer ----------------------------------------------------------------------------

    /** After every module registered its dials: take the job over from menus (or leave it there). */
    void takeOver() {
        MayorDials d = mod.dials();
        menusDial = Sky.dials().flag(MENUS_DIAL, true, MENUS_DIAL_DESC);
        d.alertsTakeover.onChange(on -> setMenus(!on));
        menusDial.onChange(on -> {
            if (writingMenusDial || !on || !d.alertsTakeover.get()) return;
            Sky.log().info("[mayors] " + MENUS_DIAL + " was turned back on: menus announces calendar events again, mayors.alerts.takeover -> false");
            d.alertsTakeover.set(false);
        });
        if (d.alertsTakeover.get() && menusDial.get()) {
            setMenus(false);
            Sky.log().info("[mayors] calendar chat is now filtered per player (mayors.alerts.takeover); " + MENUS_DIAL + " -> false");
        }
    }

    private void setMenus(boolean on) {
        if (menusDial == null || menusDial.get() == on) return;
        writingMenusDial = true;
        try { menusDial.set(on); } finally { writingMenusDial = false; }
    }

    /** Is this class the announcer right now? */
    boolean running() { return menusDial != null && mod.dials().alertsTakeover.get() && !menusDial.get(); }

    // ---- per-player prefs --------------------------------------------------------------------------------

    private static NamespacedKey key(String setting) {
        return new NamespacedKey("skyclone", "setting." + setting.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"));
    }

    private static String pdc(Player p, String setting) {
        try { return p.getPersistentDataContainer().get(key(setting), PersistentDataType.STRING); }
        catch (Throwable t) { return null; }
    }

    /**
     * A text setting: menus' Settings service when it is provided (wave 5, {@code Features.Settings}), else the PDC
     * mirror both sides write. The PDC value is the default, so nothing is lost before menus adopts the key.
     */
    private static String setting(Player p, String key) {
        String mirror = pdc(p, key);
        try {
            if (Sky.has(Features.Settings.class)) {
                String v = Sky.service(Features.Settings.class).text(p, key, mirror);
                if (v != null && !v.isBlank()) return v;
            }
        } catch (Throwable ignored) { }
        return mirror;
    }

    static AlertPolicy.Scope scope(Player p) { return AlertPolicy.Scope.parse(setting(p, SCOPE_KEY)); }

    static void setScope(Player p, AlertPolicy.Scope s) {
        try { p.getPersistentDataContainer().set(key(SCOPE_KEY), PersistentDataType.STRING, s.id); }
        catch (Throwable t) { Sky.log().warning("[mayors] could not store " + SCOPE_KEY + " for " + p.getName() + ": " + t); }
    }

    /** menus' toggle, read from its documented PDC mirror; missing = its default (on). */
    private static boolean toggle(Player p, String setting) {
        boolean mirror = !"false".equals(pdc(p, setting));
        try { if (Sky.has(Features.Settings.class)) return Sky.service(Features.Settings.class).flag(p, setting, mirror); } catch (Throwable ignored) { }
        return mirror;
    }

    static long leadMillis(Player p) {
        try { return Math.max(1, Long.parseLong(setting(p, "notify.event_reminder_minutes"))) * 60_000L; }
        catch (RuntimeException e) { return 5 * 60_000L; }
    }

    AlertPolicy.Prefs prefs(Player p) {
        double purse;
        try { purse = Sky.economy().purse(p); } catch (Throwable t) { purse = 0; }
        return new AlertPolicy.Prefs(scope(p), toggle(p, "notify.event_reminders"), toggle(p, "notify.event_start"), purse);
    }

    AlertPolicy.Rules rules() {
        MayorDials d = mod.dials();
        return new AlertPolicy.Rules(AlertPolicy.ids(d.alertsImportant.get()), AlertPolicy.ids(d.alertsOwnerAnnounced.get()),
                AlertPolicy.gates(d.alertsPerkGates.get()), d.daEntryPurse.get(), d.alertsDaNear.get());
    }

    boolean gateOk(String eventId, AlertPolicy.Rules r) {
        String perk = r.perkGates().get(AlertPolicy.norm(eventId));
        return perk == null || perk.isBlank() || mod.election().perkActive(perk);
    }

    /** May an event's own announcement (our festivals' "has begun!" lines) reach this player? */
    boolean hears(Player p, String eventId) {
        if (p == null || !Sky.profiles().isReady(p)) return false;
        AlertPolicy.Rules r = rules();
        return AlertPolicy.ownLine(eventId, gateOk(eventId, r), prefs(p), r);
    }

    /** For our own festival announcements: true when the module is not up (never lose a line to a null). */
    static boolean news(Player p, String eventId) {
        MayorsModule m = MayorsModule.get();
        if (m == null || m.alerts() == null) return true;
        try { return m.alerts().hears(p, eventId); } catch (RuntimeException e) { return true; }
    }

    /** {@code mayors:alert:<eventId>[:start|remind]} - would this player get a calendar line for it? */
    boolean wants(Player p, String eventId, AlertPolicy.Kind kind) {
        if (p == null || !Sky.profiles().isReady(p)) return false;
        AlertPolicy.Rules r = rules();
        return AlertPolicy.decide(eventId, kind, gateOk(eventId, r), prefs(p), r) != AlertPolicy.Line.NONE;
    }

    // ---- start lines -------------------------------------------------------------------------------------

    @EventHandler
    public void onStart(SkyEvents.CalendarStart e) {
        if (!running() || System.currentTimeMillis() - enabledAt < BOOT_QUIET_MS) return;
        CalendarEvent ev = find(e.eventId);
        if (ev == null) return;
        String name = name(ev);
        long now = System.currentTimeMillis();
        Long last = started.put(name.toLowerCase(Locale.ROOT), now);
        if (last != null && now - last < 120_000) return;
        AlertPolicy.Rules r = rules();
        boolean gate = gateOk(ev.id, r);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            if (AlertPolicy.decide(ev.id, AlertPolicy.Kind.START, gate, prefs(p), r) == AlertPolicy.Line.NONE) continue;
            Msg.send(p, "&e[Calendar] &6" + name + " &ahas started!");
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
            hint(p);
        }
    }

    // ---- reminders ---------------------------------------------------------------------------------------

    /** Every few seconds from the module ticker. */
    void scan() {
        if (!running()) return;
        long now = System.currentTimeMillis();
        AlertPolicy.Rules r = rules();
        Set<String> seen = new HashSet<>();
        List<Player> ready = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (Sky.profiles().isReady(p)) ready.add(p);
        if (ready.isEmpty()) return;
        for (CalendarEvent ev : Sky.calendar().events()) {
            if (ev == null || ev.id == null || forcedOnly(ev)) continue;
            long ms;
            try {
                if (Sky.calendar().active(ev.id)) continue;
                ms = Sky.calendar().millisUntil(ev.id);
            } catch (RuntimeException ex) { continue; }
            if (ms <= 0 || ms > 31 * 60_000L) continue;                    // the longest lead time is 30 minutes
            String name = name(ev);
            // ROUNDED, not floored: events start on the minute (the Dark Auction at :55:00), and now + ms jitters by a
            // few ms between scans, so a floor flipped between :54 and :55 and the same reminder went out twice, 5 s
            // apart ("starts in 4m 55s!" then "4m 50s!" - final verification 2026-10-04).
            long startMinute = Math.round((now + ms) / 60_000.0);
            String occ = name.toLowerCase(Locale.ROOT) + "|" + startMinute;
            if (!seen.add(occ)) continue;                                // mayors + progression both register New Year
            boolean gate = gateOk(ev.id, r);
            for (Player p : ready) {
                long lead = leadMillis(p);
                if (ms > lead || ms <= lead - WINDOW_MS) continue;
                String k = occ + "|" + p.getUniqueId();
                if (reminded.containsKey(k)) continue;
                reminded.put(k, startMinute);
                AlertPolicy.Prefs pr = prefs(p);
                AlertPolicy.Line line = AlertPolicy.decide(ev.id, AlertPolicy.Kind.REMIND, gate, pr, r);
                if (line == AlertPolicy.Line.NONE) continue;
                Msg.send(p, reminder(name, ms, line, pr.purse(), r.daNeed()));
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f);
                hint(p);
            }
        }
        long nowMinute = now / 60_000L;
        for (Iterator<Long> it = reminded.values().iterator(); it.hasNext(); ) if (it.next() < nowMinute - 5) it.remove();
        if (started.size() > 500) started.clear();
    }

    String reminder(String name, long ms, AlertPolicy.Line line, double purse, double need) {
        String head = "&e[Calendar] &6" + name + " &estarts in &a" + Text.duration(Math.max(1, ms / 1000)) + "&e!";
        return switch (line) {
            case DA_OPEN -> head + (mod.dials().daRemoteJoin.get() ? " &7Use &a/darkauction &7to enter." : " &7Speak to &5Ashgrave &7in the Hub to enter.");
            case DA_SHORT -> head + " &7Entry needs &6" + Text.commasInt(need) + " coins &7in your purse - you are &6"
                    + Text.commasInt(Math.max(0, need - purse)) + " &7short.";
            default -> head;
        };
    }

    private void hint(Player p) {
        if (!hinted.add(p.getUniqueId())) return;
        Msg.send(p, "&8Calendar alerts: " + scope(p).label + "&8. Change with &7/eventalerts&8.");
    }

    // ---- /eventalerts --------------------------------------------------------------------------------------

    void command(Player p, String[] a) {
        if (a.length > 0) {
            AlertPolicy.Scope s = "next".equalsIgnoreCase(a[0]) ? scope(p).next() : AlertPolicy.Scope.parseStrict(a[0]);
            if (s == null) { Msg.error(p, "Use /eventalerts important, all or off."); return; }
            setScope(p, s);
            Msg.success(p, "Calendar alerts: " + s.label + "&a - " + s.meaning + ".");
            return;
        }
        status(p).forEach(l -> Msg.send(p, l));
    }

    List<String> status(Player p) {
        AlertPolicy.Scope s = scope(p);
        AlertPolicy.Prefs pr = prefs(p);
        List<String> out = new ArrayList<>();
        out.add("&e&lCalendar Alerts: " + s.label);
        out.add("&7" + Character.toUpperCase(s.meaning.charAt(0)) + s.meaning.substring(1) + ".");
        out.add("&7Reminders: " + (pr.reminders() ? "&aon &7(" + leadMillis(p) / 60_000 + " min before)" : "&coff")
                + " &8| &7Start messages: " + (pr.starts() ? "&aon" : "&coff") + " &8(&7/settings notifications&8)");
        out.add("&7Change: &a/eventalerts important&7, &e/eventalerts all&7, &c/eventalerts off");
        if (!running()) out.add("&8(The menus module is sending calendar chat right now, so this setting does not filter it yet.)");
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** The Encore festival, the Ritual and the Carnival are parked a million years out and forced on by perks. */
    static boolean forcedOnly(CalendarEvent ev) { return ev.intervalMinutes <= 0 && (ev.everyYears > 1000 || ev.yearOffset > 1000); }

    static CalendarEvent find(String id) {
        try { for (CalendarEvent ev : Sky.calendar().events()) if (ev != null && id != null && id.equals(ev.id)) return ev; }
        catch (RuntimeException ignored) { }
        return null;
    }

    static String name(CalendarEvent ev) { return ev.name == null || ev.name.isBlank() ? Text.titleCase(ev.id) : ev.name; }
}
