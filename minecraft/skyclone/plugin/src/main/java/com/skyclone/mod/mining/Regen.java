package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.logging.Level;

/**
 * Ore regeneration (research 6.2). A mined block becomes a placeholder and is restored after the
 * island's delay. The queue is mirrored to the database (table mining_regen, batched every 5 s) so a
 * crash never leaves bedrock scars: on boot every stored row is restored. On a clean shutdown every
 * pending block is restored synchronously.
 */
final class Regen {

    record Key(String world, int x, int y, int z) {}

    static final class Entry {
        final Key key;
        final String restore;
        long due;
        Entry(Key key, String restore, long due) { this.key = key; this.restore = restore; this.due = due; }
    }

    private final Map<Key, Entry> pending = new HashMap<>();
    private final PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> Long.compare(a.due, b.due));
    private final Map<Key, String> toInsert = new HashMap<>();
    private final java.util.Set<Key> toDelete = new java.util.LinkedHashSet<>();

    Regen() {
        Sky.db().migrate("mining", 1,
                "CREATE TABLE IF NOT EXISTS mining_regen (world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY (world, x, y, z))");
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::flush, 100L, 100L);
        // restore whatever a crash left behind, once the worlds exist
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), this::loadStored, 200L);
    }

    static Key key(Block b) { return new Key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()); }

    boolean isPending(Block b) { return pending.containsKey(key(b)); }

    int size() { return pending.size(); }

    /**
     * Put {@code placeholder} now and restore the CURRENT block (or {@code restoreTo} when not null) after
     * {@code seconds}. A position already pending keeps its first original (titanium that grew out of
     * mithril goes back to mithril).
     */
    void schedule(Block b, BlockData placeholder, BlockData restoreTo, int seconds) {
        Key k = key(b);
        String original = restoreTo != null ? restoreTo.getAsString() : b.getBlockData().getAsString();
        Entry e = pending.get(k);
        long due = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
        if (e == null) {
            e = new Entry(k, original, due);
            pending.put(k, e);
            queue.add(e);
            synchronized (toInsert) { toInsert.put(k, original); toDelete.remove(k); }
        } else {
            queue.remove(e);
            e.due = due;
            queue.add(e);
        }
        b.setBlockData(placeholder, false);
    }

    /** Restore one position now (used by admin and temporary blocks). */
    void restoreNow(Block b) {
        Entry e = pending.get(key(b));
        if (e != null) { queue.remove(e); apply(e, true); }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        int budget = 400;
        while (!queue.isEmpty() && queue.peek().due <= now && budget-- > 0) {
            Entry e = queue.poll();
            if (!apply(e, false)) { e.due = now + 2000; queue.add(e); }
        }
    }

    /** False when it must wait (world unloaded, a player standing in the block). */
    private boolean apply(Entry e, boolean force) {
        World w = Bukkit.getWorld(e.key.world);
        if (w == null) return false;
        Block b = w.getBlockAt(e.key.x, e.key.y, e.key.z);
        if (!force) {
            BoundingBox box = BoundingBox.of(b);
            for (Entity en : w.getNearbyEntities(box.clone().expand(0.1))) {
                if (en instanceof Player p && p.getBoundingBox().overlaps(box)) return false;
            }
        }
        try {
            b.setBlockData(Bukkit.createBlockData(e.restore), false);
        } catch (IllegalArgumentException ex) {
            Sky.log().warning("[mining] cannot restore block data '" + e.restore + "' at " + e.key);
        }
        pending.remove(e.key);
        synchronized (toInsert) {
            if (toInsert.remove(e.key) == null) toDelete.add(e.key);
        }
        return true;
    }

    private void flush() {
        Map<Key, String> ins;
        List<Key> del;
        synchronized (toInsert) {
            if (toInsert.isEmpty() && toDelete.isEmpty()) return;
            ins = new HashMap<>(toInsert);
            del = new ArrayList<>(toDelete);
            toInsert.clear();
            toDelete.clear();
        }
        Sky.db().transaction(c -> {
            try (PreparedStatement pi = c.prepareStatement("INSERT OR REPLACE INTO mining_regen (world, x, y, z, data) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement pd = c.prepareStatement("DELETE FROM mining_regen WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                for (Map.Entry<Key, String> en : ins.entrySet()) {
                    Key k = en.getKey();
                    pi.setString(1, k.world); pi.setInt(2, k.x); pi.setInt(3, k.y); pi.setInt(4, k.z); pi.setString(5, en.getValue());
                    pi.addBatch();
                }
                for (Key k : del) {
                    pd.setString(1, k.world); pd.setInt(2, k.x); pd.setInt(3, k.y); pd.setInt(4, k.z);
                    pd.addBatch();
                }
                // deletes first: a position restored and mined again inside one batch must end up STORED
                pd.executeBatch();
                pi.executeBatch();
            }
            return null;
        }).exceptionally(t -> { Sky.log().log(Level.WARNING, "[mining] regen flush failed", t); return null; });
    }

    private void loadStored() {
        Sky.db().query(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, data FROM mining_regen");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Entry(new Key(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), rs.getString(5), 0));
            }
            return out;
        }).thenAccept(list -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            long now = System.currentTimeMillis();
            int n = 0;
            for (Entry e : list) {
                if (pending.containsKey(e.key)) continue;
                e.due = now + 1000;
                pending.put(e.key, e);
                queue.add(e);
                n++;
            }
            if (n > 0) Sky.log().info("[mining] restoring " + n + " mined blocks left from the last run");
        }));
    }

    /** Shutdown: put every block back now and clear the table. */
    void restoreAll() {
        int n = 0;
        for (Entry e : new ArrayList<>(pending.values())) {
            World w = Bukkit.getWorld(e.key.world);
            if (w == null) continue;
            try { w.getBlockAt(e.key.x, e.key.y, e.key.z).setBlockData(Bukkit.createBlockData(e.restore), false); n++; }
            catch (Throwable ignored) { }
            pending.remove(e.key);
        }
        queue.clear();
        synchronized (toInsert) { toInsert.clear(); toDelete.clear(); }
        if (!pending.isEmpty()) return;   // worlds missing: keep their rows for the next boot
        try {
            Sky.db().transaction(c -> { try (PreparedStatement ps = c.prepareStatement("DELETE FROM mining_regen")) { ps.executeUpdate(); } return null; })
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            Sky.log().log(Level.WARNING, "[mining] could not clear mining_regen at shutdown (blocks are restored; rows replay harmlessly)", ex);
        }
        if (n > 0) Sky.log().info("[mining] restored " + n + " mined blocks at shutdown");
    }

    /** Admin: restore everything right now. */
    int restoreEverything() {
        int n = 0;
        for (Entry e : new ArrayList<>(pending.values())) { queue.remove(e); if (apply(e, true)) n++; }
        return n;
    }
}
