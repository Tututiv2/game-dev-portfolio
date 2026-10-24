package com.skyclone.core.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.item.ItemData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * ItemData <-> compact JSON. Pure Java.
 *
 * <p>Two rules make stacking work: the output is DETERMINISTIC (fields in a fixed order, sets
 * sorted) and DEFAULTS ARE OMITTED (an untouched stackable writes "" so its stack carries no data
 * tag at all). Two Enchanted Diamonds made a minute apart are therefore byte-identical and stack.
 */
public final class ItemDataCodec {

    private ItemDataCodec() {}

    /** Compact JSON, or "" when every field is at its default. */
    public static String write(ItemData d) {
        if (d == null) return "";
        JsonObject o = new JsonObject();
        if (d.uuid != null && !d.uuid.isEmpty()) o.addProperty("uuid", d.uuid);
        if (d.reforge != null && !d.reforge.isEmpty()) o.addProperty("reforge", d.reforge);
        if (d.enchants != null && !d.enchants.isEmpty()) {
            JsonObject e = new JsonObject();
            for (Map.Entry<String, Integer> en : d.enchants.entrySet()) {
                if (en.getKey() == null || en.getValue() == null || en.getValue() <= 0) continue;
                e.addProperty(en.getKey(), en.getValue());
            }
            if (e.size() > 0) o.add("enchants", e);
        }
        if (d.potatoBooks > 0) o.addProperty("potatoBooks", d.potatoBooks);
        if (d.recombobulated) o.addProperty("recombobulated", true);
        if (d.stars > 0) o.addProperty("stars", d.stars);
        if (d.gems != null && !d.gems.isEmpty()) {
            JsonObject g = new JsonObject();
            List<String> keys = new ArrayList<>(d.gems.keySet());
            keys.removeIf(k -> k == null);
            Collections.sort(keys);
            for (String k : keys) { String v = d.gems.get(k); if (v != null) g.addProperty(k, v); }
            if (g.size() > 0) o.add("gems", g);
        }
        if (d.unlockedGemSlots != null && !d.unlockedGemSlots.isEmpty()) {
            List<String> s = new ArrayList<>(d.unlockedGemSlots);
            s.removeIf(k -> k == null);
            Collections.sort(s);
            JsonArray a = new JsonArray();
            for (String k : s) a.add(k);
            if (a.size() > 0) o.add("unlockedGemSlots", a);
        }
        if (d.extra != null && d.extra.size() > 0) o.add("extra", d.extra.deepCopy());
        if (d.created > 0) o.addProperty("created", d.created);
        return o.size() == 0 ? "" : o.toString();
    }

    /** Lenient: garbage or blank gives empty data, never null, never throws. */
    public static ItemData read(String json) {
        ItemData d = new ItemData();
        if (json == null || json.isBlank()) return d;
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) return d;
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return d;
        }
        d.uuid = str(o, "uuid");
        d.reforge = str(o, "reforge");
        JsonObject e = obj(o, "enchants");
        if (e != null) for (Map.Entry<String, JsonElement> en : e.entrySet()) {
            int lvl = intOf(en.getValue(), 0);
            if (lvl > 0) d.enchants.put(en.getKey(), lvl);
        }
        d.potatoBooks = Math.max(0, intOf(o.get("potatoBooks"), 0));
        d.recombobulated = boolOf(o.get("recombobulated"));
        d.stars = Math.max(0, intOf(o.get("stars"), 0));
        JsonObject g = obj(o, "gems");
        if (g != null) for (Map.Entry<String, JsonElement> en : g.entrySet()) {
            if (en.getValue() != null && en.getValue().isJsonPrimitive()) d.gems.put(en.getKey(), en.getValue().getAsString());
        }
        JsonElement u = o.get("unlockedGemSlots");
        if (u != null && u.isJsonArray()) for (JsonElement x : u.getAsJsonArray()) {
            if (x != null && x.isJsonPrimitive()) d.unlockedGemSlots.add(x.getAsString());
        }
        JsonObject ex = obj(o, "extra");
        if (ex != null) d.extra = ex.deepCopy();
        d.created = longOf(o.get("created"));
        return d;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.isEmpty() ? null : s;
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static int intOf(JsonElement e, int def) {
        try { return e == null || e.isJsonNull() ? def : e.getAsInt(); } catch (RuntimeException x) { return def; }
    }

    private static long longOf(JsonElement e) {
        try { return e == null || e.isJsonNull() ? 0 : e.getAsLong(); } catch (RuntimeException x) { return 0; }
    }

    private static boolean boolOf(JsonElement e) {
        try { return e != null && !e.isJsonNull() && e.getAsBoolean(); } catch (RuntimeException x) { return false; }
    }
}
