package com.auracapes.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;

/**
 * "Minecraft* 26.2 - Singleplayer" becomes "Aura Client 26.2 - Singleplayer".
 *
 * <p>Only the brand is replaced; the version and the where-am-I suffix that
 * vanilla appends are kept, because they are the useful part of the title.
 */
@Mixin(Minecraft.class)
public class WindowTitleMixin {

    @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
    private void auracapes$brand(CallbackInfoReturnable<String> cir) {
        String title = cir.getReturnValue();
        if (title == null || !title.startsWith("Minecraft")) {
            return;
        }
        String rest = title.substring("Minecraft".length());
        if (rest.startsWith("*")) {
            rest = rest.substring(1);
        }
        cir.setReturnValue("Aura Client" + rest);
    }
}
