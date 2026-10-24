package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.time.SkyDate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The bank module's shared state: data tables, the store, every dial, and the per-player data accessors.
 * Static on purpose (one module instance per server); every other class of the module reads from here.
 */
final class Bank {

    private Bank() {}

    static final String MEMBER_KEY = "bank";
    static final String SHARED_KEY = "bank_account";

    static BankCatalog catalog;
    static final BankStore store = new BankStore();

    // ---- dials (registered in BankModule.enable) -----------------------------------------------------
    static Dials.Dial<Integer> interestMonths;
    static Dials.Dial<Double> interestMultiplier;
    static Dials.Dial<Integer> interestCatchup;
    static Dials.Dial<Integer> interestCheckSeconds;
    static Dials.Dial<Boolean> interestRespectsCap;
    static Dials.Dial<Double> personalFraction;
    static Dials.Dial<Double> personalInterestScale;
    static Dials.Dial<Boolean> personalSolo;
    static Dials.Dial<Boolean> remoteAnywhere;
    static Dials.Dial<String> remoteRequirement;
    static Dials.Dial<Double> bankerRange;
    static Dials.Dial<Integer> pocketCooldown;
    static Dials.Dial<Double> upgradeGoldScale;
    static Dials.Dial<Integer> upgradeMuseumPerMilestone;
    static Dials.Dial<Double> upgradeCostScale;
    static Dials.Dial<Integer> historySize;

    static Dials.Dial<Double> cookieHours;
    static Dials.Dial<Double> cookiePrice;
    static Dials.Dial<Boolean> cookieFollowsBazaar;
    static Dials.Dial<Integer> cookieBitsPool;
    static Dials.Dial<Integer> cookieBitsPerPayout;
    static Dials.Dial<Integer> cookiePayoutMinutes;
    static Dials.Dial<Double> cookieWisdom;
    static Dials.Dial<Double> cookieMagicFind;
    static Dials.Dial<Boolean> cookieKeepCoins;
    static Dials.Dial<Boolean> cookieFly;
    static Dials.Dial<Boolean> cookieFreeGift;
    static Dials.Dial<Double> bitsTalismanChance;
    static Dials.Dial<String> upgradeMuseumKey;

    static Dials.Dial<Boolean> cshopRemote;
    static Dials.Dial<Double> coinsPerGem;
    static Dials.Dial<Double> buildTimeScale;
    static Dials.Dial<Integer> confirmBits;

    static Dials.Dial<Boolean> tradeEnabled;
    static Dials.Dial<Double> tradeMaxDistance;
    static Dials.Dial<Double> tradeBreakDistance;
    static Dials.Dial<Integer> tradeRequestSeconds;
    static Dials.Dial<Double> tradeAcceptCooldown;
    static Dials.Dial<Double> tradeCapLow;
    static Dials.Dial<Double> tradeCapMid;
    static Dials.Dial<Double> tradeCapHigh;
    static Dials.Dial<Integer> tradeCapMidLevel;
    static Dials.Dial<Integer> tradeCapHighLevel;
    static Dials.Dial<Boolean> tradeClickPlayer;
    static Dials.Dial<Integer> tradeSlots;
    static Dials.Dial<String> tradeRequirement;
    static Dials.Dial<Integer> tradeRequestCooldown;
    static Dials.Dial<Double> tradeConfirmFirstCoins;
    static Dials.Dial<Double> tradeConfirmSeconds;
    static Dials.Dial<String> cshopLiveUpgrades;
    static Dials.Dial<Boolean> cshopSellUnread;

    // wave 5
    static Dials.Dial<Double> interestMuseumBonus;
    static Dials.Dial<Integer> interestMuseumMax;
    static Dials.Dial<Boolean> fameOtherSources;
    static Dials.Dial<String> fameSources;
    static Dials.Dial<String> fameExclude;
    static Dials.Dial<String> fameSelfApplying;
    static Dials.Dial<Boolean> fameLearnCallers;
    static Dials.Dial<Boolean> fameNotify;
    static Dials.Dial<Double> famePerGem;
    static Dials.Dial<Double> cookieGems;
    static Dials.Dial<Boolean> cshopAutoLive;
    static Dials.Dial<Integer> coopBase;
    static Dials.Dial<Boolean> coopEnforce;
    static Dials.Dial<Boolean> coopRaiseCoreCap;

    static void registerDials() {
        Dials d = Sky.dials();
        interestMonths = d.integer("bank.interest-months", 3, "In-game months between interest payouts (research: every season = 3 months = 31 real hours)");
        interestMultiplier = d.number("bank.interest-multiplier", 1.0, "Multiplier on every interest payout (1.0 = research bands)");
        interestCatchup = d.integer("bank.interest-max-catchup", 12, "Most missed interest payouts paid at once after a long absence (TUNE)");
        interestCheckSeconds = d.integer("bank.interest-check-seconds", 20, "How often online players are checked for a due interest payout");
        interestRespectsCap = d.flag("bank.interest-respects-cap", true, "Interest never pushes a balance past the account capacity (TUNE)");
        personalFraction = d.number("bank.personal.capacity-fraction", 0.2, "Personal account capacity as a fraction of the co-op account's (research: 1/5)");
        personalInterestScale = d.number("bank.personal.interest-scale", 0.2, "Personal accounts earn interest on bands this much as wide (research: about 1/5; TUNE)");
        personalSolo = d.flag("bank.personal.solo", false, "Offer a personal account on solo profiles too (genre: co-op only)");
        remoteAnywhere = d.flag("bank.remote-anywhere", false, "Let /bank open from anywhere for everyone (genre: banker, Pocket Bank, or the Boon Buff)");
        remoteRequirement = d.text("bank.remote-requirement", "collection:EMERALD:6", "Extra requirement for /bank from anywhere with the Boon Buff (research: Emerald VI); blank = none");
        bankerRange = d.number("bank.banker-range", 8.0, "Blocks from a banker within which /bank works without the buff");
        pocketCooldown = d.integer("bank.pocket.cooldown-seconds", 5, "Cooldown of the Pocket Bank between uses (TUNE)");
        upgradeGoldScale = d.number("bank.upgrade.gold-scale", 1.0, "Multiplier on the Gold collection count each account tier asks for (0 = no gold gate)");
        upgradeMuseumPerMilestone = d.integer("bank.upgrade.museum-per-milestone", 5, "Museum donations per museum milestone asked by account tiers (0 = no museum gate; TUNE)");
        upgradeMuseumKey = d.text("bank.upgrade.museum-key", "museummilestone:{milestone}",
                "Requirement key for an account tier's museum milestone; {milestone} = the research milestone, {donations} = milestone x museum-per-milestone"
                        + " (falls back to museum:{donations} while nothing resolves the key's prefix)");
        upgradeCostScale = d.number("bank.upgrade.cost-scale", 1.0, "Multiplier on account upgrade coin costs");
        historySize = d.integer("bank.history-size", 10, "Transactions shown in Recent Transactions (research: last ~10)");

        cookieHours = d.number("bank.cookie.hours", 96, "Hours of Boon Buff per cookie (research: 4 days)");
        cookiePrice = d.number("bank.cookie.price", 2_000_000, "Coin price of a Boon Cookie at the Community Shop (research 6.5: ~2M; TUNE)");
        cookieFollowsBazaar = d.flag("bank.cookie.price-follows-bazaar", true,
                "The Community Shop never sells a cookie below what the Bazaar pays for one (stops buy-here-sell-there coin printing)");
        cookieBitsPool = d.integer("bank.cookie.bits-pool", 4800, "Bits added to the available pool per cookie, before the fame multiplier (research: 4,800)");
        cookieBitsPerPayout = d.integer("bank.cookie.bits-per-payout", 250, "Bits paid per payout, before the fame multiplier (research: 250)");
        cookiePayoutMinutes = d.integer("bank.cookie.payout-minutes", 30, "Real minutes between bits payouts (research: 30)");
        cookieWisdom = d.number("bank.cookie.wisdom", 25, "Wisdom added to every skill's Wisdom stat by the buff (research: +25)");
        cookieMagicFind = d.number("bank.cookie.magic-find", 15, "Magic Find added by the buff (research: +15)");
        cookieKeepCoins = d.flag("bank.cookie.keep-coins", true, "The buff keeps the purse on death (research: yes)");
        cookieFly = d.flag("bank.cookie.fly", true, "The buff lets you fly on your private island and garden (research: permafly)");
        bitsTalismanChance = d.number("bank.cookie.bits-talisman-chance", 0.10,
                "Chance a Boon Buff bits payout is doubled by the Bits Talisman (research: 10%). Only used when the accessories module is absent: it doubles EVERY bits gain itself");
        cookieFreeGift = d.flag("bank.cookie.free-gift", true, "Every player may claim one free (untradeable) Boon Cookie at the Community Shop (research: one free per account)");

        cshopRemote = d.flag("bank.cshop.remote", true, "/cshop opens the Community Shop anywhere (genre: only at the NPC)");
        coinsPerGem = d.number("bank.cshop.coins-per-gem", 6150, "Coins charged per reference 'gem' on upgrades (cookie 325 gems ~ 2M coins; TUNE)");
        buildTimeScale = d.number("bank.cshop.build-time-scale", 1.0, "Multiplier on Community Shop upgrade build times (0 = instant)");
        confirmBits = d.integer("bank.cshop.confirm-bits", 5000, "Bits purchases at or above this price ask for confirmation");

        tradeEnabled = d.flag("bank.trade.enabled", true, "Player-to-player /trade");
        tradeMaxDistance = d.number("bank.trade.max-distance", 9, "Max blocks between players to send/accept a trade (research: 9)");
        tradeBreakDistance = d.number("bank.trade.break-distance", 16, "An open trade is cancelled when the players get further apart than this (TUNE)");
        tradeRequestSeconds = d.integer("bank.trade.request-seconds", 60, "Seconds before a trade request expires (research: ~60)");
        tradeAcceptCooldown = d.number("bank.trade.accept-cooldown-seconds", 3, "Seconds the Accept button is locked after any change (research: ~3)");
        tradeCapLow = d.number("bank.trade.cap-low", 50_000_000, "Daily coins a player may send by trade below the mid level (research: 50M)");
        tradeCapMid = d.number("bank.trade.cap-mid", 1_000_000_000, "Daily trade coin cap from the mid level (research: 1B at SkyBlock 50)");
        tradeCapHigh = d.number("bank.trade.cap-high", 10_000_000_000.0, "Daily trade coin cap from the high level (research: 10B at SkyBlock 100)");
        tradeCapMidLevel = d.integer("bank.trade.cap-mid-level", 50, "SkyBlock level for the mid trade cap (research: 50)");
        tradeCapHighLevel = d.integer("bank.trade.cap-high-level", 100, "SkyBlock level for the high trade cap (research: 100)");
        tradeClickPlayer = d.flag("bank.trade.click-player", true, "Right-clicking another player sends them a trade request");
        tradeSlots = d.integer("bank.trade.slots", 16, "Item slots per side of a trade (research: 16)");
        tradeRequirement = d.text("bank.trade.requirement", "", "Requirement key to use /trade (blank = everyone)");
        tradeRequestCooldown = d.integer("bank.trade.request-cooldown-seconds", 3, "Seconds between trade requests from one player");
        tradeConfirmFirstCoins = d.number("bank.trade.confirm-first-coins", 1_000_000,
                "Paying at least this many coins in a FIRST trade with someone asks for a second Accept click (0 = never; research 1.8)");
        tradeConfirmSeconds = d.number("bank.trade.confirm-seconds", 5, "Seconds the second Accept click of a risky trade stays armed");
        cshopLiveUpgrades = d.text("bank.cshop.live-upgrades",
                "coins_allowance,magic_find,garden_farming_fortune,island_size,guests_limit,minion_slots,bazaar_flipper,"
                        + "armor_wardrobe,equipment_wardrobe,ender_chest_pages,sack_of_sacks",
                "Community Shop upgrades that DO something in this world (this module, island, minions, bazaar, storage read them). Also live without being listed: any upgrade another module was seen reading (bank.cshop.auto-live) and Co-op Slots");
        cshopSellUnread = d.flag("bank.cshop.sell-unread-upgrades", false,
                "Let players build Community Shop upgrades nothing reads yet (they would pay for a tier that does nothing)");

        // ---- wave 5 ----
        interestMuseumBonus = d.number("bank.interest.museum-bonus", 0.02,
                "Interest bonus per Museum milestone, as a fraction of the payout (research 1.3: +2% per milestone)");
        interestMuseumMax = d.integer("bank.interest.museum-max-milestones", 36,
                "Most Museum milestones that count for the interest bonus (research: +72% = 36, one source says +60% = 30; TUNE; 0 = no cap)");
        fameOtherSources = d.flag("bank.fame.other-sources", true,
                "Bits from other sources (slayer, Jacob, pests, bosses...) also get the fame-rank multiplier (research 6.2: 'all x fame multiplier')");
        fameSources = d.text("bank.fame.bits-sources", "*",
                "Ledger-reason prefixes (module ids) whose bits get the fame multiplier, comma list; * = every source not excluded");
        fameExclude = d.text("bank.fame.bits-exclude", "admin,refund,npc-buy,restore,rollback,trade",
                "Bits gains whose ledger reason contains any of these words never get the fame multiplier (refunds, admin grants)");
        fameSelfApplying = d.text("bank.fame.self-applying", "",
                "Modules (ledger-reason prefixes) that already multiply their own bits by Features.Bank.bitsMultiplier; the bank skips them");
        fameLearnCallers = d.flag("bank.fame.learn-callers", true,
                "A module that calls Features.Bank.bitsMultiplier is assumed to apply it itself, and the bank stops adding the bonus to its bits");
        fameNotify = d.flag("bank.fame.notify", true, "Tell the player when their fame rank added bits to a reward");
        famePerGem = d.number("bank.cshop.fame-per-gem", 200,
                "Fame per reference 'gem' of value bought with coins at the Community Shop (research 6.3: 200 Fame per gem spent; cookie = 325 gems; TUNE)");
        cookieGems = d.number("bank.cookie.gems", 325, "Reference gem value of one Boon Cookie, for the Fame it earns (research 6.1: 325 gems)");
        cshopAutoLive = d.flag("bank.cshop.auto-live", true,
                "An upgrade becomes buildable as soon as another module is seen reading its cshop:<id>:<tier> key (remembered in bank_cshop_readers)");
        coopBase = d.integer("bank.cshop.coop-base", 5, "Co-op members a profile may have without Co-op Slots (research: 5; Co-op Slots I-III add 1-3)");
        coopEnforce = d.flag("bank.cshop.coop-enforce", true, "The bank refuses /coop invite and /coop accept over a profile's cap (base + Co-op Slots)");
        coopRaiseCoreCap = d.flag("bank.cshop.coop-raise-core-cap", true,
                "At boot, raise core's profiles.max-coop-members from its untouched default 5 to base + 3, so Co-op Slots can add members (an admin-set cap is left alone)");
    }

    // ---- data access ---------------------------------------------------------------------------------

    static boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    /**
     * Ready AND the account-wide Boon Buff / bits / fame copy is in place ({@link AccountWide}); anything that
     * WRITES those fields waits for this, or the load would overwrite the write.
     */
    static boolean accountReady(Player p) { return ready(p) && AccountWide.loaded(p); }

    static BankData.Member member(Player p) { return Sky.profiles().member(p, MEMBER_KEY, BankData.Member.class); }

    static BankData.Shared shared(Player p) { return Sky.profiles().shared(p, SHARED_KEY, BankData.Shared.class); }

    static int tier(Player p) {
        if (catalog == null) return 0;
        return Math.max(0, Math.min(shared(p).tier, catalog.maxTier()));
    }

    static boolean isCoop(Player p) {
        String pid = Sky.profiles().profileId(p);
        return pid != null && Sky.profiles().members(pid).size() > 1;
    }

    static boolean hasPersonal(Player p) { return personalSolo.get() || isCoop(p); }

    /** Capacity the co-op account should have at its tier. */
    static double capacityFor(int tier) {
        BankMath.Tier t = catalog.tier(tier);
        return t == null ? 50_000_000 : t.capacity;
    }

    static double personalCapacity(Player p) {
        return Math.floor(Sky.economy().bankCapacity(p) * Math.max(0, personalFraction.get()));
    }

    static long period() {
        SkyDate d = Sky.calendar().now();
        return BankMath.periodIndex(d.year(), d.month(), interestMonths.get());
    }

    /** Real millis until the next interest payout. */
    static long millisToInterest() {
        SkyDate d = Sky.calendar().now();
        double days = BankMath.daysUntilNextPeriod(d.month(), d.day(), d.hour(), d.minute(), interestMonths.get(), 31);
        return (long) (days * Sky.calendar().millisPerDay());
    }

    static String today() { return LocalDate.now(ZoneOffset.UTC).toString(); }

    static String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    static double fameMultiplier(Player p) {
        return BankMath.bitsMultiplier(member(p).fame, catalog.fame);
    }

    /** Museum milestone of this player's profile (progression's Features.Museum), 0 when unknown. */
    static int museumMilestone(Player p) {
        if (p == null) return 0;
        try { return Math.max(0, Sky.service(com.skyclone.api.feature.Features.Museum.class).milestone(p)); }
        catch (RuntimeException e) { return 0; }
    }

    /** Interest multiplier from the Museum (research 1.3: +2% per milestone). */
    static double museumInterestMultiplier(Player p) {
        return BankMath.museumInterestMultiplier(museumMilestone(p), interestMuseumBonus.get(), interestMuseumMax.get());
    }

    static java.util.List<String> csv(String v) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (v == null) return out;
        for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    static void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(Sky.plugin(), r);
    }
}
