package com.shadowprison.profile;

import com.shadowprison.core.Service;
import com.shadowprison.money.Currency;
import com.shadowprison.store.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads a profile OFF the main thread before the player is in the world, attaches it at join,
 * persists on change and at quit.
 *
 * <p><b>Fail closed:</b> a player whose profile did not load is refused at join. SP1 would hand
 * them a blank default profile and then SAVE it — over the real one.
 */
public final class Profiles implements Service, Listener {

    private final Logger log;
    private final Database db;
    /** Loaded during pre-login, waiting for the join event. Concurrent: written off-thread. */
    private final Map<UUID, Profile> loaded = new ConcurrentHashMap<>();
    /** Online players. Main thread only. */
    private final Map<UUID, Profile> online = new ConcurrentHashMap<>();

    public Profiles(Logger log, Database db) {
        this.log = log;
        this.db = db;
    }

    public Profile get(Player player) {
        return online.get(player.getUniqueId());
    }

    public Profile get(UUID uuid) {
        return online.get(uuid);
    }

    public java.util.Collection<Profile> online() {
        return online.values();
    }

    // ---- loading ---------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        try {
            loaded.put(uuid, load(uuid, event.getName()));
        } catch (Exception e) {
            log.log(Level.SEVERE, "profile load failed for " + event.getName() + " — refusing login", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Your profile could not be loaded. Please try again in a moment.", NamedTextColor.RED));
        }
    }

    private Profile load(UUID uuid, String name) throws Exception {
        return db.read(connection -> loadFrom(connection, uuid, name));
    }

    /** The ONE loader — pre-login and /reload both use it, so neither can drop a field. */
    private static Profile loadFrom(java.sql.Connection connection, UUID uuid, String name) throws java.sql.SQLException {
        Profile profile;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT first_seen, playtime_ms FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                profile = rs.next()
                        ? new Profile(uuid, name, rs.getLong(1), rs.getLong(2))
                        : new Profile(uuid, name, System.currentTimeMillis(), 0L);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT currency, amount FROM wallets WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        profile.balances.put(Currency.valueOf(rs.getString(1)), rs.getLong(2));
                    } catch (IllegalArgumentException unknown) {
                        // A currency this build does not know: the row stays in the store untouched.
                    }
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT key, value FROM player_state WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    profile.state.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return profile;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Profile profile = loaded.remove(player.getUniqueId());
        if (profile == null) {
            // Pre-login allowed us but nothing loaded (a reload, an exotic login path). Never play blank.
            player.kick(Component.text("Your profile is still loading — please rejoin.", NamedTextColor.RED));
            return;
        }
        profile.name(player.getName());
        online.put(player.getUniqueId(), profile);
        long now = System.currentTimeMillis();
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        long first = profile.firstSeen();
        db.submit("profile-join", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO players(uuid, name, first_seen, last_seen, playtime_ms) VALUES (?, ?, ?, ?, 0)
                    ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen
                    """)) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setLong(3, first);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Profile profile = online.remove(event.getPlayer().getUniqueId());
        if (profile != null) {
            persistPlaytime(profile);
        }
    }

    private void persistPlaytime(Profile profile) {
        long playtime = profile.playtimeMs();
        long now = System.currentTimeMillis();
        String uuid = profile.uuid().toString();
        db.submit("profile-quit", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET playtime_ms = ?, last_seen = ? WHERE uuid = ?")) {
                ps.setLong(1, playtime);
                ps.setLong(2, now);
                ps.setString(3, uuid);
                ps.executeUpdate();
            }
        });
    }

    // ---- state -----------------------------------------------------------------------------

    /** Sets a schema-free state value and persists it. Main thread. */
    public void setState(Profile profile, String key, String value) {
        String uuid = profile.uuid().toString();
        if (value == null) {
            profile.state.remove(key);
            db.submit("state-del", c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM player_state WHERE uuid = ? AND key = ?")) {
                    ps.setString(1, uuid);
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
            });
            return;
        }
        profile.state.put(key, value);
        db.submit("state-set", c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO player_state(uuid, key, value) VALUES (?, ?, ?)
                    ON CONFLICT(uuid, key) DO UPDATE SET value = excluded.value
                    """)) {
                ps.setString(1, uuid);
                ps.setString(2, key);
                ps.setString(3, value);
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void start() {
        // A /reload with players online: they never went through pre-login. Enable is
        // single-threaded and nothing is ticking, so a blocking lifecycle read is correct here.
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                UUID uuid = player.getUniqueId();
                online.put(uuid, db.readDuringLifecycle(c -> loadFrom(c, uuid, player.getName())));
            } catch (Exception e) {
                log.log(Level.SEVERE, "reload: could not load " + player.getName(), e);
                player.kick(Component.text("Server reloaded — please rejoin.", NamedTextColor.RED));
            }
        }
    }

    @Override
    public void stop() {
        for (Profile profile : online.values()) {
            persistPlaytime(profile);
        }
        online.clear();
        loaded.clear();
    }
}
