package com.skyclone.mod.crimson;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob behaviours of the isle (fire bolts, flame rings, the Edgewraith's lunge, slams, charges), the
 * mini-boss bookkeeping (kills, respawn timers, reputation, bits), Vanquisher calls and the volcano's
 * eruptions. Damage numbers are fractions of each mob's own {@code damage} (TUNE: the reference
 * publishes HP, not attack patterns).
 */
final class Bosses implements Listener {

    static final String ERUPTION = "crimson_volcano_eruption";
    private static final Map<UUID, Long> NEXT = new HashMap<>();
    private static final Set<UUID> SPLIT = new HashSet<>();
    private static final Map<String, Long> LAST_DEATH = new HashMap<>();
    private static long ticks;

    static void register() {
        // every crimson mob gets the health-scale hook (dials crimson.mob-hp-scale / boss-hp-scale)
        for (MobDef d : Sky.mobs().all())
            if (d.id != null && d.id.startsWith("crimson_") && !d.behaviours.contains("crimson_scaled")) d.behaviours.add("crimson_scaled");

        Sky.mobs().registerBehaviour("crimson_scaled", new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) {
                double s = def.has("boss") ? CrimsonModule.D.bossHpScale.get() : CrimsonModule.D.mobHpScale.get();
                if (s > 0 && Math.abs(s - 1) > 1e-6) Sky.mobs().setMaxHealth(mob, Math.max(1, def.health * s));
                if (def.has("boss")) keepSingle(mob, def);
            }
            // every hit a boss (or a Cindermaw split) lands passes through here: vanilla melee routed by core,
            // and every behaviour's damagePlayer(.., mob, ..) - one place, so the dial cannot miss an attack
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                if (!def.has("boss") && !CHILDREN.containsKey(mob.getUniqueId())) return;
                double s = CrimsonRules.damageScale(CrimsonModule.D.bossDamageScale.get());
                if (s <= 0) { ctx.cancelled = true; return; }
                ctx.multiplicative *= s;
            }
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                Child c = CHILDREN.get(mob.getUniqueId());
                if (c != null) leash(mob, c);
            }
        });
        Sky.mobs().registerBehaviour("crimson_magma_size", new MobBehaviour() {
            @Override public void onSpawn(LivingEntity mob, MobDef def) { if (mob instanceof MagmaCube mc) mc.setSize(6); }
        });
        Sky.mobs().registerBehaviour("crimson_fireball", ranged(100, 0.5, Particle.FLAME, Sound.ENTITY_BLAZE_SHOOT, 16));
        Sky.mobs().registerBehaviour("crimson_firebolt", ranged(80, 0.8, Particle.SOUL_FIRE_FLAME, Sound.ENTITY_BLAZE_SHOOT, 14));
        Sky.mobs().registerBehaviour("crimson_flame_ring", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (!due(mob, 160)) return;
                Location c = mob.getLocation();
                World w = c.getWorld();
                for (int i = 0; i < 24; i++) {
                    double a = i * Math.PI / 12;
                    w.spawnParticle(Particle.FLAME, c.clone().add(Math.cos(a) * 5, 0.3, Math.sin(a) * 5), 2, 0.1, 0.1, 0.1, 0.01);
                }
                w.playSound(c, Sound.ITEM_FIRECHARGE_USE, 1f, 0.7f);
                for (Player p : w.getPlayers())
                    if (p.getLocation().distanceSquared(c) <= 25 && allows(mob, p)) Sky.combat().damagePlayer(p, def.damage * 0.6, mob, DamageContext.Kind.MOB);
            }
        });
        Sky.mobs().registerBehaviour("crimson_blade_storm", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                Player t = target(mob, 20);
                if (t == null || !due(mob, 120)) return;
                Location to = t.getLocation().clone().add(t.getLocation().getDirection().setY(0).normalize().multiply(-1.5));
                to.setY(t.getLocation().getY());
                if (to.getBlock().isPassable() && to.clone().add(0, 1, 0).getBlock().isPassable()) mob.teleport(to);
                mob.getWorld().spawnParticle(Particle.SWEEP_ATTACK, t.getLocation().add(0, 1, 0), 6, 0.6, 0.4, 0.6, 0);
                mob.getWorld().playSound(t.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.6f);
                Sky.combat().damagePlayer(t, def.damage * 1.5, mob, DamageContext.Kind.MOB);
            }
        });
        Sky.mobs().registerBehaviour("crimson_ground_slam", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (target(mob, 10) == null || !due(mob, 140)) return;
                Location c = mob.getLocation();
                c.getWorld().spawnParticle(Particle.EXPLOSION, c, 3, 1.5, 0.2, 1.5, 0);
                c.getWorld().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.6f);
                for (Player p : c.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(c) > 36 || !allows(mob, p)) continue;
                    Sky.combat().damagePlayer(p, def.damage * 1.2, mob, DamageContext.Kind.MOB);
                    p.setVelocity(p.getVelocity().add(new Vector(0, 0.6, 0)));
                }
            }
        });
        Sky.mobs().registerBehaviour("crimson_charge", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                Player t = target(mob, 12);
                if (t == null || !due(mob, 120)) return;
                Vector v = t.getLocation().toVector().subtract(mob.getLocation().toVector());
                if (v.lengthSquared() < 4) return;
                mob.setVelocity(v.normalize().multiply(1.2).setY(0.25));
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.7f, 1.4f);
            }
        });
        Sky.mobs().registerBehaviour("crimson_ignite", new MobBehaviour() {
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                ctx.multiplicative *= 1.1;
                target.getWorld().spawnParticle(Particle.FLAME, target.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.01);
            }
        });
        Sky.mobs().registerBehaviour("crimson_twin_flames", new MobBehaviour() {
            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                if (SPLIT.contains(mob.getUniqueId())) return;
                double max = Sky.mobs().maxHealth(mob);
                if (Sky.mobs().health(mob) - ctx.finalDamage > max * 0.5) return;
                split(mob);
            }
        });
    }

    // ---- the Cindermaw's split -----------------------------------------------------------------------------

    /** A Smoldering Blaze split off a Cindermaw: whose it is, where it was born, when its parent went. */
    private static final class Child {
        UUID parent;
        final Location home;
        long orphanSince;
        Child(UUID parent, Location home) { this.parent = parent; this.home = home; }
    }

    /** Split blaze uuid -> its bookkeeping. Read by the scale hooks (onAttack/onTick of crimson_scaled). */
    private static final Map<UUID, Child> CHILDREN = new HashMap<>();
    static final String SPLIT_MOB = "crimson_smoldering_blaze";
    /** A split blaze may stray this far (horizontally) from where it was born before it is put back. */
    static final double CHILD_LEASH = 12, CHILD_LEASH_Y = 10;
    /** Seconds a split blaze outlives its Cindermaw (so a fight in progress can be finished) before it burns out. */
    static final int CHILD_ORPHAN_SECONDS = 30;

    /**
     * The Cindermaw at half health throws off two Smoldering Blazes. They are part of the boss fight, so they
     * take the BOSS dials (playtest wave4-endgame: they were plain {@code spawn()}s, so with
     * {@code crimson.boss-hp-scale} at 0.002 the Cindermaw had 100k HP and its two splits the full research
     * 2.5M each, and every hit of theirs ignored any damage dial). They fight for the Cindermaw's owner when it
     * has one, are leashed to the arena (blazes fly, and one followed a player out over the void), and burn out
     * {@link #CHILD_ORPHAN_SECONDS} after the Cindermaw is gone instead of haunting the isle for ever (nothing
     * counts them, so nothing would ever clear them). Returns the blazes spawned.
     */
    static java.util.List<LivingEntity> split(LivingEntity boss) {
        java.util.List<LivingEntity> out = new java.util.ArrayList<>();
        if (!SPLIT.add(boss.getUniqueId())) return out;
        Location c = boss.getLocation();
        UUID ownerId = Sky.mobs().owner(boss);
        Player owner = ownerId == null ? null : org.bukkit.Bukkit.getPlayer(ownerId);
        if (ownerId != null && owner == null) return out;          // a private test fight whose owner left
        MobDef cd = Sky.mobs().def(SPLIT_MOB);
        for (int i = -1; i <= 1; i += 2) {
            Location at = c.clone().add(i * 3, 0.5, 0);
            if (!at.getBlock().isPassable() || !at.clone().add(0, 1, 0).getBlock().isPassable()) at = c.clone().add(0, 0.5, 0);
            LivingEntity b;
            try { b = Sky.mobs().spawn(SPLIT_MOB, at, owner); } catch (RuntimeException ex) { continue; }
            if (b == null) continue;
            CHILDREN.put(b.getUniqueId(), new Child(boss.getUniqueId(), c.clone()));
            if (cd != null) {
                double s = CrimsonModule.D.bossHpScale.get();
                Sky.mobs().setMaxHealth(b, Math.max(1, cd.health * (s > 0 ? s : 1)));
                Sky.mobs().setHealth(b, Sky.mobs().maxHealth(b));
            }
            if (boss instanceof Mob bm && b instanceof Mob m && bm.getTarget() != null) m.setTarget(bm.getTarget());
            out.add(b);
        }
        for (Player p : c.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(c) < 900 && (ownerId == null || ownerId.equals(p.getUniqueId())))
                Msg.send(p, "&c&lThe Cindermaw splits its flames!");
        return out;
    }

    /** The Cindermaw a split blaze belongs to, or null (its damage counts toward that fight's spoils). */
    static UUID parentOf(UUID splitBlaze) {
        Child c = splitBlaze == null ? null : CHILDREN.get(splitBlaze);
        return c == null ? null : c.parent;
    }

    /** True for a blaze split off a Cindermaw (self-test hook). */
    static boolean isSplitChild(LivingEntity e) { return e != null && CHILDREN.containsKey(e.getUniqueId()); }

    /** Puts a split blaze that strayed from its arena back where it was born (it keeps its target). */
    static boolean leash(LivingEntity mob, Child c) {
        Location at = mob.getLocation(), home = c.home;
        if (home.getWorld() != at.getWorld()) { mob.teleport(home); return true; }
        double dx = at.getX() - home.getX(), dz = at.getZ() - home.getZ();
        if (dx * dx + dz * dz <= CHILD_LEASH * CHILD_LEASH && Math.abs(at.getY() - home.getY()) <= CHILD_LEASH_Y) return false;
        mob.setVelocity(new Vector());
        mob.teleport(home.clone().add(0, 1, 0));
        mob.getWorld().spawnParticle(Particle.FLAME, home.clone().add(0, 1.5, 0), 20, 0.4, 0.6, 0.4, 0.02);
        return true;
    }

    /** Self-test hook: leash a split blaze now. */
    static boolean leashNow(LivingEntity mob) {
        Child c = CHILDREN.get(mob.getUniqueId());
        return c != null && leash(mob, c);
    }

    /** Every second: forget dead split blazes; burn out the ones whose Cindermaw has been gone a while. */
    private static void tickChildren() {
        if (CHILDREN.isEmpty()) return;
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<UUID, Child>> it = CHILDREN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Child> en = it.next();
            org.bukkit.entity.Entity ent = org.bukkit.Bukkit.getEntity(en.getKey());
            if (ent == null) {                                     // gone, or just unloaded with its chunk
                if (en.getValue().home.isChunkLoaded()) it.remove();
                continue;
            }
            if (!(ent instanceof LivingEntity le) || !le.isValid() || le.isDead()) { it.remove(); continue; }
            Child c = en.getValue();
            org.bukkit.entity.Entity parent = org.bukkit.Bukkit.getEntity(c.parent);
            boolean parentUp = parent instanceof LivingEntity pl && pl.isValid() && !pl.isDead();
            if (parentUp) { c.orphanSince = 0; continue; }
            if (c.orphanSince == 0) { c.orphanSince = now; continue; }
            if (now - c.orphanSince < CHILD_ORPHAN_SECONDS * 1000L) continue;
            le.getWorld().spawnParticle(Particle.LARGE_SMOKE, le.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.02);
            Sky.mobs().remove(le);
            it.remove();
        }
    }

    // ---- vanilla attacks that bypass the design ------------------------------------------------------------

    /**
     * A blaze's vanilla AI shoots its own volley of small fireballs on top of the {@code crimson_fireball}
     * behaviour that is its designed attack - from up to 48 blocks (vanilla follow range) instead of 16, three
     * a volley, each routed by core as a full {@code def.damage} hit, and each SETTING THE PLAYER ON FIRE.
     * Fire is environment damage (5% of max health a second, no Defense), so a Flare on the volcano slopes 40
     * blocks off the Dojo road could burn a player from 978 to 412 HP with no mob in sight and nothing in chat
     * (playtest wave4-endgame), and the Cindermaw's volley alone was three 6,000-damage hits. Crimson mobs
     * that carry a designed ranged attack fire only that one.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Fireball)) return;
        if (!(e.getEntity().getShooter() instanceof LivingEntity shooter)) return;
        MobDef def = crimsonDef(shooter);
        if (def != null && (def.behaviours.contains("crimson_fireball") || def.behaviours.contains("crimson_firebolt"))) e.setCancelled(true);
    }

    /** No crimson mob (or anything it shot) sets a player alight: their damage is their hit, not a hidden burn. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(org.bukkit.event.entity.EntityCombustByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        org.bukkit.entity.Entity src = e.getCombuster();
        if (src instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof org.bukkit.entity.Entity sh) src = sh;
        if (src instanceof LivingEntity le && crimsonDef(le) != null) e.setCancelled(true);
    }

    private static MobDef crimsonDef(LivingEntity e) {
        MobDef d;
        try { d = Sky.mobs().defOf(e); } catch (RuntimeException ex) { return null; }
        return d != null && d.id != null && d.id.startsWith("crimson_") ? d : null;
    }

    private static MobBehaviour ranged(int everyTicks, double mult, Particle particle, Sound sound, double range) {
        return new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                Player t = target(mob, range);
                if (t == null || !mob.hasLineOfSight(t) || !due(mob, everyTicks)) return;
                Location from = mob.getEyeLocation();
                Vector step = t.getEyeLocation().toVector().subtract(from.toVector());
                double len = step.length();
                step.normalize().multiply(0.6);
                Location at = from.clone();
                for (double d = 0; d < len; d += 0.6) { at.add(step); mob.getWorld().spawnParticle(particle, at, 1, 0, 0, 0, 0); }
                mob.getWorld().playSound(from, sound, 0.8f, 1f);
                Sky.combat().damagePlayer(t, def.damage * mult, mob, DamageContext.Kind.MOB);
            }
        };
    }

    /** Per-mob cooldown: the first call arms the timer (half an interval), later calls fire once per interval. */
    private static boolean due(LivingEntity mob, int everyTicks) {
        long now = System.currentTimeMillis();
        Long next = NEXT.get(mob.getUniqueId());
        if (next == null) { NEXT.put(mob.getUniqueId(), now + everyTicks * 25L); return false; }
        if (now < next) return false;
        NEXT.put(mob.getUniqueId(), now + everyTicks * 50L + ThreadLocalRandom.current().nextInt(400));
        return true;
    }

    private static Player target(LivingEntity mob, double range) {
        if (!(mob instanceof Mob m)) return null;
        if (!(m.getTarget() instanceof Player p) || !p.isValid() || p.isDead()) return null;
        if (p.getWorld() != mob.getWorld() || p.getLocation().distanceSquared(mob.getLocation()) > range * range) return null;
        return p;
    }

    private static boolean allows(LivingEntity mob, Player p) {
        UUID owner = Sky.mobs().owner(mob);
        return owner == null || owner.equals(p.getUniqueId());
    }

    // ---- one of each mini-boss ------------------------------------------------------------------------------

    /**
     * A mini-boss is unique on the isle. The spawn rule counts only the copies IT spawned, so a boss spawned
     * any other way (the admin {@code boss spawn}, core's generic mob spawn) is invisible to it and the rule
     * adds its own beside it: playtest-endgame fought two Edgewraiths at once, two "DOWN!" lines, +20 rep.
     *
     * <p>When a second unowned copy appears in the same world, the NEWCOMER takes over the older one's
     * fight (health, max, position, target, attack timers, split state) and the older one is removed. The
     * newcomer survives on purpose: if the rule spawned it, it is the copy the rule counts, so the rule stays
     * satisfied; if it was the older one the rule counted, the removal frees the rule, which respawns on its
     * next tick and that copy takes over in turn. Either way it settles within a second on ONE boss the rule
     * owns, whose death starts the real respawn timer. Owned copies ({@code spawn <mob> <player>}) are a
     * player's private test fight and are left alone.
     */
    static void keepSingle(LivingEntity fresh, MobDef def) {
        if (Sky.mobs().owner(fresh) != null) return;
        java.util.List<LivingEntity> older = new java.util.ArrayList<>();
        for (LivingEntity e : Sky.mobs().live(def.id))
            if (e != fresh && e.isValid() && !e.isDead() && e.getWorld() == fresh.getWorld() && Sky.mobs().owner(e) == null) older.add(e);
        if (older.isEmpty()) return;
        // the copy whose fight is furthest along is the one the players are in
        LivingEntity keep = older.get(0);
        for (LivingEntity e : older) if (fraction(e) < fraction(keep)) keep = e;
        double max = Sky.mobs().maxHealth(keep), hp = Sky.mobs().health(keep);
        if (max > 0) Sky.mobs().setMaxHealth(fresh, max);
        if (hp > 0) Sky.mobs().setHealth(fresh, hp);
        fresh.teleport(keep.getLocation());
        if (keep instanceof Mob km && fresh instanceof Mob fm && km.getTarget() != null) fm.setTarget(km.getTarget());
        Long next = NEXT.get(keep.getUniqueId());
        if (next != null) NEXT.put(fresh.getUniqueId(), next);
        if (SPLIT.contains(keep.getUniqueId())) SPLIT.add(fresh.getUniqueId());
        // the split blazes now answer to the copy that carries the fight on (else they would burn out as orphans)
        for (Child c : CHILDREN.values()) for (LivingEntity e : older) if (c.parent.equals(e.getUniqueId())) c.parent = fresh.getUniqueId();
        for (LivingEntity e : older) {
            BossLoot.transfer(e.getUniqueId(), fresh.getUniqueId());   // the damage already dealt still counts
            NEXT.remove(e.getUniqueId());
            SPLIT.remove(e.getUniqueId());
            Sky.mobs().remove(e);
        }
    }

    private static double fraction(LivingEntity e) {
        double max = Sky.mobs().maxHealth(e);
        return max <= 0 ? 1 : Sky.mobs().health(e) / max;
    }

    // ---- bookkeeping ----------------------------------------------------------------------------------------

    static LivingEntity alive(String mobId) {
        for (LivingEntity e : Sky.mobs().live(mobId)) if (e.isValid() && !e.isDead()) return e;
        return null;
    }

    /** Seconds until a mini-boss is due back, 0 when unknown/now. */
    static long respawnLeft(String mobId) {
        Long t = LAST_DEATH.get(mobId);
        if (t == null) return 0;
        int secs = mobId.equals("crimson_magma_boss") ? CrimsonModule.D.magmaRespawn.get() : CrimsonModule.D.bossRespawn.get();
        return Math.max(0, (t + secs * 1000L - System.currentTimeMillis()) / 1000);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        NEXT.remove(e.mob.getUniqueId());
        SPLIT.remove(e.mob.getUniqueId());
        CHILDREN.remove(e.mob.getUniqueId());
        MobDef def = e.def;
        if (def == null || def.id == null || !def.id.startsWith("crimson_") || def.id.startsWith("crimson_dojo")) return;
        Player p = e.killer;
        if (def.has("boss")) {
            LAST_DEATH.put(def.id, System.currentTimeMillis());
            BossLoot.bossDown(e);                                  // every sharer: loot, rep, bits, spoils, quests
            return;
        }
        if (p != null && def.id.equals("crimson_vanquisher") && CrimsonModule.ready(p)) {
            CrimsonModule.data(p).bossKills.merge(def.id, 1, Integer::sum);
            return;
        }
        if (p != null && CrimsonModule.ISLAND.equals(Sky.worlds().islandOf(e.location.getWorld()))
                && ThreadLocalRandom.current().nextDouble() < vanquisherChance(p)) {
            Location at = e.location.clone().add(0, 0.5, 0);
            try {
                LivingEntity v = Sky.mobs().spawn("crimson_vanquisher", at, p);
                if (v != null) {
                    Msg.send(p, "&5&lA VANQUISHER IS SPAWNING NEARBY!");
                    Msg.sound(p, Sound.ENTITY_WITHER_SPAWN, 1.2f);
                }
            } catch (RuntimeException ignored) { }
        }
    }

    /** The dial's base chance, raised by the killer's "Crimson Serendipity" attribute (hunting; +x% relative). */
    static double vanquisherChance(Player p) {
        return CrimsonRules.vanquisherChance(CrimsonModule.D.vanquisherChance.get(), Attrs.value(p, "crimson_serendipity"));
    }

    // ---- the volcano ------------------------------------------------------------------------------------------

    @EventHandler
    public void onStart(SkyEvents.CalendarStart e) {
        if (!ERUPTION.equals(e.eventId)) return;
        World w = Sky.worlds().world(CrimsonModule.ISLAND);
        if (w == null) return;
        for (Player p : w.getPlayers()) {
            Msg.send(p, "&c&lTHE BLAZING VOLCANO IS ERUPTING! &eFlares pour down its slopes and the lava teems with life.");
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.5f);
        }
    }

    @EventHandler
    public void onEnd(SkyEvents.CalendarEnd e) {
        if (!ERUPTION.equals(e.eventId)) return;
        World w = Sky.worlds().world(CrimsonModule.ISLAND);
        if (w == null) return;
        for (Player p : w.getPlayers()) Msg.send(p, "&7The volcano settles back into a sullen rumble.");
    }

    /** Every second: eruption particles over the crater. */
    static void tick() {
        ticks++;
        if (ticks % 600 == 0) NEXT.values().removeIf(t -> t < System.currentTimeMillis() - 600_000L);
        if (ticks % 60 == 0) BossLoot.sweep();
        tickChildren();
        if (!CrimsonModule.D.eruptionFx.get() || !Sky.calendar().active(ERUPTION)) return;
        World w = Sky.worlds().world(CrimsonModule.ISLAND);
        if (w == null || w.getPlayers().isEmpty()) return;
        Location crater = Sky.worlds().anchor(CrimsonModule.ISLAND, "crimson.crater_lookout");
        if (crater == null) return;
        Location top = crater.clone().add(0, 4, -16);
        w.spawnParticle(Particle.LAVA, top, 40, 6, 2, 6, 0);
        w.spawnParticle(Particle.LARGE_SMOKE, top.clone().add(0, 6, 0), 30, 4, 4, 4, 0.02);
        if (ticks % 5 == 0) w.playSound(top, Sound.BLOCK_LAVA_POP, 4f, 0.5f);
    }
}
