package com.skyclone.mod.crafting;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemService;
import com.skyclone.api.item.Recipe;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every grid recipe in the game, compiled for matching, plus the boot-time registration of the
 * vanilla recipes and the gap-filling reference recipes.
 *
 * <p><b>Canonical ids.</b> One item can be spelled several ways: a log a player picked up is "LOG"
 * when the materials module registered that id, otherwise the auto vanilla "OAK_LOG"; a recipe from
 * the reference data says "LOG:2" for a birch log. {@link #canon} folds every spelling onto the id the
 * player's ItemStacks actually carry, so recipes written by any module match the items in hand.
 */
final class RecipeIndex {

    /** One compiled recipe. */
    static final class Entry {
        final Recipe recipe;
        final Pattern pattern;
        final Category category;
        /** Vanilla only: Mojang's category ("building", "redstone", "equipment", "misc"). */
        final String vanillaCat;

        Entry(Recipe recipe, Pattern pattern, Category category, String vanillaCat) {
            this.recipe = recipe;
            this.pattern = pattern;
            this.category = category;
            this.vanillaCat = vanillaCat;
        }

        String id() { return recipe.id; }
    }

    private final Gson gson = new Gson();

    // ---- data files --------------------------------------------------------------------------
    final Map<String, String> neuAlias = new HashMap<>();
    /** collection id -> FARMING/MINING/... */
    final Map<String, String> collectionCat = new HashMap<>();
    final Map<String, String> collectionName = new HashMap<>();
    private final Map<String, List<String>> vanillaGroups = new HashMap<>();
    private final List<JsonObject> vanillaData = new ArrayList<>();
    private final List<Recipe> fallbackData = new ArrayList<>();

    // ---- what late registration did ------------------------------------------------------------
    /** recipe id -> 9 per-slot accepted canonical ids (null = the cell's own id only). */
    private final Map<String, List<Set<String>>> vanillaCells = new HashMap<>();
    private final Map<String, String> vanillaCats = new HashMap<>();
    /** Recipes this module registered: the exact (id, amount) per slot, so "LOG_2:1" never reads as a different log. */
    private final Map<String, CraftMath.Cell[]> exactCells = new HashMap<>();
    int ambiguousCells;
    final Set<String> fallbackIds = new HashSet<>();
    int vanillaRegistered, vanillaSkipped, fallbackRegistered, fallbackOwned, fallbackMissingResult,
            fallbackMissingIngredient;
    final List<String> fallbackMissingSamples = new ArrayList<>();
    boolean lateDone;

    // ---- the index -----------------------------------------------------------------------------
    private volatile long signature = -1;
    private List<Entry> entries = List.of();
    private Map<String, List<Entry>> byResult = Map.of();
    private Map<String, List<Entry>> byIngredient = Map.of();
    private Map<String, Entry> byId = Map.of();
    private Map<Category, List<String>> resultsByCategory = Map.of();
    /** Recipe ids that another recipe undoes (block &lt;-&gt; ingot): no Carpentry XP, see {@link CraftMath#reversible}. */
    private Set<String> reversibleIds = Set.of();
    private final Map<String, String> canonCache = new ConcurrentHashMap<>();

    // ---- loading ---------------------------------------------------------------------------------

    void load() {
        JsonElement al = read("neu_aliases.json");
        if (al != null && al.isJsonObject()) al.getAsJsonObject().entrySet().forEach(e -> neuAlias.put(e.getKey(), e.getValue().getAsString()));
        JsonElement co = read("collections.json");
        if (co != null && co.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : co.getAsJsonObject().entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                collectionCat.put(e.getKey(), o.get("category").getAsString());
                collectionName.put(e.getKey(), o.get("name").getAsString());
            }
        }
        JsonElement vg = read("vanilla_groups.json");
        if (vg != null && vg.isJsonObject()) {
            Map<String, List<String>> m = gson.fromJson(vg, new TypeToken<Map<String, List<String>>>() {}.getType());
            vanillaGroups.putAll(m);
        }
        JsonElement vr = read("vanilla_recipes.json");
        if (vr != null && vr.isJsonArray()) for (JsonElement e : vr.getAsJsonArray()) if (e.isJsonObject()) vanillaData.add(e.getAsJsonObject());
        JsonElement sr = read("skyblock_recipes.json");
        if (sr != null && sr.isJsonArray()) {
            for (JsonElement e : sr.getAsJsonArray()) {
                try {
                    Recipe r = gson.fromJson(e, Recipe.class);
                    if (r != null && r.result != null) fallbackData.add(r);
                } catch (RuntimeException ex) {
                    Sky.log().warning("[crafting] bad fallback recipe skipped: " + ex.getMessage());
                }
            }
        }
        Sky.log().info("[crafting] data: " + vanillaData.size() + " vanilla recipes, " + fallbackData.size()
                + " reference recipes, " + neuAlias.size() + " legacy aliases, " + collectionCat.size() + " collections");
    }

    private static JsonElement read(String file) {
        try (InputStream in = Sky.plugin().getResource("data/crafting/" + file)) {
            if (in == null) { Sky.log().warning("[crafting] data/crafting/" + file + " missing from the jar"); return null; }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) { return JsonParser.parseReader(r); }
        } catch (Exception e) {
            Sky.log().warning("[crafting] could not read " + file + ": " + e);
            return null;
        }
    }

    int vanillaDataSize() { return vanillaData.size(); }
    int fallbackDataSize() { return fallbackData.size(); }

    // ---- canonical ids ---------------------------------------------------------------------------------

    /**
     * The sky id the player's stacks carry for this material, or null when the material has no
     * usable def or its id is SHADOWED (another module took the id for a different item, e.g. the
     * reference game's "MELON" is a melon slice, not the block).
     */
    static String resolveMaterial(String materialName) {
        if (materialName == null) return null;
        Material m = Material.getMaterial(materialName.toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem() || m.isAir()) return null;
        ItemService items = Sky.items();
        String id = items.vanillaId(m);
        ItemDef d = id == null ? null : items.def(id);
        if (d == null) return null;
        if (d.material == null || !d.material.equalsIgnoreCase(m.name())) return null;
        return id;
    }

    String canon(String id) {
        if (id == null) return null;
        String c = canonCache.get(id);
        if (c != null) return c;
        c = computeCanon(id);
        canonCache.put(id, c);
        return c;
    }

    private String computeCanon(String id) {
        ItemService items = Sky.items();
        ItemDef d = items.def(id);
        if (d != null) {
            Material m = Material.getMaterial(id);
            if (m != null && m.isItem() && id.equalsIgnoreCase(d.material)) {
                String v = items.vanillaId(m);
                ItemDef vd = v == null ? null : items.def(v);
                if (vd != null && vd.material != null && vd.material.equalsIgnoreCase(m.name())) return v;
            }
            return id;
        }
        String mat = neuAlias.get(id);
        if (mat != null) {
            String r = resolveMaterial(mat);
            if (r != null) return r;
        }
        String r = resolveMaterial(id);
        return r != null ? r : id;
    }

    /** True when the id names a real item (directly or through an alias). */
    boolean known(String id) {
        if (id == null) return false;
        return Sky.items().exists(canon(id));
    }

    /** The canonical id of a stack (unconverted vanilla stacks count as their vanilla id). */
    String stackId(ItemStack s) {
        if (s == null || s.getType().isAir()) return null;
        String id = Sky.items().idOf(s);
        if (id == null) id = Sky.items().vanillaId(s.getType());
        return canon(id);
    }

    // ---- late registration (first server tick, after every module's postEnable) --------------------

    void lateRegister(boolean vanilla, boolean fallback) {
        if (lateDone) return;
        lateDone = true;
        canonCache.clear();
        if (fallback) registerFallback();
        if (vanilla) registerVanilla();
        signature = -1;
        Sky.log().info("[crafting] vanilla recipes registered " + vanillaRegistered + " (skipped " + vanillaSkipped
                + "); reference recipes filled in " + fallbackRegistered + " (owned by modules " + fallbackOwned
                + ", result missing " + fallbackMissingResult + ", ingredient missing " + fallbackMissingIngredient + ")");
    }

    private void registerVanilla() {
        for (JsonObject v : vanillaData) {
            try {
                Recipe r = vanillaRecipe(v);
                if (r == null) { vanillaSkipped++; continue; }
                int before = Sky.recipes().all().size();
                Sky.recipes().register(r);
                if (Sky.recipes().all().size() > before) vanillaRegistered++; else vanillaSkipped++;
            } catch (RuntimeException e) {
                vanillaSkipped++;
            }
        }
    }

    private Recipe vanillaRecipe(JsonObject v) {
        String rid = "vanilla:" + v.get("id").getAsString();
        String result = resolveMaterial(v.get("result").getAsString());
        if (result == null) return null;
        String[] tokens = new String[9];
        boolean shaped = v.get("shaped").getAsBoolean();
        if (shaped) {
            JsonArray rows = v.getAsJsonArray("rows");
            JsonObject key = v.getAsJsonObject("key");
            if (rows.size() > 3) return null;
            for (int r = 0; r < rows.size(); r++) {
                String row = rows.get(r).getAsString();
                if (row.length() > 3) return null;
                for (int c = 0; c < row.length(); c++) {
                    char ch = row.charAt(c);
                    if (ch == ' ') continue;
                    JsonElement t = key.get(String.valueOf(ch));
                    if (t == null) return null;
                    tokens[r * 3 + c] = t.getAsString();
                }
            }
        } else {
            JsonArray ing = v.getAsJsonArray("ingredients");
            if (ing.size() > 9) return null;
            for (int i = 0; i < ing.size(); i++) tokens[i] = ing.get(i).getAsString();
        }
        String[] grid = new String[9];
        CraftMath.Cell[] exact = new CraftMath.Cell[9];
        List<Set<String>> cells = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            String t = tokens[i];
            if (t == null) { cells.add(null); continue; }
            Set<String> ids = new LinkedHashSet<>();
            if (t.startsWith("#")) {
                List<String> mats = vanillaGroups.get(t.substring(1));
                if (mats == null) return null;
                for (String m : mats) {
                    String id = resolveMaterial(m);
                    if (id != null) ids.add(id);
                }
            } else {
                String id = resolveMaterial(t);
                if (id != null) ids.add(id);
            }
            if (ids.isEmpty()) return null;
            String first = ids.iterator().next();
            grid[i] = cellText(first, 1);
            exact[i] = new CraftMath.Cell(first, 1);
            cells.add(ids.size() > 1 ? ids : null);
        }
        Recipe r = new Recipe();
        r.id = rid;
        r.type = Recipe.Type.CRAFTING;
        r.result = result;
        r.amount = Math.max(1, v.get("count").getAsInt());
        r.grid = grid;
        r.shapeless = !shaped;
        r.station = "Vanilla";
        vanillaCells.put(rid, cells);
        exactCells.put(rid, exact);
        vanillaCats.put(rid, v.has("cat") ? v.get("cat").getAsString() : "misc");
        return r;
    }

    private void registerFallback() {
        ItemService items = Sky.items();
        Set<String> owned = new HashSet<>();
        for (Recipe r : Sky.recipes().all()) {
            if (r.type == Recipe.Type.CRAFTING || r.type == Recipe.Type.CARPENTRY) owned.add(r.result);
        }
        for (Recipe src : fallbackData) {
            if (!items.exists(src.result)) { fallbackMissingResult++; continue; }
            if (owned.contains(src.result)) { fallbackOwned++; continue; }
            ItemDef rd = items.def(src.result);
            if (rd != null && (rd.has("no_recipe") || rd.has("uncraftable"))) { fallbackOwned++; continue; }   // owner opted out
            String[] grid = new String[9];
            CraftMath.Cell[] exact = new CraftMath.Cell[9];
            String missing = null;
            for (int i = 0; i < 9 && src.grid != null && i < src.grid.length; i++) {
                String cell = src.grid[i];
                if (cell == null || cell.isBlank()) continue;
                CraftMath.Cell c = CraftMath.parse(cell, null);
                String id = items.exists(c.id()) ? c.id() : canon(c.id());
                if (!items.exists(id)) { missing = c.id(); break; }
                grid[i] = cellText(id, c.amount());
                exact[i] = new CraftMath.Cell(id, c.amount());
            }
            if (missing != null) {
                fallbackMissingIngredient++;
                if (fallbackMissingSamples.size() < 12) fallbackMissingSamples.add(src.result + " needs " + missing);
                continue;
            }
            Recipe r = new Recipe();
            r.id = src.id;
            r.type = Recipe.Type.CRAFTING;
            r.result = src.result;
            r.amount = Math.max(1, src.amount);
            r.grid = grid;
            r.shapeless = src.shapeless;
            r.unlock = src.unlock;
            int before = Sky.recipes().all().size();
            Sky.recipes().register(r);
            if (Sky.recipes().all().size() > before) { fallbackRegistered++; fallbackIds.add(r.id); exactCells.put(r.id, exact); }
        }
    }

    /**
     * A recipe cell core reads back as exactly (id, amount): a bare id for x1 (so "LOG_2" is never read as
     * "LOG_2:1" = a different log), "id:n" otherwise. When "id:n" is itself a registered id the text is
     * ambiguous for core's indexes; our own matching uses {@link #exactCells} and is never affected.
     */
    String cellText(String id, int amount) {
        if (amount == 1 && Sky.items().exists(id)) return id;
        String t = id + ":" + amount;
        if (Sky.items().exists(t)) ambiguousCells++;
        return t;
    }

    // ---- compile + index -------------------------------------------------------------------------

    private static long currentSignature() {
        return Sky.recipes().all().size() * 1_000_003L + Sky.items().all().size();
    }

    synchronized void ensure() {
        long sig = currentSignature();
        if (sig == signature) return;
        canonCache.clear();
        List<Entry> list = new ArrayList<>();
        for (Recipe r : Sky.recipes().all()) {
            if (r.type != Recipe.Type.CRAFTING && r.type != Recipe.Type.CARPENTRY) continue;
            try {
                Entry e = compile(r);
                if (e != null) list.add(e);
            } catch (RuntimeException ex) {
                Sky.log().warning("[crafting] recipe " + r.id + " could not be compiled: " + ex);
            }
        }
        Map<String, List<Entry>> res = new HashMap<>(), ing = new HashMap<>();
        Map<String, Entry> ids = new HashMap<>();
        Map<Category, Set<String>> cats = new EnumMap<>(Category.class);
        for (Entry e : list) {
            res.computeIfAbsent(e.pattern.result, k -> new ArrayList<>()).add(e);
            for (String id : Matcher.allIds(e.pattern)) {
                List<Entry> l = ing.computeIfAbsent(id, k -> new ArrayList<>());
                if (!l.contains(e)) l.add(e);
            }
            ids.put(e.id(), e);
            cats.computeIfAbsent(e.category, k -> new LinkedHashSet<>()).add(e.pattern.result);
        }
        // Reference recipes first, vanilla last, so "the" recipe of an item is the interesting one.
        for (List<Entry> l : res.values()) l.sort(Comparator.comparing((Entry e) -> e.pattern.vanilla));
        Map<Category, List<String>> catLists = new EnumMap<>(Category.class);
        cats.forEach((c, s) -> {
            List<String> l = new ArrayList<>(s);
            l.sort(Comparator.comparing(this::sortName));
            catLists.put(c, List.copyOf(l));
        });
        List<Pattern> pats = new ArrayList<>(list.size());
        for (Entry e : list) pats.add(e.pattern);
        reversibleIds = Set.copyOf(CraftMath.reversible(pats));
        entries = List.copyOf(list);
        byResult = res;
        byIngredient = ing;
        byId = ids;
        resultsByCategory = catLists;
        signature = sig;
    }

    private String sortName(String id) { return tierAware(plainName(id)); }

    private String plainName(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? id : com.skyclone.api.ui.Text.strip(d.name).toLowerCase(Locale.ROOT);
    }

    /**
     * "oak minion ix" sorts as "oak minion 009", so tiers read I..XII in order instead of the plain
     * alphabetical I, II, III, IV, IX, V... (wave-3 playtest, Foraging Recipes page 1).
     */
    static String tierAware(String name) {
        int sp = name.lastIndexOf(' ');
        if (sp < 0 || sp == name.length() - 1) return name;
        int v = roman(name.substring(sp + 1));
        return v <= 0 ? name : name.substring(0, sp + 1) + String.format(Locale.ROOT, "%03d", v);
    }

    private static int roman(String s) {
        if (s.length() > 6) return -1;
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) { case 'i' -> 1; case 'v' -> 5; case 'x' -> 10; case 'l' -> 50; default -> -1; };
            if (v < 0) return -1;
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    private Entry compile(Recipe r) {
        if (r.result == null) return null;
        String result = canon(r.result);
        List<Set<String>> alt = vanillaCells.get(r.id);
        CraftMath.Cell[] exact = exactCells.get(r.id);
        Pattern.Ing[] grid = new Pattern.Ing[9];
        double coins = 0;
        // A CRAFTING recipe given only as an `inputs` list (no grid) is read as shapeless.
        boolean gridEmpty = true;
        if (r.grid != null) for (String g : r.grid) if (g != null && !g.isBlank()) { gridEmpty = false; break; }
        String[] cellsIn = r.grid;
        boolean shapeless = r.shapeless;
        if (gridEmpty && r.inputs != null && !r.inputs.isEmpty()) {
            if (r.inputs.size() > 9) return null;
            cellsIn = r.inputs.toArray(new String[0]);
            shapeless = true;
        }
        for (int i = 0; i < 9; i++) {
            String cell = cellsIn == null || i >= cellsIn.length ? null : cellsIn[i];
            if (cell == null || cell.isBlank()) continue;
            CraftMath.Cell c = exact != null && exact[i] != null ? exact[i] : CraftMath.parse(cell, Sky.items()::exists);
            if ("COINS".equalsIgnoreCase(c.id()) || "SKYBLOCK_COIN".equalsIgnoreCase(c.id())) { coins += c.amount(); continue; }
            Set<String> ids;
            if (alt != null && alt.get(i) != null) {
                ids = new LinkedHashSet<>();
                for (String a : alt.get(i)) ids.add(canon(a));
            } else {
                ids = Set.of(canon(c.id()));
            }
            grid[i] = new Pattern.Ing(ids, c.amount(), ids.iterator().next());
        }
        boolean vanilla = r.id != null && r.id.startsWith("vanilla:");
        Pattern p = Pattern.fromGrid(r.id, result, r.amount, shapeless, grid, coins, r.unlock, vanilla);
        if (p.cellCount() == 0) return null;
        Category cat = Category.of(r.unlock, vanilla, collectionCat, Sky.items().def(result));
        if (r.type == Recipe.Type.CARPENTRY) cat = Category.CARPENTRY;
        return new Entry(r, p, cat, vanillaCats.get(r.id));
    }

    // ---- queries ---------------------------------------------------------------------------------------

    List<Entry> all() { ensure(); return entries; }

    /** True when another recipe undoes this one (or it consumes its own result): it grants no Carpentry XP. */
    boolean reversible(String recipeId) { ensure(); return recipeId != null && reversibleIds.contains(recipeId); }

    int reversibleCount() { ensure(); return reversibleIds.size(); }

    List<Entry> producing(String id) { ensure(); return byResult.getOrDefault(canon(id), List.of()); }

    List<Entry> using(String id) { ensure(); return byIngredient.getOrDefault(canon(id), List.of()); }

    Entry byId(String recipeId) { ensure(); return byId.get(recipeId); }

    List<String> results(Category c) { ensure(); return resultsByCategory.getOrDefault(c, List.of()); }

    Collection<String> allResults() { ensure(); return byResult.keySet(); }

    /** Results whose display name or id contains the query (case-insensitive), names first. */
    List<String> search(String query) {
        ensure();
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<String> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        String qid = q.replace(' ', '_');
        for (String id : byResult.keySet()) {
            if (plainName(id).contains(q) || id.toLowerCase(Locale.ROOT).contains(qid)) out.add(id);
        }
        out.sort(Comparator.comparing((String id) -> !plainName(id).startsWith(q)).thenComparing(this::sortName));
        return out;
    }

    /** Recipe counts per category, for the root page. */
    Map<Category, Integer> recipeCounts() {
        ensure();
        Map<Category, Integer> m = new LinkedHashMap<>();
        for (Entry e : entries) m.merge(e.category, 1, Integer::sum);
        return m;
    }
}
