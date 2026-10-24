package com.skyclone.mod.auction;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.feature.Features;

/** Every Auction House tunable, as a dial (read at use time). Defaults are research 05 section 3 unless marked TUNE. */
public final class AuctionDials {

    final Dials.Dial<Boolean> enabled;
    final Dials.Dial<String> requirement;
    final Dials.Dial<Boolean> anywhere;
    final Dials.Dial<Double> anywhereRange;
    final Dials.Dial<Boolean> specialModes;

    final Dials.Dial<Double> binFeeLow, binFeeMid, binFeeHigh, auctionFee, durationFeeScale;
    final Dials.Dial<Double> claimTax, claimTaxThreshold, quadTaxMult;
    final Dials.Dial<String> quadTaxPerk;
    final Dials.Dial<Double> minIncrement;
    final Dials.Dial<Integer> antiSnipe, binGrace;
    final Dials.Dial<Integer> maxListings, perMember, listingsCap;
    final Dials.Dial<Double> commonCap, minPrice, maxPrice;
    final Dials.Dial<Integer> minMinutes, maxHours;
    final Dials.Dial<Integer> actionCooldownMs;
    final Dials.Dial<Integer> historyDays;
    final Dials.Dial<Boolean> autoCollect;

    final Dials.Dial<Boolean> npcSellers;
    final Dials.Dial<Integer> npcListings;
    final Dials.Dial<Double> npcPriceMult;
    final Dials.Dial<Integer> npcRestockSeconds;
    final Dials.Dial<Double> npcAuctionShare;
    final Dials.Dial<Double> npcMaxPrice;
    final Dials.Dial<Integer> npcPerItem;

    final Dials.Dial<Boolean> npcBuyers;
    final Dials.Dial<Integer> npcBuyerSeconds;
    final Dials.Dial<Double> npcBinFraction, npcBidFraction, npcBuyerChance, npcDailyCap;
    final Dials.Dial<Integer> npcBuyerMinAge;

    final Dials.Dial<String> remoteRequirement;
    final Dials.Dial<Boolean> ironmanCosmetics;

    final Dials.Dial<Boolean> vaultEnabled;
    final Dials.Dial<String> vaultItems, vaultRequirement, vaultSpecialRequirement, vaultVolumePerk;
    final Dials.Dial<Integer> vaultSpecialCount, vaultSpecialStock;
    final Dials.Dial<Double> vaultSpecialMinFraction, vaultIncrement;
    final Dials.Dial<Boolean> vaultIronman, vaultIronmanSpecial;
    final Dials.Dial<Double> vaultIronmanStock;
    final Dials.Dial<String> regaliaItem;
    final Dials.Dial<Integer> browseHideSeconds;

    AuctionDials() {
        Dials d = Sky.dials();
        enabled = d.flag("auction.enabled", true, "Master switch: false closes the Auction House to players (admins still see it)");
        requirement = d.text("auction.requirement", "",
                "Requirement key to use the Auction House, e.g. sblevel:6 (research: 'likely SB Level 6'); blank = open to all. TUNE");
        anywhere = d.flag("auction.anywhere", false,
                "Allow /ah anywhere for everyone. False (genre) = at the hub Auction House, or anywhere while auction.remote-requirement is met");
        remoteRequirement = d.text("auction.remote-requirement", "cookie",
                "Requirement that lets /ah work away from the Auction House (research 05 3.1; the bank module's Boon Buff = 'cookie'); blank = nobody");
        anywhereRange = d.number("auction.anywhere-range", 40, "Blocks from the Auction Master that count as 'at the Auction House' when auction.anywhere is false");
        specialModes = d.flag("auction.special-modes", false, "Let Ironman/Stranded/Bingo profiles use the FULL Auction House (genre: no)");
        ironmanCosmetics = d.flag("auction.ironman-cosmetics", true,
                "Ironman profiles may browse the Auction House and BUY cosmetics only (research 05 3.1, Oct 2025)");

        binFeeLow = d.number("auction.bin-fee-low", 0.01, "BIN listing fee below 10M (fraction of price)");
        binFeeMid = d.number("auction.bin-fee-mid", 0.02, "BIN listing fee from 10M to 100M");
        binFeeHigh = d.number("auction.bin-fee-high", 0.025, "BIN listing fee from 100M");
        auctionFee = d.number("auction.auction-fee", 0.05, "Bid-auction listing fee (fraction of the starting bid)");
        durationFeeScale = d.number("auction.duration-fee-scale", 1.0, "Multiplier on the duration fee table (1h 20 ... 14d 55,200)");
        claimTax = d.number("auction.claim-tax", 0.01, "Tax on collecting a sale above the threshold (fraction)");
        claimTaxThreshold = d.number("auction.claim-tax-threshold", 1_000_000, "Sales at or below this pay no claim tax; taxed payouts never drop below it");
        quadTaxPerk = d.text("auction.quad-tax-perk", "derpy.quad_taxes",
                "Mayor perk id that multiplies the claim tax (mayors' QUADRUPLE TAX!!! = derpy.quad_taxes)");
        quadTaxMult = d.number("auction.quad-tax-mult", 4, "Claim-tax multiplier while that perk is active (used when the perk reports no value)");
        minIncrement = d.number("auction.min-increment", 0.025, "Minimum raise over the top bid (research: 2.5% now, 15% historically)");
        antiSnipe = d.integer("auction.anti-snipe-seconds", 120, "A bid with less than this left sets the time left to this");
        binGrace = d.integer("auction.bin-grace-seconds", 20, "Seconds after listing before a BIN can be bought");
        maxListings = d.integer("auction.max-listings", 14, "Listings per profile (active + waiting to be claimed)");
        perMember = d.integer("auction.max-listings-per-member", 3, "Extra listings per additional co-op member");
        listingsCap = d.integer("auction.max-listings-cap", 35, "Hard cap on listings per profile");
        commonCap = d.number("auction.common-cap-mult", 5, "COMMON items may not NET their seller more than this multiple of their NPC sell value (the listing and duration fees are added on top of the cap)");
        minPrice = d.number("auction.min-price", 1, "Lowest starting bid / BIN price");
        maxPrice = d.number("auction.max-price", 50_000_000_000d, "Highest starting bid / BIN price / bid");
        minMinutes = d.integer("auction.min-minutes", 5, "Shortest custom duration in minutes. TUNE (presets start at 1 hour)");
        maxHours = d.integer("auction.max-hours", 336, "Longest duration in hours (14 days)");
        actionCooldownMs = d.integer("auction.action-cooldown-ms", 350, "Per-player cooldown between bids/purchases/listings (anti-spam)");
        historyDays = d.integer("auction.history-days", 30, "Settled auctions and sale prices older than this are pruned at boot");
        autoCollect = d.flag("auction.auto-collect", false,
                "Deliver won items and sale coins automatically to online players instead of waiting for a claim click (genre: manual claim)");

        npcSellers = d.flag("auction.npc.sellers", true, "Auction Agents list consignment stock so the AH is never empty on a small server");
        npcListings = d.integer("auction.npc.listings", 90, "How many Auction Agent listings to keep up. TUNE");
        npcPriceMult = d.number("auction.npc.price-mult", 1.0, "Multiplier on Agent asking prices (1 = market reference). TUNE");
        npcRestockSeconds = d.integer("auction.npc.restock-seconds", 90, "How often the Agents top their stock up");
        npcAuctionShare = d.number("auction.npc.auction-share", 0.2, "Fraction of Agent listings that are bid auctions instead of BIN. TUNE");
        npcMaxPrice = d.number("auction.npc.max-price", 2_000_000_000d, "Agents never stock anything worth more than this. TUNE");
        npcPerItem = d.integer("auction.npc.per-item", 2, "Agents keep at most this many listings of one item id");

        npcBuyers = d.flag("auction.npc.buyers", true, "Auction Agents buy fairly-priced player BINs and bid on player auctions");
        npcBuyerSeconds = d.integer("auction.npc.buyer-seconds", 60, "How often the Agents look at player listings");
        npcBinFraction = d.number("auction.npc.bin-fraction", 0.9, "Agents buy a BIN priced at or under this fraction of its fair value. TUNE");
        npcBidFraction = d.number("auction.npc.bid-fraction", 0.8, "Agents bid on auctions up to this fraction of fair value. TUNE");
        npcBuyerChance = d.number("auction.npc.buyer-chance", 0.2, "Chance per pass that an Agent acts on a qualifying listing. TUNE");
        npcDailyCap = d.number("auction.npc.daily-cap", 5_000_000, "Most coins Agents pay any one profile per UTC day (a faucet cap). TUNE");
        npcBuyerMinAge = d.integer("auction.npc.buyer-min-age", 120, "Agents leave a new listing alone for this many seconds, so players see it first");

        // the Vault Auction (research 05 section 5.2: the yearly auction beneath the Auction House)
        vaultEnabled = d.flag("auction.vault.enabled", true, "Run the yearly Vault Auction (ranked bidding on limited-stock lots)");
        vaultItems = d.text("auction.vault.items", "ARTIFACT_OF_CONTROL:3:10m,PANDORAS_BOX:5:1m,SHENS_REGALIA:3:5m",
                "Main-track lots, ITEM:stock:minimum bid, comma separated (research: always the same three). Stocks and minimums TUNE");
        vaultRequirement = d.text("auction.vault.requirement", "museum_milestone:10",
                "Requirement to bid on the main track (research 05 5.2: Museum Milestone 10). Falls back to museum:60 (donations) when no module resolves museum_milestone");
        vaultSpecialRequirement = d.text("auction.vault.special-requirement", "museum_milestone:20",
                "Requirement to bid on the Special track (research: Museum Milestone 20). Falls back to museum:150 when museum_milestone is unknown");
        vaultSpecialCount = d.integer("auction.vault.special-count", 2, "Special-track lots each year (research: a rotating pair)");
        vaultSpecialStock = d.integer("auction.vault.special-stock", 2, "Stock of each Special lot. TUNE");
        vaultSpecialMinFraction = d.number("auction.vault.special-min-fraction", 0.6,
                "A Special lot's minimum bid as a fraction of the item's guide value. TUNE");
        vaultIncrement = d.number("auction.vault.min-increment", 0.025,
                "A bid that would push someone out must beat the lowest winning bid by this fraction. TUNE (main AH rule)");
        vaultVolumePerk = d.text("auction.vault.volume-perk", "diaz.volume_trading",
                "Mayor perk that multiplies every lot's stock by its value and adds 2 Special lots (research 05 5.2; mayors: Bulk Lots = diaz.volume_trading)");
        vaultIronman = d.flag("auction.vault.ironman", true,
                "Ironman profiles get their own Vault lots (the main-track items) that only Ironman profiles can bid on (research 05 5.2)");
        vaultIronmanStock = d.number("auction.vault.ironman-stock", 0.5,
                "Ironman Vault lots carry this fraction of the classic stock, rounded up, at least 1 (research: 50%)");
        vaultIronmanSpecial = d.flag("auction.vault.ironman-special", true,
                "Ironman also gets a copy of the year's Special lots (research says 'its own Shen's' without splitting tracks). TUNE (?)");
        regaliaItem = d.text("auction.regalia.item", "SHENS_REGALIA",
                "Item that must be carried (inventory or Accessory Bag) to enter the secret room beneath the Auction House (requirement 'regalia'); blank = nobody");
        browseHideSeconds = d.integer("auction.browse-hide-seconds", 0,
                "Listings with less than this many seconds left drop out of Browse/search (still open by link, View Bids, Manage). Research 05 3.3: 285 (4m45s), marked (?) - possibly a cache artefact; 0 = off");
    }

    AuctionRules.Settings settings() {
        AuctionRules.Settings s = new AuctionRules.Settings();
        s.binFeeLow = binFeeLow.get();
        s.binFeeMid = binFeeMid.get();
        s.binFeeHigh = binFeeHigh.get();
        s.auctionFee = auctionFee.get();
        s.durationFeeScale = durationFeeScale.get();
        s.claimTax = claimTax.get();
        s.claimTaxThreshold = claimTaxThreshold.get();
        s.claimTaxMultiplier = claimTaxMultiplier();
        s.minIncrement = minIncrement.get();
        s.antiSnipeSeconds = antiSnipe.get();
        s.binGraceSeconds = binGrace.get();
        s.maxListingsBase = maxListings.get();
        s.maxListingsPerMember = perMember.get();
        s.maxListingsCap = listingsCap.get();
        s.commonCapMultiplier = commonCap.get();
        s.minPrice = minPrice.get();
        s.maxPrice = maxPrice.get();
        s.minDurationMinutes = minMinutes.get();
        s.maxDurationHours = maxHours.get();
        return s;
    }

    boolean quadTaxActive() {
        String perk = quadTaxPerk.get();
        if (perk == null || perk.isBlank()) return false;
        try { return Sky.service(Features.Mayors.class).perkActive(perk); } catch (Throwable t) { return false; }
    }

    /** 1, or the perk's own value (mayors.json says 4) while the claim-tax perk is active; the dial when it reports none. */
    double claimTaxMultiplier() {
        if (!quadTaxActive()) return 1.0;
        double v = 0;
        try { v = Sky.service(Features.Mayors.class).perkValue(quadTaxPerk.get()); } catch (Throwable ignored) { }
        double m = v > 1 ? v : quadTaxMult.get();
        return Double.isFinite(m) && m > 0 ? m : 1.0;
    }

    /** Stock multiplier while the Vault volume perk is active (1 otherwise). */
    int vaultVolume() {
        String perk = vaultVolumePerk.get();
        if (perk == null || perk.isBlank()) return 1;
        try {
            Features.Mayors m = Sky.service(Features.Mayors.class);
            if (!m.perkActive(perk)) return 1;
            double v = m.perkValue(perk);
            return (int) Math.max(2, Math.min(10, Math.round(v > 1 ? v : 2)));
        } catch (Throwable t) {
            return 1;
        }
    }
}
