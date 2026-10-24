package com.auraqol.feature;

import com.auraqol.compat.Mc;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Five save slots for the HUD setup (positions, sizes and which features are on). Same look as the
 * /mods library. Load applies straight away; nothing needs a restart.
 */
public final class SlotsScreen extends Screen {

    private static final int ROW_H = 30;
    private static final int BTN_W = 52;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int FAINT = 0xFF6B6B80;
    private static final int ACCENT = 0xFF8B5CF6;

    private final Screen parent;
    private String status = "";
    private int px0;
    private int px1;
    private int py0;
    private int py1;

    public SlotsScreen(Screen parent) {
        super(Component.literal("Save Slots"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int pw = Math.min(300, this.width - 20);
        int ph = 46 + LayoutSlots.COUNT * ROW_H + 26;
        px0 = (this.width - pw) / 2;
        px1 = px0 + pw;
        py0 = Math.max(8, (this.height - ph) / 2);
        py1 = py0 + ph;
    }

    private int rowY(int slot) {
        return py0 + 40 + (slot - 1) * ROW_H;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, this.width, this.height, 0x90000008);
        g.fill(px0, py0, px1, py1, 0xE00C0C14);
        g.fill(px0, py0, px1, py0 + 1, 0x40FFFFFF);
        g.text(this.font, "SAVE SLOTS", px0 + 14, py0 + 12, TEXT);
        g.fill(px0 + 14, py0 + 23, px0 + 14 + this.font.width("SAVE SLOTS"), py0 + 24, ACCENT);
        String sub = "Your HUD layout + which mods are on";
        g.text(this.font, sub, px1 - 14 - this.font.width(sub), py0 + 12, FAINT);

        for (int slot = 1; slot <= LayoutSlots.COUNT; slot++) {
            int y = rowY(slot);
            boolean used = LayoutSlots.used(slot);
            g.fill(px0 + 10, y, px1 - 10, y + ROW_H - 4, 0xC0161622);
            g.text(this.font, "Slot " + slot, px0 + 18, y + 4, TEXT);
            g.text(this.font, LayoutSlots.describe(slot), px0 + 18, y + 14, used ? 0xFFA1A1B5 : FAINT);
            button(g, saveX(), y + 5, "Save", 0xFF2E2650, 0xFF4C3A8C, mouseX, mouseY, true);
            button(g, loadX(), y + 5, "Load", 0xFF1F7A45, 0xFF25B863, mouseX, mouseY, used);
        }
        if (!status.isEmpty()) {
            g.centeredText(this.font, status, (px0 + px1) / 2, py1 - 16, 0xFF4ADE80);
        }
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private int saveX() {
        return px1 - 18 - 2 * BTN_W - 6;
    }

    private int loadX() {
        return px1 - 18 - BTN_W;
    }

    private void button(GuiGraphicsExtractor g, int x, int y, String label, int col, int hot,
                        int mx, int my, boolean enabled) {
        boolean over = enabled && hit(mx, my, x, y, x + BTN_W, y + 16);
        g.fill(x, y, x + BTN_W, y + 16, enabled ? (over ? hot : col) : 0xFF26262E);
        g.centeredText(this.font, label, x + BTN_W / 2, y + 4, enabled ? TEXT : FAINT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        for (int slot = 1; slot <= LayoutSlots.COUNT; slot++) {
            int y = rowY(slot) + 5;
            if (hit(e.x(), e.y(), saveX(), y, saveX() + BTN_W, y + 16)) {
                status = LayoutSlots.save(slot);
                return true;
            }
            if (LayoutSlots.used(slot) && hit(e.x(), e.y(), loadX(), y, loadX() + BTN_W, y + 16)) {
                status = LayoutSlots.load(slot);
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Mc.setScreen(this.minecraft, parent);
    }

    private static boolean hit(double mx, double my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }
}
