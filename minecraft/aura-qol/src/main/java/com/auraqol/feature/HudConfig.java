package com.auraqol.feature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.loader.api.FabricLoader;

import com.auraqol.AuraQoL;

/**
 * Settings for {@link InfoHud}, persisted to {@code config/auraqol-hud.json}.
 *
 * <p><b>Why this exists at all.</b> The HUD is the one feature the owner will
 * want to move: a corner that is perfect on a 1080p singleplayer world is
 * sitting on top of a server scoreboard the moment he joins one. A HUD you
 * cannot move is a HUD you turn off, so corner, scale and every individual
 * readout are data rather than constants.
 *
 * <p><b>Why the JSON is hand-parsed rather than Gson.</b> Gson is on the
 * runtime classpath (Minecraft itself uses it) but it is a transitive
 * dependency, not one this mod declares, and a config file is not worth
 * inheriting somebody else's version pin. The file is a flat object of
 * booleans, numbers and strings; that grammar fits in eighty lines and it can
 * be made total — {@link #load()} cannot throw, whatever is in the file.
 * That property matters more than the parser being clever: a mod that
 * refuses to start because someone typed a stray comma into a config is a
 * worse mod than one that quietly reverts to defaults and says so in the log.
 *
 * <p><b>Failure policy.</b> Missing file, unreadable file, truncated JSON,
 * a string where a number belongs, an unknown corner name — every one of
 * these falls back to the default for that single field. Nothing here
 * propagates an exception, and nothing here aborts the rest of the parse
 * because one key was bad.
 *
 * <p><b>API verified from the jar</b> (26.2 ships deobfuscated, so these are
 * the real names): {@code net.fabricmc.loader.api.FabricLoader.getInstance()}
 * and its instance method {@code getConfigDir()} returning a
 * {@link java.nio.file.Path} — checked with {@code javap -p -s} against
 * {@code fabric-loader-0.19.3.jar}.
 */
public final class HudConfig {

    /** Where the stack is anchored. Stored in JSON by {@link #name()}, lower-cased. */
    public enum Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;

        static Corner parse(String raw, Corner fallback) {
            if (raw == null) {
                return fallback;
            }
            for (Corner c : values()) {
                if (c.name().equalsIgnoreCase(raw.trim())) {
                    return c;
                }
            }
            return fallback;
        }
    }

    /** The config file name. Prefixed so it is obvious whose it is in a shared config dir. */
    private static final String FILE_NAME = "auraqol-hud.json";

    /** Lazily built, then reused. The HUD reads this every frame; it must not touch the disk there. */
    private static volatile HudConfig instance;

    // --- what to show -------------------------------------------------------

    /** Master switch. False draws nothing at all, without unregistering the element. */
    public boolean enabled = true;
    public boolean showFps = true;
    public boolean showCoords = true;
    /** Sub-toggle of {@link #showCoords}: the "North (-Z)" line under the numbers. */
    public boolean showDirection = true;
    /** Ping is meaningless without a server, so it is skipped in singleplayer regardless. */
    public boolean showPing = true;
    public boolean showTime = true;
    public boolean showBiome = true;

    // --- which elements exist at all ---------------------------------------
    // Each one is individually switchable (/aq toggle <name>, or right-click
    // it in the HUD editor) because SkyHanni and Skyblocker already draw some
    // of the same things, and the owner decides per feature which mod wins.

    public boolean cpsEnabled = true;
    public boolean keysEnabled = true;
    /** Zoom on C. Off = the key does nothing (the mapping stays registered). */
    public boolean zoomEnabled = true;
    /** Your own nametag over your head in F5. */
    public boolean selfNametagEnabled = true;
    /** Chat hotkeys (keys that say something or run a command). The bindings live in auraqol-hotkeys.json. */
    public boolean hotkeysEnabled = true;
    /** Blocks-per-second readout. */
    public boolean bpsEnabled = true;
    /** Coins per hour / minute tracker. */
    public boolean moneyEnabled = true;
    /** The to-do list panel. */
    public boolean todoEnabled = true;
    /** Commission progress panel (mining islands only). */
    public boolean commsEnabled = true;
    /** Slayer quest panel (only while a quest is active). */
    public boolean slayerEnabled = true;
    /** Pickaxe ability READY / ACTIVE / cooldown panel. */
    public boolean abilityEnabled = true;
    /** Bazaar / lowest BIN / NPC lines on SkyBlock item tooltips. */
    public boolean priceTooltip = true;
    /** Sub-toggle of {@link #priceTooltip}: the NPC sell line. */
    public boolean priceTooltipNpc = true;
    /**
     * Recipe -> Bazaar: right-click an ingredient in a SkyBlock recipe menu to
     * type /bz (or /ahs) for it. Hold Ctrl to right-click normally.
     */
    public boolean recipeBuy = true;
    /** Sub-toggle of {@link #recipeBuy}: the one-line chat note after each command. */
    public boolean recipeBuyHint = true;
    /** Value items you gain (inventory + sacks), not just purse coins. */
    public boolean moneyCountItems = true;
    /** Seconds without any activity before the money clock stops. */
    public int moneyIdleSeconds = 60;
    /** Money panel headline: true = rolling rate over the last {@link #moneyWindowMinutes}, false = session average. */
    public boolean moneyRecent = true;
    /** Rolling window for {@link #moneyRecent}, in minutes of ACTIVE time: 1, 5, 10 or 30. */
    public int moneyWindowMinutes = 10;
    /** Per-source rows under the money panel (crops/sacks, rare drops, pest loot, purse, other). */
    public boolean moneyBreakdown = true;
    /** /money log: append every money decision to config/auraqol-money-log.txt. */
    public boolean moneyLog = false;
    /** Sacks panel: sack count of the crop/ore you are collecting. */
    public boolean sacksEnabled = true;
    /** Done to-do items stay visible (struck through) rather than vanishing. */
    public boolean todoShowDone = true;
    /**
     * Only download prices while connected to a Hypixel address. Off means
     * "always", e.g. for an alpha/proxy address that does not say hypixel.
     */
    public boolean pricesOnlyOnHypixel = true;

    // --- Shadow Prison live panels (only ever drawn after the server's WELCOME) ---
    public boolean spMiningEnabled = true;
    public boolean spEarningsEnabled = true;
    public boolean spRankupEnabled = true;
    public boolean spBoostsEnabled = true;
    public boolean spContractEnabled = true;
    public boolean spSkillsEnabled = true;
    public boolean spProfileEnabled = true;
    /** Shadow rates: true = last {@link #spWindowMinutes} of active play, false = session average. */
    public boolean spRecent = true;
    /** Rolling window for {@link #spRecent}, in minutes of ACTIVE time: 1, 5, 10 or 30. */
    public int spWindowMinutes = 10;

    // --- where and how big --------------------------------------------------

    public Corner corner = Corner.TOP_LEFT;
    /** Clamped to a sane band on load; a scale of 0 would make the box invisible and unfixable in-game. */
    public float scale = 1.0f;
    /** Gap between the box and the screen edge, in scaled pixels. */
    public int margin = 4;
    /** Gap between the box edge and the text inside it. */
    public int padding = 3;

    // --- colours ------------------------------------------------------------

    /** Whether to draw the dark backing box. The whole point of it is snow and sand. */
    public boolean background = true;
    /** ARGB. Default is ~50% black: readable over white, still shows the world through it. */
    public int backgroundColor = 0x80000000;
    /** ARGB. */
    public int textColor = 0xFFFFFFFF;
    /** ARGB, used for the label half of "Ping:" style lines so the numbers read first. */
    public int labelColor = 0xFFA0A0A0;

    private HudConfig() {
    }

    /**
     * The live config. Loads from disk on first call and never again — a HUD
     * that stats a file sixty times a second is a HUD that costs frames.
     */
    public static HudConfig get() {
        HudConfig local = instance;
        if (local == null) {
            synchronized (HudConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Forget the cached copy so the next {@link #get()} re-reads the file. */
    public static void reload() {
        synchronized (HudConfig.class) {
            instance = null;
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * Read the file, or produce defaults. Total: this method has no failure
     * mode that reaches the caller.
     */
    public static HudConfig load() {
        HudConfig cfg = new HudConfig();
        try {
            Path path = file();
            if (!Files.isRegularFile(path)) {
                // First run. Write the defaults out so there is something to edit.
                cfg.save();
                return cfg;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, String> raw = Json.parseFlatObject(text);
            cfg.applyFrom(raw);
        } catch (Throwable t) {
            AuraQoL.LOG.warn("[auraqol] hud config unreadable, using defaults: {}", t.toString());
        }
        cfg.clamp();
        return cfg;
    }

    private void applyFrom(Map<String, String> raw) {
        enabled = Json.bool(raw, "enabled", enabled);
        showFps = Json.bool(raw, "showFps", showFps);
        showCoords = Json.bool(raw, "showCoords", showCoords);
        showDirection = Json.bool(raw, "showDirection", showDirection);
        showPing = Json.bool(raw, "showPing", showPing);
        showTime = Json.bool(raw, "showTime", showTime);
        showBiome = Json.bool(raw, "showBiome", showBiome);
        cpsEnabled = Json.bool(raw, "cpsEnabled", cpsEnabled);
        keysEnabled = Json.bool(raw, "keysEnabled", keysEnabled);
        zoomEnabled = Json.bool(raw, "zoomEnabled", zoomEnabled);
        selfNametagEnabled = Json.bool(raw, "selfNametagEnabled", selfNametagEnabled);
        hotkeysEnabled = Json.bool(raw, "hotkeysEnabled", hotkeysEnabled);
        bpsEnabled = Json.bool(raw, "bpsEnabled", bpsEnabled);
        moneyEnabled = Json.bool(raw, "moneyEnabled", moneyEnabled);
        todoEnabled = Json.bool(raw, "todoEnabled", todoEnabled);
        commsEnabled = Json.bool(raw, "commsEnabled", commsEnabled);
        slayerEnabled = Json.bool(raw, "slayerEnabled", slayerEnabled);
        abilityEnabled = Json.bool(raw, "abilityEnabled", abilityEnabled);
        priceTooltip = Json.bool(raw, "priceTooltip", priceTooltip);
        priceTooltipNpc = Json.bool(raw, "priceTooltipNpc", priceTooltipNpc);
        recipeBuy = Json.bool(raw, "recipeBuy", recipeBuy);
        recipeBuyHint = Json.bool(raw, "recipeBuyHint", recipeBuyHint);
        moneyCountItems = Json.bool(raw, "moneyCountItems", moneyCountItems);
        moneyIdleSeconds = (int) Json.number(raw, "moneyIdleSeconds", moneyIdleSeconds);
        moneyRecent = Json.bool(raw, "moneyRecent", moneyRecent);
        moneyWindowMinutes = (int) Json.number(raw, "moneyWindowMinutes", moneyWindowMinutes);
        moneyBreakdown = Json.bool(raw, "moneyBreakdown", moneyBreakdown);
        moneyLog = Json.bool(raw, "moneyLog", moneyLog);
        sacksEnabled = Json.bool(raw, "sacksEnabled", sacksEnabled);
        todoShowDone = Json.bool(raw, "todoShowDone", todoShowDone);
        pricesOnlyOnHypixel = Json.bool(raw, "pricesOnlyOnHypixel", pricesOnlyOnHypixel);
        spMiningEnabled = Json.bool(raw, "spMiningEnabled", spMiningEnabled);
        spEarningsEnabled = Json.bool(raw, "spEarningsEnabled", spEarningsEnabled);
        spRankupEnabled = Json.bool(raw, "spRankupEnabled", spRankupEnabled);
        spBoostsEnabled = Json.bool(raw, "spBoostsEnabled", spBoostsEnabled);
        spContractEnabled = Json.bool(raw, "spContractEnabled", spContractEnabled);
        spSkillsEnabled = Json.bool(raw, "spSkillsEnabled", spSkillsEnabled);
        spProfileEnabled = Json.bool(raw, "spProfileEnabled", spProfileEnabled);
        spRecent = Json.bool(raw, "spRecent", spRecent);
        spWindowMinutes = (int) Json.number(raw, "spWindowMinutes", spWindowMinutes);

        corner = Corner.parse(raw.get("corner"), corner);
        scale = (float) Json.number(raw, "scale", scale);
        margin = (int) Json.number(raw, "margin", margin);
        padding = (int) Json.number(raw, "padding", padding);

        background = Json.bool(raw, "background", background);
        backgroundColor = Json.color(raw, "backgroundColor", backgroundColor);
        textColor = Json.color(raw, "textColor", textColor);
        labelColor = Json.color(raw, "labelColor", labelColor);
    }

    /**
     * Keep the numbers inside a band where the HUD stays visible and fixable.
     * A scale of 0 or a negative margin is a config you cannot recover from
     * without opening the file again, which defeats the point of having one.
     */
    private void clamp() {
        if (!(scale > 0.0f) || Float.isNaN(scale) || Float.isInfinite(scale)) {
            scale = 1.0f;
        }
        scale = Math.max(0.25f, Math.min(4.0f, scale));
        margin = Math.max(0, Math.min(200, margin));
        padding = Math.max(0, Math.min(32, padding));
        moneyIdleSeconds = Math.max(10, Math.min(3600, moneyIdleSeconds));
        moneyWindowMinutes = snapWindow(moneyWindowMinutes);
        spWindowMinutes = snapWindow(spWindowMinutes);
    }

    /** The rolling windows the money panel offers; anything else snaps to the nearest. */
    public static final int[] MONEY_WINDOWS = {1, 5, 10, 30};

    public static int snapWindow(int minutes) {
        int best = MONEY_WINDOWS[0];
        for (int w : MONEY_WINDOWS) {
            if (Math.abs(w - minutes) < Math.abs(best - minutes)) {
                best = w;
            }
        }
        return best;
    }

    /** Write the current values out. Never throws; a config that cannot be saved is a log line, not a crash. */
    public void save() {
        try {
            Path path = file();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, toJson(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            AuraQoL.LOG.warn("[auraqol] could not save hud config: {}", e.toString());
        }
    }

    private String toJson() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.toString(enabled));
        out.put("showFps", Boolean.toString(showFps));
        out.put("showCoords", Boolean.toString(showCoords));
        out.put("showDirection", Boolean.toString(showDirection));
        out.put("showPing", Boolean.toString(showPing));
        out.put("showTime", Boolean.toString(showTime));
        out.put("showBiome", Boolean.toString(showBiome));
        out.put("cpsEnabled", Boolean.toString(cpsEnabled));
        out.put("keysEnabled", Boolean.toString(keysEnabled));
        out.put("zoomEnabled", Boolean.toString(zoomEnabled));
        out.put("selfNametagEnabled", Boolean.toString(selfNametagEnabled));
        out.put("hotkeysEnabled", Boolean.toString(hotkeysEnabled));
        out.put("bpsEnabled", Boolean.toString(bpsEnabled));
        out.put("moneyEnabled", Boolean.toString(moneyEnabled));
        out.put("todoEnabled", Boolean.toString(todoEnabled));
        out.put("commsEnabled", Boolean.toString(commsEnabled));
        out.put("slayerEnabled", Boolean.toString(slayerEnabled));
        out.put("abilityEnabled", Boolean.toString(abilityEnabled));
        out.put("priceTooltip", Boolean.toString(priceTooltip));
        out.put("priceTooltipNpc", Boolean.toString(priceTooltipNpc));
        out.put("recipeBuy", Boolean.toString(recipeBuy));
        out.put("recipeBuyHint", Boolean.toString(recipeBuyHint));
        out.put("moneyCountItems", Boolean.toString(moneyCountItems));
        out.put("moneyIdleSeconds", Integer.toString(moneyIdleSeconds));
        out.put("moneyRecent", Boolean.toString(moneyRecent));
        out.put("moneyWindowMinutes", Integer.toString(moneyWindowMinutes));
        out.put("moneyBreakdown", Boolean.toString(moneyBreakdown));
        out.put("moneyLog", Boolean.toString(moneyLog));
        out.put("sacksEnabled", Boolean.toString(sacksEnabled));
        out.put("todoShowDone", Boolean.toString(todoShowDone));
        out.put("pricesOnlyOnHypixel", Boolean.toString(pricesOnlyOnHypixel));
        out.put("spMiningEnabled", Boolean.toString(spMiningEnabled));
        out.put("spEarningsEnabled", Boolean.toString(spEarningsEnabled));
        out.put("spRankupEnabled", Boolean.toString(spRankupEnabled));
        out.put("spBoostsEnabled", Boolean.toString(spBoostsEnabled));
        out.put("spContractEnabled", Boolean.toString(spContractEnabled));
        out.put("spSkillsEnabled", Boolean.toString(spSkillsEnabled));
        out.put("spProfileEnabled", Boolean.toString(spProfileEnabled));
        out.put("spRecent", Boolean.toString(spRecent));
        out.put("spWindowMinutes", Integer.toString(spWindowMinutes));
        out.put("corner", Json.quote(corner.name().toLowerCase(Locale.ROOT)));
        out.put("scale", trimFloat(scale));
        out.put("margin", Integer.toString(margin));
        out.put("padding", Integer.toString(padding));
        out.put("background", Boolean.toString(background));
        out.put("backgroundColor", Json.quote(Json.hex(backgroundColor)));
        out.put("textColor", Json.quote(Json.hex(textColor)));
        out.put("labelColor", Json.quote(Json.hex(labelColor)));

        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\n");
        int i = 0;
        int n = out.size();
        for (Map.Entry<String, String> e : out.entrySet()) {
            sb.append("  ").append(Json.quote(e.getKey())).append(": ").append(e.getValue());
            if (++i < n) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String trimFloat(float f) {
        String s = String.format(Locale.ROOT, "%.3f", f);
        while (s.contains(".") && (s.endsWith("0"))) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * A total, flat JSON reader.
     *
     * <p>It understands exactly one shape — an object whose values are
     * strings, numbers, booleans or null — because that is the only shape
     * this config has. Nested objects and arrays are skipped rather than
     * rejected, so adding one by hand degrades to "that key is ignored"
     * instead of "the whole file is thrown away".
     *
     * <p>Values come back as their raw source text (strings already
     * unescaped), and the typed accessors below each fall back
     * independently. That independence is deliberate: one bad key must not
     * cost the other fourteen.
     */
    private static final class Json {

        private Json() {
        }

        static boolean bool(Map<String, String> m, String key, boolean fallback) {
            String v = m.get(key);
            if (v == null) {
                return fallback;
            }
            String s = v.trim();
            if (s.equalsIgnoreCase("true")) {
                return true;
            }
            if (s.equalsIgnoreCase("false")) {
                return false;
            }
            return fallback;
        }

        static double number(Map<String, String> m, String key, double fallback) {
            String v = m.get(key);
            if (v == null) {
                return fallback;
            }
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /**
         * Colours are written as {@code "#AARRGGBB"} strings rather than
         * numbers on purpose: 0x80000000 does not survive a round trip
         * through a JSON number as anything a human wants to edit, and a
         * signed 32-bit alpha channel is exactly the sort of thing that
         * comes back as -2147483648 and looks like corruption.
         */
        static int color(Map<String, String> m, String key, int fallback) {
            String v = m.get(key);
            if (v == null) {
                return fallback;
            }
            String s = v.trim();
            if (s.startsWith("#")) {
                s = s.substring(1);
            } else if (s.startsWith("0x") || s.startsWith("0X")) {
                s = s.substring(2);
            }
            if (s.isEmpty() || s.length() > 8) {
                // Might still be a plain decimal number written by a text editor.
                try {
                    return (int) Long.parseLong(v.trim());
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
            try {
                long parsed = Long.parseLong(s, 16);
                // Six digits means RGB with no alpha; assume fully opaque.
                if (s.length() <= 6) {
                    parsed |= 0xFF000000L;
                }
                return (int) parsed;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        static String hex(int argb) {
            return String.format(Locale.ROOT, "#%08X", argb);
        }

        static String quote(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 2);
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
            return sb.toString();
        }

        static Map<String, String> parseFlatObject(String text) {
            Map<String, String> out = new LinkedHashMap<>();
            if (text == null) {
                return out;
            }
            int i = skipWs(text, 0);
            if (i >= text.length() || text.charAt(i) != '{') {
                return out;
            }
            i++;
            while (true) {
                i = skipWs(text, i);
                if (i >= text.length()) {
                    return out;
                }
                char c = text.charAt(i);
                if (c == '}') {
                    return out;
                }
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c != '"') {
                    // Not a key we can read. Bail with whatever we already have.
                    return out;
                }
                int[] cursor = new int[] { i };
                String key = readString(text, cursor);
                i = cursor[0];
                if (key == null) {
                    return out;
                }
                i = skipWs(text, i);
                if (i >= text.length() || text.charAt(i) != ':') {
                    return out;
                }
                i++;
                i = skipWs(text, i);
                if (i >= text.length()) {
                    return out;
                }
                char vc = text.charAt(i);
                if (vc == '"') {
                    cursor[0] = i;
                    String val = readString(text, cursor);
                    i = cursor[0];
                    if (val == null) {
                        return out;
                    }
                    out.put(key, val);
                } else if (vc == '{' || vc == '[') {
                    i = skipContainer(text, i);
                    if (i < 0) {
                        return out;
                    }
                } else {
                    int start = i;
                    while (i < text.length() && ",}]".indexOf(text.charAt(i)) < 0) {
                        i++;
                    }
                    out.put(key, text.substring(start, i).trim());
                }
            }
        }

        private static int skipWs(String s, int i) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return i;
        }

        /** {@code cursor[0]} must sit on the opening quote; it is left just past the closing one. */
        private static String readString(String s, int[] cursor) {
            int i = cursor[0] + 1;
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '"') {
                    cursor[0] = i + 1;
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i + 1 >= s.length()) {
                        return null;
                    }
                    char e = s.charAt(i + 1);
                    i += 2;
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                return null;
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException nfe) {
                                return null;
                            }
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                    continue;
                }
                sb.append(c);
                i++;
            }
            return null;
        }

        /** Step over a nested object/array without interpreting it. Returns -1 if it never closes. */
        private static int skipContainer(String s, int i) {
            int depth = 0;
            boolean inString = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (c == '"') {
                        inString = false;
                    }
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
            return -1;
        }
    }
}
