package com.shadowprison.menu;

import com.shadowprison.core.Service;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single listener for every {@link Menu}.
 *
 * <p>Inside a menu view EVERY click is cancelled — top or bottom, shift, number-key swap,
 * double-click collect — before anything else happens, and drags are cancelled outright. A menu
 * is never a container. Then the clicked top slot's action runs, wrapped so a throwing handler
 * logs and tells the player instead of leaving them in a dead menu (SP1 shipped a page whose
 * every button was dead behind a title guard).
 *
 * <p>One action per player per tick: two click packets landing in the same tick were the root of
 * several SP1 dupes (enchant-book swap, /trade). The second is simply ignored.
 */
public final class MenuService implements Service, Listener {

    private final Logger log;
    private final Map<UUID, Integer> lastActionTick = new HashMap<>();

    public MenuService(Logger log) {
        this.log = log;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;   // own inventory: nothing in a menu ever reads it
        }
        Button button = menu.button(event.getSlot());
        if (button == null || button.action() == null) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        Integer last = lastActionTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }
        try {
            button.action().click(player, event.getClick());
        } catch (Throwable t) {
            log.log(Level.SEVERE, "menu action failed in " + menu.getClass().getSimpleName()
                    + " slot " + event.getSlot() + " for " + player.getName(), t);
            player.sendMessage(Component.text("Something went wrong — this has been logged.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Menu menu
                && event.getPlayer() instanceof Player player) {
            try {
                menu.closed(player);
            } catch (Throwable t) {
                log.log(Level.WARNING, "menu close hook failed in " + menu.getClass().getSimpleName(), t);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastActionTick.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void stop() {
        // Close every open menu so no viewer is left holding a dead window across a reload.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) {
                player.closeInventory();
            }
        }
    }
}
