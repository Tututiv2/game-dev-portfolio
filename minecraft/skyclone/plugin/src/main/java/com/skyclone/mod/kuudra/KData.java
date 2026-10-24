package com.skyclone.mod.kuudra;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player hunt record (member key "kuudra"). Gson-friendly: maps, lists, primitives. Add fields, never
 * retype them (CORE_GUIDE 0.3).
 */
public final class KData {

    /** tier id -> completed hunts. */
    public Map<String, Integer> completions = new HashMap<>();
    /** tier id -> fastest completion in ms. */
    public Map<String, Long> bestMillis = new HashMap<>();
    /** Reward chests not yet fully claimed, oldest first. */
    public List<Chest> chests = new ArrayList<>();
    public long tokensEarned;
    public int huntsStarted;
    public int deaths;
    public long brood;
    /** Set while the player is a lair ghost (spectator): the game mode to restore if the server stops mid-hunt. */
    public String ghostRestore;
    /** Heard Dockhand Nell's briefing (played the first time the dock opens the hunt board). */
    public boolean introduced;
    /** The intro questline "The Leviathan's Wake" ({@link KIntro}): the step the player is on (0 = not met Nell). */
    public int introStep;
    /** Crimson Isle creatures slain for the intro's "cull" step. */
    public int introKills;
    /** Nell's Basic key was handed over (once per profile). */
    public boolean introKeyGiven;
    /** Cutscenes this player has watched in full (ids from {@link KScript}); the next showing is the short cut. */
    public List<String> scenesSeen = new ArrayList<>();

    /** One hunt's rewards for this player. Items are ids + counts; unique items are created at claim time. */
    public static final class Chest {
        public String runId;
        public String tier;
        public long at;
        public Map<String, Integer> free = new LinkedHashMap<>();
        public Map<String, Integer> paid = new LinkedHashMap<>();
        public boolean freeClaimed;
        public boolean paidClaimed;
        public boolean rerolled;
        public long seed;
        /** Wheels of Embers spun on this chest's shard. */
        public int wheels;
    }

    public int completions(String tier) { return completions.getOrDefault(tier, 0); }

    public int totalCompletions() {
        int n = 0;
        for (int v : completions.values()) n += v;
        return n;
    }

    public Chest chest(String runId) {
        for (Chest c : chests) if (c.runId.equals(runId)) return c;
        return null;
    }

    public int unclaimed() {
        int n = 0;
        for (Chest c : chests) if (!c.freeClaimed || !c.paidClaimed) n++;
        return n;
    }
}
