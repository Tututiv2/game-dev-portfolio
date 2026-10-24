package com.auracapes.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;

/**
 * Minecraft 26.1 side of the version shim. The 26.2 twin lives in
 * {@code src/mc26_2} with the same class name and methods.
 *
 * <p>Here screens are still set on {@code Minecraft} itself, and the GUI skin
 * renderer takes a {@code PlayerModel}.
 */
public final class Compat {

    /** Which of the three preview models to draw. */
    public enum Part { WIDE, SLIM, CAPE }

    private static PlayerModel wide;
    private static PlayerModel slim;
    private static PlayerModel cape;

    private Compat() {
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    public static Screen screen() {
        return Minecraft.getInstance().screen;
    }

    private static void bake() {
        if (wide != null) {
            return;
        }
        EntityModelSet models = Minecraft.getInstance().getEntityModels();
        wide = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
        slim = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        cape = new PlayerCapeModel(models.bakeLayer(ModelLayers.PLAYER_CAPE));
    }

    public static void drawModel(GuiGraphicsExtractor g, Part part, Identifier texture,
                                 float scale, float rotX, float rotY, float pivotY,
                                 int x0, int y0, int x1, int y1) {
        bake();
        PlayerModel model = switch (part) {
            case WIDE -> wide;
            case SLIM -> slim;
            case CAPE -> cape;
        };
        g.skin(model, texture, scale, rotX, rotY, pivotY, x0, y0, x1, y1);
    }
}
