package com.aisky.showcam.mixin;

import com.aisky.showcam.Flags;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code SHOWCAM_TITLE} becomes the window title, so OBS can window-capture
 * each of the five show clients separately (they all run the same instance and
 * would otherwise all be titled "Minecraft 26.1.2 - Multiplayer (3rd-party Server)").
 *
 * <p>26.1.2 builds every title in ONE place, {@code private String createTitle()}
 * (javap-verified): it is passed to the {@code Window} constructor when the
 * window is created, and {@code updateTitle()} - called on every join, leave
 * and screen change - is just {@code window.setTitle(createTitle())}. Replacing
 * its return value therefore pins the title for the client's whole life.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTitleMixin {

    @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
    private void showcam$title(CallbackInfoReturnable<String> cir) {
        if (Flags.TITLE != null) {
            cir.setReturnValue(Flags.TITLE);
        }
    }
}
