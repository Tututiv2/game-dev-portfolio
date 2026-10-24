package com.thestill.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * Versioned schema, applied forward one step at a time and tracked in SQLite's
 * own {@code PRAGMA user_version}.
 *
 * <p>To change the schema, append a migration to {@link #MIGRATIONS} and never
 * edit an existing one — an edited migration silently does nothing on any
 * database that already ran it, which is the kind of divergence you only find
 * out about in production.
 */
final class Schema {

    private Schema() {
    }

    /**
     * Index n in this list upgrades the database from user_version n to n+1.
     *
     * <p>Both {@code *_state} tables are the schema-free extension point: Phase
     * 2's Din band, Phase 4's room heat, Phase 6's PvP flag and Phase 8's Ledger
     * are all rows here, not columns, so none of them needs a migration.
     * Columns are reserved for the handful of fields that get queried or sorted.
     */
    // Explicit type witness: with a single String[] argument, plain List.of would
    // bind E to String and spread the array instead of nesting it.
    private static final List<String[]> MIGRATIONS = List.<String[]>of(

            // ---- v0 -> v1: players, regions, and the crash probe ----------
            new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS players (
                        uuid        TEXT    PRIMARY KEY NOT NULL,
                        name        TEXT    NOT NULL,
                        first_seen  INTEGER NOT NULL,
                        last_seen   INTEGER NOT NULL,
                        playtime_ms INTEGER NOT NULL DEFAULT 0,
                        -- Integer minor units, never a float. A double balance is
                        -- how a single NaN permanently poisoned an account on
                        -- Shadow Prison; with an integer column it is unstateable.
                        balance     INTEGER NOT NULL DEFAULT 0
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name)",
                    """
                    CREATE TABLE IF NOT EXISTS player_state (
                        uuid  TEXT NOT NULL,
                        key   TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (uuid, key)
                    ) WITHOUT ROWID
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS regions (
                        id         TEXT    PRIMARY KEY NOT NULL,
                        type       TEXT    NOT NULL,
                        parent_id  TEXT,
                        world      TEXT    NOT NULL,
                        -- Explicit persisted coordinates. Shadow Prison derived mine
                        -- positions from iteration-order grid indices, so deleting one
                        -- mine silently relocated every mine after it.
                        min_x      INTEGER NOT NULL,
                        min_y      INTEGER NOT NULL,
                        min_z      INTEGER NOT NULL,
                        max_x      INTEGER NOT NULL,
                        max_y      INTEGER NOT NULL,
                        max_z      INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_regions_parent ON regions(parent_id)",
                    "CREATE INDEX IF NOT EXISTS idx_regions_world ON regions(world)",
                    """
                    CREATE TABLE IF NOT EXISTS region_state (
                        region_id TEXT NOT NULL,
                        key       TEXT NOT NULL,
                        value     TEXT NOT NULL,
                        PRIMARY KEY (region_id, key)
                    ) WITHOUT ROWID
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS crash_probe (
                        run_id     TEXT    PRIMARY KEY NOT NULL,
                        seq        INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """
            },

            // ---- v1 -> v2: the admin audit trail --------------------------
            // Shadow Prison's /adminset can set anybody's balance to anything and
            // leaves no record that it happened, which makes the most powerful
            // command on the server the one thing nobody can moderate. Every
            // value-changing click here writes a row: who, whom, what, and the
            // before and after. It is a table rather than a log file because the
            // question is always "what happened to THIS player", and that is an
            // index rather than a grep.
            new String[]{
                    """
                    CREATE TABLE IF NOT EXISTS admin_audit (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        at          INTEGER NOT NULL,
                        admin_uuid  TEXT    NOT NULL,
                        admin_name  TEXT    NOT NULL,
                        target_uuid TEXT    NOT NULL,
                        target_name TEXT    NOT NULL,
                        action      TEXT    NOT NULL,
                        -- Not `before`/`after`: both are SQLite keywords (trigger
                        -- timing), and a column name that only parses by accident is
                        -- a migration that fails on some future driver.
                        old_value   TEXT,
                        new_value   TEXT
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_audit_target ON admin_audit(target_uuid, at)",
                    "CREATE INDEX IF NOT EXISTS idx_audit_at ON admin_audit(at)"
            }
    );

    static final int TARGET_VERSION = MIGRATIONS.size();

    static void migrate(Connection connection, Logger log) throws SQLException {
        int current = userVersion(connection);
        if (current > TARGET_VERSION) {
            throw new SQLException("database is at schema v" + current
                    + " but this build only knows v" + TARGET_VERSION
                    + " — you are running an older plugin against a newer database");
        }
        if (current == TARGET_VERSION) {
            log.info("database schema v" + current + " (up to date)");
            return;
        }

        for (int version = current; version < TARGET_VERSION; version++) {
            log.info("migrating database v" + version + " -> v" + (version + 1));
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : MIGRATIONS.get(version)) {
                    statement.executeUpdate(sql);
                }
                // Cannot be parameterised, and cannot run inside the prepared
                // statement above; the value is an int from a loop, not input.
                statement.executeUpdate("PRAGMA user_version = " + (version + 1));
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new SQLException("migration to v" + (version + 1) + " failed", e);
            } finally {
                connection.setAutoCommit(true);
            }
        }
        log.info("database schema now v" + TARGET_VERSION);
    }

    private static int userVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
