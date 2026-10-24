package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every Rift tunable. Read at use time. */
final class RiftDials {

    final Dials.Dial<Double> baseTime, baseDamage, baseHearts, drainScale, killTime, perCharm, soulBonus, moteTime,
            deathPenalty, infusionHours, heartRegenScale, vampireBonusCap;
    final Dials.Dial<Integer> maxInfusions, bitsCost, bottleCost, soulStep, moteValue, cradleMoteValue, moteRespawn,
            heartHitsNeeded, raceSeconds, verminNeeded, eggBerberis, eggAgaricus, gatherRegen, mazeRevealSeconds,
            danceRounds, paperworkSeconds, summonLeech, summonBacte, summonLord, bossBonusMotes, warnAt;
    final Dials.Dial<Boolean> separateInventory, freeEntry, gatesEnabled, particles, sidebar;
    final Dials.Dial<String> hubNpcOffset, gateMode;
    // wave 3
    final Dials.Dial<Boolean> replaceMainStats, galleryExhibits;
    final Dials.Dial<Integer> splitStealPot, splitStealWait, gallerySecureMotes, odonataCount, odonataRespawn;
    final Dials.Dial<Double> splitStealHours, splitStealEchoSplit;
    // wave 5
    final Dials.Dial<Boolean> soloTwoPlayer, zeroMainCombat;
    final Dials.Dial<Integer> cageOpenSeconds, wallRegrowSeconds, riddleRetrySeconds, hotdogSeconds, spookLoseSeconds, lonelySeconds,
            squatSeconds, circleSeconds, dryWindow, eyeTravelSeconds, elevatedY;
    final Dials.Dial<Double> riddleShopScale, leapHeight, meleeHitTime, punchSeconds;

    RiftDials() {
        Dials d = Sky.dials();
        baseTime = d.number("rift.base-time", 480, "Base Rift Time in seconds (research 03 sec 19: 480 s)");
        baseDamage = d.number("rift.base-damage", 20, "Base Rift Damage (research 01 sec 2.3: 20)");
        baseHearts = d.number("rift.base-hearts", 10, "Base Rift Hearts (research 01 sec 2.3: 10)");
        drainScale = d.number("rift.drain-scale", 1.0, "Rift Time drained per second (1 = real time)");
        killTime = d.number("rift.kill-time", 1.0, "Rift Time (s) gained per Rift monster killed (TUNE: the reference says monsters give time, not how much)");
        perCharm = d.number("rift.timecharm-seconds", 60, "Max Rift Time (s) each Timecharm adds (TUNE: not in research)");
        soulBonus = d.number("rift.soul-step-seconds", 5, "Max Rift Time (s) per soul milestone (TUNE)");
        moteTime = d.number("rift.mote-orb-time", 2, "Rift Time (s) per mote orb (research: +2 s)");
        deathPenalty = d.number("rift.death-time-penalty", 30, "Rift Time (s) lost when you die in the Rift (TUNE)");
        infusionHours = d.number("rift.infusion-hours", 4, "Hours per free Planar Infusion (research: 4)");
        heartRegenScale = d.number("rift.heart-regen", 1.0, "Multiplier on health regen inside the Rift (TUNE)");
        vampireBonusCap = d.number("rift.vampire-hp-scale-fallback", 1000, "Real HP per Rift HP for vampire slayer bosses when slayer's own dial is missing");
        maxInfusions = d.integer("rift.max-infusions", 3, "Stored Planar Infusions (research: 3)");
        bitsCost = d.integer("rift.bits-cost", 200, "Bits for a paid entry (research: 200)");
        bottleCost = d.integer("rift.bottle-cost", 32, "Grand Experience Bottles for a paid entry (research: 32)");
        soulStep = d.integer("rift.soul-step", 4, "Riddle Souls per Rift Time milestone (TUNE)");
        moteValue = d.integer("rift.mote-orb-motes", 25, "Motes per mote orb (research: 25)");
        cradleMoteValue = d.integer("rift.cradle-orb-motes", 10, "Motes per orb in the Cradle, no time (research: 10)");
        moteRespawn = d.integer("rift.mote-orb-respawn", 300, "Seconds before a mote orb comes back for you (TUNE)");
        heartHitsNeeded = d.integer("rift.heart-hits", 6, "Hits to catch the Living Heart (TUNE)");
        raceSeconds = d.integer("rift.race-seconds", 75, "West Village race time limit in seconds (TUNE)");
        verminNeeded = d.integer("rift.vermin-needed", 15, "Vermin to clear from the Infested House (TUNE)");
        eggBerberis = d.integer("rift.wilma-berberis", 64, "Wilted Berberis Farmer Wilma wants (TUNE)");
        eggAgaricus = d.integer("rift.wilma-agaricus", 32, "Agaricus Caps Farmer Wilma wants (TUNE)");
        gatherRegen = d.integer("rift.gather-regen", 30, "Seconds before a gathered Rift plant or crystal grows back (TUNE)");
        mazeRevealSeconds = d.integer("rift.maze-reveal", 6, "Seconds the safe maze path glows when you step on the start (TUNE)");
        danceRounds = d.integer("rift.dance-rounds", 10, "Rounds of the dance floor to win (TUNE)");
        paperworkSeconds = d.integer("rift.paperwork-seconds", 10, "Rift Time (s) each clerk's stamp costs - bureaucracy takes time (TUNE)");
        summonLeech = d.integer("rift.summon-leech", 150, "Motes to call the Leech Sovereign (TUNE)");
        summonBacte = d.integer("rift.summon-bloatmass", 400, "Motes to call the Bloatmass (TUNE)");
        summonLord = d.integer("rift.summon-lord", 600, "Motes to call the Chateau Lord (TUNE)");
        bossBonusMotes = d.integer("rift.boss-helper-motes", 50, "Motes every helper near a Rift boss gets when it dies (TUNE)");
        warnAt = d.integer("rift.grace-seconds", 10, "Below this much Rift Time you are warned and time penalties stop (research: 10 s)");
        separateInventory = d.flag("rift.separate-inventory", true, "Swap to a Rift-only inventory on entry (research 08 sec 5.1)");
        freeEntry = d.flag("rift.free-entry", false, "Admin/testing: entering the Rift costs nothing");
        gatesEnabled = d.flag("rift.gates", true, "Area gates (Timecharms + Rift Time left) are enforced");
        particles = d.flag("rift.particles", true, "Show soul / orb / egg sparkles to players nearby");
        sidebar = d.flag("rift.sidebar", true, "Rift section on the sidebar");
        hubNpcOffset = d.text("rift.hub-npc-offset", "-17,0,8", "Where the Rift entry NPC stands in the hub, relative to anchor warp.wizard_tower (x,y,z). At the default the hub's own anchor npc.rift_warden (beside the Rift arch) is used when it exists");
        replaceMainStats = d.flag("rift.replace-main-stats", true, "Inside the Rift, Rift Intelligence/Speed/Mana Regen REPLACE the main ones and Magic Find is 0 (research 08 sec 5.2); false = added on top");
        galleryExhibits = d.flag("rift.gallery-exhibits", true, "Float each secured Timecharm over its Rift Gallery pedestal (seen only by players who secured it)");
        gallerySecureMotes = d.integer("rift.gallery-secure-motes", 100, "Motes the curator pays the first time you secure each Timecharm (TUNE)");
        splitStealPot = d.integer("rift.split-steal-pot", 2000, "Motes on the table in a Split or Steal round: both split = half each, one steals = all of it (TUNE)");
        splitStealHours = d.number("rift.split-steal-hours", 2, "Hours between your Split or Steal rounds (TUNE)");
        splitStealWait = d.integer("rift.split-steal-wait", 45, "Seconds a Split or Steal choice waits for another player before the Cube's echo answers (TUNE)");
        splitStealEchoSplit = d.number("rift.split-steal-echo-split", 0.6, "Chance the Cube's echo (no other player) splits (TUNE)");
        odonataCount = d.integer("rift.odonata", 10, "Odonata drifting over the Wildwood meadows at once (TUNE)");
        odonataRespawn = d.integer("rift.odonata-respawn", 20, "Seconds before a caught Odonata is replaced (TUNE)");
        // wave 5
        soloTwoPlayer = d.flag("rift.riddle-solo", false, "Two-player Riddle Souls (Twin Plates, Beacon and Hopper) accept one player (testing; the reference needs two)");
        cageOpenSeconds = d.integer("rift.cage-open-seconds", 12, "Seconds the Twin Plates glass stays open (TUNE)");
        wallRegrowSeconds = d.integer("rift.wall-regrow", 20, "Seconds before a broken Living Wall block grows back (TUNE)");
        riddleShopScale = d.number("rift.riddle-shop-scale", 1.0, "Multiplier on the Mote price of the four Riddle Souls sold by NPCs (TUNE)");
        riddleRetrySeconds = d.integer("rift.riddle-retry", 300, "Seconds before a lost Ubaldo game can be retried (Old Vel'ka's quiz: a fifth of it) (TUNE)");
        hotdogSeconds = d.integer("rift.hotdog-seconds", 25, "Seconds for the Hot Dog Showdown (TUNE)");
        spookLoseSeconds = d.integer("rift.spook-lose", 12, "Seconds you may be more than 16 blocks behind the Flitwraith before it gives up (TUNE)");
        lonelySeconds = d.integer("rift.lonely-seconds", 30, "Seconds of company Lonely Yxvael wants (TUNE)");
        squatSeconds = d.integer("rift.squat-seconds", 30, "Seconds for Okrel's squats (TUNE)");
        circleSeconds = d.integer("rift.circle-seconds", 150, "Seconds for Digby's Full Circle lap (TUNE)");
        dryWindow = d.integer("rift.dry-seconds", 60, "Dry Feet: seconds since you last touched water (TUNE)");
        leapHeight = d.number("rift.leap-height", 6, "Leap of Faith: fall distance needed to land on it (TUNE)");
        eyeTravelSeconds = d.integer("rift.eye-travel-seconds", 10, "Rift Time an eye fast travel costs (TUNE)");
        meleeHitTime = d.number("rift.melee-hit-time", 1, "Rift Time (s) a Rift monster's melee hit takes (research: damage taken removes time; the belts take 1 less) (TUNE)");
        elevatedY = d.integer("rift.elevated-y", 100, "Satelite: 'elevated areas' start at this height (TUNE)");
        zeroMainCombat = d.flag("rift.zero-main-combat", true, "Inside the Rift, Strength / Crit / Ferocity are 0 on the stat sheet (research: they do not apply there)");
        punchSeconds = d.number("rift.punchcard-seconds", 25, "Punchcard Artifact: Rift Time both players regain (research: 25)");
        gateMode = d.text("rift.gate-mode", "remaining", "Rift Time gates check 'remaining' time (research) or 'max' time");
    }
}
