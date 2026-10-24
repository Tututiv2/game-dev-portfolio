package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.skyclone.mod.armor.SetIds.*;

/**
 * Bat Swarm (wave 5): Witch Mask (4,000) and Vampire Witch Mask (3,000). "Summons two bats that will follow
 * you and attack nearby enemies, dealing X damage upon exploding. Your bats will not spawn on your island."
 *
 * <p>The bats are drawn with particles, not spawned as entities: an entity bat would be a mob the game's
 * own systems (mob caps, bestiary, other players' AoE, despawn rules) would all have to be told to ignore.
 * Each bat circles the wearer's head while ready, flies at the nearest enemy sky mob within range when one
 * appears, explodes on arrival through {@code Sky.combat().magicDamage} (so it is an ability hit like the
 * reference's, with the usual modifiers), and comes back {@code armor.bat-swarm-respawn} seconds later.
 * Runs every 2 ticks for wearers only.</p>
 */
final class BatSwarm {

    private BatSwarm() {}

    /** Per player: two bats. */
    static final class Bat {
        long explodedAt;          // millis; 0 = never
        UUID target;              // null = circling
        double x, y, z;           // world position while flying
        String world;
    }

    private static final Map<UUID, Bat[]> BATS = new HashMap<>();

    static void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), BatSwarm::tick, 60L, 2L);
    }

    static void clear() { BATS.clear(); }

    static void forget(UUID id) { BATS.remove(id); }

    /** The explosion damage of the mask worn (Witch 4,000 beats Vampire Witch 3,000), 0 when neither. */
    static double damageFor(Worn w) {
        if (w.wears(WITCH_MASK)) return ArmorDials.BAT_SWARM_WITCH_DAMAGE.get();
        if (w.wears(VAMPIRE_WITCH_MASK)) return ArmorDials.BAT_SWARM_VAMPIRE_DAMAGE.get();
        return 0;
    }

    static void tick() {
        if (!ArmorDials.ENABLED.get()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            Worn w = Worn.of(p);
            double dmg = damageFor(w);
            if (dmg <= 0 || p.isDead() || "private_island".equals(ArmorUtil.island(p))) {   // "not on your island"
                BATS.remove(p.getUniqueId());
                continue;
            }
            Bat[] bats = BATS.computeIfAbsent(p.getUniqueId(), k -> new Bat[]{new Bat(), new Bat()});
            for (int i = 0; i < bats.length; i++) step(p, bats[i], i, dmg, now);
        }
    }

    private static void step(Player p, Bat b, int index, double dmg, long now) {
        World world = p.getWorld();
        if (b.target != null) {
            Entity e = Bukkit.getEntity(b.target);
            if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead() || !world.getName().equals(b.world)
                    || le.getWorld() != world || le.getLocation().distanceSquared(p.getLocation()) > sq(ArmorDials.BAT_SWARM_RANGE.get() * 2)) {
                b.target = null;    // lost it: fly home (re-ready at once, it never exploded)
                return;
            }
            Location aim = le.getLocation().add(0, le.getHeight() * 0.6, 0);
            double[] at = SetMath.batStep(new double[]{b.x, b.y, b.z}, new double[]{aim.getX(), aim.getY(), aim.getZ()},
                    ArmorDials.BAT_SWARM_SPEED.get());
            b.x = at[0]; b.y = at[1]; b.z = at[2];
            world.spawnParticle(Particle.SQUID_INK, b.x, b.y, b.z, 2, 0.08, 0.08, 0.08, 0);
            if (at[0] == aim.getX() && at[1] == aim.getY() && at[2] == aim.getZ()) {
                b.target = null;
                b.explodedAt = now;
                burst(world, aim);
                world.playSound(aim, Sound.ENTITY_BAT_DEATH, 0.8f, 0.8f);
                world.playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.6f);
                Sky.combat().magicDamage(p, le, dmg, 0, "Bat Swarm");
            }
            return;
        }
        if (!SetMath.batReady(b.explodedAt, now, ArmorDials.BAT_SWARM_RESPAWN.get())) return;
        // ready: circle the head, and dive at the nearest enemy in range
        double t = Bukkit.getCurrentTick() / 6.0 + index * Math.PI;
        Location head = p.getLocation().add(Math.cos(t) * 0.9, 2.1 + Math.sin(t * 2) * 0.15, Math.sin(t) * 0.9);
        world.spawnParticle(Particle.SQUID_INK, head, 1, 0.02, 0.02, 0.02, 0);
        LivingEntity target = nearestEnemy(p, ArmorDials.BAT_SWARM_RANGE.get(), index);
        if (target != null) {
            b.target = target.getUniqueId();
            b.world = world.getName();
            b.x = head.getX(); b.y = head.getY(); b.z = head.getZ();
            world.playSound(head, Sound.ENTITY_BAT_TAKEOFF, 0.6f, 1.2f);
        }
    }

    /**
     * The particles of one bat popping. Bat-sized on purpose: {@code Particle.EXPLOSION} is the TNT blast sprite,
     * a ~4-block billboard drawn at the same size whatever it is attached to, so a bat popping on a mob 1-2 blocks
     * from the wearer covered a third of the screen (playtest 065). A small puff of smoke and ink with a few crit
     * sparks reads as "it burst" from any distance and never fills the view.
     */
    static final Particle[] BURST = {Particle.POOF, Particle.SQUID_INK, Particle.CRIT};

    /** Particles that are drawn huge (or flash the whole view) and must never be a bat's pop. */
    static final Particle[] SCREEN_FILLING = {Particle.EXPLOSION, Particle.EXPLOSION_EMITTER, Particle.FLASH};

    /** Max scatter of the pop, in blocks: half a block either way at most, a bat's worth. */
    static final double BURST_SPREAD = 0.18;

    private static void burst(World world, Location at) {
        for (int i = 0; i < BURST.length; i++)
            world.spawnParticle(BURST[i], at, BURST_COUNT[i], BURST_SPREAD, BURST_SPREAD, BURST_SPREAD, BURST_SPEED[i]);
    }

    private static final int[] BURST_COUNT = {6, 5, 8};
    private static final double[] BURST_SPEED = {0.03, 0.04, 0.25};

    /** Nearest enemy sky mob in range that the OTHER bat is not already chasing. */
    private static LivingEntity nearestEnemy(Player p, double r, int index) {
        Bat[] bats = BATS.get(p.getUniqueId());
        UUID other = bats == null ? null : bats[1 - index].target;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity ent : p.getNearbyEntities(r, r, r)) {
            if (!(ent instanceof LivingEntity le) || ent instanceof Player || !le.isValid() || le.isDead() || !Sky.mobs().isSkyMob(le)) continue;
            if (le.getUniqueId().equals(other)) continue;
            UUID owner = Sky.mobs().owner(le);
            if (owner != null && !owner.equals(p.getUniqueId())) continue;
            MobDef def = ArmorUtil.def(le);
            if (!ArmorUtil.monster(def)) continue;
            double d = le.getLocation().distanceSquared(p.getLocation());
            if (d < bestD) { bestD = d; best = le; }
        }
        return best;
    }

    /** For /sb armor info and /armor me: "2 ready" / "1 ready, 1 back in 7s". */
    static String describe(Player p) {
        Bat[] bats = BATS.get(p.getUniqueId());
        if (bats == null) return "no bats";
        long now = System.currentTimeMillis();
        int ready = 0, flying = 0;
        long next = Long.MAX_VALUE;
        for (Bat b : bats) {
            if (b.target != null) flying++;
            else if (SetMath.batReady(b.explodedAt, now, ArmorDials.BAT_SWARM_RESPAWN.get())) ready++;
            else next = Math.min(next, b.explodedAt + ArmorDials.BAT_SWARM_RESPAWN.get() * 1000L - now);
        }
        return ready + " ready" + (flying > 0 ? ", " + flying + " diving" : "")
                + (next != Long.MAX_VALUE ? ", next back in " + Math.max(1, next / 1000) + "s" : "");
    }

    private static double sq(double v) { return v * v; }
}
