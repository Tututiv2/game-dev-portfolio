package com.auraqol.compat;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * The handful of client calls Mojang moved between 26.1 and 26.2, behind one
 * name so the feature code compiles against either.
 *
 * <p>There are two copies of this class - {@code src/mc26_1} and
 * {@code src/mc26_2} - and build.gradle compiles exactly one of them, picked
 * off {@code minecraft_version}. This is the 26.1 copy: the screen still lives
 * on {@code Minecraft} itself and F1 is {@code options.hideGui}.
 */
public final class Mc {
    private Mc() {}

    public static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    /** F1 - every HUD element and nametag is hidden. */
    public static boolean hudHidden(Minecraft mc) {
        return mc.options.hideGui;
    }

    /**
     * True if this mapping is currently bound to mouse button {@code button}.
     *
     * <p>26.1 has no {@code matches(Key)}; {@code matchesMouse} takes a click
     * event, so a synthetic click at 0,0 with no modifiers is offered to it.
     */
    public static boolean boundToMouse(KeyMapping mapping, int button) {
        return mapping.matchesMouse(new MouseButtonEvent(0, 0, new MouseButtonInfo(button, 0)));
    }
}
