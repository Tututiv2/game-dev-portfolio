package com.thestill.economy;

import com.thestill.config.Dial;
import com.thestill.config.DialRegistry;
import com.thestill.player.Profile;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.function.Predicate;

/** Turning inventory into money. */
public final class SellService {

    private final PriceTable prices;
    private final Economy economy;
    private final Dial<Boolean> allowCreative;

    public SellService(DialRegistry dials, PriceTable prices, Economy economy) {
        this.prices = prices;
        this.economy = economy;
        this.allowCreative = dials.boolDial("economy.sell-in-creative", false)
                .category("Economy").label("Selling in creative")
                .describe("Let creative-mode players sell. Off by default: creative can conjure "
                        + "any stack, so allowing it is an unlimited money button.")
                .register();
    }

    /**
     * What one sell did.
     *
     * @param items   how many individual items were sold
     * @param total   money earned, in minor units
     * @param skipped stacks that were left alone because nothing prices them
     */
    public record Result(int items, long total, int skipped) {

        public boolean soldAnything() {
            return items > 0;
        }

        public static final Result NOTHING = new Result(0, 0L, 0);
    }

    public Result sellHeld(Player player, Profile profile) {
        if (blockedByCreative(player)) {
            return Result.NOTHING;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            return Result.NOTHING;
        }
        if (!prices.isSellable(held)) {
            return new Result(0, 0L, 1);
        }
        long value = prices.stackValue(held);
        int amount = held.getAmount();
        player.getInventory().setItemInMainHand(null);
        economy.deposit(profile, value, "sell held");
        return new Result(amount, value, 0);
    }

    public Result sellAll(Player player, Profile profile) {
        return sell(player, profile, stack -> true);
    }

    /** Sells only the given materials — {@code /sellall <material...>}. */
    public Result sellOnly(Player player, Profile profile, Set<Material> materials) {
        return sell(player, profile, stack -> materials.contains(stack.getType()));
    }

    /** Sells everything except the given materials — the "keep my blocks" filter. */
    public Result sellExcept(Player player, Profile profile, Set<Material> keep) {
        return sell(player, profile, stack -> !keep.contains(stack.getType()));
    }

    private Result sell(Player player, Profile profile, Predicate<ItemStack> filter) {
        if (blockedByCreative(player)) {
            return Result.NOTHING;
        }
        Inventory inventory = player.getInventory();
        long total = 0L;
        int items = 0;
        int skipped = 0;

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            if (!filter.test(stack)) {
                continue;
            }
            long value = prices.stackValue(stack);
            if (value <= 0) {
                // Not priced, or priced at nothing. Either way it stays where it
                // is — PriceTable already refused to guess at a value for it.
                skipped++;
                continue;
            }
            int amount = stack.getAmount();
            // Remove BEFORE counting the money. If anything goes wrong between
            // the two, the player keeps the item rather than being paid for
            // something still in their inventory.
            inventory.setItem(slot, null);
            total += value;
            items += amount;
        }

        if (total > 0) {
            economy.deposit(profile, total, "sellall");
        }
        return new Result(items, total, skipped);
    }

    /** Total value of everything sellable, without selling it. */
    public long appraise(Player player) {
        long total = 0L;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null) {
                total += prices.stackValue(stack);
            }
        }
        return total;
    }

    private boolean blockedByCreative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE && !allowCreative.asBoolean();
    }

    public boolean isBlockedByCreative(Player player) {
        return blockedByCreative(player);
    }
}
