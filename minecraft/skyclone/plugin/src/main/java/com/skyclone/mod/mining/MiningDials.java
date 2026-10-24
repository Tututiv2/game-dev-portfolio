package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every tunable of the mining module. Read with .get() at use time. */
public final class MiningDials {

    public final Dials.Dial<Boolean> engine;
    public final Dials.Dial<Boolean> oreAreasOnly;
    public final Dials.Dial<Boolean> allowSafeZones;
    public final Dials.Dial<Double> handSpeed;
    public final Map<String, Dials.Dial<Integer>> regen = new LinkedHashMap<>();
    public final Dials.Dial<Integer> hubMineMargin;
    public final Dials.Dial<Double> powderMult;
    public final Dials.Dial<Double> xpMult;
    public final Dials.Dial<Double> dropMult;
    public final Dials.Dial<Double> goblinChance;
    public final Dials.Dial<Double> diamondGoblinRatio;
    public final Dials.Dial<Double> starChance;
    public final Dials.Dial<Double> ghastChance;
    public final Dials.Dial<Double> chestChance;
    public final Dials.Dial<Integer> chestLocks;
    public final Dials.Dial<Double> wormChance;
    public final Dials.Dial<Double> scathaRatio;
    public final Dials.Dial<Double> crystalChance;
    public final Dials.Dial<Double> scrapChance;
    public final Dials.Dial<Integer> mineshaftPity;
    public final Dials.Dial<Boolean> mineshafts;
    public final Dials.Dial<Boolean> events;
    public final Dials.Dial<Integer> eventInterval;
    public final Dials.Dial<Integer> eventDuration;
    public final Dials.Dial<Double> windBonus;
    public final Dials.Dial<Double> togetherSpeed;
    public final Dials.Dial<Double> togetherFortune;
    public final Dials.Dial<Double> raffleChance;
    public final Dials.Dial<Double> gourmandChance;
    public final Dials.Dial<Integer> gourmandTarget;
    public final Dials.Dial<Integer> raidCap;
    public final Dials.Dial<Integer> raidWave;
    public final Dials.Dial<Integer> raidHeavyMax;
    public final Dials.Dial<Double> hotmResetCost;
    public final Dials.Dial<Double> forgeTimeMult;
    public final Dials.Dial<Integer> kingDailyPowder;
    public final Dials.Dial<Integer> fetchurPowder;
    public final Dials.Dial<Integer> fetchurCoins;
    public final Dials.Dial<Integer> puzzlerPowder;
    public final Dials.Dial<Integer> coldSeconds;
    public final Dials.Dial<Integer> coldBlocks;
    public final Dials.Dial<Integer> heatY;
    public final Dials.Dial<Integer> heatSeconds;
    public final Dials.Dial<String> fiestaEvent;
    public final Dials.Dial<String> fiestaPerk;
    public final Dials.Dial<String> moltenForgePerk;
    public final Dials.Dial<Integer> commissionSlots;
    public final Dials.Dial<Boolean> claimAnywhere;
    public final Dials.Dial<Double> abilityCooldownMult;
    public final Dials.Dial<Integer> sidebarPriority;
    public final Dials.Dial<Boolean> sidebarCommissions;
    public final Dials.Dial<Integer> tabPriority;
    public final Dials.Dial<Double> hollowsHeatDamage;
    public final Dials.Dial<Integer> nucleusRadius;
    public final Dials.Dial<Double> commissionXpMult;
    public final Dials.Dial<Double> commissionBonusChance;
    public final Dials.Dial<Integer> fossilPowder;
    public final Dials.Dial<Integer> fossilSkyblockXp;
    public final Dials.Dial<Boolean> hideImpossibleCommissions;
    public final Dials.Dial<Boolean> islandXp;
    public final Dials.Dial<Boolean> islandFortune;
    public final Dials.Dial<Boolean> reforgesExtraRoll;
    public final Dials.Dial<Boolean> islandSpeed;
    public final Dials.Dial<Double> gauntletSpillMult;
    public final Dials.Dial<Double> gauntletSpillCooldown;
    public final Dials.Dial<Double> chestZoneBoost;
    public final Dials.Dial<Double> chestOffZone;
    public final Dials.Dial<Integer> coldPerHit;
    public final Dials.Dial<Integer> mineshaftGuardians;
    public final Dials.Dial<Boolean> oreExp;
    /** The ENCHANTING module's dial, read by key: while true it rolls Paleontologist's bonus scrap itself. */
    public final Dials.Dial<Boolean> paleontologistFallback;
    /** Owned by enchanting ({@code enchanting.ore-exp}): while on, enchanting pays experience on every MINING Gather. */
    public final Dials.Dial<Boolean> enchantingOreExp;
    public final Dials.Dial<Boolean> endQuarrySpeed;
    public final Dials.Dial<Integer> muncherPowder;
    public final Dials.Dial<Integer> muncherEssence;
    // ---- real-game audit 2026-10-06 (lane 3) ----
    public final Dials.Dial<Integer> wormCooldown;
    public final Dials.Dial<Double> fiestaFortunePct;
    public final Dials.Dial<Double> fiestaWisdom;
    public final Dials.Dial<Double> fiestaDropDivisor;
    public final Dials.Dial<Double> fiestaDropMult;
    public final Dials.Dial<Double> coldSpeedPerPoint;
    public final Dials.Dial<Integer> forgeUnlockTier;
    public final Dials.Dial<Integer> forgeMaxSlots;
    public final Dials.Dial<Boolean> forgeCoreSlot;

    MiningDials() {
        Dials d = Sky.dials();
        engine = d.flag("mining.engine", true, "Custom block breaking on mining islands (Mining Speed vs Block Strength)");
        oreAreasOnly = d.flag("mining.ore-areas-only", false, "Only blocks inside ores.* areas (and the hub coal_mine) can be mined at all");
        allowSafeZones = d.flag("mining.allow-safe-zones", false, "Allow mining inside SAFE zones (towns)");
        handSpeed = d.number("mining.hand-speed", 30, "Mining Speed used for Breaking-Power-0 blocks when the player has none (TUNE)");
        String[][] regenDefaults = {{"hub", "8"}, {"gold_mine", "8"}, {"deep_caverns", "8"}, {"dwarven_mines", "5"},
                {"crystal_hollows", "120"}, {"glacite_tunnels", "20"}, {"mineshaft", "0"}};
        for (String[] r : regenDefaults)
            regen.put(r[0], d.integer("mining.regen." + r[0], Integer.parseInt(r[1]),
                    "Seconds before a mined block on " + r[0] + " grows back (0 = never; research 2.5, crystal caverns TUNE)"));
        hubMineMargin = d.integer("mining.hub-mine-margin", 6, "Blocks around the hub coal_mine area where custom breaking applies");
        powderMult = d.number("mining.powder-mult", 1.0, "Global multiplier on all powder earned");
        xpMult = d.number("mining.xp-mult", 1.0, "Global multiplier on Mining XP from blocks");
        dropMult = d.number("mining.drop-mult", 1.0, "Global multiplier on base block drops");
        goblinChance = d.number("mining.goblin-chance", 0.0004, "Chance per Dwarven Mines block of a treasure goblin (TUNE, x Cave Luck)");
        diamondGoblinRatio = d.number("mining.diamond-goblin-ratio", 0.1, "Share of treasure goblins that are Diamond Goblins (TUNE)");
        starChance = d.number("mining.star-chance", 0.00005, "Chance per Dwarven Mines block to call down a Fallen Star (TUNE)");
        ghastChance = d.number("mining.ghast-chance", 0.00005, "Chance per Dwarven Mines block to summon a Powder Ghast (TUNE)");
        chestChance = d.number("mining.chest-chance", 0.002, "Chance per Hard Stone block of a treasure chest (wiki /Treasure_Chest: 1/500, x Great Explorer)");
        chestLocks = d.integer("mining.chest-locks", 5, "Locks on a treasure chest before Great Explorer (research 2.11)");
        wormChance = d.number("mining.worm-chance", 1.0 / 600, "Chance per Hard Stone block of a Cave Worm in the crystal caverns (wiki /Scatha: 1/600)");
        scathaRatio = d.number("mining.scatha-ratio", 0.2, "Share of worms that are Tunnel Wyrms (wiki /Scatha: 20%)");
        crystalChance = d.number("mining.crystal-chance", 0.0007, "Chance per gem block to find that gem's Crystal (TUNE)");
        scrapChance = d.number("mining.scrap-chance", 0.001, "Chance per block of Suspicious Scrap in the frozen tunnels/mineshafts (TUNE)");
        mineshaftPity = d.integer("mining.mineshaft-pity", 2000, "Mineshaft pity counter start (research 2.13)");
        mineshafts = d.flag("mining.mineshafts", true, "Frozen mineshafts can open while mining the tunnels");
        events = d.flag("mining.events", true, "Mining events run on a timer on the mining islands");
        eventInterval = d.integer("mining.event-interval-minutes", 20, "Minutes between mining events per island (research 2.10: one SkyBlock day)");
        eventDuration = d.integer("mining.event-duration-minutes", 10, "Length of a mining event in minutes (research 2.10)");
        windBonus = d.number("mining.wind-bonus", 25, "Tailwind event: % Mining Speed while facing the wind (TUNE)");
        togetherSpeed = d.number("mining.together-speed", 50, "Better Together: Mining Speed per nearby miner, up to 5 (TUNE)");
        togetherFortune = d.number("mining.together-fortune", 5, "Better Together: Mining Fortune per nearby miner, up to 5 (TUNE)");
        raffleChance = d.number("mining.raffle-chance", 0.02, "Raffle: chance per block of a Raffle Ticket (TUNE)");
        gourmandChance = d.number("mining.gourmand-chance", 0.05, "Mithril Feast: chance per mithril block of a Tasty Mithril (TUNE)");
        gourmandTarget = d.integer("mining.gourmand-target", 200, "Mithril Feast: Tasty Mithril to hand to the King (research 2.10)");
        raidCap = d.integer("mining.raid-cap", 20, "Goblin Raid: most raiders alive at once in the raided district (TUNE)");
        raidWave = d.integer("mining.raid-wave", 6, "Goblin Raid: raiders added every 15 s while someone is in the raided district (TUNE)");
        raidHeavyMax = d.integer("mining.raid-heavy-max", 1, "Goblin Raid: most Lv90+ raiders (Creepertamer, Cutthroat, Golem) alive at once (TUNE; 0 = weaklings and Battlers only)");
        hotmResetCost = d.number("mining.hotm-reset-cost", 1_000_000, "Coins to reset the Heart of the Mountain (TUNE, research says (?))");
        forgeTimeMult = d.number("mining.forge-time-mult", 1.0, "Multiplier on every forge duration");
        kingDailyPowder = d.integer("mining.king-daily-powder", 1000, "Mithril Powder from the King once per day (TUNE)");
        fetchurPowder = d.integer("mining.fetchur-powder", 2500, "Fetcher's daily reward in Mithril Powder (research 2.9)");
        fetchurCoins = d.integer("mining.fetchur-coins", 20000, "Fetcher's daily coin reward (research 2.9)");
        puzzlerPowder = d.integer("mining.puzzler-powder", 2500, "Riddlewright's daily reward in Mithril Powder (research 2.9)");
        coldSeconds = d.integer("mining.cold-seconds", 10, "Seconds per point of Cold in the frozen tunnels (TUNE)");
        coldBlocks = d.integer("mining.cold-blocks", 30, "Blocks mined per extra point of Cold (TUNE)");
        heatY = d.integer("mining.heat-y", 64, "Below this Y the crystal caverns heat you up (TUNE)");
        heatSeconds = d.integer("mining.heat-seconds", 2, "Seconds per point of Heat below the heat line (TUNE)");
        fiestaEvent = d.text("mining.fiesta-event", "mining_fiesta", "Calendar event id of the Mining Jubilee");
        fiestaPerk = d.text("mining.fiesta-perk", "mining_fiesta", "Mayor perk id of the Mining Jubilee");
        moltenForgePerk = d.text("mining.molten-forge-perk", "molten_forge", "Mayor perk id that cuts forge time by 25%");
        commissionSlots = d.integer("mining.commission-slots", 2, "Commission slots before milestones and the Core (research 2.8)");
        claimAnywhere = d.flag("mining.claim-anywhere", false, "Claim finished commissions from anywhere without the Royal Pigeon");
        abilityCooldownMult = d.number("mining.ability-cooldown-mult", 1.0, "Multiplier on pickaxe ability cooldowns");
        sidebarPriority = d.integer("mining.sidebar-priority", 300, "Sidebar section priority of the powder line");
        sidebarCommissions = d.flag("mining.sidebar-commissions", true,
                "Show commission progress (one line, slot order: DONE / 45%) under the powder line on commission islands");
        // 105 = straight after core's Account Info (0), before every other module's widget (skills 110 up). The tab
        // footer holds ui.tab-max-lines (40) lines and drops any widget that would overflow it; at the old 300 a
        // mid-game player's skills/stats/bank/slayer/minions/event widgets filled it first and Commissions never showed.
        // Both widgets are empty off the mining islands, so going first costs nothing anywhere else.
        tabPriority = d.integer("mining.tab-priority", 105,
                "Tab-list position of the Commissions widget (Powders = this + 1); lower = earlier. Over ~150 it gets crowded out");
        hollowsHeatDamage = d.number("mining.heat-damage", 0.05, "Fraction of max HP lost per second at 100 Heat");
        nucleusRadius = d.integer("mining.nucleus-radius", 10, "Blocks from the Nucleus anchor where crystals can be placed");
        commissionXpMult = d.number("mining.commission-xp-mult", 1.0, "Multiplier on HOTM XP from commissions");
        commissionBonusChance = d.number("mining.commission-bonus-chance", 0.3,
                "Chance each for a Rimestone Jewel and a Hoardite on a Dwarven Mines commission (TUNE: keeps drill parts reachable even if no mob drops them)");
        fossilPowder = d.integer("mining.fossil-donation-powder", 10000, "Rimestone Powder for donating a fossil kind the first time (TUNE)");
        fossilSkyblockXp = d.integer("mining.fossil-donation-sbxp", 10, "SkyBlock XP for donating a fossil kind the first time (TUNE)");
        hideImpossibleCommissions = d.flag("mining.hide-impossible-commissions", true,
                "Never hand out a commission whose mob or item does not exist on this server (e.g. an island not built yet)");
        islandXp = d.flag("mining.island-xp", true,
                "Stone, cobble and ores that grew on a private island (not player-placed) pay Mining XP like the hub mine");
        islandFortune = d.flag("mining.island-fortune", true,
                "Mining Fortune rolls extra drops for natural blocks mined on a private island");
        reforgesExtraRoll = d.flag("mining.reforges-extra-roll", true,
                "The reforges module still rolls its own extra Ambered goblin / Blazing worm (Bonuses.rareFinds): mining takes that share "
                        + "off its own roll so the bonus is never paid twice. Set false once reforges deletes that roll");
        // ---- wave 5 ----
        islandSpeed = d.flag("mining.island-speed", true,
                "On the private island a pickaxe/drill/gauntlet breaks mining blocks at Mining Speed vs Block Strength (not vanilla tool speed)");
        gauntletSpillMult = d.number("mining.gauntlet-spill-mult", 1.0,
                "Gemstone Gauntlet: rough gems spilled per slotted gem per hit = (quality step 1-5) x this (TUNE: research gives no amount)");
        gauntletSpillCooldown = d.number("mining.gauntlet-spill-cooldown", 2.0, "Gemstone Gauntlet: seconds between gem spills (reference cooldown 2s)");
        chestZoneBoost = d.number("mining.chest-zone-boost", 3.0,
                "Treasure chests: a zone's own loot (robot parts in the relic ruins, goblin eggs in the holdout...) is this many times as likely there (TUNE)");
        chestOffZone = d.number("mining.chest-off-zone", 0.25, "Treasure chests: weight multiplier of zone loot in the OTHER zones (TUNE)");
        coldPerHit = d.integer("mining.cold-per-hit", 1, "Cold gained when a mob hits you inside a frozen mineshaft (TUNE; 0 = never)");
        mineshaftGuardians = d.integer("mining.mineshaft-guardians", 2,
                "Most Rimestone guardians (hollows' gt_glacite_* mobs) standing over the corpses of a new mineshaft; 1..this spawn (TUNE; 0 = none)");
        oreExp = d.flag("mining.ore-exp", true, "Coal/lapis/redstone/diamond/emerald/quartz ores give their vanilla experience on the mining islands");
        muncherPowder = d.integer("mining.muncher-powder", 25000, "Fossil Muncher: Rimestone Powder for the daily fossil (research 03/07: 25,000)");
        muncherEssence = d.integer("mining.muncher-essence", 100, "Fossil Muncher: Fossil Essence for the daily fossil (research 03/07: 100)");
        // owned by enchanting (registered before us, so this returns its dial): exactly one side pays Paleontologist
        paleontologistFallback = d.flag("enchanting.paleontologist-fallback", true,
                "Paleontologist rolls its own bonus Suspicious Scrap in glacite mineshafts. Turn OFF the day mining folds the % into its own scrap roll (else it pays twice)");
        // owned by enchanting: while it is on, enchanting pays ore experience on our Gather and mining pays only the
        // Lapis Pickaxe's doubling (so the experience is paid exactly once at either setting)
        enchantingOreExp = d.flag("enchanting.ore-exp", true, "Mining ores gives vanilla experience");
        // ---- real-game audit 2026-10-06 (docs/AUDIT_REAL_GAME_2026-10-06.md section 2) ----
        wormCooldown = d.integer("mining.worm-cooldown-seconds", 30, "Seconds after a worm spawns before the same player can spawn another (wiki /Scatha: 30)");
        fiestaFortunePct = d.number("mining.fiesta-fortune-pct", 25, "Mining Jubilee: % more Mining Fortune on the mining islands (Aug-2026 rework: 25)");
        fiestaWisdom = d.number("mining.fiesta-wisdom", 25, "Mining Jubilee: Mining Wisdom on the mining islands (Aug-2026 rework: 25)");
        fiestaDropDivisor = d.number("mining.fiesta-drop-divisor", 10_000,
                "Mining Jubilee: a Refined Mineral / Glossy Gemstone drops with chance Breaking Power / this per block (Aug-2026 rework: 10,000; was 2,000)");
        fiestaDropMult = d.number("mining.fiesta-drop-mult", 1.0, "Mining Jubilee: multiplier on base block drops (1 = none; the old event doubled them)");
        coldSpeedPerPoint = d.number("mining.cold-speed-per-point", 0.5, "Cold: % Mining Speed lost per point of Cold (wiki: 0.5, so -25% at 50)");
        forgeUnlockTier = d.integer("mining.forge-unlock-tier", 2, "Heart of the Mountain tier that opens the Forge (wiki /The_Forge: 2)");
        forgeMaxSlots = d.integer("mining.forge-max-slots", 7, "Forge slots: one per HOTM tier from the unlock tier, up to this many (wiki: 2 at HOTM 2 .. 7 at HOTM 7)");
        forgeCoreSlot = d.flag("mining.forge-core-slot", false,
                "Core of the Mountain II adds one more forge slot on top of the tier slots (old rule; the wiki's 7-at-HOTM-7 has no Core slot)");
        endQuarrySpeed = d.flag("mining.end-quarry-speed", true,
                "End Stone in the End's quarry (combatzones' area endstone.quarry) breaks at Mining Speed (strength 30) with a pickaxe/drill/gauntlet");
    }

    public int regenSeconds(String island) {
        Dials.Dial<Integer> d = regen.get(island);
        return d == null ? 10 : d.get();
    }
}
