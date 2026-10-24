package com.skyclone.core.combat;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.CombatService;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.combat.DamageContext.Kind;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.player.VitalsService;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatService;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * The damage pipeline. Order for a player's hit:
 * <ol>
 *   <li>weapon damage from {@code Sky.items().sheet(weapon).damage()} (0 for fists / non-sky items)</li>
 *   <li>crit rolled from CRIT_CHANCE (so modifiers can read or force {@code ctx.crit})</li>
 *   <li>every DamageModifier, lowest priority first</li>
 *   <li>formula (DamageMath) -> mob magic resistance -> boss cap -> target defense -> floor =&gt; ctx.finalDamage</li>
 *   <li>MobBehaviour.onDamaged on the victim (may change finalDamage or cancel)</li>
 *   <li>SkyEvents.Damage (cancellable, may change finalDamage)</li>
 *   <li>apply, indicator, ferocity strikes</li>
 * </ol>
 */
final class CombatServiceImpl implements CombatService {

    private record Mod(String id, int priority, long seq, DamageModifier modifier) {}

    private final MobServiceImpl mobs;
    private volatile List<Mod> modifiers = List.of();
    private long seq;
    private final Set<String> modWarned = new HashSet<>();
    final Set<UUID> debug = new HashSet<>();
    private final Map<UUID, Long> lastKillTick = new HashMap<>();
    private final Map<UUID, Long> reqMsg = new HashMap<>();

    /** Set by CombatListener while it routes a vanilla event (vanilla plays the hurt animation itself). */
    boolean inVanillaEvent;
    /** One-shot multiplier for the next playerAttack (bow draw force), reset after use. */
    double pendingMultiplier = 1.0;
    /** Re-entrancy guard: a behaviour's onAttack calling damagePlayer does not re-run onAttack. */
    private int attackHookDepth;

    CombatServiceImpl(MobServiceImpl mobs) { this.mobs = mobs; }

    // ---- modifiers ----------------------------------------------------------------------------

    @Override
    public synchronized void addModifier(String id, int priority, DamageModifier modifier) {
        if (id == null || modifier == null) return;
        List<Mod> next = new ArrayList<>(modifiers);
        next.removeIf(m -> m.id.equals(id));
        next.add(new Mod(id, priority, seq++, modifier));
        next.sort(Comparator.comparingInt(Mod::priority).thenComparingLong(Mod::seq));
        modifiers = List.copyOf(next);
    }

    List<String> modifierIds() {
        List<String> out = new ArrayList<>();
        for (Mod m : modifiers) out.add(m.priority + " " + m.id);
        return out;
    }

    private void runModifiers(DamageContext ctx) {
        for (Mod m : modifiers) {
            try {
                m.modifier.modify(ctx);
            } catch (Throwable t) {
                if (modWarned.add(m.id)) Sky.log().log(Level.WARNING, "[combat] damage modifier '" + m.id + "' threw (logged once)", t);
            }
            if (ctx.cancelled) return;
        }
        if (!Double.isFinite(ctx.additive)) ctx.additive = 0;
        if (!Double.isFinite(ctx.multiplicative) || ctx.multiplicative < 0) ctx.multiplicative = 1;
        if (!Double.isFinite(ctx.flatBonus)) ctx.flatBonus = 0;
        if (!Double.isFinite(ctx.base)) ctx.base = 0;
    }

    // ---- player -> target ---------------------------------------------------------------------

    @Override
    public double playerAttack(Player attacker, LivingEntity victim, ItemStack weapon, Kind kind, double multiplier) {
        if (!(multiplier >= 0) || Double.isInfinite(multiplier)) multiplier = 1.0;
        pendingMultiplier = multiplier;
        return playerAttack(attacker, victim, weapon, kind);
    }

    @Override
    public double playerAttack(Player attacker, LivingEntity victim, ItemStack weapon, Kind kind) {
        double force = pendingMultiplier;
        pendingMultiplier = 1.0;
        if (attacker == null || victim == null || attacker == victim) return 0;
        if (!victim.isValid() || victim.isDead() || !attacker.isOnline()) return 0;
        if (kind == null) kind = Kind.MELEE;
        LiveMob lm = mobs.get(victim);
        Player pv = victim instanceof Player p ? p : null;
        if (lm == null && pv == null) return 0;
        if (lm != null && (lm.dead || !mobs.canDamage(attacker, lm, true))) return 0;
        if (pv != null && !pvpAllowed(attacker, pv)) return 0;

        StatService st = Sky.stats();
        double weaponDamage = weaponDamage(attacker, weapon);
        DamageContext ctx = new DamageContext(attacker, victim, kind, weapon, weaponDamage);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        ctx.crit = DamageMath.critRoll(st.get(attacker, Stat.CRIT_CHANCE), rnd.nextDouble());
        if (force != 1.0) ctx.multiplicative *= force;
        runModifiers(ctx);
        if (ctx.cancelled) return 0;

        double str = st.get(attacker, Stat.STRENGTH);
        double cd = st.get(attacker, Stat.CRIT_DAMAGE);
        double initial = DamageMath.melee(ctx.base, ctx.flatBonus, str, ctx.additive, ctx.multiplicative, ctx.crit, cd);
        double defense = targetDefense(victim, lm);
        ctx.finalDamage = targetSide(initial, lm, defense);

        double dealt = finish(ctx, lm, attacker);
        if (dealt < 0) return 0;

        if (debug.contains(attacker.getUniqueId())) {
            Msg.send(attacker, "&8[&cHit&8] &7base &f5+" + fmt(ctx.base) + (ctx.flatBonus != 0 ? "&7+&f" + fmt(ctx.flatBonus) : "")
                    + " &7str &c" + fmt(str) + "% &7add &a+" + fmt(ctx.additive) + "% &7mult &dx" + fmt(ctx.multiplicative)
                    + (ctx.crit ? " &9CRIT x" + fmt(1 + cd / 100) : " &8no crit")
                    + " &7def &a" + fmt(defense) + " &7-> &f" + Text.commasInt(dealt)
                    + (ctx.tags.isEmpty() ? "" : " &8" + ctx.tags));
        }

        if (kind == Kind.MELEE || kind == Kind.PROJECTILE) {
            int extra = DamageMath.ferocityStrikes(st.get(attacker, Stat.FEROCITY), CombatDials.FEROCITY_CAP.get(), rnd.nextDouble());
            if (extra > 0) scheduleFerocity(attacker, victim, ctx, dealt, extra);
        }
        return dealt;
    }

    private double weaponDamage(Player attacker, ItemStack weapon) {
        if (weapon == null || weapon.getType().isAir()) return 0;
        try {
            ItemService items = Sky.items();
            if (items.idOf(weapon) == null) return 0;
            if (CombatDials.ENFORCE_WEAPON_REQS.get() && !items.meetsRequirements(attacker, weapon, false)) {
                long now = System.currentTimeMillis();
                Long last = reqMsg.get(attacker.getUniqueId());
                if (last == null || now - last > 3000) {
                    reqMsg.put(attacker.getUniqueId(), now);
                    items.meetsRequirements(attacker, weapon, true);
                }
                return 0;
            }
            return Math.max(0, items.sheet(weapon).damage());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void scheduleFerocity(Player attacker, LivingEntity victim, DamageContext src, double dealt, int extra) {
        long delay = Math.max(1, CombatDials.FEROCITY_DELAY.get());
        for (int i = 1; i <= extra; i++) {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!attacker.isOnline() || !victim.isValid() || victim.isDead()) return;
                LiveMob lm = mobs.get(victim);
                Player pv = victim instanceof Player p ? p : null;
                if (lm == null && pv == null) return;
                if (lm != null && lm.dead) return;
                if (pv != null && !pvpAllowed(attacker, pv)) return;
                DamageContext f = new DamageContext(attacker, victim, Kind.FEROCITY, src.weapon, src.base);
                f.crit = src.crit;
                f.flatBonus = src.flatBonus;
                f.additive = src.additive;
                f.multiplicative = src.multiplicative;
                f.tags.addAll(src.tags);
                f.tags.add("ferocity");
                f.finalDamage = dealt;
                if (finish(f, lm, attacker) >= 0) ferocityEffect(victim);
            }, delay * i);
        }
    }

    private static void ferocityEffect(LivingEntity victim) {
        try {
            Location c = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
            World w = c.getWorld();
            Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(200, 20, 20), 1.1f);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Vector dir = new Vector(r.nextDouble(-1, 1), r.nextDouble(-0.6, 0.6), r.nextDouble(-1, 1)).normalize().multiply(0.12);
            Location p = c.clone().subtract(dir.clone().multiply(6));
            for (int i = 0; i < 12; i++) {
                w.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, red);
                p.add(dir);
            }
            w.playSound(c, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.6f, 1.6f);
            w.playSound(c, Sound.ITEM_FLINTANDSTEEL_USE, 0.5f, 0.6f);
        } catch (RuntimeException ignored) { }
    }

    // ---- magic --------------------------------------------------------------------------------

    @Override
    public double magicDamage(Player attacker, LivingEntity victim, double baseDamage, double scaling, String abilityName) {
        pendingMultiplier = 1.0;
        if (attacker == null || victim == null || attacker == victim || !victim.isValid() || victim.isDead()) return 0;
        LiveMob lm = mobs.get(victim);
        Player pv = victim instanceof Player p ? p : null;
        if (lm == null && pv == null) return 0;
        if (lm != null && (lm.dead || !mobs.canDamage(attacker, lm, true))) return 0;
        if (pv != null && !pvpAllowed(attacker, pv)) return 0;

        StatService st = Sky.stats();
        DamageContext ctx = new DamageContext(attacker, victim, Kind.MAGIC, attacker.getInventory().getItemInMainHand(), Math.max(0, baseDamage));
        ctx.tags.add("ability");
        if (abilityName != null && !abilityName.isBlank()) ctx.tags.add("ability:" + abilityName);
        runModifiers(ctx);
        if (ctx.cancelled) return 0;
        ctx.crit = false;   // abilities never crit (research 01 §4.2)
        double initial = DamageMath.magic(ctx.base + ctx.flatBonus, st.get(attacker, Stat.INTELLIGENCE), scaling,
                st.get(attacker, Stat.ABILITY_DAMAGE), ctx.additive, ctx.multiplicative);
        ctx.finalDamage = targetSide(initial, lm, targetDefense(victim, lm));
        double dealt = finish(ctx, lm, attacker);
        if (dealt > 0 && debug.contains(attacker.getUniqueId())) {
            Msg.send(attacker, "&8[&bMagic&8] &7base &f" + fmt(ctx.base) + " &7int &b" + fmt(st.get(attacker, Stat.INTELLIGENCE))
                    + " &7x" + fmt(scaling) + " &7AD &c" + fmt(st.get(attacker, Stat.ABILITY_DAMAGE)) + "% &7add &a+" + fmt(ctx.additive)
                    + "% &7mult &dx" + fmt(ctx.multiplicative) + " &7-> &f" + Text.commasInt(dealt));
        }
        return Math.max(0, dealt);
    }

    // ---- raw ----------------------------------------------------------------------------------

    @Override
    public double rawDamage(LivingEntity victim, double amount, Entity source, Kind kind) {
        pendingMultiplier = 1.0;
        if (victim == null || !victim.isValid() || victim.isDead() || !(amount > 0)) return 0;
        if (kind == null) kind = Kind.TRUE;
        LivingEntity attacker = resolveAttacker(source);
        Player ap = attacker instanceof Player p ? p : null;
        LiveMob lm = mobs.get(victim);
        if (lm != null) {
            if (lm.dead || (ap != null && !mobs.canDamage(ap, lm, false))) return 0;
            DamageContext ctx = new DamageContext(attacker, victim, kind, null, amount);
            ctx.tags.add("raw");
            ctx.finalDamage = Math.floor(amount);
            return Math.max(0, finish(ctx, lm, ap));
        }
        if (victim instanceof Player pv) {
            if (!damageable(pv)) return 0;
            if (ap != null && ap != pv && !pvpAllowed(ap, pv)) return 0;
            DamageContext ctx = new DamageContext(attacker, pv, kind, null, amount);
            ctx.tags.add("raw");
            ctx.finalDamage = Math.floor(amount);
            return Math.max(0, finish(ctx, null, ap));
        }
        return 0;
    }

    // ---- to a player --------------------------------------------------------------------------

    @Override
    public double damagePlayer(Player victim, double rawDamage, Entity source, Kind kind) {
        return damagePlayer(victim, rawDamage, source, kind, true);
    }

    double damagePlayer(Player victim, double rawDamage, Entity source, Kind kind, boolean applyDefense) {
        pendingMultiplier = 1.0;
        if (victim == null || !victim.isOnline() || victim.isDead() || !(rawDamage > 0) || !damageable(victim)) return 0;
        if (kind == null) kind = Kind.MOB;
        LivingEntity attacker = resolveAttacker(source);
        DamageContext ctx = new DamageContext(attacker, victim, kind, null, rawDamage);
        if (source instanceof Projectile) ctx.tags.add("projectile");
        LiveMob srcMob = attacker == null ? null : mobs.get(attacker);
        if (srcMob != null) {
            if (srcMob.dead) return 0;
            if (!(victim instanceof Player vp) || !srcMob.allows(vp)) { if (srcMob.owner != null) return 0; }   // an owned boss only fights its owner + allies
            ctx.tags.add("mob:" + srcMob.def.id);
            if (attackHookDepth == 0) {
                attackHookDepth++;
                try { mobs.runOnAttack(srcMob, victim, ctx); } finally { attackHookDepth--; }
                if (ctx.cancelled) return 0;
            }
        }
        runModifiers(ctx);
        if (ctx.cancelled) return 0;
        double raw = DamageMath.incoming(ctx.base, ctx.flatBonus, ctx.additive, ctx.multiplicative);
        StatService st = Sky.stats();
        double def = 0;
        if (applyDefense) def = kind == Kind.TRUE ? st.get(victim, Stat.TRUE_DEFENSE) : st.get(victim, Stat.DEFENSE);
        ctx.finalDamage = Math.floor(DamageMath.reduce(raw, def));
        return Math.max(0, finish(ctx, null, null));
    }

    /** Environment damage: already a %-of-max number; defense only if the dial says so. */
    double environment(Player victim, double amount, String cause) {
        if (!(amount > 0)) return 0;
        lastCause.put(victim.getUniqueId(), cause);
        try {
            return damagePlayer(victim, amount, null, Kind.ENVIRONMENT, CombatDials.ENV_DEFENSE.get());
        } finally {
            lastCause.remove(victim.getUniqueId());
        }
    }

    private final Map<UUID, String> lastCause = new HashMap<>();

    // ---- shared tail ----------------------------------------------------------------------------

    /**
     * onDamaged -> SkyEvents.Damage -> apply -> indicator. Returns the damage dealt, or -1 if the hit
     * was cancelled.
     */
    private double finish(DamageContext ctx, LiveMob lm, Player attacker) {
        if (lm != null) {
            mobs.runOnDamaged(lm, ctx);
            if (ctx.cancelled || lm.dead) return -1;
        }
        SkyEvents.Damage ev = new SkyEvents.Damage(ctx);
        try {
            Bukkit.getPluginManager().callEvent(ev);
        } catch (Throwable t) {
            Sky.log().log(Level.WARNING, "[combat] a Damage listener threw", t);
        }
        if (ctx.cancelled) return -1;
        double dealt = Double.isFinite(ctx.finalDamage) ? Math.floor(Math.max(0, ctx.finalDamage)) : 0;
        LivingEntity victim = ctx.victim;
        if (lm != null) {
            if (lm.dead) return -1;
            if (!inVanillaEvent || ctx.kind == Kind.FEROCITY) hurtEffect(victim);
            Location at = victim.getLocation().add(0, Math.max(0.5, victim.getHeight() * 0.6), 0);
            mobs.damage(lm, dealt, attacker);
            if (CombatCore.indicators != null && dealt > 0) {
                CombatCore.indicators.show(at, CombatFormat.indicator(dealt, ctx.crit, ctx.kind), null);
            }
            return dealt;
        }
        if (victim instanceof Player pv) {
            if (!inVanillaEvent || ctx.kind == Kind.FEROCITY) hurtEffect(pv);
            boolean pvp = ctx.attacker instanceof Player;
            if (CombatCore.indicators != null && dealt > 0 && (pvp || CombatDials.PLAYER_INDICATORS.get())) {
                CombatCore.indicators.show(pv.getLocation().add(0, pv.getHeight() * 0.6, 0),
                        CombatFormat.indicator(dealt, ctx.crit, ctx.kind), null);
            }
            applyToPlayer(pv, dealt, causeOf(ctx));
            return dealt;
        }
        return -1;
    }

    private String causeOf(DamageContext ctx) {
        if (ctx.attacker instanceof Player p) return "player:" + p.getName();
        if (ctx.attacker != null) {
            LiveMob lm = mobs.get(ctx.attacker);
            if (lm != null) return "mob:" + lm.def.id;
            return "entity:" + ctx.attacker.getType().name().toLowerCase();
        }
        if (ctx.victim instanceof Player p) {
            String c = lastCause.remove(p.getUniqueId());
            if (c != null) return c;
        }
        return ctx.kind.name().toLowerCase();
    }

    /** Absorption first, then health; at 0 the vitals service kills the player (once per tick). */
    void applyToPlayer(Player p, double dmg, String cause) {
        if (!(dmg > 0)) return;
        VitalsService v = Sky.vitals();
        double abs = v.absorption(p);
        if (abs > 0) {
            double take = Math.min(abs, dmg);
            v.addAbsorption(p, -take);
            dmg -= take;
        }
        if (dmg <= 0) return;
        double hp = v.health(p) - dmg;
        if (hp <= 0) {
            killOnce(p, cause);
        } else {
            v.setHealth(p, hp);
        }
    }

    void killOnce(Player p, String cause) {
        long tick = Bukkit.getCurrentTick();
        Long last = lastKillTick.get(p.getUniqueId());
        if (last != null && tick - last < 10) return;
        lastKillTick.put(p.getUniqueId(), tick);
        try {
            Sky.vitals().kill(p, cause == null ? "unknown" : cause);
        } catch (Throwable t) {
            Sky.log().log(Level.WARNING, "[combat] vitals.kill threw", t);
        }
    }

    private static void hurtEffect(LivingEntity e) {
        try {
            e.playHurtAnimation(0f);
            Sound s = e.getHurtSound();
            if (s != null && !e.isSilent()) e.getWorld().playSound(e.getLocation(), s, 0.8f, 1f);
        } catch (RuntimeException ignored) { }
    }

    // ---- target-side maths ----------------------------------------------------------------------

    private double targetDefense(LivingEntity victim, LiveMob lm) {
        if (lm != null) return Math.max(0, lm.def.defense);
        if (victim instanceof Player p) return Sky.stats().get(p, Stat.DEFENSE);
        return 0;
    }

    private static double targetSide(double initial, LiveMob lm, double defense) {
        double mr = 0, cap = 0;
        if (lm != null) {
            mr = lm.def.magicResistance > 0 ? lm.def.magicResistance : MobServiceImpl.num(lm.def.extra, "magicResistance", 0);
            cap = lm.def.damageCap > 0 ? lm.def.damageCap : MobServiceImpl.num(lm.def.extra, "damageCap", 0);
        }
        return DamageMath.mobSide(initial, mr, cap, defense);
    }

    // ---- rules -----------------------------------------------------------------------------------

    /** PvP needs both players in a PVP zone (or the pvp-everywhere dial). */
    boolean pvpAllowed(Player a, Player b) {
        if (a == null || b == null || a == b) return false;
        if (!damageable(b)) return false;
        if (a.getWorld() != b.getWorld()) return false;
        if (CombatDials.PVP_EVERYWHERE.get()) return true;
        return inPvpZone(a.getLocation()) && inPvpZone(b.getLocation());
    }

    private static boolean inPvpZone(Location l) {
        if (!Sky.has(WorldService.class)) return false;
        try {
            Layout.Zone z = Sky.worlds().zoneAt(l);
            return z != null && z.flags() != null && z.flags().contains(Layout.Zone.PVP);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean damageable(Player p) {
        GameMode g = p.getGameMode();
        return g != GameMode.CREATIVE && g != GameMode.SPECTATOR && !p.isInvulnerable();
    }

    static LivingEntity resolveAttacker(Entity source) {
        if (source instanceof LivingEntity le) return le;
        if (source instanceof Projectile pr && pr.getShooter() instanceof LivingEntity le) return le;
        return null;
    }

    // ---- indicators -------------------------------------------------------------------------------

    @Override
    public void indicator(Location at, double damage, boolean crit, Kind kind) {
        if (at == null || CombatCore.indicators == null) return;
        CombatCore.indicators.show(at, CombatFormat.indicator(damage, crit, kind), null);
    }

    private static String fmt(double v) { return Text.commas(Math.round(v * 100) / 100.0); }

    /** Drop per-player throttles on quit. */
    void forget(UUID player) {
        lastKillTick.remove(player);
        reqMsg.remove(player);
        lastCause.remove(player);
    }
}
