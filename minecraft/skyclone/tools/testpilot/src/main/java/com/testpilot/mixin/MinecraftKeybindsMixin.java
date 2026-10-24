package com.testpilot.mixin;

import com.testpilot.TestPilot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Held attack (block breaking) without a grabbed mouse.
 *
 * <p>Vanilla continues a held attack only when
 * {@code keyAttack.isDown() && mouseHandler.isMouseGrabbed()}. A test client is
 * never grabbed (it is never focused), so with TestPilot active the grab test
 * reads true here - and only here. Whether the attack is held is still decided
 * by the real {@code keyAttack} KeyMapping state, which TestPilot sets.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftKeybindsMixin {

    @Redirect(method = "handleKeybinds",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean testpilot$grabbedForAttack(MouseHandler handler) {
        return handler.isMouseGrabbed() || TestPilot.ACTIVE;
    }
}
