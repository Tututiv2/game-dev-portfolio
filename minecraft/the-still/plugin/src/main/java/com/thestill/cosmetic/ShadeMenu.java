package com.thestill.cosmetic;

import com.thestill.ui.Gradients;
import com.thestill.ui.Menu;
import com.thestill.ui.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Five shades of one colour family — <b>a transcription of {@code NickMenu.openShades}</b>
 * ({@code NickMenu.java:309-346}).
 *
 * <p>Three rows, the family's own wool on every tile, and the five tiles at
 * {@link Palette#SHADE_SLOTS} — {@code 10, 11, 13, 15, 16}, <b>with the gaps</b>. An
 * earlier pass here made them contiguous because a run of five with two holes in it looks
 * like an accident. It is not: five blocks of the same wool touching read as one object,
 * and the gaps are what make them read as a scale.
 *
 * <p>The one thing that is not a copy is the plumbing. The source hard-wires this page to
 * a {@code kind} string of {@code "primary"} or {@code "end"} that it parses back out of
 * persistent data; here it takes a consumer and a way back, which is what lets the same
 * page serve a start colour and an end colour without two copies of it.
 */
public final class ShadeMenu extends Menu {

    private final Palette.Family family;
    private final String what;
    private final Consumer<String> onPick;
    private final Supplier<Menu> back;

    /**
     * @param what   the word the source drops into "Click to select for …" — it passes
     *               {@code "start"} or {@code "end"} ({@code NickMenu.java:334})
     * @param onPick handed the {@code #RRGGBB}. Responsible for saving and for reopening
     *               whatever should be on screen afterwards
     */
    public ShadeMenu(Plugin plugin, Player viewer, Palette.Family family, String what,
                     Consumer<String> onPick, Supplier<Menu> back) {
        super(plugin, viewer);
        this.family = family;
        this.what = what;
        this.onPick = onPick;
        this.back = back;
    }

    @Override
    protected int rows() {
        // NickMenu.java:320 — 27, three rows.
        return 3;
    }

    @Override
    protected Component title() {
        // NickMenu.java:320-322, verbatim.
        return Component.text("Nickname · " + family.name() + " shades",
                NamedTextColor.LIGHT_PURPLE);
    }

    @Override
    protected void build() {
        // NickMenu.java:323, verbatim.
        fillBorder(MenuItems.pane(Material.GRAY_STAINED_GLASS_PANE));

        List<String> shades = family.shades();
        for (int i = 0; i < shades.size() && i < Palette.SHADE_SLOTS.length; i++) {
            String hex = shades.get(i);
            // NickMenu.java:330-336. The label is drawn IN the shade it names, on a tile
            // of the family's wool — so the five tiles are identical blocks and the words
            // on them are the scale.
            set(Palette.SHADE_SLOTS[i], MenuItems.of(family.wool(),
                            Component.text(Palette.SHADE_NAMES.get(i),
                                    Gradients.parse(hex, NamedTextColor.WHITE),
                                    TextDecoration.BOLD),
                            List.of(Component.text("Click to select for " + what,
                                            NamedTextColor.GRAY),
                                    Component.text(hex, NamedTextColor.DARK_GRAY))),
                    click -> onPick.accept(hex));
        }

        // NickMenu.java:342-344, verbatim.
        set(22, MenuItems.of(Material.ARROW,
                        Component.text("Back", NamedTextColor.YELLOW),
                        MenuItems.lore("Back to the color palette")),
                click -> openLater(back.get()));
    }
}
