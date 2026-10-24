package com.skyclone.mod.auction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One auction, in memory. Mutated ONLY on the main thread (the main thread is the lock that makes a
 * BIN purchase and a bid race-free); every mutation is followed by {@link AuctionStore#save}, which
 * snapshots the row and queues it on the single database thread.
 *
 * <p>Lifecycle: ACTIVE -> SOLD (a BIN purchase, or the end with a bid) | EXPIRED (the end with no
 * bid) | CANCELLED (the seller pulled it). It is SETTLED - dropped from memory, kept as history -
 * once the coins and the item have both gone where they belong.
 */
public final class Listing {

    public enum State { ACTIVE, SOLD, EXPIRED, CANCELLED }

    public String id;
    /** Null for an Auction Agent (NPC) consignment. */
    public UUID seller;
    public String sellerProfile;
    public String sellerName;
    public boolean npc;

    /** Base64 of {@code ItemStack.serializeAsBytes()} - the escrowed item itself, uuid and all. */
    public String item;
    public String itemId;
    public String itemUuid;
    /** '&amp;' coded display name, captured at listing time. */
    public String itemName;
    /** Lower-case plain name + lore, for search. */
    public String search;
    public String rarity;
    public AhTab tab;
    public int amount;

    public boolean bin;
    public double startingBid;
    public long created;
    /** BIN: not purchasable before this (the 20 s grace). Auctions: = created. */
    public long buyableAt;
    public long endsAt;

    public double topBid;
    public UUID topBidder;          // null = no bid, or an NPC bidder (see topBidderName)
    public String topBidderProfile;
    public String topBidderName;
    public int bidCount;

    public State state = State.ACTIVE;
    public long soldAt;
    public boolean sellerClaimed;
    public boolean buyerClaimed;
    public double fees;

    /** Bid history, oldest first. */
    public final List<Bid> bids = new ArrayList<>();

    // ---- transient --------------------------------------------------------------------------
    /** Cached display copy of the item (deserialised once, PDC stripped). */
    transient org.bukkit.inventory.ItemStack display;
    /** Cached: was this item once Auction Agent stock (null = not checked yet). */
    transient Boolean agentOrigin;

    public record Bid(UUID bidder, String profile, String name, double amount, long time, boolean npc) {}

    public boolean active() { return state == State.ACTIVE; }

    /** Price a browser sorts by: the top bid, else the starting bid / BIN price. */
    public double price() { return topBid > 0 ? topBid : startingBid; }

    public double unitPrice() { return price() / Math.max(1, amount); }

    public boolean hasBuyer() { return state == State.SOLD; }

    /** Both sides done: the row can leave memory. */
    public boolean settled() {
        return switch (state) {
            case ACTIVE -> false;
            case SOLD -> sellerClaimed && buyerClaimed;
            case EXPIRED, CANCELLED -> sellerClaimed;
        };
    }

    /** Is the ITEM still held by the Auction House (listed, returned-but-unclaimed, or sold-but-uncollected)? */
    public boolean itemInEscrow() {
        return switch (state) {
            case ACTIVE -> true;
            case SOLD -> !buyerClaimed;
            case EXPIRED, CANCELLED -> !sellerClaimed;
        };
    }

    /** The profile whose player will receive the escrowed item (seller for a return, buyer for a sale). */
    public String escrowOwner() {
        if (!itemInEscrow()) return null;
        return state == State.SOLD ? topBidderProfile : (npc ? null : sellerProfile);
    }

    /** Is this player (by profile) the seller side? Any co-op member may manage and claim. */
    public boolean isSellerSide(String profileId) {
        return !npc && sellerProfile != null && sellerProfile.equals(profileId);
    }

    /** Is this player (by profile) the winning side of a finished sale? */
    public boolean isBuyerSide(String profileId) {
        return state == State.SOLD && topBidderProfile != null && topBidderProfile.equals(profileId);
    }

    /** Did this profile ever bid here? */
    public boolean hasBidFrom(String profileId) {
        for (Bid b : bids) if (profileId.equals(b.profile())) return true;
        return false;
    }

    /**
     * What an open Auction View draws: the live row, else the listing it was opened on IF that has
     * settled. A BIN bought into a roomy inventory is paid, delivered and evicted from memory inside
     * the one click, so re-reading the store by id finds nothing - and "Auction not found" right after
     * "You purchased ..." reads as an error. The view keeps the object it was opened on and shows the
     * finished receipt instead; only an id that never resolved to anything is "not found".
     */
    public static Listing forView(Listing live, Listing held) {
        if (live != null) return live;
        return held != null && held.settled() ? held : null;
    }

    /** How a settled listing ended, from one profile's side of it. */
    public enum Outcome { BOUGHT, WON, SOLD_COLLECTED, ITEM_RETURNED, NONE }

    public Outcome outcomeFor(String profileId) {
        if (!settled() || profileId == null) return Outcome.NONE;
        if (state == State.SOLD && profileId.equals(topBidderProfile)) return bin ? Outcome.BOUGHT : Outcome.WON;
        if (isSellerSide(profileId)) return state == State.SOLD ? Outcome.SOLD_COLLECTED : Outcome.ITEM_RETURNED;
        return Outcome.NONE;
    }

    public String shortId() { return id.length() > 8 ? id.substring(0, 8) : id; }
}
