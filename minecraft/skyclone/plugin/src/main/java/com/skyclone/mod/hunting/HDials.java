package com.skyclone.mod.hunting;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every hunting tunable. All TUNE unless a comment names the research. Read with get() at use time. */
final class HDials {

    private HDials() {}

    static Dials.Dial<Boolean> CRITTERS;
    static Dials.Dial<Integer> CRITTER_CAP;
    static Dials.Dial<Double> CRITTER_INTERVAL;
    static Dials.Dial<Double> CRITTER_DESPAWN;
    static Dials.Dial<Double> REEL_WINDOW;
    static Dials.Dial<Integer> LASSO_MISSES;
    static Dials.Dial<Double> BLACKHOLE_HP;
    static Dials.Dial<Double> BLACKHOLE_SECONDS;
    static Dials.Dial<Double> TRAP_MINUTES;
    static Dials.Dial<Integer> TRAP_MAX;
    static Dials.Dial<Double> FISH_SHARD_CHANCE;
    static Dials.Dial<Double> XP_MULT;
    static Dials.Dial<Double> CHARM_SCALE;
    static Dials.Dial<Double> SAFARI_MINUTES;
    static Dials.Dial<Double> SPARKLING;
    static Dials.Dial<Double> TICKET_PRICE;
    static Dials.Dial<Double> SPARKLING_MULT;
    static Dials.Dial<Integer> SAFARI_LEVEL;
    static Dials.Dial<Integer> WATER_DEPTH;
    static Dials.Dial<Double> WATER_LEASH;

    static void register() {
        Dials d = Sky.dials();
        CRITTERS = d.flag("hunting.critters", true, "Spawn catchable critters in their habitats");
        CRITTER_CAP = d.integer("hunting.critter-cap", 6, "Live critters per habitat while a player is near");
        CRITTER_INTERVAL = d.number("hunting.critter-interval-seconds", 4, "Seconds between critter spawn passes");
        CRITTER_DESPAWN = d.number("hunting.critter-despawn-seconds", 90, "A critter with no player within 64 blocks for this long leaves");
        REEL_WINDOW = d.number("hunting.reel-window-seconds", 1.5, "Seconds to right-click once a lassoed critter's stamina runs out");
        LASSO_MISSES = d.integer("hunting.lasso-misses", 3, "Missed reels before a lassoed critter breaks free");
        BLACKHOLE_HP = d.number("hunting.blackhole-hp-percent", 10, "A monster below this % HP near your Black Hole can be captured (research: 10%)");
        BLACKHOLE_SECONDS = d.number("hunting.blackhole-capture-seconds", 3, "Seconds a low-HP monster must spend in a Black Hole to be captured");
        TRAP_MINUTES = d.number("hunting.trap-minutes", 20, "Minutes a Small Huntrap takes to catch a shard (bigger traps are faster)");
        TRAP_MAX = d.integer("hunting.trap-max", 3, "Huntraps one player may have set at once");
        FISH_SHARD_CHANCE = d.number("hunting.fish-shard-chance", 0.01, "Chance a plain fishing catch also brings up a Water Shard of that island");
        XP_MULT = d.number("hunting.xp-multiplier", 1.0, "Multiplier on every Hunting XP grant");
        CHARM_SCALE = d.number("hunting.charm-scale", 1.0, "Multiplier on Charm Chance when a killed mob may drop its shard");
        SAFARI_MINUTES = d.number("hunting.safari-minutes", 20, "Length of one Critter Safari hunt");
        SPARKLING = d.number("hunting.sparkling-chance", 1.0 / 4096, "Chance a Safari critter is Sparkling (research: 1/4,096)");
        SPARKLING_MULT = d.number("hunting.sparkling-multiplier", 10, "Reward multiplier for a Sparkling critter (research: 10x)");
        TICKET_PRICE = d.number("hunting.ticket-price", 50_000, "Coins for a Basic Safari Ticket at the receptionist");
        SAFARI_LEVEL = d.integer("hunting.safari-level", 15, "Hunting level that opens the Critter Safari (research: XV)");
        WATER_DEPTH = d.integer("hunting.water-critter-depth", 2, "Deepest a water critter may swim (blocks under the surface) before it is pushed back up, so a net reaches it from the shore");
        WATER_LEASH = d.number("hunting.water-critter-leash", 8, "Blocks a water critter may wander from where it appeared (near the hunter) before it swims back");
    }
}
