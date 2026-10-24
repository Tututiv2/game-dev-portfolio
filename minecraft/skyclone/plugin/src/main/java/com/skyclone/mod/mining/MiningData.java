package com.skyclone.mod.mining;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player mining state, member key "mining". Gson-friendly: maps, lists, primitives, strings.
 * RULE: add fields, never retype one (core guide trap 3).
 */
public final class MiningData {

    // ---- powder ---------------------------------------------------------------------------
    /** Current powder by type MITHRIL / GEMSTONE / GLACITE. */
    public Map<String, Long> powder = new HashMap<>();
    /** Lifetime powder earned by type. */
    public Map<String, Long> powderEarned = new HashMap<>();
    /** Powder spent on perks by type (refunded by a reset). */
    public Map<String, Long> powderSpent = new HashMap<>();

    // ---- Heart of the Mountain -------------------------------------------------------------
    public double hotmXp;
    public int tokensSpent;
    public Map<String, Integer> perks = new HashMap<>();
    public Set<String> disabledPerks = new HashSet<>();
    public String selectedAbility;
    /** Highest tier whose one-time rewards were paid. */
    public int rewardedTier;
    /**
     * The Heart wakes the first time the player reaches a powder island (the Dwarven Mines and beyond) or
     * earns any HOTM XP. Tier 1 is reached at 0 XP, so without this gate its one-time rewards (+35 SkyBlock
     * XP) were paid on the very first join, before the player had done anything.
     */
    public boolean hotmAwakened;

    // ---- commissions -----------------------------------------------------------------------
    public Map<String, List<Commission>> commissions = new HashMap<>();
    public int commissionsDone;
    public int milestonesClaimed;
    /** UTC date of the counters below. */
    public String dailyDate = "";
    public int dailyCommissions;
    public Set<String> dailyGrind = new HashSet<>();
    public boolean dailyPowderDone;
    public long gemsToday;

    // ---- forge -----------------------------------------------------------------------------
    public List<ForgeSlot> forge = new ArrayList<>();
    public long forgeCrafted;

    // ---- the King, daily NPCs --------------------------------------------------------------
    public String kingDate = "";
    public Set<String> kingsVisited = new HashSet<>();
    public boolean kingTalismanGiven;
    public String fetchurDate = "";
    public String puzzlerDate = "";
    public String puzzleSeed = "";
    /** UTC date the Fossil Muncher was last fed (wave 5). */
    public String muncherDate = "";
    public int muncherMeals;

    // ---- crystal caverns -------------------------------------------------------------------
    /** Crystal gem types placed in the Nucleus this run. */
    public Set<String> nucleusPlaced = new HashSet<>();
    public int nucleusRuns;
    public Set<String> crystalsFound = new HashSet<>();
    public int chestsOpened;

    // ---- frozen tunnels --------------------------------------------------------------------
    public int mineshaftPity = 2000;
    public String lastMinedGem = "";
    public String lastMinedBlock = "";
    public int mineshaftsFound;
    public int corpsesLooted;
    public int scrapsFound;
    public int fossilsFound;
    public Set<String> fossilsDonated = new HashSet<>();
    public long excavations;

    // ---- misc ------------------------------------------------------------------------------
    public long blocksMined;
    public Map<String, Long> minedById = new HashMap<>();
    public Set<String> scrolls = new HashSet<>();
    public int raffleTickets;
    public int tastyMithril;
    public String raffleEvent = "";
    public boolean hotmIntroSeen;

    public long powder(String type) { return powder.getOrDefault(type, 0L); }

    public int perk(String id) { return perks.getOrDefault(id, 0); }

    /** One active commission. */
    public static final class Commission {
        public String id;
        public int progress;
        public int target;
        /** Zone-specific commissions carry their zone display name and area. */
        public String zone;
        public String area;
        public String name;

        public Commission() {}

        public Commission(String id, int target) { this.id = id; this.target = target; }

        public boolean done() { return progress >= target; }
    }

    /** One forge slot in use. */
    public static final class ForgeSlot {
        public int slot;
        public String recipeId;
        public String result;
        public int amount = 1;
        public long start;
        public long end;
        /** ItemData of a unique input that the result inherits (drill upgrades), as JSON; may be null. */
        public String carry;
    }
}
