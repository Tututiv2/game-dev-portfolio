package com.skyclone.mod.weapons;

import java.util.Map;

/**
 * A weapon's passive behaviour, parsed (Gson) from {@code ItemDef.extra.w}. The generator documents
 * every key in tools/gen/weapons/spec.py. All fields default to "off".
 */
final class Trait {
    Map<String, Double> vs;
    Map<String, Double> vsMult;
    Map<String, Double> wisdomVs;
    Map<String, Double> lessFrom;
    double melee;
    double mult;
    double healHit;
    double water;
    double lavaStr;
    double miningIslands;
    double spooky;
    double ignite;
    double poisonStr;
    double cleave;
    // ---- bows
    double shortbow;
    int arrows;
    double extraPct;
    boolean homing;
    int volley;
    int multiHit;
    boolean endermen;
    double critDiv;
    boolean alwaysCrit;
    Infusion infusion;
    double explosive;
    double bounce;
    double mark;
    double aura;
    int pierce;
    double speed;
    double dragonPct;
    boolean hurricane;
    // ---- scaling passives
    KillsDamage killsDamage;
    boolean raider;
    boolean tactician;
    boolean emerald;
    String greed;
    double shaman;
    double pooch;
    double voidSword;
    double daedalus;
    /** Labyrinth Blade (Daedalus Axe): the held weapon also grants the active pet's stats again. */
    boolean petCopy;
    /** Raise Souls: how many souls the blade holds (Necromancer Sword 2, Reaper Scythe 3). */
    int souls;
    /** Blaze daggers: Burning Vengeance (ashen/auric) and Wraith Drain (spirit/crystal). */
    boolean vengeance;
    boolean wraith;
    /** Great Spook Sword: +1 Damage and +1 Strength per Fear. */
    boolean fear;
    double missingVsUndead;
    double missingSelf;
    double targetMissing;
    DefShred defShred;
    Map<String, Map<String, Integer>> animalDrops;
    Map<String, Double> cataStats;
    double dungeonHeal;
    // ---- wave 5
    /** +Magic Find on drops of that mob type (core's per-target Magic Find hook): Sword of Revelations. */
    Map<String, Double> mfVs;
    /** Labyrinth Blade: per Mythological Bestiary tier, +dmg% damage and +mf Magic Find on mythic foes. */
    MythBestiary mythBestiary;
    /** Right-click inside a dungeon uses the dungeon class ability (Highlord & co, Earthen, Adaptive, Fel). */
    boolean classAbility;
    /** Adaptive Blade: class bonuses inside dungeons. */
    boolean adaptive;
    /** Zombie Commander Whip: +1 Strength per kill of this mob type during the current dungeon run. */
    String whip;
    /** +n Strength with a full armour set {setId: n} (Zombie Knight Sword). */
    Map<String, Double> setStr;
    /** Stinger Bow: arrows on dungeon teammates buff them. */
    boolean sting;
    /** Soulstealer Bow: arrows fly as wither skulls (the blast itself is {@link #explosive}). */
    boolean witherSkull;
    /**
     * Dungeon floor rolls (research items.json {@code tiered_stats}): stat key (DAMAGE or a Stat name) to the value per
     * {@code item_tier}. The def carries index 0; an item whose {@code ItemData.extra.item_tier} is set gets the rest.
     */
    Map<String, double[]> tiered;

    static final class MythBestiary {
        double dmg;
        double mf;
    }

    static final class Infusion {
        String item;
        double mult = 1;
        String vs;
    }

    static final class KillsDamage {
        int per = 100;
        int max = 100;
    }

    static final class DefShred {
        double pct;
        int stacks;
    }

    boolean isShortbow() { return shortbow > 0; }

    boolean tracksKills() { return hurricane || raider || killsDamage != null; }
}
