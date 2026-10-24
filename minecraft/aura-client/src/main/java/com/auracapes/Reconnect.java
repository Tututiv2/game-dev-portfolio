package com.auracapes;

import com.auracapes.mixin.DisconnectedScreenAccessor;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * A "Reconnect" button right under "Back to server list" when a server kicks you (owner, 2026-10-14).
 * The server is whatever {@code ConnectScreen.startConnecting} last got ({@code ConnectScreenMixin}),
 * so it also works when the connection failed before login.
 */
public final class Reconnect {

    private static ServerAddress lastAddress;
    private static ServerData lastData;

    private Reconnect() {
    }

    public static void remember(ServerAddress address, ServerData data) {
        lastAddress = address;
        lastData = data;
    }

    /** Called from {@code ScreenEvents.AFTER_INIT}, so it is re-added on every resize. */
    public static void addButton(Screen screen) {
        if (!(screen instanceof DisconnectedScreen) || lastAddress == null || lastData == null
                || lastData.isRealm()) {
            return;   // Realms join through their own flow; nothing to reconnect to without a server
        }
        Button below = null;
        for (AbstractWidget w : Screens.getWidgets(screen)) {
            if (w instanceof Button b && (below == null || b.getY() > below.getY())) {
                below = b;
            }
        }
        if (below == null) {
            return;
        }
        Screen parent = ((DisconnectedScreenAccessor) screen).auracapes$parent();
        ServerAddress address = lastAddress;
        ServerData data = lastData;
        Screens.getWidgets(screen).add(Button.builder(Component.literal("Reconnect"),
                        b -> ConnectScreen.startConnecting(parent, Minecraft.getInstance(), address, data, false, null))
                .bounds(below.getX(), below.getY() + below.getHeight() + 4, below.getWidth(), below.getHeight())
                .build());
    }
}
