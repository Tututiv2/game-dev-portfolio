package com.auracapes.ui;

import java.util.List;

import com.auracapes.Accounts;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The account switcher: every account you have, click one to become it, type
 * a name to add a new one.
 *
 * <p>The rows are drawn by the screen rather than being widgets, because a
 * row is three click targets (switch, remove, nothing) sharing one rectangle,
 * and the list scrolls - both of which are simpler as arithmetic on the mouse
 * position than as a stack of buttons that have to be moved every scroll.
 */
public class AccountScreen extends AuraScreen {

    private static final int ROW = 28;

    private EditBox nameBox;
    private String status = "";
    private int statusColor = Aura.MUTED;
    private int scroll;

    private int px0;
    private int px1;
    private int py0;
    private int py1;
    private int listY0;
    private int listY1;
    private int addY;
    private int boxX0;
    private int boxX1;

    public AccountScreen(Screen parent) {
        super(Component.literal("Accounts"), parent);
    }

    @Override
    protected void init() {
        int pw = Math.min(300, this.width - 24);
        int ph = Math.min(310, this.height - 20);
        px0 = (this.width - pw) / 2;
        px1 = px0 + pw;
        py0 = (this.height - ph) / 2;
        py1 = py0 + ph;

        listY0 = py0 + 42;
        addY = py1 - 66;
        listY1 = addY - 8;

        boxX0 = px0 + 10;
        boxX1 = px1 - 10 - 56 - 4;
        nameBox = new EditBox(this.font, boxX0 + 6, addY + 6, boxX1 - boxX0 - 12, 10,
                Component.literal("Username"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(16);
        nameBox.setTextColor(Aura.TEXT);
        nameBox.setHint(Component.literal("Add a username...").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(nameBox);

        addRenderableWidget(new AuraButton(px1 - 10 - 56, addY, 56, 20,
                Component.literal("Add"), AuraButton.Style.SECONDARY, this::addTyped));
        addRenderableWidget(new AuraButton((px0 + px1) / 2 - 50, py1 - 28, 100, 20,
                Component.literal("Done"), AuraButton.Style.PRIMARY, this::onClose));

        if (!Accounts.canSwitchNow()) {
            say("Leave your world to switch accounts.", Aura.MUTED);
        }
        clampScroll();
    }

    private void say(String text, int color) {
        status = text;
        statusColor = color;
    }

    private void addTyped() {
        String name = nameBox.getValue().trim();
        String problem = Accounts.validate(name);
        if (problem != null) {
            say(problem, Aura.BAD);
            return;
        }
        boolean added = Accounts.add(name);
        nameBox.setValue("");
        if (!Accounts.canSwitchNow()) {
            say(added ? "Added " + name + ". Leave your world to switch to it."
                    : name + " is already in the list.", Aura.MUTED);
            return;
        }
        // Lunar's behaviour, and the one you want: adding an account is
        // almost always because you are about to play on it.
        String err = Accounts.switchTo(name);
        if (err != null) {
            say(err, Aura.BAD);
        } else {
            say("Now playing as " + Accounts.currentName() + ".", Aura.GOOD);
        }
        // Scroll so the new row is visible.
        scroll = Integer.MAX_VALUE;
        clampScroll();
    }

    private int contentHeight() {
        return Accounts.all().size() * ROW;
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight() - (listY1 - listY0));
        scroll = Math.max(0, Math.min(scroll, max));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        extractBackdrop(g);
        Aura.panel(g, px0, py0, px1, py1, 1.0F);

        int cx = (px0 + px1) / 2;
        extractHeading(g, "Accounts", cx, py0 + 10);
        g.centeredText(this.font, "Click an account to play as it", cx, py0 + 27, Aura.FAINT);

        boolean canSwitch = Accounts.canSwitchNow();
        List<String> all = Accounts.all();

        g.enableScissor(px0 + 1, listY0, px1 - 1, listY1);
        for (int i = 0; i < all.size(); i++) {
            int y = listY0 + i * ROW - scroll;
            if (y + ROW < listY0 || y > listY1) {
                continue;
            }
            extractRow(g, all.get(i), i == 0, y, mouseX, mouseY, canSwitch);
        }
        g.disableScissor();

        // Scrollbar, only when there is something to scroll.
        int content = contentHeight();
        int view = listY1 - listY0;
        if (content > view) {
            int barH = Math.max(12, view * view / content);
            int barY = listY0 + (view - barH) * scroll / (content - view);
            g.fill(px1 - 5, barY, px1 - 3, barY + barH, 0x80FFFFFF);
        }

        // Input frame for the name box.
        boolean focused = nameBox.isFocused();
        Aura.round(g, boxX0, addY, boxX1, addY + 20, focused ? 0xE0181828 : 0xC0101018);
        Aura.outline(g, boxX0, addY, boxX1, addY + 20, focused ? 0x90A78BFA : Aura.EDGE);

        if (!status.isEmpty()) {
            g.centeredText(this.font, status, cx, addY + 26, statusColor);
        } else {
            g.centeredText(this.font, "Offline accounts: singleplayer, LAN, offline servers",
                    cx, addY + 26, Aura.FAINT);
        }

        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private void extractRow(GuiGraphicsExtractor g, String name, boolean launch, int y,
                            int mouseX, int mouseY, boolean canSwitch) {
        int x0 = px0 + 8;
        int x1 = px1 - 8;
        int y1 = y + ROW - 3;
        boolean current = Accounts.isCurrent(name);
        boolean inList = mouseY >= listY0 && mouseY < listY1;
        boolean hot = inList && Aura.hits(mouseX, mouseY, x0, y, x1, y1);
        boolean removable = !launch && Accounts.removable(name);
        boolean removeHot = removable && hot && mouseX >= x1 - 18;

        int bg = current ? 0xD0221E3A : (hot && canSwitch ? 0xC81A1A2C : 0x90101018);
        Aura.round(g, x0, y, x1, y1, bg);
        if (current) {
            Aura.outline(g, x0, y, x1, y1, 0x80A78BFA);
            g.fill(x0, y + 4, x0 + 2, y1 - 4, Aura.VIOLET);
        }

        PlayerFaceExtractor.extractRenderState(g, Accounts.skinFor(name), x0 + 7, y + 4, 17);
        int nameCol = canSwitch || current ? Aura.TEXT : Aura.MUTED;
        g.text(this.font, name, x0 + 30, y + 4, nameCol);
        String tag = launch ? "Launch account" : "Offline account";
        g.text(this.font, tag, x0 + 30, y + 14, launch ? 0xFF86EFAC : 0xFFB4A7E6, false);

        if (current) {
            String pill = "ACTIVE";
            int pw = this.font.width(pill) + 8;
            int pxr = x1 - (removable ? 22 : 6);
            Aura.round(g, pxr - pw, y + 7, pxr, y + 18, 0x608B5CF6);
            g.text(this.font, pill, pxr - pw + 4, y + 9, 0xFFE9D5FF, false);
        } else if (hot && canSwitch && !removeHot) {
            String hint = "Switch";
            g.text(this.font, hint, x1 - (removable ? 22 : 6) - this.font.width(hint), y + 9,
                    0xFFC4B5FD, false);
        }

        if (removable) {
            int col = removeHot ? Aura.BAD : Aura.FAINT;
            g.text(this.font, "✕", x1 - 13, y + 9, col, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        // Clicking anywhere on the input frame focuses the box, not just on
        // the 10px strip the unbordered EditBox itself covers.
        if (Aura.hits(mx, my, boxX0, addY, boxX1, addY + 20)) {
            // super first: it moves the caret when the click lands on the
            // text, and may clear focus when it lands beside it - so focus is
            // set after, not before.
            super.mouseClicked(event, doubleClick);
            this.setFocused(nameBox);
            return true;
        }

        if (my >= listY0 && my < listY1) {
            List<String> all = Accounts.all();
            int i = (int) ((my - listY0 + scroll) / ROW);
            int rowTop = listY0 + i * ROW - scroll;
            if (i >= 0 && i < all.size() && my < rowTop + ROW - 3
                    && mx >= px0 + 8 && mx < px1 - 8) {
                String name = all.get(i);
                if (i > 0 && Accounts.removable(name) && mx >= px1 - 8 - 18) {
                    Accounts.remove(name);
                    say("Removed " + name + ".", Aura.MUTED);
                    clampScroll();
                    return true;
                }
                if (!Accounts.isCurrent(name)) {
                    String err = Accounts.switchTo(name);
                    if (err != null) {
                        say(err, Aura.BAD);
                    } else {
                        say("Now playing as " + Accounts.currentName() + ".", Aura.GOOD);
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (int) Math.round(sy * 14);
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter in the box adds - typing a name then reaching for the mouse
        // is the one interaction on this screen that should be a keypress.
        if (nameBox.isFocused() && (event.key() == 257 || event.key() == 335)) {
            addTyped();
            return true;
        }
        return super.keyPressed(event);
    }
}
