package com.shadowprison.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The ONLY list primitive. Any list that can outgrow one screen is a PagedMenu — SP1 golden rule
 * 10 found THIRTEEN menus that silently truncated (one hid shop offers on the PAID store, one
 * computed odds over items it never showed). Content is the 4x7 interior; arrows appear only when
 * there is somewhere to go.
 */
public abstract class PagedMenu<T> extends Menu {

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    private int page;

    protected PagedMenu(Component title) {
        super(title, 6);
    }

    /** The full list, freshly read on every render. */
    protected abstract List<T> entries(Player viewer);

    /** One entry's button. */
    protected abstract Button entry(Player viewer, T value);

    /** Optional extra controls on the bottom row (slots 45-53 except 48/50, which are the arrows). */
    protected void controls(Player viewer) {
    }

    /** Shown in the middle when the list is empty. */
    protected Button empty() {
        return Button.of(Ui.icon(Material.BARRIER).name("Nothing here yet", Ui.MUTED).build());
    }

    @Override
    protected final void build(Player viewer) {
        List<T> all = entries(viewer);
        int pages = Math.max(1, (all.size() + SLOTS.length - 1) / SLOTS.length);
        page = Math.max(0, Math.min(page, pages - 1));
        int start = page * SLOTS.length;
        for (int i = 0; i < SLOTS.length && start + i < all.size(); i++) {
            set(SLOTS[i], entry(viewer, all.get(start + i)));
        }
        if (all.isEmpty()) {
            set(22, empty());
        }
        if (page > 0) {
            set(48, Button.of(Ui.icon(Material.ARROW).name("Previous Page", Ui.ACTION)
                    .line("Page " + page + " of " + pages).build(), p -> {
                page--;
                refresh(p);
            }));
        }
        if (page < pages - 1) {
            set(50, Button.of(Ui.icon(Material.ARROW).name("Next Page", Ui.ACTION)
                    .line("Page " + (page + 2) + " of " + pages).build(), p -> {
                page++;
                refresh(p);
            }));
        }
        controls(viewer);
        border();
    }
}
