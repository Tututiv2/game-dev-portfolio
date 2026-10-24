package com.skyclone.mod.hunting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One player's attributes, resolved: level per attribute, the Echo multipliers applied, and every effect
 * grouped by what reads it (stats, damage, defense, Magic Find...). Pure; built when the player's syphons or
 * toggles change, then read every hit / every stat recompute.
 *
 * <p>Echo: an "Echo of X" attribute makes every attribute in group X {@code level x per}% stronger; "Echo of
 * Echoes" does the same to the Echo attributes first (never to itself).
 */
public final class AttrSnapshot {

    /** One flat or conditional stat grant. {@code stat} is a {@code Stat} enum name or a pseudo stat. */
    public record StatGrant(String stat, double value, String cond) {}

    public final Map<String, Integer> levels;
    /** Effective value of ONE effect unit for each attribute: level x per x echo. */
    public final Map<String, Double> values = new HashMap<>();
    public final Map<String, Double> echoMult = new HashMap<>();
    public final List<StatGrant> stats = new ArrayList<>();
    public final List<StatGrant> statPct = new ArrayList<>();
    public final List<StatGrant> pseudo = new ArrayList<>();
    public final Map<String, Double> damageVs = new HashMap<>();
    public final Map<String, Double> defenseVs = new HashMap<>();
    public final Map<String, Double> mfVs = new HashMap<>();
    public final Map<String, Double> hfRarity = new HashMap<>();
    public double melee, ranged, fullHp, lowHp, payback;

    public static final AttrSnapshot EMPTY = new AttrSnapshot(Map.of(), null, Set.of());

    /**
     * @param levels   attribute key -> level (1..10); missing = 0
     * @param cat      the catalog (null = empty snapshot)
     * @param disabled attribute keys the player switched off in the Attribute Menu
     */
    public AttrSnapshot(Map<String, Integer> levels, ShardCatalog cat, Set<String> disabled) {
        Map<String, Integer> lv = new HashMap<>();
        if (cat != null) {
            for (Map.Entry<String, Integer> e : levels.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                if (disabled != null && disabled.contains(e.getKey())) continue;
                if (cat.byAttr(e.getKey()) == null) continue;
                lv.put(e.getKey(), Math.min(HuntMath.MAX_LEVEL, e.getValue()));
            }
        }
        this.levels = Collections.unmodifiableMap(lv);
        if (cat == null) return;
        // echo of echoes first (boosts every OTHER echo attribute)
        double echoOfEchoes = 0;
        String eoeAttr = null;
        for (Map.Entry<String, Integer> e : lv.entrySet()) {
            ShardDef s = cat.byAttr(e.getKey());
            if ("echo".equals(s.effect.kind) && "echo".equals(s.effect.group)) {
                echoOfEchoes += s.effect.per * e.getValue();
                eoeAttr = e.getKey();
            }
        }
        // group multipliers from the echo attributes
        Map<String, Double> groupPct = new HashMap<>();
        for (Map.Entry<String, Integer> e : lv.entrySet()) {
            ShardDef s = cat.byAttr(e.getKey());
            if (!"echo".equals(s.effect.kind) || "echo".equals(s.effect.group)) continue;
            double boost = e.getKey().equals(eoeAttr) ? 1 : 1 + echoOfEchoes / 100.0;
            groupPct.merge(s.effect.group, s.effect.per * e.getValue() * boost, Double::sum);
        }
        for (Map.Entry<String, Integer> e : lv.entrySet()) {
            ShardDef s = cat.byAttr(e.getKey());
            double mult = 1;
            for (String g : s.groups) {
                Double pct = groupPct.get(g);
                if (pct == null) continue;
                if ("elemental".equals(g) && "echo".equals(s.effect.kind)) continue;   // Starchild buffs the OTHERS
                mult += pct / 100.0;
            }
            if ("echo".equals(s.effect.kind) && !e.getKey().equals(eoeAttr) && !"echo".equals(s.effect.group))
                mult = 1 + echoOfEchoes / 100.0;
            echoMult.put(e.getKey(), mult);
            int level = e.getValue();
            double v = s.effect.per * level * mult;
            values.put(e.getKey(), v);
            ShardDef.Effect f = s.effect;
            switch (f.kind == null ? "hook" : f.kind) {
                case "stat" -> {
                    if (f.stat != null) stats.add(new StatGrant(f.stat, v, f.cond));
                    if (f.stat2 != null) stats.add(new StatGrant(f.stat2, (f.per2 != 0 ? f.per2 : f.per) * level * mult, f.cond));
                    if (f.stat3 != null) stats.add(new StatGrant(f.stat3, v, f.cond));
                }
                case "stat_pct" -> { if (f.stat != null) statPct.add(new StatGrant(f.stat, v, f.cond)); }
                case "pseudo" -> {
                    if (f.stat != null) pseudo.add(new StatGrant(f.stat, v, f.cond));
                    if ("fig_fortune".equals(f.stat)) pseudo.add(new StatGrant("mangrove_fortune", (f.per2 != 0 ? f.per2 : f.per) * level * mult, f.cond));
                }
                case "damage_vs" -> damageVs.merge(f.target, v, Double::sum);
                case "defense_vs" -> defenseVs.merge(f.target, v, Double::sum);
                case "mf_vs" -> mfVs.merge(f.target, v, Double::sum);
                case "hf_rarity" -> hfRarity.merge(f.rarity, v, Double::sum);
                case "melee" -> melee += v;
                case "ranged" -> ranged += v;
                case "full_hp" -> fullHp += v;
                case "low_hp" -> lowHp += v;
                case "payback" -> payback += v;
                default -> { }
            }
        }
    }

    public int level(String attr) { return levels.getOrDefault(attr, 0); }

    /** Effective value of one attribute (level x per x echo); 0 when not owned. */
    public double value(String attr) { return values.getOrDefault(attr, 0.0); }

    /** Sum of a pseudo stat ("charm_chance", "pull", "tracking") whose condition holds. */
    public double pseudo(String stat, java.util.function.Predicate<String> condHolds) {
        double sum = 0;
        for (StatGrant g : pseudo) if (g.stat().equals(stat) && (g.cond() == null || condHolds.test(g.cond()))) sum += g.value();
        return sum;
    }

    /** How many distinct COMMON attributes are owned (Unity is Strength). */
    public int ownedOfRarity(ShardCatalog cat, String rarity) {
        int n = 0;
        for (String a : levels.keySet()) {
            ShardDef s = cat.byAttr(a);
            if (s != null && rarity.equals(s.rarity)) n++;
        }
        return n;
    }
}
