package com.skyclone.api.item;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-INSTANCE data carried on one ItemStack (stored as JSON in its PersistentDataContainer).
 * Read with {@code Sky.items().data(stack)} (a copy) and change with
 * {@code Sky.items().update(stack, d -> ...)}, which writes it back AND re-renders name + lore.
 */
public class ItemData {

    /** Set for unique items. Two stacks with one uuid = a dupe; core logs and the newer one dies. */
    public String uuid;
    /** Reforge id ("spicy"), or null. */
    public String reforge;
    /** Enchant id -> level, in application order. */
    public Map<String, Integer> enchants = new LinkedHashMap<>();
    /** Hot potato books (max 10; books 11-15 are fuming). */
    public int potatoBooks;
    public boolean recombobulated;
    /** Essence stars 0-5, master stars add 6-10. */
    public int stars;
    /** Gem slot index -> gem item id, e.g. "0" -> "FINE_JASPER_GEM". */
    public Map<String, String> gems = new LinkedHashMap<>();
    /** Unlocked gem slot indexes (for slots that cost something to open). */
    public java.util.Set<String> unlockedGemSlots = new java.util.HashSet<>();
    /** Art of War / Art of Peace / enrichments / misc counters a module wants to persist. */
    public JsonObject extra = new JsonObject();
    /** Epoch millis when created; shown nowhere, useful for anti-dupe forensics. */
    public long created;

    public int enchant(String id) { return enchants.getOrDefault(id, 0); }

    public ItemData copy() {
        ItemData c = new ItemData();
        c.uuid = uuid;
        c.reforge = reforge;
        c.enchants = new LinkedHashMap<>(enchants);
        c.potatoBooks = potatoBooks;
        c.recombobulated = recombobulated;
        c.stars = stars;
        c.gems = new LinkedHashMap<>(gems);
        c.unlockedGemSlots = new java.util.HashSet<>(unlockedGemSlots);
        c.extra = extra.deepCopy();
        c.created = created;
        return c;
    }
}
