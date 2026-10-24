package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fishing Hotspots (research 07 §3.11): rings on the water (lava on the Crimson Isle) that last
 * about four minutes, then move. Fishing inside one grants ONE random buff and lets hotspot sea
 * creatures bite. Where they can appear: the island's {@code fishing.hotspot.*} anchors if it has
 * any, otherwise the liquid surface inside areas/zones whose name sounds like water.
 */
final class Hotspots {

    private Hotspots() {}

    enum Buff {
        FISHING_SPEED(Stat.FISHING_SPEED, 15), SEA_CREATURE_CHANCE(Stat.SEA_CREATURE_CHANCE, 5),
        DOUBLE_HOOK(Stat.DOUBLE_HOOK_CHANCE, 2), TROPHY(Stat.TROPHY_FISH_CHANCE, 5), TREASURE(Stat.TREASURE_CHANCE, 1);
        final Stat stat;
        final double amount;
        Buff(Stat stat, double amount) { this.stat = stat; this.amount = amount; }
        /** "+15 ☂" style, short enough for the sidebar. */
        String shortLabel() {
            return "&" + stat.color + "+" + Text.commas(amount) + (stat.percent ? "%" : "") + " " + stat.symbol;
        }
        String label(double mult) {
            double v = amount * mult;
            return "&" + stat.color + "+" + Text.commas(v) + (stat.percent ? "%" : "") + " " + stat.symbol + " " + stat.display;
        }
    }

    static final class Hotspot {
        final String island;
        final Location center;
        final Buff buff;
        final boolean lava;
        final long expires;
        UUID label;
        Hotspot(String island, Location center, Buff buff, boolean lava, long expires) {
            this.island = island; this.center = center; this.buff = buff; this.lava = lava; this.expires = expires;
        }
        long secondsLeft() { return Math.max(0, (expires - System.currentTimeMillis()) / 1000); }
    }

    private static final List<Hotspot> LIVE = new ArrayList<>();
    private static final String[] WATER_WORDS = {"pond", "lake", "water", "river", "sea", "lagoon", "marsh", "fish", "bay", "harbor", "harbour", "dock", "lava", "pool", "spring"};
    private static long tick;

    static Collection<Hotspot> live() { return LIVE; }

    static void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Hotspots::tick, 40L, 10L);
    }

    static void stop() {
        for (Hotspot h : LIVE) removeLabel(h);
        LIVE.clear();
    }

    private static void tick() {
        tick++;
        boolean enabled = FishDials.HOTSPOTS.get() && FishDials.ENABLED.get();
        long now = System.currentTimeMillis();
        for (Iterator<Hotspot> it = LIVE.iterator(); it.hasNext(); ) {
            Hotspot h = it.next();
            if (!enabled || now >= h.expires || h.center.getWorld() == null) {
                removeLabel(h);
                it.remove();
                if (enabled && h.center.getWorld() != null && now >= h.expires) {
                    for (Player p : h.center.getWorld().getPlayers())
                        if (p.getLocation().distanceSquared(h.center) < 64 * 64)
                            com.skyclone.api.ui.Msg.send(p, "&dThe Fishing Hotspot nearby has moved on.");
                }
            }
        }
        if (!enabled) return;
        if (tick % 2 == 0) for (Hotspot h : LIVE) particles(h);
        if (tick % 10 != 0) return;
        for (Map.Entry<String, Integer> e : Fishing.CAT.hotspots.entrySet()) {
            String island = e.getKey();
            World w;
            try { w = Sky.worlds().isBuilt(island) ? Bukkit.getWorld("sb_" + island) : null; } catch (RuntimeException ex) { w = null; }
            if (w == null || w.getPlayers().isEmpty()) continue;
            int want = (int) Math.max(0, Math.round(e.getValue() * FishDials.HOTSPOT_COUNT_MULT.get()));
            int have = 0;
            for (Hotspot h : LIVE) if (h.island.equals(island)) have++;
            for (int i = have; i < want; i++) {
                Hotspot h = spawn(island, w);
                if (h == null) break;
            }
        }
    }

    /** Spawn one hotspot on an island now; null when no liquid surface could be found. */
    static Hotspot spawn(String island, World w) {
        Location at = findSpot(island, w);
        if (at == null) return null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean lava = at.getBlock().getType() == Material.LAVA;
        Buff[] buffs = Buff.values();
        Buff b = buffs[r.nextInt(buffs.length)];
        if (!lava && b == Buff.TROPHY) b = Buff.SEA_CREATURE_CHANCE;   // trophy fish only bite in lava
        long life = FishDials.HOTSPOT_LIFETIME.get() * 1000L;
        Hotspot h = new Hotspot(island, at.clone().add(0.5, 0, 0.5), b, lava, System.currentTimeMillis() + life);
        label(h);
        LIVE.add(h);
        return h;
    }

    private static Location findSpot(String island, World w) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<Location> anchors = new ArrayList<>();
        try { anchors.addAll(Sky.worlds().anchors(island, "fishing.hotspot.")); } catch (RuntimeException ignored) { }
        try { anchors.addAll(Sky.worlds().anchors(island, "hotspot.")); } catch (RuntimeException ignored) { }
        List<Box> boxes = new ArrayList<>();
        Layout lay = Sky.worlds().layout(island);
        if (lay != null) {
            for (Map.Entry<String, Box> a : lay.areas().entrySet()) if (watery(a.getKey())) boxes.add(a.getValue());
            if (boxes.isEmpty()) for (Layout.Zone z : lay.zones()) if (watery(z.id() + " " + z.displayName())) boxes.add(z.box());
        }
        for (int attempt = 0; attempt < 40; attempt++) {
            Location c = null;
            if (!anchors.isEmpty()) {
                Location a = anchors.get(r.nextInt(anchors.size()));
                c = surface(w, a.getBlockX(), a.getBlockZ(), a.getBlockY() + 3, a.getBlockY() - 6);
            } else if (!boxes.isEmpty()) {
                Box b = boxes.get(r.nextInt(boxes.size()));
                int x = b.minX() + r.nextInt(Math.max(1, b.maxX() - b.minX() + 1));
                int z = b.minZ() + r.nextInt(Math.max(1, b.maxZ() - b.minZ() + 1));
                c = surface(w, x, z, Math.min(w.getMaxHeight() - 1, b.maxY() + 4), Math.max(w.getMinHeight(), b.minY() - 6));
            }
            if (c == null) continue;
            if (tooClose(c)) continue;
            return c;
        }
        return null;
    }

    private static boolean watery(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String k : WATER_WORDS) if (n.contains(k)) return true;
        return false;
    }

    /** Topmost liquid block in the column with air above it and liquid around it; null if none. */
    static Location surface(World w, int x, int z, int top, int bottom) {
        // Never load a chunk to look for water: this runs every few seconds per island, on the main thread.
        for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2)
            if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return null;
        for (int y = top; y >= bottom; y--) {
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m != Material.WATER && m != Material.LAVA) continue;
            Material above = b.getRelative(0, 1, 0).getType();
            if (!above.isAir() && above != Material.LILY_PAD) return null;
            int liquid = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (b.getRelative(dx, 0, dz).getType() == m) liquid++;
            return liquid >= 7 ? b.getLocation() : null;
        }
        return null;
    }

    private static boolean tooClose(Location c) {
        for (Hotspot h : LIVE) if (h.center.getWorld() == c.getWorld() && h.center.distanceSquared(c) < 12 * 12) return true;
        return false;
    }

    private static void label(Hotspot h) {
        try {
            Location at = h.center.clone().add(0, 1.6, 0);
            TextDisplay td = at.getWorld().spawn(at, TextDisplay.class, t -> {
                t.text(Text.c("&d&lHOTSPOT\n" + h.buff.label(1)));
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(false);
                t.setSeeThrough(false);
                t.setShadowed(true);
            });
            h.label = td.getUniqueId();
        } catch (RuntimeException ignored) { }
    }

    private static void removeLabel(Hotspot h) {
        if (h.label == null) return;
        Entity e = Bukkit.getEntity(h.label);
        if (e != null) e.remove();
        h.label = null;
    }

    private static void particles(Hotspot h) {
        World w = h.center.getWorld();
        if (w == null) return;
        boolean near = false;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(h.center) < 48 * 48) { near = true; break; }
        if (!near) return;
        int n = Math.max(6, FishDials.TRAIL_PARTICLES.get());
        double r = FishDials.HOTSPOT_RADIUS.get();
        double phase = (tick % 40) / 40.0 * Math.PI * 2;
        Particle.DustOptions dust = new Particle.DustOptions(h.lava ? Color.fromRGB(255, 120, 30) : Color.fromRGB(255, 90, 200), 1.2f);
        for (int i = 0; i < n; i++) {
            double a = phase + i * Math.PI * 2 / n;
            Location p = h.center.clone().add(Math.cos(a) * r, 1.05, Math.sin(a) * r);
            w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
        }
        w.spawnParticle(h.lava ? Particle.LAVA : Particle.BUBBLE_POP, h.center.clone().add(0, 1.0, 0), 2, r / 2, 0.05, r / 2, 0);
    }

    /** The hotspot a bobber is in, or null. */
    static Hotspot at(Location bobber) {
        if (bobber == null) return null;
        double r = FishDials.HOTSPOT_RADIUS.get();
        for (Hotspot h : LIVE) {
            if (h.center.getWorld() != bobber.getWorld()) continue;
            double dx = h.center.getX() - bobber.getX(), dz = h.center.getZ() - bobber.getZ();
            if (dx * dx + dz * dz <= r * r && Math.abs(h.center.getY() + 1 - bobber.getY()) < 3) return h;
        }
        return null;
    }

    static Hotspot nearest(Location from) {
        Hotspot best = null;
        double bd = Double.MAX_VALUE;
        for (Hotspot h : LIVE) {
            if (h.center.getWorld() != from.getWorld()) continue;
            double d = h.center.distanceSquared(from);
            if (d < bd) { bd = d; best = h; }
        }
        return best;
    }

    static int clear(String island) {
        int n = 0;
        for (Iterator<Hotspot> it = LIVE.iterator(); it.hasNext(); ) {
            Hotspot h = it.next();
            if (island == null || h.island.equals(island)) { removeLabel(h); it.remove(); n++; }
        }
        return n;
    }
}
