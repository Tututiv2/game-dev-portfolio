package com.testpilot.mixin;

import com.testpilot.ChatLog;
import com.testpilot.GuiAccess;
import com.testpilot.TestPilot;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The action bar ("overlay message"), plus read access to the title/subtitle. */
@Mixin(Gui.class)
public abstract class GuiMixin implements GuiAccess {

    @Shadow private @Nullable Component title;
    @Shadow private @Nullable Component subtitle;

    @Override
    public @Nullable Component testpilot$title() {
        return this.title;
    }

    @Override
    public @Nullable Component testpilot$subtitle() {
        return this.subtitle;
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void testpilot$actionBar(Component message, boolean animate, CallbackInfo ci) {
        if (TestPilot.ACTIVE) {
            ChatLog.actionBar(message);
        }
    }
}
