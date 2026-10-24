package com.skyclone.core.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's ITEM STASH (profile member data, key {@link ItemStash#KEY}): everything that was earned
 * while the inventory was full. Pure on purpose (no Bukkit) so the merge/cap rules are unit-tested.
 *
 * <p>An entry holds one item as Base64 {@code ItemStack#serializeAsBytes} of a ONE-count copy plus a
 * count, so 2,000 overflowing cobblestone are one row, while unique items (each with its own uuid)
 * never merge because their bytes differ.
 */
public final class StashData {

    public List<Entry> items = new ArrayList<>();

    public StashData() {}

    public static final class Entry {
        /** Base64 bytes of the item with amount 1. */
        public String item;
        /** Sky id (or vanilla material) for listings that do not want to decode. */
        public String id;
        public int amount;
        /** When it was first stashed (epoch ms). */
        public long at;
        /**
         * A reward / trade / unique item (named in chat when stashed). Picked up before bulk drops: a
         * Harvest Fair Ticket stashed during a farming session must not wait behind 4,000 wheat.
         */
        public boolean priority;
        /** Rarity ordinal at stash time (higher = rarer); breaks ties in {@link #pickupOrder()}. */
        public int rank;

        public Entry() {}

        Entry(String item, String id, int amount, long at) {
            this.item = item;
            this.id = id;
            this.amount = amount;
            this.at = at;
        }
    }

    /**
     * Store {@code amount} of {@code item}. Mergeable items join an existing identical entry; otherwise a
     * new entry is opened unless the stash already holds {@code maxEntries} (0 or less = no limit).
     *
     * @return true when stored, false when the stash is full (nothing changed)
     */
    public boolean add(String item, String id, int amount, boolean mergeable, int maxEntries, long now) {
        return add(item, id, amount, mergeable, maxEntries, now, false, 0);
    }

    /**
     * {@link #add(String, String, int, boolean, int, long)} with the pickup-order hints: {@code priority}
     * (reward / unique) and {@code rank} (rarity ordinal). Merging into a row upgrades it, never downgrades.
     */
    public boolean add(String item, String id, int amount, boolean mergeable, int maxEntries, long now,
                       boolean priority, int rank) {
        if (item == null || item.isEmpty() || amount <= 0) return false;
        if (items == null) items = new ArrayList<>();
        if (mergeable) {
            for (Entry e : items) {
                if (e != null && item.equals(e.item) && e.amount > 0 && e.amount <= Integer.MAX_VALUE - amount) {
                    e.amount += amount;
                    e.priority |= priority;
                    e.rank = Math.max(e.rank, rank);
                    return true;
                }
            }
        }
        if (maxEntries > 0 && liveEntries() >= maxEntries) return false;
        Entry fresh = new Entry(item, id, amount, now);
        fresh.priority = priority;
        fresh.rank = rank;
        items.add(fresh);
        return true;
    }

    /**
     * The order {@code /pickupstash} hands items back in: rewards and unique items first, then rarer
     * before commoner, then SMALLER piles before bigger ones, then oldest first. Oldest-first alone
     * (playtest wave4-loops) put a farming session's 4,000 wheat and seeds in front of the Harvest Fair
     * Tickets and Fossil Essence stashed in the middle of it, so a full inventory refilled with wheat
     * and the reward stayed out of reach for six pickups. A small pile is almost always the thing the
     * player is looking for; a big one is the grind that overflowed.
     */
    public List<Entry> pickupOrder() {
        List<Entry> out = new ArrayList<>();
        if (items == null) return out;
        for (Entry e : items) if (e != null && e.amount > 0 && e.item != null && !e.item.isEmpty()) out.add(e);
        out.sort(java.util.Comparator.<Entry>comparingInt(e -> e.priority ? 0 : 1)
                .thenComparingInt(e -> -e.rank)
                .thenComparingInt(e -> e.amount)
                .thenComparingLong(e -> e.at));
        return out;
    }

    /** Entries with something in them (null / empty rows are ignored and pruned by {@link #prune}). */
    public int liveEntries() {
        if (items == null) return 0;
        int n = 0;
        for (Entry e : items) if (e != null && e.amount > 0 && e.item != null) n++;
        return n;
    }

    /** Total item count across all entries. */
    public long total() {
        if (items == null) return 0;
        long n = 0;
        for (Entry e : items) if (e != null && e.amount > 0) n += e.amount;
        return n;
    }

    /** Drop emptied / broken rows. */
    public void prune() {
        if (items == null) { items = new ArrayList<>(); return; }
        items.removeIf(e -> e == null || e.amount <= 0 || e.item == null || e.item.isEmpty());
    }
}
