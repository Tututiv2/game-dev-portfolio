package com.shadowprison.stat;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * One aggregation point. Each source (held item and armor now; skills, collections, pets later)
 * adds into a fresh {@link StatMap}; gameplay reads the total. Additive by construction — a new
 * source can never silently multiply every other one (SP1's sell boost was a product of seven
 * independent multipliers that nobody could predict).
 */
public final class Stats {

    private final List<BiConsumer<Player, StatMap>> sources = new ArrayList<>();

    public void register(BiConsumer<Player, StatMap> source) {
        sources.add(source);
    }

    public StatMap of(Player player) {
        StatMap total = new StatMap();
        for (BiConsumer<Player, StatMap> source : sources) {
            source.accept(player, total);
        }
        return total;
    }
}
