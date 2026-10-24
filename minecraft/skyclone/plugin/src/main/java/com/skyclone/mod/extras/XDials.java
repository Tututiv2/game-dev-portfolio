package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every tunable of the module (editable in game with /sb dial set). Registered in enable(). */
final class XDials {

    private XDials() {}

    // harp
    static Dials.Dial<Integer> harpMaxMistakes;
    static Dials.Dial<Double> harpIntScale;
    static Dials.Dial<Integer> harpNearBlocks;
    static Dials.Dial<String> harpNpc;
    static Dials.Dial<Double> harpSpeedScale;
    static Dials.Dial<Boolean> harpFinalReward;
    // phone
    static Dials.Dial<Integer> phoneIncomingMinutes;
    static Dials.Dial<Boolean> phoneAddOnClick;
    static Dials.Dial<Double> phoneTttBlunder;
    static Dials.Dial<Integer> phoneSnakeTicks;
    static Dials.Dial<Integer> phoneContactXp;
    // city projects
    static Dials.Dial<Boolean> cityEnabled;
    static Dials.Dial<Double> cityCycleDays;
    static Dials.Dial<Integer> cityPerCycle;
    static Dials.Dial<Double> cityGoalScale;
    static Dials.Dial<Double> cityPerkScale;
    static Dials.Dial<Double> cityCostScale;
    // fire sales
    static Dials.Dial<Boolean> fireEnabled;
    static Dials.Dial<Double> fireCycleHours;
    static Dials.Dial<Double> fireOpenHours;
    static Dials.Dial<Integer> fireCount;
    static Dials.Dial<Double> fireBitsPerGem;
    static Dials.Dial<Double> fireStockScale;
    static Dials.Dial<Integer> firePerPlayer;
    // skins
    static Dials.Dial<Integer> skinPassSeconds;

    static void register() {
        Dials d = Sky.dials();
        harpMaxMistakes = d.integer("extras.harp.max-mistakes", 5, "Mistakes (misses + wrong notes) a harp song survives; one more fails it. TUNE");
        harpIntScale = d.number("extras.harp.int-scale", 1.0, "Multiplier on the Intelligence each harp song grants (research: +26 total)");
        harpNearBlocks = d.integer("extras.harp.near-npc-blocks", 12, "The harp is played at the harpist: max distance in blocks (0 = anywhere)");
        harpNpc = d.text("extras.harp.npc", "foraging_harpist", "NPC id of the harpist (the reference's Melody); if absent the harp plays anywhere");
        harpSpeedScale = d.number("extras.harp.speed-scale", 1.0, "Multiplier on every song's beat length (2 = half speed)");
        harpFinalReward = d.flag("extras.harp.final-reward", true, "Give the harpist's hair accessory (MELODY_HAIR) for completing every song");
        phoneIncomingMinutes = d.integer("extras.phone.incoming-minutes", 45, "Average online minutes between incoming calls from contacts (0 = never). TUNE");
        phoneAddOnClick = d.flag("extras.phone.add-on-click", true, "Clicking a contact NPC while holding a phone adds them");
        phoneTttBlunder = d.number("extras.phone.ttt-blunder", 0.25, "Chance the phone plays a random Tic Tac Toe move (0 = unbeatable)");
        phoneSnakeTicks = d.integer("extras.phone.snake-ticks", 5, "Server ticks per Snake step");
        phoneContactXp = d.integer("extras.phone.contact-sbxp", 10, "SkyBlock XP per contact added (neu sblevels abiphone_contacts_xp)");
        cityEnabled = d.flag("extras.city.enabled", true, "City Projects are open");
        cityCycleDays = d.number("extras.city.cycle-days", 30.0, "Real days each set of City Projects runs. TUNE");
        cityPerCycle = d.integer("extras.city.per-cycle", 3, "City Projects running at once. TUNE");
        cityGoalScale = d.number("extras.city.goal-scale", 1.0, "Multiplier on every project's community goal (contributions)");
        cityPerkScale = d.number("extras.city.perk-scale", 1.0, "Multiplier on every City Project perk (personal and community)");
        cityCostScale = d.number("extras.city.cost-scale", 1.0, "Multiplier on every component's coin and item cost");
        fireEnabled = d.flag("extras.firesale.enabled", true, "Fire Sales run");
        fireCycleHours = d.number("extras.firesale.cycle-hours", 168.0, "Real hours between Fire Sales (one sale per cycle). TUNE");
        fireOpenHours = d.number("extras.firesale.open-hours", 96.0, "Hours each Fire Sale stays open (research API sample: 89-96 h)");
        fireCount = d.integer("extras.firesale.count", 5, "Skins per Fire Sale (research API sample: 5)");
        fireBitsPerGem = d.number("extras.firesale.bits-per-gem", 15.0, "Bits charged per reference gem (a cookie = 325 gems ~ 4,800 bits). TUNE");
        fireStockScale = d.number("extras.firesale.stock-scale", 1.0, "Multiplier on each skin's server-wide stock (sample: 950)");
        firePerPlayer = d.integer("extras.firesale.per-player", 1, "Copies of one Fire Sale skin a player may buy per sale. TUNE");
        skinPassSeconds = d.integer("extras.skins.pass-seconds", 5, "Seconds between re-applying skin looks to worn/held items");
    }
}
