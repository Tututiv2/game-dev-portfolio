package com.skyclone.mod.bank;

import com.skyclone.api.Sky;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every SQL statement of the bank module. All work runs on core's DB thread (FIFO, so a read queued
 * after a write sees it); futures complete THERE and callers hop back to the main thread.
 *
 * <p>Tables (prefixed {@code bank_}):
 * <ul>
 *   <li>{@code bank_tx} - account history (co-op accounts {@code coop:<profile>}, personal
 *       {@code personal:<profile>:<uuid>}); the menus show the last 10.</li>
 *   <li>{@code bank_personal} - personal account balances (tenths) and their last interest period.</li>
 *   <li>{@code bank_pending} - items/coins that belong to someone but are not in an inventory: trade
 *       escrow ({@code state=escrow}) and deliveries waiting for their owner ({@code state=deliver}).
 *       Rows leave this table BEFORE their contents reach an inventory, so a crash can lose a delivery
 *       but never duplicate one.</li>
 *   <li>{@code bank_trades} - the trade log (both sides, coins, item ids/uuids) for staff restores.</li>
 * </ul>
 */
public final class BankStore {

    public record TxRow(long ts, String actor, String actorName, String kind, long delta, long balance) {}

    public record Pending(String key, UUID owner, String tradeId, String kind, byte[] item, long coins, String from) {}

    public record TradeLog(String id, long ts, String aName, String bName, long aCoins, long bCoins, String aItems, String bItems) {}

    /** Last few transactions per account, kept in memory so admin output over RCON is synchronous. */
    private final Map<String, Deque<TxRow>> recent = new ConcurrentHashMap<>();
    private final Deque<TradeLog> recentTrades = new ArrayDeque<>();

    public void migrate() {
        Sky.db().migrate("bank", 1,
                "CREATE TABLE IF NOT EXISTS bank_tx (id INTEGER PRIMARY KEY AUTOINCREMENT, account TEXT NOT NULL, ts INTEGER NOT NULL, "
                        + "actor TEXT, actor_name TEXT, kind TEXT NOT NULL, delta INTEGER NOT NULL, balance INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS bank_tx_account ON bank_tx (account, id)",
                "CREATE TABLE IF NOT EXISTS bank_personal (profile_id TEXT NOT NULL, uuid TEXT NOT NULL, tenths INTEGER NOT NULL DEFAULT 0, "
                        + "last_period INTEGER NOT NULL DEFAULT -1, PRIMARY KEY (profile_id, uuid))",
                "CREATE TABLE IF NOT EXISTS bank_pending (pkey TEXT PRIMARY KEY, owner TEXT NOT NULL, trade_id TEXT, kind TEXT NOT NULL, "
                        + "item BLOB, coins INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, from_uuid TEXT, created INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS bank_pending_owner ON bank_pending (owner, state)",
                "CREATE TABLE IF NOT EXISTS bank_trades (id TEXT PRIMARY KEY, ts INTEGER NOT NULL, a TEXT NOT NULL, a_name TEXT, "
                        + "b TEXT NOT NULL, b_name TEXT, a_coins INTEGER NOT NULL, b_coins INTEGER NOT NULL, a_items TEXT, b_items TEXT)",
                "CREATE INDEX IF NOT EXISTS bank_trades_a ON bank_trades (a, ts)",
                "CREATE INDEX IF NOT EXISTS bank_trades_b ON bank_trades (b, ts)");
        // Wave 3: account-wide Boon Buff / bits / fame / account upgrades (research: these follow the ACCOUNT, not the profile)
        Sky.db().migrate("bank", 2,
                "CREATE TABLE IF NOT EXISTS bank_account_wide (uuid TEXT PRIMARY KEY, saved_at INTEGER NOT NULL, json TEXT NOT NULL)");
        // Wave 5: which module was seen reading each Community Shop upgrade (an upgrade somebody reads is buildable)
        Sky.db().migrate("bank", 3,
                "CREATE TABLE IF NOT EXISTS bank_cshop_readers (upgrade TEXT NOT NULL, module TEXT NOT NULL, first_seen INTEGER NOT NULL, "
                        + "PRIMARY KEY (upgrade, module))");
    }

    // ---- Community Shop upgrade readers (wave 5) --------------------------------------------------------

    /** Every recorded (upgrade, module) pair. */
    public CompletableFuture<List<String[]>> loadReaders() {
        return Sky.db().query(c -> {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT upgrade, module FROM bank_cshop_readers");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
            return out;
        });
    }

    public CompletableFuture<Void> addReader(String upgrade, String module) {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO bank_cshop_readers (upgrade, module, first_seen) VALUES (?, ?, ?)")) {
                ps.setString(1, upgrade);
                ps.setString(2, module);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** {@code upgrade} null = every row. */
    public CompletableFuture<Void> clearReaders(String upgrade) {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(upgrade == null
                    ? "DELETE FROM bank_cshop_readers" : "DELETE FROM bank_cshop_readers WHERE upgrade = ?")) {
                if (upgrade != null) ps.setString(1, upgrade);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- account-wide state ---------------------------------------------------------------------------

    public record AccountRow(long savedAt, String json) {}

    public CompletableFuture<AccountRow> loadAccount(UUID player) {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT saved_at, json FROM bank_account_wide WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new AccountRow(rs.getLong(1), rs.getString(2)) : null; }
            }
        });
    }

    /** Upsert; an OLDER snapshot never replaces a newer one (writes from two profiles cannot go backwards). */
    public CompletableFuture<Void> saveAccount(UUID player, long savedAt, String json) {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bank_account_wide (uuid, saved_at, json) VALUES (?,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET saved_at = excluded.saved_at, json = excluded.json "
                            + "WHERE excluded.saved_at >= bank_account_wide.saved_at")) {
                ps.setString(1, player.toString());
                ps.setLong(2, savedAt);
                ps.setString(3, json);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- account history ----------------------------------------------------------------------------

    public static String coopAccount(String profileId) { return "coop:" + profileId; }

    public static String personalAccount(String profileId, UUID member) { return "personal:" + profileId + ":" + member; }

    public void logTx(String account, UUID actor, String actorName, String kind, long deltaTenths, long balanceTenths) {
        TxRow row = new TxRow(System.currentTimeMillis(), actor == null ? null : actor.toString(), actorName, kind, deltaTenths, balanceTenths);
        remember(account, row);
        Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bank_tx (account, ts, actor, actor_name, kind, delta, balance) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, account);
                ps.setLong(2, row.ts());
                ps.setString(3, row.actor());
                ps.setString(4, actorName);
                ps.setString(5, kind);
                ps.setLong(6, deltaTenths);
                ps.setLong(7, balanceTenths);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void remember(String account, TxRow row) {
        Deque<TxRow> d = recent.computeIfAbsent(account, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addFirst(row);
            while (d.size() > 30) d.removeLast();
        }
    }

    /** Cached newest-first rows (may be empty until {@link #loadTx} ran once for this account). */
    public List<TxRow> cachedTx(String account, int limit) {
        Deque<TxRow> d = recent.get(account);
        if (d == null) return List.of();
        synchronized (d) {
            List<TxRow> out = new ArrayList<>();
            for (TxRow r : d) { if (out.size() >= limit) break; out.add(r); }
            return out;
        }
    }

    public boolean txCached(String account) { return recent.containsKey(account); }

    /** Newest-first history from the database; also refreshes the cache. */
    public CompletableFuture<List<TxRow>> loadTx(String account, int limit) {
        return Sky.db().query(c -> {
            List<TxRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ts, actor, actor_name, kind, delta, balance FROM bank_tx WHERE account = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, account);
                ps.setInt(2, Math.max(1, Math.min(limit, 30)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new TxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6)));
                }
            }
            Deque<TxRow> d = new ArrayDeque<>(out);
            recent.put(account, d);
            return out;
        });
    }

    // ---- personal accounts ---------------------------------------------------------------------------

    /** {tenths, lastPeriod}; creates the row (0, -1) when missing. */
    public CompletableFuture<long[]> loadPersonal(String profileId, UUID member) {
        return Sky.db().transaction(c -> {
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT OR IGNORE INTO bank_personal (profile_id, uuid, tenths, last_period) VALUES (?,?,0,-1)")) {
                ins.setString(1, profileId);
                ins.setString(2, member.toString());
                ins.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT tenths, last_period FROM bank_personal WHERE profile_id = ? AND uuid = ?")) {
                ps.setString(1, profileId);
                ps.setString(2, member.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new long[]{rs.getLong(1), rs.getLong(2)};
                }
            }
            return new long[]{0, -1};
        });
    }

    /** Add (credit) to a personal account. */
    public CompletableFuture<Void> personalAdd(String profileId, UUID member, long tenths) {
        return Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bank_personal SET tenths = tenths + ? WHERE profile_id = ? AND uuid = ?")) {
                ps.setLong(1, tenths);
                ps.setString(2, profileId);
                ps.setString(3, member.toString());
                if (ps.executeUpdate() == 0) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO bank_personal (profile_id, uuid, tenths, last_period) VALUES (?,?,?,-1)")) {
                        ins.setString(1, profileId);
                        ins.setString(2, member.toString());
                        ins.setLong(3, tenths);
                        ins.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    /** Take from a personal account only if the balance covers it; true when taken. */
    public CompletableFuture<Boolean> personalTake(String profileId, UUID member, long tenths) {
        return Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bank_personal SET tenths = tenths - ? WHERE profile_id = ? AND uuid = ? AND tenths >= ?")) {
                ps.setLong(1, tenths);
                ps.setString(2, profileId);
                ps.setString(3, member.toString());
                ps.setLong(4, tenths);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Void> personalPeriod(String profileId, UUID member, long period) {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE bank_personal SET last_period = ? WHERE profile_id = ? AND uuid = ?")) {
                ps.setLong(1, period);
                ps.setString(2, profileId);
                ps.setString(3, member.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- pending items / coins (trade escrow + deliveries) -------------------------------------------

    /** Put one escrowed stack on record. Fire-and-forget: FIFO guarantees it lands before any later change. */
    public CompletableFuture<Void> escrow(String key, UUID owner, String tradeId, byte[] item) {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bank_pending (pkey, owner, trade_id, kind, item, coins, state, from_uuid, created) VALUES (?,?,?,?,?,0,'escrow',?,?)")) {
                ps.setString(1, key);
                ps.setString(2, owner.toString());
                ps.setString(3, tradeId);
                ps.setString(4, "item");
                ps.setBytes(5, item);
                ps.setString(6, owner.toString());
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return (Void) null;
        }).whenComplete((v, t) -> {
            if (t != null) Sky.log().warning("[bank] could not file trade escrow " + key + " for " + owner + ": " + t.getMessage());
        });
    }

    /** Remove escrow rows (the item goes back to its owner); returns the keys that were really removed. */
    public CompletableFuture<List<String>> release(List<String> keys) {
        return Sky.db().transaction(c -> {
            List<String> gone = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM bank_pending WHERE pkey = ? AND state = 'escrow'")) {
                for (String k : keys) {
                    ps.setString(1, k);
                    if (ps.executeUpdate() == 1) gone.add(k);
                }
            }
            return gone;
        });
    }

    /** Turn escrow rows into deliveries for their current owner (the owner left mid-trade). */
    public CompletableFuture<Void> toDelivery(List<String> keys) {
        return Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE bank_pending SET state = 'deliver' WHERE pkey = ? AND state = 'escrow'")) {
                for (String k : keys) { ps.setString(1, k); ps.executeUpdate(); }
            }
            return null;
        });
    }

    /**
     * The trade itself, in ONE transaction: every escrowed row changes owner and becomes a delivery,
     * the coin deliveries are written, the log row is written. Returns false (and changes nothing) when
     * any escrow row is missing.
     */
    public CompletableFuture<Boolean> commitTrade(String tradeId, UUID a, String aName, UUID b, String bName,
                                                  List<String> aKeys, List<String> bKeys, long aCoins, long bCoins,
                                                  String aItems, String bItems) {
        return Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE bank_pending SET owner = ?, state = 'deliver' WHERE pkey = ? AND owner = ? AND state = 'escrow'")) {
                for (String k : aKeys) {
                    ps.setString(1, b.toString()); ps.setString(2, k); ps.setString(3, a.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("escrow row " + k + " missing for " + aName);
                }
                for (String k : bKeys) {
                    ps.setString(1, a.toString()); ps.setString(2, k); ps.setString(3, b.toString());
                    if (ps.executeUpdate() != 1) throw new SQLException("escrow row " + k + " missing for " + bName);
                }
            }
            if (aCoins > 0) insertCoins(c, b, tradeId, aCoins, a);
            if (bCoins > 0) insertCoins(c, a, tradeId, bCoins, b);
            long ts = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bank_trades (id, ts, a, a_name, b, b_name, a_coins, b_coins, a_items, b_items) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, tradeId);
                ps.setLong(2, ts);
                ps.setString(3, a.toString());
                ps.setString(4, aName);
                ps.setString(5, b.toString());
                ps.setString(6, bName);
                ps.setLong(7, aCoins);
                ps.setLong(8, bCoins);
                ps.setString(9, aItems);
                ps.setString(10, bItems);
                ps.executeUpdate();
            }
            synchronized (recentTrades) {
                recentTrades.addFirst(new TradeLog(tradeId, ts, aName, bName, aCoins, bCoins, aItems, bItems));
                while (recentTrades.size() > 50) recentTrades.removeLast();
            }
            return true;
        }).exceptionally(t -> {
            Sky.log().warning("[bank] trade " + tradeId + " was NOT committed: " + t.getMessage());
            return false;
        });
    }

    private static void insertCoins(Connection c, UUID owner, String tradeId, long tenths, UUID from) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO bank_pending (pkey, owner, trade_id, kind, item, coins, state, from_uuid, created) VALUES (?,?,?,'coins',NULL,?,'deliver',?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, owner.toString());
            ps.setString(3, tradeId);
            ps.setLong(4, tenths);
            ps.setString(5, from.toString());
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Take every delivery waiting for {@code owner} OUT of the table and return it. */
    public CompletableFuture<List<Pending>> claim(UUID owner) {
        return Sky.db().transaction(c -> {
            List<Pending> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT pkey, trade_id, kind, item, coins, from_uuid FROM bank_pending WHERE owner = ? AND state = 'deliver' ORDER BY created")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Pending(rs.getString(1), owner, rs.getString(2), rs.getString(3),
                            rs.getBytes(4), rs.getLong(5), rs.getString(6)));
                }
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM bank_pending WHERE pkey = ?")) {
                for (Pending p : out) { del.setString(1, p.key()); del.executeUpdate(); }
            }
            return out;
        });
    }

    /** Put claimed deliveries back (the owner logged off before they could be handed over). */
    public CompletableFuture<Void> unclaim(List<Pending> rows) {
        return Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO bank_pending (pkey, owner, trade_id, kind, item, coins, state, from_uuid, created) VALUES (?,?,?,?,?,?,'deliver',?,?)")) {
                for (Pending p : rows) {
                    ps.setString(1, p.key());
                    ps.setString(2, p.owner().toString());
                    ps.setString(3, p.tradeId());
                    ps.setString(4, p.kind());
                    ps.setBytes(5, p.item());
                    ps.setLong(6, p.coins());
                    ps.setString(7, p.from());
                    ps.setLong(8, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    /** At boot no trade is open any more: every escrow row is now a delivery back to whoever put it in. */
    public void escrowToDeliveryAtBoot() {
        try {
            Integer n = Sky.db().transaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE bank_pending SET state = 'deliver' WHERE state = 'escrow'")) {
                    return ps.executeUpdate();
                }
            }).join();
            if (n != null && n > 0) Sky.log().info("[bank] " + n + " item(s) left in trade escrow by the last shutdown will be returned to their owners on login");
        } catch (Throwable t) {
            Sky.log().warning("[bank] could not recover trade escrow at boot: " + t.getMessage());
        }
    }

    public CompletableFuture<Integer> pendingCount(UUID owner) {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM bank_pending WHERE owner = ?")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        });
    }

    public List<TradeLog> recentTrades(int limit) {
        synchronized (recentTrades) {
            List<TradeLog> out = new ArrayList<>();
            for (TradeLog t : recentTrades) { if (out.size() >= limit) break; out.add(t); }
            return out;
        }
    }

    /** Completed trades between these two players, either way round (for the first-trade warning). */
    public CompletableFuture<Integer> tradesBetween(UUID x, UUID y) {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM bank_trades WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)")) {
                ps.setString(1, x.toString());
                ps.setString(2, y.toString());
                ps.setString(3, y.toString());
                ps.setString(4, x.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        });
    }

    /** Recent trades involving a player (newest first) from the database. */
    public CompletableFuture<List<TradeLog>> tradesOf(UUID player, int limit) {
        return Sky.db().query(c -> {
            List<TradeLog> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, ts, a_name, b_name, a_coins, b_coins, a_items, b_items FROM bank_trades WHERE a = ? OR b = ? ORDER BY ts DESC LIMIT ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                ps.setInt(3, Math.max(1, Math.min(limit, 50)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new TradeLog(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getString(8)));
                }
            }
            return out;
        });
    }
}
