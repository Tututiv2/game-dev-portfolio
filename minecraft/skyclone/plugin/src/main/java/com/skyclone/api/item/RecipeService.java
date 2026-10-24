package com.skyclone.api.item;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/** Recipe registry. Pure data; owned by core so registration order between modules never matters. */
public interface RecipeService {

    void register(Recipe recipe);

    /** Load a JSON array of Recipe from {@code data/<module>/<file>}. Returns count. */
    int loadJson(String module, String file);

    Collection<Recipe> all();

    List<Recipe> byType(Recipe.Type type);

    /** Recipes that produce this item. */
    List<Recipe> producing(String itemId);

    /** Recipes that consume this item (the recipe book's "used in"). */
    List<Recipe> using(String itemId);

    /** Whether the player has unlocked it (requirement check; null unlock = true). */
    boolean unlocked(Player p, Recipe r);
}
