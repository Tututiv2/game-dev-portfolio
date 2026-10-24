package com.aisky.showcam.mixin;

import com.aisky.showcam.CamDirector;
import com.aisky.showcam.Flags;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The lens override. Verified against 26.1.2's {@code Camera.update(DeltaTracker)},
 * whose body (javap) runs, in order:
 * <pre>
 *   alignWithEntity(pt)                     // position + rotation from the player
 *   this.fov    = calculateFov(pt)          // (AuraQoL's zoom hooks calculateFov RETURN)
 *   this.hudFov = calculateHudFov(pt)
 *   prepareCullFrustum(getViewRotationMatrix(..), createProjectionMatrixForCulling(), this.position)
 *   setupPerspective(0.05, depthFar, this.fov, w, h)
 * </pre>
 * Injecting right after the {@code hudFov} store means vanilla (and any other
 * mod's FOV hook) has already run, and the frustum, the culling projection and
 * the render projection are all built from OUR position, rotation and FOV.
 * One injector, no ordering fight with other mixins on {@code calculateFov}.
 *
 * <p>{@code detached} is pinned false while a shot runs: vanilla only draws
 * the camera's own entity when the camera is detached, so the (spectator)
 * camera account never appears in its own shot.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow private boolean detached;
    @Shadow private float fov;

    @Shadow protected abstract void setPosition(Vec3 position);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Shadow public abstract Vec3 position();

    @Shadow public abstract float xRot();

    @Shadow public abstract float yRot();

    @Inject(method = "update",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;hudFov:F",
                    opcode = 181 /* Opcodes.PUTFIELD */, shift = At.Shift.AFTER))
    private void showcam$lens(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!Flags.CAMERA) {
            return;
        }
        float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
        if (!CamDirector.frame(this.position(), this.yRot(), this.xRot(), this.fov, partial)) {
            return;
        }
        this.setPosition(new Vec3(CamDirector.x(), CamDirector.y(), CamDirector.z()));
        this.setRotation(CamDirector.yaw(), CamDirector.pitch());
        this.fov = CamDirector.fov();
        this.detached = false;
    }
}
