package com.auracapes.mixin;

import com.auracapes.StreamerName;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

/**
 * Rewrites the username in chat, on the way into the chat box.
 *
 * <p><b>Why a mixin and not the Fabric event.</b> {@code ClientReceiveMessage
 * Events} has MODIFY_GAME for system messages but no MODIFY_CHAT - player chat
 * only gets ALLOW_CHAT, which can drop a line but cannot change one. Covering
 * player chat through the event would mean cancelling the real message and
 * pushing a replacement, which loses its signature and its message tag and
 * makes the client treat it as unverified. So the swap happens where every
 * kind of message already converges instead.
 *
 * <p>{@code addMessage} is private and that is exactly why it is the right
 * target: it is the single funnel every path ends at - player chat, system
 * lines, death messages, command feedback - so one injection covers all of
 * them and there is no second entry point to keep in sync.
 *
 * <p>This is display only. The message that was received, signed and logged
 * is untouched; only the copy being drawn carries the alias.
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;"
                    + "Lnet/minecraft/network/chat/MessageSignature;"
                    + "Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;"
                    + "Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1)
    private Component auracapes$swapName(Component message) {
        return StreamerName.swap(message);
    }
}
