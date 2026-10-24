package com.skyclone.mod.combatzones;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-member combat-zone progress (Gson, add fields only, never retype). */
public final class CzData {
    /** Fast-travel destinations unlocked by scrolls: nest, sanctuary, drag, void. */
    public Set<String> scrolls = new HashSet<>();
    /** Dragon kills participated in, by variant id. */
    public Map<String, Integer> dragonKills = new HashMap<>();
    /** Best placement ever reached in a dragon fight (0 = never). */
    public int bestPlacement;
    /** Summoning eyes ever placed. */
    public int eyesPlaced;
    /** Highest single-fight damage on a dragon. */
    public double bestDragonDamage;
    /** Silkmother kills by level (300 / 500). */
    public Map<String, Integer> arachneKills = new HashMap<>();
    public int broodmotherKills;
    public int colossusKills;
    /** End stone mined in Protector's Field (lifetime). */
    public long endStoneMined;
    /** Remnants of the eye that saved this player. */
    public int remnantSaves;
    /** Spider's Den relics found, by block position "x,y,z" (layout data "relics"). */
    public Set<String> relics = new HashSet<>();
    /** Professor Dunmore paid the all-relics bonus. */
    public boolean relicsRewarded;

    // ---- wave 5 (2026-10-06)
    /** Keeper Ixo's Dragon Essence perks: NEU perk id -> level bought. */
    public Map<String, Integer> dragonPerks = new HashMap<>();
    /** Dragon Essence earned at the Draconic Altar (lifetime). */
    public long altarEssence;
    /** Items sacrificed at the Draconic Altar (lifetime). */
    public int altarSacrifices;
    /** Bonus loot rolls the altar paid. */
    public int altarBonuses;
    /** Wanderer Tavi's quest chain: index of the current quest (== quest count when finished) and its kills. */
    public int taviQuest;
    public int taviProgress;
    /** Mob types slain at least once (first kill of each pays SkyBlock XP). */
    public Set<String> mobTypes = new HashSet<>();
}
