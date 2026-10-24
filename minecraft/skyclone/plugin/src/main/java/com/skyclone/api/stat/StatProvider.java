package com.skyclone.api.stat;

import org.bukkit.entity.Player;

/** Adds one source's stats into the accumulating map. Must be fast: runs every 10 ticks per player. */
@FunctionalInterface
public interface StatProvider {
    void contribute(Player player, StatMap into);
}
