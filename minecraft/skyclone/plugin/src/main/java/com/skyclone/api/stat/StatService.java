package com.skyclone.api.stat;

import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Computes every online player's stats from registered providers. Recomputed every 10 ticks
 * and on {@link #invalidate}; reads are cheap cached lookups.
 *
 * <p>Core already provides: base values, worn armour + held item (incl. reforges, enchant stat
 * bonuses, gemstones, hot potato books — via ItemStatContributors), and equipment slots.
 * Skills, pets, accessories, potions, set bonuses, mayors etc. are each a provider registered
 * by their module.
 *
 * <p>Per-player override (integration 4): a provider calls {@link StatMap#set} for the players it
 * applies to and that stat's final value is replaced (base, flat, percent and multipliers ignored;
 * the provider registered later wins a tie). {@link #breakdown} shows it as {@code "<provider> (=)"}.
 */
public interface StatService {

    /** id must be unique, e.g. "skills", "pets", "accessories". Re-registering replaces. */
    void addProvider(String id, StatProvider provider);

    void removeProvider(String id);

    /** Cached final value (base included). */
    double get(Player p, Stat stat);

    /** Snapshot of the full map for display (the profile menu). */
    StatMap snapshot(Player p);

    /** Per-provider breakdown of one stat, for the "where does my strength come from" tooltip. */
    Map<String, Double> breakdown(Player p, Stat stat);

    /** Force a recompute on the next tick (equipment change, pet swap, potion...). */
    void invalidate(Player p);
}
