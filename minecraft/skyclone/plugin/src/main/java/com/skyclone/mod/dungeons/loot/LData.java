package com.skyclone.mod.dungeons.loot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player dungeon LOOT state (profile member key {@code dungeons_loot}): reward chests waiting at the vault keeper,
 * the RNG meter, essence-shop perks and the kill counters some dungeon sets grow from. Gson-friendly; add fields, never
 * retype them.
 */
public final class LData {

    /** One completed run's chests (kept until every allowed chest is opened or it expires). */
    public static final class StoredRun {
        public String runId;
        public String floor;
        public long time;
        public int score;
        public String grade;
        public int opened;
        public int keysUsed;
        public boolean rerolled;
        public List<LootTables.Rolled> chests = new ArrayList<>();

        /** Chests this run may still open: one, plus one per Dungeon Chest Key used. */
        public boolean canOpenFree() { return opened < 1 + keysUsed; }

        public boolean anyLeft() { for (LootTables.Rolled r : chests) if (!r.opened) return true; return false; }
    }

    public List<StoredRun> croesus = new ArrayList<>();
    /** floor id -> selected RNG meter item id. */
    public Map<String, String> rngItem = new HashMap<>();
    /** floor id -> RNG meter XP. */
    public Map<String, Double> rngXp = new HashMap<>();
    /** essence perk id -> level. */
    public Map<String, Integer> perks = new HashMap<>();
    /** Take reward-chest essence as items (spendable in the essence shop) instead of into the essence pouch. */
    public Boolean essenceAsItems;
    public long zombieKills, bonelordKills;
    public int chestsOpened;
    /** Lifetime reward-chest coin spend (records page). */
    public double coinsSpent;
}
