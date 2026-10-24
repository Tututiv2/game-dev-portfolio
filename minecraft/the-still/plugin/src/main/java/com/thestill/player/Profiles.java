package com.thestill.player;

import com.thestill.core.Service;
import com.thestill.state.StateMap;
import com.thestill.store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads, caches and persists player profiles.
 *
 * <p>Two write paths, on purpose:
 *
 * <ul>
 *   <li><b>Write-behind</b> for high-frequency, low-value change — playtime,
 *       counters, live meters. Marked dirty, flushed on a timer.
 *   <li><b>Durable</b> for anything a player would file a ticket about losing —
 *       money, ranks, unlocks. Committed at once.
 * </ul>
 *
 * <p>Shadow Prison had neither: it rebuilt a snapshot of <em>every</em> player on
 * the server thread up to four times per block break, and had no dirty flag at
 * all. The review's summary was "fine at 2 players, falls over at 20+".
 */
public final class Profiles implements Service {

    private final Logger log;
    private final Database database;
    private final Map<UUID, Profile> cache = new ConcurrentHashMap<>();

    public Profiles(Logger log, Database database) {
        this.log = log;
        this.database = database;
    }

    @Override
    public void stop() throws Exception {
        long now = System.currentTimeMillis();
        // Fold every live session into stored playtime before the final write,
        // or everyone online at shutdown loses the session they just played.
        cache.values().forEach(profile -> {
            profile.endSession(now);
            profile.setLastSeen(now);
        });
        int written = flushDirty(true);
        log.info("flushed " + written + " profile(s) on shutdown");
        // The store drains its own queue in its stop(), which runs after this
        // because services stop in reverse start order.
        cache.clear();
    }

    // ---- lookup ---------------------------------------------------------

    /** The cached profile, or null if this player is not loaded. */
    public Profile get(UUID uuid) {
        return cache.get(uuid);
    }

    public int cachedCount() {
        return cache.size();
    }

    /**
     * Loads a profile from disk, blocking the calling thread.
     *
     * <p>Call this from {@code AsyncPlayerPreLoginEvent} and nowhere else. That
     * event already runs off the server thread and fires before the player
     * exists in the world, so blocking there costs nobody a tick — and it means
     * that by the time {@code PlayerJoinEvent} runs, the profile is simply
     * present, with no "not loaded yet" state for any later system to trip over.
     */
    public Profile loadBlocking(UUID uuid, String name) throws SQLException {
        Profile existing = cache.get(uuid);
        if (existing != null) {
            existing.setName(name);
            return existing;
        }
        Profile loaded = database.read(connection -> readOne(connection, uuid));
        if (loaded == null) {
            loaded = Profile.fresh(uuid, name, System.currentTimeMillis());
            loaded.markDirty();
        } else {
            loaded.setName(name);
        }
        Profile raced = cache.putIfAbsent(uuid, loaded);
        return raced != null ? raced : loaded;
    }

    public void beginSession(UUID uuid) {
        Profile profile = cache.get(uuid);
        if (profile != null) {
            profile.beginSession(System.currentTimeMillis());
        }
    }

    /** Ends the session, writes the profile durably, then drops it from the cache. */
    public void endSession(UUID uuid) {
        Profile profile = cache.get(uuid);
        if (profile == null) {
            return;
        }
        long now = System.currentTimeMillis();
        profile.endSession(now);
        profile.setLastSeen(now);
        saveDurable(profile).whenComplete((ignored, error) -> {
            if (error != null) {
                log.log(Level.SEVERE, "failed to save " + profile.name() + " on quit", error);
            }
        });
        // Safe to evict now: the write task captured its values at submit time,
        // so it does not read the profile again.
        cache.remove(uuid);
    }

    // ---- persistence ----------------------------------------------------

    /** Queues an immediate write and reports when it has actually committed. */
    public CompletableFuture<Void> saveDurable(Profile profile) {
        return database.submitTracked("profile:" + profile.uuid(), writeFor(profile));
    }

    /**
     * Writes every profile that changed since the last flush.
     *
     * @param includeClean write everything, dirty or not — used at shutdown
     * @return how many profiles were queued
     */
    public int flushDirty(boolean includeClean) {
        int queued = 0;
        for (Profile profile : cache.values()) {
            boolean columnsDirty = profile.takeDirty();
            boolean anythingDirty = columnsDirty || profile.state().isDirty();
            if (!anythingDirty && !includeClean) {
                continue;
            }
            database.submit("profile:" + profile.uuid(), writeFor(profile));
            queued++;
        }
        return queued;
    }

    public int flushDirty() {
        return flushDirty(false);
    }

    /**
     * Builds the write task, capturing the values now.
     *
     * <p>Capturing at submit time rather than reading the profile inside the
     * lambda is what makes it safe to evict a quitting player from the cache
     * immediately, and stops a concurrent mutation from half-landing in a write
     * that was meant to be a point-in-time snapshot.
     */
    private Database.SqlWork writeFor(Profile profile) {
        UUID uuid = profile.uuid();
        String name = profile.name();
        long firstSeen = profile.firstSeen();
        long lastSeen = profile.lastSeen();
        long playtime = profile.playtimeMillis();
        long balance = profile.balance();
        Map<String, String> state = profile.state().takeDirtySnapshot();

        return connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO players (uuid, name, first_seen, last_seen, playtime_ms, balance)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        name        = excluded.name,
                        last_seen   = excluded.last_seen,
                        playtime_ms = excluded.playtime_ms,
                        balance     = excluded.balance
                    """)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setLong(3, firstSeen);
                statement.setLong(4, lastSeen);
                statement.setLong(5, playtime);
                statement.setLong(6, balance);
                statement.executeUpdate();
            }
            if (state == null) {
                return; // nothing in the state bag changed
            }
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM player_state WHERE uuid = ?")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
            }
            if (state.isEmpty()) {
                return;
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO player_state (uuid, key, value) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, String> entry : state.entrySet()) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, entry.getKey());
                    insert.setString(3, entry.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        };
    }

    private Profile readOne(Connection connection, UUID uuid) throws SQLException {
        Profile profile = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name, first_seen, last_seen, playtime_ms, balance
                FROM players WHERE uuid = ?
                """)) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    profile = new Profile(uuid,
                            rs.getString("name"),
                            rs.getLong("first_seen"),
                            rs.getLong("last_seen"),
                            rs.getLong("playtime_ms"),
                            rs.getLong("balance"),
                            new StateMap());
                }
            }
        }
        if (profile == null) {
            return null;
        }
        Map<String, String> raw = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT key, value FROM player_state WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    raw.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        profile.state().load(raw, (key, reason) ->
                log.warning("player " + uuid + " state key '" + key + "' unreadable ("
                        + reason + "); using the default"));
        return profile;
    }
}
