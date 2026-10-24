package com.skyclone.mod.menus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every player-facing feature of the game, how to open it, and what it is for. Pure data.
 *
 * <p>The SkyBlock Menu, the "Your Bags" menu, the Calendar and the SkyBlock Guide all open features
 * through this table, so there is ONE place that knows "Pets = Features.Pets.openMenu, else /pets". A
 * feature is opened by, in order: its {@code service} (a feature interface that is really installed),
 * then each of its {@code commands} that is registered, then a fallback built into this module
 * (service names starting with {@code "menus:"}), else "&amp;cComing soon!".
 *
 * <p>The command names were checked against what the modules actually register (2026-10-02 review);
 * a name that no module registers is harmless - it is skipped and the next one is tried.
 */
public final class FeatureIndex {

    private FeatureIndex() {}

    public enum Category {
        PROFILE("Profile & Progress", "PLAYER_HEAD", 'a'),
        SKILLS("Skills & Collections", "DIAMOND_SWORD", 'b'),
        GATHERING("Mining, Farming & Fishing", "IRON_PICKAXE", '2'),
        ITEMS("Items & Crafting", "CRAFTING_TABLE", '6'),
        STORAGE("Storage & Bags", "CHEST", 'e'),
        ECONOMY("Coins & Trading", "GOLD_INGOT", '6'),
        EVENTS("Calendar & Events", "CLOCK", 'd'),
        TRAVEL("Travel & Islands", "FILLED_MAP", 'b'),
        HELP("Settings & Help", "REDSTONE_TORCH", 'c');

        public final String title;
        public final String icon;
        public final char color;

        Category(String title, String icon, char color) { this.title = title; this.icon = icon; this.color = color; }
    }

    /**
     * @param service  a feature-service key resolved by {@code Links} ("skills", "pets", "storage", ...) or a
     *                 built-in fallback of this module ("menus:calendar"), or null
     * @param commands registered command names tried in order (never player text)
     * @param args     arguments passed to the command (constant, never player text)
     */
    public record Feature(String id, Category category, String name, String icon, String texture,
                          String description, String service, List<String> commands, List<String> args) {
        public String primaryCommand() { return commands.isEmpty() ? null : commands.get(0); }
    }

    private static final Map<String, Feature> ALL = new LinkedHashMap<>();

    private static void f(String id, Category c, String name, String icon, String texture, String service,
                          String desc, String... commands) {
        ALL.put(id, new Feature(id, c, name, icon, texture, desc, service, List.of(commands), List.of()));
    }

    private static void fa(String id, Category c, String name, String icon, String service, String desc,
                           List<String> commands, List<String> args) {
        ALL.put(id, new Feature(id, c, name, icon, null, desc, service, commands, args));
    }

    static {
        Category P = Category.PROFILE, S = Category.SKILLS, G = Category.GATHERING, I = Category.ITEMS,
                B = Category.STORAGE, E = Category.ECONOMY, V = Category.EVENTS, T = Category.TRAVEL, H = Category.HELP;
        // ---- profile & progress
        f("stats", P, "Stats & Equipment", "ARMOR_STAND", null, "menus:stats",
                "Your armor, equipment, pet and every stat with where it comes from.", "stats", "pv");
        f("sblevel", P, "SkyBlock Leveling", "EXPERIENCE_BOTTLE", null, "sblevel",
                "Your SkyBlock Level, the tasks that raise it and the emblems it unlocks.", "sblevel", "leveling");
        f("quests", P, "Quest Log", "WRITABLE_BOOK", null, "menus:quests",
                "Your current objective and every quest you have finished.", "questlog", "quests");
        f("fairysouls", P, "Fairy Souls", "PINK_DYE", null, null,
                "How many hidden souls you have found on every island.", "fairysouls");
        f("bestiary", P, "Bestiary", "BOOK", "icon.bestiary", "bestiary",
                "Every creature you have slain, and the bonuses for hunting more.", "bestiary");
        f("museum", P, "Museum", "ITEM_FRAME", "warp.museum", "museum",
                "Donate rare items to the museum for SkyBlock experience.", "museum");
        f("cakes", P, "Century Cakes", "CAKE", null, null,
                "Your cake buffs and how long each still lasts.", "cakes");
        f("profiles", P, "Profile Management", "NAME_TAG", null, null,
                "Switch between your profiles or start a new one.", "profiles");
        fa("coop", P, "Co-op", "PLAYER_HEAD", null,
                "See who shares this profile with you.", List.of("coop"), List.of("list"));
        f("party", P, "Party", "CAKE", null, null,
                "Invite friends, warp them to you and chat as a group.", "party");
        f("phone", P, "Ringphone", "COMPARATOR", null, null,
                "Your contacts: call the people you have met, or answer when they ring.", "phone");
        // ---- skills & collections
        f("skills", S, "Your Skills", "DIAMOND_SWORD", null, "skills",
                "Level your skills to earn stats, coins and new areas.", "skills");
        f("collections", S, "Collections", "PAINTING", null, "collections",
                "Gather items to unlock recipes, trades and rewards.", "collection", "collections");
        f("trades", S, "Collection Trades", "EMERALD", null, null,
                "Swap the things you have collected for rarer goods.", "trades");
        f("minions", S, "Minions", "COBBLESTONE", null, null,
                "Your minions, their slots, fuels, upgrades and every tier you have crafted.", "minions");
        f("slayer", S, "Slayer", "ROTTEN_FLESH", null, "slayer",
                "Start a slayer quest and track your boss levels.", "slayer");
        f("dungeons", S, "Dungeons", "WITHER_SKELETON_SKULL", null, null,
                "Catacombs floors, classes and your dungeon progress.", "dungeons", "catacombs");
        // the boss and its hollow are "Magma Leviathan" / "Leviathan's Hollow" (docs/names/kuudra.md):
        // "Magmaw" is the Crystal Hollows boss (reference Bal), a different fight
        f("kuudra", S, "Leviathan's Hollow", "PLAYER_HEAD", "island.kuudra", null,
                "Hunt the Magma Leviathan with your crew: five tiers, keys, hunt perks and the reward chests.",
                "kuudra", "leviathan");
        f("partyfinder", S, "Party Finder", "WITHER_SKELETON_SKULL", null, null,
                "Find a group for a dungeon floor or join someone else's.", "pf");
        f("hunting", S, "Hunting", "LEAD", null, null,
                "Trap critters for shards, fuse them and level the attributes they buy.", "hunting");
        f("attributes", S, "Attributes", "PRISMARINE_SHARD", null, null,
                "Every attribute your shards have bought and what the next level gives.", "attributes");
        f("vitality", S, "Vitality", "GLISTERING_MELON_SLICE", null, null,
                "Your Vitality pool: what spends it and what fills it back up.", "vitality");
        // ---- gathering
        f("mining", G, "Mining", "IRON_PICKAXE", null, "mining",
                "Your mining stats, powders, the mines and what each one holds.", "mining");
        f("hotm", G, "Heart of the Mountain", "PRISMARINE_CRYSTALS", null, null,
                "Spend powder on mining perks deep under the mountains.", "hotm");
        f("forge", G, "The Forge", "FURNACE", null, null,
                "Turn mining spoils into gear, given enough time.", "forge");
        f("commissions", G, "Commissions", "PAPER", null, null,
                "Small mining jobs that pay powder and progress.", "commissions");
        f("farming", G, "Farming", "GOLDEN_HOE", null, null,
                "Farming fortune, crops, tools and contests in one place.", "farming");
        f("garden", G, "The Garden", "SUNFLOWER", null, "garden",
                "Your plots, crop milestones, visitors and composter.", "garden", "desk");
        f("harvestfair", G, "Harvest Fair", "WHEAT", null, null,
                "Farming contests: the crop in play, the schedule and medal rewards.", "harvestfair");
        f("foraging", G, "Foraging", "IRON_AXE", null, null,
                "Woods, axes, forests and the requests of the woodsfolk.", "foraging");
        f("hotf", G, "Heart of the Forest", "OAK_SAPLING", null, null,
                "Perks for those who fell trees for a living.", "hotf");
        f("fishing", G, "Fishing", "FISHING_ROD", null, null,
                "Sea creatures, trophy fish and fishing progress.", "fishing");
        f("seacreatures", G, "Sea Creatures", "PRISMARINE_SHARD", null, null,
                "Every sea creature, where it bites and what it drops.", "seacreatures");
        f("trophyfish", G, "Trophy Fish", "PUFFERFISH", null, null,
                "Your lava-fishing trophies, titles and filleting.", "trophyfish");
        f("hotspots", G, "Fishing Hotspots", "COMPASS", null, null,
                "Where the fishing hotspots are bubbling right now.", "hotspots");
        // ---- items & crafting
        f("craft", I, "Crafting Table", "CRAFTING_TABLE", null, null,
                "Open a crafting grid anywhere.", "craft", "crafting");
        f("recipes", I, "Recipe Book", "BOOK", null, null,
                "Every recipe you have unlocked and what it needs.", "recipes", "recipebook", "recipe");
        f("materials", I, "Materials Catalogue", "DIAMOND", null, null,
                "Every material, its enchanted forms and where to get it.", "materials");
        f("armor", I, "Armor Sets", "DIAMOND_CHESTPLATE", null, null,
                "Browse every armor set and its full-set bonus.", "armor");
        f("weapons", I, "Arsenal", "IRON_SWORD", null, null,
                "Every weapon, what it does and how to get it.", "weapons");
        f("reforges", I, "Reforging", "ANVIL", null, null,
                "Reforge, star and socket gemstones into your gear.", "reforges");
        f("enchant", I, "Enchanting", "ENCHANTING_TABLE", null, null,
                "Enchant items, combine books and run experiments.", "enchant", "et", "enchanting");
        f("anvil", I, "Anvil", "DAMAGED_ANVIL", null, null,
                "Combine enchanted books and apply potato books.", "anvil");
        f("experiments", I, "Experimentation Table", "LAPIS_LAZULI", null, null,
                "Memory puzzles that pay out enchanted books and experience.", "experiments");
        f("essence", I, "Essence & Stars", "NETHER_STAR", null, null,
                "Upgrade dungeon gear with essence.", "essence");
        f("alchemy", I, "Alchemy", "BREWING_STAND", null, null,
                "Brew potions and look up every recipe.", "alchemy", "brew");
        f("effects", I, "Active Effects", "POTION", null, "menus:effects",
                "Every potion effect currently running on you.", "effects");
        f("pets", I, "Pets", "BONE", null, "pets",
                "Summon, level and manage your pets.", "pets");
        f("powers", I, "Accessory Powers", "BLAZE_POWDER", null, null,
                "Pick an accessory power and spend your tuning points.", "powers", "tuning");
        f("accguide", I, "Accessory Guide", "BOOKSHELF", null, null,
                "Every accessory, and which ones you are still missing.", "accguide");
        f("soulflow", I, "Soulflow", "BLUE_DYE", null, null,
                "Your Soulstream: turn soul items into Soulflow and pick what drains it.", "soulflow");
        f("runes", I, "Runic Pedestal", "MAGMA_CREAM", null, null,
                "Apply runes to your gear and fuse them into stronger ones.", "runes");
        f("skins", I, "Item Skins", "PAINTING", null, null,
                "Apply or remove the skins you own.", "skins");
        f("furniture", I, "Furniture", "OAK_STAIRS", null, null,
                "Furniture you can craft and place on your island.", "furniture");
        // ---- storage & bags
        f("storage", B, "Storage", "CHEST", "icon.storage", "storage",
                "Your ender chest pages and backpacks.", "storage");
        f("wardrobe", B, "Wardrobe", "LEATHER_CHESTPLATE", null, "wardrobe",
                "Swap between whole armor sets in one click.", "wardrobe");
        f("bags", B, "Your Bags", "PLAYER_HEAD", "icon.bags", "menus:bags",
                "Accessory bag, potion bag, fishing bag, quiver and sacks.", "bags", "bagsmenu");
        f("accessories", B, "Accessory Bag", "GOLD_NUGGET", null, "accessories",
                "Hold accessories so their bonuses count without filling your inventory.", "accessories", "accessorybag");
        f("equipment", B, "Equipment", "LEATHER_HELMET", null, "equipment",
                "Your necklace, cloak, belt and gloves.", "equipment");
        f("sacks", B, "Sacks", "LEATHER", null, "sacks",
                "Items your sacks have picked up for you.", "sacks");
        f("sackofsacks", B, "Sack of Sacks", "BUNDLE", null, null,
                "A sack that holds your other sacks.", "sackofsacks");
        f("potionbag", B, "Potion Bag", "GLASS_BOTTLE", null, "potionbag",
                "Carry your potions without filling your inventory.", "potionbag");
        f("fishingbag", B, "Fishing Bag", "TROPICAL_FISH", null, null,
                "Carry your baits; the rod uses them from here.", "fishingbag");
        f("quiver", B, "Quiver", "ARROW", null, null,
                "Your arrows; bows draw from here first.", "quiver");
        f("vault", B, "Personal Vault", "IRON_BLOCK", null, null,
                "A private item safe that grows with your bank account.", "vault");
        // ---- coins & trading
        f("bank", E, "Personal Bank", "GOLD_BLOCK", "icon.bank", "bank",
                "Deposit coins safely and earn interest.", "bank");
        f("bazaar", E, "Bazaar", "GOLD_INGOT", null, "bazaar",
                "Buy and sell materials with other players at market price.", "bz", "bazaar");
        f("auction", E, "Auction House", "GOLDEN_HORSE_ARMOR", null, "auction",
                "Bid on or sell unique items.", "ah", "auctionhouse");
        f("cookie", E, "Cookie Buff & Bits", "COOKIE", null, null,
                "Your cookie buff, bits and the bits shop.", "cookie", "boostercookie", "bits", "communityshop");
        f("cshop", E, "Community Shop", "EMERALD_BLOCK", null, null,
                "Spend bits, buy cookies with coins and upgrade your account.", "cshop", "communityshop");
        f("merchants", E, "Merchant Directory", "VILLAGER_SPAWN_EGG", null, null,
                "Every NPC shop, where it stands and what it sells.", "merchants");
        f("community", E, "Community Desk", "EMERALD", null, null,
                "The Community Shop, City Projects and Fire Sales.", "community");
        // ---- calendar & events
        f("calendar", V, "Calendar and Events", "CLOCK", null, "menus:calendar",
                "The SkyBlock date and every upcoming event.", "calendar");
        f("election", V, "Election", "GOLDEN_HELMET", null, null,
                "The current mayor, their perks and the next vote.", "election", "mayor");
        f("festivals", V, "Festivals", "FIREWORK_ROCKET", null, null,
                "Every festival and event the mayors run.", "festivals");
        f("darkauction", V, "Dark Auction", "WITHER_ROSE", null, null,
                "A shady auction that opens every hour at :55.", "darkauction");
        f("spooky", V, "Spooky Festival", "JACK_O_LANTERN", null, null,
                "Candy, a candy shop and a leaderboard every autumn.", "spooky");
        f("zoo", V, "Traveling Zoo", "LEAD", null, null,
                "Rare pets for sale, twice a year.", "zoo");
        f("newyear", V, "New Year Celebration", "FIREWORK_STAR", null, null,
                "Claim your cake at the end of every year.", "newyear");
        f("winter", V, "Winter Workshop", "SNOWBALL", null, null,
                "The workshop that only opens in the last month of winter.", "winter");
        f("mythology", V, "Mythological Ritual", "GOLDEN_SHOVEL", null, null,
                "Griffins, spades and buried treasure while the ritual runs.", "mythology");
        f("chocolate", V, "Chocolate Factory", "BROWN_DYE", null, null,
                "Click chocolate, hire rabbits and chase the spring egg hunt.", "cf", "hoppity");
        // ---- travel
        f("fasttravel", T, "Fast Travel", "FILLED_MAP", null, "menus:fasttravel",
                "Warp to any island or area you have unlocked.", "fasttravel");
        f("island", T, "Your Island", "GRASS_BLOCK", null, "menus:home",
                "Go back to your private island.", "is", "island");
        f("hub", T, "Hub", "PLAYER_HEAD", "island.hub", null,
                "Go to the Hub.", "hub");
        f("rift", T, "The Rift", "PLAYER_HEAD", "island.rift", null,
                "A warped copy of the world where time itself is your health: your Rift Time, souls, Timecharms and the way in.",
                "rift");
        f("crimson", T, "Crimson Isle", "PLAYER_HEAD", "island.crimson_isle", null,
                "Your faction, its daily quests, the mini-bosses and the Dojo.", "crimson", "faction");
        f("galatea", T, "Lotus Atoll & Torrent Canyon", "LILY_PAD", null, null,
                "The Lotus Atoll and Torrent Canyon: travel, frogs and the canyon journal.", "galatea");
        f("sail", T, "Ship Navigator", "OAK_BOAT", null, null,
                "Sail from the Hub to the fishing islands.", "sail");
        f("hubmap", T, "Hub Map", "MAP", null, null,
                "Every district of the Hub, one click away.", "hubmap");
        f("portals", T, "Portal Directory", "END_PORTAL_FRAME", null, null,
                "Where every island portal is and where it leads.", "portals");
        // ---- help
        f("settings", H, "Settings", "REDSTONE_TORCH", null, "menus:settings",
                "Chat filters, alerts, tab list and profile privacy.", "settings");
        f("guide", H, "SkyBlock Guide", "KNOWLEDGE_BOOK", null, "menus:guide",
                "Every feature in the game, in one place.", "guide");
        f("commands", H, "All Commands", "OAK_SIGN", null, null,
                "Every command you can type.", "commands");
    }

    public static Feature get(String id) { return ALL.get(id); }

    public static List<Feature> all() { return List.copyOf(ALL.values()); }

    public static List<Feature> of(Category c) {
        List<Feature> out = new ArrayList<>();
        for (Feature f : ALL.values()) if (f.category() == c) out.add(f);
        return Collections.unmodifiableList(out);
    }

    /**
     * The feature a calendar event opens when clicked in the Calendar ("Spooky Festival" opens the
     * spooky festival menu). Matched on the event id the owning module registered; anything unknown
     * opens the festivals list. Never null, always an id in this table.
     */
    public static String forEvent(String eventId) {
        String id = eventId == null ? "" : eventId.toLowerCase(Locale.ROOT);
        if (id.contains("spooky")) return "spooky";
        if (id.contains("new_year")) return "newyear";
        if (id.contains("zoo")) return "zoo";
        if (id.contains("jerry") || id.contains("winter") || id.contains("workshop")) return "winter";
        if (id.contains("dark_auction")) return "darkauction";
        if (id.contains("election") || id.contains("mayor")) return "election";
        if (id.contains("farming_contest") || id.contains("harvest") || id.contains("contest")) return "harvestfair";
        if (id.contains("fishing")) return "fishing";
        if (id.contains("century") || id.contains("cake")) return "cakes";
        if (id.contains("myth")) return "mythology";
        if (id.contains("mining") || id.contains("fiesta")) return "mining";
        return "festivals";
    }
}
