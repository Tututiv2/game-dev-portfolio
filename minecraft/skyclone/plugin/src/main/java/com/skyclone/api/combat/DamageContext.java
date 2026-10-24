package com.skyclone.api.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * One hit, while it is being calculated. DamageModifiers mutate it; core then applies it.
 *
 * <p>Player-dealt melee/projectile damage is
 * <pre>
 *  (5 + weaponDamage + flatBonus) * (1 + strength/100)
 *     * (1 + additive/100)            &lt;- enchants (Sharpness +5%/lvl...), most "x% more damage" bonuses
 *     * multiplicative                 &lt;- true multipliers (product)
 *     * (crit ? 1 + critDamage/100 : 1)
 * </pre>
 * Magic damage is
 * <pre>  base * (1 + intelligence/100 * scaling) * (1 + abilityDamage/100) * (1 + additive/100) * multiplicative</pre>
 * Damage TO a player is reduced by defense: {@code dmg * (1 - def/(def+100))}; true damage by true defense.
 */
public final class DamageContext {

    public enum Kind { MELEE, PROJECTILE, MAGIC, TRUE, FEROCITY, ENVIRONMENT, MOB }

    public final LivingEntity attacker;   // null for environment
    public final LivingEntity victim;
    public final Kind kind;
    public final ItemStack weapon;        // may be null / air

    /** Weapon damage or ability base damage. */
    public double base;
    /** Added to base before strength scaling. */
    public double flatBonus;
    /** Sum of additive percentages. */
    public double additive;
    /** Product of true multipliers. */
    public double multiplicative = 1.0;
    public boolean crit;
    /** Free-form tags modules can set/read ("first_strike", "sweep", "slayer_boss"). */
    public final Set<String> tags = new HashSet<>();
    public boolean cancelled;

    /** Set by core after modifiers run. */
    public double finalDamage;

    public DamageContext(LivingEntity attacker, LivingEntity victim, Kind kind, ItemStack weapon, double base) {
        this.attacker = attacker;
        this.victim = victim;
        this.kind = kind;
        this.weapon = weapon;
        this.base = base;
    }

    public Player playerAttacker() { return attacker instanceof Player p ? p : null; }
    public Player playerVictim() { return victim instanceof Player p ? p : null; }
}
