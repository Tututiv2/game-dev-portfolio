package com.skyclone.mod.mines;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every tunable of the mines module. Read with .get() at use time. */
final class MinesDials {

    final Dials.Dial<String> goldRequirement, deepRequirement, dwarvenRequirement;
    final Dials.Dial<Integer> passMiningLevel, passSkyXp;
    final Dials.Dial<Boolean> liftVisitedOnly, mistBlindness, mistParticles;
    final Dials.Dial<Double> rideCooldown, minecartBase, minecartPerBlock;
    final Dials.Dial<Double> sneakyRadius, ghostTrueDamage, ghostCoinBurst, ghostCoinChance;
    final Dials.Dial<Double> knifeRange, knifeCooldown, fireRange, fireCooldown, knifeSpeed, fireSpeed, projectileHitRadius;
    final Dials.Dial<Integer> fireTicks, frostTicks, frostLevel;
    // wave 5: real rides and the Crystal Caverns warden
    final Dials.Dial<Boolean> liftRides, cartRides;
    final Dials.Dial<Double> liftSpeed, cartSpeed, hollowsPassPrice, hollowsPassHours;

    MinesDials() {
        Dials d = Sky.dials();
        goldRequirement = d.text("mines.gold-requirement", "",
                "Requirement to warp to the Gold Mine (blank = none). Read at boot: restart after changing.");
        deepRequirement = d.text("mines.deep-requirement", "skill:mining:5",
                "Requirement to warp to the Deep Caverns (research: Mining V). Read at boot.");
        dwarvenRequirement = d.text("mines.dwarven-requirement", "skill:mining:12 & mines:pass",
                "Requirement to warp to the Dwarven Mines (research: Mining XII + the delver's pass). Read at boot.");
        passMiningLevel = d.integer("mines.pass-mining-level", 12, "Mining level the delver at the bottom of the Deep Caverns asks for before letting you through");
        passSkyXp = d.integer("mines.pass-skyblock-xp", 10, "SkyBlock XP for earning the Dwarven Mines pass (TUNE)");
        liftVisitedOnly = d.flag("mines.lift-visited-only", true, "The Deep Caverns lift only stops at layers you have walked into");
        // wave 3: blindness off by default. It turned the screen black, stopped sprinting and hid every Ghost's
        // nameplate past a few blocks -- the Ghost grind is the Mist's whole point. The fog is drawn instead.
        mistBlindness = d.flag("mines.mist-blindness", false, "Players in The Mist (Dwarven Mines) are blinded by the fog (harsh: hides Ghost nameplates)");
        mistParticles = d.flag("mines.mist-particles", true, "Draw drifting fog particles round players in The Mist");
        rideCooldown = d.number("mines.ride-cooldown-seconds", 3, "Seconds between two lift or minecart rides");
        minecartBase = d.number("mines.minecart-base-fare", 0, "Coins for any Dwarven Mines minecart ride (no paid ranks: free by default)");
        minecartPerBlock = d.number("mines.minecart-fare-per-block", 0, "Extra coins per block travelled by minecart");
        sneakyRadius = d.number("mines.sneaky-creeper-radius", 3.5, "Blast radius of a Sneaky Creeper (blocks)");
        ghostTrueDamage = d.number("mines.ghost-true-damage", 100, "True damage a Ghost adds to every hit (research: 1,000 + 100 true)");
        ghostCoinBurst = d.number("mines.ghost-coin-burst", 1_000_000, "Coins in a Ghost's rare coin burst (research: 1,000,000)");
        ghostCoinChance = d.number("mines.ghost-coin-chance", 0.0001, "Chance of the Ghost coin burst per kill (research: 0.01%)");
        knifeRange = d.number("mines.knife-range", 14, "How far a Knifethrower goblin can hit you from (blocks)");
        knifeCooldown = d.number("mines.knife-cooldown", 3, "Seconds between knives");
        fireRange = d.number("mines.fire-range", 10, "How far a Fireslinger goblin can reach (blocks)");
        fireCooldown = d.number("mines.fire-cooldown", 4, "Seconds between fireballs");
        knifeSpeed = d.number("mines.knife-speed", 1.0, "Blocks per tick a Knifethrower's knife flies (dodgeable by strafing)");
        fireSpeed = d.number("mines.fire-speed", 0.6, "Blocks per tick a Fireslinger's fireball flies");
        projectileHitRadius = d.number("mines.projectile-hit-radius", 0.8, "How close (blocks) a knife or fireball must pass to a player's body to hit");
        fireTicks = d.integer("mines.fire-ticks", 60, "Ticks a Fireslinger sets you alight for");
        frostTicks = d.integer("mines.frost-ticks", 40, "Ticks of Slowness an Ice Walker hit inflicts");
        frostLevel = d.integer("mines.frost-level", 1, "Slowness amplifier from an Ice Walker hit (0 = Slowness I)");
        liftRides = d.flag("mines.lift-rides", true, "The lift is a real moving cage (false = the old instant teleport)");
        cartRides = d.flag("mines.cart-rides", true, "Dwarven Mines minecarts roll along the laid track (false = the old instant teleport)");
        // TUNE: the reference's lift and carts are instant (a menu teleport); these speeds are ours. Sneak skips either way.
        liftSpeed = d.number("mines.lift-speed", 0.8, "Top speed of the lift cage in blocks per tick (TUNE: the reference teleports)");
        cartSpeed = d.number("mines.cart-speed", 1.4, "Top speed of a minecart ride in blocks per tick (TUNE: the reference teleports)");
        hollowsPassPrice = d.number("mines.hollows-pass-price", 10_000, "Coins for a Crystal Caverns Pass from the warden at Deepking's Gateway (research: 10,000)");
        hollowsPassHours = d.number("mines.hollows-pass-hours", 6, "Hours a Crystal Caverns Pass lasts (research: 6)");
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
