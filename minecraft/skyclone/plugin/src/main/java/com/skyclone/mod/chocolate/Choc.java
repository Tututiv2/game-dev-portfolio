package com.skyclone.mod.chocolate;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;

/** Shared state of the chocolate module: the catalog, the dials and small helpers. Main thread only. */
final class Choc {

    private Choc() {}

    static final String KEY = "chocolate";
    /** Calendar event id (public contract: menus' calendar lists it, other modules may read it). */
    static final String HUNT = "hoppitys_hunt";
    static final String NPC = "hoppity";
    static final String HUNT_NAME = "Hopsworth's Hunt";
    static final String NPC_NAME = "Hopsworth";

    static ChocCatalog catalog;

    static Dials.Dial<Double> productionScale, costScale, cookieBonus, strayChance, goldenChance, residentChance,
            uniqueBase, dyeChance, factionChance, fishEggChance, offlineCapHours, goldenClickSeconds, eggFinderInterval, timeWarpHours, mixinHours;
    static Dials.Dial<Integer> strayInterval, strayLife, clickCap, eggSpots, lbMinutes, towerMinutesBonus;
    static Dials.Dial<Boolean> sideDishes, offline, announce;
    static Dials.Dial<String> huntIslands, npcAnchor, npcIsland;

    static void registerDials() {
        var d = Sky.dials();
        productionScale = d.number("chocolate.production-scale", 1.0, "Multiplies every factory's Chocolate per second (1 = reference)");
        costScale = d.number("chocolate.cost-scale", 1.0, "Multiplies every Chocolate Factory upgrade cost (1 = as generated)");
        cookieBonus = d.number("chocolate.cookie-bonus", 0.25, "Multiplier the Boon Cookie buff adds to the factory (research/05: +0.25x)");
        strayChance = d.number("chocolate.stray-chance", 0.20, "Chance a stray rabbit appears each stray interval while the factory is open (research/08: 20%)");
        strayInterval = d.integer("chocolate.stray-interval-seconds", 30, "Seconds between stray-rabbit rolls while the factory is open (research/08: 30)");
        strayLife = d.integer("chocolate.stray-life-seconds", 15, "Seconds a stray rabbit stays clickable (research/08: 15)");
        goldenChance = d.number("chocolate.golden-chance", 0.02, "Chance a stray is a Golden one (TUNE)");
        goldenClickSeconds = d.number("chocolate.golden-click-seconds", 3.0, "Seconds of production each click pays during a Golden Click (TUNE)");
        residentChance = d.number("chocolate.resident-chance", 0.10, "Chance an egg holds one of its island's resident rabbits (TUNE)");
        uniqueBase = d.number("chocolate.unique-base", 0.0, "Base chance a duplicate egg re-rolls into a rabbit you have not found (talismans add to it)");
        dyeChance = d.number("chocolate.dye-chance", 0.001, "Chance an egg also holds a Chocolate Dye (TUNE)");
        factionChance = d.number("chocolate.faction-chance", 0.05, "Chance a duplicate with a boxed form comes as that tradeable item instead of chocolate (TUNE)");
        fishEggChance = d.number("chocolate.fish-egg-chance", 0.005, "Chance a catch during the Hunt also fishes up a Chocolate Egg for the Bounty Hunter (TUNE)");
        offlineCapHours = d.number("chocolate.offline-cap-hours", 0.0, "Hours of offline production paid on login (0 = no cap)");
        offline = d.flag("chocolate.offline-production", true, "Pay the factory's production for the time a player was offline");
        clickCap = d.integer("chocolate.click-cap", 20, "Max counted cookie clicks per second per player (anti-autoclicker)");
        sideDishes = d.flag("chocolate.side-dishes", true, "Spawn the Brunch / Dejeuner / Supper eggs too (3 extra a day)");
        eggSpots = d.integer("chocolate.egg-spots", 15, "Hiding places per island the eggs rotate through");
        eggFinderInterval = d.number("chocolate.egg-finder-seconds", 750.0, "Seconds per Bounty Hunter egg from the Egg Finder attribute at level 1 (NEU shard lore: 750)");
        mixinHours = d.number("chocolate.mixin-hours", 36.0, "Hours one Hot Chocolate Mixin adds (stacks; NEU lore: 36h)");
        timeWarpHours = d.number("chocolate.time-warp-cooldown-hours", 48.0, "Cooldown of the Enchanted Clock's Time Warp (one hour of production; NEU lore: 48 hours)");
        lbMinutes = d.integer("chocolate.leaderboard-minutes", 5, "How often online players' all-time chocolate is pushed to the leaderboard");
        towerMinutesBonus = d.integer("chocolate.tower-minutes-bonus", 0, "Extra minutes on every Time Tower activation (event dial)");
        announce = d.flag("chocolate.announce-hunt", true, "Tell everyone online when the Hunt starts and ends");
        huntIslands = d.text("chocolate.hunt-islands",
                "hub,crimson_isle,gold_mine,deep_caverns,dungeon_hub,spiders_den,dwarven_mines,the_end,park,crystal_hollows,barn,mushroom_desert,backwater_bayou,galatea",
                "Islands that hide Chocolate Rabbit Eggs during the Hunt (unknown or instanced ids are skipped)");
        npcIsland = d.text("chocolate.npc-island", "hub", "Island Hopsworth stands on");
        npcAnchor = d.text("chocolate.npc-anchor", "npc.hoppity", "Anchor Hopsworth stands at (he is not spawned while it is missing)");
    }

    static boolean ready(Player p) {
        try { return p != null && Sky.profiles().isReady(p); } catch (Throwable t) { return false; }
    }

    /** Re-fetch every time (CORE_GUIDE 0.2). */
    static ChocData data(Player p) { return Sky.profiles().member(p, KEY, ChocData.class); }

    static String num(double v) { return Text.commasInt(v); }

    static String shortNum(double v) { return Text.shortNum(v); }

    static String rarityColor(String rarity) {
        try { return com.skyclone.api.item.Rarity.parse(rarity).code(); } catch (Throwable t) { return "&f"; }
    }

    static String rabbitName(ChocCatalog.Rabbit r) { return rarityColor(r.rarity) + r.name; }
}
