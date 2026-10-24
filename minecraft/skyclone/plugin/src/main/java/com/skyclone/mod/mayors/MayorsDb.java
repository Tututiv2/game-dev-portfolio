package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Schema for the whole module, in version order (a later version must never be migrated first). */
final class MayorsDb {

    private MayorsDb() {}

    static void migrate() {
        Sky.db().migrate("mayors", 1,
                "CREATE TABLE IF NOT EXISTS mayors_state (k TEXT PRIMARY KEY, v TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS mayors_votes (election INTEGER NOT NULL, uuid TEXT NOT NULL, name TEXT, "
                        + "candidate TEXT NOT NULL, weight INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (election, uuid))");
        Sky.db().migrate("mayors", 2,
                "CREATE TABLE IF NOT EXISTS mayors_claims (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, item TEXT NOT NULL, "
                        + "amount INTEGER NOT NULL, extra TEXT, reason TEXT, at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS mayors_claims_uuid ON mayors_claims(uuid)");
        Sky.db().migrate("mayors", 3,
                "CREATE TABLE IF NOT EXISTS mayors_spooky (festival TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT, "
                        + "points INTEGER NOT NULL, green INTEGER NOT NULL, purple INTEGER NOT NULL, PRIMARY KEY (festival, uuid))");
        Sky.db().migrate("mayors", 4,
                "CREATE TABLE IF NOT EXISTS mayors_stonks (round TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT, "
                        + "bid REAL NOT NULL, profile TEXT, PRIMARY KEY (round, uuid))");
    }

    /** Blocking read of one key (enable only). */
    static String readKey(String key) {
        try {
            return Sky.db().query(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT v FROM mayors_state WHERE k = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
                }
            }).join();
        } catch (Exception e) {
            Sky.log().warning("[mayors] could not read state key " + key + ": " + e);
            return null;
        }
    }

    /** Async upsert of one key. */
    static void writeKey(String key, String value) {
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mayors_state(k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
