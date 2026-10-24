package com.aisky.showcontrol;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the feed remembers within ONE episode: which "firsts" are taken, which coin milestones each
 * player crossed, which items each player has already been seen gaining, and the collection totals
 * seen through CollectionGain (the Collections service has no "list everything" call).
 * Cleared by /show reset and /show start. Main thread for writes; maps are concurrent so the
 * snapshot task and HTTP readers never see a torn structure.
 */
final class EpisodeMemory {

    /** first_of_kind key -> player name who took it. */
    private final Map<String, String> firsts = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> milestones = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seenItems = new ConcurrentHashMap<>();
    /** player (lower) -> collection id -> total. */
    private final Map<String, Map<String, Long>> collections = new ConcurrentHashMap<>();
    /** player (lower) -> placed minion count last observed (absent = not observed yet). */
    private final Map<String, Integer> minionCounts = new ConcurrentHashMap<>();

    void clear() {
        firsts.clear();
        milestones.clear();
        seenItems.clear();
        collections.clear();
        minionCounts.clear();
    }

    /** True exactly once per key per episode: the caller emits first_of_kind when it is. */
    boolean claimFirst(String key, String player) {
        return firsts.putIfAbsent(key, player) == null;
    }

    Map<String, String> firsts() { return new LinkedHashMap<>(firsts); }

    /**
     * Records every milestone {@code purse} has reached for this player and returns the HIGHEST newly
     * crossed one, or -1 when nothing new was crossed (a jump over three milestones is one event).
     */
    long crossMilestones(String player, double purse, Iterable<Long> thresholds) {
        Set<Long> got = milestones.computeIfAbsent(key(player), k -> ConcurrentHashMap.newKeySet());
        long best = -1;
        for (long t : thresholds) {
            if (purse >= t && got.add(t)) best = t;
        }
        return best;
    }

    /** True the first time this player is seen with this item id this episode. */
    boolean firstItem(String player, String itemId) {
        return seenItems.computeIfAbsent(key(player), k -> ConcurrentHashMap.newKeySet()).add(itemId);
    }

    void collection(String player, String id, long total) {
        collections.computeIfAbsent(key(player), k -> new ConcurrentHashMap<>()).put(id, total);
    }

    Map<String, Long> collections(String player) {
        Map<String, Long> m = collections.get(key(player));
        return m == null ? Map.of() : new LinkedHashMap<>(m);
    }

    /** Previous observed minion count (or null), storing the new one. */
    Integer swapMinionCount(String player, int now) {
        return minionCounts.put(key(player), now);
    }

    Set<String> takenFirstKeys() { return new HashSet<>(firsts.keySet()); }

    private static String key(String player) { return player.toLowerCase(java.util.Locale.ROOT); }
}
