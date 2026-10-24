package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every weapons tunable. Read {@code .get()} at use time so {@code /sb dial set} applies live. */
final class WeaponDials {

    private WeaponDials() {}

    static Dials.Dial<Double> ABILITY_DAMAGE;
    static Dials.Dial<Double> CLEAVE_PERCENT;
    static Dials.Dial<Integer> CLEAVE_MAX;
    static Dials.Dial<Double> SHORTBOW_MIN_CD;
    static Dials.Dial<Boolean> SHORTBOW_NEEDS_ARROWS;
    static Dials.Dial<Boolean> BOW_MELEE;
    static Dials.Dial<String> CATACOMBS_REQUIREMENT;
    static Dials.Dial<Double> ARROW_SPEED;
    static Dials.Dial<Double> HOMING_RANGE;
    static Dials.Dial<Double> SPREAD_DEGREES;
    static Dials.Dial<Double> GRAPPLE_COOLDOWN;
    static Dials.Dial<Double> GRAPPLE_STRENGTH;
    static Dials.Dial<Double> RAIDER_COINS;
    static Dials.Dial<Double> EMERALD_COEFF;
    static Dials.Dial<Double> EMERALD_CAP;
    static Dials.Dial<Double> HEAL_CHARGE_SECONDS;
    static Dials.Dial<Double> EXPLOSIVE_RADIUS_SCALE;
    static Dials.Dial<Double> CONE_RANGE;
    static Dials.Dial<Double> HALBERD_RESET_SECONDS;
    static Dials.Dial<Boolean> SLAYER_FALLBACK;
    static Dials.Dial<Boolean> DUNGEON_FALLBACK;
    static Dials.Dial<Boolean> POISONS_ENABLED;
    static Dials.Dial<Double> ROSE_HOP_BONUS;
    static Dials.Dial<Double> RAGNAROCK_BONUS;
    static Dials.Dial<String> SPOOKY_EVENT;
    static Dials.Dial<Boolean> QUIVER_DRAW;
    static Dials.Dial<Double> INFINITE_QUIVER_PER_LEVEL;
    static Dials.Dial<Double> FLAMING_DOT_PERCENT;
    static Dials.Dial<Double> ETHER_RANGE;
    static Dials.Dial<Double> SOUL_LIFETIME;
    static Dials.Dial<Double> SOUL_DAMAGE;
    static Dials.Dial<Double> SOUL_HEALTH;
    // wave 5
    static Dials.Dial<Boolean> SOULFLOW_BEAM;
    static Dials.Dial<Double> SOUL_DROP_CHANCE;
    static Dials.Dial<Boolean> CLASS_ABILITY;
    static Dials.Dial<Double> ADAPTIVE_MAGE_MANA;
    static Dials.Dial<Double> ADAPTIVE_ARCHER_PCT;
    static Dials.Dial<Double> STING_SECONDS;
    static Dials.Dial<Boolean> PET_PERKS;
    static Dials.Dial<Double> ADAPTIVE_HEALER_SHARE;

    static void register() {
        Dials d = Sky.dials();
        ABILITY_DAMAGE = d.number("weapons.ability-damage-multiplier", 1.0,
                "Multiplier on every weapon ability's base damage (1.0 = research numbers)");
        CLEAVE_PERCENT = d.number("weapons.cleave-percent", 25.0,
                "TUNE: % of a melee hit that Cleave splashes onto nearby mobs (reference says only 'a portion')");
        CLEAVE_MAX = d.integer("weapons.cleave-max-targets", 6, "TUNE: max extra mobs one Cleave hit can splash");
        SHORTBOW_MIN_CD = d.number("weapons.shortbow-min-cooldown", 0.1,
                "Floor (seconds) for a shortbow's shot cooldown after Bonus Attack Speed (base / (1 + AS/100), AS capped at 100)");
        SHORTBOW_NEEDS_ARROWS = d.flag("weapons.shortbow-needs-arrows", true, "Shortbows consume an arrow per shot (inventory, then quiver)");
        BOW_MELEE = d.flag("weapons.bow-melee-damage", false,
                "TUNE: punching with a bow uses the bow's Damage stat (false = bows count as fists in melee)");
        CATACOMBS_REQUIREMENT = d.text("weapons.catacombs-requirement", "dungeon",
                "Where a weapon's 'Catacombs Level N' requirement applies: dungeon = only inside a dungeon run (reference), everywhere = all islands, none = never");
        ARROW_SPEED = d.number("weapons.arrow-speed", 3.0, "Launch speed of shortbow arrows (vanilla full draw = 3.0)");
        HOMING_RANGE = d.number("weapons.homing-range", 10.0, "Blocks within which homing arrows pick a target (reference: 10 for dragon tracer)");
        SPREAD_DEGREES = d.number("weapons.multi-arrow-spread", 7.0, "TUNE: degrees between arrows of a multi-arrow shot");
        GRAPPLE_COOLDOWN = d.number("weapons.grappling-cooldown", 2.0, "Grappling Hook cooldown in seconds (reference: 2s)");
        GRAPPLE_STRENGTH = d.number("weapons.grappling-strength", 1.0, "TUNE: Grappling Hook pull multiplier");
        RAIDER_COINS = d.number("weapons.raider-coins-per-kill", 20.0, "Raider Sword bonus coins per monster kill (reference: 20, Combat 10+)");
        EMERALD_COEFF = d.number("weapons.emerald-blade-coefficient", 2.5,
                "TUNE: Emerald Blade bonus damage = coefficient x purse^(1/4) (reference formula as remembered: 2.5)");
        EMERALD_CAP = d.number("weapons.emerald-blade-purse-cap", 2_000_000_000.0, "Purse counted by the Emerald Blade (reference: 2B)");
        HEAL_CHARGE_SECONDS = d.number("weapons.zombie-sword-recharge", 15.0, "TUNE: seconds for one Zombie-sword heal charge to return");
        EXPLOSIVE_RADIUS_SCALE = d.number("weapons.explosion-radius-scale", 1.0, "Scale on every explosive arrow / bow blast radius");
        CONE_RANGE = d.number("weapons.cone-range", 8.0, "TUNE: reach of cone abilities (Wyrm's Fury, Frost Breath)");
        HALBERD_RESET_SECONDS = d.number("weapons.halberd-streak-reset", 3.0, "TUNE: seconds without throwing before the halberd throw streak resets");
        SLAYER_FALLBACK = d.flag("weapons.slayer-fallback-items", true,
                "Register the slayer weapons this module ships when the slayer module did not (needs a restart)");
        DUNGEON_FALLBACK = d.flag("weapons.dungeon-fallback-items", true,
                "Register the dungeon weapons this module ships when the dungeons module did not (needs a restart)");
        POISONS_ENABLED = d.flag("weapons.arrow-poisons", true, "Arrow poisons in the inventory are used up on every shot");
        ROSE_HOP_BONUS = d.number("weapons.rose-hop-bonus", 0.5, "TUNE: extra damage per hop of the rose abilities (0.5 = +50% per foe already hit)");
        RAGNAROCK_BONUS = d.number("weapons.doomcall-strength-multiplier", 1.5,
                "Doomcall multiplies the weapon's Strength by this while active (reference: 1.5x)");
        SPOOKY_EVENT = d.text("weapons.spooky-event-id", "spooky_festival",
                "Calendar event id during which Fright Night (Ghoul Buster) is active");
        QUIVER_DRAW = d.flag("weapons.quiver-arrow", true,
                "Holding a drawn bow with no loose arrows puts a Quiver Arrow in hotbar slot 9 so the bow draws straight from the quiver (reference)");
        INFINITE_QUIVER_PER_LEVEL = d.number("weapons.infinite-quiver-percent-per-level", 3.0,
                "Infinite Quiver: % chance per level that a shot takes no arrow when this module takes the arrow (research 01: 3%, X = 30%)");
        ETHER_RANGE = d.number("weapons.ether-transmission-range", 57.0,
                "Phasewarp (Ether Transmission) reach in blocks before Transmission Tuners (research 06: 57)");
        SOUL_LIFETIME = d.number("weapons.soul-lifetime", 20.0, "TUNE: seconds a freed soul waits on the ground to be absorbed (Raise Souls)");
        SOUL_DAMAGE = d.number("weapons.soul-damage-multiplier", 1.0,
                "TUNE: a raised soul hits once a second for its monster's Damage stat times this");
        SOUL_HEALTH = d.number("weapons.soul-health", 40.0, "TUNE: vanilla health of a raised soul (only stray blows and the environment hurt it)");
        SOULFLOW_BEAM = d.flag("weapons.soulflow-judgement-beam", true,
                "Judgement Beam needs 1 Soulstream (reference Soulflow); short = the click is a normal shot. The soulflow module charges it");
        SOUL_DROP_CHANCE = d.number("weapons.soul-drop-chance", 100.0,
                "% chance a kill with a Raise Souls blade drops its soul (reference: always); the Ghoul pet's '+a% more often' multiplies it");
        CLASS_ABILITY = d.flag("weapons.class-ability-weapons", true,
                "Right-clicking a class-ability weapon (Highlord & co, Earthen/Adaptive Blade, Fel Sword) inside a dungeon uses your class ability");
        ADAPTIVE_MAGE_MANA = d.number("weapons.adaptive-mage-mana-percent", 25.0,
                "Adaptive Blade (Mage): extra mana per dungeon melee hit, as % of the 5 + 1% max mana a melee hit restores (reference: 25%)");
        ADAPTIVE_ARCHER_PCT = d.number("weapons.adaptive-archer-percent", 10.0,
                "Adaptive Blade (Archer): a melee hit makes the foe take this % more from your arrows for 5s (reference: 10%)");
        STING_SECONDS = d.number("weapons.sting-seconds", 30.0, "Stinger Bow: seconds of +20 Speed / +30 Strength on a teammate you shoot (reference: 30)");
        ADAPTIVE_HEALER_SHARE = d.number("weapons.adaptive-healer-share-percent", 33.0,
                "Adaptive Blade (Healer): % of your life-steal healing shared with party members within 10 blocks in a dungeon (reference: 33%)");
        PET_PERKS = d.flag("weapons.pet-perks", true,
                "Weapon-side pet perks: Ghoul (souls, raised souls' cost/damage/health), Eerie (Fear damage vs spooky, Fearcreasing)");
        FLAMING_DOT_PERCENT = d.number("weapons.ignite-percent-per-second", 3.0,
                "TUNE: Flaming Sword burn = this % of the hit's damage every second while alight (Fire Aspect I rate)");
    }
}
