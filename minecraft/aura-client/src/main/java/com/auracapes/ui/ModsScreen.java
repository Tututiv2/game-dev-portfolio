package com.auracapes.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.auracapes.mods.ModToggles;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * {@code /mods}: every mod with an on/off switch, plus the presets along the top.
 *
 * <p>A switch shows its new state straight away and says nothing about restarting: the owner asked
 * for no warning (2026-10-13). A jar that is already loaded still runs until the game closes,
 * because Fabric cannot unload a mod; the per-server desktop shortcuts are the no-restart answer.
 */
public class ModsScreen extends AuraScreen {

    private static final int ROW = 20;

    private int px0;
    private int px1;
    private int py0;
    private int py1;
    private int listTop;
    private int listRows;
    private int scroll;
    private List<ModToggles.ModView> mods = List.of();
    private final List<String> message = new ArrayList<>();

    public ModsScreen(Screen parent) {
        super(Component.literal("Mods"), parent);
    }

    @Override
    protected void init() {
        mods = ModToggles.list();
        int pw = Math.min(360, this.width - 24);
        px0 = (this.width - pw) / 2;
        px1 = px0 + pw;
        py0 = 12;
        py1 = this.height - 12;

        // Presets row.
        Map<String, ModToggles.Preset> presets = ModToggles.presets();
        int n = Math.max(1, presets.size());
        int gap = 6;
        int bw = (pw - 20 - gap * (n - 1)) / n;
        int x = px0 + 10;
        int presetY = py0 + 34;
        for (Map.Entry<String, ModToggles.Preset> e : presets.entrySet()) {
            String id = e.getKey();
            addRenderableWidget(new AuraButton(x, presetY, bw, 18, Component.literal(e.getValue().label()),
                    AuraButton.Style.PRIMARY, () -> show(ModToggles.applyPreset(id))));
            x += bw + gap;
        }

        listTop = presetY + 28;
        int footer = 44;
        listRows = Math.max(1, (py1 - footer - listTop) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, mods.size() - listRows)));
        for (int i = 0; i < listRows && scroll + i < mods.size(); i++) {
            ModToggles.ModView v = mods.get(scroll + i);
            int y = listTop + i * ROW;
            boolean locked = v.entry().locked();
            String key = v.entry().key();
            boolean on = v.onNextStart();
            AuraButton b = new AuraButton(px1 - 74, y + 1, 64, 16,
                    Component.literal(locked ? "Locked" : (on ? "Turn off" : "Turn on")),
                    on ? AuraButton.Style.DANGER : AuraButton.Style.SECONDARY,
                    () -> show(ModToggles.set(key, !on)));
            b.active = !locked;
            addRenderableWidget(b);
        }

        addRenderableWidget(new AuraButton((px0 + px1) / 2 - 45, py1 - 24, 90, 18,
                Component.literal("Done"), AuraButton.Style.SECONDARY, this::onClose));
    }

    private void show(List<String> lines) {
        message.clear();
        message.addAll(lines);
        this.rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int before = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), Math.max(0, mods.size() - listRows)));
        if (scroll != before) {
            this.rebuildWidgets();
        }
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        extractBackdrop(g);
        Aura.panel(g, px0, py0, px1, py1, 1.0F);
        int cx = (px0 + px1) / 2;
        extractHeading(g, "Installed Mods", cx, py0 + 8);
        g.centeredText(this.font, "Whole mods - they load or unload when Minecraft starts", cx, py0 + 22, Aura.FAINT);

        if (mods.isEmpty()) {
            g.centeredText(this.font, "No switchable mod jars found (dev client?)", cx, listTop + 6, Aura.MUTED);
        }
        for (int i = 0; i < listRows && scroll + i < mods.size(); i++) {
            ModToggles.ModView v = mods.get(scroll + i);
            int y = listTop + i * ROW;
            if (i % 2 == 0) {
                g.fill(px0 + 8, y, px1 - 8, y + ROW - 2, 0x18FFFFFF);
            }
            int dot = v.onNextStart() ? Aura.GOOD : Aura.BAD;
            g.fill(px0 + 14, y + 7, px0 + 18, y + 11, dot);
            g.text(this.font, v.entry().name(), px0 + 24, y + 2, Aura.TEXT);
            g.text(this.font, v.version(), px0 + 24, y + 11, Aura.FAINT);
        }
        if (mods.size() > listRows) {
            g.text(this.font, (scroll + 1) + "-" + Math.min(mods.size(), scroll + listRows) + " of " + mods.size()
                    + "  (scroll)", px0 + 12, listTop + listRows * ROW + 1, Aura.FAINT);
        }

        int my = py1 - 40;
        if (!message.isEmpty()) {
            String line = String.join("  ·  ", message);
            g.centeredText(this.font, this.font.plainSubstrByWidth(line, px1 - px0 - 20), cx, my, Aura.MUTED);
        }

        super.extractRenderState(g, mouseX, mouseY, a);
    }
}
