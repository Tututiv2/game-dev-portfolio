package com.aisky.showcontrol;

import com.google.gson.JsonObject;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gather events arrive per block. The feed wants one line per player per kind per window
 * ("Ren mined 84 Coal Ore in the last 30 s"), never per block. Main thread only.
 */
final class GatherAggregator {

    private static final class Window {
        final long start;
        final Map<String, Map<String, Long>> byKind = new LinkedHashMap<>();   // kind -> what -> amount
        Location last;
        Window(long start) { this.start = start; }
    }

    private final ShowControlPlugin plugin;
    private final Map<String, Window> windows = new HashMap<>();

    GatherAggregator(ShowControlPlugin plugin) { this.plugin = plugin; }

    void add(String player, String kind, String what, int amount, Location at) {
        if (amount <= 0) amount = 1;
        Window w = windows.computeIfAbsent(player, k -> new Window(System.currentTimeMillis()));
        w.byKind.computeIfAbsent(kind, k -> new LinkedHashMap<>()).merge(what, (long) amount, Long::sum);
        if (at != null) w.last = at.clone();
    }

    /** Called every second; flushes windows older than the configured length. */
    void tick() {
        long now = System.currentTimeMillis();
        long len = plugin.config().gatherWindowSeconds * 1000L;
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            if (now - e.getValue().start >= len) {
                emit(e.getKey(), e.getValue(), now);
                it.remove();
            }
        }
    }

    /** Flush one player now (quit). */
    void flush(String player) {
        Window w = windows.remove(player);
        if (w != null) emit(player, w, System.currentTimeMillis());
    }

    void clear() { windows.clear(); }

    private void emit(String player, Window w, long now) {
        long secs = Math.max(1, (now - w.start) / 1000);
        for (Map.Entry<String, Map<String, Long>> k : w.byKind.entrySet()) {
            String kind = k.getKey();
            long total = 0;
            String top = null;
            long topN = -1;
            JsonObject breakdown = new JsonObject();
            for (Map.Entry<String, Long> x : k.getValue().entrySet()) {
                total += x.getValue();
                breakdown.addProperty(x.getKey(), x.getValue());
                if (x.getValue() > topN) { topN = x.getValue(); top = x.getKey(); }
            }
            String verb = switch (kind) {
                case "MINING" -> "mined";
                case "FARMING" -> "harvested";
                case "FORAGING" -> "chopped";
                case "FISHING" -> "fished up";
                default -> "gathered";
            };
            String text = player + " " + verb + " " + total + " " + Names.item(top)
                    + (k.getValue().size() > 1 ? " (+" + (k.getValue().size() - 1) + " more kinds)" : "")
                    + " in the last " + secs + " s";
            int drama = total >= 200 ? 3 : total >= 64 ? 2 : 1;
            plugin.bus().ev("gather_summary").player(player).drama(drama).text(text).loc(w.last)
                    .data("kind", kind).data("what", top).data("amount", total)
                    .data("window_s", secs).data("breakdown", breakdown).emit();
        }
    }
}
