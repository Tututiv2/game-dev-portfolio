package com.auraqol.feature;

import com.auraqol.compat.Mc;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * The Lunar-style keystrokes block: movement, both mouse buttons and jump,
 * drawn as rounded boxes that light up while held.
 *
 * <pre>
 *         [W]
 *      [A][S][D]
 *      [LMB][RMB]
 *        [SPACE]
 * </pre>
 *
 * <p><b>Why nothing here is a scancode.</b> Every box reads a
 * {@link KeyMapping} out of {@link Options} - {@code keyUp}, {@code keyLeft},
 * {@code keyDown}, {@code keyRight}, {@code keyAttack}, {@code keyUse},
 * {@code keyJump} - and asks it two things: {@code isDown()} for the lit
 * state, and {@code getTranslatedKeyMessage()} for the label. So rebinding
 * movement to ESDF, or jump to a mouse button, changes both what lights up and
 * what is written on it, with nothing in this file to update. Hard-coding
 * {@code GLFW_KEY_W} would have made the display quietly wrong for anyone who
 * rebinds - which is exactly the person who most wants a keystrokes overlay.
 *
 * <p><b>The label rule.</b> A mouse-bound action is shown the way players say
 * it - LMB / RMB / MMB - rather than as Minecraft's own "Left Button", which
 * does not fit a 25px box. That is detected, not assumed:
 * {@code KeyMapping.matches(InputConstants.Key)} is public, so each mapping is
 * asked whether it matches {@code Type.MOUSE.getOrCreate(button)} for buttons
 * 0..7. Anything else falls back to the translated key name, trimmed by
 * {@link #fit} until it fits its box. ({@code KeyMapping.getKey()} does not
 * exist - the {@code key} field is protected - which is why matching is the
 * route rather than reading the binding out.)
 *
 * <p><b>Verified from the 26.2 jar, and it differed from what I expected:</b>
 * <ul>
 *   <li>{@code GuiGraphics} is gone; the HUD draw surface is
 *       {@code GuiGraphicsExtractor} and the Fabric hook is
 *       {@code HudElementRegistry.addLast(Identifier, HudElement)} where
 *       {@code HudElement} has the single method
 *       {@code extractRenderState(GuiGraphicsExtractor, DeltaTracker)}.
 *       Neither {@code HudRenderCallback} nor
 *       {@code HudLayerRegistrationCallback} exists in fabric-api
 *       0.157.0+26.2.</li>
 *   <li>The pose is a {@code org.joml.Matrix3x2fStack} (2D now, not a
 *       {@code PoseStack}); {@code pushMatrix}/{@code popMatrix} and the
 *       inherited {@code translate}/{@code scale} are what it offers.</li>
 *   <li>There is no rounded-rectangle primitive. {@code fill} is the only
 *       shape, so a "rounded" box is three overlapping fills with the corners
 *       stepped in by 2px - see {@link #roundRect}.</li>
 * </ul>
 */
public final class Keystrokes {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "keystrokes");

    // --- geometry, in unscaled local units (the pose scales the whole block) ---
    private static final int KEY = 16;   // a WASD box
    private static final int GAP = 2;
    private static final int WIDE = 25;  // LMB / RMB
    private static final int BAR_H = 10; // the space bar
    private static final int CORNER = 2;

    /** Full block: three 16px boxes plus two gaps = 52 wide. */
    private static final int BLOCK_W = KEY * 3 + GAP * 2;
    /** W row + WASD row + mouse row + space row. */
    private static final int BLOCK_H = KEY + GAP + KEY + GAP + KEY + GAP + BAR_H;

    // --- position/scale. Local to this class on purpose: HudConfig belongs to
    // another agent and unifying these is a later, deliberate step. ---
    private static final int MARGIN_X = 6;
    private static final int MARGIN_BOTTOM = 40;
    private static final float SCALE = 1.0f;

    // ARGB. The held state inverts rather than tinting, because at 16px a tint
    // is not legible at a glance and an inversion is.
    private static final int IDLE_EDGE = 0x90000000;
    private static final int IDLE_FILL = 0x60000000;
    private static final int IDLE_TEXT = 0xFFFFFFFF;
    private static final int HELD_EDGE = 0xFFFFFFFF;
    private static final int HELD_FILL = 0xE6FFFFFF;
    private static final int HELD_TEXT = 0xFF101010;

    private Keystrokes() {
    }

    /** Wire-up. Called once from Features. */
    public static void register() {
        HudElementRegistry.addLast(ID, (extractor, deltaTracker) -> draw(extractor));
    }

    // ------------------------------------------------------------------

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.options == null || !HudConfig.get().keysEnabled) {
            return;
        }

        Options o = mc.options;
        Font font = mc.font;

        // From the shared layout, so the editor can drag it.
        float sc = HudLayout.scale(HudLayout.KEYS);
        int baseX = HudLayout.x(HudLayout.KEYS, g.guiWidth());
        int baseY = HudLayout.y(HudLayout.KEYS, g.guiHeight());
        HudLayout.reportSize(HudLayout.KEYS, BLOCK_W, BLOCK_H);

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) baseX, (float) baseY);
        pose.scale(sc, sc);

        int rowW = 0;
        int rowA = KEY + GAP;
        int rowM = rowA + KEY + GAP;
        int rowS = rowM + KEY + GAP;

        // [W]
        box(g, font, KEY + GAP, rowW, KEY, KEY, keyLabel(o.keyUp, KEY - 4, font), o.keyUp.isDown());

        // [A][S][D]
        box(g, font, 0, rowA, KEY, KEY, keyLabel(o.keyLeft, KEY - 4, font), o.keyLeft.isDown());
        box(g, font, KEY + GAP, rowA, KEY, KEY, keyLabel(o.keyDown, KEY - 4, font), o.keyDown.isDown());
        box(g, font, (KEY + GAP) * 2, rowA, KEY, KEY, keyLabel(o.keyRight, KEY - 4, font), o.keyRight.isDown());

        // [LMB][RMB]
        box(g, font, 0, rowM, WIDE, KEY, keyLabel(o.keyAttack, WIDE - 4, font), o.keyAttack.isDown());
        box(g, font, WIDE + GAP, rowM, WIDE, KEY, keyLabel(o.keyUse, WIDE - 4, font), o.keyUse.isDown());

        // [ SPACE ]
        box(g, font, 0, rowS, BLOCK_W, BAR_H, keyLabel(o.keyJump, BLOCK_W - 4, font), o.keyJump.isDown());

        pose.popMatrix();
    }

    /** One rounded key box with its label centred inside it. */
    private static void box(GuiGraphicsExtractor g, Font font,
                            int x, int y, int w, int h, String label, boolean held) {
        roundRect(g, x, y, w, h, held ? HELD_EDGE : IDLE_EDGE);
        roundRect(g, x + 1, y + 1, w - 2, h - 2, held ? HELD_FILL : IDLE_FILL);

        int textY = y + (h - font.lineHeight) / 2 + 1;
        g.centeredText(font, label, x + w / 2, textY, held ? HELD_TEXT : IDLE_TEXT);
    }

    /**
     * A rectangle with its four corners stepped in by {@link #CORNER} pixels.
     *
     * <p>Three fills: the full-height middle column, then the left and right
     * slabs shortened at top and bottom. At this size that reads as rounded and
     * costs three quads - a real arc would cost a texture or a pipeline, and
     * {@code GuiGraphicsExtractor} offers neither as a primitive.
     */
    private static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        g.fill(x + CORNER, y, x + w - CORNER, y + h, argb);
        g.fill(x, y + CORNER, x + CORNER, y + h - CORNER, argb);
        g.fill(x + w - CORNER, y + CORNER, x + w, y + h - CORNER, argb);
    }

    /**
     * What to write on a key: the players' name for a mouse button, otherwise
     * the game's own translated key name, trimmed to fit.
     */
    private static String keyLabel(KeyMapping mapping, int maxWidth, Font font) {
        String mouse = mouseLabel(mapping);
        if (mouse != null) {
            return fit(font, mouse, maxWidth);
        }
        return fit(font, mapping.getTranslatedKeyMessage().getString(), maxWidth);
    }

    /**
     * "LMB"/"RMB"/"MMB"/"MB4".. if this mapping is bound to a mouse button,
     * else null.
     *
     * <p>Asked rather than read: {@code KeyMapping} exposes no public getter
     * for its current key in 26.2, but {@code matches(InputConstants.Key)} is
     * public, so the eight mouse buttons are simply offered to it.
     */
    private static String mouseLabel(KeyMapping mapping) {
        for (int button = 0; button <= 7; button++) {
            if (Mc.boundToMouse(mapping, button)) {
                return switch (button) {
                    case InputConstants.MOUSE_BUTTON_LEFT -> "LMB";
                    case InputConstants.MOUSE_BUTTON_RIGHT -> "RMB";
                    case InputConstants.MOUSE_BUTTON_MIDDLE -> "MMB";
                    default -> "MB" + (button + 1);
                };
            }
        }
        return null;
    }

    /** Trim from the right until the string fits, so a long binding degrades instead of overflowing. */
    private static String fit(Font font, String s, int maxWidth) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String out = s;
        while (out.length() > 1 && font.width(out) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
