package com.skyclone.mod.crimson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-player Crimson Isle state ({@code Sky.profiles().member(p, "crimson", CrimsonData.class)}).
 * Gson-friendly: add fields, never retype them.
 */
public final class CrimsonData {

    /** "mage", "barbarian" or null (not pledged). */
    public String faction;
    /** Epoch millis of the last pledge/switch. */
    public long factionSince;
    /** faction -> reputation. Only the pledged faction gains; the other keeps what it had. */
    public Map<String, Integer> rep = new HashMap<>();
    /** The day ("2026-10-02", UTC) today's quest board was rolled for. */
    public String questDay;
    public List<QuestState> quests = new ArrayList<>();
    public boolean dailyBonusClaimed;
    public int questsDone;
    /** trial id -> best points. */
    public Map<String, Integer> dojoBest = new HashMap<>();
    /** Belt item ids already handed out. */
    public Set<String> beltsGiven = new HashSet<>();
    /** mob id -> kills (mini-bosses and the rare spawns). */
    public Map<String, Integer> bossKills = new HashMap<>();
    /** Ranks already announced (faction:rankIndex), so a rank-up message never repeats. */
    public Set<String> ranksSeen = new HashSet<>();
    public boolean visited;
    /** NPC ids whose full greeting this player has heard. */
    public Set<String> talked = new HashSet<>();
    /** The day ("2026-10-04", quest reset hour) the Heavy Pearl state below belongs to. */
    public String pearlDay;
    /** Pearl numbers (0 = the day's first) already collected today. */
    public Set<Integer> pearlsTaken = new HashSet<>();
    /** Matriarch's Perfume used today, and the extra pearls it released. */
    public int perfumeUses;
    public int perfumePearls;
    /** Heavy Pearls collected, ever. */
    public int pearlsTotal;
    /** Leviathan clears per tier ("basic".."infernal") when last polled (absent = not looked yet: baseline only). */
    public Map<String, Integer> kuudraSeen = new HashMap<>();
    /** Tier -> the day ("2026-10-06", quest reset hour) its once-a-day Leviathan reputation was paid. */
    public Map<String, String> kuudraRepDay = new HashMap<>();
    /** Rollim (Watchman Orrin): daily reputation claims made, and when the last one was (epoch millis). */
    public int rollimClaims;
    public long rollimLast;
    /** Fast-travel warps unlocked by reading a travel scroll (e.g. "crimson_skull", the Dusty Travel Scroll). */
    public Set<String> scrolls = new HashSet<>();

    public static final class QuestState {
        public String id;
        public int progress;
        public boolean done;
        /** KUUDRA quests: the player's Leviathan clears when the quest was rolled (-1 = not read yet). */
        public int base = -1;

        public QuestState() {}

        public QuestState(String id) { this.id = id; }
    }

    public int rep(String f) { return f == null ? 0 : rep.getOrDefault(f, 0); }
}
