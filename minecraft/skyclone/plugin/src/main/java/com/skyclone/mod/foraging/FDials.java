package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every foraging tunable, registered once in enable(). Read with {@code .get()} at use time. */
final class FDials {

    private FDials() {}

    static Dials.Dial<Boolean> enabled;
    // wave 5, second pass
    static Dials.Dial<Boolean> boosters;
    static Dials.Dial<Double> boosterScale;
    static Dials.Dial<Boolean> giftMonsters;
    static Dials.Dial<Double> giftMonsterScale;
    static Dials.Dial<Double> giftExtraScale;
    static Dials.Dial<Double> jadeDragonCoins;
    static Dials.Dial<Integer> regenSeconds;
    static Dials.Dial<Integer> regenMarshSeconds;
    static Dials.Dial<Integer> flowerRegenSeconds;
    static Dials.Dial<Integer> treeScanCap;
    static Dials.Dial<Double> throwPenalty;
    static Dials.Dial<Integer> throwRange;
    static Dials.Dial<Double> xpMultiplier;
    static Dials.Dial<Boolean> hubForestOnly;
    static Dials.Dial<Boolean> privateIslandXp;
    static Dials.Dial<Boolean> needLeaves;
    static Dials.Dial<Integer> waveTicks;
    static Dials.Dial<Double> questRewardScale;
    static Dials.Dial<Boolean> lumberJack;
    static Dials.Dial<Double> trialLogScale;
    static Dials.Dial<Double> harpIntScale;
    static Dials.Dial<Integer> harpNoteTicks;
    static Dials.Dial<Double> giftScale;
    static Dials.Dial<Double> whisperScale;
    static Dials.Dial<Double> hotfXpScale;
    static Dials.Dial<Double> hotfXpPerLog;
    static Dials.Dial<Integer> marshTreeRespawn;
    static Dials.Dial<Boolean> marshNeedsLevel;
    static Dials.Dial<Integer> resourceRegenSeconds;
    static Dials.Dial<Boolean> axeAbilities;
    static Dials.Dial<Boolean> cuttingOrder;
    static Dials.Dial<Boolean> contests;
    static Dials.Dial<Integer> contestMinutes;
    static Dials.Dial<Double> contestPointScale;
    static Dials.Dial<Double> contestRewardScale;
    static Dials.Dial<Double> timberPercentPerPoint;
    // wave 5
    static Dials.Dial<Boolean> contestBits;
    static Dials.Dial<Double> behemothChance;
    static Dials.Dial<Boolean> hives;
    static Dials.Dial<Integer> hiveRefillSeconds;
    static Dials.Dial<Integer> hiveHoneycomb;
    static Dials.Dial<Double> hiveXp;
    static Dials.Dial<Integer> latherMax;
    static Dials.Dial<Integer> latherCombOnKill;
    static Dials.Dial<Boolean> depths;
    static Dials.Dial<Double> depthAirTicksPerPoint;
    static Dials.Dial<Double> depthPressurePerBlock;
    static Dials.Dial<Double> depthDamagePercent;
    static Dials.Dial<String> depthZones;

    static void register() {
        Dials d = Sky.dials();
        enabled = d.flag("foraging.enabled", true, "Master switch: foraging owns log breaking on the Park, the Hub forest and the Moonmarsh");
        regenSeconds = d.integer("foraging.regen-seconds", 60, "Seconds before a cut Park/Hub tree regrows whole (TUNE: research says ~60-120s)");
        regenMarshSeconds = d.integer("foraging.marsh-regen-seconds", 600, "Seconds before a HALF-cut Moonmarsh tree gives up and regrows (TUNE)");
        flowerRegenSeconds = d.integer("foraging.flower-regen-seconds", 20, "Seconds before a picked flower grows back");
        treeScanCap = d.integer("foraging.tree-scan-cap", 400, "Most logs one tree may have (flood-fill cap)");
        throwPenalty = d.number("foraging.throw-penalty", 0.5, "Fraction of Sweep lost when the axe is thrown (research: -50%)");
        throwRange = d.integer("foraging.throw-range", 24, "How far a thrown axe flies, in blocks");
        xpMultiplier = d.number("foraging.xp-multiplier", 1.0, "Multiplier on Foraging XP per log (before Wisdom)");
        hubForestOnly = d.flag("foraging.hub-forest-only", true, "On the Hub, only trees inside the 'forest' area are choppable");
        privateIslandXp = d.flag("foraging.private-island-xp", true, "Natural logs broken on your own island grant Foraging XP and fortune");
        needLeaves = d.flag("foraging.need-leaves", true, "A log must belong to a tree with leaves (protects log-framed buildings)");
        waveTicks = d.integer("foraging.wave-ticks", 1, "Ticks between each ring of a sweep's felling wave (0 = all at once)");
        questRewardScale = d.number("foraging.quest-reward-scale", 1.0, "Multiplier on Park quest coin and XP rewards (TUNE)");
        lumberJack = d.flag("foraging.lumber-jack", true, "Hub woodcutter's starter quest (20 Oak Logs -> Promising Axe)");
        trialLogScale = d.number("foraging.trial-log-scale", 1.0, "Multiplier on the logs each Ember Trials badge tier asks for (TUNE)");
        harpIntScale = d.number("foraging.harp-int-scale", 0.0, "Multiplier on Intelligence from Lyra's memory-game harp songs. 0 since wave 5: extras' /harp is the real harp and pays the Intelligence; set 1 only when extras is not loaded");
        harpNoteTicks = d.integer("foraging.harp-note-ticks", 12, "Ticks each harp note lights up for while a song is played");
        giftScale = d.number("foraging.gift-scale", 1.0, "Multiplier on Tree Gift rewards");
        whisperScale = d.number("foraging.whisper-scale", 1.0, "Multiplier on Forest/Desert Whispers earned");
        hotfXpScale = d.number("foraging.hotf-xp-scale", 1.0, "Multiplier on Heart of the Forest XP earned");
        hotfXpPerLog = d.number("foraging.hotf-xp-per-log", 1.0, "HotF XP per Moonmarsh log cut (TUNE: stands in for the reference's contests)");
        marshTreeRespawn = d.integer("foraging.marsh-tree-respawn", 30, "Seconds a fully felled Moonmarsh tree stays gone before it regrows (TUNE)");
        resourceRegenSeconds = d.integer("foraging.resource-regen-seconds", 30, "Seconds before a picked Moonmarsh resource (Velvet Lilac, Sea Lumies, Ruby Veilshroom) grows back (TUNE)");
        contests = d.flag("foraging.contests", true, "Starfall Contests on the Moonmarsh and in the Torrent Canyon (ref. Starlyn Contests)");
        contestMinutes = d.integer("foraging.contest-minutes", 20, "Length of one Starfall Contest in real minutes (research: one per SkyBlock day = 20)");
        contestPointScale = d.number("foraging.contest-point-scale", 1.0, "Multiplier on Starfall Contest points");
        contestRewardScale = d.number("foraging.contest-reward-scale", 1.0, "Multiplier on Starfall Contest bracket rewards");
        cuttingOrder = d.flag("foraging.cutting-order", true, "Moonmarsh giants have a cutting order (fig trunk->branches, mangrove branches->trunk, spiral pale->red); the wrong part first halves Sweep");
        axeAbilities = d.flag("foraging.axe-abilities", true, "Heart of the Forest axe abilities (sneak + right-click an axe on a foraging island)");
        timberPercentPerPoint = d.number("foraging.timber-percent-per-point", 10.0, "Whole-tree chance per point of Timber (HotF Timber 0.1/level, Petal Shower 0.2-1.0) (TUNE)");
        contestBits = d.flag("foraging.contest-bits", true, "Starfall Contest brackets pay Bits (research 15-120)");
        behemothChance = d.number("foraging.behemoth-chance", 0.05, "Chance a Legendary+ Canyon Starfall Contest also pays a Behemoth Pot of Honeycomb (TUNE)");
        hives = d.flag("foraging.hives", true, "Honey hives on the Torrent Canyon / Spiral Mesa can be harvested for Honeycomb");
        hiveRefillSeconds = d.integer("foraging.hive-refill-seconds", 300, "Seconds before a hive you emptied is full again for you (TUNE)");
        hiveHoneycomb = d.integer("foraging.hive-honeycomb", 3, "Honeycomb in one full hive (TUNE)");
        hiveXp = d.number("foraging.hive-xp", 20.0, "Foraging XP per hive harvested (TUNE)");
        latherMax = d.integer("foraging.lather-max", 3, "Trees one player may have lathered at once (TUNE)");
        latherCombOnKill = d.integer("foraging.lather-comb-on-kill", 2, "Honeycomb a lured critter drops when killed, before Lathering Greed (TUNE)");
        depths = d.flag("foraging.depths", true, "Breath and Pressure in the underwater depths of the foraging islands");
        depthAirTicksPerPoint = d.number("foraging.depth-air-ticks-per-point", 10.0, "Air ticks per point of Respiration on foraging islands (30 base = vanilla 15 s) (TUNE)");
        depthPressurePerBlock = d.number("foraging.depth-pressure-per-block", 2.0, "Pressure per block below the water surface in a depths zone (TUNE)");
        depthDamagePercent = d.number("foraging.depth-damage-percent", 2.0, "% of max health lost per second for each 10 Pressure your resistance does not cancel (TUNE)");
        depthZones = d.text("foraging.depth-zones", "dimwater_depths,torrhus_canyon_depths", "Zone ids (comma list) where Pressure applies");
        boosters = d.flag("foraging.boosters", true, "Boosters on foraging gear (/boosters): Sweep, Foraging Fortune, Foraging/Hunting Wisdom, Fighting");
        boosterScale = d.number("foraging.booster-scale", 1.0, "Multiplier on every booster's amount (TUNE: Sweep 1/2, Fortune 5/10, Wisdom 0.5/1, Fighting 5%/10% per item)");
        giftMonsters = d.flag("foraging.gift-monsters", true, "Tree Gifts can release a creature (research 07 4.4: Phanpyre 11.8% ... Grizzly Bear 1%)");
        giftMonsterScale = d.number("foraging.gift-monster-scale", 1.0, "Multiplier on the Tree Gift creature odds");
        giftExtraScale = d.number("foraging.gift-extra-scale", 1.0, "Multiplier on the Tree Gift bonus drops (boosters, books, shards, Bark the Fish...) (TUNE)");
        jadeDragonCoins = d.number("foraging.jade-dragon-coins", 500_000_000.0, "Coins the jade dragon asks for its egg (NEU: 500,000,000 + Shiniest + 64 Figstone/Mangcore/Helixis)");
        marshNeedsLevel = d.flag("foraging.marsh-needs-level", true, "The Moonmarsh needs Foraging XII (research), the canyon HotF IV");
    }
}
