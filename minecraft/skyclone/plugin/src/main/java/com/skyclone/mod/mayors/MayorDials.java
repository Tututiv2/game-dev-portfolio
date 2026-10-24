package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every tunable of the mayors module ({@code /sb dial list mayors}). Read with {@code .get()} at use time. */
final class MayorDials {

    // ---- election ----
    final Dials.Dial<Integer> seed;
    final Dials.Dial<Integer> specialEvery;
    final Dials.Dial<Boolean> includeRetired;
    final Dials.Dial<Integer> npcVotes;
    final Dials.Dial<Integer> voteWeight;
    final Dials.Dial<Boolean> fameVotes;
    final Dials.Dial<String> voteRequirement;
    final Dials.Dial<Boolean> mayorAllPerks;
    final Dials.Dial<String> disabledPerks;
    final Dials.Dial<String> perkValues;
    final Dials.Dial<Double> bribeMin, bribeMax, bribePerLevel;
    final Dials.Dial<Integer> paradeDays;
    final Dials.Dial<String> privateIslands;
    final Dials.Dial<Boolean> sidebar;
    final Dials.Dial<String> carnivalFeastEvents;
    final Dials.Dial<Integer> carnivalParrotRange;
    final Dials.Dial<String> carnivalUndeadArmor;

    // ---- spooky ----
    final Dials.Dial<Double> candyScale;
    final Dials.Dial<Double> spookyThresholdScale;
    final Dials.Dial<Integer> spookyExtraMonth;

    // ---- Foxy's Extra Event / Mining Fiesta ----
    final Dials.Dial<String> extraEventVariant;
    final Dials.Dial<Integer> extraEventMonth;
    final Dials.Dial<Integer> extraEventDay;
    final Dials.Dial<Integer> extraEventDays;
    final Dials.Dial<Boolean> fiestaWholeTerm;

    // ---- new year / zoo ----
    final Dials.Dial<Boolean> freeCake;

    // ---- dark auction ----
    final Dials.Dial<Double> daMinBidScale;
    final Dials.Dial<Double> daEntryPurse;
    final Dials.Dial<Boolean> daRemoteJoin;
    final Dials.Dial<Integer> daBidSeconds;
    final Dials.Dial<Integer> daLobbySeconds;

    // ---- mythological ritual ----
    final Dials.Dial<Boolean> mythRequireGriffin;
    final Dials.Dial<Double> mythMobChance;
    final Dials.Dial<Double> mythHpScale;
    final Dials.Dial<Boolean> mythGriffinToHurt;

    // ---- winter workshop ----
    final Dials.Dial<Boolean> workshopAlwaysOpen;
    final Dials.Dial<Integer> siegeBreakSeconds;
    final Dials.Dial<Integer> siegeRoundSeconds;
    final Dials.Dial<Double> siegeHpScale;
    final Dials.Dial<Integer> siegeWaves;
    final Dials.Dial<Integer> siegeWaveGap;
    final Dials.Dial<Boolean> giftSelfOpen;

    // ---- gerald sightings / stonks ----
    final Dials.Dial<Double> jerryChance;
    final Dials.Dial<Double> stonksMinBid;

    // ---- calendar alerts (EventAlerts) ----
    final Dials.Dial<Boolean> alertsTakeover;
    final Dials.Dial<String> alertsImportant;
    final Dials.Dial<String> alertsOwnerAnnounced;
    final Dials.Dial<String> alertsPerkGates;
    final Dials.Dial<Double> alertsDaNear;

    MayorDials() {
        Dials d = Sky.dials();
        seed = d.integer("mayors.seed", 517, "Seed for ballots, perk rolls, tie-breaks and the Perk Parade order");
        specialEvery = d.integer("mayors.special-every", 8, "A special mayor is on the ballot every N SkyBlock years (research: 8)");
        includeRetired = d.flag("mayors.include-retired", false, "Let retired mayors (the Magic mayor) run for office");
        npcVotes = d.integer("mayors.npc-votes", 25, "Townsfolk votes split between candidates each election (TUNE: keeps the booth alive on a small server)");
        voteWeight = d.integer("mayors.vote-weight", 1, "Votes one ballot is worth (doubled by the Artifact of Control)");
        fameVotes = d.flag("mayors.fame-votes", true, "A ballot is worth the votes of the player's Fame Rank (bank fame.json: Citizen 2 ... Senator+ 100)");
        voteRequirement = d.text("mayors.vote-requirement", "always", "Requirement key to vote (e.g. sblevel:5)");
        mayorAllPerks = d.flag("mayors.mayor-all-perks", false, "The elected mayor gets every perk, not just the ones rolled onto the ballot");
        disabledPerks = d.text("mayors.disabled-perks", "", "Comma-separated perk ids that never turn on (e.g. derpy.double_mobs_hp)");
        perkValues = d.text("mayors.perk-values", "", "Overrides of perk values: id=value,id=value (e.g. aatrox.slayer_xp_buff=40)");
        bribeMin = d.number("mayors.bribe-min", 50_000, "Kickback: least a voter is paid (research: 50,000)");
        bribeMax = d.number("mayors.bribe-max", 1_000_000, "Kickback: most a voter is paid (research: 1,000,000)");
        bribePerLevel = d.number("mayors.bribe-per-level", 5_000, "Kickback: coins per SkyBlock level on top of the minimum (TUNE)");
        paradeDays = d.integer("mayors.perk-parade-days", 18, "Perk Parade: SB days between mayor rotations (research: 18 = 6 real hours)");
        privateIslands = d.text("mayors.private-islands", "private_island,garden", "Islands that are NOT public (wisdom perks do not apply there)");
        sidebar = d.flag("mayors.sidebar", true, "Show the event section on the sidebar");

        candyScale = d.number("mayors.spooky.candy-scale", 1.0, "Multiplier on the Spooky Festival candy drop chance");
        spookyThresholdScale = d.number("mayors.spooky.threshold-scale", 1.0, "Multiplier on Spooky Festival bracket point thresholds");
        spookyExtraMonth = d.integer("mayors.spooky.extra-month", 5, "Month (1-12) of the extra Spooky Festival the Encore perk schedules");

        extraEventVariant = d.text("mayors.extra-event.variant", "auto",
                "Festival Foxy's Extra Event (Encore) schedules: fishing, mining, spooky, or auto = seeded roll per election (reference: rolled per ballot)");
        extraEventMonth = d.integer("mayors.extra-event.month", 6, "Month (1-12) of the extra Fishing Festival / Mining Fiesta (reference Y518: Late Summer = 6)");
        extraEventDay = d.integer("mayors.extra-event.day", 22, "First day of the extra Fishing Festival / Mining Fiesta (reference Y518: 22)");
        extraEventDays = d.integer("mayors.extra-event.days", 3, "SB days the extra Fishing Festival / Mining Fiesta lasts (reference Y518: 22-24 = 3)");
        fiestaWholeTerm = d.flag("mayors.fiesta.whole-term", true,
                "Mining Fiesta runs the whole term while Cole's perk is on, as mayor OR minister (reference since Feb 2026); false = never from the perk (admin)");

        freeCake = d.flag("mayors.newyear.free-cake", true, "The Baker hands out one free New Year Cake per SkyBlock year");

        daMinBidScale = d.number("mayors.da.min-bid-scale", 1.0, "Multiplier on every Dark Auction minimum bid");
        daEntryPurse = d.number("mayors.da.entry-purse", 400_000, "Coins a player must carry to enter the Dark Auction (research: 400,000)");
        daRemoteJoin = d.flag("mayors.da.remote-join", true, "Allow /darkauction to join from anywhere (else only from the auctioneer)");
        daBidSeconds = d.integer("mayors.da.bid-seconds", 10, "Bid window per lot, reset by every bid (research: 10)");
        daLobbySeconds = d.integer("mayors.da.lobby-seconds", 60, "Seconds between the auction opening and the first lot");

        mythRequireGriffin = d.flag("mayors.myth.require-griffin", true, "Digging burrows needs an active Griffin pet (research: yes)");
        mythMobChance = d.number("mayors.myth.mob-chance", 0.75, "Chance a burrow holds a creature instead of treasure (reference wiki: 75%)");
        mythHpScale = d.number("mayors.myth.hp-scale", 1.0, "Multiplier on Mythological creature health");
        mythGriffinToHurt = d.flag("mayors.myth.griffin-to-hurt", true, "Only a player with a Griffin pet out can hurt Mythological creatures other than the Hunter (reference rule)");

        workshopAlwaysOpen = d.flag("mayors.workshop.always-open", true, "The Winter Workshop can be visited all year (research: Late Winter only)");
        siegeBreakSeconds = d.integer("mayors.siege.break-seconds", 120, "Break between siege rounds (reference: 5 rounds, about 8 minutes of breaks, 1 hour in all)");
        siegeRoundSeconds = d.integer("mayors.siege.round-seconds", 600, "Longest a siege round lasts before the attackers retreat; a round also ends once every attacker is down");
        siegeHpScale = d.number("mayors.siege.hp-scale", 1.0, "Multiplier on siege attacker health");
        siegeWaves = d.integer("mayors.siege.waves", 4, "Waves each siege round's attackers march in over; the boss comes with the last (TUNE)");
        siegeWaveGap = d.integer("mayors.siege.wave-gap-seconds", 60, "Longest wait between siege waves; a wave also comes early once the field is down to a quarter (TUNE)");
        giftSelfOpen = d.flag("mayors.gifts.self-open", true, "Gifts may be opened alone by sneak-right-clicking (research: must be given; TUNE for small servers)");

        jerryChance = d.number("mayors.jerry.chance", 0.004, "Gerald Sightings: chance per gather/kill that a hidden Gerald appears (TUNE)");
        stonksMinBid = d.number("mayors.stonks.min-bid", 2_000_000, "Stonks Auction minimum bid (research: 2,000,000)");

        carnivalFeastEvents = d.text("mayors.carnival.feast-events", "harvest_feast,finnegan.grand_feast",
                "Calendar event ids (or mayor perk ids) that count as the Harvest Feast for the Fortunate Feasting carnival perk");
        carnivalParrotRange = d.integer("mayors.carnival.parrot-range", 20, "Parrot Mask Party Flock range in blocks (reference: 20)");
        carnivalUndeadArmor = d.text("mayors.carnival.undead-armor", "ZOMBIE,SKELETON,REVENANT,REAPER,ROTTEN,SKELETOR,UNDEAD",
                "Zombie Mask: armor ids containing one of these words count as Undead armor (+5% stats; TUNE: the reference does not list them)");

        alertsTakeover = d.flag("mayors.alerts.takeover", true,
                "Mayors sends the per-player-filtered calendar chat ([Calendar] starts in / has started) and turns menus.event-reminders off; false hands it back to menus");
        alertsImportant = d.text("mayors.alerts.important", AlertPolicy.DEFAULT_IMPORTANT,
                "Calendar event ids announced to players on the default 'important' alert setting (comma-separated)");
        alertsOwnerAnnounced = d.text("mayors.alerts.owner-announced", AlertPolicy.DEFAULT_OWNER_ANNOUNCED,
                "Event ids whose owner sends its own start message, so no generic 'has started!' line repeats it");
        alertsPerkGates = d.text("mayors.alerts.perk-gates", AlertPolicy.DEFAULT_PERK_GATES,
                "event=perk pairs: the event is only announced while that mayor perk is on (fishing festival runs only under its perk)");
        alertsDaNear = d.number("mayors.alerts.da-near", 0.5,
                "Dark Auction reminder with its coin requirement reaches players carrying at least this share of the entry purse (0-1); nobody further below hears it");
    }
}
