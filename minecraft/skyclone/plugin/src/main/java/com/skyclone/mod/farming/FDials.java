package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Every farming tunable. Read {@code .get()} at use time; everything is live-editable with /sb dial. */
final class FDials {

    final Dials.Dial<String> islands, barnRequirement, desertRequirement, goatedPerk, hubZones;
    final Dials.Dial<Double> regrowMultiplier, xpMultiplier, dropMultiplier, poisonChance, dicerMultiplier,
            toolXpPerDrop, contestGhostMedian, contestGhostSigma, dropCropie, dropSquash, dropFermento, dropHelianthus,
            privateIslandXp, overbloomPercent, crestMultiplier, zorroChance, orbRadius;
    final Dials.Dial<Integer> counterPerDigit, analysisPerDigit, levelFortune, fungiBonus, swiftSlicerSpeed, anitaPerTier,
            regrowStages, contestPeriod, contestOffset, contestDuration, contestMinLevel, contestMinScore, contestGhosts,
            contestHistory, trapperCooldown, trapperMinutes, trapperGlowRange, maxAlmanac, pbUnlockGold, pbUnlockTickets,
            pbMaxFortune, treasureCost, treasureReward, treasureXp, treasureCooldown, treasureMinutes, placedMemory,
            shepherdCount, shepherdSeconds, shepherdReward, shepherdXp, shepherdCooldown, shearXp, shearsCost, woolRegrow,
            plushiePelts, beltPelts, crestUpgradePelts, orbSeconds, orbCrops, capMk1, capMk2, capMk3, maxOverclocks;
    final Dials.Dial<Boolean> blockVanillaGrowth, contestEnabled, contestFlowers, contestAutoClaim, contestBits, contestSidebar,
            capNeedsUniqueGolds, almanacAnywhere, contestRequireHost, contestGarden, contestTurboBooks, trackPlaced,
            enforceToolRequirements, toolGardenRequirement, gardenPlayerBonuses, trapperCallNeedsContact, respectSettings;

    FDials() {
        Dials d = Sky.dials();
        islands = d.text("farming.islands", "hub,barn,mushroom_desert",
                "Islands where this module owns crop breaking (comma list; the garden runs its own).");
        hubZones = d.text("farming.hub-zones", "farm",
                "On the hub, only crops inside these zones (or areas) are harvestable and regrow - the farm district; blank = the whole hub.");
        barnRequirement = d.text("farming.barn-requirement", "skill:farming:1", "Requirement to warp to The Barn ('' = free).");
        desertRequirement = d.text("farming.desert-requirement", "skill:farming:5", "Requirement to warp to the Mushroom Desert ('' = free).");
        goatedPerk = d.text("farming.contest.goated-perk", "finnegan.goated", "Mayor perk id that widens every contest bracket.");

        regrowMultiplier = d.number("farming.regrow-multiplier", 1.0, "Multiplier on every crop's regrow time (TUNE).");
        regrowStages = d.integer("farming.regrow-stages", 3, "Visible growth stages an ageable crop passes through while regrowing.");
        xpMultiplier = d.number("farming.xp-multiplier", 1.0, "Multiplier on Farming XP from crops and animals.");
        dropMultiplier = d.number("farming.drop-multiplier", 1.0, "Multiplier on crop drops after fortune (events/testing).");
        poisonChance = d.number("farming.poisonous-potato-chance", 0.02, "Chance per potato block for a Poisonous Potato (TUNE).");
        dicerMultiplier = d.number("farming.dicer-bonus", 10.0, "Dicer proc: extra drops = base drops x this x tool Mk (TUNE).");
        toolXpPerDrop = d.number("farming.tool-xp-per-drop", 1.0, "Tool level XP per item collected with it.");
        privateIslandXp = d.number("farming.private-island-xp", 1.0, "Farming XP multiplier for grown crops broken on private islands (0 = none).");

        counterPerDigit = d.integer("farming.counter-fortune-per-digit", 16, "Logarithmic Counter: Crop Fortune per digit (research 08 1.4).");
        analysisPerDigit = d.integer("farming.analysis-fortune-per-digit", 8, "Collection Analysis: Crop Fortune per digit (research 08 1.4).");
        levelFortune = d.integer("farming.tool-level-fortune", 2, "Crop Fortune per tool level above 1 (TUNE).");
        fungiBonus = d.integer("farming.fungi-cutter-bonus", 30, "Mushroom Fortune for the Fungi Cutter's chosen colour (TUNE).");
        swiftSlicerSpeed = d.integer("farming.swift-slicer-speed", 50, "Speed while holding a Cactus Knife on a farming island (TUNE).");
        anitaPerTier = d.integer("farming.extra-fortune-per-tier", 4, "Farming Fortune per Extra Fortune tier from Harvesta (research 08 1.3).");
        capMk1 = d.integer("farming.tool-cap-mk1", 15, "Highest tool level of a Mk I farming tool (garden_max.md: 15).");
        capMk2 = d.integer("farming.tool-cap-mk2", 30, "Highest tool level of a Mk II farming tool (30).");
        capMk3 = d.integer("farming.tool-cap-mk3", 40, "Highest tool level of a Mk III farming tool before Overclockers (40).");
        maxOverclocks = d.integer("farming.overclocker-max", 10, "Overclocker 3000s per Mk III tool, +1 level cap each (NEU: up to 10, so 50).");
        maxAlmanac = d.integer("farming.almanac-max", 5, "Farmer's Almanacs per tool (research 08 1.4).");
        almanacAnywhere = d.flag("farming.almanac-anywhere", true, "Apply almanacs from /farmtool anywhere (false = only at Harvesta).");
        blockVanillaGrowth = d.flag("farming.block-vanilla-growth", true, "Cancel vanilla crop growth/spread on farming islands (this module drives it).");

        contestEnabled = d.flag("farming.contest.enabled", true, "Run Harvest Fairs on their schedule.");
        contestPeriod = d.integer("farming.contest.period-minutes", 60, "Minutes between Harvest Fairs (research: every 3 SkyBlock days = 60 min).");
        contestOffset = d.integer("farming.contest.offset-minutes", 15, "Minutes past the hour a Harvest Fair starts.");
        contestDuration = d.integer("farming.contest.duration-minutes", 20, "Harvest Fair length (research: 1 SkyBlock day = 20 min).");
        contestMinLevel = d.integer("farming.contest.min-farming-level", 10, "Farming level needed to take part (research: Farming X).");
        contestMinScore = d.integer("farming.contest.min-score", 100, "Crops needed to be ranked (research: 100).");
        contestGhosts = d.integer("farming.contest.ghosts", 40, "NPC competitors per crop, so brackets work on a small server.");
        contestGhostMedian = d.number("farming.contest.ghost-median", 1500.0, "Median ghost score per average base drop (TUNE).");
        contestGhostSigma = d.number("farming.contest.ghost-spread", 1.0, "Log-normal spread of ghost scores (TUNE).");
        contestFlowers = d.flag("farming.contest.flowers", false, "Sunflower/Moonflower/Wild Rose can be Harvest Fair crops.");
        contestAutoClaim = d.flag("farming.contest.auto-claim", true, "Pay contest rewards at once (false = claim at the host).");
        contestBits = d.flag("farming.contest.bits", true, "Contest brackets also pay Bits (research 08 1.16).");
        contestSidebar = d.flag("farming.contest.sidebar", true, "Show the Harvest Fair on the sidebar.");
        contestHistory = d.integer("farming.contest.history", 30, "Finished contests kept per player.");
        capNeedsUniqueGolds = d.flag("farming.cap-needs-unique-golds", true, "Level-cap step N needs Gold in N distinct crops.");
        dropCropie = d.number("farming.contest.drop.cropling", 0.0005, "Cropling chance per wheat/carrot/potato block during a contest (TUNE).");
        dropSquash = d.number("farming.contest.drop.squash", 0.00025, "Squash chance per melon/pumpkin/cocoa block during a contest (TUNE).");
        dropFermento = d.number("farming.contest.drop.fermenta", 0.0001, "Fermenta chance per wart/cane/cactus/mushroom block (TUNE).");
        dropHelianthus = d.number("farming.contest.drop.helianthus", 0.00005, "Helianthus chance per flower block (TUNE).");

        contestRequireHost = d.flag("farming.contest.require-host", true, "Players must talk to the Harvest Fair host once before they can compete (research 08 1.16).");
        contestGarden = d.flag("farming.contest.garden", true, "Crops harvested in the Garden count for the Harvest Fair (read from the Garden's Gather events).");
        contestTurboBooks = d.flag("farming.contest.turbo-books", true, "Bronze or better also pays a Turbo-<crop> I book when the enchanting module registers it (research 08 1.16).");
        pbUnlockGold = d.integer("farming.pb.unlock-gold", 1, "Gold medals to unlock Personal Bests at Harvesta (TUNE).");
        pbUnlockTickets = d.integer("farming.pb.unlock-tickets", 100, "Harvest Fair Tickets to unlock Personal Bests at Harvesta (TUNE).");
        pbMaxFortune = d.integer("farming.pb.max-fortune", 100, "Personal Bests: max Crop Fortune per crop (0.01 per skyhanni increment of your best score).");
        trackPlaced = d.flag("farming.track-placed", true, "Crops a player PLACED (cane, cactus, melons, mushrooms...) pay no XP when broken on private islands.");
        placedMemory = d.integer("farming.placed-memory", 4096, "Player-placed crop blocks remembered per chunk (oldest forgotten first).");
        treasureCost = d.integer("farming.treasure.hint-cost", 2500, "Coins Digger Nim charges for a buried-treasure hint (TUNE).");
        treasureReward = d.integer("farming.treasure.reward", 4000, "Coins a dug-up treasure pays - fixed, never a gamble (TUNE).");
        treasureXp = d.integer("farming.treasure.farming-xp", 150, "Farming XP for digging up a treasure (TUNE).");
        treasureCooldown = d.integer("farming.treasure.cooldown-seconds", 300, "Rest between treasure hints (TUNE).");
        treasureMinutes = d.integer("farming.treasure.minutes", 10, "Minutes before a hinted treasure is lost (TUNE).");
        shepherdCount = d.integer("farming.shepherd.sheep", 5, "Sheep Shepherd Maela asks you to shear in one go (research 03: 'shear N sheep at once').");
        shepherdSeconds = d.integer("farming.shepherd.seconds", 30, "Seconds you have for Shepherd Maela's shearing task (TUNE).");
        shepherdReward = d.integer("farming.shepherd.reward", 1500, "Coins Shepherd Maela pays for a finished shearing task - fixed (TUNE).");
        shepherdXp = d.integer("farming.shepherd.farming-xp", 120, "Farming XP for a finished shearing task (TUNE).");
        shepherdCooldown = d.integer("farming.shepherd.cooldown-seconds", 180, "Rest between shearing tasks (TUNE).");
        shearXp = d.integer("farming.shear-xp", 4, "Farming XP per sheep sheared on a farming island ('harvest crops and shear sheep'; TUNE).");
        shearsCost = d.integer("farming.shepherd.shears-cost", 50, "Coins Shepherd Maela charges for a pair of Shears (0 = not sold).");
        woolRegrow = d.integer("farming.shepherd.wool-regrow-seconds", 20, "Seconds before a sheared sheep on a farming island grows its wool back.");

        trapperCooldown = d.integer("farming.trapper.cooldown-seconds", 60, "Rest between trapper quests (research ~60 s).");
        trapperMinutes = d.integer("farming.trapper.quest-minutes", 10, "Minutes to find a quarry before it escapes (TUNE).");
        trapperGlowRange = d.integer("farming.trapper.glow-range", 24, "Quarry glows when you are this close.");

        // ---- wave 5: other modules' items and pets inside farming -------------------------------------------
        enforceToolRequirements = d.flag("farming.tools.enforce-requirements", true,
                "A held farming tool whose requirements you do not meet adds no fortune, dices nothing and counts nothing (core drops its stats the same way).");
        toolGardenRequirement = d.flag("farming.tools.garden-requirement", true,
                "Add the reference's Garden-level requirement (garden:N) to every crop tool - only when the garden module registers that key. Read at boot.");
        gardenPlayerBonuses = d.flag("farming.garden-player-bonuses", true,
                "Write your Personal Best, pet crop stat (Pig), Shine Rush and Dark Cacao Truffle fortune into the held tool's crop_fortune, which the Garden adds (it has no per-player hook for these). Turn off if the garden ever reads them itself.");
        overbloomPercent = d.number("farming.overbloom-percent", 1.0,
                "Harvest Fair rare crops are this many % likelier per point of Overbloom (TUNE: research names the stat, not the formula).");
        plushiePelts = d.integer("farming.trapper.plushie-pelts", 1, "Extra pelts per tracker quest with the Tracker's Plushie (research 06: +1).");
        beltPelts = d.integer("farming.trapper.belt-pelts", 1, "Extra pelts per tracker quest while wearing the Pelt Belt (research 06: +1).");
        crestMultiplier = d.number("farming.trapper.crest-multiplier", 1.1, "Pelt multiplier with the Trapper Crest (research 06: x1.1).");
        crestUpgradePelts = d.integer("farming.trapper.crest-upgrade-pelts", 100, "Pelts collected with the Trapper Crest before it upgrades to Uncommon (research 06: 100).");
        trapperCallNeedsContact = d.flag("farming.trapper.call-needs-contact", true,
                "Taking a quest from /trapper away from the Den needs the trapper in your phone's contacts (the reference's call).");
        zorroChance = d.number("farming.contest.cape-medal-chance", 0.2, "Chance for one extra medal while wearing the Masked Hopper's Cape (research 06: 20%).");
        orbSeconds = d.integer("farming.farmer-orb.seconds", 3, "Farmer Orb: every N seconds a regrowing crop near the wearer finishes at once (research 06: 3 s).");
        orbCrops = d.integer("farming.farmer-orb.crops", 1, "Farmer Orb: crops finished per interval (research 06: one).");
        orbRadius = d.number("farming.farmer-orb.radius", 16.0, "Farmer Orb: how near a crop must be (TUNE: research says 'nearby').");
        respectSettings = d.flag("farming.respect-settings", true,
                "Harvest Fair chat lines follow /settings 'Event Start Messages' + 'Calendar Alerts', the sidebar follows 'Sidebar: Events'.");

        // ---- wave 5 (second pass): Shiny Pigs, the Dark Cacao Truffle ------------------------------------------
        shinyEnabled = d.flag("farming.shiny.enabled", true, "Shiny Pigs can appear while you farm (catch one, bring it to its orb for loot).");
        shinyIslands = d.text("farming.shiny.islands", "hub,barn,mushroom_desert,garden",
                "Islands where breaking a grown crop can make a Shiny Pig appear (comma list).");
        shinyChance = d.number("farming.shiny.chance", 0.00005, "Chance per grown crop broken that a Shiny Pig appears (TUNE: the reference rate is not in the data).");
        shinySeconds = d.integer("farming.shiny.seconds", 60, "Seconds to catch a Shiny Pig and bring it to its orb before it gets away (TUNE).");
        shinyCooldown = d.integer("farming.shiny.cooldown-seconds", 120, "After a Shiny Pig (caught or not), no new one appears for you for this long (TUNE).");
        shinyCoins = d.number("farming.shiny.coins", 2000.0, "Base coins a delivered Shiny Pig pays (TUNE).");
        shinyCoinsPerLevel = d.number("farming.shiny.coins-per-level", 100.0, "Extra coins per Farming level (TUNE).");
        shinyRodCoins = d.number("farming.shiny.rod-coin-percent", 25.0, "Extra coins with a Shiny Rod in your inventory (NEU lore: 25%).");
        shinyRodFlee = d.number("farming.shiny.rod-flee-percent", 30.0, "Shiny Pigs run away this much faster while you hold a Shiny Rod (NEU lore: 30%).");
        shinyHarvestingChance = d.number("farming.shiny.harvesting-chance", 0.01, "Chance a delivered pig also gives Harvesting VI (its only source in the reference; TUNE).");
        shinySpreadingChance = d.number("farming.shiny.spreading-chance", 0.04, "Chance a delivered pig also gives Potato Spreading (research 02: 'Shiny Pig rare drop'; TUNE).");
        shinyTokenChance = d.number("farming.shiny.token-chance", 0.0, "Chance for a Shiny Token (the reference drops them only during the Year of the Pig; 0 = off).");
        shinyMixinEffect = d.text("farming.shiny.mixin-effect", "pig_brain_mixin", "Active-effect id of the Pig Brain Mixin (its +10% extra-item chance).");
        shinyMixinChance = d.number("farming.shiny.mixin-chance", 0.10, "Pig Brain Mixin: chance a delivered pig rolls its items twice (NEU lore: 10%).");
        shinyOrbRadius = d.number("farming.shiny.orb-radius", 2.5, "How close to its orb a caught pig must be to count as delivered.");
        shinyRoam = d.number("farming.shiny.roam-radius", 18.0, "A fleeing Shiny Pig stays within this many blocks of its orb (it circles instead).");
        gardenAccessoryBonuses = d.flag("farming.garden-accessory-bonuses", false,
                "Also carry accessories' per-crop fortune (Cropling/Squash/Fermenta) on the held tool into the Garden. Off: the garden counts them itself (wave 5), on would pay twice.");
        cogmindPercent = d.number("farming.tools.cogmind-percent", 1.5, "Farming-tool XP % per level of the Garden's Cogmind chip (reference Mechamind: 1.5%).");
        truffleFortune = d.number("farming.truffle.fortune", 30.0, "Refined Dark Cacao Truffle: Cocoa Beans Fortune while its buff runs (NEU lore: +30).");
        truffleMinutes = d.integer("farming.truffle.minutes", 60, "Refined Dark Cacao Truffle: buff length in minutes (NEU lore: 60).");
        trufflePermanentMax = d.integer("farming.truffle.permanent-max", 5, "Refined Dark Cacao Truffle: +1 permanent Cocoa Beans Fortune per truffle, up to this many (NEU lore: 5).");
    }

    Dials.Dial<Boolean> shinyEnabled, gardenAccessoryBonuses;
    Dials.Dial<Double> cogmindPercent;
    Dials.Dial<String> shinyIslands, shinyMixinEffect;
    Dials.Dial<Double> shinyChance, shinyCoins, shinyCoinsPerLevel, shinyRodCoins, shinyRodFlee, shinyHarvestingChance,
            shinySpreadingChance, shinyTokenChance, shinyMixinChance, shinyOrbRadius, shinyRoam, truffleFortune;
    Dials.Dial<Integer> shinySeconds, shinyCooldown, truffleMinutes, trufflePermanentMax;

    private String shinyRaw;
    private Set<String> shinyCache = Set.of();

    /** Islands where Shiny Pigs can appear (cached per dial value). */
    Set<String> shinyIslandSet() {
        String raw = shinyIslands.get();
        if (!raw.equals(shinyRaw)) {
            Set<String> s = new HashSet<>();
            for (String p : raw.split(",")) if (!p.isBlank()) s.add(p.trim().toLowerCase(Locale.ROOT));
            shinyCache = Set.copyOf(s);
            shinyRaw = raw;
        }
        return shinyCache;
    }

    private String islandsRaw;
    private Set<String> islandsCache = Set.of();

    /** Islands this module farms on. Cached per dial value: handles() asks on every block break in the game. */
    Set<String> islandSet() {
        String raw = islands.get();
        if (!raw.equals(islandsRaw)) {
            Set<String> s = new HashSet<>();
            for (String p : raw.split(",")) if (!p.isBlank()) s.add(p.trim().toLowerCase(Locale.ROOT));
            islandsCache = Set.copyOf(s);
            islandsRaw = raw;
        }
        return islandsCache;
    }

    double contestDropChance(String item) {
        return switch (item) {
            case "CROPIE" -> dropCropie.get();
            case "SQUASH" -> dropSquash.get();
            case "FERMENTO" -> dropFermento.get();
            case "HELIANTHUS" -> dropHelianthus.get();
            default -> 0;
        };
    }
}
