package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import org.bukkit.Bukkit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Small key/value store for the zone clocks that used to reset on a restart: the Broodmother clock,
 * the Colossus meter and the Silkmother altar's pending Callings (those were paid for with items, so
 * losing them on a restart took items from players). Table {@code combatzones_state}, schema v2.
 *
 * <p>Writers call {@link #put} only when a value changed (the owners compare each tick), so this is a
 * handful of writes a minute at most.
 */
final class CzState {

    private CzState() {}

    /** Call once, after DragonFight's v1 migration has run (module enable order guarantees it). */
    static void migrate() {
        Sky.db().migrate("combatzones", 2, "CREATE TABLE IF NOT EXISTS combatzones_state (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
    }

    /** Loads every row, then hands them to {@code then} on the main thread. */
    static void loadAll(Consumer<Map<String, String>> then) {
        Sky.db().query(c -> {
            Map<String, String> rows = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT k, v FROM combatzones_state"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.put(rs.getString(1), rs.getString(2));
            }
            return rows;
        }).thenAccept(rows -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> then.accept(rows)))
          .exceptionally(t -> { Sky.log().warning("[combatzones] state load failed: " + t); return null; });
    }

    static void put(String key, String value) {
        Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO combatzones_state (k, v) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, value == null ? "" : value);
                ps.executeUpdate();
            }
            return null;
        });
    }

    static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
