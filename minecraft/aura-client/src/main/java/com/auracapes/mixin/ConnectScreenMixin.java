package com.auracapes.mixin;

import com.auracapes.Reconnect;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Every multiplayer join (server list, direct connect, quick play, a server transfer) goes through
 * {@code ConnectScreen.startConnecting}, so this is where "the last server" is remembered for the
 * Reconnect button. {@code Minecraft.getCurrentServer()} is not enough: it is cleared on disconnect
 * and never set when the connection fails before login.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void auracapes$remember(Screen parent, Minecraft mc, ServerAddress address, ServerData data,
                                           boolean quickPlay, TransferState transfer, CallbackInfo ci) {
        Reconnect.remember(address, data);
    }
}
