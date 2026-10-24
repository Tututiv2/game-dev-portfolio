package com.skyclone.mod.garden;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The profile's Garden (shared by every co-op member: {@code Sky.profiles().shared(p, "garden", ...)}).
 * Gson-friendly. NEVER change a field's type (core would discard the stored value): add fields.
 */
public final class GardenData {

    public double xp;
    public long copper;
    /** Garden levels whose rewards were already granted (starts at 1 = level I). */
    public int levelPaid = 1;
    /** First visit handled (starter plot given, tutorial sent). */
    public boolean started;

    /** Unlocked plots by plot id. */
    public Map<String, PlotState> plots = new HashMap<>();

    /** Crop key -> amount harvested on the Garden (crop milestones). */
    public Map<String, Long> milestones = new HashMap<>();
    /** Crop key -> highest milestone tier already rewarded. */
    public Map<String, Integer> milestonePaid = new HashMap<>();
    /** Crop key -> crop upgrade tier (0-9). */
    public Map<String, Integer> upgrades = new HashMap<>();

    public List<VisitorState> visitors = new ArrayList<>();
    public long nextVisitorAt;
    public long visitorClock;
    public Map<String, VisitorLog> log = new HashMap<>();
    public int offersAccepted;
    public int uniqueTierPaid;
    public int acceptedTierPaid;
    /** Visitors whose one-time bonus (the greenhouse blueprint) was already given. */
    public List<String> onceGiven = new ArrayList<>();

    public ComposterState composter = new ComposterState();

    public long pestCooldownUntil;
    /** Last time the profile was seen online by the garden (offline pests). */
    public long lastSeen;
    public List<TrapState> traps = new ArrayList<>();
    /** The vinyl the Maximus vacuum is playing (an item id) or null. */
    public String vinyl;

    public GreenhouseState greenhouse = new GreenhouseState();

    public String barnSkin = "default";
    public List<String> barnSkins = new ArrayList<>();
    /** Greenhouse skin id ({@link GreenhouseBuilder#SKINS}) and the ones bought with Copper. */
    public String greenhouseSkin = "default";
    public List<String> greenhouseSkins = new ArrayList<>();

    /** Garden chip item id -> level. */
    public Map<String, Integer> chips = new HashMap<>();
    /** Unused since lane 13 (the Overtuner raised chip caps; real caps come from the chip's rarity). Kept so old saves load. */
    public int chipCapBonus;
    /** Garden chip item id -> chips redeemed into it (sets its rarity: 1 Rare, 5 Epic, 21 Legendary). Lane 13. */
    public Map<String, Integer> chipsRedeemed = new HashMap<>();
    /** Sowdust (lane 13): earned by farming on the Garden, spent on chip levels. */
    public double sowdust;
    /** Lifetime Sowdust earned (stats / tab). */
    public double sowdustEarned;

    public long cropsBroken;

    public static final class PlotState {
        public String name;
        public String icon;
        public String sprayItem;
        public long sprayUntil;
        public double sprayBpc;
        public double sprayMult = 1;
        public int pests;
        /** Which pests those are (pest ids), so a body re-created later is the same kind. May be shorter than {@link #pests} for old saves. */
        public List<String> pestTypes = new ArrayList<>();
        public String preset;
        public long boughtAt;
        /** Overgrowth blocks cleared on this plot that paid a clearing reward (capped by {@code garden.clear-reward-cap}). */
        public int cleared;
    }

    public static final class VisitorState {
        public String id;
        public String item;
        public long amount;
        /** A second requested item (bigger offers, research 08 1.10 "1-2 item types"), or null. */
        public String item2;
        public long amount2;
        public long copper;
        public int gardenXp;
        public double farmingXp;
        public int bits;
        public long arrivedAt;
        public int slot;
    }

    public static final class VisitorLog {
        public int visits;
        public int accepted;
        public int refused;
        public long last;
    }

    public static final class ComposterState {
        public double organic;
        public double fuel;
        public int stored;
        public long last;
        public double progress;
        public long totalMade;
        public Map<String, Integer> upgrades = new HashMap<>();
    }

    public static final class TrapState {
        public String item;
        public String plot;
        public String bait;
        public int baitLeft;
        public List<String> caught = new ArrayList<>();
        public long last;
    }

    public static final class GreenhouseState {
        public boolean unlocked;
        public List<Cell> cells = new ArrayList<>();
        public List<String> analyzed = new ArrayList<>();
        public long lastSim;
        public long harvested;
        public long stageMillis;
        /** Greenhouse grids built (1 = the first; research 08 1.15: secondary and tertiary). */
        public int grids = 1;
    }

    public static final class Cell {
        /** Crop key or mutation id; null = empty. */
        public String crop;
        public boolean mutation;
        public int stage;
        public int water;
        public long nextStageAt;
        public boolean dead;
    }

    // ---- helpers --------------------------------------------------------------------------------

    public int upgrade(String crop) { return upgrades.getOrDefault(crop, 0); }

    public long milestone(String crop) { return milestones.getOrDefault(crop, 0L); }

    public int uniqueServed() {
        int n = 0;
        for (VisitorLog l : log.values()) if (l.accepted > 0) n++;
        return n;
    }

    public int livePests() {
        int n = 0;
        for (PlotState s : plots.values()) n += Math.max(0, s.pests);
        return n;
    }

    public int chip(String id) { return chips.getOrDefault(id, 0); }

    /**
     * Chips redeemed into {@code id}. Saves from before lane 13 have a level but no count: there, one chip was one
     * level, so the level IS the number of chips that went in.
     */
    public int redeemed(String id) {
        Integer n = chipsRedeemed.get(id);
        if (n != null) return n;
        return chip(id);
    }
}
