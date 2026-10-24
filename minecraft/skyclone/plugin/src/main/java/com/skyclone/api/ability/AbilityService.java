package com.skyclone.api.ability;

import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Item abilities and full-set bonuses.
 *
 * <p>Core detects the trigger (right click, sneak, ...) on the HELD item, checks requirements,
 * cooldown and mana, calls the handler, and on {@code true} charges the mana, starts the cooldown
 * and shows "&amp;b-50 Mana (&amp;6Instant Transmission&amp;b)" on the action bar. Return {@code false}
 * when the ability could not happen (no target, blocked) and nothing is charged.
 *
 * <p>PASSIVE / ON_HIT / ON_KILL abilities are not triggered by core — the owning module listens to
 * the relevant event and checks {@code Sky.items().idOf(held)} itself; the AbilityDef just renders lore.
 */
public interface AbilityService {

    @FunctionalInterface
    interface Handler {
        boolean use(Use use);
    }

    /** Everything a handler needs. */
    record Use(Player player, ItemStack item, ItemDef itemDef, AbilityDef ability) {}

    void register(String abilityId, Handler handler);

    /** Changes an ability's mana cost before it is checked and charged (Ultimate Wise, set bonuses). */
    @FunctionalInterface
    interface CostModifier {
        /** Return the new cost (core clamps it to &gt;= 0). {@code cost} is already in mana, not a fraction. */
        double modify(Use use, double cost);
    }

    /** Register a cost modifier; they run in registration order. Re-registering an id replaces it. (Gate, 2026-10-02.) */
    void addCostModifier(String id, CostModifier modifier);

    /** Remaining cooldown in seconds (0 = ready). */
    double cooldownLeft(Player p, String abilityId);

    void setCooldown(Player p, String abilityId, double seconds);

    /**
     * Set bonus hook: called every 20 ticks for each player wearing the full set (or for
     * {@code minPieces} pieces). Use for regen-style bonuses; stat bonuses should use a StatProvider
     * checking {@code Sky.items().fullSet(p)}.
     */
    void registerSetTick(String setId, int minPieces, java.util.function.Consumer<Player> tick);

    /**
     * Tell core that THIS tick's click was consumed by a module (it opened a block menu, talked to an NPC block,
     * placed a minion...), so the held item's RIGHT_CLICK / LEFT_CLICK ability must not also fire for it: no
     * mana, no cooldown, no message. Call it from your interact handler right after cancelling the event; it
     * only covers the current server tick. (Wave 5, enchanting request 15.)
     */
    default void consumeClick(Player p) {}
}
