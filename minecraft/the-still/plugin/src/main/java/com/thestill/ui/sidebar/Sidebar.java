package com.thestill.ui.sidebar;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.core.Service;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A registry of sidebar lines, not a scoreboard.
 *
 * <p>The distinction is the point. Shadow Prison's sidebar was one ~180-line
 * method that hardcoded every line at a fixed score — 17 date, 16 name, 14
 * balance, 13 crypto — so adding a line meant finding a free number and
 * renumbering the ones below it. Eight systems were retrofitted into it one at a
 * time, and the last few never made it in.
 *
 * <p>Here a module says
 * {@snippet : sidebar.register("din", 50, viewer -> dinLine(viewer)); }
 * in the constructor of the service that owns the behaviour, and it appears.
 * That is one line of code for Din in Phase 2, heat in Phase 4, bounty and Wake
 * in Phase 6.
 *
 * <h2>Priorities</h2>
 * Higher priority sits higher on the board. Bands are spaced so later phases can
 * slot in without renumbering anything:
 * <pre>
 *   90+   identity      rank, name
 *   70    money
 *   50    DANGER        reserved for Din (Phase 2) and heat (Phase 4)
 *   30    location      depth, region
 *   10    session       blocks mined
 * </pre>
 */
public final class Sidebar implements Service {

    /**
     * Unique invisible entry strings. A sidebar row is keyed by its entry text,
     * and the visible text is carried in the row's team prefix instead — which
     * is what lets a line hold full RGB and exceed the entry length limit.
     */
    private static final String[] ENTRIES = new String[16];

    static {
        char[] codes = "0123456789abcdef".toCharArray();
        for (int i = 0; i < codes.length; i++) {
            ENTRIES[i] = "§" + codes[i] + "§r";
        }
    }

    private final Logger log;
    private final Plugin plugin;

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Map<UUID, ViewerBoard> viewers = new HashMap<>();

    private final Dial<Integer> refreshTicks;
    private final Dial<Boolean> enabled;

    private BukkitTask task;

    /** Cached sort of the registrations; rebuilt only when something registers. */
    private volatile List<Registration> ordered = List.of();

    public Sidebar(Logger log, Plugin plugin, DialRegistry dials) {
        this.log = log;
        this.plugin = plugin;
        // The dial lives with the behaviour it controls, registered as the code
        // that uses it is written. That is the design law made routine.
        this.refreshTicks = dials.intDial("ui.sidebar-refresh-ticks", 10)
                .category("Interface").label("Sidebar refresh")
                .describe("Ticks between sidebar refreshes. 20 is once a second. "
                        + "Lower is smoother and costs more.")
                .range(1, 100).step(1)
                .register();
        this.enabled = dials.boolDial("ui.sidebar", true)
                .category("Interface").label("Sidebar")
                .describe("Show the sidebar scoreboard at all.")
                .register();
    }

    @Override
    public void start() {
        schedule();
        enabled.onChange(on -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (on) {
                Bukkit.getOnlinePlayers().forEach(this::show);
            } else {
                Bukkit.getOnlinePlayers().forEach(this::hide);
            }
        }));
        refreshTicks.onChange(ticks -> Bukkit.getScheduler().runTask(plugin, () -> schedule()));
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Hand every viewer back the main scoreboard, or a reload leaves them
        // staring at a frozen board owned by a plugin that no longer exists.
        for (Player player : Bukkit.getOnlinePlayers()) {
            hide(player);
        }
        viewers.clear();
    }

    private void schedule() {
        if (task != null) {
            task.cancel();
        }
        long period = Math.max(1L, refreshTicks.asInt());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, period, period);
    }

    // ---- registration ---------------------------------------------------

    /**
     * Adds a line.
     *
     * @param id       stable identifier, used to replace or remove the line
     * @param priority higher sits higher on the board; see the class docs for bands
     */
    public void register(String id, int priority, SidebarLine line) {
        registrations.put(id, new Registration(id, priority, line));
        reorder();
    }

    public void unregister(String id) {
        if (registrations.remove(id) != null) {
            reorder();
        }
    }

    private void reorder() {
        List<Registration> sorted = new ArrayList<>(registrations.values());
        // Priority descending, then id, so the order is stable rather than
        // dependent on which module happened to register first.
        sorted.sort(Comparator.comparingInt(Registration::priority).reversed()
                .thenComparing(Registration::id));
        if (sorted.size() > ENTRIES.length) {
            log.warning("sidebar has " + sorted.size() + " lines but only "
                    + ENTRIES.length + " slots; dropping the lowest priority ones");
            sorted = sorted.subList(0, ENTRIES.length);
        }
        ordered = List.copyOf(sorted);
    }

    public int lineCount() {
        return registrations.size();
    }

    // ---- viewers --------------------------------------------------------

    public void show(Player player) {
        if (!enabled.asBoolean()) {
            return;
        }
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("still", Criteria.DUMMY, title());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Hides the red numbers down the right-hand side.
        objective.numberFormat(NumberFormat.blank());

        viewers.put(player.getUniqueId(), new ViewerBoard(board, objective));
        player.setScoreboard(board);
        refresh(player);
    }

    public void hide(Player player) {
        viewers.remove(player.getUniqueId());
        try {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        } catch (Exception e) {
            log.log(Level.FINE, "could not restore the main scoreboard", e);
        }
    }

    public void forget(UUID playerId) {
        viewers.remove(playerId);
    }

    /**
      * The board's heading, and it moves.
      *
      * <p>Same ember sweep as the welcome banners, so the server has one colour rather
      * than a different accent per surface. It costs one component rebuild per refresh
      * — the refresh was already happening, and a static heading was the last flat
      * thing left on the board.
      */
     private Component title() {
         return com.thestill.ui.Gradients.sweep("❖ THE STILL ❖",
                 StillLines.EMBER, StillLines.EMBER_PALE, 1.3f, titlePhase, true);
     }

     /** Advanced once per refresh; only its fraction matters. */
     private float titlePhase;

    // ---- rendering ------------------------------------------------------

    private void refreshAll() {
        if (!enabled.asBoolean()) {
            return;
        }
        titlePhase += 0.02f;
        if (titlePhase > 1f) {
            titlePhase -= 1f;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                refresh(player);
            } catch (Exception e) {
                // One player's broken line must not stop everyone else's board.
                log.log(Level.SEVERE, "sidebar refresh failed for " + player.getName(), e);
            }
        }
    }

    /**
     * Rebuilds one viewer's board, writing only the lines that actually changed.
     *
     * <p>Diffing matters: this runs on a timer for every player, and pushing an
     * identical prefix every tick is a packet per line per player per tick for
     * no visible benefit.
     */
    public void refresh(Player player) {
        ViewerBoard viewer = viewers.get(player.getUniqueId());
        if (viewer == null) {
            return;
        }

        viewer.objective.displayName(title());

        List<Registration> lines = ordered;
        int slot = 0;
        for (Registration registration : lines) {
            Component text;
            try {
                text = registration.line().render(player);
            } catch (Exception e) {
                log.log(Level.SEVERE, "sidebar line '" + registration.id() + "' threw", e);
                continue;
            }
            if (text == null) {
                continue; // this line has nothing to say right now
            }
            viewer.setLine(slot, lines.size() - slot, text);
            slot++;
        }
        viewer.clearFrom(slot);
    }

    private record Registration(String id, int priority, SidebarLine line) {
    }

    /** One player's board plus the last text written to each row, for diffing. */
    private static final class ViewerBoard {

        private final Scoreboard board;
        private final Objective objective;
        private final Map<Integer, Component> lastText = new HashMap<>();
        private int usedSlots;

        ViewerBoard(Scoreboard board, Objective objective) {
            this.board = board;
            this.objective = objective;
        }

        void setLine(int slot, int score, Component text) {
            if (slot >= ENTRIES.length) {
                return;
            }
            String entry = ENTRIES[slot];
            Team team = board.getTeam("l" + slot);
            if (team == null) {
                team = board.registerNewTeam("l" + slot);
            }
            if (!team.hasEntry(entry)) {
                team.addEntry(entry);
            }
            if (!text.equals(lastText.get(slot))) {
                team.prefix(text);
                lastText.put(slot, text);
            }
            objective.getScore(entry).setScore(score);
            usedSlots = Math.max(usedSlots, slot + 1);
        }

        /** Removes rows left over from a taller board. */
        void clearFrom(int firstUnused) {
            for (int slot = firstUnused; slot < usedSlots; slot++) {
                board.resetScores(ENTRIES[slot]);
                lastText.remove(slot);
            }
            usedSlots = firstUnused;
        }
    }
}
