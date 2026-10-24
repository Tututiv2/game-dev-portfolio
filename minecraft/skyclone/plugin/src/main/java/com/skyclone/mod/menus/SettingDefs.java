package com.skyclone.mod.menus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every per-player setting, its category, default and which module reads it. Pure data.
 *
 * <p><b>Storage contract (other modules READ these):</b> member data key {@code "settings"}, a class
 * with exactly one field {@code public Map<String, String> values}. Toggles are "true"/"false",
 * choices are one of {@link Setting#options}. Every key below is filled with its default when the
 * profile becomes ready, so a reader never has to know the default. The same values are mirrored
 * onto the player's PDC as {@code skyclone:setting.<key>} (STRING) for modules that would rather not
 * touch the profile key at all. See docs/handoff/menus.md.
 */
public final class SettingDefs {

    private SettingDefs() {}

    public enum Type { TOGGLE, CHOICE }

    public enum Category {
        PERSONAL("Personal", "PLAYER_HEAD", "How menus, numbers and your profile behave."),
        CHAT("Chat", "OAK_SIGN", "What shows up in your chat."),
        NOTIFICATIONS("Notifications", "BELL", "Reminders and alerts for events and trades."),
        COMBAT("Combat", "IRON_SWORD", "Combat feedback."),
        GATHERING("Gathering", "IRON_PICKAXE", "Mining, farming, foraging and fishing alerts."),
        DISPLAY("Display", "PAINTING", "Pets and the tab list."),
        ISLAND("Island", "GRASS_BLOCK", "Who may visit your island.");

        public final String title;
        public final String icon;
        public final String description;

        Category(String title, String icon, String description) {
            this.title = title;
            this.icon = icon;
            this.description = description;
        }
    }

    /**
     * @param key         stable id, lower case with dots
     * @param reader      module that acts on it ("menus" = implemented here)
     */
    public record Setting(String key, Category category, Type type, String name, String icon, String description,
                          String def, List<String> options, String reader) {
        public boolean toggle() { return type == Type.TOGGLE; }

        /** Is {@code v} a legal value for this setting? */
        public boolean valid(String v) {
            if (v == null) return false;
            return toggle() ? (v.equals("true") || v.equals("false")) : options.contains(v);
        }
    }

    private static final Map<String, Setting> ALL = new LinkedHashMap<>();

    private static void toggle(String key, Category c, String name, String icon, boolean def, String reader, String desc) {
        ALL.put(key, new Setting(key, c, Type.TOGGLE, name, icon, desc, String.valueOf(def), List.of("true", "false"), reader));
    }

    private static void choice(String key, Category c, String name, String icon, String def, List<String> options, String reader, String desc) {
        ALL.put(key, new Setting(key, c, Type.CHOICE, name, icon, desc, def, List.copyOf(options), reader));
    }

    // Keys implemented by THIS module (constants so code and data cannot drift).
    public static final String CLICK_SOUNDS = "menus.click_sounds";
    public static final String QUICK_ROW = "menus.sbmenu_quick_row";
    public static final String PV_ALLOW = "pv.allow_view";
    public static final String PV_SNEAK = "pv.sneak_click";
    public static final String PV_COINS = "pv.show_coins";
    public static final String CHAT_PLAYERS = "chat.players";
    public static final String NOTIFY_REMIND = "notify.event_reminders";
    public static final String NOTIFY_REMIND_MIN = "notify.event_reminder_minutes";
    public static final String NOTIFY_START = "notify.event_start";
    /**
     * Calendar alert scope (important / all / off). The mayors module decides who hears what and owns the values;
     * it also writes them itself ({@code /eventalerts}, the Festivals &amp; Events bell) on the PDC mirror
     * {@code skyclone:setting.notify.event_scope}, so the entry here adopts them (reader is not "menus").
     */
    public static final String NOTIFY_SCOPE = "notify.event_scope";
    /** The scope values, in mayors' cycle order (important -&gt; all -&gt; off). */
    public static final List<String> SCOPES = List.of("important", "all", "off");
    /** The settings that steer the calendar chat, whichever module is sending it. */
    public static final List<String> EVENT_CHAT_KEYS = List.of(NOTIFY_SCOPE, NOTIFY_REMIND, NOTIFY_REMIND_MIN, NOTIFY_START);
    public static final String TAB_STATS = "tab.stats";
    /** Read by pets / alchemy, which draw those tab widgets themselves (ours would be a duplicate). */
    public static final String TAB_PET = "tab.pet";
    public static final String TAB_EFFECTS = "tab.effects";
    public static final String TAB_EVENT = "tab.event";
    /**
     * Damage Numbers. Core spawns the floating numbers; this module hides each one from the players who turned
     * the setting off ({@code Player.hideEntity} as the indicator enters the world), so it is implemented here.
     */
    public static final String DAMAGE_NUMBERS = "combat.damage_indicators";
    /** Co-op invites: this module refuses a typed {@code /coop invite <player>} to a player who turned them off. */
    public static final String COOP_INVITES = "chat.coop_invites";
    /**
     * Trade Requests: this module refuses a {@code /trade <player>} (typed or clicked) and the empty-hand right-click
     * request to a player who turned them off - unless that player asked YOU first (then it is an accept).
     */
    public static final String TRADE_REQUESTS = "chat.trade_requests";
    /** Sidebar toggles ({@link SidebarToggles}): each hides a group of module sidebar sections for this player. */
    public static final String SIDEBAR_OBJECTIVE = "sidebar.objective";
    public static final String SIDEBAR_SLAYER = "sidebar.slayer";
    public static final String SIDEBAR_EVENTS = "sidebar.events";
    public static final String SIDEBAR_AREA = "sidebar.area";

    static {
        Category P = Category.PERSONAL, C = Category.CHAT, N = Category.NOTIFICATIONS, B = Category.COMBAT,
                G = Category.GATHERING, D = Category.DISPLAY, I = Category.ISLAND;
        // personal
        toggle(CLICK_SOUNDS, P, "Menu Sounds", "NOTE_BLOCK", true, "menus", "Play a click sound when you use a menu.");
        toggle(QUICK_ROW, P, "Quick Access Row", "COMPASS", true, "menus", "Show the row of shortcuts (Museum, Bestiary, Bazaar...) in the SkyBlock Menu.");
        toggle("numbers.roman", P, "Roman Numerals", "BOOK", true, "skills, collections", "Show levels and tiers as XV instead of 15.");
        toggle(PV_ALLOW, P, "Profile Viewing", "ARMOR_STAND", true, "menus", "Let other players open your Stats & Equipment with /pv.");
        toggle(PV_SNEAK, P, "Sneak-Click Profiles", "LEATHER_BOOTS", true, "menus", "Sneak and right-click a player to view their profile.");
        toggle(PV_COINS, P, "Show Coins on Profile", "GOLD_INGOT", false, "menus", "Let players viewing your profile see your purse and bank.");
        // chat
        toggle(CHAT_PLAYERS, C, "Player Chat", "PAPER", true, "menus", "See messages other players type in chat.");
        toggle("chat.sacks", C, "Sacks Messages", "LEATHER", true, "storage", "The 30-second summary of items your sacks picked up.");
        toggle("chat.rare_drops", C, "Rare Drop Messages", "DIAMOND", true, "core loot", "RARE DROP! lines when something good drops.");
        choice("chat.xp_popup", C, "Skill XP Popup", "EXPERIENCE_BOTTLE", "amount", List.of("amount", "percent", "off"), "skills",
                "What the action bar shows when you gain skill experience.");
        toggle(TRADE_REQUESTS, C, "Trade Requests", "EMERALD", true, "menus", "Allow other players to send you trade requests (by /trade or by right-clicking you).");
        toggle(COOP_INVITES, C, "Co-op Invites", "NAME_TAG", true, "menus", "Allow other players to invite you to their co-op.");
        // notifications
        choice(NOTIFY_SCOPE, N, "Calendar Alerts", "BELL", "important", SCOPES, "mayors",
                "Which calendar events reach your chat: Important only (festivals with rewards, the mayor election, and the Dark Auction once you can afford it), Everything, or Off. Same as /eventalerts.");
        toggle(NOTIFY_REMIND, N, "Event Reminders", "CLOCK", true, "menus", "A chat reminder shortly before a calendar event starts.");
        choice(NOTIFY_REMIND_MIN, N, "Reminder Lead Time", "REPEATER", "5", List.of("1", "5", "10", "30"), "menus",
                "How many real minutes before an event the reminder arrives.");
        toggle(NOTIFY_START, N, "Event Start Messages", "FIREWORK_ROCKET", true, "menus", "A chat line when a calendar event begins.");
        toggle("notify.auction_outbid", N, "Outbid Alerts", "GOLDEN_HORSE_ARMOR", true, "auction", "Tell me when someone outbids me.");
        toggle("notify.bazaar_filled", N, "Bazaar Order Alerts", "GOLD_INGOT", true, "bazaar", "Tell me when one of my orders fills.");
        toggle("notify.bank_interest", N, "Bank Interest", "GOLD_BLOCK", true, "bank", "Tell me when bank interest is paid.");
        toggle("notify.minion_full", N, "Full Minion Alerts", "HOPPER", true, "minions", "Tell me when a minion's storage is full.");
        // combat
        toggle(DAMAGE_NUMBERS, B, "Damage Numbers", "ARROW", true, "menus", "Floating numbers showing the damage you deal.");
        toggle("combat.slayer_alerts", B, "Slayer Boss Alerts", "ROTTEN_FLESH", true, "slayer", "Sound and title when your slayer boss spawns.");
        // gathering
        toggle("garden.visitor_alerts", G, "Visitor Alerts", "VILLAGER_SPAWN_EGG", true, "garden", "Tell me when a visitor arrives at my Garden.");
        toggle("garden.pest_alerts", G, "Pest Alerts", "SILVERFISH_SPAWN_EGG", true, "garden", "Tell me when pests appear on my plots.");
        toggle("mining.commission_alerts", G, "Commission Alerts", "PRISMARINE_CRYSTALS", true, "mining", "Tell me when a commission is complete.");
        toggle("mining.event_alerts", G, "Mining Event Alerts", "TNT", true, "mining", "Tell me when a mining event starts nearby.");
        toggle("fishing.sea_creature_alerts", G, "Sea Creature Alerts", "PRISMARINE_SHARD", true, "fishing", "A title when a rare sea creature surfaces.");
        toggle("fishing.trophy_alerts", G, "Trophy Fish Alerts", "TROPICAL_FISH", true, "fishing", "A chat line for every trophy fish caught.");
        // display
        toggle("pets.visible", D, "Show My Pet", "BONE", true, "pets", "Show your summoned pet following you.");
        toggle("pets.others_visible", D, "Show Other Pets", "LEAD", true, "pets", "Show the pets of other players.");
        toggle(TAB_STATS, D, "Tab: Stats", "BLAZE_POWDER", true, "menus", "Your key combat stats in the tab list.");
        toggle(TAB_PET, D, "Tab: Pet", "BONE", true, "pets", "Your summoned pet in the tab list.");
        toggle(TAB_EFFECTS, D, "Tab: Active Effects", "POTION", true, "alchemy", "Your active potion effects in the tab list.");
        toggle(TAB_EVENT, D, "Tab: Next Event", "CLOCK", true, "menus", "The next calendar event in the tab list.");
        toggle(SIDEBAR_OBJECTIVE, D, "Sidebar: Objective", "WRITABLE_BOOK", true, "menus", "Your current objective and district guide in the sidebar.");
        toggle(SIDEBAR_SLAYER, D, "Sidebar: Slayer Quest", "ROTTEN_FLESH", true, "menus", "Your active slayer quest and its progress in the sidebar.");
        toggle(SIDEBAR_EVENTS, D, "Sidebar: Events", "CLOCK", true, "menus", "Festival countdowns and the farming contest in the sidebar.");
        toggle(SIDEBAR_AREA, D, "Sidebar: Area Info", "FILLED_MAP", true, "menus", "What the island you are on shows: powder, pests, faction, rift and so on. Dungeon and hunt timers always show.");
        toggle("sidebar.hoppity", D, "Sidebar: Egg Hunt", "EGG", true, "chocolate", "Today's eggs in the sidebar while Hopsworth's Hunt is on.");
        // research 08 s11.11 "Comms (Abiphone): Allow calls" - extras owns the phone
        toggle("abiphone.calls", N, "Incoming Calls", "COMPARATOR", true, "extras", "Let your contacts ring your phone. Off = do not disturb.");
        // island
        choice("island.visitors", I, "Island Visitors", "OAK_DOOR", "anyone", List.of("anyone", "coop", "nobody"), "island",
                "Who may use /visit to come to your island.");
        toggle("island.guest_notify", I, "Visitor Messages", "BELL", true, "island", "Tell me when someone visits my island.");
    }

    public static Setting get(String key) { return ALL.get(key); }

    public static List<Setting> all() { return List.copyOf(ALL.values()); }

    public static List<Setting> of(Category c) {
        List<Setting> out = new ArrayList<>();
        for (Setting s : ALL.values()) if (s.category() == c) out.add(s);
        return Collections.unmodifiableList(out);
    }

    /** Next option of a choice (wraps); for a toggle, the opposite value. */
    public static String next(Setting s, String current, boolean backwards) {
        if (s.toggle()) return "true".equals(current) ? "false" : "true";
        int i = s.options().indexOf(current);
        int n = s.options().size();
        if (i < 0) return s.def();
        return s.options().get(Math.floorMod(i + (backwards ? -1 : 1), n));
    }

    /** "anyone" -> "Anyone", "5" -> "5 minutes" for the reminder lead time. */
    public static String label(Setting s, String v) {
        if (s.toggle()) return "true".equals(v) ? "&aEnabled" : "&cDisabled";
        if (s.key().equals(NOTIFY_REMIND_MIN)) return "&e" + v + (v.equals("1") ? " minute" : " minutes");
        if (s.key().equals(NOTIFY_SCOPE)) return switch (v) {         // mayors' own labels (AlertPolicy.Scope)
            case "important" -> "&aImportant only";
            case "all" -> "&eEverything";
            case "off" -> "&cOff";
            default -> "&e" + StatCategories.titleCase(v);
        };
        return "&e" + StatCategories.titleCase(v);
    }

    /**
     * Who acts on a calendar-chat setting right now. Only one announcer ever runs: menus while its dial
     * {@code menus.event-reminders} is on, mayors while it holds the job ({@code mayors.alerts.takeover}, which
     * switches menus' dial off). The scope is always decided by mayors when it is installed - menus asks it
     * ({@code mayors:alert:...}) - and only "off" is honoured without it. Other keys: their static reader.
     */
    public static String liveReader(Setting s, boolean menusAnnounces, boolean mayorsInstalled, boolean mayorsAnnounces) {
        if (s == null) return "?";
        if (!EVENT_CHAT_KEYS.contains(s.key())) return s.reader();
        String sender = menusAnnounces ? "menus" : mayorsAnnounces ? "mayors" : null;
        if (sender == null) return "nobody (menus.event-reminders off, mayors not announcing)";
        if (s.key().equals(NOTIFY_SCOPE)) return mayorsInstalled ? (menusAnnounces ? "menus, decided by mayors" : "mayors") : "menus (off only)";
        return sender;
    }
}
