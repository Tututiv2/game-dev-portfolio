package com.skyclone.mod.auction;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.time.SkyDate;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Vault Auction (research 05 section 5.2; the mayors hand-off gives it to this module through the
 * {@code diaz.volume_trading} perk). Once per SkyBlock year a handful of limited-stock LOTS open; each lot keeps
 * its N highest bids (see {@link VaultRules}); when the year turns, everyone still kept wins and claims a fresh
 * copy of the item. Coins leave the purse when you bid and come straight back if you are pushed out.
 *
 * <p>Like the main Auction House: memory is authoritative and only the main thread changes it; every change is a
 * row snapshot queued on the FIFO database thread ({@code auction_vault_lots}, {@code auction_vault_bids}).
 * A win is marked CLAIMED and written before the item is handed over, so it cannot be claimed twice.
 */
final class VaultAuction {

    static final String ACTIVE = "ACTIVE", OUTBID = "OUTBID", WON = "WON", CLAIMED = "CLAIMED", REFUNDED = "REFUNDED";
    static final String MAIN = VaultRules.MAIN, SPECIAL = VaultRules.SPECIAL;
    /** The Ironman-only copies of the main and Special tracks (research 05 5.2: its own lots at 50% stock). */
    static final String IRONMAN = VaultRules.IRONMAN, IRONMAN_SPECIAL = VaultRules.IRONMAN_SPECIAL;

    /** Item categories the Special track draws from (research: fishing gear, helmets, wands, rods). */
    static final Set<ItemCategory> SPECIAL_CATEGORIES = EnumSet.of(ItemCategory.FISHING_ROD, ItemCategory.FISHING_WEAPON,
            ItemCategory.HELMET, ItemCategory.WAND);

    static final class Lot {
        String id;
        int year;
        String itemId;
        String track;
        int stock;
        double minBid;
        boolean closed;
        long created;
        final List<VBid> bids = new ArrayList<>();
        transient ItemStack display;
    }

    static final class VBid {
        UUID bidder;
        String profile;
        String name;
        double amount;
        long time;
        String state;
        String key() { return bidder + "|" + profile; }
    }

    private final AuctionHouse house;
    private final Map<String, Lot> lots = new LinkedHashMap<>();
    private boolean booted;

    VaultAuction(AuctionHouse house) { this.house = house; }

    // ---- schema / load ------------------------------------------------------------------------------

    void migrate() {
        Sky.db().migrate("auction", 2,
                "CREATE TABLE IF NOT EXISTS auction_vault_lots (id TEXT PRIMARY KEY, year INTEGER NOT NULL, item_id TEXT NOT NULL,"
                        + " track TEXT NOT NULL, stock INTEGER NOT NULL, min_bid REAL NOT NULL, closed INTEGER NOT NULL DEFAULT 0,"
                        + " created INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS auction_vault_bids (lot_id TEXT NOT NULL, bidder TEXT NOT NULL, profile_id TEXT NOT NULL,"
                        + " name TEXT, amount REAL NOT NULL, time INTEGER NOT NULL, state TEXT NOT NULL,"
                        + " PRIMARY KEY (lot_id, bidder, profile_id))",
                "CREATE INDEX IF NOT EXISTS auction_vault_bids_lot ON auction_vault_bids(lot_id)");
    }

    /** Blocking (enable only): open lots, this year's lots, and every lot that still owes someone an item. */
    void load() {
        int year = currentYear();
        Map<String, Lot> loaded = Sky.db().query(c -> {
            Map<String, Lot> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM auction_vault_lots WHERE closed = 0 OR year >= ?"
                    + " OR id IN (SELECT lot_id FROM auction_vault_bids WHERE state = 'WON') ORDER BY created")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Lot l = new Lot();
                        l.id = rs.getString("id");
                        l.year = rs.getInt("year");
                        l.itemId = rs.getString("item_id");
                        l.track = rs.getString("track");
                        l.stock = rs.getInt("stock");
                        l.minBid = rs.getDouble("min_bid");
                        l.closed = rs.getInt("closed") != 0;
                        l.created = rs.getLong("created");
                        out.put(l.id, l);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM auction_vault_bids ORDER BY time");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Lot l = out.get(rs.getString("lot_id"));
                    if (l == null) continue;
                    VBid b = new VBid();
                    b.bidder = UUID.fromString(rs.getString("bidder"));
                    b.profile = rs.getString("profile_id");
                    b.name = rs.getString("name");
                    b.amount = rs.getDouble("amount");
                    b.time = rs.getLong("time");
                    b.state = rs.getString("state");
                    l.bids.add(b);
                }
            }
            return out;
        }).join();
        lots.clear();
        lots.putAll(loaded);
    }

    // ---- reads ------------------------------------------------------------------------------------------

    static int currentYear() {
        try { return Sky.calendar().now().year(); } catch (Throwable t) { return 0; }
    }

    long millisLeft() {
        try {
            SkyDate d = Sky.calendar().now();
            return VaultRules.millisLeftInYear(d.month(), d.day(), d.hour(), d.minute(), Sky.calendar().millisPerDay());
        } catch (Throwable t) {
            return 0;
        }
    }

    Collection<Lot> all() { return Collections.unmodifiableCollection(lots.values()); }


    /** This year's lots, main track first. */
    List<Lot> current() {
        int y = currentYear();
        List<Lot> out = new ArrayList<>();
        for (Lot l : lots.values()) if (l.year == y && !l.closed) out.add(l);
        out.sort((a, b) -> a.track.equals(b.track) ? Long.compare(a.created, b.created) : Integer.compare(VaultRules.trackOrder(a.track), VaultRules.trackOrder(b.track)));
        return out;
    }

    /**
     * This year's lots as {@code p} sees them: an Ironman profile sees ONLY the Ironman lots, everyone else
     * sees everything but them (research 05 5.2: Ironman has its own Shen's). With {@code auction.special-modes}
     * on, Ironman is treated as a classic profile.
     */
    List<Lot> current(Player p) {
        boolean iron = ironman(p);
        List<Lot> out = new ArrayList<>();
        for (Lot l : current()) if (VaultRules.visible(l.track, iron)) out.add(l);
        return out;
    }

    /** True when the player's profile bids on the Ironman track. */
    boolean ironman(Player p) {
        if (house.dials.specialModes.get()) return false;
        try { return "ironman".equalsIgnoreCase(Sky.profiles().mode(p)); } catch (Throwable t) { return false; }
    }

    /** May {@code p} bid on {@code l} at all (the right track for their profile)? */
    boolean forViewer(Player p, Lot l) {
        return VaultRules.visible(l.track, ironman(p));
    }

    /**
     * Every gate a player must pass to open the Vault at all. Not {@code house.canUse}: that one refuses Ironman
     * when the cosmetic-only main AH is switched off, and Ironman's Vault lots must not depend on that dial.
     */
    boolean canUse(Player p, boolean tell) {
        if (!Sky.profiles().isReady(p)) { if (tell) Msg.error(p, "Your profile is still loading."); return false; }
        if (!house.dials.enabled.get() && !p.hasPermission("skyclone.admin")) {
            if (tell) Msg.error(p, "The Auction House is closed right now.");
            return false;
        }
        String why;
        try { why = VaultRules.modeRefusal(Sky.profiles().mode(p), house.dials.specialModes.get(), house.dials.vaultIronman.get()); }
        catch (Throwable t) { why = null; }
        if (why != null) { if (tell) Msg.error(p, why); return false; }
        String req = house.dials.requirement.get();
        if (req != null && !req.isBlank()) {
            if (tell ? !Sky.requirements().checkOrTell(p, req) : !Sky.requirements().check(p, req)) return false;
        }
        return true;
    }

    Lot get(String id) {
        if (id == null) return null;
        Lot l = lots.get(id);
        if (l != null) return l;
        Lot found = null;
        for (Lot x : lots.values()) if (x.id.startsWith(id)) { if (found != null) return null; found = x; }
        return found;
    }

    static List<VaultRules.Entry> entries(Lot l) {
        List<VaultRules.Entry> out = new ArrayList<>();
        for (VBid b : l.bids) if (ACTIVE.equals(b.state)) out.add(new VaultRules.Entry(b.key(), b.amount, b.time));
        return out;
    }

    /** Active bids, best first. */
    static List<VBid> ranked(Lot l) {
        List<VBid> out = new ArrayList<>();
        for (VBid b : l.bids) if (ACTIVE.equals(b.state)) out.add(b);
        out.sort((a, b) -> VaultRules.RANK.compare(new VaultRules.Entry(a.key(), a.amount, a.time),
                new VaultRules.Entry(b.key(), b.amount, b.time)));
        return out;
    }

    static VBid mine(Lot l, Player p, String state) {
        String prof = Sky.profiles().profileId(p);
        for (VBid b : l.bids) {
            if (state.equals(b.state) && p.getUniqueId().equals(b.bidder) && prof.equals(b.profile)) return b;
        }
        return null;
    }

    /** Fallback gates when progression's {@code museum_milestone} prefix is missing (donation counts, the old TUNE). */
    static final String FALLBACK_MAIN = "museum:60", FALLBACK_SPECIAL = "museum:150";

    String requirement(Lot l) { return requirement(VaultRules.specialTrack(l.track)); }

    /** The effective gate of the main ({@code false}) or Special ({@code true}) tracks. */
    String requirement(boolean special) {
        String dial = special ? house.dials.vaultSpecialRequirement.get() : house.dials.vaultRequirement.get();
        return VaultRules.gate(dial, special ? FALLBACK_SPECIAL : FALLBACK_MAIN, VaultAuction::knownPrefix);
    }

    static boolean knownPrefix(String prefix) {
        try { return Sky.requirements().known(prefix); } catch (Throwable t) { return true; }
    }

    boolean unlocked(Player p, Lot l) {
        String req = requirement(l);
        if (req == null || req.isBlank()) return true;
        try { return Sky.requirements().check(p, req); } catch (Throwable t) { return false; }
    }

    double increment() { return Math.max(0, house.dials.vaultIncrement.get()); }

    /** Smallest bid this player may place right now (a raise when they already hold a place). */
    double minFor(Player p, Lot l) {
        VBid own = mine(l, p, ACTIVE);
        if (own != null) return VaultRules.minRaise(own.amount, increment());
        return VaultRules.minNewBid(l.minBid, l.stock, entries(l), increment());
    }

    /** Wins this player can still collect, any year. */
    List<Object[]> winnings(Player p) {
        List<Object[]> out = new ArrayList<>();
        String prof = Sky.profiles().profileId(p);
        for (Lot l : lots.values()) for (VBid b : l.bids) {
            if (WON.equals(b.state) && p.getUniqueId().equals(b.bidder) && prof.equals(b.profile)) out.add(new Object[]{l, b});
        }
        return out;
    }

    // ---- the Regalia Room (accessories hook "auction": SHENS_REGALIA) ---------------------------------------

    /**
     * Does {@code p} carry the Regalia (research 06: "Have this in your inventory to gain access to a unique room
     * hidden below the Auction House")? Accessories' {@code has} answers bag OR inventory; without the accessories
     * module the inventory is scanned directly. Blank dial = nobody.
     */
    boolean regalia(Player p) { return carries(p, house.dials.regaliaItem.get()); }

    static boolean carries(Player p, String itemId) {
        if (p == null || itemId == null || itemId.isBlank()) return false;
        String id = itemId.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            if (Sky.service(com.skyclone.api.feature.Features.Accessories.class).has(p, id)) return true;
        } catch (Throwable ignored) { }
        try {
            for (ItemStack it : p.getInventory().getContents()) {
                if (it != null && !it.getType().isAir() && id.equals(Sky.items().idOf(it))) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    String regaliaName() { return nameOf(house.dials.regaliaItem.get()); }

    static String nameOf(String itemId) {
        if (itemId == null || itemId.isBlank()) return "&7nothing";
        try {
            ItemDef d = Sky.items().def(itemId.trim().toUpperCase(java.util.Locale.ROOT));
            if (d != null && d.name != null) return d.rarity.code() + d.name;
        } catch (Throwable ignored) { }
        return "&6" + Text.titleCase(itemId);
    }

    /** One closed lot as the Regalia Room's ledger shows it. */
    record Past(int year, String itemId, String track, int stock, VaultRules.Result result) {}

    private volatile List<Past> ledger = List.of();
    private volatile long ledgerAt;
    private volatile boolean ledgerLoading;

    /**
     * The ledger of past years (newest first, at most {@code limit} lots), cached for a minute; the first call
     * starts the read on the database thread and returns what is cached (the menu refresh picks it up).
     */
    List<Past> ledger(int limit) {
        long now = System.currentTimeMillis();
        if (!ledgerLoading && now - ledgerAt > 60_000) {
            ledgerLoading = true;
            final int lim = Math.max(1, limit);
            Sky.db().query(c -> {
                Map<String, Object[]> lots = new LinkedHashMap<>();
                Map<String, List<Double>> won = new java.util.HashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id, year, item_id, track, stock FROM auction_vault_lots"
                        + " WHERE closed = 1 ORDER BY year DESC, created ASC LIMIT ?")) {
                    ps.setInt(1, lim);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) lots.put(rs.getString("id"), new Object[]{rs.getInt("year"), rs.getString("item_id"),
                                rs.getString("track"), rs.getInt("stock")});
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT b.lot_id, b.amount FROM auction_vault_bids b"
                        + " JOIN auction_vault_lots l ON l.id = b.lot_id WHERE l.closed = 1 AND b.state IN ('WON','CLAIMED','REFUNDED')");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        if (lots.containsKey(id)) won.computeIfAbsent(id, k -> new ArrayList<>()).add(rs.getDouble(2));
                    }
                }
                List<Past> out = new ArrayList<>();
                for (Map.Entry<String, Object[]> e : lots.entrySet()) {
                    Object[] v = e.getValue();
                    out.add(new Past((Integer) v[0], (String) v[1], (String) v[2], (Integer) v[3],
                            VaultRules.result((Integer) v[3], won.getOrDefault(e.getKey(), List.of()))));
                }
                return out;
            }).whenComplete((r, ex) -> {
                if (ex != null) Sky.log().log(Level.WARNING, "[auction] vault ledger read failed", ex);
                else ledger = List.copyOf(r);
                ledgerAt = System.currentTimeMillis();
                ledgerLoading = false;
            });
        }
        return ledger;
    }

    ItemStack display(Lot l) {
        if (l.display == null) {
            try {
                l.display = ItemCodec.display(Sky.items().create(l.itemId, 1));
            } catch (Throwable t) {
                l.display = com.skyclone.api.ui.Icons.of(org.bukkit.Material.BARRIER, "&c" + l.itemId, "&7This item no longer exists.");
            }
        }
        return l.display;
    }

    String itemName(Lot l) {
        ItemDef d = Sky.items().def(l.itemId);
        return d == null || d.name == null ? "&f" + Text.titleCase(l.itemId) : d.rarity.code() + d.name;
    }

    // ---- the clock -----------------------------------------------------------------------------------------

    /** Every second: close last year's lots, open this year's. */
    void tick() {
        int year = currentYear();
        boolean rolled = false;
        for (Lot l : new ArrayList<>(lots.values())) {
            if (!l.closed && l.year < year) { close(l); rolled = true; }
        }
        if (house.dials.vaultEnabled.get() && !hasYear(year)) {
            int n = generate(year);
            if (n > 0 && (rolled || booted)) {
                Msg.broadcast("&6[Vault] &eThe &6Vault Auction &efor Year " + year + " is open: &a" + n
                        + " lots &eof limited stock. &7(Auction House)");
            }
        } else if (house.dials.vaultEnabled.get() && house.dials.vaultIronman.get() && ironmanMissing(year)) {
            // a year opened before the Ironman tracks existed (or with the dial off) gains them now
            generateIronman(year, System.currentTimeMillis() + lots.size());
        }
        booted = true;
        evictDone();
    }

    boolean hasYear(int year) {
        for (Lot l : lots.values()) if (l.year == year) return true;
        return false;
    }

    /** An OPEN lot of this track this year (closed ones do not count: an admin re-open must copy again). */
    boolean hasTrack(int year, String track) {
        for (Lot l : lots.values()) if (l.year == year && !l.closed && track.equals(l.track)) return true;
        return false;
    }

    /** True when this year has classic lots of a track whose Ironman copy has not been made yet. */
    boolean ironmanMissing(int year) {
        if (!hasTrack(year, IRONMAN) && hasTrack(year, MAIN)) return true;
        return house.dials.vaultIronmanSpecial.get() && !hasTrack(year, IRONMAN_SPECIAL) && hasTrack(year, SPECIAL);
    }

    /**
     * The Ironman-only lots: a copy of every classic lot of the year (main track, and the Special lots while
     * {@code auction.vault.ironman-special} is on) at {@code auction.vault.ironman-stock} of its stock (volume
     * perk included, since the classic stock already carries it), same minimum bids. Each track is copied once:
     * a track that already has its Ironman copy is skipped. Returns how many were made.
     */
    int generateIronman(int year, long created) {
        if (!house.dials.vaultIronman.get()) return 0;
        double frac = house.dials.vaultIronmanStock.get();
        int made = 0;
        List<String> tracks = new ArrayList<>(List.of(MAIN));
        if (house.dials.vaultIronmanSpecial.get()) tracks.add(SPECIAL);
        for (String classic : tracks) {
            String copy = VaultRules.ironmanCopyOf(classic);
            if (hasTrack(year, copy)) continue;
            List<Lot> source = new ArrayList<>();
            for (Lot l : lots.values()) if (l.year == year && !l.closed && classic.equals(l.track)) source.add(l);
            source.sort((a, b) -> Long.compare(a.created, b.created));
            List<VaultRules.Spec> specs = new ArrayList<>();
            for (Lot l : source) specs.add(new VaultRules.Spec(l.itemId, l.stock, l.minBid));
            for (VaultRules.Spec s : VaultRules.ironmanCopies(specs, frac)) {
                addLot(year, s.itemId(), copy, s.stock(), s.minBid(), created + made++);
            }
        }
        if (made > 0) Sky.log().info("[auction] Vault Auction year " + year + ": " + made + " Ironman lots");
        return made;
    }

    /** Create this year's lots. Returns how many were made. */
    int generate(int year) {
        int vol = house.dials.vaultVolume();
        long now = System.currentTimeMillis();
        int made = 0;
        Set<String> used = new HashSet<>();
        for (VaultRules.Spec s : VaultRules.parseItems(house.dials.vaultItems.get())) {
            if (!Sky.items().exists(s.itemId()) || !used.add(s.itemId())) continue;
            addLot(year, s.itemId(), MAIN, s.stock() * vol, s.minBid(), now + made++);
        }
        int count = Math.max(0, house.dials.vaultSpecialCount.get()) + (vol > 1 ? 2 : 0);
        List<String> pool = specialPool(used);
        Random r = new Random(year * 7919L + 17);
        Collections.shuffle(pool, r);
        double frac = house.dials.vaultSpecialMinFraction.get();
        int stock = Math.max(1, house.dials.vaultSpecialStock.get()) * vol;
        for (int i = 0; i < Math.min(count, pool.size()); i++) {
            String id = pool.get(i);
            double v = house.guide.agentValue(id);
            double min = NpcMarket.nice(Math.max(10_000, v * frac));
            addLot(year, id, SPECIAL, stock, min, now + made++);
        }
        made += generateIronman(year, now + made);
        if (made > 0) Sky.log().info("[auction] Vault Auction year " + year + ": " + made + " lots (volume x" + vol + ")");
        return made;
    }

    /** Unique, auctionable EPIC..MYTHIC fishing gear / helmets / wands with a known value, sorted for determinism. */
    List<String> specialPool(Set<String> exclude) {
        List<String> out = new ArrayList<>();
        for (ItemDef d : new ArrayList<>(Sky.items().all())) {
            if (d == null || d.id == null || exclude.contains(d.id)) continue;
            if (!SPECIAL_CATEGORIES.contains(d.category) || !d.isUnique()) continue;
            if (d.rarity.ordinal() < Rarity.EPIC.ordinal() || d.rarity.ordinal() > Rarity.MYTHIC.ordinal()) continue;
            if (!NpcMarket.stockable(d, false)) continue;
            double v;
            try { v = house.guide.agentValue(d.id); } catch (Throwable t) { v = 0; }
            if (v > 0) out.add(d.id);
        }
        Collections.sort(out);
        return out;
    }

    private void addLot(int year, String itemId, String track, int stock, double minBid, long created) {
        Lot l = new Lot();
        l.id = year + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        l.year = year;
        l.itemId = itemId;
        l.track = track;
        l.stock = Math.max(1, stock);
        l.minBid = Math.max(1, minBid);
        l.created = created;
        lots.put(l.id, l);
        saveLot(l);
    }

    /** The year ended (or an admin closed it): everyone still kept wins. */
    void close(Lot l) {
        if (l.closed) return;
        l.closed = true;
        saveLot(l);
        for (VBid b : ranked(l)) {
            b.state = WON;
            saveBid(l, b);
            house.store.log("vault_won", l.id, String.valueOf(b.bidder), b.amount, l.itemId);
            Player p = Bukkit.getPlayer(b.bidder);
            if (p != null && Sky.profiles().isReady(p) && b.profile.equals(Sky.profiles().profileId(p))) {
                tell(p, "&6[Vault] &aYou won " + itemName(l) + " &afor &6" + AuctionRules.coinsWords(b.amount)
                        + " coins&a! Collect it from the Vault Auction.");
            }
        }
    }

    /** Drop finished lots from memory (rows stay as history). */
    private void evictDone() {
        int y = currentYear();
        var it = lots.values().iterator();
        while (it.hasNext()) {
            Lot l = it.next();
            if (!l.closed || l.year >= y) continue;
            boolean owes = false;
            for (VBid b : l.bids) if (WON.equals(b.state)) { owes = true; break; }
            if (!owes) it.remove();
        }
    }

    // ---- bidding -------------------------------------------------------------------------------------------

    boolean bid(Player p, Lot l, double amount) {
        if (!canUse(p, true) || !house.cooldown(p)) return false;
        if (l != null && !forViewer(p, l)) {
            Msg.error(p, ironman(p) ? "Ironman profiles bid on the Ironman Vault lots only." : "That lot is for Ironman profiles only.");
            return false;
        }
        if (l == null || l.closed || l.year != currentYear()) { Msg.error(p, "This lot has closed!"); return false; }
        String req = requirement(l);
        if (req != null && !req.isBlank() && !Sky.requirements().checkOrTell(p, req)) return false;
        amount = Math.ceil(amount);
        if (!(amount > 0) || amount > house.rules().maxPrice) { Msg.error(p, "That is not a valid bid."); return false; }
        String prof = Sky.profiles().profileId(p);
        long now = System.currentTimeMillis();
        VBid own = mine(l, p, ACTIVE);
        double min = minFor(p, l);
        if (amount < min) { Msg.error(p, "Your bid must be at least " + AuctionRules.fmt(min) + " coins!"); return false; }
        double pay = own == null ? amount : amount - own.amount;
        if (!Sky.economy().withdraw(p, pay, "auction: vault bid " + l.itemId + " #" + l.id)) {
            Msg.error(p, "You cannot afford this bid!");
            return false;
        }
        if (own != null) {
            own.amount = amount;
            own.time = now;
            saveBid(l, own);
        } else {
            VBid b = new VBid();
            b.bidder = p.getUniqueId();
            b.profile = prof;
            b.name = p.getName();
            b.amount = amount;
            b.time = now;
            b.state = ACTIVE;
            // an older OUTBID row for the same bidder is replaced (one row per bidder per lot)
            l.bids.removeIf(x -> x.key().equals(b.key()));
            l.bids.add(b);
            saveBid(l, b);
            for (VaultRules.Entry out : VaultRules.displaced(l.stock, entries(l))) {
                for (VBid x : l.bids) {
                    if (!ACTIVE.equals(x.state) || !x.key().equals(out.who())) continue;
                    x.state = OUTBID;
                    saveBid(l, x);
                    final double refund = x.amount;
                    final UUID who = x.bidder;
                    Sky.economy().depositOffline(x.profile, x.bidder, refund, "auction: vault outbid refund #" + l.id)
                            .whenComplete((r, ex) -> {
                                if (ex != null) Sky.log().log(Level.SEVERE, "[auction] VAULT REFUND FAILED: " + refund + " coins to " + who, ex);
                            });
                    house.store.log("vault_outbid", l.id, String.valueOf(x.bidder), refund, null);
                    Player o = Bukkit.getPlayer(x.bidder);
                    if (o != null && Sky.profiles().isReady(o) && house.data(o).notifyBids
                            && !"false".equalsIgnoreCase(AuctionHouse.globalSetting(o, "notify.auction_outbid"))) {
                        tell(o, "&6[Vault] &eYou were pushed out of the &6" + itemName(l) + " &elot by a higher bid. Your &6"
                                + AuctionRules.coinsWords(refund) + " coins &ewere returned.");
                    }
                }
            }
        }
        house.store.log("vault_bid", l.id, p.getUniqueId().toString(), amount, l.itemId);
        house.data(p).vaultBids++;
        Msg.send(p, "&6[Vault] &eYou bid &6" + AuctionRules.coinsWords(amount) + " coins &eon " + itemName(l) + "&e. Place: &a#"
                + VaultRules.place(p.getUniqueId() + "|" + prof, entries(l)) + " &eof &a" + l.stock + "&e.");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
        return true;
    }

    /** Collect every won lot. Returns how many items were handed over. */
    int claimAll(Player p) {
        if (!Sky.profiles().isReady(p)) return 0;
        int n = 0;
        for (Object[] w : winnings(p)) {
            Lot l = (Lot) w[0];
            VBid b = (VBid) w[1];
            if (!Sky.items().exists(l.itemId)) {
                // the item was removed from the game since: never keep the coins for nothing
                b.state = REFUNDED;
                saveBid(l, b);
                Sky.economy().deposit(p, b.amount, "auction: vault refund (item removed) #" + l.id);
                Msg.send(p, "&6[Vault] &eThe item you won no longer exists; your &6" + AuctionRules.coinsWords(b.amount) + " coins &ewere returned.");
                n++;
                continue;
            }
            ItemStack it = Sky.items().create(l.itemId, 1);
            if (!AuctionHouse.hasRoom(p, it)) { Msg.error(p, "Your inventory is full!"); break; }
            b.state = CLAIMED;
            saveBid(l, b);
            house.store.log("vault_claim", l.id, p.getUniqueId().toString(), b.amount, l.itemId);
            Sky.loot().give(p, it, LootSource.AUCTION);
            house.data(p).vaultWon++;
            Msg.send(p, "&6[Vault] &aYou collected " + itemName(l) + "&a!");
            n++;
        }
        if (n > 0) {
            house.inventoryChanged(p);
            Sky.profiles().saveNow(p);
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
        }
        evictDone();
        return n;
    }

    /** Admin: close this year's lots now (winners are decided), optionally opening a fresh set. */
    int adminClose(boolean reopen) {
        int y = currentYear();
        int n = 0;
        // every open lot, including any left dated in the future by a calendar-epoch change
        for (Lot l : new ArrayList<>(lots.values())) if (!l.closed) { close(l); n++; }
        if (reopen) generate(y);
        return n;
    }

    void loginNotice(Player p) {
        int n = winnings(p).size();
        if (n == 0) return;
        Component c = Text.c("&6[Vault] &eYou have &a" + n + " &eVault Auction win" + (n == 1 ? "" : "s") + " to collect! &6&lCLICK")
                .clickEvent(ClickEvent.runCommand("/vaultauction"))
                .hoverEvent(HoverEvent.showText(Text.c("&eClick to open the Vault Auction!")));
        p.sendMessage(c);
    }

    private static void tell(Player p, String legacy) {
        p.sendMessage(Text.c(legacy + " &6&lCLICK").clickEvent(ClickEvent.runCommand("/vaultauction"))
                .hoverEvent(HoverEvent.showText(Text.c("&eClick to open the Vault Auction!"))));
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
    }

    // ---- writes --------------------------------------------------------------------------------------------

    private void saveLot(Lot l) {
        final String id = l.id, item = l.itemId, track = l.track;
        final int year = l.year, stock = l.stock;
        final double min = l.minBid;
        final boolean closed = l.closed;
        final long created = l.created;
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO auction_vault_lots(id, year, item_id, track, stock,"
                    + " min_bid, closed, created) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, id); ps.setInt(2, year); ps.setString(3, item); ps.setString(4, track); ps.setInt(5, stock);
                ps.setDouble(6, min); ps.setInt(7, closed ? 1 : 0); ps.setLong(8, created);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((r, ex) -> { if (ex != null) Sky.log().log(Level.SEVERE, "[auction] vault lot write failed " + id, ex); });
    }

    private void saveBid(Lot l, VBid b) {
        final String lot = l.id, bidder = b.bidder.toString(), profile = b.profile, name = b.name, state = b.state;
        final double amount = b.amount;
        final long time = b.time;
        Sky.db().transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO auction_vault_bids(lot_id, bidder, profile_id, name,"
                    + " amount, time, state) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, lot); ps.setString(2, bidder); ps.setString(3, profile); ps.setString(4, name);
                ps.setDouble(5, amount); ps.setLong(6, time); ps.setString(7, state);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((r, ex) -> { if (ex != null) Sky.log().log(Level.SEVERE, "[auction] vault bid write failed " + lot + " " + bidder, ex); });
    }
}
