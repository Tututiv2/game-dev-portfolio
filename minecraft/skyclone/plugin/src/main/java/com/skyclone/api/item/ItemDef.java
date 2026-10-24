package com.skyclone.api.item;

import com.google.gson.JsonObject;
import com.skyclone.api.stat.Stat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An item TYPE — what "ENCHANTED_DIAMOND" or "ASPECT_OF_THE_END" is. Loaded from JSON
 * ({@code data/<module>/items.json}, an array of these) or built in code. Public fields so Gson
 * can fill it; treat as immutable once registered.
 *
 * <p><b>IDs.</b> Upper snake case. When the item has an equivalent in the reference game, use
 * THAT item's internal id (see research/data/items.json and research/data/neu-repo/items) so
 * every module referring to "REVENANT_FLESH" means the same thing. {@link #name} is the
 * DISPLAY name and must be our own wording for anything that is a proper noun of the reference
 * game (generic names like "Enchanted Diamond" or "Diamond Sword" stay).
 *
 * <p><b>Text.</b> All strings use legacy '&amp;' colour codes ("&amp;7Gray &amp;cred").
 */
public class ItemDef {

    public String id;
    public String name;
    /** Bukkit Material name: "DIAMOND_SWORD", "PLAYER_HEAD", "LEATHER_CHESTPLATE". */
    public String material = "STONE";
    public Rarity rarity = Rarity.COMMON;
    public ItemCategory category = ItemCategory.MATERIAL;

    /** Weapon damage (the "Damage: +X" line). Not a player stat; combat reads it from the held item. */
    public double damage;
    public Map<Stat, Double> stats = new EnumMap<>(Stat.class);

    /** Description lines between the stats/enchants block and the ability block. */
    public List<String> lore = new ArrayList<>();
    public List<AbilityDef> abilities = new ArrayList<>();

    /** Armour pieces sharing a setId form a full set; bonus behaviour is registered under this id. */
    public String setId;
    public String setBonusName;
    public List<String> setBonusLore = new ArrayList<>();

    /** Base64 skin texture "value" for PLAYER_HEAD items. */
    public String skull;
    /** "#RRGGBB" for leather armour / potions. */
    public String color;
    /** Force the enchantment glint. Enchanted materials glow. */
    public boolean glow;
    /** 0 = decide from category (unique tools/weapons/armour are 1, materials 64). */
    public int maxStack;

    /** Requirement keys checked before use/wear: "skill:combat:15", "slayer:zombie:3", "catacombs:5". */
    public List<String> requirements = new ArrayList<>();

    /** Coins an NPC pays per item. 0 = cannot be sold to NPCs. */
    public double npcSell;
    /** Dungeon item: can be starred with essence, stats scale in dungeons. */
    public boolean dungeon;
    /** Gemstone slot types: "RUBY", "JADE", "AMBER", "SAPPHIRE", "AMETHYST", "TOPAZ", "JASPER", "OPAL", "COMBAT", "OFFENSIVE", "DEFENSIVE", "MINING", "UNIVERSAL". */
    public List<String> gemSlots = new ArrayList<>();

    /**
     * Free-form flags. Modules may add their own. Tags CORE acts on today:
     * "vanilla" (this def is THE sky item for its Material: vanilla pickups convert to it),
     * "placeable" (may be placed as a block), "consumable" (may be eaten/drunk), "throwable",
     * "usable" (bone meal / bucket / flint-like use on blocks), "glow" (enchant glint),
     * "reforgeable" / "no_reforge" (the "This item can be reforged!" line), "soulbound" (footer line).
     * Conventions other modules read: "enchantable", "recombable", "no_ah", "no_trade", "museum",
     * "bazaar".
     */
    public Set<String> tags = new HashSet<>();

    /** Module-specific data (minion tier, pet type, drill fuel tank, fishing rod tier...). */
    public JsonObject extra = new JsonObject();

    // ---- helpers ------------------------------------------------------------------------

    public boolean has(String tag) { return tags != null && tags.contains(tag); }

    public int effectiveMaxStack() {
        if (maxStack > 0) return maxStack;
        return category != null && category.isUniqueByNature() ? 1 : 64;
    }

    public boolean isUnique() { return effectiveMaxStack() == 1; }

    public static ItemDef of(String id, String name, String material, Rarity rarity, ItemCategory category) {
        ItemDef d = new ItemDef();
        d.id = id;
        d.name = name;
        d.material = material;
        d.rarity = rarity;
        d.category = category;
        return d;
    }

    public ItemDef stat(Stat s, double v) { stats.put(s, v); return this; }
    public ItemDef lore(String... lines) { lore.addAll(List.of(lines)); return this; }
    public ItemDef tag(String... t) { tags.addAll(List.of(t)); return this; }
    public ItemDef ability(AbilityDef a) { abilities.add(a); return this; }
    public ItemDef sell(double coins) { npcSell = coins; return this; }
}
