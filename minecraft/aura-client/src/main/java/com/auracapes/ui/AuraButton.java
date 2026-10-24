package com.auracapes.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * A flat glass button whose highlight eases in rather than snapping.
 *
 * <p>The easing is the whole difference between this and vanilla's button:
 * the same hover, but over ~80ms, with an accent line that grows out from the
 * centre. It is frame-rate independent - the step is scaled by real elapsed
 * time - so it feels the same at 30fps and 240.
 */
public class AuraButton extends AbstractButton {

    public enum Style {
        /** The big ones: Singleplayer, Multiplayer. */
        PRIMARY,
        /** Smaller row buttons. */
        SECONDARY,
        /** Red on hover, for Quit and Remove. */
        DANGER
    }

    private final Runnable action;
    private final Style style;
    private float hover;
    private long lastFrame;

    public AuraButton(int x, int y, int w, int h, Component label, Style style, Runnable action) {
        super(x, y, w, h, label);
        this.style = style;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float target = this.active && this.isHovered() ? 1.0F : 0.0F;
        hover += (target - hover) * Math.min(1.0F, dt * 14.0F);

        float a = this.alpha;
        int x0 = getX();
        int y0 = getY();
        int x1 = getRight();
        int y1 = getBottom();

        int base = style == Style.PRIMARY ? 0xC0141422 : 0xA8121220;
        int hot = style == Style.DANGER ? 0xE0401820 : 0xE0262640;
        Aura.round(g, x0, y0, x1, y1, Aura.fade(Aura.lerp(base, hot, hover), a));
        int edge = style == Style.DANGER
                ? Aura.lerp(Aura.EDGE, 0x80F87171, hover)
                : Aura.lerp(Aura.EDGE, 0x70A78BFA, hover);
        Aura.outline(g, x0, y0, x1, y1, Aura.fade(edge, a));

        // Accent line along the bottom, growing out from the middle.
        if (hover > 0.02F && style != Style.DANGER) {
            int half = (int) ((x1 - x0 - 6) / 2.0F * hover);
            int cx = (x0 + x1) / 2;
            Aura.accentBar(g, cx - half, y1 - 2, cx + half, y1 - 1, a * hover);
        }

        int text;
        if (!this.active) {
            text = Aura.FAINT;
        } else if (style == Style.DANGER) {
            text = Aura.lerp(Aura.TEXT, Aura.BAD, hover);
        } else {
            text = Aura.lerp(0xFFE4E4F0, Aura.TEXT, hover);
        }
        // Below ~4/255 alpha the text renderer can treat the colour as opaque,
        // which would flash the label at full white on the first fade frame.
        if (a > 0.04F) {
            var font = Minecraft.getInstance().font;
            int ty = y0 + (y1 - y0 - 8) / 2;
            g.centeredText(font, getMessage(), (x0 + x1) / 2, ty, Aura.fade(text, a));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
