package com.skyclone.mod.mayors;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rewards owed to a player who was offline (or logged out mid-auction): Dark Auction lots, festival
 * prizes, Stonks payouts. Stored in {@code mayors_claims} and handed over on their next
 * {@code ProfileReady}. Coins never wait here: they go through {@code depositOffline}.
 */
final class Claims {

    private Claims() {}

    /**
     * Give an item now if the player is online and ready, else queue it. {@code extra} (nullable) is
     * written into the created stack's ItemData.extra (e.g. {"winning_bid": 1234567}).
     */
    static void give(UUID uuid, String itemId, int amount, JsonObject extra, String reason) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && Sky.profiles().isReady(p) && !inRift(p)) {
            deliver(p, itemId, amount, extra);
            return;
        }
        // In the Rift the live inventory is the Rift inventory: a prize given there stays parked in the Rift when the
        // player leaves (playtest "endgame": a 20M Dark Auction lot won from the Rift vanished from the main
        // inventory). Queue it instead; it is handed over when they leave the Rift (MayorsModule.onIslandChange).
        if (p != null && inRift(p)) Msg.send(p, "&7Your reward waits for you outside the Rift.");
        String ex = extra == null ? null : extra.toString();
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mayors_claims(uuid, item, amount, extra, reason, at) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, itemId);
                ps.setInt(3, amount);
                ps.setString(4, ex);
                ps.setString(5, reason);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    static void deliver(Player p, String itemId, int amount, JsonObject extra) {
        String id = Goods.resolve(itemId);
        if (id == null) {
            Sky.log().warning("[mayors] reward " + itemId + " for " + p.getName() + " no longer exists; skipped");
            return;
        }
        if (extra == null || extra.isEmpty()) {
            Goods.give(p, id, amount, LootSource.REWARD);
            return;
        }
        for (int i = 0; i < Math.max(1, amount); i++) {
            ItemStack s = Sky.items().create(id, 1);
            try { Sky.items().update(s, d -> extra.entrySet().forEach(e -> d.extra.add(e.getKey(), e.getValue().deepCopy()))); }
            catch (Throwable ignored) { }
            Sky.loot().give(p, s, LootSource.REWARD);
        }
    }

    private record Row(long id, String item, int amount, String extra) {}

    static boolean inRift(Player p) {
        return "rift".equals(Sky.worlds().islandOf(p.getWorld()));
    }

    /** Hand over everything queued for this player. Called on ProfileReady and on leaving the Rift. */
    static void flush(Player p) {
        if (inRift(p)) return;
        String uuid = p.getUniqueId().toString();
        Sky.db().transaction(c -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, item, amount, extra FROM mayors_claims WHERE uuid = ? ORDER BY id")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4)));
                }
            }
            if (!rows.isEmpty()) {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM mayors_claims WHERE uuid = ?")) {
                    del.setString(1, uuid);
                    del.executeUpdate();
                }
            }
            return rows;
        }).thenAccept(rows -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (rows.isEmpty()) return;
            if (!p.isOnline()) {
                // logged out between the read and now: put them back
                for (Row r : rows) give(p.getUniqueId(), r.item(), r.amount(), parse(r.extra()), "requeue");
                return;
            }
            if (inRift(p)) {
                // walked back into the Rift between the read and now: put them back
                for (Row r : rows) give(p.getUniqueId(), r.item(), r.amount(), parse(r.extra()), "requeue");
                return;
            }
            for (Row r : rows) deliver(p, r.item(), r.amount(), parse(r.extra()));
            Msg.success(p, "You collected " + rows.size() + " event reward" + (rows.size() == 1 ? "" : "s") + " that were waiting for you!");
        }));
    }

    private static JsonObject parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return JsonParser.parseString(s).getAsJsonObject(); } catch (Exception e) { return null; }
    }

    /** Admin: how many rewards are queued for someone (blocking; admin command only). */
    static int pending(UUID uuid) {
        try {
            return Sky.db().query(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM mayors_claims WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
                }
            }).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return -1;
        }
    }
}
