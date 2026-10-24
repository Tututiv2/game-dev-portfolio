package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/**
 * Every storage tunable. Read with {@code .get()} at use time. Numbers trace to research or are TUNE
 * (the reference gates them behind gems or ranks; here SkyBlock level / collections / fairy souls do it).
 */
final class StorageDials {

    private StorageDials() {}

    // ---- ender chest -------------------------------------------------------------------------
    static Dials.Dial<Integer> EC_PAGES;
    static Dials.Dial<Integer> EC_LAST_PAGE;
    static Dials.Dial<String> EC_PAGE_LEVELS;
    static Dials.Dial<Boolean> EC_BLOCK;

    // ---- backpacks ----------------------------------------------------------------------------
    static Dials.Dial<Integer> BP_SLOTS;
    static Dials.Dial<String> BP_SOULS;
    static Dials.Dial<Integer> BP_FREE;
    static Dials.Dial<Integer> BP_DYE_COST;

    // ---- wardrobe -------------------------------------------------------------------------------
    static Dials.Dial<String> WD_REQUIREMENT;
    static Dials.Dial<String> WD_SLOT_LEVELS;
    static Dials.Dial<String> WD_SHOP_UPGRADE;
    static Dials.Dial<Boolean> EQ_WARDROBE;
    static Dials.Dial<String> EQ_SLOT_LEVELS;
    static Dials.Dial<String> EQ_SHOP_UPGRADE;
    static Dials.Dial<String> EC_SHOP_UPGRADE;
    static Dials.Dial<String> SACKBAG_SHOP_UPGRADE;
    static Dials.Dial<Boolean> RUNE_SACK_GROUPED;

    // ---- sacks ----------------------------------------------------------------------------------
    static Dials.Dial<Boolean> SACK_LOOT;
    static Dials.Dial<Boolean> SACK_PICKUP;
    static Dials.Dial<Boolean> SACK_ADMIN;
    static Dials.Dial<Boolean> SACK_INVENTORY;
    static Dials.Dial<Integer> SACK_SUMMARY_SECONDS;
    static Dials.Dial<Integer> SACK_FULL_SECONDS;
    static Dials.Dial<Double> LINING_BONUS;
    static Dials.Dial<Integer> LINING_MAX;
    static Dials.Dial<String> SACKBAG_LEVELS;
    static Dials.Dial<String> SACKBAG_COLLECTION;
    static Dials.Dial<Integer> SACKBAG_UNLOCK;
    static Dials.Dial<Integer> TAKE_MAX_STACKS;

    // ---- bags -----------------------------------------------------------------------------------
    static Dials.Dial<Integer> QUIVER_BASE;
    static Dials.Dial<String> QUIVER_COLLECTION;
    static Dials.Dial<Boolean> QUIVER_ABSORB;
    static Dials.Dial<String> VAULT_TIERS;

    // ---- bits shop --------------------------------------------------------------------------------
    static Dials.Dial<Boolean> SHOP;
    static Dials.Dial<Integer> PRICE_JUMBO_UPGRADE;
    static Dials.Dial<Integer> PRICE_LINING;
    static Dials.Dial<Integer> PRICE_SPECIAL_SACK;

    // ---- safety -----------------------------------------------------------------------------------
    static Dials.Dial<Boolean> DUPE_GUARD;
    static Dials.Dial<Boolean> DUPE_SCAN_SOURCE;
    static Dials.Dial<Boolean> SACK_SIZE_ATTRIBUTE;

    static void init() {
        Dials d = Sky.dials();
        EC_PAGES = d.integer("storage.ender-pages", 9, "Ender chest pages in /storage (research: 9)");
        EC_LAST_PAGE = d.integer("storage.ender-last-page-size", 36, "Slots on the LAST ender chest page (research: upgrade VIII adds 36)");
        // TUNE: the reference sells pages III-IX for gems; research 13.8 says gate them by SkyBlock level instead.
        EC_PAGE_LEVELS = d.text("storage.ender-page-levels", "0,0,10,25,50,75,100,150,200",
                "SkyBlock level needed for each ender chest page, comma separated (pages 1-2 free like the reference)");
        EC_BLOCK = d.flag("storage.ender-chest-block-opens-storage", true, "Right-clicking an Ender Chest block opens /storage");

        BP_SLOTS = d.integer("storage.backpack-slots", 18, "Backpack slots in /storage (research: 18)");
        BP_SOULS = d.text("storage.backpack-souls", "0,5,10,20,30,40,50,65,80,95,110,125,140,155,170,185,200,220",
                "Fairy souls needed per backpack slot (research 08 6.5: slot 1 free, then 5..220)");
        BP_FREE = d.integer("storage.backpack-free-slots", 1, "Backpack slots that are always open, whatever the souls say");
        BP_DYE_COST = d.integer("storage.backpack-dye-cost", 1, "Dyes used to recolour a backpack");

        WD_REQUIREMENT = d.text("storage.wardrobe-requirement", "sblevel:5", "Requirement key to use the Wardrobe (research 02: SkyBlock Level 5)");
        // TUNE: reference = 2 free, VIP 5, VIP+ 7, MVP 9, MVP+ 18 -> mapped onto SkyBlock levels (no paid ranks).
        WD_SLOT_LEVELS = d.text("storage.wardrobe-slot-levels", "0,0,10,15,20,30,40,50,60,75,90,105,120,135,150,175,200,250",
                "SkyBlock level per wardrobe slot (18 slots, 2 pages)");
        // Wave 5: the reference sells these as Community Shop account upgrades (research 05 6.4). The bank module owns
        // the shop; a tier owned there opens that many more slots on top of the SkyBlock-level ladder. Blank = ignore.
        WD_SHOP_UPGRADE = d.text("storage.wardrobe-shop-upgrade", "armor_wardrobe",
                "Community Shop upgrade id whose tier adds Armor Wardrobe slots (research 05: Armor Wardrobe Slots I-IX, +1 each)");
        EQ_WARDROBE = d.flag("storage.equipment-wardrobe", true,
                "Equipment Wardrobe: 18 sets of necklace/cloak/belt/gloves next to the armour sets (research 05: Equipment Wardrobe Slots)");
        // TUNE: the reference sells every equipment wardrobe slot (250 gems each); mapped onto SkyBlock levels like armour.
        EQ_SLOT_LEVELS = d.text("storage.equipment-wardrobe-slot-levels", "0,0,10,15,20,30,40,50,60,75,90,105,120,135,150,175,200,250",
                "SkyBlock level per Equipment Wardrobe slot (18 slots, 2 pages)");
        EQ_SHOP_UPGRADE = d.text("storage.equipment-wardrobe-shop-upgrade", "equipment_wardrobe",
                "Community Shop upgrade id whose tier adds Equipment Wardrobe slots (research 05: I-IX, +1 each)");
        EC_SHOP_UPGRADE = d.text("storage.ender-pages-shop-upgrade", "ender_chest_pages",
                "Community Shop upgrade id whose tier adds ender chest pages (research 05: Ender Chest Pages I-VIII)");
        SACKBAG_SHOP_UPGRADE = d.text("storage.sackbag-shop-upgrade", "sack_of_sacks",
                "Community Shop upgrade id whose tier adds Sack of Sacks account slots (research 05: Sack of Sacks I-VIII)");

        SACK_LOOT = d.flag("storage.sacks-absorb-loot", true, "Earned items (drops, mining, farming...) go into owned sacks first");
        SACK_PICKUP = d.flag("storage.sacks-absorb-pickups", true, "Items picked up off the ground go into owned sacks first");
        SACK_ADMIN = d.flag("storage.sacks-absorb-admin", false, "Admin /sb give also fills sacks");
        SACK_INVENTORY = d.flag("storage.sacks-in-inventory-count", true, "Sacks carried in the inventory count, not only those in the Sack of Sacks");
        SACK_SUMMARY_SECONDS = d.integer("storage.sacks-summary-seconds", 30, "Seconds between '[Sacks] +N items' summaries (0 = off)");
        SACK_FULL_SECONDS = d.integer("storage.sacks-full-message-seconds", 30, "Seconds between 'your sack is full' warnings");
        LINING_BONUS = d.number("storage.sack-lining-bonus", 2.0, "Extra capacity per Inner Sack Lining, as a fraction of the original (lore: +200%)");
        LINING_MAX = d.integer("storage.sack-lining-max", 3, "Linings one sack can take (lore: up to three times)");
        // TUNE: Sack of Sacks account upgrade I-II free, III-VIII gems -> SkyBlock level.
        SACKBAG_LEVELS = d.text("storage.sackbag-levels", "0,0,20,40,60,80,100,120",
                "SkyBlock level for each Sack of Sacks upgrade slot (research 05: tiers I-VIII, I-II free)");
        SACKBAG_COLLECTION = d.text("storage.sackbag-collection", "RAW_FISH:2", "Collection that grows the Sack of Sacks (research 02: Tropical Fish IV..IX)");
        SACKBAG_UNLOCK = d.integer("storage.sackbag-unlock-slots", 3, "Slots the collection unlock (tier IV) gives. TUNE: research names no number");
        RUNE_SACK_GROUPED = d.flag("storage.rune-sack-grouped", true,
                "Show the Rune Sack as one icon per rune with its three tiers (lore: 'capacity 64 of each rune tier')");
        TAKE_MAX_STACKS = d.integer("storage.sacks-take-max-stacks", 36, "Most stacks one right-click takes out of a sack");

        QUIVER_BASE = d.integer("storage.quiver-base-slots", 27, "Quiver slots at String Collection III (research: 27 -> 45)");
        QUIVER_COLLECTION = d.text("storage.quiver-collection", "STRING", "Collection that unlocks and grows the quiver");
        QUIVER_ABSORB = d.flag("storage.quiver-absorb", true, "Earned / picked-up arrows go into the quiver first");
        // TUNE: no research number for the vault; one row per bank account tier.
        VAULT_TIERS = d.text("storage.vault-tiers", "0:9,100000000:18,250000000:27,500000000:36,1000000000:45",
                "Personal Vault slots by bank capacity, 'capacity:slots' pairs");

        SHOP = d.flag("storage.bits-shop", true, "Sell the bits-only storage items (Jumbo upgrade, linings, special sacks) from /storage");
        PRICE_JUMBO_UPGRADE = d.integer("storage.price.jumbo-upgrade", 4000, "Bits for a Jumbo Backpack Upgrade (research 08 6.8: 4,000)");
        PRICE_LINING = d.integer("storage.price.lining", 8000, "Bits for an Inner Sack Lining (research 08 6.8: 8,000)");
        PRICE_SPECIAL_SACK = d.integer("storage.price.special-sack", 14000, "Bits for a Dungeon/Rune/Flower/Dwarven/Hollows sack (research 08 6.8: 14,000)");

        DUPE_GUARD = d.flag("storage.dupe-guard", true, "Refuse (and log) a unique item entering storage while a copy exists elsewhere");
        DUPE_SCAN_SOURCE = d.flag("storage.dupe-scan-source", true,
                "Let core's anti-dupe scan look inside every storage container (ender pages, backpacks, wardrobes, bags); copies there are logged");
        SACK_SIZE_ATTRIBUTE = d.flag("storage.sack-size-attribute", true,
                "The Hunting attribute Sack Size raises every sack's capacity (NEU: +1% per level, read as value:sack_size)");
    }
}
