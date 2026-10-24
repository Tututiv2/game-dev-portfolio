package com.shadowprison.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE Phase 0 gate: an acknowledged write survives the process being killed outright.
 *
 * <p>A child JVM writes claim rows as fast as it can and prints the id of each one ONLY after its
 * tracked future completes (i.e. after commit). The parent kills it mid-stream with
 * {@code destroyForcibly} (TerminateProcess on Windows — no shutdown hooks, no onDisable, the
 * equivalent of kill -9), reopens the store, and asserts every acknowledged row is present.
 * Repeated, because one lucky kill proves nothing.
 */
class CrashTest {

    private static final int ROUNDS = 5;

    /** Child entry point: writes forever, acknowledging each committed row on stdout. */
    public static void main(String[] args) throws Exception {
        Database db = new Database(Logger.getLogger("child"), new File(args[0]));
        db.start();
        long i = 0;
        while (true) {
            long n = i++;
            db.submitTracked("w", c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO claims(uuid, item, source, created_at) VALUES ('crash', x'01', 'test', ?)")) {
                    ps.setLong(1, n);
                    ps.executeUpdate();
                }
            }).thenRun(() -> {
                synchronized (System.out) {
                    System.out.println("ACK " + n);
                    System.out.flush();
                }
            });
            if (n % 64 == 0) {
                Thread.sleep(1);
            }
        }
    }

    @Test
    void acknowledgedWritesSurviveAHardKill(@TempDir Path dir) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        for (int round = 0; round < ROUNDS; round++) {
            File dbFile = dir.resolve("crash" + round + ".db").toFile();
            Process child = new ProcessBuilder(javaBin, "-cp", cp, CrashTest.class.getName(), dbFile.getAbsolutePath())
                    .redirectErrorStream(false).start();
            long maxAck = -1;
            long deadline = System.currentTimeMillis() + 1500 + round * 300L;
            try (BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (System.currentTimeMillis() < deadline && (line = out.readLine()) != null) {
                    if (line.startsWith("ACK ")) {
                        maxAck = Math.max(maxAck, Long.parseLong(line.substring(4)));
                    }
                }
                child.destroyForcibly();   // no hooks, no flush — the hard kill
                child.waitFor(10, TimeUnit.SECONDS);
            }
            assertTrue(maxAck > 100, "round " + round + ": child acknowledged too little to prove anything (" + maxAck + ")");

            Database db = new Database(Logger.getLogger("parent"), dbFile);
            db.start();
            long acked = maxAck;
            long missing = db.read(c -> {
                // Every id up to the highest acknowledged one must be present (acks are in order).
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(DISTINCT created_at) FROM claims WHERE source = 'test' AND created_at <= ?")) {
                    ps.setLong(1, acked);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? (acked + 1) - rs.getLong(1) : -1L;
                    }
                }
            });
            db.stop();
            assertTrue(missing == 0, "round " + round + ": " + missing + " acknowledged write(s) LOST after hard kill (acked up to " + acked + ")");
            System.out.println("crash round " + round + ": " + (acked + 1) + " acknowledged writes, 0 lost");
        }
    }
}
