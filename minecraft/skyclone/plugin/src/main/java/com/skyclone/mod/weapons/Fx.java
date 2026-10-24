package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Targeting, movement and visuals shared by every ability. Main thread only. */
final class Fx {

    private Fx() {}

    /**
     * Depth of health-costing ability code running right now (Thorn Seeker, Petal Storm, Eruption): while above 0,
     * every outgoing hit is tagged {@code health_cost} (accessories request 14: Ancient Cloak). Main thread only.
     */
    static int healthCost;

    /** Run {@code r} with outgoing hits tagged {@code health_cost}. */
    static void healthCost(Runnable r) {
        healthCost++;
        try { r.run(); } finally { healthCost--; }
    }

    /**
     * Depth of explosion code running right now (explosive bows, Charged Tip, Crypt bow skulls): while above 0, every
     * outgoing hit is tagged {@code explosion} so explosion-damage effects (accessories' Implosion Belt) can see it.
     */
    static int explosion;

    /** Run {@code r} with outgoing hits tagged {@code explosion}. */
    static void explosion(Runnable r) {
        explosion++;
        try { r.run(); } finally { explosion--; }
    }

    /** A sky mob this player may hit (not an NPC, armour stand or another player). */
    static boolean target(Entity e) {
        if (!(e instanceof LivingEntity le) || e instanceof Player || !le.isValid() || le.isDead()) return false;
        try { return Sky.mobs().isSkyMob(le); } catch (RuntimeException ex) { return false; }
    }

    static List<LivingEntity> mobsNear(Location at, double r) {
        List<LivingEntity> out = new ArrayList<>();
        if (at == null || at.getWorld() == null) return out;
        double r2 = r * r;
        for (Entity e : at.getWorld().getNearbyEntities(at, r, r, r)) {
            if (!target(e)) continue;
            Location c = ((LivingEntity) e).getLocation().add(0, ((LivingEntity) e).getHeight() / 2, 0);
            if (c.distanceSquared(at) <= r2 + ((LivingEntity) e).getWidth()) out.add((LivingEntity) e);
        }
        out.sort(Comparator.comparingDouble(m -> m.getLocation().distanceSquared(at)));
        return out;
    }

    /** Mobs in front of the player: horizontal cone of half-angle {@code halfDeg}, within {@code range}. */
    static List<LivingEntity> cone(Player p, double range, double halfDeg) {
        Location eye = p.getEyeLocation();
        Vector f = eye.getDirection();
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity m : mobsNear(p.getLocation(), range)) {
            Location c = m.getLocation();
            if (WeaponMath.inCone(c.getX() - eye.getX(), c.getZ() - eye.getZ(), f.getX(), f.getZ(), halfDeg)
                    && Math.abs(c.getY() - p.getLocation().getY()) < range * 0.6 + 2) out.add(m);
        }
        return out;
    }

    /** Mobs within {@code width} of the segment from {@code from} along {@code dir} for {@code length} blocks. */
    static List<LivingEntity> line(Location from, Vector dir, double length, double width) {
        Vector d = dir.clone().normalize();
        Location to = from.clone().add(d.clone().multiply(length));
        Location mid = from.clone().add(d.clone().multiply(length / 2));
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity m : mobsNear(mid, length / 2 + width + 1)) {
            Location c = m.getLocation().add(0, m.getHeight() / 2, 0);
            double dist = WeaponMath.distToSegment(c.getX(), c.getY(), c.getZ(), from.getX(), from.getY(), from.getZ(),
                    to.getX(), to.getY(), to.getZ());
            if (dist <= width + m.getWidth() / 2) out.add(m);
        }
        out.sort(Comparator.comparingDouble(m -> m.getLocation().distanceSquared(from)));
        return out;
    }

    /** The sky mob the player is looking at, within range (ray trace through entities). */
    static LivingEntity looking(Player p, double range) {
        RayTraceResult r = p.getWorld().rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, 0.6,
                e -> e != p && target(e));
        if (r != null && r.getHitEntity() instanceof LivingEntity le) return le;
        return null;
    }

    /** Where the player aims: the block hit (on its face) or the point {@code range} ahead. */
    static Location aim(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), range);
        if (r != null) return r.getHitPosition().toLocation(p.getWorld()).subtract(eye.getDirection().multiply(0.3));
        return eye.clone().add(eye.getDirection().multiply(range));
    }

    /**
     * Blink forward up to {@code distance} blocks, stopping before anything solid at feet or head height.
     * Returns false when there was no room at all.
     */
    static boolean blink(Player p, double distance) {
        Location start = p.getLocation();
        Vector dir = start.getDirection().normalize();
        Location best = null;
        for (double s = 0.5; s <= distance + 1e-6; s += 0.5) {
            Location l = start.clone().add(dir.clone().multiply(s));
            if (!passable(l.getBlock()) || !passable(l.clone().add(0, 1, 0).getBlock())) break;
            best = l;
        }
        if (best == null) return false;
        best.setYaw(start.getYaw());
        best.setPitch(start.getPitch());
        p.setFallDistance(0);
        p.teleport(best);
        p.setFallDistance(0);
        p.getWorld().playSound(best, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        return true;
    }

    static boolean passable(Block b) { return b.isPassable(); }

    /** Stand behind {@code target}, facing it. */
    static void behind(Player p, LivingEntity target) {
        Location t = target.getLocation();
        Vector back = t.getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = p.getLocation().toVector().subtract(t.toVector()).setY(0).multiply(-1);
        back.normalize().multiply(-1.2);
        Location dest = t.clone().add(back);
        if (!passable(dest.getBlock()) || !passable(dest.clone().add(0, 1, 0).getBlock())) dest = t.clone();
        Vector look = t.toVector().subtract(dest.toVector());
        dest.setDirection(look.lengthSquared() < 1e-4 ? p.getLocation().getDirection() : look);
        p.setFallDistance(0);
        p.teleport(dest);
    }

    // ---- damage helpers ---------------------------------------------------------------------------

    /** Ability damage through the core pipeline, scaled by the global ability dial. */
    static double magic(Player p, LivingEntity v, double base, double scaling, String name) {
        return Sky.combat().magicDamage(p, v, base * WeaponDials.ABILITY_DAMAGE.get(), scaling, name);
    }

    /** A melee hit from code (abilities that "hit with your melee damage"). */
    static double melee(Player p, LivingEntity v, ItemStack weapon, double multiplier) {
        return Sky.combat().playerAttack(p, v, weapon, DamageContext.Kind.MELEE, multiplier);
    }

    static void knockback(LivingEntity v, Location from, double strength, double up) {
        Vector k = v.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (k.lengthSquared() < 1e-4) k = new Vector(0, 0, 0);
        else k.normalize().multiply(strength);
        k.setY(up);
        v.setVelocity(k);
    }

    // ---- visuals ---------------------------------------------------------------------------------

    static void ring(Location c, double r, Particle p, int points) {
        World w = c.getWorld();
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            w.spawnParticle(p, c.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r), 1, 0, 0, 0, 0);
        }
    }

    static void dust(Location at, Color c, float size, int count, double spread) {
        at.getWorld().spawnParticle(Particle.DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(c, size));
    }

    static void burst(Location at, Particle p, int count, double spread) {
        at.getWorld().spawnParticle(p, at, count, spread, spread, spread, 0.02);
    }

    static void beam(Location from, Vector dir, double length, Particle p) {
        Vector step = dir.clone().normalize().multiply(0.4);
        Location l = from.clone();
        for (double s = 0; s < length; s += 0.4) {
            l.add(step);
            l.getWorld().spawnParticle(p, l, 1, 0, 0, 0, 0);
        }
    }

    static void sound(Location at, Sound s, float vol, float pitch) { at.getWorld().playSound(at, s, vol, pitch); }
}
