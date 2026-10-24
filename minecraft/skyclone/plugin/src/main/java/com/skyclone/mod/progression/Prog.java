package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Module-wide singletons and helpers (package-private wiring; nothing outside this package uses it).
 */
final class Prog {

    private Prog() {}

    static SkyLevels levels;
    static Objectives objectives;
    static FairySouls fairy;
    static Bestiary bestiary;
    static Museum museum;
    static Cakes cakes;
    static TravelScrolls scrolls;

    static Catalogs.LevelsFile levelsFile;
    static Catalogs.BestiaryFile bestiaryFile;
    static Catalogs.MuseumFile museumFile;
    static Catalogs.ObjectivesFile objectivesFile;
    static String fairyTexture = "";

    // ---- dials (registered in ProgressionModule.enable, read at use time) -----------------------------------
    static Dials.Dial<Double> lvlHealth;
    static Dials.Dial<Integer> lvlStrengthEvery;
    static Dials.Dial<Boolean> lvlAnnounce;
    static Dials.Dial<Boolean> objEnabled;
    static Dials.Dial<Boolean> objGuideLine;
    static Dials.Dial<String> lvlQuietPrefixes;
    static Dials.Dial<Integer> lvlScrollXp;
    static Dials.Dial<Integer> objPollTicks;
    static Dials.Dial<Double> objCoinScale;
    static Dials.Dial<Double> fairyHealth;
    static Dials.Dial<Double> fairyDefense;
    static Dials.Dial<Integer> fairyStrengthEvery;
    static Dials.Dial<Integer> fairySpeedEvery;
    static Dials.Dial<Boolean> fairyAnywhere;
    static Dials.Dial<Double> fairyHitbox;
    static Dials.Dial<Integer> fairyParticleRange;
    static Dials.Dial<String> fairyExcluded;
    static Dials.Dial<Boolean> fairyDisplays;
    static Dials.Dial<Double> bestiaryDamage;
    static Dials.Dial<Double> bestiaryMilestoneHealth;
    static Dials.Dial<Double> bestiaryMilestoneCombatXp;
    static Dials.Dial<Integer> bestiaryUnknownBracket;
    static Dials.Dial<Integer> bestiaryUnknownCap;
    static Dials.Dial<Double> museumMilestoneCoins;
    static Dials.Dial<Boolean> museumRetrieve;
    static Dials.Dial<Integer> cakeHours;
    static Dials.Dial<Boolean> cakeYearly;
    // wave 5
    static Dials.Dial<Double> bestiaryMfEarly;
    static Dials.Dial<Double> bestiaryMfLate;
    static Dials.Dial<Integer> bestiaryMfSplit;
    static Dials.Dial<Double> bestiaryCoinPerTier;
    static Dials.Dial<Double> bestiaryFortunePerTier;
    static Dials.Dial<Double> bestiaryHedgehogFf;
    static Dials.Dial<Integer> bestiaryNoticeQuietTicks;
    static Dials.Dial<Integer> bestiaryNoticeMaxTicks;
    static Dials.Dial<Integer> bestiaryNoticePerLine;
    static Dials.Dial<Integer> bestiaryNoticeLines;
    static Dials.Dial<Double> daedalusDamage;
    static Dials.Dial<Double> daedalusMf;
    static Dials.Dial<Boolean> museumPickupGuard;
    static Dials.Dial<Integer> museumSpecialMax;
    static Dials.Dial<Boolean> guidesEnabled;
    static Dials.Dial<Boolean> guidesSidebar;
    static Dials.Dial<Double> guidesCoinScale;
    static Dials.Dial<Boolean> perkTome;
    static Dials.Dial<Double> perkHealingVitality;
    static Dials.Dial<Double> perkStrongarmDefense;
    static Dials.Dial<Double> perkInflictionPct;

    static void registerDials() {
        Dials d = Sky.dials();
        lvlHealth = d.number("progression.sblevel.health-per-level", 5, "Health per SkyBlock Level (research: 5)");
        lvlStrengthEvery = d.integer("progression.sblevel.strength-every", 5, "One Strength per this many SkyBlock Levels (research: 5)");
        lvlAnnounce = d.flag("progression.sblevel.announce-xp", true, "Chat line '+X SkyBlock XP (reason)' on every award");
        objEnabled = d.flag("progression.objectives.enabled", true, "Show the guided objective chain on the sidebar");
        objGuideLine = d.flag("progression.objectives.district-guide-line", true, "In a Hub district with an unfinished District Guide, keep the Objective on the sidebar (it outranks the guide) and add one line naming the guide; off = the guide section replaces the Objective there");
        lvlQuietPrefixes = d.text("progression.sblevel.quiet-prefixes", PMath.DEFAULT_QUIET_AWARDS,
                "Award-key prefixes (comma list) whose module already prints the SkyBlock XP in its own message or box, so the '+X SkyBlock XP (reason)' line is not printed a second time");
        lvlScrollXp = d.integer("progression.sblevel.travel-scroll-xp", 15, "SkyBlock XP for each distinct Travel Scroll read (research 02: 15 per scroll, task max 300)");
        objPollTicks = d.integer("progression.objectives.poll-ticks", 40, "How often (ticks) polled objectives (have item, area, requirement) are checked");
        objCoinScale = d.number("progression.objectives.coin-scale", 1.0, "Multiplier on every objective's coin reward (TUNE)");
        fairyHealth = d.number("progression.fairy.health-per-exchange", 3, "Health per 5-soul exchange (TUNE: reference moved these stats into SkyBlock Level in 0.15)");
        fairyDefense = d.number("progression.fairy.defense-per-exchange", 1, "Defense per exchange (TUNE)");
        fairyStrengthEvery = d.integer("progression.fairy.strength-every", 2, "One Strength per this many exchanges, 0 = none (TUNE)");
        fairySpeedEvery = d.integer("progression.fairy.speed-every", 10, "One Speed per this many exchanges, 0 = none (TUNE)");
        fairyAnywhere = d.flag("progression.fairy.exchange-anywhere", false, "Allow exchanging souls from /fairysouls anywhere (default: only at the fairy)");
        fairyHitbox = d.number("progression.fairy.hitbox", 0.7, "Width and height (blocks) of the click box around each Fairy Soul head (souls are collected by clicking, not walking)");
        fairyParticleRange = d.integer("progression.fairy.particle-range", 24, "Blocks within which uncollected souls sparkle");
        fairyExcluded = d.text("progression.fairy.excluded-islands", "catacombs,kuudra", "Islands whose fairy_soul anchors are ignored (comma list)");
        fairyDisplays = d.flag("progression.fairy.floating-heads", true, "Show a spinning fairy head at each soul (shared islands)");
        bestiaryDamage = d.number("progression.bestiary.damage-per-tier", 1.0, "+% damage vs a family per bestiary tier of it (TUNE)");
        bestiaryMilestoneHealth = d.number("progression.bestiary.health-per-milestone", 1.0, "Health per bestiary milestone (research: +1 per 10 tiers)");
        bestiaryMilestoneCombatXp = d.number("progression.bestiary.combat-xp-per-milestone", 1000, "Combat XP for bestiary milestone N is N x this, before Combat Wisdom; 0 = none (TUNE: research says milestones grant Combat XP, amount unknown)");
        bestiaryUnknownBracket = d.integer("progression.bestiary.default-bracket", 2, "Kill bracket (1-8) for mobs with no curated family (TUNE)");
        bestiaryUnknownCap = d.integer("progression.bestiary.default-cap", 1000, "Kill cap for mobs with no curated family (TUNE)");
        museumMilestoneCoins = d.number("progression.museum.milestone-coins-per-xp", 50, "Museum milestone coin reward = milestone XP x this (TUNE)");
        museumRetrieve = d.flag("progression.museum.allow-retrieve", true, "Members may take donated items back out (XP stays earned)");
        cakeHours = d.integer("progression.cakes.hours", 48, "Century cake buff duration in real hours (research: 48)");
        cakeYearly = d.flag("progression.cakes.new-year-cake", true, "Each New Year celebration lets every player claim one random century cake (TUNE: the reference only bakes them every 100 years)");
        // ---- wave 5 -------------------------------------------------------------------------------------
        bestiaryMfEarly = d.number("progression.bestiary.mf-per-tier-early", 2, "Magic Find vs a family for each of its first tiers (TUNE: research 08 6.1 gives +70 MF at Tier XX; 10 x 2 + 10 x 5 = 70)");
        bestiaryMfLate = d.number("progression.bestiary.mf-per-tier-late", 5, "Magic Find vs a family for each tier past progression.bestiary.mf-split (TUNE)");
        bestiaryMfSplit = d.integer("progression.bestiary.mf-split", 10, "Last tier that pays the early Magic Find rate (TUNE)");
        bestiaryCoinPerTier = d.number("progression.bestiary.coins-per-tier", 2.0, "+% coins from a family's kills per bestiary tier of it (TUNE: research '+40% coins (?)' at Tier XX)");
        bestiaryFortunePerTier = d.number("progression.bestiary.fortune-per-tier", 0.4, "Fortune per tier of a Fortune-paying family (pests: Farming Fortune; research 08: +102 FF at the pest bestiary's max)");
        bestiaryHedgehogFf = d.number("progression.bestiary.hedgehog-ff-per-pest-tier", 0.7, "Farming Fortune per pest bestiary tier while a Hedgehog pet is active (its 'Field Notes' perk, research 06: 0.7)");
        // wave-5 regression: ~10 eight-line tier-up boxes in one dungeon room. Tier-ups that land close together share one box.
        bestiaryNoticeQuietTicks = d.integer("progression.bestiary.notice-quiet-ticks", 60,
                "Bestiary tier-up boxes wait until no tier-up has landed for this many ticks, then print ONE box (the full box for a lone family, a combined one for several); 0 = one box per tier-up at once (TUNE)");
        bestiaryNoticeMaxTicks = d.integer("progression.bestiary.notice-max-ticks", 200,
                "Longest a Bestiary tier-up box waits after the first tier-up of a batch, however many follow (TUNE)");
        bestiaryNoticePerLine = d.integer("progression.bestiary.notice-per-line", 3, "Families per line in the combined Bestiary tier-up box");
        bestiaryNoticeLines = d.integer("progression.bestiary.notice-lines", 4, "Family lines in the combined Bestiary tier-up box before '...and N more'");
        daedalusDamage = d.number("progression.bestiary.daedalus-damage-per-tier", 1.0, "Daedalus Blade: +% damage vs mythological creatures per Mythological bestiary tier (research 06: 1)");
        daedalusMf = d.number("progression.bestiary.daedalus-mf-per-tier", 0.2, "Daedalus Blade: Magic Find vs mythological creatures per Mythological bestiary tier (research 06: 0.2)");
        museumPickupGuard = d.flag("progression.museum.soulbound-pickup-guard", true, "A dropped museum (co-op soulbound) item can only be picked up by a member of the co-op it belongs to");
        museumSpecialMax = d.integer("progression.museum.special-max", 48, "Most Special Items a museum may hold (research 08 6.2: 48)");
        guidesEnabled = d.flag("progression.guides.enabled", true, "Island Guides: per-island first-entry objective mini-chains");
        guidesSidebar = d.flag("progression.guides.sidebar", true, "On an island with an unfinished Island Guide, the sidebar Objective shows the guide's step (like the reference's district objectives)");
        guidesCoinScale = d.number("progression.guides.coin-scale", 1.0, "Multiplier on every Island Guide's coin reward (TUNE)");
        perkTome = d.flag("progression.sblevel.tome-reward", true, "Hand the Tome of Progress (BOOK_OF_PROGRESSION) to players who reach SkyBlock Level 25 (research 02 6.2)");
        perkHealingVitality = d.number("progression.sblevel.stacked-healing-vitality", 5, "Stacked Healing (SkyBlock Level 25): Vitality granted (TUNE: research names the bonus, not its size)");
        perkStrongarmDefense = d.number("progression.sblevel.strongarm-defense", 10, "Defensive Strongarm (SkyBlock Level 50): Defense granted (TUNE)");
        perkInflictionPct = d.number("progression.sblevel.infliction-pct", 1.0, "Extra Infliction (SkyBlock Level 75): % more damage dealt to mobs, applied last (TUNE; research 01: a multiplier applied after the cap)");
    }

    static PData.Guides guides(Player p) { return Sky.profiles().member(p, PData.KEY_GUIDES, PData.Guides.class); }
    static PData.Perks perks(Player p) { return Sky.profiles().member(p, PData.KEY_PERKS, PData.Perks.class); }
    static Catalogs.GuidesFile guidesFile;
    static IslandGuides islandGuides;
    static Perks perksSys;
    /** "Quickest SkyBlock XP for you right now" (wave-5 regression, early pacing). */
    static XpGuide xpGuide;

    // ---- data access (re-fetch every time: CORE_GUIDE trap 2) ----------------------------------------------

    static boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    static PData.Level level(Player p) { return Sky.profiles().member(p, PData.KEY_LEVEL, PData.Level.class); }
    static PData.Objectives obj(Player p) { return Sky.profiles().member(p, PData.KEY_OBJECTIVES, PData.Objectives.class); }
    static PData.Fairy fairy(Player p) { return Sky.profiles().member(p, PData.KEY_FAIRY, PData.Fairy.class); }
    static PData.Bestiary best(Player p) { return Sky.profiles().member(p, PData.KEY_BESTIARY, PData.Bestiary.class); }
    static PData.Cakes cakes(Player p) { return Sky.profiles().member(p, PData.KEY_CAKES, PData.Cakes.class); }
    static PData.Museum museum(Player p) { return Sky.profiles().shared(p, PData.KEY_MUSEUM, PData.Museum.class); }

    static void award(Player p, String key, int xp, String reason) {
        if (levels != null) levels.award(p, key, xp, reason);
    }

    // ---- resources -------------------------------------------------------------------------------------------

    static <T> T resource(String file, Class<T> type) {
        try (InputStream in = Sky.plugin().getResource("data/progression/" + file)) {
            if (in == null) throw new IllegalStateException("missing resource data/progression/" + file);
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                T t = Catalogs.read(r, type);
                if (t == null) throw new IllegalStateException("empty resource data/progression/" + file);
                return t;
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read data/progression/" + file, e);
        }
    }

    // ---- ui helpers ------------------------------------------------------------------------------------------

    /** "&2&l&m      &f&l&m    &r &e12&6/&e40" */
    static String bar(double cur, double max) {
        double f = max <= 0 ? 1 : cur / max;
        return Text.bar(f, 20) + " &e" + Text.commasInt(cur) + "&6/&e" + Text.shortNum(max);
    }

    static String pct(double cur, double max) {
        if (max <= 0) return "&a100%";
        double v = Math.max(0, Math.min(100, cur * 100.0 / max));
        return "&e" + (v == Math.floor(v) ? String.valueOf((int) v) : String.format(java.util.Locale.US, "%.1f", v)) + "&6%";
    }

    static Material mat(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name);
        return m == null || !m.isItem() ? def : m;
    }

    /** Go Back to the SkyBlock Menu (menus module); falls back to closing. */
    static void backToSkyMenu(Menu m) {
        m.set(48, Icons.back("SkyBlock Menu"), c -> {
            if (Sky.has(Features.SkyMenu.class)) Sky.service(Features.SkyMenu.class).open(c.player());
            else c.player().closeInventory();
        });
    }
}
