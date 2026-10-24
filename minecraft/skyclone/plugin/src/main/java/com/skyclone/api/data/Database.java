package com.skyclone.api.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

/**
 * The game's SQLite database (WAL mode, one writer thread). For modules whose state is NOT per-
 * player — the bazaar order book, auctions, mayor votes, island slot allocations. Per-player state
 * belongs in ProfileService instead.
 *
 * <p>Every call runs on the database thread and completes the future there; hop back to the main
 * thread ({@code Bukkit.getScheduler().runTask}) before touching Bukkit. Never {@code join()} on the
 * main thread except during enable/disable.
 *
 * <p>Table names MUST be prefixed with your module id: {@code bazaar_orders}, {@code ah_auctions}.
 */
public interface Database {

    @FunctionalInterface
    interface SqlWork<T> {
        T apply(Connection c) throws SQLException;
    }

    <T> CompletableFuture<T> query(SqlWork<T> work);

    /** Runs inside BEGIN IMMEDIATE ... COMMIT; rolls back if work throws. Use for anything money-shaped. */
    <T> CompletableFuture<T> transaction(SqlWork<T> work);

    /**
     * Idempotent schema setup, blocking, call from enable(). Statements for version N run once, in
     * order, the first time the stored version for {@code module} is below N.
     */
    void migrate(String module, int version, String... statements);
}
