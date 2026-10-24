package com.skyclone.mod.mines;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mines' mob behaviours: the Sneaky Creeper's blast, melee-only Ghosts in The Mist, goblins that
 * throw knives and fire from range, Ice Walkers that slow you, and Emerald Slimes of three sizes.
 */
final class MineMobs implements Listener {

    static final String SNEAKY = "mines_sneaky", GHOST = "mines_ghost", KNIFE = "mines_knife",
            FIRE = "mines_fireslinger", FROST = "mines_frost", SLIME = "mines_slime";

    private final MinesDials dials;
    private final Map<UUID, Long> lastShot = new HashMap<>();
    private final Map<UUID, Long> warned = new HashMap<>();

    MineMobs(MinesDials dials) { this.dials = dials; }

    void register() {
        Sky.mobs().registerBehaviour(SNEAKY, new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) { mob.setInvisible(true); }
        });
        Sky.mobs().registerBehaviour(GHOST, new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                mob.setInvisible(true);
                if (mob instanceof Creeper c) c.setPowered(true);
            }
            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                if (ctx.kind == DamageContext.Kind.MELEE || ctx.kind == DamageContext.Kind.FEROCITY || ctx.kind == DamageContext.Kind.TRUE) return;
                ctx.cancelled = true;
                Player p = ctx.playerAttacker();
                if (p != null && warnOk(p)) Msg.send(p, "&cOnly melee attacks can hurt a Ghost!");
            }
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                double extra = dials.ghostTrueDamage.get();
                if (extra > 0) Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    if (target.isOnline() && !target.isDead() && mob.isValid()) Sky.combat().damagePlayer(target, extra, mob, DamageContext.Kind.TRUE);
                });
            }
            @Override public void onDeath(LivingEntity mob, MobDef def, Player killer) {
                if (killer == null || ThreadLocalRandom.current().nextDouble() >= dials.ghostCoinChance.get()) return;
                double coins = dials.ghostCoinBurst.get();
                Sky.economy().deposit(killer, coins, "mob_drop: dwarven_ghost coin burst");
                Msg.send(killer, "&d&lCRAZY RARE DROP! &6" + Text.commasInt(coins) + " Coins");
            }
        });
        Sky.mobs().registerBehaviour(KNIFE, new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                ranged(mob, def, dials.knifeRange.get(), dials.knifeCooldown.get(), false);
            }
        });
        Sky.mobs().registerBehaviour(FIRE, new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                ranged(mob, def, dials.fireRange.get(), dials.fireCooldown.get(), true);
            }
        });
        Sky.mobs().registerBehaviour(FROST, new MobBehaviour() {
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Math.max(1, dials.frostTicks.get()),
                        Math.max(0, dials.frostLevel.get()), false, true, true));
                target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.01);
            }
        });
        Sky.mobs().registerBehaviour(SLIME, new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                if (mob instanceof Slime s) {
                    int size = def.extra != null && def.extra.has("size") ? def.extra.get("size").getAsInt() : 2;
                    s.setSize(Math.max(1, Math.min(6, size)));
                }
            }
        });
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
    }

    private boolean warnOk(Player p) {
        long now = System.currentTimeMillis();
        Long last = warned.get(p.getUniqueId());
        if (last != null && now - last < 3000) return false;
        warned.put(p.getUniqueId(), now);
        return true;
    }

    /**
     * Knife / fireball at the nearest player in range and in sight, on its own cooldown.
     *
     * <p>Wave 3: a real, dodgeable projectile instead of an instant hit. The first pass drew a particle line and
     * applied the damage the same tick, so a Knifethrower could never be dodged and the line only showed where
     * the hit had already landed. Now the shot is aimed at the player's chest, flies at a dial speed (a knife
     * covers its 14 blocks in ~0.7 s, a fireball is slower), carries an item you can see (a spinning sword or a
     * fire charge) and hits the FIRST player whose body it passes within the hit radius of -- so strafing
     * sideways makes it miss, and a wall stops it.
     */
    private void ranged(LivingEntity mob, MobDef def, double range, double cooldownSec, boolean fire) {
        long now = System.currentTimeMillis();
        Long last = lastShot.get(mob.getUniqueId());
        if (last != null && now - last < cooldownSec * 1000) return;
        Player target = nearest(mob, range);
        if (target == null) return;
        lastShot.put(mob.getUniqueId(), now);
        if (lastShot.size() > 2000) lastShot.entrySet().removeIf(e -> now - e.getValue() > 60_000);
        Location from = mob.getEyeLocation();
        Vector dir = target.getLocation().add(0, 1.1, 0).toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 1e-3) return;
        double speed = Math.max(0.2, fire ? dials.fireSpeed.get() : dials.knifeSpeed.get());
        Vector step = dir.normalize().multiply(speed);
        mob.swingMainHand();
        mob.getWorld().playSound(from, fire ? Sound.ENTITY_BLAZE_SHOOT : Sound.ENTITY_ARROW_SHOOT, 1f, fire ? 1f : 1.6f);
        new Shot(mob, def.damage, fire, from.clone().add(step), step, len + 4).start();
    }

    /** One knife or fireball in flight: moves {@code step} a tick, hits the first player it touches. */
    private final class Shot implements Runnable {
        final LivingEntity owner;
        final double damage, maxDist;
        final boolean fire;
        final Location at;
        final Vector step;
        double travelled;
        org.bukkit.entity.ItemDisplay look;
        org.bukkit.scheduler.BukkitTask task;

        Shot(LivingEntity owner, double damage, boolean fire, Location at, Vector step, double maxDist) {
            this.owner = owner; this.damage = damage; this.fire = fire; this.at = at; this.step = step; this.maxDist = maxDist;
        }

        void start() {
            try {
                look = at.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
                    d.setItemStack(new org.bukkit.inventory.ItemStack(fire ? org.bukkit.Material.FIRE_CHARGE : org.bukkit.Material.IRON_SWORD));
                    d.setTeleportDuration(1);
                    d.setPersistent(false);
                    d.setBillboard(fire ? org.bukkit.entity.Display.Billboard.CENTER : org.bukkit.entity.Display.Billboard.FIXED);
                });
            } catch (Throwable t) { look = null; }   // particles alone still show the shot
            task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this, 1L, 1L);
        }

        @Override public void run() {
            if (owner.isDead() || !owner.isValid()) { stop(); return; }
            // move in two half-steps so a fast knife cannot skip over a player standing between ticks
            for (int half = 0; half < 2; half++) {
                at.add(step.clone().multiply(0.5));
                travelled += step.length() * 0.5;
                if (at.getBlock().getType().isSolid()) {
                    at.getWorld().spawnParticle(fire ? Particle.SMOKE : Particle.CRIT, at, 6, 0.1, 0.1, 0.1, 0.02);
                    stop();
                    return;
                }
                Player hit = touching();
                if (hit != null) {
                    Sky.combat().damagePlayer(hit, damage, owner, DamageContext.Kind.MOB);
                    if (fire) hit.setFireTicks(Math.max(hit.getFireTicks(), dials.fireTicks.get()));
                    hit.getWorld().playSound(hit.getLocation(), fire ? Sound.ENTITY_BLAZE_HURT : Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 1.2f);
                    stop();
                    return;
                }
            }
            at.getWorld().spawnParticle(fire ? Particle.FLAME : Particle.CRIT, at, fire ? 3 : 2, 0.05, 0.05, 0.05, 0);
            if (look != null && look.isValid()) {
                Location l = at.clone();
                l.setYaw((float) (travelled * 90 % 360));   // the knife tumbles end over end as it flies
                look.teleport(l);
            }
            if (travelled >= maxDist) stop();
        }

        private Player touching() {
            double r = Math.max(0.3, dials.projectileHitRadius.get());
            for (Player p : at.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
                Location f = p.getLocation();
                // body = vertical segment from the feet to the eyes; distance from the shot to that segment
                double dy = at.getY() < f.getY() ? f.getY() - at.getY() : Math.max(0, at.getY() - (f.getY() + 1.8));
                double dx = at.getX() - f.getX(), dz = at.getZ() - f.getZ();
                if (dx * dx + dz * dz + dy * dy <= r * r) return safe(f) || Rides.riding(p) ? null : p;
            }
            return null;
        }

        private void stop() {
            if (task != null) task.cancel();
            if (look != null) try { look.remove(); } catch (Throwable ignored) { }
        }
    }

    private static Player nearest(LivingEntity mob, double range) {
        Player best = null;
        double bestD = range * range;
        for (Player p : mob.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isDead()) continue;
            double d = p.getLocation().distanceSquared(mob.getLocation());
            if (d > bestD || Rides.riding(p) || !mob.hasLineOfSight(p) || safe(p.getLocation())) continue;
            best = p;
            bestD = d;
        }
        return best;
    }

    static boolean safe(Location l) {
        try {
            Layout.Zone z = Sky.worlds().zoneAt(l);
            return z != null && z.flags() != null && (z.flags().contains(Layout.Zone.SAFE) || z.flags().contains(Layout.Zone.NO_MOBS));
        } catch (Throwable t) { return false; }
    }

    private static boolean has(Entity e, String behaviour) {
        if (!(e instanceof LivingEntity le)) return false;
        MobDef def = Sky.mobs().defOf(le);
        return def != null && def.behaviours != null && def.behaviours.contains(behaviour);
    }

    // ---- vanilla events: creepers never blow up blocks; the Sneaky Creeper's blast is ours ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent e) {
        Entity en = e.getEntity();
        if (!(en instanceof LivingEntity le) || !Sky.mobs().isSkyMob(le)) return;
        if (!has(le, SNEAKY) && !has(le, GHOST)) {
            String island;
            try { island = Sky.worlds().islandOf(le.getLocation()); } catch (Throwable t) { return; }
            if (island == null || !Places.ISLANDS.contains(island)) return;   // other islands' creepers are not ours
        }
        e.setCancelled(true);
        if (!has(le, SNEAKY)) return;
        MobDef def = Sky.mobs().defOf(le);
        Location at = le.getLocation();
        double r = dials.sneakyRadius.get();
        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 3, 0.6, 0.4, 0.6, 0);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) > r * r || safe(p.getLocation()) || Rides.riding(p)) continue;
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            Sky.combat().damagePlayer(p, def.damage, le, DamageContext.Kind.MOB);
        }
        Sky.mobs().remove(le);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent e) {
        String island;
        try { island = Sky.worlds().islandOf(e.getLocation()); } catch (Throwable t) { return; }
        if (island != null && Places.ISLANDS.contains(island)) e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent e) {
        if (Sky.mobs().isSkyMob(e.getEntity())) e.setCancelled(true);
    }
}
