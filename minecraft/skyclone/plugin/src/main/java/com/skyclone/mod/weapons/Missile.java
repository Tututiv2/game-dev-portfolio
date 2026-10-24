package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A scripted projectile: moved by us every tick, so no vanilla projectile ever touches the damage
 * pipeline. Collides with blocks (ray trace) and with sky mobs (hit radius), shows particles and,
 * optionally, a floating item. Removes its display on every exit path, including plugin disable.
 */
final class Missile extends BukkitRunnable {

    private static final Set<Missile> LIVE = new HashSet<>();

    final Player owner;
    Location pos;
    Vector vel;
    double gravity;
    double hitRadius = 0.9;
    int life = 60;
    int pierce;
    Particle particle = Particle.CRIT;
    int particleCount = 2;
    /** Steer toward where the owner looks each tick (Spirit Bat). */
    boolean guided;
    /** Fly out {@code returnAfter} ticks then come back to the owner (Bone Toss, Boomerang Throw). */
    int returnAfter = -1;
    private boolean returning;
    BiConsumer<LivingEntity, Boolean> onHit = (m, back) -> {};
    Consumer<Location> onLand = l -> {};
    private final Set<UUID> hit = new HashSet<>();
    private ItemDisplay display;
    private int age;
    private boolean done;

    Missile(Player owner, Location from, Vector vel) {
        this.owner = owner;
        this.pos = from.clone();
        this.vel = vel.clone();
    }

    Missile item(Material m) {
        try {
            display = pos.getWorld().spawn(pos, ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(m));
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setTeleportDuration(1);
            });
        } catch (RuntimeException ignored) { display = null; }
        return this;
    }

    Missile launch() {
        LIVE.add(this);
        runTaskTimer(Sky.plugin(), 0L, 1L);
        return this;
    }

    @Override
    public void run() {
        if (done) return;
        if (!owner.isOnline() || pos.getWorld() == null || ++age > life) { land(pos); return; }
        if (guided) {
            Vector want = owner.getEyeLocation().getDirection().multiply(vel.length());
            vel = vel.multiply(0.6).add(want.multiply(0.4));
        }
        if (returnAfter >= 0 && age == returnAfter) { returning = true; hit.clear(); }
        if (returning) {
            Vector home = owner.getEyeLocation().toVector().subtract(pos.toVector());
            if (home.lengthSquared() < 2.0) { finish(); return; }
            vel = home.normalize().multiply(Math.max(0.8, vel.length()));
        }
        vel.setY(vel.getY() - gravity);
        double len = vel.length();
        if (len > 1e-6 && !returning) {
            RayTraceResult r = pos.getWorld().rayTraceBlocks(pos, vel, len);
            if (r != null) {
                Location at = r.getHitPosition().toLocation(pos.getWorld());
                checkEntities(at);
                if (returnAfter >= 0) { returning = true; hit.clear(); return; }
                land(at);
                return;
            }
        }
        int steps = Math.max(1, (int) Math.ceil(len / 0.5));
        Vector step = vel.clone().multiply(1.0 / steps);
        for (int i = 0; i < steps && !done; i++) {
            pos.add(step);
            checkEntities(pos);
        }
        if (done) return;
        if (particle != null) pos.getWorld().spawnParticle(particle, pos, particleCount, 0.05, 0.05, 0.05, 0);
        if (display != null && display.isValid()) display.teleport(pos);
    }

    private void checkEntities(Location at) {
        for (LivingEntity m : Fx.mobsNear(at, hitRadius)) {
            if (!hit.add(m.getUniqueId())) continue;
            try { onHit.accept(m, returning); } catch (RuntimeException e) { Sky.log().warning("[weapons] missile hit threw " + e); }
            if (returnAfter < 0 && pierce-- <= 0) { land(at); return; }
        }
    }

    private void land(Location at) {
        if (done) return;
        try { onLand.accept(at); } catch (RuntimeException e) { Sky.log().warning("[weapons] missile landing threw " + e); }
        finish();
    }

    private void finish() {
        done = true;
        LIVE.remove(this);
        if (display != null && display.isValid()) display.remove();
        try { cancel(); } catch (IllegalStateException ignored) { }
    }

    static void removeAll() {
        for (Missile m : Set.copyOf(LIVE)) m.finish();
    }
}
