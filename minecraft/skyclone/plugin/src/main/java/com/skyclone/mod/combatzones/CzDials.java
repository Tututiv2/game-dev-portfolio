package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

import java.util.HashMap;
import java.util.Map;

/** Every tunable of the combat zones, editable in game with /sb dial set combatzones.<key>. */
final class CzDials {

    final Dials.Dial<String> denRequirement, endRequirement, hideoutRequirement, tombRequirement;
    final Dials.Dial<Integer> broodCycle, broodRadius;
    final Dials.Dial<Integer> arachneCallings, arachneIdleMinutes;
    final Dials.Dial<Integer> dragonCountdown, dragonIdleMinutes, dragonMaxFragments, dragonAbilitySeconds, dragonArenaRadius;
    final Dials.Dial<Double> crystalHeal, crystalFragmentChance, dragonHealthScale;
    final Map<String, Dials.Dial<Double>> variantWeights = new HashMap<>();
    final Dials.Dial<Integer> protectorPerStage, protectorWarning, regenSeconds;
    final Dials.Dial<Double> endStoneXp, gravelFlintChance;
    final Dials.Dial<Double> zealotChestChance, specialZealotChance;
    final Dials.Dial<Integer> remnantSeconds;
    final Dials.Dial<Boolean> rainEnabled;
    final Dials.Dial<Integer> dragonSbXp, arachneSbXp;
    final Dials.Dial<Integer> relicCoins, relicAllCoins, relicSbXp;
    // wave 5
    final Dials.Dial<Integer> dragonSbXpSuperior, mobTypeSbXp;
    final Dials.Dial<Double> altarScale, altarBonusPerEssence, altarBonusCap, dragonEssenceDivisor;
    final Dials.Dial<Double> voidSwordPrice, voidSwordDiscount, questRewardScale;
    final Dials.Dial<Boolean> perksEnabled;
    final Map<String, Dials.Dial<Double>> perkPerLevel = new HashMap<>();

    CzDials() {
        Dials d = Sky.dials();
        denRequirement = d.text("combatzones.den-requirement", "skill:combat:1", "Requirement to warp to the Spider's Den (research 03: Combat I)");
        endRequirement = d.text("combatzones.end-requirement", "skill:combat:12", "Requirement to warp to The End (research 03: Combat XII)");
        hideoutRequirement = d.text("combatzones.hideout-requirement", "skill:combat:20", "Bruiser's Hollow entry (research: Combat XX). Zone reads it at boot");
        tombRequirement = d.text("combatzones.tomb-requirement", "skill:combat:25", "Null Tomb entry (research: Combat XXV). Zone reads it at boot");
        broodCycle = d.integer("combatzones.broodmother-cycle-seconds", 720, "Seconds from the Broodmother's death until she returns (research ~10-15 min)");
        broodRadius = d.integer("combatzones.broodmother-player-radius", 120, "A player must be this close to the nest for the Broodmother to rise");
        arachneCallings = d.integer("combatzones.silkmother-callings", 4, "Callings needed at the altar to wake the Silkmother (research: 4)");
        arachneIdleMinutes = d.integer("combatzones.silkmother-idle-minutes", 10, "Despawn the Silkmother after this many minutes with nobody fighting");
        dragonCountdown = d.integer("combatzones.dragon-countdown-seconds", 8, "Seconds between the 8th eye and the dragon rising");
        dragonIdleMinutes = d.integer("combatzones.dragon-idle-minutes", 10, "Despawn a dragon after this many minutes without a hit");
        dragonMaxFragments = d.integer("combatzones.dragon-max-fragments", 8, "Most fragments a player can get from one fight (TUNE)");
        dragonAbilitySeconds = d.integer("combatzones.dragon-ability-seconds", 6, "Seconds between dragon special attacks (TUNE)");
        dragonArenaRadius = d.integer("combatzones.dragon-arena-radius", 64, "Players farther than this from the nest podium are out of the dragon fight: no dragon ability targets them (was a fixed 110)");
        crystalHeal = d.number("combatzones.crystal-heal-percent", 0.05, "Dragon heal per second per linked crystal, % of max HP (research: 0.3%/s for all six)");
        crystalFragmentChance = d.number("combatzones.crystal-fragment-chance", 0.25, "Chance a destroyed nest crystal gives a Crystal Fragment (TUNE)");
        dragonHealthScale = d.number("combatzones.dragon-health-scale", 1.0, "Multiplier on dragon HP (1.0 = research numbers)");
        for (DragonMath.Variant v : DragonMath.VARIANTS)
            variantWeights.put(v.id(), d.number("combatzones.dragon-weight-" + v.id(), v.weight(), "Spawn weight of the " + v.name() + " Dragon (research: 16, Superior 4)"));
        protectorPerStage = d.integer("combatzones.colossus-blocks-per-stage", 150, "End stone mined in Protector's Field per Colossus stage, 5 stages (TUNE for player count)");
        protectorWarning = d.integer("combatzones.colossus-warning-seconds", 20, "Warning before the Colossus rises (research ~20 s)");
        regenSeconds = d.integer("combatzones.block-regen-seconds", 20, "Seconds before mined end stone / gravel grows back");
        endStoneXp = d.number("combatzones.end-stone-mining-xp", 3, "Mining XP per end stone block (TUNE)");
        gravelFlintChance = d.number("combatzones.gravel-flint-chance", 0.1, "Chance mined gravel gives flint instead (vanilla 10%)");
        zealotChestChance = d.number("combatzones.zealot-chest-chance", 0.02, "Chance a Zealot spawns holding an ender chest (research 2%)");
        specialZealotChance = d.number("combatzones.special-zealot-chance", 1.0 / 420, "Chance a Zealot is a Special Zealot dropping a Summoning Eye (research 1/420)");
        remnantSeconds = d.integer("combatzones.remnant-seconds", 5, "Invulnerability from a Remnant of the Eye (TUNE)");
        rainEnabled = d.flag("combatzones.den-weather", true, "Spider's Den rain/thunder cycle (research: rain 20 of every 40 min, every 3rd a storm)");
        dragonSbXp = d.integer("combatzones.dragon-first-kill-sbxp", 25, "SkyBlock XP for the first kill of each dragon variant (research 02: 25; Superior has its own dial)");
        arachneSbXp = d.integer("combatzones.silkmother-first-kill-sbxp", 20, "SkyBlock XP for the first Silkmother kill at each level (research 02: Arachne tiers 20 each)");
        relicCoins = d.integer("combatzones.relic-coins", 1_000, "Coins for each Spider's Den relic found (TUNE)");
        relicAllCoins = d.integer("combatzones.relic-all-coins", 25_000, "Bonus coins from Professor Dunmore for finding every relic (TUNE)");
        relicSbXp = d.integer("combatzones.relic-sbxp", 1, "SkyBlock XP per relic found (TUNE; the all-found bonus pays 10x)");
        // ---- wave 5 (2026-10-06)
        dragonSbXpSuperior = d.integer("combatzones.dragon-first-kill-sbxp-superior", 50, "SkyBlock XP for the first Superior Dragon kill (research 02: 50)");
        mobTypeSbXp = d.integer("combatzones.mob-type-sbxp", MobTypeCatalog.SBXP_PER_TYPE, "SkyBlock XP for the first kill of each mob type (research 02: 1 each)");
        altarScale = d.number("combatzones.altar-essence-scale", 1.0, "Multiplier on the Dragon Essence the Draconic Altar pays per item (TUNE)");
        altarBonusPerEssence = d.number("combatzones.altar-bonus-per-essence", 0.0005, "Bonus-loot chance per essence a sacrifice pays (TUNE: an 80-essence chestplate = 4%)");
        altarBonusCap = d.number("combatzones.altar-bonus-cap", 0.5, "Highest bonus-loot chance of one sacrifice before the Sacrificer pet (TUNE)");
        dragonEssenceDivisor = d.number("combatzones.dragon-essence-divisor", 20, "Dragon Essence per dragon kill = loot quality / this (research: dragons are a source; amount TUNE)");
        voidSwordPrice = d.number("combatzones.void-sword-price", 60_000, "Coins for a Void Sword at Wanderer Tavi (TUNE)");
        voidSwordDiscount = d.number("combatzones.void-sword-discount", 20, "Percent off the Void Sword once Tavi's quests are all done (TUNE)");
        questRewardScale = d.number("combatzones.quest-reward-scale", 1.0, "Multiplier on Tavi's quest coins and Combat XP (TUNE)");
        perksEnabled = d.flag("combatzones.dragon-perks", true, "Keeper Ixo's Dragon Essence perks apply their effects");
        for (DraconicMath.Perk pk : DraconicMath.PERKS)
            perkPerLevel.put(pk.id(), d.number("combatzones.perk-" + pk.id().replace('_', '-'), pk.perLevel(),
                    "Effect per level of the " + pk.name() + " Dragon Essence perk (TUNE; the research gives costs, not effects)"));
    }

    Map<String, Double> weights() {
        Map<String, Double> m = new HashMap<>();
        variantWeights.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
