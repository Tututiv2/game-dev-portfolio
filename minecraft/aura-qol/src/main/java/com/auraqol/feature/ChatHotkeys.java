package com.auraqol.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.auraqol.AuraQoL;
import com.auraqol.compat.Mc;
import com.auraqol.hotkeys.Hotkey;
import com.auraqol.hotkeys.HotkeyCodec;
import com.auraqol.hotkeys.HotkeyEngine;
import com.auraqol.hotkeys.Outgoing;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Chat hotkeys, Lunar's "Auto Text Hotkey": press a key (or mouse button) in
 * game and it says something in chat or runs a command. Edited in
 * {@link HotkeysScreen}, stored in {@code config/auraqol-hotkeys.json}.
 *
 * <p><b>Fed by the real press events</b> ({@code mixin/HotkeyInputMixin} on
 * {@code KeyboardHandler.keyPress} and {@code MouseHandler.onButton}), not by
 * polling key state each tick. A poll at 20 Hz can miss a quick tap entirely;
 * the event cannot. Only PRESS fires - key-repeat and release are ignored - so
 * holding the key sends once.
 *
 * <p><b>Never while a screen is open.</b> Typing the letter G into chat, a sign
 * or the hotkey screen itself must not send your G macro. Also skipped while
 * F3 is held, so F3+G (chunk borders) does not fire a G hotkey.
 *
 * <p><b>The key is not swallowed</b>: if G is also a vanilla or mod key, both
 * happen. The screen warns when that is the case instead of guessing which one
 * you meant.
 *
 * <p>All the decisions (matching, modifiers, chat vs command) are in the pure {@code com.auraqol.hotkeys} package and tested there.
 */
public final class ChatHotkeys {

    private static final int GLFW_PRESS = 1;

    private static List<Hotkey> hotkeys = new ArrayList<>();

    private ChatHotkeys() {
    }

    public static void register() {
        load();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("auraqol-hotkeys.json");
    }

    public static void load() {
        Path p = file();
        if (!Files.exists(p)) {
            hotkeys = new ArrayList<>();
            return;
        }
        try {
            hotkeys = new ArrayList<>(HotkeyCodec.parse(Files.readString(p, StandardCharsets.UTF_8)));
            AuraQoL.LOG.info("[auraqol] {} chat hotkey(s) loaded", hotkeys.size());
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] could not read {}: {}", p, e.toString());
        }
    }

    public static List<Hotkey> list() {
        return new ArrayList<>(hotkeys);
    }

    /** Replace the whole list and write it to disk. */
    public static void set(List<Hotkey> list) {
        hotkeys = new ArrayList<>(list);
        Path p = file();
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, HotkeyCodec.write(hotkeys), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AuraQoL.LOG.warn("[auraqol] could not write {}: {}", p, e.toString());
        }
    }

    /** Called from the input mixin for every key and mouse event. */
    public static void onInput(boolean mouse, int code, int mods, int action) {
        if (action != GLFW_PRESS || hotkeys.isEmpty() || !HudConfig.get().hotkeysEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || Mc.screen(mc) != null) {
            return;
        }
        if (!mouse && InputConstants.isKeyDown(mc.getWindow(), InputConstants.KEY_F3)) {
            return;
        }
        List<Outgoing> out = HotkeyEngine.press(hotkeys, mouse, code, mods);
        for (Outgoing o : out) {
            try {
                if (o.command()) {
                    mc.player.connection.sendCommand(o.body());
                } else {
                    mc.player.connection.sendChat(o.body());
                }
            } catch (RuntimeException e) {
                AuraQoL.LOG.warn("[auraqol] hotkey could not send '{}': {}", o.body(), e.toString());
            }
        }
    }

    // ------------------------------------------------------------ for the screen

    /** "Ctrl+Shift+G", "Mouse 4", or "Not bound". */
    public static String keyName(Hotkey h) {
        if (!h.bound()) {
            return "Not bound";
        }
        StringBuilder sb = new StringBuilder();
        if ((h.mods() & Hotkey.CTRL) != 0) {
            sb.append("Ctrl+");
        }
        if ((h.mods() & Hotkey.SHIFT) != 0) {
            sb.append("Shift+");
        }
        if ((h.mods() & Hotkey.ALT) != 0) {
            sb.append("Alt+");
        }
        InputConstants.Type type = h.mouse() ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM;
        sb.append(type.getOrCreate(h.code()).getDisplayName().getString());
        return sb.toString();
    }

    /**
     * The name of a Controls entry that already uses this key, or null. Only
     * the base key is compared: vanilla keys mostly ignore Ctrl/Shift, so
     * Ctrl+Q still drops your stack even with a Ctrl+Q hotkey.
     */
    public static String conflict(Hotkey h) {
        if (!h.bound()) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        for (KeyMapping km : mc.options.keyMappings) {
            if (km.isUnbound()) {
                continue;
            }
            boolean same = h.mouse()
                    ? Mc.boundToMouse(km, h.code())
                    : km.matches(new KeyEvent(h.code(), 0, 0));
            if (same) {
                return Component.translatable(km.getName()).getString();
            }
        }
        return null;
    }
}
