package com.skyclone.mod.auction;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The Auction House engine: every operation that moves an item or a coin. All of it runs on the main
 * thread, which is what serialises two players racing for one BIN. Coins move through the economy
 * (its own write-through + ledger); listing rows through {@link AuctionStore}.
 *
 * <p>Order of operations is chosen so that a failure loses nothing it should not:
 * <ul>
 *   <li>Create: the item is ALREADY escrowed (it sits in the create slot, in the database); the fee is
 *       taken; then the listing is written together with the slot's removal in one transaction.</li>
 *   <li>Bid / buy: coins are taken first (withdraw fails = nothing happens), then the listing changes.
 *       An outbid player is refunded at once (to the profile that bid, online or not).</li>
 *   <li>Claim: the claimed flag is set and written BEFORE the item or coins are handed over, so a claim
 *       can never pay twice.</li>
 * </ul>
 */
public final class AuctionHouse implements Features.Auctions {

    final AuctionStore store;
    final PriceGuide guide;
    final AuctionDials dials;
    AuctionMenus menus;   // set by the module once built

    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final Map<String, Double> lowestBinCache = new HashMap<>();
    private long lowestBinAt;

    public AuctionHouse(AuctionStore store, PriceGuide guide, AuctionDials dials) {
        this.store = store;
        this.guide = guide;
        this.dials = dials;
    }

    AuctionRules.Settings rules() { return dials.settings(); }

    AuctionData data(Player p) { return Sky.profiles().member(p, "auction", AuctionData.class); }

    // ---- Features.Auctions ------------------------------------------------------------------------

    @Override
    public double lowestBin(String itemId) {
        long now = System.currentTimeMillis();
        if (now - lowestBinAt > 5_000) {
            lowestBinCache.clear();
            for (Listing l : store.all()) {
                if (!l.active() || !l.bin || l.endsAt <= now || l.itemId == null) continue;
                lowestBinCache.merge(l.itemId, l.unitPrice(), Math::min);
            }
            lowestBinAt = now;
        }
        Double v = lowestBinCache.get(itemId);
        return v == null ? 0 : v;
    }

    void invalidateLowestBin() { lowestBinAt = 0; summaryAt = 0; }

    /** The SkyBlock Menu's entry (menus module): same gates as /ah, including where the player stands. */
    @Override
    public void open(Player p) {
        if (menus != null && canUse(p, true) && reachable(p, true)) menus.main(p).open(p);
    }

    // ---- inventory durability ------------------------------------------------------------------------

    private final java.util.Set<UUID> inventoryDirty = new java.util.HashSet<>();

    /**
     * An item just moved between the player's inventory and auction escrow. Vanilla writes the inventory file
     * only on autosave or quit (minutes), while our rows commit in milliseconds - so a crash in that gap could
     * roll the inventory back against an escrow that already moved, duplicating a stackable (escrow in) or
     * losing a claimed item (escrow out). This writes the player file on the next tick, once per tick however
     * many items moved (Claim All). Same pattern as core's stash ({@code ItemStash}: {@code p.saveData()}).
     */
    void inventoryChanged(Player p) {
        if (p == null || !p.isOnline()) return;
        boolean first = inventoryDirty.isEmpty();
        inventoryDirty.add(p.getUniqueId());
        if (!first) return;
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            for (UUID id : new ArrayList<>(inventoryDirty)) {
                Player o = Bukkit.getPlayer(id);
                if (o == null) continue;          // quitting saves the file anyway
                try { o.saveData(); } catch (Throwable t) { Sky.log().log(Level.WARNING, "[auction] saveData failed for " + o.getName(), t); }
            }
            inventoryDirty.clear();
        });
    }

    // ---- access ----------------------------------------------------------------------------------------

    /** Every gate a player must pass to use the Auction House at all. */
    boolean canUse(Player p, boolean tell) {
        if (!Sky.profiles().isReady(p)) { if (tell) Msg.error(p, "Your profile is still loading."); return false; }
        if (!dials.enabled.get() && !p.hasPermission("skyclone.admin")) {
            if (tell) Msg.error(p, "The Auction House is closed right now.");
            return false;
        }
        String mode = Sky.profiles().mode(p);
        if (specialMode(mode) && !dials.specialModes.get()
                && !("ironman".equalsIgnoreCase(mode) && dials.ironmanCosmetics.get())) {
            if (tell) Msg.error(p, "The Auction House is not available on " + Text.titleCase(mode) + " profiles.");
            return false;
        }
        String req = dials.requirement.get();
        if (req != null && !req.isBlank()) {
            if (tell ? !Sky.requirements().checkOrTell(p, req) : !Sky.requirements().check(p, req)) return false;
        }
        return true;
    }

    private static boolean specialMode(String mode) {
        return mode != null && !mode.isBlank() && !"normal".equalsIgnoreCase(mode);
    }

    /**
     * An Ironman profile on the cosmetic-only Auction House (research 05 3.1): may browse and buy COSMETIC
     * items, may not list, bid on or buy anything else.
     */
    boolean cosmeticOnly(Player p) {
        if (dials.specialModes.get()) return false;
        String mode = Sky.profiles().mode(p);
        return "ironman".equalsIgnoreCase(mode) && dials.ironmanCosmetics.get();
    }

    static boolean isCosmetic(String itemId) {
        if (itemId == null) return false;
        ItemDef d = Sky.items().def(itemId);
        return d != null && d.category == ItemCategory.COSMETIC;
    }

    /** /ah away from the building, when the dial says /ah is not free everywhere. */
    boolean atAuctionHouse(Player p) {
        if (dials.anywhere.get()) return true;
        try {
            Location a = Sky.worlds().anchor("hub", "npc.auction_master");
            if (a == null || a.getWorld() == null || !a.getWorld().equals(p.getWorld())) return false;
            double r = dials.anywhereRange.get();
            return a.distanceSquared(p.getLocation()) <= r * r;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * May this player open the Auction House from where they stand? At the building always; elsewhere only
     * with {@code auction.remote-requirement} (genre: an active Booster Cookie) or {@code auction.anywhere}.
     * Admins are never blocked, so testing works from anywhere.
     */
    boolean reachable(Player p, boolean tell) {
        if (atAuctionHouse(p) || p.hasPermission("skyclone.admin")) return true;
        String req = dials.remoteRequirement.get();
        boolean ok = false;
        if (req != null && !req.isBlank()) {
            try { ok = Sky.requirements().check(p, req); } catch (Throwable t) { ok = false; }
        }
        if (!ok && tell) {
            String perk = req == null || req.isBlank() ? null : Sky.requirements().describe(req);
            Msg.error(p, "You can only use the Auction House at the Auction House in the Hub!"
                    + (perk == null || perk.isBlank() ? "" : " Having " + perk + "&c lets you use &a/ah &canywhere."));
        }
        return ok;
    }

    /**
     * A per-player setting owned by the menus module; null when unset. Read through {@code Features.Settings}
     * (core wave 5: the authoritative member-data value) when menus provides it, else the PDC mirror.
     */
    static String globalSetting(Player p, String key) {
        try {
            if (Sky.has(com.skyclone.api.feature.Features.Settings.class)) {
                return Sky.service(com.skyclone.api.feature.Features.Settings.class).text(p, key, null);
            }
        } catch (Throwable ignored) { }
        try {
            return p.getPersistentDataContainer().get(new NamespacedKey("skyclone", "setting." + key), PersistentDataType.STRING);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- per-profile summary (tab list, login notice) -------------------------------------------------

    /** profile -> {selling, bidding, to collect}; rebuilt at most once a second over every listing. */
    private Map<String, int[]> summary = new HashMap<>();
    private long summaryAt;
    private static final int[] NOTHING = new int[3];

    int[] summary(String profile) {
        long now = System.currentTimeMillis();
        if (now - summaryAt > 1000) {
            Map<String, int[]> s = new HashMap<>();
            java.util.Set<String> bidders = new java.util.HashSet<>();
            for (Listing l : store.all()) {
                if (!l.npc && l.sellerProfile != null && !l.sellerClaimed) {
                    int[] v = s.computeIfAbsent(l.sellerProfile, k -> new int[3]);
                    if (l.active()) v[0]++; else v[2]++;
                }
                if (l.state == Listing.State.SOLD && !l.buyerClaimed && l.topBidderProfile != null) {
                    s.computeIfAbsent(l.topBidderProfile, k -> new int[3])[2]++;
                } else if (l.active() && !l.bids.isEmpty()) {
                    bidders.clear();
                    for (Listing.Bid b : l.bids) if (b.profile() != null && bidders.add(b.profile())) {
                        if (!b.profile().equals(l.sellerProfile)) s.computeIfAbsent(b.profile(), k -> new int[3])[1]++;
                    }
                }
            }
            summary = s;
            summaryAt = now;
        }
        return summary.getOrDefault(profile, NOTHING);
    }

    void invalidateSummary() { summaryAt = 0; }

    /** Anti-spam: false (and a message) when the player acted too recently. */
    boolean cooldown(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastAction.get(p.getUniqueId());
        if (last != null && now - last < dials.actionCooldownMs.get()) {
            Msg.error(p, "Slow down!");
            return false;
        }
        lastAction.put(p.getUniqueId(), now);
        return true;
    }

    void forget(UUID uuid) { lastAction.remove(uuid); }

    // ---- item rules -----------------------------------------------------------------------------------

    /** Null if this stack may be auctioned, else why not. */
    static String auctionable(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return "There is no item to auction.";
        String id = Sky.items().idOf(stack);
        if (id == null) return "This item cannot be auctioned.";
        ItemDef def = Sky.items().def(id);
        if (def == null) return "This item cannot be auctioned.";
        if (def.has("no_ah") || def.has("soulbound") || def.has("no_trade") || def.has("no_auction"))
            return "This item cannot be auctioned!";
        if (def.rarity == Rarity.ADMIN) return "This item cannot be auctioned!";
        if (boundInstance(stack)) return "Co-op Soulbound items cannot be auctioned!";
        return null;
    }

    /**
     * Per-INSTANCE soulbinding (not a def tag): {@code ItemData.extra.soulbound}, {@code coop_soulbound}, or the
     * museum's {@code museum_soulbound} (progression writes the donor profile id on first donation; research 08
     * 6.2). In the reference game a Co-op Soulbound item may only move inside its co-op, and the Auction House
     * sells to anyone, so it is refused outright (progression request 7; bank trades refuse the same keys).
     */
    static boolean boundInstance(ItemStack stack) {
        try {
            var data = Sky.items().data(stack);
            if (data == null || data.extra == null) return false;
            return AuctionRules.boundKeys(data.extra.keySet());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Core's dupe scan (wave 5, {@code ItemService.addDupeScanSource("auction", ...)}): the UNIQUE items this
     * player's profile has in escrow - the Create slot, its own listings and unclaimed returns, and wins not yet
     * collected. A crash that rolled an inventory back against an escrow move leaves the same uuid in both places;
     * the scan then logs it to {@code items_dupe_log} and tells online admins. Never throws.
     */
    List<ItemStack> escrowedStacks(Player p) {
        List<ItemStack> out = new ArrayList<>();
        try {
            if (p == null || !Sky.profiles().isReady(p)) return out;
            String prof = Sky.profiles().profileId(p);
            ItemStack slot = pendingItem(p);
            if (slot != null && uuidOf(slot) != null) out.add(slot);
            for (Listing l : store.all()) {
                if (l.itemUuid == null || l.itemUuid.isBlank() || !prof.equals(l.escrowOwner())) continue;
                try { out.add(ItemCodec.decode(l.item)); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Does the player already hold an item with this uuid (inventory or ender chest)? */
    static boolean holdsUuid(Player p, String uuid) {
        if (uuid == null) return false;
        for (ItemStack it : p.getInventory().getContents()) if (sameUuid(it, uuid)) return true;
        for (ItemStack it : p.getEnderChest().getContents()) if (sameUuid(it, uuid)) return true;
        return false;
    }

    private static boolean sameUuid(ItemStack it, String uuid) {
        if (it == null || it.getType().isAir()) return false;
        try { return uuid.equals(Sky.items().data(it).uuid); } catch (Throwable t) { return false; }
    }

    static String uuidOf(ItemStack it) {
        try {
            String u = Sky.items().data(it).uuid;
            return u == null || u.isBlank() ? null : u;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Room for this whole stack in the main inventory (merging into matching stacks counts). */
    static boolean hasRoom(Player p, ItemStack stack) {
        PlayerInventory inv = p.getInventory();
        int need = stack.getAmount();
        for (int i = 0; i < 36; i++) {
            ItemStack cur = inv.getItem(i);
            if (cur == null || cur.getType().isAir()) return true;
            if (cur.isSimilar(stack)) need -= Math.max(0, cur.getMaxStackSize() - cur.getAmount());
            if (need <= 0) return true;
        }
        return false;
    }

    // ---- the create slot ----------------------------------------------------------------------------------

    /** Move an item from the player's inventory slot into their Create Auction slot (escrowed in the DB). */
    String putInSlot(Player p, int invSlot, ItemStack live) {
        if (!canUse(p, false)) return "You cannot use the Auction House right now.";
        if (cosmeticOnly(p)) return IRONMAN_SELL;
        String why = auctionable(live);
        if (why != null) return why;
        String prof = Sky.profiles().profileId(p);
        ItemStack inSlot = p.getInventory().getItem(invSlot);
        if (inSlot == null || !inSlot.isSimilar(live) || inSlot.getAmount() != live.getAmount()) return "That item moved.";
        // a full slot SWAPS: the old item goes where the new one was (one pending write, nothing in limbo)
        ItemStack old = store.pending(p.getUniqueId(), prof) == null ? null : pendingItem(p);
        if (store.pending(p.getUniqueId(), prof) != null && old == null) return "The item in the slot could not be read; ask an admin.";
        String oldUuid = old == null ? null : uuidOf(old);
        // capture the escrow bytes BEFORE the inventory changes: inSlot is a view of the live slot
        String escrow = ItemCodec.encode(inSlot);
        p.getInventory().setItem(invSlot, null);
        if (old != null && !(oldUuid != null && holdsUuid(p, oldUuid))) {
            if (invSlot < 36) p.getInventory().setItem(invSlot, old);      // storage slot: a straight swap
            else Sky.loot().give(p, old, LootSource.AUCTION);                // armour/off-hand: never force an item onto the body
        }
        // the inventory file is saved only once the escrow row is committed: a crash in between then
        // duplicates (old inventory file + escrow row) rather than loses (no file copy, no row)
        UUID who = p.getUniqueId();
        store.setPending(who, prof, escrow).whenComplete((r, ex) -> {
            if (ex == null) Bukkit.getScheduler().runTask(Sky.plugin(), () -> inventoryChanged(Bukkit.getPlayer(who)));
        });
        Sky.profiles().saveNow(p);
        Msg.sound(p, Sound.ENTITY_ITEM_PICKUP, 1.2f);
        return null;
    }

    /** The item in the player's Create Auction slot, or null. */
    ItemStack pendingItem(Player p) {
        String b = store.pending(p.getUniqueId(), Sky.profiles().profileId(p));
        if (b == null) return null;
        try { return ItemCodec.decode(b); } catch (Throwable t) {
            Sky.log().log(Level.WARNING, "[auction] unreadable pending item for " + p.getName(), t);
            return null;
        }
    }

    /** Give the Create Auction slot's item back. */
    String takeFromSlot(Player p) {
        String prof = Sky.profiles().profileId(p);
        ItemStack it = pendingItem(p);
        if (it == null) {
            if (store.pending(p.getUniqueId(), prof) != null) return "That item could not be read; ask an admin (/sb auction audit).";
            return "The slot is empty.";
        }
        if (!hasRoom(p, it)) return "Your inventory is full!";
        String uuid = uuidOf(it);
        store.setPending(p.getUniqueId(), prof, null);
        if (uuid != null && holdsUuid(p, uuid)) {
            // only possible after a crash rolled the inventory back: never hand out a second copy
            Sky.log().warning("[auction] " + p.getName() + " already holds item " + uuid + "; pending copy discarded");
            store.log("pending_dupe_discarded", null, p.getUniqueId().toString(), 0, uuid);
            return null;
        }
        Sky.loot().give(p, it, LootSource.AUCTION);
        inventoryChanged(p);
        Sky.profiles().saveNow(p);
        return null;
    }

    // ---- create -----------------------------------------------------------------------------------------

    /** Count of this profile's listings that use a slot (active, or ended and not yet fully claimed by the seller). */
    int slotsUsed(String profile) {
        int n = 0;
        for (Listing l : store.all()) if (l.isSellerSide(profile) && !l.sellerClaimed) n++;
        return n;
    }

    int maxSlots(Player p) {
        int members = 1;
        try { members = Math.max(1, Sky.profiles().members(Sky.profiles().profileId(p)).size()); } catch (Throwable ignored) { }
        return AuctionRules.maxListings(rules(), members);
    }

    /** Highest price this stack may be listed at with these terms (+infinity unless it is COMMON with an NPC value). */
    double priceCap(ItemStack item, boolean bin, long minutes) {
        if (item == null) return Double.POSITIVE_INFINITY;
        Rarity r;
        try { r = Sky.items().rarity(item); } catch (Throwable t) { return Double.POSITIVE_INFINITY; }
        ItemDef def = Sky.items().def(Sky.items().idOf(item));
        return AuctionRules.commonCap(rules(), r.name(), def == null ? 0 : def.npcSell, item.getAmount(), bin, minutes);
    }

    /** Validate without side effects; null = would succeed. */
    static final String IRONMAN_SELL = "Ironman profiles can only buy cosmetics on the Auction House.";

    String checkCreate(Player p, ItemStack item, boolean bin, double price, long minutes) {
        if (!canUse(p, false)) return "You cannot use the Auction House right now.";
        if (cosmeticOnly(p)) return IRONMAN_SELL;
        if (item == null) return "Click an item in your inventory to put it up for auction first.";
        String why = auctionable(item);
        if (why != null) return why;
        AuctionRules.Settings s = rules();
        String id = Sky.items().idOf(item);
        ItemDef def = Sky.items().def(id);
        why = AuctionRules.checkPrice(s, price, Sky.items().rarity(item).name(), def == null ? 0 : def.npcSell, item.getAmount(), bin, minutes);
        if (why != null) return why;
        why = AuctionRules.checkDuration(s, minutes);
        if (why != null) return why;
        String prof = Sky.profiles().profileId(p);
        if (slotsUsed(prof) >= maxSlots(p)) return "You have reached the maximum of " + maxSlots(p) + " auctions! Claim or cancel some first.";
        double fee = AuctionRules.totalFee(s, bin, price, minutes);
        if (Sky.economy().purse(p) < fee) return "You need " + AuctionRules.fmt(fee) + " coins in your purse to pay the listing fee.";
        return null;
    }

    /** Create the listing from the Create Auction slot. Returns the new listing, or null after telling the player why. */
    Listing create(Player p, boolean bin, double price, long minutes) {
        if (!cooldown(p)) return null;
        ItemStack item = pendingItem(p);
        String why = checkCreate(p, item, bin, price, minutes);
        if (why != null) { Msg.error(p, why); return null; }
        String uuid = uuidOf(item);
        if (uuid != null && holdsUuid(p, uuid)) {
            store.setPending(p.getUniqueId(), Sky.profiles().profileId(p), null);
            Msg.error(p, "That item is already in your inventory.");
            return null;
        }
        AuctionRules.Settings s = rules();
        double fee = AuctionRules.totalFee(s, bin, price, minutes);
        String itemId = Sky.items().idOf(item);
        if (fee > 0 && !Sky.economy().withdraw(p, fee, "auction: listing fee " + itemId + " x" + item.getAmount())) {
            Msg.error(p, "You cannot afford the " + AuctionRules.fmt(fee) + " coin listing fee.");
            return null;
        }
        long now = System.currentTimeMillis();
        Listing l = build(item, itemId, bin, price, now, minutes);
        l.seller = p.getUniqueId();
        l.sellerProfile = Sky.profiles().profileId(p);
        l.sellerName = p.getName();
        l.fees = fee;
        l.buyableAt = bin ? now + s.binGraceSeconds * 1000L : now;
        store.add(l);
        store.saveFromPending(l, p.getUniqueId(), l.sellerProfile);
        store.log("create", l.id, p.getUniqueId().toString(), price, itemId + " x" + l.amount + (bin ? " BIN" : " AUCTION") + " fee " + fee);
        invalidateLowestBin();
        AuctionData d = data(p);
        d.auctionsCreated++;
        d.feesPaid += fee;
        d.createPrice = price;
        d.createMinutes = minutes;
        d.createBin = bin;
        Sky.profiles().saveNow(p);
        Msg.send(p, (bin ? "&aBIN Auction started for " : "&aAuction started for ") + l.itemName + "&a!");
        if (fee > 0) Msg.send(p, "&7Listing fee paid: &6" + AuctionRules.coinsWords(fee) + " coins");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        return l;
    }

    /** A listing shell around an item (no seller fields). */
    static Listing build(ItemStack item, String itemId, boolean bin, double price, long now, long minutes) {
        Listing l = new Listing();
        l.id = UUID.randomUUID().toString().replace("-", "");
        l.item = ItemCodec.encode(item);
        l.itemId = itemId;
        l.itemUuid = uuidOf(item);
        l.itemName = ItemCodec.name(item, itemId);
        l.search = ItemCodec.searchText(item, l.itemName, itemId);
        Rarity r;
        try { r = Sky.items().rarity(item); } catch (Throwable t) { r = Rarity.COMMON; }
        l.rarity = r.name();
        ItemDef def = Sky.items().def(itemId);
        // skull-textured items are PLAYER_HEAD, which Minecraft calls a block: they are not building blocks
        boolean block = item.getType().isBlock() && item.getType() != org.bukkit.Material.PLAYER_HEAD;
        l.tab = AhTab.of(def == null ? null : def.category, block);
        l.amount = item.getAmount();
        l.bin = bin;
        l.startingBid = price;
        l.created = now;
        l.buyableAt = now;
        l.endsAt = now + minutes * 60_000L;
        return l;
    }

    // ---- bid ----------------------------------------------------------------------------------------------

    boolean bid(Player p, Listing l, double amount) {
        if (!canUse(p, true) || !cooldown(p)) return false;
        long now = System.currentTimeMillis();
        String prof = Sky.profiles().profileId(p);
        if (l == null || !l.active() || l.endsAt <= now) { Msg.error(p, "This auction has ended!"); return false; }
        if (l.bin) { Msg.error(p, "This is a Buy It Now auction."); return false; }
        if (cosmeticOnly(p) && !isCosmetic(l.itemId)) { Msg.error(p, IRONMAN_SELL); return false; }
        if (l.isSellerSide(prof)) { Msg.error(p, "You cannot bid on your own auction!"); return false; }
        if (prof.equals(l.topBidderProfile)) { Msg.error(p, "You already have the highest bid!"); return false; }
        AuctionRules.Settings s = rules();
        double min = AuctionRules.minNextBid(s, l.startingBid, l.topBid);
        amount = Math.ceil(amount);
        if (!(amount >= min)) { Msg.error(p, "Your bid must be at least " + AuctionRules.fmt(min) + " coins!"); return false; }
        if (amount > s.maxPrice) { Msg.error(p, "That bid is too large."); return false; }
        if (!Sky.economy().withdraw(p, amount, "auction: bid #" + l.shortId() + " on " + l.itemId)) {
            Msg.error(p, "You cannot afford this bid!");
            return false;
        }
        placeBid(l, p.getUniqueId(), prof, p.getName(), amount, false, now);
        AuctionData d = data(p);
        d.bidsPlaced++;
        d.highestBid = Math.max(d.highestBid, amount);
        Msg.send(p, "&eBid of &6" + AuctionRules.coinsWords(amount) + " coins &eplaced for " + l.itemName + "&e!");
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.4f);
        return true;
    }

    /** Record a bid (coins already taken), refund the previous top bidder, extend for anti-snipe, notify. */
    void placeBid(Listing l, UUID bidder, String profile, String name, double amount, boolean npc, long now) {
        UUID prevUuid = l.topBidder;
        String prevProfile = l.topBidderProfile;
        String prevName = l.topBidderName;
        double prev = l.topBid;
        l.topBid = amount;
        l.topBidder = bidder;
        l.topBidderProfile = profile;
        l.topBidderName = name;
        l.bidCount++;
        l.endsAt = AuctionRules.endAfterBid(rules(), l.endsAt, now);
        Listing.Bid b = new Listing.Bid(bidder, profile, name, amount, now, npc);
        l.bids.add(b);
        store.saveWithBid(l, b);
        store.log("bid", l.id, npc ? "npc:" + name : String.valueOf(bidder), amount, null);
        if (prevUuid != null && prev > 0) {
            Sky.economy().depositOffline(prevProfile, prevUuid, prev, "auction: outbid refund #" + l.shortId())
                    .whenComplete((r, ex) -> {
                        if (ex != null) Sky.log().log(Level.SEVERE, "[auction] OUTBID REFUND FAILED: " + prev + " coins to " + prevUuid
                                + " (" + prevName + ") for " + l.id, ex);
                    });
            notify(prevUuid, "&6[Auction] " + (npc ? "&d" : "&a") + name + " &eoutbid you by &6" + AuctionRules.coinsWords(amount - prev)
                    + " coins &efor " + l.itemName + "&e! Your &6" + AuctionRules.coinsWords(prev) + " coins &ewere returned.", l.id, true, true);
        }
        if (l.seller != null && !l.npc) {
            notify(l.seller, "&6[Auction] " + (npc ? "&d" : "&a") + name + " &ebid &6" + AuctionRules.coinsWords(amount)
                    + " coins &eon " + l.itemName + "&e!", l.id, true);
        }
        invalidateSummary();
    }

    // ---- BIN ------------------------------------------------------------------------------------------------

    boolean buy(Player p, Listing l) {
        if (!canUse(p, true) || !cooldown(p)) return false;
        long now = System.currentTimeMillis();
        String prof = Sky.profiles().profileId(p);
        if (l == null || !l.active() || l.endsAt <= now) { Msg.error(p, "This auction has ended!"); return false; }
        if (!l.bin) { Msg.error(p, "This is not a Buy It Now auction."); return false; }
        if (cosmeticOnly(p) && !isCosmetic(l.itemId)) { Msg.error(p, IRONMAN_SELL); return false; }
        if (l.isSellerSide(prof)) { Msg.error(p, "You cannot buy your own item!"); return false; }
        if (now < l.buyableAt) {
            Msg.error(p, "This BIN was just listed - you can buy it in " + AuctionRules.timeLeft(l.buyableAt - now) + ".");
            return false;
        }
        double price = l.startingBid;
        if (!Sky.economy().withdraw(p, price, "auction: bought " + l.itemId + " x" + l.amount + " #" + l.shortId())) {
            Msg.error(p, "You don't have enough coins to buy this!");
            return false;
        }
        markSold(l, p.getUniqueId(), prof, p.getName(), price, false, now);
        AuctionData d = data(p);
        d.itemsBought++;
        d.coinsSpent += price;
        Msg.send(p, "&aYou purchased " + l.itemName + " &afor &6" + AuctionRules.coinsWords(price) + " coins&a!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.8f);
        // the item is handed over at once when there is room; otherwise it waits under View Bids
        ItemStack got = decodeQuiet(l);
        if (got != null && hasRoom(p, got)) claimBuyer(p, l, true);
        else Msg.send(p, "&eYour inventory is full - collect it from &aView Bids &ein the Auction House.");
        return true;
    }

    /** SOLD bookkeeping shared by a BIN purchase, an NPC purchase and an auction's end. */
    void markSold(Listing l, UUID buyer, String buyerProfile, String buyerName, double price, boolean npcBuyer, long now) {
        l.state = Listing.State.SOLD;
        l.topBid = price;
        if (l.bin) {
            l.topBidder = buyer;
            l.topBidderProfile = buyerProfile;
            l.topBidderName = buyerName;
            l.bidCount = Math.max(1, l.bidCount);
            l.bids.add(new Listing.Bid(buyer, buyerProfile, buyerName, price, now, npcBuyer));
        }
        l.soldAt = now;
        if (l.npc) l.sellerClaimed = true;           // the Agent's consignment: nobody to pay
        if (npcBuyer || l.topBidder == null) l.buyerClaimed = true;   // an Agent bought it: the item leaves the game
        store.save(l);
        store.log("sold", l.id, npcBuyer ? "npc:" + buyerName : String.valueOf(buyer), price, l.itemId);
        invalidateLowestBin();
        if (!l.npc && !npcBuyer && l.itemId != null) {
            double unit = price / Math.max(1, l.amount);
            guide.recordSale(l.itemId, unit);
            store.recordSale(l.itemId, unit);
        }
        if (!l.npc && l.seller != null) {
            String what = l.bin
                    ? "&6[Auction] &a" + buyerName + " &ebought " + l.itemName + " &efor &6" + AuctionRules.coinsWords(price) + " coins&e!"
                    : "&6[Auction] &eYour auction for " + l.itemName + " &esold to &a" + buyerName + " &efor &6" + AuctionRules.coinsWords(price) + " coins&e!";
            notify(l.seller, what, l.id, false);
            autoCollectSeller(l);
        }
    }

    // ---- cancel -----------------------------------------------------------------------------------------------

    boolean cancel(Player p, Listing l) {
        if (!canUse(p, true) || !cooldown(p)) return false;
        String prof = Sky.profiles().profileId(p);
        if (l == null || !l.isSellerSide(prof)) { Msg.error(p, "That is not your auction."); return false; }
        if (!l.active()) { Msg.error(p, "This auction has already ended."); return false; }
        if (!l.bin && l.bidCount > 0) { Msg.error(p, "You cannot cancel an auction that has bids!"); return false; }
        l.state = Listing.State.CANCELLED;
        l.endsAt = Math.min(l.endsAt, System.currentTimeMillis());
        store.save(l);
        store.log("cancel", l.id, p.getUniqueId().toString(), 0, l.itemId);
        invalidateLowestBin();
        Msg.send(p, "&eYou cancelled your auction for " + l.itemName + "&e. The listing fee is not refunded.");
        ItemStack back = decodeQuiet(l);
        if (back != null && hasRoom(p, back)) claimSeller(p, l, true);
        else Msg.send(p, "&eYour inventory is full - collect the item from &aManage Auctions&e.");
        return true;
    }

    /** Admin: end a listing without a sale. Refunds the top bidder (if a player); the item becomes the seller's to collect. */
    void adminCancel(Listing l, String why) {
        if (!l.active()) return;
        if (l.topBidder != null && l.topBid > 0) {
            Sky.economy().depositOffline(l.topBidderProfile, l.topBidder, l.topBid, "auction: admin cancel refund #" + l.shortId());
            notify(l.topBidder, "&6[Auction] &eAn auction you bid on was cancelled by staff; your &6"
                    + AuctionRules.coinsWords(l.topBid) + " coins &ewere returned.", l.id, false);
        }
        l.state = Listing.State.CANCELLED;
        l.topBid = 0;
        l.topBidder = null;
        l.topBidderProfile = null;
        if (l.npc) l.sellerClaimed = true;
        store.save(l);
        store.log("admin_cancel", l.id, "admin", 0, why);
        invalidateLowestBin();
        if (l.seller != null) notify(l.seller, "&6[Auction] &eYour auction for " + l.itemName + " &ewas cancelled by staff. Collect the item from Manage Auctions.", l.id, false);
    }

    // ---- claims ------------------------------------------------------------------------------------------------

    /** Is there anything on this listing for this player to collect? */
    boolean claimable(Player p, Listing l) {
        String prof = Sky.profiles().profileId(p);
        if (l.isSellerSide(prof) && !l.active() && !l.sellerClaimed) return true;
        return l.isBuyerSide(prof) && !l.buyerClaimed;
    }

    /** Collect whatever this listing owes the player. */
    boolean claim(Player p, Listing l) {
        if (!Sky.profiles().isReady(p) || l == null) return false;
        String prof = Sky.profiles().profileId(p);
        boolean did = false;
        if (l.isSellerSide(prof) && !l.active() && !l.sellerClaimed) did = claimSeller(p, l, false);
        if (l.isBuyerSide(prof) && !l.buyerClaimed) did |= claimBuyer(p, l, false);
        return did;
    }

    boolean claimSeller(Player p, Listing l, boolean quiet) {
        if (l.sellerClaimed || l.active()) return false;
        if (l.state == Listing.State.SOLD) {
            AuctionRules.Settings s = rules();
            double payout = AuctionRules.claimPayout(s, l.topBid);
            double tax = Math.max(0, l.topBid - payout);
            l.sellerClaimed = true;
            store.save(l);
            store.log("claim_coins", l.id, p.getUniqueId().toString(), payout, "tax " + tax);
            if (payout > 0) Sky.economy().deposit(p, payout, "auction: sold " + l.itemId + " x" + l.amount + " #" + l.shortId());
            AuctionData d = data(p);
            d.auctionsSold++;
            d.coinsEarned += payout;
            d.taxPaid += tax;
            d.highestSale = Math.max(d.highestSale, l.topBid);
            Msg.send(p, "&eYou collected &6" + AuctionRules.coinsWords(payout) + " coins &efrom selling " + l.itemName
                    + " &eto &a" + (l.topBidderName == null ? "a buyer" : l.topBidderName) + "&e!");
            if (tax > 0) Msg.send(p, "&7Claim tax: &6" + AuctionRules.fmt(tax) + " coins");
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
            evictIfDone(l);
            return true;
        }
        // EXPIRED / CANCELLED: the item comes home
        ItemStack it = decodeQuiet(l);
        if (it == null) { Msg.error(p, "That item could not be read; ask an admin to look at auction #" + l.shortId() + "."); return false; }
        if (!hasRoom(p, it)) { if (!quiet) Msg.error(p, "Your inventory is full!"); return false; }
        l.sellerClaimed = true;
        store.save(l);
        store.log("claim_item_back", l.id, p.getUniqueId().toString(), 0, l.itemId);
        if (l.state == Listing.State.EXPIRED) data(p).auctionsExpired++;
        deliver(p, l, it);
        if (!quiet) Msg.send(p, "&eYou collected " + l.itemName + " &eback from your auction.");
        evictIfDone(l);
        return true;
    }

    boolean claimBuyer(Player p, Listing l, boolean quiet) {
        if (l.buyerClaimed || l.state != Listing.State.SOLD) return false;
        ItemStack it = decodeQuiet(l);
        if (it == null) { Msg.error(p, "That item could not be read; ask an admin to look at auction #" + l.shortId() + "."); return false; }
        if (!hasRoom(p, it)) { if (!quiet) Msg.error(p, "Your inventory is full!"); return false; }
        l.buyerClaimed = true;
        store.save(l);
        store.log("claim_item", l.id, p.getUniqueId().toString(), l.topBid, l.itemId);
        if (!l.bin) data(p).auctionsWon++;
        deliver(p, l, it);
        if (!quiet) {
            Msg.send(p, "&eYou claimed " + l.itemName + " &efrom " + (l.npc ? "&d" : "&a") + (l.sellerName == null ? "an Agent" : l.sellerName) + "&e's auction!");
        }
        evictIfDone(l);
        return true;
    }

    /** Hand the escrowed item over. A unique item already in the player's hands is never given twice. */
    private void deliver(Player p, Listing l, ItemStack it) {
        String uuid = uuidOf(it);
        if (uuid != null && holdsUuid(p, uuid)) {
            Sky.log().warning("[auction] " + p.getName() + " already holds " + uuid + " from auction " + l.id + "; not delivered twice");
            store.log("deliver_dupe_blocked", l.id, p.getUniqueId().toString(), 0, uuid);
            return;
        }
        Sky.loot().give(p, it, LootSource.AUCTION);
        inventoryChanged(p);
        Sky.profiles().saveNow(p);
    }

    int claimAll(Player p) {
        int n = 0;
        for (Listing l : new ArrayList<>(store.all())) {
            if (claimable(p, l) && claim(p, l)) n++;
        }
        if (n == 0) Msg.error(p, "There was nothing you could collect.");
        return n;
    }

    private void evictIfDone(Listing l) { summaryAt = 0; if (l.settled()) store.evictSettled(); }

    static ItemStack decodeQuiet(Listing l) {
        try { return ItemCodec.decode(l.item); } catch (Throwable t) {
            Sky.log().log(Level.WARNING, "[auction] unreadable item in auction " + l.id, t);
            return null;
        }
    }

    /** With auction.auto-collect on: pay an online seller at once. */
    private void autoCollectSeller(Listing l) {
        if (!dials.autoCollect.get() || l.seller == null) return;
        Player s = Bukkit.getPlayer(l.seller);
        if (s != null && Sky.profiles().isReady(s) && l.isSellerSide(Sky.profiles().profileId(s))) claimSeller(s, l, true);
    }

    private void autoCollectBuyer(Listing l) {
        if (!dials.autoCollect.get() || l.topBidder == null) return;
        Player b = Bukkit.getPlayer(l.topBidder);
        if (b != null && Sky.profiles().isReady(b) && l.isBuyerSide(Sky.profiles().profileId(b))) claimBuyer(b, l, true);
    }

    // ---- the clock -------------------------------------------------------------------------------------------------

    /** Every second: end what is due. */
    void sweep() {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Listing l : new ArrayList<>(store.all())) {
            if (!l.active() || l.endsAt > now) continue;
            any = true;
            end(l, now);
        }
        if (any) { store.evictSettled(); invalidateLowestBin(); }
    }

    void end(Listing l, long now) {
        if (!l.active()) return;
        if (!l.bin && l.topBid > 0) {
            boolean npcWinner = l.topBidder == null;
            markSold(l, l.topBidder, l.topBidderProfile, l.topBidderName, l.topBid, npcWinner, l.endsAt);
            if (npcWinner && l.sellerProfile != null) store.addNpcPaid(l.sellerProfile, l.topBid);
            if (l.topBidder != null) {
                notify(l.topBidder, "&6[Auction] &eYou won " + l.itemName + " &efor &6" + AuctionRules.coinsWords(l.topBid)
                        + " coins&e! Collect it from View Bids.", l.id, false);
                autoCollectBuyer(l);
            }
            return;
        }
        l.state = Listing.State.EXPIRED;
        if (l.npc) l.sellerClaimed = true;
        store.save(l);
        store.log("expired", l.id, null, 0, l.itemId);
        if (l.seller != null && !l.npc) {
            notify(l.seller, "&6[Auction] &eYour auction for " + l.itemName + " &eended without a buyer. Collect it from Manage Auctions.", l.id, false);
        }
    }

    // ---- messages --------------------------------------------------------------------------------------------------

    /** Chat to a player if online, clickable to open that auction. {@code bidNotice}: honours the player's bid-notification toggle. */
    void notify(UUID who, String legacy, String auctionId, boolean bidNotice) { notify(who, legacy, auctionId, bidNotice, false); }

    /** {@code outbid}: also honours the global Settings toggle {@code notify.auction_outbid} (menus module). */
    void notify(UUID who, String legacy, String auctionId, boolean bidNotice, boolean outbid) {
        if (who == null) return;
        Player p = Bukkit.getPlayer(who);
        if (p == null || !p.isOnline() || !Sky.profiles().isReady(p)) return;
        AuctionData d = data(p);
        if (bidNotice ? !d.notifyBids : !d.notifySales) return;
        if (outbid && "false".equalsIgnoreCase(globalSetting(p, "notify.auction_outbid"))) return;
        Component c = Text.c(legacy + " &6&lCLICK")
                .clickEvent(ClickEvent.runCommand("/ahview " + auctionId))
                .hoverEvent(HoverEvent.showText(Text.c("&eClick to open this auction!")));
        p.sendMessage(c);
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
    }

    /** "You have N auctions to collect" on login. */
    void loginNotice(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        int n = 0;
        for (Listing l : store.all()) if (claimable(p, l)) n++;
        if (n > 0) {
            Component c = Text.c("&6[Auction] &eYou have &a" + n + " &eauction" + (n == 1 ? "" : "s") + " to collect! &6&lCLICK")
                    .clickEvent(ClickEvent.runCommand("/ah"))
                    .hoverEvent(HoverEvent.showText(Text.c("&eClick to open the Auction House!")));
            p.sendMessage(c);
        }
        if (pendingItem(p) != null) Msg.send(p, "&6[Auction] &eAn item is still waiting in your &aCreate Auction &eslot.");
    }

    // ---- views for menus -----------------------------------------------------------------------------------------

    /** This profile's listings: active first (ending soonest), then ended ones waiting to be collected. */
    List<Listing> mine(Player p) {
        String prof = Sky.profiles().profileId(p);
        List<Listing> out = new ArrayList<>();
        for (Listing l : store.all()) if (l.isSellerSide(prof) && !l.sellerClaimed) out.add(l);
        out.sort((a, b) -> {
            if (a.active() != b.active()) return a.active() ? -1 : 1;
            return Long.compare(a.endsAt, b.endsAt);
        });
        return out;
    }

    /** Auctions this profile bid on that still matter: running, or won and not collected. */
    List<Listing> myBids(Player p) {
        String prof = Sky.profiles().profileId(p);
        List<Listing> out = new ArrayList<>();
        for (Listing l : store.all()) {
            if (l.isBuyerSide(prof) && !l.buyerClaimed) { out.add(l); continue; }
            if (l.active() && l.hasBidFrom(prof)) out.add(l);
        }
        out.sort((a, b) -> Long.compare(a.endsAt, b.endsAt));
        return out;
    }
}
