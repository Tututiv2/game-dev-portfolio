package com.aisky.showcam.mixin;

import com.aisky.showcam.Flags;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A show client never writes options.txt. The instance's options belong to the
 * owner, who plays this instance himself; ShowCam's hidden HUD, disabled view
 * bobbing and forced first-person camera must not leak into his next session.
 * (TestPilot cancels the same method; two cancelling HEAD injections coexist.)
 */
@Mixin(Options.class)
public abstract class OptionsMixin {

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void showcam$noSave(CallbackInfo ci) {
        if (Flags.ANY) {
            ci.cancel();
        }
    }
}
