package com.skyclone.core.ui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;

/**
 * Vanilla's recipe book and advancements have nothing to do with this game, but they still fire:
 * the first real-client session showed "New Recipe(s) Unlocked!" and "Advancement Made! Stone Age"
 * toasts over the SkyBlock HUD every time an item was picked up. Both are cancelled at the source
 * (dials {@code ui.vanilla-recipe-toasts} / {@code ui.vanilla-advancements}, default off).
 */
final class VanillaNoise implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRecipe(PlayerRecipeDiscoverEvent e) {
        if (!UiDials.vanillaRecipes.get()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementCriterionGrantEvent e) {
        if (UiDials.vanillaAdvancements.get()) return;
        // Keep the hidden root/recipe advancements out of the way too; nothing in the game reads them.
        e.setCancelled(true);
    }
}
