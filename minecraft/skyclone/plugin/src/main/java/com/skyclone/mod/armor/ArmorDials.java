package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/**
 * Every armor tunable. Defaults are research 06 §3 numbers unless marked TUNE (the reference
 * does not publish the number, or our engine has no equivalent of the original mechanic).
 * Read with {@code .get()} at use time so {@code /sb dial set} takes effect live.
 */
final class ArmorDials {

    private ArmorDials() {}

    static Dials.Dial<Boolean> ENABLED;
    static Dials.Dial<Boolean> RIGHT_CLICK_EQUIP;
    // dragons
    static Dials.Dial<Double> YOUNG_SPEED, YOUNG_HP_THRESHOLD, SUPERIOR_PCT, SUPERIOR_AOTD_PCT, HOLY_REGEN, HOLY_RANGE,
            WISE_COST, STRONG_AOTE_DAMAGE, STRONG_STRENGTH, STRONG_SECONDS, UNSTABLE_DAMAGE, UNSTABLE_RANGE,
            PROTECTOR_PCT, OLD_BOOST, ENDER_MULT;
    static Dials.Dial<Integer> UNSTABLE_INTERVAL;
    // early combat
    static Dials.Dial<Double> GOLEM_ABSORPTION, GOLEM_SECONDS, MERC_COINS, MERC_HEAL, EMERALD_PER, EMERALD_CAP,
            SPEEDSTER_SPEED, CACTUS_REFLECT, ZOMBIE_HEAL, GROWTH_HEAL_PCT, GROWTH_MAX, GROWTH_COOLDOWN,
            MAGMA_PER, MAGMA_CAP, WEREWOLF_DEF, TUX_CHEAP_HP, TUX_FANCY_HP, TUX_ELEGANT_HP, TUX_CHEAP_DMG, TUX_FANCY_DMG,
            TUX_ELEGANT_DMG, RAMPART_HP, RAMPART_STR, RAMPART_CD, BLAZE_PCT, BLAZE_CAP, BLAZE_CAP_PER_5K, BLAZE_RANGE,
            FROZEN_BASE, FAIRY_HP_PER_SOUL;
    static Dials.Dial<Integer> MERC_MIN_LEVEL, WEREWOLF_MAX, WEREWOLF_SECONDS;
    // mining
    static Dials.Dial<Double> MINER_SPEED, GLACITE_PER_LEVEL, YOG_PER, YOG_CAP, LAPIS_XP_PCT, SHIMMER_XP_PCT,
            MINERAL_SPREAD, GLOSSY_SPREAD, HEAT_MULT, TANK_MINER_MULT, HEAT_RESISTANCE, SORROW_GHOST_MULT, MITHRIL_CAP;
    // farming
    static Dials.Dial<Double> RARE_CROP_MULT;
    // fishing
    static Dials.Dial<Double> ANGLER_REDUCE, ANGLER_HP_PER_LEVEL, BACKWATER_DMG, DIVER_TREASURE, ABYSSAL_TREASURE,
            THUNDER_PER_CHARGE, THUNDER_TAKEN, THUNDER_DEALT, MAGMA_LORD_TAKEN, MAGMA_LORD_DEALT, MAGMA_LORD_MF,
            NUTCRACKER_DEF, NUTCRACKER_MF, NUTCRACKER_RANGE, WATER_DEF_MULT, SALMON_MANA, SALMON_POWER;
    static Dials.Dial<Integer> THUNDER_SECONDS, THUNDER_CAP, NUTCRACKER_STACKS;
    // misc / mythic / items
    static Dials.Dial<Double> MYTHIC_MULT, UNEARTHED_PCT, FAMILIARITY_MF, GRUNT_ARROW_PCT, SPIKY_CHANCE, VAMPIRE_DRAIN,
            CREEPER_DAMAGE, SPIDER_JUMP_MANA, SPRING_MAX_POWER;
    static Dials.Dial<Integer> BONE_SHIELD_MAX, BONE_SHIELD_SECONDS, BLOCK_DAMAGE_COOLDOWN, DETONATE_COOLDOWN;
    static Dials.Dial<String> MYTHIC_EVENT, MAGMATIC_WORDS, MYTHIC_WORDS, GHOST_WORDS;
    // wave 3 (2026-10-04): bonuses that used to be lore only
    static Dials.Dial<Double> SIEVE_KNOCKBACK, REKINDLE_PCT, REKINDLE_FIRE_PER_SEC, REKINDLE_FIRE_CAP, REKINDLE_IGNITE_SECONDS,
            CROWN_GREED_COINS, CROWN_AVARICE_COINS, CROWN_AVARICE_2_COINS, BOUNCY_MIN_FALL, BOUNCY_POWER,
            BREATH_PER_LEVEL, SNORKEL_LEVELS, BEAT_SAVER_CHANCE, FEARSOME_RANGE, FEARSOME_PUSH, SHARK_FESTIVAL_SCC, STARPOWER_RANGE;
    static Dials.Dial<String> FISHING_FESTIVAL_EVENT;
    static Dials.Dial<Integer> STRONG_EXTRA_RANGE, CORE_HIT_TICKS, CORE_HIT_MIN;
    // wave 5 (2026-10-06)
    static Dials.Dial<Double> YOUNG_SPEED_CAP, NEWTON_AS_CAP, CORE_SPEED_CAP, CORE_AS_CAP;
    static Dials.Dial<Boolean> BLAZE_PET_ABILITY;
    // wave 5, second pass: behaviour gaps found by diffing our bonus text against the reference lore
    static Dials.Dial<Integer> MAGMA_LORD_PIECES, PEACE_TREATY_PIECES, BAT_SWARM_RESPAWN, EMBER_OBSIDIAN_SECONDS, SPOOKY_COOLDOWN, SPOOK_SECONDS;
    static Dials.Dial<Double> CROWN_GREED_MF, BAT_SWARM_WITCH_DAMAGE, BAT_SWARM_VAMPIRE_DAMAGE, BAT_SWARM_RANGE, BAT_SWARM_SPEED,
            EMBER_OBSIDIAN_RADIUS;
    static Dials.Dial<Boolean> PEACE_TREATY, EMBER_OBSIDIAN;

    static void register() {
        Dials d = Sky.dials();
        ENABLED = d.flag("armor.bonuses-enabled", true, "Master switch for every armor set bonus");
        RIGHT_CLICK_EQUIP = d.flag("armor.right-click-equip", true, "Right click any armour piece in your hand to wear it (swaps with the worn piece)");

        YOUNG_SPEED = d.number("armor.young-speed", 70, "Young Blood: Speed while above the health threshold");
        YOUNG_HP_THRESHOLD = d.number("armor.young-threshold", 0.5, "Young Blood: health fraction that must be exceeded");
        SUPERIOR_PCT = d.number("armor.superior-percent", 5, "Superior Blood: % added to combat stats and Magic Find");
        SUPERIOR_AOTD_PCT = d.number("armor.superior-breath-percent", 50, "Superior Blood: % extra damage of ASPECT_OF_THE_DRAGON ability");
        HOLY_REGEN = d.number("armor.holy-regen", 75, "Holy Blood: Health Regen to you and players in range");
        HOLY_RANGE = d.number("armor.holy-range", 10, "Holy Blood: aura radius (blocks)");
        WISE_COST = d.number("armor.wise-cost", 2.0 / 3.0, "Wise Blood: ability mana cost multiplier");
        STRONG_AOTE_DAMAGE = d.number("armor.strong-aote-damage", 75, "Strong Blood: extra base damage of ASPECT_OF_THE_END");
        STRONG_STRENGTH = d.number("armor.strong-strength", 5, "Strong Blood: Strength per ASPECT_OF_THE_END teleport");
        STRONG_SECONDS = d.number("armor.strong-seconds", 5, "Strong Blood: seconds each Strength stack lasts (TUNE)");
        UNSTABLE_INTERVAL = d.integer("armor.unstable-interval", 10, "Unstable Blood: seconds between lightning bolts (TUNE)");
        UNSTABLE_DAMAGE = d.number("armor.unstable-damage", 3000, "Unstable Blood: lightning magic damage (TUNE)");
        UNSTABLE_RANGE = d.number("armor.unstable-range", 8, "Unstable Blood: lightning reach in blocks (TUNE)");
        PROTECTOR_PCT = d.number("armor.protector-percent", 1, "Protective Blood: % of piece Defense per 1% missing health");
        OLD_BOOST = d.number("armor.old-boost", 0.5, "Old Blood: extra fraction of Growth/Protection/Sugar Rush/True Protection stats (TUNE)");
        ENDER_MULT = d.number("armor.ender-multiplier", 2, "Ender / Nutcracker / Snow Suit stat multiplier on their island");

        GOLEM_ABSORPTION = d.number("armor.golem-absorption", 60, "Golem: absorption health granted on kill (research: Absorption III = 3 x 20, the alchemy potion's per-level amount)");
        GOLEM_SECONDS = d.number("armor.golem-seconds", 20, "Golem: seconds the absorption lasts");
        MERC_COINS = d.number("armor.mercenary-coins", 5, "Death Tax: coins per qualifying kill");
        MERC_HEAL = d.number("armor.mercenary-heal", 20, "Death Tax: health healed per qualifying kill");
        MERC_MIN_LEVEL = d.integer("armor.mercenary-min-level", 10, "Death Tax: minimum mob level");
        EMERALD_PER = d.number("armor.emerald-per", 3000, "Tank: Emerald collection per +1 HP/+1 Defense");
        EMERALD_CAP = d.number("armor.emerald-cap", 350, "Tank: maximum bonus each");
        SPEEDSTER_SPEED = d.number("armor.speedster-speed", 20, "Speedster: bonus Speed");
        CACTUS_REFLECT = d.number("armor.cactus-reflect", 0.33, "Deflect: fraction of damage taken thrown back");
        ZOMBIE_HEAL = d.number("armor.zombie-heal", 10, "Projectile Absorption: heal per second for 5s");
        GROWTH_HEAL_PCT = d.number("armor.growth-heal-percent", 1, "Growth: % max health healed per monster kill");
        GROWTH_MAX = d.number("armor.growth-max", 100, "Growth: max bonus health per piece");
        GROWTH_COOLDOWN = d.number("armor.growth-cooldown", 4, "Growth: seconds between growth triggers");
        MAGMA_PER = d.number("armor.magma-per", 10, "Armor of Magma: Magma Cube kills per +1 HP/Int");
        MAGMA_CAP = d.number("armor.magma-cap", 200, "Armor of Magma: maximum bonus each");
        WEREWOLF_DEF = d.number("armor.werewolf-defense", 50, "Regenerative Howl: Defense per stack");
        WEREWOLF_MAX = d.integer("armor.werewolf-max", 10, "Regenerative Howl: max stacks");
        WEREWOLF_SECONDS = d.integer("armor.werewolf-seconds", 5, "Regenerative Howl: seconds per stack");
        TUX_CHEAP_HP = d.number("armor.tux-cheap-health", 75, "Cheap Tuxedo: max health cap");
        TUX_FANCY_HP = d.number("armor.tux-fancy-health", 150, "Fancy Tuxedo: max health cap");
        TUX_ELEGANT_HP = d.number("armor.tux-elegant-health", 250, "Elegant Tuxedo: max health cap");
        TUX_CHEAP_DMG = d.number("armor.tux-cheap-damage", 50, "Cheap Tuxedo: % damage dealt");
        TUX_FANCY_DMG = d.number("armor.tux-fancy-damage", 100, "Fancy Tuxedo: % damage dealt");
        TUX_ELEGANT_DMG = d.number("armor.tux-elegant-damage", 150, "Elegant Tuxedo: % damage dealt");
        RAMPART_HP = d.number("armor.rampart-health", 50, "Rampart: Health on the Crimson Isle");
        RAMPART_STR = d.number("armor.rampart-strength", 20, "Rampart: Strength on the Crimson Isle");
        RAMPART_CD = d.number("armor.rampart-crit-damage", 15, "Rampart: Crit Damage on the Crimson Isle");
        BLAZE_PCT = d.number("armor.blaze-percent", 3, "Blazing Aura: % of mob max health per second");
        BLAZE_CAP = d.number("armor.blaze-cap", 500, "Blazing Aura: max damage per second");
        BLAZE_CAP_PER_5K = d.number("armor.blaze-cap-per-5k", 100, "Blazing Aura: cap added per 5,000 Blaze Rod collection");
        BLAZE_RANGE = d.number("armor.blaze-range", 5, "Blazing Aura: radius (blocks)");
        FROZEN_BASE = d.number("armor.frozen-blaze-base", 300, "Frozen Blazing Aura: flat damage per second");
        FAIRY_HP_PER_SOUL = d.number("armor.fairy-health-per-soul", 1, "Fairy's Outfit: Health per Fairy Soul");

        MINER_SPEED = d.number("armor.miner-speed", 40, "Beginner's Boost: Mining Speed");
        GLACITE_PER_LEVEL = d.number("armor.glacite-per-level", 2, "Expert Miner: Mining Speed per Mining level");
        YOG_PER = d.number("armor.yog-per", 5, "Lavaborn Absorb: lava-beast kills per +1 Mining Speed");
        YOG_CAP = d.number("armor.yog-cap", 500, "Lavaborn Absorb: max Mining Speed");
        LAPIS_XP_PCT = d.number("armor.lapis-xp-percent", 50, "Magnetic: % more experience orbs per Lapis piece while mining");
        SHIMMER_XP_PCT = d.number("armor.shimmer-xp-percent", 50, "Shimmer: % more experience orbs per piece (200% at 4)");
        MINERAL_SPREAD = d.number("armor.mineral-spread", 5, "Mineralworks: Mining Spread per piece (TUNE)");
        GLOSSY_SPREAD = d.number("armor.glossy-spread", 7.5, "Glossy Mineralworks: Mining Spread per piece (TUNE)");
        HEAT_MULT = d.number("armor.heat-multiplier", 3, "Pressurized Coating: Defense/Strength multiplier on mining islands");
        TANK_MINER_MULT = d.number("armor.tank-miner-multiplier", 2, "Depth Coating: Defense multiplier on mining islands");
        HEAT_RESISTANCE = d.number("armor.heat-shield-resistance", 1000, "Heat Shield: Heat Resistance granted (TUNE: 'immune')");
        SORROW_GHOST_MULT = d.number("armor.sorrow-ghost-multiplier", 0.6, "Mist Aura: damage taken from ghosts (2+ pieces)");
        MITHRIL_CAP = d.number("armor.mithril-coat-cap", 0.4, "Mithril's Protection: max fraction of max health one hit can take");

        RARE_CROP_MULT = d.number("armor.rare-crop-multiplier", 1, "Farming armor rare-crop chance multiplier");

        ANGLER_REDUCE = d.number("armor.angler-reduce", 3, "Depth Champion: % less damage from sea creatures per piece");
        ANGLER_HP_PER_LEVEL = d.number("armor.angler-health-per-level", 1.5, "Deepness Within: Health per Fishing level per piece (TUNE)");
        BACKWATER_DMG = d.number("armor.backwater-damage", 5, "Swamp Soldier: % damage to sea creatures per piece");
        DIVER_TREASURE = d.number("armor.diver-treasure", 0.5, "Deep Sea Diver (Diver's): Treasure Chance per piece underwater (TUNE)");
        ABYSSAL_TREASURE = d.number("armor.abyssal-treasure", 1, "Deep Sea Diver (Abyssal): Treasure Chance per piece underwater (TUNE)");
        THUNDER_SECONDS = d.integer("armor.thunder-seconds", 30, "Static Charge: seconds per charge");
        THUNDER_CAP = d.integer("armor.thunder-cap", 2, "Static Charge: max charges");
        THUNDER_PER_CHARGE = d.number("armor.thunder-per-charge", 15, "Static Charge: % damage per charge discharged");
        THUNDER_TAKEN = d.number("armor.thunder-taken", 0.9, "Thunder: damage taken from magmatic mobs");
        THUNDER_DEALT = d.number("armor.thunder-dealt", 1.2, "Thunder: damage dealt to magmatic mobs");
        MAGMA_LORD_TAKEN = d.number("armor.magma-lord-taken", 0.85, "Lord's Blessing: damage taken from magmatic mobs");
        MAGMA_LORD_DEALT = d.number("armor.magma-lord-dealt", 1.3, "Lord's Blessing: damage dealt to magmatic mobs");
        MAGMA_LORD_MF = d.number("armor.magma-lord-mf", 20, "Lord's Blessing: Magic Find on the drops of magmatic creatures (reference 20; through core's per-target loot hook)");
        NUTCRACKER_DEF = d.number("armor.nutcracker-defense", 50, "Battalion: Defense per stack");
        NUTCRACKER_MF = d.number("armor.nutcracker-mf", 1, "Battalion: Magic Find per stack");
        NUTCRACKER_RANGE = d.number("armor.nutcracker-range", 30, "Battalion: radius (blocks)");
        NUTCRACKER_STACKS = d.integer("armor.nutcracker-stacks", 5, "Battalion: max stacks");
        WATER_DEF_MULT = d.number("armor.water-defense-multiplier", 2, "Sponge / Shark Scale: Defense multiplier in water");
        SALMON_MANA = d.number("armor.salmon-mana", 20, "Water Burst: mana cost");
        SALMON_POWER = d.number("armor.salmon-power", 1.6, "Water Burst: dash velocity (TUNE)");

        MYTHIC_EVENT = d.text("armor.mythic-event", "mythological_ritual", "Calendar event id that doubles Mythos/Challenger/Labyrinth Hunter stats in the hub");
        MYTHIC_MULT = d.number("armor.mythic-multiplier", 2, "Mythos' Might: stat multiplier during the event");
        UNEARTHED_PCT = d.number("armor.unearthed-percent", 5, "Unearthed: % less damage from mythic creatures per piece (TUNE)");
        FAMILIARITY_MF = d.number("armor.familiarity-mf", 2.5, "Familiarity: Magic Find per Mythos piece on the drops of mythological creatures (TUNE: the reference lore computes it)");
        GRUNT_ARROW_PCT = d.number("armor.grunt-arrow-percent", 5, "Skeleton Grunt: % arrow damage per piece");
        SPIKY_CHANCE = d.number("armor.spiky-chance", 0.1, "Pufferfish Hat: chance per hit to prick nearby enemies (TUNE)");
        VAMPIRE_DRAIN = d.number("armor.vampire-drain", 5, "Rejuvenate: health drained per second from each monster");
        CREEPER_DAMAGE = d.number("armor.detonate-damage", 500, "Detonate: damage to nearby monsters (TUNE)");
        SPIDER_JUMP_MANA = d.number("armor.double-jump-mana", 50, "Double Jump: mana cost");
        SPRING_MAX_POWER = d.number("armor.spring-max-power", 2.2, "To the Moon!: max launch velocity (TUNE)");
        BONE_SHIELD_MAX = d.integer("armor.bone-shield-max", 3, "Bone Shield: max bones (TUNE)");
        BONE_SHIELD_SECONDS = d.integer("armor.bone-shield-seconds", 30, "Bone Shield: seconds per regrown bone");
        BLOCK_DAMAGE_COOLDOWN = d.integer("armor.block-damage-cooldown", 60, "Block Damage: cooldown seconds");
        DETONATE_COOLDOWN = d.integer("armor.detonate-cooldown", 60, "Detonate: cooldown seconds");

        SIEVE_KNOCKBACK = d.number("armor.sieve-knockback", 0.8, "Sieve Body (Rotten): arrow knockback multiplier (research: 20% less)");
        REKINDLE_PCT = d.number("armor.rekindle-percent", 50, "Rekindle: % more damage per piece against a burning target (research: +200% burning damage at 4/4)");
        REKINDLE_FIRE_PER_SEC = d.number("armor.rekindle-fire-per-second", 1.2, "Rekindle: extra % per second YOU have been on fire");
        REKINDLE_FIRE_CAP = d.number("armor.rekindle-fire-cap", 50, "Rekindle: cap on the on-fire bonus (%)");
        REKINDLE_IGNITE_SECONDS = d.number("armor.rekindle-ignite-seconds", 4, "Rekindle (2+ pieces): seconds a burning mob's melee hit sets you on fire");
        CROWN_GREED_COINS = d.number("armor.crown-greed-coins", 2, "Indulgence (Crown of Greed): coin multiplier on mythic creature kills");
        CROWN_AVARICE_COINS = d.number("armor.crown-avarice-coins", 5, "Overindulgence (Crown of Avarice): coin multiplier on mythic creature kills");
        CROWN_AVARICE_2_COINS = d.number("armor.crown-avarice-celebration-coins", 2, "Overindulgence (celebration crown): coin multiplier on mythic kills");
        BOUNCY_MIN_FALL = d.number("armor.bouncy-min-fall", 4, "Bouncy (Slug Boots): fall distance (blocks) that bounces when you land sneaking");
        BOUNCY_POWER = d.number("armor.bouncy-power", 0.12, "Bouncy: upward velocity per block fallen (TUNE)");
        BREATH_PER_LEVEL = d.number("armor.breath-per-level", 15, "Respiration on armour: extra seconds of breath per level (vanilla Respiration: 15)");
        SNORKEL_LEVELS = d.number("armor.snorkel-levels", 2, "Long Tuba (Snorkeling): Respiration levels per piece (reference lore: +2 Respiration, read per piece like Depth Champion's 3%)");
        BEAT_SAVER_CHANCE = d.number("armor.beat-saver-chance", 1, "Beat Saver (Music Pants): % chance per catch to also reel in a music disc (TUNE)");
        FEARSOME_RANGE = d.number("armor.fearsome-range", 6, "Fearsome (Great Spook): radius in which weak monsters are driven off");
        FEARSOME_PUSH = d.number("armor.fearsome-push", 0.6, "Fearsome: push velocity (TUNE)");
        SHARK_FESTIVAL_SCC = d.number("armor.shark-festival-scc", 1, "Festival Fisher (Shark Scale): Sea Creature Chance per piece while the fishing festival runs, so more sharks bite (TUNE)");
        FISHING_FESTIVAL_EVENT = d.text("armor.fishing-festival-event", "fishing_festival", "Calendar event id of the fishing festival (Festival Fisher)");
        STRONG_EXTRA_RANGE = d.integer("armor.strong-extra-range", 2, "Strong Blood: extra blocks added to the ASPECT_OF_THE_END teleport (research: +2)");
        STARPOWER_RANGE = d.number("armor.starpower-range", 1, "Starpower (Starlight): extra Starfall radius in blocks (research: +1); duration is doubled");

        // ---- wave 5: Young Blood / Newton's Demise raise core's caps for the wearer only
        YOUNG_SPEED_CAP = d.number("armor.young-speed-cap", 100, "Young Blood: Walk Speed cap raised by this much while worn (reference +100)");
        NEWTON_AS_CAP = d.number("armor.newton-attack-speed-cap", 50, "Newton's Demise (Thermodynamic): Attack Speed cap raised by this much (reference +50)");
        BLAZE_PET_ABILITY = d.flag("armor.blaze-pet-ability", true, "Blaze pet 'Bling Armor': its % also raises the Blaze / Frozen Blaze aura (reference: stats AND ability)");
        // ---- wave 5, second pass
        MAGMA_LORD_PIECES = d.integer("armor.magma-lord-blessing-pieces", 5, "Lord's Blessing: Magma Lord pieces needed, the Magma Lord Necklace counting as one (reference 5; 4 when no necklace item is registered)");
        CROWN_GREED_MF = d.number("armor.crown-greed-mf", 10, "Crown of Greed: Magic Find on the drops of mythological creatures (reference 10)");
        PEACE_TREATY = d.flag("armor.peace-treaty", true, "Peace Treaty (Bronze/Silver/Gold/Diamond Hunter, 2+ pieces): sea creatures and you cannot hurt each other, and your Sea Creature Chance is 0");
        PEACE_TREATY_PIECES = d.integer("armor.peace-treaty-pieces", 2, "Peace Treaty: pieces of one Hunter set needed (reference 2)");
        BAT_SWARM_WITCH_DAMAGE = d.number("armor.bat-swarm-witch-damage", 4000, "Bat Swarm (Witch Mask): damage of one exploding bat (reference 4,000)");
        BAT_SWARM_VAMPIRE_DAMAGE = d.number("armor.bat-swarm-vampire-damage", 3000, "Bat Swarm (Vampire Witch Mask): damage of one exploding bat (reference 3,000)");
        BAT_SWARM_RANGE = d.number("armor.bat-swarm-range", 8, "Bat Swarm: how far from you a bat goes for an enemy, in blocks (TUNE)");
        BAT_SWARM_SPEED = d.number("armor.bat-swarm-speed", 0.7, "Bat Swarm: bat flight speed in blocks per tick (TUNE)");
        BAT_SWARM_RESPAWN = d.integer("armor.bat-swarm-respawn", 10, "Bat Swarm: seconds before an exploded bat comes back (TUNE)");
        EMBER_OBSIDIAN = d.flag("armor.ember-obsidian", true, "Nether Lord (Ember, full set): walking over lava turns it to obsidian for a few seconds (never on private islands / the garden, never with a fishing rod in hand)");
        EMBER_OBSIDIAN_SECONDS = d.integer("armor.ember-obsidian-seconds", 5, "Nether Lord: seconds before the obsidian turns back into lava (TUNE)");
        EMBER_OBSIDIAN_RADIUS = d.number("armor.ember-obsidian-radius", 2, "Nether Lord: radius of lava turned to obsidian under you (TUNE, frost-walker-like)");
        SPOOKY_COOLDOWN = d.integer("armor.spooky-cooldown", 60, "Spooker (Great Spook Staff + full Great Spook): seconds between two frights by the same player (reference 1m)");
        SPOOK_SECONDS = d.integer("armor.spook-seconds", 5, "Spook (Great Spook Staff, right click): seconds other players see you as a Dead Bush (TUNE; the cooldown is the item's 15s)");
        // core's own dials, read (same key + default returns core's dial, so /sb dial set on either is the same value)
        CORE_SPEED_CAP = d.number("stats.speed-cap", 400, "Highest Speed stat that still makes you faster (genre cap 400)");
        CORE_AS_CAP = d.number("combat.attack-speed-cap", 100, "Cap on Bonus Attack Speed (research 01: 100).");
        CORE_HIT_TICKS = d.integer("combat.mob-invuln-ticks", 10, "Ticks between two melee hits ONE player can land on one sky mob (vanilla 10). Bonus Attack Speed divides it.");
        CORE_HIT_MIN = d.integer("combat.mob-invuln-min", 2, "Floor for the invulnerability window after attack speed.");

        MAGMATIC_WORDS = d.text("armor.magmatic-words", "magma,lava,flaming,fire,blaze,inferno,moogma,taurus,thunder,fiery,pyro",
                "Comma list: a sea creature whose id contains one of these counts as magmatic (or flag 'magmatic')");
        MYTHIC_WORDS = d.text("armor.mythic-words", "minos,mythological,gaia,siamese,griffin,minotaur,champion,inquisitor,mythic",
                "Comma list: a mob whose id/family contains one of these counts as a mythic creature (or flag 'mythological')");
        GHOST_WORDS = d.text("armor.ghost-words", "ghost", "Comma list: a mob whose id/family contains one of these counts as a ghost");
    }
}
