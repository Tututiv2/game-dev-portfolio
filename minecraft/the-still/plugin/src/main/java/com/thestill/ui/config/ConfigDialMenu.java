package com.thestill.ui.config;

import com.thestill.config.Dial;
import com.thestill.config.DialKind;
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

/** Every dial in one category, editable in place. */
public final class ConfigDialMenu extends Menu {

    private final DialRegistry registry;
    /** Carried through only so Back can rebuild the hub exactly as it was. */
    private final ItemVault vault;
    private final String category;

    public ConfigDialMenu(Plugin plugin, Player viewer, DialRegistry registry,
                          ItemVault vault, String category) {
        super(plugin, viewer);
        this.registry = registry;
        this.vault = vault;
        this.category = category;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return Component.text("Config · " + category, NamedTextColor.DARK_AQUA);
    }

    @Override
    protected void build() {
        List<Dial<?>> dials = registry.byCategory().getOrDefault(category, List.of());

        int slot = 10;
        for (Dial<?> dial : dials) {
            set(slot, render(dial), click -> handle(dial, click));
            slot++;
            if (slot % 9 == 8) {
                slot += 2;
            }
            if (slot >= 45) {
                break;
            }
        }

        set(49, MenuItems.back(),
                click -> openLater(new ConfigMenu(plugin, viewer, registry, vault)));
        fillBorder(MenuItems.filler());
    }

    private org.bukkit.inventory.ItemStack render(Dial<?> dial) {
        List<Component> lore = new ArrayList<>(6);
        lore.add(Component.text(dial.description(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("Now: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(String.valueOf(dial.get()),
                        dial.isDefault() ? NamedTextColor.WHITE : NamedTextColor.GOLD)));
        lore.add(Component.text("Default: " + dial.defaultValue(), NamedTextColor.DARK_GRAY));

        if (dial.kind().isNumeric()) {
            lore.add(Component.text("Range: " + trim(dial.min()) + " to " + trim(dial.max()),
                    NamedTextColor.DARK_GRAY));
            lore.add(Component.empty());
            lore.add(Component.text("Left +" + trim(dial.step())
                    + "  ·  Right -" + trim(dial.step())
                    + "  ·  Shift x4", NamedTextColor.YELLOW));
        } else if (dial.kind() == DialKind.BOOLEAN) {
            lore.add(Component.empty());
            lore.add(Component.text("Click to toggle", NamedTextColor.YELLOW));
        } else {
            lore.add(Component.empty());
            lore.add(Component.text("/still dial set " + dial.key() + " <value>",
                    NamedTextColor.YELLOW));
        }
        lore.add(Component.text(dial.key(), NamedTextColor.DARK_GRAY));

        Material material = switch (dial.kind()) {
            case BOOLEAN -> dial.asBoolean() ? Material.LIME_DYE : Material.GRAY_DYE;
            case STRING -> Material.NAME_TAG;
            default -> Material.PAPER;
        };
        return MenuItems.of(material,
                Component.text(dial.label(), dial.isDefault()
                        ? NamedTextColor.AQUA : NamedTextColor.GOLD),
                lore);
    }

    @SuppressWarnings("unchecked")
    private void handle(Dial<?> dial, Click click) {
        switch (dial.kind()) {
            case BOOLEAN -> ((Dial<Boolean>) dial).applyFromString(
                    String.valueOf(!dial.asBoolean()));
            case STRING -> {
                click.player().sendMessage(Component.text(
                        "Text dials are set with /still dial set " + dial.key() + " <value>",
                        NamedTextColor.YELLOW));
                return;
            }
            default -> {
                double magnitude = dial.step() * (click.isShift() ? 4 : 1);
                double delta = click.isRight() ? -magnitude : magnitude;
                // Clamping happens inside the dial, so a click can never push a
                // value outside the range its author declared.
                dial.applyFromString(String.valueOf(dial.asDouble() + delta));
            }
        }
        registry.save();
        refresh();
    }

    private static String trim(double value) {
        return value == Math.rint(value) && Double.isFinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }
}
