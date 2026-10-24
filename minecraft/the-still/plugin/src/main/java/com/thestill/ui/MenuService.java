package com.thestill.ui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single click listener behind every {@link Menu}.
 *
 * <p>Sixty menus in Shadow Prison each implemented {@code Listener} and
 * re-derived their own guard, cancel and dispatch logic, which is why the same
 * three bugs kept coming back. There is one of these, it is thirty lines, and
 * every menu inherits its correctness.
 */
public final class MenuService implements Listener {

    private final Logger log;

    public MenuService(Logger log) {
        this.log = log;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu menu)) {
            return;
        }

        // Cancel every click in this view, including ones in the player's own
        // inventory. A shift-click from below would otherwise shove an item into
        // the menu, and a menu holding a real item is how items get eaten.
        event.setCancelled(true);

        if (event.getClickedInventory() != top) {
            return; // they clicked their own inventory; cancelled is all we owed them
        }

        try {
            menu.dispatch(event);
        } catch (Exception e) {
            // A throwing handler must not leave the player stuck in a broken
            // view with a stack trace as the only feedback.
            log.log(Level.SEVERE, "menu handler threw for "
                    + event.getWhoClicked().getName(), e);
            menu.closeLater();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Menu)) {
            return;
        }
        // A drag that touches even one menu slot is cancelled outright. Dragging
        // is the click guard's blind spot: cancel clicks but not drags and a
        // player can still smear an item across a menu.
        int topSize = top.getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
