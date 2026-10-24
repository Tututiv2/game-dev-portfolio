package com.testpilot.mixin;

import com.testpilot.ChatLog;
import com.testpilot.TestPilot;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every chat line the player actually sees (after the visibility filter). */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Inject(method = "logChatMessage", at = @At("HEAD"))
    private void testpilot$record(GuiMessage message, CallbackInfo ci) {
        if (TestPilot.ACTIVE) {
            ChatLog.add(message.content());
        }
    }
}
