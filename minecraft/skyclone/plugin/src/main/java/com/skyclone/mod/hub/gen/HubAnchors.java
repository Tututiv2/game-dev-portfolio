package com.skyclone.mod.hub.gen;

import java.util.List;

/**
 * Every name the rest of the game expects the hub layout to carry. PURE (no Bukkit) so the unit
 * test and the live self-test check the same list.
 */
public final class HubAnchors {

    private HubAnchors() {}

    /** CONTRACT section 4: anchors feature modules depend on. */
    public static final List<String> CONTRACT = List.of(
            "spawn", "npc.banker", "npc.banker_2", "npc.bazaar", "npc.auction_master", "npc.auction_agent_1", "npc.auction_agent_2",
            "npc.blacksmith", "npc.reforge_anvil", "npc.slayer_master", "npc.librarian", "npc.enchanting_table",
            "npc.farm_merchant", "npc.mine_merchant", "npc.lumber_merchant", "npc.fish_merchant", "npc.weaponsmith", "npc.armorsmith",
            "npc.adventurer", "npc.builder", "npc.bartender", "npc.community_shop", "npc.alchemist", "npc.brewing_stand",
            "npc.pet_sitter", "npc.contest_host", "npc.election", "npc.fairy", "npc.museum", "npc.dark_auction",
            "npc.dungeon_guide", "npc.fishing_guide", "npc.gardener");

    /** CONTRACT section 4: areas. */
    public static final List<String> CONTRACT_AREAS = List.of(
            "graveyard", "crypt", "ruins", "wilderness", "forest", "coal_mine", "farm", "colosseum", "village", "mountain", "pond",
            "election_room", "museum");

    /** CONTRACT section 4: the hub's portals (portal.&lt;warpId&gt;). */
    public static final List<String> PORTALS = List.of(
            "private_island", "barn", "park", "gold_mine", "spiders_den", "the_end", "crimson_isle", "dungeon_hub", "garden", "backwater_bayou",
            "jerrys_workshop");

    /** Asked for in other modules' hand-offs (shops' secondary merchants, accessories, townsfolk). */
    public static final List<String> REQUESTED = List.of(
            "npc.thaumaturgist", "npc.fletcher", "npc.gravedigger", "npc.beekeeper", "npc.pet_supplies", "npc.aquarist", "npc.tailor",
            "npc.broker", "npc.shady_dealer", "npc.hedgewitch", "npc.phone_vendor", "npc.decorator", "npc.owl_keeper", "npc.pig_shop",
            "npc.scroll_seller", "npc.hermit", "npc.snow_shoveler", "npc.plumber", "npc.sly_vendor", "npc.stone_merchant",
            "npc.tinkerer", "npc.wool_weaver", "npc.carpenter",
            "npc.lore.1", "npc.lore.2", "npc.lore.3", "npc.lore.4", "npc.lore.5", "npc.lore.6",
            "npc.lore.7", "npc.lore.8", "npc.lore.9", "npc.lore.10", "npc.lore.11", "npc.lore.12",
            // mayors' seasonal vendors (they stand here only while their festival runs), auction's vault keeper
            "npc.baker", "npc.fear_mongerer", "npc.zoo", "npc.mythology", "npc.stonks", "npc.auction_vault",
            // offered to the rift module (wave 3): the Warden of the Arch's spot beside the Rift gate
            "npc.rift_warden",
            // fishing's hotspots on the lake (on the water, walk check ignores them)
            "fishing.hotspot.1", "fishing.hotspot.2", "fishing.hotspot.3");

    /**
     * Wave 5: one townhouse each (HubVillage.residents). {@code npc.pet_trainer} (skills' Pet Trainer) and
     * {@code npc.hoppity} (chocolate's rabbit keeper) were asked for; the {@code npc.resident.N} are the hub's
     * own townsfolk with an errand each ({@code data/hub/errands.json}).
     */
    public static final List<String> RESIDENTS = List.of(
            "npc.pet_trainer", "npc.hoppity",
            "npc.resident.1", "npc.resident.2", "npc.resident.3", "npc.resident.4", "npc.resident.5", "npc.resident.6");

    /** Wave 5, asked for by skills: the Runic Pedestal beside the Blacksmith (inside the forge). */
    public static final List<String> WAVE5 = List.of("npc.runic_pedestal");

    /** The hub module's own NPCs. */
    public static final List<String> OWN = List.of(
            "npc.hub_guide", "npc.portal_keeper", "npc.lazy_miner", "npc.lumber_jack", "npc.mourner", "npc.farmhand",
            "npc.castellan", "npc.wizard", "npc.carnival_barker", "npc.carnival_games", "npc.ship_captain", "npc.range_master");

    /** Target Practice at the Archery Range: the four target blocks (anchors) and the shooting ground / range (areas). */
    public static final List<String> ARCHERY_TARGETS = List.of("archery.target.1", "archery.target.2", "archery.target.3", "archery.target.4");
    public static final List<String> ARCHERY_AREAS = List.of("archery.line", "archery");

    /**
     * The hub's own exits besides the Portal Plaza: launch pads ({@code pad.<warp>}, area + anchor)
     * and walk-in gates ({@code gate.<warp>}). Order matters to nobody; the warp is the suffix.
     */
    public static final List<String> TRAVEL = List.of("pad.spiders_den", "pad.barn", "pad.park", "gate.gold_mine", "gate.rift");

    /** District fast-travel spots: zone id -> display name (anchor "warp." + id). */
    public static final String[][] DISTRICTS = {
            {"portal_plaza", "Portal Plaza"}, {"mining_district", "Mining District"}, {"coal_mine", "Coal Mine"}, {"summit", "Weather Station"},
            {"graveyard", "Graveyard"}, {"crypt", "Crypts"}, {"farm", "Farm"}, {"fishing_outpost", "Fishing Outpost"},
            {"wizard_tower", "Wizard Tower"}, {"dark_auction", "Dark Auction"}, {"foraging_camp", "Foraging Camp"},
            {"colosseum", "Colosseum"}, {"ruins", "Ruins"}, {"castle", "Old Keep"}, {"carnival", "Carnival"},
            {"forest", "Forest"}, {"wilderness", "Wilderness"}, {"museum", "Museum"},
    };

    /**
     * The Village Directory (Hub Map): place id, display name, the anchor a player's compass is
     * pointed at. {@code door.<id>} stands on the step outside the entrance; for the four big
     * buildings round the square it is the door that faces the square.
     */
    public static final String[][] VILLAGE = {
            {"bank", "Bank", "door.bank"}, {"auction_house", "Auction House", "door.auction_house"},
            {"bazaar", "Bazaar", "door.bazaar"}, {"builders_house", "Builder's House", "door.builders_house"},
            {"museum", "Museum", "door.museum"}, {"community_center", "Community Center", "door.community_center"},
            {"election_hall", "Election Hall", "door.election_hall"}, {"library", "Library", "door.library"},
            {"tavern", "Tavern", "door.tavern"}, {"pet_care", "Pet Care", "door.pet_care"},
            {"alchemist", "Alchemist", "door.alchemist"}, {"weaponsmith", "Weaponsmith", "door.weaponsmith"},
            {"armorsmith", "Armorsmith", "door.armorsmith"}, {"adventurer", "Adventurer", "door.adventurer"},
            {"tailor", "Tailor", "door.tailor"}, {"fairy_pond", "Fairy Pond", "npc.fairy"},
            {"archery_range", "Archery Range", "door.archery_range"},
    };

    /** The buildings whose {@code door.<id>} is a second door cut in the wall that faces the square. */
    public static final List<String> SQUARE_DOORS = List.of("bank", "auction_house", "bazaar", "builders_house", "tavern");

    /** At least this many fairy souls must be hidden in the hub. */
    public static final int MIN_SOULS = 35;

    /** The reference hub's fairy soul count (research 03 section 4.2); wave 3 tops the hub up to it. */
    public static final int REFERENCE_SOULS = 80;
}
