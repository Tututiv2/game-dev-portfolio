package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Island Decorator's goods (wave 5; skills hand-off request 16). Registered from {@code data/shops/items.json}
 * (generated). The three minigames are played from the item:
 * <ul>
 *   <li><b>Tic Tac Toe</b> and <b>Four in a Row</b>: right-click another player to challenge them; they accept by
 *       clicking the chat line ({@code /minigame accept <you>}) or right-clicking you back with the same game. Both
 *       players share one board menu; closing it forfeits.</li>
 *   <li><b>Rock Paper Snips</b>: right-click to open a tournament for every player near you. Each round everybody
 *       picks a sign; the LEAST picked sign wins and its players go through. Two left play by the classic rules.</li>
 * </ul>
 * Nothing is wagered and nothing is paid out: they are social toys, so they can never print coins. The placeable
 * goods (parkour plates, displays, showcase, island NPC egg, egg hunt) are blocks for the private island.
 * Pure game rules live in {@link Games} (unit-tested).
 */
final class Decorations implements Listener {

    private Decorations() {}

    static final String TTT = "TIC_TAC_TOE";
    static final String C4 = "CONNECT_FOUR";
    static final String RPS = "ROCK_PAPER_SHEARS";

    static int loaded;

    static void register() {
        try { loaded = Sky.items().loadJson("shops", "items.json"); } catch (Throwable t) { Sky.log().warning("[shops] decor items: " + t); }
        Sky.abilities().register("shops_tic_tac_toe", use -> { hint(use.player(), "Tic Tac Toe"); return false; });
        Sky.abilities().register("shops_connect_four", use -> { hint(use.player(), "Four in a Row"); return false; });
        Sky.abilities().register("shops_rock_paper_shears", use -> Rps.start(use.player()));
        Sky.commands().register(SkyCommand.of("minigame", "Island minigames: accept or decline a challenge, or forfeit your game")
                .usage("<accept|deny|forfeit> [player]").category("Social").playerOnly()
                .run((s, a) -> command((Player) s, a))
                .tab((s, a) -> a.length <= 1 ? List.of("accept", "deny", "forfeit") : List.of()));
        Bukkit.getPluginManager().registerEvents(new Decorations(), Sky.plugin());
    }

    private static void hint(Player p, String game) {
        Msg.send(p, "&eRight-click another player while holding " + game + " to challenge them!");
    }

    // ---- challenges ------------------------------------------------------------------------------------

    private record Challenge(UUID from, UUID to, String game, long at) {}

    /** Pending challenges by challenged player. */
    private static final Map<UUID, List<Challenge>> PENDING = new HashMap<>();
    private static final long CHALLENGE_MS = 60_000L;
    private static final Map<UUID, Board> PLAYING = new HashMap<>();

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClickPlayer(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player target)) return;
        Player p = e.getPlayer();
        String id = Sky.items().idOf(p.getInventory().getItemInMainHand());
        if (!TTT.equals(id) && !C4.equals(id)) return;
        if (target.hasMetadata("NPC") || Bukkit.getPlayer(target.getUniqueId()) != target) return;   // a real player only
        e.setCancelled(true);
        try { Sky.abilities().consumeClick(p); } catch (Throwable ignored) { }
        ItemDef def = Sky.items().def(id);
        if (def != null && def.requirements != null) for (String r : def.requirements) {
            boolean ok;
            try { ok = Sky.requirements().checkOrTell(p, r); } catch (Throwable t) { ok = false; }
            if (!ok) return;
        }
        challenge(p, target, id);
    }

    static void challenge(Player p, Player target, String game) {
        if (p.equals(target)) return;
        if (PLAYING.containsKey(p.getUniqueId())) { Msg.error(p, "You are already playing a game! /minigame forfeit to stop."); return; }
        if (PLAYING.containsKey(target.getUniqueId())) { Msg.error(p, target.getName() + " is already playing a game."); return; }
        // they already challenged us to this game: clicking back accepts
        Challenge theirs = find(p.getUniqueId(), target.getUniqueId(), game);
        if (theirs != null) { accept(p, target); return; }
        List<Challenge> l = PENDING.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>());
        l.removeIf(c -> c.from().equals(p.getUniqueId()));
        l.add(new Challenge(p.getUniqueId(), target.getUniqueId(), game, System.currentTimeMillis()));
        String name = gameName(game);
        Msg.send(p, "&aYou challenged &e" + target.getName() + " &ato a game of &e" + name + "&a!");
        target.sendMessage(Text.c("&e" + p.getName() + " &achallenged you to a game of &e" + name + "&a! &6&l[CLICK TO PLAY]")
                .clickEvent(ClickEvent.runCommand("/minigame accept " + p.getName())));
        Msg.sound(target, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
    }

    private static Challenge find(UUID to, UUID from, String game) {
        List<Challenge> l = PENDING.get(to);
        if (l == null) return null;
        long now = System.currentTimeMillis();
        l.removeIf(c -> now - c.at() > CHALLENGE_MS);
        for (Challenge c : l) if (c.from().equals(from) && (game == null || c.game().equals(game))) return c;
        return null;
    }

    static void command(Player p, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "accept", "deny" -> {
                Player from = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (from == null) { Msg.error(p, "That player is not online."); return; }
                if (find(p.getUniqueId(), from.getUniqueId(), null) == null) { Msg.error(p, "You have no challenge from " + from.getName() + "."); return; }
                if (sub.equals("accept")) accept(p, from);
                else {
                    PENDING.get(p.getUniqueId()).removeIf(c -> c.from().equals(from.getUniqueId()));
                    Msg.send(p, "&7You declined the challenge.");
                    Msg.send(from, "&c" + p.getName() + " declined your challenge.");
                }
            }
            case "forfeit" -> {
                Board b = PLAYING.get(p.getUniqueId());
                if (b == null) Msg.error(p, "You are not playing a game.");
                else b.forfeit(p.getUniqueId());
            }
            default -> Msg.send(p, "&e/minigame accept <player> &7| &e/minigame deny <player> &7| &e/minigame forfeit");
        }
    }

    /** {@code p} accepts the challenge {@code from} sent: both open one shared board. */
    static void accept(Player p, Player from) {
        Challenge c = find(p.getUniqueId(), from.getUniqueId(), null);
        if (c == null) { Msg.error(p, "That challenge has expired."); return; }
        PENDING.get(p.getUniqueId()).remove(c);
        if (PLAYING.containsKey(p.getUniqueId()) || PLAYING.containsKey(from.getUniqueId())) { Msg.error(p, "One of you is already playing."); return; }
        if (!p.getWorld().equals(from.getWorld()) || p.getLocation().distanceSquared(from.getLocation()) > 32 * 32) {
            Msg.error(p, "You need to be near " + from.getName() + " to play.");
            return;
        }
        Board b = new Board(c.game(), from, p);   // the challenger moves first
        PLAYING.put(from.getUniqueId(), b);
        PLAYING.put(p.getUniqueId(), b);
        b.open();
    }

    static Material signIcon(Games.Sign s) {
        return switch (s) {
            case ROCK -> Material.COBBLESTONE;
            case PAPER -> Material.PAPER;
            case SHEARS -> Material.SHEARS;
        };
    }

    static String gameName(String game) {
        ItemDef d = Sky.items().def(game);
        return d != null && d.name != null ? Text.strip(d.name) : Text.titleCase(game);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        PENDING.remove(u);
        Board b = PLAYING.get(u);
        if (b != null) b.forfeit(u);
        Rps.leave(u);
    }

    // ---- a two-player board ------------------------------------------------------------------------------

    /** One Tic Tac Toe / Four in a Row game. Both players look at the same menu. */
    static final class Board {
        final String game;
        final UUID[] players = new UUID[2];
        final String[] names = new String[2];
        final Games.Grid grid;
        final Menu menu;
        int turn;
        boolean over;

        Board(String game, Player first, Player second) {
            this.game = game;
            players[0] = first.getUniqueId();
            players[1] = second.getUniqueId();
            names[0] = first.getName();
            names[1] = second.getName();
            grid = TTT.equals(game) ? new Games.Grid(3, 3, 3) : new Games.Grid(7, 6, 4);
            menu = new Menu(gameName(game), 6);
            menu.drawer(this::draw);
            menu.onClose(pl -> Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                // closing the board forfeits, unless the game already ended (the end closes it for both)
                if (!over && PLAYING.get(pl.getUniqueId()) == this) forfeit(pl.getUniqueId());
            }));
        }

        void open() {
            for (UUID u : players) {
                Player pl = Bukkit.getPlayer(u);
                if (pl != null) menu.open(pl);
            }
        }

        /** Slot of grid cell (x, y): Tic Tac Toe in the middle 3x3, Four in a Row in columns 1-7 of rows 0-5. */
        int slot(int x, int y) {
            return TTT.equals(game) ? (1 + y) * 9 + 3 + x : y * 9 + 1 + x;
        }

        private void draw(Menu m) {
            m.clear();
            m.fillEmpty();
            for (int x = 0; x < grid.w; x++) for (int y = 0; y < grid.h; y++) {
                int v = grid.at(x, y);
                int cx = x, cy = y;
                ItemStack icon = v == 0 ? Icons.of(Material.WHITE_STAINED_GLASS_PANE, over ? "&7Empty" : "&eClick to play here!")
                        : Icons.of(v == 1 ? Material.RED_CONCRETE : Material.YELLOW_CONCRETE, (v == 1 ? "&c" : "&e") + names[v - 1]);
                m.set(slot(x, y), icon, c -> click(c.player(), cx, cy));
            }
            String who = over ? "&7Game over" : (turn == 0 ? "&c" : "&e") + names[turn] + "&7's turn";
            int info = TTT.equals(game) ? 4 : 0;
            m.set(info, Icons.of(Material.CLOCK, who, "&cRed: &f" + names[0], "&eYellow: &f" + names[1], "",
                    "&7Closing this board forfeits."));
            if (!TTT.equals(game)) m.set(8, Icons.of(Material.OAK_SIGN, "&aFour in a Row", "&7Click any column to drop",
                    "&7a disc. Line up four!"));
        }

        void click(Player p, int x, int y) {
            if (over) return;
            int me = p.getUniqueId().equals(players[0]) ? 0 : p.getUniqueId().equals(players[1]) ? 1 : -1;
            if (me < 0) return;
            if (me != turn) { Msg.error(p, "It is not your turn!"); return; }
            int placedY = TTT.equals(game) ? (grid.at(x, y) == 0 ? y : -1) : grid.dropRow(x);
            if (placedY < 0) return;
            grid.set(x, placedY, me + 1);
            for (UUID u : players) { Player pl = Bukkit.getPlayer(u); if (pl != null) Msg.sound(pl, Sound.BLOCK_STONE_PLACE, 1f); }
            int w = grid.winner();
            if (w > 0) { finish(w - 1); return; }
            if (grid.full()) { finish(-1); return; }
            turn = 1 - turn;
            menu.redraw();
        }

        void forfeit(UUID who) {
            if (over) return;
            int me = who.equals(players[0]) ? 0 : 1;
            for (UUID u : players) { Player pl = Bukkit.getPlayer(u); if (pl != null) Msg.send(pl, "&e" + names[me] + " &7forfeited."); }
            finish(1 - me);
        }

        void finish(int winner) {
            over = true;
            PLAYING.remove(players[0]);
            PLAYING.remove(players[1]);
            menu.redraw();
            for (int i = 0; i < 2; i++) {
                Player pl = Bukkit.getPlayer(players[i]);
                if (pl == null) continue;
                if (winner < 0) Msg.send(pl, "&e" + gameName(game) + ": &7it's a draw!");
                else if (winner == i) { Msg.send(pl, "&a&l" + gameName(game) + ": &aYou won against " + names[1 - i] + "!"); Msg.sound(pl, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f); }
                else Msg.send(pl, "&c" + gameName(game) + ": " + names[winner] + " won.");
            }
            // leave the final board up for a moment, then close it for whoever still looks at it
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                for (UUID u : players) {
                    Player pl = Bukkit.getPlayer(u);
                    if (pl != null && pl.getOpenInventory().getTopInventory().getHolder() == menu) pl.closeInventory();
                }
            }, 60L);
        }
    }

    // ---- Rock Paper Snips tournament ---------------------------------------------------------------------

    /** One tournament at a time per host; a player sits in at most one. */
    static final class Rps {
        private Rps() {}

        static final double RADIUS = 30;
        static final int PICK_SECONDS = 10;
        private static final Map<UUID, Rps.Game> IN = new HashMap<>();

        static final class Game {
            final Set<UUID> alive = new HashSet<>();
            final Map<UUID, Games.Sign> picks = new LinkedHashMap<>();
            final Map<UUID, Menu> menus = new HashMap<>();
            int round;
            BukkitTask timer;
            boolean done;
        }

        static boolean start(Player host) {
            if (IN.containsKey(host.getUniqueId())) { Msg.error(host, "You are already in a tournament."); return false; }
            List<Player> players = new ArrayList<>();
            for (Player o : host.getWorld().getPlayers()) {
                if (o.getLocation().distanceSquared(host.getLocation()) > RADIUS * RADIUS) continue;
                if (o.hasMetadata("NPC") || IN.containsKey(o.getUniqueId()) || PLAYING.containsKey(o.getUniqueId())) continue;
                players.add(o);
            }
            if (players.size() < 2) { Msg.error(host, "You need at least one other player near you to start a tournament!"); return false; }
            Game g = new Game();
            for (Player o : players) { g.alive.add(o.getUniqueId()); IN.put(o.getUniqueId(), g); }
            for (Player o : players) Msg.send(o, "&e" + host.getName() + " &astarted a &eRock Paper Snips &atournament with &e"
                    + players.size() + " &aplayers! The least picked sign wins each round.");
            round(g);
            return true;
        }

        static void round(Game g) {
            if (g.done) return;
            g.round++;
            g.picks.clear();
            boolean classic = g.alive.size() == 2;
            for (UUID u : g.alive) {
                Player pl = Bukkit.getPlayer(u);
                if (pl == null) continue;
                Menu m = new Menu("Round " + g.round + (classic ? " - Final!" : ""), 3);
                m.drawer(mm -> {
                    mm.clear();
                    mm.fillEmpty();
                    Games.Sign mine = g.picks.get(u);
                    int[] slots = {11, 13, 15};
                    Games.Sign[] signs = Games.Sign.values();
                    for (int i = 0; i < 3; i++) {
                        Games.Sign s = signs[i];
                        ItemStack icon = Icons.of(signIcon(s), (s == mine ? "&a" : "&e") + s.label(),
                                classic ? "&7Final round: classic rules." : "&7The least picked sign wins!", "",
                                s == mine ? "&aSelected!" : "&eClick to pick!");
                        if (s == mine) Icons.glow(icon);
                        mm.set(slots[i], icon, c -> { g.picks.put(u, s); mm.redraw(); maybeResolve(g); });
                    }
                    mm.set(4, Icons.of(Material.CLOCK, "&aRound " + g.round, "&7Players left: &e" + g.alive.size(),
                            "&7Pick within &e" + PICK_SECONDS + " seconds&7."));
                });
                g.menus.put(u, m);
                m.open(pl);
            }
            if (g.timer != null) g.timer.cancel();
            g.timer = Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> resolve(g), PICK_SECONDS * 20L);
        }

        static void maybeResolve(Game g) {
            if (g.picks.keySet().containsAll(g.alive)) resolve(g);
        }

        static void resolve(Game g) {
            if (g.done) return;
            if (g.timer != null) { g.timer.cancel(); g.timer = null; }
            Set<UUID> next = Games.rpsRound(g.alive, g.picks);
            List<String> lines = new ArrayList<>();
            for (Map.Entry<UUID, Games.Sign> e : g.picks.entrySet()) {
                Player pl = Bukkit.getPlayer(e.getKey());
                lines.add("&7" + (pl == null ? "?" : pl.getName()) + ": &e" + e.getValue().label());
            }
            Set<UUID> before = new HashSet<>(g.alive);
            g.alive.retainAll(next);
            for (UUID u : before) {
                Player pl = Bukkit.getPlayer(u);
                if (pl == null) continue;
                Msg.send(pl, "&6&lROUND " + g.round + " RESULTS");
                Msg.send(pl, lines);
                if (!g.alive.contains(u)) {
                    Msg.send(pl, "&cYou are out of the tournament.");
                    IN.remove(u);
                    if (pl.getOpenInventory().getTopInventory().getHolder() == g.menus.get(u)) pl.closeInventory();
                }
            }
            if (g.alive.size() <= 1) { end(g, before); return; }
            if (g.alive.size() == before.size()) for (UUID u : g.alive) {
                Player pl = Bukkit.getPlayer(u);
                if (pl != null) Msg.send(pl, "&7Nobody went out - again!");
            }
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> round(g), 30L);
        }

        static void end(Game g, Set<UUID> everyone) {
            g.done = true;
            UUID winner = g.alive.isEmpty() ? null : g.alive.iterator().next();
            Player w = winner == null ? null : Bukkit.getPlayer(winner);
            for (UUID u : everyone) {
                IN.remove(u);
                Player pl = Bukkit.getPlayer(u);
                if (pl == null) continue;
                Msg.send(pl, w == null ? "&7The tournament ended with no winner." : "&a&l" + w.getName() + " &awon the Rock Paper Snips tournament!");
                Menu m = g.menus.get(u);
                if (m != null && pl.getOpenInventory().getTopInventory().getHolder() == m) pl.closeInventory();
            }
            if (w != null) Msg.sound(w, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        }

        static void leave(UUID u) {
            Game g = IN.remove(u);
            if (g == null) return;
            g.alive.remove(u);
            g.picks.remove(u);
            if (g.alive.size() <= 1 && !g.done) {
                Set<UUID> all = new HashSet<>(g.alive);
                all.add(u);
                end(g, all);
            } else maybeResolve(g);
        }
    }
}
