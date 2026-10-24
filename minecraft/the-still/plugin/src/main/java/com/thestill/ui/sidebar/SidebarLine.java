package com.thestill.ui.sidebar;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * One line a module contributes to the sidebar.
 *
 * <p>The supplier is live — it is asked again on every refresh — so a module
 * never pushes an update and never has to know the sidebar exists beyond the
 * one call that registers it.
 */
@FunctionalInterface
public interface SidebarLine {

    /**
     * Renders this line for one viewer.
     *
     * @return the line, or <b>null</b> to hide it entirely. Hiding is how a line
     *         that has nothing to say this second (no bounty, no Wake, not in a
     *         region) disappears instead of showing a confusing zero.
     */
    Component render(Player viewer);

    /** A fixed line of text, for separators and headings. */
    static SidebarLine constant(Component text) {
        return viewer -> text;
    }

    /** A blank spacer. */
    static SidebarLine spacer() {
        return viewer -> Component.empty();
    }
}
