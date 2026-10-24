package com.thestill.rank;

import net.kyori.adventure.text.format.TextColor;

/**
 * One rung of the ladder.
 *
 * @param index    position, 0-based. The starting rank is 0 and costs nothing.
 * @param id       stable identifier used in permissions, commands and storage
 * @param display  what the player sees, in the nametag and the sidebar
 * @param cost     minor units to buy <em>this</em> rank from the one below it
 * @param colour   the rank's colour, used everywhere it is rendered
 */
public record Rank(int index, String id, String display, long cost, TextColor colour) {

    public Rank {
        if (index < 0) {
            throw new IllegalArgumentException("negative rank index");
        }
        if (cost < 0) {
            throw new IllegalArgumentException("negative rank cost for " + id);
        }
    }

    public boolean isStarting() {
        return index == 0;
    }
}
