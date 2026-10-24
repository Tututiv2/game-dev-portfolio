package com.auraqol.feature;

import com.auraqol.AuraQoL;
import com.auraqol.skyblock.Fmt;
import com.auraqol.skyblock.ProgressRate;
import com.auraqol.skyblock.SlayerQuest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * The slayer quest: boss and tier, a progress bar with the numbers, how much
 * is left and when it will spawn at the recent pace, then SLAY THE BOSS once
 * it is up. Read from the sidebar (the same reader the money tracker uses for
 * the purse) by the pure {@link SlayerQuest}; hidden when there is no quest.
 */
public final class SlayerHud {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "slayer");

    private static final SlayerQuest.Quest SAMPLE = new SlayerQuest.Quest(
            "Revenant Horror", 4, 1234, 1500, "Combat XP", SlayerQuest.State.PROGRESS);

    /** Rate over the last 2 minutes, shown once 8 s of it exist. */
    private static final ProgressRate RATE = new ProgressRate(120_000, 8_000);

    private static volatile SlayerQuest.Quest quest;
    private static int tick;
    private static boolean loggedFailure;

    private SlayerHud() {
    }

    public static void register() {
        Skyblock.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++tick % 10 != 0) {
                return;
            }
            try {
                SlayerQuest.Quest q = Skyblock.inSkyblock() ? SlayerQuest.parse(Skyblock.sidebar()) : null;
                quest = q;
                if (q != null && q.hasProgress()) {
                    RATE.sample(now(), q.title() + "|" + q.target(), q.current(), q.target());
                } else if (q == null) {
                    RATE.clear();
                }
            } catch (Throwable t) {
                quest = null;
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

    /** The current quest, or null (for the self test). */
    public static SlayerQuest.Quest current() {
        return quest;
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();
        if (mc == null || mc.player == null || !cfg.slayerEnabled) {
            return;
        }
        SlayerQuest.Quest q = quest;
        boolean sample = false;
        if (q == null) {
            if (!HudStyle.editing()) {
                return;
            }
            q = SAMPLE;
            sample = true;
        }
        Font font = mc.font;
        long now = now();

        String title = "Slayer";
        String right;
        int rightColor;
        int dotColor;
        String line3;
        int line3Color;
        String line4 = "";
        switch (q.state()) {
            case SPAWNED -> {
                right = "BOSS UP";
                rightColor = HudStyle.LOSS;
                dotColor = (now / 400) % 2 == 0 ? HudStyle.LOSS : 0xFF8A2020;
                line3 = "Slay the boss!";
                line3Color = HudStyle.LOSS;
            }
            case SLAIN -> {
                right = "SLAIN";
                rightColor = HudStyle.GAIN;
                dotColor = HudStyle.GAIN;
                line3 = "Boss slain!";
                line3Color = HudStyle.GAIN;
            }
            case PROGRESS -> {
                right = Math.round(q.fraction() * 100) + "%";
                rightColor = HudStyle.DIM;
                dotColor = HudStyle.ACCENT;
                line3 = Fmt.coins(q.current()) + " / " + Fmt.coins(q.target())
                        + (q.unit().isEmpty() ? "" : " " + q.unit());
                line3Color = cfg.textColor;
                double left = q.remaining();
                long eta = sample ? 25_000 : RATE.etaMs(now);
                line4 = Fmt.coins(left) + " left" + (eta >= 0 ? "  ~" + Fmt.duration(eta) : "");
            }
            default -> {
                right = "";
                rightColor = HudStyle.DIM;
                dotColor = HudStyle.IDLE;
                line3 = "Quest active";
                line3Color = HudStyle.DIM;
            }
        }
        String boss = q.title();

        int lh = font.lineHeight;
        int headerW = 6 + font.width(title) + 10 + font.width(right);
        int innerW = Math.max(120, Math.max(headerW,
                Math.max(font.width(boss), Math.max(font.width(line3), font.width(line4)))));
        int w = innerW + HudStyle.PAD * 2;
        // header + rule, boss, bar, numbers, [left / ETA]
        int h = HudStyle.PAD + (lh + 2) + 3 + (lh + 2) + 6 + lh
                + (line4.isEmpty() ? 0 : lh + 1) + HudStyle.PAD - 2;

        float sc = HudLayout.scale(HudLayout.SLAYER);
        HudLayout.reportSize(HudLayout.SLAYER, w, h);
        int[] p = HudLayout.place(HudLayout.SLAYER, g.guiWidth(), g.guiHeight());

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) p[0], (float) p[1]);
            pose.scale(sc, sc);
            HudStyle.panel(g, 0, 0, w, h);
            int x = HudStyle.PAD;
            int y = HudStyle.PAD;
            HudStyle.dot(g, x, y + 2, dotColor);
            g.text(font, title, x + 6, y, HudStyle.ACCENT);
            if (!right.isEmpty()) {
                g.text(font, right, w - HudStyle.PAD - font.width(right), y, rightColor);
            }
            y += lh + 2;
            HudStyle.rule(g, x, y, innerW);
            y += 3;
            g.text(font, boss, x, y, 0xFFFF6E6E);
            y += lh + 2;
            int barColor = q.state() == SlayerQuest.State.SPAWNED ? HudStyle.LOSS
                    : q.state() == SlayerQuest.State.SLAIN ? HudStyle.GAIN : HudStyle.ACCENT;
            HudStyle.bar(g, x, y, innerW, 3, q.fraction(), barColor);
            y += 3 + 3;
            g.text(font, line3, x, y, line3Color);
            if (!line4.isEmpty()) {
                y += lh + 1;
                g.text(font, line4, x, y, HudStyle.DIM);
            }
        } finally {
            pose.popMatrix();
        }
    }

    private static void fail(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            AuraQoL.LOG.warn("[auraqol] slayer panel failed once (further errors silenced)", t);
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
