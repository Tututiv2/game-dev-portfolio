package com.thestill.npc;

import com.thestill.economy.Money;
import com.thestill.economy.SellService;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.ui.Menu;
import com.thestill.ui.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * The Assay Office counter — the first thing on this rig that a player can use.
 *
 * <p>Deliberately no new economy: it is a face on {@link SellService}, which already
 * exists, is already tested, and already knows what a haul is worth. The point of
 * shipping this one first is that it proves the whole path — a derived counter, a
 * persistent clerk, a menu that dispatches on holder identity — against a service that
 * cannot be wrong.
 *
 * <p>It is also the seam Phase 2 lands on. When ore starts carrying a Din band stamped
 * into its PDC <em>at break time</em>, this is the screen that reads it. The build plan
 * is blunt about the alternative: apply the multiplier at sell time and players bank at
 * CHOIR then cash out safely in the morning, and the design is dead.
 */
public final class AssayMenu extends Menu {

    private final Profile profile;
    private final Profiles profiles; // kept: Phase 2 sells through a durable save
    private final SellService selling;

    public AssayMenu(Plugin plugin, Player viewer, Profile profile,
                     Profiles profiles, SellService selling) {
        super(plugin, viewer);
        this.profile = profile;
        this.profiles = profiles;
        this.selling = selling;
    }

    @Override
    protected int rows() {
        return 3;
    }

    @Override
    protected Component title() {
        return Component.text("Assay Office", NamedTextColor.DARK_AQUA, TextDecoration.BOLD);
    }

    @Override
    protected void build() {
        fillBorder(MenuItems.of(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of()));

        long carried = selling.appraise(viewer);

        button(11, Material.RAW_IRON,
                Component.text("Sell what you are holding", NamedTextColor.AQUA,
                        TextDecoration.BOLD),
                List.of(grey("The stack in your hand, weighed and paid."),
                        grey("Anything the office does not buy comes back.")),
                click -> {
                    SellService.Result result = selling.sellHeld(click.player(), profile);
                    report(click.player(), result);
                });

        button(13, Material.GOLD_INGOT,
                Component.text("Sell the haul", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(grey("Everything in your pack that has a price."),
                        Component.empty(),
                        Component.text("  worth about ", NamedTextColor.DARK_GRAY)
                                .append(Component.text(Money.compact(carried),
                                        carried > 0 ? NamedTextColor.GREEN
                                                : NamedTextColor.DARK_GRAY))),
                click -> {
                    SellService.Result result = selling.sellAll(click.player(), profile);
                    report(click.player(), result);
                });

        button(15, Material.SPYGLASS,
                Component.text("Just weigh it", NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(grey("What the office would pay, without paying it.")),
                click -> {
                    long worth = selling.appraise(click.player());
                    click.player().sendMessage(Component.text("  The scale reads ",
                                    NamedTextColor.DARK_GRAY)
                            .append(Component.text(Money.format(worth), NamedTextColor.WHITE)));
                    click.player().playSound(click.player().getLocation(),
                            Sound.BLOCK_LODESTONE_PLACE, 0.6f, 1.4f);
                });

        button(22, Material.BARRIER,
                Component.text("Done", NamedTextColor.DARK_GRAY), List.of(),
                click -> closeLater());
    }

    /**
     * The line after a sale.
     *
     * <p>Rebuilds rather than closing, so a second stack can be sold without walking
     * back to the counter — and it refreshes through {@link #refresh()} rather than
     * opening a new inventory from inside a click handler, which is the thing Bukkit
     * explicitly forbids and the way duplication bugs start.
     */
    private void report(Player player, SellService.Result result) {
        if (!result.soldAnything()) {
            player.sendMessage(Component.text("  The office has no use for that.",
                    NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f);
            return;
        }
        profile.markDirty();
        player.sendMessage(Component.text("  Weighed ", NamedTextColor.DARK_GRAY)
                .append(Component.text(result.items() + " item(s)", NamedTextColor.WHITE))
                .append(Component.text(" for ", NamedTextColor.DARK_GRAY))
                .append(Component.text(Money.format(result.total()), NamedTextColor.GREEN,
                        TextDecoration.BOLD)));
        if (result.skipped() > 0) {
            player.sendMessage(Component.text("  " + result.skipped()
                            + " the office would not take.",
                    NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        refresh();
    }

    private static Component grey(String text) {
        return Component.text(text, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }
}
