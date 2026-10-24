package com.skyclone.mod.storage;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-player (member) storage state, split over several profile keys so that a change to the sacks
 * does not re-serialise every ender-chest page. Items are stored as base64 of
 * {@code ItemStack#serializeAsBytes()} keyed by slot number, so every enchant, reforge and uuid survives.
 *
 * <p>Field names are stable: other modules may read these with their own mirror classes.
 * <ul>
 *   <li>{@code storage_ender}     {@link Ender}: page index "0".."8" -> slot -> item</li>
 *   <li>{@code storage_backpacks} {@link Backpacks}: backpack slot "0".."17" -> the backpack item, and its contents</li>
 *   <li>{@code storage_wardrobe}  {@link Wardrobe}: wardrobe slot "0".."17" -> piece "0" helmet .. "3" boots;
 *       {@code eqSets}: the Equipment Wardrobe, piece "0" necklace .. "3" gloves</li>
 *   <li>{@code storage_sacks}     {@link Sacks}: item id -> amount stored; the Sack of Sacks; the pickup toggle</li>
 *   <li>{@code storage_bags}      {@link Bags}: quiver and personal vault</li>
 * </ul>
 */
public final class StorageData {

    private StorageData() {}

    public static final String ENDER = "storage_ender";
    public static final String BACKPACKS = "storage_backpacks";
    public static final String WARDROBE = "storage_wardrobe";
    public static final String SACKS = "storage_sacks";
    public static final String BAGS = "storage_bags";

    public static class Ender {
        public Map<String, Map<String, String>> pages = new HashMap<>();
        public Ender() {}
    }

    public static class Backpacks {
        /** Backpack slot -> the backpack item itself. */
        public Map<String, String> items = new HashMap<>();
        /** Backpack slot -> its contents (slot -> item). Contents belong to the SLOT; a backpack leaves only when empty. */
        public Map<String, Map<String, String>> contents = new HashMap<>();
        public Backpacks() {}
    }

    public static class Wardrobe {
        public Map<String, Map<String, String>> sets = new HashMap<>();
        /** Wardrobe slot whose armour the player is wearing right now, or -1. */
        public int equipped = -1;
        /**
         * Wave 5: the Equipment Wardrobe (research 05 Community Shop "Equipment Wardrobe Slots"). Slot "0".."17" ->
         * piece "0" necklace, "1" cloak, "2" belt, "3" gloves.
         */
        public Map<String, Map<String, String>> eqSets = new HashMap<>();
        /** Equipment Wardrobe slot whose pieces are worn right now, or -1. */
        public int eqEquipped = -1;
        /** Ours: equipping armour slot N also equips Equipment Wardrobe slot N when it holds a set. Off by default. */
        public boolean linked = false;
        public Wardrobe() {}
    }

    public static class Sacks {
        public Map<String, Long> stored = new HashMap<>();
        /** The Sack of Sacks: slot -> sack item. */
        public Map<String, String> bag = new HashMap<>();
        public boolean autoPickup = true;
        public boolean summary = true;
        public Sacks() {}
    }

    public static class Bags {
        public Map<String, String> quiver = new HashMap<>();
        public Map<String, String> vault = new HashMap<>();
        public Bags() {}
    }
}
