package com.skyclone.api.player;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Custom health and mana. A player's real health pool is the HEALTH stat (100 at start, tens of
 * thousands late game); vanilla hearts are just a scaled gauge of it. Max mana = 100 + INTELLIGENCE (Intelligence base is 0).
 *
 * <p>Regen: health every 2s by (1.5 + max/100) * HEALTH_REGEN/100 out of combat-ish (genre rule),
 * mana 2% of max per second (scaled by MANA_REGEN). Core owns the action bar:
 * <pre>  &amp;c1,234/1,234❤     &amp;a250❈ Defense     &amp;b400/400✎ Mana</pre>
 * The middle segment is replaced for a few seconds by {@link #flash} (skill XP gains, ability costs).
 */
public interface VitalsService {

    double health(Player p);
    double maxHealth(Player p);
    void setHealth(Player p, double hp);
    void heal(Player p, double amount);

    /** Extra yellow health that is consumed first. */
    double absorption(Player p);
    void addAbsorption(Player p, double amount);

    double mana(Player p);
    double maxMana(Player p);
    /** Deduct mana if available; false (and nothing deducted) when not enough. */
    boolean useMana(Player p, double amount);
    void addMana(Player p, double amount);

    /** Replace the action-bar middle segment for {@code ticks}. */
    void flash(Player p, Component text, int ticks);

    /** Same, with '&amp;' codes. */
    void flash(Player p, String legacy, int ticks);

    /**
     * Append a segment after the mana part of the action bar (drill fuel, Rift time, dungeon secrets),
     * ordered by priority, refreshed every 5 ticks. Return null/blank to hide it for that player.
     * Re-registering an id replaces it; a null function removes it. (Added by the core gate.)
     */
    void actionBarSegment(String id, int priority, java.util.function.Function<Player, String> fn);

    /**
     * Raw damage to the player's custom pool: absorption first, then health, then {@link #kill} at 0
     * with {@code cause}. No defense - use {@code Sky.combat().damagePlayer} for hits that defense
     * should reduce. (Added by the core gate, 2026-10-02.)
     */
    void damage(Player p, double amount, String cause);

    /** Kill the player the genre way: lose half purse (outside safe zones), respawn at island spawn. */
    void kill(Player p, String cause);
}
