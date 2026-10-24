package com.skyclone.mod.menus;

import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Menu;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Every screen this module draws, by name, as a Menu that has NOT been opened yet. The admin
 * {@code /sb menus open|dump} commands and the self-tests use it, so each screen can be built and
 * inspected for any player over RCON.
 */
final class Screens {

    private Screens() {}

    static final List<String> NAMES = List.of("sbmenu", "stats", "pv", "statpage", "breakdown", "fasttravel", "warps",
            "warplist", "calendar", "events", "settings", "settingcat", "bags", "guide", "guidecat", "quests", "effects");

    static SkyBlockMenu skyMenu;

    /**
     * Build screen {@code name} for {@code p}. {@code arg} is the screen's parameter (target player,
     * stat, category, island, month offset); may be null. Throws IllegalArgumentException with a
     * readable message for bad input.
     */
    static Menu build(Player p, String name, String arg) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "sbmenu": return skyMenu.build(p);
            case "stats": return ProfileViewer.build(p, p);
            case "pv": {
                Player t = arg == null ? p : Bukkit.getPlayerExact(arg);
                if (t == null) throw new IllegalArgumentException("not online: " + arg);
                return ProfileViewer.build(p, t);
            }
            case "statpage": return ProfileViewer.statPage(p, p, statCategory(arg));
            case "breakdown": {
                Stat s = Stat.parse(arg == null ? "strength" : arg);
                if (s == null) throw new IllegalArgumentException("unknown stat: " + arg);
                return ProfileViewer.breakdown(p, p, s);
            }
            case "fasttravel": return FastTravelMenu.build(p);
            case "warps": return FastTravelMenu.subWarps(p, arg == null ? "hub" : arg.toLowerCase(Locale.ROOT));
            case "warplist": return FastTravelMenu.warpList(p, 0);
            case "calendar": {
                int off = 0;
                if (arg != null) {
                    try { off = Integer.parseInt(arg); } catch (NumberFormatException e) { throw new IllegalArgumentException("month offset must be a number"); }
                }
                return CalendarMenu.build(p, off);
            }
            case "events": return CalendarMenu.upcoming(p, 0);
            case "settings": return SettingsMenu.build(p);
            case "settingcat": return SettingsMenu.category(p, settingCategory(arg), 0);
            case "bags": return MiscMenus.bags(p);
            case "guide": return MiscMenus.guide(p);
            case "guidecat": return MiscMenus.guideCategory(p, featureCategory(arg));
            case "quests": return MiscMenus.quests(p);
            case "effects": return MiscMenus.effects(p);
            default: throw new IllegalArgumentException("unknown screen '" + name + "' (" + String.join(", ", NAMES) + ")");
        }
    }

    static StatCategories.Category statCategory(String arg) {
        if (arg == null) return StatCategories.Category.COMBAT;
        try { return StatCategories.Category.valueOf(arg.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("stat category: combat, gathering, wisdom, misc"); }
    }

    static SettingDefs.Category settingCategory(String arg) {
        if (arg == null) return SettingDefs.Category.PERSONAL;
        try { return SettingDefs.Category.valueOf(arg.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("setting category: personal, chat, notifications, combat, gathering, display, island"); }
    }

    static FeatureIndex.Category featureCategory(String arg) {
        if (arg == null) return FeatureIndex.Category.PROFILE;
        try { return FeatureIndex.Category.valueOf(arg.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("guide category: profile, skills, gathering, items, storage, economy, events, travel, help"); }
    }
}
