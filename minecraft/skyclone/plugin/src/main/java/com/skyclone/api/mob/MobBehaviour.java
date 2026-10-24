package com.skyclone.api.mob;

import com.skyclone.api.combat.DamageContext;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Custom mob AI/abilities, attached by id in MobDef.behaviours. All hooks main-thread.
 * Keep onTick cheap: it runs every 5 ticks for every live mob that has this behaviour.
 */
public interface MobBehaviour {
    default void onSpawn(LivingEntity mob, MobDef def) {}
    default void onTick(LivingEntity mob, MobDef def, long tick) {}
    /** Mob is about to take damage; may mutate ctx (immunity phases, damage caps). */
    default void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {}
    /** Mob is about to hit a player; may mutate ctx. */
    default void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {}
    default void onDeath(LivingEntity mob, MobDef def, Player killer) {}
}
