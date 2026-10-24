package com.shadowprison.grant;

import com.shadowprison.store.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * /claim storage. Holds the REAL ItemStack bytes ({@link ItemStack#serializeAsBytes()}), so what
 * comes out is exactly what went in — PDC, enchants, serial and all. SP1 stored descriptors and
 * rebuilt them at claim time: keys, GPUs and cash bundles came back as plain books (rule 29).
 */
public final class Claims {

    private final Database db;

    public Claims(Database db) {
        this.db = db;
    }

    public record Entry(long id, ItemStack item, String source, long createdAt) {
    }

    /** Deposits a copy of {@code item}. Safe from any thread. */
    public void deposit(UUID owner, ItemStack item, String source) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return;
        }
        byte[] bytes = item.serializeAsBytes();
        long now = System.currentTimeMillis();
        db.submit("claim-deposit", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO claims(uuid, item, source, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, owner.toString());
                ps.setBytes(2, bytes);
                ps.setString(3, source == null ? "unknown" : source);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        });
    }

    /** Reads a player's pending claims. OFF the main thread only (Database enforces it). */
    public List<Entry> list(UUID owner) throws Exception {
        return db.read(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, item, source, created_at FROM claims WHERE uuid = ? ORDER BY id")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ItemStack item;
                        try {
                            item = ItemStack.deserializeBytes(rs.getBytes(2));
                        } catch (RuntimeException unreadable) {
                            continue;   // left in the store untouched; an admin can inspect it
                        }
                        out.add(new Entry(rs.getLong(1), item, rs.getString(3), rs.getLong(4)));
                    }
                }
            }
            return out;
        });
    }

    /**
     * Removes one claim row and completes with TRUE only if THIS call removed it. A second click,
     * a lag-spiked double packet, or two open menus all get false — exactly-once delivery is
     * decided by the store's row count, not by anything the client can race.
     */
    public CompletableFuture<Boolean> take(UUID owner, long id) {
        AtomicInteger removed = new AtomicInteger();
        return db.submitTracked("claim-take", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE id = ? AND uuid = ?")) {
                ps.setLong(1, id);
                ps.setString(2, owner.toString());
                removed.set(ps.executeUpdate());
            }
        }).thenApply(v -> removed.get() == 1);
    }
}
