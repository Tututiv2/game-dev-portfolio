package com.skyclone.core.foundation;

import com.skyclone.api.data.Database;
import org.sqlite.SQLiteConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite on ONE dedicated thread. Every query and transaction runs there, in submission order,
 * so transactions can never interleave and a read queued after a write always sees it — the
 * profile loader relies on that FIFO property (a quick relog's read lands after its own quit save).
 *
 * <p>Re-entrancy: work that is already running on the DB thread and calls {@link #query} or
 * {@link #transaction} again is run INLINE (a nested transaction becomes a SAVEPOINT) instead of
 * being queued behind itself, which would deadlock the moment anyone joined on it.
 *
 * <p>Pure JDBC, no Bukkit, so the unit tests can drive it against a temp file.
 */
public final class SqliteDatabase implements Database {

    private final Logger log;
    private final File file;
    private final ThreadPoolExecutor exec;
    private volatile Thread dbThread;
    private Connection conn;                 // only touched on the DB thread
    private int txDepth;                     // only touched on the DB thread
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean closed;

    public SqliteDatabase(File file, Logger log) {
        this.file = file;
        this.log = log;
        this.exec = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "SkyClone-DB");
            t.setDaemon(false);
            dbThread = t;
            return t;
        });
    }

    /** Opens the connection (blocking). Throws if the database cannot be opened. */
    public void open() {
        try {
            exec.submit(() -> {
                File parent = file.getAbsoluteFile().getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new SQLException("cannot create " + parent);
                }
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException e) {
                    throw new SQLException("sqlite-jdbc is not on the classpath (plugin.yml libraries:)", e);
                }
                SQLiteConfig cfg = new SQLiteConfig();
                cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
                cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
                cfg.setBusyTimeout(10_000);
                cfg.enforceForeignKeys(false);
                conn = cfg.createConnection("jdbc:sqlite:" + file.getAbsolutePath());
                conn.setAutoCommit(true);
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                    st.execute("PRAGMA busy_timeout=10000");
                    st.execute("PRAGMA temp_store=MEMORY");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS schema_versions (module TEXT PRIMARY KEY, version INTEGER NOT NULL)");
                }
                return null;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not open database " + file + ": " + e.getMessage(), e);
        }
    }

    public boolean onDbThread() { return Thread.currentThread() == dbThread; }

    public int queueSize() { return exec.getQueue().size(); }

    public long completedCount() { return completed.get(); }

    public long failedCount() { return failed.get(); }

    public File file() { return file; }

    // ---- Database ------------------------------------------------------------------------------

    @Override
    public <T> CompletableFuture<T> query(SqlWork<T> work) {
        if (onDbThread()) return runInline(work, false);
        return submit(work, false);
    }

    @Override
    public <T> CompletableFuture<T> transaction(SqlWork<T> work) {
        if (onDbThread()) return runInline(work, true);
        return submit(work, true);
    }

    private <T> CompletableFuture<T> submit(SqlWork<T> work, boolean tx) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (closed) {
            f.completeExceptionally(new IllegalStateException("database is closed"));
            return f;
        }
        Throwable origin = new Throwable("submitted from here");
        try {
            exec.execute(() -> {
                try {
                    T r = tx ? inTransaction(work) : work.apply(conn);
                    completed.incrementAndGet();
                    f.complete(r);
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    t.addSuppressed(origin);
                    log.log(Level.WARNING, "[db] " + (tx ? "transaction" : "query") + " failed: " + t, t);
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            f.completeExceptionally(new IllegalStateException("database is shutting down", e));
        }
        return f;
    }

    private <T> CompletableFuture<T> runInline(SqlWork<T> work, boolean tx) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            f.complete(tx ? inTransaction(work) : work.apply(conn));
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
        return f;
    }

    /** DB thread only. Outermost = BEGIN IMMEDIATE; nested = SAVEPOINT. */
    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        if (conn == null) throw new SQLException("database not open");
        if (txDepth > 0) {
            String sp = "sp" + txDepth;
            exec(conn, "SAVEPOINT " + sp);
            txDepth++;
            try {
                T r = work.apply(conn);
                exec(conn, "RELEASE " + sp);
                return r;
            } catch (Throwable t) {
                try { exec(conn, "ROLLBACK TO " + sp); exec(conn, "RELEASE " + sp); } catch (SQLException ignored) { }
                throw t;
            } finally {
                txDepth--;
            }
        }
        exec(conn, "BEGIN IMMEDIATE");
        txDepth = 1;
        try {
            T r = work.apply(conn);
            exec(conn, "COMMIT");
            return r;
        } catch (Throwable t) {
            try { exec(conn, "ROLLBACK"); } catch (SQLException re) { t.addSuppressed(re); }
            throw t;
        } finally {
            txDepth = 0;
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) { st.execute(sql); }
    }

    @Override
    public void migrate(String module, int version, String... statements) {
        if (module == null || module.isBlank()) throw new IllegalArgumentException("migrate: module id required");
        CompletableFuture<Boolean> f = transaction(c -> {
            int current = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT version FROM schema_versions WHERE module = ?")) {
                ps.setString(1, module);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) current = rs.getInt(1); }
            }
            if (current >= version) return false;
            for (String sql : statements) {
                if (sql == null || sql.isBlank()) continue;
                try (Statement st = c.createStatement()) {
                    st.executeUpdate(sql);
                } catch (SQLException e) {
                    throw new SQLException("migration " + module + " v" + version + " failed on: " + sql + " -> " + e.getMessage(), e);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO schema_versions(module, version) VALUES(?, ?) ON CONFLICT(module) DO UPDATE SET version = excluded.version")) {
                ps.setString(1, module);
                ps.setInt(2, version);
                ps.executeUpdate();
            }
            return true;
        });
        try {
            Boolean ran = f.get(60, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(ran)) log.info("[db] migrated " + module + " to v" + version);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(c.getMessage(), c);
        } catch (Exception e) {
            throw new IllegalStateException("migration " + module + " v" + version + " did not complete: " + e, e);
        }
    }

    /** Drain every queued task, then close the connection. Blocks up to {@code timeoutSec}. */
    public void close(int timeoutSec) {
        if (closed) return;
        closed = true;
        try {
            exec.execute(() -> {
                try {
                    if (conn != null) {
                        try (Statement st = conn.createStatement()) { st.execute("PRAGMA wal_checkpoint(TRUNCATE)"); } catch (SQLException ignored) { }
                        conn.close();
                    }
                } catch (SQLException e) {
                    log.log(Level.WARNING, "[db] close failed", e);
                }
            });
        } catch (RejectedExecutionException ignored) { }
        exec.shutdown();
        try {
            if (!exec.awaitTermination(timeoutSec, TimeUnit.SECONDS)) {
                log.severe("[db] " + exec.getQueue().size() + " database tasks still queued after " + timeoutSec
                        + "s at shutdown - they will be LOST");
                exec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True for an executor that is still accepting work. */
    public boolean isOpen() { return !closed; }

    ExecutorService executor() { return exec; }
}
