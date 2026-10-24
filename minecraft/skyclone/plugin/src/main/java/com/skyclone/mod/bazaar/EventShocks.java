package com.skyclone.mod.bazaar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Scripted market-maker moves on event days (research 05 s2.9 "Event shocks"): while a calendar event is running,
 * the products it floods the server with (Gerald's gifts, festival candy, shark teeth) are quoted lower, and the
 * move GLIDES in and out with a half-life instead of jumping, so the book drifts rather than collapsing.
 *
 * <p>Pure (no Bukkit): the spec is one dial string, parsed here, and the glide is plain arithmetic, so both are
 * unit-tested. Spec grammar: {@code event=ID,ID,...:factor;event=...} — factor is clamped to 0.2..5.
 */
final class EventShocks {

    record Shock(String event, List<String> products, double factor) {}

    private final List<Shock> shocks;
    private final Map<String, List<Shock>> byProduct = new LinkedHashMap<>();

    private EventShocks(List<Shock> shocks) {
        this.shocks = Collections.unmodifiableList(shocks);
        for (Shock s : shocks) for (String p : s.products) byProduct.computeIfAbsent(p, k -> new ArrayList<>()).add(s);
    }

    static final double MIN = 0.2, MAX = 5.0;

    /** Parse a spec; malformed entries are skipped (a dial typo must never stop the market makers). */
    static EventShocks parse(String spec) {
        List<Shock> out = new ArrayList<>();
        if (spec != null) {
            for (String entry : spec.split(";")) {
                String e = entry.trim();
                int eq = e.indexOf('='), colon = e.lastIndexOf(':');
                if (eq <= 0 || colon <= eq + 1) continue;
                String event = e.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                double f;
                try { f = Double.parseDouble(e.substring(colon + 1).trim()); } catch (NumberFormatException x) { continue; }
                if (!Double.isFinite(f) || f <= 0) continue;
                f = Math.max(MIN, Math.min(MAX, f));
                List<String> ids = new ArrayList<>();
                for (String id : e.substring(eq + 1, colon).split(",")) {
                    String t = id.trim().toUpperCase(Locale.ROOT);
                    if (!t.isEmpty()) ids.add(t);
                }
                if (event.isEmpty() || ids.isEmpty()) continue;
                out.add(new Shock(event, List.copyOf(ids), f));
            }
        }
        return new EventShocks(out);
    }

    List<Shock> all() { return shocks; }

    boolean touches(String pid) { return byProduct.containsKey(pid); }

    /** The multiplier a product's fair value is heading for right now: the product of every ACTIVE event's factor. */
    double target(String pid, Predicate<String> active) {
        List<Shock> l = byProduct.get(pid);
        if (l == null) return 1.0;
        double f = 1.0;
        for (Shock s : l) if (active.test(s.event)) f *= s.factor;
        return Math.max(MIN, Math.min(MAX, f));
    }

    /** One glide step: {@code current} moves toward {@code target} with a half-life of {@code halfLifeMinutes}. */
    static double glide(double current, double target, double minutes, double halfLifeMinutes) {
        if (!(current > 0)) current = 1.0;
        if (minutes <= 0) return current;
        if (halfLifeMinutes <= 0) return target;
        double k = Math.pow(0.5, minutes / halfLifeMinutes);
        double next = target + (current - target) * k;
        return Math.abs(next - target) < 1e-4 ? target : next;
    }

    // ---- live wiring (one cached parse per dial value) ---------------------------------------------------------

    private static String cachedSpec;
    private static EventShocks cached = new EventShocks(List.of());

    static EventShocks current() {
        String spec = BzDials.eventShocks == null ? "" : BzDials.eventShocks.get();
        if (!spec.equals(cachedSpec)) { cached = parse(spec); cachedSpec = spec; }
        return cached;
    }

    /** Is a calendar event running? False if the calendar is unavailable (no shock beats a crash). */
    static boolean active(String eventId) {
        try { return com.skyclone.api.Sky.calendar().active(eventId); } catch (RuntimeException e) { return false; }
    }
}
