package com.thestill.fog;

import org.bukkit.entity.Player;

/**
 * How a player is told they are standing in different weather.
 *
 * <p>There are exactly two answers and they are not equally good, so the choice is an
 * interface rather than a flag:
 *
 * <ul>
 *   <li>{@link PacketBiomeChannel} sends one player a biome-only chunk update. Per-player,
 *       and it never touches the world.</li>
 *   <li>{@link WorldBiomeChannel} repaints the world and re-sends the chunks. It works
 *       everywhere with no server internals at all, and it is <b>not per-player</b> —
 *       anyone standing beside you sees your lamp.</li>
 * </ul>
 *
 * <p>The fallback exists because the packet path reaches into {@code net.minecraft} and
 * that is a thing that can stop compiling or stop resolving on some future build. It is
 * not a second-class citizen — it is a working lamp with a stated cost, and
 * {@code FogService} says out loud in the log which one it is using. What must never
 * happen is the two silently swapping without anybody knowing which is on.
 */
public interface BiomeChannel {

    /** One line for the startup log and {@code /still lamp}. Says the cost, not just the name. */
    String describe();

    /**
     * Puts these chunks into the lamplight biome, for this player.
     *
     * @return false if it could not — the caller must then treat the player as unlit
     *         rather than assume, because assuming is how a lamp gets left on
     */
    boolean light(Player player, long[] chunks);

    /**
     * Puts these chunks back to the real weather.
     *
     * <p>Must be safe to call for a player who was never lit, was already doused, or is
     * halfway out of the door — it is called from quit, death, world change, plugin
     * disable and every chunk the player walks out of.
     */
    void douse(Player player, long[] chunks);
}
