package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Every perk whose owner is "mayors" (see mayors.json): stat perks (one StatProvider "mayors", which
 * the profile viewer shows as "Mayor Perks"), the skill-XP perk, the mob-health perk and the spell
 * perk. Perks owned by other modules are only exposed through {@code Features.Mayors}.
 */
final class PerkEffects implements Listener {

    private final ElectionService es;
    private final MayorDials dials;
    private final NamespacedKey hpKey = NamespacedKey.fromString("skyclone:mayors_hp");

    /** Research §8.5: Statspocalypse raises all Combat and Misc stats. */
    private static final Stat[] COMBAT_MISC = {
            Stat.HEALTH, Stat.DEFENSE, Stat.TRUE_DEFENSE, Stat.STRENGTH, Stat.INTELLIGENCE, Stat.CRIT_CHANCE,
            Stat.CRIT_DAMAGE, Stat.ATTACK_SPEED, Stat.ABILITY_DAMAGE, Stat.FEROCITY, Stat.HEALTH_REGEN,
            Stat.VITALITY, Stat.MENDING, Stat.SWING_RANGE, Stat.SPEED, Stat.MAGIC_FIND, Stat.PET_LUCK
    };

    PerkEffects(ElectionService es, MayorDials dials) {
        this.es = es;
        this.dials = dials;
    }

    void register() {
        Sky.stats().addProvider("mayors", this::contribute);
        Sky.combat().addModifier("mayors_arcane_spark", 400, this::spellDamage);
        Sky.abilities().addCostModifier("mayors_arcane_spark", (use, cost) ->
                es.perkActive("barry.arcane_catalyst") ? cost * (1 - es.perkValue("barry.arcane_catalyst") / 100.0) : cost);
        es.onPerksChanged(() -> { for (Player p : Bukkit.getOnlinePlayers()) Sky.stats().invalidate(p); });
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
    }

    boolean publicIsland(Player p) {
        String island = Sky.worlds().islandOf(p.getWorld());
        if (island == null) return false;
        Set<String> priv = new HashSet<>(Arrays.asList(dials.privateIslands.get().split("\\s*,\\s*")));
        return !priv.contains(island);
    }

    private void contribute(Player p, StatMap into) {
        if (es.activePerks().isEmpty()) return;
        boolean pub = publicIsland(p);
        if (pub && es.perkActive("cole.mining_xp_buff")) into.add(Stat.MINING_WISDOM, es.perkValue("cole.mining_xp_buff"));
        if (pub && es.perkActive("marina.fishing_xp_buff")) into.add(Stat.FISHING_WISDOM, es.perkValue("marina.fishing_xp_buff"));
        if (es.perkActive("marina.luck_of_the_sea")) into.add(Stat.SEA_CREATURE_CHANCE, es.perkValue("marina.luck_of_the_sea"));
        if (es.perkActive("marina.double_trouble")) {
            // +0.1 Double Hook per 1 Sea Creature Chance: read last tick's cached total (no recursion)
            double scc = Sky.stats().get(p, Stat.SEA_CREATURE_CHANCE);
            into.add(Stat.DOUBLE_HOOK_CHANCE, Math.max(0, scc) * es.perkValue("marina.double_trouble"));
        }
        // Diana's Lucky! (+25 Pet Luck) is gone from the reference (NEU DIANA_MAYOR_MONSTER); Huntress' Intuition
        // (+10 Tracking, diana.huntress_intuition) replaced it and is hunting's to apply.
        if (es.perkActive("barry.magic_xp_boost")) {
            double v = es.perkValue("barry.magic_xp_boost");
            into.add(Stat.ALCHEMY_WISDOM, v);
            into.add(Stat.ENCHANTING_WISDOM, v);
        }
        if (es.perkActive("jerry.statspocalypse")) {
            double v = es.perkValue("jerry.statspocalypse");
            for (Stat s : COMBAT_MISC) into.addPercent(s, v);
        }
    }

    private void spellDamage(DamageContext ctx) {
        if (ctx.kind != DamageContext.Kind.MAGIC || ctx.playerAttacker() == null) return;
        if (es.perkActive("barry.arcane_catalyst")) ctx.additive += es.perkValue("barry.arcane_catalyst");
    }

    /** MORE SKILLS!!! — research §8.5: +50% skill XP. Runs before skills applies it (NORMAL < HIGH). */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSkillXp(SkyEvents.SkillXpGain e) {
        if (es.perkActive("derpy.moar_skillz")) e.amount *= 1 + es.perkValue("derpy.moar_skillz") / 100.0;
    }

    /** BEEFY MOBS!!! — every sky monster spawns with double health (owned bosses included). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!es.perkActive("derpy.double_mobs_hp")) return;
        LivingEntity le = e.getEntity();
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!le.isValid()) return;
            try {
                if (!Sky.mobs().isSkyMob(le)) return;
                var def = Sky.mobs().defOf(le);
                if (def != null && def.has("passive")) return;
                if (le.getPersistentDataContainer().has(hpKey, PersistentDataType.BYTE)) return;
                le.getPersistentDataContainer().set(hpKey, PersistentDataType.BYTE, (byte) 1);
                double mult = Math.max(1, es.perkValue("derpy.double_mobs_hp"));
                Sky.mobs().setMaxHealth(le, Sky.mobs().maxHealth(le) * mult);
            } catch (Throwable ignored) {
            }
        });
    }
}
