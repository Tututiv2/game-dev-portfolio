package com.auraqol.hotkeys;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code config/auraqol-hotkeys.json} to and from a list of hotkeys.
 *
 * <p>Reading is total: a missing file, broken JSON or one bad entry costs only
 * what is broken, never the rest - losing every macro because one line had a
 * typo would be worse than skipping that line. Unknown fields are ignored, so
 * an older build can read a newer file.
 */
public final class HotkeyCodec {

    /** More than anyone binds, low enough that a corrupt file cannot flood the screen. */
    public static final int MAX_HOTKEYS = 50;

    private HotkeyCodec() {
    }

    public static List<Hotkey> parse(String json) {
        List<Hotkey> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonArray arr;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("hotkeys")
                    && root.getAsJsonObject().get("hotkeys").isJsonArray()) {
                arr = root.getAsJsonObject().getAsJsonArray("hotkeys");
            } else {
                return out;
            }
        } catch (RuntimeException e) {
            return out;
        }
        for (JsonElement e : arr) {
            if (out.size() >= MAX_HOTKEYS) {
                break;
            }
            try {
                JsonObject o = e.getAsJsonObject();
                out.add(new Hotkey(
                        o.has("mouse") && o.get("mouse").getAsBoolean(),
                        o.has("code") ? o.get("code").getAsInt() : Hotkey.UNBOUND,
                        o.has("mods") ? o.get("mods").getAsInt() : 0,
                        o.has("text") ? o.get("text").getAsString() : "",
                        !o.has("enabled") || o.get("enabled").getAsBoolean()));
            } catch (RuntimeException ignored) {
                // One unreadable entry; keep every other one.
            }
        }
        return out;
    }

    public static String write(List<Hotkey> hotkeys) {
        JsonArray arr = new JsonArray();
        for (Hotkey h : hotkeys) {
            JsonObject o = new JsonObject();
            o.addProperty("mouse", h.mouse());
            o.addProperty("code", h.code());
            o.addProperty("mods", h.mods());
            o.addProperty("text", h.text());
            o.addProperty("enabled", h.enabled());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Chat hotkeys. code = GLFW key or mouse button, mods = 1 Shift | 2 Ctrl | 4 Alt. "
                + "Text starting with / runs as a command. Edit in game: /mods -> Chat Hotkeys.");
        root.add("hotkeys", arr);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }
}
