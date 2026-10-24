package com.skyclone.mod.auction;

/**
 * Per-player member data, key {@code "auction"}: remembered browser settings, the last choices on
 * the Create Auction screen, and lifetime statistics for the Auction Stats page. Gson-friendly;
 * never retype a field (core would poison the key), only add.
 */
public class AuctionData {
    // browser settings
    public String tab = "WEAPONS";
    public String sort = "HIGHEST";
    public String rarity;            // null = no filter
    public String binFilter = "ALL";

    // create screen
    public boolean createBin = true;
    public double createPrice = 0;
    public long createMinutes = 6 * 60;

    // notifications
    public boolean notifyBids = true;
    public boolean notifySales = true;

    /** Auction House NPC ids whose first-visit pitch this player has heard. */
    public java.util.Set<String> greeted = new java.util.HashSet<>();

    // the Vault Auction (stats; the bids themselves live in auction_vault_bids)
    public long vaultBids;
    public long vaultWon;

    // statistics
    public long auctionsCreated;
    public long auctionsSold;
    public long auctionsExpired;
    public double coinsEarned;
    public double taxPaid;
    public double feesPaid;
    public double highestSale;
    public long itemsBought;
    public double coinsSpent;
    public long bidsPlaced;
    public long auctionsWon;
    public double highestBid;
}
