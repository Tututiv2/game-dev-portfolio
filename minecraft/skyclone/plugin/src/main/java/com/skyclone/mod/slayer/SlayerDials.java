package com.skyclone.mod.slayer;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every slayer tunable (CONTRACT 1.3). Read {@code .get()} at use time. */
final class SlayerDials {

    private SlayerDials() {}

    static Dials.Dial<Integer> BOSS_TIME;
    static Dials.Dial<Integer> VAMPIRE_TIME;
    static Dials.Dial<Double> BOSS_HP_SCALE;
    static Dials.Dial<Double> BOSS_DAMAGE_SCALE;
    static Dials.Dial<Double> VAMPIRE_HP_SCALE;
    static Dials.Dial<Double> VAMPIRE_HEART_PERCENT;
    static Dials.Dial<Double> VAMPIRE_COST_PER_MOTE;
    static Dials.Dial<Boolean> VAMPIRE_ANY_MOB;
    static Dials.Dial<Integer> STAKE_REQUIRED_LEVEL;
    static Dials.Dial<Double> SPAWN_XP_SCALE;
    static Dials.Dial<Double> XP_SCALE;
    static Dials.Dial<Double> COST_SCALE;
    static Dials.Dial<Double> MINIBOSS_CHANCE;
    static Dials.Dial<Double> RNG_BOOST;
    static Dials.Dial<Integer> AUTO_SLAYER_LEVEL;
    static Dials.Dial<Boolean> RELOG_FAILS;
    static Dials.Dial<Boolean> DEATH_FAILS;
    static Dials.Dial<Integer> LEASH_RANGE;
    static Dials.Dial<Integer> LEASH_SECONDS;
    static Dials.Dial<Integer> FOLLOW_RANGE;
    static Dials.Dial<Boolean> COOP_HELP;
    static Dials.Dial<Double> LOOTSHARE_PERCENT;
    static Dials.Dial<Boolean> BITS_NEED_COOKIE;
    static Dials.Dial<Integer> HOTLINE_COOLDOWN;
    static Dials.Dial<Integer> SIDEBAR_LINES;
    static Dials.Dial<Boolean> ENTITY_FALLBACK;
    static Dials.Dial<Integer> SPAWN_DELAY_TICKS;
    static Dials.Dial<Integer> SPAWN_MAX_WAIT;
    static Dials.Dial<Double> SPAWN_READY_HEALTH;
    static Dials.Dial<Double> SPAWN_CALM_SECONDS;
    static Dials.Dial<Double> MIXIN_HOURS;
    static Dials.Dial<Boolean> MIXIN_NEEDS_COOKIE;
    static Dials.Dial<Double> MIXIN_PRICE;
    static Dials.Dial<Double> STAKE_PRICE;
    static Dials.Dial<Double> MECHANIC_DAMAGE_SCALE;
    static Dials.Dial<Double> ZOMBIE_DRAIN_SECONDS;
    static Dials.Dial<Boolean> ACTIONBAR;
    static Dials.Dial<Boolean> TELEGRAPH_TITLES;
    static Dials.Dial<Double> GEAR_BULWARK_PER;
    // wave 5
    static Dials.Dial<String> VAMPIRE_FEE_CURRENCY;
    static Dials.Dial<Double> PRIMORDIAL_CHANCE;
    static Dials.Dial<Double> COCOON_HATCH_SECONDS;
    static Dials.Dial<Double> COCOON_HEALTH_PERCENT;
    static Dials.Dial<Integer> COCOON_LIFETIME;
    static Dials.Dial<Double> WOLF_FUR_MAGIC_FIND;
    static Dials.Dial<Boolean> VIVID_NEEDS_SOULFLOW;
    static Dials.Dial<Integer> GEAR_COMBAT_SECONDS;
    static Dials.Dial<Double> VIVID_ENDER_FEROCITY;
    static Dials.Dial<Double> COLDFUSED_BREAK_TRUE_DEFENSE;
    static Dials.Dial<Double> COLDFUSED_BREAK_DAMAGE;
    static Dials.Dial<Integer> COLDFUSED_BREAK_SECONDS;
    static Dials.Dial<Boolean> SHARD_DROPS;
    static Dials.Dial<Boolean> MIXINS_AT_BARTENDER;
    static Dials.Dial<Boolean> HUNTING_ATTRIBUTES;

    static void init() {
        Dials d = Sky.dials();
        BOSS_TIME = d.integer("slayer.boss-time-seconds", 180, "Seconds to kill a slayer boss before the quest fails (research 2.1: 3:00)");
        VAMPIRE_TIME = d.integer("slayer.vampire-time-seconds", 240, "Seconds to kill the Bloodfiend (research 2.1: 4:00)");
        BOSS_HP_SCALE = d.number("slayer.boss-hp-scale", 1.0, "Multiplier on every slayer boss's health (1 = research numbers)");
        BOSS_DAMAGE_SCALE = d.number("slayer.boss-damage-scale", 1.0, "Multiplier on every slayer boss's melee damage");
        VAMPIRE_HP_SCALE = d.number("slayer.vampire-hp-scale", 1000.0, "Overworld health per Rift heart of the Bloodfiend (TUNE: no Rift health scale here)");
        VAMPIRE_HEART_PERCENT = d.number("slayer.vampire-heart-percent", 10.0, "One Rift heart of Bloodfiend damage = this % of your max health (rift module: max HP / 10 base Rift hearts)");
        VAMPIRE_COST_PER_MOTE = d.number("slayer.vampire-cost-per-mote", 1.0, "Coins charged per Mote of a Bloodfiend quest while the fee is paid in coins (no Motes service, or slayer.vampire-fee-currency coins)");
        VAMPIRE_ANY_MOB = d.flag("slayer.vampire-any-mob", false, "While no mob is tagged slayer=vampire, any mob kill fills a Bloodfiend quest");
        STAKE_REQUIRED_LEVEL = d.integer("slayer.stake-required-level", 2, "Vampire level from which a Steak Stake is needed to execute the Bloodfiend");
        SPAWN_XP_SCALE = d.number("slayer.spawn-xp-scale", 1.0, "Multiplier on the combat XP a quest needs before the boss spawns");
        XP_SCALE = d.number("slayer.xp-scale", 1.0, "Multiplier on slayer XP from boss kills");
        COST_SCALE = d.number("slayer.cost-scale", 1.0, "Multiplier on quest prices");
        MINIBOSS_CHANCE = d.number("slayer.miniboss-chance", 0.012, "Chance per quest-mob kill to spawn a miniboss (TUNE: not published)");
        RNG_BOOST = d.number("slayer.rng-boost", 2.0, "RNG meter: the selected drop's weight is x(1 + this * fill); 2 = the reference 1x -> 3x ramp (audit C4)");
        AUTO_SLAYER_LEVEL = d.integer("slayer.auto-slayer-level", 8, "Slayer level that unlocks Auto-Slayer for that type (research 2.13)");
        RELOG_FAILS = d.flag("slayer.relog-fails-quest", true, "Logging out during a boss fight fails the quest (false: the boss can be re-summoned)");
        DEATH_FAILS = d.flag("slayer.death-fails-quest", true, "Dying during a boss fight fails the quest (research 2.1)");
        LEASH_RANGE = d.integer("slayer.leash-range", 48, "Blocks you may stray from your boss before the fail timer starts");
        LEASH_SECONDS = d.integer("slayer.leash-seconds", 10, "Seconds you may stay out of range before the quest fails");
        FOLLOW_RANGE = d.integer("slayer.follow-range", 20, "A boss further than this from its summoner teleports back to them");
        COOP_HELP = d.flag("slayer.coop-can-help", true, "Co-op members may damage your boss (and earn a lootshare)");
        LOOTSHARE_PERCENT = d.number("slayer.lootshare-damage-percent", 10.0, "A helper must deal this % of the boss's health to earn a lootshare roll");
        BITS_NEED_COOKIE = d.flag("slayer.bits-need-cookie", true, "Slayer bits (2-15 per kill) need an active Boon Cookie (research 2.1)");
        HOTLINE_COOLDOWN = d.integer("slayer.hotline-cooldown", 20, "Seconds before the Slayer Hotline picks up again");
        SIDEBAR_LINES = d.integer("slayer.sidebar-lines", 2, "Lines the slayer section may use on the sidebar (1-3)");
        ENTITY_FALLBACK = d.flag("slayer.entity-type-fallback", true, "Count mobs with no slayer tag by their entity type (zombies, spiders...)");
        SPAWN_DELAY_TICKS = d.integer("slayer.spawn-delay-ticks", 40, "Length of the boss summoning animation in ticks");
        SPAWN_MAX_WAIT = d.integer("slayer.spawn-max-wait-seconds", 60, "Bar full: the boss waits up to this long for you to step out of the fight and heal before it rises anyway (0 = rise at once, as the reference)");
        SPAWN_READY_HEALTH = d.number("slayer.spawn-ready-health", 75.0, "Bar full: the boss rises once you are at or above this % of max Health (and calm)");
        SPAWN_CALM_SECONDS = d.number("slayer.spawn-calm-seconds", 3.0, "Bar full: seconds without being hit before the boss rises");
        MIXIN_HOURS =d.number("slayer.mixin-hours", 36.0, "Hours one mixin lasts (research: 36h, stacks)");
        MIXIN_NEEDS_COOKIE = d.flag("slayer.mixin-needs-cookie", true, "Drinking a mixin needs an active Boon Cookie");
        MIXIN_PRICE = d.number("slayer.shop.mixin-price", 150000, "Coin price of a mixin in the Slayer Shop (TUNE)");
        STAKE_PRICE = d.number("slayer.shop.stake-price", 2500, "Coin price of a Steak Stake in the Slayer Shop (TUNE)");
        MECHANIC_DAMAGE_SCALE = d.number("slayer.mechanic-damage-scale", 1.0, "Multiplier on boss ability damage (drains, auras, blasts)");
        ZOMBIE_DRAIN_SECONDS = d.number("slayer.zombie-drain-seconds", 0.5, "Ghoul Life Drain (every 4 s) = this many seconds of the tier's stated damage, Defense applies; carved out of the melee so the stated damage stays the whole bill (TUNE)");
        ACTIONBAR = d.flag("slayer.actionbar", true, "Show quest progress on the action bar");
        TELEGRAPH_TITLES = d.flag("slayer.telegraph-titles", true, "Big on-screen warnings before boss mechanics");
        GEAR_BULWARK_PER = d.number("slayer.gear.bulwark-defense", 20.0, "Defense per bulwark step on slayer armour pieces (research 06)");
        // wave 5 (2026-10-06)
        VAMPIRE_FEE_CURRENCY = d.text("slayer.vampire-fee-currency", "auto", "Bloodfiend quest fee currency: auto (Motes once the rift module provides them, else coins), motes, coins");
        PRIMORDIAL_CHANCE = d.number("slayer.primordial-chance", 10.0, "The Primordial belt: % chance a slain slayer boss or miniboss is cocooned and hatches to be slain again (NEU lore: 10%)");
        COCOON_HATCH_SECONDS = d.number("slayer.cocoon-hatch-seconds", 3.0, "Seconds a cocoon takes to hatch (TUNE: the lore says only 'quickly')");
        COCOON_HEALTH_PERCENT = d.number("slayer.cocoon-health-percent", 100.0, "Health of a hatched boss/miniboss as % of the original (TUNE: not published)");
        COCOON_LIFETIME = d.integer("slayer.cocoon-lifetime-seconds", 120, "A hatched boss nobody kills crumbles after this many seconds");
        WOLF_FUR_MAGIC_FIND = d.number("slayer.wolf-fur-magic-find", 7.0, "Wolf Fur Mixin: Magic Find on monsters slain in one hit (NEU lore: 7)");
        VIVID_NEEDS_SOULFLOW = d.flag("slayer.gear.vivid-needs-soulstream", true, "Last Horizon's Vivid Darkness needs Soulstream in the pool (the soulflow module charges 2 per 5s); off = free");
        GEAR_COMBAT_SECONDS = d.integer("slayer.gear.combat-seconds", 8, "Seconds after the last hit dealt or taken that count as 'in combat' for slayer armour bonuses (TUNE, matches soulflow.combat-seconds)");
        VIVID_ENDER_FEROCITY = d.number("slayer.gear.vivid-ender-ferocity", 200.0, "Vivid Darkness: extra Ferocity against endermen (NEU lore: +200), dealt as extra ferocity strikes");
        COLDFUSED_BREAK_TRUE_DEFENSE = d.number("slayer.coldfused-break-true-defense", 30.0, "Coldfused (with a Wisp pet out): breaking a Fire Pillar grants this much True Defense (research 01: +30)");
        COLDFUSED_BREAK_DAMAGE = d.number("slayer.coldfused-break-damage", 1.15, "Coldfused (with a Wisp pet out): breaking a Fire Pillar multiplies your damage by this (research 01: x1.15)");
        COLDFUSED_BREAK_SECONDS = d.integer("slayer.coldfused-break-seconds", 60, "Seconds the Coldfused fire-pillar bonus lasts (research 01: 60s)");
        SHARD_DROPS = d.flag("slayer.shard-drops", true, "Boss loot includes Hunting shards (Revenant, Paragon, Primordial, Burningsoul) when the hunting module is loaded");
        MIXINS_AT_BARTENDER = d.flag("slayer.mixins-at-bartender", true, "Mixins are sold by the hub Bartender (reference); the Slayer Shop sells them only while no Bartender stands");
        HUNTING_ATTRIBUTES = d.flag("slayer.hunting-attributes", true, "Apply the Hunting attributes Slayer Discount, Slayer Progress (Wolverine) and Cocoon Chance");
    }
}
