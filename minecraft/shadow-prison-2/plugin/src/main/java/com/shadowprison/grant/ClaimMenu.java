package com.shadowprison.grant;

import com.shadowprison.menu.Button;
import com.shadowprison.menu.PagedMenu;
import com.shadowprison.menu.Ui;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * /claim — everything the game owes a player. Loaded off-thread, shown as a snapshot; each click
 * asks the store to remove that exact row and delivers ONLY if that click removed it.
 */
public final class ClaimMenu extends PagedMenu<Claims.Entry> {

    private final JavaPlugin plugin;
    private final Grants grants;
    private List<Claims.Entry> snapshot;

    private ClaimMenu(JavaPlugin plugin, Grants grants, List<Claims.Entry> snapshot) {
        super(Ui.title("Claim Rewards"));
        this.plugin = plugin;
        this.grants = grants;
        this.snapshot = snapshot;
    }

    /** Loads the player's claims off-thread, then opens on the main thread. */
    public static void open(JavaPlugin plugin, Grants grants, Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Claims.Entry> list;
            try {
                list = grants.claims().list(player.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "claims load failed for " + player.getName(), e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(Ui.text("Could not load your claims — try again shortly.", Ui.BAD)));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    new ClaimMenu(plugin, grants, list).open(player);
                }
            });
        });
    }

    @Override
    protected List<Claims.Entry> entries(Player viewer) {
        return snapshot;
    }

    @Override
    protected Button entry(Player viewer, Claims.Entry e) {
        ItemStack shown = e.item().clone();
        shown.editMeta(meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Ui.text("From: " + e.source(), Ui.DIM));
            lore.add(Ui.text("Click to claim!", Ui.ACTION));
            meta.lore(lore);
        });
        return Button.of(shown, p -> claim(p, List.of(e)));
    }

    @Override
    protected Button empty() {
        return Button.of(Ui.icon(Material.CHEST).name("Nothing to claim", Ui.MUTED)
                .line("Rewards that do not fit in your")
                .line("inventory wait for you here.").build());
    }

    @Override
    protected void controls(Player viewer) {
        if (snapshot.size() > 1) {
            set(49, Button.of(Ui.icon(Material.HOPPER).name("Claim All", Ui.GOOD)
                    .line(snapshot.size() + " rewards waiting").blank().action("Click to claim everything!").build(),
                    p -> claim(p, List.copyOf(snapshot))));
        }
    }

    private void claim(Player player, List<Claims.Entry> entries) {
        // Hide them at once so a second click cannot even see them; the store decides the truth.
        snapshot = new ArrayList<>(snapshot);
        snapshot.removeAll(entries);
        refresh(player);
        for (Claims.Entry e : entries) {
            grants.claims().take(player.getUniqueId(), e.id()).whenComplete((removed, err) -> {
                if (!plugin.isEnabled()) {
                    // Shutting down between the delete and the give: the scheduler is gone, so put
                    // it straight back. Removed-but-never-given is the one outcome that must not exist.
                    if (Boolean.TRUE.equals(removed)) {
                        grants.claims().deposit(player.getUniqueId(), e.item(), e.source());
                    }
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                        if (err != null) {
                            plugin.getLogger().log(Level.SEVERE, "claim take failed for " + player.getName(), err);
                            return;
                        }
                        if (Boolean.TRUE.equals(removed)) {
                            // give() re-deposits anything that does not fit, as a new row: never lost.
                            grants.give(player.getUniqueId(), e.item(), "reclaim:" + e.source());
                        }
                    });
            });
        }
    }
}
