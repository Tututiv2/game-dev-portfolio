package com.skyclone.mod.farming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player farming state, member key {@code "farming"}. Plain Gson data: add fields, never retype them.
 * Other modules may READ these field names with their own mirror class (see the hand-off).
 */
public class FarmData {

    /** Crop (enum name) -> blocks harvested by hand, all time (all islands this module handles). */
    public Map<String, Long> cropsBroken = new HashMap<>();
    /** Crop (enum name) -> items collected by hand, all time. */
    public Map<String, Long> cropsCollected = new HashMap<>();

    // ---- Harvest Fair (contests) ------------------------------------------------------------------
    /** Medal purse: spent at Harvesta's Medal Exchange. */
    public int bronze, silver, gold;
    /** Medals earned all time, by bracket name. */
    public Map<String, Integer> medalsEarned = new HashMap<>();
    /** Crop -> best score in one contest. */
    public Map<String, Long> personalBest = new HashMap<>();
    /** Crop -> best bracket name ever reached. */
    public Map<String, String> bestBracket = new HashMap<>();
    /** Crops with at least one GOLD-or-better (the farming level cap steps need distinct ones). */
    public Set<String> uniqueGolds = new HashSet<>();
    public int contestsJoined;
    /** The contest in progress this player joined: its index, locked crop and score. */
    public long contestIndex = Long.MIN_VALUE;
    public String contestCrop;
    public long contestScore;
    /** Finished contests, newest last (capped). Unclaimed ones are paid on claim. */
    public List<ContestRecord> history = new ArrayList<>();
    public boolean metHost;

    // ---- Harvesta's upgrades ------------------------------------------------------------------------
    /** Extra Farming Fortune tiers bought (0..15, +4 each). */
    public int anitaFortune;
    /** Farming level-cap steps bought (0..10 -> cap 50..60). */
    public int capSteps;
    /** Harvesta's Personal Bests perk: each crop's best Harvest Fair score adds Crop Fortune. */
    public boolean pbUnlocked;

    // ---- Trapper -----------------------------------------------------------------------------------
    public long pelts;
    public long peltsEarned;
    public TrapQuest quest;
    public long trapperCooldownUntil;
    public Map<String, Integer> trapsByRarity = new HashMap<>();
    /** Pelts earned while the Trapper Crest counted (its upgrade to Uncommon at 100, research 06). */
    public long crestPelts;
    /** Told once that the crest is ready but sits in the bag (it must be in the inventory to be upgraded). */
    public boolean crestReadyTold;

    // ---- Digger Nim's buried treasure (Treasure Hunter Camp) ----------------------------------------
    public TreasureHint treasure;
    public long treasureCooldownUntil;
    public int treasuresFound;

    // ---- Shepherd Maela's shearing task (Shepherd's Keep) ------------------------------------------
    public int shearingTasksDone;
    public long shepherdCooldownUntil;
    public long sheepSheared;

    // ---- Shiny Pigs + Refined Dark Cacao Truffle (wave 5) ---------------------------------------------
    /** Shiny Pigs delivered to their orb (the bestiary keeps its own count). */
    public int shinyPigsCaught;
    public int shinyPigsEscaped;
    public long shinyCoins;
    /** Epoch millis the truffle's +30 Cocoa Beans Fortune runs until (0 = none). */
    public long truffleUntil;
    /** Truffles eaten (each +1 permanent Cocoa Beans Fortune up to the dial's cap). */
    public int trufflesEaten;

    public static class TreasureHint {
        public int x, y, z;
        public long expires;
        public TreasureHint() {}
    }

    public static class ContestRecord {
        public long index;
        public long start;
        public String crop;
        public long score;
        public String bracket;
        public int position;
        public int participants;
        public boolean claimed;
        public ContestRecord() {}
    }

    public static class TrapQuest {
        public String animal;      // cow, pig, sheep, chicken, rabbit, horse
        public String rarity;      // trackable .. elusive
        public String area;        // layout area id
        public String areaName;    // display name of the area
        public long started;
        public long expires;
        public String mobUuid;
        public double x, y, z;
        public TrapQuest() {}
    }

    public long broken(Crop c) { return cropsBroken.getOrDefault(c.name(), 0L); }
    public long collected(Crop c) { return cropsCollected.getOrDefault(c.name(), 0L); }
}
