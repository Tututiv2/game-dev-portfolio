package com.skyclone.mod.island;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.skyclone.api.Sky;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Global (not per-player) island state: settings per profile and a name -> profile directory so
 * {@code /visit} and {@code /is ban} can resolve players who are offline. Everything lives in memory
 * (loaded once in enable) and is written through to SQLite on the DB thread.
 */
final class IslandStore {

    record PlayerEntry(UUID uuid, String name, String profileId, long lastSeen) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<String, IslandSettings> settings = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerEntry> players = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    void migrate() {
        Sky.db().migrate("island", 1,
                "CREATE TABLE IF NOT EXISTS island_profiles (profile_id TEXT PRIMARY KEY, data TEXT NOT NULL, updated INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS island_players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, name_lower TEXT NOT NULL, profile_id TEXT, last_seen INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS island_players_name ON island_players(name_lower)");
    }

    /** Blocking load; enable() only. */
    void loadAll() {
        try {
            Sky.db().query(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT profile_id, data FROM island_profiles");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        IslandSettings s;
                        try { s = GSON.fromJson(rs.getString(2), IslandSettings.class); } catch (RuntimeException e) { s = null; }
                        if (s != null) settings.put(rs.getString(1), sanitize(s));
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, profile_id, last_seen FROM island_players");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            UUID u = UUID.fromString(rs.getString(1));
                            PlayerEntry e = new PlayerEntry(u, rs.getString(2), rs.getString(3), rs.getLong(4));
                            players.put(u, e);
                            byName.put(e.name().toLowerCase(Locale.ROOT), u);
                        } catch (IllegalArgumentException ignored) { }
                    }
                }
                return null;
            }).join();
        } catch (RuntimeException e) {
            Sky.log().log(Level.WARNING, "[island] could not load island settings; starting with defaults", e);
        }
    }

    private static IslandSettings sanitize(IslandSettings s) {
        s.visitors = IslandMath.normalizeVisitors(s.visitors);
        if (s.banned == null) s.banned = new ArrayList<>();
        if (s.uniqueGuests == null) s.uniqueGuests = new java.util.LinkedHashSet<>();
        if (s.coopPerms == null) s.coopPerms = new java.util.HashMap<>();
        if (s.ranks == null) s.ranks = new java.util.HashMap<>();
        if (s.guestVisits == null) s.guestVisits = new java.util.HashMap<>();
        s.decor = Decor.sanitize(s.decor);
        return s;
    }

    /** Live settings object for a profile (created with defaults on first use). Call {@link #save} after changing it. */
    IslandSettings settings(String profileId) {
        return settings.computeIfAbsent(profileId, k -> {
            IslandSettings s = new IslandSettings();
            s.created = System.currentTimeMillis();
            return s;
        });
    }

    boolean hasSettings(String profileId) { return settings.containsKey(profileId); }

    void save(String profileId) {
        IslandSettings s = settings.get(profileId);
        if (s == null) return;
        final String json;
        synchronized (s) { json = GSON.toJson(s); }
        long now = System.currentTimeMillis();
        Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO island_profiles(profile_id, data, updated) VALUES (?, ?, ?) "
                            + "ON CONFLICT(profile_id) DO UPDATE SET data = excluded.data, updated = excluded.updated")) {
                ps.setString(1, profileId);
                ps.setString(2, json);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(t -> { Sky.log().log(Level.WARNING, "[island] could not save settings of " + profileId, t); return null; });
    }

    void seen(UUID uuid, String name, String profileId) {
        long now = System.currentTimeMillis();
        PlayerEntry old = players.put(uuid, new PlayerEntry(uuid, name, profileId, now));
        if (old != null && !old.name().equalsIgnoreCase(name)) byName.remove(old.name().toLowerCase(Locale.ROOT), uuid);
        byName.put(name.toLowerCase(Locale.ROOT), uuid);
        Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO island_players(uuid, name, name_lower, profile_id, last_seen) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, "
                            + "profile_id = excluded.profile_id, last_seen = excluded.last_seen")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, name.toLowerCase(Locale.ROOT));
                ps.setString(4, profileId);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(t -> { Sky.log().log(Level.WARNING, "[island] could not record " + name, t); return null; });
    }

    PlayerEntry player(UUID uuid) { return players.get(uuid); }

    PlayerEntry byName(String name) {
        if (name == null) return null;
        UUID u = byName.get(name.toLowerCase(Locale.ROOT));
        return u == null ? null : players.get(u);
    }

    /** Names of everyone last seen on this profile (owner + co-op), most recent first. */
    List<String> namesOf(String profileId) {
        List<PlayerEntry> l = new ArrayList<>();
        for (PlayerEntry e : players.values()) if (profileId.equals(e.profileId())) l.add(e);
        l.sort((a, b) -> Long.compare(b.lastSeen(), a.lastSeen()));
        List<String> out = new ArrayList<>();
        for (PlayerEntry e : l) out.add(e.name());
        return out;
    }

    int profileCount() { return settings.size(); }

    int playerCount() { return players.size(); }
}
