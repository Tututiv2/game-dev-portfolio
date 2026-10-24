package com.skyclone.mod.alchemy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player alchemy state, member key {@code "alchemy"}. Gson-friendly: add fields, never retype them
 * (CORE_GUIDE trap 3). Re-fetch with {@code Sky.profiles().member(p, "alchemy", AlchemyData.class)}
 * every time; never keep it across ticks.
 */
public final class AlchemyData {

    /** One active potion effect. {@code left} is seconds of ONLINE time remaining. */
    public static final class Active {
        public int level;
        public long left;
        /** "potion", "splash", "dungeon", "api", "admin". */
        public String source = "potion";
        /** Basic Brew the potion was brewed from (item id, e.g. CHEAP_COFFEE), or null. */
        public String brew;

        public Active() {}

        public Active(int level, long left, String source) {
            this.level = level;
            this.left = left;
            this.source = source;
        }
    }

    /** effect id -> active effect (one per effect: a new drink replaces the old one). */
    public Map<String, Active> effects = new HashMap<>();
    /** God Potion seconds left (stacks when more are drunk). */
    public long godLeft;
    /** Effects of the God Potion the player switched off in /effects. */
    public Set<String> godOff = new HashSet<>();

    /** Potion bag slots, base64 ItemStack bytes ("" = empty). */
    public List<String> bag = new ArrayList<>();

    /**
     * One brewing stand's contents (wave 5: every stand block holds its own). Gson-friendly; never keep
     * across ticks, re-fetch through {@code BrewingStand.state}.
     */
    public static final class Stand {
        /** [ingredient, bottle 1, bottle 2, bottle 3], base64 ItemStack bytes ("" = empty). */
        public List<String> slots = new ArrayList<>();
        /** Epoch millis when the current brew started; 0 = idle. */
        public long brewStart;
        /** Ingredient id the current brew was started with. */
        public String brewIngredient;
        /** How long this brew takes (fixed when it starts: the starter's Witch pet); 0 = the dial. */
        public long brewMillis;
        /** Brewer bonus (% duration) of whoever started the brew, stamped on the potions it makes. */
        public double brewerPct;
        /** Uuid of whoever started the current brew. */
        public String brewer;
        /** Alchemy XP waiting in each bottle slot; paid to whoever TAKES the potion out (research 02 2.4.7). */
        public List<Double> xp = new ArrayList<>();
        /** What each bottle slot held when its XP was stored ({@link Stands#sig}); a different item = taken. */
        public List<String> sig = new ArrayList<>();
        /** Epoch millis of the last use. */
        public long used;
    }

    /** LEGACY (before wave 5): the one personal stand. Migrated into {@link #portable} on first use. */
    public List<String> stand = new ArrayList<>();
    /** LEGACY: see {@link #stand}. */
    public long brewStart;
    /** LEGACY: see {@link #stand}. */
    public String brewIngredient;

    /** The stand {@code /brew} opens (the old personal stand). Null until first used. */
    public Stand portable;
    /**
     * Public brewing stand blocks (hub alchemy shop, other islands, someone else's instanced island):
     * every block holds its OWN brew, per player. Key {@code <island>|x,y,z}. Island stands are in the
     * profile's shared {@link IslandStands} instead.
     */
    public Map<String, Stand> stands = new HashMap<>();
    /** Refined Midnight Wit bottles drunk (+1 Intelligence for good each, at most 5). */
    public int jyrreRefined;

    /** Epoch millis of the last effect tick, for the optional offline-time dial. */
    public long lastTick;
    /** Whether the Alchemist's intro dialogue has been heard. */
    public boolean metAlchemist;
    /** Lifetime stats for the alchemy hub. */
    public long potionsBrewed;
    public long potionsDrunk;
}
