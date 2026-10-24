package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The fight mechanics of the Mythological creatures, from the reference wiki's creature pages (wave 5):
 * <ul>
 *   <li><b>Siamese Lynxes</b> - only the marked lynx (angry-villager particles) can be hurt; every landed hit passes
 *       the mark to its twin.</li>
 *   <li><b>Stranded Naiad</b> (Nymph) - at 50% health it floods a 5x5 patch around itself and moves faster inside it.</li>
 *   <li><b>Labyrinth Bull</b> (Cretan Bull) - charges: a hit for its charge damage (1,000 .. 6,000 by variant) with true
 *       knockback, then 5 seconds before it charges again.</li>
 *   <li><b>Harpy</b> - leaps up and drifts down shooting arrows; each arrow that lands takes 10% of your Intelligence
 *       and Vitality (max 50%) until it dies; it stays on the ground below 50% health.</li>
 *   <li><b>Earthen Construct</b> (Gaia) - takes damage only once every 6 / 7 / 8 hits (stages at 66% / 33% HP),
 *       lightning for 5,000 / 7,500 / 10,000 / 15,000 by variant, true knockback.</li>
 *   <li><b>Minotaur</b> - Bleed +1 per melee hit, +3 per thrown axe; every 2 seconds it strikes you for 75 x Bleed;
 *       at 27 Bleed it runs off until your Bleed is back to 0.</li>
 *   <li><b>Labyrinth Champion</b> - +3.5% damage per second alive (gone at +600%); melee and a bow when you keep away.</li>
 *   <li><b>Sphinx</b> - asks a riddle ({@link SphinxRiddles}); right = a piece of treasure, wrong = +50% health and a
 *       harder bleed.</li>
 *   <li><b>Labyrinth Inquisitor</b> - +6% per second (gone at +600%); each of your hits drains 10% of your stats (max
 *       40%); telegraphed lightning on your block.</li>
 *   <li><b>Manticore</b> - fast contact damage; its Fatal Sting glows green, then fires a barrage: hit, and your
 *       healing stops and a 30 second clock starts - kill it in time or die. Distance dodges it.</li>
 *   <li><b>The Labyrinth King</b> (King Minos) - rides an orange sheep behind a 75-hit shield; then a rage meter (+1% per
 *       hit taken or dealt) turns that share of its damage true, AoE from 30% rage, lightning, a bow at range; your
 *       Vitality is halved while it lives.</li>
 * </ul>
 * Every Mythological creature resists 50% magic (MobDef.magicResistance, generated); the Construct and every creature
 * first met at EPIC or above ignore bow damage. Numbers the pages do not give are TUNE constants below.
 */
final class MythMechanics implements MobBehaviour {

    // ---- reference ----
    static final double CHAMPION_RAMP = 0.035;       // "roughly 3 to 4% per second"
    static final double INQUISITOR_RAMP = 0.06;      // 6% per second
    static final double RAMP_CAP = 6.0;              // +600% -> despawn
    static final int INQ_DRAIN_PER_HIT = 10, INQ_DRAIN_MAX = 40;
    static final int BLEED_PER_HIT = 1, BLEED_PER_AXE = 3, BLEED_FLEE = 27;
    static final double BLEED_STRIKE = 75;           // every 2 s: 75 x Bleed
    static final long BLEED_TICK_MS = 2000;
    static final double[] GAIA_LIGHTNING = {5000, 7500, 10000, 15000};     // Stalwart .. Empyrean
    static final double[] BULL_CHARGE = {1000, 2000, 3000, 4000, 6000};    // Blessed .. Empyrean
    static final long BULL_PAUSE_MS = 5000;
    static final int HARPY_DRAIN_PER_ARROW = 10, HARPY_DRAIN_MAX = 50;
    static final int MINOS_SHIELD_HITS = 75;
    static final double MINOS_AOE_RAGE = 0.30;
    static final double SPHINX_ENRAGE_HP = 1.5;
    static final long STING_DEADLINE_MS = 30_000;
    /** The variant a Griffin rarity index meets (0 Common .. 5 Mythic). */
    static final String[] VARIANT_NAMES = {"Common", "Blessed", "Stalwart", "Venerable", "Exalted", "Empyrean"};

    /**
     * Reference: a Mythological creature other than the Labyrinth Hunter can only be hurt by a player with a Griffin
     * pet out (dial {@code mayors.myth.griffin-to-hurt}). Pure for the checks.
     */
    static boolean hurtAllowed(String kind, boolean griffinOut, boolean ruleOn) {
        return !ruleOn || griffinOut || "hunter".equals(kind);
    }

    /** Set by the module so the Griffin rule can read its dial without a static lookup in tests. */
    static java.util.function.BooleanSupplier griffinRule = () -> false;

    // ---- TUNE (the pages give the mechanic, not these numbers) ----
    static final long AXE_EVERY_MS = 8000;
    static final double AXE_DAMAGE = 1.5;            // x the Minotaur's damage
    static final long HEAL_SLOW_MS = 5000;
    static final long GAIA_LIGHTNING_MS = 5000;
    static final long INQ_LIGHTNING_MS = 3000;
    static final double INQ_LIGHTNING_DAMAGE = 0.35;
    static final long INQ_DRAIN_MS = 20_000;
    static final long CHAMPION_BOW_MS = 3000;
    static final long HARPY_LEAP_MS = 7000, HARPY_ARROW_MS = 1000;
    static final long NYMPH_POOL_MS = 30_000;
    static final long STING_EVERY_MS = 20_000, STING_WINDUP_MS = 3000;
    static final double STING_RANGE = 12, STING_DAMAGE = 3.0;
    static final long MINOS_AOE_MS = 4000, MINOS_LIGHTNING_MS = 6000, MINOS_BOW_MS = 3000;

    private static final Stat[] DRAINED = {
            Stat.HEALTH, Stat.DEFENSE, Stat.STRENGTH, Stat.INTELLIGENCE, Stat.CRIT_CHANCE, Stat.CRIT_DAMAGE,
            Stat.ATTACK_SPEED, Stat.FEROCITY, Stat.SPEED
    };

    private final Map<UUID, UUID> lynxMark = new HashMap<>();           // owner -> lynx that may be hit
    private final Map<UUID, Integer> gaiaHits = new HashMap<>();         // construct -> hits since the last that counted
    private final Map<UUID, Long> timerA = new HashMap<>(), timerB = new HashMap<>(), timerC = new HashMap<>();
    private final Map<UUID, Integer> shield = new HashMap<>();           // king -> shield hits left
    private final Map<UUID, Double> rage = new HashMap<>();              // king -> 0..1
    private final Map<UUID, UUID> mount = new HashMap<>();               // king -> sheep
    private final Map<UUID, Location> pool = new HashMap<>();            // nymph -> centre of its 5x5 water
    private final Map<UUID, Boolean> enraged = new HashMap<>();          // sphinx answered wrong
    private final Map<UUID, Long> fleeUntil = new HashMap<>();           // minotaur running off

    private static final class Bleed { int stacks; long nextStrike; UUID minotaur; boolean sphinx; }
    private final Map<UUID, Bleed> bleed = new HashMap<>();
    private final Map<UUID, Long> healSlowUntil = new HashMap<>();
    private static final class Drain { int pct; long until; }
    private final Map<UUID, Drain> drain = new HashMap<>();
    private final Map<UUID, Integer> harpyDrain = new HashMap<>();
    private static final class Sting { long deadline; UUID manticore; }
    private final Map<UUID, Sting> sting = new HashMap<>();
    private final Map<UUID, UUID> minosNear = new HashMap<>();          // player -> king whose fight halves their vitality

    void register() {
        Sky.mobs().registerBehaviour("mayors_myth", this);
        Sky.stats().addProvider("mayors_myth", this::contribute);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::expire, 20L, 10L);
    }

    static String kind(MobDef def) {
        if (def == null || def.id == null || !def.id.startsWith("myth_")) return "";
        String rest = def.id.substring(5);
        int us = rest.lastIndexOf('_');
        return us > 0 ? rest.substring(0, us) : rest;
    }

    /** Griffin rarity index 0..5 the creature variant belongs to (myth_x_N -> N - 1). */
    static int rarity(MobDef def) {
        if (def == null || def.id == null) return 0;
        int us = def.id.lastIndexOf('_');
        try { return Math.max(0, Integer.parseInt(def.id.substring(us + 1)) - 1); } catch (RuntimeException e) { return 0; }
    }

    static boolean bowImmune(MobDef def) {
        try { return def.extra != null && def.extra.has("bowImmune") && def.extra.get("bowImmune").getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    /** Gaia lightning damage for a variant (Stalwart = rarity 2 .. Empyrean = 5). */
    static double gaiaLightning(int rarity) { return GAIA_LIGHTNING[Math.max(0, Math.min(GAIA_LIGHTNING.length - 1, rarity - 2))]; }

    /** Bull charge damage for a variant (Blessed = rarity 1 .. Empyrean = 5). */
    static double bullCharge(int rarity) { return BULL_CHARGE[Math.max(0, Math.min(BULL_CHARGE.length - 1, rarity - 1))]; }

    /** Bonus damage fraction after {@code ageTicks}: rate per second, capped at RAMP_CAP. */
    static double ramp(int ageTicks, double ratePerSecond) { return Math.min(RAMP_CAP, Math.max(0, ageTicks / 20.0) * ratePerSecond); }

    /** 1 above 66% HP, 2 above 33%, else 3. */
    static int stageOf(double hpFraction) { return hpFraction > 2.0 / 3 ? 1 : hpFraction > 1.0 / 3 ? 2 : 3; }

    /** Minotaur strike every 2 s: 75 x Bleed. */
    static double bleedStrike(int stacks) { return BLEED_STRIKE * Math.max(0, stacks); }

    /** King Minos: the share of a hit that turns true at {@code rage} (0..1). */
    static double trueShare(double rage) { return Math.max(0, Math.min(1, rage)); }

    private static double hpFraction(LivingEntity mob) {
        double max = Sky.mobs().maxHealth(mob);
        return max <= 0 ? 1 : Sky.mobs().health(mob) / max;
    }

    private boolean due(Map<UUID, Long> timer, LivingEntity mob, long every, long now) {
        Long next = timer.get(mob.getUniqueId());
        if (next == null) { timer.put(mob.getUniqueId(), now + every); return false; }
        if (now < next) return false;
        timer.put(mob.getUniqueId(), now + every);
        return true;
    }

    // ================================================================================================= hooks

    @Override
    public void onSpawn(LivingEntity mob, MobDef def) {
        UUID id = mob.getUniqueId();
        switch (kind(def)) {
            case "lynx" -> {
                UUID owner = Sky.mobs().owner(mob);
                if (owner != null && !valid(lynxMark.get(owner))) lynxMark.put(owner, id);
            }
            case "minos" -> {
                shield.put(id, MINOS_SHIELD_HITS);
                rage.put(id, 0.0);
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    if (!mob.isValid()) return;
                    Sheep sheep = mob.getWorld().spawn(mob.getLocation(), Sheep.class, s -> {
                        s.setColor(DyeColor.ORANGE);
                        s.setInvulnerable(true);
                        s.setPersistent(false);
                        s.setSilent(true);
                    });
                    sheep.addPassenger(mob);
                    mount.put(id, sheep.getUniqueId());
                });
            }
            default -> { }
        }
    }

    @Override
    public void onTick(LivingEntity mob, MobDef def, long tick) {
        long now = System.currentTimeMillis();
        int age = mob.getTicksLived();
        Player t = target(mob, 32);
        switch (kind(def)) {
            case "lynx" -> {
                UUID owner = Sky.mobs().owner(mob);
                if (owner != null && mob.getUniqueId().equals(lynxMark.get(owner)))
                    mob.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, mob.getLocation().add(0, 1.3, 0), 2, 0.25, 0.2, 0.25, 0);
            }
            case "nymph" -> nymph(mob, now);
            case "bull" -> bull(mob, def, t, now);
            case "harpy" -> harpy(mob, def, t, now);
            case "gaia" -> { if (t != null && due(timerA, mob, GAIA_LIGHTNING_MS, now)) gaiaLightning(mob, def, t); }
            case "minotaur" -> minotaur(mob, def, t, now);
            case "champion" -> {
                if (ramp(age, CHAMPION_RAMP) >= RAMP_CAP) { leave(mob, def); return; }
                if (t != null && t.getLocation().distanceSquared(mob.getLocation()) > 5 * 5 && due(timerA, mob, CHAMPION_BOW_MS, now))
                    shoot(mob, t, def.damage * (1 + ramp(age, CHAMPION_RAMP)), Particle.CRIT, "&2" + def.name + "&c's arrow hit you!");
            }
            case "sphinx" -> { }
            case "inquisitor" -> {
                if (ramp(age, INQUISITOR_RAMP) >= RAMP_CAP) { leave(mob, def); return; }
                if (t != null && due(timerA, mob, INQ_LIGHTNING_MS, now)) inquisitorLightning(mob, def, t, 1 + ramp(age, INQUISITOR_RAMP));
            }
            case "manticore" -> manticore(mob, def, t, now);
            case "minos" -> minos(mob, def, t, now);
            default -> { }
        }
    }

    @Override
    public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p != null && !hurtAllowed(kind(def), Mythology.griffinOut(p), griffinRule.getAsBoolean())) {
            ctx.cancelled = true;
            Sky.vitals().flash(p, "&cOnly a hunter with a &fGriffin &cout can hurt the " + def.name + "&c!", 30);
            return;
        }
        if (bowImmune(def) && ctx.kind == DamageContext.Kind.PROJECTILE) {
            ctx.cancelled = true;
            if (p != null) Sky.vitals().flash(p, "&cThe " + def.name + " &cshrugs off arrows!", 30);
            return;
        }
        switch (kind(def)) {
            case "lynx" -> {
                UUID owner = Sky.mobs().owner(mob);
                if (owner == null) return;
                UUID marked = lynxMark.get(owner);
                if (valid(marked) && !marked.equals(mob.getUniqueId())) {
                    ctx.cancelled = true;
                    if (p != null) {
                        Sky.vitals().flash(p, "&cHit the other lynx! &7(the one with &cangry &7particles)", 30);
                        p.playSound(mob.getLocation(), Sound.ENTITY_CAT_HISS, 0.8f, 1.2f);
                    }
                    return;
                }
                if (ctx.kind == DamageContext.Kind.MELEE || ctx.kind == DamageContext.Kind.PROJECTILE || ctx.kind == DamageContext.Kind.MAGIC) {
                    UUID twin = twinOf(mob, def, owner);
                    if (twin != null) lynxMark.put(owner, twin);
                }
            }
            case "gaia" -> {
                if (ctx.kind == DamageContext.Kind.FEROCITY || ctx.kind == DamageContext.Kind.ENVIRONMENT) { ctx.cancelled = true; return; }
                int stage = stageOf(hpFraction(mob));
                int need = 5 + stage;                       // 6 / 7 / 8
                int hits = gaiaHits.merge(mob.getUniqueId(), 1, Integer::sum);
                if (hits < need) {
                    ctx.cancelled = true;
                    if (p != null) {
                        Sky.vitals().flash(p, "&7Construct hits: &e" + hits + "&7/&e" + need + " &8(Stage " + stage + ")", 25);
                        p.playSound(mob.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.35f, 1.6f);
                    }
                } else {
                    gaiaHits.put(mob.getUniqueId(), 0);
                    if (p != null) p.playSound(mob.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 1f, 0.8f);
                }
            }
            case "inquisitor" -> {
                if (p == null || ctx.cancelled) return;
                Drain d = drain.computeIfAbsent(p.getUniqueId(), k -> new Drain());
                int before = d.pct;
                d.pct = Math.min(INQ_DRAIN_MAX, d.pct + INQ_DRAIN_PER_HIT);
                d.until = System.currentTimeMillis() + INQ_DRAIN_MS;
                if (d.pct != before) {
                    Sky.stats().invalidate(p);
                    Sky.vitals().flash(p, "&5The Inquisitor drains you! &c-" + d.pct + "% stats", 40);
                }
            }
            case "minos" -> {
                int left = shield.getOrDefault(mob.getUniqueId(), 0);
                if (left > 0) {
                    ctx.cancelled = true;
                    left--;
                    shield.put(mob.getUniqueId(), left);
                    if (p != null) Sky.vitals().flash(p, "&6Shield: &e" + left + " &7hits left", 20);
                    mob.getWorld().playSound(mob.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.8f, 1f);
                    if (left == 0) breakShield(mob, def, p);
                    return;
                }
                addRage(mob, 0.01);
            }
            default -> { }
        }
    }

    @Override
    public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
        int age = mob.getTicksLived();
        switch (kind(def)) {
            case "gaia" -> trueKnockback(mob, target, 1.4);
            case "minotaur" -> addBleed(target, BLEED_PER_HIT, mob, false);
            case "sphinx" -> addBleed(target, Boolean.TRUE.equals(enraged.get(mob.getUniqueId())) ? 3 : 1, mob, true);
            case "champion" -> ctx.multiplicative *= 1 + ramp(age, CHAMPION_RAMP);
            case "inquisitor" -> ctx.multiplicative *= 1 + ramp(age, INQUISITOR_RAMP);
            case "minos" -> {
                double r = rage.getOrDefault(mob.getUniqueId(), 0.0);
                // the rage share of the hit lands as true damage on top (reference: 50% rage = 1,250 + 1,250 true)
                double extra = def.damage * trueShare(r);
                if (extra > 0) Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (target.isValid()) Sky.combat().damagePlayer(target, extra, mob, DamageContext.Kind.TRUE); });
                addRage(mob, 0.01);
                if (shield.getOrDefault(mob.getUniqueId(), 0) > 0) trueKnockback(mob, target, 1.2);
            }
            default -> { }
        }
    }

    @Override
    public void onDeath(LivingEntity mob, MobDef def, Player killer) {
        UUID id = mob.getUniqueId();
        gaiaHits.remove(id);
        timerA.remove(id); timerB.remove(id); timerC.remove(id);
        pool.remove(id);
        enraged.remove(id);
        fleeUntil.remove(id);
        switch (kind(def)) {
            case "lynx" -> {
                UUID owner = Sky.mobs().owner(mob);
                if (owner != null && id.equals(lynxMark.get(owner))) {
                    UUID twin = twinOf(mob, def, owner);
                    if (twin != null) lynxMark.put(owner, twin); else lynxMark.remove(owner);
                }
            }
            case "harpy" -> {
                UUID owner = Sky.mobs().owner(mob);
                if (owner != null && harpyDrain.remove(owner) != null) {
                    Player o = Bukkit.getPlayer(owner);
                    if (o != null) { Sky.stats().invalidate(o); Sky.vitals().flash(o, "&aThe Harpy's curse lifts.", 30); }
                }
            }
            case "manticore" -> sting.entrySet().removeIf(e -> {
                if (!id.equals(e.getValue().manticore)) return false;
                Player o = Bukkit.getPlayer(e.getKey());
                if (o != null) { Sky.stats().invalidate(o); Msg.send(o, "&aThe Manticore's sting fades - you made it!"); }
                return true;
            });
            case "minos" -> {
                shield.remove(id);
                rage.remove(id);
                removeMount(id);
                minosNear.values().removeIf(id::equals);
            }
            default -> { }
        }
    }

    // ================================================================================================= creatures

    private void nymph(LivingEntity mob, long now) {
        Location c = pool.get(mob.getUniqueId());
        if (c == null && hpFraction(mob) <= 0.5) {
            c = mob.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
            pool.put(mob.getUniqueId(), c);
            timerB.put(mob.getUniqueId(), now + NYMPH_POOL_MS);
            mob.getWorld().playSound(c, Sound.ENTITY_GENERIC_SPLASH, 1f, 0.8f);
        }
        if (c == null) return;
        if (now > timerB.getOrDefault(mob.getUniqueId(), 0L)) { pool.remove(mob.getUniqueId()); return; }
        World w = mob.getWorld();
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                if (ThreadLocalRandom.current().nextInt(3) == 0) w.spawnParticle(Particle.FALLING_WATER, c.clone().add(dx, 0.3, dz), 1, 0.3, 0, 0.3, 0);
        Location at = mob.getLocation();
        boolean inside = at.getWorld() == c.getWorld() && Math.abs(at.getX() - c.getX()) <= 2.5 && Math.abs(at.getZ() - c.getZ()) <= 2.5;
        if (inside) mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 15, 2, false, false));
    }

    private void bull(LivingEntity mob, MobDef def, Player t, long now) {
        if (t == null) return;
        double d = t.getLocation().distance(mob.getLocation());
        Long charging = timerB.get(mob.getUniqueId());
        if (charging != null && now < charging) {
            if (d < 2.0) {
                timerB.remove(mob.getUniqueId());
                timerA.put(mob.getUniqueId(), now + BULL_PAUSE_MS);
                Sky.combat().damagePlayer(t, bullCharge(rarity(def)), mob, DamageContext.Kind.MOB);
                trueKnockback(mob, t, 2.0);
                mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_RAVAGER_ATTACK, 1f, 0.8f);
            }
            return;
        }
        if (d >= 3 && d <= 16 && due(timerA, mob, BULL_PAUSE_MS, now)) {
            Vector v = t.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0).normalize().multiply(1.5).setY(0.25);
            mob.setVelocity(v);
            timerB.put(mob.getUniqueId(), now + 1200);
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1.4f);
            mob.getWorld().spawnParticle(Particle.CLOUD, mob.getLocation(), 12, 0.4, 0.1, 0.4, 0.05);
        }
    }

    private void harpy(LivingEntity mob, MobDef def, Player t, long now) {
        if (t == null) return;
        boolean grounded = hpFraction(mob) <= 0.5;
        if (!grounded && mob.isOnGround() && due(timerA, mob, HARPY_LEAP_MS, now)) {
            mob.setVelocity(new Vector(0, 1.3, 0));
            mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, false, false));
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.2f);
        }
        if (!mob.isOnGround() && due(timerB, mob, HARPY_ARROW_MS, now)) {
            if (shoot(mob, t, def.damage, Particle.CRIT, null)) {
                UUID owner = Sky.mobs().owner(mob);
                UUID who = t.getUniqueId();
                int before = harpyDrain.getOrDefault(who, 0);
                int pct = Math.min(HARPY_DRAIN_MAX, before + HARPY_DRAIN_PER_ARROW);
                if (owner == null || owner.equals(who)) {
                    harpyDrain.put(who, pct);
                    if (pct != before) { Sky.stats().invalidate(t); Sky.vitals().flash(t, "&dHarpy arrow! &c-" + pct + "% Intelligence and Vitality", 40); }
                }
            }
        }
    }

    private void minotaur(LivingEntity mob, MobDef def, Player t, long now) {
        Long flee = fleeUntil.get(mob.getUniqueId());
        if (flee != null) {
            if (t != null && now < flee) {
                Vector away = mob.getLocation().toVector().subtract(t.getLocation().toVector()).setY(0);
                if (away.lengthSquared() > 1e-6) mob.setVelocity(away.normalize().multiply(0.45).setY(mob.isOnGround() ? 0.1 : 0));
                return;
            }
            fleeUntil.remove(mob.getUniqueId());
        }
        if (t == null) return;
        if (due(timerC, mob, AXE_EVERY_MS, now)) throwAxe(mob, def, t);
    }

    private void manticore(LivingEntity mob, MobDef def, Player t, long now) {
        if (t == null) return;
        Long windup = timerB.get(mob.getUniqueId());
        if (windup == null) {
            if (due(timerA, mob, STING_EVERY_MS, now)) {
                timerB.put(mob.getUniqueId(), now + STING_WINDUP_MS);
                Msg.send(t, "&2The Manticore's tail glows... &eget away from it!");
            }
            return;
        }
        if (now < windup) {
            mob.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, mob.getEyeLocation(), 8, 0.4, 0.3, 0.4, 0);
            return;
        }
        timerB.remove(mob.getUniqueId());
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 0.6f);
        boolean hit = t.getLocation().distance(mob.getLocation()) <= STING_RANGE && mob.hasLineOfSight(t);
        for (int i = 0; i < 5; i++) shoot(mob, t, 0, Particle.HAPPY_VILLAGER, null);
        if (!hit) { Msg.send(t, "&aYou outran the Fatal Sting!"); return; }
        Sky.combat().damagePlayer(t, def.damage * STING_DAMAGE, mob, DamageContext.Kind.MOB);
        Sting s = new Sting();
        s.deadline = now + STING_DEADLINE_MS;
        s.manticore = mob.getUniqueId();
        sting.put(t.getUniqueId(), s);
        Sky.stats().invalidate(t);
        Msg.send(t, "&4&lFATAL STING! &cYour healing has stopped. Kill the Manticore within &e30 seconds &cor die!");
    }

    private void minos(LivingEntity mob, MobDef def, Player t, long now) {
        UUID id = mob.getUniqueId();
        if (t != null) {
            UUID before = minosNear.put(t.getUniqueId(), id);
            if (!id.equals(before)) Sky.stats().invalidate(t);
        }
        if (shield.getOrDefault(id, 0) > 0 || t == null) return;
        double r = rage.getOrDefault(id, 0.0);
        if (r >= MINOS_AOE_RAGE && due(timerA, mob, MINOS_AOE_MS, now)) {
            mob.getWorld().spawnParticle(Particle.SWEEP_ATTACK, mob.getLocation().add(0, 1, 0), 12, 2, 0.3, 2, 0);
            for (Player p : mob.getWorld().getPlayers())
                if (p.getLocation().distanceSquared(mob.getLocation()) <= 5 * 5) Sky.combat().damagePlayer(p, def.damage, mob, DamageContext.Kind.MOB);
            addRage(mob, 0.01);
        }
        if (r >= 0.5 && due(timerB, mob, MINOS_LIGHTNING_MS, now)) {
            t.getWorld().strikeLightningEffect(t.getLocation());
            Sky.combat().damagePlayer(t, def.damage * (1 + r), mob, DamageContext.Kind.MAGIC);
        }
        if (t.getLocation().distanceSquared(mob.getLocation()) > 8 * 8 && due(timerC, mob, MINOS_BOW_MS, now))
            shoot(mob, t, def.damage, Particle.CRIT, "&6The Labyrinth King&c's arrow hit you!");
    }

    private void breakShield(LivingEntity mob, MobDef def, Player p) {
        removeMount(mob.getUniqueId());
        mob.getWorld().playSound(mob.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 0.7f);
        mob.getWorld().spawnParticle(Particle.EXPLOSION, mob.getLocation().add(0, 1, 0), 2);
        if (p != null) Msg.send(p, "&6The Labyrinth King's shield breaks! &7He dismounts - and every hit now builds his &cRage&7.");
    }

    private void removeMount(UUID king) {
        UUID sheep = mount.remove(king);
        Entity e = sheep == null ? null : Bukkit.getEntity(sheep);
        if (e != null) { e.eject(); e.remove(); }
    }

    private void addRage(LivingEntity mob, double amount) {
        UUID id = mob.getUniqueId();
        if (shield.getOrDefault(id, 0) > 0) return;
        double before = rage.getOrDefault(id, 0.0);
        double after = Math.min(1.0, before + amount);
        rage.put(id, after);
        if ((int) (before * 10) != (int) (after * 10)) {
            Player t = target(mob, 32);
            if (t != null) Sky.vitals().flash(t, "&cRage: &4" + Math.round(after * 100) + "%", 30);
        }
    }

    // ================================================================================================= pieces

    private static boolean valid(UUID id) {
        if (id == null) return false;
        Entity e = Bukkit.getEntity(id);
        return e instanceof LivingEntity le && le.isValid() && !le.isDead();
    }

    private static UUID twinOf(LivingEntity mob, MobDef def, UUID owner) {
        for (LivingEntity o : Sky.mobs().live(def.id)) {
            if (o == mob || !o.isValid() || o.isDead()) continue;
            if (owner.equals(Sky.mobs().owner(o))) return o.getUniqueId();
        }
        return null;
    }

    /** The owner if near, else the nearest player within {@code range}. */
    private static Player target(LivingEntity mob, double range) {
        UUID owner = Sky.mobs().owner(mob);
        Player o = owner == null ? null : Bukkit.getPlayer(owner);
        double r2 = range * range;
        if (o != null && o.getWorld() == mob.getWorld() && o.getLocation().distanceSquared(mob.getLocation()) <= r2 && !o.isDead()) return o;
        Player best = null;
        double bd = r2;
        for (Player p : mob.getWorld().getPlayers()) {
            if (p.isDead()) continue;
            double d = p.getLocation().distanceSquared(mob.getLocation());
            if (d <= bd) { bd = d; best = p; }
        }
        return best;
    }

    private static void trueKnockback(LivingEntity mob, Player target, double strength) {
        Vector away = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-6) away = new Vector(1, 0, 0);
        Vector kb = away.normalize().multiply(strength).setY(0.55);
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (target.isValid()) target.setVelocity(kb); });
    }

    /** A drawn ranged shot (particles along the line); hits when there is line of sight. True when it landed. */
    private static boolean shoot(LivingEntity mob, Player t, double damage, Particle trail, String hitLine) {
        Location from = mob.getEyeLocation();
        Vector dir = t.getEyeLocation().toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 0.5) return false;
        dir.normalize();
        World w = mob.getWorld();
        for (double s = 0.6; s < len; s += 0.6) w.spawnParticle(trail, from.clone().add(dir.clone().multiply(s)), 1, 0, 0, 0, 0);
        w.playSound(from, Sound.ENTITY_SKELETON_SHOOT, 0.8f, 1f);
        if (!mob.hasLineOfSight(t)) return false;
        if (damage > 0) Sky.combat().damagePlayer(t, damage, mob, DamageContext.Kind.PROJECTILE);
        if (hitLine != null) Sky.vitals().flash(t, hitLine, 25);
        return true;
    }

    private void gaiaLightning(LivingEntity mob, MobDef def, Player t) {
        int strikes = stageOf(hpFraction(mob));
        double dmg = gaiaLightning(rarity(def));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < strikes; i++) {
            Location at = t.getLocation().clone();
            if (i > 0) at.add(r.nextDouble(-2.5, 2.5), 0, r.nextDouble(-2.5, 2.5));
            at.getWorld().strikeLightningEffect(at);
            for (Player p : at.getWorld().getPlayers())
                if (p.getLocation().distanceSquared(at) <= 1.6 * 1.6) Sky.combat().damagePlayer(p, dmg, mob, DamageContext.Kind.MAGIC);
        }
    }

    private void throwAxe(LivingEntity mob, MobDef def, Player t) {
        double d = t.getLocation().distance(mob.getLocation());
        if (d < 3 || d > 16) return;
        Location from = mob.getEyeLocation();
        Vector dir = t.getEyeLocation().toVector().subtract(from.toVector());
        double len = dir.length();
        dir.normalize();
        World w = mob.getWorld();
        for (double s = 0.5; s < len; s += 0.5) w.spawnParticle(Particle.CRIT, from.clone().add(dir.clone().multiply(s)), 1, 0, 0, 0, 0);
        w.playSound(from, Sound.ITEM_TRIDENT_THROW, 1f, 0.6f);
        if (!mob.hasLineOfSight(t)) return;   // a wall between you catches the axe
        Sky.combat().damagePlayer(t, def.damage * AXE_DAMAGE, mob, DamageContext.Kind.MOB);
        int stacks = addBleed(t, BLEED_PER_AXE, mob, false);
        healSlowUntil.put(t.getUniqueId(), System.currentTimeMillis() + HEAL_SLOW_MS);
        Sky.stats().invalidate(t);
        w.playSound(t.getLocation(), Sound.ITEM_AXE_STRIP, 1f, 0.7f);
        Msg.send(t, "&cThe &2" + def.name + "&c's axe hit you! &4Bleed " + stacks + " &7(healing slowed)");
    }

    private int addBleed(Player p, int n, LivingEntity source, boolean sphinx) {
        Bleed b = bleed.computeIfAbsent(p.getUniqueId(), k -> { Bleed x = new Bleed(); x.nextStrike = System.currentTimeMillis() + BLEED_TICK_MS; return x; });
        b.stacks += n;
        b.sphinx |= sphinx;
        if (source != null) b.minotaur = source.getUniqueId();
        if (!sphinx && b.stacks >= BLEED_FLEE && source != null && !fleeUntil.containsKey(source.getUniqueId())) {
            fleeUntil.put(source.getUniqueId(), Long.MAX_VALUE);
            Msg.send(p, "&2The Minotaur has drawn enough blood and backs off... &7(until your Bleed is gone)");
        }
        Sky.vitals().flash(p, "&4Bleed &c" + b.stacks + " &7(&c" + Math.round(bleedStrike(b.stacks)) + " &7every 2s)", 40);
        return b.stacks;
    }

    private void inquisitorLightning(LivingEntity mob, MobDef def, Player t, double mult) {
        Location at = t.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        World w = at.getWorld();
        w.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, 0.2, 0), 30, 0.4, 0.05, 0.4, 0.01);
        t.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 2f);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!mob.isValid()) return;
            w.strikeLightningEffect(at);
            for (Player p : w.getPlayers())
                if (p.getLocation().distanceSquared(at) <= 1.4 * 1.4)
                    Sky.combat().damagePlayer(p, def.damage * INQ_LIGHTNING_DAMAGE * mult, mob, DamageContext.Kind.MAGIC);
        }, 20L);
    }

    private void leave(LivingEntity mob, MobDef def) {
        Player t = target(mob, 48);
        mob.getWorld().spawnParticle(Particle.LARGE_SMOKE, mob.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.02);
        mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 0.6f);
        if (t != null) Msg.send(t, "&7The &2" + def.name + " &7reached its full power and vanished! &8(kill it faster)");
        Sky.mobs().remove(mob);
    }

    /** Sphinx: a wrong answer enrages it (+50% health, harder bleed). */
    void enrage(LivingEntity sphinx) {
        if (sphinx == null || !sphinx.isValid() || enraged.containsKey(sphinx.getUniqueId())) return;
        enraged.put(sphinx.getUniqueId(), true);
        double max = Sky.mobs().maxHealth(sphinx);
        double hp = Sky.mobs().health(sphinx);
        Sky.mobs().setMaxHealth(sphinx, max * SPHINX_ENRAGE_HP);
        Sky.mobs().setHealth(sphinx, Math.min(max * SPHINX_ENRAGE_HP, hp * SPHINX_ENRAGE_HP));
        sphinx.getWorld().playSound(sphinx.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.6f);
    }

    // ================================================================================================= player debuffs

    private void contribute(Player p, StatMap into) {
        long now = System.currentTimeMillis();
        UUID u = p.getUniqueId();
        Drain d = drain.get(u);
        if (d != null && d.until > now && d.pct > 0) for (Stat s : DRAINED) into.addPercent(s, -d.pct);
        Long slow = healSlowUntil.get(u);
        if (slow != null && slow > now) { into.addPercent(Stat.HEALTH_REGEN, -50); into.addPercent(Stat.VITALITY, -50); }
        Integer hd = harpyDrain.get(u);
        if (hd != null && hd > 0) { into.addPercent(Stat.INTELLIGENCE, -hd); into.addPercent(Stat.VITALITY, -hd); }
        if (sting.containsKey(u)) { into.addPercent(Stat.HEALTH_REGEN, -100); into.addPercent(Stat.VITALITY, -100); }
        UUID king = minosNear.get(u);
        if (king != null && valid(king)) into.addPercent(Stat.VITALITY, -50);
    }

    /** Twice a second: bleed strikes, the sting clock, expired drains and slows, forgotten mobs. */
    private void expire() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Bleed> e : new HashMap<>(bleed).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            Bleed b = e.getValue();
            Entity src = b.minotaur == null ? null : Bukkit.getEntity(b.minotaur);
            boolean minotaurAlive = src instanceof LivingEntity le && le.isValid() && !le.isDead();
            if (p == null || p.isDead() || !minotaurAlive || b.stacks <= 0) {
                bleed.remove(e.getKey());
                if (src != null) fleeUntil.remove(src.getUniqueId());
                continue;
            }
            if (now >= b.nextStrike) {
                b.nextStrike = now + BLEED_TICK_MS;
                Sky.combat().damagePlayer(p, bleedStrike(b.stacks), minotaurAlive ? src : null, DamageContext.Kind.TRUE);
                p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 4, 0.2, 0.3, 0.2, 0);
                if (fleeUntil.containsKey(b.minotaur)) {
                    // the Minotaur is away: your Bleed runs down, and it comes back once it reaches 0
                    b.stacks = Math.max(0, b.stacks - 3);
                    if (b.stacks == 0) { fleeUntil.remove(b.minotaur); Msg.send(p, "&2The Minotaur returns!"); }
                }
            }
        }
        for (Map.Entry<UUID, Sting> e : new HashMap<>(sting).entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !valid(e.getValue().manticore)) { sting.remove(e.getKey()); if (p != null) Sky.stats().invalidate(p); continue; }
            long left = e.getValue().deadline - now;
            if (left <= 0) {
                sting.remove(e.getKey());
                Sky.stats().invalidate(p);
                Msg.send(p, "&4The Fatal Sting claims you.");
                Sky.combat().damagePlayer(p, 1e12, Bukkit.getEntity(e.getValue().manticore), DamageContext.Kind.TRUE);
            } else {
                Sky.vitals().flash(p, "&4Fatal Sting: &c" + (left / 1000 + 1) + "s", 15);
            }
        }
        drain.entrySet().removeIf(e -> {
            if (e.getValue().until > now) return false;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) { Sky.stats().invalidate(p); Sky.vitals().flash(p, "&aYour strength returns.", 30); }
            return true;
        });
        healSlowUntil.entrySet().removeIf(e -> {
            if (e.getValue() > now) return false;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) Sky.stats().invalidate(p);
            return true;
        });
        minosNear.entrySet().removeIf(e -> {
            if (valid(e.getValue())) return false;
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) Sky.stats().invalidate(p);
            return true;
        });
        harpyDrain.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        gaiaHits.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        for (Map<UUID, Long> m : java.util.List.of(timerA, timerB, timerC)) m.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        pool.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        enraged.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        for (UUID king : new java.util.ArrayList<>(mount.keySet())) if (!valid(king)) removeMount(king);
        shield.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        rage.keySet().removeIf(id -> Bukkit.getEntity(id) == null);
        lynxMark.values().removeIf(id -> !valid(id));
    }

    /** For readouts and tests. */
    int drainOf(UUID player) { Drain d = drain.get(player); return d == null ? 0 : d.pct; }
    int bleedOf(UUID player) { Bleed b = bleed.get(player); return b == null ? 0 : b.stacks; }
    int shieldOf(UUID king) { return shield.getOrDefault(king, 0); }
}
