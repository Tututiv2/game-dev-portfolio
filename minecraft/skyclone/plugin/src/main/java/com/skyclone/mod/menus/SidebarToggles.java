package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings -&gt; Display "Sidebar: ..." toggles (menus Request 3, answered by core's
 * {@code SidebarService.setHidden}). Each setting hides a GROUP of module sidebar sections for one player.
 * Core keeps the hidden set for the session only, so it is re-applied on every ProfileReady and on every change.
 *
 * <p>Run-critical sections (the dungeon run timer, the Leviathan hunt) are deliberately in no group: hiding them
 * would hide the state of a run you are in. A section id nobody registered is harmless (core ignores it).
 * The map is pure data and unit-tested.
 */
final class SidebarToggles {

    private SidebarToggles() {}

    /** setting key -&gt; sidebar section ids (as the modules register them, 2026-10-06). */
    static final Map<String, List<String>> GROUPS;

    static {
        Map<String, List<String>> g = new LinkedHashMap<>();
        g.put(SettingDefs.SIDEBAR_OBJECTIVE, List.of("progression.objective", "progression.objective.district", "hub.guide"));
        g.put(SettingDefs.SIDEBAR_SLAYER, List.of("slayer"));
        g.put(SettingDefs.SIDEBAR_EVENTS, List.of("mayors", "farming_contest"));
        g.put(SettingDefs.SIDEBAR_AREA, List.of("island", "garden", "mining", "hollows", "crimson", "combatzones", "fishing",
                "foraging", "galatea", "hunting", "rift", "farming_trapper"));
        GROUPS = java.util.Collections.unmodifiableMap(g);
    }

    /** Sections that must never be hidden by a setting (a run's own state). */
    static final List<String> NEVER = List.of("dungeons", "kuudra");

    static boolean isToggle(String key) { return GROUPS.containsKey(key); }

    /** Apply every sidebar toggle of a ready player (ProfileReady, /reload). */
    static void applyAll(Player p) {
        for (String key : GROUPS.keySet()) apply(p, key, false);
        try { Sky.sidebar().refresh(p); } catch (Throwable ignored) { /* sidebar not up */ }
    }

    /** Apply one toggle; {@code refresh} redraws the sidebar now instead of on its next 1 s tick. */
    static void apply(Player p, String key, boolean refresh) {
        List<String> ids = GROUPS.get(key);
        if (ids == null || p == null) return;
        boolean hide = !SettingsStore.on(p, key);
        try {
            for (String id : ids) Sky.sidebar().setHidden(p, id, hide);
            if (refresh) Sky.sidebar().refresh(p);
        } catch (Throwable t) {
            Sky.log().fine("[menus] sidebar toggle " + key + ": " + t);
        }
    }
}
