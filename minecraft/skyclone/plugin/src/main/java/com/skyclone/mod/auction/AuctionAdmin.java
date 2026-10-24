package com.skyclone.mod.auction;

import com.skyclone.api.Sky;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code /sb auction <action> ...} - everything the integrator needs to drive the Auction House over
 * RCON. Every action takes player NAMES (never "the player who typed it") and prints synchronously.
 */
final class AuctionAdmin {

    private final AuctionHouse house;
    private final AuctionMenus menus;
    private final NpcMarket npc;
    private final VaultAuction vault;
    private final VaultMenus vaultMenus;

    AuctionAdmin(AuctionHouse house, AuctionMenus menus, NpcMarket npc, VaultAuction vault, VaultMenus vaultMenus) {
        this.house = house;
        this.menus = menus;
        this.npc = npc;
        this.vault = vault;
        this.vaultMenus = vaultMenus;
    }

    static final List<String> ACTIONS = List.of("help", "stats", "list", "info", "restock", "buyers", "purge-npc", "create",
            "bid", "buy", "end", "cancel", "claim", "open", "price", "audit", "sweep", "slot", "vault");

    void register() {
        Sky.commands().admin("auction", "<" + String.join("|", ACTIONS) + "> ...",
                "Auction House admin: inspect, seed, end, cancel, bid/buy/claim as a player, audit",
                this::run, (s, a) -> {
                    List<String> out = new ArrayList<>();
                    if (a.length == 1) { for (String x : ACTIONS) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x); }
                    else if (a.length == 2 && List.of("create", "bid", "buy", "claim", "open", "slot").contains(a[0])) {
                        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                    }
                    return out;
                });
    }

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    private void run(CommandSender s, String[] a) {
        String act = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        try {
            switch (act) {
                case "stats" -> stats(s);
                case "list" -> list(s, a);
                case "info" -> info(s, a);
                case "restock" -> {
                    int n = a.length > 1 ? Integer.parseInt(a[1]) : house.dials.npcListings.get();
                    npc.invalidate();
                    int added = npc.restock(n, a.length > 2 && a[2].equalsIgnoreCase("spread"));
                    say(s, "&aAgents listed &e" + added + " &aitems (candidates: " + npc.candidates().size() + ").");
                }
                case "buyers" -> {
                    int[] r = npc.buyerPass(a.length > 1 && a[1].equalsIgnoreCase("force"));
                    say(s, "&aAgent buyer pass: bought &e" + r[0] + "&a, bid on &e" + r[1] + "&a.");
                }
                case "purge-npc" -> say(s, "&aRemoved &e" + npc.purge() + " &aAgent listings.");
                case "create" -> create(s, a);
                case "bid" -> {
                    Player p = player(s, a, 1); if (p == null) return;
                    Listing l = listing(s, a, 2); if (l == null) return;
                    double amt = a.length > 3 ? parse(a[3]) : AuctionRules.minNextBid(house.rules(), l.startingBid, l.topBid);
                    say(s, house.bid(p, l, amt) ? "&aBid placed." : "&cBid refused (see the player's chat).");
                }
                case "buy" -> {
                    Player p = player(s, a, 1); if (p == null) return;
                    Listing l = listing(s, a, 2); if (l == null) return;
                    say(s, house.buy(p, l) ? "&aBought." : "&cPurchase refused (see the player's chat).");
                }
                case "end" -> {
                    Listing l = listing(s, a, 1); if (l == null) return;
                    if (!l.active()) { say(s, "&cNot active."); return; }
                    l.endsAt = System.currentTimeMillis();
                    house.end(l, l.endsAt);
                    house.store.evictSettled();
                    say(s, "&aEnded &e" + l.shortId() + " &a-> " + l.state);
                }
                case "cancel" -> {
                    Listing l = listing(s, a, 1); if (l == null) return;
                    house.adminCancel(l, "admin " + s.getName());
                    house.store.evictSettled();
                    say(s, "&aCancelled &e" + l.shortId());
                }
                case "claim" -> {
                    Player p = player(s, a, 1); if (p == null) return;
                    say(s, "&aClaimed &e" + house.claimAll(p) + " &afor " + p.getName());
                }
                case "open" -> open(s, a);
                case "price" -> price(s, a);
                case "audit" -> audit(s);
                case "sweep" -> { house.sweep(); say(s, "&aSwept."); }
                case "slot" -> slot(s, a);
                case "vault" -> vault(s, a);
                default -> help(s);
            }
        } catch (NumberFormatException e) {
            say(s, "&cBad number: " + e.getMessage());
        }
    }

    private void help(CommandSender s) {
        say(s, "&6/sb auction &7actions:");
        say(s, " &estats &7| &elist [n] [npc|player|ended] &7| &einfo <id>");
        say(s, " &erestock [n] [spread] &7| &ebuyers [force] &7| &epurge-npc &7| &esweep &7| &eaudit");
        say(s, " &ecreate <player> <itemId> <price> [bin|auction] [minutes] [amount] &7(no fee, fresh item)");
        say(s, " &ebid <player> <id> [amount] &7| &ebuy <player> <id> &7| &eclaim <player>");
        say(s, " &eend <id> &7| &ecancel <id> &7| &eopen <player> [main|browse|bids|manage|create|stats|price|vault] &7| &eprice <itemId>");
        say(s, " &eslot <player> [return] &7(the Create Auction slot)");
        say(s, " &evault [status|close|roll|claim <player>|bid <player> <lot> [amount]]");
    }

    private static double parse(String v) {
        String t = v.trim().toLowerCase(Locale.ROOT);
        double mult = 1;
        if (t.endsWith("k")) { mult = 1e3; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("m")) { mult = 1e6; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("b")) { mult = 1e9; t = t.substring(0, t.length() - 1); }
        return Double.parseDouble(t) * mult;
    }

    private Player player(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing player."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null) { say(s, "&cPlayer not online: " + a[i]); return null; }
        if (!Sky.profiles().isReady(p)) { say(s, "&cProfile not ready: " + a[i]); return null; }
        return p;
    }

    private Listing listing(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing auction id."); return null; }
        Listing l = house.store.get(a[i]);
        if (l == null) say(s, "&cNo open auction matches '" + a[i] + "' (use a longer id if several match).");
        return l;
    }

    private void stats(CommandSender s) {
        int playerActive = 0, npcActive = 0, sold = 0, ended = 0;
        double escrow = 0, unclaimedCoins = 0;
        long now = System.currentTimeMillis();
        for (Listing l : house.store.all()) {
            if (l.active()) {
                if (l.npc) npcActive++; else playerActive++;
                if (l.topBidder != null) escrow += l.topBid;
            } else if (l.state == Listing.State.SOLD) {
                sold++;
                if (!l.sellerClaimed) unclaimedCoins += l.topBid;
            } else ended++;
        }
        say(s, "&6Auction House");
        say(s, " &7Active: &e" + playerActive + " &7player, &d" + npcActive + " &7agent");
        say(s, " &7Sold, awaiting collection: &e" + sold + " &7(&6" + AuctionRules.fmt(unclaimedCoins) + " &7coins unclaimed)");
        say(s, " &7Expired/cancelled, awaiting collection: &e" + ended);
        say(s, " &7Coins held in top bids: &6" + AuctionRules.fmt(escrow));
        say(s, " &7Create-slot items waiting: &e" + house.store.pendingCount());
        say(s, " &7Reference prices: &e" + house.guide.refs().size() + " &7| agent candidates: &e" + npc.candidates().size());
        say(s, " &7Quad taxes: " + (house.dials.quadTaxActive() ? "&cON" : "&aoff") + " &7| DB write failures: "
                + (house.store.writeFailures() > 0 ? "&c" : "&a") + house.store.writeFailures());
    }

    private void list(CommandSender s, String[] a) {
        int n = 15;
        String filter = null;
        for (int i = 1; i < a.length; i++) {
            if (a[i].matches("\\d+")) n = Integer.parseInt(a[i]); else filter = a[i].toLowerCase(Locale.ROOT);
        }
        long now = System.currentTimeMillis();
        int shown = 0;
        for (Listing l : house.store.all()) {
            if ("npc".equals(filter) && !l.npc) continue;
            if ("player".equals(filter) && l.npc) continue;
            if ("ended".equals(filter) && l.active()) continue;
            if (filter == null && !l.active()) continue;
            if (shown++ >= n) break;
            say(s, " &8" + l.shortId() + " " + (l.bin ? "&aBIN" : "&6AUC") + " &f" + Text.strip(l.itemName)
                    + (l.amount > 1 ? " x" + l.amount : "") + " &6" + AuctionRules.shortNum(l.price())
                    + " &7" + (l.npc ? "[Agent] " : "") + l.sellerName + " &7" + l.state
                    + (l.active() ? " &e" + AuctionRules.timeLeft(l.endsAt - now) : ""));
        }
        if (shown == 0) say(s, "&7(no auctions match)");
    }

    private void info(CommandSender s, String[] a) {
        Listing l = listing(s, a, 1);
        if (l == null) return;
        long now = System.currentTimeMillis();
        say(s, "&6Auction &e" + l.id);
        say(s, " &7Item: " + l.itemName + " &7(" + l.itemId + (l.amount > 1 ? " x" + l.amount : "") + ", " + l.rarity + ", " + l.tab.label
                + (l.itemUuid != null ? ", uuid " + l.itemUuid : "") + ")");
        say(s, " &7Seller: " + (l.npc ? "[Agent] " : "") + l.sellerName + " &8" + l.seller + " / " + l.sellerProfile);
        say(s, " &7" + (l.bin ? "BIN" : "Auction") + " &7start " + AuctionRules.fmt(l.startingBid) + " &7top " + AuctionRules.fmt(l.topBid)
                + " by " + l.topBidderName + " &7bids " + l.bidCount + " &7fees " + AuctionRules.fmt(l.fees));
        say(s, " &7State " + l.state + " &7ends " + (l.endsAt > now ? "in " + AuctionRules.timeLeft(l.endsAt - now) : "ago " + AuctionRules.timeLeft(now - l.endsAt))
                + " &7sellerClaimed " + l.sellerClaimed + " buyerClaimed " + l.buyerClaimed);
    }

    private void create(CommandSender s, String[] a) {
        Player p = player(s, a, 1);
        if (p == null) return;
        if (a.length < 4) { say(s, "&cUsage: create <player> <itemId> <price> [bin|auction] [minutes] [amount]"); return; }
        String id = a[2].toUpperCase(Locale.ROOT);
        if (!Sky.items().exists(id)) { say(s, "&cUnknown item " + id); return; }
        double price = parse(a[3]);
        boolean bin = a.length < 5 || !a[4].equalsIgnoreCase("auction");
        long minutes = a.length > 5 ? Long.parseLong(a[5]) : 360;
        int amount = a.length > 6 ? Math.max(1, Integer.parseInt(a[6])) : 1;
        ItemStack it = Sky.items().create(id, amount);
        String why = AuctionHouse.auctionable(it);
        if (why != null) { say(s, "&c" + why); return; }
        long now = System.currentTimeMillis();
        Listing l = AuctionHouse.build(it, id, bin, price, now, Math.max(1, minutes));
        l.seller = p.getUniqueId();
        l.sellerProfile = Sky.profiles().profileId(p);
        l.sellerName = p.getName();
        l.buyableAt = bin ? now + house.rules().binGraceSeconds * 1000L : now;
        house.store.add(l);
        house.store.save(l);
        house.store.log("admin_create", l.id, s.getName(), price, id);
        house.invalidateLowestBin();
        say(s, "&aListed &e" + l.shortId() + " &a(" + id + " x" + amount + ", " + (bin ? "BIN" : "auction") + " " + AuctionRules.fmt(price)
                + ", " + minutes + "m) for " + p.getName());
    }

    private void open(CommandSender s, String[] a) {
        Player p = player(s, a, 1);
        if (p == null) return;
        String which = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "main";
        switch (which) {
            case "browse" -> menus.browse(p).open(p);
            case "bids" -> menus.bids(p).open(p);
            case "manage" -> menus.manage(p).open(p);
            case "create" -> menus.create(p).open(p);
            case "stats" -> menus.stats(p).open(p);
            case "price" -> menus.priceCheck(p, null).open(p);
            case "vault" -> vaultMenus.main(p).open(p);
            default -> menus.main(p).open(p);
        }
        say(s, "&aOpened " + which + " for " + p.getName());
    }

    /** Show (or hand back) what sits in a player's Create Auction slot. */
    private void slot(CommandSender s, String[] a) {
        Player p = player(s, a, 1);
        if (p == null) return;
        ItemStack it = house.pendingItem(p);
        if (it == null) { say(s, "&7" + p.getName() + "'s Create Auction slot is empty."); return; }
        String id = Sky.items().idOf(it);
        String uuid = AuctionHouse.uuidOf(it);
        say(s, "&7" + p.getName() + "'s slot: " + ItemCodec.name(it, id) + " &7x" + it.getAmount() + " &8(" + id
                + (uuid != null ? ", uuid " + uuid : "") + ")");
        if (a.length > 2 && a[2].equalsIgnoreCase("return")) {
            String why = house.takeFromSlot(p);
            say(s, why == null ? "&aReturned to " + p.getName() + "." : "&c" + why);
        }
    }

    private void vault(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "close" -> say(s, "&aClosed &e" + vault.adminClose(false) + " &avault lots; the bids holding copies won.");
            case "roll" -> {
                int n = vault.adminClose(true);
                say(s, "&aClosed &e" + n + " &alots and opened &e" + vault.current().size() + " &anew ones for year "
                        + VaultAuction.currentYear() + ".");
            }
            case "claim" -> {
                Player p = player(s, a, 2);
                if (p == null) return;
                say(s, "&aCollected &e" + vault.claimAll(p) + " &avault wins for " + p.getName());
            }
            case "bid" -> {
                Player p = player(s, a, 2);
                if (p == null) return;
                if (a.length < 4) { say(s, "&cUsage: vault bid <player> <lot> [amount]"); return; }
                VaultAuction.Lot l = vault.get(a[3]);
                if (l == null) { say(s, "&cNo lot matches " + a[3]); return; }
                double amt = a.length > 4 ? parse(a[4]) : vault.minFor(p, l);
                say(s, vault.bid(p, l, amt) ? "&aVault bid placed." : "&cVault bid refused (see the player's chat).");
            }
            default -> {
                int year = VaultAuction.currentYear();
                say(s, "&5Vault Auction &7year &e" + year + " &7(" + (house.dials.vaultEnabled.get() ? "&aon" : "&coff")
                        + "&7), closes in &e" + AuctionRules.timeLeft(vault.millisLeft()) + "&7, volume x" + house.dials.vaultVolume());
                int shown = 0;
                for (VaultAuction.Lot l : vault.all()) {
                    List<VaultAuction.VBid> r = VaultAuction.ranked(l);
                    int won = 0;
                    for (VaultAuction.VBid b : l.bids) if (VaultAuction.WON.equals(b.state)) won++;
                    say(s, " &8" + l.id + " &7" + l.track + " &f" + Text.strip(vault.itemName(l)) + " &7stock " + l.stock
                            + " min " + AuctionRules.shortNum(l.minBid) + " &7bids " + r.size()
                            + (r.isEmpty() ? "" : " top " + AuctionRules.shortNum(r.get(0).amount))
                            + (l.closed ? " &cclosed" + (won > 0 ? " (" + won + " unclaimed)" : "") : " &aopen"));
                    shown++;
                }
                if (shown == 0) say(s, "&7(no lots loaded)");
            }
        }
    }

    private void price(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cUsage: price <itemId>"); return; }
        String id = a[1].toUpperCase(Locale.ROOT);
        PriceGuide.Guide g = house.guide.guide(id, house.lowestBin(id));
        say(s, "&6Price guide &e" + id + (Sky.items().exists(id) ? "" : " &c(not a registered item)"));
        say(s, " &7lowest BIN " + AuctionRules.fmt(g.lowestBin()) + " | server median " + AuctionRules.fmt(g.serverMedian()) + " (" + g.serverSales()
                + ") | reference " + AuctionRules.fmt(g.reference()) + " | crafted " + AuctionRules.fmt(g.crafted()));
        say(s, " &7suggested " + AuctionRules.fmt(g.suggested()) + " (" + g.source() + ") | fair value " + AuctionRules.fmt(house.guide.fairValue(id))
                + " | agent value " + AuctionRules.fmt(house.guide.agentValue(id)));
    }

    /** Consistency: unique uuids appear once across listings + pending slots, items decode, states add up. */
    static String auditReport(AuctionHouse house) {
        List<String> problems = new ArrayList<>();
        Map<String, String> seen = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (Listing l : house.store.all()) {
            if (!ids.add(l.id)) problems.add("duplicate id " + l.id);
            if (AuctionHouse.decodeQuiet(l) == null) problems.add("unreadable item in " + l.shortId());
            if (l.state == Listing.State.SOLD && !(l.topBid > 0)) problems.add("sold without a price " + l.shortId());
            if (l.state == Listing.State.ACTIVE && l.sellerClaimed) problems.add("active but seller-claimed " + l.shortId());
            if (!l.npc && (l.seller == null || l.sellerProfile == null)) problems.add("player listing without seller " + l.shortId());
            boolean holdsItem = l.active() || (l.state == Listing.State.SOLD ? !l.buyerClaimed : !l.sellerClaimed);
            if (holdsItem && l.itemUuid != null) {
                String prev = seen.put(l.itemUuid, l.shortId());
                if (prev != null) problems.add("item uuid " + l.itemUuid + " escrowed twice (" + prev + ", " + l.shortId() + ")");
            }
        }
        for (String b64 : house.store.pendingItems()) {
            try {
                String u = AuctionHouse.uuidOf(ItemCodec.decode(b64));
                if (u != null) {
                    String prev = seen.put(u, "create-slot");
                    if (prev != null) problems.add("item uuid " + u + " both in a create slot and in " + prev);
                }
            } catch (Throwable t) {
                problems.add("unreadable create-slot item");
            }
        }
        if (house.store.writeFailures() > 0) problems.add(house.store.writeFailures() + " database write failures since boot");
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    private void audit(CommandSender s) {
        String r = auditReport(house);
        say(s, r == null ? "&aAudit clean: " + house.store.all().size() + " auctions, " + house.store.pendingCount() + " create-slot items." : "&cAudit: " + r);
    }
}
