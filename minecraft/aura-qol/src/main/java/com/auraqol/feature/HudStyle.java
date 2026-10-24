package com.auraqol.feature;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The look shared by the SkyBlock panels (blocks/s, money, to-do), so three
 * features written at once read as one product instead of three.
 *
 * <p>Backing colour and the on/off switch for it come from {@link HudConfig},
 * the same values the info panel uses - change the background there and every
 * panel follows. Corners are stepped in 2px exactly like the keystroke boxes,
 * because {@code GuiGraphicsExtractor} still has no rounded primitive.
 *
 * <p>The accent is the gold the HUD editor already highlights with; value
 * colours follow Hypixel's own (§6 gold for coins, §a green for gains, §c red
 * for losses) so a number means the same thing here as it does in the game's
 * own chat.
 */
final class HudStyle {

    static final int ACCENT = 0xFFE0B01E;
    static final int COIN = 0xFFFFAA00;
    static final int GAIN = 0xFF55FF55;
    static final int LOSS = 0xFFFF5555;
    static final int DIM = 0xFF8A8A8A;
    static final int FAINT = 0xFF5E5E5E;
    static final int IDLE = 0xFFB0B0B0;
    static final int WARN = 0xFFFFAA00;
    /** Running abilities: Hypixel's aqua. */
    static final int ACTIVE = 0xFF55FFFF;
    /** Unfilled part of a progress bar. */
    static final int TRACK = 0x40FFFFFF;

    /** Gap between the panel edge and its text. */
    static final int PAD = 4;

    private HudStyle() {
    }

    /** The backing plate, if backgrounds are on. Coordinates are in the element's own (scaled) space. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        HudConfig c = HudConfig.get();
        if (!c.background) {
            return;
        }
        roundRect(g, x, y, w, h, c.backgroundColor);
    }

    /** A 1px accent rule under a panel title. Short on purpose: an underline, not a divider. */
    static void rule(GuiGraphicsExtractor g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, (ACCENT & 0x00FFFFFF) | 0x90000000);
    }

    /** Small filled status dot, 3x3 with the corners knocked off by a 1px cross. */
    static void dot(GuiGraphicsExtractor g, int x, int y, int argb) {
        g.fill(x + 1, y, x + 3, y + 4, argb);
        g.fill(x, y + 1, x + 4, y + 3, argb);
    }

    /** A thin progress bar: track, then the filled fraction (0..1) on top. */
    static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double frac, int argb) {
        g.fill(x, y, x + w, y + h, TRACK);
        int fw = (int) Math.round(w * Math.max(0, Math.min(1, frac)));
        if (fw > 0) {
            g.fill(x, y, x + fw, y + h, argb);
        }
    }

    /** Countdown text: "45s", "1m 05s" - rounded UP, so it never says 0s while still waiting. */
    static String countdown(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        if (s >= 60) {
            return (s / 60) + "m " + String.format(java.util.Locale.ROOT, "%02d", s % 60) + "s";
        }
        return s + "s";
    }

    /** Dev only (gradlew runClient -Pdemo): context panels draw their sample when they have no data. */
    private static final boolean DEMO = Boolean.getBoolean("auraqol.demo");

    /**
     * True while the HUD editor is open: context panels (commissions, slayer,
     * pickaxe ability) then draw a sample so they can be found and placed even
     * where they would be hidden.
     */
    static boolean editing() {
        if (DEMO) {
            return true;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && com.auraqol.compat.Mc.screen(mc) instanceof HudEditor;
    }

    static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        int c = 2;
        if (w < 2 * c || h < 2 * c) {
            g.fill(x, y, x + w, y + h, argb);
            return;
        }
        g.fill(x + c, y, x + w - c, y + h, argb);
        g.fill(x, y + c, x + c, y + h - c, argb);
        g.fill(x + w - c, y + c, x + w, y + h - c, argb);
        // the 1px diagonal that makes a 2px step read as a curve, not a notch
        g.fill(x + 1, y + 1, x + c, y + c, argb);
        g.fill(x + w - c, y + 1, x + w - 1, y + c, argb);
        g.fill(x + 1, y + h - c, x + c, y + h - 1, argb);
        g.fill(x + w - c, y + h - c, x + w - 1, y + h - 1, argb);
    }

    /** Cut a string to {@code maxW} pixels, ending in "..." when it had to be cut. */
    static String ellipsize(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) {
            return s;
        }
        String dots = "...";
        int dw = font.width(dots);
        String out = s;
        while (!out.isEmpty() && font.width(out) + dw > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out.stripTrailing() + dots;
    }
}
