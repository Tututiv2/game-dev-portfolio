package com.auraqol.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.auraqol.AuraQoL;
import com.auraqol.skyblock.TodoList;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * The to-do list panel and its file.
 *
 * <p>Saved to {@code config/auraqol-todo.json} after every change (it is a
 * few hundred bytes; there is no reason to batch). Checklists to import live
 * one per file in {@code config/auraqol-todo/}, and {@code /todo load <name>}
 * appends the ones not already on the list - format in {@link TodoList}.
 *
 * <p>Done items stay on screen struck through and dimmed rather than
 * disappearing, because a daily list you can see being worked through is the
 * point of having one on the HUD. {@code /todo clear done} sweeps them.
 */
public final class TodoHud {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "todo");

    /** Text column cap, unscaled. Longer items end in "..."; /todo list shows them whole. */
    private static final int MAX_TEXT_W = 170;

    private static final TodoList LIST = new TodoList();
    private static boolean loggedFailure;

    private TodoHud() {
    }

    public static void register() {
        load();
        HudElementRegistry.addLast(ID, (g, delta) -> {
            try {
                draw(g);
            } catch (Throwable t) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    AuraQoL.LOG.warn("[auraqol] to-do panel failed once (further errors silenced)", t);
                }
            }
        });
    }

    public static TodoList list() {
        return LIST;
    }

    // ------------------------------------------------------------ files

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("auraqol-todo.json");
    }

    /** Where importable checklists live. Created on first use so the owner can find it. */
    public static Path listsDir() {
        Path d = FabricLoader.getInstance().getConfigDir().resolve("auraqol-todo");
        try {
            if (!Files.isDirectory(d)) {
                Files.createDirectories(d);
                Files.writeString(d.resolve("example.json"), """
                        {
                          "_comment": "A checklist for /todo load example. Plain strings are not-done items; objects can set done.",
                          "items": [
                            "Collect minions",
                            "Claim bazaar orders",
                            {"text": "Daily: visit the Garden", "done": false}
                          ]
                        }
                        """, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] could not create {}: {}", d, e.toString());
        }
        return d;
    }

    private static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            List<TodoList.Item> items = TodoList.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (items == null) {
                // Keep the unreadable file for the owner to rescue rather than
                // overwriting it with an empty list on the next save.
                Path bad = f.resolveSibling("auraqol-todo.json.unreadable");
                Files.copy(f, bad, StandardCopyOption.REPLACE_EXISTING);
                AuraQoL.LOG.warn("[auraqol] to-do file unreadable, copied to {} and starting empty", bad);
                return;
            }
            LIST.replaceWith(items);
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] could not read to-do list: {}", e.toString());
        }
    }

    public static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, LIST.toJson(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] could not save to-do list: {}", e.toString());
        }
    }

    /** Names (without .json) of the importable checklists. */
    public static List<String> checklistNames() {
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(listsDir(), "*.json")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - 5));
            }
        } catch (IOException | RuntimeException ignored) {
            // no folder, no names
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Read one checklist by name. The name is resolved INSIDE the lists folder
     * only - "../../something" is refused, so a typed command cannot read an
     * arbitrary file off the disk.
     *
     * @return the items, or null with the reason in {@code why[0]}
     */
    public static List<TodoList.Item> readChecklist(String name, String[] why) {
        String n = name.toLowerCase(Locale.ROOT).endsWith(".json") ? name : name + ".json";
        Path dir = listsDir().toAbsolutePath().normalize();
        Path f = dir.resolve(n).normalize();
        if (!f.startsWith(dir) || !f.getParent().equals(dir)) {
            why[0] = "Checklist names are plain file names inside config/auraqol-todo/.";
            return null;
        }
        if (!Files.isRegularFile(f)) {
            why[0] = "No checklist called " + name + " in config/auraqol-todo/.";
            return null;
        }
        try {
            List<TodoList.Item> items = TodoList.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (items == null) {
                why[0] = n + " is not valid JSON in either accepted shape (see example.json).";
            }
            return items;
        } catch (IOException | RuntimeException e) {
            why[0] = "Could not read " + n + ": " + e.getMessage();
            return null;
        }
    }

    // ------------------------------------------------------------ draw

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();
        if (mc == null || mc.player == null || !cfg.todoEnabled) {
            return;
        }
        Font font = mc.font;
        List<TodoList.Item> items = LIST.items();
        int total = items.size();
        int done = 0;
        for (TodoList.Item i : items) {
            if (i.done()) {
                done++;
            }
        }

        String title = "To-do";
        String count = total == 0 ? "" : done + "/" + total;
        int lh = font.lineHeight + 2;
        int numW = font.width(Integer.toString(Math.max(1, total))) + 3;
        int boxW = 9;

        // Visible rows: done ones only if the owner wants them kept.
        List<int[]> rows = new ArrayList<>(); // {index}
        int textW = 0;
        for (int i = 0; i < total; i++) {
            TodoList.Item it = items.get(i);
            if (it.done() && !cfg.todoShowDone) {
                continue;
            }
            rows.add(new int[]{i});
            textW = Math.max(textW, Math.min(MAX_TEXT_W, font.width(it.text())));
        }
        String empty = total == 0 ? "Nothing yet - /todo add <task>"
                : rows.isEmpty() ? "All done" : null;

        int headerW = font.width(title) + 12 + font.width(count);
        int rowsW = rows.isEmpty() ? font.width(empty) : numW + boxW + textW;
        int innerW = Math.max(90, Math.max(headerW, rowsW));
        int w = innerW + HudStyle.PAD * 2;
        int h = HudStyle.PAD + lh + 3 + Math.max(1, rows.size()) * lh + HudStyle.PAD - 2;

        float sc = HudLayout.scale(HudLayout.TODO);
        HudLayout.reportSize(HudLayout.TODO, w, h);
        int[] p = HudLayout.place(HudLayout.TODO, g.guiWidth(), g.guiHeight());

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) p[0], (float) p[1]);
            pose.scale(sc, sc);
            HudStyle.panel(g, 0, 0, w, h);
            int x = HudStyle.PAD;
            int y = HudStyle.PAD;
            g.text(font, title, x, y, HudStyle.ACCENT);
            if (!count.isEmpty()) {
                int cc = done == total ? HudStyle.GAIN : HudStyle.DIM;
                g.text(font, count, w - HudStyle.PAD - font.width(count), y, cc);
            }
            y += lh;
            HudStyle.rule(g, x, y, innerW);
            y += 3;

            if (rows.isEmpty()) {
                g.text(font, empty, x, y, total == 0 ? HudStyle.FAINT : HudStyle.GAIN);
                return;
            }
            for (int[] row : rows) {
                int idx = row[0];
                TodoList.Item it = items.get(idx);
                String n = Integer.toString(idx + 1);
                g.text(font, n, x + numW - 3 - font.width(n), y, HudStyle.FAINT);
                checkbox(g, x + numW, y, it.done());
                String txt = HudStyle.ellipsize(font, it.text(), MAX_TEXT_W);
                if (it.done()) {
                    Component c = Component.literal(txt).setStyle(Style.EMPTY.withStrikethrough(true));
                    g.text(font, c, x + numW + boxW, y, HudStyle.FAINT);
                } else {
                    g.text(font, txt, x + numW + boxW, y, cfg.textColor);
                }
                y += lh;
            }
        } finally {
            pose.popMatrix();
        }
    }

    /** 7x7 box; done = filled with the accent and a 1px inner gap, which reads as "ticked" at this size. */
    private static void checkbox(GuiGraphicsExtractor g, int x, int y, boolean done) {
        int edge = done ? HudStyle.ACCENT : 0xFF9A9A9A;
        g.fill(x, y, x + 7, y + 1, edge);
        g.fill(x, y + 6, x + 7, y + 7, edge);
        g.fill(x, y + 1, x + 1, y + 6, edge);
        g.fill(x + 6, y + 1, x + 7, y + 6, edge);
        if (done) {
            g.fill(x + 2, y + 2, x + 5, y + 5, HudStyle.ACCENT);
        }
    }
}
