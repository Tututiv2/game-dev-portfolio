package com.skyclone.core.combat;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.config.Dials.Dial;

/** Every tunable of the combat core. Read {@code .get()} at use time so /sb dial set is live. */
final class CombatDials {

    private CombatDials() {}

    // ---- combat -------------------------------------------------------------------------
    static Dial<Integer> MOB_INVULN_TICKS;
    static Dial<Integer> MOB_INVULN_MIN;
    static Dial<Double> ATTACK_SPEED_CAP;
    static Dial<Double> FEROCITY_CAP;
    static Dial<Integer> FEROCITY_DELAY;
    static Dial<Integer> INDICATOR_MAX;
    static Dial<Integer> INDICATOR_TICKS;
    static Dial<Boolean> PLAYER_INDICATORS;
    static Dial<Boolean> MOB_KNOCKBACK;
    static Dial<Boolean> CANCEL_SWEEP;
    static Dial<Boolean> BOW_FORCE_SCALING;
    static Dial<Boolean> ENFORCE_WEAPON_REQS;
    static Dial<Boolean> PVP_EVERYWHERE;
    static Dial<Boolean> ENV_DEFENSE;
    static Dial<Double> FALL_PCT_PER_BLOCK;
    static Dial<Double> FIRE_PCT;
    static Dial<Double> DROWN_PCT;
    static Dial<Double> SUFFOCATE_PCT;
    static Dial<Double> OTHER_PCT;
    static Dial<Integer> ENV_COOLDOWN;

    // ---- mobs ---------------------------------------------------------------------------
    static Dial<Double> GAUGE_HEALTH;
    static Dial<Double> SHORT_NUM_THRESHOLD;
    static Dial<Integer> KILLER_WINDOW;
    static Dial<Boolean> SPAWNER_ENABLED;
    static Dial<Integer> SPAWN_PER_RULE;
    static Dial<Integer> SPAWN_GLOBAL;
    static Dial<Integer> MAX_ALIVE;
    static Dial<Double> AGGRO_RANGE;
    static Dial<Boolean> COIN_FLASH;
    static Dial<Double> LEASH_RANGE;
    static Dial<Double> LEASH_VERTICAL;
    static Dial<Boolean> SAFE_ZONE_PROTECTS;
    static Dial<Double> RESPAWN_GRACE;

    // ---- loot ---------------------------------------------------------------------------
    static Dial<Double> MF_THRESHOLD;
    static Dial<Double> MF_CAP;
    static Dial<Integer> OWNER_SECONDS;
    static Dial<Integer> FULL_MSG_COOLDOWN;
    static Dial<Boolean> STASH_ENABLED;
    static Dial<Integer> STASH_MAX_ENTRIES;
    static Dial<Integer> STASH_REMINDER_SECONDS;

    static void init() {
        Dials d = Sky.dials();
        MOB_INVULN_TICKS = d.integer("combat.mob-invuln-ticks", 10,
                "Ticks between two melee hits ONE player can land on one sky mob (vanilla 10). Bonus Attack Speed divides it.");
        MOB_INVULN_MIN = d.integer("combat.mob-invuln-min", 2, "Floor for the invulnerability window after attack speed.");
        ATTACK_SPEED_CAP = d.number("combat.attack-speed-cap", 100, "Cap on Bonus Attack Speed (research 01: 100).");
        FEROCITY_CAP = d.number("combat.ferocity-cap", 500, "Cap on Ferocity (research 01: 500 = 5 extra strikes).");
        FEROCITY_DELAY = d.integer("combat.ferocity-delay-ticks", 4, "Ticks between ferocity strikes (0.2s).");
        INDICATOR_MAX = d.integer("combat.indicator-max", 40, "Max damage indicators alive at once (oldest removed first).");
        INDICATOR_TICKS = d.integer("combat.indicator-ticks", 20, "Lifetime of one damage indicator in ticks.");
        PLAYER_INDICATORS = d.flag("combat.player-indicators", false, "Show damage indicators for damage TAKEN by players (PvE).");
        MOB_KNOCKBACK = d.flag("combat.mob-knockback", true,
                "Keep vanilla knockback + hurt flash when a sky mob hits a player (vanilla damage zeroed instead of cancelled).");
        CANCEL_SWEEP = d.flag("combat.cancel-sweep", true, "Cancel vanilla sword sweep damage on sky mobs (the genre has no free sweep).");
        BOW_FORCE_SCALING = d.flag("combat.bow-force-scaling", true, "Scale arrow damage by bow draw (0.1..1).");
        ENFORCE_WEAPON_REQS = d.flag("combat.enforce-weapon-requirements", true,
                "A weapon whose requirements you do not meet hits like a fist.");
        PVP_EVERYWHERE = d.flag("combat.pvp-everywhere", false, "Allow PvP outside PVP zones (testing only).");
        ENV_DEFENSE = d.flag("combat.env-defense", false, "Whether Defense reduces environment damage (fall, fire...).");
        FALL_PCT_PER_BLOCK = d.number("combat.fall-percent-per-block", 5,
                "Fall damage: % of max health per block fallen beyond the safe 3. // TUNE: genre feel, no exact source");
        FIRE_PCT = d.number("combat.fire-percent", 5,
                "Fire/lava/hot floor: % of max health per vanilla damage point (lava = 4 points per hit).");
        DROWN_PCT = d.number("combat.drowning-percent", 5, "Drowning: % of max health per vanilla damage point (2 per hit).");
        SUFFOCATE_PCT = d.number("combat.suffocation-percent", 5, "Suffocation/cramming: % of max health per vanilla point.");
        OTHER_PCT = d.number("combat.environment-percent", 5,
                "Every other environment cause (cactus, poison, wither, explosions...): % of max health per vanilla point.");
        ENV_COOLDOWN = d.integer("combat.environment-cooldown-ticks", 10, "Min ticks between two hits of the same environment cause.");

        GAUGE_HEALTH = d.number("mob.gauge-health", 1024,
                "Vanilla MAX_HEALTH every sky mob is given; the real pool is tracked by core.");
        SHORT_NUM_THRESHOLD = d.number("mob.short-num-threshold", 100000,
                "Nameplate health at or above this is shown as k/M/B.");
        KILLER_WINDOW = d.integer("mob.killer-window-seconds", 15, "A kill is credited to the last player who hit the mob within this many seconds.");
        SPAWNER_ENABLED = d.flag("mob.spawner-enabled", true, "Master switch for every spawn rule.");
        SPAWN_PER_RULE = d.integer("mob.spawn-per-rule-per-second", 4, "Max mobs one spawn rule creates per second.");
        SPAWN_GLOBAL = d.integer("mob.spawn-global-per-second", 60, "Max mobs all spawn rules together create per second.");
        MAX_ALIVE = d.integer("mob.max-alive", 2000, "Spawn rules stop when this many sky mobs are alive server-wide.");
        AGGRO_RANGE = d.number("mob.aggro-range", 16, "Range at which 'aggressive' mobs that are not hostile in vanilla pick a target.");
        COIN_FLASH = d.flag("mob.coin-flash", true, "Show a floating '+X coins' to the killer.");
        LEASH_RANGE = d.number("mob.leash-range", 24,
                "Rule-spawned mobs further than this outside their area (or standing in a SAFE/NO_MOBS zone) are returned to it. 0 = off.");
        LEASH_VERTICAL = d.number("mob.leash-vertical", 8,
                "Rule-spawned mobs more than this many blocks above or below their area box are returned to it, "
                        + "whatever the horizontal distance (the Crypt lies 13 blocks under the Graveyard). 0 = off.");
        SAFE_ZONE_PROTECTS = d.flag("combat.safe-zone-protects", true,
                "Sky mobs never target or hurt a player standing in a SAFE zone (spawn plazas, villages).");
        RESPAWN_GRACE = d.number("combat.respawn-grace-seconds", 3,
                "After a death, mobs cannot hurt the player for this long (stops a mob camping a respawn point).");

        MF_THRESHOLD = d.number("loot.mf-threshold", 0.05, "Magic Find only boosts drops with a base chance below this (genre: 5%).");
        MF_CAP = d.number("loot.mf-cap", 900, "Cap on Magic Find (research 01: 900).");
        OWNER_SECONDS = d.integer("loot.owner-seconds", 60, "Overflow drops can only be picked up by their owner for this long.");
        FULL_MSG_COOLDOWN = d.integer("loot.full-message-cooldown-seconds", 20, "Throttle for 'Your inventory is full!' (3 flooded chat with ~25 lines in 3 minutes of farming, wave-3 playtest).");
        STASH_ENABLED = d.flag("loot.stash-enabled", true,
                "Earned items that do not fit go to the item stash (/pickupstash) instead of the floor.");
        // TUNE: no published cap for the reference stash; 720 kinds (20 double chests) keeps one profile row small.
        STASH_MAX_ENTRIES = d.integer("loot.stash-max-entries", 720,
                "Distinct items the item stash holds; past it overflow drops at the owner's feet again.");
        STASH_REMINDER_SECONDS = d.integer("loot.stash-reminder-seconds", 300,
                "Bulk overflow to the item stash is said in chat once, then again (with the total) at most this often until "
                        + "/pickupstash; in between it is an action-bar flash every loot.full-message-cooldown-seconds "
                        + "(wave4-loops: 17 chat lines in 6 minutes of farming).");
    }
}
