package com.auraqol.mixin;

import com.auraqol.feature.ChatHotkeys;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;

/**
 * Every keyboard event the game window gets, for chat hotkeys. Same signature
 * in 26.1.2 and 26.2 (javap-checked). Observe only - the key still reaches the
 * game. {@code require = 0}: a future rename loses hotkeys, not the game.
 */
@Mixin(KeyboardHandler.class)
public abstract class HotkeyInputMixin {

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), require = 0)
    private void auraqol$hotkey(long window, int action, KeyEvent event, CallbackInfo ci) {
        ChatHotkeys.onInput(false, event.key(), event.modifiers(), action);
    }
}
