package com.skyclone.mod.bazaar;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;

/** Every Bazaar tunable, editable live with {@code /sb dial set bazaar.<key> <value>}. Read at use time. */
public final class BzDials {

    private BzDials() {}

    // ---- access & trading rules (research 05 s2.1 / s2.3) ----
    public static Dials.Dial<Boolean> enabled;
    public static Dials.Dial<String> accessRequirement;
    public static Dials.Dial<String> remoteRequirement;
    public static Dials.Dial<Double> taxPercent;
    public static Dials.Dial<String> mayorTaxPerk;
    public static Dials.Dial<Double> mayorTaxMult;
    public static Dials.Dial<Integer> maxOrders;
    public static Dials.Dial<Integer> maxOrderAmount;
    public static Dials.Dial<Integer> maxInstantAmount;
    public static Dials.Dial<Integer> maxOrderUnstackable;
    public static Dials.Dial<Double> instantBuyReservePercent;
    public static Dials.Dial<Double> maxUnitPrice;
    public static Dials.Dial<Double> maxOfferValue;
    public static Dials.Dial<Double> expiryDays;
    public static Dials.Dial<Double> warnBuyMultiple;
    public static Dials.Dial<Double> warnSellFraction;
    public static Dials.Dial<Integer> warnCooldownSeconds;
    public static Dials.Dial<Double> offerCeilingMultiple;
    public static Dials.Dial<Integer> actionCooldownMs;
    public static Dials.Dial<Boolean> blockSelfTrade;
    public static Dials.Dial<String> blockedModes;
    public static Dials.Dial<String> ironmanProducts;
    public static Dials.Dial<String> flipper1Requirement;
    public static Dials.Dial<String> flipper2Requirement;
    public static Dials.Dial<Double> flipperTaxStep;
    public static Dials.Dial<Integer> flipperSlots;

    // ---- market makers (research 05 s2.9) ----
    public static Dials.Dial<Boolean> mmEnabled;
    public static Dials.Dial<Double> mmPriceScale;
    public static Dials.Dial<Integer> mmRefreshSeconds;
    public static Dials.Dial<Integer> mmLevels;
    public static Dials.Dial<Double> mmStepPercent;
    public static Dials.Dial<Double> mmSpreadMult;
    public static Dials.Dial<Double> mmDepthScale;
    public static Dials.Dial<Integer> mmDepthMin;
    public static Dials.Dial<Integer> mmDepthMax;
    public static Dials.Dial<Double> mmLevelMaxValue;
    public static Dials.Dial<Integer> mmPlayerHourlyCap;
    public static Dials.Dial<Double> mmRestockHours;
    public static Dials.Dial<Double> mmReversionHours;
    public static Dials.Dial<Double> mmVolatility;
    public static Dials.Dial<Double> mmSkew;
    public static Dials.Dial<Boolean> mmNpcFloor;
    public static Dials.Dial<Boolean> mmRecipeCaps;
    public static Dials.Dial<Double> mmPlayerWeight;
    public static Dials.Dial<Double> mmInventoryHours;
    public static Dials.Dial<Double> mmBidMaxRef;
    public static Dials.Dial<Double> mmFadeMax;
    public static Dials.Dial<Double> mmGuildPremium;
    public static Dials.Dial<String> eventShocks;
    public static Dials.Dial<Double> eventShockGlideMinutes;
    public static Dials.Dial<String> essenceBuyRequirement;
    public static Dials.Dial<String> essenceBuyExempt;
    public static Dials.Dial<Boolean> seedEnabled;
    public static Dials.Dial<Double> seedIntervalMinutes;
    public static Dials.Dial<Double> seedPricePercent;
    public static Dials.Dial<Double> seedBatchMult;
    public static Dials.Dial<Double> seedMaxValue;
    public static Dials.Dial<String> seedProducts;
    public static Dials.Dial<String> specialModes;
    public static Dials.Dial<String> specialAnywhereModes;

    // ---- simulated market flow ----
    public static Dials.Dial<Boolean> flowEnabled;
    public static Dials.Dial<Double> flowScale;
    public static Dials.Dial<Integer> flowMaxPerMinute;

    // ---- misc ----
    public static Dials.Dial<Integer> partialNotifySeconds;
    public static Dials.Dial<Integer> logKeepDays;

    public static void register() {
        Dials d = Sky.dials();
        enabled = d.flag("bazaar.enabled", true, "Master switch: false halts all Bazaar trading (menus still open, orders keep their escrow)");
        accessRequirement = d.text("bazaar.access-requirement", "sblevel:5", "Requirement to use the Bazaar at all (reference game: Level 7; owner chose Level 5 on 2026-10-04). A bare number N = SkyBlock Level N (e.g. 3 for a show); none / off / 0 = everyone; anything else is a requirement key such as sblevel:7");
        remoteRequirement = d.text("bazaar.remote-requirement", "cookie", "Extra requirement to open /bz away from the Bazaar NPC (research: an active Booster Cookie = bank's 'cookie' key). Empty = anywhere");
        taxPercent = d.number("bazaar.tax-percent", 1.25, "Tax on seller proceeds in percent (research 05: 1.25%)");
        mayorTaxPerk = d.text("bazaar.mayor-tax-perk", "derpy.quad_taxes", "Mayor perk id that multiplies the Bazaar tax while active (research: Derpy x4 = mayors' derpy.quad_taxes). Empty = none");
        mayorTaxMult = d.number("bazaar.mayor-tax-mult", 4.0, "Tax multiplier while bazaar.mayor-tax-perk is active");
        maxOrders = d.integer("bazaar.max-orders", 14, "Max orders + offers per player, counting filled ones not yet claimed (research: 14)");
        maxOrderAmount = d.integer("bazaar.max-order-amount", 71_680, "Max quantity in one Buy Order / Sell Offer (research: 71,680 = 1,120 stacks)");
        maxInstantAmount = d.integer("bazaar.max-instant-amount", 2_240, "Max quantity per Instant Buy click for a 64-stack item; scales with stack size, so 560 for 16-stacks (research: 2,240 = 35 stacks; essence uses max-order-amount)");
        maxOrderUnstackable = d.integer("bazaar.max-order-unstackable", 256, "Max quantity in one order of an item that does not stack, e.g. enchanted books (research: 256; their Buy Order presets are 1 / 4 / 64)");
        instantBuyReservePercent = d.number("bazaar.instant-buy-reserve-percent", 4, "Instant Buy holds this many percent above the quoted cost up front and refunds the rest after the fill (research: 4%); 0 = exact");
        maxUnitPrice = d.number("bazaar.max-unit-price", 500_000_000, "Max unit price of a Buy Order in coins (research: 500M)");
        maxOfferValue = d.number("bazaar.max-offer-value", 1_000_000_000, "Max total value of one Sell Offer in coins (research: 1B)");
        expiryDays = d.number("bazaar.order-expiry-days", 7, "Unfilled orders expire after this many real days; the remainder waits to be claimed (research: 7)");
        warnBuyMultiple = d.number("bazaar.warn-buy-multiple", 2.0, "Instant Buy asks for confirmation above this multiple of the 7-day average (research: 2x)");
        warnSellFraction = d.number("bazaar.warn-sell-fraction", 0.5, "Instant Sell asks for confirmation below this fraction of the 7-day average (research: 50%)");
        warnCooldownSeconds = d.integer("bazaar.warn-cooldown-seconds", 300, "After confirming a price warning, it is not shown again for this long (research: 5 minutes)");
        offerCeilingMultiple = d.number("bazaar.offer-ceiling-multiple", 10.0, "Sell Offers may not be listed above this multiple of the 7-day average (research: 10x)");
        actionCooldownMs = d.integer("bazaar.action-cooldown-ms", 250, "Minimum time between two trading actions of one player (anti-spam)");
        blockSelfTrade = d.flag("bazaar.block-self-trade", true, "A player's instant trades skip their own orders (no wash trading the price history)");
        blockedModes = d.text("bazaar.blocked-modes", "bingo", "Profile modes that cannot use the Bazaar at all, comma separated (research: Bingo disabled)");
        ironmanProducts = d.text("bazaar.ironman-products", "BOOSTER_COOKIE", "The only products the Special Bazaar sells (Ironman and Stranded may only Instant Buy these and never sell or place orders), comma separated (research)");
        specialModes = d.text("bazaar.special-modes", "ironman,stranded", "Profile modes that get the Special Bazaar instead of the normal one: Instant Buy of bazaar.ironman-products only (research s9.3/9.4)");
        specialAnywhereModes = d.text("bazaar.special-anywhere-modes", "stranded", "Special-Bazaar modes that may open it from anywhere without the remote requirement (Stranded profiles can never visit the Hub)");
        essenceBuyRequirement = d.text("bazaar.essence-buy-requirement", "catacombs:20", "Requirement to BUY essence (ESSENCE_*) on the Bazaar, by Instant Buy or Buy Order; selling is free (research 02 s8: Catacombs 20). none = everyone; fails open if no module owns the prefix");
        essenceBuyExempt = d.text("bazaar.essence-buy-exempt", "ESSENCE_FOREST", "Essence types anyone may buy regardless of bazaar.essence-buy-requirement, comma separated (research: Forest Essence exempt)");
        flipper1Requirement = d.text("bazaar.flipper-1-requirement", "cshop:bazaar_flipper:1", "Requirement key for Bazaar Flipper I (bank's Community Shop upgrade). Empty = nobody has it");
        flipper2Requirement = d.text("bazaar.flipper-2-requirement", "cshop:bazaar_flipper:2", "Requirement key for Bazaar Flipper II (needs Flipper I too). Empty = nobody has it");
        flipperTaxStep = d.number("bazaar.flipper-tax-step", 0.125, "Tax percentage points removed per Bazaar Flipper tier (research: -0.125% each, 1.0% at tier II)");
        flipperSlots = d.integer("bazaar.flipper-slots", 7, "Extra order slots per Bazaar Flipper tier (research: +7 each, 28 at tier II)");

        mmEnabled = d.flag("bazaar.mm-enabled", true, "Server market makers keep both sides of every priced product stocked");
        mmPriceScale = d.number("bazaar.mm-price-scale", 1.0, "Multiplier on every reference price the market makers anchor to");
        mmRefreshSeconds = d.integer("bazaar.mm-refresh-seconds", 60, "How often market makers re-quote (research: every minute)");
        mmLevels = d.integer("bazaar.mm-levels", 3, "Price levels each market maker quotes per side");
        mmStepPercent = d.number("bazaar.mm-step-percent", 1.5, "Distance between market-maker levels, percent of fair value"); // TUNE: not in research
        mmSpreadMult = d.number("bazaar.mm-spread-mult", 1.0, "Multiplier on the liquidity-based spread (6% busy .. 28% rare, research 6-30%)");
        mmDepthScale = d.number("bazaar.mm-depth-scale", 0.002, "Units per level = reference hourly volume x this"); // TUNE: reference volume is ~100k players
        mmDepthMin = d.integer("bazaar.mm-depth-min", 2, "Minimum units per market-maker level");
        mmDepthMax = d.integer("bazaar.mm-depth-max", 2_240, "Maximum units per market-maker level");
        mmLevelMaxValue = d.number("bazaar.mm-level-max-value", 25_000_000, "A market-maker level is never worth more than this many coins (expensive items get thin levels)");
        mmPlayerHourlyCap = d.integer("bazaar.mm-player-hourly-cap", 2_240, "Units one player may trade against market makers per product per hour (research: 2,240)");
        mmRestockHours = d.number("bazaar.mm-restock-hours", 6, "Half-life (hours) of a market maker's stock returning to its target");
        mmReversionHours = d.number("bazaar.mm-reversion-hours", 24, "Time constant (hours) of fair value reverting to its anchor"); // TUNE
        mmVolatility = d.number("bazaar.mm-volatility", 0.015, "Random walk of fair value per sqrt(hour) (0.015 = about 1.5%)"); // TUNE
        mmSkew = d.number("bazaar.mm-skew", 0.05, "Quote shift per target of excess stock (research: k ~ 0.05)");
        mmNpcFloor = d.flag("bazaar.mm-npc-floor", true, "Market-maker best bid never below the NPC sell price (research: NPC price is the floor)");
        mmRecipeCaps = d.flag("bazaar.mm-recipe-caps", true, "Market makers never bid more for a crafted item than its ingredients cost from them (no craft arbitrage)");
        mmPlayerWeight = d.number("bazaar.mm-player-weight", 0.5, "How far player trades can pull a product's anchor price (0 = never, 1 = fully)");
        mmInventoryHours = d.number("bazaar.mm-inventory-hours", 4, "A market maker's target stock = this many hours of its depth");
        mmBidMaxRef = d.number("bazaar.mm-bid-max-ref", 1.0, "Market makers never bid more than this multiple of a product's reference price, however players trade it (stops two accounts pumping a thin product and selling to the bots)");
        mmFadeMax = d.number("bazaar.mm-fade-max", 0.75, "As player volume on a product grows, market-maker depth shrinks by up to this fraction so players set busy prices (research 05 s2.9 fade-out)");

        mmGuildPremium = d.number("bazaar.mm-guild-premium", 1.0, "Merchant Guild: market makers bid this many percent ABOVE the NPC sell price for every farmable, so new players always have a better buyer than the NPC (research s2.9: NPC-sell + 1%; never reaches the NPC shop price)");
        eventShocks = d.text("bazaar.event-shocks",
                "season_of_jerry=WHITE_GIFT,GREEN_GIFT,RED_GIFT,PARTY_GIFT:0.75;"
                + "jerrys_workshop_open=SNOW_BALL,SNOW_BLOCK,ENCHANTED_SNOW_BLOCK,ICE_BAIT,GLACIAL_FRAGMENT,WALNUT:0.85;"
                + "spooky_festival=GREEN_CANDY,PURPLE_CANDY,DARK_CANDY,ECTOPLASM,PUMPKIN_GUTS,SPOOKY_SHARD:0.7;"
                + "fishing_festival=SHARK_FIN,NURSE_SHARK_TOOTH,BLUE_SHARK_TOOTH,TIGER_SHARK_TOOTH,GREAT_WHITE_SHARK_TOOTH:0.8;"
                + "mythological_ritual=ANCIENT_CLAW,ENCHANTED_ANCIENT_CLAW,GRIFFIN_FEATHER:0.8",
                "Event days (research s2.9): while a calendar event runs, market makers quote these products at this multiple of fair value. Format: event=ID,ID:factor;event=... (factor 0.2..5; empty = off)");
        seedEnabled = d.flag("bazaar.seed-enabled", true, "Merchant Guild supply drops (research s2.9 seeding trick): periodic sell offers for progression-gating materials (slayer, dragon, mining, fishing drops early gear needs) so a small server never stalls on one");
        seedIntervalMinutes = d.number("bazaar.seed-interval-minutes", 30, "Real minutes between Merchant Guild drops; each drop REPLACES the guild's offer with one fresh batch (never accumulates)"); // TUNE: research gives no period
        seedPricePercent = d.number("bazaar.seed-price-percent", 100, "Guild price as a percent of the market maker's un-skewed ask (never below its bid + 0.1 or the NPC sell price, never above an NPC shop)"); // TUNE
        seedBatchMult = d.number("bazaar.seed-batch-mult", 1.0, "Multiplier on each material's batch (generator: enough for the largest early craft that needs it, 16..640)"); // TUNE
        seedMaxValue = d.number("bazaar.seed-max-value", 2_000_000, "One guild batch is never worth more than this many coins (dear materials get small batches)"); // TUNE
        seedProducts = d.text("bazaar.seed-products", "", "Edit the generated guild list: ID:n adds or resizes, ID adds (64), -ID removes; comma separated. /sb bazaar seed list shows the result");
        eventShockGlideMinutes = d.number("bazaar.event-shock-glide-minutes", 20, "Half-life (real minutes) of an event shock gliding in and back out, so prices drift instead of collapsing");

        flowEnabled = d.flag("bazaar.flow-enabled", true, "Simulated traders fill player orders that beat the market makers, so patient orders fill on a quiet server");
        flowScale = d.number("bazaar.flow-scale", 0.001, "Simulated volume = reference weekly volume x this"); // TUNE: bounded by bazaar.flow-max-per-minute
        flowMaxPerMinute = d.integer("bazaar.flow-max-per-minute", 64, "Max simulated units per product per minute, each side");

        partialNotifySeconds = d.integer("bazaar.partial-notify-seconds", 30, "Partial-fill messages for one order at most this often");
        logKeepDays = d.integer("bazaar.log-keep-days", 30, "Trade history rows older than this are pruned at boot");
    }
}
