package com.skyclone.mod.extras;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-player (profile member key "extras") state. Gson-friendly; add fields, never retype them. */
public final class ExtrasData {
    // ---- harp ----
    public List<String> songsDone = new ArrayList<>();
    public List<String> songsPerfect = new ArrayList<>();
    public Map<String, Integer> songBest = new HashMap<>();
    public boolean harpReward;

    // ---- phone ----
    public List<String> contacts = new ArrayList<>();
    public int trios;
    public List<String> ringtones = new ArrayList<>();
    public String ringtone = "";
    public boolean dnd;
    public String speedDial = "";
    public int snakeBest;
    public int tttWins;
    public int tttGames;
    public List<String> missed = new ArrayList<>();
    public long lastIncoming;

    // ---- city projects / fire sales ----
    public List<String> cityDone = new ArrayList<>();
    public Map<String, Integer> fireBought = new HashMap<>();

    public ExtrasData() {}
}
