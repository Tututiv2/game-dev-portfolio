package com.skyclone.mod.dungeons;

import java.util.HashMap;
import java.util.Map;

/** Per-player dungeon progress (profile member key "dungeons"). Gson-friendly; add fields, never retype them. */
public final class DData {
    public double cataXp;
    /** class key -> XP. */
    public Map<String, Double> classXp = new HashMap<>();
    /** Selected class key ("healer"...), null = never chosen (Berserk is used). */
    public String selectedClass;
    /** floor id -> completions. */
    public Map<String, Integer> completions = new HashMap<>();
    public Map<String, Integer> bestScore = new HashMap<>();
    /** floor id -> fastest S-or-better run in seconds. */
    public Map<String, Long> fastest = new HashMap<>();
    public int runsStarted, runsFailed, totalSecrets, totalDeaths, cryptsOpened, puzzlesSolved;
    /** Real day (epoch day) of the daily bonus counter, and completions that day. */
    public long dailyDay;
    public int dailyRuns;
    /** Hide the dungeon sidebar section (Settings). */
    public boolean hideSidebar;
}
