package com.secondwatch.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The button you press to test things.
 *
 * <p>Exists because this mod's arc is five nights long and two players wide,
 * which makes almost every part of it expensive to reach by playing. Summoning
 * the thing you want to look at in one keypress is the difference between
 * checking a change in ten seconds and checking it in forty minutes with a
 * friend who has to be free.
 *
 * <p>Every button is a command the server already exposes, so this adds no new
 * powers - without operator rights the buttons are there and do nothing.
 *
 * <p>Note for future edits: 26.x rewrote the GUI layer. {@link Screen} now takes
 * {@code (Minecraft, Font, Component)}, {@code init(int,int)} is final (override
 * the no-arg {@code init()} instead), and drawing goes through a render-state
 * extraction pass rather than a {@code render(GuiGraphics, …)} override. Widgets
 * added with {@code addRenderableWidget} still draw themselves, which is why
 * this screen needs no draw code at all.
 */
public class TestPanelScreen extends Screen {

    private static final int BUTTON_WIDTH = 240;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;

    private record Action(String label, String command) {
    }

    private static final Action[] ACTIONS = {
            new Action("Show me a phantom (only you can see it)", "secondwatch phantom"),
            new Action("Give me the journal", "secondwatch book"),
            new Action("Start the run", "secondwatch start"),
            new Action("Next night", "secondwatch night"),
            new Action("Is my microphone being recorded?", "secondwatch voice"),
            new Action("Say my last line back to me, 10 blocks away", "secondwatch echo 10"),
            new Action("Say it back from 40 blocks away", "secondwatch echo 40"),
            new Action("Give me the way out", "secondwatch seat"),
            new Action("Give the sky back", "secondwatch release"),
            new Action("§cRun status - SPOILS THIS RUN", "secondwatch status"),
    };

    public TestPanelScreen() {
        super(Minecraft.getInstance(),
                Minecraft.getInstance().font,
                Component.literal("Second Watch - test panel"));
    }

    @Override
    protected void init() {
        int totalHeight = ACTIONS.length * (BUTTON_HEIGHT + GAP);
        int y = Math.max(36, (this.height - totalHeight) / 2);
        int x = (this.width - BUTTON_WIDTH) / 2;

        for (Action action : ACTIONS) {
            addRenderableWidget(Button.builder(
                            Component.literal(action.label()),
                            b -> run(action.command()))
                    .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build());
            y += BUTTON_HEIGHT + GAP;
        }
    }

    private void run(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        client.player.connection.sendCommand(command);
        // Close immediately: most of these are things you have to be LOOKING at
        // the world to judge, and a panel in the way defeats the point.
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
