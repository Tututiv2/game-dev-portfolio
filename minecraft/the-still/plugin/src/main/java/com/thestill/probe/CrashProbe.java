package com.thestill.probe;

import com.thestill.config.Dials;
import com.thestill.core.Service;
import com.thestill.store.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Proves the Phase 0 gate instead of asserting it.
 *
 * <h2>The invariant</h2>
 * For every write this plugin has <em>acknowledged as committed</em>, the value
 * is still there after {@code kill -9}.
 *
 * <h2>How it is proved</h2>
 * The probe writes a strictly increasing counter, each in its own durable
 * transaction, and logs {@code [PROBE] COMMIT n} only after the commit returns.
 * On the next start it reads the stored counter back and logs
 * {@code [PROBE] RECOVERED m}.
 *
 * <p>The harness kills the server at a random moment, restarts it, and asserts
 * {@code m >= max(n)}. That holds because:
 *
 * <ul>
 *   <li>a COMMIT line exists only if {@code commit()} returned, and
 *   <li>SQLite in WAL mode guarantees a committed transaction survives an
 *       application crash.
 * </ul>
 *
 * <p>The log itself is buffered, so it can lag reality — but that bias only ever
 * produces a <em>false failure</em>, never a false pass. A test that can only
 * err toward the alarm is a test you can believe.
 *
 * <p>Development tool. {@code probe.enabled} defaults to false.
 */
public final class CrashProbe implements Service {

    /** One row, reused across restarts, so the counter is continuous over a whole test run. */
    private static final String RUN_ID = "gate";

    private final Logger log;
    private final Plugin plugin;
    private final Database database;
    private final Dials dials;

    private final AtomicLong sequence = new AtomicLong();
    private BukkitTask task;

    public CrashProbe(Logger log, Plugin plugin, Database database, Dials dials) {
        this.log = log;
        this.plugin = plugin;
        this.database = database;
        this.dials = dials;
    }

    @Override
    public void start() throws Exception {
        long recovered = database.readDuringLifecycle(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT seq FROM crash_probe WHERE run_id = ?")) {
                statement.setString(1, RUN_ID);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
        sequence.set(recovered);
        log.info("[PROBE] RECOVERED " + recovered);

        if (dials.probeEnabled.asBoolean()) {
            startWriting();
        }
        // Let the dial turn it on and off live, so a run can be started from the
        // console without a restart.
        dials.probeEnabled.onChange(enabled -> {
            if (enabled) {
                startWriting();
            } else {
                stopWriting();
            }
        });
    }

    @Override
    public void stop() {
        stopWriting();
    }

    public synchronized void startWriting() {
        if (task != null) {
            return;
        }
        int perSecond = Math.max(1, dials.probeWritesPerSecond.asInt());
        int perTick = Math.max(1, perSecond / 20);
        long periodTicks = perSecond >= 20 ? 1L : Math.max(1L, 20L / perSecond);

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < perTick; i++) {
                writeOnce();
            }
        }, 1L, periodTicks);
        log.info("[PROBE] writing at ~" + perSecond + "/s from seq " + sequence.get());
    }

    public synchronized void stopWriting() {
        if (task != null) {
            task.cancel();
            task = null;
            log.info("[PROBE] stopped at seq " + sequence.get());
        }
    }

    public boolean isWriting() {
        return task != null;
    }

    public long sequence() {
        return sequence.get();
    }

    private void writeOnce() {
        long next = sequence.incrementAndGet();
        long now = System.currentTimeMillis();
        database.submitTracked("probe:" + next, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO crash_probe (run_id, seq, updated_at) VALUES (?, ?, ?)
                    ON CONFLICT(run_id) DO UPDATE SET
                        seq = excluded.seq, updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, RUN_ID);
                statement.setLong(2, next);
                statement.setLong(3, now);
                statement.executeUpdate();
            }
        }).thenRun(() ->
                // Runs inline on the writer thread, after commit() returned.
                // This ordering is the entire proof; do not move it.
                log.info("[PROBE] COMMIT " + next));
    }

    /** Wipes the probe row so a fresh gate run starts from zero. */
    public void reset() {
        stopWriting();
        sequence.set(0);
        database.submit("probe-reset", connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement("DELETE FROM crash_probe WHERE run_id = ?")) {
                statement.setString(1, RUN_ID);
                statement.executeUpdate();
            }
        });
        log.info("[PROBE] reset");
    }
}
