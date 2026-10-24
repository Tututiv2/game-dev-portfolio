package com.skyclone.mod.auction;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Every Auction House screen. Layouts follow the genre (research 05 section 3.6 and 11.5):
 * <pre>
 *   Auction House      11 Browse · 13 View Bids · 15 Create/Manage · 4 How it Works · 22 Vault Auction
 *                      29 Price Check · 30 Go Back · 31 Close · 32 Auction Stats · 33 Settings
 *   Auctions Browser   tabs down the left (0,9,18,27,36,45); 24 results (11-16, 20-25, 29-34, 38-43);
 *                      46 prev · 47 Search · 48 Go Back · 49 Close · 50 Sort · 51 Rarity · 52 BIN filter · 53 next
 *   Auction View       13 item · 29 Bid (Confirm Bid) / Cancel / Collect · 31 Bid History or Buy · 33 Bid Amount / Price Guide
 *                      48 Go Back · 49 Close · 50 Seller's other auctions
 *   Create Auction     13 item slot (click an item in your inventory) · 29 Create · 31 Price · 33 Duration
 *                      40 Price Guide · 4 slots used · 48 Go Back · 49 Close · 50 Switch BIN/Auction
 * </pre>
 * Every icon has a click hint; locked or impossible actions say why in red.
 */
public final class AuctionMenus {

    static final int[] RESULT_SLOTS = {11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24, 25, 29, 30, 31, 32, 33, 34, 38, 39, 40, 41, 42, 43};
    static final int[] TAB_SLOTS = {0, 9, 18, 27, 36, 45};

    private final AuctionHouse house;
    /** Set by the module once the Vault Auction is built (null = no Vault button). */
    VaultMenus vaultMenus;
    private final Map<UUID, BrowseQuery> queries = new HashMap<>();
    /** viewer -> listing id -> custom bid amount they typed. */
    private final Map<UUID, Map<String, Double>> customBids = new HashMap<>();

    AuctionMenus(AuctionHouse house) { this.house = house; }

    void forget(UUID uuid) { queries.remove(uuid); customBids.remove(uuid); }

    private static Material mat(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m == null ? fallback : m;
    }

    private static String coins(double v) { return "&6" + AuctionRules.coinsWords(v) + " coins"; }

    // =====================================================================================================
    // Main menu
    // =====================================================================================================

    Menu main(Player p) {
        Menu m = new Menu("Auction House", 4);
        m.drawer(menu -> drawMain(p, menu));
        m.refreshEvery(40);
        return m;
    }

    private void drawMain(Player p, Menu m) {
        m.chrome();
        int bids = 0, toCollectBids = 0;
        String prof = Sky.profiles().profileId(p);
        for (Listing l : house.myBids(p)) {
            if (l.active()) bids++;
            else if (l.isBuyerSide(prof) && !l.buyerClaimed) toCollectBids++;
        }
        List<Listing> mine = house.mine(p);
        int running = 0, toCollectMine = 0;
        for (Listing l : mine) { if (l.active()) running++; else toCollectMine++; }

        m.set(4, Icons.of(Material.BOOK, "&aHow it Works", howItWorks()));

        m.set(11, Icons.of(Material.GOLD_BLOCK, "&6Auctions Browser", List.of(
                "&7Find items for sale by players and the",
                "&7Auction Agents.",
                "",
                "&7Some are &6auctions&7: the top bid when the",
                "&7timer runs out wins. Others are &aBuy It Now&7:",
                "&7the first player to pay takes it.",
                "",
                "&7Listings right now: &e" + Text.commasInt(activeCount()),
                "",
                "&eClick to browse!")), c -> browse(c.player()).open(c.player()));

        List<String> bl = new ArrayList<>();
        bl.add("&7Keep track of the auctions you bid on.");
        bl.add("");
        bl.add(bids == 0 ? "&7You have no active bids." : "&7Active bids: &e" + bids);
        if (toCollectBids > 0) bl.add("&7Items to collect: &a" + toCollectBids);
        bl.add("");
        bl.add("&eClick to view!");
        ItemStack bidIcon = Icons.of(Material.GOLDEN_CARROT, "&eView Bids", bl);
        if (toCollectBids > 0) bidIcon = Icons.amount(Icons.glow(bidIcon), toCollectBids);
        m.set(13, bidIcon, c -> bids(c.player()).open(c.player()));

        if (house.cosmeticOnly(p)) {
            m.set(15, Icons.of(Material.GOLDEN_HORSE_ARMOR, "&cCreate Auction", List.of(
                    "&7Ironman profiles may only BUY",
                    "&7cosmetics on the Auction House.",
                    "",
                    "&cLocked")));
        } else if (mine.isEmpty()) {
            m.set(15, Icons.of(Material.GOLDEN_HORSE_ARMOR, "&aCreate Auction", List.of(
                    "&7Set your own items on auction for other",
                    "&7players to purchase.",
                    "",
                    "&7Slots: &e0&7/&e" + house.maxSlots(p),
                    "",
                    "&eClick to become rich!")), c -> create(c.player()).open(c.player()));
        } else {
            List<String> ml = new ArrayList<>();
            ml.add("&7You own &e" + mine.size() + " &7auction" + (mine.size() == 1 ? "" : "s") + ".");
            ml.add("&7Running: &e" + running);
            if (toCollectMine > 0) ml.add("&7Ready to collect: &a" + toCollectMine);
            ml.add("&7Slots: &e" + house.slotsUsed(prof) + "&7/&e" + house.maxSlots(p));
            ml.add("");
            ml.add("&eClick to manage!");
            ItemStack mi = Icons.of(Material.GOLDEN_HORSE_ARMOR, "&aManage Auctions", ml);
            if (toCollectMine > 0) mi = Icons.amount(Icons.glow(mi), toCollectMine);
            m.set(15, mi, c -> manage(c.player()).open(c.player()));
        }

        if (vaultMenus != null) m.set(22, vaultMenus.entryIcon(p), c -> vaultMenus.main(c.player()).open(c.player()));

        m.set(29, Icons.of(Material.NAME_TAG, "&aPrice Check", List.of(
                "&7See what an item from your inventory",
                "&7is worth: lowest Buy It Now, recent",
                "&7sales on this server, the market",
                "&7reference and its crafting cost.",
                "",
                "&eClick to open!")), c -> priceCheck(c.player(), null).open(c.player()));
        m.set(30, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.set(32, Icons.of(Material.WRITABLE_BOOK, "&aAuction Stats", List.of(
                "&7Your lifetime numbers as a buyer and",
                "&7as a seller.",
                "",
                "&eClick to view!")), c -> stats(c.player()).open(c.player()));
        m.set(33, Icons.of(Material.REDSTONE_TORCH, "&aAuction Settings", List.of(
                "&7Choose which Auction House messages",
                "&7you receive in chat.",
                "",
                "&eClick to open!")), c -> settings(c.player()).open(c.player()));
    }

    private int activeCount() {
        int n = 0;
        long now = System.currentTimeMillis();
        for (Listing l : house.store.all()) if (l.active() && l.endsAt > now) n++;
        return n;
    }

    private List<String> howItWorks() {
        AuctionRules.Settings s = house.rules();
        List<String> l = new ArrayList<>();
        l.add("&7&lLISTING");
        l.add("&7Auction fee: &6" + pct(s.auctionFee) + " &7of the starting bid");
        l.add("&7BIN fee: &6" + pct(s.binFeeLow) + "&7, &6" + pct(s.binFeeMid) + " &7from 10M,");
        l.add("&6" + pct(s.binFeeHigh) + " &7from 100M");
        l.add("&7Duration fee: &61h &e" + AuctionRules.fmt(AuctionRules.durationFee(s, 60)) + "&7, &624h &e"
                + AuctionRules.fmt(AuctionRules.durationFee(s, 1440)) + "&7, &648h &e" + AuctionRules.fmt(AuctionRules.durationFee(s, 2880)));
        l.add("");
        l.add("&7&lBIDDING");
        l.add("&7A new bid must beat the top bid by &e" + pct(s.minIncrement) + "&7.");
        l.add("&7Bids in the last &e" + AuctionRules.timeLeft(s.antiSnipeSeconds * 1000) + " &7reset the clock to it.");
        l.add("&7Outbid coins come straight back.");
        l.add("&7A new BIN can be bought after &e" + s.binGraceSeconds + "s&7.");
        l.add("");
        l.add("&7&lCOLLECTING");
        l.add("&7Sales over &6" + AuctionRules.shortNum(s.claimTaxThreshold) + " &7pay &6" + pct(s.claimTax * s.claimTaxMultiplier)
                + " &7tax, never");
        l.add("&7below &6" + AuctionRules.shortNum(s.claimTaxThreshold) + "&7.");
        if (s.claimTaxMultiplier > 1) l.add("&cThe mayor has raised the claim tax x" + AuctionRules.fmt(s.claimTaxMultiplier) + "!");
        String remote = house.dials.remoteRequirement.get();
        if (!house.dials.anywhere.get() && remote != null && !remote.isBlank()) {
            String desc;
            try { desc = Sky.requirements().describe(remote); } catch (Throwable t) { desc = remote; }
            l.add("");
            l.add("&7&lACCESS");
            l.add("&7Use &a/ah &7anywhere while you have");
            l.add(desc + "&7.");
        }
        return l;
    }

    private static String pct(double f) {
        double v = f * 100;
        return (Math.abs(v - Math.rint(v)) < 1e-9 ? String.valueOf((long) Math.rint(v)) : String.format(java.util.Locale.US, "%.1f", v)) + "%";
    }

    // =====================================================================================================
    // Browser
    // =====================================================================================================

    BrowseQuery query(Player p) {
        return queries.computeIfAbsent(p.getUniqueId(), k -> {
            BrowseQuery q = new BrowseQuery();
            AuctionData d = house.data(p);
            AhTab t = AhTab.parse(d.tab);
            if (t != null) q.tab = t;
            try { q.sort = BrowseQuery.Sort.valueOf(d.sort); } catch (Exception ignored) { }
            try { q.binFilter = BrowseQuery.BinFilter.valueOf(d.binFilter); } catch (Exception ignored) { }
            q.rarity = d.rarity;
            return q;
        });
    }

    private void remember(Player p, BrowseQuery q) {
        AuctionData d = house.data(p);
        d.tab = q.tab.name();
        d.sort = q.sort.name();
        d.binFilter = q.binFilter.name();
        d.rarity = q.rarity;
    }

    Menu browse(Player p) {
        BrowseQuery q = query(p);
        String title = q.sellerName != null ? "Auctions by " + q.sellerName
                : q.searching() ? "Auctions: \"" + clip(q.search, 18) + "\"" : "Auctions Browser";
        Menu m = new Menu(title, 6);
        m.drawer(menu -> drawBrowse(p, menu));
        m.refreshEvery(20);
        return m;
    }

    private static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    private void drawBrowse(Player p, Menu m) {
        BrowseQuery q = query(p);
        long now = System.currentTimeMillis();
        q.hideBeforeEndMs = Math.max(0, house.dials.browseHideSeconds.get()) * 1000L;
        List<Listing> results = q.apply(house.store.all(), now);
        boolean cosmeticOnly = house.cosmeticOnly(p);
        if (cosmeticOnly) results.removeIf(l -> !AuctionHouse.isCosmetic(l.itemId));
        int pages = Math.max(1, (results.size() + RESULT_SLOTS.length - 1) / RESULT_SLOTS.length);
        q.page = Math.max(0, Math.min(q.page, pages - 1));

        Material glass = (q.searching() || q.sellerName != null) ? Material.GRAY_STAINED_GLASS_PANE
                : mat(q.tab.glass, Material.BLACK_STAINED_GLASS_PANE);
        m.clear();
        for (int i = 0; i < 54; i++) m.set(i, Icons.glass(glass, " "));

        for (int i = 0; i < AhTab.values().length; i++) {
            AhTab t = AhTab.values()[i];
            boolean sel = q.sellerName == null && !q.searching() && q.tab == t;
            List<String> lore = new ArrayList<>(Text.wrap("&7" + t.blurb, 32));
            lore.add("");
            lore.add(sel ? "&aCurrently browsing!" : "&eClick to view items!");
            ItemStack icon = Icons.of(mat(t.icon, Material.STICK), t.color + t.label, lore);
            if (sel) icon = Icons.glow(icon);
            m.set(TAB_SLOTS[i], icon, c -> {
                BrowseQuery qq = query(c.player());
                qq.tab = t; qq.search = null; qq.sellerName = null; qq.page = 0;
                remember(c.player(), qq);
                browse(c.player()).open(c.player());
            });
        }

        int start = q.page * RESULT_SLOTS.length;
        for (int i = 0; i < RESULT_SLOTS.length; i++) {
            int idx = start + i;
            if (idx >= results.size()) { m.set(RESULT_SLOTS[i], null); continue; }
            Listing l = results.get(idx);
            m.set(RESULT_SLOTS[i], listingIcon(p, l, "&eClick to inspect!"), c -> view(c.player(), l.id, () -> browse(c.player())).open(c.player()));
        }
        if (results.isEmpty()) {
            m.set(22, Icons.of(Material.BARRIER, "&cNo items found!", List.of(
                    q.searching() ? "&7Nothing matches &e\"" + q.search + "\"&7." : "&7Nothing is listed here right now.",
                    q.rarity != null || q.binFilter != BrowseQuery.BinFilter.ALL ? "&7Try clearing your filters." : "&7Check back soon!")));
        }

        if (q.page > 0) m.set(46, Icons.of(Material.ARROW, "&aPrevious Page", "&ePage " + q.page), c -> {
            query(c.player()).page--; m.redraw();
        });
        if (q.page < pages - 1) m.set(53, Icons.of(Material.ARROW, "&aNext Page", "&ePage " + (q.page + 2)), c -> {
            query(c.player()).page++; m.redraw();
        });

        List<String> sl = new ArrayList<>();
        sl.add("&7Find items by name, enchant,");
        sl.add("&7reforge or anything in their lore.");
        sl.add("");
        if (q.searching()) { sl.add("&7Searching: &e" + q.search); sl.add(""); }
        if (q.sellerName != null) { sl.add("&7Showing: &b" + q.sellerName + "&7's auctions"); sl.add(""); }
        sl.add("&eClick to search!");
        if (q.searching() || q.sellerName != null) sl.add("&bRight-click to clear!");
        m.set(47, Icons.of(Material.OAK_SIGN, "&aSearch", sl), c -> {
            BrowseQuery qq = query(c.player());
            if (c.right() && (qq.searching() || qq.sellerName != null)) {
                qq.search = null; qq.sellerName = null; qq.page = 0;
                browse(c.player()).open(c.player());
                return;
            }
            Sky.menus().input(c.player(), "Search auctions", s -> {
                BrowseQuery q2 = query(c.player());
                if (s != null) {
                    String t = s.trim();
                    if (t.length() > 40) t = t.substring(0, 40);
                    q2.search = t.isEmpty() ? null : t;
                    q2.sellerName = null;
                    q2.page = 0;
                }
                browse(c.player()).open(c.player());
            });
        });
        m.set(48, Icons.back("Auction House"), c -> main(c.player()).open(c.player()));
        m.closeButton();

        List<String> so = new ArrayList<>();
        for (BrowseQuery.Sort s : BrowseQuery.Sort.values()) so.add((s == q.sort ? "&b▶ " : "&7") + s.label);
        so.add("");
        so.add("&bRight-click to go backwards!");
        so.add("&eClick to switch sort!");
        m.set(50, Icons.of(Material.HOPPER, "&aSort", so), c -> {
            BrowseQuery qq = query(c.player());
            qq.sort = c.right() ? qq.sort.prev() : qq.sort.next();
            qq.page = 0;
            remember(c.player(), qq);
            m.redraw();
        });

        List<String> ro = new ArrayList<>();
        for (String r : BrowseQuery.RARITIES) {
            String label = r == null ? "No filter" : Rarity.parse(r).code() + Rarity.parse(r).display();
            boolean cur = java.util.Objects.equals(r, q.rarity);
            ro.add((cur ? "&b▶ " : "&7") + (r == null ? "No filter" : (cur ? label : Text.strip(label))));
        }
        ro.add("");
        ro.add("&bRight-click to go backwards!");
        ro.add("&eClick to switch filter!");
        m.set(51, Icons.of(Material.ENDER_EYE, "&aItem Tier", ro), c -> {
            BrowseQuery qq = query(c.player());
            qq.rarity = qq.nextRarity(c.right() ? -1 : 1);
            qq.page = 0;
            remember(c.player(), qq);
            m.redraw();
        });

        List<String> bo = new ArrayList<>();
        for (BrowseQuery.BinFilter f : BrowseQuery.BinFilter.values()) bo.add((f == q.binFilter ? "&b▶ " : "&7") + f.label);
        bo.add("");
        bo.add("&bRight-click to go backwards!");
        bo.add("&eClick to switch filter!");
        m.set(52, Icons.of(Material.POWERED_RAIL, "&aBIN Filter", bo), c -> {
            BrowseQuery qq = query(c.player());
            qq.binFilter = c.right() ? qq.binFilter.prev() : qq.binFilter.next();
            qq.page = 0;
            remember(c.player(), qq);
            m.redraw();
        });

        List<String> rl = new ArrayList<>();
        rl.add("&7Showing &e" + Text.commasInt(results.size()) + " &7listing" + (results.size() == 1 ? "" : "s") + ".");
        rl.add("&7Page &e" + (q.page + 1) + "&7/&e" + pages);
        if (cosmeticOnly) { rl.add(""); rl.add("&7Ironman: only &dcosmetics &7are shown."); }
        m.set(4, Icons.of(Material.PAPER, "&aResults", rl));
    }

    /** Open the browser on a search (the /ahs command). */
    void openSearch(Player p, String search) {
        BrowseQuery q = query(p);
        q.search = search == null || search.isBlank() ? null : (search.length() > 40 ? search.substring(0, 40) : search);
        q.sellerName = null;
        q.page = 0;
        browse(p).open(p);
    }

    /** Open the browser on one seller's listings (the /ah &lt;player&gt; command). */
    void openSeller(Player p, String sellerName) {
        BrowseQuery q = query(p);
        q.sellerName = sellerName;
        q.search = null;
        q.page = 0;
        browse(p).open(p);
    }

    // =====================================================================================================
    // Listing icon
    // =====================================================================================================

    ItemStack display(Listing l) {
        if (l.display == null) {
            ItemStack real = AuctionHouse.decodeQuiet(l);
            l.display = real == null ? Icons.of(Material.BARRIER, "&cUnreadable item", "&7Auction #" + l.shortId()) : ItemCodec.display(real);
        }
        return l.display;
    }

    List<String> statusLines(Player viewer, Listing l) {
        long now = System.currentTimeMillis();
        String prof = Sky.profiles().profileId(viewer);
        List<String> out = new ArrayList<>();
        out.add("&8&m                        ");
        out.add("&7Seller: " + (l.npc ? "&d[Agent] " + l.sellerName : "&b" + l.sellerName));
        if (l.bin) {
            out.add("&7Buy it now: " + coins(l.startingBid));
            if (l.amount > 1) out.add("&8(" + AuctionRules.fmt(l.unitPrice()) + " each)");
        } else if (l.bidCount == 0) {
            out.add("&7Starting bid: " + coins(l.startingBid));
        } else {
            out.add("&7Bids: &a" + l.bidCount + " bid" + (l.bidCount == 1 ? "" : "s"));
            out.add("");
            out.add("&7Top bid: " + coins(l.topBid));
            out.add("&7Bidder: " + (l.topBidder == null ? "&d" : "&b") + l.topBidderName);
        }
        out.add("");
        switch (l.state) {
            case ACTIVE -> {
                if (l.endsAt <= now) out.add("&7Status: &cEnding...");
                else out.add("&7Ends in: &e" + AuctionRules.timeLeft(l.endsAt - now));
                if (l.bin && now < l.buyableAt) out.add("&7Can be bought in &e" + AuctionRules.timeLeft(l.buyableAt - now));
                if (!l.bin && prof.equals(l.topBidderProfile)) out.add("&aYou have the top bid!");
                else if (!l.bin && l.hasBidFrom(prof)) out.add("&cYou have been outbid!");
            }
            case SOLD -> {
                out.add("&7Status: &aSold!");
                if (l.isSellerSide(prof) && !l.sellerClaimed) out.add("&aCoins ready to collect!");
                if (l.isBuyerSide(prof) && !l.buyerClaimed) out.add("&aYou won this item! Ready to collect!");
            }
            case EXPIRED -> {
                out.add("&7Status: &cExpired!");
                if (l.isSellerSide(prof) && !l.sellerClaimed) out.add("&eNobody bought it. Collect it back.");
                else if (l.hasBidFrom(prof)) out.add("&7Your bid was returned.");
            }
            case CANCELLED -> {
                out.add("&7Status: &cCancelled");
                if (l.isSellerSide(prof) && !l.sellerClaimed) out.add("&eCollect your item back.");
            }
        }
        return out;
    }

    ItemStack listingIcon(Player viewer, Listing l, String hint) {
        List<String> extra = statusLines(viewer, l);
        extra.add("");
        extra.add(hint);
        return Icons.withLore(display(l), extra);
    }

    // =====================================================================================================
    // Auction view
    // =====================================================================================================

    Menu view(Player p, String listingId, Supplier<Menu> back) {
        return view(p, listingId, house.store.get(listingId), back);
    }

    /**
     * Re-open the view on a listing this code already holds. Used after every action taken FROM the view
     * (buy, bid, cancel, collect): the action may settle the listing and evict it from the store in the
     * same click, and the view must then show the finished receipt rather than "Auction not found".
     */
    Menu view(Player p, Listing held, Supplier<Menu> back) {
        return view(p, held.id, held, back);
    }

    private Menu view(Player p, String listingId, Listing held, Supplier<Menu> back) {
        String title = held == null ? "Auction View" : (held.bin ? "BIN Auction View" : "Auction View");
        Menu m = new Menu(title, 6);
        m.drawer(menu -> drawView(p, menu, listingId, held, back));
        m.refreshEvery(20);
        return m;
    }

    private void drawView(Player p, Menu m, String listingId, Listing held, Supplier<Menu> back) {
        m.chrome();
        m.set(48, Icons.back(back == null ? "Auction House" : "the previous page"),
                c -> (back == null ? main(c.player()) : back.get()).open(c.player()));
        Listing l = Listing.forView(house.store.get(listingId), held);
        if (l == null) {
            m.set(13, Icons.of(Material.BARRIER, "&cAuction not found", List.of(
                    "&7This auction has ended and everything",
                    "&7in it has been collected.")));
            return;
        }
        long now = System.currentTimeMillis();
        String prof = Sky.profiles().profileId(p);
        m.set(13, listingIcon(p, l, "&8Auction #" + l.shortId()));
        boolean mine = l.isSellerSide(prof);
        boolean claim = house.claimable(p, l);
        int primary = l.bin ? 31 : 29;
        Listing.Outcome outcome = l.outcomeFor(prof);

        if (outcome != Listing.Outcome.NONE) {
            // settled from this player's side: a receipt, not an error
            List<String> rl = new ArrayList<>();
            String title;
            switch (outcome) {
                case BOUGHT -> {
                    title = "&aPurchase Complete";
                    rl.add("&7You bought this for " + coins(l.topBid) + "&7.");
                    rl.add("&7It is in your inventory.");
                }
                case WON -> {
                    title = "&aAuction Won";
                    rl.add("&7You won this with a bid of " + coins(l.topBid) + "&7.");
                    rl.add("&7It is in your inventory.");
                }
                case SOLD_COLLECTED -> {
                    title = "&aAuction Complete";
                    rl.add("&7Sold to &b" + (l.topBidderName == null ? "a buyer" : l.topBidderName) + " &7for " + coins(l.topBid) + "&7.");
                    rl.add("&7Your coins have been collected.");
                }
                default -> {
                    title = "&aItem Collected";
                    rl.add("&7This auction " + (l.state == Listing.State.CANCELLED ? "was cancelled" : "did not sell") + ".");
                    rl.add("&7The item is back in your inventory.");
                }
            }
            m.set(primary, Icons.of(Material.EMERALD_BLOCK, title, rl));
        } else if (claim) {
            List<String> cl = new ArrayList<>();
            if (l.isSellerSide(prof) && l.state == Listing.State.SOLD) {
                double payout = AuctionRules.claimPayout(house.rules(), l.topBid);
                cl.add("&7Sold for: " + coins(l.topBid));
                if (payout < l.topBid) cl.add("&7Claim tax: &6" + AuctionRules.fmt(l.topBid - payout));
                cl.add("&7You receive: " + coins(payout));
            } else {
                cl.add("&7Collect " + l.itemName + (l.amount > 1 ? " &8x" + l.amount : ""));
            }
            cl.add("");
            cl.add("&eClick to collect!");
            m.set(primary, Icons.glow(Icons.of(Material.GOLD_BLOCK, "&aCollect Auction", cl)), c -> {
                if (house.claim(c.player(), l)) Msg.sound(c.player(), Sound.ENTITY_ITEM_PICKUP, 1f);
                m.redraw();
            });
        } else if (!l.active() || l.endsAt <= now) {
            m.set(primary, Icons.of(Material.BARRIER, "&cThis auction has ended", List.of(
                    l.state == Listing.State.SOLD ? "&7Sold for " + coins(l.topBid) + "&7." : "&7It did not sell.")));
        } else if (mine) {
            boolean can = l.bin || l.bidCount == 0;
            List<String> cl = new ArrayList<>();
            if (can) {
                cl.add("&7Take your item off the Auction House.");
                cl.add("&7The listing fee is &cnot &7refunded.");
                cl.add("");
                cl.add("&eClick to cancel!");
            } else {
                cl.add("&7Auctions with bids cannot be");
                cl.add("&7cancelled.");
            }
            m.set(primary, Icons.of(can ? Material.RED_TERRACOTTA : Material.GRAY_DYE, can ? "&cCancel Auction" : "&cCannot Cancel", cl), can ? c ->
                    Sky.menus().confirm(c.player(), "Cancel Auction?", List.of("&7Cancel your auction for " + l.itemName + "&7?",
                            "&7The listing fee is not refunded."), () -> {
                        house.cancel(c.player(), l);
                        view(c.player(), l, back).open(c.player());
                    }, () -> view(c.player(), l, back).open(c.player())) : null);
        } else if (l.bin) {
            double purse = Sky.economy().purse(p);
            List<String> bl = new ArrayList<>();
            bl.add("&7Price: " + coins(l.startingBid));
            bl.add("&7Your purse: &6" + AuctionRules.fmt(purse));
            bl.add("");
            if (now < l.buyableAt) bl.add("&cCan be bought in " + AuctionRules.timeLeft(l.buyableAt - now) + "!");
            else if (purse < l.startingBid) bl.add("&cYou cannot afford this!");
            else bl.add("&eClick to purchase!");
            m.set(31, Icons.of(Material.GOLD_NUGGET, "&6Buy Item Right Now", bl), c -> confirmBuy(c.player(), l, back));
        } else {
            double min = AuctionRules.minNextBid(house.rules(), l.startingBid, l.topBid);
            double bid = Math.max(min, customBids.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(l.id, 0.0));
            boolean top = prof.equals(l.topBidderProfile);
            List<String> bl = new ArrayList<>();
            if (top) {
                bl.add("&aYou already have the top bid of");
                bl.add(coins(l.topBid) + "&a.");
                m.set(29, Icons.of(Material.GOLD_NUGGET, "&aTop Bid", bl));
            } else {
                double purse = Sky.economy().purse(p);
                bl.add("&7New bid: " + coins(bid));
                if (l.topBid > 0) bl.add("&7Previous bid: " + coins(l.topBid));
                bl.add("");
                bl.add("&7Coins leave your purse now and come");
                bl.add("&7back if someone outbids you.");
                bl.add("");
                bl.add(purse < bid ? "&cYou cannot afford this bid!" : "&eClick to bid!");
                m.set(29, Icons.of(Material.GOLD_NUGGET, "&6Submit Bid", bl), c -> confirmBid(c.player(), l, bid, back));
                m.set(33, Icons.of(Material.GOLD_INGOT, "&6Bid Amount: &e" + AuctionRules.shortNum(bid), List.of(
                        "&7You need to bid at least " + coins(min) + "&7.",
                        "",
                        "&7Bid more to put the item out of reach.",
                        "",
                        "&eClick to set a custom amount!")), c -> Sky.menus().inputNumber(c.player(), "Your bid (min " + AuctionRules.shortNum(min) + ")", n -> {
                    if (n != null && n > 0) {
                        customBids.computeIfAbsent(c.player().getUniqueId(), k -> new HashMap<>()).put(l.id, Math.ceil(n));
                        if (n < min) Msg.error(c.player(), "Your bid must be at least " + AuctionRules.fmt(min) + " coins!");
                    }
                    view(c.player(), l, back).open(c.player());
                }));
            }
        }

        // BIN: 33 = price guide. Auction: 31 = bid history, 33 = bid amount when you can bid, else the guide.
        if (!l.bin) m.set(31, bidHistoryIcon(l));
        ItemStack at33 = m.get(33);
        if (at33 == null || at33.getType() == Material.BLACK_STAINED_GLASS_PANE) m.set(33, guideIcon(l.itemId, false));
        if (!l.npc && l.sellerName != null) {
            m.set(50, Icons.playerHead(l.seller, "&a" + l.sellerName + "&a's Auctions", List.of(
                    "&7See everything this player has listed.", "", "&eClick to view!")), c -> openSeller(c.player(), l.sellerName));
        }
    }

    private ItemStack bidHistoryIcon(Listing l) {
        List<String> lore = new ArrayList<>();
        if (l.bids.isEmpty()) {
            lore.add("&7No bids have been placed yet.");
            lore.add("&7Starting bid: " + coins(l.startingBid));
        } else {
            lore.add("&7Total bids: &a" + l.bids.size());
            lore.add("");
            int shown = 0;
            for (int i = l.bids.size() - 1; i >= 0 && shown < 10; i--, shown++) {
                Listing.Bid b = l.bids.get(i);
                lore.add("&8&m          ");
                lore.add("&7Bid: " + coins(b.amount()));
                lore.add("&7By: " + (b.npc() ? "&d" : "&b") + b.name());
                lore.add("&8" + AuctionRules.timeLeft(System.currentTimeMillis() - b.time()) + " ago");
            }
        }
        return Icons.of(Material.FILLED_MAP, "&fBid History", lore);
    }

    /** The genre's "Confirm Bid" screen: what you bid, what it replaces, what your purse will hold after. */
    private void confirmBid(Player p, Listing l, double bid, Supplier<Menu> back) {
        long now = System.currentTimeMillis();
        if (!l.active() || l.endsAt <= now) { Msg.error(p, "This auction has ended!"); return; }
        double min = AuctionRules.minNextBid(house.rules(), l.startingBid, l.topBid);
        if (bid < min) {
            Msg.error(p, "Someone bid first - the minimum is now " + AuctionRules.fmt(min) + " coins.");
            view(p, l, back).open(p);
            return;
        }
        double purse = Sky.economy().purse(p);
        if (purse < bid) { Msg.error(p, "You cannot afford this bid!"); return; }
        List<String> desc = new ArrayList<>();
        desc.add("&7Bidding on: " + l.itemName + (l.amount > 1 ? " &8x" + l.amount : ""));
        desc.add("&7Your bid: " + coins(bid));
        if (l.topBid > 0) desc.add("&7Current top bid: " + coins(l.topBid));
        desc.add("&7Purse after: &6" + AuctionRules.fmt(purse - bid));
        desc.add("");
        desc.add("&7If you are outbid, the coins come back.");
        Sky.menus().confirm(p, "Confirm Bid", desc, () -> {
            if (house.bid(p, l, bid)) {
                Map<String, Double> cb = customBids.get(p.getUniqueId());
                if (cb != null) cb.remove(l.id);
            }
            view(p, l, back).open(p);
        }, () -> view(p, l, back).open(p));
    }

    private void confirmBuy(Player p, Listing l, Supplier<Menu> back) {
        long now = System.currentTimeMillis();
        if (!l.active() || l.endsAt <= now) { Msg.error(p, "This auction has ended!"); return; }
        if (now < l.buyableAt) { Msg.error(p, "You can buy this in " + AuctionRules.timeLeft(l.buyableAt - now) + "."); return; }
        double purse = Sky.economy().purse(p);
        if (purse < l.startingBid) { Msg.error(p, "You don't have enough coins to buy this!"); return; }
        List<String> desc = new ArrayList<>();
        desc.add("&7Purchasing: " + l.itemName + (l.amount > 1 ? " &8x" + l.amount : ""));
        desc.add("&7Cost: " + coins(l.startingBid));
        desc.add("&7Purse after: &6" + AuctionRules.fmt(purse - l.startingBid));
        desc.add("&7Seller: " + (l.npc ? "&d[Agent] " : "&b") + l.sellerName);
        Sky.menus().confirm(p, "Confirm Purchase", desc, () -> {
            house.buy(p, l);
            view(p, l, back).open(p);
        }, () -> view(p, l, back).open(p));
    }

    // =====================================================================================================
    // Price guide
    // =====================================================================================================

    ItemStack guideIcon(String itemId, boolean clickable) {
        double lb = itemId == null ? 0 : house.lowestBin(itemId);
        PriceGuide.Guide g = house.guide.guide(itemId, lb);
        List<String> l = new ArrayList<>();
        l.add("&7What this item tends to go for:");
        l.add("");
        l.add("&7Lowest BIN: " + (g.lowestBin() > 0 ? coins(g.lowestBin()) : "&8none listed"));
        l.add("&7Recent sales here: " + (g.serverSales() > 0 ? coins(g.serverMedian()) + " &8(median of " + g.serverSales() + ")" : "&8none yet"));
        l.add("&7Market reference: " + (g.reference() > 0 ? coins(g.reference()) : "&8unknown"));
        l.add("&7Crafting cost: " + (g.crafted() > 0 ? coins(g.crafted()) : "&8not craftable"));
        l.add("");
        if (g.suggested() > 0) {
            l.add("&7Suggested price: " + coins(g.suggested()));
            l.add("&8(from " + g.source() + ", per item)");
        } else {
            l.add("&8No price data for this item yet.");
        }
        if (clickable && g.suggested() > 0) { l.add(""); l.add("&eClick to use the suggested price!"); }
        return Icons.of(Material.BOOK, "&aPrice Guide", l);
    }

    Menu priceCheck(Player p, ItemStack shown) {
        Menu m = new Menu("Price Check", 4);
        m.chrome();
        m.set(4, Icons.of(Material.NAME_TAG, "&aPrice Check", List.of(
                "&7Click any item in your inventory to see",
                "&7what it is worth. Nothing is moved.")));
        if (shown == null) {
            m.set(13, Icons.of(Material.STONE_BUTTON, "&eClick an item in your inventory!"));
        } else {
            String id = Sky.items().idOf(shown);
            m.set(13, ItemCodec.display(shown));
            m.set(22, id == null ? Icons.of(Material.BARRIER, "&cNot a game item") : guideIcon(id, false));
            String why = AuctionHouse.auctionable(shown);
            if (why != null) m.set(24, Icons.of(Material.RED_DYE, "&cCannot be auctioned", "&7" + why));
        }
        m.back("Auction House", () -> main(p));
        m.onPlayerInventoryClick((c, live) -> priceCheck(c.player(), live.clone()).open(c.player()));
        return m;
    }

    // =====================================================================================================
    // Create auction
    // =====================================================================================================

    Menu create(Player p) {
        AuctionData d = house.data(p);
        Menu m = new Menu(d.createBin ? "Create BIN Auction" : "Create Auction", 6);
        m.drawer(menu -> drawCreate(p, menu));
        m.onPlayerInventoryClick((c, live) -> {
            // read what we need BEFORE the stack leaves the inventory (live is the inventory's own stack)
            String id = Sky.items().idOf(live);
            int amount = live.getAmount();
            String why = house.putInSlot(c.player(), c.slot(), live);
            if (why != null) { Msg.error(c.player(), why); return; }
            // a fresh item starts at its suggested price (the player can still type their own)
            double unit = id == null ? 0 : house.guide.guide(id, house.lowestBin(id)).suggested();
            if (unit > 0) house.data(c.player()).createPrice = suggestedListPrice(c.player(), unit, amount);
            m.redraw();
        });
        return m;
    }

    /** A suggested unit price times the stack, held under the COMMON cap for the terms currently chosen. */
    private double suggestedListPrice(Player p, double unit, int amount) {
        AuctionData d = house.data(p);
        long minutes = d.createMinutes <= 0 ? 360 : d.createMinutes;
        double price = Math.max(1, Math.round(unit * amount));
        double cap = house.priceCap(house.pendingItem(p), d.createBin, minutes);
        return Math.min(price, Math.floor(cap));
    }

    private void drawCreate(Player p, Menu m) {
        m.chrome();
        AuctionData d = house.data(p);
        AuctionRules.Settings s = house.rules();
        ItemStack item = house.pendingItem(p);
        String prof = Sky.profiles().profileId(p);
        boolean bin = d.createBin;

        m.set(4, Icons.of(Material.PAPER, "&aYour Auction Slots", List.of(
                "&7Used: &e" + house.slotsUsed(prof) + "&7/&e" + house.maxSlots(p),
                "",
                "&7Co-op members add &e" + s.maxListingsPerMember + " &7slots each,",
                "&7up to &e" + s.maxListingsCap + "&7.")));

        if (item == null) {
            m.set(13, Icons.of(Material.STONE_BUTTON, "&eClick an item in your inventory!", List.of(
                    "&7Selects it for auction.",
                    "",
                    "&7The item waits safely in this slot",
                    "&7until you list it or take it back.")));
        } else {
            m.set(13, Icons.withLore(ItemCodec.display(item), List.of("", "&eClick to pick it back up!")), c -> {
                String why = house.takeFromSlot(c.player());
                if (why != null) Msg.error(c.player(), why);
                m.redraw();
            });
        }

        double price = d.createPrice;
        long minutes = d.createMinutes <= 0 ? 360 : d.createMinutes;
        double listFee = AuctionRules.listingFee(s, bin, price);
        double durFee = AuctionRules.durationFee(s, minutes);
        double total = AuctionRules.totalFee(s, bin, price, minutes);
        String why = price <= 0 ? "Set a price first!" : house.checkCreate(p, item, bin, price, minutes);

        List<String> cl = new ArrayList<>();
        cl.add("&7Item: " + (item == null ? "&cnone" : ItemCodec.name(item, Sky.items().idOf(item)) + (item.getAmount() > 1 ? " &8x" + item.getAmount() : "")));
        cl.add("&7" + (bin ? "Price" : "Starting bid") + ": " + (price > 0 ? coins(price) : "&cnot set"));
        cl.add("&7Duration: &e" + AuctionRules.durationWords(minutes));
        cl.add("");
        cl.add("&7" + (bin ? "BIN" : "Auction") + " fee: &6" + AuctionRules.fmt(listFee));
        cl.add("&7Duration fee: &6" + AuctionRules.fmt(durFee));
        cl.add("&7Total fees: &6" + AuctionRules.fmt(total));
        if (price > 0) {
            // what the seller actually keeps: a listing that loses money should say so before it is paid for
            double net = AuctionRules.netIfSold(s, bin, price, minutes);
            cl.add("&7You keep if sold" + (bin ? "" : " at the start") + ": " + (net > 0 ? "&6" + AuctionRules.fmt(net)
                    : "&c" + AuctionRules.fmt(net) + " (a loss)"));
        }
        cl.add("");
        if (why == null) cl.add("&eClick to submit!"); else cl.add("&c" + why);
        m.set(29, Icons.of(why == null ? Material.GREEN_TERRACOTTA : Material.RED_TERRACOTTA,
                why == null ? (bin ? "&aCreate BIN Auction" : "&aCreate Auction") : "&cCreate " + (bin ? "BIN Auction" : "Auction"), cl), c -> {
            String w2 = d.createPrice <= 0 ? "Set a price first!" : house.checkCreate(c.player(), house.pendingItem(c.player()), bin, d.createPrice, minutes);
            if (w2 != null) { Msg.error(c.player(), w2); return; }
            ItemStack it = house.pendingItem(c.player());
            List<String> desc = new ArrayList<>();
            desc.add("&7Auctioning: " + ItemCodec.name(it, Sky.items().idOf(it)) + (it.getAmount() > 1 ? " &8x" + it.getAmount() : ""));
            desc.add("&7" + (bin ? "Price" : "Starting bid") + ": " + coins(d.createPrice));
            desc.add("&7Duration: &e" + AuctionRules.durationWords(minutes));
            desc.add("&7Fees: " + coins(total));
            Sky.menus().confirm(c.player(), bin ? "Confirm BIN Auction" : "Confirm Auction", desc, () -> {
                Listing made = house.create(c.player(), bin, d.createPrice, minutes);
                if (made != null) view(c.player(), made.id, () -> manage(c.player())).open(c.player());
                else create(c.player()).open(c.player());
            }, () -> create(c.player()).open(c.player()));
        });

        List<String> pl = new ArrayList<>();
        pl.add(bin ? "&7The price at which you want to sell" : "&7The minimum price bidders must offer");
        pl.add(bin ? "&7this item." : "&7for this item.");
        pl.add("");
        pl.add("&7Current: " + (price > 0 ? coins(price) : "&cnot set"));
        double cap = house.priceCap(item, bin, minutes);
        if (Double.isFinite(cap)) {
            pl.add("&7COMMON cap: &6" + AuctionRules.fmt(cap) + " &8(" + AuctionRules.durationWords(minutes) + ")");
            pl.add("&8" + AuctionRules.fmt(s.commonCapMultiplier) + "x its NPC value, plus the fees.");
        }
        pl.add("");
        pl.add("&eClick to edit!");
        m.set(31, Icons.of(Material.GOLD_INGOT, bin ? "&fItem Price" : "&fStarting Bid", pl), c ->
                Sky.menus().inputNumber(c.player(), bin ? "Item price" : "Starting bid", n -> {
                    if (n != null && n > 0) house.data(c.player()).createPrice = Math.floor(n * 10) / 10.0;
                    create(c.player()).open(c.player());
                }));

        m.set(33, Icons.of(Material.CLOCK, "&fDuration", List.of(
                "&7How long the item stays up for sale.",
                "",
                "&7Current: &e" + AuctionRules.durationWords(minutes),
                "&7Fee: &6" + AuctionRules.fmt(durFee),
                "",
                "&eClick to edit!")), c -> duration(c.player()).open(c.player()));

        if (item != null) {
            String id = Sky.items().idOf(item);
            m.set(40, guideIcon(id, true), c -> {
                PriceGuide.Guide g = house.guide.guide(id, house.lowestBin(id));
                ItemStack it = house.pendingItem(c.player());
                if (g.suggested() > 0 && it != null) {
                    house.data(c.player()).createPrice = suggestedListPrice(c.player(), g.suggested(), it.getAmount());
                    m.redraw();
                }
            });
        }

        m.set(48, Icons.back("Auction House"), c -> main(c.player()).open(c.player()));
        m.set(50, bin
                ? Icons.of(Material.GOLD_INGOT, "&aSwitch to Auction", List.of(
                        "&7Let players bid on your item. The",
                        "&7highest bid when the timer ends wins.",
                        "",
                        "&7Fee: &6" + pct(s.auctionFee) + " &7of the starting bid.",
                        "",
                        "&eClick to switch!"))
                : Icons.of(Material.GOLD_BLOCK, "&aSwitch to BIN", List.of(
                        "&7Sell at a fixed price: the first",
                        "&7player to pay takes it.",
                        "",
                        "&7Fee: &6" + pct(s.binFeeLow) + "&7-&6" + pct(s.binFeeHigh) + " &7of the price.",
                        "",
                        "&eClick to switch!")), c -> {
            AuctionData dd = house.data(c.player());
            dd.createBin = !dd.createBin;
            create(c.player()).open(c.player());
        });
    }

    Menu duration(Player p) {
        Menu m = new Menu("Auction Duration", 3);
        m.chrome();
        AuctionData d = house.data(p);
        AuctionRules.Settings s = house.rules();
        Material[] mats = {Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
                Material.LIME_TERRACOTTA, Material.GREEN_TERRACOTTA};
        int[] slots = {10, 11, 12, 13, 14};
        for (int i = 0; i < AuctionRules.PRESET_HOURS.length; i++) {
            long minutes = AuctionRules.PRESET_HOURS[i] * 60L;
            boolean cur = d.createMinutes == minutes;
            ItemStack icon = Icons.of(mats[i], "&a" + AuctionRules.durationWords(minutes), List.of(
                    "&7Fee: &6" + AuctionRules.fmt(AuctionRules.durationFee(s, minutes)) + " coins",
                    "",
                    cur ? "&aSelected!" : "&eClick to pick!"));
            if (cur) icon = Icons.glow(icon);
            m.set(slots[i], icon, c -> {
                house.data(c.player()).createMinutes = minutes;
                create(c.player()).open(c.player());
            });
        }
        boolean custom = true;
        for (int h : AuctionRules.PRESET_HOURS) if (d.createMinutes == h * 60L) custom = false;
        List<String> cl = new ArrayList<>();
        cl.add("&7Type a length: &e3h&7, &e90m&7, &e2d&7, &e1d12h&7.");
        cl.add("&7From &e" + s.minDurationMinutes + " minutes &7to &e" + (s.maxDurationHours / 24) + " days&7.");
        cl.add("&7Longest fee: &6" + AuctionRules.fmt(AuctionRules.durationFee(s, s.maxDurationHours * 60)));
        if (custom) { cl.add(""); cl.add("&aSelected: " + AuctionRules.durationWords(d.createMinutes)); }
        cl.add("");
        cl.add("&eClick to set!");
        m.set(16, Icons.of(Material.OAK_SIGN, "&aCustom Duration", cl), c -> Sky.menus().input(c.player(), "Duration (e.g. 3h, 2d)", txt -> {
            long mins = AuctionRules.parseDuration(txt);
            if (txt != null) {
                String why = mins < 0 ? "That is not a duration. Try 3h, 90m or 2d." : AuctionRules.checkDuration(house.rules(), mins);
                if (why != null) Msg.error(c.player(), why);
                else house.data(c.player()).createMinutes = mins;
            }
            create(c.player()).open(c.player());
        }));
        m.back("Create Auction", () -> create(p));
        return m;
    }

    // =====================================================================================================
    // Manage auctions / View bids
    // =====================================================================================================

    Menu manage(Player p) { return manage(p, 0); }

    Menu manage(Player p, int page) {
        List<Listing> mine = house.mine(p);
        String prof = Sky.profiles().profileId(p);
        return Sky.menus().paged("Manage Auctions", mine,
                l -> listingIcon(p, l, house.claimable(p, l) ? "&eClick to collect!" : "&eClick to inspect!"),
                pc -> {
                    Player pl = pc.click().player();
                    Listing l = pc.entry();
                    if (house.claimable(pl, l) && house.claim(pl, l)) { manage(pl, pc.page()).open(pl); return; }
                    view(pl, l.id, () -> manage(pl, pc.page())).open(pl);
                },
                menu -> {
                    menu.set(48, Icons.back("Auction House"), c -> main(c.player()).open(c.player()));
                    menu.set(4, Icons.of(Material.PAPER, "&aYour Auctions", List.of(
                            "&7Slots used: &e" + house.slotsUsed(prof) + "&7/&e" + house.maxSlots(p),
                            "&7Listings shown stay here until you",
                            "&7collect their coins or items.")));
                    menu.set(50, Icons.of(Material.GOLDEN_HORSE_ARMOR, "&aCreate Auction", List.of(
                            "&7Put another item up for sale.", "", "&eClick to create!")), c -> create(c.player()).open(c.player()));
                    int claimables = 0;
                    for (Listing l : mine) if (house.claimable(p, l)) claimables++;
                    if (claimables > 0) {
                        final int n = claimables;
                        menu.set(51, Icons.glow(Icons.of(Material.CAULDRON, "&aClaim All", List.of(
                                "&7Collect &a" + n + " &7finished auction" + (n == 1 ? "" : "s") + " at once.", "", "&eClick to claim!"))), c -> {
                            house.claimAll(c.player());
                            manage(c.player()).open(c.player());
                        });
                    }
                    if (mine.isEmpty()) menu.set(22, Icons.of(Material.BARRIER, "&cNo auctions", List.of(
                            "&7You have nothing listed.", "&7Use &aCreate Auction &7to sell something!")));
                }, page);
    }

    Menu bids(Player p) { return bids(p, 0); }

    Menu bids(Player p, int page) {
        List<Listing> mine = house.myBids(p);
        return Sky.menus().paged("Your Bids", mine,
                l -> listingIcon(p, l, house.claimable(p, l) ? "&eClick to collect!" : "&eClick to inspect!"),
                pc -> {
                    Player pl = pc.click().player();
                    Listing l = pc.entry();
                    if (house.claimable(pl, l) && house.claim(pl, l)) { bids(pl, pc.page()).open(pl); return; }
                    view(pl, l.id, () -> bids(pl, pc.page())).open(pl);
                },
                menu -> {
                    menu.set(48, Icons.back("Auction House"), c -> main(c.player()).open(c.player()));
                    int claimables = 0;
                    for (Listing l : mine) if (house.claimable(p, l)) claimables++;
                    if (claimables > 0) {
                        final int n = claimables;
                        menu.set(51, Icons.glow(Icons.of(Material.CAULDRON, "&aClaim All", List.of(
                                "&7Collect &a" + n + " &7won item" + (n == 1 ? "" : "s") + " at once.", "", "&eClick to claim!"))), c -> {
                            house.claimAll(c.player());
                            bids(c.player()).open(c.player());
                        });
                    }
                    if (mine.isEmpty()) menu.set(22, Icons.of(Material.BARRIER, "&cNo bids", List.of(
                            "&7You are not bidding on anything.", "&7Browse the Auction House to find something!")));
                }, page);
    }

    // =====================================================================================================
    // Stats / settings
    // =====================================================================================================

    Menu stats(Player p) {
        AuctionData d = house.data(p);
        Menu m = new Menu("Auction Stats", 4);
        m.chrome();
        m.set(11, Icons.of(Material.GOLD_INGOT, "&6Seller", List.of(
                "&7Auctions created: &e" + Text.commasInt(d.auctionsCreated),
                "&7Auctions sold: &a" + Text.commasInt(d.auctionsSold),
                "&7Auctions expired: &c" + Text.commasInt(d.auctionsExpired),
                "",
                "&7Coins earned: " + coins(d.coinsEarned),
                "&7Highest sale: " + coins(d.highestSale),
                "&7Fees paid: &6" + AuctionRules.fmt(d.feesPaid),
                "&7Claim tax paid: &6" + AuctionRules.fmt(d.taxPaid))));
        m.set(15, Icons.of(Material.GOLD_NUGGET, "&eBuyer", List.of(
                "&7Items bought outright: &e" + Text.commasInt(d.itemsBought),
                "&7Auctions won: &a" + Text.commasInt(d.auctionsWon),
                "&7Bids placed: &e" + Text.commasInt(d.bidsPlaced),
                "",
                "&7Coins spent on BIN: " + coins(d.coinsSpent),
                "&7Highest bid: " + coins(d.highestBid),
                "",
                "&7Vault bids placed: &e" + Text.commasInt(d.vaultBids),
                "&7Vault lots won: &a" + Text.commasInt(d.vaultWon))));
        m.set(13, Icons.of(Material.EMERALD, "&aThe Floor", List.of(
                "&7Live listings: &e" + Text.commasInt(activeCount()),
                "&7Auction Agents keep the floor stocked",
                "&7and buy fairly priced items, so the",
                "&7market works even when it is quiet.")));
        m.back("Auction House", () -> main(p));
        return m;
    }

    Menu settings(Player p) {
        Menu m = new Menu("Auction Settings", 3);
        m.drawer(menu -> {
            menu.chrome();
            AuctionData d = house.data(p);
            menu.set(11, toggle("&aBid Notifications", d.notifyBids, List.of(
                    "&7Tell me when someone bids on my",
                    "&7auction or outbids me.")), c -> { house.data(c.player()).notifyBids = !house.data(c.player()).notifyBids; menu.redraw(); });
            menu.set(15, toggle("&aSale Notifications", d.notifySales, List.of(
                    "&7Tell me when my items sell, my",
                    "&7auctions end, or I win one.")), c -> { house.data(c.player()).notifySales = !house.data(c.player()).notifySales; menu.redraw(); });
            menu.back("Auction House", () -> main(p));
        });
        return m;
    }

    private static ItemStack toggle(String name, boolean on, List<String> lore) {
        List<String> l = new ArrayList<>(lore);
        l.add("");
        l.add(on ? "&aEnabled" : "&cDisabled");
        l.add("&eClick to toggle!");
        return Icons.of(on ? Material.LIME_DYE : Material.GRAY_DYE, name, l);
    }
}
