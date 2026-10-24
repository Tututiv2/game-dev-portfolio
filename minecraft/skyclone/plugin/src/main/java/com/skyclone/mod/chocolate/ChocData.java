package com.skyclone.mod.chocolate;

import java.util.HashMap;
import java.util.Map;

/**
 * One profile member's Chocolate Factory (member key {@code chocolate}). Gson-friendly: add fields, never retype one
 * (a retyped field poisons the key for that player - CORE_GUIDE 0.3).
 */
public final class ChocData {
    /** Chocolate in hand (spendable). */
    public double chocolate;
    /** Chocolate made since the last prestige (the prestige requirement). */
    public double thisPrestige;
    /** Chocolate made ever (factory milestones, leaderboard). */
    public double allTime;
    /** Chocolate spent in the Chocolate Shop (shop milestones). */
    public double spent;

    public int prestige = 1;
    public Map<String, Integer> employees = new HashMap<>();
    public int handBaked = 1;
    public int barn = 1;
    public int timeTower;
    public int shrine;
    public int coach;

    /** Time Tower charges in stock; -1 = never filled (filled when the tower is first built). */
    public int towerCharges = -1;
    /** Epoch ms the next charge arrives (0 = full, nothing pending). */
    public long towerChargeAt;
    /** Epoch ms the running tower stops (0 / past = off). */
    public long towerUntil;

    /** Rabbit id -> times found (1 = found once; more = duplicates). */
    public Map<String, Integer> rabbits = new HashMap<>();
    /** Rabbit id -> where it was first found ("hub", "milestone", "bounty"...). */
    public Map<String, String> foundWhere = new HashMap<>();

    /** Meal id -> the instance (day index) of the last egg of that meal claimed. */
    public Map<String, Long> eggClaims = new HashMap<>();
    /** Meal id -> the claim before the last one (a check that runs late still sees it as claimed). */
    public Map<String, Long> eggClaimsPrev = new HashMap<>();
    /** Absolute in-game minute up to which missed eggs have been counted for the Bounty Hunter. */
    public long missedCheckedTo;
    public int hitmanSlots;
    public int hitmanEggs;
    /** Seconds accumulated toward the next Egg Finder (hunting attribute) egg. */
    public double eggFinderSeconds;

    /** Epoch ms the Hot Chocolate Mixin runs out (0 / past = not active). */
    public long hotMixinUntil;

    /** Epoch ms the Enchanted Clock's Time Warp is usable again (0 = ready). */
    public long timeWarpAt;

    /** Epoch ms production was last paid (offline catch-up starts here). */
    public long lastTick;

    public int eggsFound;
    public int duplicates;
    public int strays;
    public int goldenStrays;
    public int doradoCaught;
    public long clicks;
    /** Epoch ms a Golden Click stray's bonus ends. */
    public long goldenClickUntil;

    /** Talisman tiers bought (item id -> true). */
    public Map<String, Boolean> talismans = new HashMap<>();
    public boolean seekerClaimed;
    public boolean fishGiven;

    /** Hopsworth's daily offer: the in-game day it was rolled, the rabbit, and whether it was bought. */
    public long offerDay = -1;
    public String offerRabbit;
    public boolean offerBought;

    /** Prestiges whose SkyBlock XP was already paid (bitmask by level). */
    public int prestigeXpPaid;

    public int unique() { return rabbits.size(); }

    public boolean has(String rabbitId) { return rabbits.containsKey(rabbitId); }

    public int level(String employee) { return employees.getOrDefault(employee, 0); }
}
