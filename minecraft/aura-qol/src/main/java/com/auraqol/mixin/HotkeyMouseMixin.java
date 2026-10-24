package com.auraqol.mixin;

import com.auraqol.feature.ChatHotkeys;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * Every mouse-button event, so a hotkey can live on mouse 4/5 like in Lunar.
 * Same signature in 26.1.2 and 26.2. Observe only; {@code require = 0}.
 */
@Mixin(MouseHandler.class)
public abstract class HotkeyMouseMixin {

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), require = 0)
    private void auraqol$hotkey(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        ChatHotkeys.onInput(true, info.input(), info.modifiers(), action);
    }
}
