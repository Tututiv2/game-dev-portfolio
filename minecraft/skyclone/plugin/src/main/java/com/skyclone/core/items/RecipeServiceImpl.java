package com.skyclone.core.items;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.item.RecipeService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Recipe registry. Indexes (producing / using) are rebuilt lazily after any registration, and
 * also when the item registry grew — because whether "INK_SACK:3" is an id or "INK_SACK x3"
 * depends on which items exist, and materials registers its ids last (postEnable).
 */
public final class RecipeServiceImpl implements RecipeService {

    private final JavaPlugin plugin;
    private final ItemServiceImpl items;
    private final List<Recipe> recipes = new CopyOnWriteArrayList<>();
    private final Set<String> ids = Collections.synchronizedSet(new HashSet<>());

    private volatile Map<String, List<Recipe>> producing = Map.of();
    private volatile Map<String, List<Recipe>> using = Map.of();
    private volatile Map<Recipe.Type, List<Recipe>> byType = Map.of();
    private volatile boolean dirty = true;
    private volatile int indexedItemVersion = -1;

    public RecipeServiceImpl(JavaPlugin plugin, ItemServiceImpl items) {
        this.plugin = plugin;
        this.items = items;
    }

    @Override
    public void register(Recipe r) {
        String why = DefJson.normalize(r);
        if (why != null) {
            plugin.getLogger().warning("Recipe for " + (r == null ? "?" : r.result) + " rejected: " + why);
            return;
        }
        if (r.id == null || r.id.isBlank()) {
            int n = 0;
            String base = r.result + "#";
            while (ids.contains(base + n)) n++;
            r.id = base + n;
        }
        if (!ids.add(r.id)) {
            plugin.getLogger().warning("Duplicate recipe id " + r.id + " (kept the first)");
            return;
        }
        recipes.add(r);
        dirty = true;
    }

    @Override
    public int loadJson(String module, String file) {
        String path = "data/" + module + "/" + file;
        int[] ok = {0};
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                plugin.getLogger().warning("[recipes] " + path + " not found in the jar");
                return 0;
            }
            try (Reader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                DefJson.readRecipes(rd, new DefJson.EntrySink<>() {
                    @Override public void ok(Recipe value) {
                        int before = recipes.size();
                        register(value);
                        if (recipes.size() > before) ok[0]++;
                    }
                    @Override public void bad(String id, String reason) {
                        plugin.getLogger().warning("[recipes] " + path + " entry " + id + " skipped: " + reason);
                    }
                });
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[recipes] could not read " + path, e);
        }
        plugin.getLogger().info("[recipes] " + module + "/" + file + ": " + ok[0] + " recipes");
        return ok[0];
    }

    @Override public Collection<Recipe> all() { return Collections.unmodifiableList(recipes); }

    @Override
    public List<Recipe> byType(Recipe.Type type) {
        index();
        return byType.getOrDefault(type, List.of());
    }

    @Override
    public List<Recipe> producing(String itemId) {
        if (itemId == null) return List.of();
        index();
        return producing.getOrDefault(itemId, List.of());
    }

    @Override
    public List<Recipe> using(String itemId) {
        if (itemId == null) return List.of();
        index();
        return using.getOrDefault(itemId, List.of());
    }

    @Override
    public boolean unlocked(Player p, Recipe r) {
        if (r == null) return false;
        if (r.unlock == null || r.unlock.isBlank()) return true;
        try {
            return Sky.requirements().check(p, r.unlock);
        } catch (RuntimeException e) {
            return false; // closed by default
        }
    }

    /** Parsed ingredients of a recipe against the live item registry. */
    public Map<String, Integer> ingredients(Recipe r) {
        return RecipeCells.ingredients(r, items::exists);
    }

    private void index() {
        int v = items.version();
        if (!dirty && v == indexedItemVersion) return;
        synchronized (this) {
            if (!dirty && v == indexedItemVersion) return;
            Map<String, List<Recipe>> prod = new HashMap<>(), use = new HashMap<>();
            Map<Recipe.Type, List<Recipe>> types = new HashMap<>();
            for (Recipe r : recipes) {
                prod.computeIfAbsent(r.result, k -> new ArrayList<>()).add(r);
                types.computeIfAbsent(r.type, k -> new ArrayList<>()).add(r);
                for (String id : RecipeCells.ingredientIds(r, items::exists)) {
                    List<Recipe> l = use.computeIfAbsent(id, k -> new ArrayList<>());
                    if (!l.contains(r)) l.add(r);
                }
            }
            producing = freeze(prod);
            using = freeze(use);
            Map<Recipe.Type, List<Recipe>> t2 = new HashMap<>();
            types.forEach((k, l) -> t2.put(k, List.copyOf(l)));
            byType = t2;
            indexedItemVersion = v;
            dirty = false;
        }
    }

    private static Map<String, List<Recipe>> freeze(Map<String, List<Recipe>> m) {
        Map<String, List<Recipe>> out = new HashMap<>(m.size() * 2);
        m.forEach((k, l) -> out.put(k, List.copyOf(l)));
        return out;
    }
}
