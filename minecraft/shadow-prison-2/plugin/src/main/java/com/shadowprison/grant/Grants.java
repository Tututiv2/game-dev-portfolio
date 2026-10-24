package com.shadowprison.grant;

import com.shadowprison.menu.Ui;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * THE way the game gives an item. There is no other.
 *
 * <p>Inventory first; whatever does not fit goes to /claim as the real ItemStack. <b>Nothing is
 * ever dropped on the floor</b> — SP1 used {@code dropItemNaturally} for overflow in dozens of
 * places, and its own hub sweep then deleted those drops (serials included). An offline recipient
 * goes straight to /claim; a Player object that has quit is treated as offline.
 */
public final class Grants {

    private final Claims claims;

    public Grants(Claims claims) {
        this.claims = claims;
    }

    public enum Outcome { INVENTORY, PARTLY_CLAIMED, CLAIMED }

    /** Main thread. {@code source} is recorded with any claim ("crate:basic", "admin:staff1"). */
    public Outcome give(Player player, ItemStack item, String source) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return Outcome.INVENTORY;
        }
        if (player == null) {
            throw new IllegalArgumentException("grant to a null player (source " + source + ")");
        }
        if (!player.isOnline()) {
            claims.deposit(player.getUniqueId(), item, source);
            return Outcome.CLAIMED;
        }
        int wanted = item.getAmount();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item.clone());
        if (leftover.isEmpty()) {
            return Outcome.INVENTORY;
        }
        int left = 0;
        for (ItemStack rest : leftover.values()) {
            left += rest.getAmount();
            claims.deposit(player.getUniqueId(), rest, source);
        }
        player.sendMessage(Ui.text("Your inventory is full — ", Ui.BAD)
                .append(Ui.text("/claim", Ui.ACTION))
                .append(Ui.text(" is holding the rest for you.", Ui.BAD)));
        return left >= wanted ? Outcome.CLAIMED : Outcome.PARTLY_CLAIMED;
    }

    /** By UUID: online -> inventory/claim; offline -> claim. */
    public Outcome give(UUID uuid, ItemStack item, String source) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return give(online, item, source);
        }
        claims.deposit(uuid, item, source);
        return Outcome.CLAIMED;
    }

    public Claims claims() {
        return claims;
    }
}
