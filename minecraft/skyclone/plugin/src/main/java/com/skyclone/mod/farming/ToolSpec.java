package com.skyclone.mod.farming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a farming tool does, read from {@code ItemDef.extra.farmTool} (written by tools/gen/farming). Pure.
 */
public final class ToolSpec {

    /** Instance keys in {@code ItemData.extra}. */
    public static final String K_COUNTER = "farm_counter", K_XP = "farm_xp", K_ALMANAC = "farm_almanac", K_FUNGI = "fungi_mode",
            K_OVERCLOCK = "farm_overclock";

    public final String line, kind, next;
    public final int mk;
    public final List<Crop> crops;
    public final int cropFortune, wisdom;
    /** The reference's Garden-level requirement (items.json GARDEN_LEVEL), 0 = none. Applied by FarmingModule when the garden is present. */
    public final int gardenLevel;
    public final boolean counter, analysis, leveling;
    public final double dicer;

    private ToolSpec(JsonObject o) {
        line = str(o, "line", "generic");
        kind = str(o, "kind", "generic");
        next = str(o, "next", null);
        mk = o.has("mk") ? o.get("mk").getAsInt() : 0;
        List<Crop> cs = new ArrayList<>();
        if (o.has("crops") && o.get("crops").isJsonArray())
            for (JsonElement e : o.getAsJsonArray("crops")) { Crop c = Crop.parse(e.getAsString()); if (c != null) cs.add(c); }
        crops = Collections.unmodifiableList(cs);
        cropFortune = o.has("cropFortune") ? o.get("cropFortune").getAsInt() : 0;
        wisdom = o.has("wisdom") ? o.get("wisdom").getAsInt() : 0;
        gardenLevel = o.has("gardenLevel") ? o.get("gardenLevel").getAsInt() : 0;
        counter = o.has("counter") && o.get("counter").getAsBoolean();
        analysis = o.has("analysis") && o.get("analysis").getAsBoolean();
        leveling = o.has("leveling") && o.get("leveling").getAsBoolean();
        dicer = o.has("dicer") ? o.get("dicer").getAsDouble() : 0;
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }

    /** Applies to this crop? Generic tools (no crop list) boost nothing crop-specific. */
    public boolean boosts(Crop c) { return crops.contains(c); }

    public boolean generic() { return crops.isEmpty(); }

    private static final Map<String, ToolSpec> CACHE = new ConcurrentHashMap<>();
    private static final ToolSpec NONE = new ToolSpec(new JsonObject());

    /** The spec of an item def's extra, or null when it is not a farming tool. Cached per item id. */
    public static ToolSpec of(String itemId, JsonObject extra) {
        if (extra == null || !extra.has("farmTool") || !extra.get("farmTool").isJsonObject()) return null;
        ToolSpec s = CACHE.computeIfAbsent(itemId == null ? "?" : itemId, k -> new ToolSpec(extra.getAsJsonObject("farmTool")));
        return s == NONE ? null : s;
    }

    /** Parse without caching (tests). */
    public static ToolSpec parse(JsonObject farmTool) { return new ToolSpec(farmTool); }

    // ---- instance values ----------------------------------------------------------------------------

    public static long counterOf(JsonObject extra) { return extra != null && extra.has(K_COUNTER) ? extra.get(K_COUNTER).getAsLong() : 0; }
    public static double xpOf(JsonObject extra) { return extra != null && extra.has(K_XP) ? extra.get(K_XP).getAsDouble() : 0; }
    public static int almanacOf(JsonObject extra) { return extra != null && extra.has(K_ALMANAC) ? extra.get(K_ALMANAC).getAsInt() : 0; }
    /** Overclocker 3000s applied (each +1 to a Mk III's level cap). */
    public static int overclockOf(JsonObject extra) { return extra != null && extra.has(K_OVERCLOCK) ? extra.get(K_OVERCLOCK).getAsInt() : 0; }
    public static String fungiOf(JsonObject extra) { return extra != null && extra.has(K_FUNGI) ? extra.get(K_FUNGI).getAsString() : "red"; }
}
