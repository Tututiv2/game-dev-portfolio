package com.skyclone.core.ui;

import com.skyclone.api.Sky;
import com.skyclone.api.command.CommandService;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.npc.NpcService;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.MenuService;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.SidebarService;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Boot class for the UI core: menus, sidebar + tab list, NPCs, chat format and the SkyBlock Menu star.
 * Called by SkyPlugin by reflection: {@link #boot}, then (after modules) {@link #afterModules},
 * and {@link #shutdown} on disable.
 */
public final class UiCore {

    private UiCore() {}

    private static InputPrompts prompts;
    private static MenuServiceImpl menus;
    private static SidebarServiceImpl sidebar;
    private static NpcServiceImpl npcs;
    private static MenuStar star;

    public static void boot(JavaPlugin plugin) {
        UiDials.init();
        prompts = new InputPrompts(plugin);
        menus = new MenuServiceImpl(plugin, prompts);
        sidebar = new SidebarServiceImpl(plugin);
        npcs = new NpcServiceImpl(plugin);
        star = new MenuStar(plugin);

        Sky.provide(MenuService.class, menus);
        Sky.provide(SidebarService.class, sidebar);
        Sky.provide(NpcService.class, npcs);

        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(star, plugin);       // LOWEST guards run before the menu listener
        pm.registerEvents(prompts, plugin);
        pm.registerEvents(menus, plugin);
        pm.registerEvents(sidebar, plugin);
        pm.registerEvents(npcs, plugin);
        pm.registerEvents(new ChatFormat(sidebar), plugin);
        pm.registerEvents(new VanillaNoise(), plugin);

        sidebar.registerDefaultWidgets();
        menus.start();
        sidebar.start();
        npcs.start();
        star.start();

        registerCommands();
        registerSelfTests();
        UiUtil.info("UI core booted (menus, sidebar, tab, NPCs, chat, SkyBlock Menu star)");
    }

    public static void afterModules() {
        try { npcs.afterModules(); } catch (Throwable t) { UiUtil.warn("spawning NPCs", t); }
        try { sidebar.adoptOnline(); } catch (Throwable t) { UiUtil.warn("adopting online players' sidebars", t); }
        try { star.repairAll(); } catch (Throwable t) { UiUtil.warn("star repair", t); }
        if (!Sky.has(Features.SkyMenu.class)) {
            // Fallback only: the menus module normally owns /sbmenu and provides SkyMenu.
            try {
                Sky.commands().register(SkyCommand.of("sbmenu", "Open the SkyBlock Menu")
                        .playerOnly().category("General")
                        .run((s, a) -> MenuStar.openSkyMenu((Player) s)));
                UiUtil.info("No module provides Features.SkyMenu; registered the fallback /sbmenu");
            } catch (Throwable t) {
                UiUtil.warn("registering fallback /sbmenu (the star still works)", t);
            }
        }
        UiUtil.info("UI core: " + npcs.count() + " NPC(s) registered");
    }

    public static void shutdown() {
        try { if (star != null) star.stop(); } catch (Throwable t) { UiUtil.warn("star stop", t); }
        try { if (menus != null) menus.stop(); } catch (Throwable t) { UiUtil.warn("menus stop", t); }
        try { if (prompts != null) prompts.clear(); } catch (Throwable t) { UiUtil.warn("prompts clear", t); }
        try { if (sidebar != null) sidebar.stop(); } catch (Throwable t) { UiUtil.warn("sidebar stop", t); }
        try { if (npcs != null) npcs.stop(); } catch (Throwable t) { UiUtil.warn("npcs stop", t); }
    }

    // ---- admin commands -------------------------------------------------------------------------

    private static void registerCommands() {
        CommandService c = Sky.commands();
        c.admin("npc", "<list|tp|respawn|here> [id]", "List, teleport to, respawn or locate NPCs",
                UiCore::npcCommand, (s, a) -> {
                    if (a.length <= 1) return filter(List.of("list", "tp", "respawn", "here"), a.length == 0 ? "" : a[0]);
                    if (a.length == 2 && (a[0].equalsIgnoreCase("tp") || a[0].equalsIgnoreCase("here"))) {
                        List<String> ids = new ArrayList<>();
                        for (NpcDef d : npcs.all()) ids.add(d.id);
                        return filter(ids, a[1]);
                    }
                    return List.of();
                });
        c.admin("ui", "<demo|freeslots|confirm|input|number|sidebar|refresh|star> [player]",
                "Test the UI core: demo menus, prompts, sidebar dump, star repair",
                UiCore::uiCommand, (s, a) -> a.length <= 1
                        ? filter(List.of("demo", "freeslots", "confirm", "input", "number", "sidebar", "refresh", "star"), a.length == 0 ? "" : a[0])
                        : List.of());
    }

    private static List<String> filter(List<String> opts, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    private static void npcCommand(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                List<String> lines = npcs.describeAll();
                say(s, "&e" + lines.size() + " NPC(s):");
                for (String l : lines) say(s, " " + l);
            }
            case "respawn" -> {
                npcs.respawnAll();
                say(s, "&aRespawning every NPC.");
            }
            case "tp" -> {
                if (!(s instanceof Player p)) { say(s, "&cPlayers only."); return; }
                if (a.length < 2) { say(s, "&cUsage: /sb npc tp <id>"); return; }
                Entity e = npcs.entity(a[1]);
                Location to = e != null ? e.getLocation() : npcs.homeOf(a[1]);
                if (to == null) { say(s, "&cNPC '" + a[1] + "' has no location (island not loaded or unknown id)."); return; }
                p.teleport(to.clone().add(to.getDirection().multiply(2)).setDirection(to.getDirection().multiply(-1)));
                say(s, "&aTeleported to " + a[1] + ".");
            }
            case "here" -> {
                if (a.length < 2) { say(s, "&cUsage: /sb npc here <id>"); return; }
                NpcDef d = npcs.def(a[1]);
                if (d == null) { say(s, "&cUnknown NPC '" + a[1] + "'."); return; }
                Location anchor = null;
                try { anchor = Sky.worlds().anchor(d.island, d.anchor); } catch (Throwable ignored) { }
                Entity e = npcs.entity(a[1]);
                say(s, "&e" + d.id + " &7(" + d.island + "/" + d.anchor + ")");
                say(s, " &7Anchor: " + (anchor == null ? "&cmissing" : fmt(anchor)));
                say(s, " &7Entity: " + (e == null ? "&cnot spawned" : fmt(e.getLocation())));
                if (anchor != null && e != null && anchor.getWorld().equals(e.getWorld())) {
                    say(s, " &7Distance: &f" + String.format(Locale.US, "%.2f", anchor.distance(e.getLocation())) + " blocks");
                }
                if (npcs.usedFallback(d.id)) say(s, " &cPlaced near spawn because the anchor is missing.");
            }
            default -> say(s, "&cUsage: /sb npc <list|tp|respawn|here> [id]");
        }
    }

    private static String fmt(Location l) {
        return "&f" + l.getWorld().getName() + " " + String.format(Locale.US, "%.1f %.1f %.1f", l.getX(), l.getY(), l.getZ());
    }

    private static void uiCommand(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "demo" : a[0].toLowerCase(Locale.ROOT);
        Player target = s instanceof Player p ? p : null;
        if (a.length >= 2) target = Bukkit.getPlayerExact(a[1]);
        if (sub.equals("refresh")) {
            for (Player p : Bukkit.getOnlinePlayers()) sidebar.refresh(p);
            say(s, "&aRefreshed every sidebar.");
            return;
        }
        if (target == null) { say(s, "&cNeed a player (run in game or give a name)."); return; }
        final Player p = target;
        switch (sub) {
            case "demo" -> openDemo(p, 0);
            case "freeslots" -> openFreeSlotDemo(p);
            case "confirm" -> menus.confirm(p, "Confirm Demo", List.of("&7This is a test confirmation."),
                    () -> Msg.success(p, "Confirmed."), () -> Msg.send(p, "&cCancelled."));
            case "input" -> menus.input(p, "Type anything", r -> Msg.send(p, "&7You typed: &f" + (r == null ? "<cancelled>" : r)));
            case "number" -> menus.inputNumber(p, "Enter a number", r -> Msg.send(p, "&7Parsed: &f" + (r == null ? "<cancelled>" : Text.commas(r))));
            case "sidebar" -> {
                say(s, "&eSidebar for " + p.getName() + ":");
                for (String l : sidebar.compose(p)) say(s, " &8| &r" + l);
                say(s, "&7Sections: " + String.join(", ", sidebar.sectionIds()));
                say(s, "&7Tab widgets: " + String.join(", ", sidebar.widgetIds()));
            }
            case "star" -> { star.repair(p); say(s, "&aRepaired the SkyBlock Menu star for " + p.getName() + "."); }
            default -> say(s, "&cUsage: /sb ui <demo|freeslots|confirm|input|number|sidebar|refresh|star> [player]");
        }
    }

    private static void openDemo(Player p, int page) {
        List<Integer> nums = new ArrayList<>();
        for (int i = 1; i <= 60; i++) nums.add(i);
        Menu m = menus.paged("UI Demo", nums,
                n -> Icons.of(Material.PAPER, "&aEntry #" + n, "&7A test entry.", "", "&eClick to confirm!"),
                pc -> menus.confirm(pc.click().player(), "Pick #" + pc.entry() + "?", List.of("&7Page " + (pc.page() + 1)),
                        () -> Msg.success(pc.click().player(), "You picked #" + pc.entry()),
                        () -> openDemo(pc.click().player(), pc.page())),
                menu -> {
                    menu.set(48, Icons.of(Material.OAK_SIGN, "&aSearch", "&7Test the text prompt.", "", "&eClick to search!"),
                            c -> menus.input(c.player(), "Search for", r -> Msg.send(c.player(), "&7Search: &f" + r)));
                    menu.set(50, Icons.of(Material.GOLD_NUGGET, "&aNumber", "&7Test the number prompt.", "", "&eClick to enter!"),
                            c -> menus.inputNumber(c.player(), "How many?", r -> Msg.send(c.player(), "&7Number: &f" + r)));
                    menu.set(51, Icons.of(Material.CHEST, "&aFree Slots", "&7Test free slots.", "", "&eClick to open!"),
                            c -> openFreeSlotDemo(c.player()));
                },
                page);
        m.open(p);
    }

    /** A sell-slot style menu: the middle row is free; on close everything comes back. */
    private static void openFreeSlotDemo(Player p) {
        Menu m = new Menu("Free Slot Demo", 3);
        m.free(10, 11, 12, 13, 14, 15, 16);
        m.chrome();
        m.set(4, Icons.of(Material.HOPPER, "&aFree Slots", "&7Put items in the middle row.",
                "&7They come back when you close this."));
        m.onFreeSlotChange(c -> {
            int n = 0;
            for (int s : c.menu().freeSlots()) {
                ItemStack it = c.menu().get(s);
                if (it != null && !it.isEmpty()) n += it.getAmount();
            }
            c.menu().set(4, Icons.of(Material.HOPPER, "&aFree Slots", "&7Items inside: &e" + n));
        });
        m.onClose(pl -> Msg.send(pl, "&7Free slot demo closed; leftovers returned."));
        m.open(p);
    }

    // ---- self-tests -----------------------------------------------------------------------------

    private static void registerSelfTests() {
        CommandService c = Sky.commands();
        c.selfTest("ui.number-parse", () -> {
            Object[][] ok = {{"64", 64.0}, {"1.5k", 1500.0}, {"2m", 2_000_000.0}, {"3b", 3_000_000_000.0},
                    {"1,234", 1234.0}, {" 2.5M ", 2_500_000.0}, {"0", 0.0}};
            for (Object[] t : ok) {
                Double v = NumberParse.parse((String) t[0]);
                if (v == null || Math.abs(v - (Double) t[1]) > 1e-6) return "'" + t[0] + "' parsed as " + v + ", expected " + t[1];
            }
            for (String bad : new String[]{"-1", "nan", "NaN", "Infinity", "", "k", "1e5", "abc", "1.2.3", "--5", "0x10"}) {
                if (NumberParse.parse(bad) != null) return "'" + bad + "' should be rejected";
            }
            return null;
        });
        c.selfTest("ui.sidebar-frame", () -> {
            List<String> head = List.of("d", "", "day", "time", "loc", "", "purse", "bits");
            List<List<String>> secs = List.of(List.of("a1", "a2"), List.of("b1", "b2", "b3", "b4"), List.of("c1"));
            List<String> out = SidebarFrame.compose(head, secs, "foot", 15);
            if (out.size() > 15) return "sidebar has " + out.size() + " lines";
            if (!out.get(out.size() - 1).equals("foot")) return "footer is not last";
            if (!out.contains("a1")) return "highest-priority section was dropped";
            if (out.contains("b1")) return "a section that does not fit was kept";
            if (!out.contains("c1")) return "a smaller lower-priority section that fits was dropped";
            return null;
        });
        // wave-5 regression (sidebar note): during a Harvest Fair the 4 free lines go to the fair and the Objective
        // dropped silently. Dropped sections are now reported so the tab list can show them.
        c.selfTest("ui.sidebar-overflow-reported", () -> {
            List<String> head = List.of("d", "", "day", "time", "loc", "", "purse", "bits", "x");   // the real 9-line head
            List<String> fair = List.of("&eHarvest Fair &a18m", "&e○ &fWheat &61,952", "&6BRONZE &7#15/41");
            List<String> objective = List.of("&fObjective", "&eTalk to the Banker");
            List<List<String>> dropped = new ArrayList<>();
            List<String> out = SidebarFrame.compose(head, List.of(fair, objective), "foot", 15, dropped);
            if (!out.contains(fair.get(0))) return "the live fair section lost its place";
            if (out.contains("&fObjective")) return "the frame grew room it does not have";
            if (dropped.size() != 1 || !dropped.get(0).equals(objective)) return "dropped = " + dropped + ", expected the objective";
            return null;
        });
        c.selfTest("ui.sidebar-overflow-nothing-when-fits", () -> {
            List<String> head = List.of("d", "", "day", "time", "loc", "", "purse", "bits", "x");
            List<List<String>> dropped = new ArrayList<>();
            SidebarFrame.compose(head, List.of(List.of("&fObjective", "&eTalk to the Banker")), "foot", 15, dropped);
            if (!dropped.isEmpty()) return "a section that fits was reported dropped: " + dropped;
            return null;
        });
        c.selfTest("ui.sidebar-overflow-tab-first", () -> {
            List<String> t = SidebarFrame.tabFooter(List.of(List.of("&3&lAccount Info", "&fProfile: &aApple")),
                    List.of(List.of("&fObjective", "&eTalk to the Banker")), "tail", 40);
            if (t.size() < 3 || !t.get(0).equals("&fObjective") || !t.get(1).equals("&eTalk to the Banker"))
                return "overflow is not at the top of the tab footer: " + t;
            if (!t.contains("&3&lAccount Info") || !t.get(t.size() - 1).equals("tail")) return "widgets or tail lost: " + t;
            List<String> none = SidebarFrame.tabFooter(List.of(List.of("w")), null, "tail", 40);
            if (!none.equals(List.of("w", "", "tail"))) return "no overflow changed the footer: " + none;
            return null;
        });
        c.selfTest("ui.sidebar-footer", () -> {
            String f = SidebarServiceImpl.footer();
            if (f.contains(".example") || f.contains("{server}")) return "footer is a placeholder: '" + f + "'";
            if (!f.isEmpty() && !f.startsWith("&e")) return "footer is not yellow: '" + f + "'";
            if ("{server}".equals(UiDials.footer.get()) && !f.equals("&e" + UiDials.serverName.get())) return "default footer does not name the server: '" + f + "'";
            return null;
        });
        c.selfTest("ui.paged-layout", () -> {
            if (PagedLayout.pages(0) != 1 || PagedLayout.pages(28) != 1 || PagedLayout.pages(29) != 2) return "page count is wrong";
            if (!PagedLayout.suffix(0, 60).equals(" (1/3)")) return "title suffix wrong: '" + PagedLayout.suffix(0, 60) + "'";
            List<Integer> nums = new ArrayList<>();
            for (int i = 0; i < 60; i++) nums.add(i);
            Menu m0 = menus.paged("Test", nums, n -> new ItemStack(Material.PAPER, 1), null, null, 0);
            if (!m0.title().equals("Test (1/3)")) return "page 1 title: " + m0.title();
            if (m0.get(10) == null || m0.get(10).getType() != Material.PAPER) return "slot 10 is not the first entry";
            if (m0.get(43) == null || m0.get(43).getType() != Material.PAPER) return "slot 43 is not the 28th entry";
            if (m0.get(53) == null || m0.get(53).getType() != Material.ARROW) return "no next arrow on page 1";
            if (m0.get(45) != null && m0.get(45).getType() == Material.ARROW) return "prev arrow on page 1";
            if (m0.handler(53) == null) return "next arrow is not clickable";
            Menu m2 = menus.paged("Test", nums, n -> new ItemStack(Material.PAPER, 1), null, null, 99);
            if (!m2.title().equals("Test (3/3)")) return "page clamp failed: " + m2.title();
            if (m2.get(53) != null && m2.get(53).getType() == Material.ARROW) return "next arrow on the last page";
            if (m2.get(45) == null || m2.get(45).getType() != Material.ARROW) return "no prev arrow on the last page";
            Menu one = menus.paged("Solo", List.of(1), n -> new ItemStack(Material.PAPER, 1), null, null, 0);
            if (!one.title().equals("Solo")) return "single page should have no suffix: " + one.title();
            return null;
        });
        c.selfTest("ui.confirm-builds", () -> {
            Menu m = menus.buildConfirm("Sure?", List.of("&7desc"), () -> { }, () -> { });
            if (m.size() != 27) return "confirm menu is " + m.size() + " slots";
            if (m.get(11) == null || m.get(11).getType() != Material.GREEN_TERRACOTTA) return "no green confirm at 11";
            if (m.get(15) == null || m.get(15).getType() != Material.RED_TERRACOTTA) return "no red cancel at 15";
            if (m.handler(11) == null || m.handler(15) == null) return "confirm/cancel not clickable";
            return null;
        });
        c.selfTest("ui.star-item", MenuStar::selfTest);
        c.selfTest("ui.level-colors", () -> {
            if (!LevelColors.of(0).equals("&7") || !LevelColors.of(39).equals("&7")) return "0-39 should be gray";
            if (!LevelColors.of(40).equals("&f")) return "40 should be white";
            if (!LevelColors.of(200).equals("&b")) return "200 should be aqua";
            if (!LevelColors.of(999).equals("&4")) return "480+ should be dark red";
            if (!ChatFormat.prefix(12).equals("&7[&712&7] ")) return "chat prefix wrong: " + ChatFormat.prefix(12);
            return null;
        });
        c.selfTest("ui.npc-anchors", () -> npcs.checkAnchors());
        c.selfTest("ui.npc-alive", () -> npcs.checkAlive());
        c.selfTest("ui.npc-nametag-style", NpcHolo::check);
        c.selfTest("ui.npc-names-unique", NpcNames::selfTest);
        c.selfTest("ui.item-owner-names", NpcNames::itemOwnerSelfTest);   // no item is named after a character it is not
        c.selfTest("ui.sign-wrap", () -> {
            List<String> w = InputPrompts.wrapPrompt("Enter the amount you want to deposit");
            if (w.isEmpty() || w.size() > 2) return "wrap gave " + w.size() + " lines";
            for (String l : w) if (l.length() > 15) return "sign line too long: " + l;
            if (InputPrompts.clean("cancel") != null || InputPrompts.clean("   ") != null) return "cancel/empty should be null";
            if (!"hi there".equals(InputPrompts.clean("  hi§c there "))) return "clean() did not strip section signs";
            return null;
        });
    }
}
