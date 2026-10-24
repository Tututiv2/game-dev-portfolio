package com.thestill.ui.config;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.ui.Menu;
import com.thestill.ui.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The root of {@code /config}: one tile per dial category, generated from the
 * registry.
 *
 * <p>Nothing here is hardcoded. Shadow Prison's admin panel was 12,626 lines
 * with dial tables written out by hand as {@code String[][]}, and the predictable
 * happened — a whole family of tunables shipped with no panel entry at all,
 * because adding one meant editing four separate places and the fourth got
 * forgotten. This menu cannot fall behind the code, because it <em>is</em> the
 * code, read back.
 */
public final class ConfigMenu extends Menu {

    private final DialRegistry registry;

    /** The item vault's dependencies, or null if it was never wired. */
    private final ItemVault vault;

    public ConfigMenu(Plugin plugin, Player viewer, DialRegistry registry, ItemVault vault) {
        super(plugin, viewer);
        this.registry = registry;
        this.vault = vault;
    }

    @Override
    protected int rows() {
        return 4;
    }

    @Override
    protected Component title() {
        return Component.text("The Still · config", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        Map<String, List<Dial<?>>> categories = registry.byCategory();

        int slot = 10;
        for (Map.Entry<String, List<Dial<?>>> entry : categories.entrySet()) {
            String category = entry.getKey();
            List<Dial<?>> dials = entry.getValue();
            long changed = dials.stream().filter(dial -> !dial.isDefault()).count();

            List<Component> lore = new ArrayList<>(3);
            lore.add(Component.text(dials.size() + " dial" + (dials.size() == 1 ? "" : "s"),
                    NamedTextColor.GRAY));
            lore.add(changed == 0
                    ? Component.text("all at defaults", NamedTextColor.DARK_GRAY)
                    : Component.text(changed + " changed from default", NamedTextColor.GOLD));
            lore.add(Component.empty());
            lore.add(Component.text("Click to open", NamedTextColor.YELLOW));

            set(slot, MenuItems.of(Material.COMPARATOR,
                            Component.text(category, NamedTextColor.AQUA), lore),
                    click -> openLater(new ConfigDialMenu(plugin, viewer, registry, vault,
                            category)));

            slot++;
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= rows() * 9 - 9) {
                break;
            }
        }

        // The bottom row. The category loop above stops before slot 27 by
        // construction, so this row is ours; both tiles are written before
        // fillBorder, which only ever fills what is still empty.
        set(30, MenuItems.of(Material.BOOK,
                Component.text("Every dial in the plugin", NamedTextColor.WHITE),
                List.of(Component.text(registry.size() + " registered", NamedTextColor.GRAY),
                        Component.text("Dials register themselves when they are written,",
                                NamedTextColor.DARK_GRAY),
                        Component.text("so this list is never out of date.",
                                NamedTextColor.DARK_GRAY))));

        // THE ITEM VAULT. Sits beside the dial book because it is the same idea
        // pointed at items: one generated list of everything the plugin can make,
        // so testing a thing never depends on knowing a command for it.
        if (vault != null) {
            set(32, MenuItems.of(Material.CHEST,
                            Component.text("Item vault", NamedTextColor.GOLD),
                            List.of(Component.text(vault.items().size() + " custom item"
                                            + (vault.items().size() == 1 ? "" : "s"),
                                            NamedTextColor.GRAY),
                                    Component.text("Everything this plugin can build,",
                                            NamedTextColor.DARK_GRAY),
                                    Component.text("grabbable. Every grant is logged.",
                                            NamedTextColor.DARK_GRAY),
                                    Component.empty(),
                                    Component.text("Click to open", NamedTextColor.YELLOW))),
                    click -> openLater(new ItemVaultMenu(plugin, viewer, registry, vault, 0)));
        }

        fillBorder(MenuItems.filler());
    }
}
