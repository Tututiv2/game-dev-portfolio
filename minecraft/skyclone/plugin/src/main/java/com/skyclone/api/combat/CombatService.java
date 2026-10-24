package com.skyclone.api.combat;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The damage pipeline. Core cancels vanilla damage between players and sky mobs and routes it
 * through here, so every hit gets the same formula, the same modifiers and a damage indicator.
 */
public interface CombatService {

    @FunctionalInterface
    interface DamageModifier {
        void modify(DamageContext ctx);
    }

    /**
     * Register a modifier. Lower priority runs first. Conventions: 100 = enchants, 200 = armour
     * set bonuses, 300 = pets, 400 = slayer/dungeon/mayor, 900 = anything that must see the rest.
     */
    void addModifier(String id, int priority, DamageModifier modifier);

    /** A player hits something with the given weapon (melee or projectile). Returns final damage dealt. */
    double playerAttack(Player attacker, LivingEntity victim, ItemStack weapon, DamageContext.Kind kind);

    /**
     * Same, with a damage multiplier applied to {@code ctx.multiplicative} before modifiers run (bow
     * draw force, a partial shortbow volley, a "deals 50% damage" secondary hit). (Gate, 2026-10-02.)
     */
    double playerAttack(Player attacker, LivingEntity victim, ItemStack weapon, DamageContext.Kind kind, double multiplier);

    /** Ability / magic damage. {@code scaling} is the ability's intelligence scaling. Returns damage dealt. */
    double magicDamage(Player attacker, LivingEntity victim, double baseDamage, double scaling, String abilityName);

    /** Direct damage to a sky mob or player with no formula (true damage, DoTs). Returns damage dealt. */
    double rawDamage(LivingEntity victim, double amount, Entity source, DamageContext.Kind kind);

    /** A mob (or the environment) damages a player; defense applies unless kind is TRUE. */
    double damagePlayer(Player victim, double rawDamage, Entity source, DamageContext.Kind kind);

    /** Floating number at a location ("✧1,234✧" rainbow for crits, grey for normal). */
    void indicator(Location at, double damage, boolean crit, DamageContext.Kind kind);
}
