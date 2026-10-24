package com.skyclone.mod.crafting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player crafting state, member key "crafting". Gson-friendly; fields are only ever ADDED
 * (changing a type poisons the key, CORE_GUIDE §0.3).
 */
public final class CraftData {
    /** Most recent first: result ids. */
    public List<String> recent = new ArrayList<>();
    /** Pinned result ids (Recipe Book favourites). */
    public List<String> favorites = new ArrayList<>();
    /** Total items crafted, and per result id. */
    public long crafted;
    public Map<String, Long> craftedBy = new HashMap<>();
    /** Trades: the real date (yyyy-MM-dd UTC) the counters below belong to, and items traded per trade id. */
    public String tradeDay = "";
    public Map<String, Integer> tradedToday = new HashMap<>();
    public long tradedTotal;
    /** Carpentry XP earned from crafting (display only). */
    public double carpentryXp;
    /** Book view preferences. */
    public boolean bookHideLocked;
    public String vanillaFilter = "all";
    /** Furniture placed (display only; placements themselves live in the world). */
    public int furniturePlaced;
    /**
     * Recipe Book progress (non-vanilla recipes), refreshed on join, collection tier-ups, skill level-ups
     * and whenever the book opens. Other modules (the SkyBlock Menu's "Recipe Book Unlocked: X%") may READ
     * these through their own Gson class with the same field names.
     */
    public int bookUnlocked;
    public int bookTotal;
    public double bookUnlockedPct;
}
