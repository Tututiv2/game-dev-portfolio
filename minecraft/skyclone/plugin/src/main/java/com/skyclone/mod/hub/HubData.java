package com.skyclone.mod.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-player hub state (member key "hub"). Gson-friendly: add fields, never retype them. */
public final class HubData {
    /** Hub zone ids this player has walked into (unlocks the district's fast travel). */
    public List<String> discovered = new ArrayList<>();
    /** NPC id -> times talked to (first talk plays the long introduction). */
    public Map<String, Integer> talks = new HashMap<>();
    /** Real-time millis of the first hub visit (0 = never). */
    public long firstVisit;
    /** District guide progress: "guideId#stepIndex" -> count so far. */
    public Map<String, Integer> guideSteps = new HashMap<>();
    /** District guides finished (their reward has been paid). */
    public List<String> guidesDone = new ArrayList<>();
    /** The hub district the player was last in (the sidebar shows that district's guide). */
    public String lastDistrict;
    /** Travel scrolls read (district ids), so the menu can say how a destination was unlocked. */
    public List<String> scrolls = new ArrayList<>();
    /** Launch pads ridden in total (flavour + admin info). */
    public int padsRidden;
    /** Target Practice: best score in one round, medals earned (0-4, each paid once), rounds played. */
    public int practiceBest, practiceTier, practiceRuns;
    /** Townsfolk errands (wave 5): errand id -> 1 running, 2 handed in (reward paid). */
    public Map<String, Integer> errands = new HashMap<>();
    /** Errand event-step progress: "errandId#stepIndex" -> count so far. */
    public Map<String, Integer> errandSteps = new HashMap<>();
    /** The errand most recently taken on (the sidebar follows it). */
    public String errandFocus;
}
