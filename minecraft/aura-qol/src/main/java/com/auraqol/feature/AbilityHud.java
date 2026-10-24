package com.auraqol.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.auraqol.AuraQoL;
import com.auraqol.skyblock.PickaxeAbility;
import com.auraqol.skyblock.SbItem;
import com.auraqol.skyblock.TabWidgets;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.joml.Matrix3x2fStack;

/**
 * Pickaxe ability timer: the ability's name, then READY, ACTIVE (with time
 * left when the tool's lore says how long it lasts) or a cooldown countdown.
 *
 * <p>All the trust rules live in the pure {@link PickaxeAbility}: server chat
 * lines win, the tab list's Pickaxe Ability widget keeps the countdown in
 * sync, the held tool's lore only seeds a countdown nothing else has given.
 * Both normal and CANCELLED chat are read (SkyHanni/Skyblocker can hide the
 * ability messages), and action-bar lines too.
 *
 * <p>Shown on the mining islands; elsewhere only while the ability is running
 * or cooling down (you used it on the way out of the Mines).
 */
public final class AbilityHud {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("auraqol", "pickaxe_ability");

    private static final PickaxeAbility STATE = new PickaxeAbility();

    private static int tick;
    private static ItemStack lastHeld = ItemStack.EMPTY;
    private static boolean loggedFailure;

    private AbilityHud() {
    }

    public static void register() {
        Skyblock.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++tick % 10 != 0 || mc.player == null) {
                return;
            }
            try {
                if (!Skyblock.inSkyblock()) {
                    return;
                }
                STATE.tab(now(), TabWidgets.pickaxeAbility(Skyblock.tabLines()));
                ItemStack held = mc.player.getMainHandItem();
                if (held != lastHeld) {
                    lastHeld = held;
                    if (isMiningTool(held)) {
                        STATE.lore(PickaxeAbility.parseLore(lore(held)));
                    }
                }
            } catch (Throwable t) {
                fail(t);
            }
        });
        ClientReceiveMessageEvents.GAME.register(AbilityHud::chat);
        ClientReceiveMessageEvents.GAME_CANCELED.register(AbilityHud::chat);
        HudElementRegistry.addLast(ID, (g, delta) -> {
            try {
                draw(g);
            } catch (Throwable t) {
                fail(t);
            }
        });
    }

    /** What the panel shows right now (for the self test). */
    public static PickaxeAbility.View view() {
        return STATE.view(now());
    }

    private static void chat(Component msg, boolean overlay) {
        try {
            if (msg == null || !Skyblock.inSkyblock()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            boolean tool = mc.player != null && isMiningTool(mc.player.getMainHandItem());
            STATE.chat(now(), msg.getString(), tool);
        } catch (Throwable t) {
            fail(t);
        }
    }

    static List<String> lore(ItemStack stack) {
        List<String> out = new ArrayList<>();
        ItemLore l = stack.get(DataComponents.LORE);
        if (l != null) {
            for (Component c : l.lines()) {
                out.add(c.getString());
            }
        }
        return out;
    }

    /** A pickaxe, drill or gauntlet - anything that carries a pickaxe ability. */
    static boolean isMiningTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        SbItem it = SkyblockItems.read(stack);
        if (it != null && it.id() != null) {
            String id = it.id().toUpperCase(Locale.ROOT);
            if (id.contains("PICKAXE") || id.contains("DRILL") || id.contains("GAUNTLET") || id.contains("PICKONIMBUS")) {
                return true;
            }
        }
        for (String line : lore(stack)) {
            if (TabWidgets.clean(line).startsWith("Breaking Power")) {
                return true;
            }
        }
        return false;
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        HudConfig cfg = HudConfig.get();
        if (mc == null || mc.player == null || !cfg.abilityEnabled) {
            return;
        }
        long now = now();
        PickaxeAbility.View v = STATE.view(now);
        boolean show = v.phase() != PickaxeAbility.Phase.NONE && Skyblock.inSkyblock()
                && (TabWidgets.isMiningArea(CommissionsHud.area()) || !CommissionsHud.current().isEmpty()
                || STATE.busy(now));
        if (!show) {
            if (!HudStyle.editing()) {
                return;
            }
            v = new PickaxeAbility.View("Mining Speed Boost", PickaxeAbility.Phase.COOLDOWN, 65_000, 0);
        }
        Font font = mc.font;
        String status;
        int statusColor;
        switch (v.phase()) {
            case ACTIVE -> {
                status = "ACTIVE " + (v.msLeft() >= 0 ? HudStyle.countdown(v.msLeft())
                        : HudStyle.countdown(v.activeMs()));
                statusColor = HudStyle.ACTIVE;
            }
            case COOLDOWN -> {
                status = v.msLeft() >= 0 ? HudStyle.countdown(v.msLeft()) : "cooling down";
                statusColor = HudStyle.WARN;
            }
            default -> {
                status = "READY";
                statusColor = HudStyle.GAIN;
            }
        }
        String name = v.name();
        int w = HudStyle.PAD + 6 + font.width(name) + 10 + font.width(status) + HudStyle.PAD;
        int h = font.lineHeight + 5;

        float sc = HudLayout.scale(HudLayout.ABILITY);
        HudLayout.reportSize(HudLayout.ABILITY, w, h);
        int[] p = HudLayout.place(HudLayout.ABILITY, g.guiWidth(), g.guiHeight());

        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        try {
            pose.translate((float) p[0], (float) p[1]);
            pose.scale(sc, sc);
            HudStyle.panel(g, 0, 0, w, h);
            int x = HudStyle.PAD;
            int y = 3;
            HudStyle.dot(g, x, y + 2, statusColor);
            g.text(font, name, x + 6, y, cfg.textColor);
            g.text(font, status, w - HudStyle.PAD - font.width(status), y, statusColor);
        } finally {
            pose.popMatrix();
        }
    }

    private static void fail(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            AuraQoL.LOG.warn("[auraqol] pickaxe ability timer failed once (further errors silenced)", t);
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }
}
