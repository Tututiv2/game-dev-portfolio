package com.skyclone.mod.collections;

import java.util.HashMap;
import java.util.Map;

/** Persistent state. Gson-friendly: maps and primitives only. Add fields; never retype one. */
public final class CollectionsData {

    private CollectionsData() {}

    /** Profile-wide ("shared") data: one object for every co-op member. Key {@value #SHARED_KEY}. */
    public static final class Shared {
        /** collection id -> combined total of every member (what decides the tier). */
        public Map<String, Long> totals = new HashMap<>();
        /** collection id -> member uuid -> that member's contribution. */
        public Map<String, Map<String, Long>> contrib = new HashMap<>();
        /** member uuid -> last known name (for the co-op contribution lines while they are offline). */
        public Map<String, String> names = new HashMap<>();
        /** collection id -> highest tier already announced (so a re-sync never re-announces). */
        public Map<String, Integer> announced = new HashMap<>();
    }

    /** Per-member data. Key {@value #MEMBER_KEY}. */
    public static final class Member {
        /** One-time per-member rewards already paid ("skillxp:WHEAT:9" -> epoch millis). */
        public Map<String, Long> claimed = new HashMap<>();
    }

    public static final String SHARED_KEY = "collections";
    public static final String MEMBER_KEY = "collections_member";
}
