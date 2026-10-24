package com.auraqol.feature;

import java.util.List;

import com.auraqol.AuraQoL;
import com.auraqol.skyblock.TabWidgets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * Commission progress, read from Hypixel's TAB-list Commissions widget
 * (Dwarven Mines, Crystal Hollows, Glacite Tunnels / Mineshafts).
 *
 * <p>Parsing lives in the pure {@link TabWidgets#commissions}. The panel only
 * exists while that widget does: off a mining island, or with the widget
 * turned off in Hypixel's /tab settings, nothing is drawn - except in the HUD
 * editor, where a sample is drawn so the box can be placed.
 */
public final class CommissionsHud {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "commissions");

    private static final List<TabWidgets.Commission> SAMPLE = List.of(
            new TabWidgets.Commission("Mithril Miner", 45, false, "45%"),
            new TabWidgets.Commission("Goblin Slayer", 100, true, "DONE"),
            new TabWidgets.Commission("Royal Mines Titanium", 12.5, false, "12.5%"));

    private static volatile List<TabWidgets.Commission> comms = List.of();
    private static volatile String area;
    private static int tick;
    private static boolean loggedFailure;

    private CommissionsHud() {
    }

    public static void register() {
        Skyblock.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++tick % 10 != 0) {
                return;
            }
            try {
                List<String> tab = Skyblock.tabLines();
                List<TabWidgets.Commission> c = List.of();
                if (Skyblock.inSkyblock()) {
                    c = TabWidgets.commissions(tab);
                    if (c.isEmpty()) {
                        // Same widget shape if Hypixel (or a mod) puts it on the sidebar.
                        c = TabWidgets.commissions(Skyblock.sidebar());
                    }
                }
                comms = c;
                area = TabWidgets.area(tab);
            } catch (Throwable t) {
                comms = List.of();
                fail(t);
            }
        });
        HudElementRegistry.addLast(ID, (g, delta) -> {
            try {
                draw(g);
            } catch (Throwable t) {
                fail(t);
            }
        });
    }

    /** Current commissions (for /aq comms and the self test). */
    public static List<TabWidgets.Commission> current() {
        return comms;
    }

    /** "Area: ..." from the tab list, or null. */
    static String area() {
        return area;
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();
        if (mc == null || mc.player == null || !cfg.commsEnabled) {
            return;
        }
        List<TabWidgets.Commission> list = comms;
        if (list.isEmpty()) {
            if (!HudStyle.editing()) {
                return;
            }
            list = SAMPLE;
        }
        Font font = mc.font;
        int done = 0;
        int nameW = 0;
        int progW = 0;
        for (TabWidgets.Commission c : list) {
            if (c.done()) {
                done++;
            }
            nameW = Math.max(nameW, font.width(c.name()));
            progW = Math.max(progW, font.width(c.progress()));
        }
        String title = "Commissions";
        String right = done + "/" + list.size();
        int lh = font.lineHeight;
        int rowH = lh + 4; // text, 1px gap, 2px bar, 1px gap
        int headerW = 6 + font.width(title) + 10 + font.width(right);
        int innerW = Math.max(120, Math.max(headerW, Math.min(170, nameW) + 10 + progW));
        int w = innerW + HudStyle.PAD * 2;
        int h = HudStyle.PAD + lh + 2 + 3 + list.size() * rowH + HudStyle.PAD - 2;

        float sc = HudLayout.scale(HudLayout.COMMS);
        HudLayout.reportSize(HudLayout.COMMS, w, h);
        int[] p = HudLayout.place(HudLayout.COMMS, g.guiWidth(), g.guiHeight());

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) p[0], (float) p[1]);
            pose.scale(sc, sc);
            HudStyle.panel(g, 0, 0, w, h);
            int x = HudStyle.PAD;
            int y = HudStyle.PAD;
            boolean all = done == list.size();
            HudStyle.dot(g, x, y + 2, all ? HudStyle.GAIN : HudStyle.ACCENT);
            g.text(font, title, x + 6, y, HudStyle.ACCENT);
            g.text(font, right, w - HudStyle.PAD - font.width(right), y, done > 0 ? HudStyle.GAIN : HudStyle.DIM);
            y += lh + 2;
            HudStyle.rule(g, x, y, innerW);
            y += 3;
            for (TabWidgets.Commission c : list) {
                int nameColor = c.done() ? HudStyle.GAIN : cfg.textColor;
                String name = HudStyle.ellipsize(font, c.name(), innerW - progW - 10);
                g.text(font, name, x, y, nameColor);
                int pc = c.done() ? HudStyle.GAIN : c.percent() >= 75 ? 0xFFFFFF55 : HudStyle.COIN;
                g.text(font, c.progress(), w - HudStyle.PAD - font.width(c.progress()), y, pc);
                HudStyle.bar(g, x, y + lh + 1, innerW, 2, c.percent() / 100.0,
                        c.done() ? HudStyle.GAIN : HudStyle.ACCENT);
                y += rowH;
            }
        } finally {
            pose.popMatrix();
        }
    }

    private static void fail(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            AuraQoL.LOG.warn("[auraqol] commissions panel failed once (further errors silenced)", t);
        }
    }
}
