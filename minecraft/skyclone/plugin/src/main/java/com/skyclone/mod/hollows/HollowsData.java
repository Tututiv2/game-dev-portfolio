package com.skyclone.mod.hollows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Per-player state of the crystal caverns quests (member key "hollows"). Gson-friendly; add fields, never retype. */
public final class HollowsData {
    /** Keepers of Varnas who already got their tool this run: "lapis", "emerald", "diamond", "gold". */
    public List<String> keepersGiven = new ArrayList<>();
    /** The buried-treasure anchor the Metal Detector is tracking ("varnas.treasure.7"), or null. */
    public String detectorTarget;
    /** Until this time (epoch ms) the temple altar summons its guardian without a new key (a guardian that vanished). */
    public long templePaidUntil;
    /** Completed quest count per crystal ("AMETHYST" -> 3). */
    public Map<String, Integer> runs = new HashMap<>();
    /** Times Odala traded juice for a key. */
    public int odalaTrades;
    /** Treasures dug with the Metal Detector, all time. */
    public int treasuresDug;
    /** Has the guide's first-visit talk been played. */
    public boolean metGuide;
    /** Lobby number this player last stood in (wave 5: a CLOSING lobby still lets them back in). -1 = never. */
    public int lobbySeen = -1;
    /** Inner temple runs finished (guardian killed), all time. */
    public int templeRuns;
    /** Real buried Varnas chests opened, all time. */
    public int chestsOpened;
    /** Crystal Caverns Pass valid until (epoch ms). */
    public long passUntil;
    /** The Rimestone Rail Pass was punched: permanent Frozen Tunnels access. */
    public boolean railPass;
}
