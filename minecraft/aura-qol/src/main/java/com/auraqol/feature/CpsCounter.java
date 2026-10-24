package com.auraqol.feature;

import com.auraqol.compat.Mc;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

/**
 * Clicks per second for the left and right mouse button, drawn as one short
 * line ("12 CPS | 3 CPS").
 *
 * <p><b>Why an edge detector and not a "is the button down" poll.</b> The
 * number is only worth anything if it counts click EVENTS. Holding the left
 * button for two seconds is one click, not forty, so this records a timestamp
 * on the <em>rising edge</em> only - the frame where the button went from
 * released to pressed - and never while it stays pressed. Everything else here
 * is bookkeeping around that one rule.
 *
 * <p><b>Why a rolling window and not a per-second bucket.</b> A bucket that
 * resets on a wall-clock second makes the readout jump to 0 and back every
 * second; the number people expect is "how many clicks landed in the last
 * 1000 ms", which is what {@link ClickWindow#countWithin} answers. The buffer
 * keeps newest-first so the scan can stop at the first timestamp that has
 * aged out.
 *
 * <p><b>Why it is sampled twice.</b> Fabric 0.157.0+26.2 ships no general
 * mouse-button event (checked: {@code ScreenMouseEvents} is screen-only and
 * {@code ClientPreAttackCallback} only fires for attacks), and the one true
 * event source, {@code MouseHandler.onButton}, is private and would need a
 * mixin - which this file is deliberately not allowed to add. So the edge is
 * sampled by polling, from the HUD element (once per rendered frame) AND from
 * the client tick (20 Hz floor, so the count keeps working if the HUD is
 * hidden). At 60+ fps that resolves any humanly achievable click rate; a
 * drag-click above ~30 CPS could in principle put a press and its release
 * inside one frame and be missed. If that ever matters, the upgrade is a
 * mixin on {@code MouseHandler.onButton(J...MouseButtonInfo;I)V} feeding
 * {@link #recordClick}, which is public for exactly that reason.
 *
 * <p><b>Verified from the 26.2 jar, and it differed from what I expected:</b>
 * <ul>
 *   <li>{@code GuiGraphics} no longer exists - the HUD draw surface is
 *       {@code net.minecraft.client.gui.GuiGraphicsExtractor}, and the Fabric
 *       HUD hook is {@code HudElementRegistry}/{@code HudElement}, not
 *       {@code HudRenderCallback} or {@code HudLayerRegistrationCallback}
 *       (neither class is in the jar at all).</li>
 *   <li>{@code Minecraft.screen} is GONE. The current screen now lives on
 *       {@code Minecraft.gui} as {@code Gui.screen()} - found by
 *       disassembling {@code MouseHandler.onButton}, which reads it that way.
 *       That is the gate used here so clicking around an inventory does not
 *       inflate a PvP number.</li>
 *   <li>{@code Window.getWindow()} is {@code Window.handle()} in 26.2.</li>
 *   <li>Text and rectangle colours are ARGB ints, so every constant here
 *       carries its own alpha byte.</li>
 * </ul>
 */
public final class CpsCounter {

    /** Namespaced id the HUD element registers under. */
    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "cps_counter");

    // --- position/scale. Local to this class on purpose: HudConfig belongs to
    // another agent and unifying these is a later, deliberate step. ---
    private static final int X = 4;
    private static final int Y = 4;
    private static final float SCALE = 1.0f;

    private static final long WINDOW_MS = 1000L;

    private static final int TEXT_ARGB = 0xFFFFFFFF;
    private static final int BACKDROP_ARGB = 0x60000000;

    private static final ClickWindow LEFT = new ClickWindow();
    private static final ClickWindow RIGHT = new ClickWindow();

    private static boolean leftWasDown;
    private static boolean rightWasDown;

    private CpsCounter() {
    }

    /** Wire-up. Called once from Features. */
    public static void register() {
        // 20 Hz floor: keeps counting even when the HUD is not being drawn.
        ClientTickEvents.END_CLIENT_TICK.register(client -> sample());

        // Per-frame: the real resolution, plus the drawing.
        HudElementRegistry.addLast(ID, (extractor, deltaTracker) -> {
            sample();
            draw(extractor);
        });
    }

    /**
     * Record one click. Public so a future mixin on the real mouse callback can
     * feed this directly and make the poll above redundant.
     *
     * @param right true for the right button, false for the left
     */
    public static void recordClick(boolean right) {
        (right ? RIGHT : LEFT).add(now());
    }

    /** Left-button clicks in the last second. */
    public static int leftCps() {
        return LEFT.countWithin(now(), WINDOW_MS);
    }

    /** Right-button clicks in the last second. */
    public static int rightCps() {
        return RIGHT.countWithin(now(), WINDOW_MS);
    }

    // ------------------------------------------------------------------

    /**
     * One poll of the raw GLFW button state, turned into at most one click per
     * button per call.
     *
     * <p>GLFW is read rather than {@code MouseHandler.isLeftPressed()} because
     * the disassembly of {@code onButton} shows several early returns (a screen
     * consuming the click, an overlay being up, a global key press being
     * handled) before that flag is touched, so it is not a reliable mirror of
     * the hardware. {@code glfwGetMouseButton} is. Both call sites are on the
     * main thread, which is where GLFW state may be read.
     */
    private static void sample() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        Window window = mc.getWindow();
        if (window == null) {
            return;
        }

        long handle = window.handle();
        boolean left = GLFW.glfwGetMouseButton(handle, InputConstants.MOUSE_BUTTON_LEFT) == InputConstants.PRESS;
        boolean right = GLFW.glfwGetMouseButton(handle, InputConstants.MOUSE_BUTTON_RIGHT) == InputConstants.PRESS;

        // Only clicks made at the game itself count. A rising edge that happens
        // while a screen is open, or while the window is not focused, is a
        // menu click and would make the number a lie - but the edge state is
        // still updated, so releasing inside a menu cannot leave a phantom
        // "already down" that swallows the next real click.
        boolean counts = mc.isWindowActive() && Mc.screen(mc) == null;

        if (counts) {
            long t = now();
            if (left && !leftWasDown) {
                LEFT.add(t);
            }
            if (right && !rightWasDown) {
                RIGHT.add(t);
            }
        }

        leftWasDown = left;
        rightWasDown = right;
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || !HudConfig.get().cpsEnabled) {
            return;
        }

        Font font = mc.font;
        String text = leftCps() + " CPS | " + rightCps() + " CPS";

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        // Position now comes from the shared layout so the editor can move
        // it. The old constants stayed as the fallback default in HudLayout.
        float sc = HudLayout.scale(HudLayout.CPS);
        HudLayout.reportSize(HudLayout.CPS, font.width(text) + 6, font.lineHeight + 4);
        pose.translate((float) HudLayout.x(HudLayout.CPS, g.guiWidth()),
                       (float) HudLayout.y(HudLayout.CPS, g.guiHeight()));
        pose.scale(sc, sc);

        // A faint plate so the line stays readable over snow and sand.
        int w = font.width(text);
        g.fill(-2, -2, w + 2, font.lineHeight + 1, BACKDROP_ARGB);
        g.text(font, text, 0, 0, TEXT_ARGB, true);

        pose.popMatrix();
    }

    private static long now() {
        // nanoTime, not currentTimeMillis: the window must not be disturbed by
        // an NTP step or a daylight-saving jump.
        return System.nanoTime() / 1_000_000L;
    }

    /**
     * A fixed-capacity ring of click timestamps, newest first when read.
     *
     * <p>Capacity 256 is far past any real click rate over one second, so the
     * ring never has to grow and never allocates while playing.
     */
    private static final class ClickWindow {
        private static final int CAP = 256;

        private final long[] times = new long[CAP];
        private int head;
        private int size;

        synchronized void add(long millis) {
            times[head] = millis;
            head = (head + 1) % CAP;
            if (size < CAP) {
                size++;
            }
        }

        synchronized int countWithin(long millis, long windowMs) {
            int n = 0;
            for (int i = 1; i <= size; i++) {
                long t = times[(head - i + CAP) % CAP];
                if (millis - t > windowMs) {
                    break; // newest-first, so everything past here is older too
                }
                n++;
            }
            return n;
        }
    }
}
