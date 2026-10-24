package com.thestill.admin;

import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Editing somebody who is not here.
 *
 * <h2>Why this class has to exist at all</h2>
 * Shadow Prison's {@code /adminset} works on offline players for free, and it is worth
 * understanding why before copying it: its store is one YAML file that is read into a
 * {@code HashMap} at boot and never evicted, so <em>every</em> player who has ever
 * joined is permanently in memory. Offline editing there is not a feature, it is an
 * accident of a whole-file store.
 *
 * <p><b>The Still is deliberately the opposite.</b> {@link Profiles} caches online
 * players only — {@code endSession} evicts the instant somebody quits, and
 * {@code get(uuid)} is cache-only and returns null for anyone else. That is the right
 * design for a server that is meant to scale, and it means offline editing is new
 * machinery here rather than a free lunch. It is also the most dangerous code in the
 * admin package, so every rule below is load-bearing.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li><b>Live wins, always.</b> If the player is online, hand back the cached
 *       profile. Loading a second copy from SQL for somebody who is playing gives you
 *       two {@code Profile} objects for one player, and whichever writes last wins —
 *       silently, and usually not the one they are actually using.
 *   <li><b>Never call {@link Profiles#loadBlocking}.</b> It inserts into the live
 *       cache. From there the flush timer writes an offline player forever, their
 *       session never ends because they never quit, and shutdown folds a fake session
 *       into their playtime. Its own javadoc says pre-login only, and it means it.
 *   <li><b>Load the state bag in full, or refuse.</b> {@code Profiles.writeFor} does
 *       {@code DELETE FROM player_state} and reinserts the whole bag. A half-loaded
 *       {@code StateMap} written back <em>erases every key it did not load</em>.
 *   <li><b>No row means no target.</b> Never mint a fresh profile for a name that has
 *       never joined — that is how an admin spends ten minutes editing a ghost.
 *   <li><b>Re-check liveness at commit.</b> They may have logged in while the menu was
 *       open. If so, abort the write and say so. Do not merge, do not overwrite.
 * </ol>
 */
public final class OfflineProfiles {

    private final Logger log;
    private final Plugin plugin;
    private final Database database;
    private final Profiles profiles;

    public OfflineProfiles(Logger log, Plugin plugin, Database database, Profiles profiles) {
        this.log = log;
        this.plugin = plugin;
        this.database = database;
        this.profiles = profiles;
    }

    /**
     * Finds somebody by name, online or not.
     *
     * <p>Async, because {@link Database#read} throws if called on the main thread —
     * which is a guard rail, not an inconvenience: a name lookup that blocks the tick
     * is a name lookup that stutters the server every time an admin tab-completes.
     */
    public CompletableFuture<Optional<AdminTarget>> find(String name) {
        for (var online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                Profile live = profiles.get(online.getUniqueId());
                if (live != null) {
                    return CompletableFuture.completedFuture(
                            Optional.of(new AdminTarget(online.getUniqueId(),
                                    online.getName(), live, true)));
                }
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return database.read(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT uuid, name, first_seen, last_seen, playtime_ms, balance
                            FROM players WHERE name = ? COLLATE NOCASE
                            ORDER BY last_seen DESC LIMIT 1
                            """)) {
                        statement.setString(1, name);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (!rows.next()) {
                                return Optional.<AdminTarget>empty();
                            }
                            UUID uuid = UUID.fromString(rows.getString("uuid"));
                            com.thestill.state.StateMap state = new com.thestill.state.StateMap();
                            loadState(connection, uuid, state);
                            Profile profile = new Profile(uuid, rows.getString("name"),
                                    rows.getLong("first_seen"), rows.getLong("last_seen"),
                                    rows.getLong("playtime_ms"), rows.getLong("balance"), state);
                            return Optional.of(new AdminTarget(uuid, profile.name(),
                                    profile, false));
                        }
                    }
                });
            } catch (Exception e) {
                log.log(Level.WARNING, "admin: could not look up '" + name + "'", e);
                return Optional.<AdminTarget>empty();
            }
        });
    }

    /** Names an admin might mean. Online first, then whoever was here most recently. */
    public CompletableFuture<List<String>> suggest(int limit) {
        List<String> online = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> online.add(player.getName()));
        return CompletableFuture.supplyAsync(() -> {
            List<String> out = new ArrayList<>(online);
            try {
                database.read(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT name FROM players ORDER BY last_seen DESC LIMIT ?")) {
                        statement.setInt(1, limit);
                        try (ResultSet rows = statement.executeQuery()) {
                            while (rows.next()) {
                                String name = rows.getString("name");
                                if (!out.contains(name)) {
                                    out.add(name);
                                }
                            }
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                log.log(Level.FINE, "admin: name suggestions unavailable", e);
            }
            return out;
        });
    }

    /**
     * Loads the whole state bag.
     *
     * <p>All of it, every time. See rule 3 — a partial load that gets written back is
     * a silent deletion of everything it did not read.
     */
    private void loadState(java.sql.Connection connection, UUID uuid,
                           com.thestill.state.StateMap state) throws java.sql.SQLException {
        Map<String, String> raw = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT key, value FROM player_state WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    raw.put(rows.getString("key"), rows.getString("value"));
                }
            }
        }
        state.load(raw, (key, reason) ->
                log.warning("admin: skipping bad state '" + key + "' for " + uuid
                        + " — " + reason));
    }

    /**
     * Writes an edited profile back.
     *
     * <p>A live target goes through {@link Profiles#saveDurable} so it takes the same
     * path a normal save does. A detached one is written here — and re-checked first,
     * because between opening the menu and clicking Save the player may have joined,
     * and at that moment this object became a stale copy of somebody's live data.
     *
     * @return true if the write was submitted; false if the target went live and the
     *         admin needs to start again
     */
    public boolean commit(AdminTarget target) {
        if (!target.live() && profiles.get(target.uuid()) != null) {
            return false;
        }
        if (target.live()) {
            profiles.saveDurable(target.profile());
            return true;
        }
        Profile profile = target.profile();
        UUID uuid = profile.uuid();
        Map<String, String> state = profile.state().serialise();
        long lastSeen = profile.lastSeen();
        long playtime = profile.playtimeMillis();
        long balance = profile.balance();
        String name = profile.name();

        database.submitTracked("admin-edit " + name, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE players SET name = ?, last_seen = ?, playtime_ms = ?, balance = ?
                    WHERE uuid = ?
                    """)) {
                statement.setString(1, name);
                statement.setLong(2, lastSeen);
                statement.setLong(3, playtime);
                statement.setLong(4, balance);
                statement.setString(5, uuid.toString());
                statement.executeUpdate();
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
        });
        return true;
    }

    /** Hops back to the main thread. Menus are not thread-safe and Bukkit is not either. */
    public void onMain(Runnable work) {
        Bukkit.getScheduler().runTask(plugin, work);
    }
}
