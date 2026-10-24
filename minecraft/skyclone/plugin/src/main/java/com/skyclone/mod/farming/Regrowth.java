package com.skyclone.mod.farming;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Drives crop regrowth. Our worlds do not rely on random ticks: a harvested crop is put back to its first
 * stage (or removed) and this scheduler walks it back to the exact block that was harvested.
 *
 * <p>Every pending regrowth is saved to {@code plugins/<plugin>/farming-regrowth.json} every 30 s and on
 * shutdown, and anything left over is finished on the next boot - so a crash never leaves a field bare.
 * Shutdown itself also finishes every pending crop, so the saved file is normally empty.
 */
final class Regrowth {

    /** One block that must come back: final block data + optional intermediate stages. */
    static final class Task {
        String world;
        int x, y, z;
        /** Block data strings, applied in order at {@code at[i]} (epoch millis). The last is the restored block. */
        List<String> stages = new ArrayList<>();
        List<Long> at = new ArrayList<>();
        int next;
        Task() {}
        String key() { return world + ":" + x + "," + y + "," + z; }
    }

    private final Map<String, Task> pending = new LinkedHashMap<>();
    private BukkitTask ticker, saver;
    private File file;
    private int applied;

    void start() {
        file = new File(Sky.plugin().getDataFolder(), "farming-regrowth.json");
        load();
        ticker = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 5L);
        saver = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::saveAsync, 600L, 600L);
    }

    void stop() {
        if (ticker != null) ticker.cancel();
        if (saver != null) saver.cancel();
        finishAll();
        saveSync();
    }

    int pendingCount() { return pending.size(); }

    int appliedCount() { return applied; }

    boolean isPending(Block b) { return pending.containsKey(key(b)); }

    private static String key(Block b) { return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ(); }

    /**
     * Schedule {@code finalData} to be restored at {@code b} after {@code seconds}. Ageable crops pass through
     * {@code stages} evenly spaced intermediate ages; others appear at the end.
     */
    void schedule(Block b, BlockData finalData, double seconds, int stages) {
        Task t = new Task();
        t.world = b.getWorld().getName(); t.x = b.getX(); t.y = b.getY(); t.z = b.getZ();
        long now = System.currentTimeMillis();
        long total = (long) Math.max(250, seconds * 1000);
        if (finalData instanceof Ageable ag && stages > 1 && ag.getMaximumAge() > 1) {
            int max = ag.getMaximumAge();
            for (int i = 1; i < stages; i++) {
                Ageable mid = (Ageable) ag.clone();
                mid.setAge(Math.max(1, Math.min(max - 1, (int) Math.round((double) max * i / stages))));
                t.stages.add(mid.getAsString());
                t.at.add(now + total * i / stages);
            }
        }
        t.stages.add(finalData.getAsString());
        t.at.add(now + total);
        pending.put(t.key(), t);
    }

    /** Finish one task right now (admin, shutdown). */
    private boolean apply(Task t, boolean finalStage) {
        World w = Bukkit.getWorld(t.world);
        if (w == null) return false;
        String data = finalStage ? t.stages.get(t.stages.size() - 1) : t.stages.get(t.next);
        try {
            BlockData bd = Bukkit.createBlockData(data);
            Block b = w.getBlockAt(t.x, t.y, t.z);
            Material cur = b.getType();
            // never overwrite something a player or builder put there meanwhile
            if (!cur.isAir() && cur != bd.getMaterial()) return true;
            b.setBlockData(bd, false);
            applied++;
        } catch (IllegalArgumentException e) {
            Sky.log().warning("[farming] bad regrowth block data " + data);
        }
        return true;
    }

    private void tick() {
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        int budget = 400;
        Iterator<Task> it = pending.values().iterator();
        while (it.hasNext() && budget > 0) {
            Task t = it.next();
            if (t.at.get(t.next) > now) continue;
            World w = Bukkit.getWorld(t.world);
            if (w == null) continue;                          // world unloaded: wait
            // Nobody is near: wait. Writing the block would load the chunk synchronously (one sync load per crop of a
            // field a player just walked away from). The crop is restored, straight to its final stage, on the first
            // tick after the chunk loads again - before anyone can see it.
            if (!w.isChunkLoaded(t.x >> 4, t.z >> 4)) {
                if (t.next < t.stages.size() - 1 && t.at.get(t.at.size() - 1) <= now) t.next = t.stages.size() - 1;
                continue;
            }
            budget--;
            apply(t, false);
            t.next++;
            if (t.next >= t.stages.size()) it.remove();
        }
    }

    /**
     * Farmer Orb: finish up to {@code n} pending crops within {@code radius} of {@code at} right now (loaded chunks
     * only). The one due SOONEST goes first, which keeps a cane/cactus column growing from the bottom up (its lower
     * blocks are always due earlier) and a tall flower's lower half before its upper half. Returns how many finished.
     */
    int hasten(org.bukkit.Location at, double radius, int n) {
        if (n <= 0 || pending.isEmpty() || at.getWorld() == null) return 0;
        String w = at.getWorld().getName();
        double r2 = radius * radius;
        int done = 0;
        while (done < n) {
            Task best = null;
            for (Task t : pending.values()) {
                if (!t.world.equals(w)) continue;
                double dx = t.x + 0.5 - at.getX(), dy = t.y + 0.5 - at.getY(), dz = t.z + 0.5 - at.getZ();
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                if (!at.getWorld().isChunkLoaded(t.x >> 4, t.z >> 4)) continue;
                if (best == null || t.at.get(t.at.size() - 1) < best.at.get(best.at.size() - 1)) best = t;
            }
            if (best == null) break;
            apply(best, true);
            pending.remove(best.key());
            done++;
            hastened++;
        }
        return done;
    }

    private int hastened;

    int hastenedCount() { return hastened; }

    /** Finish everything at once. Returns the number of blocks restored. */
    int finishAll() {
        int n = 0;
        for (Task t : new ArrayList<>(pending.values())) if (apply(t, true)) { n++; pending.remove(t.key()); }
        return n;
    }

    /** Finish everything in one world. */
    int finishWorld(String worldName) {
        int n = 0;
        for (Task t : new ArrayList<>(pending.values()))
            if (t.world.equals(worldName) && apply(t, true)) { n++; pending.remove(t.key()); }
        return n;
    }

    // ---- persistence -----------------------------------------------------------------------------

    private static final Gson GSON = new Gson();

    private void load() {
        if (file == null || !file.isFile()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            List<Task> list = GSON.fromJson(r, new TypeToken<List<Task>>() {}.getType());
            if (list != null) for (Task t : list) if (t != null && t.stages != null && !t.stages.isEmpty()) {
                t.next = 0;
                List<String> only = new ArrayList<>(List.of(t.stages.get(t.stages.size() - 1)));
                t.stages = only;
                t.at = new ArrayList<>(List.of(System.currentTimeMillis()));   // overdue: restore on the first tick
                pending.put(t.key(), t);
            }
            if (!pending.isEmpty()) Sky.log().info("[farming] restoring " + pending.size() + " crops left unregrown by the last shutdown");
        } catch (Exception e) {
            Sky.log().log(Level.WARNING, "[farming] could not read " + file.getName(), e);
        }
    }

    private String snapshot() { return GSON.toJson(new ArrayList<>(pending.values())); }

    private void saveAsync() {
        String json = snapshot();
        File f = file;
        Bukkit.getScheduler().runTaskAsynchronously(Sky.plugin(), () -> write(f, json));
    }

    private void saveSync() { write(file, snapshot()); }

    private static synchronized void write(File f, String json) {
        if (f == null) return;
        try {
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) { w.write(json); }
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Sky.log().log(Level.WARNING, "[farming] could not save regrowth state", e);
        }
    }
}
