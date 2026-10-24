package com.skyclone.mod.alchemy;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every alchemy tunable. Read {@code .get()} at use time so {@code /sb dial set} takes effect live. */
final class AlchemyDials {

    private AlchemyDials() {}

    static Dials.Dial<Integer> BREW_SECONDS;
    static Dials.Dial<Boolean> BREW_ANYWHERE;
    static Dials.Dial<Double> BREWER_PCT;
    static Dials.Dial<Double> AFFINITY_TALISMAN;
    static Dials.Dial<Double> AFFINITY_RING;
    static Dials.Dial<Double> AFFINITY_ARTIFACT;
    static Dials.Dial<Double> PARROT_PCT_PER_LEVEL;
    static Dials.Dial<Double> GOD_BASE_HOURS;
    static Dials.Dial<Double> GOD_ALCHEMY_HOURS;
    static Dials.Dial<Double> GOD_LEGACY_HOURS;
    static Dials.Dial<Double> SPLASH_RADIUS;
    static Dials.Dial<Double> SPLASH_RADIUS_WIDE;
    static Dials.Dial<Double> XP_MULT;
    static Dials.Dial<Boolean> NPC_ENABLED;
    static Dials.Dial<Boolean> DUNGEON_ONLY;
    static Dials.Dial<Boolean> PAUSE_IN_DUNGEONS;
    static Dials.Dial<Boolean> TICK_OFFLINE;
    static Dials.Dial<Boolean> TAB_WIDGET;
    static Dials.Dial<Integer> BAG_FALLBACK_SLOTS;
    static Dials.Dial<Boolean> SPLASH_SHARE;
    static Dials.Dial<Double> SPLASH_DOT_CAP;
    static Dials.Dial<Integer> JYRRE_MINUTES_PER_INT;
    static Dials.Dial<Integer> JYRRE_REFINED_MAX;
    static Dials.Dial<String> RECALL_ISLAND;
    static Dials.Dial<Boolean> ISLAND_STANDS_SHARED;

    static void register() {
        Dials d = Sky.dials();
        BREW_SECONDS = d.integer("alchemy.brew-seconds", 20,
                "Seconds one brewing step takes (research 08: vanilla 20s).");
        BREW_ANYWHERE = d.flag("alchemy.brew-anywhere", true,
                "/brew opens your personal brewing stand anywhere (false: only by clicking a brewing stand or the Alchemist).");
        BREWER_PCT = d.number("alchemy.brewer-pct-per-level", 1.0,
                "Brewer perk: % longer duration on potions you brew, per Alchemy level (research 02: +1%/level).");
        AFFINITY_TALISMAN = d.number("alchemy.affinity-talisman-pct", 10,
                "Potion Affinity Talisman: % longer duration on potions you drink.");
        AFFINITY_RING = d.number("alchemy.affinity-ring-pct", 25,
                "Potion Affinity Ring: % longer duration (TUNE: research 08 marks it (?)).");
        AFFINITY_ARTIFACT = d.number("alchemy.affinity-artifact-pct", 50,
                "Potion Affinity Artifact: % longer duration (TUNE: research 08 marks it (?)).");
        PARROT_PCT_PER_LEVEL = d.number("alchemy.parrot-pct-per-level", 0.2,
                "Parrot pet: % longer potion duration per pet level (God Potion lore shows x1.2 at level 100).");
        GOD_BASE_HOURS = d.number("alchemy.god-base-hours", 12,
                "God Potion: base hours per potion (reference lore: +12h default).");
        GOD_ALCHEMY_HOURS = d.number("alchemy.god-alchemy-hours", 12,
                "God Potion: extra hours at Alchemy 50, scaled linearly by level (reference lore: +12h Alchemy Level).");
        GOD_LEGACY_HOURS = d.number("alchemy.god-legacy-hours", 24,
                "Legacy God Potion: flat hours per potion (reference lore: 24 hours).");
        SPLASH_RADIUS = d.number("alchemy.splash-radius", 4,
                "Blocks a gunpowder splash potion reaches (research 08: ~4, vanilla).");
        SPLASH_RADIUS_WIDE = d.number("alchemy.splash-radius-wide", 6,
                "Blocks an enchanted-gunpowder splash reaches (TUNE: research says 'increased radius (?)').");
        XP_MULT = d.number("alchemy.xp-multiplier", 1.0, "Multiplier on Alchemy XP from brewing.");
        NPC_ENABLED = d.flag("alchemy.npc-enabled", true,
                "Spawn the Alchemist NPC at hub npc.alchemist (turn off if the shops module provides one). Needs a restart.");
        DUNGEON_ONLY = d.flag("alchemy.dungeon-potions-dungeon-only", true,
                "Dungeon potions can only be drunk inside a dungeon (ignored while no dungeons module is installed).");
        PAUSE_IN_DUNGEONS = d.flag("alchemy.pause-in-dungeons", true,
                "Normal potion effects pause while you are inside a dungeon (research 08).");
        TICK_OFFLINE = d.flag("alchemy.tick-offline", false,
                "Effects also run down while you are offline (reference: they only tick while online).");
        TAB_WIDGET = d.flag("alchemy.tab-widget", true, "Show the Active Effects widget in the tab list.");
        BAG_FALLBACK_SLOTS = d.integer("alchemy.bag-fallback-slots", 9,
                "Potion Bag slots when no collections module is installed (normally Nether Wart collection decides).");
        SPLASH_SHARE = d.flag("alchemy.splash-share", true,
                "Helpful splash potions also apply to other players in range (dungeon 'pot sharing').");
        SPLASH_DOT_CAP = d.number("alchemy.splash-dot-cap-seconds", 30,
                "Longest a splashed Poison/Venomous keeps hurting a mob (seconds).");
        JYRRE_MINUTES_PER_INT = d.integer("alchemy.jyrre-minutes-per-int", 10,
                "Midnight Wit bottles: minutes in your inventory per +1 Intelligence (TUNE: the research gives no rate; 10 = 300 in 50 h).");
        JYRRE_REFINED_MAX = d.integer("alchemy.jyrre-refined-max", 5,
                "Refined Midnight Wit: how many bottles give +1 Intelligence for good (reference lore: 5).");
        RECALL_ISLAND = d.text("alchemy.recall-island", "crystal_hollows",
                "Island id where the Recall Potion saves and returns (reference: the Crystal Hollows).");
        ISLAND_STANDS_SHARED = d.flag("alchemy.island-stands-shared", true,
                "Brewing stands on your own island hold one shared brew per block for the whole co-op (false: per player, like public stands).");
    }
}
