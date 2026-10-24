package com.auraqol.mixin;

import com.auraqol.feature.Zoom;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Camera;

/**
 * Narrows the field of view while the zoom key is held.
 *
 * <p>{@code Camera.calculateFov(float)} is private and is called from exactly
 * one place - {@code Camera.update}, once a frame, storing into the {@code fov}
 * field that the projection matrix and the cull frustum are both built from.
 * That makes it the only hook where the zoom, what gets culled and what
 * {@code getFov()} reports can never disagree.
 *
 * <p><b>The panorama guard is not cosmetic.</b> {@code calculateFov} has two
 * return instructions: an early {@code return 90.0f} when
 * {@code isPanoramicMode} is set, and the real one. {@code @At("RETURN")} injects
 * at BOTH, so without the check a zoom key held while the game renders a
 * panorama (the main-menu background capture) would bake a zoomed frame into a
 * cubemap face that has to be exactly 90 degrees to stitch. Shadowing the field
 * says that in the code rather than relying on {@code ordinal = 1}, which would
 * silently target the wrong return if Mojang ever reorders the method.
 *
 * <p>{@code Zoom.fovScale()} advances its own smoothing on the frame clock, so
 * it is called before the 1.0 test rather than after - skipping the call on an
 * un-zoomed frame would leave the clock stale and make the next press jump.
 */
@Mixin(Camera.class)
public abstract class ZoomMixin {

    @Shadow
    private boolean isPanoramicMode;

    @Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true)
    private void auraqol$zoom(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (isPanoramicMode) {
            return;
        }
        float scale = Zoom.fovScale();
        if (scale != 1.0f) {
            cir.setReturnValue(cir.getReturnValueF() * scale);
        }
    }
}
