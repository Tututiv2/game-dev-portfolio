package com.skyclone.mod.soulflow;

/**
 * Pure Soulstream arithmetic (no Bukkit), unit-tested against the reference numbers.
 *
 * <p>Model (research 06 + NEU lore of the three accessories): Soulstream is a per-player pool of whole units ("⸎").
 * Items are INTERNALIZED into it (Raw = 1, Soulstream = 160). Abilities drain the internalized pool first; with the
 * inventory fallback on, loose Raw Soulstream in the inventory pays the rest (the pre-accessory behaviour). A charge is
 * all-or-nothing. Leaving combat, the best accessory turns up to {@code units} of the pool into Overflow Mana, topping
 * the Overflow pool up to its {@code overflow} figure (+30% with End Portal Fumes, which is where the research's
 * "780 current max" comes from: 600 x 1.3). Overflow is spent once ordinary mana is short.
 */
public final class SoulflowMath {

    private SoulflowMath() {}

    /** Reference proper nouns that must never reach a player through our own text (docs/names/soulflow.md). */
    public static final String[] REFERENCE_NAMES = {"soulflow", "etherwarp", "esoward", "voidgloom"};

    /** The pool never grows past this (whole units; far below Long overflow). */
    public static final long HARD_CAP = 1_000_000_000_000L;

    /** How a charge of {@code need} is split. {@code ok == false} means nothing may be taken. */
    public record Plan(boolean ok, long fromPool, long fromRaw) {}

    /**
     * Split a charge between the internalized pool and loose Raw Soulstream. Pool first.
     * @param poolUsable false when the pool may not be drained (no Soulstream accessory and the dial requires one)
     */
    public static Plan plan(long pool, boolean poolUsable, long raw, boolean rawUsable, long need) {
        if (need <= 0) return new Plan(true, 0, 0);
        long p = poolUsable ? Math.max(0, pool) : 0;
        long r = rawUsable ? Math.max(0, raw) : 0;
        long fromPool = Math.min(p, need);
        long fromRaw = need - fromPool;
        if (fromRaw > r) return new Plan(false, 0, 0);
        return new Plan(true, fromPool, fromRaw);
    }

    /** What the player can spend right now, by the same rules as {@link #plan}. */
    public static long available(long pool, boolean poolUsable, long raw, boolean rawUsable) {
        return (poolUsable ? Math.max(0, pool) : 0) + (rawUsable ? Math.max(0, raw) : 0);
    }

    /** a + b, clamped to [0, cap]. */
    public static long addCapped(long a, long b, long cap) {
        long c = cap <= 0 ? HARD_CAP : Math.min(cap, HARD_CAP);
        if (b <= 0) return Math.max(0, Math.min(a, c));
        if (a >= c - b) return c;
        return Math.max(0, a + b);
    }

    /** The Overflow ceiling of a tier with a % boost: 600 x (1 + 30/100) = 780. */
    public static double overflowTarget(double tierOverflow, double boostPercent) {
        return Math.max(0, tierOverflow) * (1 + Math.max(0, boostPercent) / 100.0);
    }

    /** One leaving-combat conversion: how many units are burned and where Overflow ends up. */
    public record Conversion(long units, double overflowAfter) {
        public boolean happened() { return units > 0; }
    }

    /**
     * Turn up to {@code maxUnits} of the pool into Overflow, filling toward {@code target}. Each unit is worth
     * {@code target / maxUnits}; a partial unit is still a whole unit burned (the last one tops the pool up).
     */
    public static Conversion convert(double target, int maxUnits, double overflow, long pool) {
        if (target <= 0 || maxUnits <= 0 || pool <= 0) return new Conversion(0, Math.max(0, overflow));
        double cur = Math.max(0, overflow);
        double need = target - cur;
        if (need <= 1e-9) return new Conversion(0, cur);
        double per = target / maxUnits;
        long units = (long) Math.ceil(need / per - 1e-9);
        units = Math.min(units, Math.min(maxUnits, pool));
        if (units <= 0) return new Conversion(0, cur);
        return new Conversion(units, Math.min(target, cur + units * per));
    }

    /** Mirror of core's mana-cost rule: a field in (0,1) is a fraction of max mana. */
    public static double manaCost(double field, double maxMana) {
        if (field <= 0) return 0;
        if (field < 1) return Math.max(0, maxMana * field);
        return field;
    }

    /**
     * Overflow Mana moved into the ordinary pool so an ability can be paid: only what is missing, never more than
     * the Overflow held, never past max mana (core clamps mana to its max).
     */
    public static double overflowDraw(double mana, double cost, double overflow, double maxMana) {
        if (cost <= 0 || overflow <= 0 || mana + 1e-9 >= cost) return 0;
        double missing = cost - mana;
        double room = Math.max(0, maxMana - mana);
        return Math.max(0, Math.min(overflow, Math.min(missing, room)));
    }

    /** Whether a drain is due: never charged, or {@code every} seconds since the last charge. */
    public static boolean due(long lastChargeMs, int everySeconds, long nowMs) {
        return lastChargeMs <= 0 || nowMs - lastChargeMs >= Math.max(1, everySeconds) * 1000L;
    }

    /** "in combat" = a hit dealt or taken within {@code windowSeconds}. */
    public static boolean inCombat(long lastHitMs, double windowSeconds, long nowMs) {
        return lastHitMs > 0 && nowMs - lastHitMs < (long) (Math.max(0.5, windowSeconds) * 1000);
    }

    /** "12,345⸎" style without the comma helper (pure). */
    public static String units(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n) + "⸎";
    }
}
