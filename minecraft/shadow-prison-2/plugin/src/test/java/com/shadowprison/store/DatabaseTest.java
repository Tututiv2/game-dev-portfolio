package com.shadowprison.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static long count(Database db, String table) throws Exception {
        return db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    @Test
    void migratesFreshStoreAndReopensIdempotently(@TempDir Path dir) throws Exception {
        File f = dir.resolve("t.db").toFile();
        Database db = new Database(LOG, f);
        db.start();
        assertEquals(Schema.latestVersion(), db.read(Schema::userVersion));
        db.stop();
        Database again = new Database(LOG, f);
        again.start();   // no migration re-run, no exception
        assertEquals(Schema.latestVersion(), again.read(Schema::userVersion));
        again.stop();
    }

    @Test
    void trackedWriteIsVisibleAfterItsFutureCompletes(@TempDir Path dir) throws Exception {
        Database db = new Database(LOG, dir.resolve("t.db").toFile());
        db.start();
        db.submitTracked("w", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO claims(uuid, item, source, created_at) VALUES ('u', x'00', 's', 0)")) {
                ps.executeUpdate();
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(1, count(db, "claims"));
        db.stop();
    }

    /** One poison write must not roll back the good writes batched beside it. */
    @Test
    void poisonTaskIsIsolatedFromItsBatch(@TempDir Path dir) throws Exception {
        Database db = new Database(LOG, dir.resolve("t.db").toFile());
        db.start();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            int n = i;
            futures.add(db.submitTracked("w" + i, c -> {
                if (n == 25) {
                    throw new IllegalStateException("poison");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO claims(uuid, item, source, created_at) VALUES ('u', x'00', 's', ?)")) {
                    ps.setLong(1, n);
                    ps.executeUpdate();
                }
            }));
        }
        db.drain(5_000);
        assertEquals(49, count(db, "claims"));
        assertTrue(futures.get(25).isCompletedExceptionally());
        assertTrue(futures.get(24).isDone() && !futures.get(24).isCompletedExceptionally());
        db.stop();
    }

    /** The money column cannot hold a negative balance even if code tried. */
    @Test
    void walletRejectsNegativeAmounts(@TempDir Path dir) throws Exception {
        Database db = new Database(LOG, dir.resolve("t.db").toFile());
        db.start();
        var f = db.submitTracked("neg", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO wallets(uuid, currency, amount) VALUES ('u', 'COINS', -1)")) {
                ps.executeUpdate();
            }
        });
        assertThrows(Exception.class, () -> f.get(5, TimeUnit.SECONDS));
        assertEquals(0, count(db, "wallets"));
        db.stop();
    }

    @Test
    void writesAfterShutdownStillLand(@TempDir Path dir) throws Exception {
        Database db = new Database(LOG, dir.resolve("t.db").toFile());
        db.start();
        db.submit("before", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO claims(uuid, item, source, created_at) VALUES ('u', x'00', 's', 1)")) {
                ps.executeUpdate();
            }
        });
        db.stop();   // drains the queue before closing
        Database reopened = new Database(LOG, dir.resolve("t.db").toFile());
        reopened.start();
        assertEquals(1, count(reopened, "claims"));
        reopened.stop();
    }
}
