package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Puddle Jumper minigame (Lotus Atoll, Legendary). The reference creature is ELUSIVE (5 HP, nothing
 * hurts it) and its catch line is "cast your rod into it and hold on tight". So:
 * <ol>
 *   <li>It hops about where it surfaced; weapons, arrows and rods' swings slide off it.</li>
 *   <li>Hook it with your bobber (or right-click it with a rod in hand) and it LEAPS, dragging you along a
 *       big looping arc ({@link FishMath#rideOffset}).</li>
 *   <li>Hold on: keep the rod in your hand, do not sneak and do not reel in. Let go and it is gone.</li>
 *   <li>Ride the whole leap and it is caught: it dies to you, so its drops, Fishing XP, the galatea module's
 *       Puddle Jumper trophy frog roll and every kill hook happen as for any sea creature.</li>
 * </ol>
 * It slips away if nobody grabs it in {@code escapeSeconds}. You land under Slow Falling, with no fall damage.
 * Dial {@code fishing.puddle-jumper} off = an ordinary (5 HP) creature. Ride numbers are ours (TUNE).
 */
final class PuddleJumper implements Listener {

    static final String MOB = "sc_puddle_jumper";

    static final class Jumper {
        final UUID owner;
        final long born = System.currentTimeMillis();
        long nextHop;
        boolean riding;
        long rideStart;
        long rideMs;
        Location start;
        Jumper(UUID owner) { this.owner = owner; }
    }

    static final Map<UUID, Jumper> LIVE = new HashMap<>();
    /** Frogs being finished by their rider: the elusive guard lets exactly these hits through. */
    private static final Set<UUID> FINISHING = new HashSet<>();
    /** Players who just came off a leap: no fall damage until then. */
    private static final Map<UUID, Long> SAFE = new HashMap<>();
    private static final Map<UUID, Long> HINT = new HashMap<>();
    private static int task = -1;

    static void register() {
        Sky.mobs().registerBehaviour("fishing_puddle_jumper", new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                UUID o = Sky.mobs().owner(mob);
                if (o == null || !FishDials.PUDDLE_JUMPER.get()) return;
                Jumper j = new Jumper(o);
                j.nextHop = System.currentTimeMillis() + 1500;
                LIVE.put(mob.getUniqueId(), j);
            }

            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                if (!FishDials.PUDDLE_JUMPER.get() || !elusive(def) || FINISHING.contains(mob.getUniqueId())) return;
                ctx.cancelled = true;                    // ELUSIVE: only the end of a ride catches it
                Player p = ctx.playerAttacker();
                if (p != null) hint(p, "&9The Puddle Jumper slips away! &7Cast your rod INTO it and hold on.");
            }
        });
    }

    static boolean elusive(MobDef def) {
        return def != null && def.extra != null && def.extra.has("elusive") && def.extra.get("elusive").getAsBoolean();
    }

    static void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), PuddleJumper::tick, 1L, 1L).getTaskId();
    }

    static void stop() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        LIVE.clear();
        FINISHING.clear();
        SAFE.clear();
    }

    private static double num(MobDef d, String key, double def) {
        return d != null && d.extra != null && d.extra.has(key) ? d.extra.get(key).getAsDouble() : def;
    }

    // ---- the clock ------------------------------------------------------------------------------

    private static void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Long>> it = SAFE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || now > e.getValue()) { it.remove(); continue; }
            p.setFallDistance(0);
        }
        if (LIVE.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Jumper>> it = LIVE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Jumper> e = it.next();
            Jumper j = e.getValue();
            Entity ent = Bukkit.getEntity(e.getKey());
            Player owner = Bukkit.getPlayer(j.owner);
            if (!(ent instanceof LivingEntity frog) || !frog.isValid() || frog.isDead()) {
                if (j.riding && owner != null) land(owner);
                it.remove();
                continue;
            }
            MobDef def = Sky.mobs().defOf(frog);
            if (owner == null || !owner.isOnline() || owner.getWorld() != frog.getWorld()) {
                it.remove();
                Sky.mobs().remove(frog);
                continue;
            }
            try {
                if (!j.riding) {
                    if (now - j.born > (long) (num(def, "escapeSeconds", 40) * 1000)) {
                        it.remove();
                        escape(owner, frog, "&7The &9Puddle Jumper &7got bored and hopped away.");
                        continue;
                    }
                    if (now >= j.nextHop) hop(frog, j, def);
                    if (hookedBy(frog, owner)) startRide(owner, frog, j, def);
                } else {
                    double t = (now - j.rideStart) / (double) Math.max(1, j.rideMs);
                    if (!holding(owner, frog)) {
                        it.remove();
                        Fishing.data(owner).puddleFalls++;
                        land(owner);
                        escape(owner, frog, "&cYou lost your grip! &7The &9Puddle Jumper &7splashed away.");
                        continue;
                    }
                    if (t >= 1) {
                        it.remove();
                        finish(owner, frog);
                        continue;
                    }
                    ride(owner, frog, j, t);
                }
            } catch (RuntimeException ex) {
                Sky.log().warning("[fishing] puddle jumper tick: " + ex);
                it.remove();
                if (j.riding) land(owner);
            }
        }
    }

    private static void hop(LivingEntity frog, Jumper j, MobDef def) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        j.nextHop = System.currentTimeMillis() + (long) (num(def, "hopEvery", 2.5) * 1000 * (0.7 + r.nextDouble() * 0.6));
        double a = r.nextDouble() * Math.PI * 2;
        frog.setVelocity(new Vector(Math.cos(a) * 0.35, 0.55, Math.sin(a) * 0.35));
        frog.getWorld().spawnParticle(Particle.SPLASH, frog.getLocation(), 15, 0.3, 0.1, 0.3, 0.1);
        frog.getWorld().playSound(frog.getLocation(), Sound.ENTITY_FROG_LONG_JUMP, 0.8f, 1.2f);
    }

    /** One of the owner's bobbers is stuck in the frog. */
    private static boolean hookedBy(LivingEntity frog, Player owner) {
        for (FishHook h : frog.getWorld().getNearbyEntitiesByType(FishHook.class, frog.getLocation(), 4)) {
            if (h.getHookedEntity() == frog && h.getShooter() == owner) return true;
        }
        return false;
    }

    /** Holding on: rod in the main hand, not sneaking, and still near the frog. */
    static boolean holding(Player p, LivingEntity frog) {
        return p.isOnline() && !p.isDead() && !p.isSneaking() && p.getWorld() == frog.getWorld()
                && Fishing.isRod(p.getInventory().getItemInMainHand())
                && p.getLocation().distanceSquared(frog.getLocation()) < 16 * 16;
    }

    static void startRide(Player p, LivingEntity frog, Jumper j, MobDef def) {
        if (j.riding) return;
        j.riding = true;
        j.rideStart = System.currentTimeMillis();
        j.rideMs = (long) (num(def, "rideSeconds", 6) * 1000);
        j.start = frog.getLocation().clone();
        frog.setGravity(false);
        frog.setVelocity(new Vector());
        frog.getWorld().playSound(frog.getLocation(), Sound.ENTITY_FROG_LONG_JUMP, 1f, 0.6f);
        p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&9&lHOLD ON!"),
                Text.c("&7Keep your rod in hand - don't sneak, don't reel!"),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1500),
                        java.time.Duration.ofMillis(300))));
    }

    private static void ride(Player p, LivingEntity frog, Jumper j, double t) {
        double[] o = FishMath.rideOffset(t, FishDials.PJ_RADIUS.get(), FishDials.PJ_HEIGHT.get());
        // the loop opens away from the angler's side of the frog, along the way it was facing
        double yaw = Math.toRadians(j.start.getYaw());
        double cx = Math.cos(yaw), sz = Math.sin(yaw);
        double wx = o[0] * cx - o[2] * sz, wz = o[0] * sz + o[2] * cx;
        Location to = j.start.clone().add(wx, o[1] + 0.2, wz);
        to.setYaw((float) Math.toDegrees(Math.atan2(-(to.getX() - frog.getLocation().getX()), to.getZ() - frog.getLocation().getZ())));
        frog.teleport(to);
        frog.getWorld().spawnParticle(Particle.FALLING_WATER, to, 3, 0.2, 0.1, 0.2, 0);
        // the angler dangles on the line a couple of blocks under it
        Vector pull = to.clone().add(0, -2.2, 0).toVector().subtract(p.getLocation().toVector()).multiply(0.35);
        if (pull.length() > 1.6) pull.normalize().multiply(1.6);
        p.setVelocity(pull);
        p.setFallDistance(0);
        int left = (int) Math.ceil((1 - t) * j.rideMs / 1000.0);
        Sky.vitals().flash(p, "&9Puddle Jumper: &bhold on! &7" + left + "s", 5);
    }

    private static void finish(Player p, LivingEntity frog) {
        UUID id = frog.getUniqueId();
        FINISHING.add(id);
        try {
            Sky.combat().rawDamage(frog, Math.max(1, Sky.mobs().maxHealth(frog)) * 10 + 100, p, DamageContext.Kind.TRUE);
        } finally {
            FINISHING.remove(id);
        }
        if (frog.isValid() && !frog.isDead()) Sky.mobs().remove(frog);     // could not be killed: never leave it hanging
        Fishing.data(p).puddleRides++;
        land(p);
        Msg.send(p, "&9&lCAUGHT! &7You rode the &9Puddle Jumper &7all the way down.");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
    }

    private static void escape(Player p, LivingEntity frog, String msg) {
        frog.getWorld().spawnParticle(Particle.SPLASH, frog.getLocation(), 30, 0.4, 0.2, 0.4, 0.1);
        SeaCreatures.LIVE.remove(frog.getUniqueId());
        Sky.mobs().remove(frog);
        if (p != null && p.isOnline()) Msg.send(p, msg);
    }

    /** Coming off a leap: Slow Falling and no fall damage for a few seconds. */
    static void land(Player p) {
        SAFE.put(p.getUniqueId(), System.currentTimeMillis() + 5000);
        p.setFallDistance(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 80, 0, true, false, true));
    }

    private static void hint(Player p, String msg) {
        long now = System.currentTimeMillis();
        if (now - HINT.getOrDefault(p.getUniqueId(), 0L) < 3000) return;
        HINT.put(p.getUniqueId(), now);
        Sky.vitals().flash(p, msg, 50);
    }

    // ---- events ---------------------------------------------------------------------------------

    /** Reeling a hooked Puddle Jumper: before the leap it starts the ride; during it, it is letting go. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY || !(e.getCaught() instanceof LivingEntity frog)) return;
        Jumper j = LIVE.get(frog.getUniqueId());
        if (j == null) return;
        e.setCancelled(true);                            // never yank the frog over with vanilla's pull
        if (!j.owner.equals(e.getPlayer().getUniqueId())) return;
        if (!j.riding) startRide(e.getPlayer(), frog, j, Sky.mobs().defOf(frog));
        else e.getHook().remove();                       // the line is cut: the next tick sees the grip lost
    }

    /** Right-clicking it with a rod in hand also grabs it (a bobber that skims past still gets you on). */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof LivingEntity frog)) return;
        Jumper j = LIVE.get(frog.getUniqueId());
        if (j == null || j.riding) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (!j.owner.equals(p.getUniqueId())) { hint(p, "&7That Puddle Jumper is somebody else's catch."); return; }
        if (!Fishing.isRod(p.getInventory().getItemInMainHand())) { hint(p, "&9Grab it with a fishing rod in your hand!"); return; }
        startRide(p, frog, j, Sky.mobs().defOf(frog));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        SAFE.remove(e.getPlayer().getUniqueId());
        HINT.remove(e.getPlayer().getUniqueId());
    }

    /** "riding 3s / waiting" for admin output. */
    static String describe(UUID frog) {
        Jumper j = LIVE.get(frog);
        if (j == null) return "untracked";
        long now = System.currentTimeMillis();
        return j.riding ? "riding " + (now - j.rideStart) / 1000 + "/" + j.rideMs / 1000 + "s" : "waiting " + (now - j.born) / 1000 + "s";
    }
}
