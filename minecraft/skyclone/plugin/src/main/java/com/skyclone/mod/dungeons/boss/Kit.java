package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.ext.BossContext;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The boss fights' shared toolkit (research 04 sec 6.1): telegraphed circles that land after a warning, particle
 * projectiles that stop at walls (so pillars are cover), percent-of-max-HP true damage, timed dialogue, a per-run
 * effect queue that dies with the run, boss bars with HP, and small entity helpers. Main thread only.
 *
 * <p>Every scripted hit goes through {@code Sky.combat().damagePlayer} with no source entity, so the run's per-mob
 * damage multiplier is NOT applied a second time: the numbers passed in are already the floor's numbers.
 */
public final class Kit {

    private Kit() {}

    /** One queued effect; {@link #tick} returns false when it is finished. */
    public interface Effect { boolean tick(); }

    private record Task(String runId, Effect effect) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static boolean started;

    public static void init() {
        if (started) return;
        started = true;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), Kit::tickAll, 1L, 1L);
    }

    private static void tickAll() {
        if (TASKS.isEmpty()) return;
        List<Task> copy = new ArrayList<>(TASKS);
        List<Task> done = new ArrayList<>();
        for (Task t : copy) {
            boolean keep;
            try { keep = live(t.runId) && t.effect.tick(); }
            catch (RuntimeException e) { keep = false; Sky.log().warning("[dungeons] boss effect failed: " + e); }
            if (!keep) done.add(t);
        }
        TASKS.removeAll(done);
    }

    /** True while the run exists and is fighting its boss. */
    public static boolean live(String runId) {
        DungeonRun r = RunManager.get() == null ? null : RunManager.get().run(runId);
        return r != null && r.state == DungeonRun.State.BOSS;
    }

    public static boolean live(BossContext c) { return live(c.runId()); }

    public static void add(BossContext c, Effect e) { TASKS.add(new Task(c.runId(), e)); }

    /** Drop every queued effect of a run (BossFloor.stop). */
    public static void clear(String runId) { TASKS.removeIf(t -> t.runId.equals(runId)); }

    /** Run something after {@code ticks}, only if the fight is still on. */
    public static void later(BossContext c, int ticks, Runnable r) {
        final int[] left = {Math.max(0, ticks)};
        add(c, () -> {
            if (left[0]-- > 0) return true;
            r.run();
            return false;
        });
    }

    /** "[BOSS] Name: line" lines, 1.6 s apart, then {@code then} (may be null). */
    public static void dialogue(BossContext c, String name, List<String> lines, Runnable then) {
        int t = 0;
        for (String l : lines) {
            final String line = l;
            later(c, t, () -> c.bossSay(name, line));
            t += 32;
        }
        if (then != null) later(c, t, then);
    }

    // ---- numbers ---------------------------------------------------------------------------------

    /** Normal or Master Mode value. */
    public static double nm(BossContext c, double normal, double master) { return c.master() ? master : normal; }

    public static double hpScale() { return DDials.BOSS_FIGHT_HP.get(); }

    public static double dmgScale() { return DDials.BOSS_FIGHT_DMG.get(); }

    /** A boss body's contact hit as a share of the floor's headline damage ({@code dungeons.boss-melee-share}). */
    public static double meleeShare() { return Math.max(0, DDials.BOSS_MELEE_SHARE.get()); }

    /** A boss add's contact hit as a share of its research damage ({@code dungeons.boss-add-melee-share}). */
    public static double addShare() { return Math.max(0, DDials.ADD_MELEE_SHARE.get()); }

    /** The run behind a fight (null once it is gone). */
    public static DungeonRun run(BossContext c) { return RunManager.get() == null ? null : RunManager.get().run(c.runId()); }

    /** Run members still in the run (1..5): boss floors scale health/damage/add counts with it (wave-4 endgame). */
    public static int members(BossContext c) {
        DungeonRun r = run(c);
        return r == null ? com.skyclone.mod.dungeons.model.Balance.FULL_PARTY : Math.max(1, r.activeMembers());
    }

    /** The boss floor's health multiplier for this run's party size, read when a boss or add spawns. */
    public static double partyHp(BossContext c) {
        DungeonRun r = run(c);
        return r == null ? 1 : r.partyHp();
    }

    public static double hp(LivingEntity e) { return e == null ? 0 : Sky.mobs().health(e); }

    public static double max(LivingEntity e) { return e == null ? 1 : Math.max(1, Sky.mobs().maxHealth(e)); }

    public static double frac(LivingEntity e) { return hp(e) / max(e); }

    public static boolean alive(LivingEntity e) { return e != null && e.isValid() && !e.isDead() && Sky.mobs().isSkyMob(e); }

    // ---- damage to players -------------------------------------------------------------------------

    /** A scripted hit that defense reduces. {@code raw} is the floor's number (the boss-damage dial applies). */
    public static void hit(Player p, double raw) {
        if (p == null || !p.isOnline() || raw <= 0) return;
        scripted(p, raw * dmgScale(), DamageContext.Kind.MOB);
    }

    /** True damage (only True Defense reduces it). */
    public static void hitTrue(Player p, double raw) {
        if (p == null || !p.isOnline() || raw <= 0) return;
        scripted(p, raw * dmgScale(), DamageContext.Kind.TRUE);
    }

    /** A share of the player's max health as true damage (Storm's lightning 75%, Necron's beam 25%). */
    public static void pct(Player p, double fraction) {
        if (p == null || !p.isOnline() || fraction <= 0) return;
        scripted(p, Sky.vitals().maxHealth(p) * fraction, DamageContext.Kind.TRUE);
    }

    private static int scriptedDepth;

    /**
     * True while a scripted boss attack is inside the damage pipeline. Its source is the boss body for the death
     * message only: the dungeon damage modifiers must not treat it as that body's melee (the body's multiplier is
     * {@code boss-melee-share}, which would shrink a skull to a quarter) nor as a mob hit the Tank can divert.
     */
    public static boolean scripted() { return scriptedDepth > 0; }

    /**
     * Every scripted hit lands here. playtest-endgame: they used to pass NO source, so a death read "killed by a monster"
     * or "struck down". The nearest living boss body is the source now ("killed by Grinko"), which also lets the
     * defensive modifiers that need an attacker (Wither Shield's 10% cut, wither-armour and aura reductions) apply.
     * The party-size damage scale applies (wave-4 endgame: {@code DungeonRun.partyDmg}, solo-boss-damage-scale at one
     * member up to 1.0 at five), and nothing lands on a player inside the boss-arrival grace.
     */
    private static void scripted(Player p, double amount, DamageContext.Kind kind) {
        RunManager rm = RunManager.get();
        DungeonRun run = rm == null ? null : rm.runOf(p);
        LivingEntity by = run == null ? null : run.bossSource(p.getLocation());
        if (run != null) {
            if (run.inArrivalGrace(p)) return;
            amount *= run.partyDmg();
        }
        if (!(amount > 0)) return;
        scriptedDepth++;
        try { Sky.combat().damagePlayer(p, amount, by, kind); }
        finally { scriptedDepth--; }
    }

    public static void knock(Player p, Location from, double strength, double up) {
        Vector v = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 0.01) v = new Vector(0, 0, 1);
        v.normalize().multiply(strength).setY(up);
        try { p.setVelocity(v); } catch (RuntimeException ignored) { }
    }

    public static void effect(Player p, PotionEffectType t, int ticks, int amp) {
        try { p.addPotionEffect(new PotionEffect(t, ticks, amp, false, true, true)); } catch (RuntimeException ignored) { }
    }

    // ---- targeting -----------------------------------------------------------------------------------

    public static Player nearest(BossContext c, Location at) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : c.alive()) {
            if (!p.getWorld().equals(at.getWorld())) continue;
            double d = p.getLocation().distanceSquared(at);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    public static Player random(BossContext c, Random rng) {
        List<Player> a = c.alive();
        return a.isEmpty() ? null : a.get(rng.nextInt(a.size()));
    }

    public static List<Player> within(BossContext c, Location at, double radius) {
        List<Player> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Player p : c.alive()) if (p.getWorld().equals(at.getWorld()) && p.getLocation().distanceSquared(at) <= r2) out.add(p);
        return out;
    }

    /** Horizontal distance (y ignored). */
    public static double flat(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---- telegraphs ------------------------------------------------------------------------------------

    /**
     * A warning ring on the ground that fills for {@code ticks}, then hits every living player inside it.
     * {@code onLand} also runs once (for sounds, blocks...).
     */
    public static void circle(BossContext c, Location centre, double radius, int ticks, Color colour, Consumer<Player> hit, Runnable onLand) {
        final int[] t = {0};
        Location at = centre.clone();
        add(c, () -> {
            if (t[0] % 4 == 0) {
                double f = Math.min(1, t[0] / (double) Math.max(1, ticks));
                Fx.ring(at, radius, colour, (int) Math.max(16, radius * 10));
                if (f > 0.05) Fx.ring(at, radius * f, Color.fromRGB(255, 255, 255), (int) Math.max(8, radius * 6 * f));
            }
            if (t[0]++ < ticks) return true;
            Fx.particle(at.clone().add(0, 0.5, 0), Particle.EXPLOSION, (int) Math.max(1, radius), radius / 2, 0);
            Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.9f);
            for (Player p : within(c, at, radius)) if (Math.abs(p.getLocation().getY() - at.getY()) < 4) hit.accept(p);
            if (onLand != null) onLand.run();
            return false;
        });
    }

    /**
     * A particle projectile from {@code from} toward {@code to} (then on in a straight line up to {@code range}
     * blocks). It stops at the first solid block (pillars and walls are cover) and hits the first living player
     * within {@code radius}.
     */
    public static void bolt(BossContext c, Location from, Location to, double speed, double range, double radius,
                            Particle head, Color trail, Consumer<Player> hit) {
        Vector dir = to.toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 0.0001) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(speed);
        Location cur = from.clone();
        final double[] travelled = {0};
        final Vector step = dir;
        add(c, () -> {
            for (int sub = 0; sub < 2; sub++) {
                cur.add(step.clone().multiply(0.5));
                travelled[0] += speed / 2;
                Block b = cur.getBlock();
                if (b.getType().isSolid() || travelled[0] > range) {
                    Fx.particle(cur, Particle.SMOKE, 6, 0.2, 0.01);
                    return false;
                }
                for (Player p : c.alive()) {
                    if (!p.getWorld().equals(cur.getWorld())) continue;
                    Location pl = p.getLocation().add(0, 1, 0);
                    if (pl.distanceSquared(cur) <= radius * radius) {
                        hit.accept(p);
                        Fx.particle(cur, Particle.CRIT, 10, 0.3, 0.1);
                        return false;
                    }
                }
            }
            Fx.particle(cur, head, 1, 0, 0);
            if (trail != null) Fx.dust(cur, trail, 1.2f, 2, 0.05);
            return true;
        });
    }

    /** A straight beam drawn from a to b for {@code ticks}, hitting players within 0.9 blocks of the segment every 10 ticks. */
    public static void beam(BossContext c, Location a, Location b, int ticks, Color colour, Consumer<Player> hit) {
        final int[] t = {0};
        add(c, () -> {
            if (t[0] % 3 == 0) Fx.line(a, b, colour, 0.5);
            if (t[0] % 10 == 0) for (Player p : c.alive()) if (nearSegment(p.getLocation().add(0, 1, 0), a, b, 0.9)) hit.accept(p);
            return ++t[0] < ticks;
        });
    }

    public static boolean nearSegment(Location p, Location a, Location b, double r) {
        if (!p.getWorld().equals(a.getWorld())) return false;
        Vector ab = b.toVector().subtract(a.toVector());
        Vector ap = p.toVector().subtract(a.toVector());
        double len2 = ab.lengthSquared();
        double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, ap.dot(ab) / len2));
        Vector closest = a.toVector().add(ab.multiply(t));
        return closest.distanceSquared(p.toVector()) <= r * r;
    }

    // ---- presentation -----------------------------------------------------------------------------------

    /** The standard boss bar: "&c&lName &7- &a1.2M&c❤ &8(phase)". */
    public static void bar(BossContext c, String name, LivingEntity boss, String suffix, BossBar.Color colour) {
        double h = hp(boss), m = max(boss);
        c.bossBar("&c&l" + name + " &7- &a" + Text.shortNum(h) + "&c❤" + (suffix == null || suffix.isEmpty() ? "" : " &8| " + suffix),
                (float) (h / m), colour);
    }

    public static void sound(BossContext c, Sound s, float pitch) { for (Player p : c.players()) Fx.sound(p, s, 1f, pitch); }

    /** Title + sound to everyone (used for phase changes). */
    public static void announce(BossContext c, String title, String sub, Sound s) {
        c.title(title, sub);
        if (s != null) sound(c, s, 1f);
    }

    public static void scale(LivingEntity e, double s) {
        if (e == null) return;
        try {
            AttributeInstance a = e.getAttribute(Attribute.SCALE);
            if (a != null) a.setBaseValue(s);
        } catch (RuntimeException ignored) { }
    }

    public static void speed(LivingEntity e, double factor) {
        if (e == null) return;
        try {
            AttributeInstance a = e.getAttribute(Attribute.MOVEMENT_SPEED);
            if (a != null) a.setBaseValue(a.getBaseValue() * factor);
        } catch (RuntimeException ignored) { }
    }

    public static void freeze(LivingEntity e, boolean frozen) {
        if (e == null) return;
        try { e.setAI(!frozen); } catch (RuntimeException ignored) { }
    }

    public static void glow(Entity e, boolean on) {
        try { e.setGlowing(on); } catch (RuntimeException ignored) { }
    }

    /** Throttled "&cImmune!" action-bar flash for a player hitting an invulnerable boss. */
    public static void immune(DamageContext dc, String why) {
        dc.cancelled = true;
        Player p = dc.playerAttacker();
        if (p != null) Sky.vitals().flash(p, "&c&lIMMUNE &7" + why, 30);
    }

    /** Spread players a little so a group teleport does not stack everyone in one block. */
    public static void teleportAll(BossContext c, Location to) {
        int i = 0;
        for (Player p : c.players()) {
            double a = i++ * 1.2;
            Location l = to.clone().add(Math.cos(a) * 1.5, 0, Math.sin(a) * 1.5);
            l.setYaw(to.getYaw());
            p.teleportAsync(l);
            p.setFallDistance(0);
        }
    }

    /** Remove every queued effect with nothing else; used when a fight resets. */
    public static int pending(String runId) {
        int n = 0;
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) if (it.next().runId.equals(runId)) n++;
        return n;
    }
}
