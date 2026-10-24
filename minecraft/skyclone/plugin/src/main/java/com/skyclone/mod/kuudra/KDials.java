package com.skyclone.mod.kuudra;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every tunable of the hunt (CONTRACT rule 3). Read at use time. */
final class KDials {

    private KDials() {}

    static Dials.Dial<Double> BOSS_HP, SOLO_HP, MOB_HP, MOB_DMG, SOLO_MOB_DMG, BOSS_DMG, WAVE_SCALE, VOLLEY_SCALE, BUILD_SPEED,
            CHANNEL_SPEED, TOKEN_SCALE, ESSENCE_BONUS, PAID_ESSENCE_SCALE;
    static Dials.Dial<Integer> TIME_LIMIT, REWARD_SECONDS, MAX_PARTY, MAX_ARENAS, CHEST_CAP, SUPPLIES, FUEL_PER_VOLLEY,
            STUN_SECONDS, STOMACH_SECONDS, START_TOKENS, MAX_ALIVE;
    static Dials.Dial<Boolean> QUEUE_ANYWHERE, REQUIRE_PREVIOUS, CANNON_NEEDS_PERK, DEATH_COIN_LOSS, TAKE_OVER_DOCK,
            DOCK_TRIGGER, SIDEBAR, BRIEFING, ANNOUNCE, CUTSCENES, CUTSCENE_REPEAT, CUTSCENE_SKIP, INTRO_REQUIRED, INTRO_KEY,
            SHARD_TO_BOX;
    static Dials.Dial<Integer> INTRO_KILLS, MANDIBLE_SALVAGE;
    static final Map<String, Dials.Dial<String>> REQ = new LinkedHashMap<>();

    static void register(KCatalog cat) {
        Dials d = Sky.dials();
        BOSS_HP = d.number("kuudra.boss-hp-scale", 1.0, "Multiplier on the Leviathan's health (research 10M/25M/60M/150M/800M; try 0.05 for a solo playtest)");
        SOLO_HP = d.number("kuudra.solo-hp-fraction", 0.5, "Leviathan health for a crew of one, as a fraction of a crew of four (linear between) - TUNE");
        MOB_HP = d.number("kuudra.mob-hp-scale", 1.0, "Multiplier on the brood's health");
        MOB_DMG = d.number("kuudra.mob-damage-scale", 1.0, "Multiplier on the brood's damage");
        SOLO_MOB_DMG = d.number("kuudra.solo-mob-damage-fraction", 0.6, "Brood damage for a crew of one, as a fraction of a crew of four (linear between) - TUNE");
        BOSS_DMG = d.number("kuudra.boss-damage-scale", 1.0, "Multiplier on the Leviathan's attacks (spit, slam, rampage)");
        WAVE_SCALE = d.number("kuudra.wave-seconds-scale", 1.0, "Multiplier on the time between brood waves (research 35/30/25/20/15 s)");
        VOLLEY_SCALE = d.number("kuudra.volley-scale", 1.0, "Multiplier on the health one ballista volley removes");
        BUILD_SPEED = d.number("kuudra.build-speed", 1.0, "Multiplier on ballista build speed");
        CHANNEL_SPEED = d.number("kuudra.channel-speed", 1.0, "Multiplier on pick-up / set-down / loading speed");
        TOKEN_SCALE = d.number("kuudra.token-scale", 1.0, "Multiplier on every token reward");
        ESSENCE_BONUS = d.number("kuudra.essence-bonus-percent", 0.0, "Extra % Crimson Essence in every chest (events)");
        PAID_ESSENCE_SCALE = d.number("kuudra.paid-essence-scale", 1.0, "Multiplier on the GUARANTEED paid-chest Crimson Essence (estimate 100/250/500/1000/2000: the reference publishes only Infernal 2,000, unconfirmed)");
        TIME_LIMIT = d.integer("kuudra.time-limit-minutes", cat.fight.timeLimitMinutes, "A hunt fails after this many minutes");
        REWARD_SECONDS = d.integer("kuudra.reward-seconds", cat.fight.rewardSeconds, "Seconds in the hollow after a win before the crew is sent home (chests stay claimable from /kuudra)");
        MAX_PARTY = d.integer("kuudra.max-party", 4, "Largest hunting crew (research: 4)");
        MAX_ARENAS = d.integer("kuudra.max-arenas", 6, "Hunts that may run at the same time");
        CHEST_CAP = d.integer("kuudra.pending-chest-cap", 10, "Unclaimed reward chests kept per player (oldest dropped)");
        SUPPLIES = d.integer("kuudra.supplies", cat.fight.supplies, "Supply crates to deliver (research default 6)");
        FUEL_PER_VOLLEY = d.integer("kuudra.fuel-per-volley", cat.fight.fuelPerVolley, "Fuel cells per ballista volley (research 4)");
        STUN_SECONDS = d.integer("kuudra.stun-seconds", cat.fight.stunSeconds, "Seconds the Leviathan stays stunned");
        STOMACH_SECONDS = d.integer("kuudra.stomach-seconds", cat.fight.stomachSeconds, "Seconds before the Gullet spits its visitors out");
        START_TOKENS = d.integer("kuudra.start-tokens", cat.fight.startTokens, "Tokens every hunter starts with");
        MAX_ALIVE = d.integer("kuudra.max-alive", cat.fight.maxAlive, "Most brood alive at once for a crew of four (40% solo, +20% per extra hunter)");
        QUEUE_ANYWHERE = d.flag("kuudra.queue-anywhere", false, "Start hunts from anywhere (off: only on the Crimson Isle)");
        REQUIRE_PREVIOUS = d.flag("kuudra.require-previous-tier", true, "A tier needs one clear of the tier below it");
        CANNON_NEEDS_PERK = d.flag("kuudra.cannon-needs-perk", true, "Riding the cannon needs the Cannon Pass perk");
        DEATH_COIN_LOSS = d.flag("kuudra.death-coin-loss", false, "Dying in a hunt costs half the purse like elsewhere");
        TAKE_OVER_DOCK = d.flag("kuudra.take-over-dock", true, "Turn off the crimson module's placeholder dockhand and answer it here");
        DOCK_TRIGGER = d.flag("kuudra.dock-trigger", true, "Walking to the end of the Leviathan dock opens the hunt menu");
        SIDEBAR = d.flag("kuudra.sidebar", true, "Show the hunt section on the sidebar");
        BRIEFING = d.flag("kuudra.dock-briefing", true, "Dockhand Nell briefs a player the first time the dock opens the hunt board");
        ANNOUNCE = d.flag("kuudra.announce-infernal", true, "Announce Infernal clears to the server");
        CUTSCENES = d.flag("kuudra.cutscenes", true, "Play the hunt's cutscenes (opening, the beast crawling ashore, its carcass sinking)");
        CUTSCENE_REPEAT = d.flag("kuudra.cutscene-repeat", false, "Play the full opening every hunt (off: only for a crew with someone who has not seen it)");
        CUTSCENE_SKIP = d.flag("kuudra.cutscene-skip", true, "A cutscene ends when every hunter crouches");
        INTRO_REQUIRED = d.flag("kuudra.intro-required", true, "The Basic hunt needs Dockhand Nell's questline (The Leviathan's Wake) up to her send-off");
        INTRO_KILLS = d.integer("kuudra.intro-kills", 15, "Crimson Isle creatures to slay for the intro's 'Stand the Heat' step - TUNE");
        INTRO_KEY = d.flag("kuudra.intro-key", true, "Nell hands over one Basic Leviathan Key when she sends a player out (once per profile)");
        MANDIBLE_SALVAGE = d.integer("kuudra.mandible-salvage-essence", 500, "Crimson Essence for salvaging one Leviathan Mandible (reference 500; the bazaar cap recipe uses the value at boot)");
        SHARD_TO_BOX = d.flag("kuudra.shard-to-box", true, "Chest shards go straight into the Hunting Box (off: as items)");
        for (KCatalog.Tier t : cat.tiers)
            REQ.put(t.id, d.text("kuudra.req." + t.id, t.requirement, "Requirement to hunt at " + t.name + " (research rep 0/1k/3k/7k/12k)"));
    }

    static String requirement(KCatalog.Tier t) {
        Dials.Dial<String> d = REQ.get(t.id);
        return d == null ? t.requirement : d.get();
    }
}
