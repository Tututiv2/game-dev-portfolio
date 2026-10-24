package com.skyclone.mod.accessories;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Wave 5: "while mining X" (Cond kind {@code mining:<block ids>}). Resolves the block a player is digging -
 * its island and Material - to the mining module's block id ({@code data/mining/blocks.json}, read by id: the
 * same file contract the reforges module uses). The same Material can mean different blocks on different islands
 * (STONE is Hard Stone in the Crystal Hollows, plain Stone in the Hub), so the island decides first; when no entry
 * lists the island, the first entry with the Material wins. Pure (unit-tested).
 */
final class MineTargets {

    record Entry(String id, Set<String> islands, Set<String> materials) {}

    private final List<Entry> entries;

    MineTargets(List<Entry> entries) { this.entries = List.copyOf(entries); }

    static final MineTargets EMPTY = new MineTargets(List.of());

    int size() { return entries.size(); }

    /** The block id for a Material name dug on an island, or null when the mining module knows no such block. */
    String resolve(String island, String material) {
        if (material == null) return null;
        String m = material.toUpperCase(Locale.ROOT);
        String any = null;
        for (Entry e : entries) {
            if (!e.materials.contains(m)) continue;
            if (island != null && e.islands.contains(island)) return e.id;
            if (any == null) any = e.id;
        }
        return any;
    }

    /** Parses {@code {"blocks": [{"id", "islands": [...], "materials": [...]}, ...]}}; bad rows are skipped. */
    static MineTargets parse(JsonObject root) {
        List<Entry> out = new ArrayList<>();
        if (root == null || !root.has("blocks") || !root.get("blocks").isJsonArray()) return new MineTargets(out);
        for (JsonElement el : root.getAsJsonArray("blocks")) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id") || !o.get("id").isJsonPrimitive()) continue;
            out.add(new Entry(o.get("id").getAsString(), strings(o, "islands", false), strings(o, "materials", true)));
        }
        return new MineTargets(out);
    }

    /** The mining module's shipped table, or {@link #EMPTY} when it is missing (the conditions then never hold). */
    static MineTargets load(ClassLoader cl) {
        try (InputStream in = cl.getResourceAsStream("data/mining/blocks.json")) {
            if (in == null) return EMPTY;
            return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static Set<String> strings(JsonObject o, String key, boolean upper) {
        Set<String> s = new HashSet<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return s;
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (e.isJsonPrimitive()) s.add(upper ? e.getAsString().toUpperCase(Locale.ROOT) : e.getAsString());
        }
        return s;
    }
}
