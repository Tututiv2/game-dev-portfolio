package com.skyclone.mod.foraging;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-member foraging state (profile key "foraging"). Gson-friendly: add fields, never retype them. */
public class ForagingData {
    /** Lifetime logs felled by foraging (not vanilla breaks on your own island). */
    public long logs;
    public Map<String, Long> byWood = new HashMap<>();
    /** Warp ids unlocked by travel scrolls ("jungle", "howl"). */
    public Set<String> scrolls = new HashSet<>();
    /** Quest id -> stages completed. */
    public Map<String, Integer> quests = new HashMap<>();
    public boolean lumberJack;
    /** Ember Trials: highest campfire badge tier handed out. */
    public int trialTier;
    /** Harp songs completed (ids). */
    public Set<String> songs = new HashSet<>();
    /** Times the first visit greeting was shown, per NPC. */
    public Set<String> met = new HashSet<>();

    // ---- Heart of the Forest -----------------------------------------------------------
    public boolean hotfUnlocked;
    public long hotfXp;
    /** Highest tier whose rewards (tokens, SkyBlock XP) were handed out. */
    public int hotfRewarded;
    public long forestWhispers;
    public long desertWhispers;
    public long forestEssence;
    public Map<String, Integer> perks = new HashMap<>();
    public Set<String> disabledPerks = new HashSet<>();
    public int tokensSpent;
    public long treeGifts;
    /** Epoch day (UTC) the daily counters belong to. */
    public long day;
    public int giftsToday;
    public Set<String> woodsToday = new HashSet<>();
    /** Moonmarsh logs cut (Frenzy counters live on the axe; this is the total). */
    public long marshLogs;

    // ---- wave 3 ---------------------------------------------------------------------------
    /** Island resources picked (item id -> amount). */
    public Map<String, Long> resources = new HashMap<>();
    /** The selected Heart of the Forest axe ability ("axe_toss", "damage_boost", "mana_toss"), or null. */
    public String axeAbility;
    /** Starfall Contest in progress (wall-clock period id, 0 = none) and its two scores. */
    public long contestId;
    public double contestMarsh;
    public double contestCanyon;
    /** Contests placed today (the first three pay a HotF XP bonus), lifetime entered, best bracket (-1 none). */
    public int contestsToday;
    public int contestsEntered;
    public int bestBracket = -1;

    // ---- wave 5 ---------------------------------------------------------------------------
    /** Honey hives this player emptied: "world:x:y:z" -> epoch ms when it is full again (for them). */
    public Map<String, Long> hiveReady = new HashMap<>();
    public long hivesHarvested;
    public long honeycomb;
    /** Trees this player lathered with a Pot of Honeycomb and the critters still to come. */
    public java.util.List<Lather> lathered = new java.util.ArrayList<>();
    public long crittersLured;

    /** One lathered tree (Gson-friendly). */
    public static class Lather {
        public String world;
        public int x, y, z;
        public String wood;
        public long due;
        public String pot;
        public boolean told;
    }
}
