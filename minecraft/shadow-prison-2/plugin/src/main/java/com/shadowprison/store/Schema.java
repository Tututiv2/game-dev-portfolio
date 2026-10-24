package com.shadowprison.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * Versioned schema tracked in SQLite's own {@code PRAGMA user_version}.
 *
 * <p><b>Append a migration; never edit one.</b> An edited migration silently does nothing on any
 * database that already ran it — divergence you only discover in production.
 */
final class Schema {

    private Schema() {
    }

    /** Index n upgrades user_version n to n+1. */
    private static final List<String[]> MIGRATIONS = List.<String[]>of(
            // ---- v0 -> v1: Phase 0 foundation -------------------------------------------------
            new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS players (
                        uuid        TEXT    PRIMARY KEY NOT NULL,
                        name        TEXT    NOT NULL,
                        first_seen  INTEGER NOT NULL,
                        last_seen   INTEGER NOT NULL,
                        playtime_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name COLLATE NOCASE)",
                    // Money: INTEGER minor units per currency. SP1 kept a double balance, and a
                    // single NaN typed into /gang bank made every later check pass (rule 27).
                    """
                    CREATE TABLE IF NOT EXISTS wallets (
                        uuid     TEXT    NOT NULL,
                        currency TEXT    NOT NULL,
                        amount   INTEGER NOT NULL CHECK (amount >= 0),
                        PRIMARY KEY (uuid, currency)
                    ) WITHOUT ROWID
                    """,
                    // Every money movement, with its reason. SP1 could not answer "where did this
                    // player's balance come from" without grepping logs.
                    """
                    CREATE TABLE IF NOT EXISTS money_ledger (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid          TEXT    NOT NULL,
                        currency      TEXT    NOT NULL,
                        delta         INTEGER NOT NULL,
                        balance_after INTEGER NOT NULL,
                        reason        TEXT    NOT NULL,
                        at            INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_ledger_uuid ON money_ledger(uuid, at)",
                    // Schema-free per-player state: a new module adds rows, not columns.
                    """
                    CREATE TABLE IF NOT EXISTS player_state (
                        uuid  TEXT NOT NULL,
                        key   TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (uuid, key)
                    ) WITHOUT ROWID
                    """,
                    // /claim: the REAL ItemStack bytes. SP1 stored re-derivable descriptors and
                    // rebuilt them on claim — keys and GPUs came back as plain books (rule 29).
                    """
                    CREATE TABLE IF NOT EXISTS claims (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid       TEXT    NOT NULL,
                        item       BLOB    NOT NULL,
                        source     TEXT    NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_claims_uuid ON claims(uuid, id)",
                    // Dials: the owner's live tuning. The registry declares them; this holds values.
                    """
                    CREATE TABLE IF NOT EXISTS dials (
                        key        TEXT PRIMARY KEY NOT NULL,
                        value      TEXT NOT NULL,
                        updated_by TEXT,
                        updated_at INTEGER NOT NULL
                    ) WITHOUT ROWID
                    """,
                    // Unique item instances (serials, minted gear). One row per real object: where
                    // it is is a question with one answer. SP1's /invbackup restore could put a
                    // serial back while the original was still in circulation.
                    """
                    CREATE TABLE IF NOT EXISTS item_instances (
                        uid        TEXT    PRIMARY KEY NOT NULL,
                        item_id    TEXT    NOT NULL,
                        serial     INTEGER,
                        owner      TEXT,
                        source     TEXT    NOT NULL,
                        created_at INTEGER NOT NULL
                    ) WITHOUT ROWID
                    """,
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_instances_serial ON item_instances(item_id, serial)",
                    // Privilege by UUID only. SP1 matched NAMES; a released name could op itself (rule 31).
                    """
                    CREATE TABLE IF NOT EXISTS staff (
                        uuid     TEXT PRIMARY KEY NOT NULL,
                        role     TEXT NOT NULL,
                        added_at INTEGER NOT NULL
                    ) WITHOUT ROWID
                    """
            }
    );

    static void migrate(Connection connection, Logger log) throws SQLException {
        int version = userVersion(connection);
        if (version > MIGRATIONS.size()) {
            throw new SQLException("database is at schema v" + version + " but this build only knows v"
                    + MIGRATIONS.size() + " — refusing to run an older plugin against a newer store");
        }
        for (int v = version; v < MIGRATIONS.size(); v++) {
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : MIGRATIONS.get(v)) {
                    statement.execute(sql);
                }
                statement.execute("PRAGMA user_version=" + (v + 1));
                connection.commit();
                log.info("schema migrated v" + v + " -> v" + (v + 1));
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
        }
    }

    static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    static int latestVersion() {
        return MIGRATIONS.size();
    }
}
