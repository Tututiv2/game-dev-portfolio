package com.skyclone.mod.bazaar;

import java.util.UUID;

/**
 * One resting order on the book, or one that has left the book but still owes its owner something.
 * PURE (no Bukkit) so the matching engine can be unit tested.
 *
 * <p>Money is long TENTHS of a coin everywhere in this module (the genre's 0.1 price tick, and core's own
 * storage unit), so no fill can ever round differently on two machines.
 *
 * <p>Lifecycle: {@code OPEN} (on the book, can fill) -> {@code CLOSED} (filled, cancelled or expired; off the book,
 * may still hold {@link #itemsPending} / {@link #coinsPending} for the owner to claim) -> {@code DONE} (nothing
 * owed; the row is deleted). A BUY order's unfilled coins are escrow; a SELL offer's unfilled items are escrow.
 */
public final class Order {

    public enum Side { BUY, SELL }
    public enum State { OPEN, CLOSED, DONE }

    public final long id;
    public final String product;
    public final Side side;
    /** null for a market-maker order. */
    public final UUID owner;
    public final String ownerName;
    /** The profile the order was placed from; claims are only paid while that profile is active. */
    public final String profileId;
    /** Unit price in tenths. */
    public final long price;
    public int amount;
    public int filled;
    /** BUY: items bought and not yet claimed. SELL: unsold items returned on cancel/expiry, not yet claimed. */
    public int itemsPending;
    /** SELL: proceeds after tax not yet claimed. BUY: refunds (cancel remainder / price improvement) not yet claimed. */
    public long coinsPending;
    /** Total tax charged on this order's fills (sell offers), for the receipt. */
    public long taxPaid;
    /**
     * The owner's tax rate (percent) when the order was placed, Bazaar Flipper discount included, so a fill while
     * they are offline still charges THEIR rate. A mayor multiplier is applied on top at fill time. -1 = the
     * default rate (orders from before this existed).
     */
    public double taxBase = -1;
    public final long created;
    public long expires;
    public State state = State.OPEN;
    /** Market-maker quote (never persisted). */
    public final boolean bot;
    /**
     * A Merchant Guild supply offer (research s2.9 seeding trick). Always also {@link #bot}: it is server stock,
     * never persisted, and counts toward the per-player hourly allowance; but a market-maker re-quote leaves it alone
     * and its fills go to the guild's books, not the market maker's inventory.
     */
    public final boolean guild;
    /** Monotonic arrival counter for price-time priority (ids may be negative for bots). */
    public final long seq;

    public Order(long id, long seq, String product, Side side, UUID owner, String ownerName, String profileId,
                 long price, int amount, long created, long expires, boolean bot) {
        this(id, seq, product, side, owner, ownerName, profileId, price, amount, created, expires, bot, false);
    }

    public Order(long id, long seq, String product, Side side, UUID owner, String ownerName, String profileId,
                 long price, int amount, long created, long expires, boolean bot, boolean guild) {
        this.guild = guild;
        bot = bot || guild;
        this.id = id;
        this.seq = seq;
        this.product = product;
        this.side = side;
        this.owner = owner;
        this.ownerName = ownerName;
        this.profileId = profileId;
        this.price = price;
        this.amount = amount;
        this.created = created;
        this.expires = expires;
        this.bot = bot;
    }

    public int remaining() { return Math.max(0, amount - filled); }

    public boolean open() { return state == State.OPEN; }

    public boolean owes() { return itemsPending > 0 || coinsPending > 0; }

    /** Coins this order still holds for its owner if it is a BUY: unfilled escrow plus pending refunds. */
    public long escrowCoins() { return side == Side.BUY ? (open() ? (long) remaining() * price : 0) + coinsPending : coinsPending; }

    /** Items this order still holds: unsold escrow for a SELL, bought-but-unclaimed for a BUY. */
    public int escrowItems() { return side == Side.SELL ? (open() ? remaining() : 0) + itemsPending : itemsPending; }

    public double fillFraction() { return amount <= 0 ? 1 : (double) filled / amount; }

    @Override public String toString() {
        return "#" + id + " " + side + " " + product + " " + filled + "/" + amount + " @" + Pricing.fmt(price) + " " + state
                + (guild ? " [guild]" : bot ? " [mm]" : " " + ownerName);
    }
}
