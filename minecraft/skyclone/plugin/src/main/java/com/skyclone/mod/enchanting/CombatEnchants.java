package com.skyclone.mod.enchanting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Weapon enchants: a DamageModifier at priority 100 (CONTRACT §5) for every "+x% damage" enchant,
 * a MONITOR listener on {@link SkyEvents.Damage} for on-hit procs (Life Steal, Stormlord, Cleave,
 * Venomous, Fire Aspect, Inferno...), and a MobKill listener for on-kill ones (Vampirism, Scavenger,
 * Looting, Experience, Combo, Soul Eater). Damage-over-time ticks once a second.
 */
final class CombatEnchants implements Listener {

    /** victim uuid -> attacker uuid -> melee/projectile hits so far (First Strike, Triple-Strike, every-3rd-hit procs). */
    private static final Map<UUID, Map<UUID, Integer>> HITS = new HashMap<>();
    /** victim uuid -> lethality stack timestamps (ms expiry) and pct per stack. */
    private static final Map<UUID, Deque<Long>> LETHAL = new HashMap<>();
    private static final Map<UUID, Double> LETHAL_PCT = new HashMap<>();
    private static final Map<UUID, List<Dot>> DOTS = new HashMap<>();
    private static final Map<UUID, Deque<Long>> COMBO = new HashMap<>();
    private static final Map<UUID, Double> SOUL = new HashMap<>();
    private static final Map<UUID, Double> LAST_CRIT = new HashMap<>();
    private static final Map<UUID, Double> TEMPO = new HashMap<>();
    private static final Map<UUID, Set<String>> TYPES = new HashMap<>();
    /** Duplex: victim -> {fire-damage multiplier, until ms}. */
    private static final Map<UUID, double[]> SCORCH = new HashMap<>();
    static boolean inProc;
    private static int task = -1;

    /** A damage-over-time effect on one victim. */
    private record Dot(UUID attacker, double perSecond, long until, String why) {}

    void register() {
        Sky.combat().addModifier("enchanting_weapon", 100, this::outgoing);
        task = Bukkit.getScheduler().scheduleSyncRepeatingTask(Sky.plugin(), CombatEnchants::tickDots, 20L, 20L);
    }

    static void shutdown() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        HITS.clear(); DOTS.clear(); LETHAL.clear(); LETHAL_PCT.clear(); TYPES.clear(); SCORCH.clear();
    }

    private static boolean on() { return EnchantingModule.dials().effects.get(); }

    // ---- mob types ------------------------------------------------------------------------------------

    static Set<String> types(LivingEntity v) {
        Set<String> cached = TYPES.get(v.getUniqueId());
        if (cached != null) return cached;
        MobDef md = Sky.mobs().defOf(v);
        List<String> explicit = new ArrayList<>();
        if (md != null && md.extra != null && md.extra.has("types")) {
            JsonElement el = md.extra.get("types");
            if (el.isJsonArray()) for (JsonElement x : (JsonArray) el) explicit.add(x.getAsString());
        }
        Set<String> t = EnchantMath.mobTypes(v.getType().name(), md == null ? null : md.flags, explicit, md == null ? null : md.id);
        if (TYPES.size() > 4000) TYPES.clear();
        TYPES.put(v.getUniqueId(), t);
        return t;
    }

    static double hp(LivingEntity v) {
        if (v instanceof Player p) return Sky.vitals().health(p);
        if (Sky.mobs().isSkyMob(v)) return Sky.mobs().health(v);
        return v.getHealth();
    }

    static double maxHp(LivingEntity v) {
        if (v instanceof Player p) return Sky.vitals().maxHealth(p);
        if (Sky.mobs().isSkyMob(v)) return Sky.mobs().maxHealth(v);
        var a = v.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    static int hits(LivingEntity v, Player p) {
        Map<UUID, Integer> m = HITS.get(v.getUniqueId());
        if (m == null) return 0;
        return m.getOrDefault(p.getUniqueId(), 0);
    }

    // ---- outgoing damage (priority 100) ----------------------------------------------------------------

    private void outgoing(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || !on() || ctx.victim == null) return;
        DamageContext.Kind k = ctx.kind;
        if (k != DamageContext.Kind.MELEE && k != DamageContext.Kind.PROJECTILE && k != DamageContext.Kind.FEROCITY) return;
        boolean primary = k != DamageContext.Kind.FEROCITY;
        LivingEntity v = ctx.victim;

        // armour-side outgoing bonus: Pepper Tactics with a slayer weapon
        double pepper = Gear.wornSum(p, "ultimate_habanero_tactics", 0);
        if (pepper > 0 && isSlayerWeapon(ctx.weapon)) ctx.additive += pepper;

        if (!Gear.isSky(ctx.weapon)) return;
        Map<String, Integer> ench = Gear.enchants(ctx.weapon);
        if (ench.isEmpty()) return;
        EnchantRegistry reg = EnchantingModule.reg();
        ItemDef wdef = Gear.def(ctx.weapon);
        Set<String> types = types(v);
        MobDef md = Sky.mobs().defOf(v);
        int hits = hits(v, p);

        for (Map.Entry<String, Integer> e : ench.entrySet()) {
            EnchantSpec s = reg.spec(e.getKey());
            int lvl = e.getValue() == null ? 0 : e.getValue();
            if (s == null || lvl <= 0) continue;
            double val = s.v(lvl);
            switch (s.id) {
                case "sharpness" -> { if (k != DamageContext.Kind.PROJECTILE) ctx.additive += val; }
                case "power" -> { if (k == DamageContext.Kind.PROJECTILE || isBow(wdef)) ctx.additive += val; }
                case "spiked_hook" -> ctx.additive += val;
                case "smite" -> { if (types.contains("undead") || types.contains("skeletal") || types.contains("wither")) ctx.additive += val; }
                case "bane_of_arthropods" -> { if (types.contains("arthropod")) ctx.additive += val; }
                case "cubism" -> { if (types.contains("cubic")) ctx.additive += val; }
                case "ender_slayer" -> { if (types.contains("ender")) ctx.additive += val; }
                case "dragon_hunter" -> { if (types.contains("airborne")) ctx.additive += val; }
                case "impaling" -> { if (types.contains("aquatic")) ctx.additive += val; }
                case "magmarizer" -> { if (types.contains("magmatic")) ctx.additive += val; }
                case "smoldering" -> { if (types.contains("infernal")) ctx.additive += val; }
                case "arcane" -> { if (types.contains("woodland")) ctx.additive += val; }
                case "execute" -> ctx.additive += EnchantMath.execute(val, hp(v), maxHp(v));
                case "prosecute" -> ctx.additive += EnchantMath.prosecute(val, hp(v), maxHp(v));
                case "giant_killer" -> ctx.additive += EnchantMath.giantKiller(val, s.v(1, lvl), maxHp(v), Sky.vitals().maxHealth(p));
                case "titan_killer" -> ctx.additive += EnchantMath.titanKiller(val, s.v(1, lvl), md == null ? 0 : md.defense);
                case "first_strike" -> { if (primary && k == DamageContext.Kind.MELEE && hits == 0) { ctx.additive += val * PetPerks.firstPounce(p); ctx.tags.add("first_strike"); } }
                case "triple_strike" -> { if (primary && k == DamageContext.Kind.MELEE && hits < 3) ctx.additive += val * PetPerks.firstPounce(p); }
                case "snipe" -> { if (k == DamageContext.Kind.PROJECTILE) ctx.additive += EnchantMath.snipe(val, p.getLocation().distance(v.getLocation())); }
                case "lethality" -> {
                    Deque<Long> st = LETHAL.get(v.getUniqueId());
                    if (st != null && md != null) {
                        long now = System.currentTimeMillis();
                        st.removeIf(t -> t < now);
                        double pct = LETHAL_PCT.getOrDefault(v.getUniqueId(), val) * st.size();
                        ctx.multiplicative *= EnchantMath.defenseShredMultiplier(md.defense, pct);
                    }
                }
                case "ultimate_one_for_all" -> ctx.base *= 1 + val / 100.0;
                case "ultimate_jerry" -> { if (wdef != null && s.itemIds.contains(wdef.id)) ctx.base *= 1 + val / 100.0; }
                case "ultimate_combo" -> {
                    int kills = recentKills(p, s.v(2, lvl));
                    ctx.additive += val * Math.min(kills, (int) s.v(1, lvl)) * (kills > 0 ? PetPerks.firstPounce(p) : 1);
                }
                case "ultimate_swarm" -> ctx.additive += val * Math.min(10, nearbyMobs(p, 10));
                case "ultimate_soul_eater" -> {
                    Double stored = SOUL.get(p.getUniqueId());
                    if (ctx.crit && stored != null && stored > 0) {
                        double str = Sky.stats().get(p, Stat.STRENGTH);
                        ctx.multiplicative *= EnchantMath.strengthBonusMultiplier(str, stored);
                        ctx.tags.add("soul_eater");
                        SOUL.remove(p.getUniqueId());
                    }
                }
                case "overload" -> {
                    if (ctx.crit) {
                        double cc = Sky.stats().get(p, Stat.CRIT_CHANCE);
                        if (cc > 100 && ThreadLocalRandom.current().nextDouble() * 100 < cc - 100) {
                            ctx.multiplicative *= 1 + s.v(2, lvl) / 100.0;
                            ctx.tags.add("mega_crit");
                        }
                    }
                }
                default -> {}
            }
        }
    }

    static boolean isBow(ItemDef d) { return d != null && d.category == ItemCategory.BOW; }

    static boolean isSlayerWeapon(ItemStack w) {
        ItemDef d = Gear.def(w);
        if (d == null || d.category == null || !d.category.isWeapon()) return false;
        if (d.has("slayer_weapon")) return true;
        for (String r : d.requirements) if (r != null && r.startsWith("slayer:")) return true;
        return false;
    }

    static int nearbyMobs(Player p, double r) {
        int n = 0;
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (e instanceof LivingEntity le && !(e instanceof Player) && Sky.mobs().isSkyMob(le)) n++;
        }
        return n;
    }

    static int recentKills(Player p, double seconds) {
        Deque<Long> d = COMBO.get(p.getUniqueId());
        if (d == null) return 0;
        long cut = System.currentTimeMillis() - (long) (seconds * 1000);
        d.removeIf(t -> t < cut);
        return d.size();
    }

    // ---- on-hit procs (after the hit's final damage is known) -------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        if (inProc || !on()) return;
        DamageContext ctx = e.ctx;
        Player p = ctx.playerAttacker();
        if (p == null || ctx.victim == null || ctx.victim instanceof Player) return;
        if (ctx.kind != DamageContext.Kind.MELEE && ctx.kind != DamageContext.Kind.PROJECTILE) return;
        LivingEntity v = ctx.victim;
        double dealt = Math.max(0, ctx.finalDamage);
        // count the hit (after modifiers read the old count)
        int hitNo = HITS.computeIfAbsent(v.getUniqueId(), x -> new HashMap<>()).merge(p.getUniqueId(), 1, Integer::sum);
        if (HITS.size() > 5000) HITS.clear();
        if (ctx.crit && ctx.kind == DamageContext.Kind.PROJECTILE) LAST_CRIT.put(p.getUniqueId(), dealt);

        if (!Gear.isSky(ctx.weapon)) return;
        Map<String, Integer> ench = Gear.enchants(ctx.weapon);
        if (ench.isEmpty()) return;
        EnchantRegistry reg = EnchantingModule.reg();
        EDials dials = EnchantingModule.dials();
        UUID pu = p.getUniqueId();
        for (Map.Entry<String, Integer> en : ench.entrySet()) {
            EnchantSpec s = reg.spec(en.getKey());
            int lvl = en.getValue() == null ? 0 : en.getValue();
            if (s == null || lvl <= 0) continue;
            double val = s.v(lvl);
            switch (s.id) {
                case "life_steal" -> Sky.vitals().heal(p, val * lifeStealMult(p));
                case "syphon" -> {
                    // research 01 section 6.2: Drain refills +0.5..3 Vitality per hit; without the pool it heals % max HP
                    if (Buffs.cooldown(pu, "drain", 1000)) {
                        if (Vitality.on()) Vitality.restore(p, val * lifeStealMult(p));
                        else Sky.vitals().heal(p, Sky.vitals().maxHealth(p) * val * dials.drainHealPercent.get() / 100.0 * lifeStealMult(p));
                    }
                }
                case "mana_steal" -> Sky.vitals().addMana(p, Sky.vitals().maxMana(p) * val / 100.0);
                case "thunderlord" -> {
                    if (hitNo % 3 == 0) {
                        v.getWorld().strikeLightningEffect(v.getLocation());
                        proc(v, dealt * val / 100.0, p);
                    }
                }
                case "thunderbolt" -> {
                    if (hitNo % 3 == 0) {
                        v.getWorld().strikeLightningEffect(v.getLocation());
                        int n = 0;
                        proc(v, dealt * val / 100.0, p);
                        for (Entity o : v.getNearbyEntities(2, 2, 2)) {
                            if (n >= 9) break;
                            if (o instanceof LivingEntity le && !(o instanceof Player) && Sky.mobs().isSkyMob(le)) { proc(le, dealt * val / 100.0, p); n++; }
                        }
                    }
                }
                case "cleave" -> {
                    if (ctx.kind == DamageContext.Kind.MELEE) {
                        double r = s.v(1, lvl);
                        for (Entity o : v.getNearbyEntities(r, r, r)) {
                            if (o instanceof LivingEntity le && !(o instanceof Player) && Sky.mobs().isSkyMob(le)) proc(le, dealt * val / 100.0, p);
                        }
                    }
                }
                case "venomous" -> {
                    int amp = (int) Math.max(0, Math.min(4, Math.round(val / 5.0) - 1));
                    v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, amp, true, false));
                    addDot(v, pu, dealt * s.v(1, lvl) / 100.0, 5, "venomous", 40);
                }
                case "fire_aspect" -> {
                    if (ctx.kind == DamageContext.Kind.MELEE) {
                        v.setFireTicks((int) (val * 20));
                        addDot(v, pu, dealt * s.v(1, lvl) / 100.0, val, "fire", 1);
                    }
                }
                case "flame" -> {
                    if (ctx.kind == DamageContext.Kind.PROJECTILE) {
                        v.setFireTicks((int) (val * 20));
                        addDot(v, pu, dealt * s.v(1, lvl) / 100.0, val, "fire", 1);
                    }
                }
                case "lethality" -> {
                    if (ctx.kind == DamageContext.Kind.MELEE) {
                        Deque<Long> st = LETHAL.computeIfAbsent(v.getUniqueId(), x -> new ArrayDeque<>());
                        st.addLast(System.currentTimeMillis() + 4000);
                        while (st.size() > 4) st.removeFirst();
                        LETHAL_PCT.put(v.getUniqueId(), val);
                    }
                }
                case "knockback", "punch" -> {
                    boolean melee = ctx.kind == DamageContext.Kind.MELEE;
                    if (melee == s.id.equals("knockback")) {
                        Vector dir = v.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                        if (dir.lengthSquared() > 1e-4) {
                            dir.normalize().multiply(val * dials.knockbackPerBlock.get()).setY(0.15);
                            v.setVelocity(v.getVelocity().add(dir));
                        }
                    }
                }
                case "ultimate_inferno" -> {
                    if (hitNo % 10 == 0) {
                        v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 9, true, false));
                        v.setFireTicks(100);
                        addDot(v, pu, dealt * val / 100.0 / 5.0, 5, "inferno", 1);
                    }
                }
                case "ultimate_fatal_tempo" -> {
                    double cur = Buffs.get(pu, "fatal_tempo");
                    Buffs.set(pu, "fatal_tempo", Math.min(200, cur + val), 3000);
                    Sky.stats().invalidate(p);
                }
                case "ultimate_reiterate" -> {
                    if (ctx.kind == DamageContext.Kind.PROJECTILE) {
                        proc(v, dealt * val / 100.0, p);
                        // the second arrow leaves the target taking more fire damage for 60s (fire/inferno DoTs)
                        double mult = s.v(1, lvl);
                        double[] cur = SCORCH.get(v.getUniqueId());
                        if (mult > 1 && (cur == null || cur[0] <= mult)) SCORCH.put(v.getUniqueId(), new double[]{mult, System.currentTimeMillis() + 60_000});
                        if (SCORCH.size() > 4000) SCORCH.clear();
                    }
                }
                default -> {}
            }
        }
    }

    /** Extra damage from a proc, guarded so it cannot re-trigger procs. */
    static void proc(LivingEntity target, double amount, Player source) {
        if (amount <= 0 || target == null || !target.isValid()) return;
        inProc = true;
        try {
            Sky.combat().rawDamage(target, amount, source, DamageContext.Kind.MAGIC);
        } finally {
            inProc = false;
        }
    }

    /** Duplex's fire-damage multiplier on a victim (1 when unmarked or expired). */
    static double scorch(UUID victim, long now) {
        double[] m = SCORCH.get(victim);
        if (m == null) return 1.0;
        if (m[1] < now) { SCORCH.remove(victim); return 1.0; }
        return Math.max(1.0, m[0]);
    }

    private static void addDot(LivingEntity v, UUID attacker, double perSecond, double seconds, String why, int maxStacks) {
        if (perSecond <= 0) return;
        List<Dot> l = DOTS.computeIfAbsent(v.getUniqueId(), x -> new ArrayList<>());
        long same = l.stream().filter(d -> d.why().equals(why)).count();
        if (same >= maxStacks) {
            for (Iterator<Dot> it = l.iterator(); it.hasNext(); ) if (it.next().why().equals(why)) { it.remove(); break; }
        }
        l.add(new Dot(attacker, perSecond, System.currentTimeMillis() + (long) (seconds * 1000), why));
    }

    private static void tickDots() {
        if (DOTS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, List<Dot>>> it = DOTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, List<Dot>> en = it.next();
            Entity ent = Bukkit.getEntity(en.getKey());
            en.getValue().removeIf(d -> d.until() < now);
            if (!(ent instanceof LivingEntity le) || !le.isValid() || le.isDead() || en.getValue().isEmpty()) { it.remove(); continue; }
            double total = 0;
            UUID who = null;
            double scorch = scorch(en.getKey(), now);
            for (Dot d : en.getValue()) {
                boolean fire = d.why().equals("fire") || d.why().equals("inferno");
                total += d.perSecond() * (fire ? scorch : 1.0);
                who = d.attacker();
            }
            Player src = who == null ? null : Bukkit.getPlayer(who);
            if (total > 0) proc(le, total, src);
        }
    }

    // ---- kills ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(SkyEvents.MobKill e) {
        if (e.mob != null) { HITS.remove(e.mob.getUniqueId()); LETHAL.remove(e.mob.getUniqueId()); TYPES.remove(e.mob.getUniqueId()); }
        Player p = e.killer;
        if (p == null || !EnchantingModule.ready(p) || e.def == null) return;
        EDials dials = EnchantingModule.dials();
        UUID pu = p.getUniqueId();
        COMBO.computeIfAbsent(pu, x -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
        Deque<Long> c = COMBO.get(pu);
        while (c.size() > 20) c.removeFirst();

        double expMult = 1.0;
        if (on()) {
            ItemStack w = Gear.held(p);
            ItemDef wdef = Gear.def(w);
            Map<String, Integer> ench = Gear.enchants(w);
            EnchantRegistry reg = EnchantingModule.reg();
            for (Map.Entry<String, Integer> en : ench.entrySet()) {
                EnchantSpec s = reg.spec(en.getKey());
                int lvl = en.getValue() == null ? 0 : en.getValue();
                if (s == null || lvl <= 0) continue;
                double val = s.v(lvl);
                switch (s.id) {
                    case "vampirism" -> { if (Buffs.cooldown(pu, "vampirism", 500)) Sky.vitals().heal(p, val * lifeStealMult(p)); }
                    case "scavenger" -> e.coins += val * Math.max(1, e.def.level);
                    case "champion" -> e.coins += s.v(1, lvl);   // TUNE: research pays it on every 2nd hit; we pay it per kill
                    case "looting" -> { if (!isBow(wdef)) extraDrops(p, e.def, val, false); }
                    case "chance" -> { if (isBow(wdef)) extraDrops(p, e.def, val, false); }
                    case "luck" -> extraDrops(p, e.def, val, true);
                    case "experience" -> { if (ThreadLocalRandom.current().nextDouble() * 100 < val) expMult = 2.0; }
                    case "ultimate_soul_eater" -> SOUL.put(pu, Math.min(dials.soulEaterCap.get(), e.def.damage * val));
                    default -> {}
                }
            }
            e.coins += Gear.wornSum(p, "ultimate_bank", 1);
        }
        int exp = (int) Math.round(e.combatXp * dials.killExpPerCombatXp.get() * expMult);
        if (exp > 0) EnchantingModule.giveExp(p, exp);
    }

    /** Looting / Chance / Luck: one extra roll of each non-guaranteed drop at chance x pct/100. */
    static void extraDrops(Player p, MobDef def, double pct, boolean armorOnly) {
        if (def.drops == null || pct <= 0) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (DropDef d : def.drops) {
            if (d == null || d.item == null || d.chance >= 1.0 || d.chance <= 0) continue;
            if (!Sky.items().exists(d.item)) continue;
            if (armorOnly) {
                ItemDef id = Sky.items().def(d.item);
                if (id == null || id.category == null || !id.category.isArmor()) continue;
            }
            if (d.requirement != null && !d.requirement.isBlank() && !Sky.requirements().check(p, d.requirement)) continue;
            if (r.nextDouble() < d.chance * pct / 100.0) {
                int n = d.max > d.min ? d.min + r.nextInt(d.max - d.min + 1) : Math.max(1, d.min);
                Sky.loot().give(p, d.item, n, LootSource.MOB_DROP);
            }
        }
    }

    static double lastCrit(UUID p) { return LAST_CRIT.getOrDefault(p, 0.0); }

    static double soulStored(UUID p) { return SOUL.getOrDefault(p, 0.0); }

    static void forget(UUID p) { COMBO.remove(p); SOUL.remove(p); LAST_CRIT.remove(p); TEMPO.remove(p); }

    /**
     * Reforges request 14 (wave 3): a worn equipment piece reforged Blood-Soaked makes life-stealing (Life Steal,
     * Syphon, Vampirism) heal 1.15x as much (the reforge's bonus value at every rarity).
     */
    static double lifeStealMult(Player p) {
        try {
            for (ItemStack s : Sky.service(com.skyclone.api.feature.Features.Equipment.class).worn(p)) {
                if (!Gear.isSky(s)) continue;
                if ("blood_soaked".equals(Sky.items().data(s).reforge)) return 1.15;
            }
        } catch (RuntimeException ignored) { }
        return 1.0;
    }
}
