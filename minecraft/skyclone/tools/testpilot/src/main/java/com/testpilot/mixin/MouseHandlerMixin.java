package com.testpilot.mixin;

import com.testpilot.TestPilot;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Never grab the cursor. Vanilla already refuses while the window is unfocused;
 * this also covers the owner clicking into the test window by accident - the
 * cursor stays his. Override with TESTPILOT_ALLOW_GRAB=1.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void testpilot$noGrab(CallbackInfo ci) {
        if (TestPilot.ACTIVE && !TestPilot.ALLOW_GRAB) {
            ci.cancel();
        }
    }
}
