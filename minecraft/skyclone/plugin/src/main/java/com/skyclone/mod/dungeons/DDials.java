package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every dungeons tunable (read at use time). */
public final class DDials {

    private DDials() {}

    public static Dials.Dial<Integer> MAX_RUNS, PARTY_MAX, COUNTDOWN, CLAIM_SECONDS, FAIL_CLOSE_SECONDS, TIMEOUT_MINUTES,
            INVITE_SECONDS, PLACE_BUDGET_MS, SKILL_FLOOR, DAILY_BONUS_RUNS, BLOOD_DELAY, REVIVE_SECONDS, GHOST_REVIVE_SECONDS,
            CATA_CAP, PUZZLE_FAIL_PENALTY;
    public static Dials.Dial<Double> XP_SCALE, CLASS_OTHER_SHARE, MOB_HP_SCALE, MOB_DMG_SCALE, BOSS_HP_SCALE, STAT_BOOST_SCALE,
            STAR_BOOST, MASTER_STAR_BOOST, SPEED_FACTOR, FAIL_XP, MIN_SCORE_FACTOR, MANA_ON_HIT, SECRET_CHANCE_SCALE,
            BLESSING_SCALE, ABILITY_SCALE, PRINCE_CHANCE, DUPLICATE_CLASS_SCALE, SOUL_TETHER_RANGE;
    public static Dials.Dial<Boolean> REQUIRE_HUB, SOLO_ALLOWED, IGNORE_REQS, DUNGEON_ONLY_ITEMS, ANNOUNCE_SECRETS, MIMIC;
    public static Dials.Dial<String> ENTRANCE_REQ, WATCHER_NAME;

    // ---- part B (bosses and loot) ----------------------------------------------------------------
    public static Dials.Dial<Double> BOSS_FIGHT_HP, BOSS_FIGHT_DMG, BOSS_MELEE_SHARE, SOLO_BOSS_DMG, SOLO_BOSS_HP, ADD_MELEE_SHARE, ARRIVAL_GRACE, CHEST_COST_SCALE, RNG_SCALE, ESSENCE_SCALE, BOSS_LUCK_STEP,
            SALVAGE_SCALE, WITHERBORN_DAMAGE, SCROLL_DAMAGE, KISMET_PRICE, CHEST_KEY_PRICE;
    public static Dials.Dial<Integer> RNG_MIN_SCORE, CROESUS_DAYS, CROESUS_MAX, CLAIM_SECONDS_B, THORN_HITS, THORN_HITS_MASTER,
            SPIRIT_KILLS, SADAN_TERRACOTTA_SECONDS, STORM_CRUSHES, LURID_DELAY;
    public static Dials.Dial<Boolean> REAL_BOSSES, REAL_CHESTS, ESSENCE_AS_ITEMS_DEFAULT, MORT_SHOP_KEYS;

    /** Damage defaults, named so BalanceTest checks the numbers that actually ship (TUNE). */
    public static final double DEF_MOB_DMG = 1.0, DEF_BOSS_DMG = 1.0, DEF_BOSS_MELEE_SHARE = 0.25;
    /**
     * wave-4 endgame fix: boss floors are sized for a party of five. With fewer members the boss floor's health and
     * damage scale from these solo values (1 member) up to 1.0 (5 members), see {@code model.Balance.partyScale}; the
     * adds' contact hits carry a melee share like the boss body's does; and nothing scripted lands in the first
     * seconds after arriving through the portal. BalanceTest checks the solo arrival and the solo kill time.
     */
    public static final double DEF_SOLO_BOSS_HP = 0.3, DEF_SOLO_BOSS_DMG = 0.4, DEF_ADD_MELEE_SHARE = 0.25, DEF_ARRIVAL_GRACE = 3.0;

    static void init() {
        Dials d = Sky.dials();
        MAX_RUNS = d.integer("dungeons.max-runs", 8, "Most dungeon runs that may exist at once");
        PARTY_MAX = d.integer("dungeons.party-max", 5, "Largest party (research 04 sec 3.1: 1-5)");
        COUNTDOWN = d.integer("dungeons.countdown-seconds", 5, "Ready countdown in the start room");
        CLAIM_SECONDS = d.integer("dungeons.claim-seconds", 90, "Seconds a completed run stays open before everyone is sent back");
        FAIL_CLOSE_SECONDS = d.integer("dungeons.fail-close-seconds", 10, "Seconds a failed run stays open");
        TIMEOUT_MINUTES = d.integer("dungeons.timeout-minutes", 45, "Hard time limit of a run (research 6.3: ~45 min (?))");
        INVITE_SECONDS = d.integer("dungeons.invite-seconds", 60, "Party invites expire after this many seconds");
        PLACE_BUDGET_MS = d.integer("dungeons.place-budget-ms", 8, "Milliseconds per tick spent placing dungeon blocks (all runs)");
        SKILL_FLOOR = d.integer("dungeons.skill-score-floor", 20, "Lowest possible Skill score (TUNE: genre minimum 20)");
        DAILY_BONUS_RUNS = d.integer("dungeons.daily-bonus-runs", 5, "Completions per real day that get +40% XP");
        BLOOD_DELAY = d.integer("dungeons.blood-delay-seconds", 4, "Seconds after the blood door opens before the first blood mob");
        REVIVE_SECONDS = d.integer("dungeons.healer-revive-seconds", 100, "Healer passive revive delay at class level 0 (-1s per level)");
        GHOST_REVIVE_SECONDS = d.integer("dungeons.ghost-self-revive-seconds", 50, "Healer ghost self-revive delay");
        CATA_CAP = d.integer("dungeons.catacombs-cap", 50, "Highest Catacombs/class level counted (overflow levels above 50 cost 200M each)");
        PUZZLE_FAIL_PENALTY = d.integer("dungeons.puzzle-fail-penalty", 14, "Skill score lost per failed/unfinished puzzle (research: 14)");
        XP_SCALE = d.number("dungeons.xp-scale", 1.0, "Multiplier on Catacombs and class XP per run");
        CLASS_OTHER_SHARE = d.number("dungeons.class-other-share", 0.25, "Share of class XP the four unselected classes earn (research (?): 25%)");
        MOB_HP_SCALE = d.number("dungeons.mob-hp-scale", 1.0, "Extra multiplier on every dungeon mob's health (TUNE)");
        MOB_DMG_SCALE = d.number("dungeons.mob-damage-scale", DEF_MOB_DMG, "Extra multiplier on every dungeon mob's damage (TUNE)");
        BOSS_HP_SCALE = d.number("dungeons.fallback-boss-hp-scale", 1.0, "Multiplier on the fallback boss's research HP");
        STAT_BOOST_SCALE = d.number("dungeons.stat-boost-scale", 1.0, "Multiplier on the Catacombs-level stat boost of dungeon items");
        STAR_BOOST = d.number("dungeons.star-boost", 0.10, "Stat boost per essence star inside the Catacombs (research: 10%; 2% of it applies outside)");
        MASTER_STAR_BOOST = d.number("dungeons.master-star-boost", 0.05, "Stat boost per master star inside the Catacombs (research: 5%)");
        SPEED_FACTOR = d.number("dungeons.speed-above-100", 0.67, "Speed above 100 is multiplied by this inside the Catacombs (research: 0.67)");
        FAIL_XP = d.number("dungeons.fail-xp-share", 0.1, "Share of the run XP a failed run still pays (research (?))");
        MIN_SCORE_FACTOR = d.number("dungeons.min-score-xp-factor", 0.1, "Least share of run XP a completed run pays at very low score");
        MANA_ON_HIT = d.number("dungeons.mana-on-hit", 5, "Flat mana restored by each melee hit in a run (+1% of max; research 3.5)");
        SECRET_CHANCE_SCALE = d.number("dungeons.secret-reward-scale", 1.0, "Multiplier on secret reward amounts");
        BLESSING_SCALE = d.number("dungeons.blessing-scale", 1.0, "Multiplier on blessing stats");
        ABILITY_SCALE = d.number("dungeons.class-ability-scale", 1.0, "Multiplier on class ability damage (TUNE)");
        REQUIRE_HUB = d.flag("dungeons.require-hub", true, "Party members must stand in the Dungeon Hub when a run starts");
        SOLO_ALLOWED = d.flag("dungeons.solo", true, "Allow 1-player runs (the reference gates these behind an item; free here)");
        IGNORE_REQS = d.flag("dungeons.ignore-requirements", false, "TESTING: skip floor requirements");
        DUNGEON_ONLY_ITEMS = d.flag("dungeons.remove-run-items", true, "Revive Stones vanish when a run ends");
        ANNOUNCE_SECRETS = d.flag("dungeons.announce-secrets", true, "Tell the party when a secret is found");
        PRINCE_CHANCE = d.number("dungeons.prince-chance", 0.02, "Chance each room mob is a Prince: x3 HP, glowing, worth +1 bonus score once per run (TUNE)");
        DUPLICATE_CLASS_SCALE = d.number("dungeons.duplicate-class-scale", 0.5, "Class passives/abilities of a class that two or more run members share are scaled by this (duplicates are penalised; 1 = off, TUNE)");
        SOUL_TETHER_RANGE = d.number("dungeons.soul-tether-range", 30.0, "Healer Soul Tether reach in blocks (research 04 sec 3.4: 30)");
        MIMIC = d.flag("dungeons.mimic", true, "On floors 6-7 one chest secret is a Mimic (+2 bonus score)");
        ENTRANCE_REQ = d.text("dungeons.entrance-requirement", "skill:combat:15", "Requirement to enter the Catacombs at all (research: Combat 15)");
        WATCHER_NAME = d.text("dungeons.watcher-name", "The Onlooker", "Name of the blood room's watcher");
        // part B
        BOSS_FIGHT_HP = d.number("dungeons.boss-hp-scale", 1.0, "Multiplier on every boss-floor boss and add's research health");
        BOSS_FIGHT_DMG = d.number("dungeons.boss-damage-scale", DEF_BOSS_DMG, "Multiplier on every boss-floor attack: scripted ones (skulls, lasers, stomps...), the boss body's own hits and its adds' hits");
        BOSS_MELEE_SHARE = d.number("dungeons.boss-melee-share", DEF_BOSS_MELEE_SHARE, "A boss body's contact hit as a share of the floor's headline boss damage (TUNE: 1 = research number per swing, which killed a 588 HP / 630 Defense player in two swings on F1)");
        SOLO_BOSS_DMG = d.number("dungeons.solo-boss-damage-scale", DEF_SOLO_BOSS_DMG, "Boss-floor damage (scripted, body, adds) with ONE run member; scales linearly to 1.0 at a party of 5 (TUNE: 1 = faithful party content, which killed a solo Superior Dragon player on F2 in ~10 s)");
        SOLO_BOSS_HP = d.number("dungeons.solo-boss-hp-scale", DEF_SOLO_BOSS_HP, "Boss-floor health (bosses and adds, read when each spawns) with ONE run member; scales linearly to 1.0 at a party of 5 (TUNE: 1 = party-sized health)");
        ADD_MELEE_SHARE = d.number("dungeons.boss-add-melee-share", DEF_ADD_MELEE_SHARE, "A boss add's contact hit (Circus Undead, students, guardians...) as a share of its research damage, like boss-melee-share for the body; their scripted attacks keep the full number (TUNE: 1 = research number per swing)");
        ARRIVAL_GRACE = d.number("dungeons.boss-arrival-grace-seconds", DEF_ARRIVAL_GRACE, "Seconds after stepping through the boss portal during which boss-floor attacks do not land on that player (0 = off)");
        CHEST_COST_SCALE = d.number("dungeons.chest-cost-scale", 1.0, "Multiplier on reward chest coin costs (research 04 sec 3.14)");
        RNG_SCALE = d.number("dungeons.rng-meter-scale", 1.0, "Multiplier on RNG meter XP gained per run");
        ESSENCE_SCALE = d.number("dungeons.chest-essence-scale", 1.0, "Multiplier on reward chest essence");
        BOSS_LUCK_STEP = d.number("dungeons.boss-luck-weight", 0.02, "Per point of chest luck, rare loot weights rise by this fraction (TUNE)");
        SALVAGE_SCALE = d.number("dungeons.salvage-scale", 1.0, "Multiplier on essence returned by salvaging dungeon gear");
        WITHERBORN_DAMAGE = d.number("dungeons.witherborn-damage", 20000, "Base magic damage of a Witherborn imp (TUNE)");
        SCROLL_DAMAGE = d.number("dungeons.scroll-damage", 10000, "Base magic damage of Implosion (research: 10,000 x 0.3 Int scaling)");
        KISMET_PRICE = d.number("dungeons.kismet-price", 1_000_000, "Gatekeeper price of a Fate Feather (TUNE: a bazaar product in the reference)");
        CHEST_KEY_PRICE = d.number("dungeons.chest-key-price", 600_000, "Gatekeeper price of a Dungeon Chest Key (TUNE: a bazaar product in the reference)");
        RNG_MIN_SCORE = d.integer("dungeons.rng-min-score", 270, "Lowest run score that fills the RNG meter (research (?): S)");
        CROESUS_DAYS = d.integer("dungeons.croesus-days", 3, "Days unclaimed reward chests wait at the vault keeper");
        CROESUS_MAX = d.integer("dungeons.croesus-max", 60, "Most unclaimed runs the vault keeper holds per player (oldest dropped)");
        CLAIM_SECONDS_B = d.integer("dungeons.chest-claim-seconds", 120, "Seconds the boss room stays open for reward chests");
        THORN_HITS = d.integer("dungeons.f4-spirit-hits", 4, "Spirit Bow hits that kill Bramblemaw (research: 4)");
        THORN_HITS_MASTER = d.integer("dungeons.m4-spirit-hits", 6, "Spirit Bow hits on Master Mode (research: 6)");
        SPIRIT_KILLS = d.integer("dungeons.f4-spirit-kills", 25, "Spirit animals to kill before the Spirit Bear (research: 25, M4 30)");
        SADAN_TERRACOTTA_SECONDS = d.integer("dungeons.f6-terracotta-seconds", 120, "Length of Gorrak's terracotta phase (research: 2:00)");
        STORM_CRUSHES = d.integer("dungeons.f7-storm-crushes", 2, "Crushes Tempestra must take before she can die (research: 2)");
        LURID_DELAY = d.integer("dungeons.f5-clone-seconds", 16, "Seconds before Lurid splits into copies (research: 16)");
        REAL_BOSSES = d.flag("dungeons.real-bosses", true, "Use the scripted boss fights (off = the framework's stand-in boss)");
        REAL_CHESTS = d.flag("dungeons.real-chests", true, "Use reward chests (off = the free essence fallback)");
        ESSENCE_AS_ITEMS_DEFAULT = d.flag("dungeons.essence-as-items-default", false, "New players take chest essence as items (spendable in the essence shop)");
        MORT_SHOP_KEYS = d.flag("dungeons.gatekeeper-sells-keys", true, "The Gatekeeper sells Fate Feathers and Chest Keys for coins");

        // ---- wave 5 (2026-10-06) ----
        REQUIRE_PREVIOUS_FLOOR = d.flag("dungeons.require-previous-floor", true, "Floors II-VII need the floor below completed, Master II-VII the Master floor below (reference floor gate)");
        MUTANT_GIANT_HP = d.number("dungeons.m6-mutant-giant-hp", 600_000_000, "HP of each M6 Mutant Giant that rises beside Gorrak in his last phase (research: 600M)");
        MUTANT_GIANT_SECONDS = d.integer("dungeons.m6-mutant-giant-seconds", 30, "Seconds before a slain M6 Mutant Giant rises again beside Gorrak (TUNE)");
        KING_ADD_SECONDS = d.integer("dungeons.m7-king-add-seconds", 40, "Seconds between the Hollow King's Wither Husk / Apostle adds in M7 phase 5 (TUNE)");
        MASTER_LORD_DEFENSE = d.flag("dungeons.m7-lord-defense", true, "Master Mode Tempestra / Aurik / Morvane / dragons carry their research Defense (1,200 / 1,800 / 2,100 / 2,500)");

        // ---- wave-5 regression fixer: solo rooms ----
        SOLO_ROOM_HP = d.number("dungeons.solo-room-hp-scale", DEF_SOLO_ROOM_HP, "Room-side mob health (room mobs, minibosses, blood mobs, crypt undead, Mimic; read when each spawns) with ONE run member; scales linearly to 1.0 at a party of 5, like solo-boss-hp-scale (TUNE: 1 = the party-sized numbers that killed a solo Superior Dragon + Undead Sword player in F1 room 2)");
        SOLO_ROOM_DMG = d.number("dungeons.solo-room-damage-scale", DEF_SOLO_ROOM_DMG, "Room-side mob damage (read per hit) with ONE run member; scales linearly to 1.0 at a party of 5 (TUNE: 1 = party-sized)");
        ROOM_AGGRO = d.number("dungeons.room-aggro-range", DEF_ROOM_AGGRO, "Blocks at which a room mob or miniboss notices a player (its follow range). Vanilla's 35 pulled every mob of a 2-3 cell room (cells are 32 wide) onto the player at once; the reference's room mobs engage in packs as you walk in (TUNE; 0 = leave vanilla's range)");
    }

    /**
     * wave-5 regression: a solo Superior Dragon + Undead Sword (Smite V) player died to F1 room 2. Every mob of the room
     * aggroed at once (vanilla follow range 35 over 32-block cells) and the room's numbers are sized for a party. See
     * {@code BalanceTest.f1RoomPacksAreSurvivableSolo}: an average F1 pack costs the solo player ~21% of his health now (worst pack ~42%)
     * (the whole 2-cell room at party numbers cost ~2.9x his health before).
     */
    public static final double DEF_SOLO_ROOM_HP = 0.5, DEF_SOLO_ROOM_DMG = 0.5, DEF_ROOM_AGGRO = 12;
    public static Dials.Dial<Double> SOLO_ROOM_HP, SOLO_ROOM_DMG, ROOM_AGGRO;

    public static Dials.Dial<Boolean> REQUIRE_PREVIOUS_FLOOR, MASTER_LORD_DEFENSE;
    public static Dials.Dial<Double> MUTANT_GIANT_HP;
    public static Dials.Dial<Integer> MUTANT_GIANT_SECONDS, KING_ADD_SECONDS;
}
