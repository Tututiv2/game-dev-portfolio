package com.auracapes.ui;

import java.util.Random;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

/**
 * The darkening and the rising motes that sit between the blurred panorama
 * and the menu.
 *
 * <p>The motes are static state on purpose: every Aura screen shares one
 * field of them, so going Title -> Accounts -> back does not restart the
 * drift and the background reads as one continuous place.
 */
public final class AuraBackdrop {

    private static final int COUNT = 70;
    private static final float[] X = new float[COUNT];
    private static final float[] Y = new float[COUNT];
    private static final float[] SPEED = new float[COUNT];
    private static final float[] PHASE = new float[COUNT];
    private static final int[] SIZE = new int[COUNT];
    private static boolean seeded;
    private static long lastFrame;

    private AuraBackdrop() {
    }

    /**
     * Mote positions are kept in 0..1 of the screen, so a resize or a GUI
     * scale change moves them with the window instead of bunching them in a
     * corner.
     */
    private static void seed() {
        Random r = new Random(7);
        for (int i = 0; i < COUNT; i++) {
            X[i] = r.nextFloat();
            Y[i] = r.nextFloat();
            SPEED[i] = 0.012F + r.nextFloat() * 0.035F;
            PHASE[i] = r.nextFloat() * 6.283F;
            SIZE[i] = r.nextFloat() < 0.2F ? 2 : 1;
        }
        seeded = true;
    }

    public static void draw(GuiGraphicsExtractor g, int w, int h, float alpha) {
        if (!seeded) {
            seed();
        }
        long now = Util.getMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float t = Aura.time();

        // Darken: light at the top where the logo is, heavy at the foot.
        // Light enough that the blurred panorama still reads as a place.
        g.fillGradient(0, 0, w, h, Aura.fade(0x38060610, alpha), Aura.fade(0xA0040408, alpha));
        Aura.vignette(g, w, h, 0.45F * alpha);

        for (int i = 0; i < COUNT; i++) {
            Y[i] -= SPEED[i] * dt;
            if (Y[i] < -0.02F) {
                Y[i] = 1.02F;
            }
            float sway = (float) Math.sin(t * 0.6F + PHASE[i]) * 0.006F;
            int px = (int) ((X[i] + sway) * w);
            int py = (int) (Y[i] * h);
            // Fade in from the floor and out toward the ceiling, and twinkle.
            float life = Aura.clamp01(Y[i] * 3.0F) * Aura.clamp01((1.0F - Y[i]) * 4.0F);
            float twinkle = 0.55F + 0.45F * (float) Math.sin(t * 2.2F + PHASE[i] * 3.0F);
            float a = alpha * life * twinkle * (SIZE[i] == 2 ? 0.55F : 0.4F);
            int col = Aura.fade(Aura.accent(X[i]), a);
            g.fill(px, py, px + SIZE[i], py + SIZE[i], col);
        }
    }
}
