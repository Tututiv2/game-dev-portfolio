package com.auraqol.compat;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The handful of client calls Mojang moved between 26.1 and 26.2, behind one
 * name so the feature code compiles against either.
 *
 * <p>There are two copies of this class - {@code src/mc26_1} and
 * {@code src/mc26_2} - and build.gradle compiles exactly one of them, picked
 * off {@code minecraft_version}. This is the 26.2 copy: the current screen and
 * the F1 flag both moved onto the public {@code gui} field.
 */
public final class Mc {
    private Mc() {}

    public static Screen screen(Minecraft mc) {
        return mc.gui == null ? null : mc.gui.screen();
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }

    /** F1 - every HUD element and nametag is hidden. */
    public static boolean hudHidden(Minecraft mc) {
        return mc.gui != null && mc.gui.hud.isHidden();
    }

    /** True if this mapping is currently bound to mouse button {@code button}. */
    public static boolean boundToMouse(KeyMapping mapping, int button) {
        return mapping.matches(InputConstants.Type.MOUSE.getOrCreate(button));
    }
}
