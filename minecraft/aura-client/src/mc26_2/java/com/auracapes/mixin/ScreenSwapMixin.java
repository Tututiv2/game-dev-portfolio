package com.auracapes.mixin;

import com.auracapes.ui.AuraTitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

/**
 * Every screen change goes through {@code Gui.setScreen} (26.2), so this is
 * the one place vanilla's title screen can be traded for the Aura one -
 * whoever asked for it, and however (startup, disconnect, "Back to title").
 * The 26.1 twin targets {@code Minecraft.setScreen}.
 */
@Mixin(Gui.class)
public class ScreenSwapMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen auracapes$auraTitle(Screen screen) {
        return AuraTitleScreen.substitute(screen);
    }
}
