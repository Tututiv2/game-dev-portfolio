package com.skyclone.api.stat;

import java.util.EnumMap;
import java.util.Map;

/**
 * Accumulates stat contributions. Final value of a stat is
 * <pre>  (base + flat) * (1 + percent/100) * product(multipliers)</pre>
 * {@code flat} is what armour, reforges, skills and pets add. {@code percent} is additive
 * "+5% all stats"-style bonuses. {@code multiply} is for the rare true multipliers.
 *
 * <p><b>Overrides</b> ({@link #set}, integration 4): a provider may REPLACE a stat's final value for
 * one player, e.g. the Rift swapping the main Intelligence/Speed for the Rift's own while a player
 * is inside. A set stat ignores base, flat, percent and multipliers entirely; when two providers
 * set the same stat, the one registered LATER wins (providers run in registration order). Call
 * {@code set} only for the players it applies to — the provider runs per player, which is what makes
 * the override per-player — and it lapses on the next recompute once the provider stops calling it.
 *
 * <p>Not thread-safe. Providers are called on the main thread.
 */
public final class StatMap {

    private final EnumMap<Stat, Double> flat = new EnumMap<>(Stat.class);
    private final EnumMap<Stat, Double> pct = new EnumMap<>(Stat.class);
    private final EnumMap<Stat, Double> mult = new EnumMap<>(Stat.class);
    private final EnumMap<Stat, Double> set = new EnumMap<>(Stat.class);

    public StatMap add(Stat s, double v) { if (v != 0) flat.merge(s, v, Double::sum); return this; }
    public StatMap addPercent(Stat s, double percent) { if (percent != 0) pct.merge(s, percent, Double::sum); return this; }
    public StatMap multiply(Stat s, double factor) { mult.merge(s, factor, (a, b) -> a * b); return this; }

    /**
     * Replace the FINAL value of {@code s} (base included) with {@code value}. NaN/infinite values are
     * ignored. A later {@code set} of the same stat replaces an earlier one. (Integration 4.)
     */
    public StatMap set(Stat s, double value) {
        if (s != null && Double.isFinite(value)) set.put(s, value);
        return this;
    }

    /** Drop an override made with {@link #set}. */
    public StatMap unset(Stat s) { if (s != null) set.remove(s); return this; }

    /** The override of {@code s}, or null when its value is computed normally. */
    public Double override(Stat s) { return set.get(s); }

    public StatMap addAll(Map<Stat, Double> m) { m.forEach(this::add); return this; }
    public StatMap addAll(StatMap o) {
        o.flat.forEach(this::add);
        o.pct.forEach(this::addPercent);
        o.mult.forEach(this::multiply);
        set.putAll(o.set);   // later maps win, like providers registered later
        return this;
    }

    public double flat(Stat s) { return flat.getOrDefault(s, 0.0); }
    public double percent(Stat s) { return pct.getOrDefault(s, 0.0); }
    public double multiplier(Stat s) { return mult.getOrDefault(s, 1.0); }

    /** Final value INCLUDING {@link Stat#base}; an override ({@link #set}) wins over everything. */
    public double total(Stat s) {
        Double o = set.get(s);
        if (o != null) return o;
        return (s.base + flat(s)) * (1 + percent(s) / 100.0) * multiplier(s);
    }

    /** Final value WITHOUT the base — for item lore, where a sword "has +20 Strength". Overridden: the override minus base. */
    public double bonus(Stat s) {
        Double o = set.get(s);
        if (o != null) return o - s.base;
        return flat(s) * (1 + percent(s) / 100.0) * multiplier(s);
    }

    public Map<Stat, Double> flatView() { return java.util.Collections.unmodifiableMap(flat); }

    /** Every override in this map (read-only). */
    public Map<Stat, Double> overrideView() { return java.util.Collections.unmodifiableMap(set); }

    public boolean isEmpty() { return flat.isEmpty() && pct.isEmpty() && mult.isEmpty() && set.isEmpty(); }
}
