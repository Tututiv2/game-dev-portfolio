package com.testpilot.mixin;

import com.testpilot.TestPilot;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A test client never writes options.txt. The instance's options belong to the
 * human who also plays it; TestPilot's muted sound, fps cap and
 * pauseOnLostFocus=false must not leak into his next session.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void testpilot$noSave(CallbackInfo ci) {
        if (TestPilot.ACTIVE) {
            ci.cancel();
        }
    }
}
