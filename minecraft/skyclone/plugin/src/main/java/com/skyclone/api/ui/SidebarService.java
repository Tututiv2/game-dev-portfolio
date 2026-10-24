package com.skyclone.api.ui;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Function;

/**
 * The right-hand scoreboard and the tab list. Core draws the fixed frame:
 * <pre>
 *   &amp;e&amp;lSKYBLOCK
 *   &amp;710/02/26 &amp;8m1A
 *
 *    Early Spring 5th
 *    &amp;7☀ 2:30pm
 *    &amp;7⏣ &amp;bVillage
 *
 *   Purse: &amp;61,234
 *   Bits: &amp;b0
 *
 *   [module sections, by priority]
 *
 *   &amp;ewww.skyclone.example
 * </pre>
 * A module adds a SECTION: a function returning lines for a player, or an empty list to hide.
 * Max 15 lines total; low-priority sections are dropped first. Refreshed every 20 ticks.
 *
 * <p>Priorities: 200 slayer quest, 300 island-specific (mithril powder, pests, dungeon timer),
 * 400 events (Spooky festival countdown), 900 objective ("Objective / Talk to the Banker") as the
 * filler: the head takes 9 of 15 lines, so a live section must outrank the always-present objective
 * or it is never shown (playtest "loops", 2026-10-03).
 */
public interface SidebarService {

    void section(String id, int priority, Function<Player, List<String>> lines);

    void removeSection(String id);

    /** Tab-list widget (the genre's tab list shows skills, stats, island info). */
    void tabWidget(String id, int priority, Function<Player, List<String>> lines);

    void refresh(Player p);

    /**
     * The player's own Scoreboard (core gives every player one, created on ProfileReady), or null
     * before that. Register Teams (glow colour, nametag visibility, collision) HERE: teams on the
     * main scoreboard do not apply to players. Never call {@code p.setScoreboard}. (Gate, 2026-10-02.)
     */
    org.bukkit.scoreboard.Scoreboard scoreboard(Player p);

    /**
     * Hide (or show again) one module section for one player, e.g. a Settings toggle "hide the slayer
     * section". Kept in memory for the session only: the module that owns the setting re-applies it on
     * ProfileReady. (Added by integration round 2, menus Request 3 / slayer Request 9.)
     */
    default void setHidden(Player p, String sectionId, boolean hidden) { }

    /** Whether {@link #setHidden} hid this section for this player. */
    default boolean hidden(Player p, String sectionId) { return false; }
}
