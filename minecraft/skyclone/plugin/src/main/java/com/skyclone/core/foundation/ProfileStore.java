package com.skyclone.core.foundation;

import com.skyclone.core.foundation.ProfileModel.ProfileRow;
import com.skyclone.core.foundation.ProfileModel.Snapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Every profile SQL statement, Bukkit-free so the unit tests run it against a real SQLite file.
 * All methods run on the DB thread with the connection they are given (normally inside a
 * transaction). A membership with {@code left_at != 0} is closed: it is kept (purse, data) for a
 * future salvage feature but ignored everywhere else.
 */
final class ProfileStore {

    private ProfileStore() {}

    static final String[] SCHEMA_V1 = {
            "CREATE TABLE IF NOT EXISTS profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, mode TEXT NOT NULL DEFAULT 'normal', "
                    + "created INTEGER NOT NULL, bank INTEGER NOT NULL DEFAULT 0, bank_cap INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS profile_members (profile_id TEXT NOT NULL, uuid TEXT NOT NULL, joined INTEGER NOT NULL, "
                    + "active INTEGER NOT NULL DEFAULT 0, left_at INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0, "
                    + "last_name TEXT, purse INTEGER NOT NULL DEFAULT 0, bits INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (profile_id, uuid))",
            "CREATE INDEX IF NOT EXISTS profile_members_uuid ON profile_members(uuid)",
            "CREATE TABLE IF NOT EXISTS profile_member_data (profile_id TEXT NOT NULL, uuid TEXT NOT NULL, key TEXT NOT NULL, "
                    + "json TEXT NOT NULL, updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (profile_id, uuid, key))",
            "CREATE TABLE IF NOT EXISTS profile_shared_data (profile_id TEXT NOT NULL, key TEXT NOT NULL, json TEXT NOT NULL, "
                    + "updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (profile_id, key))",
            "CREATE TABLE IF NOT EXISTS economy_ledger (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, "
                    + "profile_id TEXT, uuid TEXT, currency TEXT NOT NULL, delta INTEGER NOT NULL, balance_after INTEGER NOT NULL, reason TEXT)",
            "CREATE INDEX IF NOT EXISTS economy_ledger_uuid ON economy_ledger(uuid, id)",
            "CREATE INDEX IF NOT EXISTS economy_ledger_profile ON economy_ledger(profile_id, id)"
    };

    static final String UPSERT_MEMBER =
            "INSERT INTO profile_member_data(profile_id, uuid, key, json, updated) VALUES(?, ?, ?, ?, ?) "
                    + "ON CONFLICT(profile_id, uuid, key) DO UPDATE SET json = excluded.json, updated = excluded.updated";
    static final String UPSERT_SHARED =
            "INSERT INTO profile_shared_data(profile_id, key, json, updated) VALUES(?, ?, ?, ?) "
                    + "ON CONFLICT(profile_id, key) DO UPDATE SET json = excluded.json, updated = excluded.updated";

    /** The player's active profile with every row a session needs; creates the first profile if none. */
    static Snapshot readOrCreate(Connection c, UUID u, String playerName, long seq, Logger log) throws SQLException {
        Snapshot s = new Snapshot();
        s.uuid = u;
        s.playerName = playerName;
        s.seq = seq;
        s.readAt = System.currentTimeMillis();
        String us = u.toString();

        String pid = null;
        for (int attempt = 0; attempt < 3 && pid == null; attempt++) {
            List<String> actives = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT profile_id FROM profile_members WHERE uuid = ? AND active = 1 AND left_at = 0 ORDER BY joined")) {
                ps.setString(1, us);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) actives.add(rs.getString(1)); }
            }
            if (!actives.isEmpty()) {
                pid = actives.get(0);
                if (actives.size() > 1) setActive(c, us, pid);
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT profile_id FROM profile_members WHERE uuid = ? AND left_at = 0 ORDER BY joined LIMIT 1")) {
                    ps.setString(1, us);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) pid = rs.getString(1); }
                }
                if (pid != null) setActive(c, us, pid);
            }
            if (pid != null && !profileExists(c, pid)) {
                if (log != null) log.severe("[profiles] membership of " + playerName + " points at missing profile " + pid + "; closing it");
                closeMembership(c, pid, us);
                pid = null;
            }
        }
        if (pid == null) {
            pid = createProfile(c, u, playerName, true);
            if (log != null) log.info("[profiles] created first profile for " + playerName);
        }
        s.profileId = pid;
        try (PreparedStatement ps = c.prepareStatement("SELECT name, mode, created, bank, bank_cap FROM profiles WHERE id = ?")) {
            ps.setString(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("profile " + pid + " vanished mid-load");
                s.profileName = rs.getString(1);
                s.mode = rs.getString(2);
                s.created = rs.getLong(3);
                s.bank = rs.getLong(4);
                s.bankCap = rs.getLong(5);
            }
        }
        s.members = membersOf(c, pid);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT purse, bits, seen FROM profile_members WHERE profile_id = ? AND uuid = ?")) {
            ps.setString(1, pid);
            ps.setString(2, us);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("membership row vanished mid-load");
                s.purse = rs.getLong(1);
                s.bits = rs.getLong(2);
                s.firstJoin = rs.getInt(3) == 0;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE profile_members SET last_name = ? WHERE profile_id = ? AND uuid = ?")) {
            ps.setString(1, playerName);
            ps.setString(2, pid);
            ps.setString(3, us);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT key, json FROM profile_member_data WHERE profile_id = ? AND uuid = ?")) {
            ps.setString(1, pid);
            ps.setString(2, us);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) s.memberJson.put(rs.getString(1), rs.getString(2)); }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT key, json FROM profile_shared_data WHERE profile_id = ?")) {
            ps.setString(1, pid);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) s.sharedJson.put(rs.getString(1), rs.getString(2)); }
        }
        return s;
    }

    static boolean profileExists(Connection c, String pid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM profiles WHERE id = ?")) {
            ps.setString(1, pid);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    static boolean isMember(Connection c, String pid, String uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM profile_members WHERE profile_id = ? AND uuid = ? AND left_at = 0")) {
            ps.setString(1, pid);
            ps.setString(2, uuid);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    static List<UUID> membersOf(Connection c, String pid) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid FROM profile_members WHERE profile_id = ? AND left_at = 0 ORDER BY joined")) {
            ps.setString(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try { out.add(UUID.fromString(rs.getString(1))); } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        return out;
    }

    static void setActive(Connection c, String uuid, String pid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE profile_members SET active = 0 WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE profile_members SET active = 1 WHERE uuid = ? AND profile_id = ?")) {
            ps.setString(1, uuid);
            ps.setString(2, pid);
            ps.executeUpdate();
        }
    }

    /** Active profile id of a (possibly offline) player, or null. */
    static String activeProfile(Connection c, UUID u) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT profile_id FROM profile_members WHERE uuid = ? AND left_at = 0 ORDER BY active DESC, joined LIMIT 1")) {
            ps.setString(1, u.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    static int profileCount(Connection c, UUID u) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM profile_members WHERE uuid = ? AND left_at = 0")) {
            ps.setString(1, u.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    static void closeMembership(Connection c, String pid, String uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE profile_members SET left_at = ?, active = 0 WHERE profile_id = ? AND uuid = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, pid);
            ps.setString(3, uuid);
            ps.executeUpdate();
        }
    }

    /** Leave a co-op: close the membership and make the oldest remaining profile active. */
    static void leave(Connection c, String pid, String uuid) throws SQLException {
        closeMembership(c, pid, uuid);
        String next = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT profile_id FROM profile_members WHERE uuid = ? AND left_at = 0 ORDER BY joined LIMIT 1")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) next = rs.getString(1); }
        }
        if (next != null) setActive(c, uuid, next);
    }

    /** Make {@code target} active if the player is (still) a member of it. */
    static boolean switchTo(Connection c, String uuid, String target) throws SQLException {
        if (!isMember(c, target, uuid)) return false;
        setActive(c, uuid, target);
        return true;
    }

    /** Creates a profile with {@code u} as its only member. Inside a transaction. */
    static String createProfile(Connection c, UUID u, String playerName, boolean makeActive) throws SQLException {
        Set<String> used = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT p.name FROM profiles p JOIN profile_members m ON m.profile_id = p.id WHERE m.uuid = ? AND m.left_at = 0")) {
            ps.setString(1, u.toString());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) used.add(rs.getString(1)); }
        }
        List<String> free = new ArrayList<>();
        for (String f : ProfileModel.FRUITS) if (!used.contains(f)) free.add(f);
        List<String> pool = free.isEmpty() ? ProfileModel.FRUITS : free;
        String name = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO profiles(id, name, mode, created, bank, bank_cap) VALUES(?, ?, 'normal', ?, 0, 0)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
        if (makeActive) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE profile_members SET active = 0 WHERE uuid = ?")) {
                ps.setString(1, u.toString());
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO profile_members(profile_id, uuid, joined, active, left_at, seen, last_name, purse, bits) "
                        + "VALUES(?, ?, ?, ?, 0, 0, ?, 0, 0)")) {
            ps.setString(1, id);
            ps.setString(2, u.toString());
            ps.setLong(3, now);
            ps.setInt(4, makeActive ? 1 : 0);
            ps.setString(5, playerName);
            ps.executeUpdate();
        }
        return id;
    }

    static String profileName(Connection c, String pid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM profiles WHERE id = ?")) {
            ps.setString(1, pid);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    /** Join a co-op: refuses when full, already a member, or at the profile cap. Makes it active. */
    static boolean addMember(Connection c, String profileId, UUID player, int maxCoop, int maxProfiles) throws SQLException {
        if (!profileExists(c, profileId)) return false;
        List<UUID> members = membersOf(c, profileId);
        if (members.contains(player)) return false;
        if (members.size() >= maxCoop) return false;
        if (profileCount(c, player) >= maxProfiles) return false;
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement("UPDATE profile_members SET active = 0 WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO profile_members(profile_id, uuid, joined, active, left_at, seen, purse, bits) VALUES(?, ?, ?, 1, 0, 0, 0, 0) "
                        + "ON CONFLICT(profile_id, uuid) DO UPDATE SET active = 1, left_at = 0, joined = excluded.joined, seen = 0")) {
            ps.setString(1, profileId);
            ps.setString(2, player.toString());
            ps.setLong(3, now);
            ps.executeUpdate();
        }
        return true;
    }

    static List<ProfileRow> listProfiles(Connection c, UUID u) throws SQLException {
        List<ProfileRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT p.id, p.name, p.mode, p.created, m.purse, m.active, "
                        + "(SELECT COUNT(*) FROM profile_members x WHERE x.profile_id = p.id AND x.left_at = 0) "
                        + "FROM profile_members m JOIN profiles p ON p.id = m.profile_id "
                        + "WHERE m.uuid = ? AND m.left_at = 0 ORDER BY m.joined")) {
            ps.setString(1, u.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ProfileRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getInt(7), rs.getLong(5), rs.getInt(6) == 1));
                }
            }
        }
        return out;
    }

    static String memberJson(Connection c, String pid, UUID u, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT json FROM profile_member_data WHERE profile_id = ? AND uuid = ? AND key = ?")) {
            ps.setString(1, pid);
            ps.setString(2, u.toString());
            ps.setString(3, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    static void upsertMember(Connection c, String pid, UUID u, String key, String json) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPSERT_MEMBER)) {
            ps.setString(1, pid);
            ps.setString(2, u.toString());
            ps.setString(3, key);
            ps.setString(4, json);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    static void markSeen(Connection c, String pid, UUID u) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE profile_members SET seen = 1 WHERE profile_id = ? AND uuid = ?")) {
            ps.setString(1, pid);
            ps.setString(2, u.toString());
            ps.executeUpdate();
        }
    }
}
