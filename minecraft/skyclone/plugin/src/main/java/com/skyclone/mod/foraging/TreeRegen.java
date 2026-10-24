package com.skyclone.mod.foraging;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Regrows cut trees WHOLE: the first cut of a tree records every log of it (and its original block
 * state); after the delay all of them come back together, so a tree never regrows half-cut
 * (research 07 §6.4). Single blocks (flowers) use the same record with one entry.
 *
 * <p>Crash safety: the pending records are written to {@code plugins/SkyClone/foraging-regen.json}
 * (off-thread, when changed, every 30 s) and anything left there is restored at the next boot;
 * on a clean shutdown everything pending is restored synchronously.
 */
final class TreeRegen {

    /** One pending regrowth. Plain fields so Gson can persist it. */
    static final class Rec {
        String world;
        long due;
        List<long[]> pos = new ArrayList<>();     // packed x,y,z as {x,y,z}
        List<String> data = new ArrayList<>();
        int postponed;
    }

    private final Map<String, Rec> byPos = new HashMap<>();   // "world:x:y:z" -> record
    private final List<Rec> pending = new ArrayList<>();
    private boolean dirty;
    private BukkitTask ticker, saver;
    private final Gson gson = new Gson();

    private File file() { return new File(Sky.plugin().getDataFolder(), "foraging-regen.json"); }

    static String key(World w, int x, int y, int z) { return w.getName() + ":" + x + ":" + y + ":" + z; }

    void start() {
        restoreLeftovers();
        ticker = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 20L);
        saver = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::saveAsync, 600L, 600L);
    }

    void stop() {
        if (ticker != null) ticker.cancel();
        if (saver != null) saver.cancel();
        restoreAll();
        try { Files.writeString(file().toPath(), "[]", StandardCharsets.UTF_8); } catch (Exception ignored) { }
    }

    boolean isPending(Block b) { return byPos.containsKey(key(b.getWorld(), b.getX(), b.getY(), b.getZ())); }

    int pendingCount() { return pending.size(); }

    /** Move a pending record's regrowth to {@code seconds} from now. */
    void reschedule(Rec r, int seconds) { r.due = System.currentTimeMillis() + seconds * 1000L; dirty = true; }

    /** The pending record a block belongs to (the tree it was cut from), or null. */
    Rec recFor(Block b) { return byPos.get(key(b.getWorld(), b.getX(), b.getY(), b.getZ())); }

    int pendingBlocks() { int n = 0; for (Rec r : pending) n += r.pos.size(); return n; }

    /**
     * Record a whole tree (or one block) for regrowth unless it is already pending. Blocks already
     * belonging to a pending record are skipped, so overlapping trees never restore twice.
     */
    void record(World w, List<Block> blocks, int seconds) {
        Rec r = new Rec();
        r.world = w.getName();
        r.due = System.currentTimeMillis() + seconds * 1000L;
        for (Block b : blocks) {
            String k = key(w, b.getX(), b.getY(), b.getZ());
            if (byPos.containsKey(k)) continue;
            r.pos.add(new long[]{b.getX(), b.getY(), b.getZ()});
            r.data.add(b.getBlockData().getAsString());
            byPos.put(k, r);
        }
        if (r.pos.isEmpty()) return;
        pending.add(r);
        dirty = true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Iterator<Rec> it = pending.iterator();
        List<Rec> done = new ArrayList<>();
        while (it.hasNext()) {
            Rec r = it.next();
            if (r.due > now) continue;
            World w = Bukkit.getWorld(r.world);
            if (w == null) { it.remove(); unindex(r); dirty = true; continue; }
            if (r.postponed < 10 && occupied(w, r)) { r.due = now + 3000; r.postponed++; continue; }
            done.add(r);
            it.remove();
        }
        for (Rec r : done) { restore(r, true); unindex(r); dirty = true; }
    }

    private boolean occupied(World w, Rec r) {
        if (w.getPlayers().isEmpty()) return false;
        for (Player p : w.getPlayers()) {
            BoundingBox pb = p.getBoundingBox();
            for (long[] q : r.pos) {
                if (Math.abs(q[0] - p.getLocation().getX()) > 3 || Math.abs(q[2] - p.getLocation().getZ()) > 3) continue;
                if (pb.overlaps(new BoundingBox(q[0], q[1], q[2], q[0] + 1, q[1] + 1, q[2] + 1))) return true;
            }
        }
        return false;
    }

    private void restore(Rec r, boolean effects) {
        World w = Bukkit.getWorld(r.world);
        if (w == null) return;
        for (int i = 0; i < r.pos.size(); i++) {
            long[] q = r.pos.get(i);
            Block b = w.getBlockAt((int) q[0], (int) q[1], (int) q[2]);
            // something else stands there now (water is fine: a picked sea pickle leaves water behind)
            if (!b.getType().isAir() && b.getType() != org.bukkit.Material.WATER && !b.getType().name().endsWith("_SAPLING")) continue;
            try {
                BlockData d = Bukkit.createBlockData(r.data.get(i));
                b.setBlockData(d, false);
            } catch (Exception e) {
                Sky.log().warning("[foraging] could not restore " + r.data.get(i) + " at " + b.getLocation());
            }
        }
        if (effects && !r.pos.isEmpty()) {
            long[] q = r.pos.get(0);
            Location l = new Location(w, q[0] + 0.5, q[1] + 1, q[2] + 0.5);
            if (l.isChunkLoaded()) w.spawnParticle(Particle.HAPPY_VILLAGER, l, 12, 0.6, 1.2, 0.6, 0);
        }
    }

    private void unindex(Rec r) {
        for (long[] q : r.pos) byPos.remove(r.world + ":" + q[0] + ":" + q[1] + ":" + q[2]);
    }

    /** Restore everything now (admin, shutdown). Returns trees restored. */
    int restoreAll() {
        int n = pending.size();
        for (Rec r : pending) restore(r, false);
        pending.clear();
        byPos.clear();
        dirty = true;
        return n;
    }

    private void saveAsync() {
        if (!dirty) return;
        dirty = false;
        String json = gson.toJson(pending);
        File f = file();
        Bukkit.getScheduler().runTaskAsynchronously(Sky.plugin(), () -> {
            try {
                f.getParentFile().mkdirs();
                Files.writeString(f.toPath(), json, StandardCharsets.UTF_8);
            } catch (Exception e) {
                Sky.log().warning("[foraging] could not save pending regrowth: " + e.getMessage());
            }
        });
    }

    /** At boot: anything a crash left cut is put back. */
    private void restoreLeftovers() {
        File f = file();
        if (!f.exists()) return;
        try {
            List<Rec> left = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), new TypeToken<List<Rec>>() {}.getType());
            if (left == null || left.isEmpty()) return;
            int n = 0;
            for (Rec r : left) {
                if (Bukkit.getWorld(r.world) == null) {
                    String island = r.world.startsWith("sb_") ? r.world.substring(3) : r.world;
                    try { Sky.worlds().world(island); } catch (Exception ignored) { }
                }
                restore(r, false);
                n++;
            }
            Sky.log().info("[foraging] restored " + n + " trees left cut by the last shutdown");
            Files.writeString(f.toPath(), "[]", StandardCharsets.UTF_8);
        } catch (Exception e) {
            Sky.log().warning("[foraging] could not read " + f.getName() + ": " + e.getMessage());
        }
    }
}
