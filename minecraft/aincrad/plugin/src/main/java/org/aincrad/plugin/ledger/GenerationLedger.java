package org.aincrad.plugin.ledger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SQLite-backed persistent record of generation work, per docs/floor-01-layout.json's
 * generation_contract: world seed, datapack hash, generator build, layout schema version, floor
 * design version, phase id, feature id, feature version, affected chunks, lifecycle state, and
 * committed content hash. A feature may only be written when its current content hash matches
 * the expected predecessor, or when it has no owner-protection flag set - this is enforced by
 * {@link #tryTransition}, not left to caller discipline.
 */
public final class GenerationLedger implements AutoCloseable {

    public enum FeatureState {PLANNED, GENERATED, VALIDATED, COMMITTED}

    public record FeatureRecord(
            String featureId,
            String featureVersion,
            String phaseId,
            FeatureState state,
            String contentHash,
            boolean ownerProtected,
            long updatedAt
    ) {
    }

    private final Connection connection;

    public GenerationLedger(Path databaseFile) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver not on classpath", e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ledger_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS features (
                        feature_id TEXT PRIMARY KEY,
                        feature_version TEXT NOT NULL,
                        phase_id TEXT NOT NULL,
                        state TEXT NOT NULL,
                        content_hash TEXT,
                        owner_protected INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS feature_chunks (
                        feature_id TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        PRIMARY KEY (feature_id, chunk_x, chunk_z)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS npc_records (
                        npc_id TEXT PRIMARY KEY,
                        global_floor INTEGER NOT NULL,
                        home_parcel TEXT NOT NULL,
                        occupation TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        schedule_id TEXT NOT NULL,
                        version TEXT NOT NULL
                    )
                    """);
        }
    }

    public void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ledger_meta(key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    public Optional<String> getMeta(String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM ledger_meta WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("value")) : Optional.empty();
            }
        }
    }

    public Optional<FeatureRecord> getFeature(String featureId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM features WHERE feature_id = ?")) {
            statement.setString(1, featureId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new FeatureRecord(
                        rs.getString("feature_id"),
                        rs.getString("feature_version"),
                        rs.getString("phase_id"),
                        FeatureState.valueOf(rs.getString("state")),
                        rs.getString("content_hash"),
                        rs.getInt("owner_protected") != 0,
                        rs.getLong("updated_at")
                ));
            }
        }
    }

    /**
     * Plans a feature for the first time. No-op (returns false) if the feature already exists -
     * planning never overwrites an existing record; use {@link #tryTransition} for state changes.
     */
    public boolean planFeature(String featureId, String featureVersion, String phaseId, Set<int[]> chunkCoords,
                                long nowMillis) throws SQLException {
        if (getFeature(featureId).isPresent()) {
            return false;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO features(feature_id, feature_version, phase_id, state, content_hash, owner_protected, updated_at) " +
                        "VALUES (?, ?, ?, 'PLANNED', NULL, 0, ?)")) {
            insert.setString(1, featureId);
            insert.setString(2, featureVersion);
            insert.setString(3, phaseId);
            insert.setLong(4, nowMillis);
            insert.executeUpdate();
        }
        try (PreparedStatement insertChunk = connection.prepareStatement(
                "INSERT OR IGNORE INTO feature_chunks(feature_id, chunk_x, chunk_z) VALUES (?, ?, ?)")) {
            for (int[] coord : chunkCoords) {
                insertChunk.setString(1, featureId);
                insertChunk.setInt(2, coord[0]);
                insertChunk.setInt(3, coord[1]);
                insertChunk.addBatch();
            }
            insertChunk.executeBatch();
        }
        return true;
    }

    /**
     * Attempts to move a feature to a new state. Refuses (returns false) if the feature is
     * owner-protected, or if {@code expectedPredecessorHash} is supplied and does not match the
     * feature's current content hash - this is the "refuse to overwrite an owner-edited/protected
     * cell" contract, enforced centrally rather than trusted to callers.
     */
    public boolean tryTransition(String featureId, FeatureState newState, String newContentHash,
                                  String expectedPredecessorHash, long nowMillis) throws SQLException {
        Optional<FeatureRecord> existing = getFeature(featureId);
        if (existing.isEmpty()) {
            return false;
        }
        FeatureRecord record = existing.get();
        if (record.ownerProtected()) {
            return false;
        }
        if (expectedPredecessorHash != null && !expectedPredecessorHash.equals(record.contentHash())) {
            return false;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE features SET state = ?, content_hash = ?, updated_at = ? WHERE feature_id = ?")) {
            update.setString(1, newState.name());
            update.setString(2, newContentHash);
            update.setLong(3, nowMillis);
            update.setString(4, featureId);
            update.executeUpdate();
        }
        return true;
    }

    /**
     * Explicit, version-gated migration primitive. Moves a feature from one feature_version to
     * another and resets it to PLANNED so its phase will re-run. Refuses (returns false) if the
     * feature does not exist, is owner-protected, or is not currently at {@code fromVersion} -
     * so a migration can never silently re-run against an already-migrated or hand-edited
     * feature. This is the "generator updates require explicit, backup-first migrations with a
     * declared from-version and to-version" rule from the project's generation contract.
     */
    public boolean migrateFeatureVersion(String featureId, String fromVersion, String toVersion,
                                          long nowMillis) throws SQLException {
        Optional<FeatureRecord> existing = getFeature(featureId);
        if (existing.isEmpty()) {
            return false;
        }
        FeatureRecord record = existing.get();
        if (record.ownerProtected() || !record.featureVersion().equals(fromVersion)) {
            return false;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE features SET feature_version = ?, state = 'PLANNED', content_hash = NULL, "
                        + "updated_at = ? WHERE feature_id = ?")) {
            update.setString(1, toVersion);
            update.setLong(2, nowMillis);
            update.setString(3, featureId);
            update.executeUpdate();
        }
        return true;
    }

    public void markOwnerProtected(String featureId, boolean protectedFlag) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE features SET owner_protected = ? WHERE feature_id = ?")) {
            update.setInt(1, protectedFlag ? 1 : 0);
            update.setString(2, featureId);
            update.executeUpdate();
        }
    }

    /** Idempotent upsert so a restart re-registers the same deterministic roster, not duplicates. */
    public void upsertNpc(String npcId, int globalFloor, String homeParcel, String occupation,
                           int x, int y, int z, String scheduleId, String version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO npc_records(npc_id, global_floor, home_parcel, occupation, x, y, z, "
                        + "schedule_id, version) VALUES (?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(npc_id) DO UPDATE SET global_floor=excluded.global_floor, "
                        + "home_parcel=excluded.home_parcel, occupation=excluded.occupation, "
                        + "x=excluded.x, y=excluded.y, z=excluded.z, "
                        + "schedule_id=excluded.schedule_id, version=excluded.version")) {
            statement.setString(1, npcId);
            statement.setInt(2, globalFloor);
            statement.setString(3, homeParcel);
            statement.setString(4, occupation);
            statement.setInt(5, x);
            statement.setInt(6, y);
            statement.setInt(7, z);
            statement.setString(8, scheduleId);
            statement.setString(9, version);
            statement.executeUpdate();
        }
    }

    public int countNpcs() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) AS c FROM npc_records")) {
            return rs.next() ? rs.getInt("c") : 0;
        }
    }

    public java.sql.Connection connection() {
        return connection;
    }

    public Map<FeatureState, Integer> countByState() throws SQLException {
        Map<FeatureState, Integer> counts = new EnumMap<>(FeatureState.class);
        for (FeatureState state : FeatureState.values()) {
            counts.put(state, 0);
        }
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT state, COUNT(*) AS c FROM features GROUP BY state")) {
            while (rs.next()) {
                counts.put(FeatureState.valueOf(rs.getString("state")), rs.getInt("c"));
            }
        }
        return counts;
    }

    public Set<long[]> ownedChunkCoords() throws SQLException {
        Set<long[]> result = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DISTINCT chunk_x, chunk_z FROM feature_chunks")) {
            while (rs.next()) {
                result.add(new long[]{rs.getInt("chunk_x"), rs.getInt("chunk_z")});
            }
        }
        return result;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
