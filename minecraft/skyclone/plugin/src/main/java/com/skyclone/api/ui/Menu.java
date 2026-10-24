package com.skyclone.api.ui;

import com.skyclone.api.Sky;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A chest GUI in the genre's style. Every click is cancelled unless the slot is declared
 * {@link #free} — icons can never be taken out, which on a public server is a dupe fix, not a nicety.
 *
 * <pre>
 *   Menu m = new Menu("Your Skills", 6);
 *   m.chrome();                                            // black glass border + Close at slot 49
 *   m.back("SkyBlock Menu", () -&gt; SkyMenu.build(p));       // Go Back arrow at slot 48
 *   m.set(19, icon, click -&gt; openFarming(click.player()));
 *   m.open(p);
 * </pre>
 *
 * For menus that change while open (timers, counters), pass a drawer with {@link #drawer} and
 * call {@link #redraw()}; or {@link #refreshEvery(int)} ticks.
 *
 * <p>Slot layout reminder for 6 rows: row r col c = r*9 + c. 4 = top middle, 49 = bottom middle.
 */
public class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final int rows;
    private final String title;
    private final Map<Integer, Consumer<MenuClick>> handlers = new HashMap<>();
    private final Set<Integer> freeSlots = new HashSet<>();
    private Consumer<Player> onClose;
    private Consumer<Menu> drawer;
    private BiConsumer<MenuClick, ItemStack> playerInventoryClick;
    private Consumer<MenuClick> freeSlotChange;
    private int refreshTicks;
    private boolean keepFreeSlotItems;

    public Menu(String title, int rows) {
        this.rows = Math.max(1, Math.min(6, rows));
        this.title = title;
        this.inventory = Bukkit.createInventory(this, this.rows * 9, Text.c(title));
    }

    @Override public @NotNull Inventory getInventory() { return inventory; }
    public int rows() { return rows; }
    public int size() { return rows * 9; }
    public String title() { return title; }

    // ---- contents ---------------------------------------------------------------------------

    public Menu set(int slot, ItemStack icon) {
        inventory.setItem(slot, icon);
        handlers.remove(slot);
        return this;
    }

    public Menu set(int slot, ItemStack icon, Consumer<MenuClick> onClick) {
        inventory.setItem(slot, icon);
        if (onClick != null) handlers.put(slot, onClick); else handlers.remove(slot);
        return this;
    }

    public ItemStack get(int slot) { return inventory.getItem(slot); }

    public Menu clear() {
        inventory.clear();
        handlers.clear();
        return this;
    }

    /** Fill every empty slot with black glass. */
    public Menu fillEmpty() {
        for (int i = 0; i < size(); i++) {
            if (freeSlots.contains(i)) continue;
            ItemStack cur = inventory.getItem(i);
            if (cur == null || cur.getType().isAir()) inventory.setItem(i, Icons.filler());
        }
        return this;
    }

    /** Fill everything (including occupied slots) with black glass, then put Close at bottom middle. */
    public Menu chrome() {
        for (int i = 0; i < size(); i++) if (!freeSlots.contains(i)) { inventory.setItem(i, Icons.filler()); handlers.remove(i); }
        closeButton();
        return this;
    }

    /** Only the outer ring is glass. */
    public Menu border() {
        for (int i = 0; i < size(); i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) { inventory.setItem(i, Icons.filler()); handlers.remove(i); }
        }
        closeButton();
        return this;
    }

    public int closeSlot() { return size() - 5; }

    public Menu closeButton() {
        return set(closeSlot(), Icons.close(), c -> c.player().closeInventory());
    }

    /** "Go Back" arrow left of Close. */
    public Menu back(String where, Supplier<Menu> to) {
        return set(closeSlot() - 1, Icons.back(where), c -> to.get().open(c.player()));
    }

    // ---- interactive slots -----------------------------------------------------------------

    /** Players may place/take items in these slots (crafting grid, storage, sell slot). */
    public Menu free(int... slots) {
        for (int s : slots) { freeSlots.add(s); handlers.remove(s); }
        return this;
    }

    public Set<Integer> freeSlots() { return freeSlots; }

    /**
     * This menu STORES what players put in its free slots (storage pages, backpacks, accessory bag,
     * wardrobe, quiver): core will NOT hand free-slot items back on close. Your onClose must save
     * them. Without this flag core returns them to the player, which for a storage menu is a dupe.
     * (Added by the core gate, 2026-10-02.)
     */
    public Menu keepFreeSlotItems() { this.keepFreeSlotItems = true; return this; }

    public boolean keepsFreeSlotItems() { return keepFreeSlotItems; }

    /** Called (next tick, after the item moved) whenever a free slot's content changes. */
    public Menu onFreeSlotChange(Consumer<MenuClick> handler) { this.freeSlotChange = handler; return this; }

    /**
     * Clicks in the PLAYER's own inventory while this menu is open. Default: shift-click into the
     * menu is blocked, normal clicks allowed. Set a handler to react (e.g. click an item to sell it).
     */
    public Menu onPlayerInventoryClick(BiConsumer<MenuClick, ItemStack> handler) { this.playerInventoryClick = handler; return this; }

    public Menu onClose(Consumer<Player> handler) { this.onClose = handler; return this; }

    // ---- redraw ------------------------------------------------------------------------------

    public Menu drawer(Consumer<Menu> drawer) { this.drawer = drawer; return this; }

    public void redraw() { if (drawer != null) drawer.accept(this); }

    public Menu refreshEvery(int ticks) { this.refreshTicks = ticks; return this; }

    public int refreshTicks() { return refreshTicks; }

    public Menu open(Player p) {
        redraw();
        Sky.menus().open(p, this);
        return this;
    }

    // ---- called by core ------------------------------------------------------------------------

    public Consumer<MenuClick> handler(int slot) { return handlers.get(slot); }
    public Consumer<Player> closeHandler() { return onClose; }
    public BiConsumer<MenuClick, ItemStack> playerInventoryHandler() { return playerInventoryClick; }
    public Consumer<MenuClick> freeSlotHandler() { return freeSlotChange; }
}
