package com.skyclone.core.ui;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every dial the UI core reads. Created once at boot; values are read at USE time. */
final class UiDials {

    private UiDials() {}

    static Dials.Dial<String> sidebarTitle;
    static Dials.Dial<String> shard;
    static Dials.Dial<String> footer;
    static Dials.Dial<Integer> sidebarMaxLines;
    static Dials.Dial<Integer> sidebarRefreshTicks;
    static Dials.Dial<Boolean> sidebarEnabled;
    static Dials.Dial<Boolean> sidebarOverflowTab;

    static Dials.Dial<String> serverName;
    static Dials.Dial<String> tabHeader;
    static Dials.Dial<String> tabFooter;
    static Dials.Dial<Integer> tabRefreshTicks;
    static Dials.Dial<Integer> tabMaxLines;
    static Dials.Dial<Boolean> tabNames;

    static Dials.Dial<Integer> menuDebounceMs;
    static Dials.Dial<Integer> inputTimeoutSeconds;
    static Dials.Dial<Boolean> inputSign;

    static Dials.Dial<Integer> npcDebounceMs;
    static Dials.Dial<Double> npcLookRange;
    static Dials.Dial<Double> npcHoloHeight;

    static Dials.Dial<Boolean> chatFormat;
    static Dials.Dial<Boolean> star;
    static Dials.Dial<Boolean> vanillaRecipes;
    static Dials.Dial<Boolean> vanillaAdvancements;

    static void init() {
        Dials d = Sky.dials();
        sidebarTitle = d.text("ui.sidebar-title", "&e&lSKYBLOCK", "Sidebar (scoreboard) title, '&' codes");
        shard = d.text("ui.shard", "1A", "Server id shown on the sidebar date line after 'm' (e.g. m1A)");
        // No invented web address: the footer names the server, so a rename (ui.server-name) carries
        // through on its own. Set a real address here once the public server has one.
        footer = d.text("server.footer", "{server}", "Sidebar footer line (shown in yellow); {server} = ui.server-name; empty hides it");
        sidebarMaxLines = d.integer("ui.sidebar-max-lines", 15, "Max sidebar lines (the client shows at most 15)");
        sidebarRefreshTicks = d.integer("ui.sidebar-refresh-ticks", 20, "Ticks between sidebar redraws");
        sidebarEnabled = d.flag("ui.sidebar", true, "Show the SkyBlock sidebar");
        sidebarOverflowTab = d.flag("ui.sidebar-overflow-tab", true,
                "Sidebar sections with no room on the 15-line sidebar (e.g. the Objective during a Harvest Fair) are shown at the top of the tab list footer instead of vanishing");

        serverName = d.text("ui.server-name", "SKYCLONE", "Server display name used in the tab header and sidebar footer");
        tabHeader = d.text("ui.tab-header", "&bYou are playing on &e&l{server}", "Tab list header; {server} = ui.server-name");
        tabFooter = d.text("ui.tab-footer", "&7Type &e/sbmenu &7to open the SkyBlock Menu", "Last line of the tab list footer");
        tabRefreshTicks = d.integer("ui.tab-refresh-ticks", 40, "Ticks between tab list header/footer/name updates");
        tabMaxLines = d.integer("ui.tab-max-lines", 40, "Max lines of tab widgets in the tab footer");
        tabNames = d.flag("ui.tab-names", true, "Show '[level] name' in the tab list");

        menuDebounceMs = d.integer("ui.menu-click-debounce-ms", 100, "Minimum ms between two handled menu clicks per player (anti-macro)");
        inputTimeoutSeconds = d.integer("ui.input-timeout-seconds", 30, "Seconds a text/number prompt waits before giving up");
        inputSign = d.flag("ui.input-sign", true, "Text prompts open a sign editor (chat always works too)");

        npcDebounceMs = d.integer("ui.npc-click-debounce-ms", 500, "Minimum ms between two NPC clicks per player");
        npcLookRange = d.number("ui.npc-look-range", 8.0, "NPCs turn to face the nearest player within this many blocks");
        npcHoloHeight = d.number("ui.npc-holo-height", 0.12, "Gap in blocks between an NPC's head and the bottom line of its nametag");

        chatFormat = d.flag("ui.chat-format", true, "Format public chat as '[level] name: message'");
        star = d.flag("ui.sbmenu-star", true, "Keep the SkyBlock Menu nether star locked in hotbar slot 9");
        vanillaRecipes = d.flag("ui.vanilla-recipe-toasts", false, "Let vanilla unlock recipe-book recipes (shows 'New Recipe(s) Unlocked!' toasts)");
        vanillaAdvancements = d.flag("ui.vanilla-advancements", false, "Let vanilla grant advancements (shows 'Advancement Made!' toasts)");
    }
}
