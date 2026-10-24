package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The phone's games as menus: Snake (9x5 board, arrow keys) and Tic Tac Toe against the phone. */
final class Apps {

    private Apps() {}

    private static final Map<UUID, BukkitTask> SNAKES = new HashMap<>();

    // ---- Snake ---------------------------------------------------------------------------------------------

    static void snake(Player p) {
        stopSnake(p.getUniqueId());
        PhoneGames.Snake g = new PhoneGames.Snake(9, 5, System.nanoTime());
        Menu m = new Menu("Snake", 6);
        boolean[] over = {false};
        drawSnake(m, g);
        m.set(48, Icons.of(Material.ARROW, "&aLeft"), c -> g.turn(PhoneGames.Snake.LEFT));
        m.set(49, Icons.of(Material.ARROW, "&aUp"), c -> g.turn(PhoneGames.Snake.UP));
        m.set(50, Icons.of(Material.ARROW, "&aRight"), c -> g.turn(PhoneGames.Snake.RIGHT));
        m.set(52, Icons.of(Material.ARROW, "&aDown"), c -> g.turn(PhoneGames.Snake.DOWN));
        m.set(45, Icons.of(Material.BARRIER, "&cQuit"), c -> c.player().closeInventory());
        m.onClose(pl -> { over[0] = true; stopSnake(pl.getUniqueId()); });
        m.open(p);
        UUID id = p.getUniqueId();
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            if (!p.isOnline() || over[0]) { stopSnake(id); return; }
            int before = g.score;
            g.step();
            if (g.score > before) p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 0.6f, 1.4f);
            if (g.dead) {
                over[0] = true;
                stopSnake(id);
                p.closeInventory();
                ExtrasData d = X.data(p);
                boolean best = g.score > d.snakeBest;
                if (best) d.snakeBest = g.score;
                Msg.send(p, "&c&lGAME OVER! &7Score: &a" + g.score + (best ? " &6&lNEW BEST!" : " &7(best " + d.snakeBest + ")"));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 0.7f);
                return;
            }
            drawSnake(m, g);
        }, 20L, Math.max(2, XDials.phoneSnakeTicks.get()));
        SNAKES.put(id, t);
    }

    static void stopSnake(UUID id) {
        BukkitTask t = SNAKES.remove(id);
        if (t != null) t.cancel();
    }

    static void stopAll() {
        for (BukkitTask t : SNAKES.values()) t.cancel();
        SNAKES.clear();
    }

    private static void drawSnake(Menu m, PhoneGames.Snake g) {
        Integer head = g.body.peekFirst();
        for (int cell = 0; cell < g.w * g.h; cell++) {
            Material mat;
            if (head != null && cell == head) mat = Material.LIME_CONCRETE;
            else if (g.body.contains(cell)) mat = Material.GREEN_CONCRETE;
            else if (cell == g.food) mat = Material.APPLE;
            else mat = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            m.set(cell, Icons.of(mat, cell == g.food ? "&cApple" : " "));
        }
        m.set(53, Icons.of(Material.GOLD_NUGGET, "&eScore: &a" + g.score));
        m.set(46, Icons.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        m.set(47, Icons.of(Material.BLACK_STAINED_GLASS_PANE, " "));
        m.set(51, Icons.of(Material.BLACK_STAINED_GLASS_PANE, " "));
    }

    // ---- Tic Tac Toe ---------------------------------------------------------------------------------------

    static void tictactoe(Player p) {
        PhoneGames.TicTacToe g = new PhoneGames.TicTacToe(System.nanoTime(), XDials.phoneTttBlunder.get());
        if (Math.random() < 0.5) g.phoneMove();   // who goes first is a coin toss
        drawTtt(p, g).open(p);
    }

    private static final int[] CELLS = {12, 13, 14, 21, 22, 23, 30, 31, 32};

    private static Menu drawTtt(Player p, PhoneGames.TicTacToe g) {
        Menu m = new Menu("Tic Tac Toe", 5).fillEmpty();
        int r = g.result();
        for (int i = 0; i < 9; i++) {
            final int cell = i;
            Material mat = g.cells[i] == 1 ? Material.RED_WOOL : g.cells[i] == 2 ? Material.BLUE_WOOL : Material.WHITE_STAINED_GLASS_PANE;
            String name = g.cells[i] == 1 ? "&cX &7(you)" : g.cells[i] == 2 ? "&9O &7(phone)" : (r == 0 ? "&eClick to play here!" : " ");
            m.set(CELLS[i], Icons.of(mat, name), c -> {
                if (g.result() != 0) return;
                if (!g.play(cell)) return;
                c.player().playSound(c.player().getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 1f, 1f);
                int res = g.result();
                if (res != 0) {
                    ExtrasData d = X.data(c.player());
                    d.tttGames++;
                    if (res == 1) d.tttWins++;
                    Msg.send(c.player(), res == 1 ? "&a&lYOU WIN! &7Wins: &a" + d.tttWins : res == 2 ? "&c&lYOU LOSE! &7The phone wins this one." : "&e&lDRAW!");
                }
                drawTtt(c.player(), g).open(c.player());
            });
        }
        if (r != 0) m.set(40, Icons.of(Material.LIME_DYE, "&aPlay again", "&eClick to play!"), c -> tictactoe(c.player()));
        m.set(36, Icons.back("Phone"), c -> ExtrasModule.phone.open(c.player()));
        return m;
    }
}
