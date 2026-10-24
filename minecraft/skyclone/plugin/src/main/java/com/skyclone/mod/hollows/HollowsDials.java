package com.skyclone.mod.hollows;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every tunable of the hollows module. Read with {@code .get()} at use time. */
final class HollowsDials {

    final Dials.Dial<Integer> lobby;
    final Dials.Dial<String> cavernsRequirement;
    final Dials.Dial<String> tunnelsRequirement;
    final Dials.Dial<Double> magmawHitDamage;
    final Dials.Dial<Integer> magmawRespawn;
    final Dials.Dial<Integer> magmawSummonSeconds;
    final Dials.Dial<Integer> magmawSummonCap;
    final Dials.Dial<Integer> gangBossRespawn;
    final Dials.Dial<Double> thystChance;
    final Dials.Dial<Integer> odalaSludge;
    final Dials.Dial<Integer> templeRetryMinutes;
    final Dials.Dial<Integer> guardianPowder;
    final Dials.Dial<Double> campfirePrice;
    final Dials.Dial<Integer> keyMaterials;
    final Dials.Dial<Double> keyCoins;
    final Dials.Dial<Integer> sidebarPriority;
    final Dials.Dial<Integer> queenRespawn;
    final Dials.Dial<Integer> queenGuards;
    final Dials.Dial<Boolean> queenGivesAmber;
    final Dials.Dial<Boolean> kingStealth;
    final Dials.Dial<Integer> kingSpottedSeconds;
    final Dials.Dial<Double> kingSightRange;
    final Dials.Dial<Boolean> templeTraps;
    final Dials.Dial<Integer> templeTrapBeats;
    final Dials.Dial<Double> templeDartDamage;
    // wave 5
    final Dials.Dial<Boolean> lobbyRotation;
    final Dials.Dial<Integer> lobbyCloseDay;
    final Dials.Dial<Integer> lobbyShutdownDay;
    final Dials.Dial<Integer> lobbyCloseBelow;
    final Dials.Dial<Boolean> lobbyLockNewcomers;
    final Dials.Dial<Integer> lobbyCountdown;
    final Dials.Dial<Boolean> templeParkour;
    final Dials.Dial<Integer> treasureHits;
    final Dials.Dial<Integer> treasureReseal;
    final Dials.Dial<Boolean> depthsGuardians;
    final Dials.Dial<Boolean> kalhuikiMask;
    final Dials.Dial<Boolean> passRequired;
    final Dials.Dial<Double> passPrice;
    final Dials.Dial<Integer> passHours;
    final Dials.Dial<Boolean> railPassRequired;

    HollowsDials() {
        var d = Sky.dials();
        lobby = d.integer("hollows.lobby", 0, "Crystal Caverns lobby number: mixed into the cave seed; /sb hollows newlobby raises it and rebuilds");
        cavernsRequirement = d.text("hollows.caverns-requirement", "skill:mining:12 & hotm:4", "Requirement to enter the Crystal Caverns (research: Mining XII + HOTM 4)");
        tunnelsRequirement = d.text("hollows.tunnels-requirement", "hotm:7", "Requirement to enter the Frozen Tunnels (research: HOTM 7)");
        magmawHitDamage = d.number("hollows.magmaw-hit-damage", 1.0, "Damage every player hit deals to Magmaw (research: 1 per hit, 200 HP)");
        magmawRespawn = d.integer("hollows.magmaw-respawn-seconds", 180, "Seconds before Magmaw rises again after a kill (TUNE: the reference spawns it on a lobby timer)");
        magmawSummonSeconds = d.integer("hollows.magmaw-summon-seconds", 20, "Seconds between Magmaw's blaze summons (TUNE)");
        magmawSummonCap = d.integer("hollows.magmaw-summon-cap", 6, "Most summoned blazes alive at once (TUNE)");
        gangBossRespawn = d.integer("hollows.gang-boss-respawn-seconds", 900, "Seconds before the Gang Boss returns to the hideout (TUNE: rare in the reference)");
        thystChance = d.number("hollows.thyst-chance", 0.02, "Chance that mining Amethyst wakes an Amethyst Leech (TUNE)");
        odalaSludge = d.integer("hollows.odala-sludge", 100, "Sludge Juice Odala wants for one Jungle Key (NEU ODAWA_NPC shop: 100)");
        templeRetryMinutes = d.integer("hollows.temple-retry-minutes", 10, "Minutes a used Jungle Key stays paid if the Door Guardian vanishes");
        guardianPowder = d.integer("hollows.guardian-powder", 50, "Rimestone powder per frozen guardian kill (research: 50 Glacite Powder)");
        campfirePrice = d.number("hollows.campfire-price", 5000, "Coins for a Portable Campfire at the Quartermaster (TUNE)");
        keyMaterials = d.integer("hollows.key-materials", 64, "Umber/Tungsten the Quartermaster wants for a corpse key (TUNE)");
        keyCoins = d.number("hollows.key-coins", 2500, "Coins the Quartermaster wants for a corpse key (TUNE)");
        queenRespawn = d.integer("hollows.goblin-queen-respawn-seconds", 300, "Seconds before the Goblin Queen returns to her den after a kill (TUNE, restart to apply)");
        queenGuards = d.integer("hollows.goblin-queen-guards", 3, "Brood Guards the Goblin Queen calls at 75/50/25% health (TUNE)");
        queenGivesAmber = d.flag("hollows.goblin-queen-amber", true, "Killing the Goblin Queen gives every participant the Amber crystal (research 03); off = only an egg for the King");
        kingStealth = d.flag("hollows.king-stealth", true, "Goblins near the King's Burrow spot players who are not sneaking (research: 'avoid goblin detection')");
        kingSpottedSeconds = d.integer("hollows.king-spotted-seconds", 30, "Seconds King Grubnak refuses a player a guard spotted (TUNE)");
        kingSightRange = d.number("hollows.king-sight-range", 6.0, "Blocks a burrow goblin can spot a non-sneaking player from, with line of sight (TUNE)");
        templeTraps = d.flag("hollows.temple-traps", true, "Dart traps fire across the Jungle Temple stairs while someone climbs");
        templeTrapBeats = d.integer("hollows.temple-trap-beats", 3, "Half-seconds between dart volleys; the two halves of the stair alternate (TUNE)");
        templeDartDamage = d.number("hollows.temple-dart-damage", 150.0, "Damage of one temple dart, defense applies (TUNE)");
        lobbyRotation = d.flag("hollows.lobby-rotation", true, "Crystal Caverns lobbies age on the SkyBlock calendar and are replaced by a new one (new caves) when they close");
        lobbyCloseDay = d.integer("hollows.lobby-close-day", 19, "SkyBlock day of a lobby on which it stops taking new players (research: Day 19)");
        lobbyShutdownDay = d.integer("hollows.lobby-shutdown-day", 35, "SkyBlock day of a lobby on which it is force-closed and rebuilt (research: Day 35)");
        lobbyCloseBelow = d.integer("hollows.lobby-close-below", 4, "After the close day, the lobby closes once fewer than this many players are inside (research: 4)");
        lobbyLockNewcomers = d.flag("hollows.lobby-lock-newcomers", true, "After the close day only players already in this lobby (or a party member's) may enter");
        lobbyCountdown = d.integer("hollows.lobby-close-countdown", 60, "Seconds of warning before a closing lobby sends everyone out (TUNE)");
        templeParkour = d.flag("hollows.temple-parkour", true, "The Jungle Key lets you into the inner temple (parkour, then the Door Guardian); off = the guardian wakes at the shrine");
        treasureHits = d.integer("hollows.treasure-hits", 5, "Hits to break the floor tile over a buried Varnas chest (TUNE: the reference lockpick is 5 clicks)");
        treasureReseal = d.integer("hollows.treasure-reseal-seconds", 30, "Seconds before a dug-open Varnas floor tile closes again");
        depthsGuardians = d.flag("hollows.depths-guardians", false, "Rimestone guardians also roam the Frozen Depths (off = reference: they only guard mineshafts; restart to apply)");
        kalhuikiMask = d.flag("hollows.kalhuiki-mask", true, "Tidecarver tribesmen ignore players wearing the Tidecarver mask (KALHUIKI_MASK lore)");
        passRequired = d.flag("hollows.pass-required", true, "Entering the Crystal Caverns needs a Crystal Caverns Pass (research: 10,000 coins / 6 h from Gwendolyn)");
        passPrice = d.number("hollows.pass-price", 10_000, "Coins for a Crystal Caverns Pass (research: 10,000)");
        passHours = d.integer("hollows.pass-hours", 6, "Hours one Crystal Caverns Pass lasts (research 03: 6 h; older wiki: 2 h)");
        railPassRequired = d.flag("hollows.tunnels-rail-pass", true, "Entering the Frozen Tunnels needs the forged Rimestone Rail Pass once (research: Secret Railroad Pass)");
        sidebarPriority = d.integer("hollows.sidebar-priority", 320, "Sidebar position of the crystals line (lower = higher up)");
    }
}
