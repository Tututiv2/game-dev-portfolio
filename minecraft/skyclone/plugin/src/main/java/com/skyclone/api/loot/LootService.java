package com.skyclone.api.loot;

import com.skyclone.api.mob.DropDef;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * THE way an item enters a player's possession. Never {@code player.getInventory().addItem} for
 * something a player EARNED — go through {@link #give}, because it:
 * <ol>
 *   <li>fires {@code SkyEvents.ItemGain} (collections count it if the source is natural; the
 *       sacks module may absorb it and mark the event handled),</li>
 *   <li>puts whatever is left in the inventory,</li>
 *   <li>drops overflow at the player's feet, owned by them for 60s, and says "Inventory full".</li>
 * </ol>
 */
public interface LootService {

    /** Returns the amount that ended up on the ground (0 normally). */
    int give(Player p, ItemStack stack, LootSource source);

    default int give(Player p, String itemId, int amount, LootSource source) {
        return give(p, com.skyclone.api.Sky.items().create(itemId, amount), source);
    }

    /** Roll a loot table for a player (magic find, labels, "RARE DROP!" messages) and give the results. */
    List<ItemStack> roll(Player p, List<DropDef> table, LootSource source, double magicFind);

    /** Drop an item in the world visible/pickable only by one player (dungeon chests, boss loot). */
    void dropFor(Player p, Location at, ItemStack stack);

    // ---- per-target Magic Find (wave 5) --------------------------------------------------------

    /**
     * Add Magic Find that depends on WHAT is being killed (bestiary tier bonuses, "+X Magic Find vs. undead"
     * weapons, slayer-boss perks). Every registered modifier is called with the killer and the mob's def
     * and the results are SUMMED onto the player's {@code MAGIC_FIND} stat when core rolls that mob's drops
     * (the result is still clamped by the dial {@code loot.mf-cap}). Return 0 for "no bonus"; a negative
     * value lowers it. Re-registering an id replaces it; a {@code null} modifier removes it.
     * Called on the main thread once per kill; keep it cheap and never throw (a throw counts as 0).
     */
    default void addMagicFindModifier(String id, java.util.function.BiFunction<Player, com.skyclone.api.mob.MobDef, Double> modifier) {}

    /**
     * The Magic Find core uses for {@code p} killing {@code def}: the {@code MAGIC_FIND} stat plus every
     * {@link #addMagicFindModifier} result. Modules that roll a mob's table themselves (slayer bosses, dungeon
     * mobs) pass this to {@link #roll} so the bonuses apply there too. {@code def} may be null (stat only).
     */
    default double magicFind(Player p, com.skyclone.api.mob.MobDef def) {
        return com.skyclone.api.Sky.stats().get(p, com.skyclone.api.stat.Stat.MAGIC_FIND);
    }
}
