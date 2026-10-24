package com.skyclone.mod.slayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player slayer state, member key {@code "slayer"}. Gson-friendly; fields are only ever ADDED (a retyped field
 * poisons the key, CORE_GUIDE 0.3).
 */
public class SlayerData {

    /** type id -> total slayer XP. */
    public Map<String, Double> xp = new HashMap<>();
    /** "type:tier" -> boss kills. */
    public Map<String, Integer> kills = new HashMap<>();
    /** type id -> highest level whose rewards were announced (so a level-up box shows once). */
    public Map<String, Integer> announced = new HashMap<>();

    // ---- active quest ----------------------------------------------------------------------
    /** null = no quest. */
    public String questType;
    public int questTier;
    public double questProgress;
    /** COLLECTING, FIGHTING, READY (boss summonable after an interruption). */
    public String questState;
    public long questStarted;
    public double questPaid;
    /** Currency of {@link #questPaid}: "coins" (null on old saves) or "motes" (Bloodfiend via the rift's Motes). */
    public String questCurrency;

    // ---- RNG meter -------------------------------------------------------------------------
    /** type id -> selected item id. */
    public Map<String, String> rngSelected = new HashMap<>();
    /** type id -> meter XP. */
    public Map<String, Double> rngXp = new HashMap<>();

    // ---- options ---------------------------------------------------------------------------
    public List<String> autoSlayer = new ArrayList<>();
    public boolean hotlineClaimed;
    /** The slayer master's three-line introduction was heard once; later clicks open the board (wave-5 regression). */
    public boolean introHeard;
    /** One-shot chat line for the next login ("your quest failed because you logged out"); null = none. */
    public String loginNotice;

    // ---- timed buffs -----------------------------------------------------------------------
    /** mixin item id -> expiry epoch millis. */
    public Map<String, Long> mixins = new HashMap<>();

    // ---- statistics ------------------------------------------------------------------------
    public long questsStarted;
    public long questsCompleted;
    public long questsFailed;
    public double coinsSpent;
    /** Motes spent on Bloodfiend fees (wave 5). */
    public double motesSpent;
    /** Bosses / minibosses The Primordial cocooned and that were slain again (wave 5). */
    public long cocoonKills;
    /** miniboss mob id -> kills. */
    public Map<String, Integer> miniKills = new HashMap<>();
    /** item id -> number dropped from slayer bosses (for the collection log). */
    public Map<String, Long> dropLog = new HashMap<>();
    /** type id -> fastest kill in seconds, per tier "type:tier". */
    public Map<String, Long> bestTime = new HashMap<>();

    public double xp(String type) { return xp.getOrDefault(type, 0.0); }
    public int kills(String type, int tier) { return kills.getOrDefault(type + ":" + tier, 0); }
    public int totalKills(String type) {
        int n = 0;
        for (Map.Entry<String, Integer> e : kills.entrySet()) if (e.getKey().startsWith(type + ":")) n += e.getValue();
        return n;
    }
    public int highestTierKilled(String type) {
        int best = 0;
        for (Map.Entry<String, Integer> e : kills.entrySet()) {
            if (!e.getKey().startsWith(type + ":") || e.getValue() <= 0) continue;
            try { best = Math.max(best, Integer.parseInt(e.getKey().substring(type.length() + 1))); } catch (NumberFormatException ignored) { }
        }
        return best;
    }
    public boolean hasQuest() { return questType != null; }
}
