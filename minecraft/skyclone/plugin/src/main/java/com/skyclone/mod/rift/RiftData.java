package com.skyclone.mod.rift;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-member Rift progress (profile key "rift"). Gson-friendly: maps, sets, primitives only.
 * Add fields, never retype them (CORE_GUIDE 0.3).
 */
public final class RiftData {
    /** Spendable Motes and every Mote ever earned. */
    public long motes;
    public long lifetimeMotes;
    /** Stored Planar Infusions (free entries) and the wall-clock ms the next one started charging. */
    public int infusions = 3;
    public long infusionClock;
    /** Riddle Souls found ("soul.12"). */
    public Set<String> souls = new HashSet<>();
    /** Timecharms owned (catalog ids). */
    public Set<String> charms = new HashSet<>();
    /** Mote orb anchor -> epoch ms it was last picked up (they come back per player). */
    public Map<String, Long> motesTaken = new HashMap<>();
    /** Farmer Wilma: 0 = not met, 1 = looking for eggs, 2 = eggs found, 3 = done. */
    public int wilma;
    public Set<String> eggs = new HashSet<>();
    /** Paperwork: the next clerk you must see (1..4), 5 = take it to the Mayor, 6 = done. */
    public int paperwork;
    /** Living Heart: hits in the current chase and hearts caught. */
    public int heartHits;
    public int heartsCaught;
    /** Looking-Glass rooms done: "parkour", "maze", "dance". */
    public Set<String> mirror = new HashSet<>();
    /** Best West Village race time in ms (0 = never finished). */
    public long raceBest;
    /** Vermin cleared from the Infested House and whether the reward was paid. */
    public int vermin;
    /** Misc one-shot flags: "met_riddler", "met_velka", "vermin_reward", "race_reward", "first_visit". */
    public Set<String> flags = new HashSet<>();
    /** Statistics. */
    public int visits;
    public long secondsInside;
    public int deaths;
    public Map<String, Integer> kills = new HashMap<>();

    // ---- wave 3 ----
    /** Timecharms secured in the Rift Gallery (catalog ids): they float on its pedestals and power the necklaces. */
    public Set<String> gallery = new HashSet<>();
    /** Split or Steal: epoch ms of your last round, and stats. */
    public long splitStealAt;
    public int splitStealRounds;
    public int splitStealSplits;
    public long splitStealMotes;
    /** Result line of the last round, shown on the host's menu ("You split, they stole: +0"). */
    public String splitStealLast;
    /** Odonata bottled. */
    public int odonataCaught;

    // ---- wave 5 ----
    /** Riddle Soul puzzles solved (RiftEnigma ids): a solved one's soul may be collected / was granted. */
    public Set<String> riddles = new HashSet<>();
    /** Riddle id -> epoch ms before which it may not be retried (Ubaldo's games, Old Vel'ka's quiz). */
    public Map<String, Long> riddleRetry = new HashMap<>();
    /** The lost cat (Weeping Maribel): 0 = not met, 1 = searching; pieces found (1..9); whether Pharaoh rides along. */
    public int catStage;
    public Set<Integer> catPieces = new HashSet<>();
    public boolean catActive = true;
    /** The Warden's eyes found (1..7): each is a fast-travel point inside the Rift. */
    public Set<Integer> eyes = new HashSet<>();
    /** Souls the Enigma Cloak holds (claimed from Riddler Nyx in steps of 4, max 42). */
    public int cloakSouls;
}
