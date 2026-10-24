package com.auraqol.feature;

import java.util.List;

import com.auraqol.compat.Mc;
import com.auraqol.hotkeys.Hotkey;
import com.auraqol.hotkeys.HotkeyCodec;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The chat hotkey editor: one row per hotkey - its key, its text, on/off,
 * delete. "+ Add hotkey" makes a row and goes straight to "press a key", the
 * way Lunar's does, so adding one is: click, press the key, type the text.
 *
 * <p>Looks like {@link ModsLibrary} on purpose; it is opened from there.
 *
 * <p>The rows are drawn by the screen, and only the text boxes are widgets.
 * On scroll, add or delete the widgets are rebuilt from the list, so the list
 * (not the widgets) is the truth and a rebuild can never lose typing. The list
 * is written to disk on every add/delete/toggle/rebind and when the screen
 * closes - which is when typed text is saved.
 */
public final class HotkeysScreen extends Screen {

    private static final int ACCENT = 0xFF8B5CF6;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFA1A1B5;
    private static final int FAINT = 0xFF6B6B80;
    private static final int WARN = 0xFFF5A524;
    private static final int ON = 0xFF1F9D55;
    private static final int OFF = 0xFF3A2A33;

    private static final int ROW = 24;
    private static final int KEY_W = 92;

    private final Screen parent;
    private final List<Hotkey> model;
    private int scroll;
    /** Row waiting for a key press, or -1. */
    private int capturing = -1;

    private int px0;
    private int px1;
    private int py0;
    private int py1;
    private int rowsTop;
    private int rowsBottom;

    public HotkeysScreen(Screen parent) {
        super(Component.literal("Chat Hotkeys"));
        this.parent = parent;
        this.model = ChatHotkeys.list();
    }

    // ------------------------------------------------------------ layout

    private int rowsFit() {
        return Math.max(1, (rowsBottom - rowsTop) / ROW);
    }

    private int rowY(int i) {
        return rowsTop + (i - scroll) * ROW;
    }

    private boolean rowShown(int i) {
        return i >= scroll && i < scroll + rowsFit() && i < model.size();
    }

    private int boxX() {
        return px0 + 14 + KEY_W + 6;
    }

    private int boxW() {
        return px1 - 14 - 28 - 4 - 16 - 6 - boxX();
    }

    private int toggleX() {
        return boxX() + boxW() + 6;
    }

    private int deleteX() {
        return toggleX() + 28 + 4;
    }

    @Override
    protected void init() {
        int pw = Math.min(400, this.width - 20);
        px0 = (this.width - pw) / 2;
        px1 = px0 + pw;
        py0 = 10;
        py1 = this.height - 10;
        rowsTop = py0 + 48;
        rowsBottom = py1 - 48;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, model.size() - rowsFit())));

        for (int i = 0; i < model.size(); i++) {
            if (!rowShown(i)) {
                continue;
            }
            final int index = i;
            EditBox box = new EditBox(this.font, boxX(), rowY(i) + 1, boxW(), 18, Component.literal("Text"));
            box.setMaxLength(256);
            box.setValue(model.get(i).text());
            box.setHint(Component.literal("Text, or /command").withColor(FAINT));
            box.setResponder(s -> {
                if (index < model.size()) {
                    model.set(index, model.get(index).withText(s));
                }
            });
            addRenderableWidget(box);
        }
    }

    private void save() {
        ChatHotkeys.set(model);
    }

    private void rebuild() {
        save();
        rebuildWidgets();
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, this.width, this.height, 0x90000008);
        g.fill(px0, py0, px1, py1, 0xE00C0C14);
        g.fill(px0, py0, px1, py0 + 1, 0x40FFFFFF);
        g.text(this.font, "CHAT HOTKEYS", px0 + 14, py0 + 12, TEXT);
        g.fill(px0 + 14, py0 + 23, px0 + 14 + this.font.width("CHAT HOTKEYS"), py0 + 24, ACCENT);
        g.text(this.font, "Press the key in game to send the text. Start with / to run a command.",
                px0 + 14, py0 + 31, FAINT, false);

        // Master switch, same state as the /mods card and /aq toggle hotkeys.
        boolean master = HudConfig.get().hotkeysEnabled;
        int mx0 = px1 - 14 - 64;
        boolean mHover = hit(mouseX, mouseY, mx0, py0 + 9, px1 - 14, py0 + 23);
        g.fill(mx0, py0 + 9, px1 - 14, py0 + 23, master ? (mHover ? 0xFF25B863 : ON) : (mHover ? 0xFF4A3440 : OFF));
        g.centeredText(this.font, master ? "ENABLED" : "DISABLED", mx0 + 32, py0 + 12, master ? TEXT : MUTED);

        String status = null;
        int statusColor = FAINT;

        if (model.isEmpty()) {
            g.centeredText(this.font, "No hotkeys yet.", (px0 + px1) / 2, rowsTop + 14, MUTED);
            g.centeredText(this.font, "Click \"+ Add hotkey\", press a key, then type what it should say.",
                    (px0 + px1) / 2, rowsTop + 28, FAINT);
        }

        for (int i = 0; i < model.size(); i++) {
            if (!rowShown(i)) {
                continue;
            }
            Hotkey h = model.get(i);
            int y = rowY(i);
            boolean rowHover = hit(mouseX, mouseY, px0 + 10, y, px1 - 10, y + ROW - 2);
            if (rowHover) {
                g.fill(px0 + 10, y - 2, px1 - 10, y + ROW - 2, 0x18FFFFFF);
            }

            // Key button.
            int kx = px0 + 14;
            boolean cap = capturing == i;
            boolean kHover = hit(mouseX, mouseY, kx, y + 1, kx + KEY_W, y + 19);
            String conflict = ChatHotkeys.conflict(h);
            int shared = sharedWith(i);
            g.fill(kx, y + 1, kx + KEY_W, y + 19, cap ? 0xFF4C3A8C : (kHover ? 0xFF2E2E46 : 0xFF1C1C2C));
            g.fill(kx, y + 18, kx + KEY_W, y + 19, cap ? ACCENT : 0x30FFFFFF);
            String label = cap ? "press a key..." : ChatHotkeys.keyName(h);
            label = this.font.plainSubstrByWidth(label, KEY_W - 8);
            int labelColor = cap ? TEXT : (!h.bound() ? FAINT : (conflict != null ? WARN : TEXT));
            g.centeredText(this.font, label, kx + KEY_W / 2, y + 6, labelColor);

            // On/off.
            int tx = toggleX();
            boolean tHover = hit(mouseX, mouseY, tx, y + 1, tx + 28, y + 19);
            g.fill(tx, y + 1, tx + 28, y + 19, h.enabled() ? (tHover ? 0xFF25B863 : ON) : (tHover ? 0xFF4A3440 : OFF));
            g.centeredText(this.font, h.enabled() ? "ON" : "OFF", tx + 14, y + 6, h.enabled() ? TEXT : MUTED);

            // Delete.
            int dx = deleteX();
            boolean dHover = hit(mouseX, mouseY, dx, y + 1, dx + 16, y + 19);
            g.centeredText(this.font, "\u2715", dx + 8, y + 6, dHover ? 0xFFF87171 : FAINT);

            if (rowHover && !cap) {
                if (conflict != null) {
                    status = ChatHotkeys.keyName(h) + " is also \"" + conflict + "\" in Controls - both will happen.";
                    statusColor = WARN;
                } else if (shared >= 0) {
                    status = "Same key as row " + (shared + 1) + " - both send, top one first.";
                    statusColor = MUTED;
                } else if (dHover) {
                    status = "Delete this hotkey.";
                }
            }
        }

        if (model.size() > rowsFit()) {
            String more = (scroll + 1) + "-" + Math.min(model.size(), scroll + rowsFit()) + " of " + model.size()
                    + "  (scroll)";
            g.text(this.font, more, px1 - 14 - this.font.width(more), rowsBottom + 2, FAINT, false);
        }

        if (capturing >= 0) {
            status = "Press a key or mouse 3/4/5 (Ctrl/Shift/Alt ok). Esc cancels, Backspace clears.";
            statusColor = 0xFFC4B5FD;
        } else if (status == null) {
            status = master ? "Fires only in game, never while chat or a menu is open."
                    : "Chat Hotkeys are DISABLED - nothing will fire until you switch them on (top right).";
            statusColor = master ? FAINT : WARN;
        }
        g.text(this.font, this.font.plainSubstrByWidth(status, px1 - px0 - 28), px0 + 14, py1 - 40, statusColor, false);

        // Bottom buttons.
        boolean full = model.size() >= HotkeyCodec.MAX_HOTKEYS;
        drawButton(g, px0 + 14, py1 - 26, 110, "+ Add hotkey", hit(mouseX, mouseY, px0 + 14, py1 - 26, px0 + 124, py1 - 8),
                !full);
        drawButton(g, px1 - 14 - 70, py1 - 26, 70, "Done", hit(mouseX, mouseY, px1 - 84, py1 - 26, px1 - 14, py1 - 8),
                true);

        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private void drawButton(GuiGraphicsExtractor g, int x, int y, int w, String label, boolean hover, boolean active) {
        g.fill(x, y, x + w, y + 18, !active ? 0xFF1A1A24 : (hover ? 0xFF4C3A8C : 0xFF2E2650));
        g.centeredText(this.font, label, x + w / 2, y + 5, active ? TEXT : FAINT);
    }

    /** Another row with exactly the same key, or -1. */
    private int sharedWith(int i) {
        Hotkey h = model.get(i);
        for (int j = 0; j < model.size(); j++) {
            if (j != i && model.get(j).sameInput(h)) {
                return j;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturing >= 0 && capturing < model.size()) {
            int k = event.key();
            if (k == InputConstants.KEY_ESCAPE) {
                capturing = -1;
                return true;
            }
            if (k == InputConstants.KEY_BACKSPACE || k == InputConstants.KEY_DELETE) {
                model.set(capturing, model.get(capturing).unbound());
                capturing = -1;
                save();
                return true;
            }
            if (isModifier(k)) {
                // Wait for the real key; Ctrl+G is recorded when G goes down with Ctrl held.
                return true;
            }
            model.set(capturing, model.get(capturing).withInput(false, k, event.modifiers()));
            capturing = -1;
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    private static boolean isModifier(int key) {
        return key >= InputConstants.KEY_LSHIFT && key <= 347; // L/R Shift, Ctrl, Alt, Super
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x();
        double my = e.y();

        if (capturing >= 0 && capturing < model.size()) {
            // Middle / 4 / 5 become the binding. Left and right are what you click
            // the menu with, so binding them would make every click a chat line.
            if (e.button() >= 2) {
                model.set(capturing, model.get(capturing).withInput(true, e.button(), e.modifiers()));
                capturing = -1;
                save();
                return true;
            }
            int y = rowY(capturing);
            int kx = px0 + 14;
            if (hit(mx, my, kx, y + 1, kx + KEY_W, y + 19)) {
                return true; // clicked the same key button again: keep waiting
            }
            capturing = -1; // any other click cancels and then does what it would have done
        }

        int mx0 = px1 - 14 - 64;
        if (hit(mx, my, mx0, py0 + 9, px1 - 14, py0 + 23)) {
            AuraCommands.toggle("hotkeys");
            return true;
        }

        for (int i = 0; i < model.size(); i++) {
            if (!rowShown(i)) {
                continue;
            }
            int y = rowY(i);
            if (hit(mx, my, px0 + 14, y + 1, px0 + 14 + KEY_W, y + 19)) {
                capturing = i;
                setFocused(null);
                return true;
            }
            if (hit(mx, my, toggleX(), y + 1, toggleX() + 28, y + 19)) {
                model.set(i, model.get(i).withEnabled(!model.get(i).enabled()));
                save();
                return true;
            }
            if (hit(mx, my, deleteX(), y + 1, deleteX() + 16, y + 19)) {
                model.remove(i);
                rebuild();
                return true;
            }
        }

        if (hit(mx, my, px0 + 14, py1 - 26, px0 + 124, py1 - 8) && model.size() < HotkeyCodec.MAX_HOTKEYS) {
            model.add(Hotkey.blank());
            scroll = Math.max(0, model.size() - rowsFit());
            rebuild();
            capturing = model.size() - 1;
            return true;
        }
        if (hit(mx, my, px1 - 84, py1 - 26, px1 - 14, py1 - 8)) {
            onClose();
            return true;
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        int before = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(sy), Math.max(0, model.size() - rowsFit())));
        if (scroll != before) {
            capturing = -1;
            rebuild();
        }
        return true;
    }

    @Override
    public void removed() {
        // Typed text is only in the model until here; this is its save.
        save();
        super.removed();
    }

    @Override
    public void onClose() {
        Mc.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean hit(double mx, double my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }
}
