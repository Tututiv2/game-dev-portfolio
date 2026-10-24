package com.auracapes.ui;

import com.auracapes.compat.Compat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shared frame for the Aura sub-screens: the same blurred backdrop as the
 * main menu, a parent to go back to, and Esc meaning "back" rather than
 * "close everything".
 *
 * <p>From the main menu the backdrop is the panorama; from the pause menu it
 * is the world, blurred, with the motes thinner so the world still reads.
 */
public abstract class AuraScreen extends Screen {

    protected final Screen parent;

    protected AuraScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (this.minecraft.level == null) {
            this.extractPanorama(g, a);
        }
    }

    /** Call first in extractRenderState: blur what is behind, then darken it. */
    protected void extractBackdrop(GuiGraphicsExtractor g) {
        if (this.minecraft.options.getMenuBackgroundBlurriness() >= 1.0F) {
            g.blurBeforeThisStratum();
        }
        AuraBackdrop.draw(g, this.width, this.height, this.minecraft.level == null ? 1.0F : 0.7F);
    }

    /** Big title with a short accent underline, centred on {@code cx}. */
    protected void extractHeading(GuiGraphicsExtractor g, String text, int cx, int y) {
        g.centeredText(this.font, text, cx, y, Aura.TEXT);
        int half = Math.max(10, this.font.width(text) / 2);
        Aura.accentBar(g, cx - half, y + 11, cx + half, y + 12, 1.0F);
    }

    @Override
    public void onClose() {
        Compat.setScreen(parent);
    }
}
