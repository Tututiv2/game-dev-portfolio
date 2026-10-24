package com.skyclone.core.ui;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.SidebarService;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Layout;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Per-player sidebar (one Scoreboard each, created on ProfileReady) plus the tab list
 * header/footer and tab names. Lines are score entries with a custom display name and a blank
 * number format, so they are free text and only the lines that changed are re-sent.
 */
public final class SidebarServiceImpl implements SidebarService, Listener {

    private record Section(String id, int priority, Function<Player, List<String>> lines) {}

    private static final class Board {
        final Scoreboard scoreboard;
        final Objective objective;
        final String[] shown = new String[15];
        int count;
        String title;
        String tabHeader;
        String tabFooter;
        String tabName;
        /** Sections the last sidebar draw had no room for; the tab footer shows them (ui.sidebar-overflow-tab). */
        List<List<String>> overflow = List.of();
        Board(Scoreboard s, Objective o) { scoreboard = s; objective = o; }
    }

    private static final String[] ENTRIES = new String[15];
    static { for (int i = 0; i < 15; i++) ENTRIES[i] = "sky_line_" + i; }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy");

    private final JavaPlugin plugin;
    private final List<Section> sections = new CopyOnWriteArrayList<>();
    private final List<Section> widgets = new CopyOnWriteArrayList<>();
    private final Map<UUID, Board> boards = new HashMap<>();
    /** Cached SkyBlock level per player, updated on the main thread; read by the async chat renderer. */
    final Map<UUID, Integer> levels = new ConcurrentHashMap<>();
    /** Selected SkyBlock Level emblem per player (legacy text, "" = none), read by chat on the async thread. */
    final Map<UUID, String> emblems = new ConcurrentHashMap<>();
    /** Sections a player hid (setHidden), session only. */
    private final Map<UUID, java.util.Set<String>> hiddenSections = new ConcurrentHashMap<>();

    @Override
    public void setHidden(Player p, String sectionId, boolean hide) {
        if (p == null || sectionId == null) return;
        if (hide) hiddenSections.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(sectionId);
        else {
            java.util.Set<String> h = hiddenSections.get(p.getUniqueId());
            if (h != null) h.remove(sectionId);
        }
    }

    @Override
    public boolean hidden(Player p, String sectionId) {
        java.util.Set<String> h = p == null ? null : hiddenSections.get(p.getUniqueId());
        return h != null && h.contains(sectionId);
    }
    private final Map<String, Long> warned = new HashMap<>();
    private BukkitTask task;
    private long tick;

    public SidebarServiceImpl(JavaPlugin plugin) { this.plugin = plugin; }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L); }

    void stop() {
        if (task != null) { task.cancel(); task = null; }
        for (UUID id : new ArrayList<>(boards.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                try {
                    p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                    p.sendPlayerListHeaderAndFooter(net.kyori.adventure.text.Component.empty(), net.kyori.adventure.text.Component.empty());
                } catch (Throwable ignored) { }
            }
        }
        boards.clear();
    }

    // ---- SidebarService ---------------------------------------------------------------------------

    @Override
    public void section(String id, int priority, Function<Player, List<String>> lines) {
        Objects.requireNonNull(id, "id");
        sections.removeIf(s -> s.id().equals(id));
        sections.add(new Section(id, priority, lines));
        sections.sort(Comparator.comparingInt(Section::priority));
    }

    @Override
    public void removeSection(String id) {
        sections.removeIf(s -> s.id().equals(id));
        widgets.removeIf(s -> s.id().equals(id));
    }

    @Override
    public void tabWidget(String id, int priority, Function<Player, List<String>> lines) {
        Objects.requireNonNull(id, "id");
        widgets.removeIf(s -> s.id().equals(id));
        widgets.add(new Section(id, priority, lines));
        widgets.sort(Comparator.comparingInt(Section::priority));
    }

    @Override
    public Scoreboard scoreboard(Player p) {
        if (p == null) return null;
        Board b = boards.get(p.getUniqueId());
        return b == null ? null : b.scoreboard;
    }

    @Override
    public void refresh(Player p) {
        if (p == null || !p.isOnline()) return;
        UiUtil.main(() -> {
            Board b = boards.get(p.getUniqueId());
            if (b == null) b = create(p);
            if (b != null) draw(p, b);
        });
    }

    List<String> sectionIds() {
        List<String> out = new ArrayList<>();
        for (Section s : sections) out.add(s.id() + "@" + s.priority());
        return out;
    }

    List<String> widgetIds() {
        List<String> out = new ArrayList<>();
        for (Section s : widgets) out.add(s.id() + "@" + s.priority());
        return out;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            Board b = create(p);
            if (b != null) { draw(p, b); updateTab(p, b); }
        });
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) { drop(e.player); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        levels.remove(e.getPlayer().getUniqueId());
        emblems.remove(e.getPlayer().getUniqueId());
        hiddenSections.remove(e.getPlayer().getUniqueId());
    }

    private void drop(Player p) {
        Board b = boards.remove(p.getUniqueId());
        if (b != null && p.isOnline()) {
            try { if (p.getScoreboard() == b.scoreboard) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()); }
            catch (Throwable ignored) { }
        }
    }

    /** Create boards for players already online (plugin reload, late boot). */
    void adoptOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean loaded;
            try { loaded = Sky.profiles().isLoaded(p); } catch (Throwable t) { loaded = false; }
            if (loaded && !boards.containsKey(p.getUniqueId())) {
                Board b = create(p);
                if (b != null) { draw(p, b); updateTab(p, b); }
            }
        }
    }

    private Board create(Player p) {
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective("skyclone", Criteria.DUMMY, Text.c(title()));
            o.numberFormat(NumberFormat.blank());
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            Board b = new Board(sb, o);
            b.title = title();
            p.setScoreboard(sb);
            boards.put(p.getUniqueId(), b);
            return b;
        } catch (Throwable t) {
            UiUtil.warn("creating sidebar for " + p.getName(), t);
            return null;
        }
    }

    // ---- tick ---------------------------------------------------------------------------------

    private void tick() {
        tick++;
        int sideEvery = Math.max(5, safeInt(UiDials.sidebarRefreshTicks, 20));
        int tabEvery = Math.max(10, safeInt(UiDials.tabRefreshTicks, 40));
        boolean side = tick % sideEvery == 0;
        boolean tab = tick % tabEvery == 0;
        if (!side && !tab) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Board b = boards.get(p.getUniqueId());
            if (b == null) continue;
            if (side) draw(p, b);
            if (tab) updateTab(p, b);
        }
    }

    private void draw(Player p, Board b) {
        try {
            boolean on = true;
            try { on = UiDials.sidebarEnabled.get(); } catch (Throwable ignored) { }
            if (!on) {
                if (b.objective.getDisplaySlot() == DisplaySlot.SIDEBAR) b.objective.setDisplaySlot(null);
                return;
            }
            if (b.objective.getDisplaySlot() != DisplaySlot.SIDEBAR) b.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            if (p.getScoreboard() != b.scoreboard) p.setScoreboard(b.scoreboard);
            String t = title();
            if (!t.equals(b.title)) { b.objective.displayName(Text.c(t)); b.title = t; }
            List<List<String>> dropped = new ArrayList<>();
            List<String> lines = compose(p, dropped);
            apply(b, lines);
            b.overflow = dropped;
        } catch (Throwable t) {
            warnOnce("draw", "sidebar draw failed for " + p.getName(), t);
        }
    }

    private void apply(Board b, List<String> lines) {
        int n = Math.min(15, lines.size());
        for (int i = 0; i < n; i++) {
            String line = lines.get(i);
            Score s = b.objective.getScore(ENTRIES[i]);
            int want = n - i;
            if (!s.isScoreSet() || s.getScore() != want) s.setScore(want);
            if (!line.equals(b.shown[i])) {
                s.customName(Text.c(line.isEmpty() ? " " : line));
                b.shown[i] = line;
            }
        }
        for (int i = n; i < b.count; i++) {
            b.scoreboard.resetScores(ENTRIES[i]);
            b.shown[i] = null;
        }
        b.count = n;
    }

    /** The full sidebar for one player (without the title). Public for the self-test and /sb ui sidebar. */
    List<String> compose(Player p) { return compose(p, null); }

    /** As {@link #compose(Player)}; sections that did not fit go into {@code dropped} when it is not null. */
    List<String> compose(Player p, List<List<String>> dropped) {
        List<String> head = new ArrayList<>(8);
        head.add("&7" + LocalDate.now().format(DATE) + " &8m" + safeText(UiDials.shard, "1A"));
        head.add("");
        SkyDate now = null;
        try { now = Sky.calendar().now(); } catch (Throwable t) { warnOnce("calendar", "calendar unavailable for sidebar", t); }
        if (now != null) {
            head.add(" " + now.dayLabel());
            head.add(" &7" + (now.isNight() ? "&b☽" : "&e☀") + " &7" + now.timeLabel());
        } else {
            head.add(" &7?");
            head.add(" &7?");
        }
        head.add(" &7⏣ " + location(p));
        head.add("");
        String purse = "0";
        long bits = 0;
        try { purse = Text.commas(Sky.economy().purse(p)); } catch (Throwable t) { warnOnce("purse", "economy unavailable for sidebar", t); }
        try { bits = Sky.economy().bits(p); } catch (Throwable t) { warnOnce("bits", "bits unavailable for sidebar", t); }
        head.add("Purse: &6" + purse);
        head.add("Bits: &b" + Text.commasInt(bits));

        List<List<String>> mods = new ArrayList<>();
        java.util.Set<String> hid = hiddenSections.get(p.getUniqueId());
        for (Section s : sections) {
            if (hid != null && hid.contains(s.id())) continue;
            try {
                List<String> l = s.lines().apply(p);
                if (l != null && !l.isEmpty()) mods.add(new ArrayList<>(l));
            } catch (Throwable t) {
                warnOnce("section:" + s.id(), "sidebar section '" + s.id() + "' threw", t);
            }
        }
        int max = Math.max(5, Math.min(15, safeInt(UiDials.sidebarMaxLines, 15)));
        return SidebarFrame.compose(head, mods, footer(), max, dropped);
    }

    /** Sidebar footer: the server.footer dial with {server} = ui.server-name, in yellow; blank stays blank. */
    static String footer() {
        String f = safeText(UiDials.footer, "{server}")
                .replace("{server}", safeText(UiDials.serverName, "SKYCLONE")).strip();
        return f.isEmpty() ? "" : "&e" + f;
    }

    private String location(Player p) {
        try {
            Layout.Zone z = Sky.worlds().zoneOf(p);
            if (z != null) return "&" + z.color() + z.displayName();
        } catch (Throwable t) { warnOnce("zone", "zone lookup failed for sidebar", t); }
        try {
            String island = Sky.worlds().islandOf(p.getLocation());
            if (island != null) {
                IslandDef d = Sky.worlds().island(island);
                if (d != null && d.displayName != null) return "&b" + d.displayName;
            }
        } catch (Throwable ignored) { }
        return "&7None";
    }

    // ---- tab list --------------------------------------------------------------------------------

    private void updateTab(Player p, Board b) {
        try {
            int level = level(p);
            levels.put(p.getUniqueId(), level);
            String emblem = emblem(p);
            emblems.put(p.getUniqueId(), emblem);
            String header = "\n" + safeText(UiDials.tabHeader, "&bYou are playing on &e&l{server}")
                    .replace("{server}", safeText(UiDials.serverName, "SKYCLONE")) + "\n";
            List<List<String>> ws = new ArrayList<>();
            for (Section s : widgets) {
                try {
                    List<String> l = s.lines().apply(p);
                    if (l != null && !l.isEmpty()) ws.add(new ArrayList<>(l));
                } catch (Throwable t) {
                    warnOnce("widget:" + s.id(), "tab widget '" + s.id() + "' threw", t);
                }
            }
            boolean overflowOn = true;
            try { overflowOn = UiDials.sidebarOverflowTab.get() && UiDials.sidebarEnabled.get(); } catch (Throwable ignored) { }
            List<List<String>> over = overflowOn ? b.overflow : null;
            List<String> foot = SidebarFrame.tabFooter(ws, over, safeText(UiDials.tabFooter, ""), Math.max(4, safeInt(UiDials.tabMaxLines, 40)));
            String footer = "\n" + String.join("\n", foot) + "\n";
            if (!header.equals(b.tabHeader) || !footer.equals(b.tabFooter)) {
                p.sendPlayerListHeaderAndFooter(Text.c(header), Text.c(footer));
                b.tabHeader = header;
                b.tabFooter = footer;
            }
            boolean names = true;
            try { names = UiDials.tabNames.get(); } catch (Throwable ignored) { }
            String name = names ? LevelColors.bracket(level) + " &7" + p.getName() + (emblem.isEmpty() ? "" : " " + emblem) : null;
            if (!Objects.equals(name, b.tabName)) {
                p.playerListName(name == null ? null : Text.c(name));
                b.tabName = name;
            }
        } catch (Throwable t) {
            warnOnce("tab", "tab list update failed for " + p.getName(), t);
        }
    }

    static String emblem(Player p) {
        try {
            String e = Sky.service(Features.SkyLevel.class).emblem(p);
            return e == null ? "" : e;
        } catch (Throwable t) { return ""; }
    }

    static int level(Player p) {
        try { return Math.max(0, Sky.service(Features.SkyLevel.class).level(p)); }
        catch (Throwable t) { return 0; }
    }

    /** Core's own tab widget: profile, area, level. */
    void registerDefaultWidgets() {
        tabWidget("core.account", 0, p -> {
            List<String> l = new ArrayList<>();
            l.add("&3&lAccount Info");
            try { l.add("&fProfile: &a" + Sky.profiles().profileName(p)); } catch (Throwable ignored) { }
            l.add("&fSB Level: " + LevelColors.bracket(level(p)));
            try {
                String island = Sky.worlds().islandOf(p.getLocation());
                IslandDef d = island == null ? null : Sky.worlds().island(island);
                l.add("&fArea: &b" + (d != null && d.displayName != null ? d.displayName : "None"));
            } catch (Throwable ignored) { }
            l.add("&fServer: &8m" + safeText(UiDials.shard, "1A"));
            return l;
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private String title() { return safeText(UiDials.sidebarTitle, "&e&lSKYBLOCK"); }

    private static String safeText(com.skyclone.api.config.Dials.Dial<String> d, String def) {
        try { String v = d.get(); return v == null ? def : v; } catch (Throwable t) { return def; }
    }

    private static int safeInt(com.skyclone.api.config.Dials.Dial<Integer> d, int def) {
        try { Integer v = d.get(); return v == null ? def : v; } catch (Throwable t) { return def; }
    }

    /** Log a failure at most once a minute per key, so a broken section cannot flood the console. */
    private void warnOnce(String key, String msg, Throwable t) {
        long now = System.currentTimeMillis();
        Long last = warned.get(key);
        if (last != null && now - last < 60_000) return;
        warned.put(key, now);
        UiUtil.warn(msg, t);
    }
}
