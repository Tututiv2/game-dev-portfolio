package com.skyclone.mod.fishing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player fishing state, stored by core under member key "fishing". Gson-friendly: add fields,
 * never retype them (CORE_GUIDE 0.3).
 */
public class FishData {
    /** Sea creature mob id -> times hooked. */
    public Map<String, Long> caught = new HashMap<>();
    /** Sea creature mob id -> times killed. */
    public Map<String, Long> kills = new HashMap<>();
    /** Trophy fish stem -> tier -> count. */
    public Map<String, Map<String, Integer>> trophies = new HashMap<>();
    /** Trophy catches since the last Gold-or-better / Diamond (pity counters). */
    public int sinceGold;
    public int sinceDiamond;
    public long trophyTotal;
    public long itemsCaught;
    public long casts;
    public long treasureGood;
    public long treasureGreat;
    public long treasureOutstanding;
    public long doubleHooks;
    public long magmafishFilleted;
    /** Fishing Bag: one entry per slot, "ITEM_ID:count" or "" (baits are stackable: an id and a count is lossless). */
    public List<String> bag = new ArrayList<>();
    /** Take baits from the Fishing Bag when the inventory has none. */
    public boolean bagEnabled = true;
    /** Bait to use first (null = the first bait found). */
    public String preferredBait;
    /** One free starter rod per profile member. */
    public boolean starterRod;
    /** Rod parts applied, ever (for the menu's "Tackle" counter). */
    public long partsApplied;
    /** First time each pool was fished (pool id -> epoch ms), for discovery messages. */
    public Map<String, Long> poolsSeen = new HashMap<>();
    /** Isopod Husks eaten (+1 Respiration each, wave 5). */
    public int isopodHusks;
    /** Puddle Jumpers ridden to the end / lost mid-leap (wave 5). */
    public long puddleRides;
    public long puddleFalls;

    /**
     * Chum buckets this profile has PLACED (item ids, one entry per bucket). Written when one is put down and
     * cleared when it is picked up, so a bucket that vanished with a restart / crash / logout is handed back
     * exactly once (ChumBuckets.sweep). Wave 5.
     */
    public List<String> chumBuckets = new ArrayList<>();
    /** Chum deposited into buckets (all buckets / someone else's). */
    public long chumDeposited;
    public long chumDepositedOthers;

    /** Items ever fished up (plain catches, treasure, junk), item id -> count. Audit 2026-10-06 (Jonesy's tasks). */
    public Map<String, Long> fished = new HashMap<>();
    /** Bayou guide chapters fully claimed (0..7). */
    public int jonesyChapter;
    /** Tasks of the CURRENT chapter already paid ("&lt;chapter&gt;:&lt;task index&gt;"). */
    public List<String> jonesyTasks = new ArrayList<>();
    /** The Pearl of the Swamp (Chapter VII's reward): +1 Treasure Chance for good. */
    public boolean swampPearl;
    /** Moby-Duck read: this much Fishing Wisdom until {@link #mobyUntil} (epoch ms). */
    public double mobyWisdom;
    public long mobyUntil;
    /** The Collector's Edition's one-time permanent +1 Fishing Wisdom has been taken. */
    public boolean mobyBookworm;

    public long fished(String item) { return fished.getOrDefault(item, 0L); }

    public long caught(String mob) { return caught.getOrDefault(mob, 0L); }

    public long kills(String mob) { return kills.getOrDefault(mob, 0L); }

    public int trophy(String stem, String tier) {
        Map<String, Integer> m = trophies.get(stem);
        return m == null ? 0 : m.getOrDefault(tier, 0);
    }

    /** Highest tier index caught of a fish (-1 = never). */
    public int bestTier(String stem) {
        Map<String, Integer> m = trophies.get(stem);
        if (m == null) return -1;
        int best = -1;
        for (int i = 0; i < FishingCatalog.TIERS.length; i++) if (m.getOrDefault(FishingCatalog.TIERS[i], 0) > 0) best = i;
        return best;
    }
}
