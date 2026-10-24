package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything about arrows: normal bows (drawn), shortbows (instant, on click, with a shot cooldown
 * set by Bonus Attack Speed), arrow types and poisons, multi-arrow bows, homing, infusions, and the
 * on-hit effects. Core routes an arrow's damage through playerAttack using the bow recorded at
 * EntityShootBowEvent; shortbow arrows fire a synthetic EntityShootBowEvent so core records them too.
 */
final class Bows implements Listener {

    /** Last "no arrows" chat line per player (3 s throttle). */
    private static final Map<UUID, Long> noArrowMsgAt = new HashMap<>();

    /** Everything we know about one flying arrow. */
    static final class Shot {
        UUID shooter;
        String bowId;
        ItemStack bow;
        String arrowId;
        double arrowDamage;
        double arrowCrit;
        double mult = 1.0;
        double infusionMult = 1.0;
        String infusionVs;
        String poison;
        boolean homing;
        boolean dragonHoming;
        boolean nastyBite;
        boolean salvationBeam;
        boolean forceCrit;
        boolean secondary;
        int victims;
        long at = Buffs.now();
        final Set<UUID> auraHit = new HashSet<>();

        Shot copySecondary(double mult) {
            Shot s = new Shot();
            s.shooter = shooter; s.bowId = bowId; s.bow = bow; s.arrowId = arrowId; s.arrowDamage = arrowDamage;
            s.arrowCrit = arrowCrit; s.mult = this.mult * mult; s.infusionMult = infusionMult; s.infusionVs = infusionVs;
            s.secondary = true; s.forceCrit = forceCrit;
            return s;
        }
    }

    /** Options for a code-fired shot. */
    static final class Opts {
        double mult = 1.0;
        boolean ignoreCooldown;
        boolean nastyBite;
        boolean quiet;
    }

    private final Traits traits;
    private final Buffs buffs;
    private final Map<UUID, Shot> shots = new HashMap<>();
    private final Map<UUID, AbstractArrow> arrows = new HashMap<>();
    private final Map<UUID, Long> nextShot = new HashMap<>();
    private final Map<UUID, Integer> terminatorHits = new HashMap<>();
    private final Map<UUID, Double> soulMarks = new HashMap<>();
    private boolean synthetic;
    /** Set by the module: refreshes the Quiver Arrow count after a drawn shot. */
    QuiverArrow quiverArrow;

    /** The shot whose damage is being routed right now (set around core's handling). */
    static Shot current;
    static LivingEntity currentVictim;

    Bows(Traits traits, Buffs buffs) {
        this.traits = traits;
        this.buffs = buffs;
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 2L, 2L);
    }

    // =========================================================================================
    // firing
    // =========================================================================================

    /** Fire the held shortbow (or any bow) from code. Returns true when at least one arrow flew. */
    boolean shoot(Player p, ItemStack bow, Opts o) {
        String bowId = Traits.idOf(bow);
        ItemDef def = bowId == null ? null : Sky.items().def(bowId);
        if (def == null) return false;
        Trait t = traits.of(bowId);
        long now = Buffs.now();
        if (!o.ignoreCooldown) {
            Long next = nextShot.get(p.getUniqueId());
            if (next != null && next > now) return false;
        }
        if (!Sky.items().meetsRequirements(p, bow, true)) return false;
        ItemDef arrow = Quiver.pick(p);
        if (arrow == null && WeaponDials.SHORTBOW_NEEDS_ARROWS.get()) {
            // wave-4 endgame playtest: a shortbow fires on every click, so an empty quiver printed this line per click
            // (dozens a minute). Chat once per 3 s; the action bar says it every time.
            if (!o.quiet) {
                Sky.vitals().flash(p, "&c&lNO ARROWS", 30);
                long nowMs = System.currentTimeMillis();
                Long last = noArrowMsgAt.get(p.getUniqueId());
                if (last == null || nowMs - last >= 3000) { noArrowMsgAt.put(p.getUniqueId(), nowMs); Msg.error(p, "You don't have any arrows! Put some in your inventory or quiver."); }
            }
            return false;
        }
        if (arrow != null && !Quiver.saved(bow) && !Quiver.consume(p, arrow.id) && WeaponDials.SHORTBOW_NEEDS_ARROWS.get()) return false;
        if (!o.ignoreCooldown) {
            double as = Sky.stats().get(p, Stat.ATTACK_SPEED);
            double base = t.shortbow > 0 ? t.shortbow : 0.5;
            nextShot.put(p.getUniqueId(), now + (long) (WeaponMath.shortbowCooldown(base, as, WeaponDials.SHORTBOW_MIN_CD.get()) * 1000));
        }
        Shot proto = new Shot();
        proto.shooter = p.getUniqueId();
        proto.bowId = bowId;
        proto.bow = bow.clone();
        fillArrow(proto, arrow);
        fillExtras(p, proto, t);
        proto.mult *= o.mult;
        proto.nastyBite = o.nastyBite;

        int count = arrowCount(bow, t);
        double spacing = WeaponDials.SPREAD_DEGREES.get();
        Vector dir = p.getEyeLocation().getDirection();
        double speed = WeaponDials.ARROW_SPEED.get() * (t.speed > 0 ? t.speed : 1.0);
        double[] yaws = WeaponMath.spread(count, spacing);
        for (int i = 0; i < yaws.length; i++) {
            boolean center = i == yaws.length / 2;
            Shot s = proto;
            if (!center) {   // every arrow gets its own record; side arrows of a multi-shot deal extraPct and may home
                s = proto.copySecondary(t.volley > 0 ? 1.0 : (t.extraPct > 0 ? t.extraPct : 1.0));
                s.secondary = false;
                s.homing = t.homing;
                s.dragonHoming = proto.dragonHoming;
            }
            Vector v = rotateYaw(dir, yaws[i]).multiply(speed);
            Arrow a = p.launchProjectile(Arrow.class, v);
            prepare(a, s, t);
            fireSynthetic(p, bow, a, 1.0f);
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.2f);
        WeaponData d = WeaponsModule.data(p);
        if (d != null) d.arrowsShot += yaws.length;
        return true;
    }

    private int arrowCount(ItemStack bow, Trait t) {
        if (t.volley > 0) return t.volley;
        if (t.hurricane) return WeaponMath.hurricaneArrows(Passives.kills(bow));
        return Math.max(1, t.arrows);
    }

    private void fillArrow(Shot s, ItemDef arrow) {
        if (arrow == null) { s.arrowId = null; return; }
        s.arrowId = arrow.id;
        s.arrowDamage = arrow.damage;
        s.arrowCrit = Quiver.arrowCritChance(arrow);
    }

    /** Infusion and poison: consumed once per shot (not per arrow). */
    private void fillExtras(Player p, Shot s, Trait t) {
        if (t.infusion != null && t.infusion.item != null && Quiver.available(p, t.infusion.item) > 0
                && Quiver.consume(p, t.infusion.item)) {
            s.infusionMult = t.infusion.mult;
            s.infusionVs = t.infusion.vs;
        }
        ItemDef poison = Quiver.poison(p);
        if (poison != null && Quiver.consume(p, poison.id)) s.poison = poison.id;
        if (t.dragonPct > 0) s.dragonHoming = true;
    }

    private void prepare(AbstractArrow a, Shot s, Trait t) {
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        a.setCritical(true);
        if (t.pierce > 0) a.setPierceLevel(Math.min(127, t.pierce));
        shots.put(a.getUniqueId(), s);
        arrows.put(a.getUniqueId(), a);
        if (t.witherSkull) skull(a);
    }

    // ---- Soulstealer Bow: the arrow flies as a wither skull ---------------------------------------------

    /** Arrow id -> the skull drawn in its place (removed with the arrow). */
    private final Map<UUID, org.bukkit.entity.ItemDisplay> skulls = new HashMap<>();

    private void skull(AbstractArrow a) {
        try {
            a.setCritical(false);
            a.setVisibleByDefault(false);
            org.bukkit.entity.ItemDisplay d = a.getWorld().spawn(a.getLocation(), org.bukkit.entity.ItemDisplay.class, x -> {
                x.setItemStack(new ItemStack(org.bukkit.Material.WITHER_SKELETON_SKULL));
                x.setPersistent(false);
                x.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            });
            a.addPassenger(d);
            skulls.put(a.getUniqueId(), d);
        } catch (RuntimeException ignored) { }   // cosmetic only: a plain arrow still explodes
    }

    private void dropSkull(UUID arrowId) {
        org.bukkit.entity.ItemDisplay d = skulls.remove(arrowId);
        if (d != null && d.isValid()) {
            Fx.burst(d.getLocation(), Particle.SMOKE, 8, 0.2);
            d.remove();
        }
    }

    // ---- Stinger Bow: arrows that hit a dungeon teammate buff them --------------------------------------

    /** True when the hit was a Sting on a teammate (the arrow is spent, no damage beyond the 1 the ability costs). */
    private boolean sting(Player shooter, Player mate, Trait t) {
        if (!t.sting || shooter.equals(mate) || !Passives.inDungeon(shooter) || !Passives.inDungeon(mate)) return false;
        boolean ally;
        try {
            ally = Sky.service(com.skyclone.api.feature.Features.Party.class).members(shooter).contains(mate);
        } catch (RuntimeException e) {
            ally = false;
        }
        if (!ally) return false;
        if (!Sky.vitals().useMana(shooter, 100)) {   // research 06: Mana Cost 100
            Sky.vitals().flash(shooter, "&c&lNOT ENOUGH MANA", 20);
            return true;
        }
        double secs = WeaponDials.STING_SECONDS.get();
        buffs.buff(mate, "sting_speed", Stat.SPEED, 20, secs);      // research 06: +20 Speed
        buffs.buff(mate, "sting_strength", Stat.STRENGTH, 30, secs); // and +30 Strength for 30s
        Sky.vitals().damage(mate, 1, "sting");                       // but deals 1 damage
        Msg.send(mate, "&a" + shooter.getName() + "'s &6Needle Gift &7gave you &f+20✦ Speed &7and &c+30❁ Strength &7for &a"
                + (int) secs + "s&7!");
        Sky.vitals().flash(shooter, "&b-100 Mana (&6Needle Gift&b)", 25);
        Fx.burst(mate.getLocation().add(0, 1, 0), Particle.HAPPY_VILLAGER, 10, 0.4);
        return true;
    }

    private void fireSynthetic(Player p, ItemStack bow, AbstractArrow a, float force) {
        synthetic = true;
        try {
            Bukkit.getPluginManager().callEvent(new EntityShootBowEvent(p, bow, new ItemStack(org.bukkit.Material.ARROW), a,
                    EquipmentSlot.HAND, force, false));
        } finally {
            synthetic = false;
        }
    }

    static Vector rotateYaw(Vector v, double degrees) {
        if (degrees == 0) return v.clone();
        double r = Math.toRadians(degrees), c = Math.cos(r), s = Math.sin(r);
        return new Vector(v.getX() * c - v.getZ() * s, v.getY(), v.getX() * s + v.getZ() * c);
    }

    // ---- a drawn (normal) bow -------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (synthetic || !(e.getEntity() instanceof Player p) || !(e.getProjectile() instanceof AbstractArrow a)) return;
        ItemStack bow = e.getBow();
        String bowId = Traits.idOf(bow);
        Trait t = traits.of(bowId);
        if (t.isShortbow()) { e.setCancelled(true); return; }   // shortbows never draw
        Shot s = new Shot();
        s.shooter = p.getUniqueId();
        s.bowId = bowId;
        s.bow = bow == null ? null : bow.clone();
        // arrow: the player's preferred type if they have it, else whatever vanilla picked
        ItemDef pick = Quiver.pick(p);
        if (QuiverArrow.isProxy(e.getConsumable())) {
            // drawn from the Quiver Arrow: the proxy is never used up, one real arrow leaves the quiver
            e.setConsumeItem(false);
            if (pick == null || (!Quiver.saved(bow) && !Quiver.consume(p, pick.id))) {
                e.setCancelled(true);
                if (quiverArrow != null) quiverArrow.syncSoon(p, true);
                return;
            }
            fillArrow(s, pick);
            if (quiverArrow != null) quiverArrow.syncSoon(p, true);
        } else if (pick != null && !pick.id.equals(Traits.idOf(e.getConsumable())) && (Quiver.saved(bow) || Quiver.consume(p, pick.id))) {
            e.setConsumeItem(false);
            fillArrow(s, pick);
        } else {
            String vanillaId = Traits.idOf(e.getConsumable());
            ItemDef v = vanillaId == null ? null : Sky.items().def(vanillaId);
            fillArrow(s, v != null && Quiver.usable(p, v) ? v : null);
        }
        fillExtras(p, s, t);
        prepare(a, s, t);
        if (t.speed > 0) a.setVelocity(a.getVelocity().multiply(t.speed));
        WeaponData d = WeaponsModule.data(p);
        if (d != null) d.arrowsShot++;
        int count = arrowCount(bow, t);
        if (count <= 1 || bow == null) return;
        double[] yaws = WeaponMath.spread(count, WeaponDials.SPREAD_DEGREES.get());
        Vector v = a.getVelocity();
        for (int i = 0; i < yaws.length; i++) {
            if (i == yaws.length / 2) continue;
            Shot x = s.copySecondary(t.volley > 0 ? 1.0 : (t.extraPct > 0 ? t.extraPct : 1.0));
            x.secondary = false;
            x.homing = t.homing;
            Arrow extra = p.launchProjectile(Arrow.class, rotateYaw(v, yaws[i]));
            prepare(extra, x, t);
            fireSynthetic(p, bow, extra, e.getForce());
            if (d != null) d.arrowsShot++;
        }
    }

    // ---- shortbow clicks ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Traits.idOf(held);
        Trait t = traits.of(id);
        if (!t.isShortbow()) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (right) e.setUseItemInHand(Event.Result.DENY);
        ItemDef def = Sky.items().def(id);
        AbilityDef.Trigger trig = right ? AbilityDef.Trigger.RIGHT_CLICK : AbilityDef.Trigger.LEFT_CLICK;
        if (def != null) for (AbilityDef a : def.abilities) if (a.trigger == trig) return;   // the ability owns this click
        shoot(p, held, new Opts());
    }

    /** Left-clicking a mob with a shortbow shoots instead of punching. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPunch(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) {
            stash(e);
            return;
        }
        ItemStack held = p.getInventory().getItemInMainHand();
        Trait t = traits.of(Traits.idOf(held));
        if (!t.isShortbow()) return;
        e.setCancelled(true);
        shoot(p, held, new Opts());
    }

    // ---- damage routing: tell the modifiers which arrow is hitting --------------------------------

    private void stash(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof AbstractArrow a)) return;
        Shot s = shots.get(a.getUniqueId());
        if (s == null) return;
        current = s;
        currentVictim = e.getEntity() instanceof LivingEntity le ? le : null;
        s.victims++;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void unstash(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof AbstractArrow) { current = null; currentVictim = null; }
    }

    /** Run a hit from code with a shot context (multi-hit, bounce, aura, explosions, endermen). */
    void hitWith(Shot s, Player p, LivingEntity victim, double extraMult) {
        Shot prevS = current;
        LivingEntity prevV = currentVictim;
        current = s;
        currentVictim = victim;
        try {
            Sky.combat().playerAttack(p, victim, s.bow, DamageContext.Kind.PROJECTILE, extraMult);
        } finally {
            current = prevS;
            currentVictim = prevV;
        }
    }

    // ---- projectile hits: endermen, explosions, cleanup -------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow a)) return;
        Shot s = shots.get(a.getUniqueId());
        if (s == null) return;
        Player p = Bukkit.getPlayer(s.shooter);
        if (p == null) return;
        Trait t = traits.of(s.bowId);
        Entity hitE = e.getHitEntity();
        if (hitE instanceof Player mate && sting(p, mate, t)) {
            e.setCancelled(true);
            a.remove();
            shots.remove(a.getUniqueId());
            dropSkull(a.getUniqueId());
            return;
        }
        if (t.witherSkull && (hitE != null || e.getHitBlock() != null)) dropSkull(a.getUniqueId());
        if (hitE instanceof Enderman en && Fx.target(en)) {
            e.setCancelled(true);
            a.remove();
            if (t.endermen) hitWith(s, p, en, 1.0);
            return;
        }
        if (e.getHitBlock() != null) {
            Location at = a.getLocation();
            if (t.explosive > 0) explode(p, s, at, t.explosive * WeaponDials.EXPLOSIVE_RADIUS_SCALE.get(), null, false);
            if ("EXPLOSIVE_ARROW".equals(s.arrowId)) explode(p, s, at, 2.5 * WeaponDials.EXPLOSIVE_RADIUS_SCALE.get(), null, true);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), a::remove, 20L);
            shots.remove(a.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(PlayerPickupArrowEvent e) {
        if (shots.containsKey(e.getArrow().getUniqueId())) { e.setCancelled(true); e.getArrow().remove(); }
    }

    /**
     * Blast at {@code at}: every mob in range takes the shot's damage (Blast Shot) or a split share
     * (Charged Tip). {@code skip} already took the direct hit.
     */
    void explode(Player p, Shot s, Location at, double radius, LivingEntity skip, boolean split) {
        Fx.burst(at, Particle.EXPLOSION, 1, 0);
        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
        List<LivingEntity> mobs = Fx.mobsNear(at, radius);
        if (skip != null) mobs.remove(skip);
        Shot sec = s.copySecondary(1.0);
        double share = split ? 1.0 / Math.max(1, mobs.size() + (skip != null ? 1 : 0)) : 1.0;
        Fx.explosion(() -> { for (LivingEntity m : mobs) hitWith(sec, p, m, share); });
        if (split && p.getLocation().distance(at) <= radius) {
            Sky.vitals().damage(p, Sky.vitals().maxHealth(p) * 0.10, "explosive arrow");   // research 06: 10% of your health
        }
    }

    // ---- on-hit effects (after core computed the hit) ---------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSkyDamageMark(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Shot s = current;
        if (s == null || ctx.kind != DamageContext.Kind.PROJECTILE || ctx.playerAttacker() == null
                || !ctx.playerAttacker().getUniqueId().equals(s.shooter)) return;
        Trait t = traits.of(s.bowId);
        if (t.mark > 0 && !ctx.tags.contains("soul_burst")) {
            UUID vid = ctx.victim.getUniqueId();
            boolean first = !soulMarks.containsKey(vid);
            soulMarks.merge(vid, ctx.finalDamage, Double::sum);
            ctx.cancelled = true;
            Fx.dust(ctx.victim.getLocation().add(0, ctx.victim.getHeight() + 0.3, 0), Color.fromRGB(120, 40, 160), 1.2f, 4, 0.2);
            if (first) {
                Player p = ctx.playerAttacker();
                LivingEntity v = ctx.victim;
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    Double sum = soulMarks.remove(vid);
                    if (sum == null || !v.isValid() || !p.isOnline()) return;
                    Fx.burst(v.getLocation().add(0, 1, 0), Particle.SOUL, 20, 0.5);
                    Fx.sound(v.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, 1f, 0.8f);
                    Sky.combat().rawDamage(v, sum * 1.10, p, DamageContext.Kind.PROJECTILE);   // research 06: +10%
                }, (long) (t.mark * 20));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkyDamage(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        Shot s = current;
        Player p = ctx.playerAttacker();
        if (s == null || p == null || ctx.kind != DamageContext.Kind.PROJECTILE || !p.getUniqueId().equals(s.shooter)) return;
        LivingEntity v = ctx.victim;
        if (s.secondary || !Fx.target(v)) return;
        Trait t = traits.of(s.bowId);
        // arrow types
        if (s.arrowId != null) {
            switch (s.arrowId) {
                case "ICY_ARROW" -> v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
                case "GLUE_ARROW" -> {
                    if (buffs.markValue(v, "glue_cd") == null) {
                        buffs.mark(v, "glue_cd", 1, 6);           // research 06: 6s per victim
                        buffs.freeze(v, 1.5);
                    }
                }
                case "BOUNCY_ARROW" -> bounce(p, s, v, 0.5, 4);
                case "EXPLOSIVE_ARROW" -> explode(p, s, v.getLocation(), 2.5 * WeaponDials.EXPLOSIVE_RADIUS_SCALE.get(), v, true);
                default -> { }
            }
        }
        if ("TWILIGHT_ARROW_POISON".equals(s.poison)) buffs.mark(v, "twilight", 1.1, 20);
        if ("TOXIC_ARROW_POISON".equals(s.poison)) {
            double dot = ctx.finalDamage * 0.10 / 4.0;   // research 06: +10% of the hit as poison, over 4s
            for (int i = 1; i <= 4; i++) {
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                    if (v.isValid() && p.isOnline()) Sky.combat().rawDamage(v, dot, p, DamageContext.Kind.TRUE);
                }, 20L * i);
            }
        }
        // bows
        if (t.multiHit > 1) {
            Shot sec = s.copySecondary(1.0);
            int n = 0;
            for (LivingEntity m : Fx.mobsNear(v.getLocation(), 3.0)) {
                if (m == v) continue;
                if (++n >= t.multiHit) break;
                hitWith(sec, p, m, 1.0);
            }
        }
        if (t.bounce > 0) bounce(p, s, v, t.bounce, 1);
        if (t.explosive > 0) explode(p, s, v.getLocation(), t.explosive * WeaponDials.EXPLOSIVE_RADIUS_SCALE.get(), v, false);
        if (t.volley > 0) v.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
        if (t.defShred != null) {
            Double st = buffs.markValue(v, "shred:" + p.getUniqueId());
            int stacks = (int) Math.min(t.defShred.stacks, (st == null ? 0 : st) + 1);
            buffs.mark(v, "shred:" + p.getUniqueId(), stacks, 10);
        }
        if ("TERMINATOR".equals(s.bowId) && !s.salvationBeam) {
            terminatorHits.merge(p.getUniqueId(), 1, (a, b) -> Math.min(3, a + b));
        }
        if (s.nastyBite) {
            Sky.vitals().heal(p, 189);   // research 06: heal 189 on hit
            Fx.burst(p.getLocation().add(0, 1, 0), Particle.HEART, 3, 0.4);
        }
    }

    private void bounce(Player p, Shot s, LivingEntity from, double chance, int maxBounces) {
        LivingEntity last = from;
        Set<UUID> done = new HashSet<>();
        done.add(from.getUniqueId());
        for (int b = 1; b <= maxBounces; b++) {
            if (Math.random() >= chance) return;
            LivingEntity next = null;
            for (LivingEntity m : Fx.mobsNear(last.getLocation(), 8)) if (!done.contains(m.getUniqueId())) { next = m; break; }
            if (next == null) return;
            done.add(next.getUniqueId());
            Fx.beam(last.getLocation().add(0, 1, 0), next.getLocation().subtract(last.getLocation()).toVector(),
                    last.getLocation().distance(next.getLocation()), Particle.CRIT);
            hitWith(s.copySecondary(1.0), p, next, WeaponMath.bounceMultiplier(b));
            last = next;
        }
    }

    // ---- Oblivion Shortbow (Terminator) Judgement Beam charge ----------------------------------------

    int terminatorCharge(Player p) { return terminatorHits.getOrDefault(p.getUniqueId(), 0); }

    void resetTerminator(Player p) { terminatorHits.remove(p.getUniqueId()); }

    // ---- per-tick: homing, aura, fuses, cleanup -------------------------------------------------------

    private void tick() {
        if (arrows.isEmpty()) return;
        long now = Buffs.now();
        for (Iterator<Map.Entry<UUID, AbstractArrow>> it = arrows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, AbstractArrow> en = it.next();
            AbstractArrow a = en.getValue();
            Shot s = shots.get(en.getKey());
            if (s == null || !a.isValid() || now - s.at > 30_000) {
                it.remove();
                shots.remove(en.getKey());
                dropSkull(en.getKey());
                if (a.isValid() && s != null && now - s.at > 30_000) a.remove();
                continue;
            }
            if (a.isInBlock() || a.isOnGround()) { dropSkull(en.getKey()); continue; }
            if (skulls.containsKey(en.getKey())) Fx.burst(a.getLocation(), Particle.SMOKE, 1, 0.05);
            Player p = Bukkit.getPlayer(s.shooter);
            if (p == null) continue;
            Trait t = traits.of(s.bowId);
            if (s.homing || s.dragonHoming) home(a, s);
            if (t.aura > 0) aura(p, a, s, t.aura);
            if ("EXPLOSIVE_ARROW".equals(s.arrowId) && now - s.at > 2000) {   // research 06: explodes after 2s
                explode(p, s, a.getLocation(), 2.5 * WeaponDials.EXPLOSIVE_RADIUS_SCALE.get(), null, true);
                a.remove();
            }
        }
    }

    private void home(AbstractArrow a, Shot s) {
        double range = WeaponDials.HOMING_RANGE.get();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        Vector vel = a.getVelocity();
        for (LivingEntity m : Fx.mobsNear(a.getLocation(), range)) {
            if (s.dragonHoming && !s.homing && !MobTypes.is("dragon", m)) continue;
            Vector to = m.getLocation().add(0, m.getHeight() / 2, 0).toVector().subtract(a.getLocation().toVector());
            if (to.dot(vel) <= 0) continue;
            double d = to.lengthSquared();
            if (d < bestD) { bestD = d; best = m; }
        }
        if (best == null) return;
        double speed = Math.max(1.2, vel.length());
        Vector to = best.getLocation().add(0, best.getHeight() / 2, 0).toVector().subtract(a.getLocation().toVector()).normalize();
        a.setVelocity(vel.normalize().multiply(0.55).add(to.multiply(0.45)).normalize().multiply(speed));
    }

    private void aura(Player p, AbstractArrow a, Shot s, double radius) {
        for (LivingEntity m : Fx.mobsNear(a.getLocation(), radius)) {
            if (!s.auraHit.add(m.getUniqueId())) continue;
            Fx.burst(m.getLocation().add(0, 1, 0), Particle.ITEM_SLIME, 6, 0.3);
            hitWith(s.copySecondary(1.0), p, m, 1.0);
        }
    }

    /** True when this shot is an aura arrow whose direct impact must not deal damage (it already did). */
    boolean auraImpact(Shot s) { return s != null && !s.secondary && traits.of(s.bowId).aura > 0; }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        nextShot.remove(id);
        terminatorHits.remove(id);
    }

    int liveArrows() { return arrows.size(); }

    /** At shutdown: no skull displays left floating. */
    void removeSkulls() {
        for (org.bukkit.entity.ItemDisplay d : skulls.values()) if (d.isValid()) d.remove();
        skulls.clear();
    }

    /** Is the player still holding the same kind of bow? (Bullet Storm stops when you swap.) */
    boolean isSame(Player p, ItemStack bow) {
        String a = Traits.idOf(bow), b = Traits.idOf(p.getInventory().getItemInMainHand());
        return a != null && a.equals(b);
    }
}
