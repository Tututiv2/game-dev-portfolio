package com.shadowprison;

import com.shadowprison.menu.Ui;
import com.shadowprison.money.Currency;
import com.shadowprison.money.Money;
import com.shadowprison.profile.Profile;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * First-join and welcome. The starter kit is gated on a STORED flag ("core.started"), never on
 * "this looks like a new player" — so a reload, a rejoin or a lag spike can never grant it twice.
 */
final class JoinFlow implements Listener {

    private static final String STARTED = "core.started";

    private final ShadowPrison plugin;

    JoinFlow(ShadowPrison plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Profile profile = plugin.profiles().get(player);
        if (profile == null) {
            return;   // Profiles kicked them: never act on a missing profile
        }
        if (plugin.joinMessage.on()) {
            player.sendMessage(Ui.gradient("SHADOW PRISON", Ui.PRIMARY, Ui.ACCENT, true)
                    .append(Ui.text("  Season 2 · in development", Ui.MUTED)));
        }
        if (profile.state(STARTED) == null) {
            // Flag FIRST: if anything below throws, the player is not re-granted on the next join.
            plugin.profiles().setState(profile, STARTED, Long.toString(System.currentTimeMillis()));
            long coins = plugin.startingCoins.get();
            if (coins > 0) {
                plugin.wallets().credit(profile, Currency.COINS, coins, "starting-coins");
            }
            plugin.grants().give(player, plugin.items().create("starter_pickaxe", 1, "starter-kit"), "starter-kit");
            player.sendMessage(Ui.text("Welcome! You start with ", Ui.MUTED)
                    .append(Ui.text(Money.format(coins), Ui.ACCENT))
                    .append(Ui.text(" and a Starter Pickaxe. Open ", Ui.MUTED))
                    .append(Ui.text("/menu", Ui.ACTION))
                    .append(Ui.text(" any time.", Ui.MUTED)));
        }
    }
}
