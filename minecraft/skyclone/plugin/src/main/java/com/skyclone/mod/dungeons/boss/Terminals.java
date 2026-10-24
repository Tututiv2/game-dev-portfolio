package com.skyclone.mod.dungeons.boss;

import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The six terminal mini-games of the Wither Lords' third chamber (research 04 sec 3.13, "Terminals"): chest-GUI
 * puzzles any player may do. Each {@link #open} rolls one type; {@code onSolve} runs once when it is solved.
 * Wrong answers on the "pick the right items" terminals close it (open it again for a new board).
 */
public final class Terminals {

    private Terminals() {}

    public static final String[] TYPES = {"panes", "colours", "order", "letter", "colour", "melody"};
    private static final Random RNG = new Random();

    public static void open(Player p, Runnable onSolve) {
        open(p, TYPES[RNG.nextInt(TYPES.length)], onSolve);
    }

    public static void open(Player p, String type, Runnable onSolve) {
        switch (type) {
            case "panes" -> panes(p, onSolve);
            case "colours" -> colours(p, onSolve);
            case "order" -> order(p, onSolve);
            case "letter" -> letter(p, onSolve);
            case "colour" -> colour(p, onSolve);
            default -> melody(p, onSolve);
        }
    }

    private static void solved(Player p, Runnable onSolve, boolean[] done) {
        if (done[0]) return;
        done[0] = true;
        p.closeInventory();
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        onSolve.run();
    }

    private static ItemStack pane(Material m, String name) { return Icons.of(m, name); }

    // ---- "Correct all the panes!" --------------------------------------------------------------------------
    static void panes(Player p, Runnable onSolve) {
        Menu m = new Menu("Correct all the panes!", 5).chrome();
        boolean[] red = new boolean[15];
        int reds = 0;
        for (int i = 0; i < 15; i++) { red[i] = RNG.nextInt(100) < 55; if (red[i]) reds++; }
        if (reds == 0) red[RNG.nextInt(15)] = true;
        boolean[] done = {false};
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            for (int i = 0; i < 15; i++) {
                int slot = (1 + i / 5) * 9 + 2 + i % 5;
                final int idx = i;
                m.set(slot, red[i] ? pane(Material.RED_STAINED_GLASS_PANE, "&cFix me") : pane(Material.LIME_STAINED_GLASS_PANE, "&aCorrect"), c -> {
                    if (!red[idx]) return;
                    red[idx] = false;
                    Msg.sound(c.player(), Sound.UI_BUTTON_CLICK, 1.4f);
                    draw[0].run();
                    for (boolean b : red) if (b) return;
                    solved(c.player(), onSolve, done);
                });
            }
        };
        draw[0].run();
        m.open(p);
    }

    // ---- "Change all to same color!" -------------------------------------------------------------------------
    static final Material[] CYCLE = {Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE};

    static void colours(Player p, Runnable onSolve) {
        Menu m = new Menu("Change all to same color!", 5).chrome();
        int[] col = new int[9];
        for (int i = 0; i < 9; i++) col[i] = RNG.nextInt(5);
        if (allSame(col)) col[0] = (col[0] + 2) % 5;
        boolean[] done = {false};
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            for (int i = 0; i < 9; i++) {
                int slot = (1 + i / 3) * 9 + 3 + i % 3;
                final int idx = i;
                m.set(slot, pane(CYCLE[col[i]], "&eClick to change &8(right-click: back)"), c -> {
                    col[idx] = (col[idx] + (c.right() ? 4 : 1)) % 5;
                    Msg.sound(c.player(), Sound.UI_BUTTON_CLICK, 1.2f);
                    draw[0].run();
                    if (allSame(col)) solved(c.player(), onSolve, done);
                });
            }
        };
        draw[0].run();
        m.open(p);
    }

    static boolean allSame(int[] a) { for (int v : a) if (v != a[0]) return false; return true; }

    // ---- "Click in order!" ---------------------------------------------------------------------------------
    static void order(Player p, Runnable onSolve) {
        Menu m = new Menu("Click in order!", 4).chrome();
        List<Integer> nums = new ArrayList<>();
        for (int i = 1; i <= 14; i++) nums.add(i);
        Collections.shuffle(nums, RNG);
        int[] next = {1};
        boolean[] done = {false};
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            for (int i = 0; i < 14; i++) {
                int slot = (1 + i / 7) * 9 + 1 + i % 7;
                int n = nums.get(i);
                boolean ok = n < next[0];
                ItemStack icon = Icons.amount(pane(ok ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE, (ok ? "&a" : "&c") + n), n);
                m.set(slot, icon, c -> {
                    if (n != next[0]) { Msg.sound(c.player(), Sound.ENTITY_VILLAGER_NO, 1f); return; }
                    next[0]++;
                    Msg.sound(c.player(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f + n * 0.08f);
                    draw[0].run();
                    if (next[0] > 14) solved(c.player(), onSolve, done);
                });
            }
        };
        draw[0].run();
        m.open(p);
    }

    // ---- "What starts with: 'X'?" ---------------------------------------------------------------------------
    static final Material[] WORDS = {Material.APPLE, Material.ARROW, Material.ANVIL, Material.BREAD, Material.BONE, Material.BOOK, Material.BUCKET,
            Material.CARROT, Material.CLOCK, Material.COMPASS, Material.CAKE, Material.DIAMOND, Material.DIRT, Material.EGG, Material.EMERALD,
            Material.FEATHER, Material.FLINT, Material.GLASS, Material.GOLD_INGOT, Material.HOPPER, Material.IRON_INGOT, Material.LANTERN,
            Material.LEATHER, Material.MELON_SLICE, Material.PAPER, Material.POTATO, Material.PUMPKIN, Material.REDSTONE, Material.SADDLE,
            Material.SNOWBALL, Material.STICK, Material.STRING, Material.SUGAR, Material.TNT, Material.TORCH, Material.WHEAT, Material.BRICK,
            Material.SHEARS, Material.CHEST, Material.COAL};

    static String word(Material mat) { return Text.titleCase(mat.name()); }

    static void letter(Player p, Runnable onSolve) {
        List<Material> pool = new ArrayList<>(List.of(WORDS));
        Collections.shuffle(pool, RNG);
        List<Material> board = pool.subList(0, 21);
        char letter = word(board.get(RNG.nextInt(board.size()))).charAt(0);
        Menu m = new Menu("What starts with: '" + letter + "'?", 5).chrome();
        boolean[] picked = new boolean[21];
        boolean[] done = {false};
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            for (int i = 0; i < 21; i++) {
                int slot = (1 + i / 7) * 9 + 1 + i % 7;
                final int idx = i;
                Material mat = board.get(i);
                ItemStack icon = Icons.of(mat, "&f" + word(mat));
                if (picked[i]) Icons.glow(icon);
                m.set(slot, icon, c -> {
                    if (picked[idx]) return;
                    if (Character.toUpperCase(word(mat).charAt(0)) != letter) {
                        Msg.error(c.player(), "Wrong item! The terminal resets.");
                        c.player().closeInventory();
                        return;
                    }
                    picked[idx] = true;
                    Msg.sound(c.player(), Sound.UI_BUTTON_CLICK, 1.4f);
                    draw[0].run();
                    for (int j = 0; j < 21; j++) if (!picked[j] && Character.toUpperCase(word(board.get(j)).charAt(0)) == letter) return;
                    solved(c.player(), onSolve, done);
                });
            }
        };
        draw[0].run();
        m.open(p);
    }

    // ---- "Select all the COLOUR items!" ---------------------------------------------------------------------
    static final String[] COLOURS = {"RED", "LIME", "BLUE", "YELLOW", "ORANGE", "PURPLE"};
    static final String[] SUFFIX = {"_WOOL", "_STAINED_GLASS", "_CONCRETE", "_TERRACOTTA", "_DYE", "_CARPET"};

    static void colour(Player p, Runnable onSolve) {
        String target = COLOURS[RNG.nextInt(COLOURS.length)];
        Menu m = new Menu("Select all the " + target.toLowerCase(Locale.ROOT).replace("lime", "green") + " items!", 5).chrome();
        Material[] board = new Material[21];
        int hits = 0;
        for (int i = 0; i < 21; i++) {
            String col = RNG.nextInt(3) == 0 ? target : COLOURS[RNG.nextInt(COLOURS.length)];
            Material mat = Material.matchMaterial(col + SUFFIX[RNG.nextInt(SUFFIX.length)]);
            board[i] = mat == null ? Material.STONE : mat;
            if (board[i].name().startsWith(target + "_")) hits++;
        }
        if (hits == 0) board[0] = Material.matchMaterial(target + "_WOOL");
        boolean[] picked = new boolean[21];
        boolean[] done = {false};
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            for (int i = 0; i < 21; i++) {
                int slot = (1 + i / 7) * 9 + 1 + i % 7;
                final int idx = i;
                ItemStack icon = Icons.of(board[i], "&f" + Text.titleCase(board[i].name()));
                if (picked[i]) Icons.glow(icon);
                m.set(slot, icon, c -> {
                    if (picked[idx]) return;
                    if (!board[idx].name().startsWith(target + "_")) {
                        Msg.error(c.player(), "Wrong colour! The terminal resets.");
                        c.player().closeInventory();
                        return;
                    }
                    picked[idx] = true;
                    Msg.sound(c.player(), Sound.UI_BUTTON_CLICK, 1.4f);
                    draw[0].run();
                    for (int j = 0; j < 21; j++) if (!picked[j] && board[j].name().startsWith(target + "_")) return;
                    solved(c.player(), onSolve, done);
                });
            }
        };
        draw[0].run();
        m.open(p);
    }

    // ---- "Click the button on time!" (melody) ----------------------------------------------------------------
    static void melody(Player p, Runnable onSolve) {
        Menu m = new Menu("Click the button on time!", 6).chrome();
        int target = 1 + RNG.nextInt(5);
        int[] row = {0}, pos = {0}, dir = {1};
        boolean[] done = {false};
        m.refreshEvery(6);
        m.drawer(menu -> {
            if (done[0]) return;
            pos[0] += dir[0];
            if (pos[0] >= 4) dir[0] = -1;
            if (pos[0] <= 0) dir[0] = 1;
            for (int col = 1; col <= 5; col++) menu.set(col, Icons.of(col == target ? Material.MAGENTA_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE, col == target ? "&dHere!" : " "));
            for (int r = 0; r < 4; r++) {
                for (int col = 1; col <= 5; col++) {
                    Material mat = r < row[0] ? Material.LIME_STAINED_GLASS_PANE : r == row[0] && col - 1 == pos[0] ? Material.LIME_STAINED_GLASS_PANE
                            : r == row[0] ? Material.RED_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
                    menu.set((r + 1) * 9 + col, Icons.of(mat, " "));
                }
                final int rr = r;
                menu.set((r + 1) * 9 + 7, Icons.of(r == row[0] ? Material.LIME_TERRACOTTA : Material.RED_TERRACOTTA, r == row[0] ? "&aClick when it lines up!" : "&7Wait"), c -> {
                    if (rr != row[0] || done[0]) return;
                    if (pos[0] + 1 == target) {
                        row[0]++;
                        Msg.sound(c.player(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f + row[0] * 0.2f);
                        if (row[0] >= 4) solved(c.player(), onSolve, done);
                    } else Msg.sound(c.player(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
                });
            }
        });
        m.open(p);
    }
}
