package com.skyclone.mod.menus;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The face of the game: the SkyBlock Menu (nether star, {@code /sbmenu}), Stats &amp; Equipment and the
 * profile viewer ({@code /stats}, {@code /pv}), Fast Travel ({@code /fasttravel}), Calendar and
 * Events ({@code /calendar}), Settings ({@code /settings}), Your Bags ({@code /bags}), the SkyBlock
 * Guide ({@code /guide}) and four tab-list widgets. Provides {@link Features.SkyMenu}.
 */
public final class MenusModule implements GameModule {

    private Dials.Dial<Boolean> quickRow, sneakClick, reminders, tabWidgets, pvOthers, pvOffline;
    private Dials.Dial<Integer> reminderSeconds, snapshotMinutes;
    private MenusListener listener;
    private BukkitTask reminderTask, snapshotTask;

    @Override public String id() { return "menus"; }

    @Override
    public void enable() {
        quickRow = Sky.dials().flag("menus.quick-row", true, "Show the quick-access row (Museum, Bestiary, Bazaar...) in the SkyBlock Menu");
        sneakClick = Sky.dials().flag("menus.sneak-click-profile", true, "Sneak + right-click a player opens their profile");
        pvOthers = Sky.dials().flag("menus.pv-others", true, "Allow /pv on other players at all (each player can also opt out in Settings)");
        reminders = Sky.dials().flag("menus.event-reminders", true, "Chat reminders before calendar events and a line when they start");
        reminderSeconds = Sky.dials().integer("menus.reminder-check-seconds", 20,
                "How often (s) the calendar reminder check runs; reminders fire within this window of the lead time");
        tabWidgets = Sky.dials().flag("menus.tab-widgets", true, "Draw this module's tab widgets (stats, pet, effects, next event)");
        pvOffline = Sky.dials().flag("menus.pv-offline", true,
                "Keep a snapshot of each player's profile so /pv works while they are offline (taken on join, every menus.pv-snapshot-minutes and on quit)");
        snapshotMinutes = Sky.dials().integer("menus.pv-snapshot-minutes", 5,
                "How often (real minutes, 1-60) online players' offline-profile snapshots are refreshed; they are always taken on quit");

        // Read the bundled data once at boot instead of on the first player's first menu open.
        MenuKit.textures();
        Summaries.collections();

        SkyBlockMenu sky = new SkyBlockMenu(quickRow);
        Screens.skyMenu = sky;
        Sky.provide(Features.SkyMenu.class, sky);
        Sky.provide(Features.Settings.class, new SettingsService());

        Map<String, Consumer<Player>> builtIns = new HashMap<>();
        builtIns.put("menus:stats", p -> ProfileViewer.open(p, p));
        builtIns.put("menus:fasttravel", p -> FastTravelMenu.build(p).open(p));
        builtIns.put("menus:calendar", p -> CalendarMenu.build(p, 0).open(p));
        builtIns.put("menus:settings", p -> SettingsMenu.build(p).open(p));
        builtIns.put("menus:bags", p -> MiscMenus.bags(p).open(p));
        builtIns.put("menus:guide", p -> MiscMenus.guide(p).open(p));
        builtIns.put("menus:quests", p -> MiscMenus.quests(p).open(p));
        builtIns.put("menus:effects", p -> MiscMenus.effects(p).open(p));
        builtIns.put("menus:home", p -> { p.closeInventory(); Sky.worlds().sendHome(p); });
        Links.builtIns = Map.copyOf(builtIns);

        listener = new MenusListener(sneakClick, reminders, tabWidgets, pvOffline);
        SettingsStore.LISTENERS.add(PvSnapshot::privacyChanged);
        Bukkit.getPluginManager().registerEvents(listener, Sky.plugin());
        listener.registerWidgets();

        registerCommands(sky);
        new MenusAdmin(listener).register();
        MenusSelfTests.register();

        scheduleReminders();
        reminderSeconds.onChange(v -> scheduleReminders());
        scheduleSnapshots();
        snapshotMinutes.onChange(v -> scheduleSnapshots());
        // players already online (a /reload): set their settings up now
        for (Player p : Bukkit.getOnlinePlayers()) if (Sky.profiles().isReady(p)) SettingsStore.prepare(p);
    }

    @Override
    public void disable() {
        if (reminderTask != null) reminderTask.cancel();
        if (snapshotTask != null) snapshotTask.cancel();
        SettingsStore.LISTENERS.clear();
    }

    /** Refresh the offline-profile snapshot of every online player, a few per tick so a full server never spikes. */
    private void scheduleSnapshots() {
        if (snapshotTask != null) snapshotTask.cancel();
        long period = Math.max(1, Math.min(60, snapshotMinutes.get())) * 60L * 20L;
        snapshotTask = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            if (!pvOffline.get()) return;
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            for (int i = 0; i < online.size(); i++) {
                Player p = online.get(i);
                Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) PvSnapshot.take(p); }, 1L + i / 4);
            }
        }, period, period);
    }

    private void scheduleReminders() {
        if (reminderTask != null) reminderTask.cancel();
        long period = Math.max(5, Math.min(120, reminderSeconds.get())) * 20L;
        reminderTask = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { listener.reminderTick(period * 50L); } catch (Throwable t) { Sky.log().warning("[menus] reminder tick: " + t); }
        }, period, period);
    }

    // ---- commands -----------------------------------------------------------------------------------

    private void registerCommands(SkyBlockMenu sky) {
        Sky.commands().register(SkyCommand.of("sbmenu", "Open the SkyBlock Menu (also the nether star in your hotbar)")
                .alias("skyblockmenu", "menu").category("General").playerOnly()
                .run((s, a) -> sky.open((Player) s)));
        Sky.commands().register(SkyCommand.of("stats", "Stats & Equipment: your armor, equipment, pet and every stat with its sources")
                .alias("equipmentstats", "mystats").category("Profile").playerOnly()
                .run((s, a) -> ProfileViewer.open((Player) s, (Player) s)));
        Sky.commands().register(SkyCommand.of("pv", "View a player's profile (online, or as they last left it): armor, equipment, pet, stats and progress")
                .alias("profileviewer", "viewprofile").usage("[player]").category("Profile").playerOnly()
                .run(this::pv)
                .tab((s, a) -> a.length <= 1 ? onlineNames() : List.of()));
        Sky.commands().register(SkyCommand.of("fasttravel", "Fast Travel: warp to any island you have unlocked")
                .alias("ft", "travelmenu", "islands").category("Travel").playerOnly()
                .run((s, a) -> FastTravelMenu.build((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("calendar", "Calendar and Events: today's date, this month and every upcoming event")
                .alias("cal", "events").usage("[events]").category("General").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length > 0 && a[0].equalsIgnoreCase("events")) CalendarMenu.upcoming(p, 0).open(p);
                    else CalendarMenu.build(p, 0).open(p);
                })
                .tab((s, a) -> List.of("events")));
        Sky.commands().register(SkyCommand.of("settings", "Settings: chat filters, alerts, tab list and profile privacy")
                .alias("options", "sbsettings").usage("[category]").category("General").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!Sky.profiles().isReady(p)) { Msg.error(p, "Your profile is still loading."); return; }
                    if (a.length > 0) {
                        try { SettingsMenu.category(p, Screens.settingCategory(a[0]), 0).open(p); return; }
                        catch (IllegalArgumentException e) { Msg.error(p, "Unknown category. Try: personal, chat, notifications, combat, gathering, display, island"); return; }
                    }
                    SettingsMenu.build(p).open(p);
                })
                .tab((s, a) -> List.of("personal", "chat", "notifications", "combat", "gathering", "display", "island")));
        // /bags is the storage module's (it enables before us and the first registration wins). Ours is
        // the stand-in the SkyBlock Menu falls back to when storage is absent, under its own name.
        Sky.commands().register(SkyCommand.of("bagsmenu", "Your Bags (stand-in): every bag in one menu, opened through its owner")
                .category("Storage").playerOnly()
                .run((s, a) -> MiscMenus.bags((Player) s).open((Player) s)));
        Sky.commands().register(SkyCommand.of("guide", "SkyBlock Guide: every feature of the game in one menu")
                .alias("sbguide", "features").category("General").playerOnly()
                .run((s, a) -> MiscMenus.guide((Player) s).open((Player) s)));
    }

    private void pv(CommandSender s, String[] a) {
        Player p = (Player) s;
        if (a.length == 0 || a[0].equalsIgnoreCase(p.getName())) { ProfileViewer.open(p, p); return; }
        if (!pvOthers.get() && !p.hasPermission("skyclone.admin")) { Msg.error(p, "Viewing other players' profiles is turned off."); return; }
        String name = a[0];
        if (!name.matches("[A-Za-z0-9_]{1,16}")) { Msg.error(p, "That is not a player name."); return; }
        Player t = Bukkit.getPlayerExact(name);
        if (t != null) { ProfileViewer.open(p, t); return; }
        if (!pvOffline.get()) { Msg.error(p, "Couldn't find an online player named " + name + "."); return; }
        PvSnapshot.openOffline(p, name);
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
