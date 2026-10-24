package com.auraqol.feature;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Hold C to narrow the field of view, the way OptiFine's zoom does.
 *
 * <p><b>Where the FOV actually lives in 26.2.</b> There is no
 * {@code GameRenderer.getFov} any more - the whole projection moved onto
 * {@link net.minecraft.client.Camera}, which computes it once a frame in the
 * private {@code calculateFov(float)} and caches the result in its {@code fov}
 * field. That one method feeds the projection matrix, the cull frustum and
 * {@code getFov()} alike, so scaling its return value is the single point where
 * a zoom stays consistent with what is actually culled. Its sibling
 * {@code calculateHudFov} is deliberately left alone: the HUD is drawn with its
 * own 70 degree projection and must not move when you zoom.
 *
 * <p>Scaling the RETURN rather than the raw option value is also what keeps the
 * nausea/death/fluid warp ({@code modifyFovBasedOnDeathOrFluid}, applied inside
 * {@code calculateFov}) proportional instead of overwritten.
 *
 * <p><b>Why the smoothing is time-based and not tick-based.</b> This is asked
 * for from the render thread, once per frame, and a per-frame constant step
 * would make the zoom snap on a 240 Hz machine and crawl on a 30 Hz one - the
 * feel of the feature would be a function of hardware. So it approaches the
 * target exponentially against a measured wall-clock delta,
 * {@code 1 - exp(-dt/TAU)}, which is frame-rate independent by construction.
 * The delta is clamped because {@code calculateFov} is not called while a screen
 * is open or the game is paused: without the clamp the first frame back would
 * carry minutes of dt and the zoom would teleport.
 *
 * <p>There is deliberately no tick-event subscription and no state machine.
 * {@code KeyMapping.isDown()} is maintained by the game itself and is already
 * released for us whenever a screen opens ({@code KeyMapping.releaseAll}), so
 * typing "c" into chat cannot leave the world stuck zoomed.
 *
 * <p><b>Not done:</b> mouse sensitivity is NOT reduced while zoomed, which
 * OptiFine does. That needs a hook in {@code MouseHandler} and this feature was
 * scoped to two files; at 4x the view is noticeably twitchy without it.
 */
public final class Zoom {

    /** 0.25 of the configured FOV, i.e. a 4x zoom - 70 degrees becomes 17.5. */
    private static final float ZOOMED_SCALE = 0.25f;

    /** Exponential time constant, seconds. ~3*TAU to settle, so ~150 ms. */
    private static final double TAU_SECONDS = 0.05;

    /** Longest frame delta the smoothing will believe. Covers pause/menus. */
    private static final long MAX_STEP_NANOS = 100_000_000L;

    /** Below this the approach is over; snapping stops a permanent epsilon. */
    private static final float SNAP_EPSILON = 1.0e-4f;

    private static KeyMapping key;
    private static float scale = 1.0f;
    private static long lastCallNanos;

    private Zoom() {
    }

    public static void register() {
        // Idempotent on purpose: KeyMapping.Category.register THROWS
        // IllegalArgumentException on an id already in SORT_ORDER, so a second
        // call would blow up before the key mapping was ever created and leave
        // the feature half-registered.
        if (key != null) {
            return;
        }
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.auraqol.zoom", InputConstants.Type.KEYSYM, InputConstants.KEY_C,
                KeyCategory.get()));
        lastCallNanos = System.nanoTime();
    }

    /**
     * The multiplier to apply to this frame's field of view. Also advances the
     * smoothing, so it must be called exactly once per frame - which is what
     * {@code Camera.calculateFov} is.
     *
     * @return 1.0 when not zooming at all, so the caller can skip the write.
     */
    public static float fovScale() {
        if (key == null) {
            // register() never ran, or threw. Vanilla FOV, no side effects.
            return 1.0f;
        }
        long now = System.nanoTime();
        long deltaNanos = Math.min(Math.max(now - lastCallNanos, 0L), MAX_STEP_NANOS);
        lastCallNanos = now;

        float target = key.isDown() && HudConfig.get().zoomEnabled ? ZOOMED_SCALE : 1.0f;
        if (Math.abs(target - scale) < SNAP_EPSILON) {
            scale = target;
        } else {
            double alpha = 1.0 - Math.exp(-(deltaNanos / 1.0e9) / TAU_SECONDS);
            scale += (float) ((target - scale) * alpha);
        }
        return scale;
    }
}
