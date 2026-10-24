package com.skyclone.mod.hub;

import com.skyclone.mod.hub.gen.HubAnchors;

import java.util.ArrayList;
import java.util.List;

/**
 * A place in the Village as the Hub Map's Village Directory shows it: the buildings a new player
 * is sent to (Bank, Auction House, Bazaar, Builder...) and the Fairy Pond. The compass points at
 * {@link #anchor()}, which stands on the step outside the entrance. Pure data.
 *
 * <p>Why (2026-10-03 playtest): the Hub Map listed only districts, so "Talk to the Banker" or
 * "Talk to the Fairy: by the pond in the Hub" gave a new player no way to find the place.
 */
record VillagePlace(String id, String name, String anchor, String icon, String color, String about, String here, String entrance) {

    static final List<VillagePlace> ALL = build();

    private static List<VillagePlace> build() {
        // id -> icon, colour, about, who is inside, where the way in is
        String[][] rows = {
                {"bank", "GOLD_INGOT", "6", "Keep your coins safe, earn interest.", "Banker, Broker",
                        "Doors on the south side, facing the square (and a portico on the east)."},
                {"auction_house", "GOLD_BLOCK", "6", "Buy and sell items with other players.", "Auction Master, Auction Agents, Vault Keeper",
                        "Doors on the south side, facing the square (and a portico on the west)."},
                {"bazaar", "EMERALD", "a", "Instant buy and sell of raw materials.", "Bazaar Agent, market stalls",
                        "Doors on the north side, facing the square (and the alley door on the east)."},
                {"builders_house", "BRICKS", "e", "Building blocks, wool and tools.", "Builder, Wool Weaver, Tinkerer",
                        "Door on the east side, facing the square (and the yard door on the south)."},
                {"museum", "ITEM_FRAME", "b", "Donate your finest items to the collection.", "Curator",
                        "Columned steps on the west side, off the south avenue."},
                {"community_center", "EMERALD_BLOCK", "a", "The community shop.", "Community Shop; the phone shop is next door",
                        "Door on the east side, off the north avenue."},
                {"election_hall", "PAPER", "e", "Vote for the next mayor.", "Election; the decorator is next door",
                        "Door on the west side, off the north avenue."},
                {"library", "ENCHANTING_TABLE", "d", "Enchanting and books.", "Librarian, Enchanting Table",
                        "Door on the south side, off the east avenue."},
                {"tavern", "MUSHROOM_STEW", "6", "Drinks, and slayer quests.", "Bartender, Slayer Master",
                        "Doors on the west side, facing the square, straight across to the Slayer Master (and the bar door on the north)."},
                {"pet_care", "BONE", "f", "Pet care and pet supplies.", "Pet Sitter, pet supplies stall",
                        "Door on the west side, off the south avenue."},
                {"alchemist", "BREWING_STAND", "5", "Potions and brewing.", "Alchemist, Brewing Stand; the Flower House is next door",
                        "Door on the east side, off the south avenue."},
                {"weaponsmith", "IRON_SWORD", "c", "Swords and bows.", "Weaponsmith",
                        "Door on the north side, off the west avenue."},
                {"armorsmith", "IRON_CHESTPLATE", "c", "Armour.", "Armorsmith",
                        "Door on the north side, off the west avenue."},
                {"adventurer", "MAP", "2", "Odds and ends for the road.", "Adventurer",
                        "Door on the south side, off the north-west lane."},
                {"tailor", "LEATHER_CHESTPLATE", "2", "Clothes and dyes.", "Tailor",
                        "Door on the south side, off the north-west lane."},
                {"fairy_pond", "PINK_TULIP", "d", "A quiet pond in the south-west garden.", "Fairy (Fairy Soul exchange)",
                        "The fairy waits on the north-east bank, the side nearest the square."},
                {"archery_range", "BOW", "a", "Target Practice: hit the glowing target, earn medals.", "Range Master Corliss, Fletcher",
                        "Gate in the east fence, just off the west lane."},
        };
        List<VillagePlace> out = new ArrayList<>();
        for (String[] v : HubAnchors.VILLAGE) {
            String[] row = null;
            for (String[] r : rows) if (r[0].equals(v[0])) row = r;
            if (row == null) row = new String[]{v[0], "MAP", "f", "", "", ""};
            out.add(new VillagePlace(v[0], v[1], v[2], row[1], row[2], row[3], row[4], row[5]));
        }
        return List.copyOf(out);
    }

    static VillagePlace byId(String id) {
        for (VillagePlace p : ALL) if (p.id.equals(id)) return p;
        return null;
    }
}
