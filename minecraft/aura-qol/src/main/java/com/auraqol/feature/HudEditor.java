package com.auraqol.feature;

import com.auraqol.compat.Mc;
import com.auraqol.AuraQoL;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Drag the HUD where you want it. Opened with a key, saved when you close it.
 *
 * <p>Bound to <b>]</b> by default rather than Right Shift, which Lunar Client
 * already uses for the same job - two mods fighting over one key is the kind
 * of collision a person experiences as "it randomly does nothing". It is a
 * normal key mapping, so it can be rebound in Controls like anything else.
 *
 * <p>The screen deliberately does NOT pause the game: the HUD shows live
 * numbers, and half of placing a CPS counter is watching it move while you
 * click. {@link #isPauseScreen()} returns false for exactly that reason.
 *
 * <p>Nothing here knows how big any element is. Each feature reports the size
 * it last drew at through {@link HudLayout#reportSize}, which is the only way
 * the box can stay correct as the info panel grows a line or the coordinates
 * get longer.
 */
public final class HudEditor extends Screen {

    private static KeyMapping key;

    private String held;
    private int grabDx;
    private int grabDy;

    /** Package-private so the /mods library can open it ("Edit HUD Layout"). */
    HudEditor() {
        super(Component.literal("Edit HUD"));
    }

    public static void register() {
        if (key != null) {
            return;
        }
        HudLayout.load();
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.auraqol.edithud",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                KeyCategory.get()));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // consumeClick drains the queue, so a held key opens the screen
            // once rather than every tick.
            while (key.consumeClick()) {
                // `Minecraft.screen` and `setScreen` are gone in 26.2 - the
                // current screen lives on the public `gui` field as
                // Gui.screen() / Gui.setScreen(). Same discovery the CPS
                // counter had to make to tell "a menu is open" from "you are
                // clicking in the world".
                if (mc.player != null && Mc.screen(mc) == null) {
                    Mc.setScreen(mc, new HudEditor());
                }
            }
        });
        AuraQoL.LOG.info("[auraqol] HUD editor on ] (rebindable in Controls)");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The elements this editor offers right now (Hypixel vs Shadow Prison sets, see HudLayout.editorShows). */
    private static java.util.List<String> shown() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String id : HudLayout.ids()) {
            if (HudLayout.editorShows(id)) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        int w = g.guiWidth();
        int h = g.guiHeight();

        // A wash rather than the usual dark menu blur: the point of this
        // screen is to see the real HUD underneath while you move it.
        g.fill(0, 0, w, h, 0x50101018);

        for (String id : shown()) {
            int[] wh = HudLayout.size(id);
            float sc = HudLayout.scale(id);
            int bw = Math.round(wh[0] * sc);
            int bh = Math.round(wh[1] * sc);
            int x = HudLayout.x(id, w);
            int y = HudLayout.y(id, h);
            boolean hot = id.equals(held)
                    || (mouseX >= x && mouseX <= x + bw && mouseY >= y && mouseY <= y + bh);
            boolean on = HudLayout.enabled(id);

            int edge = hot ? 0xFFE0B01E : on ? 0x88FFFFFF : 0x88FF6060;
            g.fill(x, y, x + bw, y + 1, edge);
            g.fill(x, y + bh - 1, x + bw, y + bh, edge);
            g.fill(x, y, x + 1, y + bh, edge);
            g.fill(x + bw - 1, y, x + bw, y + bh, edge);
            g.fill(x + 1, y + 1, x + bw - 1, y + bh - 1,
                    hot ? 0x30E0B01E : on ? 0x18FFFFFF : 0x40300000);
            String label = on ? HudLayout.label(id) : HudLayout.label(id) + " (off)";
            g.text(this.font, label, x + 3, y + 3,
                   hot ? 0xFFFFFFFF : on ? 0xFFBBBBBB : 0xFFFF9090);
        }

        String hint = "Drag to move  -  scroll to resize  -  right-click hides/shows  -  R resets  -  Esc saves";
        g.text(this.font, hint, (w - this.font.width(hint)) / 2, h - 18, 0xFFE0E0E0);

        int[] b = slotsButton(w);
        boolean over = mouseX >= b[0] && mouseX < b[2] && mouseY >= b[1] && mouseY < b[3];
        g.fill(b[0], b[1], b[2], b[3], over ? 0xFF4C3A8C : 0xFF2E2650);
        g.centeredText(this.font, "Save Slots", (b[0] + b[2]) / 2, b[1] + 5, 0xFFFFFFFF);
    }

    /** {x0, y0, x1, y1} of the "Save Slots" button, top centre. */
    private int[] slotsButton(int w) {
        int bw = this.font.width("Save Slots") + 20;
        return new int[]{(w - bw) / 2, 6, (w + bw) / 2, 24};
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        int[] b = slotsButton(this.width);
        if (e.button() == 0 && e.x() >= b[0] && e.x() < b[2] && e.y() >= b[1] && e.y() < b[3]) {
            HudLayout.save();   // what you just arranged is what "Save" will store
            Mc.setScreen(this.minecraft, new SlotsScreen(this));
            return true;
        }
        if (e.button() == 1) {
            // Right-click: switch the element off or back on. Off elements
            // keep their box here (red) so they can be found and restored.
            for (String id : shown()) {
                int[] wh = HudLayout.size(id);
                float sc = HudLayout.scale(id);
                int x = HudLayout.x(id, this.width);
                int y = HudLayout.y(id, this.height);
                if (e.x() >= x && e.x() <= x + wh[0] * sc
                        && e.y() >= y && e.y() <= y + wh[1] * sc) {
                    HudLayout.toggle(id);
                    return true;
                }
            }
        }
        if (e.button() == 0) {
            int w = this.width;
            int h = this.height;
            for (String id : shown()) {
                int[] wh = HudLayout.size(id);
                float sc = HudLayout.scale(id);
                int x = HudLayout.x(id, w);
                int y = HudLayout.y(id, h);
                if (e.x() >= x && e.x() <= x + wh[0] * sc
                        && e.y() >= y && e.y() <= y + wh[1] * sc) {
                    held = id;
                    // Remember where inside the box you grabbed it, or the
                    // element jumps so its corner meets the cursor.
                    grabDx = (int) e.x() - x;
                    grabDy = (int) e.y() - y;
                    return true;
                }
            }
        }
        return super.mouseClicked(e, dbl);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (held != null) {
            HudLayout.move(held, (int) e.x() - grabDx, (int) e.y() - grabDy,
                    this.width, this.height);
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        held = null;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        for (String id : shown()) {
            int[] wh = HudLayout.size(id);
            float sc = HudLayout.scale(id);
            int x = HudLayout.x(id, this.width);
            int y = HudLayout.y(id, this.height);
            if (mx >= x && mx <= x + wh[0] * sc && my >= y && my <= y + wh[1] * sc) {
                HudLayout.setScale(id, sc + (float) sy * 0.1f);
                return true;
            }
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == GLFW.GLFW_KEY_R) {
            for (String id : shown()) {
                HudLayout.setScale(id, 1.0f);
            }
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        HudLayout.save();
        super.onClose();
    }
}
