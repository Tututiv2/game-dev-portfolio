package com.skyclone.mod.progression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every persisted shape of the progression module. Plain Gson classes: public no-arg constructors,
 * field initialisers are the defaults. NEVER change a field's type (CORE_GUIDE trap 3): add fields.
 *
 * <p>Member keys (per player): {@link #KEY_LEVEL}, {@link #KEY_OBJECTIVES}, {@link #KEY_FAIRY},
 * {@link #KEY_BESTIARY}, {@link #KEY_CAKES}. Shared key (per profile, co-op): {@link #KEY_MUSEUM}.
 * Other modules may READ these with their own mirror classes (field names are a public contract,
 * listed in docs/handoff/progression.md).
 */
public final class PData {

    private PData() {}

    public static final String KEY_LEVEL = "progression.level";
    public static final String KEY_OBJECTIVES = "progression.objectives";
    public static final String KEY_FAIRY = "progression.fairy";
    public static final String KEY_BESTIARY = "progression.bestiary";
    public static final String KEY_CAKES = "progression.cakes";
    public static final String KEY_MUSEUM = "progression.museum";
    public static final String KEY_GUIDES = "progression.guides";
    public static final String KEY_PERKS = "progression.perks";

    /** SkyBlock XP: every one-time award by key, and the running total. */
    public static final class Level {
        /** award key -> SB XP it paid. A key present here never pays again. */
        public Map<String, Integer> awards = new LinkedHashMap<>();
        /** Sum of {@link #awards}. Kept so a read never has to add 1,000 numbers. */
        public int xp;
        /** Highest level the player has been told about (level-up boxes fire above this). */
        public int announcedLevel;
        /** Selected emblem id ("diamond"), or null. */
        public String emblem;
    }

    /** The guided objective chain. */
    public static final class Objectives {
        /** Credited objective ids (plus ids outside the chain that other modules completed, recorded only). */
        public List<String> done = new ArrayList<>();
        /** Ids the player chose to skip from the Quest Log (no reward). */
        public List<String> skipped = new ArrayList<>();
        /** Counters for "do X n times" objectives: id -> progress. */
        public Map<String, Integer> progress = new HashMap<>();
        /**
         * Ids the player did AHEAD of the chain (out of order): recorded so {@code completed()} and the requirement
         * {@code quest:<id>} answer true, but the box and the reward wait until the chain reaches the step, so chat
         * never announces a step the sidebar is not showing. Moved to {@link #done} when credited.
         */
        public List<String> early = new ArrayList<>();
        /** Set once the whole chain is through (the sidebar then points at the next locked island). */
        public boolean finished;
    }

    /** Island Guides (per-island first-entry mini-chains): guide id -> index of the current step. */
    public static final class Guides {
        /** guide id -> current step index; present = started; == steps.size() once finished. */
        public Map<String, Integer> step = new HashMap<>();
        /** guide id -> counter towards the current step ("Slay 10 ..."). */
        public Map<String, Integer> progress = new HashMap<>();
        /** Guides finished (rewarded). */
        public List<String> finished = new ArrayList<>();
        /** Guides the player chose to skip from /islandguides (no reward; can be resumed). */
        public List<String> skipped = new ArrayList<>();
    }

    /** SkyBlock Level reward claims (the Tome of Progress at Level 25). */
    public static final class Perks {
        /** Level rewards already handed over ("tome"). */
        public List<String> claimed = new ArrayList<>();
    }

    /** Fairy souls: which were collected and how many were exchanged at the fairy. */
    public static final class Fairy {
        /** "island:anchor" ids of collected souls. */
        public List<String> found = new ArrayList<>();
        /** Number of 5-soul exchanges done. */
        public int exchanged;
    }

    /** Bestiary kills per family key (a curated family id, or "mob:" + MobDef.familyId()). */
    public static final class Bestiary {
        public Map<String, Integer> kills = new HashMap<>();
        /** Highest milestone announced. */
        public int announcedMilestone;
    }

    /** Century cake buffs: cake item id -> expiry (epoch millis, real time, counts offline). */
    public static final class Cakes {
        public Map<String, Long> active = new HashMap<>();
        /** Celebration ids already claimed ("century:3", "newyear:412"). */
        public List<String> claimed = new ArrayList<>();
    }

    /** One museum display slot (a single item or a whole armour set). */
    public static final class Exhibit {
        /** Base64 of {@code ItemStack#serializeAsBytes()}, one per piece. Empty while borrowed. */
        public List<String> stacks = new ArrayList<>();
        public long donatedAt;
        public String donorName;
        public String donorUuid;
        /** True while a member has taken it back out; the XP stays earned. */
        public boolean borrowed;
    }

    /** The profile's museum (co-op shared). */
    public static final class Museum {
        /** museum entry id -> exhibit. */
        public Map<String, Exhibit> exhibits = new LinkedHashMap<>();
        /** Museum milestones whose one-off coin reward has been paid (per profile). */
        public List<Integer> milestonesPaid = new ArrayList<>();
    }
}
