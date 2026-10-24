package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.world.gen.Box;
import com.skyclone.gen.MemoryCanvas;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Copies a template-space MemoryCanvas onto one garden instance, a budget of changed blocks per
 * tick, so a plot preset or a barn skin never stalls the server. Inside {@code box}: rows at or
 * below {@code keepBelowY} are only changed where the canvas wrote something; rows above it become
 * exactly the canvas (air where it wrote nothing).
 */
final class Placer {

    private final World world;
    private final int ox, oz;
    private final MemoryCanvas canvas;
    private final Box box;
    private final int keepBelowY;
    private final Runnable done;
    private final Map<String, BlockData> cache = new HashMap<>();
    private int x, z, y;
    private BukkitTask task;
    int changed;

    private Placer(World world, int ox, int oz, MemoryCanvas canvas, Box box, int keepBelowY, Runnable done) {
        this.world = world; this.ox = ox; this.oz = oz; this.canvas = canvas; this.box = box; this.keepBelowY = keepBelowY; this.done = done;
        x = box.minX(); z = box.minZ(); y = box.minY();
    }

    /** Start copying; false (and nothing happens, {@code done} is NOT run) when the instance has no world yet. */
    static boolean run(String key, MemoryCanvas canvas, Box box, int keepBelowY, Runnable done) {
        Location o = G.origin(key);
        if (o == null || o.getWorld() == null) return false;
        Placer p = new Placer(o.getWorld(), o.getBlockX(), o.getBlockZ(), canvas, box, keepBelowY, done);
        p.task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), p::step, 1L, 1L);
        return true;
    }

    private BlockData data(String s) {
        return cache.computeIfAbsent(s, k -> {
            try { return Bukkit.createBlockData(k.startsWith("minecraft:") ? k : "minecraft:" + k); }
            catch (Throwable t) { return Bukkit.createBlockData("minecraft:stone"); }
        });
    }

    private void step() {
        int budget = Math.max(200, G.presetBlocksPerTick.get());
        int visits = budget * 12;
        long t0 = System.nanoTime();
        while (visits-- > 0 && budget > 0) {
            if (x > box.maxX()) { finish(); return; }
            String want = canvas.isWritten(x, y, z) ? canvas.get(x, y, z) : (y > keepBelowY ? "air" : null);
            if (want != null) {
                Block b = world.getBlockAt(ox + x, y, oz + z);
                BlockData d = data(want);
                if (!b.getBlockData().equals(d)) { b.setBlockData(d, false); budget--; changed++; }
            }
            if (++y > box.maxY()) {
                y = box.minY();
                if (++z > box.maxZ()) { z = box.minZ(); x++; }
            }
            if ((visits & 1023) == 0 && System.nanoTime() - t0 > 12_000_000L) return;
        }
    }

    private void finish() {
        if (task != null) task.cancel();
        if (done != null) done.run();
    }

    static Consumer<String> noop() { return s -> { }; }
}
