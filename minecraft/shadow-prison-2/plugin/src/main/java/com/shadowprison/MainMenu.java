package com.shadowprison;

import com.shadowprison.dial.ConfigMenu;
import com.shadowprison.grant.ClaimMenu;
import com.shadowprison.menu.Button;
import com.shadowprison.menu.Menu;
import com.shadowprison.menu.Ui;
import com.shadowprison.money.Currency;
import com.shadowprison.money.Money;
import com.shadowprison.profile.Profile;
import com.shadowprison.stat.Stat;
import com.shadowprison.stat.StatMap;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * /menu — the hub every system hangs off (SkyBlock's "SkyBlock Menu"). Phase 0 shows what exists;
 * each later phase adds one tile here, never a new top-level command players have to remember.
 */
final class MainMenu extends Menu {

    private final ShadowPrison plugin;

    MainMenu(ShadowPrison plugin) {
        super(Ui.title("Shadow Menu"), 6);
        this.plugin = plugin;
    }

    @Override
    protected void build(Player viewer) {
        Profile profile = plugin.profiles().get(viewer);
        set(13, Button.of(profileHead(viewer, profile)));

        set(29, Button.of(Ui.icon(Material.CHEST).name("Claim Rewards", Ui.ACCENT)
                .line("Anything that didn't fit in your")
                .line("inventory waits here — nothing")
                .line("is ever dropped on the floor.")
                .blank().action("Click to open!").build(), p -> ClaimMenu.open(plugin, plugin.grants(), p)));

        set(31, Button.of(Ui.icon(Material.DIAMOND_PICKAXE).name("Skills", Ui.ACCENT)
                .line("Mining, Foraging, Farming, Combat…")
                .line("A reward on every single level.")
                .blank().line(Ui.text("Coming in Phase 2", Ui.DIM)).build()));

        set(33, Button.of(Ui.icon(Material.PAINTING).name("Collections", Ui.ACCENT)
                .line("Gather to unlock recipes,")
                .line("perks and titles.")
                .blank().line(Ui.text("Coming in Phase 2", Ui.DIM)).build()));

        if (viewer.hasPermission(ConfigMenu.PERMISSION)) {
            set(49, Button.of(Ui.icon(Material.COMPARATOR).name("Configuration", Ui.BAD)
                    .line("Every dial on the server.").blank().action("Click to open!").build(),
                    p -> new ConfigMenu(plugin.dials(), plugin.prompts()).open(p)));
        }
        fill();
    }

    private ItemStack profileHead(Player viewer, Profile profile) {
        StatMap stats = plugin.stats().of(viewer);
        Ui.Icon icon = Ui.icon(Material.PLAYER_HEAD).name("Your Profile", Ui.PRIMARY);
        if (profile != null) {
            icon.stat("Purse", Money.format(profile.balance(Currency.COINS)), Ui.ACCENT)
                    .stat("Playtime", hours(profile.playtimeMs()), Ui.ACCENT);
        }
        icon.blank();
        for (Stat stat : Stat.values()) {
            long v = stats.get(stat);
            icon.line(Ui.text(stat.symbol() + " " + stat.display() + " ", stat.color())
                    .append(Ui.text(Long.toString(v) + (stat.percent() ? "%" : ""), Ui.MUTED)));
        }
        ItemStack head = icon.build();
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(viewer));
        return head;
    }

    private static String hours(long ms) {
        long minutes = ms / 60_000L;
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }
}
