package com.skyclone.mod.hunting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-player hunting state (profile member key "hunting"). Gson-friendly; add fields, never retype them. */
public final class HuntingData {

    /** Hunting Box: shard id -> shards stored. */
    public Map<String, Integer> box = new HashMap<>();
    /** Shard id -> shards syphoned into its attribute (the level is derived from this). */
    public Map<String, Integer> syphoned = new HashMap<>();
    /** Attribute keys switched off in the Attribute Menu. */
    public Set<String> disabled = new HashSet<>();
    /** Shard id -> times captured (the Shard Collection / critterdex). */
    public Map<String, Long> caught = new HashMap<>();
    public long totalCaught;
    public long totalSyphoned;
    public long totalFusions;
    /** Salt item id -> expiry (epoch ms). */
    public Map<String, Long> salts = new HashMap<>();
    /** Placed huntraps. */
    public List<Trap> traps = new ArrayList<>();
    /** Huntaxe absorbed weapon per huntaxe item id ("weapon id"). */
    public Map<String, String> huntaxeWeapon = new HashMap<>();

    // ---- Critter Safari ----
    /** Biome -> hunts finished there (milestones). */
    public Map<String, Long> safariHunts = new HashMap<>();
    /** Safari perk id -> level. */
    public Map<String, Integer> safariPerks = new HashMap<>();
    /** Fallback Safari Essence when no essence pouch is installed. */
    public long safariEssence;
    public long sparkling;
    /** Active ticket id while a safari hunt runs; null when none. */
    public String safariTicket;
    public int safariCapsules;
    public long safariEndsAt;
    public long safariCaught;

    public static final class Trap {
        public String item;
        public String habitat;
        public String world;
        public double x, y, z;
        public long placedAt;
        public long readyAt;
        /** Shard the trap caught (decided when it is placed). */
        public String shard;
    }
}
