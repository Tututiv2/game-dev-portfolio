package com.skyclone.mod.crafting;

import com.skyclone.api.item.ItemCategory;

import java.util.EnumSet;
import java.util.Set;

/**
 * PURE rules for what a placed piece of furniture DOES (wave 5), read from its {@code function} in furniture.json:
 * {@code seat}, {@code minion_seat}, {@code storage:N}, {@code showcase:armor}, {@code showcase:weapons:N},
 * {@code showcase:best}, {@code station:<kind>}, {@code bed}, {@code water}, {@code clock}, {@code cosmetic}.
 * Unit tested.
 */
final class FurnitureMath {

    private FurnitureMath() {}

    /** Slots per storage page (5 rows; the 6th row is navigation). */
    static final int PAGE = 45;
    /** Hard cap, whatever the data says. */
    static final int MAX_PAGES = 6;

    /** The reference: "show off your best weapon (sword, pickaxe, or axe)". */
    static final Set<ItemCategory> WEAPONS = EnumSet.of(ItemCategory.SWORD, ItemCategory.LONGSWORD,
            ItemCategory.DUNGEON_WEAPON, ItemCategory.PICKAXE, ItemCategory.DRILL, ItemCategory.AXE);

    static String kind(String function) {
        if (function == null || function.isBlank()) return "cosmetic";
        int c = function.indexOf(':');
        return c < 0 ? function : function.substring(0, c);
    }

    /** "storage:3" -&gt; 3 (clamped 1..6); 0 for anything that is not storage. */
    static int pages(String function) {
        if (!"storage".equals(kind(function))) return 0;
        return Math.max(1, Math.min(MAX_PAGES, intAfter(function, "storage:", 1)));
    }

    /** Showcase slots: armour 5 (4 pieces + a weapon), a rack N (default 4), the best rack 1; 0 otherwise. */
    static int showSlots(String function) {
        if (function == null || !function.startsWith("showcase:")) return 0;
        String rest = function.substring("showcase:".length());
        if (rest.equals("armor")) return 5;
        if (rest.equals("best")) return 1;
        if (rest.startsWith("weapons")) return Math.max(1, Math.min(9, intAfter(rest, "weapons:", 4)));
        return 0;
    }

    /** Whether showcase {@code slot} of this piece accepts an item of {@code cat}. */
    static boolean accepts(String function, int slot, ItemCategory cat) {
        if (cat == null) return false;
        int n = showSlots(function);
        if (slot < 0 || slot >= n) return false;
        if ("showcase:armor".equals(function)) {
            return switch (slot) {
                case 0 -> cat == ItemCategory.HELMET;
                case 1 -> cat == ItemCategory.CHESTPLATE;
                case 2 -> cat == ItemCategory.LEGGINGS;
                case 3 -> cat == ItemCategory.BOOTS;
                default -> WEAPONS.contains(cat);
            };
        }
        return WEAPONS.contains(cat);
    }

    /** The storage key of a page slot ("p1:7") and of a showcase slot ("s3"). */
    static String pageKey(int page, int slot) { return "p" + page + ":" + slot; }

    static String showKey(int slot) { return "s" + slot; }

    /** The station a {@code station:<kind>} piece is: its working block's material name, or null. */
    static String stationBlock(String function) {
        if (function == null || !function.startsWith("station:")) return null;
        return switch (function.substring("station:".length())) {
            case "crafting" -> "CRAFTING_TABLE";
            case "furnace" -> "FURNACE";
            case "brewing" -> "BREWING_STAND";
            case "enchanting" -> "ENCHANTING_TABLE";
            case "anvil" -> "ANVIL";
            default -> null;
        };
    }

    /** One lore line saying what the piece does (null for cosmetic). */
    static String describe(String function) {
        String k = kind(function);
        return switch (k) {
            case "seat" -> "&7Right-click to sit on it.";
            case "minion_seat" -> "&7Seats a minion; players fit too.";
            case "storage" -> "&7Holds &a" + pages(function) + " page" + (pages(function) == 1 ? "" : "s") + " &7of items.";
            case "showcase" -> "showcase:armor".equals(function) ? "&7Shows off a full armour set and a weapon."
                    : "showcase:best".equals(function) ? "&7Shows off your best weapon with its stats."
                    : "&7Shows off &a" + showSlots(function) + " &7weapons.";
            case "station" -> switch (function) {
                case "station:crafting" -> "&7Opens the Crafting menu!";
                case "station:furnace" -> "&7A working furnace!";
                case "station:brewing" -> "&7Opens the Brewing Stand menu!";
                case "station:enchanting" -> "&7Opens the Enchantment Table!";
                case "station:anvil" -> "&7Opens the Anvil menu!";
                default -> null;
            };
            case "bed" -> "&7Use to set your spawn location!";
            case "water" -> "&7An endless source of water for buckets and bottles.";
            case "clock" -> "&7Tells the time on your island.";
            default -> null;
        };
    }

    /** The grey tag lines the reference prints under "Furniture" (Seat / Storage / Item Showcase). */
    static java.util.List<String> tags(String function) {
        String k = kind(function);
        return switch (k) {
            case "seat", "minion_seat" -> java.util.List.of("Seat");
            case "storage" -> java.util.List.of("Storage");
            case "showcase" -> java.util.List.of("Item Showcase", "Storage");
            default -> java.util.List.of();
        };
    }

    private static int intAfter(String s, String prefix, int def) {
        if (!s.startsWith(prefix)) return def;
        try { return Integer.parseInt(s.substring(prefix.length()).trim()); } catch (NumberFormatException e) { return def; }
    }

    /** (dx, dz) rotated by quarter turns clockwise as seen from above (north = -z), for display offsets. */
    static double[] rotate(double dx, double dz, int quarters) {
        return switch (((quarters % 4) + 4) % 4) {
            case 1 -> new double[]{-dz, dx};
            case 2 -> new double[]{-dx, -dz};
            case 3 -> new double[]{dz, -dx};
            default -> new double[]{dx, dz};
        };
    }

    /** Quarter turns for a placer facing NORTH/EAST/SOUTH/WEST (matches Furniture.rotationFor). */
    static int quarters(String facing) {
        if (facing == null) return 0;
        return switch (facing) {
            case "EAST" -> 1;
            case "SOUTH" -> 2;
            case "WEST" -> 3;
            default -> 0;
        };
    }

    /** Yaw of the piece's FRONT (it faces the player who placed it): NORTH placer -&gt; front faces south (yaw 0). */
    static float frontYaw(String facing) {
        return switch (quarters(facing)) {
            case 1 -> 90f;     // placer faced east, front faces west
            case 2 -> 180f;    // placer faced south, front faces north
            case 3 -> -90f;    // placer faced west, front faces east
            default -> 0f;
        };
    }
}
