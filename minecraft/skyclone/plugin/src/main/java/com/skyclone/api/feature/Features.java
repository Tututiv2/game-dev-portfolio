package com.skyclone.api.feature;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Small FEATURE-service interfaces — one per module that other modules need to ask questions of.
 * Each is provided by its owning module with {@code Sky.provide(X.class, impl)} and fetched with
 * {@code Sky.service(X.class)} (never null; a no-op until the owner enables).
 *
 * <p>Keep these narrow. If a module needs something not here, it adds a method to ITS OWN
 * interface in the hand-off notes, and the integrator decides — do not edit another module's
 * interface yourself.
 */
public final class Features {

    private Features() {}

    /** Provided by "collections". */
    public interface Collections {
        long count(Player p, String collectionId);
        int tier(Player p, String collectionId);
        /** Is this item the key of some collection? */
        boolean isCollectionItem(String itemId);
        void openMenu(Player p);
        /**
         * Credit {@code amount} to a collection as {@code p}'s contribution (boss kills, minion compaction...).
         * The key is resolved like {@link #count} (aliases accepted). No-op when the player is not ready.
         */
        default void add(Player p, String collectionId, long amount) {}
    }

    /** Provided by "sacks". */
    public interface Sacks {
        int count(Player p, String itemId);
        /** Take up to amount; returns how many removed. */
        int remove(Player p, String itemId, int amount);
        /** Store if the player owns a sack that accepts it; returns how many were stored. */
        int add(Player p, String itemId, int amount);
        void openMenu(Player p);
    }

    /** Provided by "pets". */
    public interface Pets {
        /** Pet type id of the active pet ("BEE", "ENDER_DRAGON"), or null. */
        String activeType(Player p);
        int activeLevel(Player p);
        String activeRarity(Player p);
        void addPetXp(Player p, double xp, String skillSource);
        void openMenu(Player p);
        /**
         * The number behind an active-pet perk, 0 when the active pet does not have it. Ability keys are
         * {@code <pet>_<ability_snake>} ("mithril_golem_the_smell_of_powder"); token "a" is the first number.
         */
        default double abilityValue(Player p, String abilityKey, String token) { return 0; }
    }

    /** Provided by "accessories". */
    public interface Accessories {
        boolean has(Player p, String itemId);
        int magicalPower(Player p);
        void openBag(Player p);
    }

    /** Provided by "mayors". */
    public interface Mayors {
        boolean perkActive(String perkId);
        String mayorName();
        /** Numeric value of a perk (e.g. "+15% coins from slayer" -> 15), 0 if inactive. */
        double perkValue(String perkId);
    }

    /** Provided by "sblevel". */
    public interface SkyLevel {
        int level(Player p);
        double xp(Player p);
        /**
         * One-time keyed award: the same key never pays twice. The progression module prints
         * {@code +N SkyBlock XP (reason)} itself unless the key's prefix is in its dial
         * {@code progression.sblevel.quiet-prefixes}; a caller that shows the XP in its own box adds its prefix there.
         */
        void award(Player p, String key, int xp, String reason);
        /** Open SkyBlock Leveling (wave 5 default: runs {@code /sblevel}). */
        default void openMenu(Player p) { com.skyclone.api.Sky.commands().run(p, "sblevel"); }
        /**
         * The player's selected SkyBlock Level emblem as legacy text (colour included), or "" for none.
         * Core shows it after the name in chat and the tab list. (Added by integration round 2,
         * progression Request 3.)
         */
        default String emblem(Player p) { return ""; }
    }

    /** Provided by "island" (private island). */
    public interface PrivateIsland {
        Location home(Player p);
        boolean isOnOwnIsland(Player p);
        /** Is this location on an island the player is a member of? */
        boolean canBuild(Player p, Location l);
    }

    /** Provided by "objectives" — the guided "Objective: Talk to the Banker" chain. */
    public interface Objectives {
        void complete(Player p, String objectiveId);
        boolean completed(Player p, String objectiveId);
        String current(Player p);
        /** The current objective as display text ("Talk to the Banker"); defaults to the id (wave 5, menus request 7). */
        default String currentText(Player p) { return current(p); }
    }

    /** Provided by "bazaar": price lookup for value displays and NPC fallbacks. */
    public interface Bazaar {
        boolean isBazaarItem(String itemId);
        double instantBuy(String itemId);
        double instantSell(String itemId);
        void open(Player p, String itemId);
    }

    /** Provided by "auction". */
    public interface Auctions {
        double lowestBin(String itemId);
        void open(Player p);
    }

    /** Provided by "storage" (ender chest pages, backpacks, wardrobe). */
    public interface Storage {
        void openStorage(Player p);
        void openWardrobe(Player p);
    }

    /** Provided by "slayer". */
    public interface Slayers {
        int level(Player p, String slayerType);
        double xp(Player p, String slayerType);
        boolean questActive(Player p);
        void openMenu(Player p);
    }

    /** Provided by "dungeons". */
    public interface Dungeons {
        int catacombsLevel(Player p);
        int classLevel(Player p, String dungeonClass);
        boolean inDungeon(Player p);
        /** The player's selected dungeon class id (HEALER, MAGE, BERSERK, ARCHER, TANK), or null if unknown
         *  (integrator wave 3, accessories request 12). */
        default String selectedClass(Player p) { return null; }
        /** True while the player is in a Master Mode run (wave 5; accessories' Master Skulls). */
        default boolean masterMode(Player p) { return false; }
    }

    /** Provided by "mining". */
    public interface Mining {
        /** Open the mining main menu (wave 5 default: runs {@code /mining}; mining may override). */
        default void openMenu(Player p) { com.skyclone.api.Sky.commands().run(p, "mining"); }
        int hotmLevel(Player p);
        long powder(Player p, String type);
        void addPowder(Player p, String type, long amount, String reason);
        /** Crystal caverns Heat, 0-100 (integrator wave 3, reforges request 12). */
        default int heat(Player p) { return 0; }
        /** Glacite tunnels Cold, 0-100 (integrator wave 3, reforges request 12). */
        default int cold(Player p) { return 0; }
    }

    /** Provided by "garden". */
    public interface Garden {
        int gardenLevel(Player p);
        long copper(Player p);
        /** Open the Garden desk (wave 5 default: runs {@code /desk}). */
        default void openMenu(Player p) { com.skyclone.api.Sky.commands().run(p, "desk"); }
    }

    /** Provided by "bestiary". */
    public interface Bestiary {
        int kills(Player p, String familyId);
        int milestone(Player p);
        /** Open the Bestiary (wave 5 default: runs {@code /bestiary}). */
        default void openMenu(Player p) { com.skyclone.api.Sky.commands().run(p, "bestiary"); }
    }

    /** Provided by "museum". */
    public interface Museum {
        boolean donated(Player p, String itemId);
        /** Museum milestone reached (by museum XP), 0 when none / unknown (wave 5; bank + auction requests). */
        default int milestone(Player p) { return 0; }
        /** Open the Museum (wave 5 default: runs {@code /museum}). */
        default void openMenu(Player p) { com.skyclone.api.Sky.commands().run(p, "museum"); }
    }

    /** Provided by "equipment" (necklace/cloak/belt/gloves slots). */
    public interface Equipment {
        List<ItemStack> worn(Player p);
        /** Open the Equipment menu (wave 5 default: runs {@code /equipment}). */
        default void openMenu(Player p) { com.skyclone.api.Sky.commands().run(p, "equipment"); }
    }

    /** Provided by "potions": active effect id -> level. */
    public interface Potions {
        Map<String, Integer> active(Player p);
        void apply(Player p, String effectId, int level, long seconds);
        /** Open Active Effects (wave 5 default: runs {@code /effects}). */
        default void openEffects(Player p) { com.skyclone.api.Sky.commands().run(p, "effects"); }
        /** Open the Potion Bag (wave 5 default: runs {@code /potionbag}). */
        default void openBag(Player p) { com.skyclone.api.Sky.commands().run(p, "potionbag"); }
    }

    /**
     * Provided by "reforges": the per-player essence pouch (types are the item id without "ESSENCE_",
     * e.g. "WITHER", "UNDEAD"). Added for dungeons part B request 1 / reforges request 3.
     * The no-op default holds nothing and refuses every take.
     */
    public interface Essence {
        default long essence(Player p, String type) { return 0; }
        /** Take {@code amount} from the pouch; all or nothing. */
        default boolean takeEssence(Player p, String type, long amount) { return false; }
        default void addEssence(Player p, String type, long amount) {}
    }

    /**
     * Provided by "dungeons": its in-memory parties, so other group content (kuudra) can use the same
     * party instead of its own crews. Kuudra request 1. A player with no party is a party of one.
     */
    public interface Party {
        /** Online members including {@code p}; just {@code p} when not in a party. */
        default List<Player> members(Player p) { return List.of(p); }
        /** The leader, or {@code p} when not in a party. */
        default Player leader(Player p) { return p; }
    }

    // ---- wave 5 (docs/handoff/core-wave5.md) ---------------------------------------------------

    /**
     * Provided by "menus": the player's Settings values (the same keys the Settings menu shows, e.g.
     * {@code notify.event_start}, {@code tab.pet}). Every method returns {@code def} until menus provides it,
     * so a reader never needs to guard on {@code Sky.has}. Reads must be cheap (menus caches per player).
     */
    public interface Settings {
        default boolean flag(Player p, String key, boolean def) { return def; }
        default String text(Player p, String key, String def) { return def; }
    }

    /**
     * Provided by "soulflow": the Soulflow fuel (Overflux/Soulflow engines, Abiphone calls, ability costs).
     * Whole units. {@code consume} is all-or-nothing: false and nothing taken when the player is short.
     */
    public interface Soulflow {
        default long soulflow(Player p) { return 0; }
        default boolean consume(Player p, long amount, String reason) { return false; }
        default void add(Player p, long amount, String reason) {}
    }

    /**
     * Provided by "hunting": shards (by the shard ids the hunting module registers) and the attribute
     * levels they buy. {@code attributeLevel} is 0 when the player has none of that attribute.
     */
    public interface Hunting {
        default int shards(Player p, String shardId) { return 0; }
        default void addShards(Player p, String shardId, int n, String reason) {}
        default double attributeLevel(Player p, String attribute) { return 0; }
        /** The Hunting menu (wave-5 integration, hunting request 2); runs {@code /hunting} until overridden. */
        default void openMenu(Player p) { com.skyclone.api.Sky.commands().run(p, "hunting"); }
    }

    /** Provided by "extras": the Abiphone contact list (contact ids are lower_snake NPC ids). */
    public interface Abiphone {
        default void addContact(Player p, String contactId) {}
        default boolean hasContact(Player p, String contactId) { return false; }
        /** How many contacts the player has (wave-5 integration, extras request 3); 0 until extras provides it. */
        default int contacts(Player p) { return 0; }
    }

    /**
     * Provided by "bank" (bank request 5, menus request 6, slayer request 10): the bank menu and the
     * booster-cookie buff. Until bank provides it, {@code cookieActive} falls back to the {@code cookie}
     * requirement key, which the bank module already resolves.
     */
    public interface Bank {
        default void openMenu(Player p) { com.skyclone.api.Sky.commands().run(p, "bank"); }
        default boolean cookieActive(Player p) {
            try { return com.skyclone.api.Sky.requirements().check(p, "cookie"); } catch (RuntimeException e) { return false; }
        }
        /** Milliseconds of cookie buff left, 0 when inactive. */
        default long cookieMillisLeft(Player p) { return 0; }
        /** Multiplier for Bits awarded to this player (fame rank); 1.0 by default. */
        default double bitsMultiplier(Player p) { return 1.0; }
    }

    /** Provided by "rift" (rift request 6): Motes, so slayer's Riftstalker fees and shops can charge them. */
    public interface Rift {
        default long motes(Player p) { return 0; }
        /** All-or-nothing; false (nothing taken) when short. */
        default boolean spendMotes(Player p, long amount, String reason) { return false; }
        default void addMotes(Player p, long amount, String reason) {}
    }

    /** Provided by the "menus" module: the nether-star menu other menus "Go Back" to. */
    public interface SkyMenu {
        void open(Player p);
    }
}
