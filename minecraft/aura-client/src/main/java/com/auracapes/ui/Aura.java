package com.auracapes.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

/**
 * The look, in one place: colours and the handful of shapes every Aura
 * screen is built from.
 *
 * <p>Everything here is a {@code fill}. There is no rounded-rectangle
 * primitive in the GUI renderer and a 2px corner reads as rounded at every
 * GUI scale, so corners are made by leaving the corner pixels out.
 */
public final class Aura {

    public static final int VIOLET = 0xFF8B5CF6;
    public static final int CYAN = 0xFF22D3EE;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int MUTED = 0xFFA1A1B5;
    public static final int FAINT = 0xFF6B6B80;
    public static final int GOOD = 0xFF4ADE80;
    public static final int BAD = 0xFFF87171;

    /** Glass panels: dark, a little blue, see-through enough for the blur. */
    public static final int PANEL = 0xB80E0E18;
    public static final int PANEL_HI = 0xD81C1C2E;
    public static final int EDGE = 0x30FFFFFF;

    private Aura() {
    }

    /** Seconds since start, for animation. */
    public static float time() {
        return (Util.getMillis() % 3_600_000L) / 1000.0F;
    }

    /** {@code argb} with its alpha multiplied by {@code a} (0..1). */
    public static int fade(int argb, float a) {
        int alpha = (int) (((argb >>> 24) & 0xFF) * clamp01(a));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    public static int lerp(int c0, int c1, float t) {
        t = clamp01(t);
        int a = (int) (((c0 >>> 24) & 0xFF) + (((c1 >>> 24) & 0xFF) - ((c0 >>> 24) & 0xFF)) * t);
        int r = (int) (((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) - ((c0 >> 16) & 0xFF)) * t);
        int g = (int) (((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) - ((c0 >> 8) & 0xFF)) * t);
        int b = (int) ((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** The accent at position t along the violet-to-cyan ramp. */
    public static int accent(float t) {
        return lerp(VIOLET, CYAN, t);
    }

    public static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** Filled rectangle with 2px-cut corners. */
    public static void round(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int col) {
        if (x1 - x0 < 4 || y1 - y0 < 4) {
            g.fill(x0, y0, x1, y1, col);
            return;
        }
        g.fill(x0 + 2, y0, x1 - 2, y1, col);
        g.fill(x0 + 1, y0 + 1, x0 + 2, y1 - 1, col);
        g.fill(x1 - 2, y0 + 1, x1 - 1, y1 - 1, col);
        g.fill(x0, y0 + 2, x0 + 1, y1 - 2, col);
        g.fill(x1 - 1, y0 + 2, x1, y1 - 2, col);
    }

    /** 1px outline that follows the same cut corners as {@link #round}. */
    public static void outline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int col) {
        g.fill(x0 + 2, y0, x1 - 2, y0 + 1, col);
        g.fill(x0 + 2, y1 - 1, x1 - 2, y1, col);
        g.fill(x0, y0 + 2, x0 + 1, y1 - 2, col);
        g.fill(x1 - 1, y0 + 2, x1, y1 - 2, col);
        g.fill(x0 + 1, y0 + 1, x0 + 2, y0 + 2, col);
        g.fill(x1 - 2, y0 + 1, x1 - 1, y0 + 2, col);
        g.fill(x0 + 1, y1 - 2, x0 + 2, y1 - 1, col);
        g.fill(x1 - 2, y1 - 2, x1 - 1, y1 - 1, col);
    }

    /** A glass panel: fill, hairline edge. */
    public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float alpha) {
        round(g, x0, y0, x1, y1, fade(PANEL, alpha));
        outline(g, x0, y0, x1, y1, fade(EDGE, alpha));
    }

    /**
     * A horizontal violet-to-cyan bar. fillGradient only runs top to bottom,
     * so a left-to-right ramp is drawn as short segments; at 2px per step the
     * banding is below what the eye picks out.
     */
    public static void accentBar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float alpha) {
        int w = x1 - x0;
        if (w <= 0) {
            return;
        }
        for (int x = x0; x < x1; x += 2) {
            int col = fade(accent((x - x0) / (float) w), alpha);
            g.fill(x, y0, Math.min(x + 2, x1), y1, col);
        }
    }

    /**
     * Darkens toward the left and right edges, so the eye is pulled to the
     * middle where the buttons are. Vertical strips again, for the same
     * reason as {@link #accentBar}.
     */
    public static void vignette(GuiGraphicsExtractor g, int w, int h, float strength) {
        int band = Math.max(40, w / 4);
        int steps = 20;
        for (int i = 0; i < steps; i++) {
            float t = 1.0F - i / (float) steps;
            int col = fade(0xFF000000, t * t * strength);
            int x0 = band * i / steps;
            int x1 = band * (i + 1) / steps;
            g.fill(x0, 0, x1, h, col);
            g.fill(w - x1, 0, w - x0, h, col);
        }
    }

    /** True when (mx,my) is inside the rectangle. */
    public static boolean hits(double mx, double my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }
}
