package com.auracapes.mixin;

import com.auracapes.ui.AuraTitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 26.1 twin of the 26.2 ScreenSwapMixin: here every screen change still goes
 * through {@code Minecraft.setScreen}.
 */
@Mixin(Minecraft.class)
public class ScreenSwapMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen auracapes$auraTitle(Screen screen) {
        return AuraTitleScreen.substitute(screen);
    }
}
