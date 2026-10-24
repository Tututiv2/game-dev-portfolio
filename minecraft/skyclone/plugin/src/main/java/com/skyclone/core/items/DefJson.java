package com.skyclone.core.items;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.stat.Stat;

import java.io.Reader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lenient JSON for ItemDef and Recipe arrays. Pure Java (Gson only).
 *
 * <ul>
 *   <li>Stat-keyed maps go through {@link Stat#parse}; unknown keys are skipped with ONE warning
 *       per key for the whole server. A "DAMAGE" key inside "stats" becomes {@code ItemDef.damage}
 *       when the def has no explicit "damage" (the research data stores it that way).</li>
 *   <li>Rarity / ItemCategory / ability trigger parse leniently ("very special", "FARMING_TOOL").</li>
 *   <li>One bad entry never sinks the file: it is reported through {@code warn} and skipped.</li>
 * </ul>
 */
public final class DefJson {

    private DefJson() {}

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile Consumer<String> warnSink = s -> {};

    public static void setWarnSink(Consumer<String> sink) { warnSink = sink == null ? s -> {} : sink; }

    static void warnOnce(String key, String msg) {
        if (WARNED.add(key)) warnSink.accept(msg);
    }

    private static final Type STAT_MAP = new TypeToken<Map<Stat, Double>>() {}.getType();

    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(STAT_MAP, (JsonDeserializer<Map<Stat, Double>>) (json, t, ctx) -> statMap(json))
            .registerTypeAdapter(Rarity.class, (JsonDeserializer<Rarity>) (json, t, ctx) -> rarity(json))
            .registerTypeAdapter(ItemCategory.class, (JsonDeserializer<ItemCategory>) (json, t, ctx) -> category(json))
            .registerTypeAdapter(AbilityDef.Trigger.class, (JsonDeserializer<AbilityDef.Trigger>) (json, t, ctx) -> trigger(json))
            .registerTypeAdapter(Recipe.Type.class, (JsonDeserializer<Recipe.Type>) (json, t, ctx) -> recipeType(json))
            .create();

    public static Map<Stat, Double> statMap(JsonElement json) {
        Map<Stat, Double> m = new EnumMap<>(Stat.class);
        if (json == null || !json.isJsonObject()) return m;
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
            Stat s = Stat.parse(e.getKey());
            if (s == null) {
                if (!"DAMAGE".equalsIgnoreCase(e.getKey().trim()))
                    warnOnce("stat:" + e.getKey().toUpperCase(Locale.ROOT), "Unknown stat key '" + e.getKey() + "' in item data (skipped everywhere it appears)");
                continue;
            }
            try { m.merge(s, e.getValue().getAsDouble(), Double::sum); } catch (RuntimeException ex) { /* non-number: skip */ }
        }
        return m;
    }

    public static Rarity rarity(JsonElement json) {
        if (json == null || json.isJsonNull()) return Rarity.COMMON;
        String s = json.getAsString();
        Rarity r = Rarity.parse(s);
        if (r == Rarity.COMMON && !s.trim().equalsIgnoreCase("COMMON"))
            warnOnce("rarity:" + s, "Unknown rarity '" + s + "' (using COMMON)");
        return r;
    }

    public static ItemCategory category(JsonElement json) {
        if (json == null || json.isJsonNull()) return ItemCategory.MATERIAL;
        String raw = json.getAsString().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        String s = switch (raw) {
            case "FARMING_TOOL" -> "HOE";
            case "SPADE" -> "SHOVEL";
            case "FISHING_ROD_PART" -> "MATERIAL";
            case "HATCESSORY", "PORTAL", "MUTATION", "FISHING_NET", "BRACELET" -> "MATERIAL";
            case "PET_ITEM", "PETITEM" -> "PET_ITEM";
            default -> raw;
        };
        ItemCategory c = ItemCategory.parse(s);
        if (c == ItemCategory.MATERIAL && !s.equals("MATERIAL") && !s.isEmpty())
            warnOnce("category:" + raw, "Unknown item category '" + raw + "' (using MATERIAL)");
        return c;
    }

    public static AbilityDef.Trigger trigger(JsonElement json) {
        if (json == null || json.isJsonNull()) return AbilityDef.Trigger.RIGHT_CLICK;
        String s = json.getAsString().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        try { return AbilityDef.Trigger.valueOf(s); }
        catch (IllegalArgumentException e) {
            warnOnce("trigger:" + s, "Unknown ability trigger '" + s + "' (using RIGHT_CLICK)");
            return AbilityDef.Trigger.RIGHT_CLICK;
        }
    }

    public static Recipe.Type recipeType(JsonElement json) {
        if (json == null || json.isJsonNull()) return Recipe.Type.CRAFTING;
        String s = json.getAsString().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        try { return Recipe.Type.valueOf(s); }
        catch (IllegalArgumentException e) {
            warnOnce("recipetype:" + s, "Unknown recipe type '" + s + "' (using OTHER)");
            return Recipe.Type.OTHER;
        }
    }

    // ---- items -------------------------------------------------------------------------------

    public interface EntrySink<T> {
        void ok(T value);
        void bad(String id, String reason);
    }

    /** Parse a JSON array of ItemDef; each entry independently. Returns entries seen. */
    public static int readItems(Reader in, EntrySink<ItemDef> sink) {
        JsonElement root = JsonParser.parseReader(in);
        if (!root.isJsonArray()) throw new IllegalArgumentException("expected a JSON array of items");
        int n = 0;
        for (JsonElement el : root.getAsJsonArray()) {
            n++;
            String id = "#" + n;
            try {
                if (!el.isJsonObject()) { sink.bad(id, "not an object"); continue; }
                JsonObject o = el.getAsJsonObject();
                if (o.has("id") && o.get("id").isJsonPrimitive()) id = o.get("id").getAsString();
                ItemDef d = GSON.fromJson(o, ItemDef.class);
                // "stats":{"DAMAGE":35} -> damage, unless the def says "damage" itself
                if (!o.has("damage") && o.has("stats") && o.get("stats").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("stats").entrySet()) {
                        if (e.getKey().trim().equalsIgnoreCase("DAMAGE")) {
                            try { d.damage = e.getValue().getAsDouble(); } catch (RuntimeException ignored) {}
                        }
                    }
                }
                String why = normalize(d);
                if (why != null) { sink.bad(id, why); continue; }
                sink.ok(d);
            } catch (RuntimeException ex) {
                sink.bad(id, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        return n;
    }

    /** Fill nulls with defaults. Returns a reason when the def cannot be used at all. */
    public static String normalize(ItemDef d) {
        if (d == null) return "null entry";
        if (d.id == null || d.id.isBlank()) return "missing id";
        d.id = d.id.trim();
        if (d.name == null || d.name.isBlank()) d.name = com.skyclone.api.ui.Text.titleCase(d.id);
        if (d.material == null || d.material.isBlank()) d.material = "STONE";
        if (d.rarity == null) d.rarity = Rarity.COMMON;
        if (d.category == null) d.category = ItemCategory.MATERIAL;
        if (d.stats == null) d.stats = new EnumMap<>(Stat.class);
        else if (!(d.stats instanceof EnumMap)) {
            Map<Stat, Double> m = new EnumMap<>(Stat.class);
            d.stats.forEach((k, v) -> { if (k != null && v != null) m.put(k, v); });
            d.stats = m;
        }
        d.stats.values().removeIf(v -> v == null);
        if (d.lore == null) d.lore = new ArrayList<>();
        if (d.abilities == null) d.abilities = new ArrayList<>();
        d.abilities.removeIf(a -> a == null || a.id == null || a.id.isBlank());
        for (AbilityDef a : d.abilities) {
            if (a.trigger == null) a.trigger = AbilityDef.Trigger.RIGHT_CLICK;
            if (a.lore == null) a.lore = new ArrayList<>();
            if (a.name == null || a.name.isBlank()) a.name = com.skyclone.api.ui.Text.titleCase(a.id);
        }
        if (d.setBonusLore == null) d.setBonusLore = new ArrayList<>();
        if (d.requirements == null) d.requirements = new ArrayList<>();
        d.requirements.removeIf(r -> r == null || r.isBlank());
        if (d.gemSlots == null) d.gemSlots = new ArrayList<>();
        if (d.tags == null) d.tags = new HashSet<>();
        if (d.extra == null) d.extra = new JsonObject();
        if (d.maxStack < 0 || d.maxStack > 99) d.maxStack = 0;
        return null;
    }

    // ---- recipes -----------------------------------------------------------------------------

    public static int readRecipes(Reader in, EntrySink<Recipe> sink) {
        JsonElement root = JsonParser.parseReader(in);
        if (!root.isJsonArray()) throw new IllegalArgumentException("expected a JSON array of recipes");
        int n = 0;
        for (JsonElement el : root.getAsJsonArray()) {
            n++;
            String id = "#" + n;
            try {
                if (!el.isJsonObject()) { sink.bad(id, "not an object"); continue; }
                Recipe r = GSON.fromJson(el, Recipe.class);
                if (r.result != null) id = r.result + " " + id;
                String why = normalize(r);
                if (why != null) { sink.bad(id, why); continue; }
                sink.ok(r);
            } catch (RuntimeException ex) {
                sink.bad(id, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        return n;
    }

    public static String normalize(Recipe r) {
        if (r == null) return "null entry";
        if (r.result == null || r.result.isBlank()) return "missing result";
        r.result = r.result.trim();
        if (r.type == null) r.type = Recipe.Type.CRAFTING;
        if (r.amount <= 0) r.amount = 1;
        String[] g = new String[9];
        if (r.grid != null) for (int i = 0; i < Math.min(9, r.grid.length); i++) {
            String c = r.grid[i];
            g[i] = c == null || c.isBlank() ? null : c.trim();
        }
        r.grid = g;
        if (r.inputs == null) r.inputs = new ArrayList<>();
        List<String> in = new ArrayList<>();
        for (String s : r.inputs) if (s != null && !s.isBlank()) in.add(s.trim());
        r.inputs = in;
        boolean anyGrid = false;
        for (String c : g) if (c != null) { anyGrid = true; break; }
        if (!anyGrid && in.isEmpty()) return "no ingredients";
        if (r.unlock != null && r.unlock.isBlank()) r.unlock = null;
        return null;
    }
}
