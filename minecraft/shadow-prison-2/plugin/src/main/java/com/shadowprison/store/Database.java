package com.shadowprison.store;

import com.shadowprison.core.Service;
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
 * The one door to disk: a single-writer, many-reader SQLite store. Ported from THE STILL, whose
 * Phase 0 gate proved it: 10x {@code kill -9}, zero acknowledged writes lost.
 *
 * <p><b>Why it survives kill -9:</b> WAL mode + {@code synchronous=NORMAL}. A transaction
 * committed in WAL mode is durable across an APPLICATION crash, because its bytes are already
 * handed to the OS. Nothing is acknowledged before its commit returns.
 *
 * <p><b>Why the writer is a plain thread:</b> during onDisable the Bukkit scheduler is gone, so a
 * Bukkit async task cannot drain the queue (Shadow Prison 1 lost items exactly that way). SQLite
 * permits one writer; serialising writes through one thread IS the shape of the database.
 *
 * <p>This replaces SP1's whole YAML layer and with it rules 9 and 32 (live-object saves, a writer
 * that could jam for the session, half-loaded files saved over good ones).
 */
public final class Database implements Service {

    private static final int BATCH_MAX = 256;
    private static final long POLL_MILLIS = 250L;
    private static final int READ_POOL_SIZE = 3;
    private static final int BUSY_TIMEOUT_MS = 5000;
    private static final int QUEUE_WARN_DEPTH = 2000;

    private final Logger log;
    private final File file;

    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Connection> readPool = new ArrayBlockingQueue<>(READ_POOL_SIZE);
    private final List<Connection> allReadConnections = new ArrayList<>(READ_POOL_SIZE);
    private final AtomicLong committedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();

    private volatile Connection writeConnection;
    private volatile Thread writerThread;
    private volatile boolean running;
    private volatile long lastQueueWarn;

    public Database(Logger log, File file) {
        this.log = log;
        this.file = file;
    }

    @FunctionalInterface
    public interface SqlWork {
        void run(Connection connection) throws Exception;
    }

    @FunctionalInterface
    public interface SqlRead<T> {
        T read(Connection connection) throws SQLException;
    }

    // ---- lifecycle ------------------------------------------------------------------------

    @Override
    public void start() throws Exception {
        try {
            // DriverManager resolves against the CALLER's classloader; under Paper's per-plugin
            // loader the ServiceLoader lookup can come up empty. Load it explicitly.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite driver missing. Paper loads it from plugin.yml "
                    + "`libraries:` on first start, which needs network access once.", e);
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
        writerThread = new Thread(this::writeLoop, "ShadowPrison-DB-Writer");
        writerThread.setDaemon(false);   // must finish its queue before the JVM exits
        writerThread.start();
        log.info("store open at " + file.getName() + " (WAL, 1 writer + " + READ_POOL_SIZE + " readers)");
    }

    @Override
    public void stop() throws Exception {
        running = false;
        Thread writer = writerThread;
        if (writer != null) {
            writer.interrupt();
            writer.join(TimeUnit.SECONDS.toMillis(30));
            if (writer.isAlive()) {
                log.severe("write queue did not drain in 30s; " + queue.size() + " task(s) may be lost");
            }
        }
        if (!queue.isEmpty()) {
            log.severe("shutdown left " + queue.size() + " unwritten task(s)");
        }
        closeQuietly(writeConnection, "writer");
        for (Connection connection : allReadConnections) {
            closeQuietly(connection, "reader");
        }
        allReadConnections.clear();
        readPool.clear();
        log.info("store closed — " + committedTasks.get() + " task(s) committed, " + failedTasks.get() + " failed");
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
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

    // ---- writing --------------------------------------------------------------------------

    /** Enqueues a write. Returns immediately; safe from any thread. */
    public void submit(String label, SqlWork work) {
        enqueue(new Task(label, work, null));
    }

    /** Enqueues a write; the future completes only AFTER the transaction commits. */
    public CompletableFuture<Void> submitTracked(String label, SqlWork work) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        enqueue(new Task(label, work, future));
        return future;
    }

    private void enqueue(Task task) {
        if (!running) {
            // Past shutdown the writer is gone: running inline is the only way it lands at all,
            // and silently dropping it is the one thing that must never happen.
            log.warning("write '" + task.label + "' submitted after shutdown; running inline");
            runInline(task);
            return;
        }
        queue.add(task);
        int depth = queue.size();
        long now = System.currentTimeMillis();
        if (depth >= QUEUE_WARN_DEPTH && now - lastQueueWarn > 10_000L) {
            lastQueueWarn = now;
            log.warning("write queue depth " + depth + " — disk is not keeping up with the game");
        }
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

    /** Blocks until every task queued before this call has committed. */
    public boolean drain(long timeoutMillis) {
        CompletableFuture<Void> marker = submitTracked("drain-marker", connection -> {
        });
        try {
            marker.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            log.warning("drain did not complete in " + timeoutMillis + "ms (queue=" + queue.size() + ")");
            return false;
        }
    }

    public int queueDepth() {
        return queue.size();
    }

    public long committedTasks() {
        return committedTasks.get();
    }

    // ---- reading --------------------------------------------------------------------------

    /**
     * Runs a read on a pooled connection. Refused on the server thread: profiles load during
     * AsyncPlayerPreLoginEvent, which is already off-thread and the correct place to block.
     */
    public <T> T read(SqlRead<T> work) throws SQLException {
        if (onMainThread()) {
            throw new IllegalStateException("database read attempted on the server thread — load "
                    + "off-thread (AsyncPlayerPreLoginEvent) or use readDuringLifecycle at enable");
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

    /** A blocking read for enable/disable only, when nothing is ticking yet. */
    public <T> T readDuringLifecycle(SqlRead<T> work) throws SQLException {
        Connection connection = writeConnection;
        if (connection == null) {
            throw new SQLException("store is not open");
        }
        synchronized (this) {
            return work.read(connection);
        }
    }

    private static boolean onMainThread() {
        try {
            return Bukkit.getServer() != null && Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return false;   // no server at all — unit tests
        }
    }

    // ---- the writer thread ----------------------------------------------------------------

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
                Thread.interrupted();   // shutdown: loop again so the !isEmpty() clause drains the rest
                if (!running && queue.isEmpty()) {
                    break;
                }
            } catch (Throwable t) {
                log.log(Level.SEVERE, "write loop error (continuing)", t);
            } finally {
                batch.clear();
            }
        }
    }

    /** One transaction per batch; a poison task is isolated by retrying the batch one by one. */
    private void runBatch(List<Task> batch) {
        Connection connection = writeConnection;
        if (connection == null) {
            batch.forEach(task -> task.fail(new SQLException("store closed")));
            return;
        }
        synchronized (this) {
            try {
                connection.setAutoCommit(false);
                for (Task task : batch) {
                    task.work.run(connection);
                }
                connection.commit();
                committedTasks.addAndGet(batch.size());
                batch.forEach(Task::succeed);   // acknowledge AFTER the commit returns, never before
            } catch (Exception e) {
                log.log(Level.WARNING, "batch of " + batch.size() + " failed; retrying individually", e);
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
    }

    private void runIndividually(List<Task> batch, Connection connection) {
        for (Task task : batch) {
            try {
                connection.setAutoCommit(false);
                task.work.run(connection);
                connection.commit();
                committedTasks.incrementAndGet();
                task.succeed();
            } catch (Exception e) {
                rollbackQuietly(connection);
                failedTasks.incrementAndGet();
                log.log(Level.SEVERE, "write '" + task.label + "' failed and was rolled back", e);
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
}
