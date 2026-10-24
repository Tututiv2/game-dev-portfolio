package com.skyclone.api.ui;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Opens menus and routes their clicks. Also owns the generic building blocks every module
 * would otherwise re-write: paging, confirmation, and text input.
 */
public interface MenuService {

    void open(Player p, Menu menu);

    /** The menu the player has open, or null. */
    Menu current(Player p);

    /**
     * A paged list: {@code entries} are laid out in the inner 7x4 area (slots 10-16, 19-25,
     * 28-34, 37-43) with prev/next arrows at 45/53, chrome and Close. {@code decorate} may add
     * extra buttons (sort, search, back) to each page.
     */
    <T> Menu paged(String title, List<T> entries, Function<T, org.bukkit.inventory.ItemStack> icon,
                   Consumer<PagedClick<T>> onClick, Consumer<Menu> decorate, int page);

    record PagedClick<T>(MenuClick click, T entry, int page) {}

    /** Yes / no confirmation (green clay = confirm, red = cancel). */
    void confirm(Player p, String title, List<String> description, Runnable onYes, Runnable onNo);

    /** Ask for a line of text via a sign editor (falls back to chat). Callback on the main thread; null if cancelled. */
    void input(Player p, String prompt, Consumer<String> result);

    /** Ask for a number; re-prompts on garbage. Supports "1k", "2.5m", "64". */
    void inputNumber(Player p, String prompt, Consumer<Double> result);
}
