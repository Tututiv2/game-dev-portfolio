package com.thestill.cosmetic;

import com.thestill.ui.Gradients;
import com.thestill.ui.Menu;
import com.thestill.ui.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The four exclusive gradients behind the LECTERN — a transcription of
 * {@code NickMenu.openArchiveShades} ({@code NickMenu.java:353-391}).
 *
 * <p>Three rows, four tiles at {@code 10, 12, 14, 16}, each drawn on its own wool with
 * its label rendered in its own gradient, and Back at 22.
 *
 * <p><b>Every tile renders unowned, and that is the source's own state rather than an
 * invention.</b> Over there ownership is
 * {@code archivistService != null && archivistService.ownsShade(...)}
 * ({@code NickMenu.java:365}) and the service is setter-injected after construction — a
 * server that never injects one draws exactly this page. There is no Archivist here, so
 * that is the page, and the two lore lines that named their NPC and their Skydeck are the
 * only strings changed.
 *
 * <p>What the source writes and this does not: {@code NickMenu.java:357-359} fills slots
 * 18–26 with grey panes immediately after {@code fillBorder}. In a 27-slot inventory that
 * range <em>is</em> the bottom frame row, so the second write puts the same pane back on
 * top of itself. {@link Menu#set} refuses a slot twice on purpose, so the buried write is
 * simply not made — the picture is identical.
 */
public final class ArchiveShadeMenu extends Menu {

    private final Supplier<Menu> back;

    public ArchiveShadeMenu(Plugin plugin, Player viewer, Supplier<Menu> back) {
        super(plugin, viewer);
        this.back = back;
    }

    @Override
    protected int rows() {
        // NickMenu.java:354 — 27, three rows.
        return 3;
    }

    @Override
    protected Component title() {
        // NickMenu.java:354-355, verbatim. GOLD, unlike every other page in this menu.
        return Component.text("Nickname · Archive Shades", NamedTextColor.GOLD);
    }

    @Override
    protected void build() {
        // NickMenu.java:356, verbatim.
        fillBorder(MenuItems.pane(Material.GRAY_STAINED_GLASS_PANE));

        List<Palette.ArchiveShade> shades = Palette.ARCHIVE_SHADES;
        for (int i = 0; i < shades.size() && i < Palette.ARCHIVE_SLOTS.length; i++) {
            Palette.ArchiveShade shade = shades.get(i);
            List<Component> lore = new ArrayList<>();
            // NickMenu.java:374, verbatim.
            lore.add(Component.text(shade.startHex() + " → " + shade.endHex(),
                    NamedTextColor.DARK_GRAY));
            lore.add(Component.text("Exclusive — not given out on this server yet",
                    NamedTextColor.DARK_PURPLE));
            set(Palette.ARCHIVE_SLOTS[i], MenuItems.of(shade.wool(),
                            Gradients.bold(shade.label(),
                                    Gradients.parse(shade.startHex(), NamedTextColor.WHITE),
                                    Gradients.parse(shade.endHex(), NamedTextColor.WHITE)),
                            lore),
                    click -> {
                        // NickMenu.java:737-739 — the locked tile says so rather than
                        // doing nothing, which is the difference between locked and broken.
                        click.player().sendMessage(Component.text(
                                "  That shade is not given out on this server yet.",
                                NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
                        click.player().playSound(click.player().getLocation(),
                                Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
                    });
        }

        // NickMenu.java:387-389, verbatim — the same Back tile as the shade pages.
        set(22, MenuItems.of(Material.ARROW,
                        Component.text("Back", NamedTextColor.YELLOW),
                        MenuItems.lore("Back to the color palette")),
                click -> openLater(back.get()));
    }
}
