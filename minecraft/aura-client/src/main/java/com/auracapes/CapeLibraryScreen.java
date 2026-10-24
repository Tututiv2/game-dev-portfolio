package com.auracapes;

import java.util.List;

import com.auracapes.compat.Compat;
import com.auracapes.ui.Aura;
import com.auracapes.ui.AuraButton;
import com.auracapes.ui.AuraScreen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Cosmetics: a turning model of you on the left, every cape on the right.
 * Hover a cape to try it on, click to wear it. Opened with G, from the main
 * menu, or from the pause menu.
 *
 * <p><b>Hover previews, click commits.</b> That split is what makes a locker
 * feel quick: you can sweep the mouse across the whole grid and watch each
 * cape on your back without changing anything, and only a click saves (and
 * publishes, if a gist is configured - see {@link CapePush}).
 *
 * <p><b>The model is two drawings, not one.</b> The GUI draws a player model
 * as a picture-in-picture: rendered into its own texture, then pasted onto the
 * screen. The cape is a second model pasted into the same rectangle, and two
 * pasted pictures share no depth buffer - so which one is in front is decided
 * by drawing order. Facing you, the body is drawn last (the cape is behind
 * it); turned away, the cape is drawn last. At the in-between angles the cape
 * only overlaps the body by its 1px margin, which nobody can see.
 */
public final class CapeLibraryScreen extends AuraScreen {

    /** The cape front sits at (1,1) 10x16 on every 64x32 cape sheet. */
    private static final float U0 = 1.0F / 64.0F;
    private static final float U1 = 11.0F / 64.0F;
    private static final float V0 = 1.0F / 32.0F;
    private static final float V1 = 17.0F / 32.0F;

    private static final int TILE_W = 70;
    private static final int TILE_H = 74;
    private static final int TILE_GAP = 6;

    private EditBox nameBox;
    private AuraButton streamerToggle;

    private int lx0;
    private int lx1;
    private int rx0;
    private int rx1;
    private int top;
    private int bottom;
    private int prevX0;
    private int prevY0;
    private int prevX1;
    private int prevY1;
    private int gx0;
    private int gy0;
    private int gx1;
    private int gy1;
    private int cols;
    private int gridOffset;
    private int scroll;

    private float rotY = 25.0F;
    private float rotX = -5.0F;
    private boolean dragging;
    private long lastDrag;
    private long lastFrame;

    public CapeLibraryScreen(Screen parent) {
        super(Component.literal("Cosmetics"), parent);
    }

    /** Opens over whatever is showing, and goes back to it on Esc. */
    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreenAndShow(new CapeLibraryScreen(Compat.screen()));
    }

    @Override
    protected void init() {
        int margin = 12;
        top = 32;
        bottom = this.height - margin;

        int lw = Math.max(130, Math.min(190, (int) (this.width * 0.34F)));
        lx0 = margin;
        lx1 = margin + lw;
        rx0 = lx1 + 8;
        rx1 = this.width - margin;

        // Left column, bottom-up: toggle, name box, label, then the preview
        // takes whatever height is left.
        int toggleY = bottom - 6 - 18;
        int boxY = toggleY - 4 - 18;
        prevX0 = lx0 + 6;
        prevX1 = lx1 - 6;
        prevY0 = top + 6;
        prevY1 = boxY - 12 - 30;

        nameBox = new EditBox(this.font, lx0 + 12, boxY + 5, lw - 24, 10,
                Component.literal("Name to show"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(32);
        nameBox.setTextColor(Aura.TEXT);
        nameBox.setValue(StreamerName.alias());
        nameBox.setHint(Component.literal("Streamer name...").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(nameBox);

        streamerToggle = addRenderableWidget(new AuraButton(lx0 + 6, toggleY, lw - 12, 18,
                Component.empty(), AuraButton.Style.SECONDARY, this::toggleStreamer));

        addRenderableWidget(new AuraButton(this.width - margin - 60, 8, 60, 18,
                Component.literal("Done"), AuraButton.Style.PRIMARY, this::onClose));

        gx0 = rx0 + 8;
        gx1 = rx1 - 8;
        gy0 = top + 24;
        gy1 = bottom - 22;
        cols = Math.max(1, (gx1 - gx0 + TILE_GAP) / (TILE_W + TILE_GAP));
        int used = cols * TILE_W + (cols - 1) * TILE_GAP;
        gridOffset = (gx1 - gx0 - used) / 2;
        clampScroll();
    }

    private void toggleStreamer() {
        String typed = nameBox.getValue().trim();
        if (typed.isBlank()) {
            StreamerName.off();
        } else if (StreamerName.enabled() && typed.equals(StreamerName.alias())) {
            StreamerName.off();
        } else {
            StreamerName.set(typed);
        }
    }

    private List<String> capes() {
        return CapeRegistry.names();
    }

    private int contentHeight() {
        int rows = (capes().size() + cols - 1) / cols;
        return rows * (TILE_H + TILE_GAP) - TILE_GAP;
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight() - (gy1 - gy0));
        scroll = Math.max(0, Math.min(scroll, max));
    }

    /** Index of the tile under the mouse, or -1. */
    private int tileAt(double mx, double my) {
        if (my < gy0 || my >= gy1) {
            return -1;
        }
        double lx = mx - gx0 - gridOffset;
        double ly = my - gy0 + scroll;
        if (lx < 0 || ly < 0) {
            return -1;
        }
        int c = (int) (lx / (TILE_W + TILE_GAP));
        int r = (int) (ly / (TILE_H + TILE_GAP));
        if (c >= cols || lx - c * (TILE_W + TILE_GAP) >= TILE_W
                || ly - r * (TILE_H + TILE_GAP) >= TILE_H) {
            return -1;
        }
        int i = r * cols + c;
        return i < capes().size() ? i : -1;
    }

    static Identifier capeTexture(String cape) {
        if (cape == null || cape.equals("none")) {
            return null;
        }
        return Identifier.fromNamespaceAndPath(CapeRegistry.MOD_ID, "textures/cape/" + cape + ".png");
    }

    /** "minecon_2011" -> "MineCon 2011", "red_creeper" -> "Red Creeper". */
    static String pretty(String id) {
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (part.equals("minecon")) {
                sb.append("MineCon");
            } else {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        long now = Util.getMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        // Turn slowly on its own, but not for two seconds after you let go -
        // otherwise it drifts off the angle you just dragged it to.
        if (!dragging && now - lastDrag > 2000) {
            rotY += dt * 22.0F;
        }

        extractBackdrop(g);
        extractHeading(g, "Cosmetics", this.width / 2, 12);

        Aura.panel(g, lx0, top, lx1, bottom, 1.0F);
        Aura.panel(g, rx0, top, rx1, bottom, 1.0F);

        int hovered = tileAt(mouseX, mouseY);
        String worn = CapeRegistry.current();
        String shown = hovered >= 0 ? capes().get(hovered) : worn;

        extractPreview(g, shown);

        // Name plate under the model.
        int cx = (lx0 + lx1) / 2;
        String display = StreamerName.enabled() ? StreamerName.alias() : Accounts.currentName();
        g.centeredText(this.font, display, cx, prevY1 + 4, Aura.TEXT);
        // The hint takes the cape line's place only while you are over the
        // model - anywhere else it would sit on top of the model's head.
        boolean overModel = Aura.hits(mouseX, mouseY, prevX0, prevY0, prevX1, prevY1);
        String sub = hovered >= 0 && !shown.equals(worn)
                ? "Trying on: " + pretty(shown)
                : (overModel && !dragging ? "drag to turn"
                        : (worn.equals("none") ? "No cape" : pretty(worn)));
        g.centeredText(this.font, sub, cx, prevY1 + 15, hovered >= 0 ? 0xFFC4B5FD : Aura.MUTED);

        // Streamer name section.
        int toggleY = streamerToggle.getY();
        int boxY = toggleY - 4 - 18;
        g.text(this.font, "Streamer name", lx0 + 8, boxY - 11, Aura.MUTED, false);
        boolean focused = nameBox.isFocused();
        Aura.round(g, lx0 + 6, boxY, lx1 - 6, boxY + 18, focused ? 0xE0181828 : 0xC0101018);
        Aura.outline(g, lx0 + 6, boxY, lx1 - 6, boxY + 18, focused ? 0x90A78BFA : Aura.EDGE);
        streamerToggle.setMessage(Component.literal(StreamerName.enabled()
                ? "Showing alias · ON" : "Show alias · OFF"));

        // Right column.
        g.text(this.font, "Capes", rx0 + 10, top + 9, Aura.TEXT);
        String count = capes().size() - 1 + " owned";
        g.text(this.font, count, rx1 - 10 - this.font.width(count), top + 9, Aura.FAINT, false);
        Aura.accentBar(g, rx0 + 10, top + 19, rx0 + 10 + this.font.width("Capes"), top + 20, 1.0F);

        g.enableScissor(gx0 - 2, gy0 - 2, gx1 + 2, gy1);
        List<String> all = capes();
        for (int i = 0; i < all.size(); i++) {
            int c = i % cols;
            int r = i / cols;
            int x = gx0 + gridOffset + c * (TILE_W + TILE_GAP);
            int y = gy0 + r * (TILE_H + TILE_GAP) - scroll;
            if (y + TILE_H < gy0 || y > gy1) {
                continue;
            }
            extractTile(g, all.get(i), x, y, i == hovered, all.get(i).equals(worn));
        }
        g.disableScissor();

        int content = contentHeight();
        int view = gy1 - gy0;
        if (content > view) {
            int barH = Math.max(12, view * view / content);
            int barY = gy0 + (view - barH) * scroll / (content - view);
            g.fill(rx1 - 5, barY, rx1 - 3, barY + barH, 0x80FFFFFF);
        }

        // Info bar: what the hovered (or worn) cape is.
        String desc = CapeRegistry.describe(shown);
        String info = this.font.plainSubstrByWidth(desc, rx1 - rx0 - 20);
        g.fill(rx0 + 6, bottom - 19, rx1 - 6, bottom - 18, Aura.EDGE);
        g.text(this.font, info, rx0 + 10, bottom - 13, Aura.MUTED, false);

        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private void extractPreview(GuiGraphicsExtractor g, String cape) {
        // Soft floor glow under the feet so the model stands on something.
        int cx = (prevX0 + prevX1) / 2;
        int fy = prevY1 - 6;
        for (int i = 0; i < 6; i++) {
            int hw = 34 - i * 5;
            g.fill(cx - hw, fy - i, cx + hw, fy - i + 1, Aura.fade(0xFF8B5CF6, 0.05F + i * 0.03F));
        }

        PlayerSkin skin = Minecraft.getInstance().player != null
                ? Minecraft.getInstance().player.getSkin()
                : Accounts.currentSkin();
        Compat.Part body = skin.model() == PlayerModelType.SLIM ? Compat.Part.SLIM : Compat.Part.WIDE;
        Identifier capeTex = capeTexture(cape);

        int h = prevY1 - prevY0;
        float scale = 0.97F * h / 2.125F;
        float pivotY = -1.0625F;
        boolean facing = Math.cos(Math.toRadians(rotY)) >= 0;

        if (capeTex != null && facing) {
            Compat.drawModel(g, Compat.Part.CAPE, capeTex, scale, rotX, rotY, pivotY,
                    prevX0, prevY0, prevX1, prevY1);
        }
        Compat.drawModel(g, body, skin.body().texturePath(), scale, rotX, rotY, pivotY,
                prevX0, prevY0, prevX1, prevY1);
        if (capeTex != null && !facing) {
            Compat.drawModel(g, Compat.Part.CAPE, capeTex, scale, rotX, rotY, pivotY,
                    prevX0, prevY0, prevX1, prevY1);
        }

    }

    private void extractTile(GuiGraphicsExtractor g, String cape, int x, int y,
                             boolean hot, boolean worn) {
        int x1 = x + TILE_W;
        int y1 = y + TILE_H;
        int bg = worn ? 0xD0221E3A : (hot ? 0xC81C1C30 : 0x90101018);
        Aura.round(g, x, y, x1, y1, bg);
        int edge = worn ? 0xB0A78BFA : (hot ? 0x60FFFFFF : Aura.EDGE);
        Aura.outline(g, x, y, x1, y1, edge);

        int iw = 30;
        int ih = 48;
        int ix = x + (TILE_W - iw) / 2;
        int iy = y + 6 + (hot ? -1 : 0);
        Identifier tex = capeTexture(cape);
        if (tex != null) {
            // Drop shadow, then the cape front.
            g.fill(ix + 2, iy + 2, ix + iw + 2, iy + ih + 2, 0x60000000);
            g.blit(tex, ix, iy, ix + iw, iy + ih, U0, U1, V0, V1);
        } else {
            Aura.outline(g, ix, iy, ix + iw, iy + ih, 0x40FFFFFF);
            g.centeredText(this.font, "∅", x + TILE_W / 2, iy + ih / 2 - 4, Aura.FAINT);
        }

        String label = this.font.plainSubstrByWidth(pretty(cape), TILE_W - 6);
        int col = worn ? 0xFFE9D5FF : (hot ? Aura.TEXT : Aura.MUTED);
        g.centeredText(this.font, label, x + TILE_W / 2, y + TILE_H - 14, col);

        if (worn) {
            Aura.accentBar(g, x + 6, y1 - 3, x1 - 6, y1 - 2, 1.0F);
        }
    }

    // ------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int tile = tileAt(mx, my);
        if (tile >= 0) {
            String cape = capes().get(tile);
            if (!cape.equals(CapeRegistry.current())) {
                CapeRegistry.wear(cape);
            }
            return true;
        }
        if (Aura.hits(mx, my, prevX0, prevY0, prevX1, prevY1)) {
            dragging = true;
            return true;
        }
        int boxY = streamerToggle.getY() - 4 - 18;
        if (Aura.hits(mx, my, lx0 + 6, boxY, lx1 - 6, boxY + 18)) {
            super.mouseClicked(event, doubleClick);
            this.setFocused(nameBox);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            rotY += (float) dx * 2.5F;
            rotX = Math.max(-50.0F, Math.min(50.0F, rotX - (float) dy * 2.5F));
            lastDrag = Util.getMillis();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            lastDrag = Util.getMillis();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll -= (int) Math.round(sy * 16);
        clampScroll();
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
