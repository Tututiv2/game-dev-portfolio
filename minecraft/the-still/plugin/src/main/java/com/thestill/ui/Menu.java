package com.thestill.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Base class for every chest GUI.
 *
 * <p>Two decisions here remove bug families that Shadow Prison kept
 * re-encountering across its sixty hand-rolled menus:
 *
 * <ol>
 *   <li><b>Dispatch is by {@link InventoryHolder} identity, not by title.</b>
 *       Its menus guarded clicks with a title-prefix check, so a new page whose
 *       title nobody added to the guard was simply dead — including its own Back
 *       button — with no error and nothing to grep for.
 *   <li><b>Buttons carry their own handler.</b> No action strings encoded into
 *       persistent data and parsed back out through a switch with hundreds of
 *       cases. A button is a slot, an item and a lambda.
 * </ol>
 */
public abstract class Menu implements InventoryHolder {

    protected final Plugin plugin;
    protected final Player viewer;

    private final Map<Integer, Consumer<Click>> handlers = new HashMap<>();

    /**
     * Every slot {@link #set} has written this build.
     *
     * <p>Not the same thing as "every slot holding an item" — {@link #fillBorder}
     * writes panes straight to the inventory on purpose, and a header tile drawn
     * over a border pane is normal. This tracks <em>controls</em>, and two controls
     * in one slot is always a bug.
     */
    private final Set<Integer> claimed = new HashSet<>();

    private Inventory inventory;

    protected Menu(Plugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    /** Chest rows, 1..6. */
    protected abstract int rows();

    protected abstract Component title();

    /** Fill the menu by calling {@link #set} and {@link #button}. */
    protected abstract void build();

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open() {
        inventory = Bukkit.createInventory(this, Math.clamp(rows(), 1, 6) * 9, title());
        handlers.clear();
        claimed.clear();
        build();
        viewer.openInventory(inventory);
    }

    /** Rebuilds in place, keeping the window open. Used after a click changes something. */
    public void refresh() {
        if (inventory == null) {
            open();
            return;
        }
        inventory.clear();
        handlers.clear();
        claimed.clear();
        build();
    }

    // ---- building -------------------------------------------------------

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /**
     * Puts a control in a slot.
     *
     * <p><b>Writing the same slot twice throws.</b> Shadow Prison's {@code /adminset}
     * had four of these — Merchant Marks under Vote Fragments, and three more on its
     * claim-deposit page where a later row silently erased the trinket, pet-egg and
     * relic tiles. A dead button is invisible in code and only shows up as somebody
     * saying "that one does nothing", so it has to be the sort of mistake that cannot
     * survive opening the menu once. It even wrote itself a comment saying "two setItem
     * calls on one slot is a silent feature deletion. Check before adding" — a rule
     * nobody can enforce by remembering.
     *
     * <p>If two states genuinely share one slot, they belong in one {@code if/else}
     * with a single call, which is what every menu here already does.
     */
    protected void set(int slot, ItemStack item, Consumer<Click> onClick) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        if (!claimed.add(slot)) {
            throw new IllegalStateException(getClass().getSimpleName()
                    + " writes slot " + slot + " twice in one build; the second write "
                    + "would silently delete the first control");
        }
        inventory.setItem(slot, item);
        if (onClick != null) {
            handlers.put(slot, onClick);
        }
    }

    protected void button(int slot, Material material, Component name,
                          List<Component> lore, Consumer<Click> onClick) {
        set(slot, MenuItems.of(material, name, lore), onClick);
    }

    protected void fillBorder(ItemStack filler) {
        int size = inventory.getSize();
        int width = 9;
        int height = size / width;
        for (int i = 0; i < size; i++) {
            int row = i / width;
            int column = i % width;
            if (row == 0 || row == height - 1 || column == 0 || column == width - 1) {
                if (inventory.getItem(i) == null) {
                    inventory.setItem(i, filler);
                }
            }
        }
    }

    // ---- clicking -------------------------------------------------------

    /**
     * Called by {@link MenuService} for a click in the menu's own inventory. The
     * event is already cancelled.
     */
    final void dispatch(InventoryClickEvent event) {
        Consumer<Click> handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            handler.accept(new Click(event, (Player) event.getWhoClicked()));
        }
    }

    /**
     * Closes on the next tick.
     *
     * <p>Bukkit explicitly forbids closing an inventory from inside
     * {@code InventoryClickEvent} — doing it directly leaves the view in a state
     * where the client and server disagree about what the player is holding,
     * which is one of the ways duplication bugs start.
     */
    protected void closeLater() {
        // Explicit lambda, not a method reference: BukkitScheduler overloads
        // runTask for both Runnable and Consumer<BukkitTask>, and a method
        // reference matches neither unambiguously.
        Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
    }

    protected void openLater(Menu next) {
        Bukkit.getScheduler().runTask(plugin, () -> next.open());
    }

    /** One click, with everything a handler needs and nothing it does not. */
    public record Click(InventoryClickEvent event, Player player) {

        public boolean isLeft() {
            return event.isLeftClick();
        }

        public boolean isRight() {
            return event.isRightClick();
        }

        public boolean isShift() {
            return event.isShiftClick();
        }

        public int slot() {
            return event.getRawSlot();
        }
    }

    /** Convenience for menu text: white, and never accidentally italic. */
    protected static Component plain(String text) {
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }
}
