package com.auraqol.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.auraqol.AuraQoL;
import com.auraqol.compat.Mc;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * {@code /mods}: the Lunar-style library. One card per feature, click the bar to switch it, and it
 * takes effect THIS frame - these are features inside the Aura mods, not whole jars, so nothing ever
 * needs a restart. Whole-jar switching (SkyHanni, Sodium, ...) is behind "Installed Mods", which is
 * AuraCapes' screen; it is reached by reflection so either mod still works without the other.
 *
 * <p>Toggles go through {@link AuraCommands#toggle}, the same code as {@code /aq toggle}, so the
 * library, the command and the HUD editor's right-click can never disagree.
 */
public final class ModsLibrary extends Screen {

    /**
     * One card. {@code id} is an /aq toggle name, or {@code open:...} for a card that opens a screen.
     * {@code settings}, if set, is an {@code open:...} id reached through a gear in the corner - for a
     * feature that is both switched on/off AND has something to edit (Chat Hotkeys).
     */
    private record Card(String id, String title, String blurb, String tab, String settings) {
        Card(String id, String title, String blurb, String tab) {
            this(id, title, blurb, tab, null);
        }
    }

    private static final int GEAR = 16;

    private static final String[] TABS = {"All", "HUD", "Shadow Prison", "Hypixel", "Utility"};

    private static final List<Card> CARDS = List.of(
            new Card("info", "Info Panel", "FPS, coords, direction, ping, time, biome", "HUD"),
            new Card("cps", "CPS", "Clicks per second", "HUD"),
            new Card("keys", "Keystrokes", "WASD, mouse and space on screen", "HUD"),
            new Card("bps", "Blocks / Second", "Mining speed, with your session peak", "HUD"),
            new Card("todo", "To-Do List", "Your checklist on screen (/todo)", "HUD"),
            new Card(HudLayout.SP_EARNINGS, "Money / Hour", "Money you actually earned, by source", "Shadow Prison"),
            new Card(HudLayout.SP_MINING, "Mining Rates", "Blocks and value mined per hour", "Shadow Prison"),
            new Card(HudLayout.SP_RANKUP, "Rank-up ETA", "Time until you can rank up", "Shadow Prison"),
            new Card(HudLayout.SP_BOOSTS, "Boosts", "Active boosts and their timers", "Shadow Prison"),
            new Card(HudLayout.SP_CONTRACT, "Contracts", "Your Contract and its progress", "Shadow Prison"),
            new Card(HudLayout.SP_SKILLS, "Skills", "Skill XP per hour, time to next level", "Shadow Prison"),
            new Card(HudLayout.SP_PROFILE, "Profile Card", "/sp profile <name> lookups", "Shadow Prison"),
            new Card("money", "Money Tracker", "SkyBlock coins per hour, by source", "Hypixel"),
            new Card("sacks", "Sacks", "The crop or ore you are collecting", "Hypixel"),
            new Card("comms", "Commissions", "Mining commissions on screen", "Hypixel"),
            new Card("slayer", "Slayer", "Slayer quest bar and ETA", "Hypixel"),
            new Card("ability", "Pickaxe Ability", "Ready / active / cooldown", "Hypixel"),
            new Card("prices", "Price Tooltips", "Bazaar and lowest BIN on items", "Hypixel"),
            new Card("npc", "NPC Prices", "NPC sell line in price tooltips", "Hypixel"),
            new Card("recipebuy", "Recipe -> Bazaar", "Right-click an ingredient to /bz it", "Hypixel"),
            new Card("recipehint", "Recipe Hint", "Chat note after a recipe click", "Hypixel"),
            new Card("zoom", "Zoom", "Hold C to zoom", "Utility"),
            new Card("nametag", "Self Nametag", "See your own name in F5", "Utility"),
            new Card("hotkeys", "Chat Hotkeys", "Keys that send chat or a /command", "Utility",
                    "open:hotkeys"),
            new Card("open:cosmetics", "Cosmetics", "Capes - try them on", "Utility"),
            new Card("open:nickname", "Nickname", "Streamer name, your screen only", "Utility"),
            new Card("open:hud", "Edit HUD Layout", "Move and resize everything (])", "Utility"),
            new Card("open:slots", "Save Slots", "Save / load your whole HUD setup", "Utility"),
            new Card("open:jars", "Installed Mods", "Whole mods: Sodium, SkyHanni, ...", "Utility"));

    private static final int CARD_W = 122;
    private static final int CARD_H = 62;
    private static final int GAP = 8;

    private static final int ACCENT = 0xFF8B5CF6;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFA1A1B5;
    private static final int FAINT = 0xFF6B6B80;
    private static final int ON = 0xFF1F9D55;
    private static final int OFF = 0xFF3A2A33;

    private static String tab = "All";
    private final Screen parent;
    private EditBox search;
    private int px0;
    private int px1;
    private int py0;
    private int py1;
    private int gridTop;
    private int gridBottom;
    private int cols;
    private int gridX;
    private int scroll;

    private ModsLibrary(Screen parent) {
        super(Component.literal("Mods"));
        this.parent = parent;
    }

    /** From a chat command: open once chat has closed. */
    public static void openLater() {
        Minecraft mc = Minecraft.getInstance();
        mc.schedule(() -> Mc.setScreen(mc, new ModsLibrary(Mc.screen(mc))));
    }

    /** Called by AuraCapes' pause-menu "Mods" button (by reflection, so neither mod needs the other). */
    public static void open(Screen parent) {
        Mc.setScreen(Minecraft.getInstance(), new ModsLibrary(parent));
    }

    @Override
    protected void init() {
        int pw = Math.min(4 * CARD_W + 3 * GAP + 32, this.width - 20);
        px0 = (this.width - pw) / 2;
        px1 = px0 + pw;
        py0 = 10;
        py1 = this.height - 10;
        String keep = search == null ? "" : search.getValue();
        search = new EditBox(this.font, px1 - 136, py0 + 9, 124, 14, Component.literal("Search"));
        search.setValue(keep);
        search.setHint(Component.literal("Search mods...").withColor(FAINT));
        search.setResponder(s -> scroll = 0);
        addRenderableWidget(search);
        gridTop = py0 + 52;
        gridBottom = py1 - 8;
        cols = Math.max(1, (pw - 24 + GAP) / (CARD_W + GAP));
        int gw = cols * CARD_W + (cols - 1) * GAP;
        gridX = px0 + (pw - gw) / 2;
    }

    private List<Card> visible() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Card> out = new ArrayList<>();
        for (Card c : CARDS) {
            if (!tab.equals("All") && !c.tab().equals(tab)) {
                continue;
            }
            if (!q.isEmpty() && !(c.title() + " " + c.blurb()).toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (c.id().equals("open:jars") && !FabricLoader.getInstance().isModLoaded("auracapes")) {
                continue;
            }
            if ((c.id().equals("open:cosmetics") || c.id().equals("open:nickname"))
                    && !FabricLoader.getInstance().isModLoaded("auracapes")) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    private int rowsVisible() {
        return Math.max(1, (gridBottom - gridTop + GAP) / (CARD_H + GAP));
    }

    // ------------------------------------------------------------------ drawing

    /** Mouse position this frame, so a card can tell whether its gear (not just the card) is hovered. */
    private int lastMouseX;
    private int lastMouseY;

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        g.fill(0, 0, this.width, this.height, 0x90000008);
        g.fill(px0, py0, px1, py1, 0xE00C0C14);
        g.fill(px0, py0, px1, py0 + 1, 0x40FFFFFF);
        g.text(this.font, "MODS", px0 + 14, py0 + 12, TEXT);
        g.fill(px0 + 14, py0 + 23, px0 + 14 + this.font.width("MODS"), py0 + 24, ACCENT);

        // Tabs.
        int tx = px0 + 14;
        int ty = py0 + 32;
        for (String t : TABS) {
            int w = this.font.width(t) + 14;
            boolean sel = t.equals(tab);
            boolean hover = hit(mouseX, mouseY, tx, ty, tx + w, ty + 14);
            g.fill(tx, ty, tx + w, ty + 14, sel ? ACCENT : (hover ? 0x40FFFFFF : 0x20FFFFFF));
            g.text(this.font, t, tx + 7, ty + 3, sel ? TEXT : MUTED);
            tx += w + 4;
        }

        // Cards.
        List<Card> list = visible();
        int rows = (list.size() + cols - 1) / cols;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - rowsVisible())));
        for (int i = scroll * cols; i < list.size(); i++) {
            int r = i / cols - scroll;
            if (r >= rowsVisible()) {
                break;
            }
            int x = gridX + (i % cols) * (CARD_W + GAP);
            int y = gridTop + r * (CARD_H + GAP);
            drawCard(g, list.get(i), x, y, hit(mouseX, mouseY, x, y, x + CARD_W, y + CARD_H));
        }
        if (list.isEmpty()) {
            g.centeredText(this.font, "Nothing matches", (px0 + px1) / 2, gridTop + 20, FAINT);
        }
        if (rows > rowsVisible()) {
            g.text(this.font, "scroll for more", px1 - 12 - this.font.width("scroll for more"), py1 - 9, FAINT);
        }
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private void drawCard(GuiGraphicsExtractor g, Card c, int x, int y, boolean hover) {
        g.fill(x, y, x + CARD_W, y + CARD_H, hover ? 0xD0232335 : 0xC0161622);
        g.fill(x, y, x + CARD_W, y + 1, 0x30FFFFFF);
        g.text(this.font, c.title(), x + 7, y + 7, TEXT);
        List<FormattedCharSequence> lines = this.font.split(Component.literal(c.blurb()), CARD_W - 14);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.text(this.font, lines.get(i), x + 7, y + 19 + i * 10, FAINT);
        }
        String here = contextNote(c);
        int barY = y + CARD_H - 15;
        if (c.settings() != null) {
            int gx = x + CARD_W - GEAR;
            boolean gHover = hover && hit(lastMouseX, lastMouseY, gx, y, x + CARD_W, y + GEAR);
            g.fill(gx, y, x + CARD_W, y + GEAR, gHover ? 0xFF4C3A8C : 0x20FFFFFF);
            g.centeredText(this.font, "\u2699", gx + GEAR / 2, y + 4, gHover ? TEXT : MUTED);
        }
        if (c.id().startsWith("open:")) {
            g.fill(x, barY, x + CARD_W, y + CARD_H, hover ? 0xFF4C3A8C : 0xFF2E2650);
            g.centeredText(this.font, "OPEN", x + CARD_W / 2, barY + 4, TEXT);
            return;
        }
        boolean on = AuraCommands.isOn(c.id());
        g.fill(x, barY, x + CARD_W, y + CARD_H, on ? (hover ? 0xFF25B863 : ON) : (hover ? 0xFF4A3440 : OFF));
        g.centeredText(this.font, on ? "ENABLED" : "DISABLED", x + CARD_W / 2, barY + 4, on ? TEXT : MUTED);
        if (here != null) {
            g.text(this.font, here, x + CARD_W - 7 - this.font.width(here), y + 7, 0xFF4ADE80);
        }
    }

    /** A small "here" mark on cards whose server is the one you are on, so you know they are live now. */
    private static String contextNote(Card c) {
        if (c.tab().equals("Shadow Prison") && ShadowLive.active()) {
            return "●";
        }
        if (c.tab().equals("Hypixel") && Skyblock.onHypixel()) {
            return "●";
        }
        return null;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        double mx = e.x();
        double my = e.y();
        int tx = px0 + 14;
        int ty = py0 + 32;
        for (String t : TABS) {
            int w = this.font.width(t) + 14;
            if (hit(mx, my, tx, ty, tx + w, ty + 14)) {
                tab = t;
                scroll = 0;
                return true;
            }
            tx += w + 4;
        }
        List<Card> list = visible();
        for (int i = scroll * cols; i < list.size(); i++) {
            int r = i / cols - scroll;
            if (r >= rowsVisible()) {
                break;
            }
            int x = gridX + (i % cols) * (CARD_W + GAP);
            int y = gridTop + r * (CARD_H + GAP);
            if (hit(mx, my, x, y, x + CARD_W, y + CARD_H)) {
                Card c = list.get(i);
                if (c.settings() != null && hit(mx, my, x + CARD_W - GEAR, y, x + CARD_W, y + GEAR)) {
                    activate(new Card(c.settings(), c.title(), c.blurb(), c.tab()));
                } else {
                    activate(c);
                }
                return true;
            }
        }
        return super.mouseClicked(e, dbl);
    }

    private void activate(Card c) {
        switch (c.id()) {
            case "open:hud" -> Mc.setScreen(this.minecraft, new HudEditor());
            case "open:slots" -> Mc.setScreen(this.minecraft, new SlotsScreen(this));
            case "open:hotkeys" -> Mc.setScreen(this.minecraft, new HotkeysScreen(this));
            case "open:jars" -> openOther("com.auracapes.ui.ModsScreen");
            case "open:nickname" -> openOther("com.auracapes.ui.NicknameScreen");
            case "open:cosmetics" -> openOther("com.auracapes.CapeLibraryScreen");
            default -> AuraCommands.toggle(c.id());
        }
    }

    /** AuraCapes screens take their parent screen as the only constructor argument. */
    private void openOther(String className) {
        try {
            Screen s = (Screen) Class.forName(className).getConstructor(Screen.class).newInstance(this);
            Mc.setScreen(this.minecraft, s);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            AuraQoL.LOG.warn("[auraqol] /mods could not open {}", className, ex);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        scroll = Math.max(0, scroll - (int) Math.signum(sy));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Mc.setScreen(this.minecraft, parent);
    }

    private static boolean hit(double mx, double my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }
}
