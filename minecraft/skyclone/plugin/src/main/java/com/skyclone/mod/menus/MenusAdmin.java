package com.skyclone.mod.menus;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.time.CalendarEvent;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /sb menus ...} - every sub takes a player NAME so the integrator can drive it over RCON, and
 * prints synchronously (RCON only shows what is printed while the command runs).
 */
final class MenusAdmin {

    private final MenusListener listener;

    MenusAdmin(MenusListener listener) { this.listener = listener; }

    static final String USAGE = "<help|open|dump|links|layout|setting|calendar|remind|tab|snapshot> ...";

    void register() {
        Sky.commands().admin("menus", USAGE, "SkyBlock Menu, profile viewer, fast travel, calendar, settings: open/dump screens, settings, links",
                this::run, this::complete);
    }

    private void run(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "open" -> open(s, a);
                case "dump" -> dump(s, a);
                case "links" -> links(s);
                case "layout" -> layout(s);
                case "setting", "settings" -> setting(s, a);
                case "calendar" -> calendar(s);
                case "remind" -> remind(s, a);
                case "tab" -> tab(s, a);
                case "snapshot" -> snapshot(s, a);
                default -> help(s);
            }
        } catch (IllegalArgumentException e) {
            s.sendMessage("Error: " + e.getMessage());
        }
    }

    private static void help(CommandSender s) {
        s.sendMessage("/sb menus open <player> <screen> [arg]   open a screen for a player");
        s.sendMessage("/sb menus dump <player> <screen> [arg]   build a screen and print every slot");
        s.sendMessage("/sb menus snapshot <player> [viewer]     re-take an online player's offline-profile snapshot and print it; with a viewer, open the OFFLINE viewer of <player> for them");
        s.sendMessage("   screens: " + String.join(", ", Screens.NAMES));
        s.sendMessage("   args: pv <target> | statpage <combat|gathering|wisdom|misc> | breakdown <stat> | warps <island> | calendar <monthOffset> | settingcat <cat> | guidecat <cat>");
        s.sendMessage("/sb menus links                          every feature: installed service / registered command");
        s.sendMessage("/sb menus layout                         SkyBlock Menu slot map");
        s.sendMessage("/sb menus setting <player> [key [value]] list, read or set a player's settings");
        s.sendMessage("/sb menus calendar                       events, schedules, countdowns, this month's grid");
        s.sendMessage("/sb menus remind <player>                send the next event's reminder now");
        s.sendMessage("/sb menus tab <player>                   print this module's tab widgets for a player");
    }

    private static Player player(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) throw new IllegalArgumentException("not online: " + name);
        if (!Sky.profiles().isReady(p)) throw new IllegalArgumentException(name + "'s profile is not ready");
        return p;
    }

    private static void open(CommandSender s, String[] a) {
        if (a.length < 3) throw new IllegalArgumentException("usage: /sb menus open <player> <screen> [arg]");
        Player p = player(a[1]);
        Menu m = Screens.build(p, a[2], a.length > 3 ? a[3] : null);
        m.open(p);
        s.sendMessage("Opened '" + Text.strip(m.title()) + "' for " + p.getName());
    }

    private static void dump(CommandSender s, String[] a) {
        if (a.length < 3) throw new IllegalArgumentException("usage: /sb menus dump <player> <screen> [arg]");
        Player p = player(a[1]);
        Menu m = Screens.build(p, a[2], a.length > 3 ? a[3] : null);
        m.redraw();
        for (String line : describe(m)) s.sendMessage(line);
    }

    /** "slot: Name | first lore line [click]" for every non-filler slot. */
    static List<String> describe(Menu m) {
        List<String> out = new ArrayList<>();
        out.add("== " + Text.strip(m.title()) + " (" + m.rows() + " rows) ==");
        int shown = 0;
        for (int i = 0; i < m.size(); i++) {
            ItemStack it = m.get(i);
            if (it == null || it.getType().isAir()) continue;
            ItemMeta meta = it.getItemMeta();
            String name = meta != null && meta.hasDisplayName() ? Text.plain(meta.displayName()) : it.getType().name();
            if (it.getType() == Material.BLACK_STAINED_GLASS_PANE && name.isBlank()) continue;
            String lore = "";
            if (meta != null && meta.lore() != null && !meta.lore().isEmpty()) {
                for (var c : meta.lore()) { String l = Text.plain(c).trim(); if (!l.isEmpty()) { lore = l; break; } }
            }
            out.add(String.format(Locale.ROOT, "%2d: %s%s%s%s", i, name, it.getAmount() > 1 ? " x" + it.getAmount() : "",
                    lore.isEmpty() ? "" : " | " + lore, m.handler(i) != null ? " [click]" : ""));
            shown++;
        }
        out.add("(" + shown + " icons)");
        return out;
    }

    private static void links(CommandSender s) {
        int ok = 0, missing = 0, unknown = 0;
        for (FeatureIndex.Feature f : FeatureIndex.all()) {
            Links.State st = Links.state(f.id());
            switch (st) { case AVAILABLE -> ok++; case MISSING -> missing++; default -> unknown++; }
            List<String> cmds = new ArrayList<>();
            for (String c : f.commands()) {
                Boolean k = Links.commandKnown(c);
                cmds.add("/" + c + (k == null ? "?" : k ? "" : "(none)"));
            }
            s.sendMessage(String.format(Locale.ROOT, "%-9s %-12s %-22s service=%s cmds=%s", st, f.id(), f.name(),
                    f.service() == null ? "-" : f.service() + (Links.serviceInstalled(f.service()) ? "" : "(none)"), String.join(" ", cmds)));
        }
        s.sendMessage("features: " + FeatureIndex.all().size() + " available=" + ok + " missing=" + missing + " unknown=" + unknown
                + (Links.commandKnown("sbmenu") == null ? " (command probe blind: states of command-only features unknown)" : ""));
    }

    private static void layout(CommandSender s) {
        s.sendMessage("SkyBlock Menu (research 08 s11.1):");
        for (Map.Entry<Integer, String> e : SkyMenuLayout.RESEARCH.entrySet()) {
            s.sendMessage(String.format(Locale.ROOT, "  %2d %-12s %s", e.getKey(), e.getValue(), FeatureIndex.get(e.getValue()).name()));
        }
        s.sendMessage("Destinations (ours):");
        for (Map.Entry<Integer, String> e : SkyMenuLayout.DESTINATIONS.entrySet()) {
            s.sendMessage(String.format(Locale.ROOT, "  %2d %-12s %s [%s]", e.getKey(), e.getValue(), FeatureIndex.get(e.getValue()).name(), Links.state(e.getValue())));
        }
        s.sendMessage("Quick-access row (ours):");
        for (Map.Entry<Integer, String> e : SkyMenuLayout.QUICK_ROW.entrySet()) {
            s.sendMessage(String.format(Locale.ROOT, "  %2d %-12s %s", e.getKey(), e.getValue(), FeatureIndex.get(e.getValue()).name()));
        }
    }

    private static void setting(CommandSender s, String[] a) {
        if (a.length < 2) throw new IllegalArgumentException("usage: /sb menus setting <player> [key [value]]");
        Player p = player(a[1]);
        if (a.length == 2) {
            for (SettingDefs.Setting st : SettingDefs.all()) {
                String v = SettingsStore.get(p, st.key());
                s.sendMessage(String.format(Locale.ROOT, "%-34s = %-9s (default %s, read by %s)", st.key(), v, st.def(), MenusListener.liveReader(st)));
            }
            return;
        }
        SettingDefs.Setting st = SettingDefs.get(a[2]);
        if (st == null) throw new IllegalArgumentException("unknown setting '" + a[2] + "'");
        if (a.length == 3) { s.sendMessage(st.key() + " = " + SettingsStore.get(p, st.key()) + " (read by " + MenusListener.liveReader(st) + ")"); return; }
        String v = a[3].toLowerCase(Locale.ROOT);
        if (!SettingsStore.set(p, st.key(), v)) throw new IllegalArgumentException("illegal value '" + v + "' (options: " + st.options() + ")");
        s.sendMessage(st.key() + " = " + SettingsStore.get(p, st.key()) + " for " + p.getName());
    }

    private static void calendar(CommandSender s) {
        SkyDate now = Sky.calendar().now();
        long mpd = Sky.calendar().millisPerDay();
        s.sendMessage("Now: " + now.dayLabel() + ", Year " + now.year() + " " + now.timeLabel() + " | day = " + Text.duration(mpd / 1000)
                + ", SB hour = " + CalendarMath.realSecondsPerHour(mpd) + "s, year = " + CalendarMath.realHoursPerYear(mpd) + "h");
        String mayor = Summaries.mayorName();
        s.sendMessage("Mayor: " + (mayor == null ? "(none)" : mayor) + (Sky.has(Features.Mayors.class) ? "" : " (mayors module absent)"));
        List<Summaries.Upcoming> up = Summaries.upcoming();
        if (up.isEmpty()) s.sendMessage("No calendar events registered.");
        for (Summaries.Upcoming u : up) {
            s.sendMessage(String.format(Locale.ROOT, "  %-28s %-12s %s | %s", u.event().id, u.active() ? (u.remaining() > 0 ? "ACTIVE " + Text.duration(u.remaining() / 1000) + " left" : "ACTIVE") : "in " + Text.duration(u.millis() / 1000),
                    Summaries.eventName(u.event()), CalendarMath.schedule(u.event())));
        }
        List<CalendarEvent> evs = new ArrayList<>(Sky.calendar().events());
        StringBuilder grid = new StringBuilder("This month:");
        for (int d = 1; d <= CalendarMath.DAYS_PER_MONTH; d++) {
            List<CalendarEvent> on = CalendarMath.on(evs, now.year(), now.month(), d);
            if (on.isEmpty()) continue;
            List<String> names = new ArrayList<>();
            for (CalendarEvent e : on) names.add(e.id);
            grid.append(" ").append(d).append("=").append(String.join("+", names));
        }
        s.sendMessage(grid.toString());
    }

    private void remind(CommandSender s, String[] a) {
        if (a.length < 2) throw new IllegalArgumentException("usage: /sb menus remind <player>");
        Player p = player(a[1]);
        List<Summaries.Upcoming> up = Summaries.upcoming();
        for (Summaries.Upcoming u : up) {
            if (u.active()) continue;
            MenusListener.remind(p, u);
            s.sendMessage("Reminded " + p.getName() + " about " + u.event().id + " (in " + Text.duration(u.millis() / 1000) + ")");
            return;
        }
        s.sendMessage("No upcoming event to remind about.");
    }

    private void tab(CommandSender s, String[] a) {
        if (a.length < 2) throw new IllegalArgumentException("usage: /sb menus tab <player>");
        Player p = player(a[1]);
        List<List<String>> all = List.of(listener.statsWidget(p), listener.petWidget(p), listener.effectsWidget(p), listener.eventWidget(p));
        for (List<String> w : all) for (String line : w) s.sendMessage(Text.strip(line));
    }

    private List<String> complete(CommandSender s, String[] a) {
        if (a.length <= 1) return List.of("help", "open", "dump", "links", "layout", "setting", "calendar", "remind", "tab");
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2 && !sub.equals("links") && !sub.equals("layout") && !sub.equals("calendar")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        if (a.length == 3 && (sub.equals("open") || sub.equals("dump"))) return Screens.NAMES;
        if (a.length == 3 && sub.startsWith("setting")) {
            List<String> keys = new ArrayList<>();
            for (SettingDefs.Setting st : SettingDefs.all()) keys.add(st.key());
            return keys;
        }
        if (a.length == 4 && sub.startsWith("setting")) {
            SettingDefs.Setting st = SettingDefs.get(a[2]);
            return st == null ? List.of() : st.options();
        }
        if (a.length == 4 && (sub.equals("open") || sub.equals("dump"))) {
            return switch (a[2].toLowerCase(Locale.ROOT)) {
                case "statpage" -> List.of("combat", "gathering", "wisdom", "misc");
                case "settingcat" -> Arrays.stream(SettingDefs.Category.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList();
                case "guidecat" -> Arrays.stream(FeatureIndex.Category.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList();
                case "warps" -> new ArrayList<>(FastTravelMenu.byIsland().keySet());
                case "pv" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                default -> List.of();
            };
        }
        return List.of();
    }

    /**
     * /sb menus snapshot &lt;player&gt; [viewer]: an online player's snapshot is re-taken first. The stored snapshot is
     * then read the way /pv reads it offline (Sky.profiles().readOffline, off the main thread) and printed, so this
     * also proves the stored copy round-trips. With a viewer, that viewer gets the offline viewer opened.
     */
    private static void snapshot(CommandSender s, String[] a) {
        if (a.length < 2) throw new IllegalArgumentException("usage: /sb menus snapshot <player> [viewer]");
        Player online = Bukkit.getPlayerExact(a[1]);
        org.bukkit.OfflinePlayer op = online != null ? online : Bukkit.getOfflinePlayerIfCached(a[1]);
        if (op == null) throw new IllegalArgumentException("no player named " + a[1] + " has played here");
        if (online != null) PvSnapshot.take(online);
        Player viewer = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : null;
        if (a.length >= 3 && viewer == null) throw new IllegalArgumentException(a[2] + " is not online");
        String name = op.getName() == null ? a[1] : op.getName();
        if (online != null) { printSnapshot(s, Sky.profiles().member(online, PvSnapshot.KEY, PvSnapshot.Data.class), name, viewer); return; }
        // offline: never block the main thread on the DB; the answer arrives a moment later (console/log for RCON)
        s.sendMessage("Reading " + name + "'s stored snapshot...");
        Sky.profiles().readOffline(op.getUniqueId(), PvSnapshot.KEY, PvSnapshot.Data.class).whenComplete((d, err) ->
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                    if (err != null) { s.sendMessage("Could not read the snapshot: " + err); return; }
                    printSnapshot(s, d, name, viewer != null && viewer.isOnline() ? viewer : null);
                }));
    }

    private static void printSnapshot(CommandSender s, PvSnapshot.Data d, String name, Player viewer) {
        String why = PvSnapshot.refusal(true, d, name);
        if (why != null) { s.sendMessage(why); return; }
        s.sendMessage(name + ": snapshot of profile " + d.profile + ", " + Text.duration(Math.max(1, (System.currentTimeMillis() - d.at) / 1000))
                + " old; " + d.slots.size() + " icons, " + d.stats.size() + " stats (" + d.statIcons.size() + " with sources), view "
                + (d.allowView ? "allowed" : "OFF") + ", coins " + (d.showCoins ? "shown" : "hidden"));
        int bad = 0;
        for (String b : d.slots.values()) if (PvSnapshot.decode(b) == null) bad++;
        if (bad > 0) s.sendMessage(bad + " stored icons do not decode!");
        if (viewer != null) { PvSnapshot.build(viewer, d).open(viewer); s.sendMessage("Opened the offline viewer for " + viewer.getName() + "."); }
    }
}
