package com.skyclone.core.combat;

/**
 * Pure damage formulas (no Bukkit), so unit tests and self-tests can check them against the
 * research doc's worked examples. Every formula here is research/01 sections 4-5.
 *
 * <pre>
 *  melee/bow : (5 + weapon + flat) * (1 + str/100) * (1 + additive/100) * mult * (crit ? 1 + cd/100 : 1)
 *  magic     : base * (1 + int/100 * scaling) * (1 + abilityDamage/100) * (1 + additive/100) * mult
 *  then      : * (1 - magicResistance) -> boss cap -> * 100/(100 + defense) -> floor
 *  ferocity  : floor(fer/100) guaranteed extra strikes + (fer % 100)% chance of one more
 * </pre>
 */
public final class DamageMath {

    private DamageMath() {}

    /** The bare-hand base every melee/bow hit starts from. */
    public static final double BASE = 5.0;

    /** Initial melee/bow damage before mob-side reductions. */
    public static double melee(double weapon, double flat, double strength, double additive,
                               double multiplicative, boolean crit, double critDamage) {
        double d = (BASE + weapon + flat) * (1 + strength / 100.0);
        d *= 1 + additive / 100.0;
        d *= multiplicative;
        if (crit) d *= 1 + critDamage / 100.0;
        return Math.max(0, d);
    }

    /** Initial ability damage before mob-side reductions. Abilities never crit. */
    public static double magic(double base, double intelligence, double scaling, double abilityDamage,
                               double additive, double multiplicative) {
        double d = base * (1 + intelligence / 100.0 * scaling);
        d *= 1 + abilityDamage / 100.0;
        d *= 1 + additive / 100.0;
        d *= multiplicative;
        return Math.max(0, d);
    }

    /** Raw mob/environment damage to a player before defense. */
    public static double incoming(double base, double flat, double additive, double multiplicative) {
        return Math.max(0, (base + flat) * (1 + additive / 100.0) * multiplicative);
    }

    /** Boss damage cap: above {@code cap}, only 10% of the excess goes through. cap &lt;= 0 = no cap. */
    public static double bossCap(double dmg, double cap) {
        if (cap <= 0 || dmg <= cap) return dmg;
        return cap + 0.1 * (dmg - cap);
    }

    /** Defense (or true defense) reduction: dmg * 100/(100+def). Negative defense counts as 0. */
    public static double reduce(double dmg, double defense) {
        double def = Math.max(0, defense);
        return dmg * 100.0 / (100.0 + def);
    }

    /** Fraction of damage blocked: def/(def+100). */
    public static double reductionFraction(double defense) {
        double def = Math.max(0, defense);
        return def / (def + 100.0);
    }

    /** Effective health: hp * (1 + def/100). */
    public static double ehp(double health, double defense) {
        return health * (1 + Math.max(0, defense) / 100.0);
    }

    /**
     * Mob-side pipeline: magic resistance (0..1), boss cap, defense, floor. Returns the integer
     * damage that is actually dealt.
     */
    public static double mobSide(double dmg, double magicResistance, double cap, double defense) {
        double d = dmg * (1 - clamp(magicResistance, 0, 1));
        d = bossCap(d, cap);
        d = reduce(d, defense);
        return Math.floor(Math.max(0, d));
    }

    /**
     * Extra ferocity strikes. {@code roll} is a uniform [0,1) random. Ferocity is capped at {@code cap}.
     * 250 Fer = 2 guaranteed + 50% for a third.
     */
    public static int ferocityStrikes(double ferocity, double cap, double roll) {
        double fer = clamp(ferocity, 0, cap);
        int extra = (int) Math.floor(fer / 100.0);
        double rem = fer - extra * 100.0;
        if (rem > 0 && roll * 100.0 < rem) extra++;
        return extra;
    }

    /** Crit roll: crit when roll*100 &lt; chance; chance &gt;= 100 always crits. */
    public static boolean critRoll(double critChance, double roll) {
        if (critChance >= 100) return true;
        if (critChance <= 0) return false;
        return roll * 100.0 < critChance;
    }

    /**
     * Mob invulnerability window (ticks between hits that land) for an attacker with this much
     * Bonus Attack Speed. 100% AS halves it. Never below {@code min}.
     */
    public static int invulnTicks(int baseTicks, double attackSpeed, double asCap, int min) {
        double as = clamp(attackSpeed, 0, asCap);
        int t = (int) Math.round(baseTicks / (1 + as / 100.0));
        return Math.max(min, t);
    }

    /** Percent-of-max-health environment damage. */
    public static double percentOfMax(double maxHealth, double percent) {
        return Math.max(0, maxHealth * percent / 100.0);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
