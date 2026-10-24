package com.skyclone.mod.hunting;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps critters alive in their habitats (an island + a named area of its layout) while a player is near.
 * Each habitat holds up to {@code hunting.critter-cap}; which critter comes is weighted by its shard's rarity.
 * Critters leave when nobody has been near for {@code hunting.critter-despawn-seconds}. Not core spawn rules:
 * one rule per critter would be 247 rules each wanting its own cap; this keeps ONE cap per habitat.
 */
final class Critters {

    private Critters() {}

    static NamespacedKey SPARKLING;
    static NamespacedKey HABITAT;

    private static final class Live {
        final UUID id;
        final String habitat;
        /** A Water (net) critter: kept shallow and near {@link #home} so a net reaches it (see {@link #keepShallow}). */
        final boolean water;
        final Location home;
        long lastNear = System.currentTimeMillis();
        Live(UUID id, String habitat, boolean water, Location home) {
            this.id = id; this.habitat = habitat; this.water = water; this.home = home;
        }
    }

    private static final Map<UUID, Live> LIVE = new HashMap<>();
    private static BukkitTask task;
    private static long nextPass;

    static void start() {
        SPARKLING = new NamespacedKey(Sky.plugin(), "hunting_sparkling");
        HABITAT = new NamespacedKey(Sky.plugin(), "hunting_habitat");
        task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Critters::tick, 40L, 20L);
    }

    static void stop() {
        if (task != null) task.cancel();
        for (Live l : new ArrayList<>(LIVE.values())) {
            var e = Bukkit.getEntity(l.id);
            if (e instanceof LivingEntity le && le.isValid()) Sky.mobs().remove(le);
        }
        LIVE.clear();
    }

    static int live(String habitat) {
        int n = 0;
        for (Live l : LIVE.values()) if (l.habitat.equals(habitat)) n++;
        return n;
    }

    static int liveTotal() { return LIVE.size(); }

    static boolean isCritter(LivingEntity e) {
        MobDef d = e == null ? null : Sky.mobs().defOf(e);
        return d != null && d.extra != null && d.extra.has("critter");
    }

    static boolean sparkling(LivingEntity e) {
        return e != null && e.getPersistentDataContainer().has(SPARKLING, PersistentDataType.BYTE);
    }

    static void forget(UUID id) { LIVE.remove(id); }

    private static void tick() {
        long now = System.currentTimeMillis();
        // despawn / prune
        double despawn = HDials.CRITTER_DESPAWN.get() * 1000;
        for (Iterator<Live> it = LIVE.values().iterator(); it.hasNext(); ) {
            Live l = it.next();
            var e = Bukkit.getEntity(l.id);
            if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) { it.remove(); continue; }
            if (anyPlayerNear(le.getLocation(), 64)) l.lastNear = now;
            else if (now - l.lastNear > despawn) { Sky.mobs().remove(le); it.remove(); continue; }
            if (l.water) {
                try { keepShallow(le, l); } catch (RuntimeException ex) { Sky.log().fine("[hunting] keep " + l.habitat + ": " + ex); }
            }
        }
        if (!HDials.CRITTERS.get() || now < nextPass) return;
        nextPass = now + (long) (Math.max(0.5, HDials.CRITTER_INTERVAL.get()) * 1000);
        for (ShardCatalog.Habitat h : Hunt.cat.habitats()) {
            try { pass(h); } catch (RuntimeException ex) { Sky.log().fine("[hunting] habitat " + h.id + ": " + ex); }
        }
    }

    private static void pass(ShardCatalog.Habitat h) {
        if (h.critters.isEmpty()) return;
        World w = Sky.worlds().world(h.island);
        if (w == null || w.getPlayers().isEmpty()) return;
        Box b = Sky.worlds().area(h.island, h.area);
        if (b == null) return;
        Location c = new Location(w, b.centerX() + 0.5, b.centerY(), b.centerZ() + 0.5);
        double reach = Math.max(b.maxX() - b.minX(), b.maxZ() - b.minZ()) / 2.0 + 40;
        boolean near = false;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) <= reach * reach) { near = true; break; }
        if (!near) return;
        if (live(h.id) >= HDials.CRITTER_CAP.get()) return;
        String mob = pick(h);
        if (mob == null || Sky.mobs().def(mob) == null) return;
        boolean water = "water".equals(h.kind);
        // Water critters appear within one net throw of a hunter in (or at the edge of) the habitat, so a player on
        // a pier is not waiting for one to drift in from 40 blocks out (playtest wave 5). Land critters keep the
        // whole-area spread: a lasso walks to them.
        Location at = water ? waterNearHunter(w, b) : null;
        if (at == null) at = spot(w, b, water);
        if (at == null) return;
        spawn(mob, at, h);
    }

    static LivingEntity spawn(String mob, Location at, ShardCatalog.Habitat h) {
        LivingEntity le;
        try { le = Sky.mobs().spawn(mob, at); } catch (RuntimeException e) { return null; }
        if (le == null) return null;
        boolean water = h != null ? "water".equals(h.kind) : isNetCritter(le);
        LIVE.put(le.getUniqueId(), new Live(le.getUniqueId(), h == null ? "admin" : h.id, water, at.clone()));
        le.getPersistentDataContainer().set(HABITAT, PersistentDataType.STRING, h == null ? "admin" : h.id);
        if (le instanceof Mob m) m.setRemoveWhenFarAway(false);
        if (h != null && h.safari && ThreadLocalRandom.current().nextDouble() < HDials.SPARKLING.get()) {
            le.getPersistentDataContainer().set(SPARKLING, PersistentDataType.BYTE, (byte) 1);
            le.setGlowing(true);
            MobDef d = Sky.mobs().defOf(le);
            Sky.mobs().setDisplayName(le, "&d✦ Sparkling " + (d == null ? "Critter" : d.name));
        }
        return le;
    }

    private static String pick(ShardCatalog.Habitat h) {
        int total = 0;
        int[] w = new int[h.critters.size()];
        for (int i = 0; i < w.length; i++) {
            ShardDef s = Hunt.cat.byCritter(h.critters.get(i));
            w[i] = s == null ? 0 : HuntMath.spawnWeight(s.rarity);
            total += w[i];
        }
        if (total <= 0) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (int i = 0; i < w.length; i++) { r -= w[i]; if (r < 0) return h.critters.get(i); }
        return h.critters.get(0);
    }

    static boolean isNetCritter(LivingEntity e) {
        MobDef d = e == null ? null : Sky.mobs().defOf(e);
        return d != null && d.extra != null && d.extra.has("method") && "net".equals(d.extra.get("method").getAsString());
    }

    /**
     * A swimmable spot {@link HuntMath#WATER_SPAWN_MIN}..{@link HuntMath#WATER_SPAWN_MAX} blocks (horizontally) from
     * a random player standing in or beside the habitat, one block under the surface and inside the habitat box
     * (grown a little so a pier along its edge counts). Null when no such player or no water there.
     */
    static Location waterNearHunter(World w, Box b) {
        Box near = b.grow(8);
        List<Player> hunters = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (near.contains(l.getX(), l.getY(), l.getZ())) hunters.add(p);
        }
        if (hunters.isEmpty()) return null;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location pl = hunters.get(r.nextInt(hunters.size())).getLocation();
        Box inside = b.grow(2);
        for (int t = 0; t < 16; t++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double d = HuntMath.WATER_SPAWN_MIN + r.nextDouble() * (HuntMath.WATER_SPAWN_MAX - HuntMath.WATER_SPAWN_MIN);
            int x = (int) Math.floor(pl.getX() + Math.cos(ang) * d), z = (int) Math.floor(pl.getZ() + Math.sin(ang) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int top = Math.min(w.getMaxHeight() - 2, Math.max(b.maxY() + 1, pl.getBlockY() + 3));
            int bottom = Math.max(w.getMinHeight() + 1, b.minY() - 1);
            for (int y = top; y >= bottom; y--) {
                Material m = w.getBlockAt(x, y, z).getType();
                if (m == Material.WATER) {
                    // the surface: need two blocks of water so it swims rather than flops
                    if (w.getBlockAt(x, y - 1, z).getType() == Material.WATER && inside.contains(x + 0.5, y, z + 0.5))
                        return new Location(w, x + 0.5, y - 0.9, z + 0.5, r.nextFloat() * 360f, 0f);
                    break;
                }
                if (!m.isAir() && !org.bukkit.Tag.LEAVES.isTagged(m) && m.isSolid()) break;   // a pier deck, a rock: not water
            }
        }
        return null;
    }

    /**
     * Holds a Water critter where a net can reach it: pushed up when it dives past {@code hunting.water-critter-depth},
     * steered back when it wanders past {@code hunting.water-critter-leash} from where it appeared, and put back
     * outright when nudging is not enough (squid AI overwrites its own velocity every tick). Vanilla fish dive 8-12
     * blocks and cross a whole lagoon, which is what made a 7-block net useless from the shore.
     */
    private static void keepShallow(LivingEntity le, Live l) {
        if (le.isLeashed()) return;
        Location at = le.getLocation();
        Block b = at.getBlock();
        if (b.getType() != Material.WATER) return;   // beached / admin-spawned on land: leave it be
        int depth = 0;
        Block up = b.getRelative(0, 1, 0);
        while (depth < 32 && up.getType() == Material.WATER) { depth++; up = up.getRelative(0, 1, 0); }
        int maxDepth = HDials.WATER_DEPTH.get();
        double leash = HDials.WATER_LEASH.get();
        double dx = 0, dz = 0, dist = 0;
        if (l.home != null && l.home.getWorld() == at.getWorld()) {
            dx = l.home.getX() - at.getX();
            dz = l.home.getZ() - at.getZ();
            dist = Math.sqrt(dx * dx + dz * dz);
        }
        if (HuntMath.snapBack(depth, maxDepth, dist, leash)) {
            Location to;
            if (dist > Math.max(1, leash) * 2 && l.home != null && l.home.getBlock().getType() == Material.WATER) {
                to = l.home.clone();
            } else {
                int topY = up.getY() - 1;   // the highest water block of this column
                to = at.clone();
                to.setY(topY - 0.9);
                if (to.getBlock().getType() != Material.WATER) to.setY(topY + 0.05);
            }
            to.setYaw(at.getYaw());
            to.setPitch(at.getPitch());
            le.teleport(to);
            return;
        }
        double push = HuntMath.surfacePush(depth, maxDepth);
        double pull = dist > 0 ? HuntMath.leashPull(dist, leash) : 0;
        if (push <= 0 && pull <= 0) return;
        Vector v = le.getVelocity();
        if (push > 0) v.setY(Math.max(v.getY(), push));
        if (pull > 0) { v.setX(v.getX() + dx / dist * pull); v.setZ(v.getZ() + dz / dist * pull); }
        le.setVelocity(v);
    }

    /** A random standable (or swimmable) spot inside the box, or null after a few tries. */
    static Location spot(World w, Box b, boolean water) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int t = 0; t < 14; t++) {
            int x = b.minX() + r.nextInt(b.maxX() - b.minX() + 1);
            int z = b.minZ() + r.nextInt(b.maxZ() - b.minZ() + 1);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int top = Math.min(w.getMaxHeight() - 2, b.maxY() + 1);
            int bottom = Math.max(w.getMinHeight() + 1, b.minY() - 1);
            for (int y = top; y >= bottom; y--) {
                Block blk = w.getBlockAt(x, y, z);
                // Playtest wave 5: the first solid block from the top was usually a tree canopy, so land
                // critters stood on the leaves 12 blocks up where no player could see or lasso them.
                if (org.bukkit.Tag.LEAVES.isTagged(blk.getType())) continue;
                if (water) {
                    if (blk.getType() == Material.WATER && w.getBlockAt(x, y - 1, z).getType() == Material.WATER)
                        return new Location(w, x + 0.5, y - 0.5, z + 0.5);
                } else {
                    Block up = blk.getRelative(0, 1, 0), up2 = blk.getRelative(0, 2, 0);
                    if (blk.getType().isSolid() && up.isPassable() && !up.isLiquid() && up2.isPassable() && !up2.isLiquid())
                        return new Location(w, x + 0.5, y + 1, z + 0.5, r.nextFloat() * 360f, 0f);
                }
            }
        }
        return null;
    }

    static boolean anyPlayerNear(Location l, double r) {
        if (l.getWorld() == null) return false;
        double r2 = r * r;
        for (Player p : l.getWorld().getPlayers()) if (p.getLocation().distanceSquared(l) <= r2) return true;
        return false;
    }

    /** Habitats whose box contains (or nearly contains) this location. */
    static List<ShardCatalog.Habitat> habitatsAt(Location l, int margin) {
        List<ShardCatalog.Habitat> out = new ArrayList<>();
        String island = Sky.worlds().islandOf(l.getWorld());
        if (island == null) return out;
        for (ShardCatalog.Habitat h : Hunt.cat.habitats()) {
            if (!island.equals(h.island)) continue;
            Box b = Sky.worlds().area(h.island, h.area);
            if (b != null && b.grow(margin).contains(l.getX(), l.getY(), l.getZ())) out.add(h);
        }
        return out;
    }

    /** A random critter shard of a habitat (traps, fishing). */
    static ShardDef randomShard(ShardCatalog.Habitat h, String alignmentOrNull) {
        List<ShardDef> pool = new ArrayList<>();
        int total = 0;
        for (String c : h.critters) {
            ShardDef s = Hunt.cat.byCritter(c);
            if (s == null || (alignmentOrNull != null && !alignmentOrNull.equals(s.alignment))) continue;
            pool.add(s);
            total += HuntMath.spawnWeight(s.rarity);
        }
        if (pool.isEmpty()) return null;
        int r = ThreadLocalRandom.current().nextInt(total);
        for (ShardDef s : pool) { r -= HuntMath.spawnWeight(s.rarity); if (r < 0) return s; }
        return pool.get(0);
    }

    static Map<String, Integer> counts() {
        Map<String, Integer> m = new HashMap<>();
        for (Live l : LIVE.values()) m.merge(l.habitat, 1, Integer::sum);
        return m;
    }
}
