package com.auracapes.mixin;

import com.auracapes.StreamerName;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

/**
 * Puts the nickname in the tab list too.
 *
 * <p>{@code getNameForDisplay} is where every row's text is decided - the
 * server's custom tab name if it sent one (Hypixel's "[140] playerone"), otherwise
 * the team-decorated profile name - so swapping its return value covers both
 * without caring which. {@link StreamerName#swap} rewrites only the text, so the
 * level and the colours the server chose survive. Identical signature on 26.1
 * and 26.2.
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Inject(method = "getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)"
            + "Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true)
    private void auracapes$swapTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (StreamerName.enabled()) {
            cir.setReturnValue(StreamerName.swap(cir.getReturnValue()));
        }
    }
}
