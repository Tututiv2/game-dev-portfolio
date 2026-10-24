package com.auracapes.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;

/** The screen "Back to server list" returns to, so Reconnect hands the same one to ConnectScreen. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {

    @Accessor("parent")
    Screen auracapes$parent();
}
