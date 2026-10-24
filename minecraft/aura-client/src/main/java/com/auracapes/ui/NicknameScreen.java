package com.auracapes.ui;

import com.auracapes.StreamerName;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The {@code /name} nickname, from the main menu - so it is already on when
 * you join instead of being something you type once you are in.
 *
 * <p>Same feature, same state: this screen and the command both drive
 * {@link StreamerName}, so turning it on here and running {@code /name off}
 * in game are the same switch.
 *
 * <p>Typing alone never turns it on. The box is also how you edit a name
 * while the nickname is already showing, and applying every keystroke would
 * flash half-typed names over your head. Enter or the toggle applies it.
 */
public class NicknameScreen extends AuraScreen {

    private EditBox nameBox;
    private AuraButton toggle;

    private int px0;
    private int px1;
    private int py0;
    private int py1;
    private int boxY;

    public NicknameScreen(Screen parent) {
        super(Component.literal("Nickname"), parent);
    }

    @Override
    protected void init() {
        int pw = Math.min(270, this.width - 24);
        int ph = 176;
        px0 = (this.width - pw) / 2;
        px1 = px0 + pw;
        py0 = (this.height - ph) / 2;
        py1 = py0 + ph;

        boxY = py0 + 86;
        nameBox = new EditBox(this.font, px0 + 16, boxY + 6, pw - 32, 10, Component.literal("Nickname"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(32);
        nameBox.setTextColor(Aura.TEXT);
        nameBox.setValue(StreamerName.alias());
        nameBox.setHint(Component.literal("Type a nickname...").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(nameBox);

        toggle = addRenderableWidget(new AuraButton(px0 + 10, boxY + 26, pw - 20, 20,
                Component.empty(), AuraButton.Style.PRIMARY, this::flip));
        addRenderableWidget(new AuraButton((px0 + px1) / 2 - 45, py1 - 26, 90, 18,
                Component.literal("Done"), AuraButton.Style.SECONDARY, this::onClose));
        setInitialFocus(nameBox);
    }

    /** Toggle: on shows what is typed, off hides it but keeps the name. */
    private void flip() {
        if (StreamerName.enabled()) {
            StreamerName.off();
        } else {
            apply();
        }
    }

    private void apply() {
        String typed = nameBox.getValue().trim();
        if (typed.isBlank()) {
            StreamerName.off();
        } else {
            StreamerName.set(typed);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        extractBackdrop(g);
        Aura.panel(g, px0, py0, px1, py1, 1.0F);
        int cx = (px0 + px1) / 2;
        extractHeading(g, "Nickname", cx, py0 + 10);
        g.centeredText(this.font, "Over your head and in chat - only on your screen",
                cx, py0 + 27, Aura.FAINT);

        // Preview: drawn like a real nametag, so you see what F5 will show.
        boolean on = StreamerName.enabled();
        String typed = nameBox.getValue().trim();
        String shown = on ? StreamerName.alias()
                : (typed.isBlank() ? StreamerName.realName() : typed);
        if (shown == null) {
            shown = "";
        }
        int tw = this.font.width(shown);
        int tagY = py0 + 50;
        g.fill(cx - tw / 2 - 3, tagY - 2, cx + tw / 2 + 3, tagY + 10, 0x40000000);
        g.centeredText(this.font, shown, cx, tagY, on ? Aura.TEXT : 0x80FFFFFF);
        // Never "everyone sees X": other players always see the real name,
        // on or off. Saying otherwise would promise something this cannot do.
        String state = on ? "Showing now" : "Off - showing your real name";
        g.centeredText(this.font, state, cx, tagY + 15, on ? Aura.GOOD : Aura.FAINT);

        boolean focused = nameBox.isFocused();
        Aura.round(g, px0 + 10, boxY, px1 - 10, boxY + 20, focused ? 0xE0181828 : 0xC0101018);
        Aura.outline(g, px0 + 10, boxY, px1 - 10, boxY + 20, focused ? 0x90A78BFA : Aura.EDGE);

        boolean edited = on && !typed.isBlank() && !typed.equals(StreamerName.alias());
        toggle.setMessage(Component.literal(edited ? "Press Enter to update"
                : (on ? "Nickname · ON" : "Nickname · OFF")));

        super.extractRenderState(g, mouseX, mouseY, a);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (Aura.hits(event.x(), event.y(), px0 + 10, boxY, px1 - 10, boxY + 20)) {
            super.mouseClicked(event, doubleClick);
            this.setFocused(nameBox);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (nameBox.isFocused() && (event.key() == 257 || event.key() == 335)) {
            apply();
            return true;
        }
        return super.keyPressed(event);
    }
}
