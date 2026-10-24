package com.skyclone.mod.hunting;

import java.util.ArrayList;
import java.util.List;

/**
 * One attribute shard, as generated into {@code data/hunting/shards.json} by
 * {@code tools/gen/hunting/gen_hunting.py}. Pure data (Gson fills it); no Bukkit.
 *
 * <p>Every shard IS an attribute: syphoning shards of one kind into the Hunting Box raises that
 * attribute's level (1-10). {@link #effect} is the effect of ONE level; {@link #text} is its lore
 * template with {@code {v1}} / {@code {v2}} where the per-level numbers go.
 */
public final class ShardDef {
    public String id;
    public String name;
    public String rarity = "COMMON";
    /** Forest / Water / Combat. */
    public String alignment;
    public List<String> families = new ArrayList<>();
    /** The reference id line code: C1, U14, L47... */
    public String code;
    public int num;
    /** Attribute key, lower snake ("fishing_speed"). What {@code Features.Hunting.attributeLevel} takes. */
    public String attr;
    public String attrName;
    public String text;
    public Effect effect = new Effect();
    /** Echo groups this attribute belongs to ("resistance", "ruler", "wisdom"...). */
    public List<String> groups = new ArrayList<>();
    public List<Source> sources = new ArrayList<>();
    public boolean fusionOnly;
    /** Cannot be syphoned (the Chameleon): it only exists to be fused. */
    public boolean unconsumable;

    /** What one attribute level does. kind: stat, stat_pct, pseudo, damage_vs, defense_vs, mf_vs, echo, hf_rarity,
     *  melee, ranged, full_hp, low_hp, payback, hook. */
    public static final class Effect {
        public String kind = "hook";
        public String stat;
        public String stat2;
        public String stat3;
        public double per;
        public double per2;
        /** null, night, day, foraging_islands, fishing_islands, mining_islands, combat_islands, island:&lt;id&gt;. */
        public String cond;
        /** Mob type for *_vs kinds ("undead", "boss"...). */
        public String target;
        /** Echo target group. */
        public String group;
        /** hf_rarity: COMMON..LEGENDARY. */
        public String rarity;
    }

    /** Where it comes from: mob kills (charm chance / black hole), a critter, or fusion only. */
    public static final class Source {
        public String type;
        public List<String> mobs = new ArrayList<>();
        public String mob;
        public String method;
        public List<String> habitats = new ArrayList<>();
    }

    public int rarityIndex() { return HuntMath.rarityIndex(rarity); }

    public boolean inFamily(String f) { return families != null && families.contains(f); }

    public boolean hasSource(String type) {
        for (Source s : sources) if (type.equals(s.type)) return true;
        return false;
    }

    /** The critter mob id, or null. */
    public String critterMob() {
        for (Source s : sources) if ("critter".equals(s.type)) return s.mob;
        return null;
    }

    /** Effect lore at {@code level} (level 0 shows level 1's numbers). '&' coded. */
    public String effectText(int level, double echoMult) {
        int l = Math.max(1, level);
        String t = text == null ? "" : text;
        t = t.replace("{v1}", HuntMath.fmt(effect.per * l * echoMult));
        t = t.replace("{v2}", HuntMath.fmt((effect.per2 != 0 ? effect.per2 : effect.per) * l * echoMult));
        t = t.replace("{v3}", HuntMath.fmt(effect.per * l * echoMult));
        return t;
    }
}
