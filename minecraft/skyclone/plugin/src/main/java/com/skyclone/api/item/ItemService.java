package com.skyclone.api.item;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * The item registry and everything about turning an id into a real ItemStack and back.
 *
 * <p>A "sky item" is any ItemStack carrying the {@code skyclone:id} tag. Vanilla items the player
 * picks up (wheat, cobblestone) are converted on pickup into the sky item of the same id, so
 * collections, sacks, bazaar and recipes only ever see one kind of item.
 */
public interface ItemService {

    // ---- registry -----------------------------------------------------------------------

    void register(ItemDef def);

    /** Load a JSON array of ItemDef from the plugin jar: {@code data/<module>/<file>}. Returns count. */
    int loadJson(String module, String file);

    ItemDef def(String id);

    boolean exists(String id);

    Collection<ItemDef> all();

    /** Admin override of a display name; persisted. {@code null} clears it. */
    void rename(String id, String displayName);

    /** The sky id for a vanilla material ("WHEAT" for Material.WHEAT), registered automatically for every material. */
    String vanillaId(Material material);

    // ---- enchants & reforges (data only; behaviour lives in their modules) --------------

    void registerEnchant(EnchantDef def);
    EnchantDef enchant(String id);
    Collection<EnchantDef> enchants();

    void registerReforge(ReforgeDef def);
    ReforgeDef reforge(String id);
    Collection<ReforgeDef> reforges();

    // ---- stacks -------------------------------------------------------------------------

    /** A fresh stack. Unique items get a uuid. Throws IllegalArgumentException for unknown ids. */
    ItemStack create(String id, int amount);

    default ItemStack create(String id) { return create(id, 1); }

    /** The sky id of a stack, or null for air / non-sky items. */
    String idOf(ItemStack stack);

    default boolean is(ItemStack stack, String id) { return id.equals(idOf(stack)); }

    /** A COPY of the instance data. Never null for sky items (empty data for plain ones). */
    ItemData data(ItemStack stack);

    /** Mutate instance data, write it back, and re-render name and lore. Mutates the stack in place. */
    void update(ItemStack stack, Consumer<ItemData> change);

    /** Re-render name + lore (after a rename, a mayor change, a viewer-dependent line...). */
    void refresh(ItemStack stack);

    /** Effective rarity: def rarity, +1 if recombobulated. */
    Rarity rarity(ItemStack stack);

    /** Full stat sheet of one stack (base + reforge + every contributor). */
    ItemStatSheet sheet(ItemStack stack);

    void addStatContributor(String id, ItemStatContributor contributor);

    void addLoreContributor(String id, LoreContributor contributor);

    // ---- player helpers -----------------------------------------------------------------

    /** Count of this id across the player's main inventory (not sacks). */
    int count(Player p, String id);

    /** Remove up to {@code amount}; returns how many were removed. */
    int remove(Player p, String id, int amount);

    /** The setId of the player's armour if all four pieces share one, else null. */
    String fullSet(Player p);

    /** How many worn armour pieces carry this setId (for 2/4-piece bonuses). */
    int setPieces(Player p, String setId);

    /** Whether the player meets every requirement on the item; sends the reason if not and {@code tell}. */
    boolean meetsRequirements(Player p, ItemStack stack, boolean tell);

    /**
     * True when core made this def itself for a vanilla material nobody registered (so a module may
     * replace it). (Added by integration round 2, materials Request 4; it was read reflectively.)
     */
    default boolean isAutoVanilla(String id) { return false; }

    // ---- wave 5 -------------------------------------------------------------------------------

    /**
     * Join the anti-dupe scan with a container core cannot see (backpacks, ender-chest pages, wardrobe, pet
     * menu, accessory bag, minion storage...). Each scan calls {@code source} on the main thread for every
     * scanned online player and checks the returned stacks' unique uuids against the inventory, the vanilla
     * ender chest and every other source. Return the stacks as stored (copies are fine; null entries and air
     * are skipped). A copy found INSIDE a source is logged to {@code items_dupe_log} and announced to admins
     * but never deleted by core (it cannot write your container); the source's owner can read the log or run
     * its own cleanup. A copy in the inventory / ender chest is handled by the usual rules even when the older
     * copy sits in a source. Re-registering an id replaces it; {@code null} removes it. Never throw (a throw
     * skips that source for that scan and is logged once).
     */
    default void addDupeScanSource(String id, java.util.function.Function<Player, java.util.List<ItemStack>> source) {}

    /**
     * A per-instance name PREFIX (pets' {@code &7[Lvl 23] }), consulted whenever a stack's name is rendered:
     * the name becomes {@code prefix + rarityColour + [reforge] + name + stars}. Return null or "" for none; the
     * prefix carries its own colour. Re-registering an id replaces it; {@code null} removes it. Must be pure and
     * cheap (it runs on every render, possibly off the main thread for lore previews). (Wave 5, pets request 3.)
     */
    default void addNamePrefix(String id, java.util.function.BiFunction<ItemDef, ItemData, String> prefix) {}
}
