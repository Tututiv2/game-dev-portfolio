package com.skyclone.mod.reforges;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only facts about mineable blocks, keyed by the item they drop (what {@code SkyEvents.Gather}
 * carries): breaking power and mithril powder per block. Read once from the mining module's own data
 * file by id; if that file is missing the mining-only reforge bonuses that need it simply do nothing.
 */
final class MiningBlocks {

    private MiningBlocks() {}

    /** Mining islands that pay powder (same list as the mining module's). */
    static final Set<String> POWDER_ISLANDS = Set.of("dwarven_mines", "crystal_hollows", "glacite_tunnels", "mineshaft");
    /** Calendar event id and mayor perk id of the Mining Jubilee (the mining module's defaults). */
    static final String FIESTA = "mining_fiesta";

    private static Map<String, Set<Integer>> bp;
    private static Map<String, Double> mithril;
    /** island -> material -> block kind (first entry wins, as in the mining module's table). */
    private static Map<String, Map<String, String>> kinds;

    private static synchronized void load() {
        if (bp != null) return;
        Map<String, Set<Integer>> b = new HashMap<>();
        Map<String, Double> m = new HashMap<>();
        Map<String, Map<String, String>> k = new HashMap<>();
        try (InputStream in = Sky.plugin().getResource("data/mining/blocks.json")) {
            if (in != null) {
                JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                JsonArray arr = root.isJsonObject() ? root.getAsJsonObject().getAsJsonArray("blocks") : root.getAsJsonArray();
                if (arr != null) for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("kind") && o.has("islands") && o.has("materials")) {
                        String kind = o.get("kind").getAsString();
                        for (JsonElement is : o.getAsJsonArray("islands"))
                            for (JsonElement mat : o.getAsJsonArray("materials"))
                                k.computeIfAbsent(is.getAsString(), x -> new HashMap<>()).putIfAbsent(mat.getAsString().toUpperCase(java.util.Locale.ROOT), kind);
                    }
                    if (!o.has("drop")) continue;
                    String drop = o.get("drop").getAsString();
                    if (o.has("breakingPower")) b.computeIfAbsent(drop, x -> new HashSet<>()).add(o.get("breakingPower").getAsInt());
                    if (o.has("powder") && !o.get("powder").isJsonNull() && "MITHRIL".equals(o.get("powder").getAsString())
                            && o.has("powderAmount")) m.merge(drop, o.get("powderAmount").getAsDouble(), Math::max);
                }
            }
        } catch (Exception ex) {
            Sky.plugin().getLogger().warning("[reforges] could not read data/mining/blocks.json: " + ex.getMessage());
        }
        kinds = k;
        mithril = m;
        bp = b;
    }

    /** Breaking powers of the blocks that drop this item (empty = unknown). */
    static Set<Integer> breakingPower(String drop) {
        load();
        return bp.getOrDefault(drop, Set.of());
    }

    /** Base Mithril Powder for mining a block that drops this item (0 = none). */
    static double mithrilPowder(String drop) {
        load();
        return mithril.getOrDefault(drop, 0.0);
    }

    /** The mining kind (STANDARD, HARD_STONE, ORE, METAL, GEMSTONE...) of a material on an island, or null. */
    static String kind(String island, org.bukkit.Material mat) {
        if (island == null || mat == null) return null;
        load();
        Map<String, String> m = kinds.get(island);
        return m == null ? null : m.get(mat.name());
    }

    /**
     * Does the mining module's Fossil Excavator treasure table (data/mining/loot.json, read by id) hold
     * this item? Fossil Essence's reference source is that table; when the line is there, mining gives it
     * through {@code Sky.loot().give} and our ItemGain listener pours it into the essence pouch.
     * Null if the file cannot be read.
     */
    static Boolean excavatorGives(String itemId) {
        try (InputStream in = Sky.plugin().getResource("data/mining/loot.json")) {
            if (in == null) return null;
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            JsonObject ex = root.isJsonObject() && root.getAsJsonObject().has("excavator") ? root.getAsJsonObject().getAsJsonObject("excavator") : null;
            JsonArray table = ex == null || !ex.has("table") ? null : ex.getAsJsonArray("table");
            if (table == null) return false;
            for (JsonElement el : table)
                if (el.isJsonObject() && el.getAsJsonObject().has("item") && itemId.equals(el.getAsJsonObject().get("item").getAsString())) return true;
            return false;
        } catch (Exception ex) {
            return null;
        }
    }

    /** "Plain" blocks for Fleet: stone and hard stone, not ores, metals or gems. */
    static boolean plain(String kind) { return "STANDARD".equals(kind) || "HARD_STONE".equals(kind); }

    /** Is a mining festival on (the Jubilee calendar event or its mayor perk)? */
    static boolean fiesta() {
        try {
            if (Sky.calendar().active(FIESTA)) return true;
        } catch (Throwable ignored) { }
        try {
            return Sky.service(Features.Mayors.class).perkActive(FIESTA);
        } catch (Throwable t) { return false; }
    }
}
