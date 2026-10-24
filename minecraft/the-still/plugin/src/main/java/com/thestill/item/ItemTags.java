package com.thestill.item;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Item identity, in exactly one place.
 *
 * <p>Design law: identity lives in the persistent data container, never in a
 * display name. Shadow Prison enforced that by convention across 566 hand-rolled
 * {@code NamespacedKey} uses in 134 files, and the convention lost — an admin
 * tool that matched PAPER by a display-name regex deleted innocent players'
 * items, and {@code /sellall} vaporised custom items because it compared
 * {@code Material} only.
 *
 * <p>Both bugs are unwritable against this class, because the only matching
 * primitive it offers is by tag.
 *
 * <p>The shape is <b>kind + id</b>: {@code kind} is the family (breaching_charge,
 * crate_key, menu_button), {@code id} is which one. Two keys rather than one
 * means "is this any breaching charge" is as cheap as "is this a tier-3
 * breaching charge".
 */
public final class ItemTags {

    private final NamespacedKey kindKey;
    private final NamespacedKey idKey;

    public ItemTags(Plugin plugin) {
        this.kindKey = new NamespacedKey(plugin, "kind");
        this.idKey = new NamespacedKey(plugin, "id");
    }

    /**
     * Stamps kind and id onto a stack, in place.
     *
     * <p><b>Package-private, and that is the enforcement behind
     * {@link ItemRegistry}'s law.</b> Identity is what makes a soul lantern a Fog
     * Lamp; if any class anywhere could apply it, the item vault would be a
     * hand-maintained second list of what exists, drifting away from reality the
     * first time somebody built an item somewhere else — which is precisely the
     * failure {@code DialRegistry} was written to make unwritable for tunables.
     * Narrowing this to the package means the compiler refuses that mistake:
     * {@link StillItems} is the only place a custom item can come from.
     *
     * <p>Widening it back to {@code public} defeats the whole arrangement, so
     * {@code ItemRegistryTest} asserts on the modifier and says so.
     */
    ItemStack stamp(ItemStack stack, String kind, String id) {
        if (stack == null) {
            return null;
        }
        stack.editMeta(meta -> {
            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(kindKey, PersistentDataType.STRING, kind);
            if (id != null) {
                container.set(idKey, PersistentDataType.STRING, id);
            }
        });
        return stack;
    }

    public String kindOf(ItemStack stack) {
        return read(stack, kindKey);
    }

    public String idOf(ItemStack stack) {
        return read(stack, idKey);
    }

    public boolean isKind(ItemStack stack, String kind) {
        return kind.equals(kindOf(stack));
    }

    public boolean is(ItemStack stack, String kind, String id) {
        return kind.equals(kindOf(stack)) && id.equals(idOf(stack));
    }

    /** True if this stack carries any of our tags at all. */
    public boolean isTagged(ItemStack stack) {
        return kindOf(stack) != null;
    }

    /** Total count of a tagged item across the player's inventory. */
    public int count(Player player, String kind, String id) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && is(stack, kind, id)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    // ---- arbitrary typed data ------------------------------------------

    /**
     * Stamps an extra typed value under our namespace.
     *
     * <p>This is how Phase 2 records the Din band on an ore at <em>break</em>
     * time. That timing is load-bearing: apply the multiplier at sell time
     * instead and players bank at CHOIR, then cash out safely in the morning
     * with none of the risk, and the whole design stops working.
     */
    public <P, C> void put(ItemStack stack, String key, PersistentDataType<P, C> type, C value) {
        if (stack == null) {
            return;
        }
        stack.editMeta(meta -> meta.getPersistentDataContainer()
                .set(subKey(stack, key), type, value));
    }

    public <P, C> C get(ItemStack stack, String key, PersistentDataType<P, C> type, C fallback) {
        if (stack == null || !stack.hasItemMeta()) {
            return fallback;
        }
        C value = stack.getItemMeta().getPersistentDataContainer().get(subKey(stack, key), type);
        return value != null ? value : fallback;
    }

    private NamespacedKey subKey(ItemStack stack, String key) {
        return new NamespacedKey(kindKey.getNamespace(), key);
    }

    private String read(ItemStack stack, NamespacedKey key) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
    }
}
