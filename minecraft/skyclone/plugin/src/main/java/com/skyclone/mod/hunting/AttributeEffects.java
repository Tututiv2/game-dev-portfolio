package com.skyclone.mod.hunting;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What attributes DO: a StatProvider for stat grants, a damage modifier for the rulers / resistances /
 * Warrior / Deadeye / Dominance / Lifeline / Payback / Elite, a per-target Magic Find modifier for the
 * fortunes, and the handful of hooks hunting can honour itself (Midas Touch, Life Recovery, Mana Steal,
 * Breeze, Catacombs Explorer / Graduate, Unity is Strength). Everything else is published through
 * {@code Features.Hunting.attributeLevel} for the module that owns that system (see the hand-off).
 */
final class AttributeEffects implements Listener {

    private static final Map<UUID, Long> LAST_HURT = new HashMap<>();
    private static final Map<UUID, Boolean> PAYBACK = new HashMap<>();
    private static final Map<UUID, Long> LIFE_CD = new HashMap<>();
    private static final Map<UUID, Long> MANA_CD = new HashMap<>();

    static void register() {
        Sky.stats().addProvider("hunting", AttributeEffects::contribute);
        Sky.combat().addModifier("hunting_attributes", 250, AttributeEffects::modify);
        Sky.combat().addModifier("hunting_huntaxe", 150, Tools::huntaxeDamage);
        Sky.loot().addMagicFindModifier("hunting", AttributeEffects::magicFind);
        Sky.abilities().addCostModifier("hunting_breeze", (use, cost) -> {
            Player p = use.player();
            double breeze = Hunt.snapshot(p).value("breeze");
            if (breeze <= 0) return cost;
            long hurt = LAST_HURT.getOrDefault(p.getUniqueId(), 0L);
            return System.currentTimeMillis() - hurt >= 5000 ? cost * Math.max(0, 1 - breeze / 100.0) : cost;
        });
        Bukkit.getPluginManager().registerEvents(new AttributeEffects(), Sky.plugin());
    }

    static void forget(UUID id) {
        LAST_HURT.remove(id);
        PAYBACK.remove(id);
        LIFE_CD.remove(id);
        MANA_CD.remove(id);
    }

    // ---------------------------------------------------------------- stats

    static void contribute(Player p, StatMap m) {
        if (!Hunt.ready(p)) return;
        AttrSnapshot s = Hunt.snapshot(p);
        for (AttrSnapshot.StatGrant g : s.stats) {
            if (!Hunt.cond(p, g.cond())) continue;
            Stat st = Stat.parse(g.stat());
            if (st != null) m.add(st, g.value());
        }
        for (AttrSnapshot.StatGrant g : s.statPct) {
            if (!Hunt.cond(p, g.cond())) continue;
            Stat st = Stat.parse(g.stat());
            if (st != null) m.addPercent(st, g.value());
        }
        double unity = s.value("unity_is_strength");
        if (unity > 0) m.add(Stat.SWEEP, unity * s.ownedOfRarity(Hunt.cat, "COMMON"));
        Tools.huntaxeStats(p, m);
        Safari.stats(p, m);
    }

    // ---------------------------------------------------------------- damage

    static void modify(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p != null && ctx.victim != null && !(ctx.victim instanceof Player) && Hunt.ready(p)) {
            AttrSnapshot s = Hunt.snapshot(p);
            double add = 0;
            for (Map.Entry<String, Double> e : s.damageVs.entrySet()) if (MobTypes.is(e.getKey(), ctx.victim)) add += e.getValue();
            if (ctx.kind == DamageContext.Kind.MELEE) add += s.melee;
            if (ctx.kind == DamageContext.Kind.PROJECTILE) add += s.ranged;
            double hp = Sky.vitals().health(p), max = Sky.vitals().maxHealth(p);
            if (s.fullHp > 0 && max > 0 && hp >= max - 0.01) add += s.fullHp;
            if (s.lowHp > 0 && max > 0 && hp < max * 0.2) add += s.lowHp;
            if (s.payback > 0 && PAYBACK.remove(p.getUniqueId()) != null) add += s.payback;
            if (add != 0) ctx.additive += add;
            if (s.value("mana_steal") > 0) manaSteal(p, s.value("mana_steal"));
        }
        Player v = ctx.playerVictim();
        if (v != null && ctx.attacker != null && !(ctx.attacker instanceof Player) && Hunt.ready(v)) {
            AttrSnapshot s = Hunt.snapshot(v);
            LAST_HURT.put(v.getUniqueId(), System.currentTimeMillis());
            if (s.payback > 0) PAYBACK.put(v.getUniqueId(), Boolean.TRUE);
            double extra = 0;
            for (Map.Entry<String, Double> e : s.defenseVs.entrySet()) if (MobTypes.is(e.getKey(), ctx.attacker)) extra += e.getValue();
            double def = Sky.stats().get(v, Stat.DEFENSE);
            double hp = Sky.vitals().health(v), max = Sky.vitals().maxHealth(v);
            if (s.lowHp > 0 && max > 0 && hp < max * 0.2) extra += Math.max(0, def) * s.lowHp / 100.0;
            if (extra > 0 && ctx.kind != DamageContext.Kind.TRUE) {
                double d0 = Math.max(0, def);
                ctx.multiplicative *= (100 + d0) / (100 + d0 + extra);
            }
        }
    }

    private static void manaSteal(Player p, double amount) {
        long now = System.currentTimeMillis();
        if (now - MANA_CD.getOrDefault(p.getUniqueId(), 0L) < 500) return;
        MANA_CD.put(p.getUniqueId(), now);
        Sky.vitals().addMana(p, amount);
    }

    // ---------------------------------------------------------------- magic find

    static Double magicFind(Player p, MobDef def) {
        if (def == null || !Hunt.ready(p)) return 0.0;
        AttrSnapshot s = Hunt.snapshot(p);
        if (s.mfVs.isEmpty()) return 0.0;
        EntityType et;
        try { et = EntityType.valueOf(def.entity); } catch (RuntimeException e) { et = EntityType.ZOMBIE; }
        double sum = 0;
        for (Map.Entry<String, Double> e : s.mfVs.entrySet()) if (MobTypes.is(e.getKey(), et, def)) sum += e.getValue();
        return sum;
    }

    // ---------------------------------------------------------------- hooks hunting honours itself

    @EventHandler(priority = EventPriority.NORMAL)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || !Hunt.ready(p)) return;
        AttrSnapshot s = Hunt.snapshot(p);
        double midas = s.value("midas_touch");
        if (midas > 0) e.coins += midas;
        double life = s.value("life_recovery");
        if (life > 0) {
            long now = System.currentTimeMillis();
            if (now - LIFE_CD.getOrDefault(p.getUniqueId(), 0L) >= 500) {
                LIFE_CD.put(p.getUniqueId(), now);
                Sky.vitals().heal(p, Sky.vitals().maxHealth(p) * life / 100.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDungeonXp(SkyEvents.DungeonXpGain e) {
        if (e.player == null || !Hunt.ready(e.player) || "admin".equals(e.source)) return;
        AttrSnapshot s = Hunt.snapshot(e.player);
        double pct = "catacombs".equalsIgnoreCase(e.track) ? s.value("catacombs_explorer") : s.value("catacombs_graduate");
        if (pct > 0) e.amount *= 1 + pct / 100.0;
    }

    /** The equipment the player wears (for the Safari Belt). */
    static boolean wearing(Player p, String itemId) {
        try {
            List<ItemStack> worn = Sky.service(Features.Equipment.class).worn(p);
            if (worn == null) return false;
            for (ItemStack it : worn) if (it != null && itemId.equals(Sky.items().idOf(it))) return true;
        } catch (RuntimeException ignored) { }
        return false;
    }

    static ItemDef def(String id) { return id == null ? null : Sky.items().def(id); }
}
