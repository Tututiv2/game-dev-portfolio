package com.skyclone.mod.galatea;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Per-player Galatea state (profile member key {@code galatea}). Gson-friendly; add fields, never retype them. */
public final class GalateaData {
    /** Trophy frog item id -> how many ever caught (the professor's ledger). */
    public Map<String, Integer> frogs = new HashMap<>();
    /** Frog catches since the last Gold / Diamond (pity). */
    public int sinceGold, sinceDiamond;
    /** Highest collector title announced (0-4). */
    public int title;
    /** Active Frogcoin blessing id and when it ends (epoch millis). */
    public String blessing;
    public long blessingUntil;
    /** Frogcoins thrown since the Lotus Eater last woke; when he is awake, until when. */
    public int coinsTossed;
    public long eaterAwakeUntil;
    public int coinsTotal;
    /** Travel scrolls read ("lotus_atoll", "torrhus_canyon", "torrhus_springs", "torrhus_safari"). */
    public Set<String> scrolls = new HashSet<>();
    /** Canyon Journal: task id -> progress, finished tasks and paid chapters. */
    public Map<String, Integer> journal = new HashMap<>();
    public Set<String> journalDone = new HashSet<>();
    public Set<String> chaptersPaid = new HashSet<>();
    /** NPCs that already gave their long greeting. */
    public Set<String> talked = new HashSet<>();
    /** Last launch from Hobb's pad (anti-spam). */
    public long lastLaunch;
}
