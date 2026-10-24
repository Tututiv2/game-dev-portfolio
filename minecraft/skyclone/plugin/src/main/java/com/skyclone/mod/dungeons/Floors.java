package com.skyclone.mod.dungeons;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.mod.dungeons.model.FloorDef;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The 15 floors from data/dungeons/floors.json, in order E, F1..F7, M1..M7. */
public final class Floors {

    private static final Map<String, FloorDef> BY_ID = new LinkedHashMap<>();

    private Floors() {}

    static int load() {
        BY_ID.clear();
        InputStream in = Sky.plugin().getResource("data/dungeons/floors.json");
        if (in == null) { Sky.log().severe("[dungeons] floors.json missing"); return 0; }
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonArray arr = JsonParser.parseReader(r).getAsJsonArray();
            Gson g = new Gson();
            for (JsonElement e : arr) {
                FloorDef f = g.fromJson(e, FloorDef.class);
                if (f != null && f.id != null) BY_ID.put(f.id.toUpperCase(java.util.Locale.ROOT), f);
            }
        } catch (Exception ex) {
            Sky.log().severe("[dungeons] floors.json: " + ex);
        }
        return BY_ID.size();
    }

    /** "f3", "F3", "3", "e", "entrance", "m2". */
    public static FloorDef get(String id) {
        if (id == null) return null;
        String k = id.trim().toUpperCase(java.util.Locale.ROOT);
        if (k.equals("ENTRANCE") || k.equals("0")) k = "E";
        if (k.matches("[1-7]")) k = "F" + k;
        return BY_ID.get(k);
    }

    public static List<FloorDef> all() { return new ArrayList<>(BY_ID.values()); }

    public static List<FloorDef> normal() {
        List<FloorDef> out = new ArrayList<>();
        for (FloorDef f : BY_ID.values()) if (!f.master) out.add(f);
        return out;
    }

    public static List<FloorDef> masters() {
        List<FloorDef> out = new ArrayList<>();
        for (FloorDef f : BY_ID.values()) if (f.master) out.add(f);
        return out;
    }
}
