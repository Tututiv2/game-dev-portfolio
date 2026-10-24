package com.secondwatch.client;

import com.secondwatch.SecondWatch;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side entrypoint.
 *
 * <p>This side draws lies. It must never be trusted as evidence of world state -
 * a screenshot from one client is not proof of anything, which is a debugging
 * hazard as much as a design goal. When something looks wrong, read the server's
 * fabrication log, not the game.
 */
public class SecondWatchClient implements ClientModInitializer {

    /**
     * F6 rather than something comfortable, on purpose: this key must never be
     * within reach of a panicking hand during a recorded session.
     */
    private static KeyMapping testPanelKey;

    @Override
    public void onInitializeClient() {
        // Vanilla geometry, custom textures - see MobRenderers for why.
        MobRenderers.register();

        testPanelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.secondwatch.test_panel",
                GLFW.GLFW_KEY_F6,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (testPanelKey.consumeClick()) {
                if (client.player == null) continue;
                client.setScreenAndShow(new TestPanelScreen());
            }
        });

        SecondWatch.LOG.info("Second Watch client ready. F6 opens the test panel.");
    }
}
