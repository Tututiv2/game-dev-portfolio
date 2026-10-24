package com.skyclone.mod.garden;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Drives Garden crop regrowth (the worlds have no random-tick farming we can rely on): a harvested
 * crop is reset and this queue grows it back on a timer that Crop Growth shortens. A harvested
 * crop shows a half-grown stage at the halfway mark so a lane visibly fills back in.
 *
 * <p>Persisted: the queue is snapshotted to {@code garden-regrowth.json} in the plugin folder every
 * 30 seconds while it changes (written off the main thread) and re-read on enable, so a crash loses
 * at most the last 30 seconds of harvested melon / mushroom / flower spots instead of all of them. A
 * clean shutdown grows everything it can at once ({@link #finishAll}) and saves only what it could
 * not reach (a garden world that is not loaded).
 */
final class Growth {

    private record Entry(UUID world, int x, int y, int z, Material expect, BlockData data, long at, Entry next) {}

    private final PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> Long.compare(a.at, b.at));
    private boolean dirty;
    private final java.util.Map<UUID, Long> missingSince = new java.util.HashMap<>();
    private final Object ioLock = new Object();

    /**
     * CropListener's marks (wave 5: persisted with the queue so a restart no longer forgets them): a freshly placed
     * ripe-on-place crop's "harvestable at" time, and overgrowth a player placed (pays nothing when broken).
     * Position keys are {@code worldUid:x:y:z}. Main thread only.
     */
    final java.util.Map<String, Long> fresh = new java.util.HashMap<>();
    final java.util.Set<String> placed = new java.util.HashSet<>();

    /** A mark changed: save with the next snapshot. */
    void marksChanged() { dirty = true; }

    int size() { return queue.size(); }

    private static File file() { return new File(Sky.plugin().getDataFolder(), "garden-regrowth.json"); }

    void start() {
        int loaded = load();
        if (loaded > 0) Sky.log().info("[garden] restored " + loaded + " pending regrowth entries from the last run");
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::saveIfDirty, 600L, 600L);
    }

    private void add(Entry e) { queue.add(e); dirty = true; }

    /**
     * An ageable crop was reset to age 0: show half growth at half time, full at the end.
     * {@code expect} is the block type that must still be there.
     */
    void ageable(Block b, double seconds) {
        BlockData full = b.getBlockData().clone();
        if (!(full instanceof Ageable a)) return;
        a.setAge(a.getMaximumAge());
        BlockData half = b.getBlockData().clone();
        ((Ageable) half).setAge(Math.max(1, a.getMaximumAge() / 2));
        long now = System.currentTimeMillis();
        long ms = (long) (seconds * 1000);
        Entry fin = new Entry(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(), b.getType(), full, now + ms, null);
        add(new Entry(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(), b.getType(), half, now + ms / 2, fin));
    }

    /** Put {@code data} back at this (now empty) position after {@code seconds}. */
    void restore(World w, int x, int y, int z, BlockData data, double seconds) {
        add(new Entry(w.getUID(), x, y, z, Material.AIR, data.clone(), System.currentTimeMillis() + (long) (seconds * 1000), null));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        int budget = 3000;
        while (budget-- > 0) {
            Entry e = queue.peek();
            if (e == null || e.at > now) return;
            queue.poll();
            dirty = true;
            World w = Bukkit.getWorld(e.world);
            if (w == null) {
                // the garden world is not loaded: try again a little later, for up to a day after we first
                // noticed it missing (re-queueing moves `at`, so the age has to be kept per world)
                long since = missingSince.computeIfAbsent(e.world, k -> now);
                if (now - since < 86_400_000L) queue.add(new Entry(e.world, e.x, e.y, e.z, e.expect, e.data, now + 30_000, e.next));
                continue;
            }
            missingSince.remove(e.world);
            if (!w.isChunkLoaded(e.x >> 4, e.z >> 4)) {
                // everyone left the garden: load that chunk off-thread and grow it then, so a harvested
                // melon or mushroom is never lost just because its owner warped away
                final Entry fe = e;
                w.getChunkAtAsync(e.x >> 4, e.z >> 4).whenComplete((ch, err) -> {
                    if (err != null) return;
                    if (Bukkit.isPrimaryThread()) apply(w, fe); else Bukkit.getScheduler().runTask(Sky.plugin(), () -> apply(w, fe));
                });
                continue;
            }
            apply(w, e);
        }
    }

    private void apply(World w, Entry e) {
        Block b = w.getBlockAt(e.x, e.y, e.z);
        Material cur = b.getType();
        boolean ok = e.expect == Material.AIR ? cur.isAir() && supported(b, e.data) : cur == e.expect;
        if (ok) {
            if (e.expect != Material.AIR && b.getBlockData() instanceof Ageable now2 && e.data instanceof Ageable want
                    && now2.getAge() >= want.getAge()) {
                // already further along (vanilla growth got there first)
            } else b.setBlockData(e.data, false);
        }
        if (e.next != null) add(e.next);
    }

    /**
     * A restored block must stand on something that can hold it: a cane or cactus segment on the
     * same plant, a flower's top half on its bottom half, anything else on a solid block. Without
     * this, breaking the soil under a harvested plant left the queue placing floating crops that
     * popped into free items.
     */
    static boolean supported(Block b, BlockData data) {
        Material m = data.getMaterial();
        Material below = b.getRelative(org.bukkit.block.BlockFace.DOWN).getType();
        if (m == Material.SUGAR_CANE || m == Material.CACTUS) return below == m;
        if (data instanceof org.bukkit.block.data.Bisected bi && bi.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) return below == m;
        return below.isSolid();
    }

    void clear() { queue.clear(); dirty = true; }

    /**
     * Shutdown: grow everything that is still waiting right now, then save only the entries whose
     * world was not loaded (they are grown when it is). Without this a melon, mushroom or flower
     * harvested in the last minute before a restart never came back. Returns how many blocks were placed.
     */
    int finishAll() {
        int n = 0;
        List<Entry> unreachable = new ArrayList<>();
        Entry e;
        while ((e = queue.poll()) != null) {
            Entry last = e;
            while (last.next != null) last = last.next;   // straight to the last stage
            World w = Bukkit.getWorld(last.world);
            if (w == null) { unreachable.add(e); continue; }
            try {
                Block b = w.getBlockAt(last.x, last.y, last.z);
                Material cur = b.getType();
                boolean ok = last.expect == Material.AIR ? cur.isAir() && supported(b, last.data) : cur == last.expect;
                if (ok) { b.setBlockData(last.data, false); n++; }
            } catch (Throwable ignored) { }
        }
        queue.addAll(unreachable);
        write(snapshot());                                  // synchronous: the scheduler is going away
        queue.clear();
        dirty = false;
        return n;
    }

    // =====================================================================================
    // persistence
    // =====================================================================================

    private void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        String json = snapshot();                           // built on the main thread, written off it
        Bukkit.getScheduler().runTaskAsynchronously(Sky.plugin(), () -> write(json));
    }

    /**
     * The queue and the marks as JSON: {@code {"queue": [chains...], "fresh": {pos: millis}, "placed": [pos...]}}, each
     * chain an array of stages. (The first format was the bare queue array; {@link #load} still reads it.)
     */
    private String snapshot() {
        JsonArray all = new JsonArray();
        for (Entry e : queue) {
            JsonArray chain = new JsonArray();
            for (Entry s = e; s != null; s = s.next) {
                JsonObject o = new JsonObject();
                o.addProperty("w", s.world.toString());
                o.addProperty("x", s.x);
                o.addProperty("y", s.y);
                o.addProperty("z", s.z);
                o.addProperty("e", s.expect.name());
                o.addProperty("d", s.data.getAsString());
                o.addProperty("t", s.at);
                chain.add(o);
            }
            all.add(chain);
        }
        long now = System.currentTimeMillis();
        fresh.values().removeIf(t -> t <= now);
        if (all.isEmpty() && fresh.isEmpty() && placed.isEmpty()) return "[]";
        JsonObject root = new JsonObject();
        root.add("queue", all);
        JsonObject fr = new JsonObject();
        for (java.util.Map.Entry<String, Long> e : fresh.entrySet()) fr.addProperty(e.getKey(), e.getValue());
        root.add("fresh", fr);
        JsonArray pl = new JsonArray();
        for (String s : placed) pl.add(s);
        root.add("placed", pl);
        return root.toString();
    }

    private void write(String json) {
        synchronized (ioLock) {
            try {
                File f = file();
                File dir = f.getParentFile();
                if (dir != null && !dir.exists() && !dir.mkdirs()) return;
                if ("[]".equals(json)) { Files.deleteIfExists(f.toPath()); return; }
                File tmp = new File(dir, f.getName() + ".tmp");
                Files.writeString(tmp.toPath(), json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable t) {
                Sky.log().warning("[garden] could not save the regrowth queue: " + t);
            }
        }
    }

    /** Read the last snapshot back into the queue. A bad entry is skipped, never fatal. */
    private int load() {
        File f = file();
        if (!f.isFile()) return 0;
        int n = 0;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            JsonArray chains;
            if (root.isJsonArray()) chains = root.getAsJsonArray();             // the first format: the bare queue
            else if (root.isJsonObject()) {
                JsonObject o = root.getAsJsonObject();
                chains = o.has("queue") && o.get("queue").isJsonArray() ? o.getAsJsonArray("queue") : new JsonArray();
                long now = System.currentTimeMillis();
                if (o.has("fresh") && o.get("fresh").isJsonObject())
                    for (java.util.Map.Entry<String, JsonElement> e : o.getAsJsonObject("fresh").entrySet()) {
                        try { long t = e.getValue().getAsLong(); if (t > now) fresh.put(e.getKey(), t); } catch (Throwable ignored) { }
                    }
                if (o.has("placed") && o.get("placed").isJsonArray())
                    for (JsonElement e : o.getAsJsonArray("placed")) { try { placed.add(e.getAsString()); } catch (Throwable ignored) { } }
            } else return 0;
            for (JsonElement ce : chains) {
                try {
                    JsonArray chain = ce.getAsJsonArray();
                    Entry next = null;
                    for (int i = chain.size() - 1; i >= 0; i--) {
                        JsonObject o = chain.get(i).getAsJsonObject();
                        next = new Entry(UUID.fromString(o.get("w").getAsString()), o.get("x").getAsInt(), o.get("y").getAsInt(),
                                o.get("z").getAsInt(), Material.valueOf(o.get("e").getAsString()),
                                Bukkit.createBlockData(o.get("d").getAsString()), o.get("t").getAsLong(), next);
                    }
                    if (next != null) { queue.add(next); n++; }
                } catch (Throwable bad) { /* one unreadable chain: skip it */ }
            }
        } catch (Throwable t) {
            Sky.log().warning("[garden] could not read the regrowth queue: " + t);
        }
        return n;
    }
}
