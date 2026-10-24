package com.skyclone.mod.auction;

import com.skyclone.api.Sky;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The auctions, in memory (authoritative, main thread) and in SQLite (durable). Every change is a
 * full-row snapshot taken on the main thread and queued on the single FIFO database thread, so the
 * database can lag the memory by a few milliseconds but never disagree with it in order.
 *
 * <p>Tables: {@code auction_listings} (one row per auction, kept as history once settled),
 * {@code auction_bids}, {@code auction_pending} (the item sitting in a player's Create Auction slot),
 * {@code auction_sales} (player-to-player sale prices for the price guide), {@code auction_npc_daily}
 * (coins NPC buyers paid each profile per UTC day) and {@code auction_log} (audit).
 */
public final class AuctionStore {

    private final Map<String, Listing> live = new LinkedHashMap<>();
    /** (uuid|profile) -> base64 item waiting in that player's Create Auction slot. */
    private final Map<String, String> pending = new HashMap<>();
    /** profile|utcDay -> coins NPC buyers paid. */
    private final Map<String, Double> npcDaily = new HashMap<>();
    private volatile long writeFailures;

    // ---- schema / load ------------------------------------------------------------------------

    public void migrate() {
        Sky.db().migrate("auction", 1,
                "CREATE TABLE IF NOT EXISTS auction_listings (id TEXT PRIMARY KEY, seller TEXT, seller_profile TEXT, seller_name TEXT,"
                        + " npc INTEGER NOT NULL DEFAULT 0, item TEXT NOT NULL, item_id TEXT, item_uuid TEXT, item_name TEXT, search TEXT,"
                        + " rarity TEXT, tab TEXT, amount INTEGER NOT NULL DEFAULT 1, bin INTEGER NOT NULL, starting_bid REAL NOT NULL,"
                        + " created INTEGER NOT NULL, buyable_at INTEGER NOT NULL, ends_at INTEGER NOT NULL, top_bid REAL NOT NULL DEFAULT 0,"
                        + " top_bidder TEXT, top_bidder_profile TEXT, top_bidder_name TEXT, bid_count INTEGER NOT NULL DEFAULT 0,"
                        + " state TEXT NOT NULL, sold_at INTEGER NOT NULL DEFAULT 0, seller_claimed INTEGER NOT NULL DEFAULT 0,"
                        + " buyer_claimed INTEGER NOT NULL DEFAULT 0, fees REAL NOT NULL DEFAULT 0, settled INTEGER NOT NULL DEFAULT 0)",
                "CREATE INDEX IF NOT EXISTS auction_listings_settled ON auction_listings(settled)",
                "CREATE TABLE IF NOT EXISTS auction_bids (auction_id TEXT NOT NULL, bidder TEXT, bidder_profile TEXT, bidder_name TEXT,"
                        + " amount REAL NOT NULL, time INTEGER NOT NULL, npc INTEGER NOT NULL DEFAULT 0)",
                "CREATE INDEX IF NOT EXISTS auction_bids_auction ON auction_bids(auction_id)",
                "CREATE TABLE IF NOT EXISTS auction_pending (uuid TEXT NOT NULL, profile_id TEXT NOT NULL, item TEXT NOT NULL,"
                        + " created INTEGER NOT NULL, PRIMARY KEY (uuid, profile_id))",
                "CREATE TABLE IF NOT EXISTS auction_sales (item_id TEXT NOT NULL, unit_price REAL NOT NULL, time INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS auction_sales_item ON auction_sales(item_id, time)",
                "CREATE TABLE IF NOT EXISTS auction_npc_daily (profile_id TEXT NOT NULL, day INTEGER NOT NULL, coins REAL NOT NULL,"
                        + " PRIMARY KEY (profile_id, day))",
                "CREATE TABLE IF NOT EXISTS auction_log (time INTEGER NOT NULL, kind TEXT NOT NULL, auction_id TEXT, actor TEXT,"
                        + " amount REAL, detail TEXT)");
    }

    /** Blocking (enable only): unsettled listings + their bids, pending slots, today's NPC caps, recent sales. */
    public void load(PriceGuide guide, long historyDays) {
        long cutoff = System.currentTimeMillis() - historyDays * 86_400_000L;
        long today = utcDay(System.currentTimeMillis());
        record Loaded(List<Listing> listings, Map<String, String> pend, Map<String, Double> daily, List<Object[]> sales) {}
        Loaded l = Sky.db().query(c -> {
            // housekeeping first: settled history past the window
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM auction_listings WHERE settled = 1 AND ends_at < ?")) {
                ps.setLong(1, cutoff); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM auction_bids WHERE auction_id NOT IN (SELECT id FROM auction_listings)")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM auction_sales WHERE time < ?")) {
                ps.setLong(1, cutoff); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM auction_npc_daily WHERE day < ?")) {
                ps.setLong(1, today - 7); ps.executeUpdate();
            }
            // the audit log is append-only and would grow for ever: keep max(90 days, the history window)
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM auction_log WHERE time < ?")) {
                ps.setLong(1, Math.min(cutoff, System.currentTimeMillis() - 90L * 86_400_000L)); ps.executeUpdate();
            }
            Map<String, Listing> byId = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM auction_listings WHERE settled = 0 ORDER BY created");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Listing x = row(rs);
                    byId.put(x.id, x);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM auction_bids ORDER BY time");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Listing x = byId.get(rs.getString("auction_id"));
                    if (x == null) continue;
                    String b = rs.getString("bidder");
                    x.bids.add(new Listing.Bid(b == null ? null : UUID.fromString(b), rs.getString("bidder_profile"),
                            rs.getString("bidder_name"), rs.getDouble("amount"), rs.getLong("time"), rs.getInt("npc") != 0));
                }
            }
            Map<String, String> pend = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, profile_id, item FROM auction_pending");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) pend.put(rs.getString(1) + "|" + rs.getString(2), rs.getString(3));
            }
            Map<String, Double> daily = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT profile_id, day, coins FROM auction_npc_daily WHERE day = ?")) {
                ps.setLong(1, today);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) daily.put(rs.getString(1) + "|" + rs.getLong(2), rs.getDouble(3));
                }
            }
            List<Object[]> sales = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT item_id, unit_price FROM auction_sales ORDER BY time");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sales.add(new Object[]{rs.getString(1), rs.getDouble(2)});
            }
            return new Loaded(new ArrayList<>(byId.values()), pend, daily, sales);
        }).join();
        live.clear();
        for (Listing x : l.listings()) live.put(x.id, x);
        pending.clear();
        pending.putAll(l.pend());
        npcDaily.clear();
        npcDaily.putAll(l.daily());
        for (Object[] s : l.sales()) guide.recordSale((String) s[0], (Double) s[1]);
    }

    private static Listing row(ResultSet rs) throws SQLException {
        Listing x = new Listing();
        x.id = rs.getString("id");
        String s = rs.getString("seller");
        x.seller = s == null ? null : UUID.fromString(s);
        x.sellerProfile = rs.getString("seller_profile");
        x.sellerName = rs.getString("seller_name");
        x.npc = rs.getInt("npc") != 0;
        x.item = rs.getString("item");
        x.itemId = rs.getString("item_id");
        x.itemUuid = rs.getString("item_uuid");
        x.itemName = rs.getString("item_name");
        x.search = rs.getString("search");
        x.rarity = rs.getString("rarity");
        AhTab t = AhTab.parse(rs.getString("tab"));
        x.tab = t == null ? AhTab.TOOLS_MISC : t;
        x.amount = rs.getInt("amount");
        x.bin = rs.getInt("bin") != 0;
        x.startingBid = rs.getDouble("starting_bid");
        x.created = rs.getLong("created");
        x.buyableAt = rs.getLong("buyable_at");
        x.endsAt = rs.getLong("ends_at");
        x.topBid = rs.getDouble("top_bid");
        String tb = rs.getString("top_bidder");
        x.topBidder = tb == null ? null : UUID.fromString(tb);
        x.topBidderProfile = rs.getString("top_bidder_profile");
        x.topBidderName = rs.getString("top_bidder_name");
        x.bidCount = rs.getInt("bid_count");
        try { x.state = Listing.State.valueOf(rs.getString("state")); } catch (Exception e) { x.state = Listing.State.EXPIRED; }
        x.soldAt = rs.getLong("sold_at");
        x.sellerClaimed = rs.getInt("seller_claimed") != 0;
        x.buyerClaimed = rs.getInt("buyer_claimed") != 0;
        x.fees = rs.getDouble("fees");
        return x;
    }

    // ---- memory ---------------------------------------------------------------------------------

    public Collection<Listing> all() { return Collections.unmodifiableCollection(live.values()); }

    public Listing get(String id) {
        if (id == null) return null;
        Listing l = live.get(id);
        if (l != null) return l;
        // short ids from chat links / admin commands
        Listing found = null;
        for (Listing x : live.values()) {
            if (x.id.startsWith(id)) { if (found != null) return null; found = x; }
        }
        return found;
    }

    public void add(Listing l) { live.put(l.id, l); }

    /** Drop settled listings from memory (their rows stay as history). */
    public int evictSettled() {
        int n = 0;
        var it = live.values().iterator();
        while (it.hasNext()) if (it.next().settled()) { it.remove(); n++; }
        return n;
    }

    public long writeFailures() { return writeFailures; }

    // ---- writes -----------------------------------------------------------------------------------

    private record Snap(String id, String seller, String sellerProfile, String sellerName, boolean npc, String item,
                        String itemId, String itemUuid, String itemName, String search, String rarity, String tab, int amount,
                        boolean bin, double startingBid, long created, long buyableAt, long endsAt, double topBid,
                        String topBidder, String topBidderProfile, String topBidderName, int bidCount, String state,
                        long soldAt, boolean sellerClaimed, boolean buyerClaimed, double fees, boolean settled) {}

    private static Snap snap(Listing l) {
        return new Snap(l.id, l.seller == null ? null : l.seller.toString(), l.sellerProfile, l.sellerName, l.npc, l.item,
                l.itemId, l.itemUuid, l.itemName, l.search, l.rarity, l.tab.name(), l.amount, l.bin, l.startingBid, l.created,
                l.buyableAt, l.endsAt, l.topBid, l.topBidder == null ? null : l.topBidder.toString(), l.topBidderProfile,
                l.topBidderName, l.bidCount, l.state.name(), l.soldAt, l.sellerClaimed, l.buyerClaimed, l.fees, l.settled());
    }

    /** Queue a full-row write of the listing's CURRENT state (snapshot taken now, on the caller's thread). */
    public void save(Listing l) {
        Snap s = snap(l);
        Sky.db().transaction(c -> { upsert(c, s); return null; }).whenComplete((r, ex) -> failed(ex, "save " + s.id()));
    }

    /** Listing row + one bid row in ONE transaction. */
    public void saveWithBid(Listing l, Listing.Bid b) {
        Snap s = snap(l);
        Sky.db().transaction(c -> {
            upsert(c, s);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO auction_bids(auction_id, bidder, bidder_profile, bidder_name, amount, time, npc) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, s.id());
                ps.setString(2, b.bidder() == null ? null : b.bidder().toString());
                ps.setString(3, b.profile());
                ps.setString(4, b.name());
                ps.setDouble(5, b.amount());
                ps.setLong(6, b.time());
                ps.setInt(7, b.npc() ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((r, ex) -> failed(ex, "bid " + s.id()));
    }

    /**
     * A new listing AND the removal of the pending create-slot item it came from, in ONE transaction:
     * a crash can never leave the item both listed and still waiting in the slot.
     */
    public void saveFromPending(Listing l, UUID uuid, String profile) {
        pending.remove(pkey(uuid, profile));
        Snap s = snap(l);
        Sky.db().transaction(c -> {
            upsert(c, s);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM auction_pending WHERE uuid = ? AND profile_id = ?")) {
                ps.setString(1, uuid.toString()); ps.setString(2, profile); ps.executeUpdate();
            }
            return null;
        }).whenComplete((r, ex) -> failed(ex, "create " + s.id()));
    }

    private static void upsert(Connection c, Snap s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO auction_listings(id, seller, seller_profile, seller_name, npc, item, item_id, item_uuid, item_name,"
                        + " search, rarity, tab, amount, bin, starting_bid, created, buyable_at, ends_at, top_bid, top_bidder, top_bidder_profile,"
                        + " top_bidder_name, bid_count, state, sold_at, seller_claimed, buyer_claimed, fees, settled)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int i = 1;
            ps.setString(i++, s.id());
            ps.setString(i++, s.seller());
            ps.setString(i++, s.sellerProfile());
            ps.setString(i++, s.sellerName());
            ps.setInt(i++, s.npc() ? 1 : 0);
            ps.setString(i++, s.item());
            ps.setString(i++, s.itemId());
            ps.setString(i++, s.itemUuid());
            ps.setString(i++, s.itemName());
            ps.setString(i++, s.search());
            ps.setString(i++, s.rarity());
            ps.setString(i++, s.tab());
            ps.setInt(i++, s.amount());
            ps.setInt(i++, s.bin() ? 1 : 0);
            ps.setDouble(i++, s.startingBid());
            ps.setLong(i++, s.created());
            ps.setLong(i++, s.buyableAt());
            ps.setLong(i++, s.endsAt());
            ps.setDouble(i++, s.topBid());
            ps.setString(i++, s.topBidder());
            ps.setString(i++, s.topBidderProfile());
            ps.setString(i++, s.topBidderName());
            ps.setInt(i++, s.bidCount());
            ps.setString(i++, s.state());
            ps.setLong(i++, s.soldAt());
            ps.setInt(i++, s.sellerClaimed() ? 1 : 0);
            ps.setInt(i++, s.buyerClaimed() ? 1 : 0);
            ps.setDouble(i++, s.fees());
            ps.setInt(i, s.settled() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private void failed(Throwable ex, String what) {
        if (ex == null) return;
        writeFailures++;
        Sky.log().log(Level.SEVERE, "[auction] DATABASE WRITE FAILED (" + what + ") - memory is ahead of the database", ex);
    }

    // ---- pending create-slot items ------------------------------------------------------------------

    static String pkey(UUID uuid, String profile) { return uuid + "|" + profile; }

    public String pending(UUID uuid, String profile) { return pending.get(pkey(uuid, profile)); }

    /** Returns a future that completes once the row is committed (exceptionally if the write failed). */
    public java.util.concurrent.CompletableFuture<?> setPending(UUID uuid, String profile, String item) {
        String k = pkey(uuid, profile);
        if (item == null) {
            pending.remove(k);
            return Sky.db().transaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM auction_pending WHERE uuid = ? AND profile_id = ?")) {
                    ps.setString(1, uuid.toString()); ps.setString(2, profile); ps.executeUpdate();
                }
                return null;
            }).whenComplete((r, ex) -> failed(ex, "pending clear " + uuid));
        } else {
            pending.put(k, item);
            long now = System.currentTimeMillis();
            return Sky.db().transaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR REPLACE INTO auction_pending(uuid, profile_id, item, created) VALUES(?,?,?,?)")) {
                    ps.setString(1, uuid.toString()); ps.setString(2, profile); ps.setString(3, item); ps.setLong(4, now);
                    ps.executeUpdate();
                }
                return null;
            }).whenComplete((r, ex) -> failed(ex, "pending set " + uuid));
        }
    }

    public int pendingCount() { return pending.size(); }

    /** Every pending item (for the audit). */
    public Collection<String> pendingItems() { return Collections.unmodifiableCollection(pending.values()); }

    // ---- sales history / NPC caps / audit log -----------------------------------------------------

    public void recordSale(String itemId, double unit) {
        long now = System.currentTimeMillis();
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO auction_sales(item_id, unit_price, time) VALUES(?,?,?)")) {
                ps.setString(1, itemId); ps.setDouble(2, unit); ps.setLong(3, now); ps.executeUpdate();
            }
            return null;
        }).whenComplete((r, ex) -> failed(ex, "sale " + itemId));
    }

    public static long utcDay(long millis) { return Math.floorDiv(millis, 86_400_000L); }

    public double npcPaidToday(String profile) {
        Double v = npcDaily.get(profile + "|" + utcDay(System.currentTimeMillis()));
        return v == null ? 0 : v;
    }

    public void addNpcPaid(String profile, double coins) {
        long day = utcDay(System.currentTimeMillis());
        double total = npcDaily.merge(profile + "|" + day, coins, Double::sum);
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO auction_npc_daily(profile_id, day, coins) VALUES(?,?,?)")) {
                ps.setString(1, profile); ps.setLong(2, day); ps.setDouble(3, total); ps.executeUpdate();
            }
            return null;
        }).whenComplete((r, ex) -> failed(ex, "npc daily " + profile));
    }

    public void log(String kind, String auctionId, String actor, double amount, String detail) {
        long now = System.currentTimeMillis();
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO auction_log(time, kind, auction_id, actor, amount, detail) VALUES(?,?,?,?,?,?)")) {
                ps.setLong(1, now); ps.setString(2, kind); ps.setString(3, auctionId); ps.setString(4, actor);
                ps.setDouble(5, amount); ps.setString(6, detail); ps.executeUpdate();
            }
            return null;
        }).whenComplete((r, ex) -> failed(ex, "log " + kind));
    }
}
