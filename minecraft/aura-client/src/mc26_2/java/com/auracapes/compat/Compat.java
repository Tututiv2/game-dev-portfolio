package com.auracapes.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Minecraft 26.2 side of the version shim. The 26.1 twin lives in
 * {@code src/mc26_1} with the same class name and methods.
 *
 * <p>26.2 moved screen handling from {@code Minecraft} onto {@code Gui}, and
 * changed the GUI skin renderer to take a plain {@code Model.Simple}.
 */
public final class Compat {

    /** Which of the three preview models to draw. */
    public enum Part { WIDE, SLIM, CAPE }

    private static Model.Simple wide;
    private static Model.Simple slim;
    private static Model.Simple cape;

    private Compat() {
    }

    public static void setScreen(Screen screen) {
        Minecraft.getInstance().gui.setScreen(screen);
    }

    public static Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    private static void bake() {
        if (wide != null) {
            return;
        }
        EntityModelSet models = Minecraft.getInstance().getEntityModels();
        wide = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
        slim = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
        cape = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_CAPE), RenderTypes::entityTranslucent);
    }

    public static void drawModel(GuiGraphicsExtractor g, Part part, Identifier texture,
                                 float scale, float rotX, float rotY, float pivotY,
                                 int x0, int y0, int x1, int y1) {
        bake();
        Model.Simple model = switch (part) {
            case WIDE -> wide;
            case SLIM -> slim;
            case CAPE -> cape;
        };
        g.skin(model, texture, scale, rotX, rotY, pivotY, x0, y0, x1, y1);
    }
}
