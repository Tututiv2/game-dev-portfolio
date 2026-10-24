package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Geysers in the Crimson volcano's lava (research 07 3.7: the Steaming-Hot Flounder bites "within 2 blocks
 * of a geyser"). An island's {@code geyser*} anchors are used when its generator places them; the Crimson
 * Isle has none, so the geysers are FOUND instead: lava columns at the crater's lava level inside the
 * crimson module's {@code gather.volcano} area (its layout data {@code crimson.crater} = "rimY,lavaLevel"),
 * picked in a fixed hashed order with a minimum spacing ({@link FishMath#pickSites}), so the same lava gives
 * the same geysers every boot. Only loaded chunks are ever read; the search retries every 30 s until it has
 * its full count.
 *
 * <p>Live: geysers simmer (steam) while someone is near, and each one spouts on its own clock - a column of
 * steam that throws anyone standing over it into the air (dial, no damage). The Gusher window (Trophies)
 * makes them steam harder.
 */
final class Geysers {

    private Geysers() {}

    static final String ISLAND = Trophies.TROPHY_ISLAND;

    private static final Map<String, List<Location>> FOUND = new HashMap<>();
    private static final Map<String, Long> LAST_SCAN = new HashMap<>();
    private static int task = -1;
    private static long ticks;

    static void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Geysers::tick, 60L, 10L).getTaskId();
    }

    static void stop() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
    }

    /** The geysers of an island: its geyser anchors, else the found ones (possibly still empty). */
    static List<Location> sites(String island) {
        if (island == null || !FishDials.GEYSERS.get()) return List.of();
        List<Location> a;
        try { a = Sky.worlds().anchors(island, "geyser"); } catch (RuntimeException e) { a = List.of(); }
        if (a != null && !a.isEmpty()) return a;
        List<Location> f = FOUND.get(island);
        return f == null ? List.of() : f;
    }

    static boolean near(List<Location> geysers, Location at, double radius) {
        if (at == null) return false;
        double r2 = radius * radius;
        for (Location g : geysers) {
            if (g.getWorld() != at.getWorld()) continue;
            double dx = g.getX() + 0.5 - at.getX(), dz = g.getZ() + 0.5 - at.getZ(), dy = g.getY() - at.getY();
            if (dx * dx + dz * dz <= r2 && Math.abs(dy) <= 3) return true;
        }
        return false;
    }

    /** Admin: forget the found geysers so the next tick searches again. */
    static void reset() { FOUND.clear(); LAST_SCAN.clear(); }

    /** Lava level of the crater from crimson's layout data "rimY,lavaLevel", or Integer.MIN_VALUE. */
    static int lavaLevel(Layout l) {
        if (l == null) return Integer.MIN_VALUE;
        String v = l.data().get("crimson.crater");
        if (v == null) return Integer.MIN_VALUE;
        String[] p = v.split(",");
        try { return Integer.parseInt(p[p.length - 1].trim()); } catch (RuntimeException e) { return Integer.MIN_VALUE; }
    }

    // ---- the search (loaded chunks only) --------------------------------------------------------

    private static void scan(String island) {
        long now = System.currentTimeMillis();
        Long last = LAST_SCAN.get(island);
        if (last != null && now - last < 30_000) return;
        LAST_SCAN.put(island, now);
        World w = Sky.worlds().world(island);
        Layout l = Sky.worlds().layout(island);
        Box area = Trophies.volcanoArea(island);
        if (w == null || l == null || area == null) return;
        int level = lavaLevel(l);
        List<int[]> cand = new ArrayList<>();
        Map<Long, Integer> topY = new HashMap<>();
        for (int x = area.minX(); x <= area.maxX(); x += 2) {
            for (int z = area.minZ(); z <= area.maxZ(); z += 2) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = level != Integer.MIN_VALUE ? level : surfaceLava(w, x, z, area);
                if (y == Integer.MIN_VALUE) continue;
                if (w.getBlockAt(x, y, z).getType() != Material.LAVA || !w.getBlockAt(x, y + 1, z).getType().isAir()) continue;
                // a geyser needs lava round it, not a lava lip on the shore
                if (w.getBlockAt(x + 1, y, z).getType() != Material.LAVA || w.getBlockAt(x - 1, y, z).getType() != Material.LAVA
                        || w.getBlockAt(x, y, z + 1).getType() != Material.LAVA || w.getBlockAt(x, y, z - 1).getType() != Material.LAVA) continue;
                cand.add(new int[]{x, z});
                topY.put(((long) x << 32) ^ (z & 0xffffffffL), y);
            }
        }
        List<int[]> picked = FishMath.pickSites(cand, Math.max(0, FishDials.GEYSER_COUNT.get()), FishDials.GEYSER_SPACING.get());
        List<Location> out = new ArrayList<>();
        for (int[] c : picked) out.add(new Location(w, c[0], topY.get(((long) c[0] << 32) ^ (c[1] & 0xffffffffL)), c[1]));
        List<Location> prev = FOUND.get(island);
        if (prev == null || out.size() > prev.size()) {
            FOUND.put(island, out);
            if (!out.isEmpty()) Sky.log().info("[fishing] " + out.size() + " geysers found in the " + island + " volcano lava");
        }
    }

    /** Highest lava block of a column inside the area (used when the crater has no published lava level). */
    private static int surfaceLava(World w, int x, int z, Box area) {
        for (int y = Math.min(area.maxY(), w.getMaxHeight() - 2); y >= Math.max(area.minY(), w.getMinHeight()); y--) {
            Material m = w.getBlockAt(x, y, z).getType();
            if (m == Material.LAVA) return y;
            if (!m.isAir()) return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    // ---- live -----------------------------------------------------------------------------------

    private static void tick() {
        if (!FishDials.ENABLED.get() || !FishDials.GEYSERS.get()) return;
        ticks += 10;
        World w = Sky.worlds().world(ISLAND);
        if (w == null || w.getPlayers().isEmpty()) return;
        List<Location> sites = sites(ISLAND);
        if (sites.size() < FishDials.GEYSER_COUNT.get()) scan(ISLAND);
        sites = sites(ISLAND);
        if (sites.isEmpty()) return;
        boolean gushing = Trophies.gusherWindow();
        int every = Math.max(5, FishDials.GEYSER_SPOUT_SECONDS.get()) * 20;
        for (int i = 0; i < sites.size(); i++) {
            Location g = sites.get(i);
            if (!w.isChunkLoaded(g.getBlockX() >> 4, g.getBlockZ() >> 4)) continue;
            boolean watched = false;
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(g) < 48 * 48) { watched = true; break; }
            if (!watched) continue;
            Location top = g.clone().add(0.5, 1.0, 0.5);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, top, gushing ? 4 : 2, 0.2, 0.2, 0.2, 0.01);
            long offset = ((long) i * every / Math.max(1, sites.size())) / 10 * 10;   // staggered, on the 10-tick grid
            long phase = Math.floorMod(ticks + offset, (long) every / 10 * 10);
            if (phase < 60) spout(w, g, top, phase == 0);
        }
    }

    private static void spout(World w, Location g, Location top, boolean first) {
        w.spawnParticle(Particle.CLOUD, top.clone().add(0, 1.5, 0), 12, 0.25, 1.6, 0.25, 0.02);
        w.spawnParticle(Particle.LAVA, top, 2, 0.2, 0.1, 0.2, 0);
        if (first) w.playSound(top, Sound.BLOCK_FIRE_EXTINGUISH, 0.8f, 0.6f);
        if (!FishDials.GEYSER_LAUNCH.get()) return;
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            double dx = l.getX() - top.getX(), dz = l.getZ() - top.getZ(), dy = l.getY() - g.getY();
            if (dx * dx + dz * dz > 2.25 || dy < -0.5 || dy > 6) continue;
            if (p.getVelocity().getY() > 0.6) continue;
            p.setVelocity(p.getVelocity().setY(1.1).add(new Vector(dx * 0.1, 0, dz * 0.1)));
            p.setFallDistance(0);
        }
    }

    /** "5 geysers (found)" / "3 geysers (anchors)" / "none found yet". */
    static String describe(String island) {
        List<Location> a;
        try { a = Sky.worlds().anchors(island, "geyser"); } catch (RuntimeException e) { a = List.of(); }
        if (a != null && !a.isEmpty()) return a.size() + " geysers (anchors)";
        List<Location> f = FOUND.get(island);
        return f == null || f.isEmpty() ? "none found yet (the volcano's chunks are not loaded)" : f.size() + " geysers (found in the lava)";
    }
}
