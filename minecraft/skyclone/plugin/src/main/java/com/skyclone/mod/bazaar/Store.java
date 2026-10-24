package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Persistence. The order book is authoritative in memory on the main thread; every change is captured as an
 * immutable row snapshot ON the main thread and queued to the single DB thread, which runs writes in the order they
 * were queued. That FIFO is what makes the money safe: the market always queues its order rows BEFORE it asks core
 * to credit a player, so a crash can lose a payout but can never pay twice.
 */
final class Store {

    record OrderRow(long id, String product, String side, String owner, String ownerName, String profileId, long price,
                    int amount, int filled, int itemsPending, long coinsPending, long taxPaid, long created, long expires,
                    String state, double taxBase) {}

    record MmRow(String product, double fair, double inv, long pnl, double vwap, double pvol, long updated) {}

    record LogRow(UUID uuid, String profileId, long time, String kind, String product, long qty, long price, long total) {}

    private final Map<Long, OrderRow> pending = new LinkedHashMap<>();
    private final List<Long> deletes = new ArrayList<>();

    void migrate() {
        Sky.db().migrate("bazaar", 1,
                "CREATE TABLE bazaar_orders (id INTEGER PRIMARY KEY, product TEXT NOT NULL, side TEXT NOT NULL, owner TEXT NOT NULL,"
                        + " owner_name TEXT, profile_id TEXT, price INTEGER NOT NULL, amount INTEGER NOT NULL, filled INTEGER NOT NULL,"
                        + " items_pending INTEGER NOT NULL, coins_pending INTEGER NOT NULL, tax_paid INTEGER NOT NULL,"
                        + " created INTEGER NOT NULL, expires INTEGER NOT NULL, state TEXT NOT NULL)",
                "CREATE INDEX bazaar_orders_owner ON bazaar_orders(owner)",
                "CREATE TABLE bazaar_mm (product TEXT PRIMARY KEY, fair REAL NOT NULL, inv REAL NOT NULL, pnl INTEGER NOT NULL,"
                        + " vwap REAL NOT NULL, pvol REAL NOT NULL, updated INTEGER NOT NULL)",
                "CREATE TABLE bazaar_bars (product TEXT NOT NULL, hour INTEGER NOT NULL, open INTEGER, high INTEGER, low INTEGER,"
                        + " close INTEGER, volume INTEGER, notional INTEGER, mid_sum INTEGER, mid_count INTEGER, PRIMARY KEY (product, hour))",
                "CREATE TABLE bazaar_log (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, profile_id TEXT, time INTEGER NOT NULL,"
                        + " kind TEXT NOT NULL, product TEXT NOT NULL, qty INTEGER NOT NULL, price INTEGER NOT NULL, total INTEGER NOT NULL)",
                "CREATE INDEX bazaar_log_uuid ON bazaar_log(uuid, time)");
        // v2: each order remembers its owner's tax rate (Bazaar Flipper) so offline fills charge the right rate
        Sky.db().migrate("bazaar", 2, "ALTER TABLE bazaar_orders ADD COLUMN tax_base REAL NOT NULL DEFAULT -1");
    }

    // ---- orders ---------------------------------------------------------------------------------------------

    static OrderRow row(Order o) {
        return new OrderRow(o.id, o.product, o.side.name(), o.owner.toString(), o.ownerName, o.profileId, o.price, o.amount,
                o.filled, o.itemsPending, o.coinsPending, o.taxPaid, o.created, o.expires, o.state.name(), o.taxBase);
    }

    /** Mark a player order changed; its state is captured NOW. Written by the next {@link #flush()}. */
    void dirty(Order o) {
        if (o.bot || o.owner == null) return;
        if (o.state == Order.State.DONE) { pending.remove(o.id); deletes.add(o.id); return; }
        pending.put(o.id, row(o));
    }

    /** Queue every captured change as ONE transaction. Call before crediting a player. */
    CompletableFuture<Void> flush() {
        if (pending.isEmpty() && deletes.isEmpty()) return CompletableFuture.completedFuture(null);
        List<OrderRow> rows = new ArrayList<>(pending.values());
        List<Long> del = new ArrayList<>(deletes);
        pending.clear();
        deletes.clear();
        return Sky.db().transaction(c -> {
            if (!rows.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO bazaar_orders (id, product, side, owner, owner_name,"
                        + " profile_id, price, amount, filled, items_pending, coins_pending, tax_paid, created, expires, state, tax_base)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (OrderRow r : rows) {
                        ps.setLong(1, r.id()); ps.setString(2, r.product()); ps.setString(3, r.side()); ps.setString(4, r.owner());
                        ps.setString(5, r.ownerName()); ps.setString(6, r.profileId()); ps.setLong(7, r.price()); ps.setInt(8, r.amount());
                        ps.setInt(9, r.filled()); ps.setInt(10, r.itemsPending()); ps.setLong(11, r.coinsPending()); ps.setLong(12, r.taxPaid());
                        ps.setLong(13, r.created()); ps.setLong(14, r.expires()); ps.setString(15, r.state()); ps.setDouble(16, r.taxBase());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            if (!del.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM bazaar_orders WHERE id = ?")) {
                    for (Long id : del) { ps.setLong(1, id); ps.addBatch(); }
                    ps.executeBatch();
                }
            }
            return (Void) null;
        }).whenComplete((v, ex) -> {
            if (ex != null) Sky.log().log(Level.SEVERE, "[bazaar] ORDER WRITE FAILED (" + rows.size() + " rows) - memory is ahead of the database", ex);
        });
    }

    /** Blocking load at enable (allowed there). Every order that is not DONE. */
    List<OrderRow> loadOrders() {
        return Sky.db().query(c -> {
            List<OrderRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, product, side, owner, owner_name, profile_id, price, amount, filled,"
                    + " items_pending, coins_pending, tax_paid, created, expires, state, tax_base FROM bazaar_orders WHERE state <> 'DONE'");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new OrderRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getLong(11), rs.getLong(12),
                            rs.getLong(13), rs.getLong(14), rs.getString(15), rs.getDouble(16)));
                }
            }
            return out;
        }).join();
    }

    long maxOrderId() {
        return Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM bazaar_orders"); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }).join();
    }

    // ---- market makers --------------------------------------------------------------------------------------

    void saveMm(List<MmRow> rows) {
        if (rows.isEmpty()) return;
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO bazaar_mm (product, fair, inv, pnl, vwap, pvol, updated)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (MmRow r : rows) {
                    ps.setString(1, r.product()); ps.setDouble(2, r.fair()); ps.setDouble(3, r.inv()); ps.setLong(4, r.pnl());
                    ps.setDouble(5, r.vwap()); ps.setDouble(6, r.pvol()); ps.setLong(7, r.updated());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return (Void) null;
        }).whenComplete((v, ex) -> { if (ex != null) Sky.log().log(Level.WARNING, "[bazaar] market-maker save failed", ex); });
    }

    List<MmRow> loadMm() {
        return Sky.db().query(c -> {
            List<MmRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT product, fair, inv, pnl, vwap, pvol, updated FROM bazaar_mm");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new MmRow(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getLong(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getLong(7)));
            }
            return out;
        }).join();
    }

    // ---- price bars -----------------------------------------------------------------------------------------

    record BarRow(String product, PriceHistory.Bar bar) {}

    void saveBars(List<BarRow> rows) {
        if (rows.isEmpty()) return;
        List<long[]> vals = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (BarRow r : rows) {
            PriceHistory.Bar b = r.bar();
            ids.add(r.product());
            vals.add(new long[]{b.hour, b.open, b.high, b.low, b.close, b.volume, b.notional, b.midSum, b.midCount});
        }
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO bazaar_bars (product, hour, open, high, low, close,"
                    + " volume, notional, mid_sum, mid_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < ids.size(); i++) {
                    long[] v = vals.get(i);
                    ps.setString(1, ids.get(i));
                    for (int k = 0; k < 9; k++) ps.setLong(2 + k, v[k]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return (Void) null;
        }).whenComplete((v, ex) -> { if (ex != null) Sky.log().log(Level.WARNING, "[bazaar] price-bar save failed", ex); });
    }

    Map<String, List<PriceHistory.Bar>> loadBars(long fromHour) {
        return Sky.db().query(c -> {
            Map<String, List<PriceHistory.Bar>> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT product, hour, open, high, low, close, volume, notional, mid_sum, mid_count"
                    + " FROM bazaar_bars WHERE hour >= ?")) {
                ps.setLong(1, fromHour);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PriceHistory.Bar b = new PriceHistory.Bar();
                        b.hour = rs.getLong(2); b.open = rs.getLong(3); b.high = rs.getLong(4); b.low = rs.getLong(5);
                        b.close = rs.getLong(6); b.volume = rs.getLong(7); b.notional = rs.getLong(8); b.midSum = rs.getLong(9);
                        b.midCount = rs.getInt(10);
                        out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(b);
                    }
                }
            }
            return out;
        }).join();
    }

    void pruneBars(long beforeHour) {
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM bazaar_bars WHERE hour < ?")) { ps.setLong(1, beforeHour); ps.executeUpdate(); }
            return (Void) null;
        });
    }

    // ---- player trade log -----------------------------------------------------------------------------------

    void log(LogRow r) {
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bazaar_log (uuid, profile_id, time, kind, product, qty, price, total)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, r.uuid().toString()); ps.setString(2, r.profileId()); ps.setLong(3, r.time()); ps.setString(4, r.kind());
                ps.setString(5, r.product()); ps.setLong(6, r.qty()); ps.setLong(7, r.price()); ps.setLong(8, r.total());
                ps.executeUpdate();
            }
            return (Void) null;
        }).whenComplete((v, ex) -> { if (ex != null) Sky.log().log(Level.WARNING, "[bazaar] log write failed", ex); });
    }

    CompletableFuture<List<LogRow>> recent(UUID uuid, int n) {
        return Sky.db().query(c -> {
            List<LogRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT profile_id, time, kind, product, qty, price, total FROM bazaar_log"
                    + " WHERE uuid = ? ORDER BY time DESC, id DESC LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, n);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new LogRow(uuid, rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                            rs.getLong(5), rs.getLong(6), rs.getLong(7)));
                }
            }
            return out;
        });
    }

    void pruneLog(long beforeMillis) {
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM bazaar_log WHERE time < ?")) { ps.setLong(1, beforeMillis); ps.executeUpdate(); }
            return (Void) null;
        });
    }
}
