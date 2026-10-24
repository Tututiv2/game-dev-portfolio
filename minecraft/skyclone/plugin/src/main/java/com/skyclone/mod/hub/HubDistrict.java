package com.skyclone.mod.hub;

import com.skyclone.mod.hub.gen.HubAnchors;

import java.util.ArrayList;
import java.util.List;

/**
 * The hub's districts as the Hub Map shows them. The zone id is the layout zone a player walks
 * into to discover it; the sub-warp sits at anchor {@code warp.<id>}. Five districts keep the
 * reference game's short warp names ({@code /warp castle}, {@code crypt}, {@code da}, {@code wizard},
 * {@code museum}) because a Travel Scroll exists for each; the rest are {@code hub_<id>}. Pure data.
 */
public record HubDistrict(String id, String name, String icon, String color, List<String> about, List<String> services, String warp) {

    public String warpId() { return warp; }

    public String anchor() { return "warp." + id; }

    static final List<HubDistrict> ALL = List.of(
            d("portal_plaza", "MAGENTA_STAINED_GLASS", "d", "Eleven framed gates, one for every island.", "Portal Warden", null),
            d("mining_district", "IRON_PICKAXE", "6", "Smithy, reforge anvil, stone and mine merchants.", "Blacksmith, Anvil-keeper, Mine Merchant, Stone Merchant", null),
            d("coal_mine", "COAL_ORE", "7", "A tunnel into the mountain full of coal and iron. A gate at the back leads on to the Gold Mine.", "Dozing Miner, Gold Mine gate", null),
            d("summit", "SNOW_BLOCK", "f", "The weather station on top of the mountain. Follow the switchback road.", "Snow Shoveler", null),
            d("graveyard", "SKELETON_SKULL", "c", "Zombies by day, more by night. Chapel, bell tower and a launch pad to the Spider's Den.", "Gravedigger, Old Wenna, Dungeon Guide, Launch Pad", null),
            d("crypt", "COBWEB", "c", "Halls under the graveyard where the ghouls live.", "Crypt Ghouls, Golden Ghoul", "crypt"),
            d("farm", "WHEAT", "e", "Fields of every crop, animal pens, the contest stage and a launch pad to the Barn.", "Farm Merchant, Contest Host, Farmhand, Launch Pad", null),
            d("fishing_outpost", "FISHING_ROD", "3", "The hub lake, a dock and a ship that sails to the fishing islands.", "Fish Merchant, Fishing Guide, Aquarist, Ship Captain", null),
            d("wizard_tower", "AMETHYST_CLUSTER", "5", "A tall tower in the Wilderness, and the arch into the Rift.", "Archmage, Scroll Seller, Warden of the Arch", "wizard"),
            d("dark_auction", "BLACKSTONE", "5", "A black hall where rare goods change hands at night.", "Doorman, Shady Dealer", "da"),
            d("foraging_camp", "OAK_LOG", "2", "Log cabins at the edge of the Forest and a launch pad to the Park.", "Lumber Merchant, Carpenter, Woodcutter, Launch Pad", null),
            d("colosseum", "SAND", "c", "An old arena with seven rows of seats.", "", null),
            d("ruins", "MOSSY_STONE_BRICKS", "c", "A broken temple on a hill. Wolves, and worse.", "Hermit", null),
            d("castle", "STONE_BRICKS", "9", "A walled keep with four climbable towers.", "Castellan", "castle"),
            d("carnival", "FIREWORK_ROCKET", "d", "Tents and a carousel, busy when the festival is on.", "Barker, Gamesmaster", null),
            d("forest", "BIRCH_LOG", "2", "Thick oak and birch woods. Every tree can be chopped.", "", null),
            d("wilderness", "DARK_OAK_SAPLING", "c", "Dark woods south-west of town. Wolves roam here.", "Hedge Witch", null),
            d("museum", "ITEM_FRAME", "b", "The village museum: donate your finest items to the collection.", "Curator", "museum"));

    private static HubDistrict d(String id, String icon, String color, String about, String services, String warp) {
        String name = id;
        for (String[] e : HubAnchors.DISTRICTS) if (e[0].equals(id)) name = e[1];
        List<String> s = new ArrayList<>();
        if (!services.isEmpty()) for (String x : services.split(", ")) s.add(x);
        return new HubDistrict(id, name, icon, color, List.of(about), s, warp == null ? "hub_" + id : warp);
    }

    static HubDistrict byId(String id) {
        for (HubDistrict d : ALL) if (d.id.equals(id)) return d;
        return null;
    }
}
