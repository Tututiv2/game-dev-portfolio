package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import com.skyclone.mod.hollows.gen.HollowsGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * The Crystal Caverns lobby lifecycle (research: Day 0 open, Day 19 closed to newcomers, closes when fewer than 4
 * remain, Day 35 forced shutdown). There is ONE caverns world, so a lobby is a generation of it: when a lobby closes,
 * everyone inside is sent to the Dwarven Mines and the caverns are rebuilt with the next lobby number (new caves,
 * veins and ores around the same landmarks), and the new lobby opens when the build finishes. "Party-scoped": a
 * newcomer may still enter a closing lobby while a party member is inside, as joining a party member's lobby does in
 * the reference. The opening time lives in table {@code hollows_lobby}; ages run on the SkyBlock calendar's day length.
 */
final class Lobbies implements Listener {

    static final String REQ = "hollows_lobby";

    private final HollowsModule m;
    /** Epoch ms the current lobby opened; 0 = unknown yet (DB still loading) or a new one is being built. */
    private volatile long openedAt;
    private boolean loaded, building;
    private LobbyMath.Stage lastStage;
    /** Epoch ms the running close countdown ends, 0 = none. */
    private long closeAt;
    private int lastWarn;
    private final long bootAt = System.currentTimeMillis();

    Lobbies(HollowsModule m) {
        this.m = m;
        Sky.db().migrate("hollows", 1, "CREATE TABLE IF NOT EXISTS hollows_lobby (k TEXT PRIMARY KEY, v TEXT)");
        Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM hollows_lobby WHERE k = 'opened'"); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Long.parseLong(rs.getString(1)) : 0L;
            }
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (err != null) Sky.log().warning("[hollows] lobby clock unreadable: " + err);
            loaded = true;
            if (openedAt == 0) {
                if (v != null && v > 0) openedAt = v;
                else open(System.currentTimeMillis(), false);
            }
        }));
        Sky.requirements().register(REQ, new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) { return mayEnter(p); }
            @Override public String describe(String[] parts) {
                if (building) return "&ca new Crystal Caverns lobby (it is being built, a few minutes)";
                return "&can open Crystal Caverns lobby (this one closed to newcomers on Day " + m.dials.lobbyCloseDay.get() + ")";
            }
        });
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 200L, 100L);
    }

    // =============================================================================================
    // state
    // =============================================================================================

    int lobby() { return Math.max(0, m.dials.lobby.get()); }

    boolean building() { return building; }

    int day() { return LobbyMath.day(System.currentTimeMillis(), openedAt, Sky.calendar().millisPerDay()); }

    LobbyMath.Stage stage() {
        if (!m.dials.lobbyRotation.get() || openedAt <= 0) return LobbyMath.Stage.OPEN;
        return LobbyMath.stage(day(), m.dials.lobbyCloseDay.get(), m.dials.lobbyShutdownDay.get());
    }

    private void open(long at, boolean announce) {
        openedAt = at;
        building = false;
        closeAt = 0;
        lastStage = LobbyMath.Stage.OPEN;
        long v = at;
        Sky.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO hollows_lobby (k, v) VALUES ('opened', ?)")) {
                ps.setString(1, Long.toString(v));
                return ps.executeUpdate();
            }
        });
        if (announce)
            for (Player p : Bukkit.getOnlinePlayers()) {
                String isl = Quests.island(p);
                if ("dwarven_mines".equals(isl) || Quests.GT.equals(isl))
                    Msg.send(p, "&5&lCRYSTAL CAVERNS &7Lobby &d#" + lobby() + " &7has opened. &8(/hollows)");
            }
    }

    /** Status lines for the guide menu and admins. */
    List<String> status() {
        List<String> out = new ArrayList<>();
        out.add("&7Lobby: &d#" + lobby() + (building ? " &c(being built)" : ""));
        if (!m.dials.lobbyRotation.get()) { out.add("&8Lobby rotation is off."); return out; }
        if (openedAt <= 0) return out;
        long now = System.currentTimeMillis(), day = Sky.calendar().millisPerDay();
        int close = m.dials.lobbyCloseDay.get(), shut = m.dials.lobbyShutdownDay.get();
        out.add("&7Lobby age: &aDay " + day());
        LobbyMath.Stage s = stage();
        if (s == LobbyMath.Stage.OPEN) out.add("&7Closes to newcomers in &e" + LobbyMath.span(LobbyMath.millisUntilDay(now, openedAt, day, close)));
        else out.add("&cClosed to newcomers &7(Day " + close + "+)");
        out.add("&7Shuts down in &e" + LobbyMath.span(LobbyMath.millisUntilDay(now, openedAt, day, shut)));
        if (closeAt > 0) out.add("&c&lCLOSING in " + Math.max(0, (closeAt - now) / 1000) + "s");
        return out;
    }

    String sidebarTag() { return "&8(#" + lobby() + (openedAt > 0 && m.dials.lobbyRotation.get() ? " Day " + day() : "") + ")"; }

    // =============================================================================================
    // entry
    // =============================================================================================

    boolean mayEnter(Player p) {
        if (!m.dials.lobbyRotation.get()) return true;
        if (building) return false;
        boolean seen = Sky.profiles().isReady(p) && m.quests.data(p).lobbySeen == lobby();
        return LobbyMath.mayEnter(stage(), m.dials.lobbyLockNewcomers.get(), seen, partyInside(p));
    }

    private boolean partyInside(Player p) {
        for (Player o : Sky.service(Features.Party.class).members(p))
            if (o != null && o != p && Quests.CH.equals(Quests.island(o))) return true;
        return false;
    }

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        if (Quests.CH.equals(e.to) && Sky.profiles().isReady(e.player)) m.quests.data(e.player).lobbySeen = lobby();
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        if (Quests.CH.equals(Quests.island(e.player)) && !building) m.quests.data(e.player).lobbySeen = lobby();
    }

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (!Quests.CH.equals(e.islandId) || e.instanceKey != null) return;
        open(System.currentTimeMillis(), true);
    }

    // =============================================================================================
    // the clock
    // =============================================================================================

    private List<Player> inside() {
        World w = Sky.worlds().world(Quests.CH);
        return w == null ? List.of() : new ArrayList<>(w.getPlayers());
    }

    private void tick() {
        if (!loaded || building || !m.dials.lobbyRotation.get() || openedAt <= 0) return;
        if (!Sky.worlds().isBuilt(Quests.CH)) return;
        if (System.currentTimeMillis() - bootAt < 60_000) return;          // let a fresh boot settle first
        LobbyMath.Stage s = stage();
        List<Player> in = inside();
        if (lastStage == null) lastStage = s;
        if (s != lastStage) {
            lastStage = s;
            if (s == LobbyMath.Stage.CLOSING)
                for (Player p : in) Msg.send(p, "&5This Crystal Caverns lobby is now &cclosed to new players &7(Day " + m.dials.lobbyCloseDay.get()
                        + "). &7It closes when fewer than " + m.dials.lobbyCloseBelow.get() + " players remain, or on Day " + m.dials.lobbyShutdownDay.get() + ".");
        }
        boolean close = LobbyMath.shouldClose(s, in.size(), m.dials.lobbyCloseBelow.get());
        long now = System.currentTimeMillis();
        if (!close) {
            if (closeAt > 0) { closeAt = 0; for (Player p : in) Msg.send(p, "&aThe lobby is staying open for now."); }
            return;
        }
        if (in.isEmpty()) { rotate(s == LobbyMath.Stage.SHUTDOWN ? "Day " + m.dials.lobbyShutdownDay.get() + " shutdown" : "closed and empty", null); return; }
        if (closeAt == 0) {
            closeAt = now + Math.max(5, m.dials.lobbyCountdown.get()) * 1000L;
            lastWarn = Integer.MAX_VALUE;
        }
        int left = (int) Math.max(0, (closeAt - now + 999) / 1000);
        for (int mark : new int[]{60, 30, 10, 5}) {
            if (left <= mark && lastWarn > mark) {
                lastWarn = mark;
                for (Player p : in) {
                    Msg.send(p, "&c&lLOBBY CLOSING &7in &c" + left + "s&7. This Crystal Caverns lobby is " + (s == LobbyMath.Stage.SHUTDOWN
                            ? "at the end of its life (Day " + day() + ")." : "too empty to keep open.") + " You will be sent to the Dwarven Mines.");
                    Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
                }
                break;
            }
        }
        if (now >= closeAt) rotate(s == LobbyMath.Stage.SHUTDOWN ? "Day " + day() + " shutdown" : "too few players", null);
    }

    /** Close this lobby: everyone out, the next lobby number, rebuild. */
    void rotate(String why, CommandSender report) {
        if (building) { if (report != null) report.sendMessage("A new lobby is already being built."); return; }
        for (Player p : inside()) {
            Msg.send(p, "&5The Crystal Caverns lobby has closed &8(" + why + ")&5. A new one opens in a few minutes.");
            if (!Sky.worlds().warp(p, "dwarven_mines")) Sky.worlds().sendHome(p);
            if (Quests.CH.equals(Quests.island(p))) Sky.worlds().sendHome(p);
        }
        int next = lobby() + 1;
        m.dials.lobby.set(next);
        HollowsGenerator.lobby = next;
        building = true;
        openedAt = 0;
        closeAt = 0;
        Sky.log().info("[hollows] caverns lobby closed (" + why + "): building lobby #" + next);
        Sky.worlds().build(Quests.CH, true, report).whenComplete((v, err) -> {
            if (err != null) Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                Sky.log().warning("[hollows] lobby #" + next + " build failed: " + err);
                open(System.currentTimeMillis(), false);          // never leave the caverns locked forever
            });
        });
    }

    /** Admin: pretend the lobby opened {@code days} SkyBlock days ago. */
    void setDay(int days) {
        open(System.currentTimeMillis() - Math.max(0, days) * Sky.calendar().millisPerDay(), false);
        lastStage = null;
    }
}
