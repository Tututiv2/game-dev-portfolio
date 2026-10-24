package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Calendar and Events (research 08 section 11.12): the month as a grid of 31 days (stack size = the
 * day), today highlighted, every calendar event coloured onto the days it runs, arrows to look at
 * other months, and an "Upcoming Events" list with real-time countdowns for every event core knows -
 * including real-time ones like the hourly auction. Mayor at slot 51.
 */
final class CalendarMenu {

    private CalendarMenu() {}

    static final int FIRST_DAY_SLOT = 9;   // days 1..31 -> slots 9..39

    private static final Material[] DYES = {Material.ORANGE_DYE, Material.LIME_DYE, Material.LIGHT_BLUE_DYE,
            Material.MAGENTA_DYE, Material.YELLOW_DYE, Material.PINK_DYE, Material.CYAN_DYE, Material.PURPLE_DYE,
            Material.RED_DYE, Material.GREEN_DYE, Material.BLUE_DYE, Material.BROWN_DYE};
    private static final char[] DYE_COLORS = {'6', 'a', 'b', 'd', 'e', 'd', '3', '5', 'c', '2', '9', '6'};

    static int colorIndex(CalendarEvent e) { return Math.floorMod(e.id == null ? 0 : e.id.hashCode(), DYES.length); }

    static Menu build(Player p, int monthOffset) {
        SkyDate now = Sky.calendar().now();
        int[] my = CalendarMath.shiftMonth(now.month(), now.year(), monthOffset);
        int month = my[0], year = my[1];
        String title = monthOffset == 0 ? "Calendar and Events" : MenuKit.clip(SkyDate.MONTHS[month - 1] + ", Year " + year);
        Menu m = new Menu(title, 6).chrome();
        m.drawer(menu -> draw(menu, p, month, year, monthOffset));
        m.refreshEvery(100);
        m.set(45, Icons.of(Material.ARROW, "&aPrevious Month", "&7" + SkyDate.MONTHS[CalendarMath.shiftMonth(month, year, -1)[0] - 1]),
                c -> { MenuKit.click(c.player()); build(c.player(), monthOffset - 1).open(c.player()); });
        m.set(53, Icons.of(Material.ARROW, "&aNext Month", "&7" + SkyDate.MONTHS[CalendarMath.shiftMonth(month, year, 1)[0] - 1]),
                c -> { MenuKit.click(c.player()); build(c.player(), monthOffset + 1).open(c.player()); });
        if (monthOffset != 0) {
            m.set(47, Icons.of(Material.CLOCK, "&aBack to Today", "&7" + now.dayLabel()),
                    c -> { MenuKit.click(c.player()); build(c.player(), 0).open(c.player()); });
        }
        m.set(50, Icons.of(Material.BOOK, "&aUpcoming Events", List.of("&7Every event with a countdown,", "&7soonest first.", "", "&eClick to view!")),
                c -> { MenuKit.click(c.player()); upcoming(c.player(), 0).open(c.player()); });
        m.set(51, mayorIcon(), c -> { MenuKit.click(c.player()); Links.open(c.player(), "election"); });
        m.set(52, Icons.of(Material.BELL, "&aEvent Reminders", List.of(
                        "&7Get a chat reminder before an",
                        "&7event starts.",
                        "",
                        "&7Currently: " + SettingDefs.label(SettingDefs.get(SettingDefs.NOTIFY_REMIND), SettingsStore.get(p, SettingDefs.NOTIFY_REMIND)),
                        "&7Lead time: " + SettingDefs.label(SettingDefs.get(SettingDefs.NOTIFY_REMIND_MIN), SettingsStore.get(p, SettingDefs.NOTIFY_REMIND_MIN)),
                        "&7Calendar alerts: " + SettingDefs.label(SettingDefs.get(SettingDefs.NOTIFY_SCOPE), SettingsStore.get(p, SettingDefs.NOTIFY_SCOPE)),
                        "",
                        "&eLeft-click to toggle reminders!",
                        "&eRight-click to change which events!")),
                c -> {
                    MenuKit.click(c.player());
                    if (c.right()) {
                        // Important only -> Everything -> Off, the same cycle as /eventalerts and the Festivals bell
                        SettingDefs.Setting sc = SettingDefs.get(SettingDefs.NOTIFY_SCOPE);
                        SettingsStore.set(c.player(), sc.key(), SettingDefs.next(sc, SettingsStore.get(c.player(), sc.key()), false));
                    } else {
                        SettingsStore.set(c.player(), SettingDefs.NOTIFY_REMIND, SettingsStore.on(c.player(), SettingDefs.NOTIFY_REMIND) ? "false" : "true");
                    }
                    build(c.player(), monthOffset).open(c.player());
                });
        MenuKit.backToSkyMenu(m);
        return m;
    }

    private static void draw(Menu m, Player p, int month, int year, int offset) {
        SkyDate now = Sky.calendar().now();
        long mpd = Sky.calendar().millisPerDay();
        List<CalendarEvent> events = new ArrayList<>(Sky.calendar().events());
        m.set(4, header(now, mpd));
        int todayIdx = now.year() * CalendarMath.DAYS_PER_YEAR + CalendarMath.dayIndex(now.month(), now.day());
        for (int day = 1; day <= CalendarMath.DAYS_PER_MONTH; day++) {
            int idx = year * CalendarMath.DAYS_PER_YEAR + CalendarMath.dayIndex(month, day);
            List<CalendarEvent> on = distinctByName(CalendarMath.on(events, year, month, day));
            final List<CalendarEvent> today = on;
            m.set(FIRST_DAY_SLOT + day - 1, dayIcon(month, year, day, idx - todayIdx, on, now, mpd),
                    on.isEmpty() ? null : c -> { MenuKit.click(c.player()); Links.open(c.player(), FeatureIndex.forEvent(today.get(0).id)); });
        }
    }

    /** Two modules may register the same festival (mayors and progression both register the New Year). */
    static List<CalendarEvent> distinctByName(List<CalendarEvent> on) {
        if (on.size() < 2) return on;
        List<CalendarEvent> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CalendarEvent e : on) if (seen.add(Summaries.eventName(e).toLowerCase(java.util.Locale.ROOT))) out.add(e);
        return out;
    }

    static ItemStack header(SkyDate now, long mpd) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Year " + now.year() + ", &" + CalendarMath.seasonColor(now.month()) + CalendarMath.season(now.month()));
        lore.add("&7" + now.timeLabel() + " " + (now.isNight() ? "&b☽ Night" : "&e☀ Day"));
        lore.add("");
        lore.add("&7One SkyBlock day lasts &e" + Text.duration(mpd / 1000) + "&7.");
        lore.add("&7One SkyBlock year lasts &e" + Text.duration(Math.round(CalendarMath.realHoursPerYear(mpd) * 3600)) + "&7.");
        String mayor = Summaries.mayorName();
        if (mayor != null) { lore.add(""); lore.add("&7Mayor: &a" + mayor); }
        List<Summaries.Upcoming> up = Summaries.upcoming();
        if (!up.isEmpty()) {
            lore.add("");
            lore.add("&7Coming up:");
            for (int i = 0; i < Math.min(5, up.size()); i++) {
                Summaries.Upcoming u = up.get(i);
                lore.add(" &6" + Summaries.eventName(u.event()) + " " + Summaries.when(u));
            }
        }
        return Icons.glow(Icons.of(Material.CLOCK, "&a" + now.dayLabel(), lore));
    }

    static ItemStack dayIcon(int month, int year, int day, int daysFromToday, List<CalendarEvent> on, SkyDate now, long mpd) {
        String label = SkyDate.MONTHS[month - 1] + " " + SkyDate.ordinal(day);
        List<String> lore = new ArrayList<>();
        lore.add("&7Year " + year);
        if (!on.isEmpty()) {
            lore.add("");
            for (CalendarEvent e : on) {
                int dayOf = CalendarMath.dayOfEvent(e, year, month, day);
                int dur = Math.max(1, e.durationDays);
                lore.add("&" + DYE_COLORS[colorIndex(e)] + "● " + Summaries.eventName(e) + (dur > 1 ? " &8(day " + dayOf + "/" + dur + ")" : ""));
            }
        }
        if (daysFromToday > 0) {
            double ms = (daysFromToday - CalendarMath.dayFraction(now)) * mpd;
            lore.add("");
            lore.add("&7Starts in: &e" + Text.duration(Math.round(ms / 1000)));
        }
        if (!on.isEmpty()) {
            FeatureIndex.Feature f = FeatureIndex.get(FeatureIndex.forEvent(on.get(0).id));
            lore.add("");
            lore.add("&eClick to open " + (f == null ? "the event" : f.name()) + "!");
        }
        ItemStack it;
        String name;
        if (daysFromToday == 0) {
            name = "&a" + label + " &7(Today)";
            it = Icons.glow(Icons.of(on.isEmpty() ? Material.CLOCK : DYES[colorIndex(on.get(0))], name, lore));
        } else if (daysFromToday < 0) {
            name = "&8" + label;
            lore.add("");
            lore.add("&8This day has passed.");
            it = Icons.of(Material.GRAY_STAINED_GLASS_PANE, name, lore);
        } else if (on.isEmpty()) {
            it = Icons.of(Material.PAPER, "&f" + label, lore);
        } else {
            CalendarEvent first = on.get(0);
            it = Icons.of(DYES[colorIndex(first)], "&" + DYE_COLORS[colorIndex(first)] + label, lore);
        }
        return Icons.amount(it, day);
    }

    static ItemStack mayorIcon() {
        String mayor = Summaries.mayorName();
        List<String> lore = new ArrayList<>();
        if (mayor == null) lore.add("&8No mayor has been elected yet.");
        else lore.add("&7Current Mayor: &a" + mayor);
        lore.add("");
        lore.add("&eClick to view the election!");
        return Icons.of(Material.GOLDEN_HELMET, "&aMayor & Election", lore);
    }

    // ---- upcoming events -----------------------------------------------------------------------------

    static Menu upcoming(Player p, int page) {
        List<Summaries.Upcoming> list = Summaries.upcoming();
        Menu m = Sky.menus().paged("Upcoming Events", list, CalendarMenu::upcomingIcon,
                pc -> { MenuKit.click(pc.click().player()); Links.open(pc.click().player(), FeatureIndex.forEvent(pc.entry().event().id)); },
                menu -> menu.back("Calendar and Events", () -> build(p, 0)), page);
        // countdowns tick: redraw this page's icons every 5 s while it is the open menu (cheap: the list is cached)
        int pg = Math.max(0, Math.min(page, Math.max(0, (list.size() - 1) / PagedSlots.PER_PAGE)));
        // Core's arrows rebuild the next page through its own paged() - without this method's drawer, so
        // pages 2+ used to be frozen snapshots. Re-point them here so every page ticks.
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c -> upcoming(c.player(), pg - 1).open(c.player()));
        if ((pg + 1) * PagedSlots.PER_PAGE < list.size())
            m.set(53, Icons.nextPage(pg + 1), c -> upcoming(c.player(), pg + 1).open(c.player()));
        m.refreshEvery(100);
        m.drawer(menu -> {
            if (Sky.menus().current(p) != menu) return;
            List<Summaries.Upcoming> now = Summaries.upcoming();
            int start = pg * PagedSlots.PER_PAGE;
            for (int i = 0; i < PagedSlots.PER_PAGE && start + i < now.size() && start + i < list.size(); i++) {
                if (!now.get(start + i).event().id.equals(list.get(start + i).event().id)) return;   // order changed: leave it
                menu.getInventory().setItem(PagedSlots.INNER[i], upcomingIcon(now.get(start + i)));
            }
        });
        return m;
    }

    /** The inner 7x4 area core's paged menus use (MenuService javadoc: 10-16, 19-25, 28-34, 37-43). */
    static final class PagedSlots {
        static final int[] INNER = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        static final int PER_PAGE = INNER.length;
    }

    static ItemStack upcomingIcon(Summaries.Upcoming u) {
        CalendarEvent e = u.event();
        List<String> lore = new ArrayList<>();
        lore.add("&8" + CalendarMath.schedule(e));
        lore.add("");
        if (u.active()) {
            lore.add("&aHappening right now!");
            if (u.remaining() > 0) lore.add("&7Ends in: &e" + Text.duration(u.remaining() / 1000));
        } else {
            lore.add("&7Starts in: &e" + Text.duration(u.millis() / 1000));
            if (!CalendarMath.isRealtime(e)) {
                SkyDate start = CalendarMath.dateAfter(Sky.calendar().now(), u.millis(), Sky.calendar().millisPerDay());
                lore.add("&7On: &f" + start.dayLabel() + ", Year " + start.year());
            }
        }
        FeatureIndex.Feature f = FeatureIndex.get(FeatureIndex.forEvent(e.id));
        lore.add("");
        lore.add("&eClick to open " + (f == null ? "the event" : f.name()) + "!");
        char col = DYE_COLORS[colorIndex(e)];
        ItemStack it = Icons.of(u.active() ? Material.LIME_DYE : DYES[colorIndex(e)],
                (u.active() ? "&a" : "&" + col) + Summaries.eventName(e), lore);
        return u.active() ? Icons.glow(it) : it;
    }
}
