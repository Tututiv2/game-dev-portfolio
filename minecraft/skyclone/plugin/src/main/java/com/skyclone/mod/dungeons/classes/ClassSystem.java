package com.skyclone.mod.dungeons.classes;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.mod.dungeons.DDials;
import com.skyclone.mod.dungeons.Fx;
import com.skyclone.mod.dungeons.Progress;
import com.skyclone.mod.dungeons.boss.Kit;
import com.skyclone.mod.dungeons.gear.TimedShields;
import com.skyclone.mod.dungeons.model.CataMath;
import com.skyclone.mod.dungeons.model.DClass;
import com.skyclone.mod.dungeons.model.DungeonPerks;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The five classes inside a run (research 04 sec 3.4): passives as stat providers and damage modifiers, the class
 * ability (F, or right-click the Dungeon Map), the Ultimate (Sneak+F, or drop the map), ghost abilities, class
 * milestones; plus the in-dungeon rules of sec 3.5 (dungeon item stat boost from Catacombs level and stars, mana on
 * hit, speed above 100 x0.67, mob damage scaling) and blessings (sec 3.11). Numbers are research's; anything (?) or
 * missing is marked TUNE.
 */
public final class ClassSystem implements Listener {

    private ClassSystem() {}

    private static final Map<UUID, Double> speedFactor = new HashMap<>();
    /** How long a class shield (Protective Barrier, Wish, Castle of Stone's party shield) lasts (TUNE; it used to never end). */
    static final long SHIELD_TICKS = 30 * 20;
    /** Taunt (Tank passive): mobs within this many blocks of a Tank above 25% HP go for the Tank (TUNE). */
    static final double TAUNT_RANGE = 16;
    private static final org.bukkit.NamespacedKey STANCE = new org.bukkit.NamespacedKey("skyclone", "dungeons_tank_stance");
    private static final Map<UUID, Long> distractedUntil = new HashMap<>();
    private static final Map<UUID, Long> beamCd = new HashMap<>();
    /** Live effects: healing circles, thunderstorms, rapid fire. */
    private static final List<Effect> EFFECTS = new ArrayList<>();
    private static boolean extraHit;

    private record Effect(DungeonRun run, UUID owner, String kind, Location at, long until) {}

    /** Part B hook: extra blessing strength per player (essence shop "Forbidden Blessing"), as a fraction. */
    public static java.util.function.ToDoubleFunction<Player> BLESSING_BONUS = p -> 0;

    public static void init() {
        Sky.stats().addProvider("dungeon_class", ClassSystem::classStats);
        Sky.stats().addProvider("dungeon_blessings", ClassSystem::blessingStats);
        Sky.stats().addProvider("dungeon_items", ClassSystem::itemStats);
        Sky.stats().addProvider("dungeon_speed", ClassSystem::speedStats);
        Sky.combat().addModifier("dungeon_mob_scale", 400, ClassSystem::mobScale);
        Sky.combat().addModifier("dungeon_outgoing", 400, ClassSystem::outgoing);
        Sky.combat().addModifier("dungeon_incoming", 900, ClassSystem::incoming);
        Bukkit.getPluginManager().registerEvents(new ClassSystem(), Sky.plugin());
    }

    static DungeonRun runOf(Player p) { return RunManager.get() == null ? null : RunManager.get().runOf(p); }

    static boolean inside(Player p, DungeonRun r) { return r != null && r.world != null && p.getWorld().equals(r.world); }

    /**
     * Duplicate-class penalty (finish wave 2026-10-03; was "not built"): when two or more members of a run (who have
     * not left) play the same class, that class's level stats, passive damage bonuses and ability damage are scaled
     * by {@code dungeons.duplicate-class-scale} (0.5). Ultimates, cooldowns and the flat behaviours are untouched.
     */
    public static double dup(DungeonRun r, DungeonRun.Member m) {
        if (r == null || m == null) return 1;
        for (DungeonRun.Member o : r.members.values()) {
            if (o != m && !o.left && o.cls == m.cls) return Math.max(0, Math.min(1, DDials.DUPLICATE_CLASS_SCALE.get()));
        }
        return 1;
    }

    // ===========================================================================================
    // stats
    // ===========================================================================================

    private static void classStats(Player p, StatMap into) {
        DungeonRun r = runOf(p);
        if (!inside(p, r)) return;
        DungeonRun.Member m = r.member(p);
        if (m == null) return;
        int L = m.classLevel;
        double f = dup(r, m);
        switch (m.cls) {
            case HEALER -> { into.add(Stat.VITALITY, f * 0.2 * L); into.add(Stat.MENDING, f * 0.5 * L); }
            case MAGE -> { into.add(Stat.INTELLIGENCE, f * 5.0 * L); into.add(Stat.ABILITY_DAMAGE, f * Math.floor(L / 5.0)); }
            case BERSERK -> {
                into.add(Stat.SPEED, f * 0.4 * Math.floor(L / 5.0));
                // Indomitable (wave 5, was "not built"): a share of Strength as Defense. Reads the cached sheet (like
                // the speed rule); Defense never feeds Strength, so this cannot compound
                if (!m.ghost) into.add(Stat.DEFENSE, f * DungeonPerks.indomitable(Sky.stats().get(p, Stat.STRENGTH), L));
            }
            case ARCHER -> { }
            case TANK -> {
                into.add(Stat.HEALTH, f * 100);
                into.add(Stat.DEFENSE, f * (50 + (L >= 50 ? 51 : L)));
                into.add(Stat.VITALITY, f * (10 + 0.1 * Math.min(49, L) + (L >= 50 ? 2 : 0)));
                // Protective Barrier's permanent Defense: +25% (+0.2%/lvl); a SOLO Tank gets +50% (research 04 sec 3.4)
                into.addPercent(Stat.DEFENSE, f * ((r.solo() ? 50 : 25) + 0.2 * L));
            }
        }
        long now = System.currentTimeMillis();
        // Ragnarok: +100 Attack Speed, +400 Speed (research 04 sec 3.4; the dungeon speed rule then trims it)
        if (m.ragnarokUntil > now) { into.add(Stat.ATTACK_SPEED, 100); into.add(Stat.SPEED, 400); }
        if (m.buffUntil > now) into.add(Stat.STRENGTH, m.buffStrength > 0 ? m.buffStrength : 30);
        if (m.healBowUntil > now) into.add(Stat.STRENGTH, 50);
        if (m.potion > 0) {
            // Jellyfish "Strong Brew" (pets' info-only perk, wave 5): dungeon potions are a% stronger
            double brew = petValue(p, "jellyfish_powerful_potions", "a");
            into.add(Stat.STRENGTH, DungeonPerks.strongBrew(5 * m.potion, brew));
            into.add(Stat.CRIT_DAMAGE, DungeonPerks.strongBrew(5 * m.potion, brew));
            into.add(Stat.HEALTH, DungeonPerks.strongBrew(20 * m.potion, brew));
            into.add(Stat.SPEED, DungeonPerks.strongBrew(2 * m.potion, brew));
        }
    }

    /** An active-pet perk number from the pets module (0 when the pet lacks it or pets is absent). */
    public static double petValue(Player p, String key, String token) {
        try {
            double v = Sky.service(com.skyclone.api.feature.Features.Pets.class).abilityValue(p, key, token);
            return Double.isFinite(v) ? v : 0;
        } catch (RuntimeException e) { return 0; }
    }

    /** Blessing stats per tier (research 04 sec 3.11 approximations, (?) = TUNE). */
    private static void blessingStats(Player p, StatMap into) {
        DungeonRun r = runOf(p);
        if (!inside(p, r) || r.blessings.isEmpty()) return;
        double s = DDials.BLESSING_SCALE.get() * (1 + perk("paul.benediction") / 100.0) * (1 + BLESSING_BONUS.applyAsDouble(p));
        int power = r.blessings.getOrDefault("power", 0), life = r.blessings.getOrDefault("life", 0),
                wisdom = r.blessings.getOrDefault("wisdom", 0), stone = r.blessings.getOrDefault("stone", 0),
                time = r.blessings.getOrDefault("time", 0);
        into.add(Stat.STRENGTH, s * (2 * power + time));
        into.add(Stat.CRIT_DAMAGE, s * (2 * power + time));
        into.addPercent(Stat.HEALTH, s * (3 * life + 1.5 * time));
        into.add(Stat.HEALTH_REGEN, s * (2 * life + time));
        into.add(Stat.INTELLIGENCE, s * (2 * wisdom + time));
        into.add(Stat.SPEED, s * (1 * wisdom + 0.5 * time));
        into.add(Stat.DEFENSE, s * (6 * stone + 3 * time));
    }

    private static double perk(String id) {
        try {
            var may = Sky.service(com.skyclone.api.feature.Features.Mayors.class);
            return may.perkActive(id) ? may.perkValue(id) : 0;
        } catch (RuntimeException e) { return 0; }
    }

    /**
     * Fraction added to a dungeon item's BASE stats inside the Catacombs: the Catacombs-level boost, +8% per essence
     * star (10% in dungeons, 2% of which already applies everywhere), +5% per master star in a Master Mode run only
     * (wave 5 fix: they used to apply on every floor) and General's Medallion (+1% per digit of secrets found, max 5%).
     */
    public static double boostFraction(Player p, ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        String id = Sky.items().idOf(it);
        if (id == null) return 0;
        ItemDef def = Sky.items().def(id);
        if (def == null || !def.dungeon) return 0;
        ItemData d = Sky.items().data(it);
        DungeonRun r = runOf(p);
        boolean masterRun = r != null && r.floor != null && r.floor.master;
        double f = CataMath.statBoostPercent(Progress.cataLevel(p)) / 100.0 * DDials.STAT_BOOST_SCALE.get();
        f += DungeonPerks.starFraction(d.stars, masterRun, DDials.STAR_BOOST.get(), DDials.MASTER_STAR_BOOST.get());
        f += medallion(p) / 100.0;
        return f;
    }

    /** General's Medallion percent for this player (0 without the accessory). */
    public static int medallion(Player p) {
        try {
            if (!Sky.profiles().isReady(p) || !Sky.service(com.skyclone.api.feature.Features.Accessories.class).has(p, "GENERAL_MEDALLION")) return 0;
            return DungeonPerks.medallionPercent(Progress.data(p).totalSecrets);
        } catch (RuntimeException e) { return 0; }
    }

    private static void itemStats(Player p, StatMap into) {
        DungeonRun r = runOf(p);
        if (!inside(p, r) || !Sky.profiles().isReady(p)) return;
        List<ItemStack> pieces = new ArrayList<>();
        for (ItemStack a : p.getInventory().getArmorContents()) pieces.add(a);
        ItemStack hand = p.getInventory().getItemInMainHand();
        String hid = Sky.items().idOf(hand);
        if (hid != null && Sky.items().def(hid) != null && Sky.items().def(hid).category != null && Sky.items().def(hid).category.isWeapon()) pieces.add(hand);
        for (ItemStack it : pieces) {
            double f = boostFraction(p, it);
            if (f <= 0) continue;
            ItemStatSheet sheet = Sky.items().sheet(it);
            for (Stat s : sheet.view().keySet()) {
                double base = sheet.part(s, ItemStatSheet.Source.BASE);
                if (base != 0) into.add(s, base * f);
            }
        }
    }

    private static void speedStats(Player p, StatMap into) {
        DungeonRun r = runOf(p);
        if (!inside(p, r)) { speedFactor.remove(p.getUniqueId()); return; }
        double last = speedFactor.getOrDefault(p.getUniqueId(), 1.0);
        double raw = Sky.stats().get(p, Stat.SPEED) / Math.max(0.01, last);
        double factor = raw > 100 ? (100 + (raw - 100) * DDials.SPEED_FACTOR.get()) / raw : 1.0;
        speedFactor.put(p.getUniqueId(), factor);
        if (factor != 1.0) into.multiply(Stat.SPEED, factor);
    }

    // ===========================================================================================
    // damage
    // ===========================================================================================

    private static void mobScale(DamageContext ctx) {
        if (ctx.playerVictim() == null || ctx.attacker == null || ctx.attacker instanceof Player) return;
        // a scripted boss attack names the boss body as its source (for the death message), but it is not that body's
        // swing: its number is already final (Kit applies boss-damage-scale and the solo dial)
        if (Kit.scripted()) return;
        DungeonRun r = RunManager.get().runOfEntity(ctx.attacker);
        if (r == null) return;
        DungeonRun.RunMob m = r.runMob(ctx.attacker);
        if (m == null) return;
        ctx.multiplicative *= m.damageMultiplier;
        // wave-4 endgame: boss floors are sized for five. Bodies and adds scale with the run's member count (solo-boss-
        // damage-scale at one up to 1.0 at five), and nothing lands inside the arrival grace after the boss portal
        if ((m.kind.equals("boss") || m.kind.equals("summon")) && r.state == DungeonRun.State.BOSS) {
            if (r.inArrivalGrace(ctx.playerVictim())) { ctx.cancelled = true; return; }
            ctx.multiplicative *= r.partyDmg();
        }
        // wave-5 regression: the rooms are party content too (solo-room-damage-scale at one member, 1.0 at five)
        else if (DungeonRun.roomSide(m.kind)) ctx.multiplicative *= r.roomDmg();
    }

    private static void outgoing(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null) return;
        DungeonRun r = runOf(p);
        if (!inside(p, r)) return;
        DungeonRun.Member m = r.member(p);
        if (m == null || m.ghost) return;
        boolean melee = ctx.kind == DamageContext.Kind.MELEE, bow = ctx.kind == DamageContext.Kind.PROJECTILE;
        if ((melee || bow) && ctx.weapon != null) {
            double f = boostFraction(p, ctx.weapon);
            if (f > 0) ctx.base *= 1 + f;
        }
        long now = System.currentTimeMillis();
        double dupF = dup(r, m);
        if (m.cls == DClass.BERSERK && melee) {
            ctx.additive += dupF * 0.75 * m.classLevel;
            UUID v = ctx.victim.getUniqueId();
            if (v.equals(m.lustVictim)) m.lustStacks = Math.min(5 + m.classLevel / 5, m.lustStacks + 1);
            else { m.lustVictim = v; m.lustStacks = 0; }
            ctx.additive += 3.0 * m.lustStacks;
            if (m.bloodlust) {
                m.bloodlust = false;
                ctx.additive += 20 + 0.4 * m.classLevel;      // TUNE: research gives no bonus %
                double missing = Sky.vitals().maxHealth(p) - Sky.vitals().health(p);
                if (missing > 0) Sky.vitals().heal(p, missing * 0.03);
            }
        }
        if (m.cls == DClass.ARCHER && bow) ctx.additive += dupF * 1.6 * m.classLevel;
        if (m.ragnarokUntil > now && melee) ctx.additive += 50;
        int stone = r.blessings.getOrDefault("stone", 0) + r.blessings.getOrDefault("time", 0) / 2;
        if (stone > 0) ctx.additive += stone * DDials.BLESSING_SCALE.get();
    }

    private static void incoming(DamageContext ctx) {
        Player p = ctx.playerVictim();
        if (p == null || ctx.playerAttacker() != null || ctx.attacker == null || Kit.scripted()) return;
        DungeonRun r = runOf(p);
        if (!inside(p, r)) return;
        DungeonRun.Member m = r.member(p);
        if (m == null) return;
        long now = System.currentTimeMillis();
        if (m.castleUntil > now) ctx.multiplicative *= 0.3;
        if (m.cls == DClass.ARCHER && now >= m.bonePlatingReady) {
            m.bonePlatingReady = now + 250_000;
            ctx.multiplicative *= 1 - Math.min(0.5, 0.1 + 0.005 * m.classLevel);   // TUNE
            Sky.vitals().flash(p, "&6Bone Plating!", 20);
        }
        // Tank Diversion: allies within 30 blocks give 80% of their damage to a living Tank (research); a Tank wearing a
        // Bone Necklace reaches 15 blocks further (wave 5: the accessories module's hook for us)
        if (m.cls != DClass.TANK) {
            for (Player q : r.alive()) {
                DungeonRun.Member t = r.member(q);
                if (t == null || t.cls != DClass.TANK || q.equals(p) || !q.getWorld().equals(p.getWorld())) continue;
                double reach = diversionRange(q);
                if (q.getLocation().distanceSquared(p.getLocation()) > reach * reach) continue;
                ctx.multiplicative *= 0.2;
                double share = Math.max(1, ctx.base * 0.8);
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (q.isOnline()) Sky.combat().damagePlayer(q, share, ctx.attacker, DamageContext.Kind.MOB); });
                t.taken += share;
                break;
            }
        }
    }

    /** Diversion reach for this Tank: 30 blocks, +15 with a Bone Necklace (or its starred form) worn. */
    public static double diversionRange(Player tank) {
        double r = 30;
        if (wears(tank, "BONE_NECKLACE", "STARRED_BONE_NECKLACE")) r += DungeonPerks.BONE_NECKLACE_DIVERSION_BONUS;
        return r;
    }

    /** True when the player wears one of these equipment pieces (accessories' equipment slots). */
    public static boolean wears(Player p, String... ids) {
        try {
            for (ItemStack it : Sky.service(com.skyclone.api.feature.Features.Equipment.class).worn(p)) {
                String id = it == null ? null : Sky.items().idOf(it);
                if (id == null) continue;
                for (String want : ids) if (want.equals(id)) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    /** After a hit landed (MONITOR): stats, mana on hit, extra arrows, cleave. */
    public static void afterDamage(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        Player v = ctx.playerVictim();
        if (v != null) {
            DungeonRun r = runOf(v);
            if (inside(v, r)) {
                DungeonRun.Member m = r.member(v);
                if (m != null) m.taken += ctx.finalDamage;
                // Protective Barrier: below 50% HP -> 10% max HP absorption, 90 s; a SOLO Tank's triggers on any hit for
                // 20%, 45 s (research 04 sec 3.4, wave 5)
                boolean soloTank = m != null && m.cls == DClass.TANK && r.solo();
                if (m != null && m.cls == DClass.TANK && Sky.vitals().health(v) - ctx.finalDamage < Sky.vitals().maxHealth(v) * (soloTank ? 1.0 : 0.5)
                        && System.currentTimeMillis() >= m.barrierReady) {
                    m.barrierReady = System.currentTimeMillis() + (soloTank ? 45_000 : 90_000);
                    TimedShields.grant(v, Sky.vitals().maxHealth(v) * (soloTank ? 0.20 : 0.10), SHIELD_TICKS);
                    Sky.vitals().flash(v, "&aProtective Barrier!", 30);
                }
            }
        }
        if (p == null || extraHit) return;
        DungeonRun r = runOf(p);
        if (!inside(p, r)) return;
        DungeonRun.Member m = r.member(p);
        if (m == null) return;
        long now = System.currentTimeMillis();
        switch (ctx.kind) {
            case MELEE -> {
                m.melee += ctx.finalDamage;
                if (ctx.finalDamage > m.bestMelee || now - m.bestMeleeAt > 60_000) { m.bestMelee = ctx.finalDamage; m.bestMeleeAt = now; }
                Sky.vitals().addMana(p, DDials.MANA_ON_HIT.get() + Sky.vitals().maxMana(p) * 0.01);
                if (m.cls == DClass.BERSERK && ctx.victim != null) cleave(r, p, ctx);
                // Soul Tether (research): every 5 melee hits by the Healer heal both ends of the tether 1%
                if (m.cls == DClass.HEALER && !m.ghost && ++m.soulHits >= 5) {
                    m.soulHits = 0;
                    Player mate = m.tether == null ? null : Bukkit.getPlayer(m.tether);
                    healAlly(r, m, p, p, Sky.vitals().maxHealth(p) * 0.01);
                    if (mate != null && r.alive().contains(mate)) healAlly(r, m, p, mate, Sky.vitals().maxHealth(mate) * 0.01);
                }
            }
            case PROJECTILE -> {
                m.ranged += ctx.finalDamage;
                if (ctx.finalDamage > m.bestBow || now - m.bestBowAt > 60_000) { m.bestBow = ctx.finalDamage; m.bestBowAt = now; }
                if (m.cls == DClass.ARCHER && ctx.victim != null) archerExtra(r, p, m, ctx);
            }
            case MAGIC -> m.ability += ctx.finalDamage;
            default -> { }
        }
    }

    /** Weapon Master: a Berserk's melee hit also strikes up to 4 more enemies in front at half damage (TUNE). */
    private static void cleave(DungeonRun r, Player p, DamageContext ctx) {
        Vector look = p.getLocation().getDirection().setY(0).normalize();
        int n = 0;
        for (Entity e : ctx.victim.getNearbyEntities(3, 2, 3)) {
            if (n >= 4 || !(e instanceof LivingEntity le) || le.equals(ctx.victim) || r.runMob(le) == null) continue;
            Vector to = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > 25 || to.normalize().dot(look) < 0.3) continue;
            n++;
            extraHit = true;
            try { Sky.combat().playerAttack(p, le, ctx.weapon, DamageContext.Kind.MELEE, 0.5); } finally { extraHit = false; }
        }
    }

    /** Doubleshot (50% +1%/lvl) and Bouncy Arrows (+2%/lvl to a second target). */
    private static void archerExtra(DungeonRun r, Player p, DungeonRun.Member m, DamageContext ctx) {
        double chance = Math.min(1.0, 0.5 + 0.01 * m.classLevel);
        if (Math.random() < chance) {
            extraHit = true;
            try { Sky.combat().playerAttack(p, ctx.victim, ctx.weapon, DamageContext.Kind.PROJECTILE, 1.0); } finally { extraHit = false; }
        }
        if (Math.random() < 0.02 * m.classLevel) {
            for (Entity e : ctx.victim.getNearbyEntities(6, 3, 6)) {
                if (!(e instanceof LivingEntity le) || r.runMob(le) == null || le.equals(ctx.victim)) continue;
                Fx.line(ctx.victim.getLocation().add(0, 1, 0), le.getLocation().add(0, 1, 0), Color.fromRGB(255, 200, 80), 0.4);
                extraHit = true;
                try { Sky.combat().playerAttack(p, le, ctx.weapon, DamageContext.Kind.PROJECTILE, 1.0); } finally { extraHit = false; }
                break;
            }
        }
    }

    /** Mage Staff: swinging at air fires a 10-block beam for (30% + 0.9 x Int/1000) of melee damage. */
    public static void mageBeam(DungeonRun r, Player p) {
        DungeonRun.Member m = r.member(p);
        if (m == null || m.ghost || m.cls != DClass.MAGE) return;
        ItemStack w = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(w);
        if (id == null || Sky.items().def(id) == null || Sky.items().def(id).category == null || !Sky.items().def(id).category.isMeleeWeapon()) return;
        long now = System.currentTimeMillis();
        Long cd = beamCd.get(p.getUniqueId());
        if (cd != null && now < cd) return;
        beamCd.put(p.getUniqueId(), now + 250);
        LivingEntity target = rayMob(r, p, 10);
        Location eye = p.getEyeLocation();
        Location end = target != null ? target.getLocation().add(0, 1, 0) : eye.clone().add(eye.getDirection().multiply(10));
        Fx.line(eye, end, Color.fromRGB(120, 200, 255), 0.5);
        if (target == null) return;
        double mult = 0.3 + 0.9 * Sky.stats().get(p, Stat.INTELLIGENCE) / 1000.0;
        extraHit = true;
        try { Sky.combat().playerAttack(p, target, w, DamageContext.Kind.MELEE, mult); } finally { extraHit = false; }
    }

    private static LivingEntity rayMob(DungeonRun r, Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult res = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.6,
                e -> e instanceof LivingEntity le && !(e instanceof Player) && r.runMob(le) != null);
        return res != null && res.getHitEntity() instanceof LivingEntity le ? le : null;
    }

    private static Location rayPoint(Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult res = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.4,
                e -> e instanceof LivingEntity && !(e instanceof Player));
        if (res == null) return eye.clone().add(eye.getDirection().multiply(range));
        return res.getHitPosition().toLocation(p.getWorld());
    }

    private static List<LivingEntity> mobsNear(DungeonRun r, Location at, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (e instanceof LivingEntity le && !(e instanceof Player) && r.runMob(le) != null && le.getLocation().distanceSquared(at) <= radius * radius) out.add(le);
        }
        return out;
    }

    // ===========================================================================================
    // abilities
    // ===========================================================================================

    public static void ability(DungeonRun r, Player p) {
        DungeonRun.Member m = r.member(p);
        if (m == null) return;
        if (r.state != DungeonRun.State.CLEARING && r.state != DungeonRun.State.BOSS) { Msg.error(p, "Wait for the run to start!"); return; }
        long now = System.currentTimeMillis();
        if (now < m.abilityReady) {
            Sky.vitals().flash(p, "&cThis ability is on cooldown for " + ((m.abilityReady - now) / 1000 + 1) + "s.", 20);
            return;
        }
        if (m.ghost) { ghostAbility(r, p, m); return; }
        int L = m.classLevel;
        double scale = DDials.ABILITY_SCALE.get() * dup(r, m);
        long cd = m.cls.abilityCooldown * 1000L;
        switch (m.cls) {
            case HEALER -> {
                EFFECTS.add(new Effect(r, p.getUniqueId(), "circle", p.getLocation(), now + 3000 + 1000L * (L / 5)));
                Fx.sound(p, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.6f);
            }
            case MAGE -> guidedSheep(r, p, (100 + 38.0 * L) * scale);
            case BERSERK -> {
                double dmg = Math.max(m.bestMelee, 200) * scale;
                LivingEntity t = rayMob(r, p, 15);
                Location end = t != null ? t.getLocation().add(0, 1, 0) : rayPoint(p, 15);
                Fx.line(p.getEyeLocation(), end, Color.fromRGB(200, 200, 200), 0.4);
                Fx.sound(p, Sound.ITEM_TRIDENT_THROW, 1f, 0.8f);
                if (t != null) { Sky.combat().rawDamage(t, dmg, p, DamageContext.Kind.MELEE); m.ability += dmg; }
                cd = Math.max(1000, cd - 1000L * Math.min(9, L / 5));
            }
            case ARCHER -> {
                Location at = rayPoint(p, 30);
                double dmg = Math.max(m.bestBow, 200) * scale;
                Fx.line(p.getEyeLocation(), at, Color.fromRGB(255, 150, 40), 0.6);
                for (LivingEntity le : mobsNear(r, at, 4)) { Sky.combat().rawDamage(le, dmg, p, DamageContext.Kind.PROJECTILE); m.ranged += dmg; }
                r.explode(p, at, 4);
                cd = Math.max(5000, cd - 2000L * (L / 5));
            }
            case TANK -> {
                double def = Sky.stats().get(p, Stat.DEFENSE);
                double dmg = (20000 + 20000 * 0.1 * def / 50.0) * scale;
                Vector dir = p.getLocation().getDirection().setY(0).normalize();
                for (int k = 1; k <= 3; k++) {
                    Location at = p.getLocation().add(dir.clone().multiply(3.0 * k));
                    final int step = k;
                    Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                        Fx.particle(at, Particle.EXPLOSION, 2, 0.6, 0);
                        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
                        for (LivingEntity le : mobsNear(r, at, 2.5)) { Sky.combat().rawDamage(le, dmg, p, DamageContext.Kind.MAGIC); m.ability += dmg; }
                    }, 4L * step);
                }
            }
        }
        m.abilityReady = now + cd;
        Sky.vitals().flash(p, "&aUsed &e" + m.cls.ability + "&a!", 25);
    }

    public static void ultimate(DungeonRun r, Player p) {
        DungeonRun.Member m = r.member(p);
        if (m == null) return;
        if (r.state != DungeonRun.State.CLEARING && r.state != DungeonRun.State.BOSS) { Msg.error(p, "Wait for the run to start!"); return; }
        // ghosts have TWO ghost abilities (research 04 sec 3.4); the Ultimate input casts the second one
        if (m.ghost) { ghostSecond(r, p, m); return; }
        long now = System.currentTimeMillis();
        if (now < m.ultReady) {
            Sky.vitals().flash(p, "&cYour Ultimate is ready in " + ((m.ultReady - now) / 1000 + 1) + "s.", 20);
            return;
        }
        int L = m.classLevel;
        switch (m.cls) {
            case HEALER -> {
                for (Player q : r.alive()) {
                    Sky.vitals().setHealth(q, Sky.vitals().maxHealth(q));
                    TimedShields.grant(q, Sky.vitals().maxHealth(q) * (0.20 + 0.01 * L), SHIELD_TICKS);
                    Fx.particle(q.getLocation().add(0, 1, 0), Particle.HEART, 10, 0.5, 0.05);
                    m.healing += Sky.vitals().maxHealth(q) * 0.2;
                }
                r.broadcast("&d✦ &b" + p.getName() + " &dcast &lWish&d!");
                m.healedSinceWish = 0;          // Jellyfish Stored Charge counts from this Wish
                m.wishTrimmed = 0;
            }
            case MAGE -> {
                EFFECTS.add(new Effect(r, p.getUniqueId(), "storm", p.getLocation(), now + 15_000));
                r.broadcast("&b⚡ &b" + p.getName() + " &bcalled a &lThunderstorm&b!");
            }
            case BERSERK -> {
                m.ragnarokUntil = now + 15_000;
                Sky.stats().invalidate(p);
                ragnarokZombies(r, p, m);
                Fx.sound(p, Sound.ENTITY_RAVAGER_ROAR, 1f, 0.8f);
                r.broadcast("&c⚔ &b" + p.getName() + " &centered &lRagnarok&c!");
            }
            case ARCHER -> {
                m.rapidUntil = now + 4000 + 1000L * (L / 10);
                EFFECTS.add(new Effect(r, p.getUniqueId(), "rapid", p.getLocation(), m.rapidUntil));
                r.broadcast("&6➶ &b" + p.getName() + " &6unleashed &lRapid Fire&6!");
            }
            case TANK -> {
                m.castleUntil = now + 20_000;
                for (LivingEntity le : mobsNear(r, p.getLocation(), 10)) if (le instanceof Mob mob) mob.setTarget(p);
                Fx.sound(p, Sound.BLOCK_ANVIL_LAND, 1f, 0.6f);
                r.broadcast("&7⛨ &b" + p.getName() + " &7raised the &lCastle of Stone&7!");
            }
        }
        m.ultReady = now + m.cls.ultimateCooldown * 1000L;
    }

    private static void ghostAbility(DungeonRun r, Player p, DungeonRun.Member m) {
        long now = System.currentTimeMillis();
        int L = m.classLevel;
        switch (m.cls) {
            case HEALER -> {
                for (Player q : r.alive()) if (q.getLocation().distanceSquared(p.getLocation()) <= 100) {
                    double h = Sky.vitals().maxHealth(q) * 0.10 + 100;
                    Sky.vitals().heal(q, h);
                    m.healing += h;
                }
                Fx.particle(p.getLocation(), Particle.HEART, 15, 3, 0.05);
            }
            case MAGE -> {
                LivingEntity t = rayMob(r, p, 20);
                Fx.line(p.getEyeLocation(), t != null ? t.getLocation().add(0, 1, 0) : rayPoint(p, 20), Color.fromRGB(255, 120, 0), 0.5);
                if (t != null) Sky.combat().rawDamage(t, 80 + 30.4 * L, p, DamageContext.Kind.MAGIC);
            }
            case BERSERK -> {
                // Buff Potion: +30 Strength (+2 per 5 levels) to mates within 10 blocks (research)
                double str = 30 + 2.0 * (L / 5);
                for (Player q : r.alive()) if (q.getWorld().equals(p.getWorld()) && q.getLocation().distanceSquared(p.getLocation()) <= 100) {
                    DungeonRun.Member qm = r.member(q);
                    if (qm != null) { qm.buffUntil = now + 10_000; qm.buffStrength = str; Sky.stats().invalidate(q); }
                }
                Fx.particle(p.getLocation(), Particle.ANGRY_VILLAGER, 10, 3, 0);
            }
            case ARCHER -> stun(r, p, 8, 60);   // Stun Bow (5 s cooldown)
            case TANK -> {
                // Absorption Potion: +200 (+10/lvl) absorption for 3 s to mates within 10 blocks (research)
                for (Player q : r.alive()) if (q.getWorld().equals(p.getWorld()) && q.getLocation().distanceSquared(p.getLocation()) <= 100)
                    TimedShields.grant(q, 200 + 10.0 * L, 60);
                Fx.particle(p.getLocation(), Particle.TOTEM_OF_UNDYING, 20, 3, 0.1);
            }
        }
        long cd = m.cls == DClass.ARCHER ? 5_000 : 20_000;     // research: Stun Bow 5 s; the rest TUNE 20 s
        m.abilityReady = now + DungeonPerks.restlessCooldown(cd, petValue(p, "spirit_spirit_cooldowns", "a"));
        Sky.vitals().flash(p, "&7Ghost ability used.", 20);
    }

    /**
     * The second ghost ability (wave 5; the Ultimate input while a ghost): Healer - time until the self-revive;
     * Mage - Instant Wall (a 5x3 wall for 10 s); Berserk - Ghost Axe (50 -> 1,000 damage by level); Archer - Healing Bow
     * (the mate you look at within 30 blocks: 10% max HP + 50 Strength for 10 s, 10 s cooldown); Tank - Stun Potion
     * (non-boss mobs within 10 blocks stunned 3 s). Research 04 sec 3.4; cooldowns not given there are TUNE 20 s.
     */
    private static void ghostSecond(DungeonRun r, Player p, DungeonRun.Member m) {
        if (r.state != DungeonRun.State.CLEARING && r.state != DungeonRun.State.BOSS) return;
        long now = System.currentTimeMillis();
        if (now < m.ghost2Ready) {
            Sky.vitals().flash(p, "&cThis ghost ability is on cooldown for " + ((m.ghost2Ready - now) / 1000 + 1) + "s.", 20);
            return;
        }
        int L = m.classLevel;
        long cd = 20_000;
        switch (m.cls) {
            case HEALER -> {
                long left = Math.max(0, DDials.GHOST_REVIVE_SECONDS.get() - (now - m.ghostSince) / 1000);
                Sky.vitals().flash(p, "&dRevive: &7you rise again in &a" + left + "s&7.", 40);
                return;
            }
            case MAGE -> { instantWall(r, p); cd = 20_000; }
            case BERSERK -> {
                LivingEntity t = rayMob(r, p, 20);
                Fx.line(p.getEyeLocation(), t != null ? t.getLocation().add(0, 1, 0) : rayPoint(p, 20), Color.fromRGB(190, 190, 190), 0.4);
                Fx.sound(p, Sound.ITEM_TRIDENT_THROW, 1f, 1.2f);
                if (t != null) { double dmg = 50 + 19.0 * Math.min(50, L); Sky.combat().rawDamage(t, dmg, p, DamageContext.Kind.MAGIC); m.ability += dmg; }
            }
            case ARCHER -> {
                Player mate = rayMate(r, p, 30);
                if (mate == null) { Sky.vitals().flash(p, "&cLook at a living teammate.", 20); return; }
                DungeonRun.Member mm = r.member(mate);
                Sky.vitals().heal(mate, Sky.vitals().maxHealth(mate) * 0.10);
                if (mm != null) { mm.healBowUntil = now + 10_000; Sky.stats().invalidate(mate); }
                Fx.line(p.getEyeLocation(), mate.getLocation().add(0, 1, 0), Color.fromRGB(255, 120, 200), 0.5);
                Msg.send(mate, "&d" + p.getName() + "'s Healing Bow: &a+10% Health &7and &c+50 Strength &7for 10s.");
                cd = 10_000;
            }
            case TANK -> stun(r, p, 10, 60);
        }
        m.ghost2Ready = now + DungeonPerks.restlessCooldown(cd, petValue(p, "spirit_spirit_cooldowns", "a"));
        Sky.vitals().flash(p, "&7Ghost ability used.", 20);
    }

    /** Stun every non-boss run mob within {@code radius}: no movement for {@code ticks}. */
    private static void stun(DungeonRun r, Player p, double radius, int ticks) {
        for (LivingEntity le : mobsNear(r, p.getLocation(), radius)) {
            var def = Sky.mobs().defOf(le);
            if (def != null && def.has("boss")) continue;
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 10, false, false));
            if (le instanceof Mob mob) mob.setTarget(null);
        }
        Fx.particle(p.getLocation(), Particle.CRIT, 30, 3, 0.1);
    }

    /** A living teammate under the crosshair within {@code range} blocks. */
    private static Player rayMate(DungeonRun r, Player p, double range) {
        Location eye = p.getEyeLocation();
        RayTraceResult res = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.8,
                e -> e instanceof Player q && !q.equals(p) && r.alive().contains(q));
        return res != null && res.getHitEntity() instanceof Player q ? q : null;
    }

    /**
     * Mage ghost "Instant Wall": a 5-wide, 3-high wall of glass across the ghost's facing, 3 blocks ahead, for 10 s. Only
     * air is filled and only those blocks are taken back (never a door, a secret or a puzzle block).
     */
    private static void instantWall(DungeonRun r, Player p) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        Location base = p.getLocation().add(dir.clone().multiply(3));
        List<org.bukkit.block.Block> placed = new ArrayList<>();
        for (int w = -2; w <= 2; w++) for (int h = 0; h < 3; h++) {
            org.bukkit.block.Block b = base.clone().add(side.clone().multiply(w)).add(0, h, 0).getBlock();
            if (!b.getType().isAir()) continue;
            b.setType(org.bukkit.Material.PURPLE_STAINED_GLASS, false);
            placed.add(b);
        }
        Fx.sound(p, Sound.BLOCK_GLASS_PLACE, 1f, 0.8f);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            for (org.bukkit.block.Block b : placed) if (b.getType() == org.bukkit.Material.PURPLE_STAINED_GLASS) b.setType(org.bukkit.Material.AIR, false);
        }, 200L);
    }

    /**
     * Ragnarok's zombies (finish wave 2026-10-03; was "not built"): three allied zombies rise around the Berserk for the
     * 15 s of Ragnarok. They are props, not run mobs (no score, no loot, invulnerable, cleaned up with the run). Their
     * vanilla AI is stripped so they can never go for a player; they walk to the nearest run mob within 12 blocks and,
     * once in reach, strike it every second for 40% of the Berserk's best hit this minute (min 100), credited to the
     * Berserk through the normal combat path. Boss bodies are fair game. Numbers are TUNE.
     */
    private static void ragnarokZombies(DungeonRun r, Player p, DungeonRun.Member m) {
        List<org.bukkit.entity.Zombie> zs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double a = Math.PI * 2 * i / 3;
            Location at = p.getLocation().add(Math.cos(a) * 1.8, 0, Math.sin(a) * 1.8);
            try {
                org.bukkit.entity.Zombie z = p.getWorld().spawn(at, org.bukkit.entity.Zombie.class, s -> {
                    s.setAdult();
                    s.setPersistent(false);
                    s.setRemoveWhenFarAway(false);
                    s.setInvulnerable(true);
                    s.setSilent(true);
                    s.setCanPickupItems(false);
                    s.setShouldBurnInDay(false);
                    s.customName(com.skyclone.api.ui.Text.c("&c" + p.getName() + "'s Berserker"));
                    s.setCustomNameVisible(true);
                    if (s.getEquipment() != null) s.getEquipment().setItemInMainHand(new ItemStack(org.bukkit.Material.IRON_AXE));
                });
                try { Bukkit.getMobGoals().removeAllGoals(z); } catch (RuntimeException ignored) { }
                r.addProp(z);
                zs.add(z);
                Fx.particle(at.clone().add(0, 1, 0), Particle.LARGE_SMOKE, 12, 0.4, 0.02);
            } catch (RuntimeException ignored) { }
        }
        if (zs.isEmpty()) return;
        final int[] ticks = {0};
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), task -> {
            ticks[0] += 10;
            boolean over = ticks[0] > 15 * 20 || !p.isOnline() || m.ghost || r.state == DungeonRun.State.CLOSED
                    || r.state == DungeonRun.State.FAILED || r.state == DungeonRun.State.COMPLETE;
            if (over) {
                for (org.bukkit.entity.Zombie z : zs) {
                    if (z.isValid()) { Fx.particle(z.getLocation().add(0, 1, 0), Particle.LARGE_SMOKE, 10, 0.3, 0.02); z.remove(); }
                }
                task.cancel();
                return;
            }
            boolean strike = ticks[0] % 20 == 0;
            for (org.bukkit.entity.Zombie z : zs) {
                if (!z.isValid()) continue;
                List<LivingEntity> near = mobsNear(r, z.getLocation(), 12);
                LivingEntity t = null;
                double bd = Double.MAX_VALUE;
                for (LivingEntity le : near) {
                    double d = le.getLocation().distanceSquared(z.getLocation());
                    if (d < bd) { bd = d; t = le; }
                }
                if (t == null) {
                    if (z.getLocation().distanceSquared(p.getLocation()) > 16) try { z.getPathfinder().moveTo(p.getLocation()); } catch (RuntimeException ignored) { }
                    continue;
                }
                if (bd > 2.5 * 2.5) { try { z.getPathfinder().moveTo(t.getLocation()); } catch (RuntimeException ignored) { } continue; }
                z.lookAt(t);
                if (!strike) continue;
                z.swingMainHand();
                double dmg = Math.max(100, m.bestMelee * 0.4) * DDials.ABILITY_SCALE.get() * dup(r, m);
                Sky.combat().rawDamage(t, dmg, p, DamageContext.Kind.MELEE);
                m.ability += dmg;
            }
        }, 10L, 10L);
    }

    private static void guidedSheep(DungeonRun r, Player p, double dmg) {
        Location start = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize());
        Location target = rayPoint(p, 10);
        Sheep sheep;
        try {
            sheep = p.getWorld().spawn(start, Sheep.class, s -> { s.setAI(false); s.setInvulnerable(true); s.setPersistent(false); s.setSilent(true); });
        } catch (RuntimeException e) { return; }
        r.addProp(sheep);
        Vector step = target.toVector().subtract(start.toVector()).multiply(1.0 / 15);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), task -> {
            if (!sheep.isValid()) { task.cancel(); return; }
            int age = sheep.getTicksLived();
            if (age < 30) { sheep.teleport(sheep.getLocation().add(step)); return; }
            Location at = sheep.getLocation();
            sheep.remove();
            task.cancel();
            DungeonRun.Member m = r.member(p);
            for (LivingEntity le : mobsNear(r, at, 4)) {
                double d = Sky.combat().magicDamage(p, le, dmg, 1.0, "Guided Sheep");
                if (m != null) m.ability += d;
            }
            r.explode(p, at, 3.5);
        }, 2L, 2L);
    }

    // ===========================================================================================
    // per second / kills / decoys / milestones
    // ===========================================================================================

    /** Called every second by each live run. */
    public static void runSecond(DungeonRun r) {
        long now = System.currentTimeMillis();
        List<Player> alive = r.alive();
        // Defensive Stance (Tank passive, was lore only): immune to knockback while a living Tank in the run
        for (DungeonRun.Member m : r.members.values()) {
            Player q = m.player();
            if (q != null) stance(q, m.cls == DClass.TANK && !m.ghost && !m.left && inside(q, r));
        }
        // Healing Aura: allies within 8 blocks of a Healer heal 0.5% (+0.1% per 5 levels) max HP/s
        for (Player h : alive) {
            DungeonRun.Member hm = r.member(h);
            if (hm == null || hm.cls != DClass.HEALER) continue;
            double pct = 0.005 + 0.001 * (hm.classLevel / 5);
            for (Player q : alive) {
                if (!q.getWorld().equals(h.getWorld()) || q.getLocation().distanceSquared(h.getLocation()) > 64) continue;
                healAlly(r, hm, h, q, Sky.vitals().maxHealth(q) * pct * 1.5);     // Renew: healing +50%
            }
            soulTether(r, h, hm, alive);
            storedCharge(h, hm, now);
        }
        // Spirit pet "Ghost Ally" (wave 5): a ghost whose active pet is a Spirit has a spirit fighting beside it
        for (DungeonRun.Member m : r.members.values()) if (m.ghost && !m.left) ghostAlly(r, m, now);
        for (Effect e : new ArrayList<>(EFFECTS)) {
            if (e.run != r) continue;
            if (now > e.until || r.state == DungeonRun.State.CLOSED) { EFFECTS.remove(e); continue; }
            Player owner = Bukkit.getPlayer(e.owner);
            DungeonRun.Member om = owner == null ? null : r.member(owner);
            switch (e.kind) {
                case "circle" -> {
                    int L = om == null ? 0 : om.classLevel;
                    double radius = 5 + 0.5 * (L / 5);
                    Fx.ring(e.at, radius, Color.fromRGB(255, 120, 220), 40);
                    for (Player q : alive) {
                        if (!q.getWorld().equals(e.at.getWorld()) || q.getLocation().distanceSquared(e.at) > radius * radius) continue;
                        double amt = Sky.vitals().maxHealth(q) * (0.02 + 0.005 * (L / 5)) * 1.5;
                        if (om != null && owner != null) healAlly(r, om, owner, q, amt);
                        else Sky.vitals().heal(q, amt);
                    }
                }
                case "storm" -> {
                    if (owner == null) break;
                    int L = om == null ? 0 : om.classLevel;
                    int hits = 0;
                    for (LivingEntity le : mobsNear(r, owner.getLocation(), 10)) {
                        if (hits++ >= 3) break;
                        le.getWorld().strikeLightningEffect(le.getLocation());
                        double d = Sky.combat().magicDamage(owner, le, (500 + 50.0 * L) * DDials.ABILITY_SCALE.get(), 1.0, "Thunderstorm");
                        if (om != null) om.ability += d;
                    }
                }
                case "rapid" -> {
                    if (owner == null || om == null) break;
                    for (int i = 0; i < 5; i++) {
                        LivingEntity t = rayMob(r, owner, 25);
                        if (t == null) {
                            List<LivingEntity> near = mobsNear(r, owner.getLocation(), 12);
                            t = near.isEmpty() ? null : near.get(0);
                        }
                        if (t == null) break;
                        double d = Math.max(om.bestBow, 200) * 0.75;
                        Fx.line(owner.getEyeLocation(), t.getLocation().add(0, 1, 0), Color.fromRGB(255, 220, 120), 0.6);
                        Sky.combat().rawDamage(t, d, owner, DamageContext.Kind.PROJECTILE);
                        om.ranged += d;
                    }
                }
                default -> { }
            }
        }
        // decoys: keep distracted mobs walking to the lure
        distractedUntil.entrySet().removeIf(en -> en.getValue() < now);
        for (DungeonRun.Member m : r.members.values()) {
            if (m.ragnarokUntil > 0 && m.ragnarokUntil < now) { m.ragnarokUntil = 0; Player q = m.player(); if (q != null) Sky.stats().invalidate(q); }
            if (m.buffUntil > 0 && m.buffUntil < now) { m.buffUntil = 0; Player q = m.player(); if (q != null) Sky.stats().invalidate(q); }
        }
    }

    /**
     * Soul Tether (Healer passive, finish wave 2026-10-03; was "not built"): each second the Healer tethers to the most
     * hurt living ally within {@code dungeons.soul-tether-range} blocks (a pink line between them) and mends them for
     * 1% (+0.1% per 5 levels) of their max HP; the Healer takes back half of that for themself. Numbers are TUNE.
     */
    private static void soulTether(DungeonRun r, Player h, DungeonRun.Member hm, List<Player> alive) {
        double range = Math.max(0, DDials.SOUL_TETHER_RANGE.get());
        Player best = null;
        double bestFrac = 1.0;
        for (Player q : alive) {
            if (q.equals(h) || !q.getWorld().equals(h.getWorld()) || q.getLocation().distanceSquared(h.getLocation()) > range * range) continue;
            double max = Sky.vitals().maxHealth(q);
            double frac = max <= 0 ? 1 : Sky.vitals().health(q) / max;
            if (frac < bestFrac) { bestFrac = frac; best = q; }
        }
        if (best == null) {
            // nobody hurt: keep the tether on the old partner while they are in reach (for the 5-hit heal)
            Player old = hm.tether == null ? null : Bukkit.getPlayer(hm.tether);
            if (old == null || !alive.contains(old) || !old.getWorld().equals(h.getWorld())
                    || old.getLocation().distanceSquared(h.getLocation()) > range * range) hm.tether = null;
            return;
        }
        hm.tether = best.getUniqueId();
        // wave 5, research 04 sec 3.4: the tethered mate gets 0.5% of the HEALER's max HP each second (was 1% of the
        // mate's own, plus a self-heal research does not have); the 1%-both-ends heal is every 5 melee hits (afterDamage)
        double amt = Sky.vitals().maxHealth(h) * 0.005 * dup(r, hm);
        healAlly(r, hm, h, best, amt);
        Fx.line(h.getLocation().add(0, 1, 0), best.getLocation().add(0, 1, 0), Color.fromRGB(255, 140, 230), 0.8);
    }

    /**
     * A Healer's heal on {@code q} (wave 5): Overheal turns what overflows full health into absorption, up to
     * 20% (+0.4%/lvl) of q's max health, for {@link #SHIELD_TICKS}; the heal counts toward the Healer's milestone and,
     * while Wish is on cooldown, toward the Jellyfish pet's Stored Charge. Returns the health actually restored + shield.
     */
    public static double healAlly(DungeonRun r, DungeonRun.Member hm, Player healer, Player q, double amt) {
        if (!(amt > 0) || q == null) return 0;
        double hp = Sky.vitals().health(q), max = Sky.vitals().maxHealth(q);
        double over = hm.cls == DClass.HEALER
                ? DungeonPerks.overheal(hp, max, amt, Sky.vitals().absorption(q), DungeonPerks.overhealCapFraction(hm.classLevel)) : 0;
        Sky.vitals().heal(q, amt);
        if (over >= 1) TimedShields.grant(q, over, SHIELD_TICKS);
        else over = 0;
        double effective = Math.min(amt, Math.max(0, max - hp)) + over;
        hm.healing += amt;
        if (healer != null && !q.equals(healer) && hm.ultReady > System.currentTimeMillis()) hm.healedSinceWish += effective;
        return effective;
    }

    /** Jellyfish "Stored Charge": healing on allies trims the Healer's Wish cooldown (2,000 health -> a s, max 30 s). */
    private static void storedCharge(Player h, DungeonRun.Member hm, long now) {
        if (hm.ultReady <= now || hm.healedSinceWish <= 0) return;
        double per = petValue(h, "jellyfish_stored_energy", "a");
        if (per <= 0) return;
        double trim = DungeonPerks.storedChargeTrim(hm.healedSinceWish, 2000, per, 30);
        double delta = trim - hm.wishTrimmed;
        if (delta <= 0) return;
        hm.wishTrimmed = trim;
        hm.ultReady = Math.max(now, hm.ultReady - Math.round(delta * 1000));
        Sky.vitals().flash(h, "&dStored Charge: &7Wish &a-" + com.skyclone.api.ui.Text.commas(delta) + "s", 20);
    }

    /**
     * Spirit pet "Ghost Ally" (pets' info-only perk, wave 5): while its owner is a ghost, a spirit (soul-flame particles)
     * strikes the nearest run mob within 12 blocks every 2 s for max(200, 25% of the owner's best hit this minute) x the
     * class-ability dial (TUNE: the reference states no number).
     */
    private static void ghostAlly(DungeonRun r, DungeonRun.Member m, long now) {
        if (now < m.allyNext) return;
        Player p = m.player();
        if (p == null || !inside(p, r)) return;
        String type;
        try { type = Sky.service(com.skyclone.api.feature.Features.Pets.class).activeType(p); } catch (RuntimeException e) { return; }
        if (type == null || !type.equalsIgnoreCase("SPIRIT")) return;
        m.allyNext = now + 2000;
        Location at = p.getLocation().add(Math.cos(now / 400.0) * 1.2, 1.4, Math.sin(now / 400.0) * 1.2);
        Fx.particle(at, Particle.SOUL_FIRE_FLAME, 6, 0.15, 0.01);
        List<LivingEntity> near = mobsNear(r, p.getLocation(), 12);
        LivingEntity t = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity le : near) {
            double d = le.getLocation().distanceSquared(p.getLocation());
            if (d < bd) { bd = d; t = le; }
        }
        if (t == null) return;
        double dmg = Math.max(200, Math.max(m.bestMelee, m.bestBow) * 0.25) * DDials.ABILITY_SCALE.get();
        Fx.line(at, t.getLocation().add(0, 1, 0), Color.fromRGB(150, 230, 255), 0.5);
        Sky.combat().rawDamage(t, dmg, p, DamageContext.Kind.MAGIC);
        m.ability += dmg;
    }

    /** A kill by a run member: Bloodlust and Orbies. */
    public static void onKill(DungeonRun r, Player killer, LivingEntity mob) {
        DungeonRun.Member m = r.member(killer);
        if (m == null) return;
        if (m.cls == DClass.BERSERK) {
            m.bloodlust = true;
            m.abilityReady = Math.max(System.currentTimeMillis(), m.abilityReady - 1000);
        }
        boolean healer = false;
        for (Player q : r.alive()) { DungeonRun.Member qm = r.member(q); if (qm != null && qm.cls == DClass.HEALER) healer = true; }
        if (healer && Math.random() < 0.15) {
            Location at = mob.getLocation().add(0, 1, 0);
            Fx.particle(at, Particle.HEART, 8, 0.4, 0.05);
            for (Player q : r.alive()) if (q.getLocation().distanceSquared(at) <= 36) Sky.vitals().heal(q, Sky.vitals().maxHealth(q) * 0.30);
        }
    }

    /** Lure every run mob within {@code radius} to {@code at} for {@code seconds}. */
    public static void distract(DungeonRun r, Location at, double radius, int seconds) {
        long until = System.currentTimeMillis() + seconds * 1000L;
        for (LivingEntity le : mobsNear(r, at, radius)) {
            if (Sky.mobs().defOf(le) != null && Sky.mobs().defOf(le).has("boss")) continue;
            distractedUntil.put(le.getUniqueId(), until);
            if (le instanceof Mob mob) {
                mob.setTarget(null);
                try { mob.getPathfinder().moveTo(at); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** Defensive Stance on/off: full knockback resistance under the dungeons' own modifier key. */
    public static void stance(Player p, boolean on) {
        try {
            org.bukkit.attribute.AttributeInstance a = p.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
            if (a == null) return;
            boolean has = a.getModifier(STANCE) != null;
            if (on && !has) a.addModifier(new org.bukkit.attribute.AttributeModifier(STANCE, 1.0,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER, org.bukkit.inventory.EquipmentSlotGroup.ANY));
            else if (!on && has) a.removeModifier(STANCE);
        } catch (RuntimeException ignored) { }
    }

    /**
     * Taunt (Tank passive, was lore only): a run mob about to go for a non-Tank goes for the nearest living Tank within
     * {@link #TAUNT_RANGE} blocks of it instead, while that Tank is above 25% health. Boss bodies are not taunted (their
     * fights pick targets themselves). Returns the Tank, or null to leave the target alone.
     */
    public static Player taunt(DungeonRun r, Entity mob, Player target) {
        if (r == null || mob == null || target == null) return null;
        DungeonRun.Member tm = r.member(target);
        if (tm != null && tm.cls == DClass.TANK) return null;
        DungeonRun.RunMob rm = mob instanceof LivingEntity le ? r.runMob(le) : null;
        if (rm == null || rm.kind.equals("boss")) return null;
        Player best = null;
        double bd = TAUNT_RANGE * TAUNT_RANGE;
        for (Player q : r.alive()) {
            DungeonRun.Member m = r.member(q);
            if (m == null || m.cls != DClass.TANK || !q.getWorld().equals(mob.getWorld())) continue;
            if (Sky.vitals().health(q) <= Sky.vitals().maxHealth(q) * 0.25) continue;
            double d = q.getLocation().distanceSquared(mob.getLocation());
            if (d < bd) { bd = d; best = q; }
        }
        return best;
    }

    public static boolean distracted(Entity e) {
        Long u = distractedUntil.get(e.getUniqueId());
        return u != null && u > System.currentTimeMillis();
    }

    /** Class milestone 0..9 from the class's own contribution (TUNE thresholds; research: Wood chest needs 2). */
    public static int milestone(DungeonRun r, DungeonRun.Member m) {
        double scale = Math.max(0.35, r.floor.hpScale);
        double per = switch (m.cls) {
            case BERSERK -> m.melee / (20_000 * scale);
            case MAGE -> m.ability / (15_000 * scale);
            case ARCHER -> m.ranged / (20_000 * scale);
            case HEALER -> m.healing / (2_000.0 * (1 + r.floor.number));
            case TANK -> m.taken / (3_000 * Math.max(0.5, r.floor.dmgScale));
        };
        return (int) Math.max(0, Math.min(9, Math.floor(per)));
    }

    /** Mage "Efficient Spells": item ability cooldowns -25% (+1% per 2 levels, 50% at 50) inside the Catacombs. */
    @EventHandler
    public void onAbility(SkyEvents.AbilityUsed e) {
        Player p = e.player;
        DungeonRun r = runOf(p);
        if (!inside(p, r) || e.ability == null || e.ability.id == null) return;
        DungeonRun.Member m = r.member(p);
        if (m == null || m.cls != DClass.MAGE) return;
        double red = Math.min(0.5, 0.25 + 0.005 * m.classLevel);
        double left = Sky.abilities().cooldownLeft(p, e.ability.id);
        if (left > 0) Sky.abilities().setCooldown(p, e.ability.id, left * (1 - red));
    }
}
