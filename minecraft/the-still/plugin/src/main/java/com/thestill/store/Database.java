package com.thestill.store;

import com.thestill.config.Dials;
import com.thestill.core.Service;
import org.bukkit.Bukkit;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one door to disk: a single-writer, many-reader SQLite store.
 *
 * <h2>Why this survives kill -9</h2>
 * The database runs in WAL mode with {@code synchronous=NORMAL}. SQLite
 * guarantees that transactions committed in WAL mode are durable across an
 * <em>application</em> crash regardless of the synchronous setting, because the
 * committed bytes are already handed to the operating system. Only a power cut
 * or kernel panic can roll one back, and that would need {@code synchronous=FULL}
 * and an fsync per commit — which costs an order of magnitude in throughput to
 * defend against a failure mode the Phase 0 gate does not test. So: anything
 * this class has acknowledged as committed will still be there after
 * {@code kill -9}, by construction rather than by luck.
 *
 * <h2>Why the writer is a plain thread</h2>
 * Not a Bukkit async task. During {@code onDisable} the scheduler is already
 * shut down, so {@code runTaskAsynchronously} throws — and on Shadow Prison that
 * exception escaped and aborted the whole shutdown chain, skipping every save
 * below it, on every clean shutdown with a player online. A plain thread has no
 * such dependency and can drain the queue at any point in the lifecycle.
 *
 * <p>SQLite permits exactly one writer, so serialising writes through one thread
 * is not a limitation being worked around — it is the shape of the database.
 * Readers run concurrently on their own pooled connections, which WAL supports.
 */
public final class Database implements Service {

    private static final int BATCH_MAX = 256;
    private static final long POLL_MILLIS = 250L;
    private static final int READ_POOL_SIZE = 3;

    private final Logger log;
    private final Dials dials;
    private final File file;

    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Connection> readPool = new ArrayBlockingQueue<>(READ_POOL_SIZE);
    private final List<Connection> allReadConnections = new ArrayList<>(READ_POOL_SIZE);

    private final AtomicLong committedBatches = new AtomicLong();
    private final AtomicLong committedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();

    private volatile Connection writeConnection;
    private volatile Thread writerThread;
    private volatile boolean running;
    private volatile long lastQueueWarn;

    public Database(Logger log, Dials dials, File file) {
        this.log = log;
        this.dials = dials;
        this.file = file;
    }

    // ---- lifecycle ------------------------------------------------------

    @Override
    public void start() throws Exception {
        try {
            // Explicit load rather than relying on the JDBC ServiceLoader:
            // DriverManager resolves drivers against the *caller's* classloader,
            // and under Paper's per-plugin classloader that lookup can come up
            // empty even though the jar is right there.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "SQLite driver missing. Paper loads it from the `libraries:` block in "
                            + "plugin.yml on first start, which needs network access once.", e);
        }

        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        writeConnection = openConnection();
        Schema.migrate(writeConnection, log);

        for (int i = 0; i < READ_POOL_SIZE; i++) {
            Connection connection = openConnection();
            allReadConnections.add(connection);
            readPool.add(connection);
        }

        running = true;
        writerThread = new Thread(this::writeLoop, "TheStill-DB-Writer");
        writerThread.setDaemon(false); // must finish its queue before the JVM exits
        writerThread.start();

        log.info("store open at " + file.getName() + " (WAL, 1 writer + "
                + READ_POOL_SIZE + " readers)");
    }

    @Override
    public void stop() throws Exception {
        running = false;
        Thread writer = writerThread;
        if (writer != null) {
            writer.interrupt();
            // Generous: the queue must be empty before we close the connection,
            // or we lose exactly the writes a shutdown is supposed to save.
            writer.join(TimeUnit.SECONDS.toMillis(30));
            if (writer.isAlive()) {
                log.severe("write queue did not drain in 30s; " + queue.size()
                        + " task(s) may be lost");
            }
        }
        int remaining = queue.size();
        if (remaining > 0) {
            log.severe("shutdown left " + remaining + " unwritten task(s)");
        }

        closeQuietly(writeConnection, "writer");
        for (Connection connection : allReadConnections) {
            closeQuietly(connection, "reader");
        }
        allReadConnections.clear();
        readPool.clear();

        log.info("store closed — " + committedTasks.get() + " task(s) committed in "
                + committedBatches.get() + " transaction(s), " + failedTasks.get() + " failed");
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + dials.storeBusyTimeoutMs.asInt());
        }
        connection.setAutoCommit(true);
        return connection;
    }

    private void closeQuietly(Connection connection, String what) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.log(Level.WARNING, "failed to close " + what + " connection", e);
        }
    }

    // ---- submitting work ------------------------------------------------

    /** Enqueues a write. Returns immediately; safe from any thread including the main one. */
    public void submit(String label, SqlWork work) {
        enqueue(new Task(label, work, null));
    }

    /**
     * Enqueues a write and returns a future completed <em>after</em> the
     * transaction commits.
     *
     * <p>Continuations attached to the returned future run inline on the writer
     * thread, in queue order — which is what makes "log it only once it is
     * genuinely durable" expressible.
     */
    public CompletableFuture<Void> submitTracked(String label, SqlWork work) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        enqueue(new Task(label, work, future));
        return future;
    }

    private void enqueue(Task task) {
        if (!running) {
            // Past shutdown the writer is gone. Running it inline on the caller
            // is the only way it lands at all, and silently dropping it is the
            // one thing that must never happen.
            log.warning("write '" + task.label + "' submitted after shutdown; running inline");
            runInline(task);
            return;
        }
        queue.add(task);
        warnIfBackedUp();
    }

    private void runInline(Task task) {
        Connection connection = writeConnection;
        if (connection == null) {
            log.severe("cannot run '" + task.label + "': store is closed. WRITE LOST.");
            task.fail(new SQLException("store closed"));
            return;
        }
        try {
            task.work.run(connection);
            task.succeed();
        } catch (Exception e) {
            log.log(Level.SEVERE, "inline write '" + task.label + "' failed", e);
            task.fail(e);
        }
    }

    private void warnIfBackedUp() {
        int depth = queue.size();
        if (depth < dials.storeQueueWarnDepth.asInt()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastQueueWarn < 10_000L) {
            return;
        }
        lastQueueWarn = now;
        log.warning("write queue depth " + depth + " — disk is not keeping up with the game");
    }

    // ---- reading --------------------------------------------------------

    /**
     * Runs a read on a pooled connection.
     *
     * <p>Must not be called from the server thread. Profiles are loaded during
     * {@code AsyncPlayerPreLoginEvent}, which is already off-thread and is the
     * correct place to block: the player is not in the world yet, so waiting
     * there costs nobody a tick.
     */
    public <T> T read(SqlRead<T> work) throws SQLException {
        if (onMainThread()) {
            throw new IllegalStateException(
                    "database read attempted on the server thread — this is a stall waiting to "
                            + "happen; load during AsyncPlayerPreLoginEvent or hand off to the "
                            + "writer thread instead");
        }
        Connection connection;
        try {
            connection = readPool.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted waiting for a read connection", e);
        }
        if (connection == null) {
            throw new SQLException("no read connection free after 10s");
        }
        try {
            return work.read(connection);
        } finally {
            readPool.offer(connection);
        }
    }

    /**
     * A read that is allowed to block the calling thread, for enable and disable
     * only.
     *
     * <p>At enable nothing is ticking yet and the world is not accepting
     * players, so loading regions synchronously is not a stall — it is the
     * correct order of operations. Everything after startup must use
     * {@link #read} and take the main-thread check.
     */
    public <T> T readDuringLifecycle(SqlRead<T> work) throws SQLException {
        Connection connection = writeConnection;
        if (connection == null) {
            throw new SQLException("store is not open");
        }
        return work.read(connection);
    }

    private static boolean onMainThread() {
        try {
            return Bukkit.getServer() != null && Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return false; // no server at all — unit tests
        }
    }

    // ---- draining -------------------------------------------------------

    /**
     * Blocks until every task queued before this call has committed.
     *
     * @return true if the queue drained within the timeout
     */
    public boolean drain(long timeoutMillis) {
        CompletableFuture<Void> marker = submitTracked("drain-marker", connection -> {
        });
        try {
            marker.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warning("drain did not complete in " + timeoutMillis + "ms (queue="
                    + queue.size() + ")");
            return false;
        }
    }

    public int queueDepth() {
        return queue.size();
    }

    public long committedTasks() {
        return committedTasks.get();
    }

    // ---- the writer thread ----------------------------------------------

    private void writeLoop() {
        List<Task> batch = new ArrayList<>(BATCH_MAX);
        while (running || !queue.isEmpty()) {
            try {
                Task first = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_MAX - 1);
                runBatch(batch);
            } catch (InterruptedException e) {
                // Shutdown asked us to stop. Keep looping so the `!queue.isEmpty()`
                // clause gets its chance to drain what is left, then exit.
                Thread.interrupted();
                if (!running && queue.isEmpty()) {
                    break;
                }
            } catch (Throwable t) {
                log.log(Level.SEVERE, "write loop error (continuing)", t);
            } finally {
                batch.clear();
            }
        }
        log.fine("writer thread finished");
    }

    /**
     * Commits a batch as one transaction, falling back to one-at-a-time on
     * failure.
     *
     * <p>Batching is what makes write-behind cheap. But a single poison task
     * must not roll back the ninety-nine good writes queued beside it, so a
     * failed batch is retried item by item: the fast path stays fast, and the
     * error path costs one transaction per task only when something is actually
     * broken.
     */
    private void runBatch(List<Task> batch) {
        Connection connection = writeConnection;
        if (connection == null) {
            batch.forEach(task -> task.fail(new SQLException("store closed")));
            return;
        }
        long startedAt = System.nanoTime();
        try {
            connection.setAutoCommit(false);
            for (Task task : batch) {
                task.work.run(connection);
            }
            connection.commit();
            committedBatches.incrementAndGet();
            committedTasks.addAndGet(batch.size());
            // Only now is it true. Acknowledge after the commit returns, never before.
            batch.forEach(Task::succeed);

            if (dials.storeVerboseTiming.asBoolean()) {
                log.info("flushed " + batch.size() + " task(s) in "
                        + (System.nanoTime() - startedAt) / 1_000_000L + "ms");
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "batch of " + batch.size()
                    + " failed; retrying individually to isolate the bad one", e);
            rollbackQuietly(connection);
            runIndividually(batch, connection);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                log.log(Level.SEVERE, "could not restore autocommit", e);
            }
        }
    }

    private void runIndividually(List<Task> batch, Connection connection) {
        for (Task task : batch) {
            try {
                connection.setAutoCommit(false);
                task.work.run(connection);
                connection.commit();
                committedBatches.incrementAndGet();
                committedTasks.incrementAndGet();
                task.succeed();
            } catch (Exception e) {
                rollbackQuietly(connection);
                failedTasks.incrementAndGet();
                log.log(Level.SEVERE, "write '" + task.label + "' failed and was dropped", e);
                task.fail(e);
            }
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.log(Level.SEVERE, "rollback failed", e);
        }
    }

    // ---- task -----------------------------------------------------------

    private record Task(String label, SqlWork work, CompletableFuture<Void> future) {

        void succeed() {
            if (future != null) {
                future.complete(null);
            }
        }

        void fail(Exception e) {
            if (future != null) {
                future.completeExceptionally(e);
            }
        }
    }

    @FunctionalInterface
    public interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlRead<T> {
        T read(Connection connection) throws SQLException;
    }
}
