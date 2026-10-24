package com.shadowprison.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * A server-owned chest menu.
 *
 * <p><b>Identity is the holder, never the title.</b> SP1 recognised menus by their title text, and
 * any player can anvil-rename a chest to any title: a renamed double chest cloned items into Gear
 * Sets, and a renamed "⚙" chest drove an admin page (golden rule 28). Here the open inventory's
 * holder IS this object, which no placed block can ever be.
 *
 * <p>Subclasses fill {@link #build(Player)} with {@link #set}. Re-call {@link #refresh(Player)} to
 * redraw in place.
 */
public abstract class Menu implements InventoryHolder {

    private final Component title;
    private final int rows;
    private final Map<Integer, Button> buttons = new HashMap<>();
    private Inventory inventory;

    protected Menu(Component title, int rows) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("rows must be 1-6");
        }
        this.title = title;
        this.rows = rows;
    }

    /** Populate the menu for this viewer. Called on open and on every refresh. */
    protected abstract void build(Player viewer);

    /** Called when the viewer closes this menu (not when it is replaced by a refresh). */
    protected void onClose(Player viewer) {
    }

    public final void open(Player viewer) {
        inventory = Bukkit.createInventory(this, rows * 9, title);
        render(viewer);
        viewer.openInventory(inventory);
    }

    /** Redraws without closing — the cursor and the window stay put. */
    public final void refresh(Player viewer) {
        if (inventory == null) {
            open(viewer);
            return;
        }
        render(viewer);
    }

    private void render(Player viewer) {
        buttons.clear();
        inventory.clear();
        build(viewer);
        for (Map.Entry<Integer, Button> e : buttons.entrySet()) {
            inventory.setItem(e.getKey(), e.getValue().icon());
        }
    }

    protected final void set(int slot, Button button) {
        if (slot >= 0 && slot < rows * 9) {
            buttons.put(slot, button);
        }
    }

    protected final void set(int slot, ItemStack decoration) {
        set(slot, Button.of(decoration));
    }

    /** Fills every empty slot on the outer ring with the standard border. */
    protected final void border() {
        int size = rows * 9;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            if ((row == 0 || row == rows - 1 || col == 0 || col == 8) && !buttons.containsKey(slot)) {
                buttons.put(slot, Button.of(Ui.pane()));
            }
        }
    }

    /** Fills every remaining empty slot. */
    protected final void fill() {
        for (int slot = 0; slot < rows * 9; slot++) {
            buttons.putIfAbsent(slot, Button.of(Ui.pane()));
        }
    }

    public final int rows() {
        return rows;
    }

    final Button button(int slot) {
        return buttons.get(slot);
    }

    final void closed(Player viewer) {
        onClose(viewer);
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }
}
