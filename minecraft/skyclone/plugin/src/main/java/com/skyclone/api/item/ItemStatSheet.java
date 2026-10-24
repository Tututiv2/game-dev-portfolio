package com.skyclone.api.item;

import com.skyclone.api.stat.Stat;

import java.util.EnumMap;
import java.util.Map;

/**
 * The stats ONE item instance grants, split by where they come from so lore can colour them:
 * <pre>
 *   &amp;7Strength: &amp;c+75 &amp;e(+20) &amp;9(+15) &amp;d[+10]
 *                 total  potato  reforge  gems
 * </pre>
 * Core fills BASE from the ItemDef and REFORGE from the ReforgeDef; modules add the rest through
 * {@link ItemStatContributor}s (enchant stat bonuses, gems, stars, potato books...).
 */
public final class ItemStatSheet {

    public enum Source { BASE, POTATO, REFORGE, GEMSTONE, ENCHANT, STARS, OTHER }

    private final EnumMap<Stat, EnumMap<Source, Double>> lines = new EnumMap<>(Stat.class);
    private double damage;
    private double damageBonus;

    public void add(Stat stat, double v, Source src) {
        if (v == 0) return;
        lines.computeIfAbsent(stat, k -> new EnumMap<>(Source.class)).merge(src, v, Double::sum);
    }

    /** Weapon damage; {@code bonus} is the non-base part (potato books, stars) shown in brackets. */
    public void addDamage(double v, boolean bonus) {
        damage += v;
        if (bonus) damageBonus += v;
    }

    public double damage() { return damage; }
    public double damageBonus() { return damageBonus; }

    public double total(Stat s) {
        EnumMap<Source, Double> m = lines.get(s);
        if (m == null) return 0;
        double t = 0;
        for (double v : m.values()) t += v;
        return t;
    }

    public double part(Stat s, Source src) {
        EnumMap<Source, Double> m = lines.get(s);
        return m == null ? 0 : m.getOrDefault(src, 0.0);
    }

    public Map<Stat, EnumMap<Source, Double>> view() { return java.util.Collections.unmodifiableMap(lines); }
}
